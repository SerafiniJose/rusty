package dev.rusty.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Best-effort "start on boot": when [KEY_START_ON_BOOT] is set, start the foreground receiver
 * service so the device is a discoverable Spotify Connect target without opening the app.
 *
 * Known limitation: apps targeting Android 15+ may NOT launch a mediaPlayback foreground service
 * from BOOT_COMPLETED — startForegroundService throws ForegroundServiceStartNotAllowedException.
 * We catch + log it, so this works on Android <=14 and silently no-ops on 15+. A robust fix
 * (an allowed-from-boot FGS type that transitions to mediaPlayback) is a future sub-project.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_START_ON_BOOT, false)) return

        val deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        val bitrateKbps = prefs.getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS)
        val serviceIntent = Intent(context, SpotifyService::class.java).apply {
            putExtra("DEVICE_NAME", deviceName)
            putExtra("BITRATE_KBPS", bitrateKbps)
        }
        runCatching { context.startForegroundService(serviceIntent) }
            .onFailure { e ->
                Log.w(TAG, "start-on-boot skipped (FGS boot restriction on Android 15+?)", e)
                prefs.edit()
                    .putLong(KEY_LAST_BOOT_START_ATTEMPT, System.currentTimeMillis())
                    .putBoolean(KEY_LAST_BOOT_START_OK, false)
                    .apply()
            }
    }

    companion object {
        const val KEY_START_ON_BOOT = "start_on_boot"
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_BITRATE_KBPS = "bitrate_kbps"
        private const val DEFAULT_DEVICE_NAME = "Android Speaker"
        private const val DEFAULT_BITRATE_KBPS = 160
        const val KEY_LAST_BOOT_START_ATTEMPT = "last_boot_start_attempt"
        const val KEY_LAST_BOOT_START_OK = "last_boot_start_ok"
    }
}
