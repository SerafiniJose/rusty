package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Home Assistant feature: default off; configured once a dashboard URL is set. When enabled
 * but unconfigured, [HomeAssistantFragment] shows its own setup screen.
 */
object HomeAssistantFeature : Feature {
    const val KEY_ENABLED = "ha_enabled"
    const val KEY_URL = "ha_url"
    const val KEY_DASHBOARDS_CACHE = "ha_dashboards_cache"
    const val KEY_SELECTED_DASHBOARDS = "ha_selected_dashboards"
    const val KEY_DASHBOARDS_ORIGIN = "ha_dashboards_origin"
    const val KEY_SELECTED_THEME = "ha_selected_theme"
    // Light/dark mode (auto|light|dark) for the selected theme. Intentionally NOT server-reset: it is a
    // display preference that applies to the built-in default theme (present on every server), so it
    // survives a server change while the custom-theme NAME (KEY_SELECTED_THEME) resets.
    const val KEY_SELECTED_THEME_MODE = "ha_selected_theme_mode"

    /** Pref keys wiped whenever the active HA server session is reset — on a server-URL change AND on
     *  sign-out. Excludes [KEY_URL]: the server address is preserved across sign-out. Shared by the
     *  settings URL-save, the in-fragment URL-save, and sign-out so the three paths cannot drift. */
    val SERVER_RESET_KEYS: List<String> = listOf(
        KEY_DASHBOARDS_CACHE,
        KEY_DASHBOARDS_ORIGIN,
        KEY_SELECTED_DASHBOARDS,
        HomeAssistantFragment.KEY_ACTIVE_DASHBOARD_ORIGIN,
        HomeAssistantFragment.KEY_ACTIVE_DASHBOARD_PATH,
        KEY_SELECTED_THEME,
    )

    override val id = FeatureId.HOME_ASSISTANT
    override val title = "Home Assistant"
    override val iconRes = R.drawable.ic_mdi_home_assistant
    override fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    override fun createFragment(): Fragment = HomeAssistantFragment()
    override val settingsTab = SettingsTabKey.HOME_ASSISTANT

    override fun settingsPanel(ctx: SettingsPanelContext): SettingsPanelProvider =
        HomeAssistantSettingsPanel(ctx)
}

/**
 * Feature-owned settings panel for Home Assistant.
 *
 * Owns: URL input + save, sign out, dashboard discovery checklist + refresh.
 * The [HomeAssistantDashboardRepository] listener (Task 15) is registered in [bind] and the
 * returned cleanup lambda removes it — cleanup is guaranteed to fire on BOTH tab-switch AND
 * dialog dismiss because [SettingsSheet] always invokes the current cleanup before swapping panels
 * and on the dismiss listener.
 *
 * Moved verbatim from [SettingsSheet.bindHomeAssistant] + [SettingsSheet.renderDashboardChecklist];
 * no behavior changes.
 */
private class HomeAssistantSettingsPanel(
    private val ctx: SettingsPanelContext,
) : SettingsPanelProvider {

    override val layoutRes = R.layout.settings_panel_home_assistant

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secrets = SecretStore.of(activity)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        val urlInput = panel.findViewById<TextInputEditText>(R.id.etHaSettingsUrl)
        val saveButton = panel.findViewById<MaterialButton>(R.id.btnHaSaveUrl)
        val feedback = panel.findViewById<TextView>(R.id.tvHaFeedback)
        val dashGrid = panel.findViewById<LinearLayout>(R.id.haDashGrid)
        val appsGrid = panel.findViewById<LinearLayout>(R.id.haAppsGrid)
        val appsHint = panel.findViewById<TextView>(R.id.tvHaAppsHint)
        val hint = panel.findViewById<TextView>(R.id.tvHaDashboardsHint)
        val refreshButton = panel.findViewById<MaterialButton>(R.id.btnHaRefresh)
        val refreshSpinner = panel.findViewById<android.widget.ProgressBar>(R.id.haRefreshSpinner)
        val accountName = panel.findViewById<TextView>(R.id.haAccountName)
        val accountSub = panel.findViewById<TextView>(R.id.haAccountSub)
        val accountAvatar = panel.findViewById<TextView>(R.id.haAccountAvatar)
        val accountAction = panel.findViewById<MaterialButton>(R.id.btnHaAccountAction)
        val connectionSection = panel.findViewById<View>(R.id.haConnectionSection)
        val usernameInput = panel.findViewById<TextInputEditText>(R.id.etHaUsername)
        val passwordInput = panel.findViewById<TextInputEditText>(R.id.etHaPassword)
        val mfaLayout = panel.findViewById<View>(R.id.layoutHaMfa)
        val mfaInput = panel.findViewById<TextInputEditText>(R.id.etHaMfaCode)
        val testButton = panel.findViewById<MaterialButton>(R.id.btnHaTestConnection)
        val cleartextWarning = panel.findViewById<TextView>(R.id.tvHaCleartextWarning)
        val themeHint = panel.findViewById<TextView>(R.id.tvHaThemeHint)
        val appearanceControls = panel.findViewById<LinearLayout>(R.id.haAppearanceControls)
        val themeFlowContainer =
            panel.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.haThemeFlowContainer)
        val themeFlow = panel.findViewById<androidx.constraintlayout.helper.widget.Flow>(R.id.haThemeFlow)
        val modeRadios = listOf(
            panel.findViewById<android.widget.RadioButton>(R.id.rbHaModeAuto) to HomeAssistantNav.MODE_AUTO,
            panel.findViewById<android.widget.RadioButton>(R.id.rbHaModeLight) to HomeAssistantNav.MODE_LIGHT,
            panel.findViewById<android.widget.RadioButton>(R.id.rbHaModeDark) to HomeAssistantNav.MODE_DARK,
        )

        fun renderCleartextWarning() {
            cleartextWarning.visibility =
                if (prefs.getString(HomeAssistantFeature.KEY_URL, null)?.startsWith("http://") == true)
                    View.VISIBLE else View.GONE
        }
        renderCleartextWarning()

        // ---- Collapsible sections + signed-in state -----------------------------------------
        val serverSection = CollapsibleSection(
            panel.findViewById(R.id.headHaServer), panel.findViewById(R.id.bodyHaServer),
            "Server", startExpanded = false)
        val dashSection = CollapsibleSection(
            panel.findViewById(R.id.headHaDash), panel.findViewById(R.id.bodyHaDash),
            "Dashboards", startExpanded = false)
        val appsSection = CollapsibleSection(
            panel.findViewById(R.id.headHaApps), panel.findViewById(R.id.bodyHaApps),
            "Apps", startExpanded = false)
        val appearanceSection = CollapsibleSection(
            panel.findViewById(R.id.headHaAppearance), panel.findViewById(R.id.bodyHaAppearance),
            "Appearance", startExpanded = false)

        fun currentOrigin(): String? = HomeAssistantUrl.origin(
            HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))

        // "Signed in" for the Server header = a live discovery session OR a stored refresh token for
        // the current origin. The token half lets the header collapse to a signed-in row the instant
        // credentials succeed, even when the HA feature isn't open yet (so discovery can't have run).
        fun tokenSignedIn(): Boolean =
            currentOrigin()?.let { HaAuthStore.tokensFor(prefs, secrets, it)?.refreshToken != null } == true

        var lastSignedIn: Boolean? = null
        // True between a user tapping Refresh and the next terminal discovery state — gates the
        // spinner + check feedback so ordinary page-load discovery doesn't trigger it.
        var refreshInFlight = false
        // Set when a settings sign-in just succeeded; the next discovery carrying the account name
        // upgrades the (name-less) success toast to name the user. lastAccount caches the most recent
        // known identity so Test connection and refreshes can keep naming them.
        var justSignedIn = false
        var lastAccount: HomeAssistantDashboards.HaAccount? = null

        fun applySignedInUi(signedIn: Boolean, account: HomeAssistantDashboards.HaAccount?) {
            // Attention rule: apply on bind and on sign-in/out TRANSITIONS only — a discovery refresh
            // must never yank a section the user opened or closed by hand.
            if (lastSignedIn == null || signedIn != lastSignedIn) serverSection.expanded = !signedIn
            lastSignedIn = signedIn
            val host = currentOrigin()?.substringAfter("://")
            serverSection.setSummary(HaSummaries.server(signedIn, account?.name, host))
        }

        // Sign-in flow state: set when the server asks for a two-factor code, consumed by the next Save.
        var pendingMfaFlowId: String? = null
        var pendingMfaOrigin: String? = null

        fun onSignedIn(origin: String, tokens: HaAuth.HaTokens) {
            HaAuthStore.save(prefs, secrets, origin, tokens)
            passwordInput.setText("")           // the password is never kept, not even in the field
            mfaInput.setText("")
            mfaLayout.visibility = View.GONE
            pendingMfaFlowId = null; pendingMfaOrigin = null
            justSignedIn = true
            // Token is stored now → collapse the Server header to a signed-in row immediately, without
            // waiting for discovery (which can't run if the HA feature isn't open). Pass no name: a fresh
            // sign-in must never inherit a prior user's identity; discovery fills the real name in later.
            applySignedInUi(true, null)
            showFeedback(feedback, "✓ Signed in — password not stored.", HaFeedbackKind.SUCCESS)
            // Boot the WebView signed-in (Task 10 injects the token on load) + rerun discovery so the
            // account card, chips and card grids fill in.
            activity.currentHomeAssistantFragment()?.reloadUrl()
        }

        fun handleSignInResult(origin: String, result: HaAuthClient.SignIn) {
            saveButton.isEnabled = true; testButton.isEnabled = true
            when (result) {
                is HaAuthClient.SignIn.Success -> onSignedIn(origin, result.tokens)
                is HaAuthClient.SignIn.MfaRequired -> {
                    pendingMfaFlowId = result.flowId; pendingMfaOrigin = origin
                    mfaLayout.visibility = View.VISIBLE
                    mfaInput.requestFocus()
                    showFeedback(feedback,
                        "Enter the two-factor code from your authenticator app, then tap Save.",
                        HaFeedbackKind.NEUTRAL)
                }
                is HaAuthClient.SignIn.Failed -> {
                    pendingMfaFlowId = null; pendingMfaOrigin = null
                    mfaLayout.visibility = View.GONE
                    showFeedback(feedback, result.reason, HaFeedbackKind.ERROR)
                }
            }
        }

        // Latest discovered theme names, kept like lastAccount: updated on a Loaded state, dropped
        // when fully signed out so a stale list can't outlive the session.
        var lastThemes: List<String> = emptyList()

        // Re-applies the current selection to the embedded HA (if open) and re-renders the picker.
        fun applyThemeAndRerender(signedIn: Boolean, rerender: () -> Unit) {
            rerender()
            // Reload the embedded HA so onPageStarted re-injects and the theme applies now; if HA isn't
            // open, it applies on next open. (No-op when the fragment is absent.)
            activity.currentHomeAssistantFragment()?.reloadUrl()
        }

        fun renderThemeList(signedIn: Boolean) {
            val selectedTheme = prefs.getString(HomeAssistantFeature.KEY_SELECTED_THEME, null)
                ?.takeIf { it.isNotBlank() }
            val selectedMode = prefs.getString(HomeAssistantFeature.KEY_SELECTED_THEME_MODE, null)
                ?: HomeAssistantNav.MODE_AUTO
            appearanceSection.setSummary(HaSummaries.theme(signedIn, selectedTheme, selectedMode))
            themeHint.visibility = View.VISIBLE
            if (!signedIn) {
                themeHint.text = "Sign in to Home Assistant to choose a theme."
                appearanceControls.visibility = View.GONE
                return
            }
            appearanceControls.visibility = View.VISIBLE
            // The built-in Home Assistant theme + the Light/Dark mode are always available, even before
            // any custom themes are discovered — HA never lists its default theme, so Refresh only adds
            // *extra* custom themes.
            themeHint.text = if (lastThemes.isEmpty())
                "Applies to the embedded Home Assistant dashboard. Tap Refresh to load custom themes."
            else
                "Applies to the embedded Home Assistant dashboard."

            // THEME radios: built-in Default first (value = null), then discovered custom themes (sorted).
            // Rebuilt each render into the Flow container; the Flow (a virtual helper) is preserved.
            themeFlowContainer.removeAllViews()
            themeFlowContainer.addView(themeFlow)
            val themeIds = ArrayList<Int>()
            (listOf("Default" to null) + lastThemes.map { it to it }).forEach { (label, value) ->
                val rb = makeSelectorRadio(activity, label, value == selectedTheme) {
                    applyThemeAndRerender(signedIn) {
                        if (value == null) prefs.edit().remove(HomeAssistantFeature.KEY_SELECTED_THEME).apply()
                        else prefs.edit().putString(HomeAssistantFeature.KEY_SELECTED_THEME, value).apply()
                        renderThemeList(signedIn)
                    }
                }
                themeFlowContainer.addView(rb)
                themeIds.add(rb.id)
            }
            themeFlow.referencedIds = themeIds.toIntArray()

            // MODE radios (static Auto/Light/Dark): reflect the selection + wire clicks. Not a RadioGroup,
            // so exclusivity is set here (each render sets all three from selectedMode). This is what
            // makes "default light" / "default dark" selectable, applied to whichever theme is chosen.
            modeRadios.forEach { (rb, mode) ->
                rb.isChecked = mode == selectedMode
                rb.setOnClickListener {
                    applyThemeAndRerender(signedIn) {
                        prefs.edit().putString(HomeAssistantFeature.KEY_SELECTED_THEME_MODE, mode).apply()
                        renderThemeList(signedIn)
                    }
                }
            }
        }

        fun renderDashboardCards(signedIn: Boolean) {
            val base = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
            val cacheOrigin = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null)
            val cacheJson = if (HomeAssistantDashboards.isCacheFresh(base, cacheOrigin))
                prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null) else null
            val available = HomeAssistantDashboards.availableFrom(cacheJson)
            val selected = HomeAssistantDashboards.normalizeSelection(
                HomeAssistantDashboards.parseSelectedPaths(
                    prefs.getString(HomeAssistantFeature.KEY_SELECTED_DASHBOARDS, null)),
                available,
            ).toMutableSet()

            val apps = available.filter { it.kind == HomeAssistantDashboards.Kind.APP }
            val dashboards = available.filter { it.kind != HomeAssistantDashboards.Kind.APP }

            fun fill(grid: LinearLayout, items: List<HomeAssistantDashboards.HaDashboard>) {
                grid.removeAllViews()
                if (items.isEmpty()) return
                val g = android.widget.GridLayout(activity).apply { columnCount = 3; useDefaultMargins = false }
                // Collapse replaces the old "Show all (N more)" truncation: an expanded section
                // always shows every card.
                items.forEach { d ->
                    g.addView(buildCard(activity, prefs, available, selected, d) {
                        dashSection.setSummary(HaSummaries.items(signedIn,
                            selected.count { p -> dashboards.any { it.urlPath == p } }, dashboards.size))
                        appsSection.setSummary(HaSummaries.items(signedIn,
                            selected.count { p -> apps.any { it.urlPath == p } }, apps.size))
                    })
                }
                grid.addView(g)
            }
            fill(dashGrid, dashboards)
            fill(appsGrid, apps)

            hint.text = if (available.size > 1) "Choose what appears in Rusty."
                else "Connect and log in to Home Assistant first, then tap Refresh."
            hint.visibility = View.VISIBLE
            appsHint.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE

            dashSection.setSummary(HaSummaries.items(signedIn,
                dashboards.count { it.urlPath in selected }, dashboards.size))
            appsSection.setSummary(HaSummaries.items(signedIn,
                apps.count { it.urlPath in selected }, apps.size))
        }

        urlInput.setText(prefs.getString(HomeAssistantFeature.KEY_URL, "").orEmpty())

        saveButton.setOnClickListener {
            val normalized = HomeAssistantUrl.normalize(urlInput.text?.toString())
            if (normalized == null) {
                showFeedback(feedback, "Enter a Home Assistant address.", HaFeedbackKind.NEUTRAL)
                return@setOnClickListener
            }
            val newOrigin = HomeAssistantUrl.origin(normalized)
            val oldOrigin = HomeAssistantUrl.origin(
                HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))
            val originChanged = newOrigin != oldOrigin

            val edit = prefs.edit().putString(HomeAssistantFeature.KEY_URL, normalized)
            if (originChanged) HomeAssistantFeature.SERVER_RESET_KEYS.forEach { edit.remove(it) }
            edit.apply()
            urlInput.setText(normalized)
            renderCleartextWarning()

            if (originChanged) {
                // Tokens belong to the OLD server: revoke there (best-effort), then forget locally.
                val oldTokens = if (oldOrigin != null) HaAuthStore.tokensFor(prefs, secrets, oldOrigin) else null
                HaAuthStore.clear(prefs, secrets)
                if (oldOrigin != null && oldTokens?.refreshToken != null) {
                    scope.launch { withContext(Dispatchers.IO) {
                        HaAuthClient.shared.revoke(oldOrigin, oldTokens.refreshToken) } }
                }
                pendingMfaFlowId = null; pendingMfaOrigin = null; mfaLayout.visibility = View.GONE
                RustyApp.haRepository(activity).reset()
                activity.currentHomeAssistantFragment()?.reloadUrl()
                activity.refreshDashboardChips()
            }

            // Credentials present → sign in right here. Absent → plain URL save, as before.
            val username = usernameInput.text?.toString()?.trim().orEmpty()
            val password = passwordInput.text?.toString().orEmpty()
            val mfaCode = mfaInput.text?.toString()?.trim().orEmpty()
            val origin = newOrigin ?: return@setOnClickListener
            when {
                pendingMfaFlowId != null && pendingMfaOrigin == origin && mfaCode.isNotEmpty() -> {
                    val flowId = pendingMfaFlowId!!
                    saveButton.isEnabled = false; testButton.isEnabled = false
                    showFeedback(feedback, "Checking code…", HaFeedbackKind.NEUTRAL)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            HaAuthClient.shared.completeMfa(origin, flowId, mfaCode, System.currentTimeMillis())
                        }
                        handleSignInResult(origin, r)
                    }
                }
                username.isNotEmpty() && password.isNotEmpty() -> {
                    saveButton.isEnabled = false; testButton.isEnabled = false
                    showFeedback(feedback, "Signing in…", HaFeedbackKind.NEUTRAL)
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            HaAuthClient.shared.signIn(origin, username, password, System.currentTimeMillis())
                        }
                        handleSignInResult(origin, r)
                    }
                }
                else -> showFeedback(feedback,
                    "✓ Saved. Enter username and password to sign in.", HaFeedbackKind.SUCCESS)
            }
        }

        testButton.setOnClickListener {
            val origin = HomeAssistantUrl.origin(
                HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))
            val tokens = if (origin != null) HaAuthStore.tokensFor(prefs, secrets, origin) else null
            if (origin == null || tokens?.refreshToken == null) {
                showFeedback(feedback, "Save the address and sign in first.", HaFeedbackKind.NEUTRAL)
                return@setOnClickListener
            }
            testButton.isEnabled = false
            showFeedback(feedback, "Testing…", HaFeedbackKind.NEUTRAL)
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    HaAuthClient.shared.test(origin, tokens.refreshToken, System.currentTimeMillis())
                }
                testButton.isEnabled = true
                when (r) {
                    is HaAuthClient.TestResult.Ok -> {
                        val who = lastAccount?.let {
                            " as ${it.name}${if (it.isAdmin) " (admin)" else ""}"
                        } ?: ""
                        showFeedback(feedback, "✓ Connected and signed in$who.", HaFeedbackKind.SUCCESS)
                    }
                    is HaAuthClient.TestResult.Failed -> showFeedback(feedback, r.reason, HaFeedbackKind.ERROR)
                }
            }
        }

        val repo = RustyApp.haRepository(activity)

        // React to discovery state transitions — listener is registered below; initial state is
        // delivered immediately on addListener so the checklist renders without a separate call.
        val listener = HomeAssistantDashboardRepository.Listener { state ->
            val account = (state as? HaDiscovery.Loaded)?.account
            val signedIn = HomeAssistantDashboards.isSignedIn(state) || tokenSignedIn()
            // Cache the identity while known; forget it once fully signed out (no live session AND no
            // stored token) so a later sign-in as a different user is never labelled with the old name.
            if (account != null) lastAccount = account else if (!signedIn) lastAccount = null
            // Pass the token-inclusive signedIn (not the bare discovery state) so a manual Refresh —
            // which emits a transient Refreshing state — never flashes the sign-in form back open.
            renderAccount(activity, prefs, state, signedIn, lastAccount,
                accountName, accountSub, accountAvatar, accountAction, connectionSection)
            applySignedInUi(signedIn, account ?: lastAccount)
            (state as? HaDiscovery.Loaded)?.let { lastThemes = it.themes }
                ?: run { if (!signedIn) lastThemes = emptyList() }
            renderThemeList(signedIn)
            // A settings sign-in shows a name-less success toast; once discovery reveals who we are,
            // upgrade it to name them (only while that pending sign-in is still the thing being described).
            if (justSignedIn && account != null) {
                justSignedIn = false
                showFeedback(feedback,
                    "✓ Signed in as ${account.name} — password not stored.", HaFeedbackKind.SUCCESS)
            }

            // Refresh lives in the sign-in row now: enabled only while signed in and not mid-discovery
            // (shown-but-disabled when signed out, since there's nothing to discover).
            refreshButton.isEnabled = signedIn && state !is HaDiscovery.Refreshing
            // Spinner + check feedback, gated on a user-initiated refresh (refreshInFlight) so ordinary
            // page-load discovery doesn't flash it.
            if (refreshInFlight) when (state) {
                is HaDiscovery.Refreshing -> { refreshSpinner.visibility = View.VISIBLE; refreshButton.icon = null }
                else -> {
                    refreshInFlight = false
                    refreshSpinner.visibility = View.GONE
                    // Brief green check on the button confirming the refresh completed.
                    refreshButton.icon = ContextCompat.getDrawable(activity, R.drawable.ic_check)
                    refreshButton.iconTint =
                        android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent_fallback))
                    refreshButton.postDelayed({ refreshButton.icon = null }, 1500)
                }
            }
            when (state) {
                is HaDiscovery.Refreshing -> hint.text = "Discovering dashboards…"
                is HaDiscovery.Loaded -> renderDashboardCards(signedIn)
                is HaDiscovery.Error -> {
                    showFeedback(feedback, state.reason, HaFeedbackKind.ERROR)
                    renderDashboardCards(signedIn)
                }
                is HaDiscovery.Idle -> renderDashboardCards(signedIn)
            }
        }
        repo.addListener(listener)

        refreshButton.setOnClickListener {
            val fragment = activity.currentHomeAssistantFragment()
            if (fragment != null) {
                // Enter the feedback cycle immediately: cancel any lingering check, show the spinner.
                refreshInFlight = true
                refreshButton.icon = null
                refreshSpinner.visibility = View.VISIBLE
                fragment.runDiscovery()
                // Watchdog: if discovery returns early (no origin / webView hidden) so no terminal state
                // ever arrives, don't leave the spinner stuck. Self-clearing — a no-op once completed.
                refreshButton.postDelayed({
                    if (refreshInFlight) {
                        refreshInFlight = false
                        refreshSpinner.visibility = View.GONE
                        refreshButton.icon = null
                    }
                }, 10_000)
            } else {
                showFeedback(feedback, "Open Home Assistant first, then tap Refresh.", HaFeedbackKind.NEUTRAL)
            }
        }

        // Cleanup: cancel in-flight sign-in/test coroutines and remove the repo listener. This
        // lambda is invoked by SettingsSheet on BOTH tab-switch AND dialog dismiss — preserving the
        // Task 15 lifecycle exactly.
        return { scope.cancel(); repo.removeListener(listener) }
    }

    /** Single source of truth for a dashboard card's selected/unselected appearance, so the
     *  initial render and the click handler can never drift apart. */
    private fun applyCardSelectionVisual(
        card: com.google.android.material.card.MaterialCardView,
        icon: android.widget.ImageView,
        checked: Boolean,
    ) {
        val ctx = card.context
        card.setCardBackgroundColor(
            ContextCompat.getColor(ctx, if (checked) R.color.accent_chip_fill else R.color.surface_raised))
        card.strokeColor =
            ContextCompat.getColor(ctx, if (checked) R.color.accent_fallback else R.color.surface_border)
        val iconTint = ContextCompat.getColor(ctx, if (checked) R.color.accent_fallback else R.color.ink)
        androidx.core.widget.ImageViewCompat.setImageTintList(
            icon, android.content.res.ColorStateList.valueOf(iconTint))
    }

    /** Builds one Appearance-picker radio, styled to match the Screensaver theme radios (accent tint,
     *  Hanken body font, D-pad focus foreground). Used for the dynamic Theme radios; the Mode radios are
     *  the static XML ones. A generated id lets the ConstraintLayout Flow reference it. */
    private fun makeSelectorRadio(
        activity: HomeActivity,
        label: String,
        checked: Boolean,
        onClick: () -> Unit,
    ): android.widget.RadioButton {
        val rb = android.widget.RadioButton(activity)
        rb.id = View.generateViewId()
        rb.text = label
        rb.buttonTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(activity, R.color.accent_fallback))
        rb.setTextColor(ContextCompat.getColor(activity, R.color.ink))
        androidx.core.content.res.ResourcesCompat.getFont(activity, R.font.hanken_regular)
            ?.let { rb.typeface = it }
        rb.foreground = ContextCompat.getDrawable(activity, R.drawable.bg_tv_focus_switch)
        rb.isChecked = checked
        rb.layoutParams = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT,
            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.WRAP_CONTENT)
        rb.setOnClickListener { onClick() }
        return rb
    }

    private fun buildCard(
        activity: HomeActivity,
        prefs: SharedPreferences,
        available: List<HomeAssistantDashboards.HaDashboard>,
        selected: MutableSet<String>,
        d: HomeAssistantDashboards.HaDashboard,
        onSelectionChanged: () -> Unit,
    ): View {
        val card = android.view.LayoutInflater.from(activity)
            .inflate(R.layout.view_dashboard_card, null) as com.google.android.material.card.MaterialCardView
        card.layoutParams = android.widget.GridLayout.LayoutParams().apply {
            this.width = 0
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
        }
        val icon = card.findViewById<android.widget.ImageView>(R.id.cardIcon)
        val iconSizePx = (26f * activity.resources.displayMetrics.density).toInt()
        icon.setImageDrawable(HaIcons.iconDrawable(activity, d.icon, iconSizePx))
        card.findViewById<TextView>(R.id.cardTitle).text = d.title
        card.findViewById<TextView>(R.id.cardTag).visibility =
            if (d.urlPath == HomeAssistantDashboards.OVERVIEW_PATH) View.VISIBLE else View.GONE
        card.isChecked = d.urlPath in selected
        applyCardSelectionVisual(card, icon, card.isChecked)
        card.setOnClickListener {
            val nowChecked = d.urlPath !in selected
            if (nowChecked) selected.add(d.urlPath) else selected.remove(d.urlPath)
            card.isChecked = nowChecked
            applyCardSelectionVisual(card, icon, nowChecked)
            val normalized = HomeAssistantDashboards.normalizeSelection(selected.toList(), available)
            prefs.edit().putString(
                HomeAssistantFeature.KEY_SELECTED_DASHBOARDS,
                HomeAssistantDashboards.serializeSelectedPaths(normalized)
            ).apply()
            activity.refreshDashboardChips()
            onSelectionChanged()
        }
        return card
    }

    private fun renderAccount(
        activity: HomeActivity,
        prefs: android.content.SharedPreferences,
        state: HaDiscovery,
        signedIn: Boolean,
        knownAccount: HomeAssistantDashboards.HaAccount?,
        name: TextView, sub: TextView, avatar: TextView, action: MaterialButton,
        connectionSection: View,
    ) {
        // [signedIn] is token-inclusive (a stored refresh token counts), so a transient Refreshing state
        // during a manual refresh no longer reveals the sign-in form or downgrades the account card.
        connectionSection.visibility = if (signedIn) View.GONE else View.VISIBLE
        sub.visibility = if (signedIn) View.VISIBLE else View.GONE
        // Recompute host each render so a URL Save takes effect immediately.
        val host = HomeAssistantUrl.origin(
            HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))
            ?.substringAfter("://") ?: "Home Assistant"
        // Prefer the live account; fall back to the cached identity while signed in (e.g. mid-refresh,
        // when the discovery state carries no account yet) so the row doesn't blink to a generic label.
        val account = (state as? HaDiscovery.Loaded)?.account ?: knownAccount?.takeIf { signedIn }
        when {
            account != null -> {                       // SIGNED IN with known account
                avatar.text = account.name.take(1).uppercase()
                name.text = if (account.isAdmin) "${account.name}  ·  Admin" else account.name
                sub.text = "Signed in to $host"
                action.text = "Sign out"
                action.setOnClickListener { signOut(activity, prefs) }
            }
            signedIn -> {                              // SIGNED IN, name not known
                avatar.text = "•"
                name.text = "Signed in"
                sub.text = "Connected to $host"
                action.text = "Sign out"
                action.setOnClickListener { signOut(activity, prefs) }
            }
            state is HaDiscovery.Error -> {            // SIGNED OUT / AUTH ERROR
                avatar.text = "?"
                name.text = "Not signed in"
                sub.text = "Sign in to $host to load dashboards"
                action.text = "Sign in"
                action.setOnClickListener {
                    activity.currentHomeAssistantFragment()?.reloadUrl()
                        ?: showFeedbackToast(activity, "Open Home Assistant to sign in.")
                }
            }
            state is HaDiscovery.Refreshing -> {       // IN-PROGRESS — do not claim signed-in
                avatar.text = "…"
                name.text = "Checking sign-in…"
                sub.text = "Open Home Assistant to sign in"
                action.text = "Sign in"
                action.setOnClickListener {
                    activity.currentHomeAssistantFragment()?.reloadUrl()
                        ?: showFeedbackToast(activity, "Open Home Assistant to sign in.")
                }
            }
            else -> {                                   // IDLE — fresh install / cold start / post-reset
                avatar.text = "…"
                name.text = "Not connected yet"
                sub.text = "Open Home Assistant to sign in"
                action.text = "Sign in"
                action.setOnClickListener {
                    activity.currentHomeAssistantFragment()?.reloadUrl()
                        ?: showFeedbackToast(activity, "Open Home Assistant to sign in.")
                }
            }
        }
    }

    private fun signOut(activity: HomeActivity, prefs: android.content.SharedPreferences) {
        // Revoke the app's refresh token server-side (shows as gone in HA's own token list), then
        // forget it locally. Best-effort: local wipe proceeds even if the server is unreachable.
        val secrets = SecretStore.of(activity)
        val origin = HomeAssistantUrl.origin(
            HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))
        val tokens = if (origin != null) HaAuthStore.tokensFor(prefs, secrets, origin) else null
        HaAuthStore.clear(prefs, secrets)
        if (origin != null && tokens?.refreshToken != null) {
            Thread { HaAuthClient.shared.revoke(origin, tokens.refreshToken) }.start()
        }
        // Clear the web session.
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.CookieManager.getInstance().flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
        // Wipe the server-scoped discovery cache + dashboard selection (the user re-selects after
        // re-login), mirroring the URL-change reset. KEY_URL is preserved.
        val edit = prefs.edit()
        HomeAssistantFeature.SERVER_RESET_KEYS.forEach { edit.remove(it) }
        edit.apply()
        // Reset discovery state: the panel listener re-renders the (now-empty) checklist + account
        // card against the cleared prefs, and the chip bar is rebuilt empty.
        RustyApp.haRepository(activity).reset()
        activity.refreshDashboardChips()
        // Reload the WebView to the HA login page; re-discovery settles the card on the signed-out state.
        activity.currentHomeAssistantFragment()?.reloadUrl()
    }

    private fun showFeedbackToast(activity: HomeActivity, msg: String) {
        android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private val PREFS_NAME = "spotify_receiver_prefs"
}

/** Brand-token feedback colors for the HA settings status line (replaces hardcoded hex). */
internal enum class HaFeedbackKind { SUCCESS, NEUTRAL, ERROR }

/** Writes a brand-tinted status line into a settings panel's feedback [TextView] and reveals it.
 *  Shared by the HA panel and [DlnaPlayerSettingsPanel] — one feedback idiom, not two. */
internal fun showFeedback(view: TextView, message: String, kind: HaFeedbackKind) {
    view.text = message
    view.setTextColor(ContextCompat.getColor(view.context, haFeedbackColorRes(kind)))
    view.visibility = View.VISIBLE
}

@androidx.annotation.ColorRes
internal fun haFeedbackColorRes(kind: HaFeedbackKind): Int = when (kind) {
    HaFeedbackKind.SUCCESS -> R.color.dot_green
    HaFeedbackKind.NEUTRAL -> R.color.muted_dim
    HaFeedbackKind.ERROR -> R.color.dot_red
}
