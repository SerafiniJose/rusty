package dev.rusty.app

import android.speech.tts.TextToSpeech

/**
 * Android-side enumeration of a [TextToSpeech] engine's voices into the wire model, shared by
 * the control runtime (`GET /api/tts/voices`) and the on-device settings picker so both surfaces
 * list, label and identify voices identically. The pure rules (selector format, quality buckets)
 * live in [TtsVoices]; only the engine access is here.
 */
object SystemTtsVoices {

    /** The always-present first row: let the engine pick, exactly what a fresh install does. */
    fun defaultRow(): VoiceInfo = VoiceInfo(
        id = TtsVoices.SYSTEM_DEFAULT,
        label = "System default",
        language = "",
        quality = "normal",
        installed = true,
        requiresNetwork = false,
    )

    /** Enumerates the live engine's voices. Defensive throughout: `getVoices` is documented to
     *  return null on some engines, and third-party engines have been seen throwing from it. */
    fun list(engine: TextToSpeech): List<VoiceInfo> {
        val enginePkg = runCatching { engine.defaultEngine }.getOrNull() ?: return emptyList()
        val voices = runCatching { engine.voices }.getOrNull() ?: return emptyList()
        return voices
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
            .map { v ->
                VoiceInfo(
                    id = TtsVoices.format(VoiceSelector.System(enginePkg, v.name)),
                    label = v.name,
                    language = v.locale.toLanguageTag(),
                    quality = TtsVoices.qualityBucket(v.quality),
                    installed = !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
                    requiresNetwork = v.isNetworkConnectionRequired,
                )
            }
    }
}
