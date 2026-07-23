package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Pure request-building + response-parsing for Home Assistant's auth API (JVM-unit-testable;
 * mirrors the ImmichApi encode/parse split). Endpoints: POST /auth/login_flow (start),
 * POST /auth/login_flow/{id} (credentials / MFA step), POST /auth/token (code exchange,
 * refresh grant, revoke). The client_id is the frontend's own convention — origin + "/" —
 * so tokens minted here are accepted by the HA frontend when injected as `hassTokens`.
 */
object HaAuth {

    fun clientId(origin: String): String = "$origin/"
    fun loginFlowUrl(origin: String): String = "$origin/auth/login_flow"
    fun loginFlowStepUrl(origin: String, flowId: String): String = "$origin/auth/login_flow/$flowId"
    fun tokenUrl(origin: String): String = "$origin/auth/token"

    fun loginFlowBody(origin: String): String = JSONObject()
        .put("client_id", clientId(origin))
        .put("handler", JSONArray().put("homeassistant").put(JSONObject.NULL))
        .put("redirect_uri", "${clientId(origin)}?auth_callback=1")
        .toString()

    fun credentialsBody(origin: String, username: String, password: String): String = JSONObject()
        .put("client_id", clientId(origin))
        .put("username", username)
        .put("password", password)
        .toString()

    fun mfaBody(origin: String, code: String): String = JSONObject()
        .put("client_id", clientId(origin))
        .put("code", code)
        .toString()

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    fun tokenExchangeBody(origin: String, authCode: String): String =
        form("grant_type" to "authorization_code", "code" to authCode, "client_id" to clientId(origin))

    fun tokenRefreshBody(origin: String, refreshToken: String): String =
        form("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId(origin))

    fun tokenRevokeBody(refreshToken: String): String =
        form("token" to refreshToken, "action" to "revoke")

    /** One login_flow response, discriminated. */
    sealed interface FlowStep {
        data class NeedCredentials(val flowId: String) : FlowStep
        data class NeedMfa(val flowId: String) : FlowStep
        data class Done(val authCode: String) : FlowStep
        data class Rejected(val message: String) : FlowStep
        object Malformed : FlowStep
    }

    fun parseFlowResponse(json: String?): FlowStep {
        val o = runCatching { JSONObject(json ?: return FlowStep.Malformed) }.getOrNull()
            ?: return FlowStep.Malformed
        return when (o.optString("type")) {
            "create_entry" -> o.optString("result").takeIf { it.isNotEmpty() }
                ?.let { FlowStep.Done(it) } ?: FlowStep.Malformed
            "form" -> {
                val flowId = o.optString("flow_id").takeIf { it.isNotEmpty() }
                    ?: return FlowStep.Malformed
                // Android's org.json returns the string "null" for JSON null — not an error.
                val error = o.optJSONObject("errors")?.optString("base").orEmpty()
                when {
                    error.isNotEmpty() && error != "null" -> FlowStep.Rejected(rejectionMessage(error))
                    o.optString("step_id") == "mfa" -> FlowStep.NeedMfa(flowId)
                    else -> FlowStep.NeedCredentials(flowId)
                }
            }
            else -> FlowStep.Malformed
        }
    }

    private fun rejectionMessage(errorCode: String): String = when (errorCode) {
        "invalid_auth" -> "Wrong username or password."
        "invalid_code" -> "Wrong two-factor code."
        else -> "Sign-in failed ($errorCode)."
    }

    /** [refreshToken] is null on refresh-grant responses (the grant keeps the old one). */
    data class HaTokens(val accessToken: String, val refreshToken: String?, val expiresEpochMs: Long)

    fun parseTokens(json: String?, nowMs: Long): HaTokens? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val access = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        val refresh = o.optString("refresh_token").takeIf { it.isNotEmpty() && it != "null" }
        return HaTokens(access, refresh, nowMs + o.optLong("expires_in", 1800L) * 1000)
    }

    fun serializeTokens(t: HaTokens): String = JSONObject()
        .put("access_token", t.accessToken)
        .put("refresh_token", t.refreshToken ?: JSONObject.NULL)
        .put("expires", t.expiresEpochMs)
        .toString()

    fun parseStoredTokens(json: String?): HaTokens? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val access = o.optString("access_token").takeIf { it.isNotEmpty() } ?: return null
        val refresh = o.optString("refresh_token").takeIf { it.isNotEmpty() && it != "null" }
        return HaTokens(access, refresh, o.optLong("expires", 0L))
    }

    /** The exact localStorage entry the HA frontend reads at boot to skip its login page. */
    fun hassTokensJson(origin: String, t: HaTokens): String = JSONObject()
        .put("access_token", t.accessToken)
        .put("token_type", "Bearer")
        .put("refresh_token", t.refreshToken ?: JSONObject.NULL)
        .put("expires_in", 1800)
        .put("expires", t.expiresEpochMs)
        .put("hassUrl", origin)
        .put("clientId", clientId(origin))
        .toString()

    /** Seeds hassTokens only when absent: the frontend refreshes its own copy over time, and a
     *  navigation must never clobber a newer access token with our stored (possibly stale) one. */
    fun hassTokensJs(origin: String, t: HaTokens): String =
        "try{if(!localStorage.getItem('hassTokens')){localStorage.setItem('hassTokens'," +
            JSONObject.quote(hassTokensJson(origin, t)) + ");}}catch(e){}"
}
