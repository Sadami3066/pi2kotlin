package dev.pi.mcp

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.LATEST_PROTOCOL_VERSION
import dev.pi.mcp.transport.StdioTransport
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private fun javaExecutable(): String {
    val home = System.getProperty("java.home")
    val bin = File(home, "bin")
    val executable = File(bin, "java.exe").takeIf { it.exists() } ?: File(bin, "java")
    return executable.absolutePath
}

private fun newTransport(extraArgs: List<String> = emptyList(), env: Map<String, String> = emptyMap()): StdioTransport =
    StdioTransport(
        command = javaExecutable(),
        args = listOf("-cp", System.getProperty("java.class.path"), "dev.pi.mcp.StdioTestServer") + extraArgs,
        env = env,
        cwd = null,
        stderrTailBytes = 8 * 1024,
    )

private fun request(id: Long, method: String, params: JsonObject? = null): JsonRpcRequest =
    JsonRpcRequest(JsonRpcId.Number(id), method, params)

private fun initializeParams(): JsonObject = buildJsonObject {
    put("protocolVersion", LATEST_PROTOCOL_VERSION)
    put("capabilities", buildJsonObject { })
    put(
        "clientInfo",
        buildJsonObject {
            put("name", "stdio-transport-test")
            put("version", "0.0.1")
        },
    )
}

private fun toolCallParams(name: String, text: String? = null): JsonObject = buildJsonObject {
    put("name", name)
    put(
        "arguments",
        buildJsonObject {
            if (text != null) put("text", text)
        },
    )
}

private class Harness(val transport: StdioTransport) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messages = LinkedBlockingQueue<JsonRpcMessage>()
    private val events = LinkedBlockingQueue<McpTransportEvent>()
    private val collector = scope.launch {
        transport.events.collect { event ->
            events.add(event)
            if (event is McpTransportEvent.Message) messages.add(event.message)
        }
    }

    fun awaitMessage(predicate: (JsonRpcMessage) -> Boolean, timeoutMs: Long = 10_000): JsonRpcMessage {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            val message = messages.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            if (predicate(message)) return message
        }
        fail("expected message not received within ${timeoutMs}ms")
    }

    fun awaitResponse(id: Long, timeoutMs: Long = 10_000): JsonRpcResponse {
        val message = awaitMessage(
            { it is JsonRpcResponse && it.id == JsonRpcId.Number(id) || it is JsonRpcErrorResponse && it.id == JsonRpcId.Number(id) },
            timeoutMs,
        )
        if (message is JsonRpcErrorResponse) fail("unexpected JSON-RPC error for id=$id: ${message.message}")
        return message as JsonRpcResponse
    }

    fun awaitClosed(timeoutMs: Long = 5_000): McpTransportEvent.Closed {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: break
            if (event is McpTransportEvent.Closed) return event
        }
        fail("transport did not emit Closed within ${timeoutMs}ms")
    }

    fun awaitNotification(method: String, timeoutMs: Long = 5_000): JsonRpcNotification {
        val message = awaitMessage(
            { it is JsonRpcNotification && it.method == method },
            timeoutMs,
        )
        return message as JsonRpcNotification
    }

    override fun close() {
        collector.cancel()
        scope.cancel()
    }
}

private suspend fun awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        delay(20)
    }
    if (!condition()) fail("condition not met within ${timeoutMs}ms")
}

private fun toolNames(response: JsonRpcResponse): List<String> {
    val result = response.result ?: fail("tools/list response has no result")
    val tools = result["tools"] as? JsonArray ?: fail("tools/list result has no tools array")
    return tools.mapNotNull { element ->
        (element as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }
}

private fun firstTextContent(response: JsonRpcResponse): String {
    val result = response.result ?: fail("call response has no result")
    val content = result["content"] as? JsonArray ?: fail("call result has no content")
    val first = content.firstOrNull() as? JsonObject ?: fail("call result has no content block")
    return (first["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

private fun isErrorOf(response: JsonRpcResponse): Boolean {
    val result = response.result ?: fail("call response has no result")
    return (result["isError"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
}

class StdioTransportTest {
    @Test
    fun initializeHandshake(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            assertNotNull(transport.pid)
            transport.send(request(1, "initialize", initializeParams()))
            val response = harness.awaitResponse(1)
            val result = assertNotNull(response.result)
            assertEquals(LATEST_PROTOCOL_VERSION, (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull)
            assertTrue(result["capabilities"] is JsonObject)
            val serverInfo = assertNotNull(result["serverInfo"] as? JsonObject)
            assertEquals("stdio-test-server", (serverInfo["name"] as? JsonPrimitive)?.contentOrNull)
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun toolsListReturnsExpectedTools(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            transport.send(request(2, "tools/list"))
            val names = toolNames(harness.awaitResponse(2))
            assertTrue(names.containsAll(listOf("echo", "fail", "malformed", "stderr")))
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun toolsCallEchoAndFailureResults(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            transport.send(request(3, "tools/call", toolCallParams("echo", "hello")))
            val echo = harness.awaitResponse(3)
            assertEquals("echo: hello", firstTextContent(echo))
            assertEquals(false, isErrorOf(echo))

            transport.send(request(4, "tools/call", toolCallParams("fail")))
            val failing = harness.awaitResponse(4)
            assertEquals("induced failure", firstTextContent(failing))
            assertEquals(true, isErrorOf(failing))
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun malformedJsonLineIsTolerated(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            transport.send(request(5, "tools/call", toolCallParams("malformed")))
            val response = harness.awaitResponse(5)
            assertEquals("after malformed", firstTextContent(response))
            assertTrue(transport.malformedMessageCount() >= 1)

            transport.send(request(6, "ping"))
            assertNotNull(harness.awaitResponse(6))
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun notificationsAreDeliveredAsMessages(): Unit = runBlocking {
        val transport = newTransport(extraArgs = listOf("--notifications"))
        val harness = Harness(transport)
        try {
            transport.start()
            transport.send(request(7, "tools/list"))
            harness.awaitNotification("notifications/tools/list_changed")
            assertNotNull(harness.awaitResponse(7))
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun closeTerminatesServerProcessWithinTwoSeconds(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            val pid = assertNotNull(transport.pid)
            transport.send(request(8, "ping"))
            harness.awaitResponse(8)
            val startedAt = System.nanoTime()
            transport.close()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertTrue(elapsedMs <= 2_000, "close took ${elapsedMs}ms")
            assertTrue(!transport.isLivePid(pid), "server process $pid is still alive")
            harness.awaitClosed()
            assertFailsWith<McpConnectionClosedException> {
                transport.send(request(9, "ping"))
            }
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun stderrIsCapturedInTailBuffer(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            awaitCondition { transport.stderrTail().contains("[stdio-test-server] started") }
            assertTrue(transport.stderrTail().contains("[stdio-test-server] started"))
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun childProcessesAreCleanedUpOnClose(): Unit = runBlocking {
        val transport = newTransport(extraArgs = listOf("--spawn-child"))
        val harness = Harness(transport)
        var childPid: Long? = null
        try {
            transport.start()
            awaitCondition {
                val match = Regex("childPid=(\\d+)").find(transport.stderrTail())
                childPid = match?.groupValues?.get(1)?.toLongOrNull()
                childPid != null
            }
            val pid = assertNotNull(childPid)
            assertTrue(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child $pid should be alive before close")
            transport.close()
            awaitCondition(2_000) {
                !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
            }
            assertTrue(!ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "child $pid survived transport close")
        } finally {
            transport.close()
            harness.close()
            childPid?.let { pid ->
                if (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
                    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                }
            }
        }
    }

    @Test
    fun abortedSignalPreventsSend(): Unit = runBlocking {
        val transport = newTransport()
        val harness = Harness(transport)
        try {
            transport.start()
            val signal = RunSignal()
            signal.abort("test abort")
            assertFailsWith<RequestAbortedException> {
                transport.send(request(10, "ping"), signal)
            }
        } finally {
            transport.close()
            harness.close()
        }
    }

    @Test
    fun envIsMergedIntoChildEnvironment(): Unit = runBlocking {
        val transport = newTransport(env = mapOf("MCP_TEST_ENV" to "merged-value"))
        val harness = Harness(transport)
        try {
            transport.start()
            awaitCondition { transport.stderrTail().contains("env=merged-value") }
            assertTrue(transport.stderrTail().contains("env=merged-value"))
        } finally {
            transport.close()
            harness.close()
        }
    }
}

private fun StdioTransport.isLivePid(pid: Long): Boolean =
    ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
