package dev.pi.mcp

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.LATEST_PROTOCOL_VERSION
import dev.pi.mcp.protocol.McpCallResult
import dev.pi.mcp.protocol.McpLogMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpClientTest {
    private fun initializeResult(version: String = LATEST_PROTOCOL_VERSION): JsonObject = buildJsonObject {
        put("protocolVersion", version)
        put(
            "capabilities",
            buildJsonObject {
                put("tools", buildJsonObject {})
                put("resources", buildJsonObject {})
                put("prompts", buildJsonObject {})
            },
        )
        put(
            "serverInfo",
            buildJsonObject {
                put("name", "test-server")
                put("version", "1.0.0")
            },
        )
        put("instructions", "be helpful")
    }

    private fun toolJson(name: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", "$name tool")
        put(
            "inputSchema",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            },
        )
    }

    private fun textResult(text: String): JsonObject = buildJsonObject {
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                ),
            ),
        )
    }

    private fun callText(result: McpCallResult): String =
        (result.content.first()["text"] as JsonPrimitive).content

    private suspend fun newClient(
        transport: InMemoryTransport = InMemoryTransport(),
        options: McpClientOptions = McpClientOptions(requestTimeoutMs = 5_000),
        serverVersion: String = LATEST_PROTOCOL_VERSION,
    ): Pair<McpClient, InMemoryTransport> {
        transport.responder = { request ->
            if (request.method == "initialize") transport.respond(initializeResult(serverVersion), request.id)
        }
        val client = McpClient("test-server", transport, options)
        client.initialize()
        return client to transport
    }

    @Test
    fun initializeSendsHandshakeAndRecordsServerInfo() = runBlocking {
        val transport = InMemoryTransport()
        transport.responder = { request ->
            if (request.method == "initialize") transport.respond(initializeResult(), request.id)
        }
        val client = McpClient(
            "test-server",
            transport,
            McpClientOptions(clientInfo = McpImplementationInfo("tester", "9.9.9", "Test Client")),
        )
        val result = client.initialize()

        assertEquals(LATEST_PROTOCOL_VERSION, result.protocolVersion)
        assertEquals("test-server", result.serverInfo.name)
        assertEquals("be helpful", result.instructions)
        assertEquals("be helpful", client.instructions)
        assertEquals("test-server", assertNotNull(client.serverInfo).name)
        assertTrue(assertNotNull(client.serverCapabilities).hasTools)
        assertTrue(client.isInitialized)

        val sent = transport.sent()
        val initialize = assertNotNull(
            sent.filterIsInstance<JsonRpcRequest>().singleOrNull { it.method == "initialize" },
        )
        val params = assertNotNull(initialize.params)
        assertEquals(LATEST_PROTOCOL_VERSION, (params["protocolVersion"] as JsonPrimitive).content)
        val clientInfo = assertNotNull(params["clientInfo"] as? JsonObject)
        assertEquals("tester", (clientInfo["name"] as JsonPrimitive).content)
        assertEquals("9.9.9", (clientInfo["version"] as JsonPrimitive).content)
        assertEquals("Test Client", (clientInfo["title"] as JsonPrimitive).content)
        assertNotNull(params["capabilities"] as? JsonObject)

        val initialized = sent.filterIsInstance<JsonRpcNotification>()
            .single { it.method == "notifications/initialized" }
        assertNull(initialized.params)
        client.close()
    }

    @Test
    fun initializeAcceptsDifferentSupportedServerVersion() = runBlocking {
        val (client, _) = newClient(serverVersion = "2025-06-18")
        assertEquals("2025-06-18", client.protocolVersion)
        assertTrue(client.isInitialized)
        client.close()
    }

    @Test
    fun initializeRejectsUnsupportedServerVersion() = runBlocking {
        val transport = InMemoryTransport()
        transport.responder = { request ->
            if (request.method == "initialize") transport.respond(initializeResult("1999-01-01"), request.id)
        }
        val client = McpClient("test-server", transport)
        val error = assertFailsWith<McpProtocolException> { client.initialize() }
        assertTrue(error.message!!.contains("unsupported protocol version"))
        assertTrue(transport.closed)
    }

    @Test
    fun initializeRejectsUnsupportedRequestedVersion() = runBlocking {
        val transport = InMemoryTransport()
        val client = McpClient("test-server", transport, McpClientOptions(protocolVersion = "1999-01-01"))
        assertFailsWith<McpProtocolException> { client.initialize() }
        assertTrue(transport.closed)
    }

    @Test
    fun initializeTwiceThrows() = runBlocking {
        val (client, _) = newClient()
        val error = assertFailsWith<McpException> { client.initialize() }
        assertTrue(error.message!!.contains("already initialized"))
        client.close()
    }

    @Test
    fun listToolsFollowsCursorPagination() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            when (request.method) {
                "tools/list" -> {
                    val cursor = (request.params?.get("cursor") as? JsonPrimitive)?.content
                    if (cursor == null) {
                        transport.respond(
                            buildJsonObject {
                                put("tools", JsonArray(listOf(toolJson("alpha"))))
                                put("nextCursor", "page-2")
                            },
                            request.id,
                        )
                    } else {
                        transport.respond(
                            buildJsonObject {
                                put("tools", JsonArray(listOf(toolJson("beta"))))
                                put("nextCursor", JsonNull)
                            },
                            request.id,
                        )
                    }
                }
            }
        }

        val tools = client.listTools()
        assertEquals(listOf("alpha", "beta"), tools.map { it.name })
        assertEquals("alpha tool", tools[0].description)

        val requests = transport.sent().filterIsInstance<JsonRpcRequest>().filter { it.method == "tools/list" }
        assertEquals(2, requests.size)
        assertNull(requests[0].params)
        assertEquals("page-2", (assertNotNull(requests[1].params)["cursor"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun listToolsPageStopsOnNullOrEmptyCursor() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            when (request.method) {
                "tools/list" -> transport.respond(
                    buildJsonObject {
                        put("tools", JsonArray(listOf(toolJson("only"))))
                        put("nextCursor", "")
                    },
                    request.id,
                )
            }
        }

        val page = client.listToolsPage("start")
        assertEquals(listOf("only"), page.tools.map { it.name })
        assertNull(page.nextCursor)
        client.close()
    }

    @Test
    fun callToolSendsArgumentsAndParsesResult() = runBlocking {
        val (client, transport) = newClient()
        var capturedParams: JsonObject? = null
        transport.responder = { request ->
            when (request.method) {
                "tools/call" -> {
                    capturedParams = request.params
                    transport.respond(
                        buildJsonObject {
                            put("content", textResult("ok")["content"]!!)
                            put("isError", false)
                            put("structuredContent", buildJsonObject { put("value", 1) })
                        },
                        request.id,
                    )
                }
            }
        }

        val result = client.callTool("lookup", buildJsonObject { put("query", "pi") })
        val params = assertNotNull(capturedParams)
        assertEquals("lookup", (params["name"] as JsonPrimitive).content)
        assertEquals("pi", ((params["arguments"] as JsonObject)["query"] as JsonPrimitive).content)
        assertEquals("ok", callText(result))
        assertFalse(result.isError)
        assertEquals(JsonPrimitive(1), (result.structuredContent as JsonObject)["value"])
        client.close()
    }

    @Test
    fun callToolRpcErrorBecomesMcpRpcException() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            when (request.method) {
                "tools/call" -> transport.respondError(
                    request.id,
                    code = -32000,
                    message = "tool exploded",
                    data = buildJsonObject { put("detail", "boom") },
                )
            }
        }

        val error = assertFailsWith<McpRpcException> {
            client.callTool("lookup", JsonObject(emptyMap()))
        }
        assertEquals(-32000, error.code)
        assertEquals("tool exploded", error.message)
        assertEquals("boom", ((assertNotNull(error.data) as JsonObject)["detail"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun requestTimesOutAndSendsCancelledNotification() = runBlocking {
        val transport = InMemoryTransport()
        transport.responder = { request ->
            if (request.method == "initialize") transport.respond(initializeResult(), request.id)
        }
        val client = McpClient("test-server", transport, McpClientOptions(requestTimeoutMs = 50))
        client.initialize()
        transport.responder = { }

        assertFailsWith<McpTimeoutException> { client.listTools() }

        val toolsRequest = transport.sent().filterIsInstance<JsonRpcRequest>()
            .single { it.method == "tools/list" }
        val requestId = (toolsRequest.id as JsonRpcId.Number).value
        val cancelled = transport.awaitSent { message ->
            message is JsonRpcNotification && message.method == "notifications/cancelled"
        } as JsonRpcNotification
        val params = assertNotNull(cancelled.params)
        assertEquals(requestId, (params["requestId"] as JsonPrimitive).content.toLong())
        assertEquals("Request timed out", (params["reason"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun signalAbortRejectsPendingRequestAndNotifiesServer() = runBlocking {
        val (client, transport) = newClient()
        val gate = CompletableDeferred<Unit>()
        transport.responder = { request ->
            if (request.method == "tools/list") gate.await()
        }
        val signal = RunSignal()
        val pending = async {
            runCatching { client.listTools(McpRequestOptions(signal = signal)) }
        }
        val request = transport.awaitSent { it is JsonRpcRequest && it.method == "tools/list" } as JsonRpcRequest
        val requestId = (request.id as JsonRpcId.Number).value
        signal.abort("user cancelled")

        val outcome = withTimeout(5_000) { pending.await() }
        assertTrue(outcome.isFailure)
        assertIs<RequestAbortedException>(outcome.exceptionOrNull())

        val cancelled = transport.awaitSent { message ->
            message is JsonRpcNotification && message.method == "notifications/cancelled"
        } as JsonRpcNotification
        val params = assertNotNull(cancelled.params)
        assertEquals(requestId, (params["requestId"] as JsonPrimitive).content.toLong())
        assertEquals("user cancelled", (params["reason"] as JsonPrimitive).content)
        gate.complete(Unit)
        client.close()
        Unit
    }

    @Test
    fun abortedSignalFailsBeforeSendingRequest() = runBlocking {
        val (client, transport) = newClient()
        val signal = RunSignal()
        signal.abort("already cancelled")
        assertFailsWith<RequestAbortedException> {
            client.listTools(McpRequestOptions(signal = signal))
        }
        val requests = transport.sent().filterIsInstance<JsonRpcRequest>().filter { it.method == "tools/list" }
        assertTrue(requests.isEmpty())
        client.close()
    }

    @Test
    fun progressNotificationsAreDispatchedToRequestCallback() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            if (request.method == "tools/list") {
                val meta = assertNotNull(request.params)["_meta"] as JsonObject
                val token = meta["progressToken"]!!
                transport.notify(
                    "notifications/progress",
                    buildJsonObject {
                        put("progressToken", token)
                        put("progress", 1)
                        put("total", 10)
                        put("message", "step one")
                    },
                )
                transport.notify(
                    "notifications/progress",
                    buildJsonObject {
                        put("progressToken", token)
                        put("progress", 5)
                    },
                )
                transport.respond(
                    buildJsonObject { put("tools", JsonArray(listOf(toolJson("alpha")))) },
                    request.id,
                )
            }
        }

        val updates = mutableListOf<Triple<Double, Double?, String?>>()
        val page = client.listToolsPage(
            null,
            McpRequestOptions(onProgress = { _, progress, total, message ->
                updates.add(Triple(progress, total, message))
            }),
        )

        assertEquals(1, page.tools.size)
        assertEquals(2, updates.size)
        assertEquals(1.0, updates[0].first)
        assertEquals(10.0, updates[0].second)
        assertEquals("step one", updates[0].third)
        assertEquals(5.0, updates[1].first)
        assertNull(updates[1].second)
        client.close()
    }

    @Test
    fun notificationListenerReceivesToolsResourcesAndLogs() = runBlocking {
        val (client, transport) = newClient()
        val toolsChanged = CompletableDeferred<Unit>()
        val resourcesChanged = CompletableDeferred<Unit>()
        val logs = mutableListOf<McpLogMessage>()
        client.setNotificationListener(
            McpNotificationListener(
                onToolsChanged = { toolsChanged.complete(Unit) },
                onResourcesChanged = { resourcesChanged.complete(Unit) },
                onLog = { logs.add(it) },
            ),
        )

        transport.notify("notifications/tools/list_changed")
        transport.notify("notifications/resources/list_changed")
        transport.notify(
            "notifications/message",
            buildJsonObject {
                put("level", "info")
                put("logger", "test")
                put("data", buildJsonObject { put("message", "hello") })
            },
        )

        withTimeout(5_000) {
            toolsChanged.await()
            resourcesChanged.await()
        }
        withTimeout(5_000) {
            while (logs.isEmpty()) delay(5)
        }
        assertEquals("info", logs[0].level)
        assertEquals("test", logs[0].logger)
        assertEquals("hello", ((logs[0].data as JsonObject)["message"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun closeRejectsPendingRequestsAndClosesTransport() = runBlocking {
        val (client, transport) = newClient()
        val gate = CompletableDeferred<Unit>()
        transport.responder = { request ->
            if (request.method == "tools/list") gate.await()
        }
        val pending = async {
            runCatching { client.listTools() }
        }
        transport.awaitSent { it is JsonRpcRequest && it.method == "tools/list" }
        client.close()
        val outcome = withTimeout(5_000) { pending.await() }
        assertTrue(outcome.isFailure)
        assertIs<McpConnectionClosedException>(outcome.exceptionOrNull())
        assertTrue(transport.closed)
        assertFalse(client.isInitialized)
        assertFailsWith<McpConnectionClosedException> { client.listTools() }
        gate.complete(Unit)
        Unit
    }

    @Test
    fun transportCloseRejectsPendingRequests() = runBlocking {
        val (client, transport) = newClient()
        val gate = CompletableDeferred<Unit>()
        transport.responder = { request ->
            if (request.method == "tools/list") gate.await()
        }
        val pending = async {
            runCatching { client.listTools() }
        }
        transport.awaitSent { it is JsonRpcRequest && it.method == "tools/list" }
        transport.fail(McpConnectionClosedException("dropped"))
        val outcome = withTimeout(5_000) { pending.await() }
        assertTrue(outcome.isFailure)
        assertIs<McpConnectionClosedException>(outcome.exceptionOrNull())
        assertFalse(client.isInitialized)
        gate.complete(Unit)
        client.close()
    }

    @Test
    fun concurrentRequestsAreMatchedById() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            if (request.method == "tools/call") {
                val name = (assertNotNull(request.params)["name"] as JsonPrimitive).content
                if (name == "slow") delay(60) else delay(1)
                transport.respond(textResult(name), request.id)
            }
        }

        val slow = async { client.callTool("slow", JsonObject(emptyMap())) }
        val fast = async { client.callTool("fast", JsonObject(emptyMap())) }
        assertEquals("fast", callText(fast.await()))
        assertEquals("slow", callText(slow.await()))
        client.close()
    }

    @Test
    fun pingRoundTrip() = runBlocking {
        val (client, transport) = newClient()
        var pinged = false
        transport.responder = { request ->
            if (request.method == "ping") {
                pinged = true
                transport.respond(JsonObject(emptyMap()), request.id)
            }
        }
        client.ping()
        assertTrue(pinged)
        client.close()
    }

    @Test
    fun unknownResponseIdsAreIgnored() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            if (request.method == "ping") transport.respond(JsonObject(emptyMap()), request.id)
        }
        transport.emit(
            dev.pi.mcp.protocol.JsonRpcResponse(JsonRpcId.Number(999), JsonObject(emptyMap())),
        )
        client.ping()
        client.close()
    }

    @Test
    fun parsesResourceAndPromptResults() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            when (request.method) {
                "resources/list" -> transport.respond(
                    buildJsonObject {
                        put(
                            "resources",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("uri", "file:///a.txt")
                                        put("name", "a")
                                        put("mimeType", "text/plain")
                                        put("size", 5)
                                    },
                                ),
                            ),
                        )
                        put("nextCursor", "next")
                    },
                    request.id,
                )

                "resources/templates/list" -> transport.respond(
                    buildJsonObject {
                        put(
                            "resourceTemplates",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("uriTemplate", "file:///{path}")
                                        put("name", "template")
                                    },
                                ),
                            ),
                        )
                    },
                    request.id,
                )

                "resources/read" -> transport.respond(
                    buildJsonObject {
                        put(
                            "contents",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("uri", "file:///a.txt")
                                        put("mimeType", "text/plain")
                                        put("text", "hello")
                                    },
                                ),
                            ),
                        )
                    },
                    request.id,
                )

                "prompts/list" -> transport.respond(
                    buildJsonObject {
                        put(
                            "prompts",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("name", "summarize")
                                        put("description", "Summarize")
                                        put(
                                            "arguments",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put("name", "text")
                                                        put("required", true)
                                                    },
                                                ),
                                            ),
                                        )
                                    },
                                ),
                            ),
                        )
                    },
                    request.id,
                )

                "prompts/get" -> transport.respond(
                    buildJsonObject {
                        put("description", "Summarize")
                        put(
                            "messages",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", buildJsonObject { put("type", "text"); put("text", "hi") })
                                    },
                                ),
                            ),
                        )
                    },
                    request.id,
                )
            }
        }

        val resources = client.listResources()
        assertEquals("file:///a.txt", resources.resources[0].uri)
        assertEquals("a", resources.resources[0].name)
        assertEquals("text/plain", resources.resources[0].mimeType)
        assertEquals(5L, resources.resources[0].size)
        assertEquals("next", resources.nextCursor)

        val templates = client.listResourceTemplates()
        assertEquals("file:///{path}", templates.templates[0].uriTemplate)
        assertEquals("template", templates.templates[0].name)

        val read = client.readResource("file:///a.txt")
        assertEquals("hello", read.contents[0].text)
        assertEquals("text/plain", read.contents[0].mimeType)

        val prompts = client.listPrompts()
        assertEquals("summarize", prompts.prompts[0].name)
        assertEquals("text", prompts.prompts[0].arguments[0].name)
        assertTrue(prompts.prompts[0].arguments[0].required)

        val prompt = client.getPrompt("summarize", mapOf("text" to "hello"))
        assertEquals("Summarize", prompt.description)
        assertEquals(1, prompt.messages.size)
        client.close()
    }

    @Test
    fun invalidListResultBecomesProtocolException() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            if (request.method == "tools/list") {
                transport.respond(
                    buildJsonObject { put("tools", "not-an-array") },
                    request.id,
                )
            }
        }
        assertFailsWith<McpProtocolException> { client.listToolsPage(null) }
        client.close()
    }

    @Test
    fun duplicatePaginationCursorIsRejected() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            if (request.method == "tools/list") {
                transport.respond(
                    buildJsonObject {
                        put("tools", JsonArray(listOf(toolJson("alpha"))))
                        put("nextCursor", "same")
                    },
                    request.id,
                )
            }
        }
        val error = assertFailsWith<McpProtocolException> { client.listTools() }
        assertTrue(error.message!!.contains("duplicate cursor"))
        client.close()
    }

    @Test
    fun serverPingRequestIsAnswered() = runBlocking {
        val (client, transport) = newClient()
        transport.emit(
            JsonRpcRequest(JsonRpcId.Number(42), "ping"),
        )
        val response = transport.awaitSent { message ->
            message is dev.pi.mcp.protocol.JsonRpcResponse && message.id == JsonRpcId.Number(42)
        }
        assertEquals(JsonRpcId.Number(42), (response as dev.pi.mcp.protocol.JsonRpcResponse).id)
        client.close()
    }

    @Test
    fun serverUnknownRequestGetsMethodNotFoundError() = runBlocking {
        val (client, transport) = newClient()
        transport.emit(
            JsonRpcRequest(JsonRpcId.Number(43), "sampling/createMessage"),
        )
        val response = transport.awaitSent { message ->
            message is JsonRpcErrorResponse && message.id == JsonRpcId.Number(43)
        } as JsonRpcErrorResponse
        assertEquals(-32601, response.code)
        assertTrue(response.message.contains("sampling/createMessage"))
        client.close()
    }

    @Test
    fun responseWithStructuredContentOnlyIsAccepted() = runBlocking {
        val (client, transport) = newClient()
        transport.responder = { request ->
            if (request.method == "tools/call") {
                transport.respond(
                    buildJsonObject { put("structuredContent", buildJsonObject { put("ok", true) }) },
                    request.id,
                )
            }
        }
        val result = client.callTool("lookup", JsonObject(emptyMap()))
        assertTrue(result.content.isEmpty())
        assertEquals(JsonPrimitive(true), (assertNotNull(result.structuredContent) as JsonObject)["ok"])
        client.close()
    }

    @Test
    fun notificationWithoutListenerDoesNotFail() = runBlocking {
        val (client, transport) = newClient()
        transport.notify("notifications/tools/list_changed")
        transport.notify("notifications/message", buildJsonObject { put("level", "info") })
        delay(20)
        assertTrue(client.isInitialized)
        client.close()
    }

}
