package dev.rusty.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Android side of app self-update: the real download and PackageInstaller seams behind
 * the process-wide [UpdateInstaller] singleton (state machine + tests live there; nothing
 * here is JVM-testable, so this file stays a thin adapter).
 *
 * The install is never silent — REQUEST_INSTALL_PACKAGES only lets the app *hand* an APK
 * to the system installer, which shows its confirmation dialog on the device screen (and,
 * on first use, the one-time "allow installs from this source" screen). Success needs no
 * handling at all: the process is replaced by the new build.
 */
object ApkInstall {
    private const val TAG = "ApkInstall"

    /** One fixed name in cacheDir: a re-download after an abort/failure overwrites the
     *  previous attempt instead of accumulating APKs. */
    private const val CACHED_APK_NAME = "update.apk"

    @Volatile
    private var instance: UpdateInstaller? = null
    private val instanceLock = Any()

    fun installer(context: Context): UpdateInstaller {
        instance?.let { return it }
        synchronized(instanceLock) {
            instance?.let { return it }
            val app = context.applicationContext
            return UpdateInstaller(
                executor = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "rusty-apk-install").apply { isDaemon = true }
                },
                download = { url, onProgress -> downloadApk(app, url, onProgress) },
                commit = { apk -> commitSession(app, apk) },
            ).also { instance = it }
        }
    }

    // -------------------------------------------------------------------
    // Download seam
    // -------------------------------------------------------------------

    private fun downloadApk(context: Context, url: String, onProgress: (Int?) -> Unit): File {
        val dest = File(context.cacheDir, CACHED_APK_NAME)
        dest.delete()
        var conn: HttpURLConnection? = null
        try {
            // GitHub asset URLs 302 to a CDN host; HttpURLConnection follows same-protocol
            // redirects on its own, so no manual redirect loop is needed.
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "rusty-android")
                setRequestProperty("Accept", "application/octet-stream")
                connectTimeout = 15000
                readTimeout = 15000
            }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("download HTTP $code")
            val total = conn.contentLengthLong
            onProgress(if (total > 0) 0 else null)
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            return dest
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            conn?.disconnect()
        }
    }

    // -------------------------------------------------------------------
    // PackageInstaller seam
    // -------------------------------------------------------------------

    private fun commitSession(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = pi.createSession(params)
        try {
            pi.openSession(sessionId).use { session ->
                session.openWrite(CACHED_APK_NAME, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Explicit PendingIntent to the manifest-declared, non-exported receiver:
                // targetSdk 36 does not deliver the system's implicit status broadcasts to
                // runtime-registered non-exported receivers. FLAG_MUTABLE because the
                // installer service appends the status extras to this very intent.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, Intent(context, ResultReceiver::class.java), flags
                )
                session.commit(pending.intentSender)
            }
        } catch (t: Throwable) {
            runCatching { pi.abandonSession(sessionId) }
            throw t
        }
    }

    private fun deleteCachedApk(context: Context) {
        File(context.cacheDir, CACHED_APK_NAME).delete()
    }

    /** Receives PackageInstaller status broadcasts (see [commitSession] for why it must be
     *  manifest-declared). Non-exported: only the system's install service, firing our own
     *  explicit PendingIntent, ever targets it. */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                    if (confirm == null) {
                        installer(context).onInstallFailed("system confirm intent missing")
                        return
                    }
                    // Android 10+ suppresses background activity starts; fine here — Rusty is a
                    // full-screen appliance, so it's in the foreground when a user drives an
                    // update from its own About sheet or watches the control page's prompt.
                    try {
                        context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) {
                        Log.w(TAG, "couldn't launch system installer: ${e.message}")
                        installer(context).onInstallFailed("couldn't open the system installer")
                    }
                }
                PackageInstaller.STATUS_SUCCESS ->
                    // The new APK replaces this process momentarily; just tidy the cache.
                    deleteCachedApk(context)
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    deleteCachedApk(context)
                    installer(context).onInstallAborted()
                }
                else -> {
                    deleteCachedApk(context)
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.w(TAG, "install failed: status=$status $msg")
                    installer(context).onInstallFailed(msg ?: "install failed (status $status)")
                }
            }
        }
    }
}
