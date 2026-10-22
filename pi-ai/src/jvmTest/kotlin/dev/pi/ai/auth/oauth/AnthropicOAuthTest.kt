package dev.pi.ai.auth.oauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicOAuthTest {
    @Test
    fun copyCodeLoginExchangesAuthorizationCode() = runTest {
        val verifier = deterministicVerifier()
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"access-1","refresh_token":"refresh-1","expires_in":3600}""")
        }
        val interaction = FakeOAuthInteraction(
            selectAnswers = mutableListOf("copy_code"),
            promptAnswers = mutableListOf("the-code#$verifier"),
        )
        val before = oauthNowMillis()
        val credential = withOAuthRuntime(randomBytes = deterministicRandom()) {
            anthropicOAuth.login(interaction, transport)
        }

        assertEquals("access-1", credential.access)
        assertEquals("refresh-1", credential.refresh)
        assertTrue(credential.expires >= before + 3600_000L - 5 * 60 * 1000L)
        assertTrue(credential.expires <= oauthNowMillis() + 3600_000L - 5 * 60 * 1000L)

        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://platform.claude.com/v1/oauth/token", request.url)
        assertTrue(request.header("content-type")!!.startsWith("application/json"))
        val body = request.jsonBody()
        assertEquals("authorization_code", body.string("grant_type"))
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", body.string("client_id"))
        assertEquals("the-code", body.string("code"))
        assertEquals(verifier, body.string("state"))
        assertEquals(verifier, body.string("code_verifier"))
        assertEquals("https://platform.claude.com/oauth/code/callback", body.string("redirect_uri"))

        val opened = interaction.openedUrls.single()
        assertTrue(opened.startsWith("https://claude.ai/oauth/authorize?"))
        val params = parseUrlEncoded(opened.substringAfter('?'))
        assertEquals("S256", params["code_challenge_method"])
        assertEquals(verifier, params["state"])
        assertEquals(
            "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
            params["scope"],
        )
        assertEquals(sha256Base64Url(verifier), params["code_challenge"])

        val auth = anthropicOAuth.toAuth(credential)
        assertEquals("access-1", auth.apiKey)
        assertEquals("OAuth", auth.source)
    }

    @Test
    fun browserLoginFallsBackToManualInputWithoutServer() = runTest {
        val verifier = deterministicVerifier()
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":60}""")
        }
        val interaction = FakeOAuthInteraction(
            selectAnswers = mutableListOf("browser"),
            promptAnswers = mutableListOf("the-code#$verifier"),
        )
        val credential = withOAuthRuntime(callbackServerFactory = null, randomBytes = deterministicRandom()) {
            anthropicOAuth.login(interaction, transport)
        }
        assertEquals("access-2", credential.access)
        val body = transport.requests.single().jsonBody()
        assertEquals("http://localhost:53692/callback", body.string("redirect_uri"))
        assertEquals(verifier, body.string("state"))
    }

    @Test
    fun browserLoginAcceptsPastedRedirectUrl() = runTest {
        val verifier = deterministicVerifier()
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"access-3","refresh_token":"refresh-3","expires_in":60}""")
        }
        val interaction = FakeOAuthInteraction(
            selectAnswers = mutableListOf("browser"),
            promptAnswers = mutableListOf("https://platform.claude.com/oauth/code/callback?code=pasted&state=$verifier"),
        )
        withOAuthRuntime(randomBytes = deterministicRandom()) {
            anthropicOAuth.login(interaction, transport)
        }
        val body = transport.requests.single().jsonBody()
        assertEquals("pasted", body.string("code"))
        assertEquals(verifier, body.string("state"))
    }

    @Test
    fun browserLoginRejectsStateMismatch() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"access","refresh_token":"refresh","expires_in":60}""")
        }
        val interaction = FakeOAuthInteraction(
            selectAnswers = mutableListOf("browser"),
            promptAnswers = mutableListOf("the-code#wrong-state"),
        )
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                anthropicOAuth.login(interaction, transport)
            }
        }
        assertEquals("OAuth state mismatch", error.message)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun browserLoginRequiresAuthorizationCode() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"access","refresh_token":"refresh","expires_in":60}""")
        }
        val interaction = FakeOAuthInteraction(
            selectAnswers = mutableListOf("browser"),
            promptAnswers = mutableListOf(""),
        )
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                anthropicOAuth.login(interaction, transport)
            }
        }
        assertEquals("Missing authorization code", error.message)
    }

    @Test
    fun refreshExchangesRefreshToken() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"access_token":"access-new","refresh_token":"refresh-new","expires_in":1800}""")
        }
        val credential = anthropicOAuth.refresh(
            OAuthCredential(refresh = "refresh-old", access = "access-old", expires = 1),
            transport,
        )
        assertEquals("access-new", credential.access)
        assertEquals("refresh-new", credential.refresh)
        val body = transport.requests.single().jsonBody()
        assertEquals("refresh_token", body.string("grant_type"))
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", body.string("client_id"))
        assertEquals("refresh-old", body.string("refresh_token"))
    }

    @Test
    fun tokenEndpointErrorsAreReported() = runTest {
        val transport = FakeHttpTransport {
            FakeHttpResponse(status = 400, body = """{"error":"invalid_grant"}""")
        }
        val error = assertFailsWith<IllegalStateException> {
            anthropicOAuth.refresh(
                OAuthCredential(refresh = "refresh-old", access = "access-old", expires = 1),
                transport,
            )
        }
        assertTrue(error.message!!.contains("HTTP request failed. status=400"))
        assertTrue(error.message!!.contains("https://platform.claude.com/v1/oauth/token"))
    }

    @Test
    fun rejectsUnknownLoginMethod() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("{}") }
        val interaction = FakeOAuthInteraction(selectAnswers = mutableListOf("unknown"))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(randomBytes = deterministicRandom()) {
                anthropicOAuth.login(interaction, transport)
            }
        }
        assertEquals("Unknown Anthropic login method: unknown", error.message)
    }
}
