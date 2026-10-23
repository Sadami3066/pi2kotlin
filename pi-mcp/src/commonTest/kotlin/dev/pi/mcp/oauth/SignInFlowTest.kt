package dev.pi.mcp.oauth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInFlowTest {
    private val serverUrl = "https://mcp.example.com/mcp"
    private val authIssuer = "https://auth.example.com"
    private val metadataUrl = "$authIssuer/metadata"

    private fun options(
        clientId: String? = null,
        scope: String? = null,
        issParameterSupported: Boolean = false,
    ): McpOAuthOptions = McpOAuthOptions(
        serverName = "server",
        serverUrl = serverUrl,
        clientId = clientId,
        scope = scope,
        authorizationServerMetadataUrl = metadataUrl,
    )

    private fun transport(
        issParameterSupported: Boolean = false,
        includeRegistration: Boolean = true,
    ): FakeOAuthTransport = FakeOAuthTransport { request ->
        when {
            request.url == "https://mcp.example.com/.well-known/oauth-protected-resource/mcp" ->
                FakeOAuthResponse(status = 404, body = "not found")

            request.url == metadataUrl -> oauthJsonResponse(
                metadataJson(
                    issuer = authIssuer,
                    registrationEndpoint = if (includeRegistration) "$authIssuer/register" else null,
                    issParameterSupported = issParameterSupported,
                ),
            )

            request.url == "$authIssuer/register" -> oauthJsonResponse(
                clientInformationJson(clientId = "registered-client"),
            )

            request.url == "$authIssuer/token" -> oauthJsonResponse(tokensJson())
            else -> FakeOAuthResponse(status = 404, body = "unexpected ${request.url}")
        }
    }

    @Test
    fun signsInWithDynamicClientRegistrationAndStoresTokens() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect -> callbackUrlFor(url, redirect) }

        val tokens = signInMcpServer(options(), "http://127.0.0.1:1456/callback", interaction, transport, store)

        assertEquals("access-token", tokens.accessToken)
        assertEquals("refresh-token", tokens.refreshToken)
        val registration = transport.requests.filter { it.url == "$authIssuer/register" }.single()
        assertEquals("POST", registration.method)
        val tokenRequest = transport.requests.filter { it.url == "$authIssuer/token" }.single()
        assertEquals("POST", tokenRequest.method)
        assertEquals("authorization_code", tokenRequest.formBody()["grant_type"])
        assertEquals("the-code", tokenRequest.formBody()["code"])
        assertEquals("registered-client", tokenRequest.formBody()["client_id"])

        val state = store.read("server", serverUrl)!!
        assertEquals("registered-client", state.clientInformation?.clientId)
        assertEquals("access-token", state.tokens?.accessToken)
        assertTrue(state.tokensExpireAt != null)
        assertNull(state.codeVerifier)
        assertNull(state.oauthState)
        assertEquals(authIssuer, state.discovery?.authorizationServerUrl)
    }

    @Test
    fun buildsAuthorizationUrlWithPkceStateAndResource() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect -> callbackUrlFor(url, redirect) }
        signInMcpServer(options(scope = "mcp:read"), "http://127.0.0.1:1456/callback", interaction, transport, store)
        val params = parseUrlQuery(interaction.authorizationUrls.single())
        assertEquals("registered-client", params["client_id"])
        assertEquals("http://127.0.0.1:1456/callback", params["redirect_uri"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("mcp:read", params["scope"])
        assertTrue(params["code_challenge"]!!.length == 43)
        assertTrue(params["state"]!!.length == 64)
        assertEquals(interaction.redirectUris.single(), params["redirect_uri"])
    }

    @Test
    fun skipsRegistrationWhenClientIsAlreadyStored() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            McpOAuthState(
                serverUrl = serverUrl,
                clientInformation = OAuthClientInformation(clientId = "stored-client"),
            ),
        )
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect -> callbackUrlFor(url, redirect) }
        signInMcpServer(options(), "http://127.0.0.1:1456/callback", interaction, transport, store)
        assertTrue(transport.requests.none { it.url == "$authIssuer/register" })
        assertEquals("stored-client", transport.requests.last().formBody()["client_id"])
        assertEquals("stored-client", store.read("server", serverUrl)?.clientInformation?.clientId)
    }

    @Test
    fun usesConfiguredClientWithoutRegistration() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect -> callbackUrlFor(url, redirect) }
        signInMcpServer(
            options(clientId = "preconfigured-client"),
            "http://127.0.0.1:1456/callback",
            interaction,
            transport,
            store,
        )
        assertEquals("preconfigured-client", parseUrlQuery(interaction.authorizationUrls.single())["client_id"])
        assertEquals("preconfigured-client", transport.requests.last().formBody()["client_id"])
    }

    @Test
    fun rejectsStateMismatch() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect ->
            callbackUrlFor(url, redirect, overrideState = "wrong-state")
        }
        val error = assertFailsWith<OAuthFlowException> {
            signInMcpServer(options(), "http://127.0.0.1:1456/callback", interaction, transport, store)
        }
        assertEquals("OAuth state mismatch", error.message)
        assertTrue(store.read("server", serverUrl)?.tokens == null)
    }

    @Test
    fun reportsAuthorizationErrorsFromCallback() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { _, redirect ->
            "$redirect?error=access_denied&error_description=user+denied"
        }
        val error = assertFailsWith<OAuthResponseException> {
            signInMcpServer(options(), "http://127.0.0.1:1456/callback", interaction, transport, store)
        }
        assertEquals("access_denied", error.code)
        assertEquals("user denied", error.message)
    }

    @Test
    fun validatesIssuerWhenAdvertised() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport(issParameterSupported = true)
        val interaction = FakeOAuthInteraction { url, redirect ->
            callbackUrlFor(url, redirect, issuer = "https://evil.example.com")
        }
        assertFailsWith<OAuthIssuerMismatchException> {
            signInMcpServer(options(), "http://127.0.0.1:1456/callback", interaction, transport, store)
        }
    }

    @Test
    fun requiresAuthorizationCodeInCallback() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect ->
            val state = parseUrlQuery(url)["state"]
            "$redirect?state=$state"
        }
        val error = assertFailsWith<OAuthFlowException> {
            signInMcpServer(options(), "http://127.0.0.1:1456/callback", interaction, transport, store)
        }
        assertTrue(error.message!!.contains("authorization code"))
    }

    @Test
    fun requiresSecureMetadataUrl() = runTest {
        val store = MemoryMcpTokenStore()
        val transport = transport()
        val interaction = FakeOAuthInteraction { url, redirect -> callbackUrlFor(url, redirect) }
        val insecure = options().copy(authorizationServerMetadataUrl = "http://auth.example.com/metadata")
        assertFailsWith<OAuthInsecureEndpointException> {
            signInMcpServer(insecure, "http://127.0.0.1:1456/callback", interaction, transport, store)
        }
    }
}
