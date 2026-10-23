package dev.pi.mcp.oauth

import dev.pi.ai.PiJson
import dev.pi.ai.platform.platformCurrentTimeMillis
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMcpTokenStoreTest {
    private fun temporaryDirectory(): Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "pi-mcp-oauth-${platformCurrentTimeMillis()}-${Random.nextInt(1_000_000)}"

    @Test
    fun persistsStatesAsJsonKeyedByServerAndUrl() = runTest {
        val directory = temporaryDirectory()
        val file = directory / "mcp-auth.json"
        try {
            val store = FileMcpTokenStore(file)
            val state = stateWithTokens(serverUrl = "https://mcp.example.com/mcp")
            store.write("server-a", "https://mcp.example.com/mcp", state)
            assertTrue(FileSystem.SYSTEM.exists(file))

            val reopened = FileMcpTokenStore(file)
            assertEquals(state, reopened.read("server-a", "https://mcp.example.com/mcp"))
            assertNull(reopened.read("server-b", "https://mcp.example.com/mcp"))

            val raw = PiJson.parseToJsonElement(fileSystemRead(file)).jsonObject
            assertTrue(raw.containsKey("server-a|https://mcp.example.com/mcp"))
            assertTrue(!FileSystem.SYSTEM.exists(directory / "mcp-auth.json.tmp"))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun overwritesAndClearsIndividualEntries() = runTest {
        val directory = temporaryDirectory()
        val file = directory / "mcp-auth.json"
        try {
            val store = FileMcpTokenStore(file)
            store.write("server", "https://mcp.example.com/mcp", stateWithTokens(serverUrl = "https://mcp.example.com/mcp"))
            store.write("other", "https://other.example.com/mcp", stateWithTokens(serverUrl = "https://other.example.com/mcp"))
            store.write(
                "server",
                "https://mcp.example.com/mcp",
                stateWithTokens(serverUrl = "https://mcp.example.com/mcp", accessToken = "new-access"),
            )
            assertEquals("new-access", store.read("server", "https://mcp.example.com/mcp")?.tokens?.accessToken)
            assertEquals(2, PiJson.parseToJsonElement(fileSystemRead(file)).jsonObject.size)

            store.clear("server", "https://mcp.example.com/mcp")
            assertNull(store.read("server", "https://mcp.example.com/mcp"))
            assertEquals(
                "access-token",
                store.read("other", "https://other.example.com/mcp")?.tokens?.accessToken,
            )
            assertEquals(1, PiJson.parseToJsonElement(fileSystemRead(file)).jsonObject.size)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun roundTripsDiscoveryAndClientInformation() = runTest {
        val directory = temporaryDirectory()
        val file = directory / "nested" / "mcp-auth.json"
        try {
            val store = FileMcpTokenStore(file)
            val state = McpOAuthState(
                serverUrl = "https://mcp.example.com/mcp",
                clientInformation = OAuthClientInformation(
                    clientId = "client-1",
                    clientSecret = "secret",
                    tokenEndpointAuthMethod = "client_secret_post",
                ),
                tokens = OAuthTokens(
                    accessToken = "access",
                    tokenType = "Bearer",
                    expiresIn = 120,
                    scope = "mcp:read",
                    refreshToken = "refresh",
                ),
                tokensExpireAt = 123_456L,
                codeVerifier = "verifier",
                oauthState = "state",
                discovery = OAuthDiscoveryState(
                    authorizationServerUrl = "https://auth.example.com",
                    authorizationServerMetadata = metadataFor("https://auth.example.com"),
                ),
            )
            store.write("server", "https://mcp.example.com/mcp", state)
            val reloaded = FileMcpTokenStore(file).read("server", "https://mcp.example.com/mcp")!!
            assertEquals(state, reloaded)
            assertEquals("client-1", reloaded.clientInformation?.clientId)
            assertEquals("mcp:read", reloaded.tokens?.scope)
            assertEquals("https://auth.example.com", reloaded.discovery?.authorizationServerMetadata?.issuer)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    private fun fileSystemRead(file: Path): String = FileSystem.SYSTEM.read(file) { readUtf8() }
}
