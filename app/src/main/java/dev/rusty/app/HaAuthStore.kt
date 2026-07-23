package dev.rusty.app

import android.content.SharedPreferences

/**
 * Persistence for the HA refresh/access tokens. The token JSON lives in [SecretStore] (encrypted,
 * excluded from backups — same home as the Immich API key); the origin it was minted for lives in
 * plain prefs (an origin is not a secret) and gates every read: a URL change makes stored tokens
 * unreachable even before the explicit [clear] on origin change runs.
 */
object HaAuthStore {
    const val KEY_TOKENS = "ha_auth_tokens"        // SecretStore
    const val KEY_TOKEN_ORIGIN = "ha_token_origin" // SharedPreferences

    /** Pure origin gate (unit-tested); the Android wrappers below just feed it. */
    fun tokensForOrigin(storedOrigin: String?, storedTokensJson: String?, origin: String?): HaAuth.HaTokens? =
        if (origin == null || storedOrigin != origin) null
        else HaAuth.parseStoredTokens(storedTokensJson)

    fun tokensFor(prefs: SharedPreferences, secrets: SecretStore, origin: String?): HaAuth.HaTokens? =
        tokensForOrigin(prefs.getString(KEY_TOKEN_ORIGIN, null), secrets.get(KEY_TOKENS), origin)

    fun save(prefs: SharedPreferences, secrets: SecretStore, origin: String, tokens: HaAuth.HaTokens) {
        secrets.put(KEY_TOKENS, HaAuth.serializeTokens(tokens))
        prefs.edit().putString(KEY_TOKEN_ORIGIN, origin).apply()
    }

    fun clear(prefs: SharedPreferences, secrets: SecretStore) {
        secrets.remove(KEY_TOKENS)
        prefs.edit().remove(KEY_TOKEN_ORIGIN).apply()
    }
}
