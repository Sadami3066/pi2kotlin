package dev.pi.mcp.oauth

import dev.pi.mcp.McpAuthRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpOAuthProviderTest {
    private val serverUrl = "https://mcp.example.com/mcp"
    private val options = McpOAuthOptions(serverName = "server", serverUrl = serverUrl)
    private val now = 1_000_000L

    private fun provider(
        store: McpTokenStore,
        transport: dev.pi.ai.http.HttpTransport = FakeOAuthTransport { error("no network expected") },
        onChallenge: ((OAuthChallenge) -> Unit)? = null,
    ): McpOAuthProvider = McpOAuthProvider(
        options = options,
        transport = transport,
        tokenStore = store,
        onChallenge = onChallenge,
        now = { now },
    )

    @Test
    fun returnsNullWithoutStoredState() = runTest {
        assertNull(provider(MemoryMcpTokenStore()).token())
    }

    @Test
    fun returnsCachedTokenWithoutNetwork() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "cached", expiresAt = now + 600_000),
        )
        val transport = FakeOAuthTransport { error("no network expected") }
        assertEquals("cached", provider(store, transport).token())
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun refreshesExpiredTokenBeforeUse() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "old", expiresAt = now - 1),
        )
        val transport = FakeOAuthTransport { request ->
            assertEquals("https://auth.example.com/token", request.url)
            assertEquals("refresh_token", request.formBody()["grant_type"])
            oauthJsonResponse(tokensJson(accessToken = "new", refreshToken = "refresh-2"))
        }
        val provider = provider(store, transport)
        assertEquals("new", provider.token())
        assertEquals(1, transport.requests.size)
        val state = store.read("server", serverUrl)!!
        assertEquals("new", state.tokens?.accessToken)
        assertEquals("refresh-2", state.tokens?.refreshToken)
        assertEquals(now + 3_600_000L, state.tokensExpireAt)
    }

    @Test
    fun tokenRefreshFailureFallsBackToStoredToken() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "old", expiresAt = now - 1),
        )
        val transport = FakeOAuthTransport { oauthJsonResponse("""{"error":"invalid_grant"}""", status = 400) }
        assertEquals("old", provider(store, transport).token())
        assertEquals("old", store.read("server", serverUrl)?.tokens?.accessToken)
    }

    @Test
    fun onUnauthorizedRefreshesOnceAndUpdatesStore() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "rejected", expiresAt = now + 3_600_000),
        )
        val transport = FakeOAuthTransport { oauthJsonResponse(tokensJson(accessToken = "fresh")) }
        val provider = provider(store, transport)
        provider.onUnauthorized(
            McpUnauthorizedContext(
                serverUrl = serverUrl,
                status = 401,
                headers = mapOf("WWW-Authenticate" to "Bearer error=\"invalid_token\""),
                token = "rejected",
            ),
        )
        assertEquals(1, transport.requests.size)
        assertEquals("fresh", store.read("server", serverUrl)?.tokens?.accessToken)
    }

    @Test
    fun onUnauthorizedSkipsRefreshWhenAnotherRequestAlreadyRefreshed() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "already-refreshed", expiresAt = now + 3_600_000),
        )
        val transport = FakeOAuthTransport { error("no network expected") }
        provider(store, transport).onUnauthorized(
            McpUnauthorizedContext(
                serverUrl = serverUrl,
                status = 401,
                headers = mapOf("WWW-Authenticate" to "Bearer error=\"invalid_token\""),
                token = "rejected-stale",
            ),
        )
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun onUnauthorizedWithInsufficientScopeRequiresInteractiveSignIn() = runTest {
        var challenge: OAuthChallenge? = null
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "token", expiresAt = now + 3_600_000),
        )
        val transport = FakeOAuthTransport { error("no network expected") }
        val provider = provider(store, transport) { challenge = it }
        val header = "Bearer error=\"insufficient_scope\", scope=\"mcp:admin\", resource_metadata=\"https://mcp.example.com/rm\""
        val error = assertFailsWith<McpAuthRequiredException> {
            provider.onUnauthorized(
                McpUnauthorizedContext(
                    serverUrl = serverUrl,
                    status = 403,
                    headers = mapOf("WWW-Authenticate" to header),
                    token = "token",
                ),
            )
        }
        assertEquals(header, error.wwwAuthenticate)
        assertEquals(serverUrl, error.serverUrl)
        assertEquals("mcp:admin", challenge?.scope)
        assertEquals("https://mcp.example.com/rm", challenge?.resourceMetadataUrl)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun onUnauthorizedWithoutRefreshTokenRequiresSignIn() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "token", refreshToken = null),
        )
        val error = assertFailsWith<McpAuthRequiredException> {
            provider(store).onUnauthorized(
                McpUnauthorizedContext(
                    serverUrl = serverUrl,
                    status = 401,
                    headers = mapOf("WWW-Authenticate" to "Bearer"),
                    token = "token",
                ),
            )
        }
        assertEquals("Bearer", error.wwwAuthenticate)
    }

    @Test
    fun onUnauthorizedRefreshFailureRequiresSignIn() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(serverUrl = serverUrl, accessToken = "token", expiresAt = now + 3_600_000),
        )
        val transport = FakeOAuthTransport { oauthJsonResponse("""{"error":"invalid_grant"}""", status = 400) }
        val error = assertFailsWith<McpAuthRequiredException> {
            provider(store, transport).onUnauthorized(
                McpUnauthorizedContext(
                    serverUrl = serverUrl,
                    status = 401,
                    headers = mapOf("WWW-Authenticate" to "Bearer error=\"invalid_token\""),
                    token = "token",
                ),
            )
        }
        assertTrue(error.message!!.contains("MCP OAuth refresh failed"))
        assertEquals("token", store.read("server", serverUrl)?.tokens?.accessToken)
    }

    @Test
    fun discoversMetadataWhenNoDiscoveryStateIsStored() = runTest {
        val store = MemoryMcpTokenStore()
        store.write(
            "server",
            serverUrl,
            stateWithTokens(
                serverUrl = serverUrl,
                accessToken = "token",
                expiresAt = now + 3_600_000,
                discovery = null,
            ),
        )
        var discoveryRequests = 0
        val transport = FakeOAuthTransport { request ->
            when {
                request.url.endsWith("/.well-known/oauth-protected-resource") -> {
                    oauthJsonResponse(
                        """{"resource":"https://mcp.example.com/mcp","authorization_servers":["https://auth.example.com"]}""",
                    )
                }

                request.url.endsWith("/.well-known/oauth-authorization-server") -> {
                    discoveryRequests += 1
                    oauthJsonResponse(metadataJson("https://auth.example.com"))
                }

                request.url == "https://auth.example.com/token" -> oauthJsonResponse(tokensJson(accessToken = "fresh"))
                else -> FakeOAuthResponse(status = 404, body = "not found")
            }
        }
        provider(store, transport).onUnauthorized(
            McpUnauthorizedContext(
                serverUrl = serverUrl,
                status = 401,
                headers = mapOf("WWW-Authenticate" to "Bearer"),
                token = "token",
            ),
        )
        assertEquals(1, discoveryRequests)
        assertEquals("fresh", store.read("server", serverUrl)?.tokens?.accessToken)
    }
}
