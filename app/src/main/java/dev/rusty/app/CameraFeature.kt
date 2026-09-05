package dev.rusty.app

import android.content.SharedPreferences
import androidx.fragment.app.Fragment

/**
 * The Camera (RTSP) screen feature: default off, following [DlnaPlayerFeature]'s structure. Grid +
 * live view live in the single [CameraFragment]; snapshotting/playback are owned by that fragment
 * (Task 11 wiring) and torn down whenever the fragment is hidden or stopped — [retainStartedWhenHidden]
 * is deliberately NOT overridden, so an inactive camera screen never keeps a decoder alive.
 *
 * [settingsTab] points at its own [SettingsTabKey.CAMERA] tab; [settingsPanel] returns
 * [CameraSettingsPanel] (Task 12: camera CRUD, ONVIF discovery, behavior prefs), the same
 * Feature-owned-panel wiring [SpotifyFeature] and [HomeAssistantFeature] use.
 */
object CameraFeature : Feature {
    const val KEY_ENABLED = "camera_feature_enabled"

    override val id = FeatureId.CAMERA
    override val title = "Cameras"
    override val iconRes = R.drawable.ic_mdi_cctv
    override fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    override fun createFragment(): Fragment = CameraFragment()
    override val settingsTab = SettingsTabKey.CAMERA
    override fun settingsPanel(ctx: SettingsPanelContext): SettingsPanelProvider = CameraSettingsPanel(ctx)
}
