package dev.rusty.app

import java.io.File
import java.util.concurrent.Executor

/** Where an app-update install currently is. One in flight at most, process-wide. */
enum class InstallPhase { IDLE, DOWNLOADING, AWAITING_CONFIRM, ERROR }

/** [progress]: 0–100 while downloading with a known length, null when indeterminate or
 *  not downloading. [error]: set only in [InstallPhase.ERROR]. */
data class InstallSnapshot(
    val phase: InstallPhase,
    val progress: Int?,
    val error: String?,
)

/**
 * State machine for "download the release APK, hand it to the system installer, wait for
 * the on-device confirmation". Pure JVM — no `android.*` imports — so the whole lifecycle
 * is unit-testable off-device: the blocking [download] and the PackageInstaller [commit]
 * are injected seams (real ones in [ApkInstall]), and the pipeline runs on the injected
 * [executor] (a single background thread in production, a same-thread executor in tests).
 *
 * Shared by the remote-control API and the About sheet, so its snapshot type crosses the
 * [ControlRuntime] seam. Terminal outcomes after commit arrive from the system via
 * [onInstallAborted]/[onInstallFailed]; install success never needs handling — the
 * process is replaced by the new APK.
 *
 * [InstallPhase.ERROR] is sticky (kept on screen until the user acts) but not busy:
 * a later [start] retries. Only [InstallPhase.DOWNLOADING] and
 * [InstallPhase.AWAITING_CONFIRM] reject a new [start].
 */
class UpdateInstaller(
    private val executor: Executor,
    /** Blocking; reports 0–100 or null (indeterminate); throws on failure. */
    private val download: (url: String, onProgress: (Int?) -> Unit) -> File,
    /** Writes the APK into a PackageInstaller session and commits; throws on failure. */
    private val commit: (apk: File) -> Unit,
) {
    private val lock = Any()
    private var state = InstallSnapshot(InstallPhase.IDLE, null, null)

    fun snapshot(): InstallSnapshot = synchronized(lock) { state }

    /** Kicks off download+commit on [executor]. False when one is already in flight. */
    fun start(apkUrl: String): Boolean {
        synchronized(lock) {
            if (state.phase == InstallPhase.DOWNLOADING || state.phase == InstallPhase.AWAITING_CONFIRM) {
                return false
            }
            state = InstallSnapshot(InstallPhase.DOWNLOADING, null, null)
        }
        executor.execute {
            try {
                val apk = download(apkUrl) { pct ->
                    synchronized(lock) {
                        // A late progress callback must not resurrect a finished/failed install.
                        if (state.phase == InstallPhase.DOWNLOADING) {
                            state = InstallSnapshot(InstallPhase.DOWNLOADING, pct, null)
                        }
                    }
                }
                commit(apk)
                synchronized(lock) { state = InstallSnapshot(InstallPhase.AWAITING_CONFIRM, null, null) }
            } catch (t: Throwable) {
                onInstallFailed(t.message ?: "install failed")
            }
        }
        return true
    }

    /** System reported the user declined the confirmation dialog. */
    fun onInstallAborted() {
        synchronized(lock) { state = InstallSnapshot(InstallPhase.IDLE, null, null) }
    }

    /** System (or the pipeline itself) reported a failure. */
    fun onInstallFailed(message: String) {
        synchronized(lock) { state = InstallSnapshot(InstallPhase.ERROR, null, message) }
    }
}
