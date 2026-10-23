package dev.pi.coding.mcp

import kotlinx.serialization.json.JsonElement
import dev.pi.ai.PiJson
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import dev.pi.ai.ImageContent
import dev.pi.mcp.McpClient
import dev.pi.mcp.McpClientOptions
import dev.pi.mcp.McpTransport
import dev.pi.mcp.McpTransportEvent
import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.McpTool
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class McpScriptedTransport : McpTransport {
    private val channel = Channel<McpTransportEvent>(Channel.UNLIMITED)

    override val events: Flow<McpTransportEvent> = channel.receiveAsFlow()

    val requests = mutableListOf<JsonRpcRequest>()
    val notifications = mutableListOf<JsonRpcNotification>()
    var responder: (suspend (JsonRpcRequest) -> Unit)? = null
    var closed = false

    override suspend fun start() = Unit

    override suspend fun send(message: JsonRpcMessage, signal: RunSignal?) {
        when (message) {
            is JsonRpcRequest -> {
                requests.add(message)
                responder?.invoke(message)
            }

            is JsonRpcNotification -> notifications.add(message)
            else -> Unit
        }
    }

    suspend fun respond(request: JsonRpcRequest, result: JsonObject) {
        channel.send(McpTransportEvent.Message(JsonRpcResponse(request.id, result)))
    }

    suspend fun respondError(request: JsonRpcRequest, code: Int, message: String, data: JsonElement? = null) {
        channel.send(McpTransportEvent.Message(JsonRpcErrorResponse(request.id, code, message, data)))
    }

    suspend fun notify(method: String, params: JsonObject?) {
        channel.send(McpTransportEvent.Message(JsonRpcNotification(method, params)))
    }

    override suspend fun close() {
        closed = true
        channel.trySend(McpTransportEvent.Closed(null))
    }
}

internal val mcpInitializeResultJson: JsonObject = buildJsonObject {
    put("protocolVersion", "2025-11-25")
    put(
        "capabilities",
        buildJsonObject {
            put("tools", JsonObject(emptyMap()))
            put("resources", JsonObject(emptyMap()))
        },
    )
    put(
        "serverInfo",
        buildJsonObject {
            put("name", "test-server")
            put("version", "1.0.0")
        },
    )
}

internal suspend fun mcpConnectedClient(
    transport: McpScriptedTransport,
    requestTimeoutMs: Long = 60_000,
): McpClient {
    transport.responder = { request ->
        if (request.method == "initialize") transport.respond(request, mcpInitializeResultJson)
    }
    val client = McpClient("srv", transport, McpClientOptions(requestTimeoutMs = requestTimeoutMs))
    client.initialize()
    return client
}

internal fun mcpTextBlock(text: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("text"))
    put("text", JsonPrimitive(text))
}

internal fun mcpImageBlock(data: String, mimeType: String): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("image"))
    put("data", JsonPrimitive(data))
    put("mimeType", JsonPrimitive(mimeType))
}

internal fun mcpCallToolResultJson(
    content: List<JsonObject>,
    structuredContent: JsonElement? = null,
    isError: Boolean = false,
): JsonObject = buildJsonObject {
    put("content", JsonArray(content))
    if (structuredContent != null) put("structuredContent", structuredContent)
    if (isError) put("isError", JsonPrimitive(true))
}

class McpToolAdapterTest {
    private fun tool(
        name: String = "echo",
        description: String? = "Echoes input",
        title: String? = null,
        schema: JsonObject = JsonObject(emptyMap()),
    ): McpTool = McpTool(name = name, description = description, inputSchema = schema, title = title)

    @Test
    fun sanitizesNamesNormalizesSchemaAndTruncatesDescriptions() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        val adapter = McpToolAdapter("my-server", client)
        val adapted = adapter.adapt(
            tool(
                name = "do-thing.v2",
                description = "x".repeat(2000),
                schema = buildJsonObject { put("properties", JsonObject(emptyMap())) },
            ),
        )
        assertEquals("mcp__my_server__do_thing_v2", adapted.name)
        assertEquals(1024, adapted.description.length)
        assertEquals("my-server/do-thing.v2", adapted.label)
        assertEquals(JsonPrimitive("object"), adapted.parameters["type"])
        assertEquals(JsonObject(emptyMap()), adapted.parameters["properties"])
        client.close()
    }

    @Test
    fun nameCollisionsAndLongNamesGetStableHashSuffix() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        val adapter = McpToolAdapter("srv", client)
        val firstName = adapter.adapt(tool(name = "a-b")).name
        val secondName = adapter.adapt(tool(name = "a_b"), setOf(firstName)).name
        assertEquals("mcp__srv__a_b", firstName)
        assertTrue(secondName.startsWith("mcp__srv__a_b_"))
        assertTrue(secondName.length <= 64)
        assertEquals(secondName, adapter.adapt(tool(name = "a_b"), setOf(firstName)).name)
        val longTool = tool(name = "very".repeat(20))
        val longName = adapter.adapt(longTool).name
        assertEquals(64, longName.length)
        assertEquals(longName, adapter.adapt(longTool).name)
        client.close()
    }

    @Test
    fun descriptionFallsBackToTitleThenDefaults() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        val adapter = McpToolAdapter("srv", client)
        assertEquals("A title", adapter.adapt(tool(description = "  ", title = "A title")).description)
        assertEquals("MCP tool fallback from server srv", adapter.adapt(tool(name = "fallback", description = null)).description)
        client.close()
    }

    @Test
    fun mapsTextImageAudioAndResourceLinkContent() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "tools/call") {
                transport.respond(
                    request,
                    mcpCallToolResultJson(
                        listOf(
                            mcpTextBlock("hello"),
                            mcpImageBlock("aGVsbG8=", "image/png"),
                            buildJsonObject {
                                put("type", JsonPrimitive("audio"))
                                put("data", JsonPrimitive("aGVsbG8="))
                                put("mimeType", JsonPrimitive("audio/wav"))
                            },
                            buildJsonObject {
                                put("type", JsonPrimitive("resource_link"))
                                put("uri", JsonPrimitive("file:///tmp/report.pdf"))
                                put("name", JsonPrimitive("report"))
                                put("mimeType", JsonPrimitive("application/pdf"))
                                put("size", JsonPrimitive(2048))
                            },
                        ),
                    ),
                )
            }
        }
        val adapter = McpToolAdapter("srv", client)
        val result = adapter.adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
        assertEquals(4, result.content.size)
        assertEquals("hello", (result.content[0] as TextContent).text)
        assertEquals("image/png", (result.content[1] as ImageContent).mimeType)
        assertEquals("aGVsbG8=", (result.content[1] as ImageContent).data)
        assertEquals("[audio audio/wav omitted]", (result.content[2] as TextContent).text)
        val link = (result.content[3] as TextContent).text
        assertTrue(link.contains("file:///tmp/report.pdf"))
        assertTrue(link.contains("application/pdf"))
        assertTrue(link.contains("2.0KB"))
        assertTrue(link.contains("read_mcp_resource"))
        assertNull(result.isError)
        val details = assertNotNull(result.details) as JsonObject
        assertEquals("srv", (details["server"] as JsonPrimitive).content)
        assertEquals("echo", (details["tool"] as JsonPrimitive).content)
        client.close()
    }

    @Test
    fun mapsErrorResultsAndStructuredContent() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "tools/call") {
                transport.respond(
                    request,
                    mcpCallToolResultJson(emptyList(), structuredContent = buildJsonObject { put("ok", false) }, isError = true),
                )
            }
        }
        val adapter = McpToolAdapter("srv", client)
        val result = adapter.adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
        assertEquals(true, result.isError)
        assertEquals("{\"ok\":false}", (result.content.single() as TextContent).text)
        val structured = assertNotNull(result.structuredContent) as JsonObject
        assertEquals(JsonPrimitive(true), structured["isError"])
        assertEquals(buildJsonObject { put("ok", false) }, structured["structuredContent"])
        client.close()
    }

    @Test
    fun structuredContentBecomesTextWhenContentIsEmpty() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "tools/call") {
                transport.respond(request, mcpCallToolResultJson(emptyList(), structuredContent = buildJsonObject { put("ok", true) }))
            }
        }
        val adapter = McpToolAdapter("srv", client)
        val result = adapter.adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
        assertEquals("{\"ok\":true}", (result.content.single() as TextContent).text)
        client.close()
    }

    @Test
    fun truncatesLongTextAndSavesFullOutput() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/mcp-adapter-truncate".toPath()
        try {
            val fullText = "abcdefghij".repeat(20)
            val transport = McpScriptedTransport()
            val client = mcpConnectedClient(transport)
            transport.responder = { request ->
                if (request.method == "tools/call") transport.respond(request, mcpCallToolResultJson(listOf(mcpTextBlock(fullText))))
            }
            val adapter = McpToolAdapter(
                "srv",
                client,
                McpToolAdapterOptions(maxOutputBytes = 64, outputDirectory = directory, fileSystem = fileSystem),
            )
            val result = adapter.adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
            val text = (result.content.single() as TextContent).text
            assertTrue(text.startsWith("Warning: truncated output (original token count: 50)"))
            assertTrue(text.contains("Total output lines: 1"))
            assertTrue(text.contains("chars truncated"))
            val details = assertNotNull(result.details) as JsonObject
            val fullOutputPath = assertNotNull(details["fullOutputPath"]).let { (it as JsonPrimitive).content }
            assertTrue(text.contains("[Full output: $fullOutputPath"))
            assertEquals(fullText, fileSystem.read(fullOutputPath.toPath()) { readUtf8() })
            client.close()
        } finally {
            fileSystem.deleteRecursively(directory)
        }
    }

    @Test
    fun savesBinaryResourcesToOutputDirectory() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/mcp-adapter-binary".toPath()
        try {
            val payload = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            val encoded = kotlin.io.encoding.Base64.encode(payload)
            val transport = McpScriptedTransport()
            val client = mcpConnectedClient(transport)
            transport.responder = { request ->
                if (request.method == "tools/call") {
                    transport.respond(
                        request,
                        mcpCallToolResultJson(
                            listOf(
                                buildJsonObject {
                                    put("type", JsonPrimitive("resource"))
                                    put(
                                        "resource",
                                        buildJsonObject {
                                            put("uri", JsonPrimitive("file:///tmp/report.pdf"))
                                            put("mimeType", JsonPrimitive("application/pdf"))
                                            put("blob", JsonPrimitive(encoded))
                                        },
                                    )
                                },
                            ),
                        ),
                    )
                }
            }
            val adapter = McpToolAdapter(
                "srv",
                client,
                McpToolAdapterOptions(outputDirectory = directory, fileSystem = fileSystem),
            )
            val result = adapter.adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
            val text = (result.content.single() as TextContent).text
            assertTrue(text.contains("saved to"))
            assertTrue(text.contains("application/pdf"))
            val saved = fileSystem.list(directory)
            assertEquals(1, saved.size)
            assertTrue(saved.single().name.startsWith("mcp-srv-"))
            assertTrue(saved.single().name.endsWith(".pdf"))
            assertTrue(payload.contentEquals(fileSystem.read(saved.single()) { readByteArray() }))
            client.close()
        } finally {
            fileSystem.deleteRecursively(directory)
        }
    }

    @Test
    fun forwardsProgressUpdates() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "tools/call") {
                val token = ((request.params?.get("_meta") as? JsonObject)?.get("progressToken"))
                transport.notify(
                    "notifications/progress",
                    buildJsonObject {
                        if (token != null) put("progressToken", token)
                        put("progress", JsonPrimitive(1))
                        put("total", JsonPrimitive(3))
                        put("message", JsonPrimitive("Working"))
                    },
                )
                transport.respond(request, mcpCallToolResultJson(listOf(mcpTextBlock("done"))))
            }
        }
        val adapter = McpToolAdapter("srv", client)
        val updates = mutableListOf<String>()
        adapter.adapt(tool()).execute(
            "call-1",
            JsonObject(emptyMap()),
            null,
            { update -> updates.add((update.content.single() as TextContent).text) },
        )
        assertEquals(listOf("Working"), updates)
        client.close()
    }

    @Test
    fun mapsRpcErrorsAndTimeoutsToErrorResults() = runTest {
        val errorTransport = McpScriptedTransport()
        val errorClient = mcpConnectedClient(errorTransport)
        errorTransport.responder = { request ->
            if (request.method == "tools/call") errorTransport.respondError(request, -32602, "bad arguments")
        }
        val errorResult = McpToolAdapter("srv", errorClient).adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
        assertEquals(true, errorResult.isError)
        assertEquals("bad arguments", (errorResult.content.single() as TextContent).text)
        errorClient.close()

        val timeoutTransport = McpScriptedTransport()
        val timeoutClient = mcpConnectedClient(timeoutTransport, requestTimeoutMs = 25)
        timeoutTransport.responder = { request ->
            if (request.method == "tools/call") Unit
        }
        val timeoutResult = McpToolAdapter("srv", timeoutClient).adapt(tool()).execute("call-1", JsonObject(emptyMap()), null, null)
        assertEquals(true, timeoutResult.isError)
        assertTrue((timeoutResult.content.single() as TextContent).text.contains("timed out"))
        timeoutClient.close()
    }

    @Test
    fun parsesProvisionedToolSchemas() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        val adapter = McpToolAdapter("srv", client)
        val schema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put("query", buildJsonObject { put("type", JsonPrimitive("string")) })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        }
        val adapted = adapter.adapt(tool(schema = schema))
        assertEquals(schema, adapted.parameters)
        client.close()
    }
}
