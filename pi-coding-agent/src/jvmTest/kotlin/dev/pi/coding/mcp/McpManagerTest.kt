package dev.pi.coding.mcp

import dev.pi.ai.RunSignal
import dev.pi.coding.tools.CodingTool
import dev.pi.mcp.McpConnectionClosedException
import dev.pi.mcp.McpTransport
import dev.pi.mcp.McpTransportEvent
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpManagerTest {
    private class ScriptedTransport : McpTransport {
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
            channel.send(McpTransportEvent.Message(JsonRpcResponse(request.id, respond(request.method))))
        }

        override suspend fun close() {
            closed = true
            channel.send(McpTransportEvent.Closed(null))
        }

        private fun respond(method: String): JsonObject = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject { })
                    put("resources", buildJsonObject { })
                    put("prompts", buildJsonObject { })
                })
                put("serverInfo", buildJsonObject { put("name", "scripted"); put("version", "1") })
                put("instructions", "Scripted server instructions.")
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
                put("content", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("type", "text"); put("text", "echoed") },
                )))
            }
            "resources/list" -> buildJsonObject {
                put("resources", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("uri", "file:///a.txt"); put("name", "a") },
                )))
            }
            "prompts/list" -> buildJsonObject {
                put("prompts", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject { put("name", "greet"); put("arguments", kotlinx.serialization.json.JsonArray(listOf(buildJsonObject { put("name", "who") }))) },
                )))
            }
            "prompts/get" -> buildJsonObject {
                put("messages", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonObject { put("type", "text"); put("text", "hello") })
                    },
                )))
            }
            "ping" -> buildJsonObject { }
            else -> buildJsonObject { }
        }
    }

    private class Recorder {
        val added = mutableListOf<CodingTool>()
        val removed = mutableSetOf<String>()
        val states = mutableListOf<McpServerStatus>()
    }

    private fun manager(
        servers: Map<String, McpServerConfig>,
        scope: CoroutineScope,
        recorder: Recorder,
    ): McpManager = McpManager(
        options = McpOptions(loadConfig = false),
        factory = McpTransportFactory { _, _, _ -> ScriptedTransport() },
        scope = scope,
        initialServers = servers,
        callbacks = McpManagerCallbacks(
            toolsAdded = { _, tools -> recorder.added.addAll(tools) },
            toolsRemoved = { _, names -> recorder.removed.addAll(names) },
            statusChanged = { recorder.states.add(it) },
            log = { _, _, _ -> },
            resourceToolsChanged = { tools -> recorder.added.addAll(tools) },
            existingToolNames = { recorder.added.map { it.name }.toSet() },
        ),
    )

    private suspend fun awaitTools(recorder: Recorder, name: String) {
        withTimeout(5_000) {
            while (recorder.added.none { it.name == name }) delay(10)
        }
    }

    @Test
    fun directServerRegistersToolsAndResources() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recorder = Recorder()
            val manager = manager(
                mapOf("demo" to McpServerConfig.Http(url = "https://mcp.test/mcp", exposure = McpExposure.Direct)),
                scope,
                recorder,
            )
            manager.start()
            awaitTools(recorder, "mcp__demo__echo")
            awaitTools(recorder, "list_mcp_resources")
            val status = manager.servers.single()
            assertEquals(McpServerState.Connected, status.state)
            assertEquals(1, status.toolCount)
            assertEquals("Scripted server instructions.", status.instructions)
            assertTrue(manager.healthCheck().single().healthy)
            assertEquals("greet", manager.listPrompts().single().name)
            assertEquals("hello", manager.expandPrompt("demo", "greet", mapOf("who" to "x")))
            manager.close()
            assertTrue(manager.servers.isEmpty())
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun deferredServerExposesCatalogAndLoadsTools() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recorder = Recorder()
            val manager = manager(
                mapOf("demo" to McpServerConfig.Http(url = "https://mcp.test/mcp")),
                scope,
                recorder,
            )
            manager.start()
            while (manager.servers.single().toolCount == 0) delay(10)
            assertTrue(manager.hasDeferredTools())
            assertTrue(manager.deferredCatalog().any { it.name == "mcp__demo__echo" })
            val loaded = manager.loadDeferredTools(listOf("mcp__demo__echo"))
            assertTrue(loaded.any { it.name == "mcp__demo__echo" })
            assertTrue(manager.tools().any { it.name == "echo" })
            manager.close()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun addAndRemoveServer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val recorder = Recorder()
            val manager = manager(emptyMap(), scope, recorder)
            manager.start()
            manager.addServer("later", McpServerConfig.Http(url = "https://mcp.test/mcp", exposure = McpExposure.Direct))
            awaitTools(recorder, "mcp__later__echo")
            assertTrue(manager.removeServer("later") != null)
            assertTrue(recorder.removed.any { it.startsWith("mcp__later__") })
            manager.close()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }
}
