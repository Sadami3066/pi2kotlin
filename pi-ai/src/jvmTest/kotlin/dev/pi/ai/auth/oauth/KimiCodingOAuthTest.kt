package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.AuthContext
import dev.pi.ai.auth.AuthEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KimiCodingOAuthTest {
    private fun deviceAuthorizationResponse(interval: Int = 1): String =
        """{"device_code":"kimi-device","user_code":"KIMI-CODE","verification_uri":"https://auth.kimi.com/device","verification_uri_complete":"https://auth.kimi.com/device?code=KIMI-CODE","interval":$interval,"expires_in":60}"""

    @Test
    fun deviceFlowLoginPollsUntilToken() = runTest {
        var tokenPolls = 0
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/api/oauth/device_authorization") -> jsonHttpResponse(deviceAuthorizationResponse())
                request.url.endsWith("/api/oauth/token") -> {
                    tokenPolls += 1
                    if (tokenPolls == 1) {
                        jsonHttpResponse("""{"error":"authorization_pending"}""")
                    } else {
                        jsonHttpResponse(
                            """{"access_token":"kimi-access","refresh_token":"kimi-refresh","expires_in":3600}""",
                        )
                    }
                }

                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction()
        val credential = kimiCodingOAuth.login(interaction, transport)

        assertEquals("kimi-access", credential.access)
        assertEquals("kimi-refresh", credential.refresh)
        assertTrue(credential.expires > oauthNowMillis())
        assertEquals(2, tokenPolls)

        val deviceRequest = transport.requests.first()
        assertEquals("https://auth.kimi.com/api/oauth/device_authorization", deviceRequest.url)
        val deviceForm = deviceRequest.formBody()
        assertEquals("17e5f671-d194-4dfb-9706-5516cb48c098", deviceForm["client_id"])

        val tokenForm = transport.requests.filter { it.url.endsWith("/api/oauth/token") }.last().formBody()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", tokenForm["grant_type"])
        assertEquals("kimi-device", tokenForm["device_code"])

        assertEquals("https://auth.kimi.com/device?code=KIMI-CODE", interaction.openedUrls.single())
        assertTrue(interaction.openedInstructions.single()!!.contains("KIMI-CODE"))
    }

    @Test
    fun usesOauthHostOverride() = runTest {
        val previous = AuthEnvironment.context
        AuthEnvironment.context = AuthContext { name ->
            if (name == "KIMI_CODE_OAUTH_HOST") "https://auth.example.test/" else null
        }
        try {
            val transport = FakeHttpTransport { request ->
                if (request.url.startsWith("https://auth.example.test/")) {
                    jsonHttpResponse(deviceAuthorizationResponse())
                } else {
                    FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
                }
            }
            runCatching { kimiCodingOAuth.login(FakeOAuthInteraction(), transport) }
            assertTrue(transport.requests.first().url == "https://auth.example.test/api/oauth/device_authorization")
        } finally {
            AuthEnvironment.context = previous
        }
    }

    @Test
    fun reportsDeviceAuthorizationFailures() = runTest {
        val transport = FakeHttpTransport { FakeHttpResponse(status = 500, body = "server error") }
        val error = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertEquals("Kimi Code device authorization failed with status 500: server error", error.message)
    }

    @Test
    fun rejectsInvalidDeviceAuthorizationResponse() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"device_code":"only"}""") }
        val error = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertTrue(error.message!!.startsWith("Invalid Kimi Code device authorization response:"))
    }

    @Test
    fun reportsDeviceTokenErrors() = runTest {
        fun transportFor(error: String): FakeHttpTransport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/api/oauth/device_authorization") -> jsonHttpResponse(deviceAuthorizationResponse())
                else -> jsonHttpResponse("""{"error":"$error"}""")
            }
        }
        val expired = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.login(FakeOAuthInteraction(), transportFor("expired_token"))
        }
        assertEquals("Kimi Code device authorization expired. Please restart login.", expired.message)
        val denied = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.login(FakeOAuthInteraction(), transportFor("access_denied"))
        }
        assertEquals("Kimi Code login was denied.", denied.message)
        val failed = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.login(FakeOAuthInteraction(), transportFor("invalid_request"))
        }
        assertEquals("Kimi Code device token request failed (status 200): invalid_request", failed.message)
    }

    @Test
    fun refreshExchangesRefreshToken() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"kimi-access-2","refresh_token":"kimi-refresh-2","expires_in":1800}""")
        }
        val credential = kimiCodingOAuth.refresh(
            OAuthCredential(refresh = "kimi-refresh", access = "kimi-access", expires = 1),
            transport,
        )
        assertEquals("kimi-access-2", credential.access)
        assertEquals("kimi-refresh-2", credential.refresh)
        val form = transport.requests.single().formBody()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("kimi-refresh", form["refresh_token"])
    }

    @Test
    fun refreshRetriesRetryableFailures() = runTest {
        var attempts = 0
        val transport = FakeHttpTransport {
            attempts += 1
            if (attempts == 1) {
                FakeHttpResponse(status = 503, body = "unavailable")
            } else {
                jsonHttpResponse("""{"access_token":"kimi-access-2","refresh_token":"kimi-refresh-2","expires_in":1800}""")
            }
        }
        val credential = kimiCodingOAuth.refresh(
            OAuthCredential(refresh = "kimi-refresh", access = "kimi-access", expires = 1),
            transport,
        )
        assertEquals("kimi-access-2", credential.access)
        assertEquals(2, attempts)
    }

    @Test
    fun reportsRefreshUnauthorized() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"error":"invalid_grant","error_description":"revoked"}""", status = 401)
        }
        val error = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.refresh(OAuthCredential(refresh = "r", access = "a", expires = 1), transport)
        }
        assertEquals("Kimi Code token refresh unauthorized (status 401): revoked", error.message)
    }

    @Test
    fun refreshAbortedBeforeRequest() = runTest {
        val signal = RunSignal().apply { abort() }
        val error = assertFailsWith<IllegalStateException> {
            kimiCodingOAuth.refresh(
                OAuthCredential(refresh = "r", access = "a", expires = 1),
                FakeHttpTransport { error("no request expected") },
                signal,
            )
        }
        assertEquals("Kimi Code token refresh aborted", error.message)
    }

    @Test
    fun toAuthUsesBearerHeader() = runTest {
        val auth = kimiCodingOAuth.toAuth(OAuthCredential(refresh = "r", access = "kimi-access", expires = 1))
        assertEquals(null, auth.apiKey)
        assertEquals("Bearer kimi-access", auth.headers["Authorization"])
        assertEquals("OAuth", auth.source)
    }
}
