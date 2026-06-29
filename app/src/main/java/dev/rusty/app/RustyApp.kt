package dev.rusty.app

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.SystemClock

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

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME

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
    }

    companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val DEFAULT_DEVICE_NAME = "Android Speaker"

        /** The process-wide store, reachable from any [Context]. */
        fun from(context: Context): ReceiverStateStore =
            (context.applicationContext as RustyApp).receiverStore

        /** The process-wide HA dashboard repository, reachable from any [Context]. */
        fun haRepository(context: Context): HomeAssistantDashboardRepository =
            (context.applicationContext as RustyApp).haRepository
    }
}
