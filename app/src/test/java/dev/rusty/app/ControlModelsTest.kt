package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlModelsTest {
    private fun snap(
        panel: ControlPanel = panel(),
        app: ControlApp = ControlApp(foreground = true, canBringForward = true),
        cameraShare: ControlCameraShare? = null,
    ) = ControlSnapshot(
        deviceId = "abc", deviceName = "Rusty Speaker", version = "2.3.0",
        screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
        volume = ControlVolume(value = 47, fixed = false),
        playing = ControlPlaying(spotify = true, dlna = false, elapsedMs = 12_345L, durationMs = 200_000L),
        slideshowEnabled = true,
        panel = panel,
        app = app,
        cameraShare = cameraShare,
    )

    private fun panel(
        active: ControlPanelId? = ControlPanelId.SPOTIFY,
        available: List<ControlPanelId> = listOf(
            ControlPanelId.SPOTIFY, ControlPanelId.DLNA, ControlPanelId.LOCKSCREEN,
        ),
        theme: ScreensaverThemeId = ScreensaverThemeId.CLOCK,
        themes: List<ScreensaverThemeId> = listOf(ScreensaverThemeId.CLOCK, ScreensaverThemeId.OLED),
        homeAssistant: ControlHomeAssistant = ControlHomeAssistant.NONE,
    ) = ControlPanel(active, available, ControlLockscreen(theme, themes), homeAssistant)

    private fun JSONObject.stringList(key: String): List<String> =
        getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }

    @Test fun jsonMatchesApiContract() {
        val o = JSONObject(snap().toJson())
        assertEquals("abc", o.getJSONObject("device").getString("id"))
        assertEquals("Rusty Speaker", o.getJSONObject("device").getString("name"))
        assertEquals("2.3.0", o.getJSONObject("device").getString("version"))
        // screen.on is what Home Assistant's light entity's on/off state is built from — the one
        // field in this payload that a typo would break most visibly and most silently.
        assertEquals(true, o.getJSONObject("screen").getBoolean("on"))
        assertEquals(80, o.getJSONObject("screen").getInt("brightness"))
        assertEquals("system", o.getJSONObject("screen").getString("mode"))
        assertEquals(true, o.getJSONObject("screen").getBoolean("writable"))
        assertEquals(true, o.getJSONObject("screen").getBoolean("available"))
        assertEquals(47, o.getJSONObject("volume").getInt("value"))
        assertEquals(false, o.getJSONObject("volume").getBoolean("fixed"))
        assertEquals(true, o.getJSONObject("playing").getBoolean("spotify"))
        assertEquals(false, o.getJSONObject("playing").getBoolean("dlna"))
        // Position + length feed a media-player entity's progress; integers in ms, never labels.
        assertEquals(12_345L, o.getJSONObject("playing").getLong("elapsedMs"))
        assertEquals(200_000L, o.getJSONObject("playing").getLong("durationMs"))
        assertEquals(true, o.getJSONObject("slideshow").getBoolean("enabled"))
        assertEquals("spotify", o.getJSONObject("panel").getString("active"))
    }

    // ---- panel block --------------------------------------------------------

    @Test fun panelJson_reportsActiveAvailableAndLockscreen() {
        val o = JSONObject(snap().toJson()).getJSONObject("panel")
        assertEquals("spotify", o.getString("active"))
        assertEquals(listOf("spotify", "dlna", "lockscreen"), o.stringList("available"))
        val lock = o.getJSONObject("lockscreen")
        assertEquals("clock", lock.getString("theme"))
        assertEquals(listOf("clock", "oled"), lock.stringList("themes"))
    }

    /** `available` is a ring order, not a set: the page draws the lamps in exactly this sequence,
     *  so the serializer must not sort or dedupe it. */
    @Test fun panelJson_preservesAvailableOrder() {
        val o = JSONObject(
            snap(
                panel(
                    available = listOf(
                        ControlPanelId.LOCKSCREEN, ControlPanelId.HOME_ASSISTANT, ControlPanelId.SPOTIFY,
                    ),
                )
            ).toJson()
        ).getJSONObject("panel")
        assertEquals(listOf("lockscreen", "home_assistant", "spotify"), o.stringList("available"))
    }

    /**
     * No attached window: `active` must be present AND null. Omitting the key would be
     * indistinguishable from an older build that never reported a panel at all, and the page
     * would leave the lamps live over a device that cannot take a switch.
     */
    @Test fun panelJson_activeIsExplicitNullWithNoWindow() {
        val json = snap(panel(active = null)).toJson()
        val o = JSONObject(json).getJSONObject("panel")
        assertEquals(true, o.has("active"))
        assertEquals(true, o.isNull("active"))
        assertEquals(true, json.contains("\"active\":null"))
    }

    /** The dashboard picker's data: the chip-bar dashboards in chip order, each as the url_path
     *  the page posts back plus the title it shows, and the one on screen. */
    @Test fun panelJson_reportsHomeAssistantDashboardsAndActive() {
        val ha = ControlHomeAssistant(
            dashboards = listOf(
                ControlHaDashboard(path = "__overview__", title = "Overview"),
                ControlHaDashboard(path = "kitchen", title = "Kitchen"),
            ),
            active = "kitchen",
        )
        val o = JSONObject(snap(panel(homeAssistant = ha)).toJson())
            .getJSONObject("panel").getJSONObject("homeAssistant")
        val list = o.getJSONArray("dashboards")
        assertEquals(2, list.length())
        assertEquals("__overview__", list.getJSONObject(0).getString("path"))
        assertEquals("Overview", list.getJSONObject(0).getString("title"))
        assertEquals("kitchen", list.getJSONObject(1).getString("path"))
        assertEquals("Kitchen", list.getJSONObject(1).getString("title"))
        assertEquals("kitchen", o.getString("active"))
    }

    /** Off the HA panel (or with HA switched off) nothing is on screen: `active` is present AND
     *  null, the same always-present-key rule as `panel.active`. */
    @Test fun panelJson_homeAssistantActiveIsExplicitNullWhenNothingShows() {
        val o = JSONObject(snap().toJson()).getJSONObject("panel").getJSONObject("homeAssistant")
        assertEquals(0, o.getJSONArray("dashboards").length())
        assertEquals(true, o.has("active"))
        assertEquals(true, o.isNull("active"))
    }

    // ---- app block ----------------------------------------------------------

    @Test fun appJson_reportsForegroundAndPermission() {
        val o = JSONObject(snap().toJson()).getJSONObject("app")
        assertEquals(true, o.getBoolean("foreground"))
        assertEquals(true, o.getBoolean("canBringForward"))
    }

    @Test fun appJson_backgroundedWithoutPermission() {
        val o = JSONObject(snap(app = ControlApp(foreground = false, canBringForward = false)).toJson())
            .getJSONObject("app")
        assertEquals(false, o.getBoolean("foreground"))
        assertEquals(false, o.getBoolean("canBringForward"))
    }

    /**
     * `app.foreground` and `panel.active != null` are the same fact from the same source. A
     * snapshot that disagreed would leave the page with a lit switch over inert lamps (or the
     * reverse), so the two are pinned together here.
     */
    @Test fun appForeground_agreesWithPanelActive() {
        val live = JSONObject(snap().toJson())
        assertEquals(
            live.getJSONObject("app").getBoolean("foreground"),
            !live.getJSONObject("panel").isNull("active"),
        )

        val gone = JSONObject(
            snap(
                panel = panel(active = null),
                app = ControlApp(foreground = false, canBringForward = true),
            ).toJson()
        )
        assertEquals(
            gone.getJSONObject("app").getBoolean("foreground"),
            !gone.getJSONObject("panel").isNull("active"),
        )
    }

    @Test fun panelJson_emptyThemeListSerializesAsEmptyArray() {
        val o = JSONObject(snap(panel(themes = emptyList())).toJson())
            .getJSONObject("panel").getJSONObject("lockscreen")
        assertEquals(emptyList<String>(), o.stringList("themes"))
    }

    // ---- ControlUpdateCheck -------------------------------------------------

    @Test fun updateCheckJson_fullShape() {
        val check = ControlUpdateCheck(
            current = "2.3.0",
            status = "update_available",
            latest = ControlUpdateLatest(
                version = "2.4.0",
                notes = "• Remote updates",
                url = "https://github.com/SerafiniJose/rusty/releases/tag/v2.4.0",
                hasApk = true,
            ),
            install = InstallSnapshot(InstallPhase.DOWNLOADING, 42, null),
        )
        val o = JSONObject(check.toJson())
        assertEquals("2.3.0", o.getString("current"))
        assertEquals("update_available", o.getString("status"))
        val latest = o.getJSONObject("latest")
        assertEquals("2.4.0", latest.getString("version"))
        assertEquals("• Remote updates", latest.getString("notes"))
        assertEquals("https://github.com/SerafiniJose/rusty/releases/tag/v2.4.0", latest.getString("url"))
        assertEquals(true, latest.getBoolean("hasApk"))
        val install = o.getJSONObject("install")
        assertEquals("downloading", install.getString("phase"))
        assertEquals(42, install.getInt("progress"))
        assertEquals(false, install.has("error"))
    }

    @Test fun updateCheckJson_noLatestOmitsKey() {
        val check = ControlUpdateCheck(
            current = "2.3.0", status = "error", latest = null,
            install = InstallSnapshot(InstallPhase.IDLE, null, null),
        )
        val o = JSONObject(check.toJson())
        assertEquals(false, o.has("latest"))
        val install = o.getJSONObject("install")
        assertEquals("idle", install.getString("phase"))
        assertEquals(false, install.has("progress"))
        assertEquals(false, install.has("error"))
    }

    @Test fun updateCheckJson_errorPhaseCarriesMessage() {
        val check = ControlUpdateCheck(
            current = "2.3.0", status = "up_to_date", latest = null,
            install = InstallSnapshot(InstallPhase.ERROR, null, "download HTTP 503"),
        )
        val install = JSONObject(check.toJson()).getJSONObject("install")
        assertEquals("error", install.getString("phase"))
        assertEquals("download HTTP 503", install.getString("error"))
        assertEquals(false, install.has("progress"))
    }

    @Test fun updateCheckJson_awaitingConfirmPhaseName() {
        val check = ControlUpdateCheck(
            current = "2.3.0", status = "update_available", latest = null,
            install = InstallSnapshot(InstallPhase.AWAITING_CONFIRM, null, null),
        )
        assertEquals(
            "awaiting_confirm",
            JSONObject(check.toJson()).getJSONObject("install").getString("phase")
        )
    }

    @Test fun updateCheckJson_releaseDetailsAndSections() {
        val check = ControlUpdateCheck(
            current = "2.6.0",
            status = "update_available",
            latest = ControlUpdateLatest(
                version = "2.7.0", notes = "Added\n• Share this camera. Over RTSP.", url = "https://x/rel",
                hasApk = true, publishedAt = "2026-09-20T22:18:19Z", apkSize = 75_370_564,
                sections = listOf(
                    ReleaseNotes.Section("Added", listOf(ReleaseNotes.Entry("Share this camera.", "Over RTSP."))),
                ),
            ),
            install = InstallSnapshot(InstallPhase.IDLE, null, null),
            checkedAt = 1_790_000_000_000L,
            checkFailed = true,
        )
        val o = JSONObject(check.toJson())
        assertEquals(1_790_000_000_000L, o.getLong("checkedAt"))
        assertEquals(true, o.getBoolean("checkFailed"))
        val latest = o.getJSONObject("latest")
        assertEquals("2026-09-20T22:18:19Z", latest.getString("publishedAt"))
        assertEquals(75_370_564L, latest.getLong("apkSize"))
        val section = latest.getJSONArray("sections").getJSONObject(0)
        assertEquals("Added", section.getString("name"))
        val entry = section.getJSONArray("entries").getJSONObject(0)
        assertEquals("Share this camera.", entry.getString("title"))
        assertEquals("Over RTSP.", entry.getString("detail"))
    }

    @Test fun updateCheckJson_unknownDetailsAreOmitted() {
        val check = ControlUpdateCheck(
            current = "2.6.0", status = "update_available",
            latest = ControlUpdateLatest("2.7.0", "", "https://x/rel", hasApk = false),
            install = InstallSnapshot(InstallPhase.IDLE, null, null),
        )
        val o = JSONObject(check.toJson())
        assertEquals(false, o.has("checkedAt"))
        assertEquals(false, o.getBoolean("checkFailed"))
        val latest = o.getJSONObject("latest")
        assertEquals(false, latest.has("publishedAt"))
        assertEquals(false, latest.has("apkSize"))
        assertEquals(0, latest.getJSONArray("sections").length())
    }

    // ---- cameraShare block ----------------------------------------------------

    /** A runtime that reports no share (the default for every fake) must still emit the block,
     *  so a client can tell "this device cannot share" from "this build predates the field". */
    @Test fun cameraShareJson_absentReportsUnsupported() {
        val o = JSONObject(snap().toJson()).getJSONObject("cameraShare")
        assertEquals(false, o.getBoolean("supported"))
        assertEquals(false, o.getBoolean("enabled"))
        assertEquals("off", o.getString("status"))
        assertTrue(o.isNull("url"))
    }

    @Test fun cameraShareJson_streamingCarriesViewersDetailUrlAndLens() {
        val share = ControlCameraShare(
            supported = true, enabled = true, status = ControlCameraShareStatus.STREAMING,
            viewers = 2, detail = "1280×720 at 15 fps", url = "rtsp://192.168.7.251:8554/live",
            lens = CameraShareSettings.Lens.BACK, lenses = 2, appForeground = true,
        )
        val o = JSONObject(snap(cameraShare = share).toJson()).getJSONObject("cameraShare")
        assertEquals(true, o.getBoolean("supported"))
        assertEquals(true, o.getBoolean("enabled"))
        assertEquals("streaming", o.getString("status"))
        assertEquals(2, o.getInt("viewers"))
        assertEquals("1280×720 at 15 fps", o.getString("detail"))
        assertEquals("rtsp://192.168.7.251:8554/live", o.getString("url"))
        assertEquals("back", o.getString("lens"))
        assertEquals(2, o.getInt("lenses"))
        assertEquals(true, o.getBoolean("appForeground"))
    }

    @Test fun cameraShareJson_unavailableWritesReasonAndNullUrl() {
        val share = ControlCameraShare(
            supported = true, enabled = true, status = ControlCameraShareStatus.UNAVAILABLE,
            viewers = 0, detail = "camera permission needed", url = null,
            lens = CameraShareSettings.Lens.FRONT, lenses = 1, appForeground = false,
        )
        val o = JSONObject(snap(cameraShare = share).toJson()).getJSONObject("cameraShare")
        assertEquals("unavailable", o.getString("status"))
        assertEquals("camera permission needed", o.getString("detail"))
        assertTrue(o.isNull("url"))
        assertEquals("front", o.getString("lens"))
    }

    /** Every wire word the page switches on, pinned so a renamed enum cannot silently change the API. */
    @Test fun cameraShareStatus_wireWords() {
        assertEquals(
            listOf("off", "starting", "ready", "streaming", "unavailable"),
            ControlCameraShareStatus.entries.map { it.wire },
        )
    }
}
