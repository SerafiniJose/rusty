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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.rusty.app.renderer.LanAddress
import dev.rusty.app.renderer.MediaRendererService
import dev.rusty.app.renderer.RendererRuntimeHolder
import dev.rusty.app.renderer.RendererTransport
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    /** The live [ControlServiceRuntime], kept only so [onDestroy] can release what it lazily
     *  acquired (the TTS engine). Written on the main thread in onStartCommand. */
    @Volatile
    private var runtime: ControlServiceRuntime? = null

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

    /** Guarded by [lifecycleLock], like [boundPort]. The [ControlNsdPlan.advertKey] most recently
     *  handed to [ControlNsdAdvertiser.register] — or `""` after an unregister, or before the first
     *  register this instance ever makes. [reconcileNsd] diffs the freshly-computed key against
     *  this, so a change on ANY axis that feeds the advertisement (address, camera-share status,
     *  the auth pref) triggers a re-register, not only an address change. */
    private var advertisedKey: String = ""

    /** For [deviceName], the device id [currentTxt] reads for [reconcileNsd], the auth pref
     *  [authPrefListener] watches, and the shade Stop's toggle write. `by lazy` is fine here: first
     *  touched from [onCreate] (to register [authPrefListener]), well after the Service is
     *  attached. */
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /** Owns the NSD registration for this service instance. See [reconcileNsd] for when it is
     *  driven and why. */
    private val nsdAdvertiser: ControlNsdAdvertiser by lazy { ControlNsdAdvertiser(applicationContext) }

    /** Registered on [CameraShareStatus] in [onCreate], removed in [onDestroy]. The camera-share
     *  state is one of the two non-network inputs to the TXT record (see [currentTxt]); this
     *  listener's only job is calling [refreshAdvertisement] whenever that state changes, including
     *  the initial replay [CameraShareStatus.addListener] delivers on registration. */
    private val shareListener: (CameraShareStatus.State) -> Unit = { refreshAdvertisement() }

    /** Registered on [prefs] in [onCreate], removed in [onDestroy]. The other non-network TXT
     *  input: whether a Remote Control password is actually enforced. Filters on
     *  [ControlSettings.KEY_AUTH_REQUIRED] so writes to unrelated keys (device name, camera-share
     *  prefs, …) do not churn the advertisement — those already have their own paths to
     *  [reconcileNsd] ([deviceName] is re-read fresh on every registration; camera-share prefs feed
     *  [CameraShareStatus], not this listener). */
    private val authPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ControlSettings.KEY_AUTH_REQUIRED) refreshAdvertisement()
        }

    override fun onCreate() {
        super.onCreate()
        // ControlProtocol must stay android.*-free to remain JVM-testable, so it cannot log the
        // failures it turns into 500s. Give it somewhere to report them for the lifetime of the
        // process — left installed after teardown deliberately: it is a pure logging sink, and a
        // re-created service reinstalls the identical lambda.
        ControlProtocol.onInternalError = { t, req ->
            Log.w(TAG, "control API failed on ${req.method} ${req.path}", t)
        }
        // Both feed [refreshAdvertisement]: the TXT record depends on the camera-share status and
        // the auth pref, neither of which is a network event, so neither goes through
        // [refreshUrl]'s ConnectivityManager callbacks. Registered here — not lazily at bind time —
        // so a status/pref change arriving before the server ever binds still reconciles (to a
        // no-op, since [currentUrl] is "" until [publishRunningLocked] runs) rather than being
        // silently missed. Removed in [onDestroy]; leaving either registered would leak this
        // Service instance via the process-wide static listener sets it is held in.
        CameraShareStatus.addListener(shareListener)
        prefs.registerOnSharedPreferenceChangeListener(authPrefListener)
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
        // Held so onDestroy can release the resources the runtime lazily acquires (its TTS
        // engine binds a system service that only shutdown() disconnects).
        this.runtime = runtime
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

        // Mirrors onCreate's registration, in reverse: stop reacting to camera-share/auth-pref
        // changes before tearing the advertisement down, so neither listener can fire against a
        // Service instance that is on its way out. Not itself what makes the unregister-after-
        // destroyed race safe (destroyed, checked under lifecycleLock in refreshAdvertisement,
        // already does that) — this is about not leaking this Service instance via the
        // process-wide CameraShareStatus listener set and the SharedPreferences listener map.
        CameraShareStatus.removeListener(shareListener)
        prefs.unregisterOnSharedPreferenceChangeListener(authPrefListener)

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
        // After the server is queued to stop: no new request can reach the runtime. But release()
        // can still BLOCK — engine shutdown, and the announce lock it takes to unmap the Piper
        // model may be held by a synthesis already in flight for tens of seconds — so it runs on
        // its own thread rather than risking an ANR here on the main thread. Daemon: teardown
        // must never pin the process.
        runtime?.let { rt ->
            Thread({ rt.release() }, "control-release").apply { isDaemon = true }.start()
        }
        runtime = null
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
                    // Design order: "bind server -> advertise." advertisedKey is always "" here —
                    // this is the first Running this service instance ever publishes — so
                    // ControlNsdPlan.action resolves to Register only when a site-local IPv4 is
                    // already available (the usual case) and NoOp when it is not yet (boot before
                    // Wi-Fi; refreshUrl's own call to reconcileNsd registers once one arrives).
                    reconcileNsd(url)
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
            // watching the exact event this method already reacts to. reconcileNsd diffs against
            // advertisedKey itself now, so this call needs nothing from `current` beyond having
            // already used it for the "did the URL actually change" check above.
            //
            // Called inline, unlike refreshAdvertisement's: ConnectivityManager delivers these
            // callbacks on its own ConnectivityThread, never the main thread, so currentTxt()'s
            // blocking secret read costs no UI responsiveness here — and this method is already
            // doing comparable work on that thread (urlFor -> LanAddress walks every network
            // interface). Deferring it would also split the "publish the status, then advertise it"
            // pair this whole method holds lifecycleLock to keep atomic. The crash half of the
            // problem is handled where it belongs, inside reconcileNsd, which never throws.
            reconcileNsd(url)
            Log.i(TAG, "network changed; control URL is now ${url.ifEmpty { "(no LAN address)" }}")
        }
    }

    /**
     * Builds the TXT record for [newUrl] and applies [ControlNsdPlan.action] for the move from
     * [advertisedKey] to that record's [ControlNsdPlan.advertKey] — register, unregister, or leave
     * the advertisement alone. Callers are [bindServer] (the first-ever publish for this service
     * instance, [advertisedKey] still `""`), [refreshUrl] (a network transition moved the address)
     * and [refreshAdvertisement] (the camera-share status or the auth pref moved instead — [newUrl]
     * may be identical to what is already advertised; the TXT half of the key is what changed).
     *
     * [currentTxt] is called exactly once here, so the key just computed and the record
     * [ControlNsdAdvertiser.register] is handed (on [ControlNsdPlan.Action.Register]) are always
     * the same snapshot of camera-share/auth state — never a key built from one read racing a
     * record built from a second, later read that could disagree with it.
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
     *
     * ## Never throws
     * [ControlNsdPlan.action] is pure and [ControlNsdAdvertiser]'s calls are already `runCatching`-
     * wrapped, but [currentTxt] reads an encrypted secret, which can throw (see its KDoc). All three
     * call sites would take that badly:
     *
     *  - [refreshUrl] runs on a `ConnectivityManager` callback thread with no `catch` of its own,
     *    and [refreshAdvertisement]'s task runs on [serverExecutor] — on either, an escape reaches
     *    the thread's default uncaught handler, which on Android kills the whole process, for the
     *    same reason [bindServer]'s own catch is a [Throwable].
     *  - [bindServer]'s call IS inside a `try`, but the wrong one: its catch means "the port could
     *    not be bound", so a throw from here — after the server has bound and published `Running` —
     *    would report a healthy server as a bind failure and tear it down.
     *
     * So a failure is logged and the cycle simply skipped, leaving the previous advertisement in
     * place until the next reconcile (a network tick, a camera-share change, a pref write) retries.
     * Wrapping the whole body rather than just [currentTxt] also keeps [advertisedKey] and the live
     * registration consistent: the bookkeeping write and the call it records are inside together.
     */
    private fun reconcileNsd(newUrl: String) {
        runCatching {
            val txt = currentTxt()
            val newKey = ControlNsdPlan.advertKey(newUrl, txt)
            when (ControlNsdPlan.action(advertisedKey, newKey)) {
                ControlNsdPlan.Action.NoOp -> Unit
                ControlNsdPlan.Action.Unregister -> {
                    nsdAdvertiser.unregister()
                    advertisedKey = ""
                }
                ControlNsdPlan.Action.Register -> {
                    nsdAdvertiser.register(deviceName(), boundPort, txt)
                    advertisedKey = newKey
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "could not reconcile the NSD advertisement; leaving it as it is", t)
        }
    }

    /**
     * The TXT record [reconcileNsd] should advertise right now. [CameraShareStatus.current] is read
     * exactly once, into a local — reading it a second time (say, once for the "is this shared at
     * all" flag and again for something else) could observe a state change in between and produce a
     * record that is internally inconsistent, describing two different moments at once.
     *
     * WHICH states are worth advertising a camera for is [ControlNsdPlan.advertisesCamera]'s
     * decision, not this method's: it is pure logic over a sealed class, so it belongs in the
     * tested layer, and expressing it as an exhaustive `when` there makes a future
     * [CameraShareStatus.State] a compile error rather than a silent "advertise". See that function
     * for why [CameraShareStatus.State.Unavailable] joins [CameraShareStatus.State.Off] and
     * [CameraShareStatus.State.Unsupported] in omitting `cam`/`rtsp`.
     *
     * `auth` reflects whether a password is actually ENFORCED —
     * [ControlSettings.requiredPassword] returning non-null, i.e. the switch is on AND a secret is
     * actually stored — not merely whether the switch is on; see that function's KDoc for why those
     * can disagree (a restored backup with the switch on but no secret).
     *
     * ## This blocks, and it can throw
     * [SecretStore.of] builds a KeyStore-backed `EncryptedSharedPreferences` on first use (master-key
     * generation, Tink setup, a file read), and the per-value read behind
     * [ControlSettings.requiredPassword] is NOT guarded the way that construction is: a single
     * undecryptable entry raises `SecurityException`/`GeneralSecurityException` straight out of
     * `getString`, a failure mode distinct from the whole-file corruption [SecretStore] recovers
     * from at open time. That is why [reconcileNsd] — the only caller — never lets a throw escape,
     * and why [refreshAdvertisement] hops onto [serverExecutor] before getting here.
     */
    private fun currentTxt(): Map<String, String> {
        val share = CameraShareStatus.current()
        return ControlNsdPlan.txtAttributes(
            deviceId = ControlSettings.deviceId(prefs),
            deviceName = deviceName(),
            cameraShared = ControlNsdPlan.advertisesCamera(share),
            authRequired = ControlSettings.requiredPassword(prefs, SecretStore.of(applicationContext)) != null,
        )
    }

    /**
     * Reconciles the advertisement when something OTHER than the network moved: [shareListener] (a
     * [CameraShareStatus] change) or [authPrefListener] (the auth pref) firing. Unlike [refreshUrl],
     * the URL itself has usually NOT changed here — [currentUrl] just re-reads whatever is currently
     * live so [reconcileNsd] has a URL to fold the freshly-read TXT record against; it is
     * [ControlNsdPlan.advertKey]/[ControlNsdPlan.action] that notice the TXT half moved even though
     * the URL half did not.
     *
     * ## Why this hops onto [serverExecutor]
     * Both callers arrive on the MAIN thread — [CameraShareStatus] dispatches through a main-looper
     * `Handler` (including the replay [CameraShareStatus.addListener] delivers from [onCreate]), and
     * `SharedPreferences` listener callbacks are always delivered there too — while [currentTxt]
     * does KeyStore/Tink construction and a file read (see its KDoc). Left inline, EVERY
     * [ControlService] start would do that on the UI thread, and usually as the process's first such
     * construction, since [RustyApp]'s warm-up only runs when there is a legacy plaintext key to
     * migrate. It also recurs: [shareListener] fires on every viewer attach and detach, so each
     * [CameraShareStatus.State.Streaming] tick would re-decrypt the stored password on the UI thread
     * — pure waste, since the viewer count is not in the TXT record and the re-registration itself
     * is already suppressed by an unchanged [advertisedKey].
     *
     * [serverExecutor] is the machinery this file already has for exactly this, and needs no new
     * state to be teardown-safe: a task queued before [onDestroy] still drains (the executor is
     * `shutdown()`, not `shutdownNow()`) and then no-ops on [destroyed] under [lifecycleLock], like
     * every other publisher; the [reconcileNsd] contract of being called under that lock with
     * [destroyed] false is preserved verbatim. A submission that loses the race with `shutdown()`
     * raises `RejectedExecutionException` — caught here for the same reason [onStartCommand] and
     * [onDestroy] catch it around their own `execute` calls, and doubly so on this path, which runs
     * on the main thread where an escape would be fatal.
     */
    private fun refreshAdvertisement() {
        runCatching {
            serverExecutor.execute {
                synchronized(lifecycleLock) {
                    if (destroyed) return@execute
                    reconcileNsd(currentUrl())
                }
            }
        }.onFailure { e -> Log.w(TAG, "advertisement refresh dropped: the service is stopping", e) }
    }

    /** The URL currently live in [ControlServerStatus]: a running server's url, or `""` when it is
     *  not (yet, or no longer) running — the same "nothing to advertise" reading [refreshUrl] does
     *  of its own `current`, pulled out so [refreshAdvertisement] can ask the same question without
     *  a network transition to hang it off. */
    private fun currentUrl(): String =
        (ControlServerStatus.current() as? ControlServerStatus.State.Running)?.url ?: ""

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

        /** TTS engine bind + init; generous because a just-booted device is the common slow case. */
        const val TTS_INIT_TIMEOUT_MS = 10_000L

        /** Synthesis of up to [ControlAnnounce.MAX_TEXT_CHARS] chars; engines write faster than
         *  realtime, so this bounds a hung engine, not a long text. */
        const val TTS_SYNTH_TIMEOUT_MS = 30_000L

        /** How old an announcement file must be before it is pruned. Longer than any plausible
         *  clip so a file still being streamed is never deleted under the player. */
        const val ANNOUNCE_PRUNE_AGE_MS = 10 * 60_000L

        /** After a failed engine init, how long further attempts short-circuit to null. Without
         *  it a device with no working TTS engine would pay [TTS_INIT_TIMEOUT_MS] on EVERY
         *  voice-list poll (the page polls every 1.5 s during a download). */
        const val TTS_INIT_COOLDOWN_MS = 60_000L

        /** Gap between attempts at opening a summoned live view — see [attemptShowLive]. */
        const val SUMMON_RETRY_MS = 250L

        /** How many attempts before a summon gives up (~10 s at [SUMMON_RETRY_MS]): long enough
         *  for a cold start (activity launch + fragment mount + the camera list load) on a slow
         *  Echo Show, short enough that a stuck summon cannot post forever. */
        const val SUMMON_MAX_ATTEMPTS = 40
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

    /** Per request, so the settings switch takes effect immediately; [SecretStore.of] caches its
     *  encrypted-prefs instance process-wide, so this is two map reads, not key derivation. */
    override fun requiredPassword(): String? =
        ControlSettings.requiredPassword(prefs, SecretStore.of(context))
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
            panel = panelSnapshot(),
            app = ControlApp(
                // Same source as panel.active, so the two cannot disagree.
                foreground = PanelControlRelay.hasHost(),
                canBringForward = AppForeground.canBringForward(context),
            ),
        )
    }

    /**
     * The `panel` block: what the shell is showing (null while nothing can take a switch — see
     * [PanelControlRelay]), where the remote may send it, and the lockscreen's theme.
     *
     * `available` is built from [FeatureRegistry.enabledIds] rather than every [FeatureId], so a
     * feature switched off in settings is not offered; [ControlPanelId.LOCKSCREEN] is appended
     * unconditionally because the screensaver is not a feature and has no enable flag.
     *
     * The reported theme is healed through [SlideshowDisable.initialTheme] for the same reason the
     * in-app picker is: a stored `SLIDESHOW` with the feature since switched off is not the theme
     * that would actually mount, and reporting it would leave the remote showing a selection the
     * device would never honour.
     */
    private fun panelSnapshot(): ControlPanel {
        val slideshowEnabled = SlideshowSettings.isEnabled(prefs)
        val stored = ScreensaverThemeId.fromPrefValue(prefs.getString(ScreensaverController.KEY_THEME, null))
        return ControlPanel(
            active = PanelControlRelay.current(),
            available = FeatureRegistry.enabledIds(prefs).map { ControlPanelId.of(it) } +
                ControlPanelId.LOCKSCREEN,
            lockscreen = ControlLockscreen(
                theme = SlideshowDisable.initialTheme(stored, slideshowEnabled),
                themes = ControlLockscreenThemes.selectable(slideshowEnabled),
            ),
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

    override fun setPanel(id: ControlPanelId): ControlPanelResult = synchronized(commandLock) {
        // Availability is decided here, against the same prefs the snapshot reports from, so the
        // page can never talk the device into mounting a feature the user switched off.
        if (id !in panelSnapshot().available) return ControlPanelResult.Disabled
        // Checked before posting: a request that has nowhere to land must answer 409 now, not be
        // posted into a void and reported as accepted.
        if (!PanelControlRelay.hasHost()) return ControlPanelResult.NoWindow

        // The host commits a fragment transaction, so it must run on the main thread — and this
        // must not block on it: the command lock is held, and the main thread may itself be
        // waiting on something that needs it. The switch is therefore observed by the caller only
        // through the next snapshot, exactly like a brightness apply.
        mainHandler.post { PanelControlRelay.requestPanel(id) }
        // Deliberately the PRE-switch snapshot: the transaction has not run yet, so claiming the
        // new panel here would be the optimistic report the control page is built not to trust.
        ControlPanelResult.Ok(snapshot())
    }

    override fun setLockscreenTheme(theme: ScreensaverThemeId): ControlLockscreenResult =
        synchronized(commandLock) {
            if (theme !in panelSnapshot().lockscreen.themes) return ControlLockscreenResult.ThemeUnavailable
            prefs.edit().putString(ScreensaverController.KEY_THEME, theme.prefValue).apply()
            // Same shape as setFilters: persist under the lock, then announce outside it on the
            // main thread, because the subscriber live-swaps a mounted saver's Views. A device
            // with no window simply has no subscriber — the pref is still saved.
            mainHandler.post { PanelControlRelay.notifyLockscreenThemeChanged() }
            // Safe to report the new theme: unlike a panel switch this IS applied synchronously —
            // the preference is the source of truth and it has already been written.
            ControlLockscreenResult.Ok(snapshot())
        }

    override fun setForeground(on: Boolean): ControlForegroundResult = synchronized(commandLock) {
        // The grant gates BOTH directions. Refusing to send Rusty away without it is the point:
        // on a touchless Echo Show or TV the launcher has no way back to Rusty, so the remote
        // must not be able to perform an action it cannot undo. See ControlForegroundResult.
        if (!AppForeground.canBringForward(context)) return ControlForegroundResult.CannotBringForward

        if (on) {
            // No host needed — this is precisely the case where there is none. Posted rather than
            // run inline: the wake half touches ScreenControlModel, whose renderer callbacks are
            // Activity-bound, and the command lock is held here.
            mainHandler.post { AppForeground.bringToFront(context) }
        } else {
            // A false return means no attached window, i.e. Rusty is already not in front —
            // a satisfied request, so it is deliberately not an error.
            mainHandler.post { PanelControlRelay.requestBackground() }
        }
        // The pre-command snapshot by design: neither direction has run yet, and a page that
        // trusted an optimistic answer here would show a switch that silently lied on the OEM
        // builds where the launch is dropped.
        ControlForegroundResult.Ok(snapshot())
    }

    // -- cameras: list, summon, dismiss, snapshot ------------------------------------------

    /**
     * The summon's memory. Lives on the runtime (not the fragment) because it must outlive every
     * camera screen: a summon foregrounds the app and switches panels, both of which tear the
     * previous fragment down.
     */
    private val summonPlan = CameraSummonPlan()

    /**
     * Bumped by every summon and every restore, so a retry loop still waiting for a camera screen
     * dies the moment a newer command supersedes it (a switch to another camera, or a dismiss).
     * Main thread only.
     */
    private var summonGeneration = 0

    init {
        // The camera screen's only way to tell the API that a summon ended by itself (BACK, the
        // camera deleted, the feature switched off, the app stopped) — see CameraControlRelay.
        // Owner-keyed so a restarted service's registration can't be cleared by the outgoing
        // runtime's release().
        CameraControlRelay.setExitListener(this) { onCameraLiveExited() }
    }

    override fun cameras(): List<ControlCamera>? {
        if (!CameraFeature.isEnabled(prefs)) return null
        // Published by the mounted camera screen (≤1 s stale); no screen mounted means no snapshot
        // loop is running at all, so every camera honestly reports "none".
        val states = CameraControlRelay.host()?.cameraStates() ?: emptyMap()
        return CameraStore.load(prefs).map {
            ControlCamera(id = it.id, name = it.name, state = states[it.id] ?: "none")
        }
    }

    override fun viewCamera(cameraId: String): ControlCameraViewResult = synchronized(commandLock) {
        if (!CameraFeature.isEnabled(prefs)) return ControlCameraViewResult.FeatureDisabled
        // Membership is decided here, against the same store the grid renders from, so an unknown
        // id can never reach the shell (which would foreground the app to show nothing).
        if (CameraStore.load(prefs).none { it.id == cameraId }) return ControlCameraViewResult.UnknownCamera

        // The overlay grant, checked exactly as POST /api/foreground checks it: without it Android
        // silently drops a background activity start, so a summon from a backgrounded Rusty would
        // report success and put nothing on screen. With Rusty already in front no grant is needed.
        val current = PanelControlRelay.current()
        if (current == null && !AppForeground.canBringForward(context)) {
            return ControlCameraViewResult.OverlayPermissionRequired
        }

        // `panelId` is the panel to RESELECT, which while the saver is up is the feature the saver
        // covers — not LOCKSCREEN. Capturing what the shell "shows" (LOCKSCREEN) would lose the
        // feature underneath: the restore would re-show the saver over the summoned CAMERA, and
        // dismissing the saver a moment later would land the user on the camera grid rather than
        // where they were. `screensaverWasActive` is the separate instruction to bring the saver
        // back on top afterwards.
        val capture = SummonCapture(
            panelId = when {
                current == null -> SummonCapture.BACKGROUND
                current == ControlPanelId.LOCKSCREEN ->
                    // The shell always reports its feature alongside the saver; the fallback keeps
                    // an older/odd host from producing a capture that restores nothing at all.
                    (PanelControlRelay.currentFeature() ?: ControlPanelId.LOCKSCREEN).wire
                else -> current.wire
            },
            screensaverWasActive = current == ControlPanelId.LOCKSCREEN,
        )
        // Only the FIRST view of a summon captures — see CameraSummonPlan.
        val commands = summonPlan.onView(cameraId, capture)
        mainHandler.post { executeSummon(commands) }
        // The pre-summon snapshot by design, like every other asynchronously applied command.
        ControlCameraViewResult.Ok(snapshot())
    }

    override fun dismissCamera(): ControlCameraDismissResult = synchronized(commandLock) {
        if (!CameraFeature.isEnabled(prefs)) return ControlCameraDismissResult.FeatureDisabled
        // Checked BEFORE the plan is consumed, exactly as setPanel checks it before posting: with
        // no attached window every restore step is a silent no-op, so a 200 here would report a
        // restore that never happened AND burn the capture, leaving the next dismiss to 409.
        if (!PanelControlRelay.hasHost()) return ControlCameraDismissResult.NoWindow
        val commands = summonPlan.onDismiss()
        if (commands.isEmpty()) return ControlCameraDismissResult.NotSummoned
        mainHandler.post { executeSummon(commands) }
        ControlCameraDismissResult.Ok(snapshot())
    }

    override fun showCameraGrid(): ControlCameraGridResult = synchronized(commandLock) {
        if (!CameraFeature.isEnabled(prefs)) return ControlCameraGridResult.FeatureDisabled
        // Same window check dismissCamera makes, and for the same reason: with nothing attached
        // the panel switch below is a silent no-op, so a 200 would report a screen change that
        // never happened. Deliberately NOT a summon — this does not wake or foreground the device
        // (that is what viewCamera's overlay-gated path is for), it only moves a screen that is
        // already there.
        if (!PanelControlRelay.hasHost()) return ControlCameraGridResult.NoWindow
        // Clears any capture SYNCHRONOUSLY, before the executor below can reach showGridNow() —
        // see CameraSummonPlan.onGrid for why that ordering is the whole trick.
        val commands = summonPlan.onGrid()
        mainHandler.post { executeSummon(commands) }
        ControlCameraGridResult.Ok(snapshot())
    }

    /** Lock-free like the other reads: [CameraControlRelay]'s map and the snapshot store behind it
     *  are each individually thread-safe, and a frame poll must never queue behind a command. */
    override fun cameraSnapshot(cameraId: String): ControlCameraSnapshotResult {
        if (!CameraFeature.isEnabled(prefs)) return ControlCameraSnapshotResult.FeatureDisabled
        // Filtered by the STORE, not by what the snapshot map happens to hold: a just-deleted
        // camera can still have a frame cached for a tick, and serving it would be serving a
        // camera the user removed.
        if (CameraStore.load(prefs).none { it.id == cameraId }) return ControlCameraSnapshotResult.UnknownCamera
        val jpeg = CameraControlRelay.host()?.snapshotJpeg(cameraId)
            ?: return ControlCameraSnapshotResult.NoFrame
        return ControlCameraSnapshotResult.Ok(jpeg)
    }

    /**
     * Single-flight guard for [localCameraSnapshot] below — see that function's KDoc for the
     * thread-budget reason a second concurrent grab must fail fast rather than queue.
     */
    private val localSnapshotInFlight = AtomicBoolean(false)

    /**
     * Pool thread. [CameraShareService.hub] opens the camera if it is idle and its linger closes
     * it again.
     *
     * ## Thread budget
     * [CameraShareHub.snapshotJpeg] already serializes concurrent grabs against ITSELF (the
     * pipeline holds one still slot, one image deep) — but it does that by BLOCKING the loser
     * until the winner's grab returns, which the hub's own doc says can take seconds and longer
     * still on a cold start that has to open the camera first. This method runs on a worker from
     * [ControlHttpServer]'s bounded 8-thread pool, the SAME pool `/api/state` draws from — the one
     * endpoint Home Assistant polls to decide this device is even alive. If several other Rusty
     * devices thumbnail this one's grid at once, letting them queue on the hub's internal lock
     * would tie up one pool worker per waiter for as long as the grabs ahead of it take (not a
     * single bounded wait, but N of them stacked in series), which is exactly the kind of burst
     * that could starve `/api/state` of every worker.
     *
     * [localSnapshotInFlight] keeps that risk to AT MOST one worker ever blocked inside a real
     * grab at a time: a request that finds one already running fails fast with
     * [ControlLocalSnapshotResult.Unavailable] (503) instead of queuing behind it. A thumbnail
     * poller retrying a 503 shortly is a perfectly normal outcome; eight blocked workers turning
     * `/api/state` into a false "device offline" for Home Assistant is not.
     */
    override fun localCameraSnapshot(): ControlLocalSnapshotResult {
        if (!CameraShareSettings.isEnabled(prefs)) return ControlLocalSnapshotResult.SharingOff
        val hub = CameraShareService.hub()
            ?: return ControlLocalSnapshotResult.Unavailable("camera share is starting")
        (CameraShareStatus.current() as? CameraShareStatus.State.Unsupported)?.let {
            return ControlLocalSnapshotResult.Unavailable(it.reason)
        }
        if (!localSnapshotInFlight.compareAndSet(false, true)) {
            return ControlLocalSnapshotResult.Unavailable("a snapshot is already being captured, try again shortly")
        }
        return try {
            hub.snapshotJpeg()?.let { ControlLocalSnapshotResult.Ok(it) }
                ?: ControlLocalSnapshotResult.Unavailable(
                    (CameraShareStatus.current() as? CameraShareStatus.State.Unavailable)?.reason ?: "no frame"
                )
        } finally {
            localSnapshotInFlight.set(false)
        }
    }

    /** Runs the plan's commands. Main thread: every one of them touches windows or fragments. */
    private fun executeSummon(commands: List<SummonCmd>) {
        for (command in commands) {
            when (command) {
                is SummonCmd.ShowLive -> executeShowLive(command.cameraId)
                is SummonCmd.Restore -> executeRestore(command.capture)
                SummonCmd.ShowGrid -> executeShowGrid()
                SummonCmd.None -> Unit
            }
        }
    }

    /**
     * Wake → foreground → camera panel → live view, in that order, because
     * [CameraFragment.showLive] refuses to open a session unless its fragment is RESUMED and its
     * camera list has loaded. Neither is true yet at this point on a cold summon (the app may not
     * even be in front), so the last step is a bounded retry loop rather than a single call — see
     * [attemptShowLive].
     */
    private fun executeShowLive(cameraId: String) {
        summonGeneration++
        // A summon to a dark device has to light it up; same path POST /api/screen drives.
        ScreenControlModel.set(true, null)
        // Not gated on the grant here: viewCamera already refused the summon without it. With a
        // host attached Rusty is already in front and this would be a no-op anyway.
        if (!PanelControlRelay.hasHost()) AppForeground.bringToFront(context)
        attemptShowLive(cameraId, summonGeneration, attempt = 0)
    }

    /**
     * One attempt at putting [cameraId]'s live view up, re-posted every [SUMMON_RETRY_MS] until it
     * lands or [SUMMON_MAX_ATTEMPTS] is reached (~10 s — an activity start plus a fragment mount,
     * with generous room for a device that was asleep). Abandoned immediately when [generation] is
     * stale, i.e. when a newer view or a dismiss has superseded this summon.
     */
    private fun attemptShowLive(cameraId: String, generation: Int, attempt: Int) {
        if (generation != summonGeneration) return

        // Re-asked rather than asked once: on a cold summon the first attempts run before the
        // Activity exists, so there is no host to take the switch yet.
        if (PanelControlRelay.current() != ControlPanelId.CAMERA) {
            PanelControlRelay.requestPanel(ControlPanelId.CAMERA)
        }
        // False = dropped (not resumed yet, or the camera list hasn't loaded) — the exact case
        // this loop exists for.
        if (CameraControlRelay.host()?.showLiveNow(cameraId) == true) return

        if (attempt >= SUMMON_MAX_ATTEMPTS) {
            Log.w(TAG, "camera summon gave up waiting for the camera screen")
            return
        }
        mainHandler.postDelayed({ attemptShowLive(cameraId, generation, attempt + 1) }, SUMMON_RETRY_MS)
    }

    /**
     * Puts the camera grid on screen.
     *
     * No capture is consulted: [CameraSummonPlan.onGrid] already dropped it, which is what keeps
     * [CameraFragment.showGrid]'s external-exit notification from turning this into a restore.
     *
     * A camera screen that is not mounted yet needs no [CameraControlHost.showGridNow] call at
     * all: [CameraFragment] resets its mode to GRID in `onStop`, so the fragment the panel switch
     * brings up always comes up on the grid. The call below is for the case that actually needs
     * it — the camera panel already showing, with a live view open on top of it.
     */
    private fun executeShowGrid() {
        // Cancels any retry loop still chasing a summon this request supersedes; without it a
        // late attemptShowLive would re-open the live view the user just asked to leave.
        summonGeneration++
        if (PanelControlRelay.current() != ControlPanelId.CAMERA) {
            PanelControlRelay.requestPanel(ControlPanelId.CAMERA)
        }
        CameraControlRelay.host()?.showGridNow()
    }

    /** Puts back what [SummonCapture] recorded. */
    private fun executeRestore(capture: SummonCapture) {
        // Cancels any retry loop still chasing the summon being undone.
        summonGeneration++
        // Leave the live view first: it holds a decoder (and possibly audio focus), and the panel
        // switch below does not by itself close it. Idempotent, and a no-op when the user already
        // left with BACK.
        CameraControlRelay.host()?.showGridNow()

        // Someone else already moved the shell off the camera (a manual feature switch from the
        // launcher, a playback takeover): the summon is over, and forcing the captured panel now
        // would override a choice the user made after it.
        //
        // Read from the FEATURE, never from the raw panel: an unattended summon — the core use
        // case — idles into the screensaver over the camera, at which point the panel reads
        // LOCKSCREEN while the feature underneath is still CAMERA. Guarding on the panel would
        // abandon exactly the restore that matters most. A null feature (detached, or a host that
        // doesn't report one) falls through to the restore, which is harmlessly inert with no host.
        val feature = PanelControlRelay.currentFeature()
            ?: PanelControlRelay.current()?.takeIf { it != ControlPanelId.LOCKSCREEN }
        if (feature != null && feature != ControlPanelId.CAMERA) return

        if (capture.panelId == SummonCapture.BACKGROUND) {
            // Rusty was not on screen when it was summoned, so "restore" means getting out of the
            // way again — but only while the grant that let it come forward is still held, the
            // same both-directions rule POST /api/foreground follows.
            if (AppForeground.canBringForward(context)) PanelControlRelay.requestBackground()
            return
        }
        // Feature first, saver second — the saver covers whatever feature is current, so showing
        // it before the switch would leave the camera underneath it.
        // ...and skipped entirely when that feature is already the one showing (summoned from the
        // camera feature itself): switching to it would dismiss the saver only for the line below
        // to put it straight back — a visible double transition for no change at all.
        val panel = ControlPanelId.fromWire(capture.panelId)
        if (panel != null && panel != ControlPanelId.LOCKSCREEN && panel != feature) {
            PanelControlRelay.requestPanel(panel)
        }
        if (capture.screensaverWasActive || panel == ControlPanelId.LOCKSCREEN) {
            PanelControlRelay.requestPanel(ControlPanelId.LOCKSCREEN)
        }
    }

    /**
     * The camera screen left its live view by itself (BACK, the camera deleted, the feature
     * switched off). Restores exactly as a dismiss would; a no-op when no summon is in force
     * (an ordinary in-app BACK out of a live view the user opened themselves).
     */
    private fun onCameraLiveExited() {
        val commands = summonPlan.onExternalExit()
        if (commands.isEmpty()) return
        // Already on the main thread, but POSTED: this arrives from inside
        // CameraFragment.showGrid, and restoring the panel re-enters the shell — that must not
        // happen underneath a fragment mid-teardown.
        mainHandler.post { executeSummon(commands) }
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

    // -- announcements (TTS through the DLNA pipeline) --------------------

    /**
     * Serializes announcements on their OWN lock, never [commandLock]: TTS synthesis can take
     * seconds, and a screen/volume command must not queue behind it. Serialization matters for
     * the shared TTS engine (one utterance listener slot) and keeps two uploads from ever
     * racing the SetUri → Play chain into the renderer interleaved.
     */
    private val announceLock = Any()

    /** Non-null after the first successful lazy init; released by [release]. */
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var released = false

    /** Wall-clock of the last FAILED engine init, for [TTS_INIT_COOLDOWN_MS]; 0 = never failed. */
    @Volatile private var lastTtsInitFailureMs = 0L

    /**
     * Serializes engine init/publish/teardown WITHOUT involving [announceLock], so a voice-list
     * poll looking the engine up can never queue behind a 30 s synthesis. Where both locks are
     * held the order is announceLock → ttsInitLock ([announceText] → [ttsEngine]); nothing takes
     * them the other way around.
     */
    private val ttsInitLock = Any()

    // -- downloadable Piper voices ---------------------------------------------------------

    private val piperStore = PiperVoiceStore(context)
    private val piperEngine = PiperEngine()

    /** The curated catalog shipped in assets; a parse failure (a build mistake — the file is
     *  checked in and tested) degrades to "no downloadable voices", never a crash. */
    private val piperCatalog: List<PiperVoice> by lazy { PiperVoiceStore.loadCatalog(context) }

    /** The download slot is process-wide ([PiperDownloads]) — the settings picker installs
     *  voices too, and the same voice directory must never be published twice at once. This
     *  hook is how a download or delete THERE drops a model mapped HERE: the next Piper
     *  announcement reloads from the fresh files. Runs on the caller's thread, serialized with
     *  any announcement in flight. */
    private val engineInvalidator: () -> Unit = { synchronized(announceLock) { piperEngine.release() } }

    init {
        PiperDownloads.addEngineInvalidator(engineInvalidator)
    }

    override fun announceText(text: String): ControlAnnounceResult = synchronized(announceLock) {
        val renderer = MediaRendererService.instance ?: return ControlAnnounceResult.RendererUnavailable
        val selector = TtsVoices.parse(selectedVoicePref()) ?: VoiceSelector.SystemDefault
        val file = newAnnouncementFile("tts", "wav")
        // The selector prefix routes synthesis. No silent cross-engine fallback in either
        // direction — the user chose that voice; a failure is the route's 503.
        if (selector is VoiceSelector.Piper) {
            if (!piperStore.isInstalled(selector.voiceId)) return ControlAnnounceResult.TtsUnavailable
            val ok = piperEngine.synthesizeToFile(
                selector.voiceId, piperStore.voiceDir(selector.voiceId), text, file,
            )
            if (!ok) return ControlAnnounceResult.TtsUnavailable
        } else {
            val engine = ttsEngine() ?: return ControlAnnounceResult.TtsUnavailable
            applySelectedVoice(engine)
            // synthesizeToFile writes WAV regardless of engine.
            if (!synthesizeBlocking(engine, text, file)) return ControlAnnounceResult.TtsUnavailable
        }
        play(renderer, file, "audio/wav", "Announcement")
    }

    private fun play(renderer: MediaRendererService, file: File, mime: String, title: String): ControlAnnounceResult =
        // Same process, so a plain file:// URI is playable by the renderer's ExoPlayer with no
        // FileProvider or loopback HTTP hop. playAnnouncement returns false only when the
        // service tore down between the instance read above and now.
        if (renderer.playAnnouncement(Uri.fromFile(file).toString(), mime, title)) ControlAnnounceResult.Ok
        else ControlAnnounceResult.RendererUnavailable

    /**
     * A FRESH file per announcement, never overwrite-in-place: ExoPlayer may still be reading the
     * previous clip when the next one arrives (SetUri replaces it asynchronously on the main
     * thread), and rewriting a file mid-read would corrupt live playback. Older siblings are
     * pruned on a lag long enough that nothing still playing can be deleted under the player.
     */
    private fun newAnnouncementFile(prefix: String, ext: String): File {
        val dir = File(context.cacheDir, "announce").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()
            ?.filter { now - it.lastModified() > ANNOUNCE_PRUNE_AGE_MS }
            ?.forEach { runCatching { it.delete() } }
        return File(dir, "$prefix-$now.$ext")
    }

    // -- voice selection (system TTS) ------------------------------------------------------

    /**
     * Deliberately NOT under [announceLock]: this is what the page polls every 1.5 s during a
     * voice download, and an announcement in flight may hold that lock for tens of seconds —
     * queueing polls behind it would eat the HTTP pool. Every read below is individually
     * thread-safe (prefs, a disk listing, the download manager's own lock, the kept engine
     * reference / [ttsInitLock]); the writers keep taking [announceLock], so a poll racing one
     * of them just serves the listing from a moment earlier.
     */
    override fun ttsVoices(): ControlTtsVoices {
        val selected = selectedVoicePref()
        val installed = piperStore.installedIds()
        // Installed catalog voices list as selectable rows ahead of the system voices; a device
        // without a working system TTS engine still answers (default row + Piper rows) rather
        // than 500ing the picker.
        val piperRows = piperStore.installedRows(piperCatalog)
        val systemRows = ttsEngine()?.let { SystemTtsVoices.list(it) } ?: emptyList()
        return ControlTtsVoices(
            selected = selected,
            voices = TtsVoices.pickerRows(piperRows, systemRows),
            catalog = piperCatalog.map { ControlCatalogVoice(it, it.id in installed) },
            download = PiperDownloads.snapshot(),
        )
    }

    override fun setTtsVoice(selector: VoiceSelector): ControlTtsVoiceResult = synchronized(announceLock) {
        when (selector) {
            VoiceSelector.SystemDefault -> Unit
            is VoiceSelector.System -> {
                val engine = ttsEngine() ?: return ControlTtsVoiceResult.TtsUnavailable
                val known = runCatching { engine.voices }.getOrNull()
                    ?.any { it.name == selector.voiceName } == true
                if (!known) return ControlTtsVoiceResult.UnknownVoice
            }
            // Selectable once its files are on disk; the catalog knowing the id is not enough.
            is VoiceSelector.Piper ->
                if (!piperStore.isInstalled(selector.voiceId)) return ControlTtsVoiceResult.UnknownVoice
        }
        prefs.edit().putString(TtsVoices.PREF_KEY, TtsVoices.format(selector)).apply()
        // Re-listed under the same lock so the Ok body reports exactly what was persisted.
        ControlTtsVoiceResult.Ok(ttsVoices())
    }

    override fun downloadTtsVoice(voiceId: String): ControlVoiceDownloadStart {
        val voice = piperCatalog.firstOrNull { it.id == voiceId }
            ?: return ControlVoiceDownloadStart.UNKNOWN_VOICE
        // Space gate and the "a re-download is a repair" allowance live with the shared slot.
        return PiperDownloads.start(context, voice)
    }

    override fun deleteTtsVoice(voiceId: String): ControlVoiceDeleteResult = synchronized(announceLock) {
        // Shared with the settings picker, including the busy refusal (the extractor's atomic
        // publish would race the recursive delete), dropping the mapped model before the files
        // go, and falling the selection back when it named this voice.
        when (PiperDownloads.deleteVoice(context, prefs, voiceId)) {
            VoiceDeleteOutcome.BUSY -> ControlVoiceDeleteResult.Busy
            VoiceDeleteOutcome.NOT_INSTALLED -> ControlVoiceDeleteResult.NotInstalled
            VoiceDeleteOutcome.DELETED -> ControlVoiceDeleteResult.Ok(ttsVoices())
        }
    }

    private fun selectedVoicePref(): String {
        val raw = prefs.getString(TtsVoices.PREF_KEY, null) ?: return TtsVoices.SYSTEM_DEFAULT
        // A corrupted or future-format pref must not leak to clients as an unparseable id.
        return if (TtsVoices.parse(raw) != null) raw else TtsVoices.SYSTEM_DEFAULT
    }

    /**
     * Points the kept engine at the persisted selection before each synthesis. An unknown or
     * uninstalled selection falls back to the engine default rather than failing the
     * announcement — the voice is a preference, not a precondition. Falling back (and the
     * SystemDefault case) RESETS `voice`: the engine is kept between announcements, so a stale
     * selection would otherwise stick after the user switches back to default.
     */
    private fun applySelectedVoice(engine: TextToSpeech) {
        val selector = TtsVoices.parse(selectedVoicePref()) ?: VoiceSelector.SystemDefault
        val target = (selector as? VoiceSelector.System)?.let { sel ->
            runCatching { engine.voices }.getOrNull()?.firstOrNull { it.name == sel.voiceName }
        }
        runCatching {
            engine.voice = target ?: engine.defaultVoice ?: return
        }
    }

    /**
     * Lazy, blocking TTS init — pool threads only, same cost class as [updateCheck]. The engine is
     * kept for the life of the runtime once up: init binds a system service and takes noticeable
     * time, and announcements tend to come in bursts. A failed init is retried by a later call
     * rather than latched — the usual cause (engine still booting after device start) is transient
     * — but only after [TTS_INIT_COOLDOWN_MS], so a device with no working engine answers its
     * voice-list polls instantly instead of paying the init timeout on each one.
     */
    private fun ttsEngine(): TextToSpeech? {
        tts?.let { return it }
        if (released) return null
        synchronized(ttsInitLock) {
            tts?.let { return it }
            if (released) return null
            if (System.currentTimeMillis() - lastTtsInitFailureMs < TTS_INIT_COOLDOWN_MS) return null
            val ready = CountDownLatch(1)
            var status = TextToSpeech.ERROR
            val engine = TextToSpeech(context) { s ->
                status = s
                ready.countDown()
            }
            val initialised = runCatching { ready.await(TTS_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrDefault(false) && status == TextToSpeech.SUCCESS
            if (!initialised) {
                runCatching { engine.shutdown() }
                lastTtsInitFailureMs = System.currentTimeMillis()
                Log.w(TAG, "TTS engine failed to initialise; retrying after cooldown")
                return null
            }
            // release() may have run while we waited on the latch (it could not take
            // ttsInitLock yet, but `released` is volatile); a kept engine would leak its
            // service binding past the runtime's death.
            if (released) {
                runCatching { engine.shutdown() }
                return null
            }
            tts = engine
            return engine
        }
    }

    /** Blocks until the engine reports the utterance written (or failed/timed out). The listener
     *  filters by utterance id so a stale callback from an earlier synthesis cannot satisfy it. */
    private fun synthesizeBlocking(engine: TextToSpeech, text: String, out: File): Boolean {
        val id = "announce-" + System.nanoTime()
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) {
                    ok.set(true)
                    done.countDown()
                }
            }
            @Deprecated("pre-21 signature; still invoked by some engines")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) done.countDown()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) done.countDown()
            }
        })
        if (engine.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) return false
        val finished = runCatching { done.await(TTS_SYNTH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        return finished && ok.get() && out.length() > 0
    }

    /** Idempotent; called (off the main thread — engine shutdown and the announce lock can both
     *  block) by the service's onDestroy after the HTTP server is stopped. */
    fun release() {
        // Dropped first: nothing should be able to drive a panel restore through a runtime that is
        // being torn down (the camera screen outlives this service — the feature works with the
        // API off).
        CameraControlRelay.clearExitListener(this)
        released = true   // volatile write FIRST: an init mid-await sees it and discards its engine
        val engine = synchronized(ttsInitLock) {
            val e = tts
            tts = null
            e
        }
        engine?.let { runCatching { it.shutdown() } }
        // Under the announce lock: a Piper synthesis that beat the server shutdown may still be
        // inside the engine; unmapping the model out from under it is native code.
        synchronized(announceLock) { piperEngine.release() }
        // The download slot outlives this service (the settings picker shares it), so a fetch in
        // flight is left running — only this service's claim on the engine is dropped.
        PiperDownloads.removeEngineInvalidator(engineInvalidator)
    }

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
