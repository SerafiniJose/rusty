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
            "/api/cameras", "\"/api/camera/view\"", "\"/api/camera/grid\"",
            "\"/api/camera/share\"",
        ).forEach { endpoint ->
            assertTrue("page must reference $endpoint", page.contains(endpoint))
        }
        // "/api/update" is a substring of "/api/update/install" above, so pin the exact quoted
        // literal the JS calls, not just the substring.
        assertTrue("page must reference \"/api/update\"", page.contains("\"/api/update\""))
    }

    /**
     * The Announce card lets the user pick among the voices ALREADY ON the device: a dropdown
     * fed by GET /api/tts/voices (installed rows only), persisting through POST /api/tts/voice —
     * the same device-wide setting the on-device picker edits. Downloading and deleting voices
     * stays the device's job, so the catalog/download UI must not creep into the page.
     */
    @Test fun page_hasAnnounceVoicePicker() {
        listOf(
            "announce-voice", "/api/tts/voices", "\"/api/tts/voice\"", "loadVoices",
        ).forEach { marker ->
            assertTrue("voice-picker marker missing: $marker", page.contains(marker))
        }
        // Only installed voices are offered; a voice needing a download never shows here.
        assertTrue("picker must filter on the installed flag", page.contains("v.installed"))
        listOf(
            "voice-catalog", "/api/tts/voices/download", "/api/tts/voices/delete",
            "voice-progress", "sizeBytes",
        ).forEach { marker ->
            assertFalse("catalog/download UI must stay on-device: $marker", page.contains(marker))
        }
    }

    /**
     * The Camera share card: a power button, a status/notice/error line, Front/Back lens chips
     * and the rtsp URL with a copy button — between Announce and Service, hidden until
     * `cameraShare.supported`. The page must read the `reason` an error body carries so the two
     * 409s (window vs. permission) get their own wording.
     */
    @Test fun page_hasCameraShareCardBetweenAnnounceAndService() {
        listOf(
            "id=\"camshare-card\"", "id=\"camshare-pwr\"", "id=\"camshare-status\"",
            "id=\"camshare-notice\"", "id=\"camshare-error\"", "id=\"camshare-lens\"",
            "id=\"camshare-chips\"", "id=\"camshare-url\"", "id=\"camshare-copy\"",
            "cameraShare.supported", "needs_foreground", "permission_needed",
            "Rusty has to be on screen",
        ).forEach { marker ->
            assertTrue("camera share marker missing: $marker", page.contains(marker))
        }
        val announce = page.indexOf("id=\"announce-card\"")
        val share = page.indexOf("id=\"camshare-card\"")
        val service = page.indexOf("id=\"service-card\"")
        assertTrue("camera share card must sit between Announce and Service", share > announce && share < service)
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

    /**
     * The camera picker is a chip strip inset under the source switch that selects it — the same
     * shape the lock screen's themes use — and Camera is one of the switch's own destinations.
     */
    @Test fun page_hasCameraStripUnderTheSourceSwitch() {
        listOf(
            "id=\"camera-strip\"",
            "id=\"camera-chips\"",
            "data-endpoint=\"/api/cameras\"",
            "renderCameraStrip",
        ).forEach { marker ->
            assertTrue("camera strip marker missing: $marker", page.contains(marker))
        }
        // Camera is a segment of the source switch, and every PANELS entry needs `full`:
        // labelFor() and the segment aria-label both read it.
        assertTrue(
            "Camera must be a source panel with a full label",
            page.contains("""{ id: "camera", label: "Camera", full: "Camera" }"""),
        )
    }

    /**
     * The camera strip is CONTEXTUAL: it reports what the device is showing right now, which is
     * not a question anywhere but the camera panel — and off that panel every camera reports
     * "none" anyway. Gated on the SELECTED source (`pendingPanel || active`), not on `active`
     * alone, so it appears on the tap rather than a poll later.
     *
     * The lock-screen theme is deliberately NOT gated this way: selecting Lock actually shows the
     * screensaver, so gating the theme behind it would make the theme unsettable while music
     * plays. That asymmetry is the point, so pin both halves.
     */
    @Test fun page_cameraStripIsContextualButLockThemeIsNot() {
        assertTrue("camera strip must gate on the selected source", page.contains("selectedPanel"))
        assertTrue(
            "the gate must be the selected source, pending included",
            page.contains("""selectedPanel() === "camera""""),
        )
        // The lock-screen strip keeps its own rule: shown whenever there are themes to pick.
        assertTrue(
            "lock theme must stay available from any source",
            page.contains("""el["lockscreen-strip"].hidden = themes.length === 0"""),
        )
    }

    /**
     * The strip SELECTS; it never renders camera imagery. `GET /api/camera/{id}/snapshot` stays a
     * published route, but a frame from inside the house is a different class of secret from a
     * control surface, and this page is reachable by anyone on the LAN — so the tile grid and all
     * its snapshot plumbing must not creep back in.
     */
    @Test fun page_neverRendersCameraImagery() {
        listOf(
            "cameras-section", "camera-tile", "cameraBlobUrls", "cameraGenerations",
            "/snapshot", "Set an API password to see camera tiles.",
        ).forEach { marker ->
            assertFalse("camera imagery must stay off the page: $marker", page.contains(marker))
        }
    }

    /**
     * "Grid" is a DESTINATION, so it posts to `/api/camera/grid`. `/api/camera/dismiss` undoes a
     * summon — it restores whatever was on screen before (tap Grid, land on Spotify) and 409s
     * when the camera panel was reached by an ordinary panel switch — so the page must not reach
     * for it here.
     */
    @Test fun page_gridChipUsesTheGridEndpointNotDismiss() {
        assertTrue("Grid chip must post /api/camera/grid", page.contains("\"/api/camera/grid\""))
        // The QUOTED literal, i.e. an actual call — the doc comment on onSelectCameraGrid names
        // the dismiss route in prose to explain why it is the wrong one, and must stay legal.
        assertFalse(
            "dismiss is not the Grid chip's endpoint",
            page.contains("\"/api/camera/dismiss\""),
        )
    }

    @Test fun page_keepsPendingConfirmMachinery() {
        listOf("pendingPanel", "pendingForeground", "PENDING_TIMEOUT_MS", "playing.spotify")
            .forEach { marker -> assertTrue("state machinery missing: $marker", page.contains(marker)) }
    }
}
