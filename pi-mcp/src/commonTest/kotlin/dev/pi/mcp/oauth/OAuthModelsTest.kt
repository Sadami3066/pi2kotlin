package dev.pi.mcp.oauth

import dev.pi.ai.PiJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OAuthModelsTest {
    @Test
    fun parsesBearerChallenge() {
        val challenge = WwwAuthenticate.parse(
            "Bearer realm=\"mcp\", scope=\"mcp:read mcp:write\", resource=\"https://mcp.example.com/mcp\", " +
                "error=\"invalid_token\", error_description=\"token expired\"",
        )
        assertEquals("mcp", challenge.realm)
        assertEquals("mcp:read mcp:write", challenge.scope)
        assertEquals("https://mcp.example.com/mcp", challenge.resource)
        assertEquals("invalid_token", challenge.error)
        assertEquals("token expired", challenge.errorDescription)
    }

    @Test
    fun parsesResourceMetadataAndInsufficientScope() {
        val challenge = WwwAuthenticate.parse(
            "Bearer error=\"insufficient_scope\", scope=\"mcp:admin\", " +
                "resource_metadata=\"https://mcp.example.com/.well-known/oauth-protected-resource\"",
        )
        assertEquals("insufficient_scope", challenge.error)
        assertEquals("mcp:admin", challenge.scope)
        assertEquals("https://mcp.example.com/.well-known/oauth-protected-resource", challenge.resourceMetadataUrl)
    }

    @Test
    fun ignoresNonBearerChallenges() {
        assertNull(WwwAuthenticate.parse("Basic realm=\"x\"").realm)
        assertNull(WwwAuthenticate.parse("Basic realm=\"x\"").error)
        assertEquals("x", WwwAuthenticate.parse("DPoP realm=\"x\"").realm)
        assertEquals(OAuthChallenge(), WwwAuthenticate.parse(null))
        assertEquals(OAuthChallenge(), WwwAuthenticate.parse(""))
    }

    @Test
    fun ignoresEmptyQuotedValues() {
        val challenge = WwwAuthenticate.parse("""Bearer scope="", error=invalid_token""")
        assertNull(challenge.scope)
        assertEquals("invalid_token", challenge.error)
    }

    @Test
    fun parsesAuthorizationServerMetadata() {
        val metadata = parseAuthorizationServerMetadata(
            PiJson.parseToJsonElement(
                metadataJson(
                    issuer = "https://auth.example.com",
                    registrationEndpoint = "https://auth.example.com/register",
                    issParameterSupported = true,
                ),
            ),
        )
        assertEquals("https://auth.example.com", metadata.issuer)
        assertEquals("https://auth.example.com/authorize", metadata.authorizationEndpoint)
        assertEquals("https://auth.example.com/token", metadata.tokenEndpoint)
        assertEquals("https://auth.example.com/register", metadata.registrationEndpoint)
        assertEquals(listOf("mcp:read"), metadata.scopesSupported)
        assertEquals(listOf("code"), metadata.responseTypesSupported)
        assertEquals(listOf("S256"), metadata.codeChallengeMethodsSupported)
        assertTrue(metadata.authorizationResponseIssParameterSupported)
    }

    @Test
    fun rejectsInvalidMetadata() {
        val missingToken = assertFailsWith<OAuthFlowException> {
            parseAuthorizationServerMetadata(
                buildJsonObject {
                    put("issuer", "https://auth.example.com")
                    put("authorization_endpoint", "https://auth.example.com/authorize")
                },
            )
        }
        assertTrue(missingToken.message!!.contains("token endpoint"))
        assertFailsWith<OAuthFlowException> {
            parseAuthorizationServerMetadata(JsonPrimitive("not-an-object"))
        }
        assertFailsWith<OAuthFlowException> {
            parseAuthorizationServerMetadata(
                buildJsonObject {
                    put("issuer", "not a url")
                    put("authorization_endpoint", "https://auth.example.com/authorize")
                    put("token_endpoint", "https://auth.example.com/token")
                    put("response_types_supported", kotlinx.serialization.json.JsonArray(emptyList()))
                },
            )
        }
    }

    @Test
    fun parsesProtectedResourceMetadata() {
        val metadata = parseProtectedResourceMetadata(
            PiJson.parseToJsonElement(
                """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"],"scopes_supported":["mcp:read"]}""",
            ),
        )
        assertEquals("https://mcp.example.com/mcp", metadata.resource)
        assertEquals(listOf("https://auth.example.com"), metadata.authorizationServers)
        assertEquals(listOf("mcp:read"), metadata.scopesSupported)
    }

    @Test
    fun parsesTokensAndClientInformation() {
        val tokens = parseOAuthTokens(
            PiJson.parseToJsonElement(tokensJson(accessToken = "a", refreshToken = "r", expiresIn = 120)),
        )
        assertEquals("a", tokens.accessToken)
        assertEquals("Bearer", tokens.tokenType)
        assertEquals("r", tokens.refreshToken)
        assertEquals(120L, tokens.expiresIn)
        assertEquals("mcp:read", tokens.scope)

        val client = parseClientInformation(
            PiJson.parseToJsonElement(clientInformationJson(clientId = "c", clientSecret = "s")),
        )
        assertEquals("c", client.clientId)
        assertEquals("s", client.clientSecret)
        assertEquals("client_secret_post", client.tokenEndpointAuthMethod)
    }

    @Test
    fun rejectsTokenResponseWithoutAccessToken() {
        val error = assertFailsWith<OAuthFlowException> {
            parseOAuthTokens(PiJson.parseToJsonElement("""{"token_type":"Bearer"}"""))
        }
        assertTrue(error.message!!.contains("access_token"))
    }

    @Test
    fun stepUpScopeMergesGrantedAndChallengedScopes() {
        assertEquals("mcp:read mcp:admin", stepUpScope("mcp:read", "mcp:admin"))
        assertEquals("mcp:read mcp:admin", stepUpScope("mcp:read mcp:admin", "mcp:admin"))
        assertEquals("mcp:admin", stepUpScope(null, "mcp:admin"))
        assertNull(stepUpScope("mcp:read", null))
        assertNull(stepUpScope("mcp:read", ""))
    }

    @Test
    fun validatesResourceSelection() {
        val resource = OAuthProtectedResourceMetadata(resource = "https://mcp.example.com/api")
        assertEquals(
            "https://mcp.example.com/api",
            selectResource("https://mcp.example.com/api/mcp#fragment", resource),
        )
        assertNull(selectResource("https://mcp.example.com/api", null))
        val mismatch = assertFailsWith<OAuthFlowException> {
            selectResource("https://other.example.com/api", resource)
        }
        assertTrue(mismatch.message!!.contains("does not match MCP server"))
        assertFailsWith<OAuthFlowException> {
            selectResource("https://mcp.example.com/other", resource)
        }
    }

    @Test
    fun detectsLoopbackHosts() {
        assertTrue(isLoopbackHost("127.0.0.1"))
        assertTrue(isLoopbackHost("localhost"))
        assertTrue(isLoopbackHost("[::1]"))
        assertTrue(!isLoopbackHost("example.com"))
        assertEquals("https://mcp.example.com/mcp", resourceUrlFromServerUrl("https://mcp.example.com/mcp#x"))
    }
}
