package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAuthTest {

    private val origin = "http://ha.local:8123"

    // ---- request bodies ----
    @Test fun loginFlowBodyCarriesClientIdHandlerRedirect() {
        val o = JSONObject(HaAuth.loginFlowBody(origin))
        assertEquals("http://ha.local:8123/", o.getString("client_id"))
        assertEquals("homeassistant", o.getJSONArray("handler").getString(0))
        assertTrue(o.getJSONArray("handler").isNull(1))
        assertEquals("http://ha.local:8123/?auth_callback=1", o.getString("redirect_uri"))
    }
    @Test fun credentialsBodyCarriesUsernamePassword() {
        val o = JSONObject(HaAuth.credentialsBody(origin, "jose", "p@ss"))
        assertEquals("jose", o.getString("username"))
        assertEquals("p@ss", o.getString("password"))
        assertEquals("http://ha.local:8123/", o.getString("client_id"))
    }
    @Test fun tokenBodiesAreFormEncoded() {
        assertEquals(
            "grant_type=authorization_code&code=abc&client_id=http%3A%2F%2Fha.local%3A8123%2F",
            HaAuth.tokenExchangeBody(origin, "abc"))
        assertEquals(
            "grant_type=refresh_token&refresh_token=rt&client_id=http%3A%2F%2Fha.local%3A8123%2F",
            HaAuth.tokenRefreshBody(origin, "rt"))
        assertEquals("token=rt&action=revoke", HaAuth.tokenRevokeBody("rt"))
    }

    // ---- flow parsing ----
    @Test fun parseInitialFormStep() {
        val step = HaAuth.parseFlowResponse(
            """{"type":"form","flow_id":"f1","step_id":"init","errors":{}}""")
        assertEquals(HaAuth.FlowStep.NeedCredentials("f1"), step)
    }
    @Test fun parseMfaStep() {
        val step = HaAuth.parseFlowResponse(
            """{"type":"form","flow_id":"f2","step_id":"mfa","errors":{}}""")
        assertEquals(HaAuth.FlowStep.NeedMfa("f2"), step)
    }
    @Test fun parseInvalidAuth() {
        val step = HaAuth.parseFlowResponse(
            """{"type":"form","flow_id":"f1","step_id":"init","errors":{"base":"invalid_auth"}}""")
        assertEquals(HaAuth.FlowStep.Rejected("Wrong username or password."), step)
    }
    @Test fun parseJsonNullErrorIsNotARejection() {
        // Android org.json optString() returns "null" for JSON null — must not read as an error.
        val step = HaAuth.parseFlowResponse(
            """{"type":"form","flow_id":"f1","step_id":"init","errors":{"base":null}}""")
        assertEquals(HaAuth.FlowStep.NeedCredentials("f1"), step)
    }
    @Test fun parseCreateEntry() {
        val step = HaAuth.parseFlowResponse("""{"type":"create_entry","result":"authcode"}""")
        assertEquals(HaAuth.FlowStep.Done("authcode"), step)
    }
    @Test fun parseGarbageIsMalformed() {
        assertEquals(HaAuth.FlowStep.Malformed, HaAuth.parseFlowResponse("not json"))
        assertEquals(HaAuth.FlowStep.Malformed, HaAuth.parseFlowResponse(null))
    }

    // ---- tokens ----
    @Test fun parseTokensComputesExpiryFromNow() {
        val t = HaAuth.parseTokens(
            """{"access_token":"at","refresh_token":"rt","expires_in":1800}""", nowMs = 1_000L)
        assertEquals("at", t!!.accessToken)
        assertEquals("rt", t.refreshToken)
        assertEquals(1_000L + 1800 * 1000, t.expiresEpochMs)
    }
    @Test fun parseTokensWithoutRefreshKeepsNull() {
        val t = HaAuth.parseTokens("""{"access_token":"at","expires_in":1800}""", 0L)
        assertNull(t!!.refreshToken)
    }
    @Test fun tokensRoundTripThroughStorage() {
        val t = HaAuth.HaTokens("at", "rt", 123_456L)
        assertEquals(t, HaAuth.parseStoredTokens(HaAuth.serializeTokens(t)))
    }
    @Test fun storedTokensGarbageIsNull() {
        assertNull(HaAuth.parseStoredTokens(null))
        assertNull(HaAuth.parseStoredTokens("{}"))
    }
    @Test fun parseTokensExplicitNullRefreshIsNull() {
        // Explicit JSON null (not omitted) — org.json optString() returns "null" string.
        // The != "null" guard must treat this as absent refresh_token.
        val t = HaAuth.parseTokens(
            """{"access_token":"at","refresh_token":null,"expires_in":1800}""", nowMs = 0L)
        assertEquals("at", t!!.accessToken)
        assertNull(t.refreshToken)
        assertEquals(1800 * 1000, t.expiresEpochMs)
    }
    @Test fun parseStoredTokensExplicitNullRefreshIsNull() {
        // Stored token object with explicit null — same guard applies.
        val t = HaAuth.parseStoredTokens(
            """{"access_token":"at","refresh_token":null,"expires":123}""")
        assertEquals("at", t!!.accessToken)
        assertNull(t.refreshToken)
        assertEquals(123L, t.expiresEpochMs)
    }

    // ---- WebView injection ----
    @Test fun hassTokensJsonMatchesFrontendShape() {
        val o = JSONObject(HaAuth.hassTokensJson(origin, HaAuth.HaTokens("at", "rt", 99L)))
        assertEquals("at", o.getString("access_token"))
        assertEquals("rt", o.getString("refresh_token"))
        assertEquals("Bearer", o.getString("token_type"))
        assertEquals(99L, o.getLong("expires"))
        assertEquals(origin, o.getString("hassUrl"))
        assertEquals("http://ha.local:8123/", o.getString("clientId"))
    }
    @Test fun hassTokensJsOnlySetsWhenAbsent() {
        val js = HaAuth.hassTokensJs(origin, HaAuth.HaTokens("at", "rt", 99L))
        // Never clobber tokens the frontend refreshed itself; only seed an empty store.
        assertTrue(js.contains("!localStorage.getItem('hassTokens')"))
        assertTrue(js.startsWith("try{"))
    }
}
