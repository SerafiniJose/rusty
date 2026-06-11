package dev.rusty.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.UUID

class SpotifyService : Service() {

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    // Read on JNI callback threads (publishStatus/publishPlayback) and now also
    // written from the UI thread on an in-place rename, so keep it @Volatile.
    @Volatile
    private var currentDeviceName: String = DEFAULT_DEVICE_NAME
    private var currentBitrateKbps: Int = DEFAULT_BITRATE_KBPS

    // Spotify account username for the connected session, published with status
    // broadcasts. Set from the native connect callback (a JNI thread) and read
    // when broadcasting, so it is @Volatile.
    @Volatile
    private var currentSessionUser: String? = null
    @Volatile private var currentSessionDisplayName: String? = null
    @Volatile private var currentSessionAvatarUrl: String? = null
    @Volatile private var currentTrackId: String? = null

    // Name we have already asked the native layer to start. Guards against
    // START_STICKY redeliveries spawning duplicate native receivers. Reset to
    // null on failure (to allow a retry) and in onDestroy.
    @Volatile
    private var nativeStartedConfig: NativeReceiverConfig? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        NativeBridge.initAndroidContext(applicationContext, applicationContext.cacheDir.absolutePath)
        NativeBridge.initLogger()
        acquireLocks()
        startForeground(1, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ReceiverDashboardBroadcast.ACTION_STOP) {
            Log.i("SpotifyService", "Stop requested via ACTION_STOP; tearing down")
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val deviceName = intent?.getStringExtra("DEVICE_NAME")
            ?: prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME)
            ?: DEFAULT_DEVICE_NAME
        val requestedBitrateKbps = intent?.getIntExtra("BITRATE_KBPS", DEFAULT_BITRATE_KBPS)
            ?: prefs.getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS)
        val bitrateKbps = requestedBitrateKbps
            .takeIf { it in SUPPORTED_BITRATES_KBPS }
            ?: DEFAULT_BITRATE_KBPS
        currentDeviceName = deviceName
        currentBitrateKbps = bitrateKbps
        publishStatus(ReceiverDashboardStatusEvent.Lifecycle.FOREGROUND)

        // Duplicate-start guard: START_STICKY (and re-delivered intents) can call
        // onStartCommand repeatedly with the same name. The native layer is also
        // idempotent, but skipping here avoids spawning redundant threads.
        val requestedConfig = NativeReceiverConfig(deviceName, bitrateKbps)
        if (nativeStartedConfig == requestedConfig) {
            Log.i("SpotifyService", "Native receiver already started as '$deviceName' at ${bitrateKbps}kbps; skipping duplicate start")
            return START_STICKY
        }
        nativeStartedConfig = requestedConfig
        val deviceId = resolveDeviceId()

        // start the Rust engine
        Thread {
            try {
                publishStatus(ReceiverDashboardStatusEvent.Lifecycle.NATIVE_STARTING)
                NativeBridge.startDevice(deviceName, deviceId, bitrateKbps)
            } catch (throwable: Throwable) {
                Log.e("SpotifyService", "Native receiver failed", throwable)
                nativeStartedConfig = null
                publishStatus(
                    ReceiverDashboardStatusEvent.Lifecycle.ERROR,
                    throwable.message ?: throwable.javaClass.simpleName
                )
            }
        }.start()

        return START_STICKY
    }

    /**
     * Returns a stable per-install device id, generating and persisting one on
     * first use. It is intentionally kept stable across renames so Spotify does
     * not treat each rename as a brand-new receiver.
     */
    private fun resolveDeviceId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        Log.i("SpotifyService", "Generated persistent device id for this install")
        return generated
    }

    private fun publishStatus(
        lifecycle: ReceiverDashboardStatusEvent.Lifecycle,
        message: String? = null
    ) {
        // Keep the process-wide snapshot current so an Activity that was stopped
        // (screensaver / Now Playing) catches up when it re-seeds on start.
        DashboardStateHolder.current = ReceiverDashboardStatusEvent(
            receiverName = currentDeviceName,
            lifecycle = lifecycle,
            message = message,
            sessionUser = currentSessionUser,
            sessionDisplayName = currentSessionDisplayName,
            sessionAvatarUrl = currentSessionAvatarUrl
        ).toDashboardState(DashboardStateHolder.current)

        sendBroadcast(Intent(ReceiverDashboardBroadcast.ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(ReceiverDashboardBroadcast.EXTRA_RECEIVER_NAME, currentDeviceName)
            putExtra(ReceiverDashboardBroadcast.EXTRA_LIFECYCLE, lifecycle.name)
            message?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_MESSAGE, it) }
            currentSessionUser?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_USER, it) }
            currentSessionDisplayName?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_DISPLAY_NAME, it) }
            currentSessionAvatarUrl?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_AVATAR_URL, it) }
        })
    }

    private fun publishPlayback(
        playbackState: String,
        title: String?,
        artist: String?,
        elapsedMs: Long,
        durationMs: Long,
        queueTitle: String?,
        queueArtist: String?,
        queueUnavailable: Boolean,
        coverUrl: String?,
        trackId: String?
    ) {
        currentTrackId = trackId?.takeIf { it.isNotBlank() } ?: currentTrackId

        ReceiverDashboardPlaybackEvent.PlaybackState.fromWireName(playbackState)?.let { state ->
            DashboardStateHolder.current = ReceiverDashboardPlaybackEvent(
                receiverName = currentDeviceName,
                playbackState = state,
                trackTitle = title,
                trackArtist = artist,
                coverArtUrl = coverUrl,
                trackId = currentTrackId,
                sessionUser = currentSessionUser,
                sessionDisplayName = currentSessionDisplayName,
                sessionAvatarUrl = currentSessionAvatarUrl,
                elapsedMs = elapsedMs,
                durationMs = durationMs,
                queueTitle = queueTitle,
                queueArtist = queueArtist,
                queueUnavailable = queueUnavailable
            ).toDashboardState(DashboardStateHolder.current)
            // Ground-truth position for any screen's playback clock (see DashboardStateHolder).
            DashboardStateHolder.anchorPlayback(
                elapsedMs,
                playing = state == ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING
            )
        }

        sendBroadcast(Intent(ReceiverDashboardBroadcast.ACTION_PLAYBACK).apply {
            setPackage(packageName)
            putExtra(ReceiverDashboardBroadcast.EXTRA_RECEIVER_NAME, currentDeviceName)
            putExtra(ReceiverDashboardBroadcast.EXTRA_PLAYBACK_STATE, playbackState)
            putExtra(ReceiverDashboardBroadcast.EXTRA_TRACK_TITLE, title)
            putExtra(ReceiverDashboardBroadcast.EXTRA_TRACK_ARTIST, artist)
            putExtra(ReceiverDashboardBroadcast.EXTRA_ELAPSED_MS, elapsedMs)
            putExtra(ReceiverDashboardBroadcast.EXTRA_DURATION_MS, durationMs)
            putExtra(ReceiverDashboardBroadcast.EXTRA_QUEUE_UNAVAILABLE, queueUnavailable)
            queueTitle?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_QUEUE_TITLE, it) }
            queueArtist?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_QUEUE_ARTIST, it) }
            coverUrl?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_COVER_URL, it) }
            currentTrackId?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_TRACK_ID, it) }
            currentSessionUser?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_USER, it) }
            currentSessionDisplayName?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_DISPLAY_NAME, it) }
            currentSessionAvatarUrl?.let { putExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_AVATAR_URL, it) }
        })
    }

    /** Resolves the connected account's display name + avatar off the main thread, then re-publishes. */
    private fun resolveSessionProfile(token: String) {
        val username = currentSessionUser ?: return
        val self = this
        Thread {
            val profile = ProfileRepository.fetch(username, token) ?: return@Thread
            // Drop the update if the service was destroyed while we were fetching.
            if (activeService !== self) return@Thread
            currentSessionDisplayName = profile.displayName?.takeIf { it.isNotBlank() }
            currentSessionAvatarUrl = profile.avatarUrl?.takeIf { it.isNotBlank() }
            publishStatus(ReceiverDashboardStatusEvent.Lifecycle.CONNECTED)
        }.start()
    }

    private fun acquireLocks() {
        //wifi multicast lock - mdns requirement for spot connect discovery
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("SpotifyDiscoveryLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        //wake lock - run when screen off
        val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "SpotifyReceiver::WakeLock").apply {
            acquire()
        }
    }

    private fun createNotification(): Notification {
        val channelId = "spotify_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Spotify Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, SpotifyService::class.java).apply {
            action = ReceiverDashboardBroadcast.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getForegroundService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, NowPlayingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Spotify Receiver Active")
            .setContentText("Listening for connections...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    override fun onDestroy() {
        currentSessionUser = null
        currentSessionDisplayName = null
        currentSessionAvatarUrl = null
        currentTrackId = null
        TokenStore.clear()
        ProfileRepository.clear()
        // Order matters: emit the playback-STOPPED reset FIRST (it clears the now-playing
        // track and tells LyricsActivity to close), then publish OFF LAST so the final
        // dashboard state is the honest "Off" — not the "listening" health a STOPPED
        // playback event maps to. Publishing OFF last makes it win in both the
        // DashboardStateHolder snapshot (written in call order) and the broadcast the
        // Activity receives (delivered in send order).
        publishPlayback(
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED.name,
            title = null,
            artist = null,
            elapsedMs = 0L,
            durationMs = 0L,
            queueTitle = null,
            queueArtist = null,
            queueUnavailable = true,
            coverUrl = null,
            trackId = null
        )
        publishStatus(ReceiverDashboardStatusEvent.Lifecycle.OFF)
        NativeBridge.stopDevice()
        nativeStartedConfig = null
        multicastLock?.release()
        wakeLock?.release()
        activeService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_BITRATE_KBPS = "bitrate_kbps"
        private const val DEFAULT_DEVICE_NAME = "Android Speaker"
        private const val DEFAULT_BITRATE_KBPS = 160
        private val SUPPORTED_BITRATES_KBPS = setOf(96, 160, 320)

        @Volatile
        private var activeService: SpotifyService? = null

        /**
         * Keeps the running service's broadcast identity in sync after an in-place
         * rename (NativeBridge.renameDevice). Without this, the next status/playback
         * broadcast would carry the old name and revert the UI. Does not restart the
         * service or native receiver.
         */
        @JvmStatic
        fun onReceiverRenamed(newName: String) {
            val sanitized = newName.trim().takeIf { it.isNotBlank() } ?: return
            activeService?.let { service ->
                service.currentDeviceName = sanitized
                service.nativeStartedConfig = service.nativeStartedConfig?.copy(deviceName = sanitized)
            }
            DashboardStateHolder.current = DashboardStateHolder.current.copy(receiverName = sanitized)
        }

        @JvmStatic
        fun onNativeReceiverConnected(username: String?) {
            activeService?.let { service ->
                service.currentSessionUser = username?.takeIf { it.isNotBlank() }
                service.publishStatus(ReceiverDashboardStatusEvent.Lifecycle.CONNECTED)
                NativeBridge.requestAccessToken()
            }
        }

        /**
         * The native session ended on its own — the controller logged out, the network
         * dropped the receiver's session, or it sat idle past the inactivity timeout.
         * Return the dashboard to the idle "Ready for Spotify" face WITHOUT tearing the
         * service down: the native receiver stays running and discoverable for the next
         * connection (which may be a different account). Mirrors the state-clearing half
         * of [onDestroy], minus stopDevice()/lock releases/activeService reset.
         */
        @JvmStatic
        fun onNativeReceiverDisconnected() {
            activeService?.let { service ->
                service.currentSessionUser = null
                service.currentSessionDisplayName = null
                service.currentSessionAvatarUrl = null
                service.currentTrackId = null
                TokenStore.clear()
                ProfileRepository.clear()
                service.publishStatus(ReceiverDashboardStatusEvent.Lifecycle.STOPPED)
                service.publishPlayback(
                    playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED.name,
                    title = null,
                    artist = null,
                    elapsedMs = 0L,
                    durationMs = 0L,
                    queueTitle = null,
                    queueArtist = null,
                    queueUnavailable = true,
                    coverUrl = null,
                    trackId = null
                )
            }
        }

        @JvmStatic
        fun onNativeAccessToken(token: String?, expiresInSecs: Int) {
            val service = activeService ?: return
            if (token.isNullOrBlank()) {
                Log.w("SpotifyService", "onNativeAccessToken: empty token")
                return
            }
            TokenStore.update(token, expiresInSecs)
            // Bare signal — the token lives in TokenStore, not in the broadcast.
            service.sendBroadcast(Intent(ReceiverDashboardBroadcast.ACTION_TOKEN).apply {
                setPackage(service.packageName)
            })
            service.resolveSessionProfile(token)
        }

        @JvmStatic
        fun onNativePlaybackEvent(
            playbackState: String,
            title: String?,
            artist: String?,
            elapsedMs: Long,
            durationMs: Long,
            queueTitle: String?,
            queueArtist: String?,
            queueUnavailable: Boolean,
            coverUrl: String?,
            trackId: String?
        ) {
            activeService?.publishPlayback(
                playbackState = playbackState,
                title = title,
                artist = artist,
                elapsedMs = elapsedMs,
                durationMs = durationMs,
                queueTitle = queueTitle,
                queueArtist = queueArtist,
                queueUnavailable = queueUnavailable,
                coverUrl = coverUrl,
                trackId = trackId
            )
        }
    }

    private data class NativeReceiverConfig(
        val deviceName: String,
        val bitrateKbps: Int
    )
}