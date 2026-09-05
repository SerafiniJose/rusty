package dev.rusty.app

import android.content.SharedPreferences

/**
 * Persists the camera list as a single JSON pref value (`camera_list`). Credentials never live
 * here — a caller that split inline credentials off a pasted URL stores them separately in
 * [SecretStore] under the keys [credentialKeys] names.
 */
object CameraStore {
    const val KEY_CAMERA_LIST = "camera_list"

    fun load(prefs: SharedPreferences): List<CameraRecord> =
        CameraCodec.decode(prefs.getString(KEY_CAMERA_LIST, null)).sortedBy { it.position }

    fun save(prefs: SharedPreferences, cameras: List<CameraRecord>) {
        prefs.edit().putString(KEY_CAMERA_LIST, CameraCodec.encode(cameras)).apply()
    }

    /** The [SecretStore] key pair (username, password) for a camera's inline credentials. */
    fun credentialKeys(id: String): Pair<String, String> = "camera_user_$id" to "camera_pass_$id"
}
