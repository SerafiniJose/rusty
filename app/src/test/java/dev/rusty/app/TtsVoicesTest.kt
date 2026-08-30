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

    // -- quality tiers ------------------------------------------------------

    @Test fun qualityBuckets_matchVoiceConstants() {
        // One vocabulary for both engines: Android's five constants fold onto the four Piper
        // tiers, so a system voice and a downloaded voice describe themselves the same way.
        assertEquals("x_low", TtsVoices.qualityBucket(100))    // QUALITY_VERY_LOW
        assertEquals("low", TtsVoices.qualityBucket(200))      // QUALITY_LOW
        assertEquals("medium", TtsVoices.qualityBucket(300))   // QUALITY_NORMAL
        assertEquals("high", TtsVoices.qualityBucket(400))     // QUALITY_HIGH
        assertEquals("high", TtsVoices.qualityBucket(500))     // QUALITY_VERY_HIGH
        // Engines are free to report anything; below the first constant is still the low end.
        assertEquals("x_low", TtsVoices.qualityBucket(0))
    }

    @Test fun voiceQuality_wireValuesAreTheCatalogVocabulary() {
        assertEquals(
            listOf("x_low", "low", "medium", "high"),
            VoiceQuality.entries.map { it.wire },
        )
        assertEquals(VoiceQuality.MEDIUM, VoiceQuality.parse("medium"))
        assertNull(VoiceQuality.parse("normal"))
        assertNull(VoiceQuality.parse(null))
    }

    @Test fun selectedQuality_defaultsToLowAndSurvivesGarbage() {
        // The pref is the one place a stale or hand-edited value can arrive; a picker that
        // matched nothing would show an empty catalog, so an unreadable value IS the default.
        assertEquals(VoiceQuality.LOW, TtsVoices.selectedQuality(null))
        assertEquals(VoiceQuality.LOW, TtsVoices.selectedQuality(""))
        assertEquals(VoiceQuality.LOW, TtsVoices.selectedQuality("normal"))
        assertEquals(VoiceQuality.X_LOW, TtsVoices.selectedQuality("x_low"))
        assertEquals(VoiceQuality.HIGH, TtsVoices.selectedQuality("high"))
    }

    @Test fun catalogFor_keepsOnlyTheChosenTier() {
        val entries = listOf(
            catalogEntry("en_US-amy-low", "low"),
            catalogEntry("en_US-amy-medium", "medium"),
            catalogEntry("it_IT-riccardo-x_low", "x_low"),
        )
        assertEquals(
            listOf("en_US-amy-medium"),
            TtsVoices.catalogFor(entries, VoiceQuality.MEDIUM).map { it.voice.id },
        )
        assertTrue(TtsVoices.catalogFor(entries, VoiceQuality.HIGH).isEmpty())
    }

    @Test fun downloadableFor_dropsWhatIsAlreadyInstalled() {
        // An installed voice is offered as a selectable row (with its own Remove button), so
        // listing it again under "download" would be the same voice twice.
        val entries = listOf(
            catalogEntry("en_US-amy-low", "low"),
            catalogEntry("en_US-danny-low", "low", installed = true),
            catalogEntry("en_US-amy-medium", "medium"),
        )
        assertEquals(
            listOf("en_US-amy-low"),
            TtsVoices.downloadableFor(entries, VoiceQuality.LOW).map { it.voice.id },
        )
    }

    @Test fun catalogEmptyNote_tellsTheTwoEmptyCasesApart() {
        // Nothing left to download because it is all here, versus nothing to download at all:
        // the first is an achievement, the second is a reason to try another tier.
        val allInstalled = TtsVoices.catalogEmptyNote(VoiceQuality.LOW, allInstalled = true)
        assertTrue(allInstalled.contains("Low"))
        assertTrue(allInstalled.contains("downloaded"))
        val noneExist = TtsVoices.catalogEmptyNote(VoiceQuality.HIGH, allInstalled = false)
        assertTrue(noneExist.contains("High"))
        assertTrue(noneExist.contains("another quality"))
    }

    private fun catalogEntry(id: String, quality: String, installed: Boolean = false) = ControlCatalogVoice(
        PiperVoice(
            id = id, label = id, language = "en-US", quality = quality, sizeBytes = 1000,
            url = "https://example.com/$id.tar.bz2", sha256 = "a".repeat(64),
            license = "CC0", attribution = "test",
        ),
        installed = installed,
    )

    // -- JSON shapes --------------------------------------------------------

    @Test fun voicesJson_carriesSelectionAndRows() {
        val voices = ControlTtsVoices(
            selected = "system:default",
            voices = listOf(
                VoiceInfo("system:default", "System default", "", "medium", true, false),
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

    // -- picker row composition --------------------------------------------

    private fun piperVoice(id: String, size: Long = 63L * 1024 * 1024) = PiperVoice(
        id = id, label = id, language = "en_US", quality = "medium", sizeBytes = size,
        url = "https://example.invalid/$id.tar.bz2", sha256 = "a".repeat(64),
        license = "MIT", attribution = "Some dataset",
    )

    @Test fun pickerRows_composeDefaultThenPiperThenSystem() {
        val piper = listOf(VoiceInfo("piper:amy", "Amy", "en_US", "medium", true, false))
        val system = listOf(VoiceInfo("system:e/v", "v", "en-US", "high", true, false))
        val rows = TtsVoices.pickerRows(piper, system)
        assertEquals(listOf("system:default", "piper:amy", "system:e/v"), rows.map { it.id })
    }

    @Test fun pickerRows_withoutASystemEngine_stillOfferDefaultAndPiper() {
        // The regression this pins: a device with NO system TTS engine enumerates no system
        // voices, but the default row and every downloaded Piper voice stay selectable — Piper
        // synthesis never touches TextToSpeech, so the picker must not gate on it.
        val piper = listOf(VoiceInfo("piper:amy", "Amy", "en_US", "medium", true, false))
        val rows = TtsVoices.pickerRows(piper, emptyList())
        assertEquals(listOf("system:default", "piper:amy"), rows.map { it.id })
    }

    @Test fun pickerRows_withNothingInstalled_stillOffersTheDefaultRow() {
        assertEquals(listOf("system:default"), TtsVoices.pickerRows(emptyList(), emptyList()).map { it.id })
    }

    // -- catalog rows ------------------------------------------------------

    @Test fun catalogActionDescription_describesTheIconButton() {
        // The row's action is an icon; these are the words TalkBack reads out, and they mirror
        // the control page's aria-label.
        val notInstalled = ControlCatalogVoice(piperVoice("amy"), installed = false)
        assertEquals("Download amy (63 MB)", TtsVoices.catalogActionDescription(notInstalled))
        val installed = ControlCatalogVoice(piperVoice("amy"), installed = true)
        assertEquals("Remove amy", TtsVoices.catalogActionDescription(installed))
    }

    @Test fun catalogMetaShort_isJustCostAndTerms() {
        assertEquals("63 MB \u00b7 MIT", TtsVoices.catalogMetaShort(piperVoice("amy")))
    }

    // -- the single download status line -----------------------------------

    @Test fun downloadStatus_namesThePhaseAndTheVoice() {
        fun snap(phase: VoiceDownloadPhase, pct: Int? = null, error: String? = null) =
            VoiceDownloadSnapshot(phase, "amy", pct, error)
        assertEquals("Downloading Amy\u2026 42%", TtsVoices.downloadStatus(snap(VoiceDownloadPhase.DOWNLOADING, 42), "Amy"))
        assertEquals("Downloading Amy\u2026", TtsVoices.downloadStatus(snap(VoiceDownloadPhase.DOWNLOADING), "Amy"))
        assertEquals("Verifying Amy\u2026", TtsVoices.downloadStatus(snap(VoiceDownloadPhase.VERIFYING), "Amy"))
        assertEquals("Unpacking Amy\u2026", TtsVoices.downloadStatus(snap(VoiceDownloadPhase.EXTRACTING), "Amy"))
        assertEquals(
            "Couldn't download Amy: checksum mismatch",
            TtsVoices.downloadStatus(snap(VoiceDownloadPhase.ERROR, error = "checksum mismatch"), "Amy"),
        )
    }

    @Test fun downloadStatus_isSilentWhenNothingIsHappening() {
        assertNull(TtsVoices.downloadStatus(VoiceDownloadSnapshot.IDLE, null))
        assertNull(TtsVoices.downloadStatus(
            VoiceDownloadSnapshot(VoiceDownloadPhase.DONE, "amy", null, null), "Amy"))
    }

    @Test fun downloadStatus_fallsBackToTheBareIdForAnUncuratedVoice() {
        // A voice dropped from the catalog by an app update can still be mid-download.
        assertEquals(
            "Verifying gone\u2026",
            TtsVoices.downloadStatus(VoiceDownloadSnapshot(VoiceDownloadPhase.VERIFYING, "gone", null, null), null),
        )
    }

    @Test fun catalogMeta_alwaysCarriesSizeLicenseAttribution() {
        assertEquals(
            "63 MB \u00b7 MIT \u00b7 Some dataset",
            TtsVoices.catalogMeta(piperVoice("amy")),
        )
    }
}
