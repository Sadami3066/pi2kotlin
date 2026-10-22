package dev.pi.ai.auth.oauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAIChatGPTOAuthTest {
    private val deviceId = "123e4567-e89b-12d3-a456-426614174000"

    private fun tokenResponse(
        access: String = "openai-access",
        refresh: String = "openai-refresh",
        scope: String = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
        includesIdToken: Boolean = true,
    ): String {
        val idToken = if (includesIdToken) "\"id_token\":\"id-token\"," else ""
        return """{$idToken"access_token":"$access","refresh_token":"$refresh","scope":"$scope","expires_in":3600}"""
    }

    private fun manualCallbackUrl(state: String, clientId: String = "issued-client"): String =
        "http://127.0.0.1:1455/auth/callback?code=the-code&state=$state&client_id=$clientId"

    @Test
    fun manualLoginExchangesCodeWithIssuedClientId() = runTest {
        val verifier = deterministicVerifier()
        val state = base64UrlEncode(ByteArray(32) { 7 })
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(manualCallbackUrl(state)))
        val options = OAuthLoginOptions(deviceId = deviceId)
        val before = oauthNowMillis()

        val credential = withOAuthRuntime(randomBytes = deterministicRandom()) {
            openaiChatGPTOAuth.login(interaction, transport, options)
        }

        assertEquals("openai-access", credential.access)
        assertEquals("openai-refresh", credential.refresh)
        assertEquals("issued-client", credential.clientId)
        assertEquals(listOf("openid", "profile", "email", "offline_access", "resource.invoke", "chatgpt.tokens.use.direct"), credential.scopes)
        assertTrue(credential.expires >= before + 3600_000L - 3 * 60 * 1000L)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://auth.openai.com/api/accounts/oauth/token", request.url)
        assertTrue(request.header("content-type")!!.startsWith("application/x-www-form-urlencoded"))
        val form = request.formBody()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("issued-client", form["client_id"])
        assertEquals("the-code", form["code"])
        assertEquals(verifier, form["code_verifier"])
        assertEquals("http://127.0.0.1:1455/auth/callback", form["redirect_uri"])
        assertEquals("https://api.openai.com/v1", form["resource"])

        val opened = interaction.openedUrls.single()
        assertTrue(opened.startsWith("https://auth.openai.com/api/accounts/authorize?"))
        val params = parseUrlEncoded(opened.substringAfter('?'))
        assertEquals("dynamic_agent_client", params["client_id"])
        assertEquals("Pi", params["agent_name_hint"])
        assertEquals("urn:uuid:$deviceId", params["ext_agent_host_id"])
        assertEquals(state, params["state"])
        assertEquals(state, params["nonce"])
        assertEquals(sha256Base64Url(verifier), params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("https://api.openai.com/v1", params["resource"])
    }

    @Test
    fun loginRequiresDeviceId() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val interaction = FakeOAuthInteraction()
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                openaiChatGPTOAuth.login(interaction, transport, OAuthLoginOptions())
            }
        }
        assertEquals("Sign in with ChatGPT requires a device ID (UUID) for this installation", error.message)
    }

    @Test
    fun loginRejectsNonUrlManualInput() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("just-a-code"))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                openaiChatGPTOAuth.login(interaction, transport, OAuthLoginOptions(deviceId = deviceId))
            }
        }
        assertEquals("Paste the full callback URL from the browser", error.message)
    }

    @Test
    fun loginRejectsCallbackUrlOnWrongOrigin() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val interaction = FakeOAuthInteraction(
            promptAnswers = mutableListOf("http://localhost:1455/auth/callback?code=a&state=b&client_id=c"),
        )
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                openaiChatGPTOAuth.login(interaction, transport, OAuthLoginOptions(deviceId = deviceId))
            }
        }
        assertEquals(
            "The pasted callback URL must start with http://127.0.0.1:1455/auth/callback",
            error.message,
        )
    }

    @Test
    fun loginRequiresIdToken() = runTest {
        val state = base64UrlEncode(ByteArray(32) { 7 })
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse(includesIdToken = false)) }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(manualCallbackUrl(state)))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                openaiChatGPTOAuth.login(interaction, transport, OAuthLoginOptions(deviceId = deviceId))
            }
        }
        assertEquals("OpenAI OAuth token response did not contain an ID token", error.message)
    }

    @Test
    fun loginRequiresDirectTokenScope() = runTest {
        val state = base64UrlEncode(ByteArray(32) { 7 })
        val transport = FakeHttpTransport {
            jsonHttpResponse(tokenResponse(scope = "openid profile email offline_access resource.invoke"))
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(manualCallbackUrl(state)))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                openaiChatGPTOAuth.login(interaction, transport, OAuthLoginOptions(deviceId = deviceId))
            }
        }
        assertEquals("OpenAI OAuth grant did not include chatgpt.tokens.use.direct", error.message)
    }

    @Test
    fun refreshUsesStoredClientId() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse(access = "openai-access-2")) }
        val credential = openaiChatGPTOAuth.refresh(
            OAuthCredential(
                refresh = "openai-refresh",
                access = "openai-access",
                expires = 1,
                clientId = "issued-client",
            ),
            transport,
        )
        assertEquals("openai-access-2", credential.access)
        assertEquals("issued-client", credential.clientId)
        val form = transport.requests.single().formBody()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("issued-client", form["client_id"])
        assertEquals("openai-refresh", form["refresh_token"])
        assertEquals("https://api.openai.com/v1", form["resource"])
    }

    @Test
    fun refreshRequiresStoredClientId() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val error = assertFailsWith<IllegalStateException> {
            openaiChatGPTOAuth.refresh(
                OAuthCredential(refresh = "r", access = "a", expires = 1),
                transport,
            )
        }
        assertEquals(
            "Stored OpenAI OAuth credential does not contain an issued client ID; reconnect ChatGPT",
            error.message,
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun tokenRequestErrorsAreReported() = runTest {
        val state = base64UrlEncode(ByteArray(32) { 7 })
        val transport = FakeHttpTransport {
            FakeHttpResponse(status = 401, body = "invalid_client")
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(manualCallbackUrl(state)))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                openaiChatGPTOAuth.login(interaction, transport, OAuthLoginOptions(deviceId = deviceId))
            }
        }
        assertEquals("OpenAI OAuth token request failed (401): invalid_client", error.message)
    }
}
