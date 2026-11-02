package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.TimeZone

/**
 * The real Spotify idle face promoted to a screensaver theme: a drifting [AmbientMeshView] (or one
 * of the flat [ClockBackdrop] fields) + a large centred clock/date and the idle status line (which
 * already reads "♪ title — artist" while a track plays). Carries the same bottom-right
 * Settings/Info chrome as the idle face, routed to the shell via [ScreensaverHost]. A wake gesture
 * off the buttons exits immediately (the bloom lives in the destination dashboard).
 *
 * Appearance is read from prefs on every [bind] (1 Hz) rather than being baked in at inflation:
 * changing the backdrop or scrim in Settings should update the face already on screen, without a
 * remount.
 */
class ClockTheme : ScreensaverTheme {
    private lateinit var root: View
    private lateinit var mesh: AmbientMeshView
    private lateinit var scrim: ClockScrimView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var chrome: View
    private lateinit var settingsButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var launcherToggle: ImageButton
    private lateinit var launcher: FeatureLauncher
    private lateinit var prefs: SharedPreferences

    private var appliedBackdrop: ClockBackdrop? = null
    private var appliedScrimPercent = -1
    private var shown = false

    override fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View {
        root = LayoutInflater.from(context).inflate(R.layout.screensaver_clock, parent, false)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mesh = root.findViewById(R.id.ssMesh)
        scrim = root.findViewById(R.id.ssClockScrim)
        clock = root.findViewById(R.id.ssClock)
        date = root.findViewById(R.id.ssDate)
        status = root.findViewById(R.id.ssStatus)
        chrome = root.findViewById(R.id.ssChrome)
        settingsButton = root.findViewById(R.id.ssBtnSettings)
        infoButton = root.findViewById(R.id.ssBtnInfo)
        settingsButton.setOnClickListener { host.openSettings() }
        infoButton.apply {
            setOnClickListener { host.openInfo() }
            UpdateDot.attach(this)
        }
        launcherToggle = root.findViewById(R.id.ssBtnLauncher)
        launcher = FeatureLauncher(
            toggle = launcherToggle,
            menu = root.findViewById<LinearLayout>(R.id.ssLauncherMenu),
            scrim = root.findViewById(R.id.ssLauncherScrim),
            activeTint = ContextCompat.getColor(context, R.color.accent_fallback),
            inactiveTint = ContextCompat.getColor(context, R.color.ink),
            itemLayoutRes = R.layout.view_launcher_item,
            minEntriesToShow = 2,
        ) { host.launcherEntries() }
        launcher.refresh()
        applyBackdrop(ClockAppearance.backdrop(prefs))
        return root
    }

    override fun bind(state: ReceiverDashboardState, is24Hour: Boolean) {
        val backdrop = ClockAppearance.backdrop(prefs)
        if (backdrop != appliedBackdrop) applyBackdrop(backdrop)
        val scrimPercent = ClockAppearance.scrimPercent(prefs)
        if (scrimPercent != appliedScrimPercent) applyScrim(backdrop)

        val now = System.currentTimeMillis()
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        clock.text = ClockFormat.time(now, is24Hour, locale, zone)
        date.text = ClockFormat.date(now, locale, zone)
        status.text = state.idleStatus().first
    }

    override fun onShown() {
        shown = true
        if (ClockAppearance.backdrop(prefs) == ClockBackdrop.AMBIENT) mesh.start()
    }

    override fun onHidden() {
        shown = false
        mesh.stop()
    }

    private fun applyBackdrop(backdrop: ClockBackdrop) {
        appliedBackdrop = backdrop
        val context = root.context
        val light = backdrop.isLight

        mesh.visibility = if (backdrop == ClockBackdrop.AMBIENT) View.VISIBLE else View.GONE
        if (shown) {
            if (backdrop == ClockBackdrop.AMBIENT) mesh.start() else mesh.stop()
        }

        when (backdrop) {
            ClockBackdrop.AMBIENT -> root.setBackgroundColor(color(context, R.color.bg_base))
            ClockBackdrop.MIDNIGHT -> root.background = gradient(
                context,
                R.color.clock_midnight_start,
                R.color.clock_midnight_end,
            )
            ClockBackdrop.SLATE -> root.background = gradient(
                context,
                R.color.clock_slate_start,
                R.color.clock_slate_end,
            )
            ClockBackdrop.EMBER -> root.background = gradient(
                context,
                R.color.clock_ember_start,
                R.color.clock_ember_end,
            )
            ClockBackdrop.PAPER -> root.background = gradient(
                context,
                R.color.clock_paper_start,
                R.color.clock_paper_end,
            )
        }

        val ink = color(context, if (light) R.color.ink_on_light else R.color.ink)
        val muted = color(context, if (light) R.color.muted_on_light else R.color.muted)
        val mutedDim = color(context, if (light) R.color.muted_dim_on_light else R.color.muted_dim)
        clock.setTextColor(ink)
        date.setTextColor(muted)
        status.setTextColor(mutedDim)

        val shadow = if (light) Color.TRANSPARENT else 0x99000000.toInt()
        for (view in listOf(clock, date, status)) {
            view.setShadowLayer(if (light) 1f else 24f, 0f, if (light) 0f else 3f, shadow)
        }

        val chromeTint = ColorStateList.valueOf(ink)
        settingsButton.imageTintList = chromeTint
        infoButton.imageTintList = chromeTint
        launcherToggle.imageTintList = chromeTint
        launcher.updateTints(color(context, R.color.accent_fallback), ink)

        applyScrim(backdrop)
    }

    private fun applyScrim(backdrop: ClockBackdrop) {
        val percent = ClockAppearance.scrimPercent(prefs)
        appliedScrimPercent = percent
        scrim.configure(
            color = Color.BLACK.takeUnless { backdrop.isLight } ?: Color.WHITE,
            strength = percent / 100f,
            centerYFraction = 0.42f,
            radiusFraction = 0.62f,
        )
    }

    private fun gradient(context: Context, start: Int, end: Int): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color(context, start), color(context, end)),
        )

    private fun color(context: Context, resId: Int): Int = ContextCompat.getColor(context, resId)

    override fun setChromeVisible(visible: Boolean) {
        if (!visible) launcher.collapse()  // don't leave the menu floating over a chrome-less saver
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun refreshLauncher() = launcher.refresh()

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
