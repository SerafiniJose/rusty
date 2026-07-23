package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaAuthClientTest {

    private val origin = "http://ha.local:8123"

    /** Scripted fake: answers by URL suffix; records requests for assertions. */
    private class FakeHttp(private val responses: MutableMap<String, MutableList<HaHttpResponse>>) : HaHttp {
        val requests = mutableListOf<Triple<String, String, String?>>() // method, url, body
        override fun request(method: String, url: String, contentType: String?, bearer: String?, body: String?): HaHttpResponse {
            requests += Triple(method, url, body)
            val key = responses.keys.first { url.endsWith(it) || url.contains(it) }
            return responses.getValue(key).removeAt(0)
        }
    }

    private fun okFlowStart(flowId: String = "f1") =
        HaHttpResponse(200, """{"type":"form","flow_id":"$flowId","step_id":"init","errors":{}}""")

    @Test fun signInHappyPath() {
        val http = FakeHttp(mutableMapOf(
            "/auth/login_flow/f1" to mutableListOf(
                HaHttpResponse(200, """{"type":"create_entry","result":"code1"}""")),
            "/auth/login_flow" to mutableListOf(okFlowStart()),
            "/auth/token" to mutableListOf(
                HaHttpResponse(200, """{"access_token":"at","refresh_token":"rt","expires_in":1800}""")),
        ))
        val r = HaAuthClient(http).signIn(origin, "jose", "pw", nowMs = 0L)
        val s = r as HaAuthClient.SignIn.Success
        assertEquals("rt", s.tokens.refreshToken)
        assertEquals("at", s.tokens.accessToken)
        // Password went only into the flow-step body, never the token exchange.
        assertTrue(http.requests.first { it.second.endsWith("/f1") }.third!!.contains("pw"))
        assertTrue(http.requests.last { it.second.endsWith("/auth/token") }.third!!.startsWith("grant_type=authorization_code"))
    }

    @Test fun signInWrongPassword() {
        val http = FakeHttp(mutableMapOf(
            "/auth/login_flow/f1" to mutableListOf(HaHttpResponse(200,
                """{"type":"form","flow_id":"f1","step_id":"init","errors":{"base":"invalid_auth"}}""")),
            "/auth/login_flow" to mutableListOf(okFlowStart()),
        ))
        val r = HaAuthClient(http).signIn(origin, "jose", "wrong", 0L)
        assertEquals(HaAuthClient.SignIn.Failed("Wrong username or password."), r)
    }

    @Test fun signInSurfacesMfa() {
        val http = FakeHttp(mutableMapOf(
            "/auth/login_flow/f1" to mutableListOf(HaHttpResponse(200,
                """{"type":"form","flow_id":"f1","step_id":"mfa","errors":{}}""")),
            "/auth/login_flow" to mutableListOf(okFlowStart()),
        ))
        val r = HaAuthClient(http).signIn(origin, "jose", "pw", 0L)
        assertEquals(HaAuthClient.SignIn.MfaRequired("f1"), r)
    }

    @Test fun completeMfaExchangesCode() {
        val http = FakeHttp(mutableMapOf(
            "/auth/login_flow/f1" to mutableListOf(
                HaHttpResponse(200, """{"type":"create_entry","result":"code1"}""")),
            "/auth/token" to mutableListOf(
                HaHttpResponse(200, """{"access_token":"at","refresh_token":"rt","expires_in":1800}""")),
        ))
        val r = HaAuthClient(http).completeMfa(origin, "f1", "123456", 0L)
        assertTrue(r is HaAuthClient.SignIn.Success)
    }

    @Test fun signInUnreachableServer() {
        val http = FakeHttp(mutableMapOf("/auth/login_flow" to mutableListOf(HaHttpResponse(-1, null))))
        val r = HaAuthClient(http).signIn(origin, "jose", "pw", 0L)
        assertEquals(HaAuthClient.SignIn.Failed("Couldn't reach the server — check the address."), r)
    }

    @Test fun refreshKeepsRefreshToken() {
        val http = FakeHttp(mutableMapOf("/auth/token" to mutableListOf(
            HaHttpResponse(200, """{"access_token":"at2","expires_in":1800}"""))))
        val t = HaAuthClient(http).refresh(origin, "rt", 1_000L)
        assertEquals("rt", t!!.refreshToken)
        assertEquals("at2", t.accessToken)
    }

    @Test fun refreshRevokedTokenIsNull() {
        val http = FakeHttp(mutableMapOf("/auth/token" to mutableListOf(HaHttpResponse(400, "{}"))))
        assertNull(HaAuthClient(http).refresh(origin, "rt", 0L))
    }

    @Test fun testHappyPath() {
        val http = FakeHttp(mutableMapOf(
            "/auth/token" to mutableListOf(
                HaHttpResponse(200, """{"access_token":"at2","expires_in":1800}""")),
            "/api/" to mutableListOf(HaHttpResponse(200, """{"message":"API running."}""")),
        ))
        val r = HaAuthClient(http).test(origin, "rt", 0L)
        assertTrue(r is HaAuthClient.TestResult.Ok)
    }

    @Test fun testExpiredSession() {
        val http = FakeHttp(mutableMapOf("/auth/token" to mutableListOf(HaHttpResponse(400, "{}"))))
        val r = HaAuthClient(http).test(origin, "rt", 0L)
        assertEquals(
            HaAuthClient.TestResult.Failed("Sign-in expired or was revoked — sign in again."), r)
    }
}
