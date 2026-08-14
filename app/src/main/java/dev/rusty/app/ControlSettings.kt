package dev.rusty.app

import android.content.SharedPreferences
import java.util.UUID

/**
 * Prefs for the Remote Control feature. [deviceId] is the persistent per-install identity the
 * HA integration keys config entries on (spec: service name and IP are NOT identities — Android
 * may rename NSD services on collision and DHCP moves addresses).
 */
object ControlSettings {
    const val KEY_ENABLED = "control_api_enabled"
    const val KEY_DEVICE_ID = "control_device_id"
    const val PORT = 8765

    fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun deviceId(prefs: SharedPreferences): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
