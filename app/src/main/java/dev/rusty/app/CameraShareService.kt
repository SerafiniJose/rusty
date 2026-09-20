package dev.rusty.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Composition root for "Share this device's camera": the foreground service that owns the
 * [CameraCapturePipeline], the [CameraShareHub] and the [RtspServer] for exactly as long as the
 * share is on, and the only place the three are wired together.
 *
 * ## Why a foreground service, of type `camera`
 *
 * The share has to answer a viewer while this device's screen is off and the app is backgrounded,
 * so it cannot live in an Activity. Android 11+ then requires the `camera` foreground-service type
 * for any process that opens a camera from the background, and Android 14+ requires
 * `FOREGROUND_SERVICE_CAMERA` plus the runtime CAMERA grant to start one at all.
 *
 * That type comes with the rule that shapes every start path here: **a camera FGS may only be
 * started while the app is visible.** So the share is started from [HomeActivity.onStart] and from
 * the settings switch — never from `BootReceiver`, and never from `RustyApp.onCreate`, both of
 * which run with no window on screen and would be refused. Consequently it is also
 * `START_NOT_STICKY`: a sticky restart is a background start by definition, and the next time the
 * user opens the app [syncFromPrefs] brings the share back anyway.
 *
 * ## Remote Control is a prerequisite
 *
 * The `cam=1` TXT record other Rusty devices scan for and the `/api/camera/local/snapshot.jpg`
 * endpoint that thumbnails this share both live in [ControlService]. A share running without it is
 * an RTSP port nothing can discover, so [shouldRun] requires BOTH switches and [prefsListener]
 * stops this service the moment Remote Control is switched off underneath it.
 *
 * ## Threads
 *
 * Everything here runs on the main thread except two deliberate exceptions:
 * - the RTSP server's own accept/reader/writer threads, which is where the multi-second camera
 *   open happens (see [CameraShareHub]'s threading contract), and
 * - [lensExecutor], because [CameraShareHub.restartForLensChange] is a synchronous camera close
 *   and reopen and SharedPreferences delivers every change callback on the main thread.
 */
class CameraShareService : Service() {

    companion object {
        private const val TAG = "CameraShareService"
        private const val PREFS_NAME = "spotify_receiver_prefs"

        /** Distinct from SpotifyService (1), MediaRendererService (2), the group summary (3) and
         *  ControlService (4). */
        private const val NOTIFICATION_ID = 6
        private const val CHANNEL_ID = "rusty_camera_share"
        private const val NOTIFICATION_TITLE = "Camera share"
        private const val STARTING_TEXT = "Starting…"

        /** Shade Stop action — mirrors [ControlService.ACTION_STOP]. */
        const val ACTION_STOP = "dev.rusty.app.action.CAMERA_SHARE_STOP"

        @Volatile private var liveHub: CameraShareHub? = null

        /**
         * The hub of the running share, or null when nothing is being shared. Read by the control
         * API's local-snapshot endpoint, which needs a JPEG out of the same camera the RTSP server
         * is streaming — opening a second [CameraCapturePipeline] for it would fight this one for
         * the device. Withdrawn at the very top of [onDestroy], so a snapshot arriving during
         * teardown cannot reopen a camera that is about to lose its owner.
         */
        fun hub(): CameraShareHub? = liveHub

        /**
         * Pure decision — unit-testable: should the share be running, given prefs?
         *
         * Remote Control is a prerequisite (its service owns mDNS advertising and the snapshot
         * endpoint), so sharing runs only while BOTH switches are on. Gated here rather than only
         * at the switch's call site so the rule also holds for the app-start re-sync and for
         * installs whose prefs already drifted into share-on/control-off.
         */
        fun shouldRun(prefs: SharedPreferences): Boolean =
            CameraShareSettings.isEnabled(prefs) && ControlSettings.isEnabled(prefs)

        /**
         * Starts or stops the service to match the persisted switches — the single entry point
         * used by [HomeActivity.onStart] and, later, by the settings switch. Mirrors
         * [ControlService.syncFromPrefs].
         *
         * Starting an already-running service is harmless: the re-delivered `onStartCommand`
         * no-ops (see [onStartCommand]).
         */
        fun syncFromPrefs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val intent = Intent(context, CameraShareService::class.java)
            if (!shouldRun(prefs)) {
                context.stopService(intent)
                // Mirrors ControlServerStatus.publishStoppedIfInactive: a start the system refused
                // left a reason behind with no service to clear it, and that reason must not
                // outlive the switch that produced it. A LIVE service publishes its own Off from
                // onDestroy, so only speak for one that is not there.
                if (liveHub == null) CameraShareStatus.publish(CameraShareStatus.State.Off)
                return
            }
            // A device with no camera and a device with no H.264 encoder answer the same way
            // every time, and getting that answer costs a service start, a foreground promotion
            // (a "Starting…" notification flashed into the shade) and a codec + camera
            // enumeration — on EVERY HomeActivity.onStart, for as long as the pref stays on.
            // [CameraShareStatus] is process-static, so once the answer is known this re-sync can
            // simply stop here. Only Unsupported: Unavailable is a condition that heals (a busy
            // port, a permission just granted) and its re-sync IS the retry.
            if (CameraShareStatus.current() is CameraShareStatus.State.Unsupported) return
            // The runtime grant is requested by the settings switch, which has a window to ask
            // from; a service has none. Without it a camera FGS is a SecurityException on API 34+,
            // so say why and stay stopped rather than crash.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                context.stopService(intent)
                CameraShareStatus.publish(CameraShareStatus.State.Unavailable("camera permission needed"))
                return
            }
            runCatching { context.startForegroundService(intent) }
                .onFailure { e ->
                    Log.w(TAG, "camera share start refused by the system", e)
                    CameraShareStatus.publish(
                        CameraShareStatus.State.Unavailable(e.message ?: "could not start the service")
                    )
                }
        }

        /** Pure: what the shade says about a given state. Idle and streaming must read
         *  differently — "is anyone watching me?" is the question this notification answers. */
        @VisibleForTesting
        fun notificationText(state: CameraShareStatus.State): String = when (state) {
            is CameraShareStatus.State.Streaming ->
                if (state.viewers == 1) "Sharing camera to 1 viewer"
                else "Sharing camera to ${state.viewers} viewers"
            is CameraShareStatus.State.Unavailable -> "Camera unavailable: ${state.reason}"
            // Unsupported reasons are already whole phrases ("no H.264 encoder on this device").
            is CameraShareStatus.State.Unsupported -> state.reason
            // Ready is the lazy-camera idle state: advertised, nobody watching, camera CLOSED.
            CameraShareStatus.State.Ready -> "Ready for viewers"
            // Reachable, briefly: [CameraShareStatus.addListener] REPLAYS the current value, so
            // a service starting while the previous instance's Off is still the published state
            // posts this before its own first publish lands. Claiming to be sharing there would
            // be a lie, and the shade is the one place a camera claim must not be one.
            CameraShareStatus.State.Off -> "Not sharing"
        }
    }

    private var started = false

    /**
     * The reason a start path gave up, remembered so [onDestroy] can put it back.
     *
     * Reading it back off [CameraShareStatus] at the end of teardown does not work once a hub
     * exists: [CameraShareHub.shutdown] publishes [CameraShareStatus.State.Off] unconditionally —
     * it cannot know it is being torn down mid-start rather than switched off — so the reason
     * would be gone by the time onDestroy asked for it, and the settings row would show a share
     * that is merely "off" instead of one that could not open its port.
     */
    private var terminalState: CameraShareStatus.State? = null
    private var server: RtspServer? = null
    private var hub: CameraShareHub? = null
    private var pipeline: CameraCapturePipeline? = null
    private val main = Handler(Looper.getMainLooper())

    /**
     * The ONE thread a lens change may run on.
     *
     * [CameraShareHub.restartForLensChange] closes and reopens the camera SYNCHRONOUSLY: seconds
     * on a good device, and the pipeline's full 4 s open timeout on an Echo Show whose hardware
     * privacy gate is engaged. SharedPreferences delivers every change callback on the MAIN thread
     * (it posts there when the writer was a background thread), so running it inline from
     * [prefsListener] would be a guaranteed ANR. Single-threaded, so two quick flips of the lens
     * choice queue behind each other instead of racing into the hub's camera mutex; daemon, so a
     * restart in flight can never pin the process.
     */
    private val lensExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camera-share-lens").apply { isDaemon = true }
    }

    /** Held as a field so [onDestroy] can remove the SAME instance (a fresh lambda would not
     *  match). Delivered on the main thread by [CameraShareStatus]'s dispatcher. */
    private val statusListener: (CameraShareStatus.State) -> Unit = { state ->
        if (started) postNotification(notificationText(state))
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            // NEVER inline — see [lensExecutor]: this callback is the main thread.
            CameraShareSettings.KEY_LENS,
            CameraShareSettings.KEY_RESOLUTION,
            CameraShareSettings.KEY_TIER,
            CameraShareSettings.KEY_FPS -> hub?.let { h ->
                runCatching {
                    lensExecutor.execute {
                        // An exception escaping a ThreadPoolExecutor task reaches the thread's
                        // default uncaught handler, which on Android takes the whole process down.
                        // A lens change is a camera close and reopen: it can throw for reasons
                        // entirely outside this app (the Echo Show's hardware privacy gate, a
                        // camera another app grabbed first), and none of them are worth a crash.
                        // The hub publishes its own Unavailable; the share keeps the old lens.
                        runCatching { h.restartForLensChange() }
                            .onFailure { Log.w(TAG, "lens change failed", it) }
                    }
                }.onFailure { Log.w(TAG, "lens change dropped: the service is stopping", it) }
            }
            // Remote Control switched off underneath us: sharing is its dependent (mDNS and the
            // snapshot endpoint live in ControlService), so it goes with it.
            ControlSettings.KEY_ENABLED -> if (!ControlSettings.isEnabled(prefs)) stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A shade stop must behave exactly like the settings switch: clear the pref FIRST so
            // the share does not resurrect on the next app start (same rule as ControlService's
            // and MediaRendererService's ACTION_STOP). No startForeground on this path — the
            // service is already foregrounded, and stopSelf is the documented escape.
            CameraShareSettings.setEnabled(getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (started) {
            // Re-delivered start: HomeActivity re-syncs on every return to the foreground.
            Log.i(TAG, "onStartCommand: already started; ignoring duplicate start")
            return START_NOT_STICKY
        }
        started = true

        // FGS promotion first — Android's deadline is short and everything below can block.
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        val promoted = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(STARTING_TEXT), type)
        }.onFailure { e ->
            // API 34+ refuses a camera FGS started from the background or without the CAMERA
            // grant. Stop immediately: a startForegroundService() that never reaches
            // startForeground() is itself a crash a few seconds later.
            Log.w(TAG, "camera foreground promotion refused", e)
            fail(CameraShareStatus.State.Unavailable(e.message ?: "could not start sharing"))
        }.isSuccess
        if (!promoted) return START_NOT_STICKY
        ServiceNotifications.started(this, ServiceNotifications.Kind.CAMERA_SHARE)

        // Neither a camera nor an encoder can be conjured. Say so plainly and stop, rather than
        // advertise a share that answers every DESCRIBE with a failure. lensCount counts DISTINCT
        // front/back facings, which is exactly what CameraShareSettings.Lens offers: a device with
        // only an external camera cannot serve this share either.
        val unsupported = when {
            !CameraCapturePipeline.hasH264Encoder() -> "no H.264 encoder on this device"
            CameraCapturePipeline.lensCount(applicationContext) == 0 -> "this device has no camera"
            else -> null
        }
        if (unsupported != null) {
            fail(CameraShareStatus.State.Unsupported(unsupported))
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val app = applicationContext
        // A local, not the `main` field: a lambda that reads a member captures `this`, which would
        // make the hub — and through it the process-static liveHub — hold this Service, and with
        // it a Context, alive for as long as the reference lasts.
        val handler = main
        // Called with the hub's state lock held, so it may only POST the task and un-post it —
        // never run it inline.
        val scheduler = DelayScheduler { delayMs, task ->
            val r = Runnable { task() }
            handler.postDelayed(r, delayMs)
            ({ handler.removeCallbacks(r) })
        }
        val pipeline = CameraCapturePipeline(applicationContext)
        this.pipeline = pipeline
        val hub = CameraShareHub(
            pipeline = pipeline,
            lens = { CameraShareSettings.lens(prefs) },
            encoding = { CameraShareSettings.encoding(prefs) },
            scheduler = scheduler,
            publish = { CameraShareStatus.publish(it) },
        )
        this.hub = hub
        liveHub = hub
        val server = RtspServer(
            port = CameraShareSettings.RTSP_PORT,
            hub = hub,
            // Read per request, not captured: the API password can change under a live share, and
            // the next RTSP challenge must use the new one. SecretStore.of() is built INSIDE the
            // lambda, exactly as ControlServiceRuntime.requiredPassword does it — it builds an
            // Android Keystore-backed EncryptedSharedPreferences (key generation, Tink AEAD setup
            // and a file read), and this runs on an RTSP socket thread whereas onStartCommand is
            // the MAIN thread, reached from HomeActivity.onStart on every return to the
            // foreground. RustyApp.onCreate guards the same construction for the same reason. The
            // instance is cached process-wide, so per request this is two map reads.
            password = { ControlSettings.requiredPassword(prefs, SecretStore.of(app)) },
            log = { Log.i(TAG, it) },
        )
        this.server = server

        // Listener BEFORE the first publish, so the shade is never left sitting on "Starting…"
        // with nothing registered to move it on.
        CameraShareStatus.addListener(statusListener)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        if (!server.start()) {
            // Stop rather than linger — the same call ControlService makes when its own bind
            // fails, and for the same two reasons. `started` would stay true on a live-but-dead
            // instance, so every later syncFromPrefs would hit the duplicate-start guard and the
            // share would be wedged for the life of the process, contradicting HomeActivity's
            // promise that re-syncing "also heals a start the system once refused"; only the user
            // toggling the switch could recover it. And liveHub is already set by this point, so
            // the local-snapshot endpoint would happily open the camera and serve stills for a
            // share whose RTSP port never opened — other Rusty devices would thumbnail it and
            // then fail to play it. Stopping makes the next start a fresh instance, which IS the
            // retry; [fail] carries the reason across the teardown for the settings row.
            fail(CameraShareStatus.State.Unavailable("port ${CameraShareSettings.RTSP_PORT} busy"))
            return START_NOT_STICKY
        }
        // This instance is up, so whatever an EARLIER start of it could not do is history. [fail]
        // clears `started`, so a retry — the user toggling off and on, or the next
        // HomeActivity.onStart — is answered by this same instance whenever it arrives before the
        // pending stopSelf's onDestroy. Left set, that onDestroy would then republish the old
        // reason over a share that is running: the row would show "Ready", then snap back to
        // "port 8554 busy" with nothing wrong.
        terminalState = null
        CameraShareStatus.publish(CameraShareStatus.State.Ready)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Withdrawn FIRST: a snapshot request arriving mid-teardown must not adopt a hub that is
        // going away and reopen the camera behind the server's back.
        if (liveHub === hub) liveHub = null
        CameraShareStatus.removeListener(statusListener)
        runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
        }

        // This order is not negotiable. The SERVER first, so no new viewer can attach or DESCRIBE
        // its way into a camera open; then the HUB, which refuses every later DESCRIBE and asks
        // for a stop; then the PIPELINE, which retires its camera thread and release worker.
        // hub.shutdown() deliberately does NOT wait for a start already in flight, so close() can
        // land on top of one — CameraCapturePipeline.close() is written for exactly that, and
        // neither call may be moved above the one before it. All three return promptly: nothing
        // here waits on a camera close.
        server?.stop(); server = null
        hub?.shutdown(); hub = null
        pipeline?.close(); pipeline = null
        // After the pipeline: a queued lens restart can no longer reach a live camera. shutdown(),
        // never shutdownNow() — a restart already inside a camera close is left to finish.
        lensExecutor.shutdown()

        ServiceNotifications.stopped(this, ServiceNotifications.Kind.CAMERA_SHARE)
        // NOT the ghost-notification protection it looks like: the framework refuses an app's
        // cancel() of a notification still carrying FLAG_FOREGROUND_SERVICE, so on the ordinary
        // path this line does nothing. What actually stops a ghost is the removeListener at the
        // top of this method — no listener, no out-of-band re-post. Kept because it does clear
        // anything left behind once the flag is gone, and costs nothing.
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }

        // Off LAST, and only when there is nothing better to say. The reason a share never came
        // up is the one thing the settings row still needs after teardown (those paths stop this
        // service on purpose); an unconditional Off would erase it a millisecond after publishing
        // it. [terminalState] first and the live state only as a fallback, because hub.shutdown()
        // above publishes Off unconditionally: on the bind-failure path the reason is no longer
        // readable from CameraShareStatus by the time we get here. An ordinary stop has neither,
        // and says Off.
        val terminal = terminalState ?: when (val now = CameraShareStatus.current()) {
            is CameraShareStatus.State.Unsupported, is CameraShareStatus.State.Unavailable -> now
            else -> null
        }
        CameraShareStatus.publish(terminal ?: CameraShareStatus.State.Off)
        super.onDestroy()
    }

    /**
     * The shape every start path that cannot come up shares: say why, and go.
     *
     * `started = false` before [stopSelf] for the reason ControlService clears it on a bind
     * failure — stopSelf() is asynchronous, and until onDestroy runs this instance is still the
     * one the system delivers start commands to. Left set, a user toggling off-then-on inside
     * that window is answered by the duplicate-start no-op and then destroyed: their retry
     * silently swallowed, with nothing left to trigger another. It also keeps [statusListener]
     * from re-posting a notification for a service that is on its way out.
     *
     * [terminalState] is what carries the reason past the teardown — see the field.
     */
    private fun fail(state: CameraShareStatus.State) {
        terminalState = state
        started = false
        CameraShareStatus.publish(state)
        stopSelf()
    }

    // -- notification ----------------------------------------------------------------------

    private fun postNotification(text: String) {
        runCatching {   // POST_NOTIFICATIONS may be denied on API 33+
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    /**
     * Held for the service's whole life, because Android requires a foreground service to hold
     * one. Shaped like the sibling services' notifications: same group, tap-to-open, a subtitle
     * that tracks the real state ([statusListener]) and a Stop action that flips the settings
     * switch off. The channel is requested at IMPORTANCE_MIN — the OS clamps FGS channels up to
     * LOW, which lands it at the same level as the siblings.
     */
    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, NOTIFICATION_TITLE, NotificationManager.IMPORTANCE_MIN)
        )
        val openIntent = PendingIntent.getActivity(
            this, 6,
            Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )
        // getForegroundService, not getService: a shade tap arrives with the app in the
        // background, where a plain startService is forbidden on API 26+. The ACTION_STOP branch
        // runs before the duplicate-start guard, so the re-delivered command is never swallowed.
        val stopIntent = PendingIntent.getForegroundService(
            this, 7,
            Intent(this, CameraShareService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(NOTIFICATION_TITLE)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mdi_cctv)
            .setGroup(ServiceNotifications.GROUP_KEY)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopIntent)
            .build()
    }
}
