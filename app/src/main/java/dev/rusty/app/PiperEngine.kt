package dev.rusty.app

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Embedded neural synthesis for downloaded Piper voices, wrapping sherpa-onnx's [OfflineTts].
 *
 * The loaded model is KEPT between announcements — loading is the expensive step (hundreds of
 * ms to seconds; inference itself runs faster than realtime on arm64) and announcements come in
 * bursts — and swapped only when the selection changes. Callers serialize on the announce lock,
 * so there is no concurrent [synthesizeToFile]/[release]; `@Volatile` covers release from the
 * service teardown path.
 *
 * Output is a plain PCM16 WAV, which then rides the exact same
 * `MediaRendererService.playAnnouncement` path as a system-TTS clip.
 */
class PiperEngine {

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var loadedId: String? = null

    /**
     * Synthesizes [text] with the voice installed at [voiceDir] into [out]. False on any failure
     * — a missing model file, a native init error, empty output — which the caller reports as the
     * announce route's 503. No fallback to the system engine: the user chose this voice.
     */
    fun synthesizeToFile(voiceId: String, voiceDir: File, text: String, out: File): Boolean {
        val engine = engineFor(voiceId, voiceDir) ?: return false
        val audio = runCatching { engine.generate(text, sid = 0, speed = 1.0f) }.getOrElse { t ->
            Log.w(TAG, "piper synthesis failed for $voiceId", t)
            return false
        }
        val samples = audio.samples
        if (samples.isEmpty() || audio.sampleRate <= 0) return false
        out.writeBytes(WavPcm.encodeMono16(samples, audio.sampleRate))
        return true
    }

    private fun engineFor(voiceId: String, voiceDir: File): OfflineTts? {
        tts?.let { if (loadedId == voiceId) return it }
        // Selection changed (or first use): drop the old model before loading the new one, so
        // peak memory holds one model, not two.
        releaseLoaded()

        val model = File(voiceDir, "$voiceId.onnx")
        val tokens = File(voiceDir, "tokens.txt")
        val espeakData = File(voiceDir, "espeak-ng-data")
        if (!model.isFile || !tokens.isFile || !espeakData.isDirectory) {
            Log.w(TAG, "voice $voiceId is missing model files")
            return null
        }

        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = espeakData.absolutePath,
                ),
                numThreads = 2,
            ),
        )
        return runCatching { OfflineTts(null, config) }
            .onFailure { Log.w(TAG, "piper engine failed to load $voiceId", it) }
            .getOrNull()
            ?.also {
                tts = it
                loadedId = voiceId
            }
    }

    private fun releaseLoaded() {
        tts?.let { runCatching { it.release() } }
        tts = null
        loadedId = null
    }

    /** Idempotent; drops the loaded model (service stop, or the selected voice was deleted). */
    fun release() = releaseLoaded()

    private companion object {
        const val TAG = "PiperEngine"
    }
}
