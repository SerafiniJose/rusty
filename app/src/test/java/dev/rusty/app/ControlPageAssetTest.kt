package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the control page asset's contract without a browser: the endpoints the JS must call
 * exist as literals, and the removed voice-message feature never creeps back in.
 */
class ControlPageAssetTest {

    private val page: String = run {
        // Gradle runs JVM tests with the module dir as working dir, but be tolerant of a
        // repo-root runner too.
        val candidates = listOf(
            File("src/main/assets/control.html"),
            File("app/src/main/assets/control.html"),
        )
        candidates.first { it.exists() }.readText()
    }

    @Test fun page_callsEveryLiveEndpoint() {
        listOf(
            "/api/state", "/api/panel", "/api/foreground", "/api/lockscreen",
            "/api/screen", "/api/volume", "/api/announce/text",
            "/api/slideshow/filters", "/api/immich/", "/api/update/install",
        ).forEach { endpoint ->
            assertTrue("page must reference $endpoint", page.contains(endpoint))
        }
        // "/api/update" is a substring of "/api/update/install" above, so pin the exact quoted
        // literal the JS calls, not just the substring.
        assertTrue("page must reference \"/api/update\"", page.contains("\"/api/update\""))
    }

    /**
     * Voice selection and Piper downloads are the device's job (Settings -> DLNA Player ->
     * "Announcement voice"), not the LAN page's: the page only composes and sends text. The
     * TTS routes stay live on the server for other clients — this pins the page only.
     */
    @Test fun page_hasNoVoicePickerUi() {
        listOf(
            "voice-card", "voice-chips", "voice-catalog-chips", "voice-progress", "voice-summary",
            "/api/tts/voice", "loadVoices", "renderVoiceCatalog", "scheduleVoicePoll",
            "catalogChipLabel", "Announcement voice",
        ).forEach { marker ->
            assertFalse("voice-picker marker still present: $marker", page.contains(marker))
        }
    }

    /** The lock-screen theme belongs to the switch that selects the lock screen, not to Service. */
    @Test fun page_putsLockThemeInTheSourceCard() {
        val source = page.indexOf("id=\"source-card\"")
        val chips = page.indexOf("id=\"theme-chips\"")
        val service = page.indexOf("id=\"service-card\"")
        assertTrue("source card missing", source > 0)
        assertTrue("theme chips missing", chips > 0)
        assertTrue("service card missing", service > 0)
        assertTrue(
            "theme chips must sit inside the Source card, above Service",
            chips > source && chips < service,
        )
    }

    /** Selectors of every rule that spans a card across the whole bento row. */
    private fun fullWidthRules(): List<String> =
        Regex("([^{}]*)\\{[^{}]*grid-column:\\s*1\\s*/\\s*-1[^{}]*}")
            .findAll(page).map { it.groupValues[1].trim() }.toList()

    @Test fun page_spansSourceFullWidthSoScreenAndVolumePair() {
        val bento = fullWidthRules().firstOrNull { it.contains("#dock") }
        assertTrue("no full-width bento rule found", bento != null)
        listOf("#source-card", "#announce-card", "#service-card").forEach {
            assertTrue("full-width rule must include $it, was: $bento", bento!!.contains(it))
        }
        listOf("#screen-card", "#volume-card").forEach {
            assertFalse("$it must stay half-width so the two pair up", bento!!.contains(it))
        }
    }

    @Test fun page_givesScreenTheWholeRowWhenVolumeIsFixed() {
        assertTrue("a fixed-volume device must flag the page", page.contains("no-volume"))
        assertTrue(
            "a rule must widen #screen-card once the volume card is hidden",
            fullWidthRules().any { it.contains("no-volume") && it.contains("#screen-card") },
        )
    }

    @Test fun page_hasNoVoiceRecordingCode() {
        listOf(
            "getUserMedia", "MediaRecorder", "announce-record", "/api/announce/voice",
            "Record voice message", "recordChunks",
        ).forEach { marker ->
            assertFalse("removed voice-message marker still present: $marker", page.contains(marker))
        }
    }

    @Test fun page_isSelfContained() {
        listOf(
            "https://fonts.", "cdn.", "<link rel=\"stylesheet\" href=\"http",
            "src=\"http", "url(http",
        ).forEach { marker ->
            assertFalse("external resource reference: $marker", page.contains(marker))
        }
    }

    @Test fun page_hasRedesignComponents() {
        listOf(
            "id=\"dock\"", "class=\"dock-led\"", "class=\"eq",          // status dock + meter
            "class=\"segmented\"", "id=\"seg-thumb\"",                   // source switch
            "class=\"fader\"", "bright-range", "vol-range",             // channel-strip faders
            "announce-text", "announce-send",                            // pill composer keeps real ids
            "details class=\"service\"", "class=\"chips\"",             // service accordion
            "id=\"toast\"", "prefers-reduced-motion",
        ).forEach { marker ->
            assertTrue("redesign marker missing: $marker", page.contains(marker))
        }
    }

    @Test fun page_keepsPendingConfirmMachinery() {
        listOf("pendingPanel", "pendingForeground", "PENDING_TIMEOUT_MS", "playing.spotify")
            .forEach { marker -> assertTrue("state machinery missing: $marker", page.contains(marker)) }
    }
}
