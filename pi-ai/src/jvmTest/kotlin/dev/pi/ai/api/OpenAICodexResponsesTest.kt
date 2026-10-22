package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingDeltaEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingLevel
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.ToolChoice
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Transport
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.auth.oauth.base64UrlEncode
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.okHttpTransport
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenAICodexResponsesTest {
    private class StreamResult(
        val message: AssistantMessage,
        val events: List<AssistantMessageEvent>,
    )

    private fun withServer(block: (MockWebServer, HttpTransport) -> Unit) {
        val server = MockWebServer()
        server.start()
        val transport = okHttpTransport(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
        try {
            block(server, transport)
        } finally {
            server.close()
        }
    }

    private fun sse(vararg events: String): String = events.joinToString("") { "data: $it\n\n" }

    private fun event(type: String, build: JsonObjectBuilder.() -> Unit = {}): String =
        buildJsonObject {
            put("type", type)
            build()
        }.toString()

    private fun responseObject(
        id: String,
        status: String,
        usage: JsonObject? = null,
        endTurn: Boolean? = null,
        serviceTier: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("status", status)
        if (usage != null) put("usage", usage)
        if (endTurn != null) put("end_turn", endTurn)
        if (serviceTier != null) put("service_tier", serviceTier)
    }

    private fun usageObject(
        input: Long,
        output: Long,
        cached: Long = 0,
        cacheWrite: Long = 0,
        reasoning: Long = 0,
    ): JsonObject = buildJsonObject {
        put("input_tokens", input)
        put("output_tokens", output)
        put("total_tokens", input + output)
        put(
            "input_tokens_details",
            buildJsonObject {
                put("cached_tokens", cached)
                put("cache_write_tokens", cacheWrite)
            },
        )
        put("output_tokens_details", buildJsonObject { put("reasoning_tokens", reasoning) })
    }

    private fun textItem(id: String, text: String, phase: String? = null, status: String = "completed"): JsonObject =
        buildJsonObject {
            put("type", "message")
            put("id", id)
            put("role", "assistant")
            put("status", status)
            put(
                "content",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "output_text")
                            put("text", text)
                            put("annotations", JsonArray(emptyList()))
                        },
                    ),
                ),
            )
            if (phase != null) put("phase", phase)
        }

    private fun functionCallItem(
        id: String,
        callId: String,
        name: String,
        arguments: String,
    ): JsonObject = buildJsonObject {
        put("type", "function_call")
        put("id", id)
        put("call_id", callId)
        put("name", name)
        put("arguments", arguments)
    }

    private fun reasoningItem(id: String, summary: String, encryptedContent: String? = null): JsonObject =
        buildJsonObject {
            put("type", "reasoning")
            put("id", id)
            put(
                "summary",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("type", "summary_text")
                            put("text", summary)
                        },
                    ),
                ),
            )
            if (encryptedContent != null) put("encrypted_content", encryptedContent)
        }

    private fun jwt(accountId: String): String {
        val header = base64UrlEncode("""{"alg":"none","typ":"JWT"}""".encodeToByteArray())
        val payload = base64UrlEncode(
            """{"$JWT_CLAIM_PATH":{"chatgpt_account_id":"$accountId"}}""".encodeToByteArray(),
        )
        return "$header.$payload.signature"
    }

    private fun testModel(
        server: MockWebServer,
        reasoning: Boolean = true,
        thinkingLevelMap: Map<String, String?>? = mapOf(
            "off" to "none",
            "minimal" to "minimal",
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to "xhigh",
            "max" to "max",
        ),
        compat: JsonObject? = null,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        baseUrl: String = server.url("/backend-api").toString(),
    ): Model = Model(
        id = "gpt-5.6-codex",
        name = "Codename Codex",
        api = "openai-codex-responses",
        provider = "openai-codex",
        baseUrl = baseUrl,
        input = input,
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.5),
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = 400_000,
        maxTokens = 128_000,
        compat = compat,
    )

    private fun context(
        vararg messages: Message,
        tools: List<Tool> = emptyList(),
        systemPrompt: String = "You are a test assistant.",
    ): TranscriptContext = TranscriptContext(
        listOf(
            SystemMessage(
                content = PlainTextContent(systemPrompt),
                toolsAdded = tools.takeIf { it.isNotEmpty() },
                timestamp = 0,
            ),
        ) + messages,
    )

    private fun userText(text: String): UserMessage = UserMessage(PlainTextContent(text), timestamp = 1)

    private fun toolSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
    }

    private fun runStream(
        transport: HttpTransport,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions = StreamOptions(apiKey = jwt("acct-test")),
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val api = openAICodexResponsesApi(transport)
            val stream = api.stream(model, context, options)
            val message = stream.result()
            StreamResult(message, stream.events.toList())
        }
    }

    private fun runSimple(
        transport: HttpTransport,
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val api = openAICodexResponsesApi(transport)
            val stream = api.streamSimple(model, context, options)
            val message = stream.result()
            StreamResult(message, stream.events.toList())
        }
    }

    private fun eventTypes(events: List<AssistantMessageEvent>): List<String> = events.map { event ->
        when (event) {
            is StartEvent -> "start"
            is TextStartEvent -> "text_start"
            is TextDeltaEvent -> "text_delta"
            is TextEndEvent -> "text_end"
            is ThinkingStartEvent -> "thinking_start"
            is ThinkingDeltaEvent -> "thinking_delta"
            is ThinkingEndEvent -> "thinking_end"
            is ToolCallStartEvent -> "toolcall_start"
            is ToolCallDeltaEvent -> "toolcall_delta"
            is ToolCallEndEvent -> "toolcall_end"
            is DoneEvent -> "done"
            is ErrorEvent -> "error"
        }
    }

    private fun enqueueSse(server: MockWebServer, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("content-type", "text/event-stream")
                .body(body)
                .build(),
        )
    }

    private fun nextRecorded(server: MockWebServer): RecordedRequest =
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    private fun bodyOf(recorded: RecordedRequest): JsonObject {
        val body = assertNotNull(recorded.body).utf8()
        return PiJson.parseToJsonElement(body) as JsonObject
    }

    private fun nextRequest(server: MockWebServer): JsonObject = bodyOf(nextRecorded(server))

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.booleanField(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()

    private fun JsonObject.arrayField(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.objects(key: String): List<JsonObject> =
        arrayField(key).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.inputItems(): List<JsonObject> = objects("input")

    @Test
    fun streamsTextAndParsesUsage() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") { put("response", responseObject("resp_1", "in_progress")) },
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_1", "", phase = "final_answer", status = "in_progress"))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "Hello")
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", " world")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_1", "Hello world", phase = "final_answer"))
                },
                event("response.completed") {
                    put(
                        "response",
                        responseObject("resp_1", "completed", usage = usageObject(100, 20, cached = 10, cacheWrite = 5, reasoning = 7)),
                    )
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("resp_1", result.message.responseId)
        assertEquals("Hello world", (result.message.content.first() as TextContent).text)
        assertEquals(85L, result.message.usage.input)
        assertEquals(20L, result.message.usage.output)
        assertEquals(10L, result.message.usage.cacheRead)
        assertEquals(5L, result.message.usage.cacheWrite)
        assertEquals(7L, result.message.usage.reasoning)
        assertEquals(120L, result.message.usage.totalTokens)
        assertTrue(result.message.usage.cost.total > 0.0)
        assertEquals(listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"), eventTypes(result.events))

        val request = nextRecorded(server)
        assertEquals("/backend-api/codex/responses", request.url.encodedPath)
        val body = bodyOf(request)
        assertEquals("gpt-5.6-codex", body.stringField("model"))
        assertEquals(false, body.booleanField("store"))
        assertEquals(true, body.booleanField("stream"))
        assertEquals("You are a test assistant.", body.stringField("instructions"))
        assertEquals("low", (body["text"] as? JsonObject)?.stringField("verbosity"))
        assertEquals("auto", body.stringField("tool_choice"))
        assertEquals(true, body.booleanField("parallel_tool_calls"))
        assertTrue(body.arrayField("include").orEmpty().any { (it as? JsonPrimitive)?.content == "reasoning.encrypted_content" })
        assertEquals("user", body.inputItems().first().stringField("role"))
    }

    @Test
    fun sendsCodexHeadersAndSessionId() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_h", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "ok")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_h", "ok"))
                },
                event("response.completed") { put("response", responseObject("resp_h", "completed")) },
            ),
        )
        val token = jwt("acct-42")
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = token, sessionId = "sess-123", identity = ClientIdentity(userAgent = "pi-test/1.0")),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)

        val recorded = nextRecorded(server)
        assertEquals("Bearer $token", recorded.headers["Authorization"])
        assertEquals("acct-42", recorded.headers["chatgpt-account-id"])
        assertEquals("pi", recorded.headers["originator"])
        assertEquals("responses=experimental", recorded.headers["OpenAI-Beta"])
        assertEquals("text/event-stream", recorded.headers["accept"])
        assertEquals("application/json", recorded.headers["content-type"])
        assertEquals("sess-123", recorded.headers["session-id"])
        assertEquals("sess-123", recorded.headers["x-client-request-id"])
        assertEquals("pi-test/1.0", recorded.headers["User-Agent"])

        val body = bodyOf(recorded)
        assertEquals("sess-123", body.stringField("prompt_cache_key"))
    }

    @Test
    fun appendsResponsesPathToCodexBaseUrl() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_u", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "u")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_u", "u"))
                },
                event("response.completed") { put("response", responseObject("resp_u", "completed")) },
            ),
        )
        val model = testModel(server, baseUrl = server.url("/backend-api/codex").toString())
        val result = runStream(transport, model, context(userText("Hi")))
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("/backend-api/codex/responses", nextRecorded(server).url.encodedPath)
    }

    @Test
    fun fallsBackToSseWhenWebSocketTransportRequested() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_ws", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "sse")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_ws", "sse"))
                },
                event("response.completed") { put("response", responseObject("resp_ws", "completed")) },
            ),
        )
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = jwt("acct-test"), transport = Transport.WebSocket),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("sse", (result.message.content.first() as TextContent).text)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun parsesReasoningWithEncryptedContent() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", buildJsonObject {
                        put("type", "reasoning")
                        put("id", "rs_1")
                        put("summary", JsonArray(emptyList()))
                    })
                },
                event("response.reasoning_summary_text.delta") {
                    put("output_index", 0)
                    put("delta", "think")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", reasoningItem("rs_1", "think", encryptedContent = "enc-xyz"))
                },
                event("response.output_item.added") {
                    put("output_index", 1)
                    put("item", textItem("msg_1", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 1)
                    put("delta", "answer")
                },
                event("response.output_item.done") {
                    put("output_index", 1)
                    put("item", textItem("msg_1", "answer"))
                },
                event("response.completed") { put("response", responseObject("resp_r", "completed")) },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        val thinking = result.message.content.filterIsInstance<ThinkingContent>().firstOrNull()
        assertNotNull(thinking)
        assertEquals("think", thinking.thinking)
        assertTrue(thinking.thinkingSignature.orEmpty().contains("enc-xyz"))
        assertEquals("answer", (result.message.content.filterIsInstance<TextContent>().first()).text)
        assertTrue(eventTypes(result.events).contains("thinking_start"))
        assertTrue(eventTypes(result.events).contains("thinking_end"))
    }

    @Test
    fun replaysEncryptedReasoningInRequestInput() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_2", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "done")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_2", "done"))
                },
                event("response.completed") { put("response", responseObject("resp_2", "completed")) },
            ),
        )
        val assistant = AssistantMessage(
            content = mutableListOf(
                ThinkingContent(thinking = "plan", thinkingSignature = reasoningItem("rs_prev", "plan", "enc-prev").toString()),
                TextContent("first answer"),
            ),
            api = "openai-codex-responses",
            provider = "openai-codex",
            model = "gpt-5.6-codex",
            usage = Usage.empty(),
            stopReason = StopReason.Stop,
            timestamp = 2,
        )
        val result = runStream(
            transport,
            testModel(server),
            context(userText("one"), assistant, userText("two")),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val input = nextRequest(server).inputItems()
        val reasoning = input.firstOrNull { it.stringField("type") == "reasoning" }
        assertNotNull(reasoning)
        assertEquals("enc-prev", reasoning.stringField("encrypted_content"))
    }

    @Test
    fun streamsToolCalls() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", functionCallItem("fc_1", "call_1", "lookup", ""))
                },
                event("response.function_call_arguments.delta") {
                    put("output_index", 0)
                    put("delta", "{\"path\":\"a.txt\"}")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", functionCallItem("fc_1", "call_1", "lookup", "{\"path\":\"a.txt\"}"))
                },
                event("response.completed") { put("response", responseObject("resp_t", "completed")) },
            ),
        )
        val tool = Tool(name = "lookup", description = "Look up a path", parameters = toolSchema())
        val result = runStream(transport, testModel(server), context(userText("Hi"), tools = listOf(tool)))

        assertEquals(StopReason.ToolUse, result.message.stopReason)
        val toolCall = result.message.content.filterIsInstance<ToolCall>().first()
        assertEquals("lookup", toolCall.name)
        assertEquals("call_1|fc_1", toolCall.id)
        assertEquals("a.txt", (toolCall.arguments["path"] as? JsonPrimitive)?.content)
        assertTrue(eventTypes(result.events).contains("toolcall_end"))
        val body = nextRequest(server)
        assertEquals("lookup", body.objects("tools").first().stringField("name"))
    }

    @Test
    fun mapsResponseDoneTerminalEvent() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_d", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "done-event")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_d", "done-event"))
                },
                event("response.done") {
                    put("response", responseObject("resp_d", "completed", endTurn = true))
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals(true, result.message.endTurn)
        assertEquals("done-event", (result.message.content.first() as TextContent).text)
    }

    @Test
    fun errorEventBecomesErrorResult() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("error") {
                    put("code", "server_error")
                    put("message", "boom")
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("Codex error: boom"))
        assertEquals("error", eventTypes(result.events).last())
    }

    @Test
    fun failedResponseBecomesErrorResult() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.failed") {
                    put(
                        "response",
                        buildJsonObject {
                            put("id", "resp_f")
                            put("status", "failed")
                            put(
                                "error",
                                buildJsonObject {
                                    put("code", "invalid_request")
                                    put("message", "failed hard")
                                },
                            )
                        },
                    )
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("failed hard", result.message.errorMessage)
    }

    @Test
    fun retriesRetryableHttpError() = withServer { server, transport ->
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .addHeader("retry-after-ms", "1")
                .body("server exploded")
                .build(),
        )
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_retry", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "recovered")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_retry", "recovered"))
                },
                event("response.completed") { put("response", responseObject("resp_retry", "completed")) },
            ),
        )
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = jwt("acct-test"), maxRetries = 1, maxRetryDelayMs = 1_000),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("recovered", (result.message.content.first() as TextContent).text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun oversizedServerRetryDelayFailsImmediately() = withServer { server, transport ->
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("retry-after", "277403")
                .body("rate limited")
                .build(),
        )
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = jwt("acct-test"), maxRetries = 2, maxRetryDelayMs = 1_000),
        )
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("Server requested 277403s retry delay (max: 1s)"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun nonRetryableHttpErrorParsesMessage() = withServer { server, transport ->
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("content-type", "application/json")
                .body("""{"error":{"message":"bad request"}}""")
                .build(),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("bad request", result.message.errorMessage)
    }

    @Test
    fun usageLimitHttpErrorGetsFriendlyMessage() = withServer { server, transport ->
        val resetsAt = (System.currentTimeMillis() / 1000) + 120
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("content-type", "application/json")
                .body("""{"error":{"code":"usage_limit_reached","plan_type":"Plus","resets_at":$resetsAt}}""")
                .build(),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("You have hit your ChatGPT usage limit (plus plan)."))
    }

    @Test
    fun missingApiKeyThrowsFromStreamSimple() = withServer { server, transport ->
        val failure = runBlocking {
            runCatching {
                openAICodexResponsesApi(transport).streamSimple(
                    testModel(server),
                    context(userText("Hi")),
                    SimpleStreamOptions(),
                )
            }.exceptionOrNull()
        }
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message.orEmpty().contains("No API key for provider: openai-codex"))
    }

    @Test
    fun invalidJwtBecomesErrorResult() = withServer { server, transport ->
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = "not-a-jwt"),
        )
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Failed to extract accountId from token", result.message.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun streamSimpleMapsReasoningEffortAndToolChoice() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_s", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "simple")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_s", "simple"))
                },
                event("response.completed") { put("response", responseObject("resp_s", "completed")) },
            ),
        )
        val result = runSimple(
            transport,
            testModel(server),
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = jwt("acct-test")),
                reasoning = ThinkingLevel.High,
                toolChoice = ToolChoice.None,
            ),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val body = nextRequest(server)
        assertEquals("high", (body["reasoning"] as? JsonObject)?.stringField("effort"))
        assertEquals("none", body.stringField("tool_choice"))
    }

    @Test
    fun missingTerminalEventBecomesErrorResult() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") { put("response", responseObject("resp_x", "in_progress")) },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("terminal"))
    }

    @Test
    fun abortedSignalProducesAbortedResult() = withServer { server, transport ->
        val signal = RunSignal().apply { abort() }
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = jwt("acct-test"), signal = signal),
        )
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val JWT_CLAIM_PATH = "https://api.openai.com/auth"
    }
}
