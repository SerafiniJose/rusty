package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.rusty.app.renderer.DidlParser
import dev.rusty.app.renderer.RendererPrefs
import dev.rusty.app.renderer.RendererRuntimeHolder
import dev.rusty.app.renderer.RendererStatusPublisher
import dev.rusty.app.renderer.RendererStatusSnapshot
import dev.rusty.app.renderer.RendererUiSnapshot
import dev.rusty.app.renderer.SharedPrefsRendererStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shell-owned "Services & status" card: what is actually running, whether the configured
 * integrations are connected, and where to go to change either one.
 *
 * Shell-owned rather than feature-owned because it is app-wide. The old sheet lived in
 * [SpotifyFragment] and piggybacked on that fragment's store listener — which is removed whenever
 * another feature is foreground, so the page could not have shown live Spotify state over Home
 * Assistant or DLNA. This registers its own listeners on every publisher it reads, and composes every
 * removal into the dialog's single dismiss callback.
 *
 * Two subtleties the wiring depends on:
 *  - Every publisher here replays its current value ASYNCHRONOUSLY (all of them post to the main
 *    looper). The first paint therefore comes from a synchronous [render] before `show()`, not from
 *    the replay, or the card would flash empty for a frame.
 *  - An exception escaping a [ReceiverStateStore.Listener] wedges that store's drain loop for the
 *    whole process, so every callback body here is wrapped in `runCatching`.
 */
object InfoSheet {

    private const val PREFS_NAME = "spotify_receiver_prefs"

    fun show(activity: HomeActivity, host: ShellHost) {
        val root = activity.layoutInflater.inflate(R.layout.bottom_sheet_info, null)
        root.findViewById<MaxHeightNestedScrollView>(R.id.infoScroll).maxHeightFraction =
            INFO_CARD_MAX_HEIGHT_FRACTION

        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = RustyApp.from(activity)
        val haRepo = RustyApp.haRepository(activity)

        val summaryLine = root.findViewById<TextView>(R.id.tvInfoSummary)
        val servicesContainer = root.findViewById<LinearLayout>(R.id.llInfoServices)
        val featuresContainer = root.findViewById<LinearLayout>(R.id.llInfoFeatures)
        val featuresHeader = root.findViewById<TextView>(R.id.tvInfoFeaturesHeader)
        val featuresDivider = root.findViewById<View>(R.id.viewInfoFeaturesDivider)
        val updateBadge = root.findViewById<TextView>(R.id.tvUpdateBadge)
        val aboutRow = root.findViewById<View>(R.id.rowAbout)

        // Every listener this page registers is composed into one lambda, invoked by the card's single
        // dismiss callback — the same shape SettingsSheet uses for its panel teardown.
        var cleanup: (() -> Unit)? = null
        val dialog = AboutSheet.createCardDialog(activity, root, onDismiss = {
            cleanup?.invoke()
            cleanup = null
        })

        /** Rows navigate AFTER the card closes, so the settings card is not stacked on top of it. */
        fun openTab(tab: SettingsTabKey) {
            dialog.dismiss()
            host.openSettings(tab)
        }

        // Service rows are a fixed set of three in a fixed order, so they are inflated once and
        // re-bound in place — rebuilding them on every snapshot would drop D-pad focus mid-playback.
        val serviceViews = InfoServiceId.entries.associateWith { _ ->
            activity.layoutInflater.inflate(R.layout.item_info_service, servicesContainer, false)
                .also { servicesContainer.addView(it) }
        }
        // Feature rows appear and disappear with configuration, so they are rebuilt only when the set
        // of ids actually changes.
        var renderedFeatureIds: List<InfoFeatureId> = emptyList()
        var featureViews: Map<InfoFeatureId, View> = emptyMap()

        fun render() {
            val overview = buildOverview(activity, prefs, host, store, haRepo)
            summaryLine.text = overview.summary

            overview.services.forEach { row ->
                serviceViews[row.id]?.let { bindServiceRow(activity, it, row, ::openTab) }
            }

            val ids = overview.features.map { it.id }
            if (ids != renderedFeatureIds) {
                featuresContainer.removeAllViews()
                featureViews = ids.associateWith { _ ->
                    activity.layoutInflater.inflate(R.layout.item_info_feature, featuresContainer, false)
                        .also { featuresContainer.addView(it) }
                }
                renderedFeatureIds = ids
            }
            overview.features.forEach { row ->
                featureViews[row.id]?.let { bindFeatureRow(activity, it, row, ::openTab) }
            }

            val hasFeatures = overview.features.isNotEmpty()
            featuresContainer.visibility = if (hasFeatures) View.VISIBLE else View.GONE
            featuresHeader.visibility = if (hasFeatures) View.VISIBLE else View.GONE
            featuresDivider.visibility = if (hasFeatures) View.VISIBLE else View.GONE
        }

        // First paint is synchronous: every publisher's replay is posted, never inline.
        render()

        // Held in vals because every one of these removes by instance identity — a fresh lambda at
        // dismiss time would not match, and the listener would outlive the card.
        val storeListener = ReceiverStateStore.Listener { runCatching { render() } }
        val controlListener: (ControlServerStatus.State) -> Unit = { runCatching { render() } }
        val rendererStatusListener: (RendererStatusSnapshot) -> Unit = { runCatching { render() } }
        val rendererUiListener: (RendererUiSnapshot) -> Unit = { runCatching { render() } }
        val haListener = HomeAssistantDashboardRepository.Listener { runCatching { render() } }
        val slideshowListener: () -> Unit = { runCatching { render() } }

        store.addListener(storeListener)
        ControlServerStatus.addListener(controlListener)
        // BOTH renderer publishers: the holder only forwards status changes after the service has
        // attached once in this process, so a start the system refused (FAILED, service never created)
        // would never reach a holder-only listener.
        RendererStatusPublisher.addListener(rendererStatusListener)
        RendererRuntimeHolder.addListener(rendererUiListener)
        haRepo.addListener(haListener)
        SlideshowConfigRelay.addListener(slideshowListener)

        aboutRow.setOnClickListener { AboutSheet.show(activity) }

        // The update badge: same cached check as before, so reopening is cheap. Guarded by the
        // activity's own flags plus isShowing, because the blocking call can outlive the card.
        val version = AboutSheet.appVersionName(activity)
        activity.lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) { UpdateRepository.check(version) }
            if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return@launch
            if (check.status == UpdateRepository.UpdateStatus.UPDATE_AVAILABLE) {
                updateBadge.visibility = View.VISIBLE
            }
        }
        root.findViewById<TextView>(R.id.tvAboutValue).text = "Version $version"

        cleanup = {
            store.removeListener(storeListener)
            ControlServerStatus.removeListener(controlListener)
            RendererStatusPublisher.removeListener(rendererStatusListener)
            RendererRuntimeHolder.removeListener(rendererUiListener)
            haRepo.removeListener(haListener)
            SlideshowConfigRelay.removeListener(slideshowListener)
        }

        dialog.show()
        // D-pad order runs top-to-bottom, so the first service row is the landing target.
        AboutSheet.requestInitialFocus(serviceViews[InfoServiceId.SPOTIFY] ?: aboutRow)
    }

    // ---- Reading the runtime ------------------------------------------------

    /**
     * Reads every publisher and preference the page needs and hands plain values to the pure reducer.
     * All reads are cheap and main-thread safe: the snapshots are in-memory, and [SecretStore] is only
     * touched when there is actually a configured server to check — its first construction does key
     * derivation and file I/O, which must not land on the main thread on an install that never
     * configured either integration.
     */
    private fun buildOverview(
        activity: HomeActivity,
        prefs: SharedPreferences,
        host: ShellHost,
        store: ReceiverStateStore,
        haRepo: HomeAssistantDashboardRepository,
    ): InfoOverview {
        val snapshot = store.snapshot
        val state = snapshot.state
        val spotify = InfoSpotifyInput(
            service = snapshot.service,
            status = state.status,
            receiverName = state.receiverName,
            sessionName = state.sessionDisplayName?.takeIf { it.isNotBlank() } ?: state.sessionUser,
            bitrateKbps = host.currentBitrateKbps,
        )

        val rendererStatus = RendererStatusPublisher.current()
        val rendererUi = RendererRuntimeHolder.current()
        val dlna = InfoDlnaInput(
            featureEnabled = prefs.getBoolean(DlnaPlayerFeature.KEY_ENABLED, false),
            status = rendererStatus.status,
            descriptionUrl = rendererStatus.descriptionUrl,
            // The runtime reports "" whenever no backend is attached, so a stopped player falls back
            // to its persisted name rather than rendering a nameless row.
            rendererName = rendererUi.identity.deviceName.takeIf { it.isNotBlank() }
                ?: RendererPrefs.name(SharedPrefsRendererStore(prefs)),
            transport = rendererUi.state?.transport,
            transportError = rendererUi.state?.transportStatus == TRANSPORT_ERROR,
            trackTitle = DidlParser.parse(rendererUi.state?.media?.metadata).title,
        )

        val control = InfoControlInput(
            state = ControlServerStatus.current(),
            enabled = ControlSettings.isEnabled(prefs),
        )

        val haUrl = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
        val haOrigin = HomeAssistantUrl.origin(haUrl)
        val cacheJson =
            if (HomeAssistantDashboards.isCacheFresh(
                    haUrl,
                    prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null),
                )
            ) {
                prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null)
            } else {
                null
            }
        val ha = InfoHaInput(
            enabled = prefs.getBoolean(HomeAssistantFeature.KEY_ENABLED, false),
            configuredUrl = haUrl,
            hasOriginToken = haOrigin != null &&
                HaAuthStore.tokensFor(prefs, SecretStore.of(activity), haOrigin)?.refreshToken != null,
            discovery = haRepo.state,
            accountName = HomeAssistantFeature.accountName(prefs),
            selectedDashboards = HomeAssistantDashboards.selectedFrom(
                cacheJson,
                prefs.getString(HomeAssistantFeature.KEY_SELECTED_DASHBOARDS, null),
            ).size,
        )

        val immichUrl = prefs.getString(SlideshowSettings.KEY_URL, null)?.takeIf { it.isNotBlank() }
        val filters = SlideshowSettings.filters(prefs)
        val immich = InfoImmichInput(
            enabled = SlideshowSettings.isEnabled(prefs),
            configured = immichUrl != null && SlideshowSettings.config(prefs, SecretStore.of(activity)) != null,
            verified = SlideshowSettings.isVerified(prefs),
            lastVerifyFailed = SlideshowSettings.lastVerifyFailed(prefs),
            serverUrl = immichUrl,
            accountName = SlideshowSettings.accountName(prefs),
            albums = filters.albumIds.size,
            people = filters.personIds.size,
            tags = filters.tagIds.size,
        )

        val featureTabs = FeatureRegistry.enabledIds(prefs).map { FeatureRegistry.byId(it).settingsTab }
        val availableTabs = settingsTabsFor(featureTabs, SlideshowSettings.isEnabled(prefs)).toSet()

        return InfoOverviewReducer.reduce(spotify, dlna, control, ha, immich, availableTabs)
    }

    // ---- Row binding --------------------------------------------------------

    private fun bindServiceRow(
        activity: HomeActivity,
        view: View,
        row: InfoServiceRow,
        onOpen: (SettingsTabKey) -> Unit,
    ) {
        view.findViewById<ImageView>(R.id.ivInfoServiceIcon).apply {
            setImageResource(iconFor(row.id))
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.ink))
        }
        view.findViewById<TextView>(R.id.tvInfoServiceTitle).text = row.title
        val toneColor = ContextCompat.getColor(activity, toneColorRes(row.tone))
        view.findViewById<View>(R.id.viewInfoServiceDot).backgroundTintList =
            ColorStateList.valueOf(toneColor)
        view.findViewById<TextView>(R.id.tvInfoServiceStatus).apply {
            text = row.status
            setTextColor(toneColor)
        }
        view.findViewById<TextView>(R.id.tvInfoServiceIdentity).apply {
            text = row.identity.orEmpty()
            visibility = if (row.identity.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.tvInfoServiceDetail).text = row.detail
        // A focusable ViewGroup with an explicit contentDescription is announced from that string
        // ALONE — a screen reader never descends into the child TextViews — so the identity has to be
        // spelled out here or the receiver/renderer name is silent. The dot is decorative; the status
        // label carries the same meaning in words.
        view.contentDescription = listOfNotNull(
            "${row.title}, ${row.status}.",
            row.identity?.takeIf { it.isNotBlank() }?.let { "$it." },
            row.detail,
        ).joinToString(" ")
        view.setOnClickListener { onOpen(row.settingsTab) }
    }

    private fun bindFeatureRow(
        activity: HomeActivity,
        view: View,
        row: InfoFeatureRow,
        onOpen: (SettingsTabKey) -> Unit,
    ) {
        view.findViewById<ImageView>(R.id.ivInfoFeatureIcon).apply {
            setImageResource(iconFor(row.id))
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.ink))
        }
        view.findViewById<TextView>(R.id.tvInfoFeatureTitle).text = row.title
        val toneColor = ContextCompat.getColor(activity, toneColorRes(row.tone))
        view.findViewById<ImageView>(R.id.ivInfoFeatureMark).apply {
            visibility = if (row.tone == InfoTone.POSITIVE) View.VISIBLE else View.GONE
            imageTintList = ColorStateList.valueOf(toneColor)
        }
        view.findViewById<TextView>(R.id.tvInfoFeatureStatus).apply {
            text = row.status
            setTextColor(toneColor)
        }
        view.findViewById<TextView>(R.id.tvInfoFeatureDetail).text = row.detail
        view.contentDescription = "${row.title}, ${row.status}. ${row.detail}"
        view.setOnClickListener { onOpen(row.settingsTab) }
    }

    private fun toneColorRes(tone: InfoTone): Int = when (tone) {
        InfoTone.POSITIVE -> R.color.dot_green
        InfoTone.PENDING -> R.color.dot_amber
        InfoTone.NEGATIVE -> R.color.dot_red
        InfoTone.NEUTRAL -> R.color.dot_grey
    }

    private fun iconFor(id: InfoServiceId): Int = when (id) {
        // Deliberately the neutral note, not a Spotify mark: the page ships no third-party brand art.
        InfoServiceId.SPOTIFY -> R.drawable.ic_music_note
        InfoServiceId.DLNA -> R.drawable.ic_mdi_dlna
        InfoServiceId.REMOTE_CONTROL -> R.drawable.ic_mdi_remote
    }

    private fun iconFor(id: InfoFeatureId): Int = when (id) {
        InfoFeatureId.HOME_ASSISTANT -> R.drawable.ic_mdi_home_assistant
        InfoFeatureId.IMMICH_SLIDESHOW -> R.drawable.ic_mdi_image
    }

    /** The only non-OK value the UPnP transport status ever carries. */
    private const val TRANSPORT_ERROR = "ERROR_OCCURRED"
}
