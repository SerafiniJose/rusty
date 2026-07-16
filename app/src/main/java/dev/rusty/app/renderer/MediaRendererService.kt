package dev.rusty.app.renderer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.rusty.app.HomeActivity
import dev.rusty.app.NativeBridge
import dev.rusty.app.R
import dev.rusty.app.ReceiverStateStore
import dev.rusty.app.RustyApp
import dev.rusty.app.ServiceNotifications
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Composition root for the DLNA MediaRenderer feature. Wires the tested pure layer
 * ([RendererStore]/reducer, [GenaSubscriptions], [RendererHttpProtocol], [SsdpMessages],
 * [UpnpEventXml]) to Android: an [ExoPlayer]-backed [RendererPlayer], a raw-socket
 * [RendererHttpServer]/[SsdpEndpoint] pair, and the Spotify session via
 * [RendererSpotifyBridge] + [NativeBridge]. This class intentionally contains no
 * arbitration logic of its own — every `when` below is a mechanical effect/event
 * translation; the "what should happen" decisions were made by the reducer already.
 */
class MediaRendererService : Service(), RendererRuntime {

    companion object {
        private const val TAG = "MediaRendererService"

        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_UDN = "dlna_renderer_udn"

        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_CHANNEL_ID = "renderer_service_channel"

        private const val FALLBACK_IP = "0.0.0.0"

        const val ACTION_STOP = "dev.rusty.app.renderer.ACTION_STOP"

        /** Live-service registry for the rename path: non-null between a SUCCESSFUL onCreate and
         *  onDestroy. The controller reads it instead of using startService (which would start a
         *  stopped renderer just to deliver a rename). */
        @Volatile
        internal var instance: MediaRendererService? = null
    }

    // -- infrastructure, built once in onCreate -----------------------------------------

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var prefsStore: SharedPrefsRendererStore
    private lateinit var audioManager: AudioManager
    private lateinit var connectivityManager: ConnectivityManager
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var store: RendererStore
    private lateinit var player: RendererPlayer
    private lateinit var bridge: RendererSpotifyBridge
    private lateinit var genaSubscriptions: GenaSubscriptions
    private lateinit var httpServer: RendererHttpServer
    private lateinit var ssdp: SsdpEndpoint

    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var udnValue: String = ""
    private var httpPort: Int = 0
    private var currentIp: String = FALLBACK_IP
    private var bootId: Long = 0L
    @Volatile private var identity: SsdpIdentity = SsdpIdentity("", "", 0L, 1L)

    /** Served by [friendlyName]/[configId] so the description flips ATOMICALLY with the SSDP
     *  announcement during a rename — a fresh prefs read would change the served name the instant
     *  the pref was written, making the required byebye(old) → alive(new) ordering impossible. */
    @Volatile private var nameSnapshot: String = RendererPrefs.DEFAULT_NAME
    @Volatile private var configIdSnapshot: Long = 1L

    /** True only after onCreate completed; onDestroy no-ops teardown when false (the failure
     *  path already cleaned up after itself, and its published FAILED must survive).
     *
     * Volatile because the event executor reads it: onDestroy clears it on the MAIN thread while a
     * rename task may already be queued (see [applyRename]), and that task must observe the flip. */
    @Volatile private var initialised = false

    @Volatile private var started = false

    private var lastEventedFields: EventedFields? = null
    private data class EventedFields(
        val transport: RendererTransport,
        val media: RendererMedia?,
        val durationMs: Long?,
        val seekable: Boolean,
        val transportStatus: String,
    )

    private val resumeRunnable = Runnable {
        val (playing, gen) = bridge.snapshot()
        store.dispatch(RendererEvent.ResumeTimerFired(playing, gen))
    }

    /** The pending fade-timer runnable; replaced by every ScheduleFadeTimer (single-slot, like
     *  the resume timer — the reducer's phases never need two fades pending at once).
     *  Confined to the store-drain thread, like every other mutable field here (RendererStore's
     *  single-drain serialization is what makes that safe without further synchronization). */
    private var fadeRunnable: Runnable? = null

    private val storeListener = RendererStore.Listener { state, _ ->
        // Every reduced state — not just the subset that changes the GENA-evented fields below —
        // is a potential UI-visible transition (fade phase, resume-pending, etc.), so the runtime
        // holder is poked unconditionally.
        RendererRuntimeHolder.publishChanged()
        val fields = EventedFields(state.transport, state.media, state.durationMs, state.seekable, state.transportStatus)
        if (fields != lastEventedFields) {
            lastEventedFields = fields
            pushEvent(UpnpService.AVTRANSPORT, UpnpEventXml.avTransportLastChange(state))
        }
    }

    /**
     * Backend for [RendererRuntimeHolder]: lets the DLNA player fragment observe live state and
     * issue transport commands without binding to this service. [dispatch] MUST route through
     * [dispatchViaTranslator] — the exact snapshot-Spotify-then-reduce path the network SOAP Play
     * handler uses (see [RendererHttpProtocol.handleAvTransport]) — so a UI command preserves
     * Spotify interruption arbitration, fade choreography, and GENA eventing rather than poking
     * [player]/[NativeBridge] directly.
     */
    private val runtimeBackend = object : RendererRuntimeHolder.Backend {
        override fun state(): RendererState? = store.state
        override fun positionMs(): Long? = player.positionMs()
        override fun deviceName(): String = nameSnapshot
        override fun dispatch(command: RendererCommand) {
            dispatchViaTranslator(command)
            RendererRuntimeHolder.publishChanged()
        }
    }

    /** The ONE construction site (shared with the network Play handler) for turning a live
     *  Spotify snapshot + prefs into a [RendererEvent] via [RendererCommandTranslator], then
     *  handing it to the reducer. */
    private fun dispatchViaTranslator(command: RendererCommand) {
        val (playing, gen) = bridge.snapshot()
        val event = RendererCommandTranslator.toEvent(
            command, playing, gen, RendererPrefs.mixMode(prefsStore), RendererPrefs.fadeMs(prefsStore),
        )
        store.dispatch(event)
    }

    private lateinit var bridgeListener: ReceiverStateStore.Listener

    /** Every network transition re-resolves the address from scratch ([refreshAddress]); which
     *  network moved is irrelevant, only what the device is reachable at. */
    private inner class AddressCallback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAddress()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshAddress()
        override fun onLost(network: Network) = refreshAddress()
    }

    private val defaultNetworkCallback = AddressCallback()
    private val lanNetworkCallback = AddressCallback()

    // -- Service lifecycle -----------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefsStore = SharedPrefsRendererStore(prefs)

        // FGS promotion must not wait for sockets/IP work — Android's deadline is short.
        // The provisional notification shows the (possibly migrated) name a moment later.
        acquireMulticastLock()
        startForeground(NOTIFICATION_ID, createNotification())

        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            udnValue = resolveUdn()

            // Pre-existing installs served the Spotify receiver name; they now serve the default.
            // A description change with no user action ⇒ CONFIGID bumps once.
            RendererPrefs.migrateIfNeeded(prefsStore)
            nameSnapshot = RendererPrefs.name(prefsStore)
            configIdSnapshot = RendererPrefs.configId(prefsStore)

            // UPnP: a restarted device must present a NEW BootID so control points discard
            // stale state and subscriptions.
            bootId = RendererPrefs.bumpBootId(prefsStore)

            store = RendererStore(::handleEffect)
            player = RendererPlayer(this, store)
            bridge = RendererSpotifyBridge(store)
            genaSubscriptions = GenaSubscriptions(
                nowMs = { SystemClock.elapsedRealtime() },
                newSid = { "uuid:" + UUID.randomUUID() },
            )
            httpServer = RendererHttpServer(this)
            httpPort = httpServer.start(preferredPort = RendererPrefs.port(prefsStore))
            RendererPrefs.persistPort(prefsStore, httpPort)   // sticky: this is the stable URL

            currentIp = resolveLocalIp() ?: FALLBACK_IP
            identity = buildIdentity()
            ssdp = SsdpEndpoint { identity }
            // A no-op while currentIp is the FALLBACK sentinel: SsdpEndpoint refuses to advertise a
            // LOCATION no control point can GET (http://0.0.0.0:port/… would be accepted by every
            // control point on the LAN and then cached as a broken device for max-age=1800).
            // refreshAddress() starts the endpoint the moment a real address turns up.
            ssdp.start()
            if (currentIp == FALLBACK_IP) {
                Log.w(TAG, "No LAN address yet; SSDP deferred until one arrives")
            }

            store.addListener(storeListener)
            bridgeListener = ReceiverStateStore.Listener(bridge::onSnapshot)
            RustyApp.from(this).addListener(bridgeListener)

            registerNetworkCallbacks()

            initialised = true
            instance = this
            RendererRuntimeHolder.attach(runtimeBackend)
            postNotification()   // now shows the real player name
            ServiceNotifications.started(this, ServiceNotifications.Kind.DLNA)
            publishRunning()
            Log.i(
                TAG,
                "MediaRendererService created: udn=$udnValue port=$httpPort ip=$currentIp " +
                    "name=$nameSnapshot bootId=$bootId configId=$configIdSnapshot",
            )
        } catch (e: Exception) {
            Log.e(TAG, "MediaRendererService failed to initialise", e)
            RendererStatusPublisher.publishFailed()
            releaseEverything()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A shade-stop must behave exactly like the settings Stop: clear the desired-state
            // pref FIRST so the service does not resurrect on the next boot or app start.
            prefs.edit().putBoolean(MediaRendererController.KEY_RENDERER_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            Log.i(TAG, "onStartCommand: already started; ignoring duplicate start")
            return START_STICKY
        }
        started = true
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        if (!initialised) {
            // onCreate threw and already cleaned up; its published FAILED must survive —
            // an unconditional STOPPED here would erase the state the user needs to see.
            super.onDestroy()
            return
        }
        initialised = false
        releaseEverything()
        ServiceNotifications.stopped(this, ServiceNotifications.Kind.DLNA)
        if (RendererStatusPublisher.current().status != RendererStatus.FAILED) {
            RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.STOPPED, null))
        }
        super.onDestroy()
    }

    /** Reverse-order teardown used by BOTH the onCreate failure path and onDestroy. Stops external
     *  entry points FIRST (SSDP byebye + HTTP) so no new SOAP/GENA work arrives once Shutdown is
     *  dispatched; NativeBridge is process-static and store.dispatch is synchronous, so
     *  ResumeSpotify/RestoreSpotifyVolume still fire correctly from here. Every step is individually
     *  guarded: a half-constructed service must never throw out of teardown, and calling this
     *  twice must be a no-op. */
    private fun releaseEverything() {
        runCatching { if (::ssdp.isInitialized) ssdp.stop(sendByebye = true) }
        runCatching { if (::httpServer.isInitialized) httpServer.stop() }
        runCatching { RendererRuntimeHolder.detach(runtimeBackend) }
        runCatching {
            if (::store.isInitialized && ::bridge.isInitialized) {
                val (playing, generation) = bridge.snapshot()
                store.dispatch(RendererEvent.Shutdown(playing, generation))
            }
        }
        runCatching {
            if (::connectivityManager.isInitialized) {
                connectivityManager.unregisterNetworkCallback(defaultNetworkCallback)
            }
        }
        runCatching {
            if (::connectivityManager.isInitialized) {
                connectivityManager.unregisterNetworkCallback(lanNetworkCallback)
            }
        }
        runCatching { if (::bridgeListener.isInitialized) RustyApp.from(this).removeListener(bridgeListener) }
        runCatching { if (::store.isInitialized) store.removeListener(storeListener) }
        runCatching { if (::player.isInitialized) player.release() }
        runCatching { eventExecutor.shutdown() }
        runCatching { mainHandler.removeCallbacksAndMessages(null) }
        runCatching { multicastLock?.let { lock -> if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -- Rename (no restart) --------------------------------------------------------------

    /**
     * Applies a rename WITHOUT restarting: a restart would release the player (killing playback)
     * and wipe the GENA table (HA's live SIDs would fail renewal with 412).
     *
     * UPnP: a description change is announced as byebye(old identity) → change → alive(new
     * identity). That alive is a REJOIN, so BOOTID must increase along with CONFIGID. The whole
     * sequence runs on the event executor — never the caller thread (announceNow/byebyeNow send
     * datagrams; on the main thread that throws NetworkOnMainThreadException, the b6bb331 bug).
     */
    fun applyRename(name: String, newConfigId: Long) {
        // The submit itself is guarded too: a rename racing teardown would otherwise reach an
        // already-shut-down executor and throw RejectedExecutionException on the caller's thread.
        runCatching {
            eventExecutor.execute {
                // The submit winning the race is not enough: the controller reads `instance` and
                // submits while the main thread may already be in onDestroy, and shutdown() still
                // RUNS queued tasks. A rename that lands after teardown would re-announce a dead
                // renderer (announceNow reopens the SSDP socket when it is not running) and re-post
                // the foreground notification as an undismissable orphan.
                if (!initialised) {
                    Log.i(TAG, "rename dropped; renderer already torn down")
                    return@execute
                }
                runCatching {
                    ssdp.byebyeNow()                              // old identity, old BOOTID/CONFIGID
                    nameSnapshot = name
                    configIdSnapshot = newConfigId
                    bootId = RendererPrefs.bumpBootId(prefsStore) // rejoin ⇒ new BOOTID
                    identity = buildIdentity()
                    ssdp.announceNow()                            // new identity
                    // Re-checked on the main thread, where onDestroy runs: a teardown that started
                    // after the check above must not leave a notification behind.
                    mainHandler.post { if (initialised) postNotification() }
                    // A resumed "Ready to cast" card must pick up the new device name too.
                    RendererRuntimeHolder.publishChanged()
                    Log.i(TAG, "renamed to '$name' (configId=$newConfigId bootId=$bootId)")
                }.onFailure { Log.w(TAG, "rename announcement failed", it) }
            }
        }.onFailure { Log.w(TAG, "rename not applied; renderer is shutting down", it) }
    }

    // -- Effect handling (RendererStore.EffectHandler) --------------------------------------

    private fun handleEffect(effect: RendererEffect) {
        when (effect) {
            is RendererEffect.PreparePlayer -> player.prepare(effect.uri, effect.mime, effect.generation)
            RendererEffect.PlayPlayer -> player.play()
            RendererEffect.PausePlayer -> player.pause()
            RendererEffect.StopPlayer -> player.stop()
            is RendererEffect.SeekPlayer -> player.seekTo(effect.positionMs)
            RendererEffect.PauseSpotify -> NativeBridge.pause()
            RendererEffect.ResumeSpotify -> NativeBridge.play()
            is RendererEffect.DuckSpotify ->
                NativeBridge.setSpotifyAttenuation(NativeBridge.DUCK_FACTOR, effect.fadeMs.toInt())
            is RendererEffect.MuteSpotify ->
                NativeBridge.setSpotifyAttenuation(0f, effect.fadeMs.toInt())
            is RendererEffect.RestoreSpotifyVolume ->
                NativeBridge.setSpotifyAttenuation(1f, effect.fadeMs.toInt())
            is RendererEffect.ScheduleFadeTimer -> {
                fadeRunnable?.let(mainHandler::removeCallbacks)
                val generation = effect.mediaGeneration   // stamped at SCHEDULE time, on purpose
                val r = Runnable {
                    val (_, sessionGen) = bridge.snapshot()
                    store.dispatch(RendererEvent.FadeTimerFired(generation, sessionGen))
                }
                fadeRunnable = r
                mainHandler.postDelayed(r, effect.delayMs)
            }
            RendererEffect.CancelFadeTimer -> {
                fadeRunnable?.let(mainHandler::removeCallbacks)
                fadeRunnable = null
            }
            is RendererEffect.ScheduleResumeTimer -> {
                // Re-arming (SoapSetUri while owning) must not stack two pending releases: the
                // earlier one would fire on the old, longer deadline and release Spotify mid-chain.
                mainHandler.removeCallbacks(resumeRunnable)
                mainHandler.postDelayed(resumeRunnable, effect.delayMs)
            }
            RendererEffect.CancelResumeTimer -> mainHandler.removeCallbacks(resumeRunnable)
        }
    }

    // -- RendererRuntime (the seam RendererHttpProtocol drives) -----------------------------

    override val friendlyName: String get() = nameSnapshot

    override val udn: String get() = udnValue

    override val configId: Long get() = configIdSnapshot

    override val volumeFixed: Boolean get() = audioManager.isVolumeFixed

    override val rendererState: RendererState get() = store.state

    override fun dispatch(event: RendererEvent) = store.dispatch(event)

    override fun positionMs(): Long = player.positionMs()

    override fun spotifySnapshot(): Pair<Boolean, Long> = bridge.snapshot()

    override fun mixMode(): SpotifyInterruption = RendererPrefs.mixMode(prefsStore)

    override fun fadeMs(): Long = RendererPrefs.fadeMs(prefsStore)

    override fun volumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (100f * current / max).roundToInt().coerceIn(0, 100)
    }

    override fun setVolumePercent(v: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (v.coerceIn(0, 100) / 100f * max).roundToInt()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
    }

    override fun muted(): Boolean = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)

    override fun setMuted(m: Boolean) {
        val direction = if (m) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
    }

    override fun onVolumeChanged() {
        pushEvent(UpnpService.RENDERINGCONTROL, UpnpEventXml.renderingControlLastChange(volumePercent(), muted()))
    }

    override fun gena(): GenaSubscriptions = genaSubscriptions

    override fun onSubscribed(sub: GenaSubscriptions.Sub) {
        val body = when (sub.service) {
            UpnpService.AVTRANSPORT -> UpnpEventXml.avTransportLastChange(store.state)
            UpnpService.RENDERINGCONTROL -> UpnpEventXml.renderingControlLastChange(volumePercent(), muted())
            UpnpService.CONNECTIONMANAGER -> UpnpEventXml.connectionManagerInitial()
        }
        sendNotify(sub, body)
    }

    // -- GENA eventing pump ------------------------------------------------------------------

    private fun pushEvent(service: UpnpService, body: String) {
        for (sub in genaSubscriptions.activeFor(service)) {
            sendNotify(sub, body)
        }
    }

    private fun sendNotify(sub: GenaSubscriptions.Sub, body: String) {
        eventExecutor.execute {
            val seq = genaSubscriptions.nextSeq(sub.sid)
            val delivered = httpServer.sendNotify(sub, body, seq)
            if (!delivered) genaSubscriptions.markFailed(sub.sid)
        }
    }

    // -- Identity / networking -----------------------------------------------------------------

    private fun resolveUdn(): String {
        val existing = prefs.getString(KEY_UDN, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = "uuid:" + UUID.randomUUID()
        prefs.edit().putString(KEY_UDN, generated).apply()
        return generated
    }

    private fun buildIdentity(): SsdpIdentity =
        SsdpIdentity(udnValue, "http://$currentIp:$httpPort${UpnpXml.DESCRIPTION_PATH}", bootId, configId)

    private fun registerNetworkCallbacks() {
        runCatching { connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback) }
            .onFailure { Log.w(TAG, "Failed to register default-network callback", it) }

        // The LAN is not always the DEFAULT network: with a VPN up the default is the tunnel, and a
        // Wi-Fi network Android could not validate (a LAN with no internet) never becomes default at
        // all. The default callback then only ever reports links with no site-local IPv4, so a
        // renderer that came up before Wi-Fi did would never learn its address. Watch the LAN
        // transports directly — no INTERNET capability is required, deliberately.
        runCatching {
            val lanRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            connectivityManager.registerNetworkCallback(lanRequest, lanNetworkCallback)
        }.onFailure { Log.w(TAG, "Failed to register LAN network callback", it) }
    }

    /**
     * The address we advertise. The default network is consulted first (it is the semantically
     * correct answer when the LAN IS the default network), but it is NOT authoritative: with a VPN
     * up, or on an unvalidated Wi-Fi network, activeNetwork carries no site-local IPv4 while wlan0
     * does — and SSDP would happily transmit from wlan0 anyway. Falling back to the interfaces makes
     * the LOCATION host and the SSDP transmit interface agree ([LanAddress]).
     */
    private fun resolveLocalIp(): String? {
        val fromDefaultNetwork = connectivityManager.activeNetwork
            ?.let { localIpv4From(connectivityManager.getLinkProperties(it)) }
        return fromDefaultNetwork ?: LanAddress.siteLocalIpv4()
    }

    private fun localIpv4From(linkProperties: LinkProperties?): String? =
        LanAddress.siteLocalIpv4(linkProperties?.linkAddresses?.map { it.address } ?: emptyList())
            ?.hostAddress

    /**
     * Network callback: re-resolves the address and rebinds the SSDP identity (new bootId) on a real
     * change. Synchronized — two callbacks (default + LAN) can fire concurrently, and a torn rebind
     * would announce a half-built identity.
     */
    @Synchronized
    private fun refreshAddress() {
        val ip = resolveLocalIp()
        if (ip == null) {
            if (currentIp == FALLBACK_IP) return
            // The address is gone. Keep RUNNING (the service and HTTP listener are alive) but drop
            // the URL: the Address row must never show a dead IP. The SSDP identity keeps its last
            // LOCATION — with no network there is nobody to byebye to, and stop() is terminal.
            currentIp = FALLBACK_IP
            publishRunning()
            Log.i(TAG, "Network lost; renderer has no LAN address")
            return
        }
        if (ip == currentIp) return
        currentIp = ip
        bootId = RendererPrefs.bumpBootId(prefsStore)
        identity = buildIdentity()
        genaSubscriptions.clear()
        ssdp.announceNow()   // starts the endpoint when this is the FIRST address it has ever had
        publishRunning()     // the Address row must never show a dead IP
        Log.i(TAG, "Network changed; rebinding SSDP identity to ip=$currentIp bootId=$bootId")
    }

    // -- Status --------------------------------------------------------------------------------

    /** RUNNING carries the URL only when a routable address exists — 0.0.0.0 is never shown. */
    private fun publishRunning() {
        val url = if (currentIp == FALLBACK_IP) null
                  else "http://$currentIp:$httpPort${UpnpXml.DESCRIPTION_PATH}"
        RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.RUNNING, url))
    }

    // -- Notification --------------------------------------------------------------------------

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("RustyRendererLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun postNotification() {
        runCatching {   // POST_NOTIFICATIONS may be denied on API 33+
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
        }
    }

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "DLNA Player Service",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, MediaRendererService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("DLNA player")
            .setContentText("Listening as \"$nameSnapshot\"")
            .setSmallIcon(R.drawable.ic_mdi_dlna)
            .setGroup(ServiceNotifications.GROUP_KEY)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }
}
