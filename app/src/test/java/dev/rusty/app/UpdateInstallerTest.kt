package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The installer runs its pipeline on an injected executor; tests use a same-thread
 * executor so every transition is observable synchronously. The download/commit seams
 * are fakes — the real ones (HTTP + PackageInstaller) live in ApkInstall and are
 * exercised on-device only.
 */
class UpdateInstallerTest {

    private val directExecutor = Executor { it.run() }
    private val apkFile = File("build/tmp/fake-update.apk")

    private fun installer(
        download: (String, (Int?) -> Unit) -> File = { _, _ -> apkFile },
        commit: (File) -> Unit = {},
    ) = UpdateInstaller(directExecutor, download, commit)

    @Test fun startsIdle() {
        assertEquals(InstallSnapshot(InstallPhase.IDLE, null, null), installer().snapshot())
    }

    @Test fun happyPath_downloadsCommitsAndAwaitsConfirm() {
        var downloadedUrl: String? = null
        var committed: File? = null
        val inst = installer(
            download = { url, _ -> downloadedUrl = url; apkFile },
            commit = { committed = it },
        )
        assertTrue(inst.start("https://example.com/rusty-v9.9.9.apk"))
        assertEquals("https://example.com/rusty-v9.9.9.apk", downloadedUrl)
        assertEquals(apkFile, committed)
        assertEquals(InstallPhase.AWAITING_CONFIRM, inst.snapshot().phase)
        assertNull(inst.snapshot().error)
    }

    @Test fun progressVisibleMidDownload() {
        lateinit var inst: UpdateInstaller
        var midDownload: InstallSnapshot? = null
        inst = installer(download = { _, onProgress ->
            onProgress(42)
            midDownload = inst.snapshot()
            apkFile
        })
        inst.start("u")
        assertEquals(InstallSnapshot(InstallPhase.DOWNLOADING, 42, null), midDownload)
    }

    @Test fun indeterminateProgressIsNull() {
        lateinit var inst: UpdateInstaller
        var midDownload: InstallSnapshot? = null
        inst = installer(download = { _, onProgress ->
            onProgress(null)
            midDownload = inst.snapshot()
            apkFile
        })
        inst.start("u")
        assertEquals(InstallSnapshot(InstallPhase.DOWNLOADING, null, null), midDownload)
    }

    @Test fun downloadFailure_errorAndCommitNotCalled() {
        var committed = false
        val inst = installer(
            download = { _, _ -> throw IOException("download HTTP 503") },
            commit = { committed = true },
        )
        assertTrue(inst.start("u"))
        assertFalse(committed)
        assertEquals(InstallSnapshot(InstallPhase.ERROR, null, "download HTTP 503"), inst.snapshot())
    }

    @Test fun commitFailure_error() {
        val inst = installer(commit = { throw SecurityException("no permission") })
        inst.start("u")
        assertEquals(InstallPhase.ERROR, inst.snapshot().phase)
        assertEquals("no permission", inst.snapshot().error)
    }

    @Test fun failureWithoutMessage_getsFallbackText() {
        val inst = installer(download = { _, _ -> throw IOException() })
        inst.start("u")
        assertEquals("install failed", inst.snapshot().error)
    }

    @Test fun busyWhileAwaitingConfirm_secondStartRejected() {
        var downloads = 0
        val inst = installer(download = { _, _ -> downloads++; apkFile })
        assertTrue(inst.start("u"))
        assertEquals(InstallPhase.AWAITING_CONFIRM, inst.snapshot().phase)
        assertFalse(inst.start("u"))
        assertEquals(1, downloads)
    }

    @Test fun abortedReturnsToIdle_andCanStartAgain() {
        val inst = installer()
        inst.start("u")
        inst.onInstallAborted()
        assertEquals(InstallSnapshot(InstallPhase.IDLE, null, null), inst.snapshot())
        assertTrue(inst.start("u"))
    }

    @Test fun errorIsStickyButNotBusy_retryAllowed() {
        var attempts = 0
        val inst = installer(download = { _, _ ->
            attempts++
            if (attempts == 1) throw IOException("flaky") else apkFile
        })
        inst.start("u")
        assertEquals(InstallPhase.ERROR, inst.snapshot().phase)
        assertTrue(inst.start("u"))
        assertEquals(InstallPhase.AWAITING_CONFIRM, inst.snapshot().phase)
    }

    @Test fun systemFailureCallback_error() {
        val inst = installer()
        inst.start("u")
        inst.onInstallFailed("INSTALL_FAILED_INVALID_APK")
        assertEquals(InstallSnapshot(InstallPhase.ERROR, null, "INSTALL_FAILED_INVALID_APK"), inst.snapshot())
    }
}
