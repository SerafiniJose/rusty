package dev.rusty.app

import dev.rusty.app.renderer.RendererStatus
import dev.rusty.app.renderer.RendererTransport

/**
 * The "Services & status" page's whole decision layer, pulled out of the view for the same reason
 * [ControlStatusLine] and [SlideshowSummaries] were: turning five independent runtime publishers into
 * one page of rows is an ordinary decision with no `android.*` dependency, and it is the part most
 * likely to render a comforting lie — a service that is starting, failed, or bound-without-an-address
 * must never be counted as running, and a configured integration must never inflate the service count.
 *
 * Everything here is pure: primitives and plain enums in, immutable data out, literal strings (this app
 * has no string resources). Colours are NOT decided here — the view maps [InfoTone] to `R.color.dot_*`,
 * so the reducer stays JVM-testable without touching the stubbed `android.jar`.
 */

/** Row severity. The view maps this to a dot colour; every state also carries a text label, so colour
 *  is never the only signal. */
enum class InfoTone { POSITIVE, PENDING, NEGATIVE, NEUTRAL }

/** The three foreground services the page always reports, in display order. */
enum class InfoServiceId { SPOTIFY, DLNA, REMOTE_CONTROL }

/** The integrations the page reports when they are enabled or configured. */
enum class InfoFeatureId { HOME_ASSISTANT, IMMICH_SLIDESHOW }

data class InfoServiceRow(
    val id: InfoServiceId,
    val title: String,
    val status: String,
    val identity: String?,
    val detail: String,
    val tone: InfoTone,
    val settingsTab: SettingsTabKey,
)

data class InfoFeatureRow(
    val id: InfoFeatureId,
    val title: String,
    val status: String,
    val detail: String,
    val tone: InfoTone,
    val settingsTab: SettingsTabKey,
)

data class InfoOverview(
    val summary: String,
    val services: List<InfoServiceRow>,
    val features: List<InfoFeatureRow>,
)

/**
 * Spotify Connect runtime facts. [service] is `ReceiverStateStore.snapshot.service`, the app's only
 * published liveness truth; [status] is the untyped `ReceiverDashboardState.status` string, which is a
 * SEPARATE axis — `RUNNING` with `status == "Error"` is reachable (a playback error carries no service
 * transition), and `"Permission needed"` arrives with `service == STOPPED` rather than `FAILED`.
 * [bitrateKbps] is the CONFIGURED bitrate (there is none in the snapshot); it can briefly differ from the
 * running native session's until it cycles.
 */
data class InfoSpotifyInput(
    val service: ReceiverServiceState,
    val status: String,
    val receiverName: String,
    val sessionName: String?,
    val bitrateKbps: Int,
)

/**
 * DLNA renderer runtime facts. [featureEnabled] is the `dlna_feature_enabled` preference, and it
 * outranks [status]: the feature toggle owns the renderer service, and `stopService` is asynchronous,
 * so the last RUNNING snapshot outlives a toggle-off. [descriptionUrl] is the publisher's own UPnP
 * description URL (`http://host:port/upnp/device.xml`) or null — null under [RendererStatus.RUNNING]
 * means "bound, no routable address", never a failure. [rendererName] must already have the
 * persisted-name fallback applied: the runtime reports `""` whenever no backend is attached.
 */
data class InfoDlnaInput(
    val featureEnabled: Boolean,
    val status: RendererStatus,
    val descriptionUrl: String?,
    val rendererName: String,
    val transport: RendererTransport?,
    val transportError: Boolean,
    val trackTitle: String?,
)

/**
 * Remote Control runtime facts. [enabled] is the `control_api_enabled` preference, and it is load-bearing:
 * `startForegroundService` is asynchronous, so `Stopped` while enabled is a real transient window that
 * must read as starting, not as a deliberate "Off".
 */
data class InfoControlInput(
    val state: ControlServerStatus.State,
    val enabled: Boolean,
)

/**
 * Home Assistant connection facts. Connected means an origin-matched refresh token exists OR live
 * discovery is loaded — the token half is the only cold-start signal, because discovery only ever runs
 * from inside the HA fragment, so [HaDiscovery.Idle] with a valid token is a normal connected state.
 * [accountName] is the persisted name (`ha_account_name`); it survives the restart that the in-memory
 * `HaDiscovery.Loaded.account` does not.
 */
data class InfoHaInput(
    val enabled: Boolean,
    val configuredUrl: String?,
    val hasOriginToken: Boolean,
    val discovery: HaDiscovery,
    val accountName: String?,
    val selectedDashboards: Int,
)

/**
 * Immich Slideshow connection facts, all read from preferences (the API key itself never reaches here —
 * [configured] is `SlideshowSettings.config(...) != null`). [verified] is the stored key having actually
 * authenticated on its last Save; [lastVerifyFailed] separates "verification explicitly failed" (red)
 * from "never verified" (grey), which a single verified flag cannot express.
 */
data class InfoImmichInput(
    val enabled: Boolean,
    val configured: Boolean,
    val verified: Boolean,
    val lastVerifyFailed: Boolean,
    val serverUrl: String?,
    val accountName: String?,
    val albums: Int,
    val people: Int,
    val tags: Int,
)

object InfoOverviewReducer {

    // Status labels. Kept as constants because the header summary and the rows must agree on them.
    const val RUNNING = "Running"
    const val PLAYING = "Playing"
    const val PAUSED = "Paused"
    const val STARTING = "Starting…"
    const val WAITING_FOR_NETWORK = "Waiting for network"
    const val NEEDS_ATTENTION = "Needs attention"
    const val OFF = "Off"
    const val UNAVAILABLE = "Unavailable"

    const val CONNECTED = "Connected"
    const val DISABLED = "Disabled"
    const val REFRESHING = "Refreshing…"
    const val NEEDS_SETUP = "Needs setup"
    const val CONNECTION_ISSUE = "Connection issue"

    /** Every feature's enable toggle lives in the General panel, so a disabled row points there. */
    private const val TURN_ON_DETAIL = "Turn it on in General settings"

    /**
     * [availableTabs] is the settings sheet's CURRENT tab list. A tab that is not in it would make
     * `SettingsSheet.show` fall back to index 0 silently, so rows resolve to General deliberately
     * instead — General is where every feature's enable toggle and the Remote Control switch live,
     * so the user can still act. This is reachable in normal use: every disabled integration loses
     * its own tab while staying configured, and its row still has to lead somewhere.
     */
    fun reduce(
        spotify: InfoSpotifyInput,
        dlna: InfoDlnaInput,
        control: InfoControlInput,
        ha: InfoHaInput,
        immich: InfoImmichInput,
        availableTabs: Set<SettingsTabKey>,
    ): InfoOverview {
        val services = listOf(
            spotifyRow(spotify, availableTabs),
            dlnaRow(dlna, availableTabs),
            controlRow(control, availableTabs),
        )
        val features = listOfNotNull(
            haRow(ha, availableTabs),
            immichRow(immich, availableTabs),
        )
        return InfoOverview(summary(services), services, features)
    }

    /**
     * Counts runtime states only — a connected feature never appears here. The simple form is used
     * when nothing is transitional or broken; otherwise every non-empty bucket is named, because
     * "2 services running · 1 off" would hide the one service that actually needs the user.
     */
    fun summary(services: List<InfoServiceRow>): String {
        if (services.isEmpty()) return ""
        val running = services.count { it.tone == InfoTone.POSITIVE }
        val starting = services.count { it.tone == InfoTone.PENDING }
        val attention = services.count { it.tone == InfoTone.NEGATIVE }
        val off = services.count { it.tone == InfoTone.NEUTRAL }
        if (starting == 0 && attention == 0) {
            return when {
                off == 0 -> "$running ${services(running)} running"
                running == 0 -> "$off ${services(off)} off"
                else -> "$running ${services(running)} running · $off off"
            }
        }
        return buildList {
            if (running > 0) add("$running running")
            if (off > 0) add("$off off")
            if (starting > 0) add("$starting starting")
            if (attention > 0) add("$attention needs attention")
        }.joinToString(" · ")
    }

    fun spotifyRow(input: InfoSpotifyInput, availableTabs: Set<SettingsTabKey>): InfoServiceRow {
        val (status, detail, tone) = when {
            // PERMISSION_DENIED lands as service = STOPPED, so keying off the service alone would
            // paint a missing notification permission as a deliberate grey "Off".
            input.status == STATUS_PERMISSION_NEEDED ->
                Triple(NEEDS_ATTENTION, "Notification permission needed", InfoTone.NEGATIVE)
            // A native start failure leaves the foreground service alive; FAILED is still "needs attention".
            input.service == ReceiverServiceState.FAILED ->
                Triple(NEEDS_ATTENTION, "The receiver couldn't start", InfoTone.NEGATIVE)
            input.service == ReceiverServiceState.STARTING ->
                Triple(STARTING, "Starting the receiver", InfoTone.PENDING)
            input.service == ReceiverServiceState.RUNNING -> spotifyRunning(input)
            input.service == ReceiverServiceState.STOPPED ->
                Triple(OFF, "Receiver stopped · start it from Spotify settings", InfoTone.NEUTRAL)
            // UNKNOWN is the store's value at process start: runtime truth is not established yet.
            else -> Triple(UNAVAILABLE, "Receiver state not known yet", InfoTone.NEUTRAL)
        }
        return InfoServiceRow(
            id = InfoServiceId.SPOTIFY,
            title = "Spotify Connect",
            status = status,
            identity = input.receiverName.takeIf { it.isNotBlank() },
            detail = detail,
            tone = tone,
            settingsTab = tabOr(SettingsTabKey.SPOTIFY, availableTabs),
        )
    }

    private fun spotifyRunning(input: InfoSpotifyInput): Triple<String, String, InfoTone> {
        val who = input.sessionName?.takeIf { it.isNotBlank() }
        return when (input.status) {
            STATUS_PLAYING -> Triple(PLAYING, controlled(PLAYING, who, input.bitrateKbps), InfoTone.POSITIVE)
            STATUS_PAUSED -> Triple(PAUSED, controlled(PAUSED, who, input.bitrateKbps), InfoTone.POSITIVE)
            STATUS_ERROR -> Triple(NEEDS_ATTENTION, "Playback error", InfoTone.NEGATIVE)
            // "Unavailable" is a per-TRACK content restriction (a region-locked or withdrawn track,
            // typically a failed preload of the NEXT track), not a service fault: the receiver stays
            // connected and discoverable throughout. Reporting it red would also drop the header's
            // running count over something the user cannot act on.
            STATUS_UNAVAILABLE -> Triple(RUNNING, "Track unavailable", InfoTone.POSITIVE)
            else -> Triple(
                RUNNING,
                if (who != null) "Connected · $who controlling playback"
                else "Listening for Spotify · ${input.bitrateKbps} kbps",
                InfoTone.POSITIVE,
            )
        }
    }

    /** The display name arrives on a delayed second snapshot, so the nameless form must stand alone. */
    private fun controlled(word: String, who: String?, bitrateKbps: Int): String =
        if (who != null) "$word · $who controlling playback" else "$word · $bitrateKbps kbps"

    fun dlnaRow(input: InfoDlnaInput, availableTabs: Set<SettingsTabKey>): InfoServiceRow {
        // The feature toggle owns the service, so a disabled feature is reported as disabled whatever
        // the publisher last said — and the row has to route to General, because the DLNA Player tab
        // (which holds Start/Stop) is gone with the feature.
        if (!input.featureEnabled) {
            return InfoServiceRow(
                id = InfoServiceId.DLNA,
                title = "DLNA Player",
                status = DISABLED,
                identity = input.rendererName.takeIf { it.isNotBlank() },
                detail = TURN_ON_DETAIL,
                tone = InfoTone.NEUTRAL,
                settingsTab = tabOr(SettingsTabKey.GENERAL, availableTabs),
            )
        }
        val (status, detail, tone) = when (input.status) {
            RendererStatus.FAILED -> Triple(NEEDS_ATTENTION, "The player couldn't start", InfoTone.NEGATIVE)
            RendererStatus.STARTING -> Triple(STARTING, "Starting the player", InfoTone.PENDING)
            RendererStatus.STOPPED ->
                Triple(OFF, "Player stopped · start it from DLNA Player settings", InfoTone.NEUTRAL)
            RendererStatus.RUNNING -> dlnaRunning(input)
        }
        return InfoServiceRow(
            id = InfoServiceId.DLNA,
            title = "DLNA Player",
            status = status,
            identity = input.rendererName.takeIf { it.isNotBlank() },
            detail = detail,
            tone = tone,
            settingsTab = tabOr(SettingsTabKey.DLNA_PLAYER, availableTabs),
        )
    }

    private fun dlnaRunning(input: InfoDlnaInput): Triple<String, String, InfoTone> {
        val host = hostOf(input.descriptionUrl)
        return when {
            input.transportError -> Triple(NEEDS_ATTENTION, "Playback error", InfoTone.NEGATIVE)
            input.transport == RendererTransport.PLAYING ->
                Triple(PLAYING, withTrack(PLAYING, input.trackTitle), InfoTone.POSITIVE)
            input.transport == RendererTransport.PAUSED_PLAYBACK ->
                Triple(PAUSED, withTrack(PAUSED, input.trackTitle), InfoTone.POSITIVE)
            input.transport == RendererTransport.TRANSITIONING ->
                Triple(RUNNING, "Loading…", InfoTone.POSITIVE)
            // Bound with no routable address is expected (booted before Wi-Fi), never a failure — and
            // the address is only ever the publisher's own, never one this page constructs.
            host == null -> Triple(WAITING_FOR_NETWORK, "Bound, but no network address yet", InfoTone.PENDING)
            else -> Triple(RUNNING, "Ready · $host", InfoTone.POSITIVE)
        }
    }

    private fun withTrack(word: String, title: String?): String {
        val t = title?.takeIf { it.isNotBlank() } ?: return word
        return "$word · $t"
    }

    fun controlRow(input: InfoControlInput, availableTabs: Set<SettingsTabKey>): InfoServiceRow {
        val state = input.state
        val (status, detail, tone) = when {
            // Failed.message is a raw exception string; the overview stays concise and defers the
            // detail to the General settings row, which prints the message verbatim.
            state is ControlServerStatus.State.Failed ->
                Triple(NEEDS_ATTENTION, "The server couldn't start", InfoTone.NEGATIVE)
            state is ControlServerStatus.State.Starting ->
                Triple(STARTING, "Starting the server", InfoTone.PENDING)
            state is ControlServerStatus.State.Running ->
                if (state.url.isEmpty()) {
                    Triple(WAITING_FOR_NETWORK, "Bound, but no network address yet", InfoTone.PENDING)
                } else {
                    Triple(RUNNING, state.url, InfoTone.POSITIVE)
                }
            // Stopped-while-enabled is the window between syncFromPrefs and onStartCommand.
            input.enabled -> Triple(STARTING, "Starting the server", InfoTone.PENDING)
            else -> Triple(OFF, "Enable it in General settings", InfoTone.NEUTRAL)
        }
        return InfoServiceRow(
            id = InfoServiceId.REMOTE_CONTROL,
            title = "Remote Control",
            status = status,
            identity = "Remote web interface",
            detail = detail,
            tone = tone,
            // There is no Remote Control tab — the switch lives in the General panel.
            settingsTab = tabOr(SettingsTabKey.GENERAL, availableTabs),
        )
    }

    /** Null when Home Assistant is neither enabled nor configured — an unused integration is omitted. */
    fun haRow(input: InfoHaInput, availableTabs: Set<SettingsTabKey>): InfoFeatureRow? {
        if (!input.enabled && input.configuredUrl.isNullOrBlank() && !input.hasOriginToken) return null
        // The feature toggle outranks every connection fact, because none of them expire when it goes
        // off: the refresh token survives, and discovery keeps its last loaded (or failed) result. A
        // row that kept reporting those would describe a server the app is no longer using.
        if (!input.enabled) return featureRow(InfoFeatureId.HOME_ASSISTANT, "Home Assistant", availableTabs)
        val host = hostOf(input.configuredUrl)
        val discovery = input.discovery
        val connected = input.hasOriginToken || discovery is HaDiscovery.Loaded
        val name = input.accountName?.takeIf { it.isNotBlank() }
        val connectedDetail = buildList {
            add(if (name != null) "Signed in as $name" else host ?: "Home Assistant")
            if (input.selectedDashboards > 0) add(dashboardCount(input.selectedDashboards))
        }.joinToString(" · ")
        val (status, detail, tone) = when {
            // Refreshing carries no payload, so the last known summary is preserved by re-deriving it
            // from the persisted name and selection rather than from the in-flight discovery.
            discovery is HaDiscovery.Refreshing ->
                Triple(REFRESHING, if (connected) connectedDetail else "Checking the connection", InfoTone.PENDING)
            discovery is HaDiscovery.Error ->
                Triple(CONNECTION_ISSUE, discovery.reason, InfoTone.NEGATIVE)
            connected -> Triple(CONNECTED, connectedDetail, InfoTone.POSITIVE)
            else -> Triple(
                NEEDS_SETUP,
                host?.let { "Sign in at $it" } ?: "Add your Home Assistant server",
                InfoTone.NEUTRAL,
            )
        }
        return InfoFeatureRow(
            id = InfoFeatureId.HOME_ASSISTANT,
            title = "Home Assistant",
            status = status,
            detail = detail,
            tone = tone,
            settingsTab = tabOr(SettingsTabKey.HOME_ASSISTANT, availableTabs),
        )
    }

    /** Null when the Slideshow is neither enabled nor configured. */
    fun immichRow(input: InfoImmichInput, availableTabs: Set<SettingsTabKey>): InfoFeatureRow? {
        if (!input.enabled && !input.configured) return null
        // As with Home Assistant: the stored key stays verified (or stays failed) after the toggle goes
        // off, so the toggle has to be read first or the row reports on a slideshow that cannot run.
        if (!input.enabled) return featureRow(InfoFeatureId.IMMICH_SLIDESHOW, "Immich Slideshow", availableTabs)
        val host = hostOf(input.serverUrl)
        val name = input.accountName?.takeIf { it.isNotBlank() }
        val (status, detail, tone) = when {
            !input.configured ->
                Triple(NEEDS_SETUP, "Add your Immich server and API key", InfoTone.NEUTRAL)
            // Checked BEFORE [verified]: re-saving an unchanged connection does not clear the stored
            // verified flag, so a server that has since gone down would otherwise keep reporting the
            // green "Connected" from its last successful save. The most recent explicit verdict wins.
            input.lastVerifyFailed ->
                Triple(CONNECTION_ISSUE, "Couldn't reach Immich · check the server and key", InfoTone.NEGATIVE)
            // A scoped key that cannot read /api/users/me verifies without yielding a name — that is a
            // normal connected state, so the host stands in for the name rather than blocking green.
            input.verified -> Triple(
                CONNECTED,
                listOf(name ?: host ?: "Immich", filtersLabel(input.albums, input.people, input.tags))
                    .joinToString(" · "),
                InfoTone.POSITIVE,
            )
            else -> Triple(NEEDS_SETUP, "Key not verified yet", InfoTone.NEUTRAL)
        }
        return InfoFeatureRow(
            id = InfoFeatureId.IMMICH_SLIDESHOW,
            title = "Immich Slideshow",
            status = status,
            detail = detail,
            tone = tone,
            settingsTab = tabOr(SettingsTabKey.SLIDESHOW, availableTabs),
        )
    }

    /**
     * The row a switched-off integration gets: no connection facts at all, and it always opens General,
     * where the toggle that switched it off lives — the feature's own tab is hidden while it is off.
     */
    private fun featureRow(
        id: InfoFeatureId,
        title: String,
        availableTabs: Set<SettingsTabKey>,
    ): InfoFeatureRow = InfoFeatureRow(
        id = id,
        title = title,
        status = DISABLED,
        detail = TURN_ON_DETAIL,
        tone = InfoTone.NEUTRAL,
        settingsTab = tabOr(SettingsTabKey.GENERAL, availableTabs),
    )

    /** No filter in any category means the whole library. Pluralization stays in [ImmichPickerModel]. */
    fun filtersLabel(albums: Int, people: Int, tags: Int): String {
        if (albums == 0 && people == 0 && tags == 0) return "All photos"
        return buildList {
            if (albums > 0) add(ImmichPickerModel.unitCount(albums, ImmichFilterKind.ALBUMS))
            if (people > 0) add(ImmichPickerModel.unitCount(people, ImmichFilterKind.PEOPLE))
            if (tags > 0) add(ImmichPickerModel.unitCount(tags, ImmichFilterKind.TAGS))
        }.joinToString(" · ")
    }

    /**
     * The host of a publisher-supplied URL, e.g. `http://192.168.1.40:49152/upnp/device.xml` →
     * `192.168.1.40`. Null in, null out — this never invents an address.
     */
    fun hostOf(url: String?): String? {
        val authority = url
            ?.takeIf { it.isNotBlank() }
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // A bracketed IPv6 literal is full of colons, so the port can only be stripped after the
        // closing bracket — splitting on the first ':' would emit "[2001" as the host.
        if (authority.startsWith("[")) {
            return (authority.substringBefore(']') + "]").takeIf { it.length > 2 }
        }
        return authority.substringBefore(':').takeIf { it.isNotBlank() }
    }

    private fun dashboardCount(n: Int): String = "$n ${if (n == 1) "dashboard" else "dashboards"}"

    private fun services(n: Int): String = if (n == 1) "service" else "services"

    private fun tabOr(tab: SettingsTabKey, available: Set<SettingsTabKey>): SettingsTabKey =
        if (tab in available) tab else SettingsTabKey.GENERAL

    // The `ReceiverDashboardState.status` literals this reducer keys off. They are produced by
    // ReceiverDashboardStatusEvent/ReceiverDashboardPlaybackEvent; naming them here keeps the string
    // comparisons in one place instead of scattering literals through the when-ladders.
    private const val STATUS_PLAYING = "Playing"
    private const val STATUS_PAUSED = "Paused"
    private const val STATUS_ERROR = "Error"
    private const val STATUS_UNAVAILABLE = "Unavailable"
    private const val STATUS_PERMISSION_NEEDED = "Permission needed"
}
