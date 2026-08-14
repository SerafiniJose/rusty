package dev.rusty.app

import dev.rusty.app.renderer.RendererStatus
import dev.rusty.app.renderer.RendererTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The "Services & status" decision layer. The failures worth pinning are all the same shape: a row
 * that comforts instead of informs. A service that is starting, that failed, or that is bound with no
 * routable address must never be counted as running; a missing notification permission must not read
 * as a deliberate "Off"; and a configured integration must never inflate the service count.
 *
 * The tab assertions matter for a second reason: [SettingsSheet] silently falls back to its first tab
 * when asked for one that is not currently shown, so a row for a headless DLNA service (screen feature
 * off) has to resolve to General deliberately rather than appear to open a tab that does not exist.
 */
class InfoOverviewReducerTest {

    private val allTabs = setOf(
        SettingsTabKey.GENERAL,
        SettingsTabKey.SCREENSAVER,
        SettingsTabKey.SLIDESHOW,
        SettingsTabKey.DLNA_PLAYER,
        SettingsTabKey.SPOTIFY,
        SettingsTabKey.HOME_ASSISTANT,
    )
    private val minimalTabs = setOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.SPOTIFY)

    private fun spotify(
        service: ReceiverServiceState = ReceiverServiceState.RUNNING,
        status: String = "Waiting",
        receiverName: String = "Living Room",
        sessionName: String? = null,
        bitrateKbps: Int = 320,
    ) = InfoSpotifyInput(service, status, receiverName, sessionName, bitrateKbps)

    private fun dlna(
        status: RendererStatus = RendererStatus.STOPPED,
        descriptionUrl: String? = null,
        rendererName: String = "Rusty Media Player",
        transport: RendererTransport? = null,
        transportError: Boolean = false,
        trackTitle: String? = null,
    ) = InfoDlnaInput(status, descriptionUrl, rendererName, transport, transportError, trackTitle)

    private fun control(
        state: ControlServerStatus.State = ControlServerStatus.State.Stopped,
        enabled: Boolean = false,
    ) = InfoControlInput(state, enabled)

    private fun ha(
        enabled: Boolean = false,
        configuredUrl: String? = null,
        hasOriginToken: Boolean = false,
        discovery: HaDiscovery = HaDiscovery.Idle,
        accountName: String? = null,
        selectedDashboards: Int = 0,
    ) = InfoHaInput(enabled, configuredUrl, hasOriginToken, discovery, accountName, selectedDashboards)

    private fun immich(
        enabled: Boolean = false,
        configured: Boolean = false,
        verified: Boolean = false,
        lastVerifyFailed: Boolean = false,
        serverUrl: String? = null,
        accountName: String? = null,
        albums: Int = 0,
        people: Int = 0,
        tags: Int = 0,
    ) = InfoImmichInput(
        enabled, configured, verified, lastVerifyFailed, serverUrl, accountName, albums, people, tags,
    )

    // ---- Spotify Connect row -------------------------------------------------

    @Test fun spotifyRunningIdleListensWithBitrate() {
        val row = InfoOverviewReducer.spotifyRow(spotify(), allTabs)
        assertEquals("Running", row.status)
        assertEquals("Living Room", row.identity)
        assertEquals("Listening for Spotify · 320 kbps", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
        assertEquals(SettingsTabKey.SPOTIFY, row.settingsTab)
    }

    @Test fun spotifyPlayingNamesTheControllingListener() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(status = "Playing", sessionName = "Marco"), allTabs,
        )
        assertEquals("Playing", row.status)
        assertEquals("Playing · Marco controlling playback", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
    }

    /** The display name arrives on a delayed second snapshot; the first frame must still say something. */
    @Test fun spotifyPlayingWithoutAProfileFallsBackToBitrate() {
        val row = InfoOverviewReducer.spotifyRow(spotify(status = "Playing"), allTabs)
        assertEquals("Playing · 320 kbps", row.detail)
    }

    @Test fun spotifyPausedStaysPositive() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(status = "Paused", sessionName = "Marco"), allTabs,
        )
        assertEquals("Paused", row.status)
        assertEquals("Paused · Marco controlling playback", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
    }

    @Test fun spotifyConnectedButNotPlayingNamesTheListener() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(status = "Connected", sessionName = "Marco"), allTabs,
        )
        assertEquals("Running", row.status)
        assertEquals("Connected · Marco controlling playback", row.detail)
    }

    @Test fun spotifyStartingIsPendingNotRunning() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.STARTING, status = "Starting"), allTabs,
        )
        assertEquals("Starting…", row.status)
        assertEquals(InfoTone.PENDING, row.tone)
    }

    @Test fun spotifyFailedNeedsAttention() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.FAILED, status = "Error"), allTabs,
        )
        assertEquals("Needs attention", row.status)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    /**
     * A playback error carries no service transition, so the service is still RUNNING. Reading only
     * the service would paint a receiver that cannot play anything as healthy green.
     */
    @Test fun spotifyPlaybackErrorWhileServiceRunningStillNeedsAttention() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.RUNNING, status = "Error"), allTabs,
        )
        assertEquals("Needs attention", row.status)
        assertEquals("Playback error", row.detail)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    /**
     * "Unavailable" is a per-track content restriction — most often a failed preload of the NEXT
     * track, which librespot does not skip past, so the status can sit there for the rest of the
     * song. The receiver stays connected and discoverable the whole time, so painting the row red
     * would both misinform and drop the header's running count over something nobody can act on.
     */
    @Test fun spotifyTrackUnavailableIsNotAServiceFault() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.RUNNING, status = "Unavailable"), allTabs,
        )
        assertEquals("Running", row.status)
        assertEquals("Track unavailable", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
    }

    /**
     * PERMISSION_DENIED is recorded as service = STOPPED, not FAILED. Keying off the service alone
     * would hide an actionable problem behind a grey "Off".
     */
    @Test fun spotifyMissingNotificationPermissionIsNotAPlainOff() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.STOPPED, status = "Permission needed"), allTabs,
        )
        assertEquals("Needs attention", row.status)
        assertEquals("Notification permission needed", row.detail)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    @Test fun spotifyStoppedIsOff() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.STOPPED, status = "Off"), allTabs,
        )
        assertEquals("Off", row.status)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    /** The store starts at UNKNOWN after a process restart: runtime truth is not established yet. */
    @Test fun spotifyUnknownIsUnavailableNotOff() {
        val row = InfoOverviewReducer.spotifyRow(
            spotify(service = ReceiverServiceState.UNKNOWN, status = "Waiting"), allTabs,
        )
        assertEquals("Unavailable", row.status)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    @Test fun spotifyBlankReceiverNameYieldsNoIdentityLine() {
        val row = InfoOverviewReducer.spotifyRow(spotify(receiverName = "  "), allTabs)
        assertNull(row.identity)
    }

    // ---- DLNA Player row -----------------------------------------------------

    @Test fun dlnaReadyShowsTheHostFromThePublishersOwnUrl() {
        val row = InfoOverviewReducer.dlnaRow(
            dlna(
                status = RendererStatus.RUNNING,
                descriptionUrl = "http://192.168.1.40:49152/upnp/device.xml",
            ),
            allTabs,
        )
        assertEquals("Running", row.status)
        assertEquals("Rusty Media Player", row.identity)
        assertEquals("Ready · 192.168.1.40", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
        assertEquals(SettingsTabKey.DLNA_PLAYER, row.settingsTab)
    }

    /**
     * Booted before Wi-Fi: the HTTP listener is up but there is no site-local IPv4 to advertise. The
     * runtime keeps publishing RUNNING with a null url on purpose — that is amber "Waiting for
     * network", never red, and never a synthesized 0.0.0.0.
     */
    @Test fun dlnaRunningWithNoAddressIsWaitingForNetworkNotFailed() {
        val row = InfoOverviewReducer.dlnaRow(
            dlna(status = RendererStatus.RUNNING, descriptionUrl = null), allTabs,
        )
        assertEquals("Waiting for network", row.status)
        assertEquals(InfoTone.PENDING, row.tone)
    }

    @Test fun dlnaPlayingNamesTheTrack() {
        val row = InfoOverviewReducer.dlnaRow(
            dlna(
                status = RendererStatus.RUNNING,
                descriptionUrl = "http://192.168.1.40:49152/upnp/device.xml",
                transport = RendererTransport.PLAYING,
                trackTitle = "Windmills",
            ),
            allTabs,
        )
        assertEquals("Playing", row.status)
        assertEquals("Playing · Windmills", row.detail)
    }

    @Test fun dlnaPausedWithoutMetadataDoesNotAppendASeparator() {
        val row = InfoOverviewReducer.dlnaRow(
            dlna(
                status = RendererStatus.RUNNING,
                descriptionUrl = "http://192.168.1.40:49152/upnp/device.xml",
                transport = RendererTransport.PAUSED_PLAYBACK,
            ),
            allTabs,
        )
        assertEquals("Paused", row.detail)
    }

    @Test fun dlnaTransportErrorNeedsAttention() {
        val row = InfoOverviewReducer.dlnaRow(
            dlna(
                status = RendererStatus.RUNNING,
                descriptionUrl = "http://192.168.1.40:49152/upnp/device.xml",
                transport = RendererTransport.PLAYING,
                transportError = true,
            ),
            allTabs,
        )
        assertEquals("Needs attention", row.status)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    @Test fun dlnaStartingIsPending() {
        val row = InfoOverviewReducer.dlnaRow(dlna(status = RendererStatus.STARTING), allTabs)
        assertEquals("Starting…", row.status)
        assertEquals(InfoTone.PENDING, row.tone)
    }

    @Test fun dlnaFailedNeedsAttention() {
        val row = InfoOverviewReducer.dlnaRow(dlna(status = RendererStatus.FAILED), allTabs)
        assertEquals("Needs attention", row.status)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    @Test fun dlnaStoppedIsOff() {
        val row = InfoOverviewReducer.dlnaRow(dlna(status = RendererStatus.STOPPED), allTabs)
        assertEquals("Off", row.status)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    /**
     * The renderer service runs headless with the DLNA screen feature off, and then its settings tab
     * does not exist. Falling through to General is deliberate — General owns the feature toggle.
     */
    @Test fun dlnaRowFallsBackToGeneralWhenItsTabIsHidden() {
        val row = InfoOverviewReducer.dlnaRow(dlna(status = RendererStatus.RUNNING), minimalTabs)
        assertEquals(SettingsTabKey.GENERAL, row.settingsTab)
    }

    // ---- Remote Control row --------------------------------------------------

    @Test fun controlRunningShowsTheReachableUrl() {
        val row = InfoOverviewReducer.controlRow(
            control(ControlServerStatus.State.Running("http://192.168.1.40:8765"), enabled = true),
            allTabs,
        )
        assertEquals("Running", row.status)
        assertEquals("Remote web interface", row.identity)
        assertEquals("http://192.168.1.40:8765", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
    }

    @Test fun controlBoundWithNoAddressIsWaitingForNetwork() {
        val row = InfoOverviewReducer.controlRow(
            control(ControlServerStatus.State.Running(""), enabled = true), allTabs,
        )
        assertEquals("Waiting for network", row.status)
        assertEquals(InfoTone.PENDING, row.tone)
    }

    @Test fun controlOffOffersTheSetupHint() {
        val row = InfoOverviewReducer.controlRow(control(), allTabs)
        assertEquals("Off", row.status)
        assertEquals("Enable it in General settings", row.detail)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    /**
     * startForegroundService is asynchronous: Starting is only published once onStartCommand runs, so
     * an enabled-but-still-Stopped server is a real window that must not read as a deliberate "Off".
     */
    @Test fun controlStoppedWhileEnabledReadsAsStarting() {
        val row = InfoOverviewReducer.controlRow(control(enabled = true), allTabs)
        assertEquals("Starting…", row.status)
        assertEquals(InfoTone.PENDING, row.tone)
    }

    /** The raw bind exception belongs in the settings panel, not in the overview. */
    @Test fun controlFailedStaysConciseAndHidesTheRawMessage() {
        val row = InfoOverviewReducer.controlRow(
            control(ControlServerStatus.State.Failed("could not bind port 8765"), enabled = true),
            allTabs,
        )
        assertEquals("Needs attention", row.status)
        assertEquals("The server couldn't start", row.detail)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    @Test fun controlRowAlwaysRoutesToGeneral() {
        assertEquals(
            SettingsTabKey.GENERAL,
            InfoOverviewReducer.controlRow(control(), allTabs).settingsTab,
        )
    }

    // ---- Header summary ------------------------------------------------------

    @Test fun summaryUsesTheSimpleFormWhenNothingIsTransitional() {
        val overview = InfoOverviewReducer.reduce(
            spotify(),
            dlna(status = RendererStatus.RUNNING, descriptionUrl = "http://192.168.1.40:49152/upnp/device.xml"),
            control(),
            ha(),
            immich(),
            allTabs,
        )
        assertEquals("2 services running · 1 off", overview.summary)
    }

    @Test fun summaryNamesEveryBucketOnceSomethingIsStarting() {
        val overview = InfoOverviewReducer.reduce(
            spotify(),
            dlna(status = RendererStatus.RUNNING, descriptionUrl = "http://192.168.1.40:49152/upnp/device.xml"),
            control(ControlServerStatus.State.Starting, enabled = true),
            ha(),
            immich(),
            allTabs,
        )
        assertEquals("2 running · 1 starting", overview.summary)
    }

    @Test fun summaryNamesAFailureRatherThanHidingItInTheOffCount() {
        val overview = InfoOverviewReducer.reduce(
            spotify(),
            dlna(status = RendererStatus.STOPPED),
            control(ControlServerStatus.State.Failed("could not bind port 8765"), enabled = true),
            ha(),
            immich(),
            allTabs,
        )
        assertEquals("1 running · 1 off · 1 needs attention", overview.summary)
    }

    @Test fun summarySingularizesOneService() {
        val overview = InfoOverviewReducer.reduce(
            spotify(service = ReceiverServiceState.STOPPED, status = "Off"),
            dlna(status = RendererStatus.STOPPED),
            control(ControlServerStatus.State.Running("http://192.168.1.40:8765"), enabled = true),
            ha(),
            immich(),
            allTabs,
        )
        assertEquals("1 service running · 2 off", overview.summary)
    }

    @Test fun summaryWithEverythingOffOmitsAZeroRunningSegment() {
        val overview = InfoOverviewReducer.reduce(
            spotify(service = ReceiverServiceState.STOPPED, status = "Off"),
            dlna(status = RendererStatus.STOPPED),
            control(),
            ha(),
            immich(),
            allTabs,
        )
        assertEquals("3 services off", overview.summary)
    }

    /** A connected integration is not a running service; it must not move the header count. */
    @Test fun connectedFeaturesNeverChangeTheServiceCount() {
        val bare = InfoOverviewReducer.reduce(spotify(), dlna(), control(), ha(), immich(), allTabs)
        val wired = InfoOverviewReducer.reduce(
            spotify(),
            dlna(),
            control(),
            ha(enabled = true, configuredUrl = "http://homeassistant.local:8123", hasOriginToken = true),
            immich(enabled = true, configured = true, verified = true, serverUrl = "https://photos.example.com"),
            allTabs,
        )
        assertEquals(bare.summary, wired.summary)
        assertEquals(3, wired.services.size)
        assertEquals(2, wired.features.size)
    }

    @Test fun servicesAlwaysAppearInSpecOrder() {
        val overview = InfoOverviewReducer.reduce(spotify(), dlna(), control(), ha(), immich(), allTabs)
        assertEquals(
            listOf(InfoServiceId.SPOTIFY, InfoServiceId.DLNA, InfoServiceId.REMOTE_CONTROL),
            overview.services.map { it.id },
        )
    }

    // ---- Home Assistant feature row -----------------------------------------

    @Test fun haIsOmittedWhenNeitherEnabledNorConfigured() {
        assertNull(InfoOverviewReducer.haRow(ha(), allTabs))
    }

    @Test fun haConnectedByTokenNamesTheAccountAndDashboards() {
        val row = InfoOverviewReducer.haRow(
            ha(
                enabled = true,
                configuredUrl = "http://homeassistant.local:8123",
                hasOriginToken = true,
                accountName = "Marco",
                selectedDashboards = 3,
            ),
            allTabs,
        )!!
        assertEquals("Connected", row.status)
        assertEquals("Signed in as Marco · 3 dashboards", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
        assertEquals(SettingsTabKey.HOME_ASSISTANT, row.settingsTab)
    }

    /**
     * Discovery only ever runs from inside the HA fragment, so a cold launch that never opened the
     * feature sits at Idle forever. With an origin-matched refresh token that is connected, not
     * disconnected — this is the whole reason the token half of the rule exists.
     */
    @Test fun haIdleDiscoveryWithATokenIsStillConnected() {
        val row = InfoOverviewReducer.haRow(
            ha(enabled = true, configuredUrl = "http://homeassistant.local:8123", hasOriginToken = true),
            allTabs,
        )!!
        assertEquals("Connected", row.status)
        assertEquals("homeassistant.local", row.detail)
    }

    @Test fun haLoadedDiscoveryWithoutATokenIsConnected() {
        val row = InfoOverviewReducer.haRow(
            ha(
                enabled = true,
                configuredUrl = "http://homeassistant.local:8123",
                discovery = HaDiscovery.Loaded(emptyList()),
                selectedDashboards = 1,
            ),
            allTabs,
        )!!
        assertEquals("Connected", row.status)
        assertEquals("homeassistant.local · 1 dashboard", row.detail)
    }

    /** Refreshing carries no payload, so the previously known summary has to survive the amber state. */
    @Test fun haRefreshingKeepsTheLastKnownSummary() {
        val row = InfoOverviewReducer.haRow(
            ha(
                enabled = true,
                configuredUrl = "http://homeassistant.local:8123",
                hasOriginToken = true,
                discovery = HaDiscovery.Refreshing,
                accountName = "Marco",
                selectedDashboards = 2,
            ),
            allTabs,
        )!!
        assertEquals("Refreshing…", row.status)
        assertEquals("Signed in as Marco · 2 dashboards", row.detail)
        assertEquals(InfoTone.PENDING, row.tone)
    }

    @Test fun haDiscoveryErrorIsAConnectionIssue() {
        val row = InfoOverviewReducer.haRow(
            ha(
                enabled = true,
                configuredUrl = "http://homeassistant.local:8123",
                discovery = HaDiscovery.Error("Log in to Home Assistant, then tap Refresh."),
            ),
            allTabs,
        )!!
        assertEquals("Connection issue", row.status)
        assertEquals("Log in to Home Assistant, then tap Refresh.", row.detail)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    @Test fun haEnabledButUnconfiguredNeedsSetup() {
        val row = InfoOverviewReducer.haRow(ha(enabled = true), allTabs)!!
        assertEquals("Needs setup", row.status)
        assertEquals("Add your Home Assistant server", row.detail)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    @Test fun haConfiguredButSignedOutPointsAtTheServer() {
        val row = InfoOverviewReducer.haRow(
            ha(enabled = true, configuredUrl = "http://homeassistant.local:8123"), allTabs,
        )!!
        assertEquals("Needs setup", row.status)
        assertEquals("Sign in at homeassistant.local", row.detail)
    }

    @Test fun haRowFallsBackToGeneralWhenTheFeatureIsDisabled() {
        val row = InfoOverviewReducer.haRow(
            ha(configuredUrl = "http://homeassistant.local:8123", hasOriginToken = true), minimalTabs,
        )!!
        assertEquals(SettingsTabKey.GENERAL, row.settingsTab)
    }

    // ---- Immich Slideshow feature row ---------------------------------------

    @Test fun immichIsOmittedWhenNeitherEnabledNorConfigured() {
        assertNull(InfoOverviewReducer.immichRow(immich(), allTabs))
    }

    @Test fun immichVerifiedNamesTheAccountAndTheFilters() {
        val row = InfoOverviewReducer.immichRow(
            immich(
                enabled = true,
                configured = true,
                verified = true,
                serverUrl = "https://photos.example.com",
                accountName = "Family photos",
                albums = 2,
            ),
            allTabs,
        )!!
        assertEquals("Connected", row.status)
        assertEquals("Family photos · 2 albums", row.detail)
        assertEquals(InfoTone.POSITIVE, row.tone)
        assertEquals(SettingsTabKey.SLIDESHOW, row.settingsTab)
    }

    @Test fun immichWithNoFiltersSaysAllPhotos() {
        val row = InfoOverviewReducer.immichRow(
            immich(
                enabled = true,
                configured = true,
                verified = true,
                serverUrl = "https://photos.example.com",
                accountName = "Marco",
            ),
            allTabs,
        )!!
        assertEquals("Marco · All photos", row.detail)
    }

    @Test fun immichMixedFiltersJoinEveryNonEmptyCategory() {
        assertEquals("2 albums · 1 person · 3 tags", InfoOverviewReducer.filtersLabel(2, 1, 3))
    }

    /**
     * A scoped Immich key legitimately 403s on /api/users/me, so a verified connection with no name is
     * normal. The host stands in rather than blocking the row out of its green state.
     */
    @Test fun immichVerifiedWithoutAnIdentityFallsBackToTheHost() {
        val row = InfoOverviewReducer.immichRow(
            immich(enabled = true, configured = true, verified = true, serverUrl = "https://photos.example.com"),
            allTabs,
        )!!
        assertEquals("photos.example.com · All photos", row.detail)
    }

    @Test fun immichEnabledButUnconfiguredNeedsSetup() {
        val row = InfoOverviewReducer.immichRow(immich(enabled = true), allTabs)!!
        assertEquals("Needs setup", row.status)
        assertEquals("Add your Immich server and API key", row.detail)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    /** Configured but never verified is not the same as broken — grey, not red. */
    @Test fun immichConfiguredButNeverVerifiedStaysGrey() {
        val row = InfoOverviewReducer.immichRow(
            immich(enabled = true, configured = true, serverUrl = "https://photos.example.com"), allTabs,
        )!!
        assertEquals("Needs setup", row.status)
        assertEquals("Key not verified yet", row.detail)
        assertEquals(InfoTone.NEUTRAL, row.tone)
    }

    @Test fun immichWhoseLastVerificationFailedTurnsRed() {
        val row = InfoOverviewReducer.immichRow(
            immich(
                enabled = true,
                configured = true,
                lastVerifyFailed = true,
                serverUrl = "https://photos.example.com",
            ),
            allTabs,
        )!!
        assertEquals("Connection issue", row.status)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    /**
     * Re-saving an UNCHANGED connection does not clear the stored verified flag, so a server that
     * verified once and has since gone down carries both flags at once. The fresh failure has to
     * win, or the page reports a green "Connected" for a slideshow that cannot load a photo.
     */
    @Test fun immichFailureAfterAPreviousSuccessOutranksTheStaleVerifiedFlag() {
        val row = InfoOverviewReducer.immichRow(
            immich(
                enabled = true,
                configured = true,
                verified = true,
                lastVerifyFailed = true,
                serverUrl = "https://photos.example.com",
                accountName = "Marco",
            ),
            allTabs,
        )!!
        assertEquals("Connection issue", row.status)
        assertEquals(InfoTone.NEGATIVE, row.tone)
    }

    /** Configured while the Slideshow feature is off: the tab is hidden, so General it is. */
    @Test fun immichRowFallsBackToGeneralWhenTheSlideshowTabIsHidden() {
        val row = InfoOverviewReducer.immichRow(
            immich(configured = true, verified = true, serverUrl = "https://photos.example.com"),
            minimalTabs,
        )!!
        assertEquals(SettingsTabKey.GENERAL, row.settingsTab)
    }

    // ---- Host parsing --------------------------------------------------------

    @Test fun hostOfStripsSchemePortAndPath() {
        assertEquals(
            "192.168.1.40",
            InfoOverviewReducer.hostOf("http://192.168.1.40:49152/upnp/device.xml"),
        )
        assertEquals("photos.example.com", InfoOverviewReducer.hostOf("https://photos.example.com/"))
        assertEquals("homeassistant.local", InfoOverviewReducer.hostOf("http://homeassistant.local:8123"))
    }

    /** A bracketed IPv6 literal is all colons: splitting on the first one would emit "[2001". */
    @Test fun hostOfKeepsAnIpv6LiteralIntact() {
        assertEquals(
            "[2001:db8::1]",
            InfoOverviewReducer.hostOf("http://[2001:db8::1]:8123/lovelace"),
        )
        assertEquals("[fe80::1]", InfoOverviewReducer.hostOf("https://[fe80::1]"))
    }

    /** Null in, null out — the page never invents an address the publisher does not have. */
    @Test fun hostOfNeverInventsAnAddress() {
        assertNull(InfoOverviewReducer.hostOf(null))
        assertNull(InfoOverviewReducer.hostOf(""))
        assertNull(InfoOverviewReducer.hostOf("   "))
        assertNull(InfoOverviewReducer.hostOf("http://"))
    }
}
