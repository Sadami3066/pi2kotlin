package io.github.whywood.deepdeep.sdk.demo.mcp

import io.github.whywood.deepdeep.sdk.agent.AgentEvent
import io.github.whywood.deepdeep.sdk.ai.StopReason
import io.github.whywood.deepdeep.sdk.ai.TextDeltaEvent
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderOptions
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.FauxTokenSize
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxProvider
import io.github.whywood.deepdeep.sdk.ai.providers.fauxToolCall
import io.github.whywood.deepdeep.sdk.coding.AgentSessionOptions
import io.github.whywood.deepdeep.sdk.coding.createAgentSession
import io.github.whywood.deepdeep.sdk.coding.mcp.McpOptions
import io.github.whywood.deepdeep.sdk.coding.mcp.McpServerState
import io.github.whywood.deepdeep.sdk.coding.session.SessionEvent
import io.github.whywood.deepdeep.sdk.mcp.config.McpExposure
import io.github.whywood.deepdeep.sdk.mcp.config.McpServerConfig
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
                transport = io.github.whywood.deepdeep.sdk.ai.http.okHttpTransport(),
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
