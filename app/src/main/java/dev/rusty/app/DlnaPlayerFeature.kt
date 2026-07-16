package dev.rusty.app

import android.content.SharedPreferences
import androidx.fragment.app.Fragment

/**
 * The DLNA Player screen feature: default off. Independent of the renderer service run-state
 * ([dev.rusty.app.renderer.MediaRendererController.KEY_RENDERER_ENABLED]) — this pref only controls
 * whether the now-playing screen + launcher entry appear. The renderer can run headless with this
 * off, and this can be on while the service is stopped (the screen then shows a "stopped" state).
 */
object DlnaPlayerFeature : Feature {
    const val KEY_ENABLED = "dlna_feature_enabled"

    override val id = FeatureId.DLNA
    override val title = "DLNA Player"
    override val iconRes = R.drawable.ic_mdi_dlna
    override fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    override fun createFragment(): Fragment = DlnaPlayerFragment()
    override val settingsTab = SettingsTabKey.DLNA_PLAYER
}
