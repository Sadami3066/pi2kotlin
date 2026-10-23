package dev.pi.coding.mcp

import dev.pi.mcp.config.McpExposure

internal fun buildMcpServersSection(statuses: List<McpServerStatus>): String? {
    val reachable = statuses.filter { status ->
        status.exposure == McpExposure.Deferred || status.exposure == McpExposure.Codemode
    }
    if (reachable.isEmpty()) return null
    val lines = mutableListOf(
        "Some MCP (Model Context Protocol) servers are connected but their tools are not declared upfront.",
        "Use the `tool_search` tool to find and load the tools you need before calling them.",
        "",
    )
    for (status in reachable) {
        val summary = firstLine(status.descriptionOrInstructions()) ?: "MCP server"
        val state = if (status.isConnected) "${status.toolCount} tools" else status.state.name.lowercase()
        lines.add("- ${status.name}: $summary ($state)")
    }
    return lines.joinToString("\n")
}

private fun McpServerStatus.descriptionOrInstructions(): String? = description ?: instructions ?: lastError

private fun firstLine(text: String?): String? {
    val value = text?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: return null
    return value.take(200)
}
