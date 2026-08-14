package dev.rusty.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * The Home Assistant feature's view. Two states in one layout: a URL setup form, and a full-bleed
 * WebView showing the configured dashboard. The WebView is permissive for LAN HA (cleartext +
 * accept self-signed certs, scoped here) and persists cookies/DOM storage so login survives restarts.
 */
class HomeAssistantFragment : Fragment(), InsetAware, FocusRestorable, ShellContribution {

    private lateinit var prefs: SharedPreferences
    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var setup: View
    private lateinit var urlInput: TextInputEditText
    private lateinit var connectButton: MaterialButton
    private lateinit var errorText: TextView
    private var lastLoadedUrl: String? = null
    private var trustedOrigin: String? = null
    private var lastDiscoveryOrigin: String? = null
    // Spec §5.1: throttle suppresses re-discovery only when we already have a RECENT SUCCESSFUL
    // discovery for the current origin. Stamped when the repo delivers HaDiscovery.Loaded.
    private var lastDiscoverySuccessAtMs: Long = 0L
    // Bug #6 fix: HaBridge is constructed in onViewCreated (after the fragment is attached) so it
    // can capture the application Context at a point where the fragment IS attached. The captured
    // application Context outlives the fragment, making onDiscovery/onDiscoveryError safe to call on
    // the WebView's background JavaBridge thread even after the fragment is detached — no requireContext() call
    // from a background thread, no IllegalStateException crash.
    private lateinit var haBridge: HaBridge
    private var currentDashboardPath: String = HomeAssistantDashboards.OVERVIEW_PATH
    // Where HA actually is, as reported by the page (currentDashboardPath is where the APP last sent
    // it). Null while HA is somewhere the chips can't represent, so no chip reads as active.
    private var visibleDashboardPath: String? = HomeAssistantDashboards.OVERVIEW_PATH
    // The HA panel key the app's Overview lands on, learnt from the page rather than guessed: HA
    // redirects its root (which is what Overview loads) to a default panel whose name differs by HA
    // version and which `hass` does not expose. Null until the first Overview landing is observed.
    private var overviewPanel: String? = null
    // True between an app-initiated navigation to Overview and the page reporting where HA settled —
    // that report is what teaches [overviewPanel].
    private var expectingOverviewLanding: Boolean = false
    // True once the HA frontend has reported a successful discovery for the current page (i.e. it is
    // loaded and authenticated). Gates in-app SPA dashboard navigation; reset on every full page load.
    private var frontendReady: Boolean = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home_assistant, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Capture a stable repo reference while attached; HaBridge holds this instead of calling
        // requireContext() later on the JavaBridge thread (Bug #6 fix).
        haBridge = HaBridge(RustyApp.haRepository(requireContext()))
        root = view
        webView = view.findViewById(R.id.haWebView)
        setup = view.findViewById(R.id.haSetup)
        urlInput = view.findViewById(R.id.etHaUrl)
        connectButton = view.findViewById(R.id.btnHaConnect)
        errorText = view.findViewById(R.id.tvHaError)

        val banner = view.findViewById<TextView>(R.id.haBanner)
        val repo = RustyApp.haRepository(requireContext())
        val bannerListener = HomeAssistantDashboardRepository.Listener { state ->
            banner.post {
                if (state is HaDiscovery.Error) {
                    banner.text = state.reason
                    banner.visibility = View.VISIBLE
                } else {
                    banner.visibility = View.GONE
                }
            }
        }
        // Stamp last-success time when the repo delivers a Loaded state for the current origin so
        // the throttle in shouldRunDiscovery is anchored to the last SUCCESSFUL discovery (spec §5.1).
        val successListener = HomeAssistantDashboardRepository.Listener { state ->
            if (state is HaDiscovery.Loaded) {
                lastDiscoverySuccessAtMs = System.currentTimeMillis()
                // Frontend is loaded + authenticated → dashboard switches can now be done in-app
                // (SPA pushState) instead of reloading the whole page.
                frontendReady = true
            }
        }
        repo.addListener(bannerListener)
        repo.addListener(successListener)
        viewLifecycleOwner.lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                repo.removeListener(bannerListener)
                repo.removeListener(successListener)
            }
        })
        banner.setOnClickListener { banner.visibility = View.GONE }

        // Warm the bundled MDI font + codepoint map off the UI thread so the first dashboard-chip
        // render (on the main thread) doesn't pay the one-time ~7.4k-line parse cost as a hitch.
        val warmCtx = requireContext().applicationContext
        Thread {
            MdiFont.typeface(warmCtx)
            MdiFont.glyphFor(warmCtx, "mdi:home")
        }.start()

        configureWebView()
        connectButton.setOnClickListener { onConnect() }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        render()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        // Register the discovery bridge ONCE, before the first load. Android only injects a
        // JavascriptInterface into a page whose load began AFTER the interface was added — registering
        // it in onPageStarted (mid-navigation) was too late for that very page, so the FIRST dashboard
        // load (the persisted active dashboard, opened directly) had no RustyHaBridge. Discovery's
        // onDiscovery/onDiscoveryError callbacks then never reached the app, the repo hit its 10s
        // timeout, and a false "No response from Home Assistant" banner stuck even though HA was
        // connected and rendering. Registering here makes the bridge available on the first page.
        //
        // Exposure note: the main frame is pinned to the trusted HA origin (shouldOverrideUrlLoading
        // hands off-origin links to the external browser), but addJavascriptInterface injects
        // RustyHaBridge into EVERY frame in the WebView — including ha-panel-app ingress add-on
        // iframes and any third-party page embedded via a Lovelace iframe/webpage card — so every
        // method on HaBridge is reachable from a hostile or compromised child frame, not just HA's
        // own top-level script. Per method: onDiscovery/onDiscoveryError are gated by the repo's
        // monotonic generation guard (stale generations are ignored); onBackgroundColor/onTextColor
        // are parsed by parseCssColorToArgb and silently dropped if malformed; onHomeTap takes no
        // arguments and only navigates to a dashboard the app already trusts. onPath carries the most
        // risk — it feeds a page-reported path into lastLoadedUrl, which a later loadUrl() (see
        // onHiddenChanged) can navigate to — but HomeAssistantNav.decidePathReport gates it:
        // HomeAssistantUrl.childUrlOrNull rejects anything that doesn't parse back out as a
        // same-origin absolute path (in particular a leading "@host" or "//host", which would
        // otherwise smuggle a foreign origin past naive base+path concatenation), and HA's auth flow
        // and its single-use OAuth callback are refused outright, before anything is ever assigned. So the worst a hostile child frame can force is a redundant
        // discovery refresh, a wrong tint, or a same-origin navigation/chip state of its own choosing —
        // never an off-origin redirect, token access, or code execution. Acceptable for a self-hosted
        // LAN appliance where add-ons are already trusted.
        webView.addJavascriptInterface(haBridge, "RustyHaBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            // DEFERRED BLOCKER: TLS is still accept-all for LAN self-signed HA. Awaiting a product
            // decision (trust-prompt for self-signed vs. strict block). Do not ship as-is.
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }

            // Keep main-frame navigation inside the configured HA origin; hand off-origin links to the
            // system browser so foreign pages never run inside the bridged WebView.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return false
                if (HomeAssistantUrl.isSameOrigin(target, trustedOrigin)) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Any full page load (incl. login redirects) tears down the live frontend; block SPA
                // navigation until the next successful discovery re-arms it.
                frontendReady = false
                // The bridge is registered once in configureWebView (see note there). Here we only need
                // to set HA's full-width sidebar preference before its app bundle reads it, on the
                // trusted origin. Render the dashboard full-width (no docked-sidebar 256px left band):
                // HA's ha-drawer reserves that band via .app-content's inline-start padding, which can't
                // be overridden by injected CSS in current HA; instead set HA's own sidebar pref to
                // "always_hidden" BEFORE its app bundle reads it, so HA never docks the sidebar.
                if (HomeAssistantUrl.isSameOrigin(url, trustedOrigin)) {
                    view?.evaluateJavascript(DOCK_HIDDEN_JS, null)
                    view?.evaluateJavascript(
                        HomeAssistantNav.selectedThemeJs(
                            prefs.getString(HomeAssistantFeature.KEY_SELECTED_THEME, null),
                            prefs.getString(HomeAssistantFeature.KEY_SELECTED_THEME_MODE, null)),
                        null)
                }
                // Boot the HA frontend signed-in: seed its hassTokens localStorage entry (only when
                // absent — hassTokensJs guards) with the tokens minted by the settings sign-in.
                // Origin-gated twice: HaAuthStore only returns tokens for the SAVED origin, and we
                // only inject when this page IS that origin, so tokens can never leak to a redirect target.
                val savedOrigin = HomeAssistantUrl.origin(
                    HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))
                if (savedOrigin != null && HomeAssistantUrl.isSameOrigin(url, savedOrigin)) {
                    HaAuthStore.tokensFor(prefs, SecretStore.of(requireContext()), savedOrigin)?.let { tokens ->
                        view?.evaluateJavascript(HaAuth.hassTokensJs(savedOrigin, tokens), null)
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    showSetup(prefs.getString(HomeAssistantFeature.KEY_URL, null),
                        "Couldn't reach Home Assistant. Check the address and your network.")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!HomeAssistantUrl.isSameOrigin(url, trustedOrigin)) return
                // A finished load on the HA origin (outside the auth/login flow) means the SPA frontend
                // is mounted, so subsequent dashboard switches can navigate in-app. Discovery success
                // also arms this, but discovery can be slow/flaky on some custom panels — don't make
                // SPA navigation depend on it.
                if (!HomeAssistantNav.isAuthPath(url)) frontendReady = true
                runDiscovery(force = false)
                view?.evaluateJavascript(HomeAssistantNav.kioskJs(), null)
                // Apply the chosen theme to the live frontend. The onPageStarted localStorage seed only
                // paints the first frame: once the WebSocket connects, HA replaces hass.selectedTheme
                // with the account's server-stored theme (and overwrites the seed), so the selection has
                // to be pushed through HA's own settheme event to stick. See applyThemeJs.
                if (!HomeAssistantNav.isAuthPath(url)) {
                    view?.evaluateJavascript(
                        HomeAssistantNav.applyThemeJs(
                            prefs.getString(HomeAssistantFeature.KEY_SELECTED_THEME, null),
                            prefs.getString(HomeAssistantFeature.KEY_SELECTED_THEME_MODE, null)),
                        null)
                }
                // Match the shell to HA's theme: tint the reserved strips (top clock clearance / bottom
                // chrome clearance) to HA's background so they stop reading as black bands, and tint the
                // floating chrome (clock, settings, app-selector) to HA's text colour so it stays legible.
                // The frontend samples both and reports via RustyHaBridge.onBackgroundColor/onTextColor.
                view?.evaluateJavascript(HomeAssistantNav.reportThemeColorsJs(), null)
                // White-screen mitigation: when HA is shown by switching away from a Spotify session
                // that had an active SurfaceView (album art / ambient mesh), the freshly-shown WebView
                // can present a blank/white first frame until something invalidates the view tree — a
                // stray touch "fixes" it. Forcing a relayout + redraw once the page has FINISHED loading
                // replicates that invalidation safely: it runs post-load, so it can't disturb the load.
                view?.let { it.requestLayout(); it.invalidate() }
            }
        }
    }

    /** Chooses setup vs. WebView based on whether a URL is configured. */
    private fun render() {
        val url = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
        if (url == null) {
            showSetup(null, null)
            return
        }
        trustedOrigin = HomeAssistantUrl.origin(url)
        val origin = trustedOrigin!!
        RustyApp.haRepository(requireContext()).hydrate(origin)
        setup.visibility = View.GONE
        webView.visibility = View.VISIBLE

        // Restore the active dashboard that was persisted on last navigation (origin-scoped).
        // resolveActiveDashboard validates that the stored origin matches the current one AND that
        // the stored path still exists in the available list — falls back to Overview otherwise.
        val storedOrigin = prefs.getString(KEY_ACTIVE_DASHBOARD_ORIGIN, null)
        val storedPath = prefs.getString(KEY_ACTIVE_DASHBOARD_PATH, null)
        val available = availableDashboards()
        currentDashboardPath = HomeAssistantDashboards.resolveActiveDashboard(
            storedOrigin = storedOrigin,
            storedPath = storedPath,
            currentOrigin = origin,
            available = available,
        )
        visibleDashboardPath = currentDashboardPath

        val targetDashboard = available.find { it.urlPath == currentDashboardPath }
            ?: HomeAssistantDashboards.OVERVIEW
        val targetUrl = HomeAssistantDashboards.urlFor(url, targetDashboard)
        if (targetUrl != lastLoadedUrl) {
            lastLoadedUrl = targetUrl
            // A cold load of Overview is a load of the HA root, so it teaches which panel that is.
            expectingOverviewLanding = targetDashboard.urlPath == HomeAssistantDashboards.OVERVIEW_PATH
            webView.loadUrl(targetUrl)
        }
        if (!webView.isInTouchMode) webView.post { webView.requestFocus() }
    }

    private fun showSetup(prefill: String?, error: String?) {
        webView.visibility = View.GONE
        setup.visibility = View.VISIBLE
        // Restore the app base background: a prior dashboard may have tinted root to HA's (possibly light)
        // theme colour, which the dark setup form is not styled for.
        root.setBackgroundResource(R.color.bg_base)
        urlInput.setText(prefill ?: prefs.getString(HomeAssistantFeature.KEY_URL, null) ?: "")
        if (urlInput.text.isNullOrBlank()) urlInput.setText(HomeAssistantDashboards.DEFAULT_URL)
        errorText.text = error.orEmpty()
        errorText.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!urlInput.isInTouchMode) urlInput.post { urlInput.requestFocus() }
    }

    private fun onConnect() {
        val normalized = HomeAssistantUrl.normalize(urlInput.text?.toString())
        if (normalized == null) {
            errorText.text = "Enter your Home Assistant address."
            errorText.visibility = View.VISIBLE
            return
        }

        // Bug #3 fix: when the in-fragment setup form submits a different origin, perform the same
        // origin-change invalidation that the Settings URL-save path does (HomeAssistantFeature
        // saveButton handler). This prevents stale origin-scoped state (discovery cache, dashboard
        // selection, active-dashboard keys) from leaking into the new origin, and resets the repo
        // so any pending old-origin generation cannot pass validation under the new origin.
        val newOrigin = HomeAssistantUrl.origin(normalized)
        val oldOrigin = HomeAssistantUrl.origin(
            HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
        )
        val originChanged = newOrigin != oldOrigin && oldOrigin != null

        val edit = prefs.edit().putString(HomeAssistantFeature.KEY_URL, normalized)
        if (originChanged) {
            // Clear origin-gated discovery cache + active-dashboard selection so stale chips from
            // the old server are never shown against the new URL. Mirrors the Settings path exactly.
            HomeAssistantFeature.SERVER_RESET_KEYS.forEach { edit.remove(it) }
        }
        edit.apply()

        if (originChanged) {
            // Pre-sign-in surface: no revoke here (the settings-panel path revokes when it changes
            // the URL). Just drop any stale token for the old origin so it isn't carried forward.
            HaAuthStore.clear(prefs, SecretStore.of(requireContext()))
            // Reset repo to Idle so stale Loaded dashboards don't linger in the chip bar and a
            // pending old-origin generation is invalidated before render()/hydrate().
            RustyApp.haRepository(requireContext()).reset()
        }

        lastLoadedUrl = null
        if (originChanged) {
            overviewPanel = null
            expectingOverviewLanding = false
        }
        render()
    }

    /** The url_path of the dashboard currently shown (Overview by default). Read by the shell via
     *  [ShellContribution.activeDashboardPath] to mark the active switcher chip. */
    override val activeDashboardPath: String? get() = visibleDashboardPath

    /** The dashboards the app knows about for the current origin: the synthetic Overview plus the
     *  discovery cache, ignoring a cache captured against a different HA server. */
    private fun availableDashboards(): List<HomeAssistantDashboards.HaDashboard> {
        val origin = trustedOrigin
        val cacheOrigin = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null)
        val cacheJson = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null)
        return HomeAssistantDashboards.availableFrom(
            if (HomeAssistantDashboards.isCacheFresh(origin, cacheOrigin)) cacheJson else null
        )
    }

    /** Target of the section bar's back control: the dashboard the app last put the user on — the
     *  active chip, Overview by default. Never dead, because there is always an active dashboard.
     *  [showDashboard] relights the chip row itself. */
    private fun goHome() {
        val target = availableDashboards().find { it.urlPath == currentDashboardPath }
            ?: HomeAssistantDashboards.OVERVIEW
        showDashboard(target)
    }

    /** HA navigated itself (a card, a link, its own router). Keep the chip row honest and remember
     *  the real page so a later re-show restores it instead of snapping back to the active chip.
     *  Thin glue over the pure [HomeAssistantNav.decidePathReport], which is where the actual rules
     *  (origin safety, the OAuth return leg, deferred chip resolution) live and are tested. */
    private fun onHaPathChanged(path: String, currentPanel: String) {
        val decision = HomeAssistantNav.decidePathReport(
            reportedPath = path,
            // Blank while hass hasn't populated on a cold load; the decision defers the chips then.
            currentPanel = currentPanel,
            overviewPanel = overviewPanel,
            expectingOverviewLanding = expectingOverviewLanding,
            baseUrl = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)),
            available = availableDashboards(),
            visiblePath = visibleDashboardPath,
        )
        decision.overviewPanelToRemember?.let {
            overviewPanel = it
            expectingOverviewLanding = false
        }
        decision.restoreUrl?.let { lastLoadedUrl = it }
        if (decision.refreshChips) {
            visibleDashboardPath = decision.activePath
            // Not routed through showDashboard (the page moved itself), so refresh explicitly.
            (activity as? ShellHost)?.refreshDashboardChips()
        }
    }

    /** Navigates the WebView to [dashboard]. When the frontend is loaded + authenticated we navigate
     *  in-app via HA's own client-side router ([HomeAssistantNav]) so the live connection and already-
     *  rendered views stay warm (revisiting a dashboard doesn't reload). Otherwise — cold load, login
     *  screen, post-error — we fall back to a full [WebView.loadUrl] for reliability.
     *  Persists the active dashboard (origin-scoped) so it survives fragment switch + recreation.
     *  Refreshes the chip row itself: the optimistic [visibleDashboardPath] write below means the
     *  page's own confirming report sees no change and fires no refresh, so every caller would
     *  otherwise have to remember to do it. */
    override fun showDashboard(dashboard: HomeAssistantDashboards.HaDashboard) {
        val base = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)) ?: return
        currentDashboardPath = dashboard.urlPath
        // Reflect the tap immediately; the page's own report will confirm it a moment later.
        visibleDashboardPath = dashboard.urlPath
        // Persist origin + path together so resolveActiveDashboard can validate them atomically on restore.
        val origin = trustedOrigin
        if (origin != null) {
            prefs.edit()
                .putString(KEY_ACTIVE_DASHBOARD_ORIGIN, origin)
                .putString(KEY_ACTIVE_DASHBOARD_PATH, dashboard.urlPath)
                .apply()
        }
        val target = HomeAssistantDashboards.urlFor(base, dashboard)
        lastLoadedUrl = target
        // Overview IS the HA root, so the panel HA settles on next is Overview's — the report that
        // follows this navigation is the app's only chance to learn it. Any other dashboard names
        // its own panel, so nothing is pending.
        expectingOverviewLanding = dashboard.urlPath == HomeAssistantDashboards.OVERVIEW_PATH
        val spaPath = HomeAssistantUrl.pathWithQuery(target)
        if (spaPath != null && HomeAssistantNav.shouldSpaNavigate(frontendReady, webView.url, trustedOrigin)) {
            webView.evaluateJavascript(HomeAssistantNav.navigateScript(spaPath), null)
        } else {
            webView.loadUrl(target)
        }
        (activity as? ShellHost)?.refreshDashboardChips()
    }

    /** Builds the generation-stamped discovery JS for a given refresh [gen]. */
    private fun discoveryScript(gen: Long): String = """
        (function(gen){
          var n=0;
          var t=setInterval(function(){
            n++;
            if(window.hassConnection){
              clearInterval(t);
              window.hassConnection.then(function(c){
                var conn=c.conn;
                function opt(type){ return conn.sendMessagePromise({type:type}).then(function(r){return r;},function(){return null;}); }
                return Promise.all([
                  conn.sendMessagePromise({type:'get_panels'}),
                  opt('lovelace/dashboards/list'),
                  opt('auth/current_user'),
                  opt('frontend/get_themes')
                ]).then(function(p){
                  if(window.RustyHaBridge) RustyHaBridge.onDiscovery(gen, JSON.stringify({panels:p[0],dashboards:p[1],user:p[2],themes:(p[3]&&p[3].themes)||null}));
                });
              }).catch(function(e){
                if(window.RustyHaBridge) RustyHaBridge.onDiscoveryError(gen, ''+(e&&e.message||(e&&e.error&&e.error.message)||(e&&e.code)||JSON.stringify(e)));
              });
            } else if(n>20){
              clearInterval(t);
              if(window.RustyHaBridge) RustyHaBridge.onDiscoveryError(gen,'frontend-not-ready');
            }
          },250);
        })($gen);
    """.trimIndent()

    /** Begins a tracked refresh. [force]=true (manual Refresh) always fires; otherwise repeats for the
     *  same origin within DISCOVERY_THROTTLE_MS of the last SUCCESSFUL discovery are suppressed so
     *  login redirects / in-page navigation don't thrash the chips + hint. */
    fun runDiscovery(force: Boolean) {
        val origin = trustedOrigin ?: return
        if (webView.visibility != View.VISIBLE) return
        val now = System.currentTimeMillis()
        // Throttle anchored to last SUCCESS (spec §5.1): if we already have a recent successful
        // discovery for this origin, skip. Origin changes are tracked by lastDiscoveryOrigin; the
        // time-window check uses lastDiscoverySuccessAtMs (stamped when the repo delivers Loaded).
        if (!HomeAssistantDashboards.shouldRunDiscovery(
                force, origin, lastDiscoveryOrigin, lastDiscoverySuccessAtMs, now,
                HomeAssistantDashboards.DISCOVERY_THROTTLE_MS)) return
        lastDiscoveryOrigin = origin
        val repo = RustyApp.haRepository(requireContext())
        val gen = repo.beginRefresh(origin)
        webView.evaluateJavascript(discoveryScript(gen), null)
    }

    /** Manual refresh from Settings — always forces a fresh discovery. */
    override fun runDiscovery() { runDiscovery(force = true) }

    /**
     * Reloads the WebView from prefs (called live after the HA URL changes to a new origin in Settings).
     * Resets [lastLoadedUrl] so the new URL is unconditionally loaded, then delegates to [render].
     */
    override fun reloadUrl() {
        lastDiscoveryOrigin = null
        lastLoadedUrl = null
        frontendReady = false
        // Per-server: a different HA may send its root to a different panel.
        overviewPanel = null
        expectingOverviewLanding = false
        render()
    }

    /**
     * Re-homes D-pad focus when this retained fragment is shown again after a feature switch
     * ([FocusRestorable]): the visible WebView (or the URL field while in setup). No-op in touch mode.
     */
    override fun restoreFocus() {
        if (!::webView.isInitialized) return
        val target = if (webView.visibility == View.VISIBLE) webView else urlInput
        if (!target.isInTouchMode) target.post { target.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    /**
     * White-screen fix for switching BACK to a retained HA. While hidden behind another feature the
     * fragment's view goes GONE, and Chromium FREEZES the page (rendering + JS suspended). On re-show
     * the renderer does NOT reliably un-freeze — the WebView stays blank/white until a reload (which is
     * what tapping a dashboard chip did, hence "touch fixes it"). offscreenPreRaster, renderer-priority
     * and visibility toggles were all tried on-device and did NOT thaw it. So when this fragment is
     * shown again, reload the current dashboard so HA always appears fresh instead of blank. (Dashboard
     * chip switches still use warm in-app SPA navigation; rotation still does not reload.) No-op while
     * the setup form is up; onHiddenChanged never fires on first creation, only on re-show.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden || !::webView.isInitialized || setup.visibility == View.VISIBLE) return
        // Re-issue a full loadUrl of the current dashboard (NOT webView.reload()): on a frozen renderer
        // reload() is a no-op, but a fresh loadUrl forces a new navigation that wakes it — exactly what
        // tapping a dashboard chip does (the user's "touch fixes it"). loadUrl(lastLoadedUrl) preserves
        // the dashboard the user left on; fall back to render() only if we never loaded anything.
        frontendReady = false
        val url = lastLoadedUrl
        if (url != null) webView.loadUrl(url) else render()
    }

    override fun onPause() {
        webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroyView() {
        webView.removeJavascriptInterface("RustyHaBridge")
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroyView()
    }

    override fun onInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        // Reserve a top strip for the shell's floating corner clock and a bottom strip for the shell's
        // chrome bar (chips + cluster), so neither overlays the HA dashboard content.
        val clockClearance = resources.getDimensionPixelSize(R.dimen.ha_clock_clearance)
        val chromeClearance = resources.getDimensionPixelSize(R.dimen.ha_chrome_clearance)
        root.setPadding(bars.left, bars.top + clockClearance, bars.right, bars.bottom + chromeClearance)
        // Push haBanner below the status bar + clock strip so it is never hidden under the system UI.
        val banner = root.findViewById<android.widget.TextView>(R.id.haBanner)
        (banner?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { lp ->
            lp.topMargin = bars.top + clockClearance
            banner.layoutParams = lp
        }
    }

    /**
     * Bridge the discovery JS calls into the repository.
     *
     * Bug #6 fix: [repo] is captured at construction time (in onViewCreated, while the fragment IS
     * attached) rather than being looked up via requireContext() inside bridge methods. The WebView's
     * JavaBridge thread calls these on a BACKGROUND thread; if the fragment has been detached
     * by the time that call arrives, requireContext() would throw IllegalStateException. The captured
     * [repo] reference is a process-wide singleton (RustyApp.haRepository) that outlives the fragment,
     * so calling submitResult/fail on it is always safe regardless of fragment lifecycle state.
     */
    private inner class HaBridge(private val repo: HomeAssistantDashboardRepository) {
        @android.webkit.JavascriptInterface
        fun onDiscovery(generation: Long, json: String) {
            repo.submitResult(generation, json)
            // Mirror the discovered account name into prefs: it lives only inside the live
            // HaDiscovery.Loaded state, so surfaces that never open this WebView (the Info page)
            // would otherwise have no way to name the signed-in user after a process restart.
            // Read back from the repo state rather than re-parsing the JSON, so a stale generation —
            // which submitResult drops — can never store another server's identity. A payload with no
            // user object leaves the stored name untouched: a known name is never downgraded to null.
            // No thread hop: like submitResult above, this touches no view and no requireContext() —
            // prefs is a process-wide store and apply() writes off the main thread by design.
            (repo.state as? HaDiscovery.Loaded)?.account?.name?.takeIf { it.isNotBlank() }
                ?.let { HomeAssistantFeature.setAccountName(prefs, it) }
        }

        @android.webkit.JavascriptInterface
        fun onDiscoveryError(generation: Long, reason: String) {
            repo.fail(generation, HomeAssistantDashboards.friendlyError(reason))
        }

        /** HA's own theme background colour, reported by [HomeAssistantNav.reportBackgroundColorJs] so
         *  the reserved top/bottom shell strips can match it instead of the static near-black bg_base.
         *  Called on the WebView's background JavaBridge thread — hop to the UI thread and only touch the
         *  view while the fragment is still attached. */
        @android.webkit.JavascriptInterface
        fun onBackgroundColor(css: String) {
            val argb = HomeAssistantNav.parseCssColorToArgb(css) ?: return
            webView.post { if (isAdded) root.setBackgroundColor(argb) }
        }

        /** HA's own primary text colour — used to tint the shell chrome (clock, settings, app-selector)
         *  that floats over the themed strips so it stays legible. Applied only while HA is foreground;
         *  the shell restores its defaults when another feature takes over. UI-thread + attach-guarded. */
        @android.webkit.JavascriptInterface
        fun onTextColor(css: String) {
            val argb = HomeAssistantNav.parseCssColorToArgb(css) ?: return
            webView.post { if (isAdded) (activity as? ShellHost)?.applyHaChromeColor(argb) }
        }

        /** The section bar's back control was activated. Called on the WebView's background
         *  JavaBridge thread — hop to the UI thread and only act while still attached. */
        @android.webkit.JavascriptInterface
        fun onHomeTap() {
            webView.post { if (isAdded) goHome() }
        }

        /** HA's frontend reported a client-side navigation. Background JavaBridge thread — hop to the
         *  UI thread and only act while still attached. */
        @android.webkit.JavascriptInterface
        fun onPath(path: String, currentPanel: String) {
            webView.post { if (isAdded) onHaPathChanged(path, currentPanel) }
        }
    }

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"

        /** Origin-scoped pref keys for the active dashboard.  Written in [showDashboard]; read in
         *  [render] via [HomeAssistantDashboards.resolveActiveDashboard].  Cleared in [SettingsSheet]
         *  whenever the HA base URL changes to a different origin. */
        const val KEY_ACTIVE_DASHBOARD_ORIGIN = "ha_active_dashboard_origin"
        const val KEY_ACTIVE_DASHBOARD_PATH = "ha_active_dashboard_path"

        /** Set HA's own sidebar preference to "always_hidden" so the frontend renders the dashboard
         *  full-width (no docked 256px sidebar band). Injected at document start so HA reads it during
         *  app init; idempotent and scoped to this WebView's localStorage (does not affect other HA
         *  clients). The value is JSON ("always_hidden" with quotes) to match how HA persists it. */
        private const val DOCK_HIDDEN_JS =
            "try{localStorage.setItem('dockedSidebar',JSON.stringify('always_hidden'));}catch(e){}"

    }
}
