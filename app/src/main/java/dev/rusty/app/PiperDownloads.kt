package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The ONE voice-download slot in the process, plus the delete that mirrors it.
 *
 * Two surfaces install Piper voices — the control page (`POST /api/tts/voices/download`) and the
 * on-device settings picker — and [VoiceDownloadPhase] is documented single-slot for a reason:
 * `PiperVoiceStore.extract` publishes `<id>.tmp/` over `<id>/` with a rename, so two managers
 * working the same voice would race a recursive delete against that publish. Both surfaces go
 * through this object, so "busy" means busy everywhere and the page's progress poll reports a
 * download the settings picker started (and vice versa).
 *
 * Voice files changing on disk must also drop any model a live [PiperEngine] has mapped under
 * that id — a re-download republishes the same directory. Whoever holds an engine registers an
 * [addEngineInvalidator] hook rather than this object reaching into it, so the settings picker
 * can delete a voice the control service is holding without knowing the service exists.
 */
object PiperDownloads {

    /** One thread for the pipeline: a 67 MB fetch must never occupy an HTTP pool thread (the
     *  pool is what answers the page's progress polls). Daemon, so it cannot pin the process. */
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "voice-download").apply { isDaemon = true }
    }

    private val invalidators = CopyOnWriteArrayList<() -> Unit>()
    private val lock = Any()
    @Volatile private var manager: VoiceDownloadManager? = null

    private fun manager(context: Context): VoiceDownloadManager {
        manager?.let { return it }
        synchronized(lock) {
            manager?.let { return it }
            val store = PiperVoiceStore(context.applicationContext)
            return VoiceDownloadManager(
                executor = executor,
                download = store::download,
                verify = store::verify,
                extract = { voice, archive ->
                    store.extract(voice, archive)
                    // After the atomic publish, on the download thread: the engine may still hold
                    // the OLD model mapped under this id.
                    notifyVoiceFilesChanged()
                },
            ).also { manager = it }
        }
    }

    /** IDLE until something has actually asked for a download this process. */
    fun snapshot(): VoiceDownloadSnapshot = manager?.snapshot() ?: VoiceDownloadSnapshot.IDLE

    /**
     * Starts a download, or reports why not. A re-download of an installed voice is allowed on
     * purpose: [PiperVoiceStore.extract] publishes atomically over the old directory, so it
     * doubles as a repair path.
     */
    fun start(context: Context, voice: PiperVoice): ControlVoiceDownloadStart {
        val store = PiperVoiceStore(context.applicationContext)
        if (store.freeBytes() < PiperCatalog.requiredBytes(voice.sizeBytes)) {
            return ControlVoiceDownloadStart.NO_SPACE
        }
        return if (manager(context).start(voice)) ControlVoiceDownloadStart.STARTED
        else ControlVoiceDownloadStart.BUSY
    }

    /**
     * Removes an installed voice and, when the persisted selection named it, falls the selection
     * back to [TtsVoices.SYSTEM_DEFAULT]. Refuses while the single slot is mid-flight ON THIS
     * VOICE — the extractor's atomic publish would race the recursive delete; any OTHER voice
     * deletes fine.
     */
    fun deleteVoice(context: Context, prefs: SharedPreferences, voiceId: String): VoiceDeleteOutcome {
        val download = snapshot()
        if (download.busy && download.voiceId == voiceId) return VoiceDeleteOutcome.BUSY
        val store = PiperVoiceStore(context.applicationContext)
        if (!store.isInstalled(voiceId)) return VoiceDeleteOutcome.NOT_INSTALLED
        // Drop any mapped model BEFORE the files go: unmapping under a running synthesis is
        // native code.
        notifyVoiceFilesChanged()
        store.delete(voiceId)
        if (TtsVoices.parse(prefs.getString(TtsVoices.PREF_KEY, null) ?: "") == VoiceSelector.Piper(voiceId)) {
            prefs.edit().putString(TtsVoices.PREF_KEY, TtsVoices.SYSTEM_DEFAULT).apply()
        }
        return VoiceDeleteOutcome.DELETED
    }

    /** Registered by whoever holds a live [PiperEngine]; called whenever a voice's files change. */
    fun addEngineInvalidator(hook: () -> Unit) {
        invalidators.add(hook)
    }

    fun removeEngineInvalidator(hook: () -> Unit) {
        invalidators.remove(hook)
    }

    /** Hooks run OUTSIDE [lock] — a hook takes its owner's announce lock, and holding this
     *  object's lock across that would invent a lock cycle. */
    private fun notifyVoiceFilesChanged() {
        invalidators.forEach { runCatching { it() } }
    }
}
