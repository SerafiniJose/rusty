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
            "/api/tts/voices", "/api/tts/voice", "/api/tts/voices/download", "/api/tts/voices/delete",
            "/api/slideshow/filters", "/api/immich/", "/api/update", "/api/update/install",
        ).forEach { endpoint ->
            assertTrue("page must reference $endpoint", page.contains(endpoint))
        }
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
        listOf("https://fonts.", "cdn.", "<link rel=\"stylesheet\" href=\"http").forEach { marker ->
            assertFalse("external resource reference: $marker", page.contains(marker))
        }
    }
}
