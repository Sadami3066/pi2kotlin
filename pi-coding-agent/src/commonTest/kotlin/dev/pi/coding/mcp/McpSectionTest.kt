package dev.pi.coding.mcp

import dev.pi.mcp.config.McpExposure
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpSectionTest {
    @Test
    fun wrapsReachableServersInContainer() {
        val section = buildMcpServersSection(
            listOf(
                McpServerStatus(
                    name = "files",
                    state = McpServerState.Connected,
                    toolCount = 3,
                    description = "File tools",
                ),
                McpServerStatus(
                    name = "search",
                    state = McpServerState.Failed,
                    lastError = "connection refused",
                ),
            ),
        )
        requireNotNull(section)
        assertTrue(section.startsWith("<mcp_servers>"))
        assertTrue(section.endsWith("</mcp_servers>"))
        assertTrue(section.contains("`tool_search`"))
        assertTrue(section.contains("- files: File tools (3 tools)"))
        assertTrue(section.contains("- search: connection refused (failed)"))
    }

    @Test
    fun returnsNullWithoutReachableServers() {
        assertNull(buildMcpServersSection(emptyList()))
        assertNull(
            buildMcpServersSection(
                listOf(
                    McpServerStatus(name = "direct", state = McpServerState.Connected, exposure = McpExposure.Direct),
                    McpServerStatus(name = "hidden", state = McpServerState.Connected, exposure = McpExposure.Hidden),
                ),
            ),
        )
    }
}
