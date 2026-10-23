package dev.pi.demo.mcp

import dev.pi.agent.AgentEvent
import dev.pi.ai.StopReason
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.ai.providers.fauxToolCall
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.mcp.McpOptions
import dev.pi.coding.mcp.McpServerState
import dev.pi.coding.session.SessionEvent
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath

fun main() = runBlocking {
    val workspace = "build/mcp-demo/workspace".toPath()
    val agentDir = "build/mcp-demo/agent".toPath()
    FileSystem.SYSTEM.createDirectories(workspace)
    FileSystem.SYSTEM.createDirectories(agentDir)

    val server = MockMcpServer()
    server.start()
    try {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 2)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(
                    fauxAssistantMessage(
                        content = listOf(fauxToolCall("tool_search", buildJsonObject { put("query", "echo") })),
                        stopReason = StopReason.ToolUse,
                    ),
                ),
                FauxResponseStep.Fixed(
                    fauxAssistantMessage(
                        content = listOf(
                            fauxToolCall(
                                "mcp__demo__echo",
                                buildJsonObject { put("text", "hello from the demo") },
                            ),
                        ),
                        stopReason = StopReason.ToolUse,
                    ),
                ),
                FauxResponseStep.Fixed(fauxAssistantMessage("MCP demo finished.")),
            ),
        )

        val session = createAgentSession(
            AgentSessionOptions(
                cwd = workspace,
                agentDir = agentDir,
                transport = dev.pi.ai.http.okHttpTransport(),
                model = "faux/faux-1",
                tools = emptyList(),
                providers = listOf(faux.provider),
                mcp = McpOptions(
                    loadConfig = false,
                    servers = mapOf(
                        "demo" to McpServerConfig.Http(
                            url = server.url,
                            description = "Demo MCP server",
                            exposure = McpExposure.Deferred,
                        ),
                    ),
                ),
            ),
        )

        session.subscribe { event ->
            when (event) {
                is SessionEvent.AgentEventReceived -> {
                    val agentEvent = event.event
                    if (agentEvent is AgentEvent.MessageUpdate) {
                        val update = agentEvent.assistantMessageEvent
                        if (update is TextDeltaEvent) print(update.delta)
                    }
                }

                is SessionEvent.McpServerStatusChanged -> {
                    println("[mcp] ${event.name}: ${event.status.state} tools=${event.status.toolCount}")
                }

                is SessionEvent.McpToolsChanged -> {
                    println("[mcp] tools changed on ${event.server}: +${event.added} -${event.removed}")
                }

                else -> Unit
            }
        }

        println("MCP server: ${server.url}")
        session.mcp.servers.forEach { println("server ${it.name}: ${it.state} ${it.lastError ?: ""}") }
        session.prompt("Search for the echo tool and call it with hello.")
        println()
        println("active tools: ${session.activeToolNames}")
        println("mcp state: ${session.mcp.servers.map { "${it.name}=${it.state} ${it.lastError}" }}")
        println("last assistant: ${session.getLastAssistantText()}")
        session.close()
    } finally {
        server.stop()
    }
}
