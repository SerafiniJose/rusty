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
        // hands off-origin links to the external browser), but addJavascriptInterface also injects into
        // child frames — including ha-panel-app ingress add-on iframes. The bridge's only reachable
        // surface is the two discovery callbacks, and both are gated by the repository's monotonic
        // generation guard (stale generations are ignored), so the worst a hostile/compromised ingress
        // add-on could do is force a redundant discovery refresh or a spurious banner — no token access,
        // no code execution. Acceptable for a self-hosted LAN appliance where add-ons are already trusted.
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
                view?.evaluateJavascript(KIOSK_JS, null)
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
        val cacheJson = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null)
        val cacheOrigin = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null)
        val available = HomeAssistantDashboards.availableFrom(
            if (HomeAssistantDashboards.isCacheFresh(origin, cacheOrigin)) cacheJson else null
        )
        currentDashboardPath = HomeAssistantDashboards.resolveActiveDashboard(
            storedOrigin = storedOrigin,
            storedPath = storedPath,
            currentOrigin = origin,
            available = available,
        )

        val targetDashboard = available.find { it.urlPath == currentDashboardPath }
            ?: HomeAssistantDashboards.OVERVIEW
        val targetUrl = HomeAssistantDashboards.urlFor(url, targetDashboard)
        if (targetUrl != lastLoadedUrl) {
            lastLoadedUrl = targetUrl
            webView.loadUrl(targetUrl)
        }
        if (!webView.isInTouchMode) webView.post { webView.requestFocus() }
    }

    private fun showSetup(prefill: String?, error: String?) {
        webView.visibility = View.GONE
        setup.visibility = View.VISIBLE
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
            // Reset repo to Idle so stale Loaded dashboards don't linger in the chip bar and a
            // pending old-origin generation is invalidated before render()/hydrate().
            RustyApp.haRepository(requireContext()).reset()
        }

        lastLoadedUrl = null
        render()
    }

    /** The url_path of the dashboard currently shown (Overview by default). Read by the shell via
     *  [ShellContribution.activeDashboardPath] to mark the active switcher chip. */
    override val activeDashboardPath: String get() = currentDashboardPath

    /** Navigates the WebView to [dashboard]. When the frontend is loaded + authenticated we navigate
     *  in-app via HA's own client-side router ([HomeAssistantNav]) so the live connection and already-
     *  rendered views stay warm (revisiting a dashboard doesn't reload). Otherwise — cold load, login
     *  screen, post-error — we fall back to a full [WebView.loadUrl] for reliability.
     *  Persists the active dashboard (origin-scoped) so it survives fragment switch + recreation. */
    override fun showDashboard(dashboard: HomeAssistantDashboards.HaDashboard) {
        val base = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)) ?: return
        currentDashboardPath = dashboard.urlPath
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
        val spaPath = HomeAssistantUrl.pathWithQuery(target)
        if (spaPath != null && HomeAssistantNav.shouldSpaNavigate(frontendReady, webView.url, trustedOrigin)) {
            webView.evaluateJavascript(HomeAssistantNav.navigateScript(spaPath), null)
        } else {
            webView.loadUrl(target)
        }
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
                  opt('auth/current_user')
                ]).then(function(p){
                  if(window.RustyHaBridge) RustyHaBridge.onDiscovery(gen, JSON.stringify({panels:p[0],dashboards:p[1],user:p[2]}));
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
        }

        @android.webkit.JavascriptInterface
        fun onDiscoveryError(generation: Long, reason: String) {
            repo.fail(generation, HomeAssistantDashboards.friendlyError(reason))
        }
    }

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"

        /** Origin-scoped pref keys for the active dashboard.  Written in [showDashboard]; read in
         *  [render] via [HomeAssistantDashboards.resolveActiveDashboard].  Cleared in [SettingsSheet]
         *  whenever the HA base URL changes to a different origin. */
        const val KEY_ACTIVE_DASHBOARD_ORIGIN = "ha_active_dashboard_origin"
        const val KEY_ACTIVE_DASHBOARD_PATH = "ha_active_dashboard_path"

        // Hides Home Assistant's own sidebar + Lovelace header by inserting idempotent <style> tags
        // into HA's nested shadow roots, so the WebView shows only the dashboard content. HA renders
        // asynchronously, so an apply() runs on a bounded retry loop until both roots are styled (or
        // ~5s elapses). Selectors target current HA; tune here if a future HA renames them.
        /** Set HA's own sidebar preference to "always_hidden" so the frontend renders the dashboard
         *  full-width (no docked 256px sidebar band). Injected at document start so HA reads it during
         *  app init; idempotent and scoped to this WebView's localStorage (does not affect other HA
         *  clients). The value is JSON ("always_hidden" with quotes) to match how HA persists it. */
        private const val DOCK_HIDDEN_JS =
            "try{localStorage.setItem('dockedSidebar',JSON.stringify('always_hidden'));}catch(e){}"

        // Hides Home Assistant's own sidebar + drawer band + the entire Lovelace top app bar by
        // inserting idempotent <style> tags into HA's nested shadow roots, so the WebView shows only
        // dashboard content. The sidebar/drawer styles live in stable roots (home-assistant-main,
        // ha-drawer) and survive in-app navigation; the header style lives inside the active panel's
        // hui-root, which HA RECREATES on every SPA dashboard switch — so a one-shot inject is lost
        // the moment the user taps another dashboard chip. To stay hidden, a MutationObserver on the
        // stable partial-panel-resolver re-applies the header kill whenever the active panel swaps
        // (no timing race against the swap). HA renders asynchronously, so the initial pass also runs
        // on a bounded ~5s retry loop. The active panel wraps hui-root generically — classic
        // dashboards use ha-panel-lovelace, the newer default uses ha-panel-home; both hold hui-root
        // in their shadow root. The header kill collapses the whole bar (--header-height:0 +
        // .header/.toolbar/app-header). Selectors target current HA — verify on-device (CDP) and tune
        // here if a future HA renames them.
        private val KIOSK_JS = """
            (function(){
              // dockedSidebar is already set at document-start by DOCK_HIDDEN_JS (onPageStarted), which
              // is the load-bearing write (it must run before HA reads the pref). No need to repeat it here.
              var HEADER_CSS=':host{--header-height:0px!important;}'+
                '.header{display:none!important;}'+
                '.toolbar{display:none!important;}'+
                'app-header,ha-top-app-bar-fixed{display:none!important;}';
              function sr(el){return el&&el.shadowRoot;}
              function styled(root, id, css){
                if(!root) return false;
                if(root.querySelector('#'+id)) return true;
                var s=document.createElement('style');
                s.id=id; s.textContent=css;
                root.appendChild(s);
                return true;
              }
              function mainRoot(){
                var ha=document.querySelector('home-assistant');
                return sr(ha)&&sr(sr(ha).querySelector('home-assistant-main'));
              }
              function resolver(){ var m=mainRoot(); return m&&m.querySelector('partial-panel-resolver'); }
              function activePanel(){ var r=resolver(); return r&&r.firstElementChild; }
              function huiRoot(){
                var r=resolver(); if(!r) return null;
                var panel=r.firstElementChild;
                var hui=panel&&(panel.tagName.toLowerCase()==='hui-root'?panel
                  :(sr(panel)&&sr(panel).querySelector('hui-root')));
                if(!hui){var lov=r.querySelector('ha-panel-lovelace');
                  hui=lov&&sr(lov)&&sr(lov).querySelector('hui-root');}
                return sr(hui);
              }
              // The top bar lives in different shadow roots per panel type: Lovelace/home dashboards
              // nest it inside hui-root, while ha-panel-app (custom/ingress webapp panels) exposes its
              // own div.header wrapper directly in the panel's shadow. Inject the kill into BOTH so the
              // bar is hidden whichever panel is active (the .header/.toolbar selectors no-op where absent).
              function applyHeader(){
                var a=styled(sr(activePanel()),'rusty-kiosk-header',HEADER_CSS);
                var b=styled(huiRoot(),'rusty-kiosk-header',HEADER_CSS);
                return a||b;
              }
              function applyAll(){
                var m=mainRoot();
                var sidebar=styled(m,'rusty-kiosk-sidebar',
                  'ha-sidebar{display:none!important;}'+
                  'home-assistant-main,ha-drawer{--mdc-drawer-width:0px!important;}'+
                  '.mdc-drawer-app-content{margin-left:0!important;margin-inline-start:0!important;}');
                var drawer=m&&m.querySelector('ha-drawer');
                var drawerDone=styled(sr(drawer),'rusty-kiosk-drawer',
                  '.mdc-drawer-app-content{margin-left:0!important;margin-inline-start:0!important;}');
                // Re-apply the header kill to the CURRENT active panel's hui-root every pass; a fresh
                // panel from a dashboard swap starts unstyled and gets restyled here / by the observer.
                var headerDone=applyHeader();
                installObserver();
                return sidebar&&drawerDone&&headerDone;
              }
              // One observer per page lifetime: when partial-panel-resolver swaps its panel child
              // (dashboard switch), retry styling the new panel until its shadow root mounts. The
              // retry runs ~5s because ingress panels (ha-panel-app) attach their shadow/iframe well
              // after the element is inserted.
              function installObserver(){
                if(window.__rustyKioskObserver) return;
                var r=resolver(); if(!r) return;
                window.__rustyKioskObserver=new MutationObserver(function(){
                  var k=0; var rt=setInterval(function(){ k++; if(applyHeader()||k>50) clearInterval(rt); },100);
                });
                window.__rustyKioskObserver.observe(r,{childList:true});
              }
              // ~20s bounded retry: a cold direct load boots the whole HA frontend, and an ingress
              // panel attaches its shadow seconds after partial-panel-resolver mounts — well past a 5s
              // window. applyHeader/applyAll are idempotent, so re-running each tick is cheap; the
              // observer then maintains the header kill across later dashboard swaps.
              var n=0;
              var t=setInterval(function(){ n++; if(applyAll()||n>80) clearInterval(t); },250);
            })();
        """.trimIndent()
    }
}
