package dev.rusty.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.commitNow
import java.util.Locale
import java.util.TimeZone

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

    private val screensaverHost = object : ScreensaverHost {
        // From the screensaver chrome → open straight to the Screensaver tab (not the active
        // feature's tab), so the user lands on the settings for what they're looking at.
        override fun openSettings() = this@HomeActivity.openSettings(SettingsTabKey.SCREENSAVER)
        override fun openInfo() {
            currentFeatureContribution()?.showInfo()
        }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        )

        shellChrome = ShellChromeController(
            context = this,
            prefs = prefs,
            tvClock = tvClock,
            btnInfo = findViewById(R.id.btnInfo),
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
    }

    override fun onStart() {
        super.onStart()
        shellChrome.launcher.refresh()
        registerReceiver(clockTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        updateSharedClock()
        RustyApp.haRepository(this).addListener(shellChrome.chipListener)
        receiverController.ensureStarted()
    }

    override fun onStop() {
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
        applyKeepScreenOn()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(autoHideBarsTick)
        screensaver.onPause()
    }

    override fun onDestroy() {
        screensaver.dispose()
        super.onDestroy()
    }

    /**
     * The Activity now absorbs configuration changes itself (`android:configChanges`) instead of being
     * recreated on rotation — that's what keeps Home Assistant's WebView from reloading when the device
     * rotates. The trade-off is that nothing is auto-re-inflated, so we manually refresh the few things
     * a recreate used to give us for free:
     *  1. the shared floating clock's orientation-qualified text size (`values-port/dimens` shrinks it);
     *  2. the orientation-sensitive Spotify fragment's view (it has `layout-port`/`values-port`), rebuilt
     *     in place — HA and the screensaver are not orientation-qualified, so they are left untouched and
     *     keep their live state;
     *  3. the clock park / chrome layout for the new window dimensions.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        tvClock.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.shell_clock_text_size))
        featureNavigator.recreateFeatureView(FeatureId.SPOTIFY)
        shellChrome.onFeatureChanged(featureNavigator.current, animate = false)
    }

    /**
     * While the screensaver is up, the first key dismisses it (and is consumed so it doesn't
     * leak to a control beneath). Otherwise any key re-arms the idle timer, then transitional
     * key delegation to the active fragment (e.g. transport keys) runs as before.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screensaver.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) screensaver.onWakeKey()
            return true
        }
        screensaver.resetIdleTimer()
        val active = featureNavigator.currentFragment
        if (active is KeyEventTarget && active.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

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
     */
    fun setHomeAssistantEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(HomeAssistantFeature.KEY_ENABLED, enabled).apply()
        // Keep the nav-state ring in sync so next() and onEnabledChanged queries are correct.
        featureNavigator.state.onEnabledChanged(FeatureRegistry.enabledIds(prefs))
        if (!enabled) {
            // Disabling HA: if it's foreground, switch away first (so it's no longer current), then
            // REMOVE its retained fragment so the WebView/native resources are destroyed rather than
            // kept hidden. removeRetained no-ops when HA was never shown (no fragment to destroy).
            if (currentFeatureId() == FeatureId.HOME_ASSISTANT) {
                switchTo(FeatureId.SPOTIFY)
            }
            featureNavigator.removeRetained(FeatureId.HOME_ASSISTANT)
        }
        shellChrome.onFeatureChanged(currentFeatureId(), animate = false)
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

    /** Applies the keep-screen-on window flag to match the current setting. */
    private fun applyKeepScreenOn() {
        if (keepScreenOnEnabled) {
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
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_BITRATE_KBPS = "bitrate_kbps"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_TIME_FORMAT_24H = "time_format_24h"
        private const val KEY_CURRENT_FEATURE = "current_feature"
        private const val DEFAULT_DEVICE_NAME = "Android Speaker"
        private const val DEFAULT_BITRATE_KBPS = 160
        // Mirrors SpotifyFragment.BASE_PAD_DP so the floating shell chrome sits in the same safe box
        // the Spotify content used (keeps the clock's corner-park position consistent across the move).
        private const val CHROME_BASE_PAD_DP = 22
        private val SUPPORTED_BITRATES_KBPS = setOf(96, 160, 320)

        private const val AUTO_HIDE_MS = 4_000L
    }
}
