package dev.pi.mcp

import dev.pi.mcp.protocol.LATEST_PROTOCOL_VERSION
import java.io.PrintWriter
import kotlin.system.exitProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object StdioTestServer {
    private val json = Json
    private val output = PrintWriter(System.out, true)
    private var emitNotifications = false

    @JvmStatic
    fun main(args: Array<String>) {
        emitNotifications = args.contains("--notifications")
        if (args.contains("--spawn-child")) {
            val child = ProcessBuilder("sleep", "60").start()
            System.err.println("[stdio-test-server] childPid=${child.pid()}")
            System.err.flush()
        }
        System.err.println("[stdio-test-server] started pid=${ProcessHandle.current().pid()}")
        System.getenv("MCP_TEST_ENV")?.let { value ->
            System.err.println("[stdio-test-server] env=$value")
        }
        System.err.flush()
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        while (true) {
            val line = reader.readLine() ?: break
            val text = line.trim()
            if (text.isEmpty()) continue
            try {
                handle(text)
            } catch (error: Exception) {
                System.err.println("[stdio-test-server] bad request: ${error.message}")
                System.err.flush()
            }
        }
        output.flush()
        exitProcess(0)
    }

    private fun handle(text: String) {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            System.err.println("[stdio-test-server] invalid json")
            System.err.flush()
            return
        }
        val obj = element as? JsonObject ?: return
        val id = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: return
        val params = obj["params"] as? JsonObject
        when (method) {
            "initialize" -> respond(
                id,
                buildJsonObject {
                    put("protocolVersion", LATEST_PROTOCOL_VERSION)
                    put(
                        "capabilities",
                        buildJsonObject {
                            put("tools", buildJsonObject { put("listChanged", true) })
                        },
                    )
                    put(
                        "serverInfo",
                        buildJsonObject {
                            put("name", "stdio-test-server")
                            put("version", "1.0.0")
                        },
                    )
                },
            )

            "notifications/initialized" -> Unit
            "ping" -> respond(id, buildJsonObject { })
            "tools/list" -> {
                if (emitNotifications) {
                    notify("notifications/tools/list_changed", buildJsonObject { })
                }
                respond(id, buildJsonObject { put("tools", toolsJson()) })
            }

            "tools/call" -> handleToolCall(id, params)
            else -> respondError(id, -32601, "Method not found: $method")
        }
    }

    private fun handleToolCall(id: JsonElement?, params: JsonObject?) {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val arguments = params?.get("arguments") as? JsonObject
        val text = (arguments?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty()
        when (name) {
            "echo" -> respond(id, callResult("echo: $text", false))
            "fail" -> respond(id, callResult("induced failure", true))
            "malformed" -> {
                output.println("this line is not valid json")
                output.flush()
                respond(id, callResult("after malformed", false))
            }

            "stderr" -> {
                System.err.println("[stdio-test-server] tool stderr marker")
                System.err.flush()
                respond(id, callResult("stderr: $text", false))
            }

            else -> respondError(id, -32602, "Unknown tool: $name")
        }
    }

    private fun toolsJson(): JsonArray = buildJsonArray {
        add(toolJson("echo", "Echo the supplied text"))
        add(toolJson("fail", "Always returns an error result"))
        add(toolJson("malformed", "Emits an invalid JSON line before responding"))
        add(toolJson("stderr", "Writes a marker to stderr and responds"))
    }

    private fun toolJson(name: String, description: String): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "inputSchema",
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "text",
                            buildJsonObject {
                                put("type", "string")
                            },
                        )
                    },
                )
            },
        )
    }

    private fun callResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            },
        )
        put("isError", isError)
    }

    private fun respond(id: JsonElement?, result: JsonObject) {
        if (id == null || id is JsonNull) return
        output.println(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString(),
        )
        output.flush()
    }

    private fun respondError(id: JsonElement?, code: Int, message: String) {
        if (id == null || id is JsonNull) return
        output.println(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            }.toString(),
        )
        output.flush()
    }

    private fun notify(method: String, params: JsonObject) {
        output.println(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            }.toString(),
        )
        output.flush()
    }
}
