package dev.rusty.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Spotify Authorization Code + PKCE for the "Watch Spotify" account link.
 *
 * The receiver's native session can mint a Web API token only while a Connect session is alive.
 * That left Watch mode blank on a cold start. Linking an account here gives the app a refresh token
 * that survives reboots, so `/v1/me/player` (and Canvas) keep working while this display is idle.
 *
 * The client is a desktop-style loopback client, so the redirect is `http://127.0.0.1:25566/callback`
 * and the in-app login WebView intercepts it before it is ever loaded. PKCE protects the code
 * exchange even though the app binary cannot keep a client secret secret; the secret is therefore
 * deliberately not embedded here.
 */
object SpotifyOAuth {
    const val CLIENT_ID = "90d7fa8653384b63bdd1d795406b6be4"
    const val REDIRECT_URI = "http://127.0.0.1:25566/callback"

    /**
     * Read state for the Watch face, the profile read for the linked account's name, and
     * `user-modify-playback-state` for the Watch detail view's transport buttons. A token minted
     * before that last scope was added still reads fine but answers 403 on transport — which the
     * detail view reports as "relink".
     */
    const val SCOPES =
        "user-read-playback-state user-read-currently-playing user-read-private user-read-email " +
            "user-modify-playback-state"

    private const val AUTHORIZE_ENDPOINT = "https://accounts.spotify.com/authorize"
    private const val TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token"
    private const val VERIFIER_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val STATE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    /** A token pair plus its absolute expiry. [refreshToken] is null only on a malformed response. */
    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    ) {
        fun isValid(nowMs: Long, safetyMs: Long = SAFETY_MS): Boolean =
            accessToken.isNotBlank() && nowMs < expiresAtMs - safetyMs
    }

    sealed interface Redirect {
        data class Code(val code: String) : Redirect
        data class Failure(val message: String) : Redirect
        data object Ignored : Redirect
    }

    sealed interface TokenResult {
        data class Success(val tokens: Tokens) : TokenResult
        data class Failure(val message: String) : TokenResult
    }

    /** Refresh this many milliseconds before the server expiry. */
    const val SAFETY_MS = 60_000L

    /** 64 characters is the maximum PKCE verifier length and the most entropy Spotify accepts. */
    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        randomString(64, VERIFIER_ALPHABET, random)

    fun newState(random: SecureRandom = SecureRandom()): String =
        randomString(32, STATE_ALPHABET, random)

    /** S256 challenge: base64url(SHA-256(ASCII(verifier))) without padding. */
    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(state: String, verifier: String): String = buildString {
        append(AUTHORIZE_ENDPOINT)
        append("?client_id=").append(encode(CLIENT_ID))
        append("&response_type=code")
        append("&redirect_uri=").append(encode(REDIRECT_URI))
        append("&scope=").append(encode(SCOPES))
        append("&state=").append(encode(state))
        append("&code_challenge_method=S256")
        append("&code_challenge=").append(encode(challenge(verifier)))
    }

    /**
     * The WebView hands us the redirect after Spotify approves (or rejects) the request. Only the
     * exact loopback callback is claimed; every other URL belongs to Spotify's login flow and must
     * keep loading. `state` is checked before the code is trusted.
     */
    fun parseRedirect(url: String, expectedState: String): Redirect {
        if (!url.startsWith(REDIRECT_URI)) return Redirect.Ignored
        val query = try {
            URL(url).query.orEmpty()
        } catch (e: Exception) {
            return Redirect.Failure("Could not read Spotify's reply.")
        }
        val params = query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) null else decode(part.substring(0, eq)) to decode(part.substring(eq + 1))
        }.toMap()
        params["error"]?.let { return Redirect.Failure(oauthError(it)) }
        if (params["state"] != expectedState) return Redirect.Failure("Spotify's reply did not match this request.")
        val code = params["code"]?.takeIf { it.isNotBlank() }
            ?: return Redirect.Failure("Spotify did not return an authorization code.")
        return Redirect.Code(code)
    }

    /** I/O: exchange the one-time code. Runs on a worker thread at every call site. */
    fun exchange(code: String, verifier: String): TokenResult =
        tokenRequest(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to CLIENT_ID,
                "code_verifier" to verifier,
            ),
            previousRefreshToken = null,
        )

    /** I/O: rotate the refresh token into a new access token. */
    fun refresh(refreshToken: String): TokenResult =
        tokenRequest(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
            ),
            previousRefreshToken = refreshToken,
        )

    /** Pure: parse an Accounts token response. Kept out of [tokenRequest] so it is JVM-testable. */
    fun parseTokenResponse(
        body: String,
        nowMs: Long,
        previousRefreshToken: String? = null,
    ): Tokens? = try {
        val json = JSONObject(body)
        val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
        val expiresIn = json.optLong("expires_in", 3_600L)
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: previousRefreshToken
        Tokens(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMs = nowMs + expiresIn.coerceAtLeast(1L) * 1_000L,
        )
    } catch (e: Exception) {
        null
    }

    private fun tokenRequest(
        params: Map<String, String>,
        previousRefreshToken: String?,
    ): TokenResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val form = params.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
            conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return TokenResult.Failure(errorMessage(body, code))
            }
            val tokens = parseTokenResponse(body, System.currentTimeMillis(), previousRefreshToken)
                ?: return TokenResult.Failure("Spotify returned an unreadable token.")
            TokenResult.Success(tokens)
        } catch (e: Exception) {
            TokenResult.Failure(e.message ?: "Could not reach Spotify.")
        } finally {
            conn?.disconnect()
        }
    }

    /** I/O: reads the linked account's display name after the token is stored. */
    fun fetchDisplayName(token: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://api.spotify.com/v1/me").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optString("display_name").takeIf { it.isNotBlank() }
                ?: json.optString("id").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun errorMessage(body: String, code: Int): String {
        val description = try {
            JSONObject(body).optString("error_description")
                .takeIf { it.isNotBlank() }
                ?: JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        return description ?: "Spotify refused the token request (HTTP $code)."
    }

    private fun oauthError(code: String): String = when (code) {
        "access_denied" -> "Spotify access was cancelled."
        "invalid_client" -> "Spotify rejected this app's client ID."
        "invalid_scope" -> "Spotify rejected the requested permissions."
        else -> "Spotify returned an error: $code."
    }

    private fun randomString(length: Int, alphabet: String, random: SecureRandom): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(alphabet[random.nextInt(alphabet.length)]) }
        return out.toString()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
