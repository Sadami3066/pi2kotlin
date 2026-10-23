package dev.pi.mcp.oauth

import dev.pi.ai.auth.oauth.sha256Base64Url
import kotlin.io.encoding.Base64
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthFlowTest {
    private val issuer = "https://auth.example.com"
    private val metadata = metadataFor(issuer, registration = "$issuer/register")
    private val client = OAuthClientInformation(clientId = "client-1")

    @Test
    fun startAuthorizationBuildsPkceUrl() = runTest {
        val request = startAuthorization(
            authorizationServerUrl = issuer,
            metadata = metadata,
            clientInformation = client,
            redirectUrl = "http://127.0.0.1:1456/callback",
            scope = "mcp:read offline_access",
            resource = "https://mcp.example.com/mcp",
            random = deterministicRandom(),
        )
        assertEquals(deterministicVerifier(), request.codeVerifier)
        assertEquals(43, request.codeVerifier.length)
        assertTrue(request.codeVerifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val params = parseUrlQuery(request.authorizationUrl)
        assertTrue(request.authorizationUrl.startsWith("$issuer/authorize?"))
        assertEquals("code", params["response_type"])
        assertEquals("client-1", params["client_id"])
        assertEquals(sha256Base64Url(request.codeVerifier), params["code_challenge"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("http://127.0.0.1:1456/callback", params["redirect_uri"])
        assertEquals("mcp:read offline_access", params["scope"])
        assertEquals("consent", params["prompt"])
        assertEquals("https://mcp.example.com/mcp", params["resource"])
        assertEquals(request.state, params["state"])
        assertEquals(64, request.state!!.length)
    }

    @Test
    fun startAuthorizationUsesProvidedState() = runTest {
        val request = startAuthorization(
            issuer,
            metadata,
            client,
            "http://127.0.0.1/callback",
            state = "fixed-state",
            random = deterministicRandom(),
        )
        assertEquals("fixed-state", request.state)
    }

    @Test
    fun startAuthorizationRejectsUnsupportedCapabilities() = runTest {
        val noS256 = metadata.copy(codeChallengeMethodsSupported = listOf("plain"))
        val error = assertFailsWith<OAuthFlowException> {
            startAuthorization(issuer, noS256, client, "http://127.0.0.1/callback")
        }
        assertTrue(error.message!!.contains("PKCE S256"))
        val noCode = metadata.copy(responseTypesSupported = listOf("token"))
        assertFailsWith<OAuthFlowException> {
            startAuthorization(issuer, noCode, client, "http://127.0.0.1/callback")
        }
    }

    @Test
    fun startAuthorizationRefusesInsecureNonLoopbackEndpoint() = runTest {
        val insecureMetadata = metadata.copy(
            authorizationEndpoint = "http://auth.example.com/authorize",
        )
        assertFailsWith<OAuthInsecureEndpointException> {
            startAuthorization(issuer, insecureMetadata, client, "http://127.0.0.1/callback")
        }
    }

    @Test
    fun registerClientPostsDynamicRegistration() = runTest {
        var captured: dev.pi.ai.http.HttpRequest? = null
        val transport = FakeOAuthTransport { request ->
            captured = request
            oauthJsonResponse(clientInformationJson(clientId = "registered-client", clientSecret = "secret"))
        }
        val client = registerClient(
            authorizationServerUrl = issuer,
            transport = transport,
            metadata = metadata,
            clientMetadata = OAuthClientMetadata(
                redirectUris = listOf("http://127.0.0.1:1456/callback"),
                clientName = "pi2kotlin",
            ),
            scope = "mcp:read",
        )
        assertEquals("registered-client", client.clientId)
        assertEquals("secret", client.clientSecret)
        val request = captured!!
        assertEquals("POST", request.method)
        assertEquals("$issuer/register", request.url)
        assertEquals("application/json", request.header("content-type"))
        val body = request.jsonBody()
        assertEquals(listOf("http://127.0.0.1:1456/callback"), body.stringList("redirect_uris"))
        assertEquals("native", body.string("application_type"))
        assertEquals("pi2kotlin", body.string("client_name"))
        assertEquals("mcp:read", body.string("scope"))
    }

    @Test
    fun registerClientRequiresRegistrationEndpoint() = runTest {
        val error = assertFailsWith<OAuthFlowException> {
            registerClient(
                issuer,
                FakeOAuthTransport { oauthJsonResponse("{}") },
                AuthorizationServerMetadata(
                    issuer = issuer,
                    authorizationEndpoint = "$issuer/authorize",
                    tokenEndpoint = "$issuer/token",
                    responseTypesSupported = listOf("code"),
                ),
                OAuthClientMetadata(redirectUris = listOf("http://127.0.0.1/callback")),
            )
        }
        assertTrue(error.message!!.contains("does not support dynamic client registration"))
    }

    @Test
    fun registerClientReportsRegistrationFailure() = runTest {
        val transport = FakeOAuthTransport { oauthJsonResponse("""{"error":"invalid_redirect_uri"}""", status = 400) }
        val error = assertFailsWith<OAuthRegistrationException> {
            registerClient(
                issuer,
                transport,
                metadata,
                OAuthClientMetadata(redirectUris = listOf("http://127.0.0.1/callback")),
            )
        }
        assertEquals(400, error.status)
        assertTrue(error.body.contains("invalid_redirect_uri"))
    }

    @Test
    fun exchangeCodePostsFormAndParsesTokens() = runTest {
        var captured: dev.pi.ai.http.HttpRequest? = null
        val transport = FakeOAuthTransport { request ->
            captured = request
            oauthJsonResponse(tokensJson())
        }
        val tokens = exchangeCode(
            authorizationServerUrl = issuer,
            transport = transport,
            metadata = metadata,
            clientInformation = client,
            code = "the-code",
            codeVerifier = "the-verifier",
            redirectUrl = "http://127.0.0.1/callback",
            resource = "https://mcp.example.com/mcp",
        )
        assertEquals("access-token", tokens.accessToken)
        assertEquals("refresh-token", tokens.refreshToken)
        val request = captured!!
        assertEquals("POST", request.method)
        assertEquals("$issuer/token", request.url)
        assertEquals("application/x-www-form-urlencoded", request.header("content-type"))
        val form = request.formBody()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals("http://127.0.0.1/callback", form["redirect_uri"])
        assertEquals("https://mcp.example.com/mcp", form["resource"])
        assertEquals("client-1", form["client_id"])
        assertTrue(request.header("authorization") == null)
    }

    @Test
    fun exchangeCodeUsesBasicAuthWhenAdvertised() = runTest {
        var captured: dev.pi.ai.http.HttpRequest? = null
        val transport = FakeOAuthTransport { request ->
            captured = request
            oauthJsonResponse(tokensJson())
        }
        val basicMetadata = metadata.copy(tokenEndpointAuthMethodsSupported = listOf("client_secret_basic"))
        exchangeCode(
            issuer,
            transport,
            basicMetadata,
            OAuthClientInformation(clientId = "client-1", clientSecret = "secret"),
            "code",
            "verifier",
            "http://127.0.0.1/callback",
        )
        val request = captured!!
        val form = request.formBody()
        assertNull(form["client_secret"])
        assertEquals("Basic ${Base64.Default.encode("client-1:secret".encodeToByteArray())}", request.header("authorization"))
    }

    @Test
    fun exchangeCodeReportsOAuthErrors() = runTest {
        val transport = FakeOAuthTransport {
            oauthJsonResponse("""{"error":"invalid_grant","error_description":"code expired"}""", status = 400)
        }
        val error = assertFailsWith<OAuthResponseException> {
            exchangeCode(issuer, transport, metadata, client, "code", "verifier", "http://127.0.0.1/callback")
        }
        assertEquals("invalid_grant", error.code)
        assertEquals("code expired", error.message)
    }

    @Test
    fun refreshPostsRefreshTokenAndKeepsItWhenNotRotated() = runTest {
        var captured: dev.pi.ai.http.HttpRequest? = null
        val transport = FakeOAuthTransport { request ->
            captured = request
            oauthJsonResponse(tokensJson(accessToken = "new-access", refreshToken = null))
        }
        val tokens = refresh(
            issuer,
            transport,
            metadata,
            client,
            refreshToken = "old-refresh",
            resource = "https://mcp.example.com/mcp",
        )
        assertEquals("new-access", tokens.accessToken)
        assertEquals("old-refresh", tokens.refreshToken)
        val form = captured!!.formBody()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("old-refresh", form["refresh_token"])
        assertEquals("https://mcp.example.com/mcp", form["resource"])
    }

    @Test
    fun buildsDiscoveryUrlsForRootAndPathIssuers() = runTest {
        val root = buildAuthorizationServerDiscoveryUrls("https://auth.example.com")
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server",
                "https://auth.example.com/.well-known/openid-configuration",
            ),
            root.map { it.url },
        )
        val withPath = buildAuthorizationServerDiscoveryUrls("https://auth.example.com/tenant/")
        assertEquals(
            listOf(
                "https://auth.example.com/.well-known/oauth-authorization-server/tenant",
                "https://auth.example.com/.well-known/openid-configuration/tenant",
                "https://auth.example.com/tenant/.well-known/openid-configuration",
            ),
            withPath.map { it.url },
        )
        assertEquals(listOf("oauth", "oidc", "oidc"), withPath.map { it.type })
    }

    @Test
    fun discoveryOverridesMustBeSecureUnlessLoopback() = runTest {
        val transport = FakeOAuthTransport { oauthJsonResponse(metadataJson("https://auth.example.com")) }
        assertFailsWith<OAuthInsecureEndpointException> {
            discover("http://mcp.example.com", transport, "http://auth.example.com/metadata")
        }
        val metadata = discover("http://127.0.0.1:9/mcp", transport, "http://127.0.0.1:9/metadata")
        assertEquals("https://auth.example.com", metadata.issuer)
    }

    @Test
    fun registerClientUsesApplicationTypeWebForHttpsRedirect() = runTest {
        var captured: dev.pi.ai.http.HttpRequest? = null
        val transport = FakeOAuthTransport { request ->
            captured = request
            oauthJsonResponse(clientInformationJson())
        }
        registerClient(
            issuer,
            transport,
            metadata,
            OAuthClientMetadata(redirectUris = listOf("https://app.example.com/callback")),
        )
        assertEquals("web", captured!!.jsonBody().string("application_type"))
    }

    @Test
    fun encodesSpecialCharactersInQueryComponents() = runTest {
        assertEquals("a+b%2Fc%3Dd", encodeQueryComponent("a b/c=d"))
        assertEquals("simple-._*", encodeQueryComponent("simple-._*"))
    }
}

private fun kotlinx.serialization.json.JsonObject.string(name: String): String =
    (this[name] as kotlinx.serialization.json.JsonPrimitive).content

private fun kotlinx.serialization.json.JsonObject.stringList(name: String): List<String> =
    (this[name] as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }
