package dev.rusty.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.rusty.app.renderer.LanAddress
import dev.rusty.app.renderer.RendererRuntimeHolder
import dev.rusty.app.renderer.RendererTransport
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Composition root for the remote-control feature: the foreground service that owns the HTTP
 * server's lifetime and supplies the Android side of every seam the pure layer was built against
 * ([ControlProtocol], [ControlHttpServer], [ScreenControlModel], [ControlImmichProxy]).
 *
 * Why a foreground service rather than a socket opened by the Activity: the API must answer Home
 * Assistant while the screen is off and the app is backgrounded, and it must survive on boot with
 * no Activity ever created. `foregroundServiceType="connectedDevice"` is chosen deliberately — its
 * prerequisite permission (`CHANGE_WIFI_MULTICAST_STATE`) is already declared for SSDP, and unlike
 * `mediaPlayback` (see [BootStartSupport]) it is NOT among the types Android 15+ forbids starting
 * from `BOOT_COMPLETED`, which is exactly what makes start-on-boot work here. Being a foreground
 * service means holding a notification — Android allows no way around that — so the notification
 * is shaped like its siblings': a "Listening at ip:port" subtitle and a Stop action.
 *
 * Status is published to [ControlServerStatus], never back into the preference: a failed bind
 * leaves the toggle ON and is retried by the next start. That retry is why a bind failure calls
 * `stopSelf()` instead of lingering — see [bindServer].
 */
class ControlService : Service() {

    companion object {
        private const val TAG = "ControlService"
        private const val PREFS_NAME = "spotify_receiver_prefs"

        /** Distinct from SpotifyService (1), MediaRendererService (2) and the group summary (3). */
        private const val NOTIFICATION_ID = 4

        /** Fresh id: the legacy channel was IMPORTANCE_LOW, and a channel's importance cannot be
         *  lowered programmatically once created — see [buildNotification], which deletes it. */
        private const val NOTIFICATION_CHANNEL_ID = "control_service_channel_min"
        private const val LEGACY_CHANNEL_ID = "control_service_channel"

        private const val NOTIFICATION_TITLE = "Remote control"
        private const val STARTING_TEXT = "Starting…"

        /** Shade Stop action — mirrors [dev.rusty.app.renderer.MediaRendererService.ACTION_STOP]. */
        const val ACTION_STOP = "dev.rusty.app.control.ACTION_STOP"

        // Duplicated from ControlServiceRuntime/HomeActivity/RustyApp/etc. rather than shared —
        // matches how this device-name key is already kept local to every file that reads it.
        private const val KEY_DEVICE_NAME = "device_name"
        private const val DEFAULT_DEVICE_NAME = "Rusty Speaker"

        /** How long an interface enumeration is reused for the Host guard — see [localHosts]. */
        private const val HOSTS_CACHE_MS = 5_000L

        /**
         * Starts or stops the service to match the persisted toggle — the single entry point used
         * by [BootReceiver], [RustyApp.onCreate] (app launch heals a boot start that was refused)
         * and, later, the settings toggle. Mirrors
         * [dev.rusty.app.renderer.MediaRendererController.syncFromPrefs].
         *
         * Starting an already-running service is harmless: it re-delivers `onStartCommand`, which
         * no-ops (see [onStartCommand]). Starting one that stopped after a failed bind creates a
         * fresh instance — which is the retry.
         */
        fun syncFromPrefs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val serviceIntent = Intent(context, ControlService::class.java)
            if (ControlSettings.isEnabled(prefs)) {
                runCatching { context.startForegroundService(serviceIntent) }
                    .onFailure { e ->
                        Log.w(TAG, "control server start refused by the system", e)
                        ControlServerStatus.publish(
                            ControlServerStatus.State.Failed(e.message ?: "could not start the service")
                        )
                    }
            } else {
                context.stopService(serviceIntent)
                ControlServerStatus.publishStoppedIfInactive()
            }
        }
    }

    /**
     * The ONE thread that ever touches [ControlHttpServer]. Its `serverSocket`/`acceptThread`
     * fields are not `@Volatile` (inherited verbatim from `RendererHttpServer`), so calling
     * `start()` on a worker and `stop()` from the main thread would read fields published without
     * any happens-before edge between them. Queueing both on a single-thread executor supplies
     * that edge — and orders them — without editing the shared server class.
     */
    private val serverExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "control-server").apply { isDaemon = true }
    }

    /** Written and read only on [serverExecutor]; `@Volatile` anyway so a future reader elsewhere
     *  (a diagnostic, a test) cannot see a stale null. */
    @Volatile
    private var server: ControlHttpServer? = null

    /**
     * Guards against a re-delivered start command rebinding (or regressing the status). Read and
     * set on the main thread (`onStartCommand`); CLEARED from [serverExecutor] on the bind-failure
     * path so the next start command becomes the retry the design promises (see [bindServer]) —
     * hence `@Volatile`, which is the whole reason this is not a plain field.
     */
    @Volatile
    private var started = false

    /**
     * Mutual exclusion between teardown and everything that publishes this service's externally
     * visible state (status + notification): [bindServer] on [serverExecutor], [refreshUrl] on a
     * ConnectivityManager callback thread, and [onDestroy] on the main thread.
     *
     * Without it, a toggle-off landing inside the bind window loses the race in a way that outlives
     * the service: `onDestroy` publishes `Stopped` while `bindServer` is mid-bind, then `bindServer`
     * succeeds and publishes `Running(url)` — so the settings row (and Task 10's advertisement)
     * would keep pointing at a dead endpoint, and the notification it posts after the FGS has
     * already been torn down has nothing left to cancel it.
     */
    private val lifecycleLock = Any()

    /** Guarded by [lifecycleLock]. Once set, nothing may publish status or notifications again. */
    private var destroyed = false

    /** The port the server actually bound, for re-deriving the URL on a network change. Written
     *  under [lifecycleLock] at bind time; 0 until then. */
    private var boundPort = 0

    /** For [deviceName], the device id [reconcileNsd] hands to [nsdAdvertiser], and the shade
     *  Stop's toggle write. `by lazy` is fine here: first touched from [onStartCommand] or
     *  [bindServer], well after the Service is attached. */
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Owns the NSD registration for this service instance. See [reconcileNsd] for when it is
     *  driven and why. */
    private val nsdAdvertiser: ControlNsdAdvertiser by lazy { ControlNsdAdvertiser(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        // ControlProtocol must stay android.*-free to remain JVM-testable, so it cannot log the
        // failures it turns into 500s. Give it somewhere to report them for the lifetime of the
        // process — left installed after teardown deliberately: it is a pure logging sink, and a
        // re-created service reinstalls the identical lambda.
        ControlProtocol.onInternalError = { t, req ->
            Log.w(TAG, "control API failed on ${req.method} ${req.path}", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A shade-stop must behave exactly like the settings toggle: clear the pref FIRST so
            // the service does not resurrect on the next boot or app start (same rule as
            // MediaRendererService's ACTION_STOP).
            ControlSettings.setEnabled(prefs, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            // Re-delivered start (app-launch re-sync, START_STICKY restart of a live instance).
            // Re-publishing Starting here would be permanent: nothing would rebind to move it on.
            Log.i(TAG, "onStartCommand: already started; ignoring duplicate start")
            return START_STICKY
        }
        started = true

        // FGS promotion first — Android's deadline is short and the bind may block on the network.
        // The foreground status is kept for the service's whole life: demoting to drop the
        // notification was tried and rejected — a background service only survives while another
        // FGS or an Activity keeps the app in the foreground, which silently kills the server the
        // moment every other feature is off.
        startForeground(NOTIFICATION_ID, buildNotification(STARTING_TEXT))
        ServiceNotifications.started(this, ServiceNotifications.Kind.CONTROL)
        ControlServerStatus.publish(ControlServerStatus.State.Starting)

        val runtime = ControlServiceRuntime(applicationContext)
        runCatching { serverExecutor.execute { bindServer(runtime) } }
            .onFailure { e ->
                Log.w(TAG, "control server could not be scheduled", e)
                ControlServerStatus.publish(ControlServerStatus.State.Failed(e.message ?: "could not start"))
                stopSelf()
            }
        return START_STICKY
    }

    override fun onDestroy() {
        // Closes the publishing window FIRST. A bind still in flight will see this and discard its
        // socket instead of announcing a server that is being torn down. Blocks only for as long as
        // a concurrent publish takes (a status post + a notification call).
        synchronized(lifecycleLock) { destroyed = true }

        // Design ordering: "Stop advertising -> close server." Unconditional and safe to call even
        // when nothing was ever registered (boot-before-Wi-Fi, or a bind that never succeeded) —
        // ControlNsdAdvertiser.unregister() is idempotent. Because destroyed is now true under the
        // same lock every register call also runs inside (see reconcileNsd), any register that was
        // still in flight has either already completed (and is torn down by this call) or will see
        // destroyed and never call register() at all — the same reasoning that already protects
        // registerNetworkCallbacks below.
        nsdAdvertiser.unregister()
        unregisterNetworkCallbacks()
        // stop() is queued on the same thread that ran start(), so it cannot overtake it and sees
        // the fields it published. shutdown() (not shutdownNow()) lets that queued task run.
        runCatching {
            serverExecutor.execute {
                server?.stop()
                server = null
            }
        }
        serverExecutor.shutdown()
        ServiceNotifications.stopped(this, ServiceNotifications.Kind.CONTROL)
        // Belt and braces: the framework removes an FGS notification with the service, but this
        // one is also re-posted from background threads ([publishRunningLocked]), so cancel it
        // explicitly rather than rely on the ordering.
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        // A FAILED status is the one thing the settings row still needs after teardown (the bind
        // failure path stops this service on purpose); an unconditional Stopped would erase it.
        if (ControlServerStatus.current() !is ControlServerStatus.State.Failed) {
            ControlServerStatus.publish(ControlServerStatus.State.Stopped)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -- server lifetime, [serverExecutor] only ------------------------------------------

    /**
     * Binds the fixed API port. A fresh [ControlHttpServer] per service instance is mandatory:
     * once stopped its connection pool is shut down and it can never serve again.
     *
     * The expected failure is [IOException] (port already taken), but the catch is deliberately
     * [Throwable] for the same reason [ControlProtocol.route]'s is: this runs on [serverExecutor],
     * and anything escaping a plain `execute` task reaches the thread's default uncaught handler,
     * which on Android kills the whole process. A bind that fails for an unforeseen reason must
     * degrade to the FAILED status the settings row is built to show, never to a crash.
     */
    private fun bindServer(runtime: ControlRuntime) {
        val instance = ControlHttpServer(runtime) { localHosts() }
        try {
            val port = instance.start(ControlSettings.PORT)
            val adopted = synchronized(lifecycleLock) {
                if (destroyed) false else {
                    server = instance
                    boundPort = port
                    val url = urlFor(port)
                    publishRunningLocked(url)
                    // Registered under the lock too: onDestroy unregisters only AFTER setting
                    // `destroyed` under it, so registering outside could land after that
                    // unregister and leave two callbacks holding this Service forever.
                    registerNetworkCallbacks()
                    // Design order: "bind server -> advertise." Previous URL is always "" here —
                    // this is the first Running this service instance ever publishes — so
                    // ControlNsdPlan.action resolves to Register only when a site-local IPv4 is
                    // already available (the usual case) and NoOp when it is not yet (boot before
                    // Wi-Fi; refreshUrl's own call to reconcileNsd registers once one arrives).
                    reconcileNsd(previousUrl = "", newUrl = url)
                    true
                }
            }
            if (!adopted) {
                // Toggled off (or killed) while this bind was in flight: onDestroy has already
                // published Stopped and torn the notification down. Announcing now would strand
                // both. Close the socket we just opened and leave no trace.
                runCatching { instance.stop() }
                Log.i(TAG, "control server bound after teardown; socket discarded")
                return
            }
            Log.i(TAG, "control server listening on port $port")
        } catch (t: Throwable) {
            runCatching { instance.stop() }
            Log.w(TAG, "control server could not bind port ${ControlSettings.PORT}", t)
            val reported = synchronized(lifecycleLock) {
                if (destroyed) false else {
                    ControlServerStatus.publish(
                        ControlServerStatus.State.Failed(
                            t.message ?: "could not bind port ${ControlSettings.PORT}"
                        )
                    )
                    true
                }
            }
            // Stop rather than linger: a live-but-dead instance would swallow every later start
            // command (`started` is already true), so the next boot/toggle/app launch would no
            // longer be the retry the design promises. Already-destroyed needs no stopSelf.
            //
            // Clearing `started` first closes the one window where that promise still failed:
            // stopSelf() is asynchronous, so between it and onDestroy this instance is still the
            // one the system delivers to, and a user toggling off-then-on inside that window was
            // answered by the duplicate-start no-op and then destroyed — their retry silently
            // swallowed, with nothing left to trigger another. With the flag cleared, that
            // re-delivered command IS the retry. Written from serverExecutor rather than the main
            // thread, hence @Volatile on the field.
            started = false
            if (reported) stopSelf()
        }
    }

    /**
     * Publishes the running status + notification subtitle for [url]. MUST be called holding
     * [lifecycleLock] with [destroyed] false — that is what keeps it from racing teardown.
     * The subtitle mirrors SpotifyService's "Listening as …" wording; with no LAN address yet
     * (boot before Wi-Fi) there is no "ip:port" fact to show, so it falls back to the bare port.
     */
    private fun publishRunningLocked(url: String) {
        ControlServerStatus.publish(ControlServerStatus.State.Running(url))
        postNotification(
            if (url.isEmpty()) "Listening on port $boundPort"
            else "Listening at ${url.removePrefix("http://")}"
        )
    }

    // -- address changes -----------------------------------------------------------------

    /** Every network transition re-derives the URL from scratch ([refreshUrl]); which network moved
     *  is irrelevant, only what the device is reachable at. Mirrors the renderer's AddressCallback. */
    private inner class AddressCallback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshUrl()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshUrl()
        override fun onLost(network: Network) = refreshUrl()
    }

    private val defaultNetworkCallback = AddressCallback()
    private val lanNetworkCallback = AddressCallback()

    /**
     * Why two callbacks: the LAN is not always the DEFAULT network. With a VPN up the default is the
     * tunnel (whose CGNAT address is not site-local), and a Wi-Fi network Android could not validate
     * — a LAN with no internet — never becomes default at all. Watching only the default network
     * would leave a server that came up before Wi-Fi did with no address forever, which is the
     * normal case for this feature: `BOOT_COMPLETED` fires before Wi-Fi associates. Transcribed from
     * [dev.rusty.app.renderer.MediaRendererService.registerNetworkCallbacks], which solves exactly
     * this for SSDP; Task 10's NSD re-registration hangs off the same signal ([refreshUrl]).
     */
    private fun registerNetworkCallbacks() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.registerDefaultNetworkCallback(defaultNetworkCallback) }
            .onFailure { Log.w(TAG, "Failed to register default-network callback", it) }
        runCatching {
            val lanRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(lanRequest, lanNetworkCallback)
        }.onFailure { Log.w(TAG, "Failed to register LAN network callback", it) }
    }

    private fun unregisterNetworkCallbacks() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(defaultNetworkCallback) }
        runCatching { cm.unregisterNetworkCallback(lanNetworkCallback) }
    }

    /**
     * Re-derives the advertised URL after a network transition. The socket is bound to every
     * interface and does not care, but the URL shown in settings (and advertised by Task 10) is an
     * address, and addresses move: DHCP renewals change it, and on boot there is usually no LAN
     * address at all when the bind happens. Only a live [ControlServerStatus.State.Running] is
     * updated — a transition can neither resurrect a stopped server nor clear a failed bind — and an
     * unchanged URL publishes nothing, so the settings row does not flicker on every Wi-Fi event.
     */
    private fun refreshUrl() {
        synchronized(lifecycleLock) {
            if (destroyed) return
            val current = ControlServerStatus.current()
            if (current !is ControlServerStatus.State.Running) return
            val url = urlFor(boundPort)
            if (url == current.url) return
            publishRunningLocked(url)
            // Task 10's NSD re-registration hangs off this same signal, per the controller's
            // resolution — a second ConnectivityManager callback would be duplicated machinery
            // watching the exact event this method already reacts to. current.url is the URL that
            // was (or was not) advertised up to this point; the invariant "the NSD advertisement
            // always mirrors what ControlServerStatus just published" means no separate tracking
            // field is needed here — see reconcileNsd's KDoc for the full argument.
            reconcileNsd(previousUrl = current.url, newUrl = url)
            Log.i(TAG, "network changed; control URL is now ${url.ifEmpty { "(no LAN address)" }}")
        }
    }

    /**
     * Applies [ControlNsdPlan.action] for the transition from [previousUrl] to [newUrl] — register,
     * unregister, or leave the advertisement alone. Callers are [bindServer] (`previousUrl = ""`,
     * the first-ever publish for this service instance) and [refreshUrl] (`previousUrl` is the URL
     * [ControlServerStatus] held immediately before this call, which — because this method and
     * every status publish always run together inside [lifecycleLock] — IS what is currently
     * advertised; no separate "what did we last register" field is needed).
     *
     * MUST be called holding [lifecycleLock] with [destroyed] false, exactly like
     * [registerNetworkCallbacks]. This is not because `NsdManager.registerService`/
     * `unregisterService` are slow — they are not, they enqueue work and return immediately, the
     * same cost class as the `ConnectivityManager` calls [registerNetworkCallbacks] already makes
     * inside this lock — but because it is what makes the race with [onDestroy] provably
     * impossible: either this whole locked block completes with [destroyed] still false, in which
     * case any [ControlNsdAdvertiser.register] call here is guaranteed to run-before [onDestroy]'s
     * own `nsdAdvertiser.unregister()` (both sides serialize on [lifecycleLock], and `onDestroy`
     * only unregisters after setting `destroyed` under it), or [destroyed] is already true and this
     * method is never reached. A "call NsdManager outside the lock, reconcile bookkeeping under it"
     * shape was considered and rejected: without the lock covering the actual call, a register that
     * lands after `onDestroy` has already unregistered would leave a live advertisement for a
     * service instance that no longer exists — the exact notification/network-callback race Task 9
     * hit and fixed the same way [registerNetworkCallbacks] is fixed.
     */
    private fun reconcileNsd(previousUrl: String, newUrl: String) {
        when (ControlNsdPlan.action(previousUrl, newUrl)) {
            ControlNsdPlan.Action.NoOp -> Unit
            ControlNsdPlan.Action.Unregister -> nsdAdvertiser.unregister()
            ControlNsdPlan.Action.Register ->
                nsdAdvertiser.register(deviceName(), boundPort, ControlSettings.deviceId(prefs))
        }
    }

    /** Same key/default HomeActivity's rename dialog writes to; read fresh (not cached) so a
     *  rename that lands while the server is up is reflected on the next network-driven
     *  re-registration — but note there is currently no dedicated "rename" hook, only this one
     *  (see Task 10's report). */
    private fun deviceName(): String = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME

    /**
     * The URL to show and advertise. Empty when the device currently has no site-local IPv4 — the
     * server is bound on every interface, but "reachable at X" is a different fact from "running"
     * and must never be faked with the 0.0.0.0 placeholder (see [LanAddress]).
     */
    private fun urlFor(port: Int): String {
        val host = LanAddress.siteLocalIpv4() ?: return ""
        return "http://$host:$port"
    }

    /**
     * Every address this device answers on, for [ControlProtocol]'s DNS-rebinding guard. The
     * provider is a lambda invoked per request, so a DHCP change mid-session cannot lock clients
     * out — but the enumeration behind it walks every [java.net.NetworkInterface] and its
     * addresses, and with Home Assistant polling every ~5 s plus an open control page it would run
     * roughly once a second, forever, for an answer that changes maybe once a week. Memoized for
     * [HOSTS_CACHE_MS]: the guard exists to survive an address MOVE, not to be instantaneous, and
     * the worst a stale entry can do is 403 a request for a few seconds after a DHCP change (the
     * page retries on its next 5 s poll).
     *
     * Called from HTTP worker threads, so the cache is guarded — cheaply, since the lock is only
     * ever held across a set copy or one enumeration. This half is only that enumeration; the
     * (tested) assembly rules live in [ControlHosts].
     */
    private fun localHosts(): Set<String> = synchronized(hostsLock) {
        val now = System.currentTimeMillis()
        val cached = cachedHosts
        if (cached != null && now - cachedHostsAt < HOSTS_CACHE_MS) return cached
        val fresh = ControlHosts.localHosts(
            LanAddress.usableInterfaces()
                .flatMap { LanAddress.addressesOf(it) }
                .mapNotNull { it.hostAddress }
        )
        cachedHosts = fresh
        cachedHostsAt = now
        return fresh
    }

    private val hostsLock = Any()
    private var cachedHosts: Set<String>? = null
    private var cachedHostsAt = 0L

    // -- notification --------------------------------------------------------------------

    private fun postNotification(text: String) {
        runCatching {   // POST_NOTIFICATIONS may be denied on API 33+
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /**
     * Held for the service's whole life, because Android requires a foreground service to hold
     * one. Shaped like the sibling services' notifications: same group, tap-to-open, a
     * "Listening at ip:port" subtitle ([publishRunningLocked]) and a Stop action that flips the
     * settings toggle off. The channel is requested at IMPORTANCE_MIN — the OS clamps FGS
     * channels up to LOW, which lands it at the same level as the siblings.
     */
    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // The pre-existing LOW channel from older installs would keep the notification visible —
        // importance can only be lowered by the user, never by code — so retire it. (Recreating the
        // SAME id would restore its old settings; hence the new channel id.)
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Remote control service",
            NotificationManager.IMPORTANCE_MIN,
        )
        manager.createNotificationChannel(channel)

        val openIntent = PendingIntent.getActivity(
            this, 4,
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        // getForegroundService, not getService: a shade tap arrives with the app in the
        // background, where a plain startService is forbidden on API 26+ (same choice as
        // SpotifyService's Stop). The ACTION_STOP branch runs before the duplicate-start guard,
        // so the re-delivered command is never swallowed.
        val stopIntent = PendingIntent.getForegroundService(
            this, 5,
            Intent(this, ControlService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_settings)
            .setGroup(ServiceNotifications.GROUP_KEY)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }
}

/**
 * [ControlRuntime] against the live app. Every Android touchpoint the pure protocol layer was
 * deliberately kept away from lands here: prefs, [AudioManager], [Settings.System], the receiver
 * store, the DLNA renderer and the app's assets.
 *
 * ## One serialized command dispatcher
 * The design doc requires that concurrent writes (Home Assistant polling+commanding while the
 * control page is open) cannot interleave, so all three mutating methods hold [commandLock] for
 * the whole apply-then-read-back. Reads stay lock-free on purpose: `GET /api/state` is polled every
 * ~5 s per client and must never queue behind a command — every source it reads
 * ([ScreenControlModel], [ReceiverStateStore], [RendererRuntimeHolder], SharedPreferences) is
 * already internally thread-safe and individually atomic.
 *
 * No mutable fact is cached from construction time: `canWrite()` is re-checked on every snapshot
 * because the user can grant or revoke WRITE_SETTINGS while the service runs, and the volume/
 * renderer/playback facts are read fresh for the same reason. Only the two genuinely immutable
 * ones — the install's device id and the embedded control page — are resolved once.
 */
private class ControlServiceRuntime(private val context: Context) : ControlRuntime {

    private companion object {
        const val TAG = "ControlRuntime"
        const val PREFS_NAME = "spotify_receiver_prefs"
        const val KEY_DEVICE_NAME = "device_name"
        const val DEFAULT_DEVICE_NAME = "Rusty Speaker"
        const val CONTROL_PAGE_ASSET = "control.html"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)

    /**
     * Resolved ONCE, here, on the thread that constructs the runtime — before the server binds and
     * therefore before any pool thread can ask. [ControlSettings.deviceId] generates-and-stores on
     * first call, so resolving it per request would let two concurrent first requests on a fresh
     * install mint two UUIDs and report the losing one; Home Assistant keys its config entry on
     * this value, so a split identity would duplicate the device. The id is immutable per install,
     * so caching it costs nothing else.
     */
    private val deviceId = ControlSettings.deviceId(prefs)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repository = ImmichRepository()

    /** Read once, from the APK's assets, and reused: the page is immutable for the life of the
     *  install, and every browser reload would otherwise re-open and re-decode it. */
    private val pageHtml: String by lazy {
        context.assets.open(CONTROL_PAGE_ASSET).bufferedReader().use { it.readText() }
    }

    private val immich = ControlImmichProxy(
        configProvider = { SlideshowSettings.config(prefs, SecretStore.of(context)) },
        fetcher = { kind, cfg ->
            when (kind) {
                "albums" -> repository.fetchAlbums(cfg)
                "people" -> repository.fetchPeople(cfg)
                "tags" -> repository.fetchTags(cfg)
                // Unreachable in practice — ControlProtocol whitelists the three kinds before
                // routing — but a runtime must not throw into an HTTP worker if that ever changes.
                else -> ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
            }
        },
        clock = System::currentTimeMillis,
    )

    private val commandLock = Any()

    override fun snapshot(): ControlSnapshot {
        val desired = ScreenControlModel.desired()
        // Granted/revoked at any time from system settings, so it is a per-snapshot question:
        // "system" writes the device's real brightness, "window" only dims this app's window.
        val canWriteSystem = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)
        // …and holding the permission is not the same as the write LANDING: a device whose putInt
        // refuses anyway makes the renderer degrade to window-local brightness, and the snapshot has
        // to say so rather than promise a device-wide change that never happened. The renderer
        // applies asynchronously, so this reflects the last COMPLETED apply — see
        // ScreenControlModel.systemBrightnessUsable for why the POST response cannot do better.
        val systemBrightness = ScreenControlModel.systemBrightnessUsable(canWriteSystem)
        val max = maxVolume()
        val fixed = volumeFixed(max)

        return ControlSnapshot(
            deviceId = deviceId,
            deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME,
            version = BuildConfig.VERSION_NAME,
            screen = ControlScreen(
                on = desired.on,
                brightness = desired.brightness,
                mode = if (systemBrightness) "system" else "window",
                writable = systemBrightness,
                // Attached is not enough: a backgrounded Activity still holds the desired state
                // (and re-applies it) but cannot keep the panel awake, so a fake-off there is a
                // dead end no remote wake can undo. `available` is the "can it take effect right
                // now" question — see ScreenControlModel.setRendererVisible.
                available = ScreenControlModel.screenControlAvailable(),
            ),
            volume = ControlVolume(
                value = ControlVolumeMath.percent(currentVolume(), max),
                fixed = fixed,
            ),
            playing = ControlPlaying(
                // The explicit ground truth for each player, never a display string: the anchor is
                // what the receiver itself reports, and an absent/stopped renderer service yields
                // a null state, i.e. false — never a stale "still playing".
                spotify = runCatching { RustyApp.from(context).snapshot.anchor.playing }.getOrDefault(false),
                dlna = RendererRuntimeHolder.current().state?.transport == RendererTransport.PLAYING,
            ),
            slideshowEnabled = SlideshowSettings.isEnabled(prefs),
        )
    }

    override fun setScreen(on: Boolean, brightness: Int?): ControlSnapshot = synchronized(commandLock) {
        // NOTE for the screen renderer (Task 11): ScreenControlModel drains its delivery queue on
        // THIS thread, so the attached renderer runs while [commandLock] is held. It must post its
        // View work to the main thread and return immediately — doing the work inline would block
        // every other control command for as long as the UI takes.
        ScreenControlModel.set(on, brightness)
        snapshot()
    }

    override fun setVolume(percent: Int): ControlSnapshot? = synchronized(commandLock) {
        val max = maxVolume()
        // A fixed-volume device (TV/HDMI/dock output) rejects the write with 409 rather than
        // silently accepting a change that will never happen.
        if (volumeFixed(max)) return null
        // setStreamVolume throws SecurityException when a Do-Not-Disturb policy owns the stream;
        // that is the same "cannot be changed" answer as a fixed device — logged, because on a
        // device that reports the stream as changeable it is the only trace of why 409 came back.
        val applied = runCatching {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, ControlVolumeMath.step(percent, max), 0)
        }.onFailure { Log.w(TAG, "volume write rejected by the system", it) }
        if (applied.isFailure) return null
        // Read back rather than echo: the device quantizes to its own step count, so the caller is
        // told the percentage it actually got (e.g. 55 -> 53 on a 15-step stream).
        snapshot()
    }

    override fun filters(): ImmichFilters = SlideshowSettings.filters(prefs)

    override fun setFilters(f: ImmichFilters) {
        synchronized(commandLock) { SlideshowSettings.setFilters(prefs, f) }
        // Outside the lock, and on the main thread: the subscriber is HomeActivity, which remounts
        // a running slideshow — View work that must not happen on an HTTP pool thread, and must
        // not hold up the next command while it runs.
        mainHandler.post { SlideshowConfigRelay.notifyChanged() }
    }

    override fun immichList(kind: String): ControlImmichResult = immich.list(kind)

    override fun controlPageHtml(): String = pageHtml

    // -- update check / install ----------------------------------------------------------

    /** Blocking GitHub fetch on a server pool thread — same cost class as the Immich proxy
     *  routes, and bounded by [UpdateRepository]'s 15-minute cache. */
    override fun updateCheck(): ControlUpdateCheck {
        val check = UpdateRepository.check(BuildConfig.VERSION_NAME)
        return ControlUpdateCheck(
            current = check.currentVersion,
            status = when (check.status) {
                UpdateRepository.UpdateStatus.UP_TO_DATE -> "up_to_date"
                UpdateRepository.UpdateStatus.UPDATE_AVAILABLE -> "update_available"
                UpdateRepository.UpdateStatus.ERROR -> "error"
            },
            latest = check.latest?.let {
                ControlUpdateLatest(it.versionName, it.notes, it.releaseUrl, hasApk = it.apkUrl != null)
            },
            install = ApkInstall.installer(context).snapshot(),
        )
    }

    override fun startUpdateInstall(): ControlInstallStart {
        // Normally answered from cache (the page GETs /api/update right before POSTing), but a
        // cold cache blocks on the fetch here — acceptable for the same reason as updateCheck().
        val check = UpdateRepository.check(BuildConfig.VERSION_NAME)
        if (check.status != UpdateRepository.UpdateStatus.UPDATE_AVAILABLE) return ControlInstallStart.NO_UPDATE
        val apkUrl = check.latest?.apkUrl ?: return ControlInstallStart.NO_APK
        return if (ApkInstall.installer(context).start(apkUrl)) ControlInstallStart.STARTED
        else ControlInstallStart.BUSY
    }

    // -- volume helpers ------------------------------------------------------------------

    private fun maxVolume(): Int =
        runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)

    private fun currentVolume(): Int =
        runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)

    /** A query that throws counts as "fixed" (a stream we cannot even interrogate is one we cannot
     *  change); the `max <= 0` half of the rule is [ControlVolumeMath.isFixed]'s. */
    private fun volumeFixed(max: Int): Boolean =
        ControlVolumeMath.isFixed(max, runCatching { audio.isVolumeFixed }.getOrDefault(true))
}
