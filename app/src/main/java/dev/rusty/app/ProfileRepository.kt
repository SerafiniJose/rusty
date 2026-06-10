package dev.rusty.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the connected user's public profile (display name + avatar) via the documented Spotify
 * Web API. Parsing is split from I/O so it can be unit-tested on the JVM.
 */
object ProfileRepository {
    private const val TAG = "ProfileRepository"

    private val cache = ConcurrentHashMap<String, UserProfile>()

    /** Drops cached profiles (call at session/service teardown). */
    fun clear() {
        cache.clear()
    }

    /** Pure: maps a `GET /v1/users/{id}` JSON body to a [UserProfile], or null if unparseable. */
    fun parse(json: String): UserProfile? {
        return try {
            val obj = JSONObject(json)
            val name = obj.optString("display_name").takeIf { it.isNotBlank() }
            val images = obj.optJSONArray("images")
            val avatar = if (images != null && images.length() > 0) {
                images.getJSONObject(0).optString("url").takeIf { it.isNotBlank() }
            } else null
            UserProfile(displayName = name, avatarUrl = avatar)
        } catch (e: Exception) {
            null
        }
    }

    /** I/O: fetches + caches the profile for [username]. Returns null on any network/parse failure. */
    fun fetch(username: String, token: String): UserProfile? {
        cache[username]?.let { return it }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://api.spotify.com/v1/users/$username").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "profile fetch HTTP $code for $username")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body)?.also { cache[username] = it }
        } catch (e: Exception) {
            Log.w(TAG, "profile fetch failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
