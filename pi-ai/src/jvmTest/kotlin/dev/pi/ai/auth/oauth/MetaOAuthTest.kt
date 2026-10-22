package dev.pi.ai.auth.oauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetaOAuthTest {
    private fun deviceAuthResponse(): String =
        """{"device_code":"meta-device","user_code":"META-CODE","verification_uri":"https://auth.meta.com/device","interval":1,"expires_in":60}"""

    @Test
    fun deviceFlowLoginPollsIdentityThenMintsApiKey() = runTest {
        var tokenPolls = 0
        val transport = FakeHttpTransport { request ->
            when {
                request.url == "https://auth.meta.com/oidc/device/authorization/" -> jsonHttpResponse(deviceAuthResponse())
                request.url == "https://auth.meta.com/oidc/device/token/" -> {
                    tokenPolls += 1
                    if (tokenPolls == 1) {
                        jsonHttpResponse("""{"error":"authorization_pending"}""")
                    } else {
                        jsonHttpResponse("""{"access_token":"identity-token"}""")
                    }
                }

                request.url == "https://api.meta.ai/muse-code/key" -> jsonHttpResponse("""{"api_key":"meta-api-key"}""")
                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction()
        val before = oauthNowMillis()
        val credential = metaOAuth.login(interaction, transport)

        assertEquals("identity-token", credential.refresh)
        assertEquals("meta-api-key", credential.access)
        assertTrue(credential.expires >= before + 24 * 60 * 60 * 1000L)
        assertEquals(2, tokenPolls)

        val deviceForm = transport.requests.first().formBody()
        assertEquals("1031625952748946", deviceForm["client_id"])

        val tokenForm = transport.requests.filter { it.url.contains("/device/token/") }.last().formBody()
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", tokenForm["grant_type"])
        assertEquals("meta-device", tokenForm["device_code"])
        assertEquals("1031625952748946", tokenForm["client_id"])

        val mintRequest = transport.requests.last()
        assertEquals("Bearer identity-token", mintRequest.header("authorization"))
        assertEquals("1.0.0", mintRequest.header("x-api-version"))
        assertTrue(mintRequest.header("content-type")!!.startsWith("application/json"))
        assertEquals("{}", mintRequest.bodyText())

        assertEquals("https://auth.meta.com/device", interaction.openedUrls.single())
        assertTrue(interaction.openedInstructions.single()!!.contains("META-CODE"))
        assertTrue(interaction.messages.any { it.contains("Enabling Meta Model API access") })
    }

    @Test
    fun reportsDeviceAuthorizationFailures() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"error_description":"bad client"}""", status = 400)
        }
        val error = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertEquals("Meta device authorization failed with status 400: bad client", error.message)
    }

    @Test
    fun rejectsInvalidDeviceAuthorizationResponse() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"device_code":"only"}""") }
        val error = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertTrue(error.message!!.startsWith("Invalid Meta device authorization response:"))
    }

    @Test
    fun reportsDeniedAndExpiredDeviceCodes() = runTest {
        fun transportFor(error: String): FakeHttpTransport = FakeHttpTransport { request ->
            when (request.url) {
                "https://auth.meta.com/oidc/device/authorization/" -> jsonHttpResponse(deviceAuthResponse())
                else -> jsonHttpResponse("""{"error":"$error"}""")
            }
        }
        val denied = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(), transportFor("access_denied"))
        }
        assertEquals("Meta login was denied.", denied.message)
        val expired = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(), transportFor("expired_token"))
        }
        assertEquals("Meta device authorization expired. Please restart login.", expired.message)
    }

    @Test
    fun mapsAbortToLoginCancelled() = runTest {
        val signal = dev.pi.ai.RunSignal().apply { abort() }
        val transport = FakeHttpTransport { throw IllegalStateException("transport failed after abort") }
        val error = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(signal = signal), transport)
        }
        assertEquals(OAUTH_CANCEL_MESSAGE, error.message)
    }

    @Test
    fun reportsExpiredSessionOnMint() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/device/authorization/") -> jsonHttpResponse(deviceAuthResponse())
                request.url.endsWith("/device/token/") -> jsonHttpResponse("""{"access_token":"identity"}""")
                else -> jsonHttpResponse("""{"detail":"nothing"}""", status = 401)
            }
        }
        val error = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertEquals(
            "Meta session expired (status 401). Run `/login meta` to sign in again.: nothing",
            error.message,
        )
    }

    @Test
    fun reportsMissingApiKeyWithActionUrl() = runTest {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/device/authorization/") -> jsonHttpResponse(deviceAuthResponse())
                request.url.endsWith("/device/token/") -> jsonHttpResponse("""{"access_token":"identity"}""")
                else -> jsonHttpResponse("""{"action_url":"https://api.meta.ai/setup"}""")
            }
        }
        val error = assertFailsWith<IllegalStateException> {
            metaOAuth.login(FakeOAuthInteraction(), transport)
        }
        assertEquals("Meta did not issue an API key. Complete setup at https://api.meta.ai/setup", error.message)
    }

    @Test
    fun refreshMintsNewApiKeyFromIdentityToken() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"api_key":"meta-api-key-2"}""") }
        val credential = metaOAuth.refresh(
            OAuthCredential(refresh = "identity-token", access = "old", expires = 1),
            transport,
        )
        assertEquals("meta-api-key-2", credential.access)
        assertEquals("identity-token", credential.refresh)
        assertEquals("Bearer identity-token", transport.requests.single().header("authorization"))
    }

    @Test
    fun toAuthUsesMintedKey() = runTest {
        val auth = metaOAuth.toAuth(OAuthCredential(refresh = "identity", access = "meta-api-key", expires = 1))
        assertEquals("meta-api-key", auth.apiKey)
        assertEquals("OAuth", auth.source)
    }
}
