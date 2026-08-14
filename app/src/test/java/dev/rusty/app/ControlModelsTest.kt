package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlModelsTest {
    private fun snap(
        panel: ControlPanel = panel(),
        app: ControlApp = ControlApp(foreground = true, canBringForward = true),
    ) = ControlSnapshot(
        deviceId = "abc", deviceName = "Rusty Speaker", version = "2.3.0",
        screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
        volume = ControlVolume(value = 47, fixed = false),
        playing = ControlPlaying(spotify = true, dlna = false),
        slideshowEnabled = true,
        panel = panel,
        app = app,
    )

    private fun panel(
        active: ControlPanelId? = ControlPanelId.SPOTIFY,
        available: List<ControlPanelId> = listOf(
            ControlPanelId.SPOTIFY, ControlPanelId.DLNA, ControlPanelId.LOCKSCREEN,
        ),
        theme: ScreensaverThemeId = ScreensaverThemeId.CLOCK,
        themes: List<ScreensaverThemeId> = listOf(ScreensaverThemeId.CLOCK, ScreensaverThemeId.OLED),
    ) = ControlPanel(active, available, ControlLockscreen(theme, themes))

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
}
