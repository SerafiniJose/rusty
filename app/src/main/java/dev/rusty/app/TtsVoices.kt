package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * How good a voice sounds, and how hard it works to say it — one vocabulary for both engines.
 *
 * The values are Piper's own tiers, because they are the ones a user actually chooses between
 * when downloading a voice; a system engine's five `Voice.QUALITY_*` constants fold onto them in
 * [TtsVoices.qualityBucket]. The tier is also the wire value of the `quality` member of
 * `GET /api/tts/voices`, so the page, the settings picker and the catalog asset all say the
 * same four words.
 */
enum class VoiceQuality(val wire: String, val label: String, val hint: String) {
    X_LOW("x_low", "Extra low", "For the oldest devices: smallest download, roughest voice"),
    LOW("low", "Low", "Quick to speak on modest hardware"),
    MEDIUM("medium", "Medium", "Fuller voice, a little more work per announcement"),
    HIGH("high", "High", "Clearest voice, slowest to speak and biggest to download");

    companion object {
        /** What a fresh install announces with: a voice every supported device can actually
         *  keep up with. Better audio is one row away, and costs the user nothing to try. */
        val DEFAULT = LOW

        /** Null for anything outside the vocabulary — a caller decides whether that is a
         *  rejected catalog entry ([PiperCatalog.parse]) or a pref falling back to
         *  [DEFAULT] ([TtsVoices.selectedQuality]). */
        fun parse(raw: String?): VoiceQuality? = entries.firstOrNull { it.wire == raw }
    }
}

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
    /** [VoiceQuality.wire]: `x_low` / `low` / `medium` / `high`. */
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

    /** SharedPreferences key holding the persisted [VoiceQuality.wire]. Separate from
     *  [PREF_KEY] on purpose: the tier chooses which voices are OFFERED, and survives picking,
     *  deleting and re-picking a voice. */
    const val PREF_QUALITY_KEY = "tts_voice_quality"

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
     * Maps Android's `Voice.QUALITY_*` int (100..500 in steps of 100) onto a [VoiceQuality].
     * Five constants, four tiers: HIGH and VERY_HIGH both land on [VoiceQuality.HIGH], which is
     * already the top of what an embedded voice offers. Kept here, off-device, because the
     * mapping is a wire contract.
     */
    fun qualityBucket(quality: Int): String = when {
        quality >= 400 -> VoiceQuality.HIGH.wire      // QUALITY_HIGH, QUALITY_VERY_HIGH
        quality >= 300 -> VoiceQuality.MEDIUM.wire    // QUALITY_NORMAL
        quality >= 200 -> VoiceQuality.LOW.wire       // QUALITY_LOW
        else -> VoiceQuality.X_LOW.wire               // QUALITY_VERY_LOW and below
    }

    /**
     * The tier a stored pref names. An unreadable value is [VoiceQuality.DEFAULT] rather than
     * nothing: a tier matching no catalog entry would show the user an empty download list and
     * no way to tell why.
     */
    fun selectedQuality(raw: String?): VoiceQuality = VoiceQuality.parse(raw) ?: VoiceQuality.DEFAULT

    // -- picker composition ----------------------------------------------------------------

    /** The always-present first row: let the engine pick, exactly what a fresh install does. */
    fun defaultRow(): VoiceInfo = VoiceInfo(
        id = SYSTEM_DEFAULT,
        label = "System default",
        language = "",
        quality = VoiceQuality.MEDIUM.wire,
        installed = true,
        requiresNetwork = false,
    )

    /**
     * The selectable rows, composed identically by every surface that offers the picker — the
     * control page's `GET /api/tts/voices` and the on-device settings panel.
     *
     * [system] is EMPTY on a device that has no TTS engine installed at all (LineageOS builds
     * routinely ship none), and that is a normal list, not a failure: the default row is static
     * and a downloaded Piper voice synthesizes through [PiperEngine] without ever touching
     * `TextToSpeech`. Composing here is what keeps a surface from re-inventing the rule and
     * gating the whole picker on a system engine it does not need.
     */
    fun pickerRows(piper: List<VoiceInfo>, system: List<VoiceInfo>): List<VoiceInfo> =
        listOf(defaultRow()) + piper + system

    /**
     * The settings row's one-line value now that the row is the ONLY entry point to voices:
     * the selection, plus how many voices are on disk — the count is what tells the user the
     * Manage card has content beyond the selection. Zero downloads is just the selection; the
     * card itself explains how to get more.
     */
    fun settingsRowValue(selectionLabel: String, installedCount: Int): String =
        if (installedCount <= 0) selectionLabel
        else "$selectionLabel · $installedCount downloaded"

    // -- downloadable catalog labels -------------------------------------------------------

    /**
     * The downloadable rows for one tier. Only the CATALOG narrows: an installed voice stays
     * listed and selectable whatever tier it is, because a dropdown must never hide a voice the
     * user already spent a download on — least of all the selected one.
     */
    fun catalogFor(entries: List<ControlCatalogVoice>, quality: VoiceQuality): List<ControlCatalogVoice> =
        entries.filter { it.voice.quality == quality.wire }

    /**
     * What is actually offered for download at one tier: [catalogFor] minus everything already
     * on disk. An installed voice is a selectable row with its own Remove button, so leaving it
     * here too would list the same voice twice, in two different moods.
     */
    fun downloadableFor(entries: List<ControlCatalogVoice>, quality: VoiceQuality): List<ControlCatalogVoice> =
        catalogFor(entries, quality).filterNot { it.installed }

    /** Shown in place of an empty download list. Two different emptinesses: everything at this
     *  tier is already here, or this tier has nothing for the languages we curate. */
    fun catalogEmptyNote(quality: VoiceQuality, allInstalled: Boolean): String = if (allInstalled) {
        "Every ${quality.label} voice is already downloaded."
    } else {
        "No ${quality.label} voices are available for the languages we curate \u2014 try another quality."
    }

    /**
     * What the small icon button on a catalog row means, for TalkBack and for the D-pad
     * tooltip — the page's `aria-label` exactly. The button itself is an icon: the list stays
     * scannable, and the words live where a decision is actually made (the confirm card).
     */
    fun catalogActionDescription(entry: ControlCatalogVoice): String =
        if (entry.installed) "Remove ${entry.voice.label}"
        else "Download ${entry.voice.label} (${megabytes(entry.voice.sizeBytes)})"

    /**
     * Size, license and attribution in full, shown in the download confirm card — the moment the
     * user actually decides. Every Piper voice carries its own dataset terms, so this text is
     * never optional; it is only moved off the scannable list and onto the decision.
     */
    fun catalogMeta(voice: PiperVoice): String =
        megabytes(voice.sizeBytes) + " \u00b7 " + voice.license + " \u00b7 " + voice.attribution

    /** The one-line form under a catalog row: what it costs and under what terms. */
    fun catalogMetaShort(voice: PiperVoice): String =
        megabytes(voice.sizeBytes) + " \u00b7 " + voice.license

    /**
     * The live download line for the picker's footer, or null when nothing is happening. One
     * status in one place, because the slot is single: a per-row spinner would be five ways of
     * saying the same thing. [label] is the voice's catalog name, or null if it is not curated.
     */
    fun downloadStatus(download: VoiceDownloadSnapshot, label: String?): String? {
        val name = label ?: download.voiceId ?: "voice"
        return when (download.phase) {
            VoiceDownloadPhase.DOWNLOADING ->
                "Downloading $name\u2026" + (download.progress?.let { " $it%" } ?: "")
            VoiceDownloadPhase.VERIFYING -> "Verifying $name\u2026"
            VoiceDownloadPhase.EXTRACTING -> "Unpacking $name\u2026"
            VoiceDownloadPhase.ERROR ->
                "Couldn't download $name: " + (download.error ?: "unknown error")
            VoiceDownloadPhase.IDLE, VoiceDownloadPhase.DONE -> null
        }
    }

    /** Whole megabytes, the page's rounding exactly. */
    fun megabytes(bytes: Long): String = "${Math.round(bytes / (1024.0 * 1024.0))} MB"
}
