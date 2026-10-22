package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.CacheRetention
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelCatalog
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
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicMessagesTest {
    private class MockTransport : HttpTransport {
        var lastRequest: HttpRequest? = null
        var requestCount: Int = 0
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            requestCount += 1
            requests.add(request)
            val connection = URL(request.url).openConnection() as HttpURLConnection
            connection.requestMethod = request.method
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val headers = mutableMapOf<String, String>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null && values != null) headers[name] = values.joinToString(", ")
            }
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
            }
            val bytes = stream.use { it.readBytes() }
            return HttpResponse(status, headers, Buffer().write(bytes))
        }
    }

    private class StreamResult(
        val message: AssistantMessage,
        val events: List<AssistantMessageEvent>,
        val transport: MockTransport,
    )

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun event(type: String, payload: String): String = "event: $type\ndata: $payload\n\n"

    private fun sse(vararg payloads: String): String = payloads.joinToString("")

    private fun sseEvent(type: String, json: String): String = event(type, json)

    private fun messageStartEvent(
        modelId: String = "claude-test",
        inputTokens: Long = 10,
        outputTokens: Long = 1,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        cacheWrite1h: Long? = null,
        id: String = "msg_test",
    ): String {
        val cacheCreation = if (cacheWrite1h != null) {
            ""","cache_creation":{"ephemeral_1h_input_tokens":$cacheWrite1h}"""
        } else {
            ""
        }
        return sseEvent(
            "message_start",
            """{"type":"message_start","message":{"id":"$id","type":"message","role":"assistant","model":"$modelId","content":[],"stop_reason":null,"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens,"cache_read_input_tokens":$cacheRead,"cache_creation_input_tokens":$cacheWrite$cacheCreation}}}""",
        )
    }

    private fun textBlockStart(index: Int, initialText: String = ""): String = sseEvent(
        "content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":"$initialText"}}""",
    )

    private fun thinkingBlockStart(index: Int, initialThinking: String = "", signature: String = ""): String = sseEvent(
        "content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"thinking","thinking":"$initialThinking","signature":"$signature"}}""",
    )

    private fun toolBlockStart(index: Int, id: String, name: String): String = sseEvent(
        "content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""",
    )

    private fun jsonString(value: String): String = JsonPrimitive(value).toString().removeSurrounding("\"")

    private fun textDelta(index: Int, text: String): String = sseEvent(
        "content_block_delta",
        """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"${jsonString(text)}"}}""",
    )

    private fun thinkingDelta(index: Int, thinking: String): String = sseEvent(
        "content_block_delta",
        """{"type":"content_block_delta","index":$index,"delta":{"type":"thinking_delta","thinking":"${jsonString(thinking)}"}}""",
    )

    private fun signatureDelta(index: Int, signature: String): String = sseEvent(
        "content_block_delta",
        """{"type":"content_block_delta","index":$index,"delta":{"type":"signature_delta","signature":"${jsonString(signature)}"}}""",
    )

    private fun inputJsonDelta(index: Int, partialJson: String): String = sseEvent(
        "content_block_delta",
        """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":"${jsonString(partialJson)}"}}""",
    )

    private fun blockStop(index: Int): String =
        sseEvent("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun messageDelta(stopReason: String, outputTokens: Long = 5, usageExtra: String = ""): String = sseEvent(
        "message_delta",
        """{"type":"message_delta","delta":{"stop_reason":"$stopReason"},"usage":{"output_tokens":$outputTokens$usageExtra}}""",
    )

    private fun messageStop(): String = sseEvent("message_stop", """{"type":"message_stop"}""")

    private fun okResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun serverBaseUrl(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    private fun testModel(
        baseUrl: String,
        provider: String = "test-anthropic",
        id: String = "claude-test",
        reasoning: Boolean = false,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        thinkingLevelMap: Map<String, String?>? = null,
        compat: JsonObject? = null,
        maxTokens: Long = 4096,
        contextWindow: Long = 128_000,
        cost: ModelCost = ModelCost(1.0, 2.0, 0.1, 0.2),
    ): Model = Model(
        id = id,
        name = "Anthropic Test Model",
        api = "anthropic-messages",
        provider = provider,
        baseUrl = baseUrl,
        input = input,
        cost = cost,
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        compat = compat,
    )

    private fun context(vararg messages: Message, tools: List<Tool> = emptyList()): TranscriptContext =
        TranscriptContext(
            listOf(
                SystemMessage(
                    content = PlainTextContent("You are a helpful assistant."),
                    toolsAdded = tools.takeIf { it.isNotEmpty() },
                    timestamp = 0,
                ),
            ) + messages,
        )

    private fun userText(text: String): UserMessage = UserMessage(PlainTextContent(text), timestamp = 1)

    private fun runStream(
        api: AnthropicMessagesApi,
        transport: MockTransport,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions = StreamOptions(apiKey = "test-key", cacheRetention = CacheRetention.None),
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val stream = api.stream(model, context, options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events, transport)
        }
    }

    private fun runSimple(
        api: AnthropicMessagesApi,
        transport: MockTransport,
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val stream = api.streamSimple(model, context, options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events, transport)
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

    private fun bodyOf(transport: MockTransport): JsonObject {
        val body = assertNotNull(transport.lastRequest?.body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    private fun JsonObject.messageList(): List<JsonObject> =
        (this["messages"] as JsonArray).map { it as JsonObject }

    private fun JsonObject.stringFieldOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.toolList(): List<JsonObject>? =
        (this["tools"] as? JsonArray)?.map { it as JsonObject }

    private fun catalogModel(provider: String, id: String, baseUrl: String): Model =
        (ModelCatalog.find(provider, id) as Model).copy(baseUrl = baseUrl)

    @Test
    fun streamsTextDeltasAndParsesUsage() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(inputTokens = 10, outputTokens = 1, cacheRead = 2, cacheWrite = 3, cacheWrite1h = 1),
                    textBlockStart(0),
                    textDelta(0, "Hello"),
                    textDelta(0, " world"),
                    blockStop(0),
                    messageDelta("end_turn", 5),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            eventTypes(result.events),
        )
        val message = result.message
        assertEquals(StopReason.Stop, message.stopReason)
        assertEquals("end_turn", message.rawStopReason)
        assertEquals("msg_test", message.responseId)
        assertEquals(listOf<dev.pi.ai.Content>(TextContent("Hello world")), message.content.toList())
        assertEquals(10L, message.usage.input)
        assertEquals(5L, message.usage.output)
        assertEquals(2L, message.usage.cacheRead)
        assertEquals(3L, message.usage.cacheWrite)
        assertEquals(1L, message.usage.cacheWrite1h)
        assertEquals(20L, message.usage.totalTokens)
        val expectedCost = (10 * 1.0 + 5 * 2.0 + 2 * 0.1 + (3 - 1) * 0.2 + 1 * 1.0 * 2) / 1_000_000.0
        assertTrue(kotlin.math.abs(message.usage.cost.total - expectedCost) < 1e-12)

        val request = assertNotNull(transport.lastRequest)
        assertTrue(request.url.endsWith("/v1/messages"))
        assertEquals("test-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        val body = bodyOf(transport)
        assertEquals("claude-test", body.stringFieldOrNull("model"))
        assertEquals(JsonPrimitive(4096), body["max_tokens"])
        assertEquals(JsonPrimitive(true), body["stream"])
        val system = body["system"] as JsonArray
        assertEquals("You are a helpful assistant.", (system[0] as JsonObject).stringFieldOrNull("text"))
        assertEquals("user", body.messageList()[0].stringFieldOrNull("role"))
    }

    @Test
    fun streamsThinkingAndAccumulatesSignature() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    thinkingBlockStart(0),
                    thinkingDelta(0, "step one, "),
                    thinkingDelta(0, "step two"),
                    signatureDelta(0, "sig-part-1"),
                    signatureDelta(0, "sig-part-2"),
                    blockStop(0),
                    textBlockStart(1),
                    textDelta(1, "answer"),
                    blockStop(1),
                    messageDelta("end_turn"),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server), reasoning = true)
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertEquals(
            listOf(
                "start",
                "thinking_start",
                "thinking_delta",
                "thinking_delta",
                "thinking_end",
                "text_start",
                "text_delta",
                "text_end",
                "done",
            ),
            eventTypes(result.events),
        )
        val thinking = result.message.content[0] as ThinkingContent
        assertEquals("step one, step two", thinking.thinking)
        assertEquals("sig-part-1sig-part-2", thinking.thinkingSignature)
        assertEquals(TextContent("answer"), result.message.content[1])
    }

    @Test
    fun streamsRedactedThinking() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    sseEvent(
                        "content_block_start",
                        """{"type":"content_block_start","index":0,"content_block":{"type":"redacted_thinking","data":"opaque-data"}}""",
                    ),
                    blockStop(0),
                    messageDelta("end_turn"),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server), reasoning = true)
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        val thinking = result.message.content[0] as ThinkingContent
        assertEquals("[Reasoning redacted]", thinking.thinking)
        assertEquals("opaque-data", thinking.thinkingSignature)
        assertEquals(true, thinking.redacted)
        assertEquals(listOf("start", "thinking_start", "thinking_end", "done"), eventTypes(result.events))
    }

    @Test
    fun streamsMultipleToolUsesWithPartialJson() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    toolBlockStart(0, "toolu_1", "read"),
                    inputJsonDelta(0, "{\"path\":"),
                    inputJsonDelta(0, "\"README.md\"}"),
                    blockStop(0),
                    toolBlockStart(1, "toolu_2", "grep"),
                    inputJsonDelta(1, "{\"pattern\":\"foo\"}"),
                    blockStop(1),
                    messageDelta("tool_use"),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        val types = eventTypes(result.events)
        assertEquals(2, types.count { it == "toolcall_start" })
        assertEquals(2, types.count { it == "toolcall_end" })
        val first = result.message.content[0] as ToolCall
        assertEquals("toolu_1", first.id)
        assertEquals("read", first.name)
        assertEquals("""{"path":"README.md"}""", first.arguments.toString())
        assertNull(first.argumentsError)
        val second = result.message.content[1] as ToolCall
        assertEquals("grep", second.name)
        assertEquals("""{"pattern":"foo"}""", second.arguments.toString())
        assertEquals(StopReason.ToolUse, result.message.stopReason)
    }

    @Test
    fun incompleteToolJsonBecomesArgumentsError() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    toolBlockStart(0, "toolu_1", "read"),
                    inputJsonDelta(0, "{\"path\":"),
                    blockStop(0),
                    messageDelta("tool_use"),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        val toolCall = result.message.content[0] as ToolCall
        assertNotNull(toolCall.argumentsError)
        assertTrue(toolCall.argumentsError!!.contains("Received arguments:"))
        assertEquals(JsonObject(emptyMap()), toolCall.arguments)
        assertEquals(StopReason.ToolUse, result.message.stopReason)
    }

    @Test
    fun parsesUsageReportedOnlyInMessageDelta() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(inputTokens = 100, outputTokens = 1),
                    textBlockStart(0),
                    textDelta(0, "hi"),
                    blockStop(0),
                    messageDelta(
                        "end_turn",
                        outputTokens = 7,
                        usageExtra = ""","cache_read_input_tokens":20,"cache_creation_input_tokens":30,"cache_creation":{"ephemeral_1h_input_tokens":10},"output_tokens_details":{"thinking_tokens":4}""",
                    ),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertEquals(100L, result.message.usage.input)
        assertEquals(7L, result.message.usage.output)
        assertEquals(20L, result.message.usage.cacheRead)
        assertEquals(30L, result.message.usage.cacheWrite)
        assertEquals(10L, result.message.usage.cacheWrite1h)
        assertEquals(4L, result.message.usage.reasoning)
        assertEquals(157L, result.message.usage.totalTokens)
        val expectedCacheWrite = ((30 - 10) * 0.2 + 10 * 1.0 * 2) / 1_000_000.0
        assertTrue(kotlin.math.abs(result.message.usage.cost.cacheWrite - expectedCacheWrite) < 1e-12)
    }

    @Test
    fun sseErrorEventBecomesErrorEvent() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    textBlockStart(0),
                    sseEvent("error", """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertEquals("error", eventTypes(result.events).last())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("overloaded_error"))
    }

    @Test
    fun httpErrorBodyIsParsed() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"invalid_request_error","message":"bad request"}}"""),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertEquals(1, transport.requestCount)
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("400"))
        assertTrue(result.message.errorMessage!!.contains("bad request"))
    }

    @Test
    fun abortProducesAbortedErrorEvent() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    textBlockStart(0),
                    textDelta(0, "first"),
                    textDelta(0, "second"),
                    blockStop(0),
                    messageDelta("end_turn"),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val signal = RunSignal()
        val options = StreamOptions(
            apiKey = "test-key",
            signal = signal,
            cacheRetention = CacheRetention.None,
            onProviderStreamEvent = { _, _ -> signal.abort() },
        )
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")), options)

        assertEquals("error", eventTypes(result.events).last())
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
    }

    @Test
    fun streamEndingBeforeMessageStopFails() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    textBlockStart(0),
                    textDelta(0, "partial"),
                    blockStop(0),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Anthropic stream ended before message_stop", result.message.errorMessage)
    }

    @Test
    fun streamSimpleDisablesThinkingWithoutReasoning() = withServer { server ->
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        val transport = MockTransport()
        val model = catalogModel("anthropic", "claude-sonnet-4-5", serverBaseUrl(server))
        runSimple(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key", cacheRetention = CacheRetention.None)),
        )
        val body = bodyOf(transport)
        assertEquals(JsonPrimitive("disabled"), (body["thinking"] as JsonObject)["type"])
        assertNull(body["output_config"])
    }

    @Test
    fun streamSimpleUsesAdaptiveThinkingForAdaptiveModels() = withServer { server ->
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        val transport = MockTransport()
        val model = catalogModel("anthropic", "claude-opus-4-8", serverBaseUrl(server))
        runSimple(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key", temperature = 0.5, cacheRetention = CacheRetention.None),
                reasoning = dev.pi.ai.ThinkingLevel.High,
            ),
        )
        val body = bodyOf(transport)
        val thinking = body["thinking"] as JsonObject
        assertEquals(JsonPrimitive("adaptive"), thinking["type"])
        assertEquals(JsonPrimitive("summarized"), thinking["display"])
        assertEquals(JsonPrimitive("high"), (body["output_config"] as JsonObject)["effort"])
        assertNull(body["temperature"])
    }

    @Test
    fun streamSimpleUsesBudgetThinkingForOlderModels() = withServer { server ->
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        val transport = MockTransport()
        val model = catalogModel("anthropic", "claude-sonnet-4-5", serverBaseUrl(server))
        runSimple(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key", cacheRetention = CacheRetention.None),
                reasoning = dev.pi.ai.ThinkingLevel.Medium,
            ),
        )
        val body = bodyOf(transport)
        val thinking = body["thinking"] as JsonObject
        assertEquals(JsonPrimitive("enabled"), thinking["type"])
        assertEquals(JsonPrimitive(8192L), thinking["budget_tokens"])
        assertEquals(JsonPrimitive(64000L), body["max_tokens"])
    }

    @Test
    fun appliesAnthropicCacheControlMarkers() = withServer { server ->
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val tool = Tool(
            name = "read",
            description = "Read a file",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )
        runStream(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
            StreamOptions(apiKey = "test-key"),
        )
        val body = bodyOf(transport)
        val system = body["system"] as JsonArray
        assertEquals(
            JsonPrimitive("ephemeral"),
            ((system[0] as JsonObject)["cache_control"] as JsonObject)["type"],
        )
        val tools = assertNotNull(body.toolList())
        assertEquals(
            JsonPrimitive("ephemeral"),
            (tools[0]["cache_control"] as JsonObject)["type"],
        )
        val lastMessage = body.messageList().last()
        val lastBlocks = lastMessage["content"] as JsonArray
        assertEquals(
            JsonPrimitive("ephemeral"),
            ((lastBlocks[lastBlocks.size - 1] as JsonObject)["cache_control"] as JsonObject)["type"],
        )
    }

    @Test
    fun oauthTokenUsesClaudeCodeHeadersAndToolNames() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(),
                    toolBlockStart(0, "toolu_1", "Read"),
                    inputJsonDelta(0, "{\"path\":\"x\"}"),
                    blockStop(0),
                    messageDelta("tool_use"),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server), provider = "anthropic")
        val tool = Tool(
            name = "read",
            description = "Read a file",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )
        val result = runStream(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
            StreamOptions(apiKey = "sk-ant-oat-test", cacheRetention = CacheRetention.None),
        )

        val request = assertNotNull(transport.lastRequest)
        assertEquals("Bearer sk-ant-oat-test", request.headers["Authorization"])
        assertNull(request.headers["x-api-key"])
        assertEquals("claude-cli/2.1.280", request.headers["user-agent"])
        assertTrue(request.headers["anthropic-beta"]!!.contains("claude-code-20250219"))
        assertTrue(request.headers["anthropic-beta"]!!.contains("oauth-2025-04-20"))
        val body = bodyOf(transport)
        val system = body["system"] as JsonArray
        assertEquals(
            AnthropicMessagesApiTestConstants.CLAUDE_CODE_IDENTITY,
            (system[0] as JsonObject).stringFieldOrNull("text"),
        )
        val tools = assertNotNull(body.toolList())
        assertEquals("Read", tools[0].stringFieldOrNull("name"))
        val toolCall = result.message.content[0] as ToolCall
        assertEquals("read", toolCall.name)
        assertEquals(StopReason.ToolUse, result.message.stopReason)
    }

    @Test
    fun strictToolsConvertSchemaAndRejectUnsupportedKeywords() = withServer { server ->
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        val transport = MockTransport()
        val model = testModel(
            serverBaseUrl(server),
            compat = buildJsonObject { put("supportsStrictTools", JsonPrimitive(true)) },
        )
        val api = AnthropicMessagesApi(transport)
        val strictTool = Tool(
            name = "lookup",
            description = "Look up a value",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("strict", JsonPrimitive("prefer"))
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put("value", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("optional", buildJsonObject { put("type", JsonPrimitive("number")) })
                    },
                )
                put("required", JsonArray(listOf(JsonPrimitive("value"))))
            },
        )
        runStream(api, transport, model, context(userText("Hi"), tools = listOf(strictTool)))
        var tools = assertNotNull(bodyOf(transport).toolList())
        assertEquals(JsonPrimitive(true), tools[0]["strict"])
        val inputSchema = tools[0]["input_schema"] as JsonObject
        assertEquals(JsonPrimitive(false), inputSchema["additionalProperties"])
        assertEquals(JsonArray(listOf(JsonPrimitive("value"), JsonPrimitive("optional"))), inputSchema["required"])

        val unsupportedTool = Tool(
            name = "lookup",
            description = "Look up a value",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("strict", JsonPrimitive("prefer"))
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "timeout",
                            buildJsonObject {
                                put("type", JsonPrimitive("integer"))
                                put("minimum", JsonPrimitive(1))
                            },
                        )
                    },
                )
            },
        )
        runStream(api, transport, model, context(userText("Hi"), tools = listOf(unsupportedTool)))
        tools = assertNotNull(bodyOf(transport).toolList())
        assertNull(tools[0]["strict"])
        val legacySchema = tools[0]["input_schema"] as JsonObject
        assertEquals(setOf("type", "properties", "required"), legacySchema.keys)
    }

    @Test
    fun preferredStrictToolsFallBackOnceOnRejection() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"type":"error","error":{"type":"invalid_request_error","message":"The compiled grammar is too large, which would cause performance issues."}}""",
                ),
        )
        server.enqueue(
            okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())),
        )
        val transport = MockTransport()
        val model = testModel(
            serverBaseUrl(server),
            compat = buildJsonObject { put("supportsStrictTools", JsonPrimitive(true)) },
        )
        val preferred = Tool(
            name = "preferred",
            description = "Preferred",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("strict", JsonPrimitive("prefer"))
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject { put("value", buildJsonObject { put("type", JsonPrimitive("string")) }) },
                )
            },
        )
        val required = Tool(
            name = "required",
            description = "Required",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("strict", JsonPrimitive("require"))
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject { put("value", buildJsonObject { put("type", JsonPrimitive("string")) }) },
                )
            },
        )
        val result = runStream(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(preferred, required)),
        )

        assertEquals(2, transport.requestCount)
        assertEquals(StopReason.Stop, result.message.stopReason)
        val diagnostics = assertNotNull(result.message.diagnostics)
        assertEquals("anthropic_strict_tools_fallback", diagnostics[0].type)
        assertEquals(
            JsonPrimitive("strict_tool_limits_exceeded"),
            diagnostics[0].details?.get("reason"),
        )
        val firstBody = PiJson.parseToJsonElement(transport.requests[0].body!!.decodeToString()) as JsonObject
        val secondBody = bodyOf(transport)
        val firstTools = assertNotNull(firstBody.toolList())
        val secondTools = assertNotNull(secondBody.toolList())
        assertEquals(JsonPrimitive(true), firstTools[0]["strict"])
        assertNull(secondTools[0]["strict"])
        assertEquals(JsonPrimitive(true), secondTools[1]["strict"])
    }

    @Test
    fun eagerToolInputStreamingCompatControlsToolFieldAndBeta() = withServer { server ->
        server.enqueue(okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())))
        server.enqueue(okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())))
        val transport = MockTransport()
        val api = AnthropicMessagesApi(transport)
        val tool = Tool(
            name = "lookup",
            description = "Look up a value",
            parameters = buildJsonObject { put("type", JsonPrimitive("object")) },
        )
        val model = testModel(serverBaseUrl(server))
        runStream(api, transport, model, context(userText("Hi"), tools = listOf(tool)))
        var tools = assertNotNull(bodyOf(transport).toolList())
        assertEquals(JsonPrimitive(true), tools[0]["eager_input_streaming"])
        assertNull(transport.lastRequest?.headers?.get("anthropic-beta"))

        val compatModel = testModel(
            serverBaseUrl(server),
            compat = buildJsonObject { put("supportsEagerToolInputStreaming", JsonPrimitive(false)) },
        )
        runStream(api, transport, compatModel, context(userText("Hi"), tools = listOf(tool)))
        tools = assertNotNull(bodyOf(transport).toolList())
        assertNull(tools[0]["eager_input_streaming"])
        assertTrue(transport.lastRequest?.headers?.get("anthropic-beta")!!.contains("fine-grained-tool-streaming"))
    }

    @Test
    fun splitsErrorToolResultImagesIntoFollowingMessage() = withServer { server ->
        server.enqueue(okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())))
        val transport = MockTransport()
        val model = testModel(
            serverBaseUrl(server),
            input = listOf(ModelInputModality.Text, ModelInputModality.Image),
        )
        val assistant = AssistantMessage(
            content = mutableListOf(ToolCall(id = "toolu_1", name = "read", arguments = JsonObject(emptyMap()))),
            api = "anthropic-messages",
            provider = "test-anthropic",
            model = "claude-test",
            usage = dev.pi.ai.Usage.empty(),
            stopReason = StopReason.ToolUse,
            timestamp = 2,
        )
        val toolResult = ToolResultMessage(
            toolCallId = "toolu_1",
            toolName = "read",
            content = listOf(TextContent("failed"), ImageContent(data = "AAA", mimeType = "image/png")),
            isError = true,
            timestamp = 3,
        )
        val replayContext = TranscriptContext(
            listOf(
                SystemMessage(PlainTextContent("You are a helpful assistant."), timestamp = 0),
                userText("hi"),
                assistant,
                toolResult,
            ),
        )
        runStream(AnthropicMessagesApi(transport), transport, model, replayContext)

        val messages = bodyOf(transport).messageList()
        val lastMessage = messages.last()
        val blocks = lastMessage["content"] as JsonArray
        assertEquals(3, blocks.size)
        val toolResultBlock = blocks[0] as JsonObject
        assertEquals("tool_result", toolResultBlock.stringFieldOrNull("type"))
        val splitContent = toolResultBlock.stringFieldOrNull("content")
        assertEquals("failed\n(image attached after the tool results)", splitContent)
        assertEquals("text", (blocks[1] as JsonObject).stringFieldOrNull("type"))
        assertEquals("image", (blocks[2] as JsonObject).stringFieldOrNull("type"))
    }

    @Test
    fun midConversationEffortAddsSystemEffortMarkers() = withServer { server ->
        server.enqueue(okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())))
        val transport = MockTransport()
        val model = testModel(
            serverBaseUrl(server),
            reasoning = true,
            compat = buildJsonObject {
                put("supportsMidConvoEffort", JsonPrimitive(true))
                put("forceAdaptiveThinking", JsonPrimitive(true))
            },
        )
        val result = runStream(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", cacheRetention = CacheRetention.None),
        )
        val request = assertNotNull(transport.lastRequest)
        val beta = request.headers["anthropic-beta"]!!
        assertTrue(beta.contains("mid-conversation-output-config-2026-07-01"))
        assertTrue(beta.contains("thinking-binding-controls-2026-08-01"))
        val messages = bodyOf(transport).messageList()
        val last = messages.last()
        assertEquals("system", last.stringFieldOrNull("role"))
        assertEquals("high", ((last["output_config"] as JsonObject)["effort"] as JsonPrimitive).content)
        assertEquals("high", result.message.providerThinkingLevel)
    }

    @Test
    fun serverSideFallbackAddsBetasFallbacksAndRoutedCost() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    messageStartEvent(modelId = "fallback-model", inputTokens = 10, outputTokens = 2),
                    textBlockStart(0),
                    textDelta(0, "fallback"),
                    blockStop(0),
                    messageDelta("end_turn", 3),
                    messageStop(),
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(
            serverBaseUrl(server),
            compat = buildJsonObject {
                put(
                    "allowedFallbackModels",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("provider", JsonPrimitive("test-anthropic"))
                                put("model", JsonPrimitive("fallback-model"))
                                put(
                                    "cost",
                                    buildJsonObject {
                                        put("input", JsonPrimitive(2.0))
                                        put("output", JsonPrimitive(4.0))
                                        put("cacheRead", JsonPrimitive(0.2))
                                        put("cacheWrite", JsonPrimitive(0.4))
                                    },
                                )
                            },
                        ),
                    ),
                )
            },
        )
        val result = runStream(AnthropicMessagesApi(transport), transport, model, context(userText("Hi")))

        assertTrue(transport.lastRequest?.headers?.get("anthropic-beta")!!.contains("server-side-fallback"))
        val body = bodyOf(transport)
        val fallbacks = body["fallbacks"] as JsonArray
        assertEquals("fallback-model", ((fallbacks[0] as JsonObject)["model"] as JsonPrimitive).content)
        assertEquals("fallback-model", result.message.responseModel)
        assertTrue(kotlin.math.abs(result.message.usage.cost.input - 10 * 2.0 / 1_000_000.0) < 1e-12)
    }

    @Test
    fun headersCanOverrideAnthropicBetaAndCustomHeadersAreSent() = withServer { server ->
        server.enqueue(okResponse(sse(messageStartEvent(), messageDelta("end_turn"), messageStop())))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        runStream(
            AnthropicMessagesApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "test-key",
                cacheRetention = CacheRetention.None,
                headers = mapOf("anthropic-beta" to "custom-beta-1, custom-beta-2", "x-custom" to "yes"),
            ),
        )
        val request = assertNotNull(transport.lastRequest)
        assertEquals("custom-beta-1,custom-beta-2", request.headers["anthropic-beta"])
        assertEquals("yes", request.headers["x-custom"])
    }

    private object AnthropicMessagesApiTestConstants {
        const val CLAUDE_CODE_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."
    }
}
