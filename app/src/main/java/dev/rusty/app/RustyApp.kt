package dev.rusty.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings

/**
 * Process-wide bootstrap for the receiver state store and HA dashboard repository.
 *
 * The store is stood up HERE, in the [Application], rather than in [HomeActivity], because
 * [BootReceiver] start-on-boot launches [SpotifyService] WITHOUT ever creating the activity.
 * A native status/playback callback can therefore fire before any activity exists, so the single
 * source of truth must already be live when the process starts.
 *
 * The Android adapters (main-thread [Handler] poster, [SystemClock] monotonic clock) are supplied
 * here — the pure [ReceiverStateStore] stays Android-free for JVM unit tests.
 */
class RustyApp : Application() {

    /** Process-wide single source of truth. Reach it via [from]. */
    lateinit var receiverStore: ReceiverStateStore
        private set

    /** Process-wide HA dashboard repository. Reach it via [haRepository]. */
    lateinit var haRepository: HomeAssistantDashboardRepository
        private set

    /** Process-wide playback-takeover driver. See [PlaybackTakeoverCoordinator]. */
    lateinit var takeoverCoordinator: PlaybackTakeoverCoordinator
        private set

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME

        // Sweep a plaintext API key into encrypted storage at startup. SecretStore migrates
        // lazily on first use, but "first use" only happens if the user opens the Slideshow
        // settings tab or the theme mounts — until then the plaintext value would sit on disk
        // indefinitely, which is the exact exposure this is meant to remove. Guarded on the key
        // actually being present so normal startups never pay for building the KeyStore-backed
        // store; the guard is a single prefs lookup and the whole path runs once, ever.
        if (!prefs.getString(SlideshowSettings.KEY_API_KEY, null).isNullOrBlank()) {
            SecretStore.of(this)
        }

        val mainHandler = Handler(mainLooper)
        receiverStore = ReceiverStateStore(
            initial = ReceiverDashboardState.waiting(deviceName),
            poster = MainPoster { mainHandler.post(it) },
            clock = MonotonicClock { SystemClock.elapsedRealtime() },
        )

        val haStore = object : HaStore {
            override fun load(origin: String): PersistedHa? {
                val cachedOrigin = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null)
                if (!HomeAssistantDashboards.isCacheFresh(origin, cachedOrigin)) return null
                val cacheJson = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null)
                val selectedJson = prefs.getString(HomeAssistantFeature.KEY_SELECTED_DASHBOARDS, null)
                val dashboards = HomeAssistantDashboards.parseCachedDashboards(cacheJson)
                val selectedPaths = HomeAssistantDashboards.parseSelectedPaths(selectedJson)
                return PersistedHa(dashboards, selectedPaths)
            }

            override fun save(origin: String, dashboards: List<HomeAssistantDashboards.HaDashboard>, selectedPaths: List<String>) {
                val cacheJson = HomeAssistantDashboards.serializeDashboards(dashboards)
                val selectedJson = HomeAssistantDashboards.serializeSelectedPaths(selectedPaths)
                prefs.edit()
                    .putString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, cacheJson)
                    .putString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, origin)
                    .putString(HomeAssistantFeature.KEY_SELECTED_DASHBOARDS, selectedJson)
                    .apply()
            }
        }

        val haScheduler = Scheduler { delayMs, action ->
            val cancellable = Cancellable { mainHandler.removeCallbacks(action) }
            mainHandler.postDelayed(action, delayMs)
            cancellable
        }

        haRepository = HomeAssistantDashboardRepository(
            store = haStore,
            poster = MainPoster { mainHandler.post(it) },
            scheduler = haScheduler,
        )

        // Heals a remote-control start the system refused at boot: every app launch re-syncs the
        // service to the toggle, which is the retry the bind-failure status model promises. Last,
        // because the service's runtime reads [receiverStore] and must not see it uninitialised.
        ControlService.syncFromPrefs(this)

        // Last: the takeover coordinator reads receiverStore and ScreenControlModel state.
        takeoverCoordinator = PlaybackTakeoverCoordinator(
            store = receiverStore,
            clock = MonotonicClock { SystemClock.elapsedRealtime() },
            toggles = { PlaybackTakeoverSettings.toggles(prefs) },
            canDrawOverlays = { Settings.canDrawOverlays(this) },
            screenDesiredOn = { ScreenControlModel.desired().on },
            wakeScreen = { fakeOffActive ->
                if (fakeOffActive) {
                    // The fake-off is a remote-control command; clearing it must go through
                    // the model so the API snapshot (and any open control page) sees it. But the
                    // desired state outlives this Activity (see HomeActivity.onStart), so a
                    // fake-off that has been sitting behind a stopped/hidden window has already
                    // let the panel really sleep — flipping the model to "on" alone launches a
                    // renderer callback that a real display-off has no way to act on. The wake
                    // lock below is what actually relights the hardware in that case, so it is
                    // unconditional rather than an alternative to this branch.
                    ScreenControlModel.set(on = true, brightness = null)
                }
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION") // the only wake-without-activity mechanism
                pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "Rusty::PlaybackWake",
                ).acquire(PLAYBACK_WAKE_TIMEOUT_MS)
                // ON_AFTER_RELEASE pokes the user-activity timer on release, not just at
                // acquire: without it the display-off timeout is evaluated against whatever
                // user activity last happened — possibly long before the device went to sleep —
                // so the panel can drop straight back to black the instant this 5s lock expires.
            },
            launchHome = {
                // SAW holders are exempt from background-activity-launch blocks on most
                // builds; where an OEM blocks it anyway the failure is silent — accepted
                // (documented residual risk), so no fallback attempt here.
                val intent = Intent(this, HomeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                runCatching { startActivity(intent) }
            },
        )
        takeoverCoordinator.start()
    }

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val DEFAULT_DEVICE_NAME = "Rusty Speaker"
        private const val PLAYBACK_WAKE_TIMEOUT_MS = 5_000L

        /** The process-wide store, reachable from any [Context]. */
        fun from(context: Context): ReceiverStateStore =
            (context.applicationContext as RustyApp).receiverStore

        /** The process-wide HA dashboard repository, reachable from any [Context]. */
        fun haRepository(context: Context): HomeAssistantDashboardRepository =
            (context.applicationContext as RustyApp).haRepository
    }
}
