package dev.rusty.app

import android.content.Context
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
 * The real Spotify idle face promoted to a screensaver theme: a drifting [AmbientMeshView] + a
 * large centered clock/date and the idle status line (which already reads "♪ title — artist"
 * while a track plays). Carries the same bottom-right Settings/Info chrome as the idle face,
 * routed to the shell via [ScreensaverHost]. A wake gesture off the buttons exits immediately
 * (the bloom lives in the destination dashboard).
 */
class ClockTheme : ScreensaverTheme {
    private lateinit var root: View
    private lateinit var mesh: AmbientMeshView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var chrome: View
    private lateinit var launcher: FeatureLauncher

    override fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View {
        root = LayoutInflater.from(context).inflate(R.layout.screensaver_clock, parent, false)
        mesh = root.findViewById(R.id.ssMesh)
        clock = root.findViewById(R.id.ssClock)
        date = root.findViewById(R.id.ssDate)
        status = root.findViewById(R.id.ssStatus)
        chrome = root.findViewById(R.id.ssChrome)
        root.findViewById<ImageButton>(R.id.ssBtnSettings).setOnClickListener { host.openSettings() }
        root.findViewById<ImageButton>(R.id.ssBtnInfo).setOnClickListener { host.openInfo() }
        launcher = FeatureLauncher(
            toggle = root.findViewById(R.id.ssBtnLauncher),
            menu = root.findViewById<LinearLayout>(R.id.ssLauncherMenu),
            scrim = root.findViewById(R.id.ssLauncherScrim),
            activeTint = ContextCompat.getColor(context, R.color.accent_fallback),
            inactiveTint = ContextCompat.getColor(context, R.color.ink),
            itemLayoutRes = R.layout.view_launcher_item,
            minEntriesToShow = 2,
        ) { host.launcherEntries() }
        launcher.refresh()
        return root
    }

    override fun bind(state: ReceiverDashboardState, is24Hour: Boolean) {
        val now = System.currentTimeMillis()
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        clock.text = ClockFormat.time(now, is24Hour, locale, zone)
        date.text = ClockFormat.date(now, locale, zone)
        status.text = state.idleStatus().first
    }

    override fun onShown() { mesh.start() }
    override fun onHidden() { mesh.stop() }

    override fun setChromeVisible(visible: Boolean) {
        if (!visible) launcher.collapse()  // don't leave the menu floating over a chrome-less saver
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
