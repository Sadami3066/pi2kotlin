package io.github.whywood.deepdeep.sdk.coding.scenarios

import io.github.whywood.deepdeep.sdk.agent.asLlmMessage
import io.github.whywood.deepdeep.sdk.ai.RunSignal
import io.github.whywood.deepdeep.sdk.ai.StopReason
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxToolCall
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderHandle
import io.github.whywood.deepdeep.sdk.coding.AgentSessionOptions
import io.github.whywood.deepdeep.sdk.coding.createAgentSession
import io.github.whywood.deepdeep.sdk.coding.mcp.McpOptions
import io.github.whywood.deepdeep.sdk.coding.mcp.McpServerState
import io.github.whywood.deepdeep.sdk.coding.mcp.McpTransportFactory
import io.github.whywood.deepdeep.sdk.mcp.McpConnectionClosedException
import io.github.whywood.deepdeep.sdk.mcp.McpTransport
import io.github.whywood.deepdeep.sdk.mcp.McpTransportEvent
import io.github.whywood.deepdeep.sdk.mcp.config.McpExposure
import io.github.whywood.deepdeep.sdk.mcp.config.McpServerConfig
import io.github.whywood.deepdeep.sdk.mcp.protocol.JsonRpcMessage
import io.github.whywood.deepdeep.sdk.mcp.protocol.JsonRpcRequest
import io.github.whywood.deepdeep.sdk.mcp.protocol.JsonRpcResponse
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpSessionScenarioTest {
    private class FakeMcpTransport : McpTransport {
        private val channel = Channel<McpTransportEvent>(Channel.UNLIMITED)
        override val events: Flow<McpTransportEvent> = channel.receiveAsFlow()
        private var started = false
        var closed = false
            private set

        override suspend fun start() {
            started = true
        }

        override suspend fun send(message: JsonRpcMessage, signal: RunSignal?) {
            if (!started || closed) throw McpConnectionClosedException()
            val request = message as? JsonRpcRequest ?: return
            val params = request.params ?: JsonObject(emptyMap())
            channel.send(McpTransportEvent.Message(JsonRpcResponse(request.id, respond(request.method, params))))
        }

        override suspend fun close() {
            closed = true
            channel.send(McpTransportEvent.Closed(null))
        }

        private fun respond(method: String, params: JsonObject): JsonObject = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { })
                    put("prompts", buildJsonObject { })
                })
                put("serverInfo", buildJsonObject { put("name", "fake"); put("version", "1") })
            }
            "tools/list" -> buildJsonObject {
                put("tools", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject {
                        put("name", "echo")
                        put("description", "Echo a value")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
                        })
                    },
                )))
            }
            "tools/call" -> buildJsonObject {
                val value = (params["arguments"] as? JsonObject)
                    ?.get("value")
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                    ?: "none"
                put("content", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("type", "text"); put("text", "echoed:$value") },
                )))
            }
            "prompts/list" -> buildJsonObject {
                put("prompts", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("name", "greet") },
                )))
            }
            "prompts/get" -> buildJsonObject {
                put("messages", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonObject { put("type", "text"); put("text", "hello from prompt") })
                    },
                )))
            }
            "ping" -> buildJsonObject { }
            else -> buildJsonObject { }
        }
    }

    private suspend fun createSession(
        exposure: McpExposure,
        transport: FakeMcpTransport,
        responses: List<FauxResponseStep>,
        autoEnableToolSearch: Boolean = false,
    ): Pair<io.github.whywood.deepdeep.sdk.coding.session.AgentSession, FauxProviderHandle> {
        val fs = ScenarioSupport.fileSystem()
        ScenarioSupport.writeSettings(fs, """{"projectTrusted":true}""")
        val faux = ScenarioSupport.faux()
        faux.setResponses(responses)
        val session = createAgentSession(
            AgentSessionOptions(
                cwd = ScenarioSupport.CWD.toPath(),
                agentDir = ScenarioSupport.AGENT_DIR.toPath(),
                transport = NoNetworkTransport,
                fs = fs,
                model = "faux/faux-1",
                providers = listOf(faux.provider),
                mcp = McpOptions(
                    loadConfig = false,
                    autoEnableToolSearch = autoEnableToolSearch,
                    servers = mapOf(
                        "demo" to McpServerConfig.Http(url = "https://mcp.test/mcp", exposure = exposure),
                    ),
                    transportFactory = McpTransportFactory { _, _, _ -> transport },
                ),
            ),
        )
        return session to faux
    }

    @Test
    fun directMcpToolsAreAvailableBeforeFirstPrompt() = runBlocking {
        val transport = FakeMcpTransport()
        val (session, _) = createSession(McpExposure.Direct, transport, listOf(
            FauxResponseStep.Fixed(fauxAssistantMessage("hi there")),
        ))
        try {
            assertTrue(session.activeToolNames.contains("mcp__demo__echo"))
            assertEquals(McpServerState.Connected, session.mcp.servers.single().state)
            session.prompt("hello")
            assertEquals("hi there", session.getLastAssistantText())
        } finally {
            session.close()
        }
        assertTrue(transport.closed)
    }

    @Test
    fun deferredToolsLoadViaToolSearchInSameRun() = runBlocking {
        val transport = FakeMcpTransport()
        val (session, faux) = createSession(McpExposure.Deferred, transport, listOf(
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
                            buildJsonObject { put("value", "abc") },
                        ),
                    ),
                    stopReason = StopReason.ToolUse,
                ),
            ),
            FauxResponseStep.Fixed(fauxAssistantMessage("done")),
        ), autoEnableToolSearch = true)
        try {
            assertTrue(session.activeToolNames.contains("tool_search"))
            assertFalse(session.activeToolNames.contains("mcp__demo__echo"))
            session.prompt("search and echo")
            assertEquals("done", session.getLastAssistantText())
            assertTrue(session.activeToolNames.contains("mcp__demo__echo"))
            val texts = session.messages.mapNotNull { message ->
                (message.asLlmMessage() as? io.github.whywood.deepdeep.sdk.ai.ToolResultMessage)
                    ?.content
                    ?.filterIsInstance<io.github.whywood.deepdeep.sdk.ai.TextContent>()
                    ?.joinToString("") { it.text }
            }
            assertTrue(texts.any { it.contains("echoed:abc") }, texts.toString())
            assertEquals(3, faux.state.callCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun callDeferredToolExecutesWithoutLoadingTools() = runBlocking {
        val transport = FakeMcpTransport()
        val (session, _) = createSession(McpExposure.Deferred, transport, emptyList())
        try {
            assertFalse(session.activeToolNames.contains("mcp__demo__echo"))
            val result = session.mcp.callDeferredTool(
                "mcp__demo__echo",
                buildJsonObject { put("value", "abc") },
            )
            val text = result?.content?.filterIsInstance<io.github.whywood.deepdeep.sdk.ai.TextContent>()?.joinToString("") { it.text }
            assertTrue(text?.contains("echoed:abc") == true, text)
            assertFalse(session.activeToolNames.contains("mcp__demo__echo"))
            assertTrue(session.mcp.callDeferredTool("missing", JsonObject(emptyMap())) == null)
        } finally {
            session.close()
        }
    }

    @Test
    fun mcpPromptExpandsIntoUserMessage() = runBlocking {
        val transport = FakeMcpTransport()
        val (session, _) = createSession(McpExposure.Direct, transport, listOf(
            FauxResponseStep.Fixed(fauxAssistantMessage("received")),
        ))
        try {
            session.promptMcp("demo", "greet", mapOf("who" to "x"))
            assertEquals("received", session.getLastAssistantText())
            assertTrue(session.messages.any { message ->
                message.asLlmMessage()?.let { it is io.github.whywood.deepdeep.sdk.ai.UserMessage } == true
            })
        } finally {
            session.close()
        }
    }
}
