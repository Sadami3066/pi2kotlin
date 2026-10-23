package dev.pi.mcp

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.okHttpTransport
import dev.pi.ai.PiJson
import dev.pi.mcp.protocol.JsonRpcCodec
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.SseEvent
import dev.pi.mcp.protocol.consumeSseStream
import dev.pi.mcp.transport.StreamableHttpReconnectOptions
import dev.pi.mcp.transport.StreamableHttpTransport
import dev.pi.mcp.transport.StreamableHttpTransportOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamableHttpTransportTest {
    private suspend fun withServer(block: suspend (MockWebServer, OkHttpClient) -> Unit) {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
        try {
            block(server, client)
        } finally {
            server.close()
        }
    }

    private fun transport(
        server: MockWebServer,
        client: OkHttpClient,
        openGetStream: Boolean = false,
        requestTimeoutMs: Long? = null,
        reconnect: StreamableHttpReconnectOptions = StreamableHttpReconnectOptions(initialDelayMs = 10, maxDelayMs = 50),
        headers: Map<String, String> = emptyMap(),
    ): StreamableHttpTransport = StreamableHttpTransport(
        okHttpTransport(client),
        StreamableHttpTransportOptions(
            url = server.url("/mcp").toString(),
            headers = headers,
            openGetStream = openGetStream,
            requestTimeoutMs = requestTimeoutMs,
            reconnect = reconnect,
        ),
    )

    private fun enqueueJson(server: MockWebServer, body: String, sessionId: String? = null, code: Int = 200) {
        val builder = MockResponse.Builder()
            .code(code)
            .addHeader("content-type", "application/json")
            .body(body)
        if (sessionId != null) builder.addHeader("mcp-session-id", sessionId)
        server.enqueue(builder.build())
    }

    private fun enqueueSse(server: MockWebServer, body: String, sessionId: String? = null) {
        val builder = MockResponse.Builder()
            .addHeader("content-type", "text/event-stream")
            .body(body)
        if (sessionId != null) builder.addHeader("mcp-session-id", sessionId)
        server.enqueue(builder.build())
    }

    private fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" }

    private fun notification(method: String): String = """{"jsonrpc":"2.0","method":"$method"}"""

    private fun response(id: Long, result: String = "{}"): String = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    private suspend fun collectMessages(
        transport: StreamableHttpTransport,
        count: Int,
        timeoutMs: Long = 5_000,
    ): List<JsonRpcMessage> = withTimeout(timeoutMs) {
        transport.events.filterIsInstance<McpTransportEvent.Message>().map { it.message }.take(count).toList()
    }

    private suspend fun collectClosed(
        transport: StreamableHttpTransport,
        timeoutMs: Long = 5_000,
    ): McpTransportEvent.Closed = withTimeout(timeoutMs) {
        transport.events.filterIsInstance<McpTransportEvent.Closed>().first()
    }

    private fun next(server: MockWebServer): RecordedRequest = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    private fun bodyOf(request: RecordedRequest): String = assertNotNull(request.body).utf8()

    private fun requestBody(request: RecordedRequest): JsonRpcMessage =
        JsonRpcCodec.decode(bodyOf(request))

    @Test
    fun rejectsSendBeforeStartAndDuplicateStart() = runBlocking<Unit> {
        withServer { server, client ->
            val transport = transport(server, client)
            assertFailsWith<McpConnectionClosedException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(1), "ping"))
            }
            transport.start()
            assertFailsWith<McpProtocolException> { transport.start() }
            transport.close()
        }
    }

    @Test
    fun postsRequestAndParsesJsonResponse() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueJson(server, response(1, """{"ok":true}"""))
            val transport = transport(server, client, headers = mapOf("x-static-token" to "static-value"))
            transport.start()
            val messages = async { collectMessages(transport, 1) }
            transport.send(
                JsonRpcRequest(JsonRpcId.Number(1), "ping", buildJsonObject { put("x", 1) }),
            )
            val message = assertIs<JsonRpcResponse>(messages.await().single())
            assertEquals(JsonRpcId.Number(1), message.id)
            assertTrue((message.result?.get("ok") as JsonPrimitive).content.toBoolean())

            val recorded = next(server)
            assertEquals("POST", recorded.method)
            assertEquals("application/json", recorded.headers["content-type"])
            assertEquals("static-value", recorded.headers["x-static-token"])
            val accept = assertNotNull(recorded.headers["accept"])
            assertTrue(accept.contains("application/json"))
            assertTrue(accept.contains("text/event-stream"))
            val sent = assertIs<JsonRpcRequest>(requestBody(recorded))
            assertEquals("ping", sent.method)
            assertEquals(JsonRpcId.Number(1), sent.id)
            assertEquals("1", (sent.params?.get("x") as JsonPrimitive).content)
            transport.close()
        }
    }

    @Test
    fun parsesJsonBatchResponse() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueJson(server, """[${response(1)},${response(2, """{"two":true}""")}]""")
            val transport = transport(server, client)
            transport.start()
            val messages = async { collectMessages(transport, 2) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(1), "batch"))
            val received = messages.await().map { assertIs<JsonRpcResponse>(it) }
            assertEquals(listOf(JsonRpcId.Number(1), JsonRpcId.Number(2)), received.map { it.id })
            assertTrue((received[1].result?.get("two") as JsonPrimitive).content.toBoolean())
            transport.close()
        }
    }

    @Test
    fun parsesSseResponseStream() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueSse(server, sse(notification("notifications/message"), response(1, """{"streamed":true}""")))
            val transport = transport(server, client)
            transport.start()
            val messages = async { collectMessages(transport, 2) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(1), "stream"))
            val received = messages.await()
            assertEquals("notifications/message", assertIs<JsonRpcNotification>(received[0]).method)
            val streamed = assertIs<JsonRpcResponse>(received[1])
            assertEquals(JsonRpcId.Number(1), streamed.id)
            assertTrue((streamed.result?.get("streamed") as JsonPrimitive).content.toBoolean())
            val recorded = next(server)
            assertTrue(assertNotNull(recorded.headers["accept"]).contains("text/event-stream"))
            transport.close()
        }
    }

    @Test
    fun capturesSessionIdAndSendsProtocolVersion() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueJson(server, response(1), sessionId = "sess-1")
            val transport = transport(server, client)
            transport.start()
            val first = async { collectMessages(transport, 1) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(1), "first"))
            first.await()
            val firstRequest = next(server)
            assertNull(firstRequest.headers["Mcp-Session-Id"])
            assertEquals("sess-1", transport.sessionId)

            transport.setProtocolVersion("2025-06-18")
            enqueueJson(server, response(2))
            val second = async { collectMessages(transport, 1) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(2), "second"))
            second.await()
            val secondRequest = next(server)
            assertEquals("sess-1", secondRequest.headers["Mcp-Session-Id"])
            assertEquals("2025-06-18", secondRequest.headers["MCP-Protocol-Version"])
            transport.close()
        }
    }

    @Test
    fun notificationAcceptedWith202() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(MockResponse.Builder().code(202).build())
            val transport = transport(server, client)
            transport.start()
            transport.send(JsonRpcNotification("notifications/tools/list_changed"))
            val recorded = next(server)
            assertEquals("POST", recorded.method)
            assertEquals("notifications/tools/list_changed", assertIs<JsonRpcNotification>(requestBody(recorded)).method)
            assertEquals(1, server.requestCount)
            transport.close()
        }
    }

    @Test
    fun requestAnsweredWith202ThrowsHttpException() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(MockResponse.Builder().code(202).build())
            val transport = transport(server, client)
            transport.start()
            val failure = assertFailsWith<McpHttpException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(1), "ping"))
            }
            assertEquals(202, failure.status)
            assertTrue(failure.message.orEmpty().contains("without a response"))
            transport.close()
        }
    }

    @Test
    fun mapsAuthAndServerErrorsToExceptions() = runBlocking<Unit> {
        withServer { server, client ->
            val transport = transport(server, client)
            transport.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(401)
                    .addHeader("www-authenticate", """Bearer resource_metadata="https://auth.example/meta"""")
                    .build(),
            )
            val unauthorized = assertFailsWith<McpAuthRequiredException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(1), "ping"))
            }
            assertEquals(
                """Bearer resource_metadata="https://auth.example/meta"""",
                unauthorized.wwwAuthenticate,
            )
            assertEquals(server.url("/mcp").toString(), unauthorized.serverUrl)

            server.enqueue(
                MockResponse.Builder()
                    .code(403)
                    .addHeader("www-authenticate", """Bearer error="insufficient_scope", scope="files"""")
                    .build(),
            )
            assertFailsWith<McpAuthRequiredException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(2), "ping"))
            }

            server.enqueue(MockResponse.Builder().code(500).body("boom").build())
            val serverError = assertFailsWith<McpHttpException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(3), "ping"))
            }
            assertEquals(500, serverError.status)
            assertEquals("boom", serverError.body)
            assertTrue(serverError.message.orEmpty().contains("MCP HTTP request failed with status 500: boom"))
            transport.close()
        }
    }

    @Test
    fun truncatesErrorBodyToEightKilobytes() = runBlocking<Unit> {
        withServer { server, client ->
            val body = "x".repeat(10_000)
            server.enqueue(MockResponse.Builder().code(500).body(body).build())
            val transport = transport(server, client)
            transport.start()
            val failure = assertFailsWith<McpHttpException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(1), "ping"))
            }
            assertEquals(500, failure.status)
            assertEquals(8 * 1024, failure.body.length)
            assertTrue(failure.message.orEmpty().contains("MCP HTTP request failed with status 500: xxx"))
            transport.close()
        }
    }

    @Test
    fun reportsSessionExpiredAfter404() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueJson(server, response(1), sessionId = "sess-expire")
            val transport = transport(server, client)
            transport.start()
            val first = async { collectMessages(transport, 1) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(1), "first"))
            first.await()
            next(server)

            server.enqueue(MockResponse.Builder().code(404).body("""{"error":"session not found"}""").build())
            val expired = assertFailsWith<McpSessionExpiredException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(2), "second"))
            }
            assertTrue(expired.body.contains("session not found"))
            transport.close()
        }
    }

    @Test
    fun requestSignalCancellationAndClosedTransportRejectSends() = runBlocking<Unit> {
        withServer { server, client ->
            val transport = transport(server, client)
            transport.start()
            val signal = RunSignal()
            signal.abort("cancelled")
            assertFailsWith<RequestAbortedException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(1), "ping"), signal)
            }
            assertEquals(0, server.requestCount)
            transport.close()
            assertFailsWith<McpConnectionClosedException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(2), "ping"))
            }
        }
    }

    @Test
    fun requestTimeoutThrowsTransportException() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(
                MockResponse.Builder()
                    .addHeader("content-type", "application/json")
                    .body(response(1))
                    .bodyDelay(3, TimeUnit.SECONDS)
                    .build(),
            )
            val transport = transport(server, client, requestTimeoutMs = 200)
            transport.start()
            assertFailsWith<HttpTransportException> {
                transport.send(JsonRpcRequest(JsonRpcId.Number(1), "slow"))
            }
            transport.close()
        }
    }

    @Test
    fun getStreamDeliversNotificationsAndResumesWithLastEventId() = runBlocking<Unit> {
        withServer { server, client ->
            server.enqueue(MockResponse.Builder().code(202).build())
            enqueueSse(
                server,
                "id: evt-1\nevent: message\n" + sse(notification("notifications/message")),
            )
            enqueueSse(
                server,
                "id: evt-2\nevent: message\n" + sse(notification("notifications/progress")),
            )
            server.enqueue(MockResponse.Builder().code(405).build())

            val transport = transport(server, client, openGetStream = true)
            transport.start()
            val messages = async { collectMessages(transport, 2) }
            transport.send(JsonRpcNotification("notifications/initialized"))
            val received = messages.await()
            assertEquals("notifications/message", assertIs<JsonRpcNotification>(received[0]).method)
            assertEquals("notifications/progress", assertIs<JsonRpcNotification>(received[1]).method)

            val post = next(server)
            assertEquals("POST", post.method)
            val firstGet = next(server)
            assertEquals("GET", firstGet.method)
            assertEquals("text/event-stream", firstGet.headers["accept"])
            assertNull(firstGet.headers["last-event-id"])
            val secondGet = next(server)
            assertEquals("GET", secondGet.method)
            assertEquals("evt-1", secondGet.headers["last-event-id"])
            val thirdGet = next(server)
            assertEquals("evt-2", thirdGet.headers["last-event-id"])
            transport.close()
        }
    }

    @Test
    fun closeSendsDeleteAndEmitsClosedEvent() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueJson(server, response(1), sessionId = "sess-close")
            val transport = transport(server, client)
            transport.start()
            val first = async { collectMessages(transport, 1) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(1), "first"))
            first.await()
            next(server)

            server.enqueue(MockResponse.Builder().code(204).build())
            transport.close()
            val closed = collectClosed(transport)
            assertNull(closed.error)

            val delete = next(server)
            assertEquals("DELETE", delete.method)
            assertEquals("sess-close", delete.headers["Mcp-Session-Id"])
        }
    }

    @Test
    fun closeIgnoresDeleteFailuresAndStillEmitsClosed() = runBlocking<Unit> {
        withServer { server, client ->
            enqueueJson(server, response(1), sessionId = "sess-delete-fail")
            val transport = transport(server, client)
            transport.start()
            val first = async { collectMessages(transport, 1) }
            transport.send(JsonRpcRequest(JsonRpcId.Number(1), "first"))
            first.await()
            next(server)

            server.enqueue(MockResponse.Builder().code(500).body("delete failed").build())
            transport.close()
            val closed = collectClosed(transport)
            assertNull(closed.error)
            assertEquals("DELETE", next(server).method)
        }
    }

    @Test
    fun sseParsesCrlfMultilineDataCommentsAndMetadata() = runBlocking<Unit> {
        val buffer = Buffer().writeUtf8(
            ": keep-alive\r\nid: 7\r\nevent: message\r\ndata: line1\r\ndata: line2\r\n\r\ndata: tail\n\nretry: 1500\n\n",
        )
        val events = mutableListOf<SseEvent>()
        val ids = mutableListOf<String>()
        val retries = mutableListOf<Long>()
        consumeSseStream(
            source = buffer,
            onEvent = { events.add(it) },
            onId = { ids.add(it) },
            onRetry = { retries.add(it) },
        )
        assertEquals(
            listOf(SseEvent("line1\nline2", "message", "7"), SseEvent("tail")),
            events,
        )
        assertEquals(listOf("7"), ids)
        assertEquals(listOf(1500L), retries)
    }

    @Test
    fun sseRejectsEventsAboveByteLimit() = runBlocking<Unit> {
        val buffer = Buffer().writeUtf8("data: 0123456789\n\n")
        assertFailsWith<McpSseEventTooLargeException> {
            consumeSseStream(source = buffer, maxEventBytes = 5, onEvent = {})
        }
    }
}
