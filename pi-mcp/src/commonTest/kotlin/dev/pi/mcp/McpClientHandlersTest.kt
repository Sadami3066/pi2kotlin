package dev.pi.mcp

import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.LATEST_PROTOCOL_VERSION
import dev.pi.mcp.protocol.McpClientHandlers
import dev.pi.mcp.protocol.McpElicitationRequest
import dev.pi.mcp.protocol.McpElicitationResult
import dev.pi.mcp.protocol.McpRoot
import dev.pi.mcp.protocol.McpSamplingRequest
import dev.pi.mcp.protocol.McpSamplingResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpClientHandlersTest {
    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", LATEST_PROTOCOL_VERSION)
        put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
        put(
            "serverInfo",
            buildJsonObject {
                put("name", "test-server")
                put("version", "1.0.0")
            },
        )
    }

    private suspend fun newClient(
        handlers: McpClientHandlers = McpClientHandlers(),
        capabilities: JsonObject = JsonObject(emptyMap()),
    ): Pair<McpClient, InMemoryTransport> {
        val transport = InMemoryTransport()
        transport.responder = { request ->
            if (request.method == "initialize") transport.respond(initializeResult(), request.id)
        }
        val client = McpClient(
            "test-server",
            transport,
            McpClientOptions(handlers = handlers, capabilities = capabilities, requestTimeoutMs = 5_000),
        )
        client.initialize()
        return client to transport
    }

    private suspend fun sendServerRequest(
        transport: InMemoryTransport,
        method: String,
        params: JsonObject? = null,
        id: Long = 100,
    ) {
        transport.emit(JsonRpcRequest(JsonRpcId.Number(id), method, params))
    }

    private suspend fun awaitResponse(transport: InMemoryTransport, id: Long = 100): JsonRpcResponse =
        transport.awaitSent { message ->
            message is JsonRpcResponse && message.id == JsonRpcId.Number(id)
        } as JsonRpcResponse

    private suspend fun awaitErrorResponse(transport: InMemoryTransport, id: Long = 100): JsonRpcErrorResponse =
        transport.awaitSent { message ->
            message is JsonRpcErrorResponse && message.id == JsonRpcId.Number(id)
        } as JsonRpcErrorResponse

    private suspend fun initializeCapabilities(transport: InMemoryTransport): JsonObject {
        val initialize = transport.sent().filterIsInstance<JsonRpcRequest>()
            .single { it.method == "initialize" }
        return assertNotNull(initialize.params)["capabilities"] as JsonObject
    }

    private fun samplingParams(
        maxTokens: Long? = null,
        withSystemPrompt: Boolean = true,
    ): JsonObject = buildJsonObject {
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put(
                            "content",
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive("hello"))
                            },
                        )
                    },
                ),
            ),
        )
        if (withSystemPrompt) put("systemPrompt", JsonPrimitive("be nice"))
        maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
    }

    @Test
    fun initializeDeclaresRootsCapability() = runBlocking {
        val (client, transport) = newClient(McpClientHandlers(listRoots = { emptyList() }))
        val capabilities = initializeCapabilities(transport)
        assertEquals(JsonPrimitive(true), (capabilities["roots"] as JsonObject)["listChanged"])
        assertNull(capabilities["sampling"])
        assertNull(capabilities["elicitation"])
        client.close()
    }

    @Test
    fun initializeDeclaresSamplingCapability() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(sample = { request -> McpSamplingResult(content = listOf(), model = "model") }),
        )
        val capabilities = initializeCapabilities(transport)
        assertEquals(JsonObject(emptyMap()), capabilities["sampling"])
        assertNull(capabilities["roots"])
        assertNull(capabilities["elicitation"])
        client.close()
    }

    @Test
    fun initializeDeclaresElicitationCapability() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(elicit = { McpElicitationResult("decline") }),
        )
        val capabilities = initializeCapabilities(transport)
        assertEquals(JsonObject(emptyMap()), capabilities["elicitation"])
        assertNull(capabilities["roots"])
        assertNull(capabilities["sampling"])
        client.close()
    }

    @Test
    fun initializeMergesCustomCapabilities() = runBlocking {
        val custom = buildJsonObject {
            put("experimental", buildJsonObject { put("feature", JsonPrimitive(true)) })
            put("sampling", buildJsonObject { put("tools", JsonObject(emptyMap())) })
        }
        val (client, transport) = newClient(
            McpClientHandlers(
                listRoots = { emptyList() },
                sample = { request -> McpSamplingResult(content = listOf(), model = "model") },
                elicit = { McpElicitationResult("decline") },
            ),
            capabilities = custom,
        )
        val capabilities = initializeCapabilities(transport)
        assertEquals(true, ((capabilities["experimental"] as JsonObject)["feature"] as JsonPrimitive).content.toBoolean())
        assertEquals(JsonObject(emptyMap()), (capabilities["sampling"] as JsonObject)["tools"])
        assertEquals(JsonPrimitive(true), (capabilities["roots"] as JsonObject)["listChanged"])
        assertEquals(JsonObject(emptyMap()), capabilities["elicitation"])
        client.close()
    }

    @Test
    fun rootsListReturnsHandlerRoots() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(
                listRoots = {
                    listOf(
                        McpRoot("file:///project", "project"),
                        McpRoot("file:///unlabeled"),
                    )
                },
            ),
        )
        sendServerRequest(transport, "roots/list")
        val response = awaitResponse(transport)
        val roots = assertNotNull(response.result)["roots"] as JsonArray
        assertEquals(2, roots.size)
        assertEquals("file:///project", ((roots[0] as JsonObject)["uri"] as JsonPrimitive).content)
        assertEquals("project", ((roots[0] as JsonObject)["name"] as JsonPrimitive).content)
        assertEquals("file:///unlabeled", ((roots[1] as JsonObject)["uri"] as JsonPrimitive).content)
        assertFalse((roots[1] as JsonObject).containsKey("name"))
        client.close()
    }

    @Test
    fun rootsListWithoutHandlerReturnsMethodNotFound() = runBlocking {
        val (client, transport) = newClient()
        sendServerRequest(transport, "roots/list")
        val error = awaitErrorResponse(transport)
        assertEquals(-32601, error.code)
        assertTrue(error.message.contains("roots/list"))
        client.close()
    }

    @Test
    fun samplingCreateMessageInvokesHandlerWithParsedParams() = runBlocking {
        var captured: McpSamplingRequest? = null
        val (client, transport) = newClient(
            McpClientHandlers(
                sample = { request ->
                    captured = request
                    McpSamplingResult(
                        role = "assistant",
                        content = listOf(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive("hi there"))
                            },
                        ),
                        model = "test-model",
                        stopReason = "endTurn",
                    )
                },
            ),
        )
        val params = samplingParams(maxTokens = 256).toMutableMap().apply {
            put("temperature", JsonPrimitive(0.5))
            put("stopSequences", JsonArray(listOf(JsonPrimitive("STOP"))))
            put("modelPreferences", buildJsonObject { put("speedPriority", JsonPrimitive(0.7)) })
            put("_meta", buildJsonObject { put("progressToken", JsonPrimitive("p1")) })
        }
        sendServerRequest(transport, "sampling/createMessage", JsonObject(params))

        val response = awaitResponse(transport)
        val result = assertNotNull(response.result)
        assertEquals("assistant", (result["role"] as JsonPrimitive).content)
        assertEquals("test-model", (result["model"] as JsonPrimitive).content)
        assertEquals("endTurn", (result["stopReason"] as JsonPrimitive).content)
        val content = result["content"] as JsonArray
        assertEquals(1, content.size)
        assertEquals("hi there", ((content[0] as JsonObject)["text"] as JsonPrimitive).content)

        val request = assertNotNull(captured)
        assertEquals(1, request.messages.size)
        assertEquals("be nice", request.systemPrompt)
        assertEquals(256L, request.maxTokens)
        assertEquals(0.5, assertNotNull(request.temperature))
        assertEquals(listOf("STOP"), request.stopSequences)
        assertEquals(0.7, (assertNotNull(request.modelPreferences)["speedPriority"] as JsonPrimitive).content.toDouble())
        assertEquals("p1", (((request.raw["_meta"] as JsonObject)["progressToken"]) as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun samplingDefaultsMaxTokensAndOmitsStopReason() = runBlocking {
        var captured: McpSamplingRequest? = null
        val singleBlock = buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("block"))
        }
        val (client, transport) = newClient(
            McpClientHandlers(
                sample = { request ->
                    captured = request
                    McpSamplingResult.of(singleBlock, "mini-model")
                },
            ),
        )
        sendServerRequest(transport, "sampling/createMessage", samplingParams(withSystemPrompt = false))
        val response = awaitResponse(transport)
        val result = assertNotNull(response.result)
        assertEquals("assistant", (result["role"] as JsonPrimitive).content)
        assertFalse(result.containsKey("stopReason"))
        assertEquals(1, (result["content"] as JsonArray).size)

        val request = assertNotNull(captured)
        assertEquals(4096L, request.maxTokens)
        assertNull(request.systemPrompt)
        assertNull(request.temperature)
        assertTrue(request.stopSequences.isEmpty())
        assertNull(request.modelPreferences)
        client.close()
    }

    @Test
    fun samplingInvalidParamsReturnsInvalidParamsError() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(sample = { request -> McpSamplingResult(content = listOf(), model = "model") }),
        )
        sendServerRequest(
            transport,
            "sampling/createMessage",
            buildJsonObject { put("messages", JsonArray(emptyList())) },
        )
        val error = awaitErrorResponse(transport)
        assertEquals(-32602, error.code)
        assertTrue(error.message.contains("messages"))
        client.close()
    }

    @Test
    fun samplingHandlerFailureReturnsInternalError() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(sample = { throw IllegalStateException("model unavailable") }),
        )
        sendServerRequest(transport, "sampling/createMessage", samplingParams())
        val error = awaitErrorResponse(transport)
        assertEquals(-32603, error.code)
        assertTrue(error.message.contains("model unavailable"))
        client.close()
    }

    @Test
    fun samplingWithoutHandlerReturnsMethodNotFound() = runBlocking {
        val (client, transport) = newClient()
        sendServerRequest(transport, "sampling/createMessage", samplingParams())
        val error = awaitErrorResponse(transport)
        assertEquals(-32601, error.code)
        assertTrue(error.message.contains("sampling/createMessage"))
        client.close()
    }

    @Test
    fun elicitationAcceptIncludesContent() = runBlocking {
        var captured: McpElicitationRequest? = null
        val (client, transport) = newClient(
            McpClientHandlers(
                elicit = { request ->
                    captured = request
                    McpElicitationResult(
                        action = "accept",
                        content = buildJsonObject { put("name", JsonPrimitive("Alice")) },
                    )
                },
            ),
        )
        val requestedSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject { put("name", buildJsonObject { put("type", JsonPrimitive("string")) }) },
            )
        }
        sendServerRequest(
            transport,
            "elicitation/create",
            buildJsonObject {
                put("message", JsonPrimitive("What is your name?"))
                put("requestedSchema", requestedSchema)
            },
        )
        val response = awaitResponse(transport)
        val result = assertNotNull(response.result)
        assertEquals("accept", (result["action"] as JsonPrimitive).content)
        assertEquals("Alice", ((result["content"] as JsonObject)["name"] as JsonPrimitive).content)

        val request = assertNotNull(captured)
        assertEquals("What is your name?", request.message)
        assertEquals(requestedSchema, request.requestedSchema)
        client.close()
    }

    @Test
    fun elicitationDeclineOmitsContent() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(elicit = { McpElicitationResult(action = "decline") }),
        )
        sendServerRequest(
            transport,
            "elicitation/create",
            buildJsonObject {
                put("message", JsonPrimitive("Continue?"))
                put("requestedSchema", buildJsonObject { put("type", JsonPrimitive("object")) })
            },
        )
        val response = awaitResponse(transport)
        val result = assertNotNull(response.result)
        assertEquals("decline", (result["action"] as JsonPrimitive).content)
        assertFalse(result.containsKey("content"))
        client.close()
    }

    @Test
    fun elicitationHandlerFailureReturnsInternalError() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(elicit = { throw IllegalStateException("ui dismissed") }),
        )
        sendServerRequest(
            transport,
            "elicitation/create",
            buildJsonObject {
                put("message", JsonPrimitive("Continue?"))
                put("requestedSchema", buildJsonObject { put("type", JsonPrimitive("object")) })
            },
        )
        val error = awaitErrorResponse(transport)
        assertEquals(-32603, error.code)
        assertTrue(error.message.contains("ui dismissed"))
        client.close()
    }

    @Test
    fun elicitationInvalidParamsReturnsInvalidParamsError() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(elicit = { McpElicitationResult("decline") }),
        )
        sendServerRequest(
            transport,
            "elicitation/create",
            buildJsonObject { put("message", JsonPrimitive("missing schema")) },
        )
        val error = awaitErrorResponse(transport)
        assertEquals(-32602, error.code)
        assertTrue(error.message.contains("requestedSchema"))
        client.close()
    }

    @Test
    fun elicitationWithoutHandlerReturnsMethodNotFound() = runBlocking {
        val (client, transport) = newClient()
        sendServerRequest(
            transport,
            "elicitation/create",
            buildJsonObject {
                put("message", JsonPrimitive("Continue?"))
                put("requestedSchema", buildJsonObject { put("type", JsonPrimitive("object")) })
            },
        )
        val error = awaitErrorResponse(transport)
        assertEquals(-32601, error.code)
        client.close()
    }

    @Test
    fun notifyRootsChangedSendsNotification() = runBlocking {
        val (client, transport) = newClient(McpClientHandlers(listRoots = { emptyList() }))
        client.notifyRootsChanged()
        val notification = transport.awaitSent { message ->
            message is JsonRpcNotification && message.method == "notifications/roots/list_changed"
        } as JsonRpcNotification
        assertNull(notification.params)
        client.close()
    }

    @Test
    fun notifyRootsChangedWithoutHandlerIsNoOp() = runBlocking {
        val (client, transport) = newClient()
        client.notifyRootsChanged()
        delay(50)
        val notifications = transport.sent().filterIsInstance<JsonRpcNotification>()
            .filter { it.method == "notifications/roots/list_changed" }
        assertTrue(notifications.isEmpty())
        client.close()
    }

    @Test
    fun pingAndUnknownRequestsStillUseExistingBehavior() = runBlocking {
        val (client, transport) = newClient()
        sendServerRequest(transport, "ping", id = 200)
        assertNotNull(awaitResponse(transport, 200).result)
        sendServerRequest(transport, "unknown/method", id = 201)
        val error = awaitErrorResponse(transport, 201)
        assertEquals(-32601, error.code)
        assertTrue(error.message.contains("unknown/method"))
        client.close()
    }

    @Test
    fun serverRequestResponsesMatchRequestIds() = runBlocking {
        val (client, transport) = newClient(
            McpClientHandlers(
                listRoots = { listOf(McpRoot("file:///a")) },
                elicit = { McpElicitationResult("decline") },
            ),
        )
        sendServerRequest(transport, "roots/list", id = 300)
        sendServerRequest(
            transport,
            "elicitation/create",
            buildJsonObject {
                put("message", JsonPrimitive("Continue?"))
                put("requestedSchema", buildJsonObject { put("type", JsonPrimitive("object")) })
            },
            id = 301,
        )
        val rootsResponse = awaitResponse(transport, 300)
        val elicitationResponse = awaitResponse(transport, 301)
        assertEquals(JsonRpcId.Number(300), rootsResponse.id)
        assertEquals(JsonRpcId.Number(301), elicitationResponse.id)
        assertEquals("decline", ((assertNotNull(elicitationResponse.result))["action"] as JsonPrimitive).content)
        client.close()
    }
}
