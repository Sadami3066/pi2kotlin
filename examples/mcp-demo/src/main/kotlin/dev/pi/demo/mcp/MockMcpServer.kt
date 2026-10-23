package dev.pi.demo.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.pi.ai.PiJson
import java.net.InetSocketAddress
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MockMcpServer {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    val url: String
        get() = "http://127.0.0.1:${server.address.port}/mcp"

    fun start() {
        server.createContext("/mcp") { exchange -> handle(exchange) }
        server.start()
    }

    fun stop() {
        server.stop(0)
    }

    private fun handle(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(405, -1)
            exchange.close()
            return
        }
        val body = exchange.requestBody.readBytes().decodeToString()
        val request = PiJson.parseToJsonElement(body).jsonObject
        val method = (request["method"] as? JsonPrimitive)?.content ?: ""
        val id = request["id"]
        val result = when (method) {
            "initialize" -> buildJsonObject {
                put("protocolVersion", "2025-11-25")
                put(
                    "capabilities",
                    buildJsonObject {
                        put("tools", buildJsonObject { })
                        put("resources", buildJsonObject { })
                    },
                )
                put("serverInfo", buildJsonObject { put("name", "mock-mcp"); put("version", "1.0") })
                put("instructions", "Demo MCP server with an echo tool and one resource.")
            }

            "tools/list" -> buildJsonObject {
                put(
                    "tools",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("name", "echo")
                                put("description", "Echo the provided text back to the caller.")
                                put(
                                    "inputSchema",
                                    buildJsonObject {
                                        put("type", "object")
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put("text", buildJsonObject { put("type", "string") })
                                            },
                                        )
                                        put("required", JsonArray(listOf(JsonPrimitive("text"))))
                                    },
                                )
                            },
                        ),
                    ),
                )
            }

            "tools/call" -> {
                val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
                val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                val text = (args["text"] as? JsonPrimitive)?.content ?: ""
                buildJsonObject {
                    put(
                        "content",
                        JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", "echo: $text") })),
                    )
                }
            }

            "resources/list" -> buildJsonObject {
                put(
                    "resources",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("uri", "demo://readme")
                                put("name", "readme")
                                put("mimeType", "text/plain")
                            },
                        ),
                    ),
                )
            }

            "resources/read" -> buildJsonObject {
                put(
                    "contents",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("uri", "demo://readme")
                                put("mimeType", "text/plain")
                                put("text", "This is a resource served by the mock MCP server.")
                            },
                        ),
                    ),
                )
            }

            "ping" -> buildJsonObject { }
            else -> buildJsonObject { }
        }
        val response = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            put("result", result)
        }
        val bytes = response.toString().encodeToByteArray()
        exchange.responseHeaders.add("content-type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
