package dev.pi.ai.auth.oauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XaiOAuthTest {
    private fun deviceCodeResponse(verificationUri: String = "https://x.ai/device"): String =
        """{"device_code":"dev-code","user_code":"USER-1","verification_uri":"$verificationUri","interval":1,"expires_in":60}"""

    @Test
    fun deviceFlowLoginPollsUntilToken() = runTest {
        var tokenPolls = 0
        val transport = FakeHttpTransport { request ->
            when {
                request.url == "https://auth.x.ai/oauth2/device/code" -> jsonHttpResponse(deviceCodeResponse())
                request.url == "https://auth.x.ai/oauth2/token" -> {
                    tokenPolls += 1
                    if (tokenPolls == 1) {
                        jsonHttpResponse("""{"error":"authorization_pending"}""", status = 400)
                    } else {
                        jsonHttpResponse(
                            """{"access_token":"xai-access","refresh_token":"xai-refresh","expires_in":3600}""",
                        )
                    }
                }

                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction()
        val before = oauthNowMillis()
        val credential = xaiOAuth.login(interaction, transport)

        assertEquals("xai-access", credential.access)
        assertEquals("xai-refresh", credential.refresh)
        assertTrue(credential.expires >= before + 3_600_000L - 300_000L)
        assertTrue(credential.expires <= oauthNowMillis() + 3_600_000L - 300_000L)
        assertEquals(2, tokenPolls)

        val deviceRequest = transport.requests.first()
        assertEquals("POST", deviceRequest.method)
        assertTrue(deviceRequest.header("content-type")!!.startsWith("application/x-www-form-urlencoded"))
        val deviceForm = deviceRequest.formBody()
        assertEquals("b1a00492-073a-47ea-816f-4c329264a828", deviceForm["client_id"])
        assertEquals("openid profile email offline_access grok-cli:access api:access", deviceForm["scope"])
        assertEquals("pi", deviceForm["referrer"])

        val tokenRequest = transport.requests.last()
        val tokenForm = tokenRequest.formBody()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", tokenForm["grant_type"])
        assertEquals("dev-code", tokenForm["device_code"])
        assertEquals("b1a00492-073a-47ea-816f-4c329264a828", tokenForm["client_id"])

        assertEquals("https://x.ai/device", interaction.openedUrls.single())
        assertTrue(interaction.openedInstructions.single()!!.contains("USER-1"))
        assertTrue(interaction.messages.any { it.contains("Waiting for xAI") })
    }

    @Test
    fun loginPrefersCompleteVerificationUri() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse(
                """{"device_code":"dev-code","user_code":"USER-1","verification_uri":"https://x.ai/device","verification_uri_complete":"https://x.ai/device?code=USER-1","expires_in":60}""",
            )
        }
        val interaction = FakeOAuthInteraction()
        runCatching { xaiOAuth.login(interaction, transport) }
        assertEquals("https://x.ai/device?code=USER-1", interaction.openedUrls.single())
    }

    @Test
    fun reportsDeviceAuthorizationFailure() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"error":"invalid_client","error_description":"bad app"}""", status = 401)
        }
        val error = assertFailsWith<IllegalStateException> {
            xaiOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertEquals("xAI OAuth device authorization failed (HTTP 401): invalid_client: bad app", error.message)
    }

    @Test
    fun rejectsUntrustedVerificationUri() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse(deviceCodeResponse(verificationUri = "http://x.ai/device"))
        }
        val error = assertFailsWith<IllegalStateException> {
            xaiOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertEquals("Untrusted verification URI in xAI OAuth response", error.message)
    }

    @Test
    fun reportsDeniedAndExpiredDeviceCodes() = runTest {
        var tokenPolls = 0
        fun transportFor(error: String): FakeHttpTransport = FakeHttpTransport { request ->
            when (request.url) {
                "https://auth.x.ai/oauth2/device/code" -> jsonHttpResponse(deviceCodeResponse())
                else -> {
                    tokenPolls += 1
                    jsonHttpResponse("""{"error":"$error"}""", status = 400)
                }
            }
        }
        val denied = assertFailsWith<IllegalStateException> {
            xaiOAuth.login(FakeOAuthInteraction(), transportFor("access_denied"))
        }
        assertEquals("xAI device authorization was denied", denied.message)
        val expired = assertFailsWith<IllegalStateException> {
            xaiOAuth.login(FakeOAuthInteraction(), transportFor("expired_token"))
        }
        assertEquals("xAI device code expired", expired.message)
        assertTrue(tokenPolls >= 2)
    }

    @Test
    fun refreshKeepsPreviousRefreshTokenWhenOmitted() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"xai-access-2","expires_in":1800}""")
        }
        val credential = xaiOAuth.refresh(
            OAuthCredential(refresh = "xai-refresh", access = "xai-access", expires = 1),
            transport,
        )
        assertEquals("xai-access-2", credential.access)
        assertEquals("xai-refresh", credential.refresh)
        val form = transport.requests.single().formBody()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("xai-refresh", form["refresh_token"])
    }

    @Test
    fun reportsRefreshFailures() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"error":"invalid_grant","error_description":"revoked"}""", status = 400)
        }
        val error = assertFailsWith<IllegalStateException> {
            xaiOAuth.refresh(OAuthCredential(refresh = "r", access = "a", expires = 1), transport)
        }
        assertEquals("xAI OAuth token refresh failed (HTTP 400): invalid_grant: revoked", error.message)
    }

    @Test
    fun reportsInvalidJson() = runTest {
        val transport = FakeHttpTransport { FakeHttpResponse(status = 200, body = "not-json") }
        val error = assertFailsWith<IllegalStateException> {
            xaiOAuth.refresh(OAuthCredential(refresh = "r", access = "a", expires = 1), transport)
        }
        assertEquals("xAI OAuth returned invalid JSON (HTTP 200)", error.message)
    }

    @Test
    fun mapsAbortedRequestsToLoginCancelled() = runTest {
        val signal = dev.pi.ai.RunSignal().apply { abort() }
        val transport = FakeHttpTransport {
            throw IllegalStateException("transport failed after abort")
        }
        val interaction = FakeOAuthInteraction(signal = signal)
        val error = assertFailsWith<IllegalStateException> {
            xaiOAuth.login(interaction, transport)
        }
        assertEquals(OAUTH_CANCEL_MESSAGE, error.message)
    }

    @Test
    fun toAuthUsesAccessToken() = runTest {
        val auth = xaiOAuth.toAuth(OAuthCredential(refresh = "r", access = "xai-access", expires = 1))
        assertEquals("xai-access", auth.apiKey)
        assertEquals("OAuth", auth.source)
    }
}
