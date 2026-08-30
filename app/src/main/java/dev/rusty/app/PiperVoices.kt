package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor

/**
 * One entry of the curated Piper voice catalog (`assets/piper-voices.json`). The catalog ships
 * with the app rather than being fetched: the feature stays offline-capable after a voice is
 * downloaded, and a new curation rides an app update like any other asset.
 *
 * [quality] is already the wire bucket ([TtsVoices.qualityBucket]'s vocabulary). [sha256] is
 * required — a bundle is executable-adjacent input (it feeds a native inference runtime), so an
 * unverifiable download is a failed download. [license]/[attribution] are shown at download time:
 * every Piper voice carries its own dataset terms.
 */
data class PiperVoice(
    val id: String,
    val label: String,
    val language: String,
    val quality: String,
    val sizeBytes: Long,
    val url: String,
    val sha256: String,
    val license: String,
    val attribution: String,
) {
    /** The selector this voice answers to in the shared `tts_voice` pref. */
    val selector: String get() = TtsVoices.format(VoiceSelector.Piper(id))
}

object PiperCatalog {

    /**
     * Parses the curated catalog. Total: a malformed entry fails the whole parse (null) rather
     * than being skipped — the file is checked in and covered by tests, so a bad entry is a build
     * mistake to surface loudly, never runtime input to tolerate.
     */
    fun parse(json: String): List<PiperVoice>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val arr = root.optJSONArray("voices") ?: return null
        val voices = ArrayList<PiperVoice>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val voice = PiperVoice(
                id = o.optString("id").ifEmpty { return null },
                label = o.optString("label").ifEmpty { return null },
                language = o.optString("language").ifEmpty { return null },
                quality = o.optString("quality").ifEmpty { return null },
                sizeBytes = o.optLong("sizeBytes", -1L).takeIf { it > 0 } ?: return null,
                url = o.optString("url").ifEmpty { return null },
                sha256 = o.optString("sha256").takeIf { it.length == 64 } ?: return null,
                license = o.optString("license").ifEmpty { return null },
                attribution = o.optString("attribution").ifEmpty { return null },
            )
            voices.add(voice)
        }
        return voices
    }

    /**
     * Free space a download must see before it starts: the archive itself plus an extracted tree
     * that can exceed the archive (onnx weights barely compress; espeak-ng-data does), with
     * headroom so a voice install never rides the disk to zero. 3× the archive is comfortably
     * above the observed ~2.3× worst case.
     */
    fun requiredBytes(sizeBytes: Long): Long = sizeBytes * 3
}

// ---------------------------------------------------------------------------------------------
// Download state machine
// ---------------------------------------------------------------------------------------------

/** Where a voice download currently is. One in flight at most, process-wide — the same
 *  single-slot posture as [InstallPhase]. DONE/ERROR are sticky until the next [VoiceDownloadManager.start]. */
enum class VoiceDownloadPhase { IDLE, DOWNLOADING, VERIFYING, EXTRACTING, DONE, ERROR }

/** [progress]: 0–100 while downloading with a known length, else null. [voiceId]: the bare
 *  catalog id being (or last) worked on; null only in IDLE. [error]: set only in ERROR. */
data class VoiceDownloadSnapshot(
    val phase: VoiceDownloadPhase,
    val voiceId: String?,
    val progress: Int?,
    val error: String?,
) {
    /** True while the single slot is occupied — a new [VoiceDownloadManager.start] is refused,
     *  and deleting the voice being worked on is a 409. */
    val busy: Boolean
        get() = phase == VoiceDownloadPhase.DOWNLOADING ||
            phase == VoiceDownloadPhase.VERIFYING ||
            phase == VoiceDownloadPhase.EXTRACTING

    /** Encodes for the `download` member of `GET /api/tts/voices`; optional members omitted,
     *  the [ControlUpdateCheck] treatment. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("phase", phase.name.lowercase())
        voiceId?.let { put("voiceId", it) }
        progress?.let { put("progress", it) }
        error?.let { put("error", it) }
    }

    companion object {
        val IDLE = VoiceDownloadSnapshot(VoiceDownloadPhase.IDLE, null, null, null)
    }
}

/** Router-level outcome of `POST /api/tts/voices/download`; maps 1:1 to an HTTP status,
 *  like [ControlInstallStart]. */
enum class ControlVoiceDownloadStart { STARTED, BUSY, UNKNOWN_VOICE, NO_SPACE }

/** Outcome of `POST /api/tts/voices/delete`. */
sealed class ControlVoiceDeleteResult {
    /** Removed from disk; [voices] is the fresh listing (selection already fallen back if it
     *  named the deleted voice). */
    data class Ok(val voices: ControlTtsVoices) : ControlVoiceDeleteResult()

    /** Not a catalog voice, or not installed — nothing to delete either way. */
    object NotInstalled : ControlVoiceDeleteResult()

    /** The download slot is mid-flight ON THIS VOICE — deleting under the extractor would race
     *  its atomic publish; the router answers 409 like a busy download start. */
    object Busy : ControlVoiceDeleteResult()
}

/**
 * "Download a voice bundle, verify its hash, unpack it, atomically publish it" as a pure-JVM
 * state machine, [UpdateInstaller]'s shape exactly: the blocking steps are injected seams (the
 * real ones live in `PiperVoiceStore`), the pipeline runs on the injected [executor], and the
 * remote-control page polls the [snapshot] embedded in `GET /api/tts/voices`.
 *
 * ERROR is sticky (kept until the user acts — the page shows it) but not busy: a later [start]
 * retries. Only DOWNLOADING/VERIFYING/EXTRACTING reject a new [start]. DONE flips to a fresh
 * download's state on the next [start], so one slot serves any number of sequential installs.
 */
class VoiceDownloadManager(
    private val executor: Executor,
    /** Blocking; downloads [PiperVoice.url] to a temp file, reporting 0–100 or null; throws on failure. */
    private val download: (voice: PiperVoice, onProgress: (Int?) -> Unit) -> File,
    /** Blocking; true iff [archive]'s sha256 equals [PiperVoice.sha256]. */
    private val verify: (voice: PiperVoice, archive: File) -> Boolean,
    /** Blocking; unpacks and atomically publishes the voice directory; throws on failure.
     *  Also owns deleting the archive (success or not). */
    private val extract: (voice: PiperVoice, archive: File) -> Unit,
) {
    private val lock = Any()
    private var state = VoiceDownloadSnapshot.IDLE

    fun snapshot(): VoiceDownloadSnapshot = synchronized(lock) { state }

    /** Kicks off the pipeline. False when one is already in flight. */
    fun start(voice: PiperVoice): Boolean {
        synchronized(lock) {
            if (state.busy) return false
            state = VoiceDownloadSnapshot(VoiceDownloadPhase.DOWNLOADING, voice.id, null, null)
        }
        executor.execute {
            try {
                val archive = download(voice) { pct ->
                    synchronized(lock) {
                        // A late progress callback must not resurrect a finished/failed download.
                        if (state.phase == VoiceDownloadPhase.DOWNLOADING && state.voiceId == voice.id) {
                            state = VoiceDownloadSnapshot(VoiceDownloadPhase.DOWNLOADING, voice.id, pct, null)
                        }
                    }
                }
                synchronized(lock) {
                    state = VoiceDownloadSnapshot(VoiceDownloadPhase.VERIFYING, voice.id, null, null)
                }
                if (!verify(voice, archive)) {
                    archive.delete()
                    throw IllegalStateException("checksum mismatch — the download was corrupted")
                }
                synchronized(lock) {
                    state = VoiceDownloadSnapshot(VoiceDownloadPhase.EXTRACTING, voice.id, null, null)
                }
                extract(voice, archive)
                synchronized(lock) {
                    state = VoiceDownloadSnapshot(VoiceDownloadPhase.DONE, voice.id, null, null)
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    state = VoiceDownloadSnapshot(
                        VoiceDownloadPhase.ERROR, voice.id, null, t.message ?: "download failed",
                    )
                }
            }
        }
        return true
    }
}

// ---------------------------------------------------------------------------------------------
// WAV encoding
// ---------------------------------------------------------------------------------------------

/**
 * Minimal PCM16 mono WAV writer for the Piper engine's float samples. Pure (bytes in, bytes
 * out) so the header math — the classic off-by-8s — is pinned by tests rather than by ExoPlayer
 * refusing to play on a device.
 */
object WavPcm {

    /** [samples] in [-1, 1]; values outside are clamped, not wrapped. */
    fun encodeMono16(samples: FloatArray, sampleRate: Int): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)

        fun putAscii(offset: Int, s: String) {
            for (i in s.indices) out[offset + i] = s[i].code.toByte()
        }
        fun putLe32(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
            out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        fun putLe16(offset: Int, v: Int) {
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putLe32(4, 36 + dataBytes)
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putLe32(16, 16)                    // PCM fmt chunk size
        putLe16(20, 1)                     // audio format: PCM
        putLe16(22, 1)                     // channels: mono
        putLe32(24, sampleRate)
        putLe32(28, sampleRate * 2)        // byte rate = rate * channels * 16/8
        putLe16(32, 2)                     // block align
        putLe16(34, 16)                    // bits per sample
        putAscii(36, "data")
        putLe32(40, dataBytes)

        var offset = 44
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val pcm = (clamped * 32767f).toInt()
            out[offset] = (pcm and 0xFF).toByte()
            out[offset + 1] = ((pcm shr 8) and 0xFF).toByte()
            offset += 2
        }
        return out
    }
}
