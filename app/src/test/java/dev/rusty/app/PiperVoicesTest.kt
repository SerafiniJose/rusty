package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/** [PiperCatalog] parsing, the checked-in asset itself, [VoiceDownloadManager]'s phase machine
 *  (same-thread executor, [UpdateInstaller]'s test recipe) and [WavPcm]'s header math. */
class PiperVoicesTest {

    private fun voice(id: String = "en_US-amy-medium", size: Long = 1000) = PiperVoice(
        id = id, label = "Amy", language = "en-US", quality = "medium",
        sizeBytes = size, url = "https://example.com/$id.tar.bz2",
        sha256 = "a".repeat(64), license = "CC0", attribution = "test",
    )

    private val sameThread = Executor { it.run() }

    // -- catalog parsing ----------------------------------------------------

    @Test fun parse_roundTripsAllFields() {
        val json = """
            {"voices":[{"id":"it_IT-riccardo-x_low","label":"Riccardo","language":"it-IT",
              "quality":"x_low","sizeBytes":26496614,"url":"https://example.com/v.tar.bz2",
              "sha256":"${"b".repeat(64)}","license":"MIT","attribution":"who"}]}
        """.trimIndent()
        val voices = PiperCatalog.parse(json)!!
        assertEquals(1, voices.size)
        val v = voices[0]
        assertEquals("it_IT-riccardo-x_low", v.id)
        assertEquals("piper:it_IT-riccardo-x_low", v.selector)
        assertEquals(26496614L, v.sizeBytes)
        assertEquals("x_low", v.quality)
    }

    @Test fun parse_rejectsAQualityOutsideTheTierVocabulary() {
        // The tier is what the settings picker filters on, so an entry the picker could never
        // show is a build mistake to surface loudly — the same posture as a missing sha256.
        assertNull(
            PiperCatalog.parse(
                """{"voices":[{"id":"x","label":"X","language":"en","quality":"normal",
                    "sizeBytes":5,"url":"https://e/x","sha256":"${"c".repeat(64)}",
                    "license":"l","attribution":"a"}]}""",
            ),
        )
    }

    @Test fun parse_isTotal_malformedEntryFailsTheWholeCatalog() {
        assertNull(PiperCatalog.parse("not json"))
        assertNull(PiperCatalog.parse("{}"))
        // Missing sha256 — invalid entry sinks the parse, it is not skipped.
        assertNull(
            PiperCatalog.parse(
                """{"voices":[{"id":"x","label":"X","language":"en","quality":"low",
                    "sizeBytes":5,"url":"u","license":"l","attribution":"a"}]}""",
            ),
        )
        assertNull(PiperCatalog.parse("""{"voices":[{"id":"x","sha256":"short"}]}"""))
    }

    @Test fun shippedCatalogAsset_parsesAndIsSane() {
        val json = File("src/main/assets/piper-voices.json").readText()
        val voices = PiperCatalog.parse(json)
        assertTrue("checked-in catalog must parse", voices != null && voices.isNotEmpty())
        voices!!.forEach { v ->
            assertTrue(v.url.startsWith("https://"))
            assertTrue("selector must round-trip", TtsVoices.parse(v.selector) == VoiceSelector.Piper(v.id))
            assertTrue("quality must be a tier", VoiceQuality.parse(v.quality) != null)
            assertTrue("sha256 must be a full digest", v.sha256.matches(Regex("[0-9a-f]{64}")))
        }
        assertEquals("ids must be unique", voices.size, voices.map { it.id }.toSet().size)
    }

    @Test fun shippedCatalogAsset_offersVoicesAtEveryTier() {
        // The settings card lets the user pick a tier before a voice; a tier the catalog cannot
        // fill would be a dead choice on every device.
        val voices = PiperCatalog.parse(File("src/main/assets/piper-voices.json").readText())!!
        VoiceQuality.entries.forEach { tier ->
            assertTrue(
                "no catalog voice at tier ${tier.wire}",
                voices.any { it.quality == tier.wire },
            )
        }
    }

    @Test fun requiredBytes_leavesExtractionHeadroom() {
        assertEquals(300L, PiperCatalog.requiredBytes(100))
    }

    // -- download phase machine --------------------------------------------

    @Test fun download_happyPath_walksThePhases() {
        val mgr = VoiceDownloadManager(
            executor = sameThread,
            download = { _, onProgress ->
                onProgress(50)
                File("archive")
            },
            verify = { _, _ -> true },
            extract = { _, _ -> },
        )
        assertTrue(mgr.start(voice()))
        val snap = mgr.snapshot()
        assertEquals(VoiceDownloadPhase.DONE, snap.phase)
        assertEquals("en_US-amy-medium", snap.voiceId)
        assertNull(snap.error)
    }

    @Test fun download_checksumMismatch_isError() {
        val mgr = VoiceDownloadManager(
            executor = sameThread,
            download = { _, _ -> File("archive") },
            verify = { _, _ -> false },
            extract = { _, _ -> throw AssertionError("must not extract an unverified archive") },
        )
        mgr.start(voice())
        val snap = mgr.snapshot()
        assertEquals(VoiceDownloadPhase.ERROR, snap.phase)
        assertTrue(snap.error!!.contains("checksum"))
    }

    @Test fun download_downloadThrows_isErrorWithMessage() {
        val mgr = VoiceDownloadManager(
            executor = sameThread,
            download = { _, _ -> throw RuntimeException("network gone") },
            verify = { _, _ -> true },
            extract = { _, _ -> },
        )
        mgr.start(voice())
        assertEquals(VoiceDownloadPhase.ERROR, mgr.snapshot().phase)
        assertEquals("network gone", mgr.snapshot().error)
    }

    @Test fun download_busyWhileInFlight_errorAndDoneAreNot() {
        // A deferred executor holds the pipeline "in flight" so the busy window is observable.
        val queued = mutableListOf<Runnable>()
        val mgr = VoiceDownloadManager(
            executor = { queued.add(it) },
            download = { _, _ -> File("archive") },
            verify = { _, _ -> true },
            extract = { _, _ -> },
        )
        assertTrue(mgr.start(voice()))
        assertEquals(VoiceDownloadPhase.DOWNLOADING, mgr.snapshot().phase)
        assertFalse("in flight must reject a second start", mgr.start(voice("other")))
        queued.removeAt(0).run()
        assertEquals(VoiceDownloadPhase.DONE, mgr.snapshot().phase)
        assertTrue("DONE is not busy", mgr.start(voice("other")))
        queued.removeAt(0).run()
        assertEquals(VoiceDownloadPhase.DONE, mgr.snapshot().phase)
        assertEquals("other", mgr.snapshot().voiceId)
    }

    @Test fun snapshotBusy_isExactlyTheInFlightPhases() {
        // The same predicate gates a second start() AND deleting the voice being worked on (409).
        VoiceDownloadPhase.values().forEach { phase ->
            val expected = phase == VoiceDownloadPhase.DOWNLOADING ||
                phase == VoiceDownloadPhase.VERIFYING || phase == VoiceDownloadPhase.EXTRACTING
            assertEquals(phase.name, expected, VoiceDownloadSnapshot(phase, "x", null, null).busy)
        }
    }

    @Test fun download_progressIsObservableMidDownload() {
        lateinit var mgr: VoiceDownloadManager
        var observed: VoiceDownloadSnapshot? = null
        mgr = VoiceDownloadManager(
            executor = sameThread,
            download = { _, onProgress ->
                onProgress(42)
                observed = mgr.snapshot()
                File("a")
            },
            verify = { _, _ -> true },
            extract = { _, _ -> },
        )
        mgr.start(voice())
        assertEquals(VoiceDownloadPhase.DOWNLOADING, observed!!.phase)
        assertEquals(42, observed!!.progress)
        val json = observed!!.toJson()
        assertEquals("downloading", json.getString("phase"))
        assertEquals(42, json.getInt("progress"))
        assertFalse(json.has("error"))
    }

    @Test fun snapshotJson_omitsAbsentMembers() {
        val idle = VoiceDownloadSnapshot.IDLE.toJson()
        assertEquals("idle", idle.getString("phase"))
        assertFalse(idle.has("voiceId"))
        assertFalse(idle.has("progress"))
        assertFalse(idle.has("error"))
    }

    // -- ControlTtsVoices with catalog ----------------------------------------

    @Test fun ttsVoicesJson_carriesCatalogAndDownload() {
        val v = voice()
        val payload = ControlTtsVoices(
            selected = "system:default",
            voices = listOf(VoiceInfo("system:default", "System default", "", "normal", true, false)),
            catalog = listOf(ControlCatalogVoice(v, installed = true)),
            download = VoiceDownloadSnapshot(VoiceDownloadPhase.EXTRACTING, v.id, null, null),
        )
        val root = JSONObject(payload.toJson())
        val cat = root.getJSONArray("catalog").getJSONObject(0)
        assertEquals("piper:en_US-amy-medium", cat.getString("id"))
        assertEquals(1000L, cat.getLong("sizeBytes"))
        assertEquals("CC0", cat.getString("license"))
        assertTrue(cat.getBoolean("installed"))
        assertEquals("extracting", root.getJSONObject("download").getString("phase"))
    }

    // -- WAV encoding -------------------------------------------------------

    @Test fun wav_headerAndSamplesAreCorrect() {
        val bytes = WavPcm.encodeMono16(floatArrayOf(0f, 1f, -1f, 0.5f), 22050)
        assertEquals(44 + 8, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))

        fun le32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        fun le16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)

        assertEquals(36 + 8, le32(4))       // RIFF size
        assertEquals(1, le16(20))           // PCM
        assertEquals(1, le16(22))           // mono
        assertEquals(22050, le32(24))       // sample rate
        assertEquals(44100, le32(28))       // byte rate
        assertEquals(16, le16(34))          // bits per sample
        assertEquals(8, le32(40))           // data size

        fun sample(i: Int): Int {
            val v = le16(44 + i * 2)
            return if (v >= 0x8000) v - 0x10000 else v
        }
        assertEquals(0, sample(0))
        assertEquals(32767, sample(1))
        assertEquals(-32767, sample(2))
        assertEquals((0.5f * 32767).toInt(), sample(3))
    }

    @Test fun wav_clampsOutOfRangeSamples() {
        val bytes = WavPcm.encodeMono16(floatArrayOf(2f, -2f), 16000)
        fun le16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        assertEquals(32767, le16(44))
        assertEquals(-32767, le16(46) - 0x10000)
    }
}
