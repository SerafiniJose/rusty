package dev.rusty.app

/**
 * Process-wide holder for the Spotify access token minted by the native core. The token itself is
 * kept here (not broadcast in the clear); a bare ACTION_TOKEN signal tells listeners it changed.
 * The token + its expiry are stored as one immutable snapshot swapped atomically, so a concurrent
 * reader never sees a new token paired with a stale expiry.
 */
object TokenStore {
    /** Safety margin so we refresh slightly before the server-side expiry. */
    private const val SAFETY_MS = 60_000L

    private data class Snapshot(val token: String, val expiresAtMs: Long)

    @Volatile
    private var snapshot: Snapshot? = null

    val accessToken: String?
        get() = snapshot?.token

    fun update(token: String, expiresInSecs: Int, nowMs: Long = System.currentTimeMillis()) {
        snapshot = Snapshot(token, nowMs + expiresInSecs * 1000L)
    }

    fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = snapshot ?: return false
        return current.token.isNotBlank() && nowMs < current.expiresAtMs - SAFETY_MS
    }

    fun clear() {
        snapshot = null
    }
}
