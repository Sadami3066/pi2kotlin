package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
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
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
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
import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAIResponsesTest {
    private class StreamResult(
        val message: AssistantMessage,
        val events: List<dev.pi.ai.AssistantMessageEvent>,
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
        error: JsonObject? = null,
        incompleteReason: String? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("status", status)
        if (usage != null) put("usage", usage)
        if (error != null) put("error", error)
        if (incompleteReason != null) {
            put("incomplete_details", buildJsonObject { put("reason", incompleteReason) })
        }
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

    private fun textItem(
        id: String,
        text: String,
        phase: String? = null,
        status: String = "completed",
    ): JsonObject = buildJsonObject {
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
        namespace: String? = null,
    ): JsonObject = buildJsonObject {
        put("type", "function_call")
        put("id", id)
        put("call_id", callId)
        put("name", name)
        put("arguments", arguments)
        if (namespace != null) put("namespace", namespace)
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

    private fun testModel(
        server: MockWebServer,
        provider: String = "openai",
        reasoning: Boolean = false,
        thinkingLevelMap: Map<String, String?>? = null,
        compat: JsonObject? = null,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
    ): Model = Model(
        id = "gpt-test",
        name = "GPT Test",
        api = "openai-responses",
        provider = provider,
        baseUrl = server.url("/v1").toString(),
        input = input,
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.5),
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = 128_000,
        maxTokens = 4_096,
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

    private fun runStream(
        transport: HttpTransport,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions = StreamOptions(apiKey = "test-key"),
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val api = openAIResponsesApi(transport)
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
            val api = openAIResponsesApi(transport)
            val stream = api.streamSimple(model, context, options)
            val message = stream.result()
            StreamResult(message, stream.events.toList())
        }
    }

    private fun eventTypes(events: List<dev.pi.ai.AssistantMessageEvent>): List<String> = events.map { event ->
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

    private fun nextRecorded(server: MockWebServer): mockwebserver3.RecordedRequest =
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))

    private fun nextRequest(server: MockWebServer): JsonObject {
        val recorded = nextRecorded(server)
        val body = assertNotNull(recorded.body).utf8()
        return PiJson.parseToJsonElement(body) as JsonObject
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.arrayField(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()

    private fun JsonObject.objects(key: String): List<JsonObject> =
        arrayField(key).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.inputItems(): List<JsonObject> = objects("input")

    private fun emptyAssistant(content: MutableList<dev.pi.ai.Content> = mutableListOf()): AssistantMessage =
        AssistantMessage(
            content = content,
            api = "openai-responses",
            provider = "openai",
            model = "gpt-test",
            usage = Usage(
                input = 0,
                output = 0,
                cacheRead = 0,
                cacheWrite = 0,
                totalTokens = 0,
                cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            stopReason = StopReason.Stop,
            timestamp = 3,
        )

    @Test
    fun streamsTextDeltasAndParsesUsage() = withServer { server, transport ->
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
                        responseObject("resp_1", "completed", usage = usageObject(20, 7, cached = 2, cacheWrite = 3)),
                    )
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            eventTypes(result.events),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("resp_1", result.message.responseId)
        val text = assertNotNull(result.message.content.filterIsInstance<TextContent>().firstOrNull())
        assertEquals("Hello world", text.text)
        val signatureJson = PiJson.parseToJsonElement(assertNotNull(text.textSignature)) as JsonObject
        assertEquals(1, signatureJson.intField("v"))
        assertEquals("msg_1", signatureJson.stringField("id"))
        assertEquals("final_answer", signatureJson.stringField("phase"))
        assertEquals(15, result.message.usage.input)
        assertEquals(7, result.message.usage.output)
        assertEquals(2, result.message.usage.cacheRead)
        assertEquals(3, result.message.usage.cacheWrite)
        assertEquals(27, result.message.usage.totalTokens)
        assertEquals("completed", result.message.rawStopReason)
        assertNull(result.message.errorMessage)
    }

    @Test
    fun streamsReasoningSummaryWithEncryptedSignature() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") { put("response", responseObject("resp_r", "in_progress")) },
                event("response.output_item.added") {
                    put("output_index", 0)
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "reasoning")
                            put("id", "rs_1")
                            put("summary", JsonArray(emptyList()))
                        },
                    )
                },
                event("response.reasoning_summary_text.delta") {
                    put("output_index", 0)
                    put("item_id", "rs_1")
                    put("delta", "pondering")
                },
                event("response.reasoning_summary_part.done") {
                    put("output_index", 0)
                    put("item_id", "rs_1")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", reasoningItem("rs_1", "pondering", "enc_blob"))
                },
                event("response.completed") { put("response", responseObject("resp_r", "completed")) },
            ),
        )
        val result = runStream(transport, testModel(server, reasoning = true), context(userText("Hi")))

        assertTrue(eventTypes(result.events).containsAll(listOf("thinking_start", "thinking_delta", "thinking_end")))
        val thinking = assertNotNull(result.message.content.filterIsInstance<ThinkingContent>().firstOrNull())
        assertEquals("pondering", thinking.thinking)
        val signature = assertNotNull(thinking.thinkingSignature)
        assertTrue(signature.contains("enc_blob"))
        assertTrue(signature.contains("\"type\":\"reasoning\""))
        assertEquals(StopReason.Stop, result.message.stopReason)
    }

    @Test
    fun streamsToolCallAndReplaysFunctionCallAndOutput() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") { put("response", responseObject("resp_t", "in_progress")) },
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", functionCallItem("fc_1", "call_1", "edit", ""))
                },
                event("response.function_call_arguments.delta") {
                    put("output_index", 0)
                    put("delta", "{\"path\":\"a")
                },
                event("response.function_call_arguments.done") {
                    put("output_index", 0)
                    put("arguments", "{\"path\":\"a\",\"content\":\"b\"}")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", functionCallItem("fc_1", "call_1", "edit", "{\"path\":\"a\",\"content\":\"b\"}"))
                },
                event("response.completed") { put("response", responseObject("resp_t", "completed")) },
            ),
        )
        val model = testModel(server)
        val first = runStream(transport, model, context(userText("edit")))
        assertEquals(StopReason.ToolUse, first.message.stopReason)
        val toolCall = assertNotNull(first.message.content.filterIsInstance<ToolCall>().firstOrNull())
        assertEquals("call_1|fc_1", toolCall.id)
        assertEquals("edit", toolCall.name)
        assertEquals("a", (toolCall.arguments["path"] as JsonPrimitive).content)
        assertNull(toolCall.argumentsError)
        assertTrue(eventTypes(first.events).contains("toolcall_end"))
        nextRecorded(server)

        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_t2", "completed")) }),
        )
        val toolResult = ToolResultMessage(
            toolCallId = "call_1|fc_1",
            toolName = "edit",
            content = listOf(TextContent("ok")),
            isError = false,
            timestamp = 2,
        )
        runStream(transport, model, TranscriptContext(listOf(userText("edit"), first.message, toolResult)))
        val body = nextRequest(server)
        val functionCall = assertNotNull(body.inputItems().firstOrNull { it.stringField("type") == "function_call" })
        assertEquals("call_1", functionCall.stringField("call_id"))
        assertEquals("fc_1", functionCall.stringField("id"))
        assertEquals("edit", functionCall.stringField("name"))
        assertEquals("{\"path\":\"a\",\"content\":\"b\"}", functionCall.stringField("arguments"))
        val callOutput = assertNotNull(
            body.inputItems().firstOrNull { it.stringField("type") == "function_call_output" },
        )
        assertEquals("call_1", callOutput.stringField("call_id"))
        assertEquals("ok", callOutput.stringField("output"))
        assertEquals("gpt-test", body.stringField("model"))
        assertEquals("true", (body["stream"] as JsonPrimitive).content)
        assertEquals("false", (body["store"] as JsonPrimitive).content)
    }

    @Test
    fun mapsIncompleteMaxOutputTokensToLength() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.incomplete") {
                    put(
                        "response",
                        responseObject(
                            "resp_inc",
                            "incomplete",
                            usage = usageObject(30, 12, cached = 5),
                            incompleteReason = "max_output_tokens",
                        ),
                    )
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Length, result.message.stopReason)
        assertEquals("incomplete.max_output_tokens", result.message.rawStopReason)
        assertEquals(25, result.message.usage.input)
        assertEquals(12, result.message.usage.output)
        assertEquals(5, result.message.usage.cacheRead)
        assertEquals(42, result.message.usage.totalTokens)
        assertEquals("done", eventTypes(result.events).last())
    }

    @Test
    fun contentFilteredIncompleteBecomesNonRetryableError() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.incomplete") {
                    put("response", responseObject("resp_cf", "incomplete", incompleteReason = "content_filter"))
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("incomplete.content_filter", result.message.rawStopReason)
        assertEquals("Response incomplete: content_filter", result.message.errorMessage)
        assertEquals("error", eventTypes(result.events).last())
    }

    @Test
    fun failedTerminalEventSurfacesProviderError() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.failed") {
                    put(
                        "response",
                        responseObject(
                            "resp_fail",
                            "failed",
                            error = buildJsonObject {
                                put("code", "server_error")
                                put("message", "boom")
                            },
                        ),
                    )
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("failed", result.message.rawStopReason)
        assertEquals("server_error: boom", result.message.errorMessage)
    }

    @Test
    fun rejectsStreamsEndingBeforeTerminalEvent() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") { put("response", responseObject("resp_eof", "in_progress")) },
                event("response.output_item.added") {
                    put("output_index", 0)
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "reasoning")
                            put("id", "rs_eof")
                            put("summary", JsonArray(emptyList()))
                        },
                    )
                },
                event("response.reasoning_text.delta") {
                    put("output_index", 0)
                    put("delta", "partial")
                },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("OpenAI Responses stream ended before a terminal response event", result.message.errorMessage)
    }

    @Test
    fun rejectsUnfinishedToolCalls() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", functionCallItem("fc_x", "call_x", "bash", ""))
                },
                event("response.function_call_arguments.delta") {
                    put("output_index", 0)
                    put("delta", "{\"command\":\"ls")
                },
                event("response.completed") { put("response", responseObject("resp_unfinished", "completed")) },
            ),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        val errorMessage = assertNotNull(result.message.errorMessage)
        assertTrue(errorMessage.contains("unfinished tool call"), "unexpected error: $errorMessage")
        assertTrue(errorMessage.contains("bash"))
    }

    @Test
    fun httpErrorBecomesErrorEvent() = withServer { server, transport ->
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("content-type", "application/json")
                .body("""{"error":{"message":"rate limited"}}""")
                .build(),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        val errorMessage = assertNotNull(result.message.errorMessage)
        assertTrue(errorMessage.contains("429"))
        assertTrue(errorMessage.contains("rate limited"))
        assertEquals("error", eventTypes(result.events).last())
    }

    @Test
    fun retriesRetryableResponseUsingRetryAfterMs() = withServer { server, transport ->
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
            StreamOptions(apiKey = "test-key", maxRetries = 1, maxRetryDelayMs = 1_000),
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
            StreamOptions(apiKey = "test-key", maxRetries = 2, maxRetryDelayMs = 1_000),
        )

        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("Server requested 277403s retry delay (max: 1s)"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun abortedSignalProducesAbortedErrorEvent() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_abort", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "first")
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "second")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_abort", "firstsecond"))
                },
                event("response.completed") { put("response", responseObject("resp_abort", "completed")) },
            ),
        )
        val signal = RunSignal()
        val options = StreamOptions(
            apiKey = "test-key",
            signal = signal,
            onProviderStreamEvent = { data, _ ->
                val type = (data as? JsonObject)?.stringField("type")
                if (type == "response.output_text.delta") signal.abort()
            },
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")), options)

        assertEquals("error", eventTypes(result.events).last())
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
        assertTrue(eventTypes(result.events).contains("text_delta"))
    }

    @Test
    fun buildsReasoningCacheAndSamplingRequestBody() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_req", "completed")) }),
        )
        val thinkingLevelMap = mapOf(
            "off" to "none",
            "minimal" to null,
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to "xhigh",
            "max" to "max",
        )
        val model = testModel(server, reasoning = true, thinkingLevelMap = thinkingLevelMap)
        val result = runSimple(
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(
                    apiKey = "test-key",
                    sessionId = "session-1",
                    maxTokens = 100,
                    temperature = 0.4,
                ),
                reasoning = ThinkingLevel.High,
                toolChoice = ToolChoice.None,
            ),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val body = nextRequest(server)
        assertEquals("gpt-test", body.stringField("model"))
        assertEquals("true", (body["stream"] as JsonPrimitive).content)
        assertEquals("false", (body["store"] as JsonPrimitive).content)
        assertEquals("session-1", body.stringField("prompt_cache_key"))
        assertEquals("100", (body["max_output_tokens"] as JsonPrimitive).content)
        assertEquals("0.4", (body["temperature"] as JsonPrimitive).content)
        assertEquals("none", body.stringField("tool_choice"))
        val reasoning = assertNotNull(body["reasoning"] as? JsonObject)
        assertEquals("high", reasoning.stringField("effort"))
        assertEquals("auto", reasoning.stringField("summary"))
        val include = assertNotNull(body.arrayField("include"))
        assertEquals("reasoning.encrypted_content", (include.first() as JsonPrimitive).content)
        val system = assertNotNull(body.inputItems().firstOrNull { it.stringField("role") == "developer" })
        assertTrue(system.stringField("content")!!.contains("test assistant"))
    }

    @Test
    fun respectsResponsesCompatOverrides() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_compat", "completed")) }),
        )
        val compat = buildJsonObject {
            put("supportsDeveloperRole", false)
            put("sessionAffinityFormat", "openrouter")
            put("supportsStrictMode", true)
        }
        val tool = Tool(
            name = "lookup",
            description = "Look up a value",
            parameters = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("query", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject { put("type", "number") })
                    },
                )
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            },
            constrainedSampling = buildJsonObject {
                put("type", "json_schema")
                put("strict", "prefer")
            },
        )
        runStream(
            transport,
            testModel(server, compat = compat),
            context(userText("Hi"), tools = listOf(tool)),
            StreamOptions(apiKey = "test-key", sessionId = "session-compat"),
        )
        val recorded = nextRecorded(server)
        assertEquals("session-compat", recorded.headers["x-session-id"])
        val body = PiJson.parseToJsonElement(assertNotNull(recorded.body).utf8()) as JsonObject
        val input = body.inputItems()
        assertNotNull(input.firstOrNull { it.stringField("role") == "system" })
        assertNull(input.firstOrNull { it.stringField("role") == "developer" })
        val functionTool = assertNotNull(body.arrayField("tools")).single() as JsonObject
        assertEquals("lookup", functionTool.stringField("name"))
        assertEquals("true", (functionTool["strict"] as JsonPrimitive).content)
        val parameters = assertNotNull(functionTool["parameters"] as JsonObject)
        assertEquals("false", (parameters["additionalProperties"] as JsonPrimitive).content)
        assertTrue((parameters["required"] as JsonArray).any { (it as JsonPrimitive).content == "limit" })
    }

    @Test
    fun emitsConfigurationUpdateWhenMidConvoEffortIsSupported() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_effort", "completed")) }),
        )
        val thinkingLevelMap = mapOf(
            "off" to "none",
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
        )
        val model = testModel(
            server,
            reasoning = true,
            thinkingLevelMap = thinkingLevelMap,
            compat = buildJsonObject { put("supportsMidConvoEffort", true) },
        )
        val result = runSimple(
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.High),
        )
        assertEquals("high", result.message.providerThinkingLevel)
        val body = nextRequest(server)
        assertEquals("medium", (body["reasoning"] as JsonObject).stringField("effort"))
        val last = body.inputItems().last()
        assertEquals("configuration_update", last.stringField("type"))
        assertEquals("high", (last["reasoning"] as JsonObject).stringField("effort"))

        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_effort2", "completed")) }),
        )
        val replayAssistant = result.message.copy(content = mutableListOf(TextContent("previous answer")))
        runSimple(
            transport,
            model,
            TranscriptContext(listOf(userText("Hi"), replayAssistant, userText("Again"))),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.High),
        )
        val replayInput = nextRequest(server).inputItems()
        val updateIndex = replayInput.indexOfFirst { it.stringField("type") == "configuration_update" }
        val assistantMessageIndex = replayInput.indexOfFirst { it.stringField("type") == "message" }
        assertTrue(updateIndex >= 0 && updateIndex < assistantMessageIndex)
    }

    @Test
    fun convertsCustomToolCallWithGrammarInput() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "custom_tool_call")
                            put("id", "ctc_1")
                            put("call_id", "call_1")
                            put("name", "query")
                            put("input", "")
                        },
                    )
                },
                event("response.custom_tool_call_input.delta") {
                    put("output_index", 0)
                    put("delta", "hel")
                },
                event("response.custom_tool_call_input.delta") {
                    put("output_index", 0)
                    put("delta", "lo")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "custom_tool_call")
                            put("id", "ctc_1")
                            put("call_id", "call_1")
                            put("name", "query")
                            put("input", "hello")
                        },
                    )
                },
                event("response.completed") { put("response", responseObject("resp_custom", "completed")) },
            ),
        )
        val tool = Tool(
            name = "query",
            description = "Run a grammar query",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("input", buildJsonObject { put("type", "string") }) })
                put("required", JsonArray(listOf(JsonPrimitive("input"))))
            },
            constrainedSampling = buildJsonObject {
                put("type", "grammar")
                put("variants", buildJsonObject { put("openai_lark", "start: /[a-z]+/") })
            },
        )
        val model = testModel(server, compat = buildJsonObject { put("supportsOpenAIGrammarTools", true) })
        val result = runStream(
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
            StreamOptions(apiKey = "test-key"),
        )
        val toolCall = assertNotNull(result.message.content.filterIsInstance<ToolCall>().firstOrNull())
        assertEquals("call_1|ctc_1", toolCall.id)
        assertEquals("hello", (toolCall.arguments["input"] as JsonPrimitive).content)
        assertTrue(eventTypes(result.events).contains("toolcall_end"))
        val body = nextRequest(server)
        val customTool = assertNotNull(body.arrayField("tools")).single() as JsonObject
        assertEquals("custom", customTool.stringField("type"))
        val format = assertNotNull(customTool["format"] as JsonObject)
        assertEquals("grammar", format.stringField("type"))
        assertEquals("lark", format.stringField("syntax"))
    }

    @Test
    fun replaysTextSignatureAsMessageIdAndPhase() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_replay", "completed")) }),
        )
        val assistant = emptyAssistant(
            mutableListOf(
                TextContent(text = "answer", textSignature = """{"v":1,"id":"msg_replay","phase":"commentary"}"""),
            ),
        )
        runStream(transport, testModel(server), TranscriptContext(listOf(userText("Hi"), assistant)))
        val body = nextRequest(server)
        val message = assertNotNull(body.inputItems().firstOrNull { it.stringField("type") == "message" })
        assertEquals("msg_replay", message.stringField("id"))
        assertEquals("commentary", message.stringField("phase"))
        val content = assertNotNull(message.arrayField("content")).single() as JsonObject
        assertEquals("output_text", content.stringField("type"))
        assertEquals("answer", content.stringField("text"))
    }

    @Test
    fun legacyPlainTextSignatureFallsBackToMessageId() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_legacy", "completed")) }),
        )
        val assistant = emptyAssistant(mutableListOf(TextContent(text = "legacy", textSignature = "msg_legacy_plain")))
        runStream(transport, testModel(server), TranscriptContext(listOf(userText("Hi"), assistant)))
        val body = nextRequest(server)
        val message = assertNotNull(body.inputItems().firstOrNull { it.stringField("type") == "message" })
        assertEquals("msg_legacy_plain", message.stringField("id"))
        assertNull(message.stringField("phase"))
    }

    @Test
    fun reportsMissingApiKeyAsErrorEvent() = withServer { server, transport ->
        enqueueSse(server, sse(event("response.completed") { put("response", responseObject("x", "completed")) }))
        val result = runStream(transport, testModel(server), context(userText("Hi")), StreamOptions())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("No API key for provider: openai", result.message.errorMessage)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun synthesizesOrphanToolResultsOnReplay() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_orphan", "completed")) }),
        )
        val assistant = emptyAssistant(
            mutableListOf(
                ToolCall(
                    id = "call_orphan|fc_orphan",
                    name = "bash",
                    arguments = buildJsonObject { put("command", "ls") },
                ),
            ),
        ).copy(stopReason = StopReason.ToolUse)
        runStream(
            transport,
            testModel(server),
            TranscriptContext(listOf(userText("Hi"), assistant)),
            StreamOptions(apiKey = "test-key"),
        )
        val body = nextRequest(server)
        val input = body.inputItems()
        val originalCall = assertNotNull(input.firstOrNull { it.stringField("type") == "function_call" })
        assertEquals("call_orphan", originalCall.stringField("call_id"))
        assertEquals("fc_orphan", originalCall.stringField("id"))
        val synthesized = assertNotNull(input.firstOrNull { it.stringField("type") == "function_call_output" })
        assertEquals("call_orphan", synthesized.stringField("call_id"))
        assertEquals("No result provided", synthesized.stringField("output"))
    }

    @Test
    fun normalizesForeignToolCallIdsIntoBoundedFcShape() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_foreign", "completed")) }),
        )
        val rawId = "call_foreign|X/C0+B2=="
        val assistant = AssistantMessage(
            content = mutableListOf(
                ToolCall(id = rawId, name = "edit", arguments = buildJsonObject { put("path", "app.css") }),
            ),
            api = "anthropic-messages",
            provider = "github-copilot",
            model = "gpt-5.5",
            usage = Usage(
                input = 0,
                output = 0,
                cacheRead = 0,
                cacheWrite = 0,
                totalTokens = 0,
                cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
            ),
            stopReason = StopReason.ToolUse,
            timestamp = 3,
        )
        val toolResult = ToolResultMessage(
            toolCallId = rawId,
            toolName = "edit",
            content = listOf(TextContent("ok")),
            isError = false,
            timestamp = 4,
        )
        runStream(
            transport,
            testModel(server, provider = "openai-codex"),
            TranscriptContext(listOf(userText("Hi"), assistant, toolResult)),
            StreamOptions(apiKey = "test-key"),
        )
        val body = nextRequest(server)
        val input = body.inputItems()
        val originalCall = assertNotNull(input.firstOrNull { it.stringField("type") == "function_call" })
        assertEquals("call_foreign", originalCall.stringField("call_id"))
        val itemId = assertNotNull(originalCall.stringField("id"))
        assertTrue(itemId.startsWith("fc_"))
        assertTrue(itemId.length <= 64)
        val callOutput = assertNotNull(input.firstOrNull { it.stringField("type") == "function_call_output" })
        assertEquals("call_foreign", callOutput.stringField("call_id"))
    }
}
