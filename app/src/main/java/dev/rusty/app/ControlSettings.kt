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
    /** Whether /api/... requests must present the stored password ([SECRET_PASSWORD]). */
    const val KEY_AUTH_REQUIRED = "control_auth_required"
    /** [SecretStore] entry holding the API password — encrypted storage, like every credential. */
    const val SECRET_PASSWORD = "control_api_password"
    const val PORT = 8765

    fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun isAuthRequired(prefs: SharedPreferences) = prefs.getBoolean(KEY_AUTH_REQUIRED, false)
    fun setAuthRequired(prefs: SharedPreferences, required: Boolean) =
        prefs.edit().putBoolean(KEY_AUTH_REQUIRED, required).apply()

    /**
     * The password every `/api/...` request must present, or null when the gate is off.
     *
     * The switch and the secret must BOTH be set: the settings UI refuses to enable the switch
     * before a password exists, and if prefs ever drift into flag-on/secret-gone (a restored
     * backup — secrets deliberately don't ride backups), the API stays reachable rather than
     * 401ing its owner out forever. An unset password can never lock the device.
     */
    fun requiredPassword(prefs: SharedPreferences, secrets: SecretStore): String? =
        if (isAuthRequired(prefs)) secrets.get(SECRET_PASSWORD)?.takeIf { it.isNotBlank() }
        else null

    fun deviceId(prefs: SharedPreferences): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }
}
