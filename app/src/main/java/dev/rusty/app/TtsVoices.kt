package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which voice announcements speak with, as an opaque persisted selector string.
 *
 * The selector's PREFIX routes synthesis — `system:` voices go through Android's [TextToSpeech]
 * engine, `piper:` voices through the embedded [PiperEngine] over a downloaded model; both share
 * the same pref and the same picker. Parsing lives here in the pure layer so the wire shapes
 * and the routing rule are pinned by JVM tests, like the panel ids and lockscreen themes.
 */
sealed class VoiceSelector {
    /** Whatever voice the device's default TTS engine picks on its own. */
    object SystemDefault : VoiceSelector()

    /** A named voice of a system TTS engine: `system:<engine.package>/<voiceName>`. */
    data class System(val enginePackage: String, val voiceName: String) : VoiceSelector()

    /** A downloadable Piper voice: `piper:<voiceId>`. [voiceId] is the bare catalog id, which
     *  doubles as the voice's on-disk directory name and its `<id>.onnx` model filename. */
    data class Piper(val voiceId: String) : VoiceSelector()
}

/**
 * One row of the voice picker: a voice the user may select, in the JSON shape
 * `GET /api/tts/voices` lists.
 */
data class VoiceInfo(
    /** Full selector, exactly what `POST /api/tts/voice` takes back. */
    val id: String,
    val label: String,
    /** BCP-47 tag, e.g. `en-US`. */
    val language: String,
    /** Coarse bucket: `low` / `normal` / `high` / `very_high`. */
    val quality: String,
    /** False for a system voice whose data the engine has not downloaded yet, and for a
     *  catalog Piper voice not yet on disk. */
    val installed: Boolean,
    val requiresNetwork: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("language", language)
        put("quality", quality)
        put("installed", installed)
        put("requiresNetwork", requiresNetwork)
    }
}

/** One downloadable catalog voice as the page lists it: the curated entry plus whether it is
 *  already on disk (an installed one also appears in [ControlTtsVoices.voices], selectable). */
data class ControlCatalogVoice(
    val voice: PiperVoice,
    val installed: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", voice.selector)
        put("label", voice.label)
        put("language", voice.language)
        put("quality", voice.quality)
        put("sizeBytes", voice.sizeBytes)
        put("license", voice.license)
        put("attribution", voice.attribution)
        put("installed", installed)
    }
}

/**
 * What `GET /api/tts/voices` answers: the persisted selection, everything selectable, the
 * downloadable catalog, and the (single-slot) download state the page polls — the update card's
 * accepted-then-observe pattern.
 */
data class ControlTtsVoices(
    val selected: String,
    val voices: List<VoiceInfo>,
    val catalog: List<ControlCatalogVoice> = emptyList(),
    val download: VoiceDownloadSnapshot = VoiceDownloadSnapshot.IDLE,
) {
    fun toJson(): String = JSONObject().apply {
        put("selected", selected)
        put("voices", JSONArray(voices.map { it.toJson() }))
        put("catalog", JSONArray(catalog.map { it.toJson() }))
        put("download", download.toJson())
    }.toString()
}

/** Outcome of a `POST /api/tts/voice`. */
sealed class ControlTtsVoiceResult {
    /** Applied to prefs (the source of truth), so [voices] already reports the new selection. */
    data class Ok(val voices: ControlTtsVoices) : ControlTtsVoiceResult()

    /** A well-formed selector naming a voice this device does not have. */
    object UnknownVoice : ControlTtsVoiceResult()

    /** The system TTS engine failed to initialise, so system voices cannot be verified. */
    object TtsUnavailable : ControlTtsVoiceResult()
}

object TtsVoices {

    /** SharedPreferences key holding the persisted selector. */
    const val PREF_KEY = "tts_voice"

    /** The selector every device starts on, and what a deleted voice falls back to. */
    const val SYSTEM_DEFAULT = "system:default"

    private const val SYSTEM_PREFIX = "system:"
    private const val PIPER_PREFIX = "piper:"

    /**
     * Parses a persisted or wire selector; null for anything malformed, so a bad `id` is a 400
     * in the protocol layer and a corrupted pref falls back to [SYSTEM_DEFAULT] in the runtime.
     */
    fun parse(raw: String): VoiceSelector? = when {
        raw == SYSTEM_DEFAULT -> VoiceSelector.SystemDefault
        raw.startsWith(SYSTEM_PREFIX) -> {
            val rest = raw.removePrefix(SYSTEM_PREFIX)
            val slash = rest.indexOf('/')
            val engine = if (slash > 0) rest.substring(0, slash) else ""
            val voice = if (slash > 0) rest.substring(slash + 1) else ""
            if (engine.isNotBlank() && voice.isNotBlank()) VoiceSelector.System(engine, voice) else null
        }
        raw.startsWith(PIPER_PREFIX) -> {
            val id = raw.removePrefix(PIPER_PREFIX)
            if (id.isNotBlank()) VoiceSelector.Piper(id) else null
        }
        else -> null
    }

    /** Inverse of [parse]; the string is what gets persisted and what the API speaks. */
    fun format(selector: VoiceSelector): String = when (selector) {
        VoiceSelector.SystemDefault -> SYSTEM_DEFAULT
        is VoiceSelector.System -> "$SYSTEM_PREFIX${selector.enginePackage}/${selector.voiceName}"
        is VoiceSelector.Piper -> "$PIPER_PREFIX${selector.voiceId}"
    }

    /**
     * Maps Android's `Voice.QUALITY_*` int (100..500 in steps of 100) to the coarse wire bucket.
     * Kept here, off-device, because the mapping is a wire contract the page relies on.
     */
    fun qualityBucket(quality: Int): String = when {
        quality >= 400 -> "very_high"
        quality >= 300 -> "high"
        quality >= 200 -> "normal"
        else -> "low"
    }
}
