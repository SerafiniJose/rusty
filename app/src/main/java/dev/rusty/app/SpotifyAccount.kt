package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Process-wide owner of the linked Spotify account's OAuth tokens.
 *
 * The token pair lives in [SecretStore] (encrypted at rest); the non-secret linked marker and
 * display name live in the normal prefs so settings can render synchronously. [preload] warms the
 * in-memory snapshot from disk on a worker thread at app start, which keeps the synchronous
 * [cachedToken] used by the existing token-provider seam off the main thread's first-use path.
 *
 * All refresh I/O runs on [Dispatchers.IO] and is serialized by [refreshLock], so the watch poll
 * and a Canvas resolve that both see an expired token cannot rotate it twice and invalidate one
 * of the results.
 */
object SpotifyAccount {
    private const val TAG = "SpotifyAccount"
    private const val PREFS_NAME = "spotify_receiver_prefs"
    private const val KEY_LINKED = "spotify_oauth_linked"
    private const val KEY_NAME = "spotify_oauth_name"

    private const val SECRET_ACCESS = "spotify_oauth_access"
    private const val SECRET_REFRESH = "spotify_oauth_refresh"
    private const val SECRET_EXPIRES = "spotify_oauth_expires"

    private val refreshLock = Mutex()

    @Volatile
    private var snapshot: SpotifyOAuth.Tokens? = null

    sealed interface LinkResult {
        data class Linked(val displayName: String?) : LinkResult
        data class Failed(val message: String) : LinkResult
    }

    fun isLinked(context: Context): Boolean = prefs(context).getBoolean(KEY_LINKED, false)

    fun displayName(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    /** Synchronous read for [androidSpotifyTokenProvider]; null until [preload] or a link. */
    fun cachedToken(nowMs: Long = System.currentTimeMillis()): String? =
        snapshot?.takeIf { it.isValid(nowMs) }?.accessToken

    /** Load the persisted token pair once. Safe to call repeatedly; does disk/crypto work. */
    suspend fun preload(context: Context) {
        if (snapshot != null || !isLinked(context)) return
        val loaded = withContext(Dispatchers.IO) { loadFromDisk(context) }
        if (snapshot == null) snapshot = loaded
    }

    /**
     * Returns a usable token. With [forceRefresh] (after a 401) the refresh token is used even if
     * the cached access token has not quite expired yet.
     */
    suspend fun accessToken(context: Context, forceRefresh: Boolean = false): String? {
        val now = System.currentTimeMillis()
        val current = snapshot ?: if (isLinked(context)) {
            withContext(Dispatchers.IO) { loadFromDisk(context) }.also { if (snapshot == null) snapshot = it }
        } else {
            null
        }
        if (!forceRefresh && current?.isValid(now) == true) return current.accessToken

        val refreshToken = current?.refreshToken ?: return null
        return refreshLock.withLock {
            val latest = snapshot
            if (!forceRefresh && latest?.isValid(System.currentTimeMillis()) == true) {
                return@withLock latest.accessToken
            }
            val tokenToUse = latest?.refreshToken ?: refreshToken
            when (val result = withContext(Dispatchers.IO) { SpotifyOAuth.refresh(tokenToUse) }) {
                is SpotifyOAuth.TokenResult.Success -> {
                    persist(context, result.tokens)
                    result.tokens.accessToken
                }
                is SpotifyOAuth.TokenResult.Failure -> {
                    Log.w(TAG, "refresh failed: ${result.message}")
                    // A revoked/expired refresh token is fatal; keep the linked marker until the
                    // user unlinks so settings can say the account needs re-linking? Better to
                    // surface the failure and let the next poll retry with the same refresh token.
                    null
                }
            }
        }
    }

    /** Exchange the WebView's authorization code and persist the account. */
    suspend fun link(context: Context, code: String, verifier: String): LinkResult =
        withContext(Dispatchers.IO) {
            when (val result = SpotifyOAuth.exchange(code, verifier)) {
                is SpotifyOAuth.TokenResult.Success -> {
                    val name = SpotifyOAuth.fetchDisplayName(result.tokens.accessToken)
                    persist(context, result.tokens, name)
                    LinkResult.Linked(name)
                }
                is SpotifyOAuth.TokenResult.Failure -> LinkResult.Failed(result.message)
            }
        }

    fun unlink(context: Context) {
        snapshot = null
        val secrets = SecretStore.of(context)
        secrets.remove(SECRET_ACCESS)
        secrets.remove(SECRET_REFRESH)
        secrets.remove(SECRET_EXPIRES)
        prefs(context).edit().remove(KEY_LINKED).remove(KEY_NAME).apply()
    }

    private fun persist(context: Context, tokens: SpotifyOAuth.Tokens, name: String? = null) {
        val secrets = SecretStore.of(context)
        secrets.put(SECRET_ACCESS, tokens.accessToken)
        tokens.refreshToken?.let { secrets.put(SECRET_REFRESH, it) }
        secrets.put(SECRET_EXPIRES, tokens.expiresAtMs.toString())
        val editor = prefs(context).edit().putBoolean(KEY_LINKED, true)
        name?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_NAME, it) }
        editor.apply()
        snapshot = tokens
    }

    private fun loadFromDisk(context: Context): SpotifyOAuth.Tokens? {
        val secrets = SecretStore.of(context)
        val access = secrets.get(SECRET_ACCESS)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = secrets.get(SECRET_REFRESH)?.takeIf { it.isNotBlank() }
        val expires = secrets.get(SECRET_EXPIRES)?.toLongOrNull() ?: 0L
        return SpotifyOAuth.Tokens(access, refresh, expires)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
