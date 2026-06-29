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

    /** Pref keys wiped whenever the active HA server session is reset — on a server-URL change AND on
     *  sign-out. Excludes [KEY_URL]: the server address is preserved across sign-out. Shared by the
     *  settings URL-save, the in-fragment URL-save, and sign-out so the three paths cannot drift. */
    val SERVER_RESET_KEYS: List<String> = listOf(
        KEY_DASHBOARDS_CACHE,
        KEY_DASHBOARDS_ORIGIN,
        KEY_SELECTED_DASHBOARDS,
        HomeAssistantFragment.KEY_ACTIVE_DASHBOARD_ORIGIN,
        HomeAssistantFragment.KEY_ACTIVE_DASHBOARD_PATH,
    )

    override val id = FeatureId.HOME_ASSISTANT
    override val title = "Home Assistant"
    override val iconRes = R.drawable.ic_home
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

    // expanded-section state (per panel bind); reset whenever the panel rebinds
    private val expanded = mutableSetOf<HomeAssistantDashboards.Kind>()

    override fun bind(panel: View): () -> Unit {
        expanded.clear()
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val urlInput = panel.findViewById<TextInputEditText>(R.id.etHaSettingsUrl)
        val saveButton = panel.findViewById<MaterialButton>(R.id.btnHaSaveUrl)
        val clearButton = panel.findViewById<MaterialButton>(R.id.btnHaClearUrl)
        val feedback = panel.findViewById<TextView>(R.id.tvHaFeedback)
        val list = panel.findViewById<LinearLayout>(R.id.haDashboardsList)
        val hint = panel.findViewById<TextView>(R.id.tvHaDashboardsHint)
        val refreshButton = panel.findViewById<MaterialButton>(R.id.btnHaRefreshDashboards)
        val accountName = panel.findViewById<TextView>(R.id.haAccountName)
        val accountSub = panel.findViewById<TextView>(R.id.haAccountSub)
        val accountAvatar = panel.findViewById<TextView>(R.id.haAccountAvatar)
        val accountAction = panel.findViewById<MaterialButton>(R.id.btnHaAccountAction)
        val connectionSection = panel.findViewById<View>(R.id.haConnectionSection)

        urlInput.setText(prefs.getString(HomeAssistantFeature.KEY_URL, "").orEmpty())

        saveButton.setOnClickListener {
            val normalized = HomeAssistantUrl.normalize(urlInput.text?.toString())
            if (normalized == null) {
                showFeedback(feedback, "Enter a Home Assistant address.", HaFeedbackKind.NEUTRAL)
            } else {
                val newOrigin = HomeAssistantUrl.origin(normalized)
                val oldOrigin = HomeAssistantUrl.origin(
                    HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
                )
                val originChanged = newOrigin != oldOrigin

                val edit = prefs.edit().putString(HomeAssistantFeature.KEY_URL, normalized)
                if (originChanged) {
                    // Clear origin-gated discovery cache + active-dashboard selection so stale chips/
                    // checklist from the old server are never shown against the new URL.
                    HomeAssistantFeature.SERVER_RESET_KEYS.forEach { edit.remove(it) }
                }
                edit.apply()
                urlInput.setText(normalized)

                if (originChanged) {
                    // Reset the repo to Idle so the settings checklist shows the "Connect and log in…"
                    // hint and stale Loaded dashboards don't linger in the chip bar.
                    RustyApp.haRepository(activity).reset()
                    // Tell the active HomeAssistantFragment to reload at the new URL (live apply — no
                    // "reopen the feature" required).
                    val haFragment = activity.currentHomeAssistantFragment()
                    haFragment?.reloadUrl()
                    // Refresh the chip bar immediately (will show zero chips until re-discovery).
                    activity.refreshDashboardChips()
                    showFeedback(feedback, "✓ Saved — loading new address.", HaFeedbackKind.SUCCESS)
                } else {
                    showFeedback(feedback, "✓ Saved.", HaFeedbackKind.SUCCESS)
                }
            }
        }

        // Clear the URL field so the user can type a fresh address; the empty field then shows the
        // default placeholder. The saved URL is not forgotten until a new one is saved.
        clearButton.setOnClickListener { urlInput.setText("") }

        val repo = RustyApp.haRepository(activity)

        // React to discovery state transitions — listener is registered below; initial state is
        // delivered immediately on addListener so the checklist renders without a separate call.
        val listener = HomeAssistantDashboardRepository.Listener { state ->
            renderAccount(activity, prefs, state, accountName, accountSub, accountAvatar, accountAction, connectionSection)
            when (state) {
                is HaDiscovery.Refreshing -> {
                    refreshButton.isEnabled = false
                    hint.text = "Discovering dashboards…"
                }
                is HaDiscovery.Loaded -> {
                    refreshButton.isEnabled = true
                    renderDashboardCards(activity, prefs, list, hint)
                }
                is HaDiscovery.Error -> {
                    refreshButton.isEnabled = true
                    showFeedback(feedback, state.reason, HaFeedbackKind.ERROR)
                }
                is HaDiscovery.Idle -> {
                    refreshButton.isEnabled = true
                    renderDashboardCards(activity, prefs, list, hint)
                }
            }
        }
        repo.addListener(listener)

        refreshButton.setOnClickListener {
            val fragment = activity.currentHomeAssistantFragment()
            if (fragment != null) {
                fragment.runDiscovery()
            } else {
                showFeedback(feedback, "Open Home Assistant first, then tap Refresh.", HaFeedbackKind.NEUTRAL)
            }
        }

        // Cleanup: remove the repo listener. This lambda is invoked by SettingsSheet on BOTH
        // tab-switch AND dialog dismiss — preserving the Task 15 lifecycle exactly.
        return { repo.removeListener(listener) }
    }

    private fun renderDashboardCards(
        activity: HomeActivity,
        prefs: SharedPreferences,
        list: LinearLayout,
        hint: TextView,
    ) {
        val base = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
        val origin = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null)
        val cacheJson = if (HomeAssistantDashboards.isCacheFresh(base, origin))
            prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null) else null
        val available = HomeAssistantDashboards.availableFrom(cacheJson)
        val selected = HomeAssistantDashboards.normalizeSelection(
            HomeAssistantDashboards.parseSelectedPaths(
                prefs.getString(HomeAssistantFeature.KEY_SELECTED_DASHBOARDS, null)),
            available,
        ).toMutableSet()

        hint.text = if (available.size > 1)
            "Choose what appears in Rusty."
        else
            "Connect and log in to Home Assistant first, then tap Refresh."

        // Group: APP → Apps; DASHBOARD/UNKNOWN → Dashboards. Overview leads Dashboards.
        val apps = available.filter { it.kind == HomeAssistantDashboards.Kind.APP }
        val dashboards = available.filter { it.kind != HomeAssistantDashboards.Kind.APP }

        list.removeAllViews()
        addCardSection(activity, prefs, list, available, selected, "Dashboards",
            HomeAssistantDashboards.Kind.DASHBOARD, dashboards)
        addCardSection(activity, prefs, list, available, selected, "Apps",
            HomeAssistantDashboards.Kind.APP, apps)
    }

    private fun addCardSection(
        activity: HomeActivity,
        prefs: SharedPreferences,
        parent: LinearLayout,
        available: List<HomeAssistantDashboards.HaDashboard>,
        selected: MutableSet<String>,
        title: String,
        sectionKey: HomeAssistantDashboards.Kind,
        items: List<HomeAssistantDashboards.HaDashboard>,
    ) {
        if (items.isEmpty()) return
        val header = TextView(activity).apply {
            text = "$title · ${items.size} found"
            setTextColor(ContextCompat.getColor(activity, R.color.muted_dim))
            textSize = 12f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
        }
        parent.addView(header)

        val grid = android.widget.GridLayout(activity).apply {
            columnCount = 3
            useDefaultMargins = false
        }
        val isExpanded = sectionKey in expanded
        val shown = if (isExpanded) items else items.take(3)
        shown.forEach { d -> grid.addView(buildCard(activity, prefs, available, selected, d)) }
        parent.addView(grid)

        if (items.size > 3) {
            val btn = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.view_dashboard_more_button, parent, false) as MaterialButton
            btn.text = if (isExpanded) "Show less" else "Show all (${items.size - 3} more)"
            btn.setOnClickListener {
                if (isExpanded) expanded.remove(sectionKey) else expanded.add(sectionKey)
                renderDashboardCards(activity, prefs,
                    parent, parent.rootView.findViewById(R.id.tvHaDashboardsHint))
            }
            parent.addView(btn)
        }
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

    private fun buildCard(
        activity: HomeActivity,
        prefs: SharedPreferences,
        available: List<HomeAssistantDashboards.HaDashboard>,
        selected: MutableSet<String>,
        d: HomeAssistantDashboards.HaDashboard,
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
        }
        return card
    }

    private fun renderAccount(
        activity: HomeActivity,
        prefs: android.content.SharedPreferences,
        state: HaDiscovery,
        name: TextView, sub: TextView, avatar: TextView, action: MaterialButton,
        connectionSection: View,
    ) {
        // Connection config is only useful while signed out; the account subtitle only while signed in.
        val signedIn = HomeAssistantDashboards.isSignedIn(state)
        connectionSection.visibility = if (signedIn) View.GONE else View.VISIBLE
        sub.visibility = if (signedIn) View.VISIBLE else View.GONE
        // Recompute host each render so a URL Save takes effect immediately.
        val host = HomeAssistantUrl.origin(
            HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null)))
            ?.substringAfter("://") ?: "Home Assistant"
        val account = (state as? HaDiscovery.Loaded)?.account
        when {
            account != null -> {                       // SIGNED IN with known account
                avatar.text = account.name.take(1).uppercase()
                name.text = if (account.isAdmin) "${account.name}  ·  Admin" else account.name
                sub.text = "Signed in to $host"
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
            state is HaDiscovery.Loaded -> {           // LOADED but current_user returned no name
                avatar.text = "•"
                name.text = "Signed in"
                sub.text = "Connected to $host"
                action.text = "Sign out"
                action.setOnClickListener { signOut(activity, prefs) }
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

    private fun showFeedback(view: TextView, message: String, kind: HaFeedbackKind) {
        view.text = message
        view.setTextColor(ContextCompat.getColor(view.context, haFeedbackColorRes(kind)))
        view.visibility = View.VISIBLE
    }

    private val PREFS_NAME = "spotify_receiver_prefs"
}

/** Brand-token feedback colors for the HA settings status line (replaces hardcoded hex). */
internal enum class HaFeedbackKind { SUCCESS, NEUTRAL, ERROR }

@androidx.annotation.ColorRes
internal fun haFeedbackColorRes(kind: HaFeedbackKind): Int = when (kind) {
    HaFeedbackKind.SUCCESS -> R.color.dot_green
    HaFeedbackKind.NEUTRAL -> R.color.muted_dim
    HaFeedbackKind.ERROR -> R.color.dot_red
}
