package dev.rusty.app

import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange. Seam so [HaAuthClient] is unit-testable without a network. */
fun interface HaHttp {
    fun request(method: String, url: String, contentType: String?, bearer: String?, body: String?): HaHttpResponse
}

data class HaHttpResponse(val code: Int, val body: String?)

/**
 * Blocking Home Assistant auth client (callers run it on Dispatchers.IO). Never throws; failures
 * map to [SignIn.Failed] / null / [TestResult.Failed]. The password exists only as a parameter —
 * it is sent to the login_flow step and never stored or logged.
 */
class HaAuthClient(private val http: HaHttp = defaultHttp()) {

    sealed interface SignIn {
        data class Success(val tokens: HaAuth.HaTokens) : SignIn
        data class MfaRequired(val flowId: String) : SignIn
        data class Failed(val reason: String) : SignIn
    }

    sealed interface TestResult {
        data class Ok(val message: String) : TestResult
        data class Failed(val reason: String) : TestResult
    }

    fun signIn(origin: String, username: String, password: String, nowMs: Long): SignIn {
        val start = postJson(HaAuth.loginFlowUrl(origin), HaAuth.loginFlowBody(origin))
            ?: return SignIn.Failed(UNREACHABLE)
        val flowId = when (val step = HaAuth.parseFlowResponse(start)) {
            is HaAuth.FlowStep.NeedCredentials -> step.flowId
            is HaAuth.FlowStep.Rejected -> return SignIn.Failed(step.message)
            // No password form offered (SSO / trusted-networks-only server).
            else -> return SignIn.Failed(NO_PASSWORD_LOGIN)
        }
        return step(origin, flowId, HaAuth.credentialsBody(origin, username, password), nowMs)
    }

    fun completeMfa(origin: String, flowId: String, code: String, nowMs: Long): SignIn =
        step(origin, flowId, HaAuth.mfaBody(origin, code), nowMs)

    private fun step(origin: String, flowId: String, body: String, nowMs: Long): SignIn {
        val resp = postJson(HaAuth.loginFlowStepUrl(origin, flowId), body)
            ?: return SignIn.Failed(UNREACHABLE)
        return when (val step = HaAuth.parseFlowResponse(resp)) {
            is HaAuth.FlowStep.Done -> exchange(origin, step.authCode, nowMs)
            is HaAuth.FlowStep.NeedMfa -> SignIn.MfaRequired(step.flowId)
            is HaAuth.FlowStep.Rejected -> SignIn.Failed(step.message)
            is HaAuth.FlowStep.NeedCredentials -> SignIn.Failed("Wrong username or password.")
            HaAuth.FlowStep.Malformed -> SignIn.Failed(UNREACHABLE)
        }
    }

    private fun exchange(origin: String, authCode: String, nowMs: Long): SignIn {
        val resp = safeRequest("POST", HaAuth.tokenUrl(origin), FORM, null,
            HaAuth.tokenExchangeBody(origin, authCode))
        val tokens = if (resp.code in 200..299) HaAuth.parseTokens(resp.body, nowMs) else null
        return if (tokens?.refreshToken != null) SignIn.Success(tokens)
        else SignIn.Failed(UNREACHABLE)
    }

    /** Refresh-grant responses carry no refresh_token; the result keeps the one passed in. */
    fun refresh(origin: String, refreshToken: String, nowMs: Long): HaAuth.HaTokens? {
        val resp = safeRequest("POST", HaAuth.tokenUrl(origin), FORM, null,
            HaAuth.tokenRefreshBody(origin, refreshToken))
        if (resp.code !in 200..299) return null
        return HaAuth.parseTokens(resp.body, nowMs)?.copy(refreshToken = refreshToken)
    }

    fun test(origin: String, refreshToken: String, nowMs: Long): TestResult {
        val fresh = refresh(origin, refreshToken, nowMs)
            ?: return TestResult.Failed("Sign-in expired or was revoked — sign in again.")
        val api = safeRequest("GET", "$origin/api/", null, fresh.accessToken, null)
        return when {
            api.code in 200..299 -> TestResult.Ok("✓ Connected and signed in.")
            api.code == -1 -> TestResult.Failed(UNREACHABLE)
            else -> TestResult.Failed("Server reachable but the API refused the token (HTTP ${api.code}).")
        }
    }

    /** Best-effort server-side revocation on sign-out; failures are ignored (local wipe still happens). */
    fun revoke(origin: String, refreshToken: String) {
        safeRequest("POST", HaAuth.tokenUrl(origin), FORM, null, HaAuth.tokenRevokeBody(refreshToken))
    }

    private fun postJson(url: String, body: String): String? {
        val resp = safeRequest("POST", url, "application/json", null, body)
        return if (resp.code in 200..299) resp.body else null
    }

    private fun safeRequest(method: String, url: String, contentType: String?, bearer: String?, body: String?): HaHttpResponse =
        runCatching { http.request(method, url, contentType, bearer, body) }
            .getOrDefault(HaHttpResponse(-1, null))

    companion object {
        const val UNREACHABLE = "Couldn't reach the server — check the address."
        const val NO_PASSWORD_LOGIN =
            "This server doesn't offer password login — sign in on the Home Assistant page instead."
        private const val FORM = "application/x-www-form-urlencoded"

        /** Process-wide instance (settings panel + sign-out share it). Tests build their own. */
        val shared: HaAuthClient by lazy { HaAuthClient() }

        private fun defaultHttp(): HaHttp = HaHttp { method, url, contentType, bearer, body ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    setRequestProperty("Accept", "application/json")
                    if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                    connectTimeout = 8000
                    readTimeout = 8000
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", contentType ?: "application/json")
                    }
                }
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                HaHttpResponse(code, stream?.use { it.readBytes().decodeToString() })
            } catch (e: Exception) {
                HaHttpResponse(-1, null)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
