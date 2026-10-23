package dev.pi.coding.mcp

import dev.pi.ai.ImageContent
import dev.pi.ai.PiJson
import dev.pi.ai.TextContent
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
import kotlin.test.assertTrue

class McpResourceToolsTest {
    private fun JsonObject.stringField(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun exposesTheThreeResourceTools() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        val resourceTools = mcpResourceTools("srv", client)
        assertEquals(
            listOf("list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource"),
            resourceTools.map { it.name },
        )
        val read = resourceTools.last()
        assertEquals(JsonArray(listOf(JsonPrimitive("uri"))), read.parameters["required"])
        assertEquals(JsonPrimitive(false), read.parameters["additionalProperties"])
        client.close()
    }

    @Test
    fun listResourcesReturnsJsonWithServerAndCursor() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "resources/list") {
                transport.respond(
                    request,
                    buildJsonObject {
                        put(
                            "resources",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("uri", JsonPrimitive("file:///a.txt"))
                                        put("name", JsonPrimitive("a"))
                                        put("mimeType", JsonPrimitive("text/plain"))
                                        put("_meta", buildJsonObject { put("hidden", JsonPrimitive(true)) })
                                    },
                                ),
                            ),
                        )
                        put("nextCursor", JsonPrimitive("cursor-2"))
                    },
                )
            }
        }
        val listTool = mcpResourceTools("srv", client).first()
        val result = listTool.execute("call-1", JsonObject(emptyMap()), null, null)
        val text = (result.content.single() as TextContent).text
        val payload = PiJson.parseToJsonElement(text) as JsonObject
        assertEquals("srv", payload.stringField("server"))
        assertEquals("cursor-2", payload.stringField("nextCursor"))
        val resource = (payload["resources"] as JsonArray).single() as JsonObject
        assertEquals("srv", resource.stringField("server"))
        assertEquals("file:///a.txt", resource.stringField("uri"))
        assertTrue(!resource.containsKey("_meta"))
        assertEquals(payload, result.structuredContent)
        client.close()
    }

    @Test
    fun listResourcesForwardsCursor() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        var cursorSeen: String? = null
        transport.responder = { request ->
            if (request.method == "resources/list") {
                cursorSeen = (request.params?.get("cursor") as? JsonPrimitive)?.content
                transport.respond(request, buildJsonObject { put("resources", JsonArray(emptyList())) })
            }
        }
        val listTool = mcpResourceTools("srv", client).first()
        listTool.execute("call-1", buildJsonObject { put("cursor", JsonPrimitive("next-1")) }, null, null)
        assertEquals("next-1", cursorSeen)
        client.close()
    }

    @Test
    fun listResourceTemplatesReturnsJson() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "resources/templates/list") {
                transport.respond(
                    request,
                    buildJsonObject {
                        put(
                            "resourceTemplates",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("uriTemplate", JsonPrimitive("file:///{path}"))
                                        put("name", JsonPrimitive("files"))
                                    },
                                ),
                            ),
                        )
                    },
                )
            }
        }
        val templateTool = mcpResourceTools("srv", client)[1]
        val result = templateTool.execute("call-1", JsonObject(emptyMap()), null, null)
        val payload = PiJson.parseToJsonElement((result.content.single() as TextContent).text) as JsonObject
        val template = (payload["resourceTemplates"] as JsonArray).single() as JsonObject
        assertEquals("srv", template.stringField("server"))
        assertEquals("file:///{path}", template.stringField("uriTemplate"))
        client.close()
    }

    @Test
    fun readResourceMapsTextAndImageContents() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "resources/read") {
                transport.respond(
                    request,
                    buildJsonObject {
                        put(
                            "contents",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("uri", JsonPrimitive("file:///a.txt"))
                                        put("mimeType", JsonPrimitive("text/plain"))
                                        put("text", JsonPrimitive("hello"))
                                    },
                                    buildJsonObject {
                                        put("uri", JsonPrimitive("file:///b.png"))
                                        put("mimeType", JsonPrimitive("image/png"))
                                        put("blob", JsonPrimitive("aGVsbG8="))
                                    },
                                ),
                            ),
                        )
                    },
                )
            }
        }
        val readTool = mcpResourceTools("srv", client).last()
        val result = readTool.execute("call-1", buildJsonObject { put("uri", JsonPrimitive("file:///a.txt")) }, null, null)
        assertEquals(4, result.content.size)
        assertEquals("file:///a.txt:", (result.content[0] as TextContent).text)
        assertEquals("hello", (result.content[1] as TextContent).text)
        assertEquals("file:///b.png:", (result.content[2] as TextContent).text)
        assertEquals("image/png", (result.content[3] as ImageContent).mimeType)
        assertEquals("aGVsbG8=", (result.content[3] as ImageContent).data)
        val payload = assertNotNull(result.structuredContent) as JsonObject
        assertEquals("srv", payload.stringField("server"))
        assertEquals("file:///a.txt", payload.stringField("uri"))
        client.close()
    }

    @Test
    fun readResourceSavesBinaryContents() = runTest {
        val fileSystem = FakeFileSystem()
        val directory = "/mcp-resource-binary".toPath()
        try {
            val transport = McpScriptedTransport()
            val client = mcpConnectedClient(transport)
            transport.responder = { request ->
                if (request.method == "resources/read") {
                    transport.respond(
                        request,
                        buildJsonObject {
                            put(
                                "contents",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("uri", JsonPrimitive("file:///data.bin"))
                                            put("mimeType", JsonPrimitive("application/octet-stream"))
                                            put("blob", JsonPrimitive("AAECAw=="))
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
            val readTool = mcpResourceTools(
                "srv",
                client,
                McpToolAdapterOptions(outputDirectory = directory, fileSystem = fileSystem),
            ).last()
            val result = readTool.execute("call-1", buildJsonObject { put("uri", JsonPrimitive("file:///data.bin")) }, null, null)
            val text = (result.content.single() as TextContent).text
            assertTrue(text.contains("saved to"))
            assertTrue(text.contains("application/octet-stream"))
            assertEquals(1, fileSystem.list(directory).size)
            client.close()
        } finally {
            fileSystem.deleteRecursively(directory)
        }
    }

    @Test
    fun readEmptyResourceReportsPlaceholder() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "resources/read") {
                transport.respond(request, buildJsonObject { put("contents", JsonArray(emptyList())) })
            }
        }
        val readTool = mcpResourceTools("srv", client).last()
        val result = readTool.execute("call-1", buildJsonObject { put("uri", JsonPrimitive("file:///empty")) }, null, null)
        assertEquals("Resource file:///empty is empty.", (result.content.single() as TextContent).text)
        client.close()
    }

    @Test
    fun rejectsUnknownServerAndMissingUri() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        val tools = mcpResourceTools("srv", client)
        val mismatch = tools.first().execute(
            "call-1",
            buildJsonObject { put("server", JsonPrimitive("other")) },
            null,
            null,
        )
        assertEquals(true, mismatch.isError)
        assertTrue((mismatch.content.single() as TextContent).text.contains("other"))
        val missingUri = tools.last().execute("call-1", JsonObject(emptyMap()), null, null)
        assertEquals(true, missingUri.isError)
        assertEquals("uri must be provided", (missingUri.content.single() as TextContent).text)
        client.close()
    }

    @Test
    fun mapsResourceRpcErrorsToErrorResults() = runTest {
        val transport = McpScriptedTransport()
        val client = mcpConnectedClient(transport)
        transport.responder = { request ->
            if (request.method == "resources/read") {
                transport.respondError(request, -32603, "resource unavailable")
            }
        }
        val readTool = mcpResourceTools("srv", client).last()
        val result = readTool.execute("call-1", buildJsonObject { put("uri", JsonPrimitive("file:///x")) }, null, null)
        assertEquals(true, result.isError)
        assertEquals("resource unavailable", (result.content.single() as TextContent).text)
        client.close()
    }

    private fun resourcesPage(vararg uris: String): JsonObject = buildJsonObject {
        put(
            "resources",
            JsonArray(
                uris.map { uri ->
                    buildJsonObject {
                        put("uri", JsonPrimitive(uri))
                        put("name", JsonPrimitive(uri.substringAfterLast('/')))
                    }
                },
            ),
        )
    }

    @Test
    fun multiServerListMergesEveryServer() = runTest {
        val transportA = McpScriptedTransport()
        val transportB = McpScriptedTransport()
        val clientA = mcpConnectedClient(transportA)
        val clientB = mcpConnectedClient(transportB)
        transportA.responder = { request ->
            if (request.method == "resources/list") transportA.respond(request, resourcesPage("file:///a.txt"))
        }
        transportB.responder = { request ->
            if (request.method == "resources/list") transportB.respond(request, resourcesPage("file:///b.txt"))
        }
        val listTool = mcpResourceTools(linkedMapOf("a" to clientA, "b" to clientB)).first()
        val result = listTool.execute("call-1", JsonObject(emptyMap()), null, null)
        val payload = PiJson.parseToJsonElement((result.content.single() as TextContent).text) as JsonObject
        val servers = payload["servers"] as JsonArray
        assertEquals(2, servers.size)
        val first = servers[0] as JsonObject
        assertEquals("a", first.stringField("server"))
        assertEquals("file:///a.txt", ((first["resources"] as JsonArray).single() as JsonObject).stringField("uri"))
        val second = servers[1] as JsonObject
        assertEquals("b", second.stringField("server"))
        assertEquals("file:///b.txt", ((second["resources"] as JsonArray).single() as JsonObject).stringField("uri"))
        clientA.close()
        clientB.close()
    }

    @Test
    fun multiServerListWithServerUsesSingleServerShape() = runTest {
        val transportA = McpScriptedTransport()
        val transportB = McpScriptedTransport()
        val clientA = mcpConnectedClient(transportA)
        val clientB = mcpConnectedClient(transportB)
        transportA.responder = { request ->
            if (request.method == "resources/list") transportA.respond(request, resourcesPage("file:///a.txt"))
        }
        transportB.responder = { request ->
            if (request.method == "resources/list") transportB.respond(request, resourcesPage("file:///b.txt"))
        }
        val listTool = mcpResourceTools(linkedMapOf("a" to clientA, "b" to clientB)).first()
        val result = listTool.execute("call-1", buildJsonObject { put("server", JsonPrimitive("b")) }, null, null)
        val payload = PiJson.parseToJsonElement((result.content.single() as TextContent).text) as JsonObject
        assertEquals("b", payload.stringField("server"))
        assertTrue(!payload.containsKey("servers"))
        val resource = (payload["resources"] as JsonArray).single() as JsonObject
        assertEquals("file:///b.txt", resource.stringField("uri"))
        assertTrue(transportA.requests.none { it.method == "resources/list" })
        clientA.close()
        clientB.close()
    }

    @Test
    fun multiServerReadUsesExplicitServer() = runTest {
        val transportA = McpScriptedTransport()
        val transportB = McpScriptedTransport()
        val clientA = mcpConnectedClient(transportA)
        val clientB = mcpConnectedClient(transportB)
        transportA.responder = { request ->
            if (request.method == "resources/read") {
                transportA.respond(request, buildJsonObject {
                    put("contents", JsonArray(listOf(buildJsonObject {
                        put("uri", JsonPrimitive("file:///a.txt"))
                        put("text", JsonPrimitive("from-a"))
                    })))
                })
            }
        }
        transportB.responder = { request ->
            if (request.method == "resources/read") {
                transportB.respond(request, buildJsonObject {
                    put("contents", JsonArray(listOf(buildJsonObject {
                        put("uri", JsonPrimitive("file:///b.txt"))
                        put("text", JsonPrimitive("from-b"))
                    })))
                })
            }
        }
        val readTool = mcpResourceTools(linkedMapOf("a" to clientA, "b" to clientB)).last()
        val result = readTool.execute(
            "call-1",
            buildJsonObject {
                put("server", JsonPrimitive("b"))
                put("uri", JsonPrimitive("file:///b.txt"))
            },
            null,
            null,
        )
        assertEquals("from-b", (result.content.single() as TextContent).text)
        assertEquals("b", (result.structuredContent as JsonObject).stringField("server"))
        assertTrue(transportA.requests.none { it.method == "resources/read" })
        clientA.close()
        clientB.close()
    }

    @Test
    fun multiServerReadWithoutServerRequiresServer() = runTest {
        val transportA = McpScriptedTransport()
        val transportB = McpScriptedTransport()
        val clientA = mcpConnectedClient(transportA)
        val clientB = mcpConnectedClient(transportB)
        val readTool = mcpResourceTools(linkedMapOf("a" to clientA, "b" to clientB)).last()
        val result = readTool.execute("call-1", buildJsonObject { put("uri", JsonPrimitive("file:///x")) }, null, null)
        assertEquals(true, result.isError)
        val message = (result.content.single() as TextContent).text
        assertTrue(message.contains("server must be specified"))
        assertTrue(message.contains("a"))
        assertTrue(message.contains("b"))
        assertTrue(transportA.requests.none { it.method == "resources/read" })
        clientA.close()
        clientB.close()
    }
}
