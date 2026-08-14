package dev.rusty.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app shell. SP0: hosts exactly one feature fragment (Spotify) in [R.id.featureContainer].
 *
 * Owns the app-global concerns that survive feature switches: the foreground receiver service
 * lifecycle + notification-permission flow, the receiver config (name/bitrate), the window /
 * immersive (fullscreen) state, and key dispatch. The active fragment is a pure renderer that
 * delegates control here through [ShellHost].
 */
class HomeActivity : AppCompatActivity(), ShellHost {

    private lateinit var prefs: SharedPreferences

    /** Process-wide receiver state store (single source of truth). Every shell write routes here. */
    private val store: ReceiverStateStore by lazy { RustyApp.from(this) }
    private val btnSettings by lazy { findViewById<android.widget.ImageButton>(R.id.btnSettings) }
    private val tvClock by lazy { findViewById<android.widget.TextView>(R.id.tvClock) }
    private lateinit var shellChrome: ShellChromeController
    private val launcherBackCallback = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = shellChrome.launcher.collapse()
    }
    private lateinit var screensaver: ScreensaverController
    private lateinit var takeover: PlaybackTakeoverCoordinator

    private val screensaverHost = object : ScreensaverHost {
        // From the screensaver chrome → open straight to the Screensaver tab (not the active
        // feature's tab), so the user lands on the settings for what they're looking at.
        override fun openSettings() = this@HomeActivity.openSettings(SettingsTabKey.SCREENSAVER)
        override fun openInfo() {
            currentFeatureContribution()?.showInfo()
        }

        // The Immich clock/✕ own their exit gesture; a clickable child never reaches the
        // controller's tap-anywhere wake path, so the theme asks for the exit explicitly.
        override fun requestExit() = screensaver.dismissToForeground()

        // Features-only launcher entries for the saver chrome (no Lock — you're already in the saver).
        // Reverse ring order so the first enabled feature sits nearest the toggle, matching the shell.
        // Selecting a feature commits it underneath, then crossfades the saver out into it.
        override fun launcherEntries(): List<LauncherEntry> {
            val current = currentFeatureId()
            return FeatureRegistry.enabledIds(prefs).reversed().map { id ->
                val feature = FeatureRegistry.byId(id)
                LauncherEntry(feature.iconRes, feature.title, active = id == current) {
                    if (id != current) switchTo(id)
                    screensaver.dismissToForeground()
                }
            }
        }
    }

    private var deviceName = DEFAULT_DEVICE_NAME
    private var bitrateKbps = DEFAULT_BITRATE_KBPS
    private var fullscreenEnabled = false
    private var keepScreenOnEnabled = false

    /** Orchestrates the receiver service lifecycle (start / stop / rename / bitrate). */
    private lateinit var receiverController: ReceiverController

    private lateinit var insetsController: WindowInsetsControllerCompat
    private lateinit var featureNavigator: FeatureNavigator

    private val handler = Handler(Looper.getMainLooper())

    private val autoHideBarsTick = Runnable {
        if (fullscreenEnabled) hideSystemBars()
    }

    // The shared clock is shell-owned and floats over every feature, so the shell ticks its digits
    // (the SpotifyFragment also refreshes them while it's foreground — an identical, harmless write).
    // Without this the corner clock would freeze over Home Assistant (no SpotifyFragment to drive it).
    private val clockTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateSharedClock()
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            receiverController.onPermissionResult(isGranted)
        }

    /** DLNA Start gate: on API 33+ an ungranted POST_NOTIFICATIONS hides the FGS notification
     *  from the shade. The service starts EITHER WAY — the permission only affects visibility. */
    private val dlnaNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            dev.rusty.app.renderer.MediaRendererController.setEnabled(this, true)
        }

    /** Entry point for the DLNA Player settings tab's Start button. */
    fun startDlnaPlayer() {
        val needsAsk = android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (needsAsk) {
            dlnaNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            dev.rusty.app.renderer.MediaRendererController.setEnabled(this, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        takeover = (application as RustyApp).takeoverCoordinator
        deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        bitrateKbps = prefs.getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS)
            .takeIf { it in SUPPORTED_BITRATES_KBPS }
            ?: DEFAULT_BITRATE_KBPS
        fullscreenEnabled = prefs.getBoolean(KEY_FULLSCREEN, false)
        keepScreenOnEnabled = KeepScreenOnSettings.isEnabled(prefs)

        receiverController = ReceiverController(
            context = this,
            store = store,
            permissionLauncher = requestPermissionLauncher,
            getDeviceName = { deviceName },
            getBitrateKbps = { bitrateKbps },
            onStateChanged = { notifyActiveFragmentStateChanged() },
        )

        setupFullscreen()
        applyKeepScreenOn()

        screensaver = ScreensaverController(
            overlay = findViewById(R.id.screensaverOverlay),
            prefs = prefs,
            host = screensaverHost,
            reassertImmersive = { reassertImmersiveIfEnabled() },
            exitTarget = {
                featureNavigator.currentFragment as? ScreensaverExitTarget
            },
            isReceiverForeground = { currentFeatureId() == FeatureId.SPOTIFY },
            store = store,
            screenSuppressed = { !ScreenControlModel.desired().on },
        )

        shellChrome = ShellChromeController(
            context = this,
            prefs = prefs,
            tvClock = tvClock,
            btnInfo = findViewById(R.id.btnInfo),
            btnSettings = findViewById(R.id.btnSettings),
            haChipBar = findViewById(R.id.haChipBar),
            haChipGroup = findViewById(R.id.haChipGroup),
            toggle = findViewById(R.id.btnLauncher),
            launcherMenu = findViewById(R.id.launcherMenu),
            launcherScrim = findViewById(R.id.launcherScrim),
            currentFeatureId = { currentFeatureId() },
            currentFragment = { featureNavigator.currentFragment },
            haSignedIn = { HomeAssistantDashboards.isSignedIn(RustyApp.haRepository(this).state) },
            showScreensaver = { showScreensaver() },
            switchTo = { id -> switchTo(id) },
        )
        shellChrome.launcher.onOpenChanged = { open -> launcherBackCallback.isEnabled = open }
        onBackPressedDispatcher.addCallback(this, launcherBackCallback)
        setupChrome()

        // Resolve the persisted start id (or SPOTIFY as the safe default).
        val storedName = prefs.getString(KEY_CURRENT_FEATURE, FeatureId.SPOTIFY.name) ?: FeatureId.SPOTIFY.name
        val startId = runCatching {
            val parsed = FeatureId.valueOf(storedName)
            if (FeatureRegistry.enabledIds(prefs).contains(parsed)) parsed else FeatureId.SPOTIFY
        }.getOrDefault(FeatureId.SPOTIFY)

        val navState = FeatureNavState(
            persisted = startId,
            enabled = FeatureRegistry.enabledIds(prefs),
        )
        featureNavigator = FeatureNavigator(
            fm = supportFragmentManager,
            containerId = R.id.featureContainer,
            state = navState,
            onSwitched = { id ->
                // Persist the new selection immediately.
                prefs.edit().putString(KEY_CURRENT_FEATURE, id.name).apply()
                screensaver.onForegroundFeatureChanged()
                // The shown fragment is re-shown (not recreated), so its onViewCreated initial-focus
                // never re-runs — restore D-pad focus onto a visible control of the new feature so it
                // is never stranded on a now-hidden one.
                (featureNavigator.currentFragment as? FocusRestorable)?.restoreFocus()
                // Update chrome (info button, chips, launcher, clock park).
                // animate=false here; switchTo() re-calls with animate=true for explicit user switches.
                shellChrome.onFeatureChanged(id, animate = false)
            },
        )

        if (savedInstanceState == null) {
            // commitNow (not commit) so the new fragment's tag is readable immediately.
            featureNavigator.commitInitial(startId)
        } else {
            // Config-change restore: the FragmentManager re-added every retained fragment with its
            // pre-rotation hidden/shown flags. Seed state from persistence (authoritative), then
            // reconcile so EXACTLY ONE fragment (the current one) is visible — no duplicates, no two
            // visible faces. Insets are replayed inside reconcileAfterRestore (and again by the
            // window listener on its next pass).
            navState.current = startId
            featureNavigator.reconcileAfterRestore()
        }
        // The start fragment now exists (fresh commitNow, or a config-change restore); reconcile the
        // per-feature chrome so Info/chips/launcher are correct even on a cold start into a non-Spotify
        // feature. animate=false — no bloom on cold start (clock snaps to corner if needed).
        shellChrome.onFeatureChanged(featureNavigator.current, animate = false)

        // Best-effort DLNA/UPnP media-renderer exposure — syncs the service to the desired-state
        // pref owned by the DLNA Player settings tab; no-ops if the player is stopped.
        dev.rusty.app.renderer.MediaRendererController.syncFromPrefs(this)

        // A slideshow filter change made from OFF-device (the remote-control API) must reload a
        // mounted saver exactly like the in-app picker does. Subscribed for the window's lifetime;
        // dropped in onDestroy so a destroyed Activity is never called back.
        SlideshowConfigRelay.addListener(slideshowConfigListener)

        // The Activity-bound half of the screen control: the model holds the desired state (and
        // survives this Activity), we are what actually blacks the panel and moves brightness.
        // Attaching also flips the API's `screen.available` to true and immediately replays the
        // current desired state, so an "off" issued while no Activity existed is applied on arrival.
        ScreenControlModel.attachRenderer(screenRenderer)
    }

    /** Held as a field so onDestroy can unsubscribe the SAME instance (a fresh lambda would not
     *  match). Fires on the main thread — the relay's caller posts there. */
    private val slideshowConfigListener: () -> Unit = { onSlideshowConfigChanged() }

    override fun onStart() {
        super.onStart()
        // `screen.available` is about whether a screen command can take effect NOW, which is a
        // different fact from whether a renderer is attached (that is onCreate/onDestroy, above).
        // FLAG_KEEP_SCREEN_ON only holds a VISIBLE window awake, so once this window is hidden a
        // faked-off panel really sleeps and no remote wake can relight it — the API has to say so.
        ScreenControlModel.setRendererVisible(true)
        shellChrome.launcher.refresh()
        registerReceiver(clockTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        updateSharedClock()
        RustyApp.haRepository(this).addListener(shellChrome.chipListener)
        receiverController.ensureStarted()
    }

    override fun onStop() {
        // See onStart: the desired state stays attached and will still be applied, but from here on
        // it cannot take effect until this window is visible again.
        ScreenControlModel.setRendererVisible(false)
        RustyApp.haRepository(this).removeListener(shellChrome.chipListener)
        unregisterReceiver(clockTickReceiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (fullscreenEnabled) {
            hideSystemBars()
            scheduleAutoHide()
        }
        screensaver.onResume()
        // After screensaver.onResume(): attaching can deliver a pending takeover synchronously,
        // and the saver dismissal inside it needs the controller resumed.
        takeover.attachPageConsumer { onPlaybackTakeover() }
        applyKeepScreenOn()
    }

    override fun onPause() {
        super.onPause()
        // Before anything else: past this point the navigator's commitNow is no longer safe,
        // so no takeover page switch may be delivered.
        takeover.detachPageConsumer()
        handler.removeCallbacks(autoHideBarsTick)
        screensaver.onPause()
    }

    override fun onDestroy() {
        SlideshowConfigRelay.removeListener(slideshowConfigListener)
        // Detach FIRST: an HTTP thread mid-set must not queue a delivery onto a dying window, and
        // `screen.available` must report false the moment the last Activity goes away. Detaching
        // does not clear the desired state — a new Activity re-applies it on attach.
        ScreenControlModel.detachRenderer(screenRenderer)
        handler.removeCallbacks(applyScreenTick)
        detachScreenOffOverlay()
        screensaver.dispose()
        super.onDestroy()
    }

    /**
     * The Activity now absorbs configuration changes itself (`android:configChanges`) instead of being
     * recreated on rotation — that's what keeps Home Assistant's WebView from reloading when the device
     * rotates. The trade-off is that nothing is auto-re-inflated, so we manually refresh the few things
     * a recreate used to give us for free:
     *  1. the shared floating clock's orientation-qualified text size (`values-port/dimens` shrinks it);
     *  2. the orientation-sensitive Spotify and DLNA Player fragments' views (each has `layout-port`/
     *     `values-port`), rebuilt in place — HA and the screensaver are not orientation-qualified, so
     *     they are left untouched and keep their live state;
     *  3. the clock park / chrome layout for the new window dimensions;
     *  4. anything registered through [addConfigurationChangeListener] — open popup cards, whose
     *     window width is a pixel count that would otherwise stay sized for the old orientation.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tvClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.shell_clock_text_size))
        featureNavigator.recreateFeatureView(FeatureId.SPOTIFY)
        featureNavigator.recreateFeatureView(FeatureId.DLNA)
        shellChrome.onFeatureChanged(featureNavigator.current, animate = false)
        configurationListeners.forEach { it() }
    }

    /**
     * Shell-scoped configuration-change listeners, for views the activity does not own but which
     * must still react to rotation (see [followDisplaySize]). Copy-on-write so a listener may
     * unregister itself from inside the dispatch.
     */
    private val configurationListeners = CopyOnWriteArrayList<() -> Unit>()

    fun addConfigurationChangeListener(listener: () -> Unit) {
        configurationListeners.add(listener)
    }

    fun removeConfigurationChangeListener(listener: () -> Unit) {
        configurationListeners.remove(listener)
    }

    /**
     * Hardware-key contract (spec 2026-07-22): system keys (assistant, volume) are NEVER
     * consumed — the system owns them; media transport keys mean MUSIC from anywhere — through
     * any saver theme without waking it, and from any foreground feature via the fallback below;
     * the D-pad means PHOTOS while a slideshow owns the remote (BACK/UP exit, the rest are dead
     * keys); every non-system key still wakes a non-owning saver so a D-pad user is never trapped
     * (the v2.0.0 Shield rule). The whole decision table lives in [ShellKeyRouting]; this method
     * only executes it.
     *
     * A remote-control fake-off ([screenOffOverlay]) adds one branch that obeys the same contract:
     * transport keys still mean MUSIC (the panel being dark says nothing about the audio, so a
     * PLAY/PAUSE press must pause on the FIRST press, exactly as it does through a saver), and
     * every other non-system key wakes the panel and is consumed so it cannot reach the feature
     * underneath a screen the user cannot see. That table lives in
     * [ShellKeyRouting.routeWhileScreenFakedOff] like every other one here.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ShellKeyRouting.isSystemKey(event.keyCode)) {
            if (!screensaver.isShowing) screensaver.resetIdleTimer()
            return super.dispatchKeyEvent(event)
        }
        // Screen faked off. A null field when the screen is on, so the routing below is untouched.
        // The decision table itself lives in ShellKeyRouting with the rest of the shell's routing
        // (and its regression tests); this only executes it.
        if (screenOffOverlay != null) {
            val action = ShellKeyRouting.routeWhileScreenFakedOff(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                spotifyActive = store.snapshot.state.visualState() == VisualState.ACTIVE,
            )
            return when (action) {
                ScreenOffKeyAction.SPOTIFY_TRANSPORT -> spotifyTransportKey(event)
                ScreenOffKeyAction.WAKE_AND_CONSUME -> {
                    wakeScreenFromLocalInput()
                    true
                }
                ScreenOffKeyAction.CONSUME -> true
            }
        }
        if (screensaver.isShowing) {
            val action = ShellKeyRouting.routeWhileSaverShowing(
                keyCode = event.keyCode,
                slideshowOwnsRemote = screensaver.themeOwnsRemote(),
                spotifyActive = store.snapshot.state.visualState() == VisualState.ACTIVE,
            )
            return when (action) {
                SaverKeyAction.SPOTIFY_TRANSPORT -> spotifyTransportKey(event)
                SaverKeyAction.SLIDESHOW_NAV -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        screensaver.onNavKey(event.keyCode)
                    }
                    true
                }
                SaverKeyAction.WAKE -> {
                    if (event.action == KeyEvent.ACTION_DOWN) screensaver.onWakeKey()
                    true
                }
                SaverKeyAction.CONSUME -> true
            }
        }
        screensaver.resetIdleTimer()
        val active = featureNavigator.currentFragment
        if (active is KeyEventTarget && active.onKeyEvent(event)) return true
        // Media keys mean music from ANY foreground feature (HA, DLNA, …): the fragment got first
        // refusal above, so this only fires for features that don't handle transport themselves.
        if (store.snapshot.state.visualState() == VisualState.ACTIVE && spotifyTransportKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Routes a transport key to the receiver. Returns false for non-transport keys; consumes both
     * the down and the up of a transport key, acting once on the initial press ([TvRemote]).
     */
    private fun spotifyTransportKey(event: KeyEvent): Boolean =
        TvRemote.dispatchTransportKey(
            event,
            onPlayPause = {
                if (ShellKeyRouting.togglesToPause(store.snapshot.state.status)) {
                    NativeBridge.pause()
                } else {
                    NativeBridge.play()
                }
            },
            onNext = { NativeBridge.nextTrack() },
            onPrevious = { NativeBridge.previousTrack() },
        )

    /** Any touch re-arms the idle timer (the overlay itself handles dismiss while showing). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (screensaver.isShowing) return
        screensaver.resetIdleTimer()
        if (fullscreenEnabled) scheduleAutoHide()
    }

    // ---- ShellHost ----------------------------------------------------------

    override val currentDeviceName: String get() = deviceName
    override val currentBitrateKbps: Int get() = bitrateKbps

    override fun openSettings(tab: SettingsTabKey?) {
        val active = currentFeatureId()
        SettingsSheet.show(this, this, tab ?: defaultSettingsTab(active)) { store.snapshot.state }
    }

    override fun startReceiver() = receiverController.ensureStarted()

    override fun stopReceiver() {
        // Delegates to ReceiverController: stopService routes through SpotifyService.onDestroy
        // (clean teardown, publishes OFF). The controller renders OFF immediately so the
        // sheet/header update without waiting for the broadcast round-trip.
        receiverController.stopReceiver()
    }

    override fun showScreensaver() = screensaver.show()

    override fun sharedClock(): android.widget.TextView = tvClock

    override fun applyHaChromeColor(textColor: Int) = shellChrome.applyHaTextColor(textColor)

    /**
     * Renames the receiver.
     *
     * While a phone is connected, librespot 0.8 cannot rename the live Connect session in
     * place — the session's name is fixed when it's created and the device reports
     * `supports_rename = false`, so re-advertising mDNS alone never updates the name the
     * controlling phone sees. We therefore reconnect the native session under the new name
     * via the same start-intent path bitrate changes use: a brief interruption, after which
     * the new name shows everywhere, including on the controlling phone.
     *
     * While idle (no session), we re-advertise mDNS in place — instant and non-disruptive.
     */
    override fun applyReceiverName(newName: String) {
        val sessionActive = store.snapshot.state.sessionUser != null
        deviceName = newName
        prefs.edit().putString(KEY_DEVICE_NAME, newName).apply()
        // Delegates store dispatch, native call, and service-intent to the controller.
        // Do NOT call onReceiverRenamed() here when a session is active: it pre-stamps the
        // service's nativeStartedConfig with the new name, which would trip the duplicate-start
        // guard and suppress the very restart we need.
        receiverController.applyReceiverName(newName, sessionActive)
    }

    /**
     * Bitrate is bound when the native player is created, so changing it requires recreating
     * the native session. Re-delivering the start intent lets the native layer replace the
     * receiver, fully tearing the old one (and its mDNS responder) down before starting the
     * new one — two live libmdns responders panic on teardown (SIGABRT). The Activity and
     * foreground service stay alive; only the native session cycles.
     */
    override fun applyBitrate(newKbps: Int) {
        bitrateKbps = newKbps
        prefs.edit().putInt(KEY_BITRATE_KBPS, newKbps).apply()
        receiverController.applyBitrate(deviceName)
    }

    /** Re-renders the active fragment from the shared snapshot after the shell changed state. */
    private fun notifyActiveFragmentStateChanged() {
        (featureNavigator.currentFragment as? ReceiverStateAware)?.onShellStateChanged()
    }

    // ---- Settings: clock format (Screensaver tab) --------------------------

    /** The current 24h clock preference, for the Screensaver tab's switch initial state. */
    val currentIs24HourClock: Boolean
        get() = prefs.getBoolean(KEY_TIME_FORMAT_24H, android.text.format.DateFormat.is24HourFormat(this))

    /**
     * Persists the clock-format override and re-renders the active Spotify fragment's clock
     * immediately (no restart). Invoked by the Screensaver settings panel; the actual clock
     * re-render is delegated to the fragment, which owns the clock view.
     */
    fun applyTimeFormat(is24Hour: Boolean) {
        prefs.edit().putBoolean(KEY_TIME_FORMAT_24H, is24Hour).apply()
        updateSharedClock() // reflect the new format on the shell clock at once (live over any feature)
        currentFeatureContribution()?.applyTimeFormat(is24Hour)
    }

    // ---- Settings: screensaver (Screensaver tab) ---------------------------

    /** The persisted screensaver theme, for the selector's initial state. */
    val currentScreensaverThemeId: ScreensaverThemeId
        get() = ScreensaverThemeId.fromPrefValue(prefs.getString(ScreensaverController.KEY_THEME, null))

    /** Persists the screensaver theme and live-swaps it if the saver is already showing. */
    fun applyScreensaverTheme(id: ScreensaverThemeId) {
        prefs.edit().putString(ScreensaverController.KEY_THEME, id.prefValue).apply()
        screensaver.onThemeChanged()
    }

    /** The persisted idle timeout, for the picker's initial state. */
    val currentScreensaverTimeout: ScreensaverTimeout
        get() = ScreensaverTimeout.fromPrefSeconds(
            prefs.getInt(ScreensaverController.KEY_TIMEOUT_SECONDS, ScreensaverTimeout.DEFAULT.prefSeconds)
        )

    /** Persists the idle timeout and re-arms the timer with the new value. */
    fun applyScreensaverTimeout(timeout: ScreensaverTimeout) {
        prefs.edit().putInt(ScreensaverController.KEY_TIMEOUT_SECONDS, timeout.prefSeconds).apply()
        screensaver.resetIdleTimer()
    }

    /**
     * Delegates to [ShellChromeController.refreshDashboardChips]. Called by [SettingsSheet] after
     * the user saves HA dashboard selection so the chip bar updates immediately without waiting for
     * the next repo-listener tick.
     */
    fun refreshDashboardChips() = shellChrome.refreshDashboardChips()

    /**
     * Wires the shell chrome cluster's clicks once (called from onCreate). Settings opens the active
     * feature's tab; Info routes to the Spotify info sheet (btnInfo wired in ShellChromeController
     * constructor; its click is still routed here via screensaverHost). Per-feature visibility is
     * reconciled by [ShellChromeController.onFeatureChanged].
     */
    private fun setupChrome() {
        btnSettings.setOnClickListener { openSettings(null) }      // null → active feature's tab
        findViewById<android.widget.ImageButton>(R.id.btnInfo)
            .setOnClickListener { screensaverHost.openInfo() }     // routes to SpotifyFragment.showInfo()
        tvClock.setOnClickListener { showScreensaver() }
        // The clock shrinks to ~0.22 scale in the corner, so a foreground focus ring all but vanishes
        // there — recolor the digits to the brand green on focus instead (reads at any scale).
        val clockInk = androidx.core.content.ContextCompat.getColor(this, R.color.ink)
        val clockFocused = androidx.core.content.ContextCompat.getColor(this, R.color.accent_fallback)
        tvClock.setOnFocusChangeListener { _, hasFocus ->
            tvClock.setTextColor(if (hasFocus) clockFocused else clockInk)
        }
    }

    /** Refreshes the shared clock's digits from the current time + 24h preference. Driven by the
     *  shell so the corner clock stays live over every feature, not just Spotify. */
    private fun updateSharedClock() {
        val now = System.currentTimeMillis()
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        tvClock.text = ClockFormat.time(now, currentIs24HourClock, locale, TimeZone.getDefault())
    }

    /** The active feature id; authoritative from [FeatureNavigator.current] (not derived from tag). */
    private fun currentFeatureId(): FeatureId = featureNavigator.current

    /**
     * The [ShellContribution] of the currently-visible feature's fragment, or null if no fragment
     * is live. Replaces `currentFragment as? SpotifyFragment` / `as? HomeAssistantFragment` casts
     * in the shell layer — the active feature's fragment implements [ShellContribution] directly.
     */
    private fun currentFeatureContribution(): ShellContribution? =
        FeatureRegistry.byId(currentFeatureId()).shellContribution(featureNavigator.currentFragment)

    /**
     * The [ShellContribution] of the live Home Assistant fragment when HA is the visible feature,
     * else null. Callers (e.g. [HomeAssistantSettingsPanel]) use [ShellContribution.runDiscovery]
     * and [ShellContribution.reloadUrl] through the interface — no concrete-type cast needed.
     *
     * With fragment retention, multiple fragments may share the container; the navigator's
     * authoritative current tag is used rather than `findFragmentById`.
     */
    fun currentHomeAssistantFragment(): ShellContribution? =
        if (currentFeatureId() == FeatureId.HOME_ASSISTANT) currentFeatureContribution() else null

    /** Replaces the hosted feature fragment and persists the selection. Delegates to [FeatureNavigator]. */
    private fun switchTo(id: FeatureId) {
        if (shellChrome.launcher.isOpen) shellChrome.launcher.collapse()
        // The navigator commits the transaction, replays insets, updates state, and fires onSwitched.
        // onSwitched calls shellChrome.onFeatureChanged(animate=false) as a neutral default.
        // Override to animate=true here for explicit user switches.
        featureNavigator.switchTo(id)
        if (featureNavigator.current != FeatureId.SPOTIFY) shellChrome.parkClockInCorner(animate = true)
    }

    /**
     * A playback-start takeover: land on the Spotify page and clear any saver above it.
     * The explicit dismiss is required — a saver shown over a non-Spotify feature is
     * AMBIENT and never auto-blooms on the track-start edge (same pattern as the saver
     * launcher's switch-then-dismiss at [screensaverHost.launcherEntries]).
     */
    private fun onPlaybackTakeover() {
        switchTo(FeatureId.SPOTIFY)
        screensaver.dismissToForeground()
    }

    /** Takeover launches arrive as SINGLE_TOP|CLEAR_TOP re-deliveries; keep the intent fresh. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    // ---- Test hooks (instrumentation only) ----------------------------------

    /** Switches to [id] (drives the retained add/hide/show path). Test-only handle on the private switch. */
    @androidx.annotation.VisibleForTesting
    fun switchToForTest(id: FeatureId) = switchTo(id)

    /** The currently-VISIBLE feature fragment (authoritative via the navigator's current tag). */
    @androidx.annotation.VisibleForTesting
    fun currentFragmentForTest(): androidx.fragment.app.Fragment? = featureNavigator.currentFragment

    /** The retained (possibly hidden, possibly absent) fragment for [id]. */
    @androidx.annotation.VisibleForTesting
    fun retainedFragmentForTest(id: FeatureId): androidx.fragment.app.Fragment? =
        featureNavigator.fragmentFor(id)

    /** Exposes the [ReceiverController] instance for instrumented tests. */
    @androidx.annotation.VisibleForTesting
    fun receiverControllerForTest(): ReceiverController = receiverController

    // ---- Feature enable + behavior (General tab) ----------------------------

    /** Whether HA is enabled, for the General toggle's initial state. */
    val isHomeAssistantEnabled: Boolean
        get() = prefs.getBoolean(HomeAssistantFeature.KEY_ENABLED, false)

    /**
     * Persists the HA enable flag and reconciles the shell: disabling HA while it's foreground snaps
     * back to Spotify (which also recomputes the screensaver); [FeatureLauncher.refresh] keeps the
     * launcher toggle visible and rebuilds the open menu so active-marking stays current.
     *
     * The toggle is reached from Settings, which is a dialog over the (possibly showing) screensaver
     * — so the shell's launcher refresh isn't enough: [ScreensaverController.onEnabledFeaturesChanged]
     * re-evaluates the showing saver's own launcher so the newly enabled feature is reachable there
     * too, without waiting for the idle saver to re-mount.
     */
    fun setHomeAssistantEnabled(enabled: Boolean) {
        // Capture BEFORE mutating the ring: onEnabledChanged() below may move `current` off HA when
        // it's the active feature, which would make a post-mutation `currentFeatureId() ==
        // HOME_ASSISTANT` check always false and skip the switch-away (see FeatureDisable).
        val activeBefore = currentFeatureId()
        prefs.edit().putBoolean(HomeAssistantFeature.KEY_ENABLED, enabled).apply()
        // Keep the nav-state ring in sync so next() and onEnabledChanged queries are correct.
        val stillEnabled = FeatureRegistry.enabledIds(prefs)
        featureNavigator.state.onEnabledChanged(stillEnabled)
        if (!enabled) {
            // Disabling HA: if it's foreground, switch away first (so it's no longer current), then
            // REMOVE its retained fragment so the WebView/native resources are destroyed rather than
            // kept hidden. removeRetained no-ops when HA was never shown (no fragment to destroy).
            FeatureDisable.switchTargetOnDisable(FeatureId.HOME_ASSISTANT, activeBefore, stillEnabled)
                ?.let { switchTo(it) }
            featureNavigator.removeRetained(FeatureId.HOME_ASSISTANT)
        }
        shellChrome.onFeatureChanged(currentFeatureId(), animate = false)
        screensaver.onEnabledFeaturesChanged()
    }

    /** Whether the DLNA Player screen feature is enabled, for the General toggle's initial state. */
    val isDlnaFeatureEnabled: Boolean
        get() = prefs.getBoolean(DlnaPlayerFeature.KEY_ENABLED, false)

    /**
     * Persists the DLNA screen-feature flag and reconciles the shell. Independent of the renderer
     * service run-state ([dev.rusty.app.renderer.MediaRendererController.setEnabled]) — this never
     * starts/stops the service, it only controls whether the now-playing screen + launcher entry
     * appear. Captures the active feature BEFORE the nav ring is mutated so disabling the active
     * DLNA screen actually switches away and shows the fallback (see [FeatureDisable]).
     */
    fun setDlnaFeatureEnabled(enabled: Boolean) {
        val activeBefore = currentFeatureId()
        prefs.edit().putBoolean(DlnaPlayerFeature.KEY_ENABLED, enabled).apply()
        val stillEnabled = FeatureRegistry.enabledIds(prefs)
        featureNavigator.state.onEnabledChanged(stillEnabled)
        if (!enabled) {
            FeatureDisable.switchTargetOnDisable(FeatureId.DLNA, activeBefore, stillEnabled)
                ?.let { switchTo(it) }
            featureNavigator.removeRetained(FeatureId.DLNA)
        }
        shellChrome.onFeatureChanged(currentFeatureId(), animate = false)
        screensaver.onEnabledFeaturesChanged()
    }

    /** Whether the Slideshow screensaver feature is enabled (General → Features switch). */
    val isSlideshowEnabled: Boolean
        get() = SlideshowSettings.isEnabled(prefs)

    /**
     * Persists the Slideshow flag. Toggling the flag either way heals a stored Slideshow theme to
     * Clock via the normal applyScreensaverTheme path (which also live-swaps a mounted saver): while
     * off it must not resolve to a disabled theme, and on re-enable the two-step flow requires the
     * user to re-pick Slideshow rather than have it auto-restored. So the picker and the stored pref
     * can never disagree, from any entry point.
     */
    fun setSlideshowEnabled(enabled: Boolean) {
        SlideshowSettings.setEnabled(prefs, enabled)
        // A stored Slideshow theme must never survive a toggle of its own feature. themeAfterDisable
        // heals SLIDESHOW→Clock and is a no-op for any other theme. This heal used to live in the
        // Screensaver-tab binder (which ran before its co-located switch could flip); with the switch
        // moved to General → Features, enabling no longer passes through that binder, so heal here.
        val healed = SlideshowDisable.themeAfterDisable(currentScreensaverThemeId)
        if (healed != currentScreensaverThemeId) applyScreensaverTheme(healed)
        if (!enabled) {
            // Turning the feature off should not leave the user's photos on disk. invalidate()
            // performs blocking disk IO, so it never runs on the main thread; NonCancellable (the
            // same guard the connection path uses) keeps an activity teardown racing the toggle from
            // leaving the photos half-deleted.
            val app = applicationContext
            lifecycleScope.launch {
                withContext(Dispatchers.IO + NonCancellable) { ImmichImages.invalidate(app) }
            }
        }
    }

    /**
     * A saved Immich connection/filter change must reach a MOUNTED idle saver immediately —
     * "retry on next show" never comes when the device sits idle with the saver up. Remounting
     * via onThemeChanged() cancels the old slideshow generation and reloads the new config.
     */
    fun onSlideshowConfigChanged() {
        if (currentScreensaverThemeId == ScreensaverThemeId.SLIDESHOW) screensaver.onThemeChanged()
    }

    /**
     * A saved connection change: drop every cached Immich image (old credentials must
     * never be served), THEN live-reload a mounted saver. Runs in the activity scope so a
     * settings tab-switch/dismissal mid-flight can't clear the cache but lose the reload.
     */
    fun onImmichConnectionChanged() {
        val app = applicationContext
        ImmichConnectionSwap.launch(
            scope = lifecycleScope,
            io = Dispatchers.IO,
            invalidate = { ImmichImages.invalidate(app) },
            reload = { onSlideshowConfigChanged() },
        )
    }

    /** Whether start-on-boot is enabled, for the General toggle's initial state. */
    val isStartOnBootEnabled: Boolean
        get() = prefs.getBoolean(BootReceiver.KEY_START_ON_BOOT, false)

    /** Persists the start-on-boot flag (read by [BootReceiver] at next boot). */
    fun setStartOnBootEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(BootReceiver.KEY_START_ON_BOOT, enabled).apply()
    }

    /** Whether the Canvas now-playing video overlay is enabled. Default OFF. */
    val isCanvasEnabled: Boolean
        get() = CanvasSettings.isEnabled(prefs)

    /** Persists the Canvas enabled flag (read by [SpotifyFragment] during playback). */
    fun setCanvasEnabled(enabled: Boolean) {
        CanvasSettings.setEnabled(prefs, enabled)
    }

    /** Whether a playback start switches the visible page to Spotify. Default OFF. */
    val isTakeoverPageEnabled: Boolean
        get() = PlaybackTakeoverSettings.isSwitchPageEnabled(prefs)

    fun setTakeoverPageEnabled(enabled: Boolean) {
        PlaybackTakeoverSettings.setSwitchPage(prefs, enabled)
    }

    /** Whether a playback start brings the app over other apps. Default OFF. */
    val isTakeoverFrontEnabled: Boolean
        get() = PlaybackTakeoverSettings.isBringToFrontEnabled(prefs)

    fun setTakeoverFrontEnabled(enabled: Boolean) {
        PlaybackTakeoverSettings.setBringToFront(prefs, enabled)
    }

    /** Whether a playback start wakes the screen. Default OFF. */
    val isTakeoverWakeEnabled: Boolean
        get() = PlaybackTakeoverSettings.isWakeScreenEnabled(prefs)

    fun setTakeoverWakeEnabled(enabled: Boolean) {
        PlaybackTakeoverSettings.setWakeScreen(prefs, enabled)
    }

    // ---- Fullscreen / immersive --------------------------------------------

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The background layers (mesh/wash/scrim/grain) fill the window edge-to-edge; only the
        // foreground contentLayer is padded by the system-bar insets + a base margin, so there's
        // no inset border. When fullscreen hides the bars the insets collapse to the base margin.
        // The shell forwards the window insets to the active fragment, which pads its own content.
        val homeRoot = findViewById<View>(R.id.homeRoot)
        ViewCompat.setOnApplyWindowInsetsListener(homeRoot) { _, insets ->
            featureNavigator.latestInsets = insets
            (featureNavigator.currentFragment as? InsetAware)?.onInsets(insets)
            // The shell chrome floats above the feature, so pad it by the system-bar insets too —
            // keeping the clock + cluster inside the safe area and collapsing to the edge under
            // immersive fullscreen, mirroring how the Spotify contentLayer is padded. The +base
            // matches contentLayer's 22dp base inset so the clock's corner-park geometry (measured
            // from this padded box) reproduces its pre-SP2.1 position rather than sitting ~22dp closer
            // to the edge.
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (CHROME_BASE_PAD_DP * resources.displayMetrics.density).toInt()
            findViewById<View>(R.id.shellChrome)
                ?.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
            insets
        }
        insetsController = WindowCompat.getInsetsController(window, homeRoot).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (fullscreenEnabled) hideSystemBars()
    }

    /** Toggles immersive fullscreen and persists it. Exposed for Task 4's General settings panel. */
    fun setFullscreen(enabled: Boolean) {
        fullscreenEnabled = enabled
        prefs.edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
        if (enabled) {
            hideSystemBars()
            scheduleAutoHide()
        } else {
            handler.removeCallbacks(autoHideBarsTick)
            showSystemBars()
        }
    }

    /** Whether immersive fullscreen is currently enabled (for the settings switch's initial state). */
    val isFullscreenEnabled: Boolean get() = fullscreenEnabled

    /** Re-asserts immersive after a transient interruption (e.g. a dialog dismiss). */
    fun reassertImmersiveIfEnabled() {
        if (fullscreenEnabled) hideSystemBars()
    }

    // ---- Keep screen on ----------------------------------------------------

    /**
     * Applies the keep-screen-on window flag to match the current setting — except while the screen
     * is faked off, where the flag is force-held regardless of the user's preference. That override
     * is the whole mechanism: without it the system's display timeout puts the panel genuinely to
     * sleep behind the black overlay, and a remote "screen on" can no longer relight it (there is no
     * WAKE_LOCK / turnScreenOn path from a backgrounded HTTP request). The check lives HERE rather
     * than only in [applyScreenDesired] so every other caller — [onResume], [setKeepScreenOn] — is
     * automatically prevented from clearing the flag out from under an active fake-off.
     */
    private fun applyKeepScreenOn() {
        if (keepScreenOnEnabled || screenOffOverlay != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Toggles keep-screen-on and persists it. Exposed for the General settings panel. */
    fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnEnabled = enabled
        KeepScreenOnSettings.setEnabled(prefs, enabled)
        applyKeepScreenOn()
    }

    /** Whether keep-screen-on is currently enabled (for the settings switch's initial state). */
    val isKeepScreenOnEnabled: Boolean get() = keepScreenOnEnabled

    // ---- Screen control renderer (remote-control fake-off + brightness) -----

    /** The black fake-off layer while the screen is desired off; null whenever it is on. Also the
     *  single flag [applyKeepScreenOn] reads to decide whether to force-hold the wake flag. */
    private var screenOffOverlay: View? = null

    /**
     * The renderer registered with [ScreenControlModel]. It MUST NOT do any work here:
     * [ControlService]'s `setScreen` holds its command lock across `ScreenControlModel.set(...)`,
     * and the model drains its delivery queue on the CALLING thread — an HTTP pool thread. Doing
     * the View / [Settings.System] work inline would therefore block every other control command
     * behind the UI thread. So this hands off to the main looper and returns immediately.
     *
     * The delivered value is deliberately ignored in favour of re-reading [ScreenControlModel] when
     * the post runs: the model is atomic, so the fresh read is never older than what was delivered,
     * and a burst of commands can only ever converge on the newest state rather than replay stale
     * ones. That also lets one shared [Runnable] instance serve every delivery, so [onDestroy] can
     * cancel any still-queued apply with a single `removeCallbacks`.
     */
    private val screenRenderer: (ScreenDesired) -> Unit = { handler.post(applyScreenTick) }

    private val applyScreenTick = Runnable { applyScreenDesired(ScreenControlModel.desired()) }

    /**
     * Applies one desired screen state. Main thread only (posted by [screenRenderer]); idempotent,
     * so a coalesced burst of deliveries costs nothing. The pure decisions — which brightness mode,
     * which values — live in [ScreenRenderPlan]; this only executes them.
     */
    private fun applyScreenDesired(desired: ScreenDesired) {
        if (isDestroyed || isFinishing) return
        // Re-checked per command, never cached: WRITE_SETTINGS can be granted or revoked while the
        // app runs. Holding it is still only a promise — the write below reports what it got.
        val canWriteSystem = runCatching { Settings.System.canWrite(this) }.getOrDefault(false)
        // A DEFAULT brightness is not a command: without this the replay that every attachRenderer
        // performs would push 100 % onto the device on every Activity create (see ScreenRenderPlan).
        val commanded = ScreenControlModel.brightnessEverCommanded()
        val plan = ScreenRenderPlan.of(desired, canWriteSystem, commanded)
        if (plan.overlayVisible) attachScreenOffOverlay() else detachScreenOffOverlay()
        var windowBrightness = plan.windowBrightness
        plan.systemLevel?.let { level ->
            // MODE_MANUAL first, and only then the value: under auto-brightness the system
            // recomputes from the light sensor and the value we write is simply ignored.
            val wrote = try {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                ) && Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
            } catch (e: SecurityException) {
                Log.w(TAG, "system brightness write refused; degrading to window brightness", e)
                false
            }
            // Report the outcome so the API stops claiming `mode: "system"` for a write that never
            // landed — the snapshot reads this back through ScreenControlModel.systemBrightnessUsable.
            ScreenControlModel.noteSystemBrightnessWrite(wrote)
            // Revoked between canWrite() and the write, or refused by the OEM: degrade to the
            // window-local override, which needs no permission. Re-planning as if we never had the
            // permission is exactly that fallback, expressed once.
            if (!wrote) {
                windowBrightness =
                    ScreenRenderPlan.of(desired, canWriteSystem = false, brightnessCommanded = commanded)
                        .windowBrightness
            }
        }
        val attributes = window.attributes
        attributes.screenBrightness = windowBrightness
        window.attributes = attributes
        // Force-holds the flag while the overlay is up, restores the user's own preference when it
        // comes down (see applyKeepScreenOn) — never leaves a forced-on flag behind.
        applyKeepScreenOn()
        // A slideshow must not keep fetching and decoding photos nobody can see. A separate
        // suppression from the user's manual pause, so waking never resumes what the user paused.
        screensaver.setSlideshowSuppressed(!desired.on)
    }

    /** Raises the black fake-off layer over everything, including the screensaver. Idempotent —
     *  the field is the guard, so repeated deliveries can never stack two overlays. */
    private fun attachScreenOffOverlay() {
        if (screenOffOverlay != null) return
        val overlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            // Above every decorView child (the content view, the screensaver overlay inside it and
            // any dialog-less popup), independent of add order.
            elevation = SCREEN_OFF_OVERLAY_ELEVATION
            // Belt and braces with the window flag: a View asking to keep the screen on survives
            // window-flag churn from any other caller.
            keepScreenOn = true
            isClickable = true
            // A focus sink: while this is up, focus that would otherwise land on a control nobody
            // can see stops here instead. NOT a key path — [dispatchKeyEvent] consumes every key
            // before the view hierarchy is ever offered one, so an OnKeyListener here could not
            // fire even if it existed, and the guaranteed D-pad wake is the one in
            // [ShellKeyRouting.routeWhileScreenFakedOff] (a TV remote's focus can be anywhere).
            isFocusable = true
            // Local touch ALWAYS wakes: the first touch is consumed here (never reaching the
            // slideshow/feature underneath) and turns the panel back on.
            setOnTouchListener { _, _ -> wakeScreenFromLocalInput(); true }
        }
        (window.decorView as ViewGroup).addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        screenOffOverlay = overlay
    }

    /** Removes the fake-off layer if it is up. Idempotent and safe on a torn-down window. */
    private fun detachScreenOffOverlay() {
        val overlay = screenOffOverlay ?: return
        screenOffOverlay = null
        (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    /**
     * A local touch/key while the screen is faked off. Routed through [ScreenControlModel] rather
     * than straight to the views so the API's snapshot (and any open control page) learns that the
     * screen came back on — the model's delivery brings us right back to [applyScreenDesired].
     * `brightness = null` restores the brightness that was in effect before the fake-off.
     */
    private fun wakeScreenFromLocalInput() {
        ScreenControlModel.set(on = true, brightness = null)
    }

    private fun hideSystemBars() {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideBarsTick)
        handler.postDelayed(autoHideBarsTick, AUTO_HIDE_MS)
    }

    private companion object {
        private const val TAG = "HomeActivity"
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_BITRATE_KBPS = "bitrate_kbps"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_TIME_FORMAT_24H = "time_format_24h"
        private const val KEY_CURRENT_FEATURE = "current_feature"
        private const val DEFAULT_DEVICE_NAME = "Rusty Speaker"
        private const val DEFAULT_BITRATE_KBPS = 160
        // Mirrors SpotifyFragment.BASE_PAD_DP so the floating shell chrome sits in the same safe box
        // the Spotify content used (keeps the clock's corner-park position consistent across the move).
        private const val CHROME_BASE_PAD_DP = 22
        private val SUPPORTED_BITRATES_KBPS = setOf(96, 160, 320)

        private const val AUTO_HIDE_MS = 4_000L

        /** Well above any elevation the shell/features/screensaver use, so the fake-off layer is
         *  unconditionally the topmost child of the decorView. */
        private const val SCREEN_OFF_OVERLAY_ELEVATION = 1_000f
    }
}
