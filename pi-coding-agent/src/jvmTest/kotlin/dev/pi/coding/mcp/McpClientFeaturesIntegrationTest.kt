package dev.pi.coding.mcp

import dev.pi.ai.RunSignal
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.scenarios.NoNetworkTransport
import dev.pi.coding.scenarios.ScenarioSupport
import dev.pi.mcp.McpConnectionClosedException
import dev.pi.mcp.McpTransport
import dev.pi.mcp.McpTransportEvent
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.McpElicitationRequest
import dev.pi.mcp.protocol.McpElicitationResult
import dev.pi.mcp.protocol.McpRoot
import dev.pi.mcp.protocol.McpSamplingRequest
import dev.pi.mcp.protocol.McpSamplingResult
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpClientFeaturesIntegrationTest {
    private class FeatureTransport : McpTransport {
        private val channel = Channel<McpTransportEvent>(Channel.UNLIMITED)
        override val events: Flow<McpTransportEvent> = channel.receiveAsFlow()
        private var started = false
        val responses = Channel<JsonRpcMessage>(Channel.UNLIMITED)
        val notifications = Channel<JsonRpcNotification>(Channel.UNLIMITED)
        var closed = false
            private set

        override suspend fun start() {
            started = true
        }

        override suspend fun send(message: JsonRpcMessage, signal: RunSignal?) {
            if (!started || closed) throw McpConnectionClosedException()
            when (message) {
                is JsonRpcRequest -> channel.send(
                    McpTransportEvent.Message(JsonRpcResponse(message.id, respond(message.method))),
                )
                is JsonRpcNotification -> notifications.send(message)
                else -> responses.send(message)
            }
        }

        override suspend fun close() {
            closed = true
            channel.send(McpTransportEvent.Closed(null))
        }

        suspend fun emit(request: JsonRpcRequest) {
            channel.send(McpTransportEvent.Message(request))
        }

        fun respond(method: String): JsonObject = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put(
                    "capabilities",
                    buildJsonObject {
                        put("tools", buildJsonObject { })
                        put("sampling", buildJsonObject { })
                        put("elicitation", buildJsonObject { })
                        put("roots", buildJsonObject { put("listChanged", true) })
                    },
                )
                put("serverInfo", buildJsonObject { put("name", "features"); put("version", "1") })
            }
            "tools/list" -> buildJsonObject {
                put("tools", JsonArray(listOf(
                    buildJsonObject {
                        put("name", "echo")
                        put("description", "echo")
                        put("inputSchema", buildJsonObject { put("type", "object") })
                    },
                )))
            }
            "ping" -> buildJsonObject { }
            else -> buildJsonObject { }
        }
    }

    private fun manager(
        scope: CoroutineScope,
        transport: FeatureTransport,
        roots: List<McpRoot>?,
        sampling: (suspend (McpSamplingRequest) -> McpSamplingResult)?,
        elicit: (suspend (McpElicitationRequest) -> McpElicitationResult)?,
    ): McpManager = McpManager(
        options = McpOptions(loadConfig = false),
        factory = McpTransportFactory { _, _, _ -> transport },
        scope = scope,
        initialServers = mapOf("demo" to McpServerConfig.Http(url = "https://mcp.test/mcp", exposure = McpExposure.Direct)),
        callbacks = McpManagerCallbacks(
            toolsAdded = { _, _ -> },
            toolsRemoved = { _, _ -> },
            statusChanged = { },
            log = { _, _, _ -> },
            resourceToolsChanged = { },
            existingToolNames = { emptySet() },
        ),
        roots = roots,
        samplingHandler = sampling,
        elicitationHandler = elicit,
    )

    @Test
    fun servesRootsAndSamplingAndElicitation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val transport = FeatureTransport()
            val samplingRequests = mutableListOf<McpSamplingRequest>()
            val elicitationRequests = mutableListOf<McpElicitationRequest>()
            val manager = manager(
                scope = scope,
                transport = transport,
                roots = listOf(McpRoot("file:///workspace", "workspace")),
                sampling = { request ->
                    samplingRequests += request
                    McpSamplingResult(
                        content = listOf(buildJsonObject { put("type", "text"); put("text", "sampled") }),
                        model = "faux/faux-1",
                    )
                },
                elicit = { request ->
                    elicitationRequests += request
                    McpElicitationResult("accept", buildJsonObject { put("value", "yes") })
                },
            )
            manager.start()
            withTimeout(5_000) {
                while (manager.servers.single().state != McpServerState.Connected) delay(10)
            }

            transport.emit(
                JsonRpcRequest(
                    id = dev.pi.mcp.protocol.JsonRpcId.Number(100),
                    method = "roots/list",
                ),
            )
            val rootsResponse = withTimeout(5_000) { transport.responses.receive() } as JsonRpcResponse
            val roots = rootsResponse.result!!["roots"] as JsonArray
            assertEquals("file:///workspace", ((roots.single() as JsonObject)["uri"] as JsonPrimitive).content)

            transport.emit(
                JsonRpcRequest(
                    id = dev.pi.mcp.protocol.JsonRpcId.Number(101),
                    method = "sampling/createMessage",
                    params = buildJsonObject {
                        put(
                            "messages",
                            JsonArray(listOf(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", buildJsonObject { put("type", "text"); put("text", "hello") })
                                },
                            )),
                        )
                        put("maxTokens", 128)
                    },
                ),
            )
            val samplingResponse = withTimeout(5_000) { transport.responses.receive() } as JsonRpcResponse
            assertEquals(1, samplingRequests.size)
            assertEquals("sampled", ((samplingResponse.result!!["content"] as JsonArray).single() as JsonObject)["text"]?.let { (it as JsonPrimitive).content })

            transport.emit(
                JsonRpcRequest(
                    id = dev.pi.mcp.protocol.JsonRpcId.Number(102),
                    method = "elicitation/create",
                    params = buildJsonObject {
                        put("message", "Proceed?")
                        put("requestedSchema", buildJsonObject { put("type", "object") })
                    },
                ),
            )
            val elicitationResponse = withTimeout(5_000) { transport.responses.receive() } as JsonRpcResponse
            assertEquals("accept", (elicitationResponse.result!!["action"] as JsonPrimitive).content)
            assertEquals(1, elicitationRequests.size)

            manager.setRoots(listOf(McpRoot("file:///other")))
            val notification = withTimeout(5_000) {
                var message = transport.notifications.receive()
                while (message.method != "notifications/roots/list_changed") {
                    message = transport.notifications.receive()
                }
                message
            }
            assertEquals("notifications/roots/list_changed", notification.method)
            manager.close()
        } finally {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    @Test
    fun sessionWiresDefaultRootsAndSamplingToSessionModel() = runBlocking {
        val fs = ScenarioSupport.fileSystem()
        val faux = ScenarioSupport.faux()
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(fauxAssistantMessage("sampled by model")),
                FauxResponseStep.Fixed(fauxAssistantMessage("prompt answer")),
            ),
        )
        val transport = FeatureTransport()
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
                    servers = mapOf(
                        "demo" to McpServerConfig.Http(url = "https://mcp.test/mcp", exposure = McpExposure.Direct),
                    ),
                    transportFactory = McpTransportFactory { _, _, _ -> transport },
                ),
            ),
        )
        try {
            transport.emit(
                JsonRpcRequest(
                    id = dev.pi.mcp.protocol.JsonRpcId.Number(200),
                    method = "roots/list",
                ),
            )
            val rootsResponse = withTimeout(5_000) { transport.responses.receive() } as JsonRpcResponse
            val rootUri = (((rootsResponse.result!!["roots"] as JsonArray).single() as JsonObject)["uri"] as JsonPrimitive).content
            assertTrue(rootUri.startsWith("file://"))

            transport.emit(
                JsonRpcRequest(
                    id = dev.pi.mcp.protocol.JsonRpcId.Number(201),
                    method = "sampling/createMessage",
                    params = buildJsonObject {
                        put(
                            "messages",
                            JsonArray(listOf(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", buildJsonObject { put("type", "text"); put("text", "summarize") })
                                },
                            )),
                        )
                        put("maxTokens", 64)
                    },
                ),
            )
            val samplingResponse = withTimeout(5_000) { transport.responses.receive() } as JsonRpcResponse
            val text = (samplingResponse.result!!["content"] as JsonArray).joinToString("") { element ->
                (element as JsonObject)["text"]?.let { (it as JsonPrimitive).content }.orEmpty()
            }
            assertEquals("sampled by model", text)
            session.prompt("hello")
            assertEquals("prompt answer", session.getLastAssistantText())
        } finally {
            session.close()
        }
    }
}
