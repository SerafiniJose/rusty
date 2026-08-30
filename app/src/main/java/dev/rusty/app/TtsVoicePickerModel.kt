package dev.rusty.app

import android.content.Context
import android.speech.tts.TextToSpeech

/**
 * What the on-device announcement-voice picker may see and do — the settings-side twin of the
 * control runtime's `/api/tts/voices*` routes, over the same prefs, the same [PiperVoiceStore]
 * and the same process-wide [PiperDownloads] slot, so the page and the device never disagree
 * about what is installed, selected or downloading.
 *
 * [engine] is null on a device with NO system TTS engine installed at all — a normal state, not
 * a failure: it only means [TtsVoices.pickerRows] gets an empty system list. The default row and
 * every downloaded Piper voice stay selectable, and Piper announcements never touch
 * [TextToSpeech].
 */
class TtsVoicePickerModel(
    context: Context,
    private val engine: TextToSpeech?,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val store = PiperVoiceStore(app)
    private val catalog = PiperVoiceStore.loadCatalog(app)

    /** True when a system engine answered init, i.e. when "System default" can actually speak. */
    val hasSystemEngine: Boolean get() = engine != null

    /** Re-read on every call: a download completing mid-dialog adds a row. */
    fun rows(): List<VoiceInfo> = TtsVoices.pickerRows(
        piper = store.installedRows(catalog),
        system = engine?.let { SystemTtsVoices.list(it) } ?: emptyList(),
    )

    /** The downloadable catalog with live installed flags, the page's `catalog` member exactly. */
    fun catalogEntries(): List<ControlCatalogVoice> =
        catalog.map { ControlCatalogVoice(it, store.isInstalled(it.id)) }

    /** What the settings picker offers to download: the chosen tier, minus what is already
     *  installed (those are selectable rows instead). The routes keep listing the whole
     *  catalog — filtering is a picker's job, not the wire's. */
    fun downloadableEntries(): List<ControlCatalogVoice> =
        TtsVoices.downloadableFor(catalogEntries(), quality())

    /** Whether the chosen tier has any curated voice at all, so an empty download list can say
     *  WHY it is empty. */
    fun qualityHasVoices(): Boolean = TtsVoices.catalogFor(catalogEntries(), quality()).isNotEmpty()

    /** The catalog entry a selectable row stands for, or null when the row is not a downloaded
     *  Piper voice (the default row, or a system engine's voice — neither is ours to delete). */
    fun installedVoiceFor(voice: VoiceInfo): PiperVoice? =
        (TtsVoices.parse(voice.id) as? VoiceSelector.Piper)
            ?.let { sel -> catalog.firstOrNull { it.id == sel.voiceId } }
            ?.takeIf { store.isInstalled(it.id) }

    /** The tier announcements download at, [VoiceQuality.DEFAULT] until the user changes it. */
    fun quality(): VoiceQuality =
        TtsVoices.selectedQuality(prefs.getString(TtsVoices.PREF_QUALITY_KEY, null))

    /** Persists the tier. Never touches [TtsVoices.PREF_KEY]: a voice already downloaded stays
     *  selected and installed whatever tier it belongs to. */
    fun selectQuality(quality: VoiceQuality) {
        prefs.edit().putString(TtsVoices.PREF_QUALITY_KEY, quality.wire).apply()
    }

    fun downloadSnapshot(): VoiceDownloadSnapshot = PiperDownloads.snapshot()

    /** The catalog's human name for a bare voice id, or null when it is not curated. */
    fun catalogLabelFor(voiceId: String?): String? =
        voiceId?.let { id -> catalog.firstOrNull { it.id == id }?.label }

    fun selectedId(): String {
        val raw = prefs.getString(TtsVoices.PREF_KEY, null) ?: return TtsVoices.SYSTEM_DEFAULT
        // A corrupted or future-format pref must not select nothing at all.
        return if (TtsVoices.parse(raw) != null) raw else TtsVoices.SYSTEM_DEFAULT
    }

    fun select(voice: VoiceInfo) {
        prefs.edit().putString(TtsVoices.PREF_KEY, voice.id).apply()
    }

    /** The settings row's one-line value for the current selection. */
    fun selectionLabel(): String = when (val sel = TtsVoices.parse(selectedId())) {
        null, VoiceSelector.SystemDefault -> "System default"
        is VoiceSelector.System -> sel.voiceName
        // The catalog's human label when the id is curated; the raw id only for an unknown one.
        is VoiceSelector.Piper -> catalog.firstOrNull { it.id == sel.voiceId }?.label ?: sel.voiceId
    }

    /** Starts a download; null on success, else the message to show. */
    fun download(voice: PiperVoice): String? = when (PiperDownloads.start(app, voice)) {
        ControlVoiceDownloadStart.STARTED -> null
        ControlVoiceDownloadStart.BUSY -> "Another voice is downloading — wait for it to finish."
        ControlVoiceDownloadStart.NO_SPACE ->
            "Not enough free space for ${voice.label} (${TtsVoices.megabytes(voice.sizeBytes)})."
        ControlVoiceDownloadStart.UNKNOWN_VOICE -> "That voice is no longer in the catalog."
    }

    /** Removes a downloaded voice; null on success, else the message to show. */
    fun delete(voice: PiperVoice): String? =
        when (PiperDownloads.deleteVoice(app, prefs, voice.id)) {
            VoiceDeleteOutcome.DELETED -> null
            VoiceDeleteOutcome.BUSY -> "${voice.label} is downloading — wait for it to finish."
            VoiceDeleteOutcome.NOT_INSTALLED -> "${voice.label} isn't installed."
        }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
