package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAICodexOAuthTest {
    private val expectedState: String = "07".repeat(16)

    private fun accessToken(accountId: String? = "acct-1"): String {
        val payload = if (accountId == null) {
            """{"sub":"user"}"""
        } else {
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        }
        return "e30." + base64UrlEncode(payload.encodeToByteArray()) + ".signature"
    }

    private fun tokenResponse(accountId: String? = "acct-1"): String =
        """{"access_token":"${accessToken(accountId)}","refresh_token":"codex-refresh","expires_in":3600}"""

    private fun deviceAuthResponse(): String =
        """{"device_auth_id":"dev-1","user_code":"UC-1","interval":"1"}"""

    @Test
    fun browserLoginExchangesManualCode() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val interaction = FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("the-code#$expectedState"))
        val credential = withOAuthRuntime(
            randomBytes = deterministicRandom(),
            callbackServerFactory = null,
        ) {
            openAICodexOAuth.login(interaction, transport)
        }

        assertEquals("codex-refresh", credential.refresh)
        assertEquals(accessToken(), credential.access)
        assertEquals("acct-1", credential.openAICodexAccountId())

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://auth.openai.com/oauth/token", request.url)
        val form = request.formBody()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", form["client_id"])
        assertEquals("the-code", form["code"])
        assertEquals(deterministicVerifier(), form["code_verifier"])
        assertEquals("http://localhost:1455/auth/callback", form["redirect_uri"])

        val opened = interaction.openedUrls.single()
        assertTrue(opened.startsWith("https://auth.openai.com/oauth/authorize?"))
        val params = parseUrlEncoded(opened.substringAfter('?'))
        assertEquals("code", params["response_type"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", params["client_id"])
        assertEquals("http://localhost:1455/auth/callback", params["redirect_uri"])
        assertEquals("openid profile email offline_access", params["scope"])
        assertEquals(expectedState, params["state"])
        assertEquals("true", params["id_token_add_organizations"])
        assertEquals("true", params["codex_cli_simplified_flow"])
        assertEquals("pi", params["originator"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(sha256Base64Url(deterministicVerifier()), params["code_challenge"])
    }

    @Test
    fun usesAgentNameAsOriginator() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse()) }
        val interaction = FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("the-code#$expectedState"))
        withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
            openAICodexOAuth.login(interaction, transport, OAuthLoginOptions(agentName = "custom-agent"))
        }
        val params = parseUrlEncoded(interaction.openedUrls.single().substringAfter('?'))
        assertEquals("custom-agent", params["originator"])
    }

    @Test
    fun deviceCodeLoginExchangesCode() = runTest {
        var tokenPolls = 0
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/api/accounts/deviceauth/usercode") -> jsonHttpResponse(deviceAuthResponse())
                request.url.endsWith("/api/accounts/deviceauth/token") -> {
                    tokenPolls += 1
                    if (tokenPolls == 1) {
                        FakeHttpResponse(status = 403, body = "{}")
                    } else {
                        jsonHttpResponse("""{"authorization_code":"device-code","code_verifier":"device-verifier"}""")
                    }
                }

                request.url.endsWith("/oauth/token") -> jsonHttpResponse(tokenResponse())
                else -> FakeHttpResponse(status = 404, body = "unexpected ${request.url}")
            }
        }
        val interaction = FakeOAuthInteraction(selectAnswers = mutableListOf("device_code"))
        val credential = withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
            openAICodexOAuth.login(interaction, transport)
        }
        assertEquals("acct-1", credential.openAICodexAccountId())
        assertEquals(2, tokenPolls)

        val deviceAuthRequest = transport.requests.first()
        assertEquals("POST", deviceAuthRequest.method)
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", deviceAuthRequest.jsonBody().string("client_id"))

        val deviceTokenRequest = transport.requests[1]
        assertEquals("dev-1", deviceTokenRequest.jsonBody().string("device_auth_id"))
        assertEquals("UC-1", deviceTokenRequest.jsonBody().string("user_code"))

        val exchangeForm = transport.requests.last().formBody()
        assertEquals("device-code", exchangeForm["code"])
        assertEquals("device-verifier", exchangeForm["code_verifier"])
        assertEquals("https://auth.openai.com/deviceauth/callback", exchangeForm["redirect_uri"])

        assertEquals("https://auth.openai.com/codex/device", interaction.openedUrls.single())
        assertTrue(interaction.openedInstructions.single()!!.contains("UC-1"))
    }

    @Test
    fun rejectsUnknownLoginMethod() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("other")),
                    FakeHttpTransport { jsonHttpResponse("{}") },
                )
            }
        }
        assertEquals("Unknown OpenAI Codex login method: other", error.message)
    }

    @Test
    fun rejectsStateMismatch() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("the-code#wrong-state")),
                    FakeHttpTransport { jsonHttpResponse(tokenResponse()) },
                )
            }
        }
        assertEquals("State mismatch", error.message)
    }

    @Test
    fun requiresAuthorizationCode() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("")),
                    FakeHttpTransport { jsonHttpResponse(tokenResponse()) },
                )
            }
        }
        assertEquals("Missing authorization code", error.message)
    }

    @Test
    fun failsWhenAccountIdIsMissingFromToken() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("the-code#$expectedState")),
                    FakeHttpTransport { jsonHttpResponse(tokenResponse(accountId = null)) },
                )
            }
        }
        assertEquals("Failed to extract accountId from token", error.message)
    }

    @Test
    fun reportsTokenExchangeErrors() = runTest {
        val transport = FakeHttpTransport { FakeHttpResponse(status = 400, body = "invalid code") }
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("the-code#$expectedState")),
                    transport,
                )
            }
        }
        assertEquals("OpenAI Codex token exchange failed (400): invalid code", error.message)
    }

    @Test
    fun reportsDeviceCodeNotEnabled() = runTest {
        val transport = FakeHttpTransport { request ->
            if (request.url.endsWith("/deviceauth/usercode")) {
                FakeHttpResponse(status = 404, body = "{}")
            } else {
                FakeHttpResponse(status = 200, body = "")
            }
        }
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("device_code")),
                    transport,
                )
            }
        }
        assertEquals(
            "OpenAI Codex device code login is not enabled for this server. Use browser login or verify the server URL.",
            error.message,
        )
    }

    @Test
    fun mapsAbortedExchangeToLoginCancelled() = runTest {
        val signal = RunSignal().apply { abort() }
        val transport = FakeHttpTransport { throw IllegalStateException("transport failed after abort") }
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom(), callbackServerFactory = null) {
                openAICodexOAuth.login(
                    FakeOAuthInteraction(selectAnswers = mutableListOf("browser"), promptAnswers = mutableListOf("the-code#$expectedState"), signal = signal),
                    transport,
                )
            }
        }
        assertEquals(OAUTH_CANCEL_MESSAGE, error.message)
    }

    @Test
    fun refreshExchangesRefreshToken() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse(tokenResponse(accountId = "acct-2")) }
        val credential = openAICodexOAuth.refresh(
            OAuthCredential(refresh = "codex-refresh", access = accessToken(), expires = 1),
            transport,
        )
        assertEquals("acct-2", credential.openAICodexAccountId())
        val form = transport.requests.single().formBody()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("codex-refresh", form["refresh_token"])
        assertEquals("app_EMoamEEZ73f0CkXaXp7hrann", form["client_id"])
    }

    @Test
    fun toAuthUsesAccessToken() = runTest {
        val auth = openAICodexOAuth.toAuth(OAuthCredential(refresh = "r", access = "a", expires = 1))
        assertEquals("a", auth.apiKey)
        assertEquals("OAuth", auth.source)
    }
}
