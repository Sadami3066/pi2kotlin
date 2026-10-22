package dev.pi.ai.auth.oauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenRouterOAuthTest {
    @Test
    fun manualLoginExchangesCodeForKey() = runTest {
        val verifier = deterministicVerifier()
        val transport = FakeHttpTransport {
            jsonHttpResponse("""{"key":"sk-openrouter"}""")
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("the-code"))
        val credential = withOAuthRuntime(
            randomBytes = deterministicRandom(),
            callbackServerFactory = SocketOAuthCallbackServerFactory(),
        ) {
            openRouterOAuth.login(interaction, transport)
        }

        assertEquals("sk-openrouter", credential.access)
        assertEquals("", credential.refresh)
        assertEquals(Long.MAX_VALUE, credential.expires)

        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://openrouter.ai/api/v1/auth/keys", request.url)
        assertTrue(request.header("content-type")!!.startsWith("application/json"))
        val body = request.jsonBody()
        assertEquals("the-code", body.string("code"))
        assertEquals(verifier, body.string("code_verifier"))
        assertEquals("S256", body.string("code_challenge_method"))

        val opened = interaction.openedUrls.single()
        assertTrue(opened.startsWith("https://openrouter.ai/auth?"))
        val params = parseUrlEncoded(opened.substringAfter('?'))
        assertEquals(sha256Base64Url(verifier), params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertTrue(params["callback_url"]!!.startsWith("http://127.0.0.1:"))
        assertTrue(params["callback_url"]!!.contains("/oauth/callback/"))
        assertTrue(interaction.messages.any { it.contains("Listening for OpenRouter OAuth callback on") })
    }

    @Test
    fun manualLoginAcceptsPastedRedirectUrl() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"key":"sk-openrouter"}""") }
        val interaction = FakeOAuthInteraction(
            promptAnswers = mutableListOf("http://127.0.0.1:1234/oauth/callback/x?code=pasted-code"),
        )
        withOAuthRuntime(
            randomBytes = deterministicRandom(),
            callbackServerFactory = SocketOAuthCallbackServerFactory(),
        ) {
            openRouterOAuth.login(interaction, transport)
        }
        assertEquals("pasted-code", transport.requests.single().jsonBody().string("code"))
    }

    @Test
    fun requiresAuthorizationCode() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"key":"sk"}""") }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf(""))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(
                randomBytes = deterministicRandom(),
                callbackServerFactory = SocketOAuthCallbackServerFactory(),
            ) {
                openRouterOAuth.login(interaction, transport)
            }
        }
        assertEquals("Missing authorization code", error.message)
    }

    @Test
    fun reportsExchangeFailures() = runTest {
        val transport = FakeHttpTransport {
            jsonHttpResponse(
                """{"error":{"message":"invalid code verifier"},"error_description":"nope"}""",
                status = 400,
            )
        }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("the-code"))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(
                randomBytes = deterministicRandom(),
                callbackServerFactory = SocketOAuthCallbackServerFactory(),
            ) {
                openRouterOAuth.login(interaction, transport)
            }
        }
        assertEquals("OpenRouter OAuth key exchange failed (HTTP 400): nope", error.message)
    }

    @Test
    fun requiresKeyField() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"data":{}}""") }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("the-code"))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(
                randomBytes = deterministicRandom(),
                callbackServerFactory = SocketOAuthCallbackServerFactory(),
            ) {
                openRouterOAuth.login(interaction, transport)
            }
        }
        assertEquals("OpenRouter OAuth response carries no \"key\"", error.message)
    }

    @Test
    fun reportsInvalidJsonOnSuccess() = runTest {
        val transport = FakeHttpTransport { FakeHttpResponse(status = 200, body = "not-json") }
        val interaction = FakeOAuthInteraction(promptAnswers = mutableListOf("the-code"))
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(
                randomBytes = deterministicRandom(),
                callbackServerFactory = SocketOAuthCallbackServerFactory(),
            ) {
                openRouterOAuth.login(interaction, transport)
            }
        }
        assertEquals("OpenRouter OAuth returned invalid JSON", error.message)
    }

    @Test
    fun requiresCallbackServer() = runTest {
        val transport = FakeHttpTransport { jsonHttpResponse("""{"key":"sk"}""") }
        val error = assertFailsWith<IllegalStateException> {
            withOAuthRuntime(callbackServerFactory = null) {
                openRouterOAuth.login(FakeOAuthInteraction(), transport)
            }
        }
        assertEquals("OAuth callback server is not available on this platform", error.message)
    }

    @Test
    fun refreshReturnsStoredCredential() = runTest {
        val credential = OAuthCredential(refresh = "", access = "sk-openrouter", expires = Long.MAX_VALUE)
        val refreshed = openRouterOAuth.refresh(credential, FakeHttpTransport { error("no network") })
        assertEquals(credential, refreshed)
    }

    @Test
    fun toAuthUsesApiKey() = runTest {
        val auth = openRouterOAuth.toAuth(OAuthCredential(refresh = "", access = "sk-openrouter", expires = 1))
        assertEquals("sk-openrouter", auth.apiKey)
        assertEquals("OAuth", auth.source)
    }
}
