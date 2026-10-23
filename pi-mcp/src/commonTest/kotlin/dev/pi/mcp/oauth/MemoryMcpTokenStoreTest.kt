package dev.pi.mcp.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class MemoryMcpTokenStoreTest {
    @Test
    fun roundTripsStatePerServerAndUrl() = runTest {
        val store = MemoryMcpTokenStore()
        val state = stateWithTokens(serverUrl = "https://mcp.example.com/mcp")
        store.write("server-a", "https://mcp.example.com/mcp", state)
        assertEquals(state, store.read("server-a", "https://mcp.example.com/mcp"))
        assertNull(store.read("server-a", "https://other.example.com/mcp"))
        assertNull(store.read("server-b", "https://mcp.example.com/mcp"))
    }

    @Test
    fun overwritesAndClears() = runTest {
        val store = MemoryMcpTokenStore()
        store.write("server", "https://mcp.example.com/mcp", stateWithTokens(serverUrl = "https://mcp.example.com/mcp"))
        store.write(
            "server",
            "https://mcp.example.com/mcp",
            stateWithTokens(serverUrl = "https://mcp.example.com/mcp", accessToken = "new-access"),
        )
        assertEquals("new-access", store.read("server", "https://mcp.example.com/mcp")?.tokens?.accessToken)
        store.clear("server", "https://mcp.example.com/mcp")
        assertNull(store.read("server", "https://mcp.example.com/mcp"))
    }
}
