package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TtsVoices] selector parse/format round-trips, the prefix routing rule Phase 4 plugs into,
 *  and the JSON shapes `GET /api/tts/voices` speaks. */
class TtsVoicesTest {

    // -- selector parse / format --------------------------------------------

    @Test fun systemDefault_roundTrips() {
        assertEquals(VoiceSelector.SystemDefault, TtsVoices.parse(TtsVoices.SYSTEM_DEFAULT))
        assertEquals(TtsVoices.SYSTEM_DEFAULT, TtsVoices.format(VoiceSelector.SystemDefault))
    }

    @Test fun systemVoice_roundTrips() {
        val raw = "system:com.google.android.tts/en-us-x-tpd-local"
        val parsed = TtsVoices.parse(raw)
        assertEquals(VoiceSelector.System("com.google.android.tts", "en-us-x-tpd-local"), parsed)
        assertEquals(raw, TtsVoices.format(parsed!!))
    }

    @Test fun systemVoice_voiceNameMayContainSlashes() {
        // Only the FIRST slash separates engine from voice; a voice name with a slash survives.
        val parsed = TtsVoices.parse("system:engine.pkg/weird/voice")
        assertEquals(VoiceSelector.System("engine.pkg", "weird/voice"), parsed)
    }

    @Test fun piperVoice_roundTrips() {
        val parsed = TtsVoices.parse("piper:en_US-lessac-medium")
        assertEquals(VoiceSelector.Piper("en_US-lessac-medium"), parsed)
        assertEquals("piper:en_US-lessac-medium", TtsVoices.format(parsed!!))
    }

    @Test fun malformedSelectors_parseToNull() {
        assertNull(TtsVoices.parse(""))
        assertNull(TtsVoices.parse("default"))
        assertNull(TtsVoices.parse("system:"))
        assertNull(TtsVoices.parse("system:no-slash"))
        assertNull(TtsVoices.parse("system:/voice-without-engine"))
        assertNull(TtsVoices.parse("system:engine/"))
        assertNull(TtsVoices.parse("piper:"))
        assertNull(TtsVoices.parse("espeak:something"))
    }

    // -- quality buckets ----------------------------------------------------

    @Test fun qualityBuckets_matchVoiceConstants() {
        assertEquals("low", TtsVoices.qualityBucket(100))       // QUALITY_VERY_LOW
        assertEquals("low", TtsVoices.qualityBucket(200 - 1))
        assertEquals("normal", TtsVoices.qualityBucket(300 - 1))
        assertEquals("high", TtsVoices.qualityBucket(300))       // QUALITY_HIGH
        assertEquals("very_high", TtsVoices.qualityBucket(400))  // QUALITY_VERY_HIGH
    }

    // -- JSON shapes --------------------------------------------------------

    @Test fun voicesJson_carriesSelectionAndRows() {
        val voices = ControlTtsVoices(
            selected = "system:default",
            voices = listOf(
                VoiceInfo("system:default", "System default", "", "normal", true, false),
                VoiceInfo("system:e/v", "v", "en-US", "high", true, true),
            ),
        )
        val root = JSONObject(voices.toJson())
        assertEquals("system:default", root.getString("selected"))
        val arr = root.getJSONArray("voices")
        assertEquals(2, arr.length())
        val second = arr.getJSONObject(1)
        assertEquals("system:e/v", second.getString("id"))
        assertEquals("en-US", second.getString("language"))
        assertEquals("high", second.getString("quality"))
        assertTrue(second.getBoolean("requiresNetwork"))
        assertFalse(arr.getJSONObject(0).getBoolean("requiresNetwork"))
    }
}
