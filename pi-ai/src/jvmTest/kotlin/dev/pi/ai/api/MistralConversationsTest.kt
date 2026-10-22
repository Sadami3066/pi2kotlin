package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.CacheRetention
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
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
import dev.pi.ai.ThinkingLevel
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MistralConversationsTest {
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

    private fun serverBaseUrl(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    private fun jsonString(value: String): String = JsonPrimitive(value).toString().removeSurrounding("\"")

    private fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\r\n\r\n" }

    private fun okResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun chunk(
        delta: String,
        finishReason: String? = null,
        id: String? = "response-1",
        usage: String? = null,
        extra: String = "",
    ): String {
        val idField = if (id == null) "" else "\"id\":\"$id\","
        val finish = if (finishReason == null) "null" else "\"$finishReason\""
        val usageField = if (usage == null) "" else ",\"usage\":$usage"
        return "{$idField\"choices\":[{\"index\":0,\"finish_reason\":$finish,\"delta\":$delta}]" +
            "$extra$usageField}"
    }

    private fun textChunk(text: String, finishReason: String? = null, usage: String? = null): String =
        chunk("{\"content\":\"${jsonString(text)}\"}", finishReason, usage = usage)

    private fun contentArrayChunk(contentArray: String, finishReason: String? = null, usage: String? = null): String =
        chunk("{\"content\":$contentArray}", finishReason, usage = usage)

    private fun textUsage(
        prompt: Long,
        completion: Long,
        total: Long,
        cached: Long = 0,
    ): String =
        """{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":$total,"prompt_tokens_details":{"cached_tokens":$cached}}"""

    private fun terminalChunk(
        finishReason: String = "stop",
        usage: String? = textUsage(1, 1, 2),
    ): String = chunk("{}", finishReason, usage = usage)

    private fun testModel(
        baseUrl: String,
        provider: String = "mistral",
        id: String = "mistral-large-latest",
        reasoning: Boolean = false,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        thinkingLevelMap: Map<String, String?>? = null,
        maxTokens: Long = 4096,
        contextWindow: Long = 128_000,
        cost: ModelCost = ModelCost(2.0, 6.0, 0.5, 0.0),
    ): Model = Model(
        id = id,
        name = "Mistral Test Model",
        api = "mistral-conversations",
        provider = provider,
        baseUrl = baseUrl,
        input = input,
        cost = cost,
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = contextWindow,
        maxTokens = maxTokens,
    )

    private fun context(vararg messages: Message, tools: List<Tool> = emptyList()): TranscriptContext =
        TranscriptContext(
            listOf(
                SystemMessage(
                    content = PlainTextContent("Be precise"),
                    toolsAdded = tools.takeIf { it.isNotEmpty() },
                    timestamp = 0,
                ),
            ) + messages,
        )

    private fun userText(text: String): UserMessage = UserMessage(PlainTextContent(text), timestamp = 1)

    private fun runStream(
        api: MistralConversationsApi,
        transport: MockTransport,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions = StreamOptions(apiKey = "test-key"),
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val stream = api.stream(model, context, options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events, transport)
        }
    }

    private fun runSimple(
        api: MistralConversationsApi,
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

    private fun bodyAt(transport: MockTransport, index: Int): JsonObject {
        val body = assertNotNull(transport.requests[index].body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.messageList(): List<JsonObject> =
        (this["messages"] as JsonArray).map { it as JsonObject }

    private fun JsonObject.toolList(): List<JsonObject>? =
        (this["tools"] as? JsonArray)?.map { it as JsonObject }

    @Test
    fun streamsTextDeltasAndUsage() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    textChunk("Hello"),
                    textChunk(" world"),
                    terminalChunk("stop", textUsage(prompt = 10, completion = 4, total = 14, cached = 3)),
                    "[DONE]",
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            eventTypes(result.events),
        )
        val message = result.message
        assertEquals(StopReason.Stop, message.stopReason)
        assertEquals("stop", message.rawStopReason)
        assertEquals("response-1", message.responseId)
        assertEquals(listOf<dev.pi.ai.Content>(TextContent("Hello world")), message.content.toList())
        assertEquals(7L, message.usage.input)
        assertEquals(4L, message.usage.output)
        assertEquals(3L, message.usage.cacheRead)
        assertEquals(0L, message.usage.cacheWrite)
        assertEquals(14L, message.usage.totalTokens)
        val expectedCost = (7 * 2.0 + 4 * 6.0 + 3 * 0.5) / 1_000_000.0
        assertTrue(abs(message.usage.cost.total - expectedCost) < 1e-12)

        val request = assertNotNull(transport.lastRequest)
        assertTrue(request.url.endsWith("/v1/chat/completions"))
        assertEquals("Bearer test-key", request.headers["authorization"])
        assertEquals("text/event-stream", request.headers["accept"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals("pi", request.headers["User-Agent"])
        val body = bodyOf(transport)
        assertEquals("mistral-large-latest", body.stringAt("model"))
        assertEquals(JsonPrimitive(true), body["stream"])
        val messages = body.messageList()
        assertEquals("system", messages[0].stringAt("role"))
        assertEquals("Be precise", messages[0].stringAt("content"))
        assertEquals("user", messages[1].stringAt("role"))
        assertEquals("Hi", messages[1].stringAt("content"))
    }

    @Test
    fun streamsThinkingTextAndFragmentedToolCalls() = withServer { server ->
        val thinkingDelta = """{"type":"thinking","thinking":[{"type":"text","text":"reason"}]}"""
        server.enqueue(
            okResponse(
                sse(
                    contentArrayChunk("[$thinkingDelta]"),
                    contentArrayChunk("""[{"type":"text","text":"answer"}]"""),
                    chunk(
                        """{"tool_calls":[{"id":"abc123456","index":0,"function":{"name":"lookup","arguments":"{\"query\":"}}]}""",
                    ),
                    chunk(
                        """{"tool_calls":[{"index":0,"function":{"name":"","arguments":"\"pi\"}"}}]}""",
                        "tool_calls",
                        usage = textUsage(prompt = 10, completion = 4, total = 14, cached = 3),
                    ),
                    "[DONE]",
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.ToolUse, result.message.stopReason)
        assertEquals("tool_calls", result.message.rawStopReason)
        assertEquals("response-1", result.message.responseId)
        assertEquals(
            listOf<dev.pi.ai.Content>(
                ThinkingContent(thinking = "reason"),
                TextContent("answer"),
                ToolCall(id = "abc123456", name = "lookup", arguments = buildJsonObject { put("query", JsonPrimitive("pi")) }),
            ),
            result.message.content.toList(),
        )
        assertEquals(7L, result.message.usage.input)
        assertEquals(4L, result.message.usage.output)
        assertEquals(3L, result.message.usage.cacheRead)
        assertEquals(14L, result.message.usage.totalTokens)
        val types = eventTypes(result.events)
        assertEquals(
            listOf(
                "start",
                "thinking_start",
                "thinking_delta",
                "thinking_end",
                "text_start",
                "text_delta",
                "text_end",
                "toolcall_start",
                "toolcall_delta",
                "toolcall_delta",
                "toolcall_end",
                "done",
            ),
            types,
        )
    }

    @Test
    fun ignoresEmptyContentDeltas() = withServer { server ->
        val thinking = """{"type":"thinking","thinking":[{"type":"text","text":"part"}]}"""
        server.enqueue(
            okResponse(
                sse(
                    chunk("""{"content":""}"""),
                    contentArrayChunk("[$thinking]"),
                    chunk("""{"content":""}"""),
                    contentArrayChunk("""[{"type":"text","text":""}]"""),
                    contentArrayChunk("""[{"type":"thinking","thinking":[{"type":"text","text":" two"}]},{"type":"text","text":"Reading."}]"""),
                    chunk("""{"content":"","tool_calls":[{"index":0,"id":"abc123456","function":{"name":"read","arguments":""}}]}"""),
                    chunk("""{"content":"","tool_calls":[{"index":0,"function":{"name":"","arguments":"{\"path\":"}}]}"""),
                    chunk("""{"content":"","tool_calls":[{"index":0,"function":{"name":"","arguments":"\"a.txt\"}"}}]}""", "tool_calls"),
                    "[DONE]",
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(3, result.message.content.size)
        assertEquals(ThinkingContent(thinking = "part two"), result.message.content[0])
        assertEquals(TextContent("Reading."), result.message.content[1])
        val toolCall = result.message.content[2] as ToolCall
        assertEquals("read", toolCall.name)
        assertEquals("""{"path":"a.txt"}""", toolCall.arguments.toString())
        assertEquals(StopReason.ToolUse, result.message.stopReason)
    }

    @Test
    fun replaysAssistantThinkingToolCallsAndToolResults() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server), input = listOf(ModelInputModality.Text, ModelInputModality.Image))
        val assistant = AssistantMessage(
            content = mutableListOf(
                ThinkingContent(thinking = "reason"),
                TextContent("answer"),
                ToolCall(id = "abc123456", name = "lookup", arguments = buildJsonObject { put("query", JsonPrimitive("pi")) }),
            ),
            api = "mistral-conversations",
            provider = "mistral",
            model = "mistral-large-latest",
            usage = dev.pi.ai.Usage.empty(),
            stopReason = StopReason.ToolUse,
            timestamp = 1,
        )
        val toolResult = ToolResultMessage(
            toolCallId = "abc123456",
            toolName = "lookup",
            content = listOf(TextContent("found"), ImageContent(data = "aGVsbG8=", mimeType = "image/png")),
            isError = false,
            timestamp = 2,
        )
        runStream(
            MistralConversationsApi(transport),
            transport,
            model,
            TranscriptContext(listOf(assistant, toolResult)),
        )

        val messages = bodyOf(transport).messageList()
        assertEquals(2, messages.size)
        val assistantWire = messages[0]
        assertEquals("assistant", assistantWire.stringAt("role"))
        assertEquals(JsonPrimitive(false), assistantWire["prefix"])
        val content = assistantWire["content"] as JsonArray
        assertEquals("thinking", (content[0] as JsonObject).stringAt("type"))
        assertEquals("text", (content[1] as JsonObject).stringAt("type"))
        val toolCalls = assistantWire["tool_calls"] as JsonArray
        val toolCall = toolCalls[0] as JsonObject
        assertEquals("abc123456", toolCall.stringAt("id"))
        assertEquals(JsonPrimitive(0), toolCall["index"])
        val function = toolCall["function"] as JsonObject
        assertEquals("lookup", function.stringAt("name"))
        assertEquals("""{"query":"pi"}""", function.stringAt("arguments"))
        val toolWire = messages[1]
        assertEquals("tool", toolWire.stringAt("role"))
        assertEquals("abc123456", toolWire.stringAt("tool_call_id"))
        assertEquals("lookup", toolWire.stringAt("name"))
        val toolContent = toolWire["content"] as JsonArray
        assertEquals("found", (toolContent[0] as JsonObject).stringAt("text"))
        assertEquals("image_url", (toolContent[1] as JsonObject).stringAt("type"))
        assertEquals(
            "data:image/png;base64,aGVsbG8=",
            (toolContent[1] as JsonObject).stringAt("image_url"),
        )
    }

    @Test
    fun normalizesForeignToolCallIdsForReplay() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        val transport = MockTransport()
        val api = MistralConversationsApi(transport)
        val model = testModel(serverBaseUrl(server))
        fun foreignContext(): TranscriptContext {
            val assistant = AssistantMessage(
                content = mutableListOf(
                    ToolCall(
                        id = "call_abc|item_very-long-id-with-special-chars",
                        name = "lookup",
                        arguments = buildJsonObject { put("query", JsonPrimitive("pi")) },
                    ),
                ),
                api = "openai-completions",
                provider = "openai",
                model = "gpt-test",
                usage = dev.pi.ai.Usage.empty(),
                stopReason = StopReason.ToolUse,
                timestamp = 1,
            )
            val toolResult = ToolResultMessage(
                toolCallId = "call_abc|item_very-long-id-with-special-chars",
                toolName = "lookup",
                content = listOf(TextContent("found")),
                isError = false,
                timestamp = 2,
            )
            return TranscriptContext(listOf(assistant, toolResult))
        }
        runStream(api, transport, model, foreignContext())
        runStream(api, transport, model, foreignContext())

        val firstId = (((bodyAt(transport, 0).messageList()[0])["tool_calls"] as JsonArray)[0] as JsonObject).stringAt("id")!!
        val firstToolCallId = bodyAt(transport, 0).messageList()[1].stringAt("tool_call_id")
        assertEquals(firstId, firstToolCallId)
        assertTrue(Regex("^[a-zA-Z0-9]{9}$").matches(firstId))
        val secondId = (((bodyAt(transport, 1).messageList()[0])["tool_calls"] as JsonArray)[0] as JsonObject).stringAt("id")!!
        assertEquals(firstId, secondId)
    }

    @Test
    fun omitsImagesForVisionlessModels() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        val transport = MockTransport()
        val api = MistralConversationsApi(transport)
        val model = testModel(serverBaseUrl(server))
        runStream(
            api,
            transport,
            model,
            TranscriptContext(
                listOf(
                    SystemMessage(PlainTextContent("Be precise"), timestamp = 0),
                    UserMessage(
                        dev.pi.ai.BlockContent(
                            listOf(
                                TextContent("describe"),
                                ImageContent(data = "aGVsbG8=", mimeType = "image/png"),
                            ),
                        ),
                        timestamp = 1,
                    ),
                ),
            ),
        )
        var messages = bodyOf(transport).messageList()
        val content = messages[1]["content"] as JsonArray
        assertEquals(2, content.size)
        assertEquals("describe", (content[0] as JsonObject).stringAt("text"))
        assertEquals(
            "(image omitted: model does not support images)",
            (content[1] as JsonObject).stringAt("text"),
        )

        runStream(
            api,
            transport,
            model,
            TranscriptContext(
                listOf(
                    SystemMessage(PlainTextContent("Be precise"), timestamp = 0),
                    UserMessage(
                        dev.pi.ai.BlockContent(listOf(ImageContent(data = "aGVsbG8=", mimeType = "image/png"))),
                        timestamp = 1,
                    ),
                ),
            ),
        )
        messages = bodyOf(transport).messageList()
        val placeholderContent = messages[1]["content"] as JsonArray
        assertEquals(1, placeholderContent.size)
        assertEquals(
            "(image omitted: model does not support images)",
            (placeholderContent[0] as JsonObject).stringAt("text"),
        )
    }

    @Test
    fun rendersToolResultTextVariants() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server), input = listOf(ModelInputModality.Text, ModelInputModality.Image))
        val assistant = AssistantMessage(
            content = mutableListOf(
                ToolCall(id = "call_1", name = "lookup", arguments = JsonObject(emptyMap())),
                ToolCall(id = "call_2", name = "lookup", arguments = JsonObject(emptyMap())),
                ToolCall(id = "call_3", name = "lookup", arguments = JsonObject(emptyMap())),
            ),
            api = "mistral-conversations",
            provider = "mistral",
            model = "mistral-large-latest",
            usage = dev.pi.ai.Usage.empty(),
            stopReason = StopReason.ToolUse,
            timestamp = 1,
        )
        val results = listOf(
            ToolResultMessage(
                toolCallId = "call_1",
                toolName = "lookup",
                content = listOf(TextContent("boom"), ImageContent(data = "aGVsbG8=", mimeType = "image/png")),
                isError = true,
                timestamp = 2,
            ),
            ToolResultMessage(
                toolCallId = "call_2",
                toolName = "lookup",
                content = listOf(ImageContent(data = "aGVsbG8=", mimeType = "image/png")),
                isError = false,
                timestamp = 3,
            ),
            ToolResultMessage(
                toolCallId = "call_3",
                toolName = "lookup",
                content = emptyList(),
                isError = false,
                timestamp = 4,
            ),
        )
        runStream(MistralConversationsApi(transport), transport, model, TranscriptContext(listOf(assistant) + results))

        val messages = bodyOf(transport).messageList()
        val toolMessages = messages.filter { it.stringAt("role") == "tool" }
        assertEquals(3, toolMessages.size)
        val firstContent = toolMessages[0]["content"] as JsonArray
        assertEquals("[tool error] boom", (firstContent[0] as JsonObject).stringAt("text"))
        assertEquals("image_url", (firstContent[1] as JsonObject).stringAt("type"))
        val secondContent = toolMessages[1]["content"] as JsonArray
        assertEquals("(see attached image)", (secondContent[0] as JsonObject).stringAt("text"))
        assertEquals(2, secondContent.size)
        val thirdContent = toolMessages[2]["content"] as JsonArray
        assertEquals("(no tool output)", (thirdContent[0] as JsonObject).stringAt("text"))
        assertEquals(1, thirdContent.size)
    }

    @Test
    fun httpErrorBecomesFormattedErrorEvent() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(422)
                .setHeader("Content-Type", "application/json")
                .setBody("bad request"),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(1, transport.requestCount)
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Mistral API error (422): bad request", result.message.errorMessage)
        assertEquals("error", eventTypes(result.events).last())
    }

    @Test
    fun invalidStreamEventBecomesError() = withServer { server ->
        server.enqueue(okResponse(sse("""{"foo":1}""", "[DONE]")))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Invalid Mistral streaming event", result.message.errorMessage)
    }

    @Test
    fun abortProducesAbortedErrorEvent() = withServer { server ->
        server.enqueue(
            okResponse(
                sse(
                    textChunk("first"),
                    textChunk("second"),
                    terminalChunk(),
                    "[DONE]",
                ),
            ),
        )
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val signal = RunSignal()
        val options = StreamOptions(
            apiKey = "test-key",
            signal = signal,
            onProviderStreamEvent = { _, _ -> signal.abort() },
        )
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")), options)

        assertEquals("error", eventTypes(result.events).last())
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
    }

    @Test
    fun streamWithoutFinishReasonFails() = withServer { server ->
        server.enqueue(okResponse(sse(textChunk("partial"), "[DONE]")))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Mistral stream ended without a finish reason", result.message.errorMessage)
    }

    @Test
    fun mapsRawStopReasons() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk("stop", usage = null), "[DONE]")))
        server.enqueue(okResponse(sse(terminalChunk("error", usage = null), "[DONE]")))
        server.enqueue(okResponse(sse(terminalChunk("unmapped_error", usage = null), "[DONE]")))
        val transport = MockTransport()
        val api = MistralConversationsApi(transport)
        val model = testModel(serverBaseUrl(server))

        val stopped = runStream(api, transport, model, context(userText("Hi")))
        assertEquals(StopReason.Stop, stopped.message.stopReason)
        assertEquals("stop", stopped.message.rawStopReason)
        assertNull(stopped.message.errorMessage)

        val errored = runStream(api, transport, model, context(userText("Hi")))
        assertEquals(StopReason.Error, errored.message.stopReason)
        assertEquals("error", errored.message.rawStopReason)
        assertEquals("Provider stopped with: error (server error)", errored.message.errorMessage)

        val unmapped = runStream(api, transport, model, context(userText("Hi")))
        assertEquals(StopReason.Error, unmapped.message.stopReason)
        assertEquals("unmapped_error", unmapped.message.rawStopReason)
        assertEquals("Provider stopped with: unmapped_error", unmapped.message.errorMessage)
    }

    @Test
    fun streamSimpleMapsReasoningModes() = withServer { server ->
        repeat(6) { server.enqueue(okResponse(sse(terminalChunk(usage = null), "[DONE]"))) }
        val transport = MockTransport()
        val api = MistralConversationsApi(transport)
        val baseUrl = serverBaseUrl(server)
        val noneHighLevels = mapOf(
            "off" to "none",
            "minimal" to null,
            "low" to null,
            "medium" to null,
            "high" to "high",
            "xhigh" to null,
            "max" to null,
        )

        val promptModeModel = testModel(baseUrl, id = "magistral-medium-latest", reasoning = true)
        runSimple(
            api,
            transport,
            promptModeModel,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key"),
                reasoning = ThinkingLevel.Medium,
            ),
        )
        var body = bodyOf(transport)
        assertEquals("reasoning", body.stringAt("prompt_mode"))
        assertNull(body["reasoning_effort"])

        val effortModel = testModel(baseUrl, id = "mistral-small-2603", reasoning = true, thinkingLevelMap = noneHighLevels)
        runSimple(
            api,
            transport,
            effortModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.Low),
        )
        body = bodyOf(transport)
        assertEquals("high", body.stringAt("reasoning_effort"))
        assertNull(body["prompt_mode"])

        runSimple(
            api,
            transport,
            effortModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key")),
        )
        body = bodyOf(transport)
        assertEquals("none", body.stringAt("reasoning_effort"))

        val glm53Levels = mapOf(
            "off" to null,
            "minimal" to null,
            "low" to "low",
            "medium" to null,
            "high" to "high",
            "xhigh" to null,
            "max" to "max",
        )
        val glmModel = testModel(baseUrl, id = "zai-glm-5-3", reasoning = true, thinkingLevelMap = glm53Levels)
        runSimple(
            api,
            transport,
            glmModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.Medium),
        )
        assertEquals("high", bodyOf(transport).stringAt("reasoning_effort"))
        runSimple(
            api,
            transport,
            glmModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.Max),
        )
        assertEquals("max", bodyOf(transport).stringAt("reasoning_effort"))

        val nonReasoningModel = testModel(baseUrl, id = "mistral-medium-2505", reasoning = false)
        runSimple(
            api,
            transport,
            nonReasoningModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.Medium),
        )
        body = bodyOf(transport)
        assertNull(body["reasoning_effort"])
        assertNull(body["prompt_mode"])
    }

    @Test
    fun promptCacheAndAffinityHeaders() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(usage = null), "[DONE]")))
        server.enqueue(okResponse(sse(terminalChunk(usage = null), "[DONE]")))
        server.enqueue(okResponse(sse(terminalChunk(usage = null), "[DONE]")))
        val transport = MockTransport()
        val api = MistralConversationsApi(transport)
        val model = testModel(serverBaseUrl(server))

        runStream(
            api,
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", sessionId = "session-1"),
        )
        var body = bodyOf(transport)
        assertEquals("session-1", body.stringAt("prompt_cache_key"))
        assertEquals("session-1", transport.lastRequest?.headers?.get("x-affinity"))

        runStream(
            api,
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", sessionId = "session-2", cacheRetention = CacheRetention.None),
        )
        body = bodyOf(transport)
        assertNull(body["prompt_cache_key"])
        assertNull(transport.lastRequest?.headers?.get("x-affinity"))

        runStream(
            api,
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "test-key",
                sessionId = "session-3",
                headers = mapOf("authorization" to null, "x-affinity" to null, "User-Agent" to "custom-agent"),
            ),
        )
        assertNull(transport.lastRequest?.headers?.get("authorization"))
        assertNull(transport.lastRequest?.headers?.get("x-affinity"))
        assertEquals("custom-agent", transport.lastRequest?.headers?.get("User-Agent"))
    }

    @Test
    fun onPayloadUsesCamelCaseAndSerializesWireFormat() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(), "[DONE]")))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        var capturedPayload: JsonObject? = null
        var capturedStatus: Int? = null
        runStream(
            MistralConversationsApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "test-key",
                maxTokens = 123,
                onPayload = { payload, _ ->
                    val objectPayload = payload as JsonObject
                    capturedPayload = objectPayload
                    JsonObject(
                        objectPayload.toMutableMap().apply {
                            put("topP", JsonPrimitive(0.9))
                            put("randomSeed", JsonPrimitive(42))
                            put(
                                "responseFormat",
                                buildJsonObject {
                                    put("type", JsonPrimitive("json_schema"))
                                    put(
                                        "jsonSchema",
                                        buildJsonObject {
                                            put("name", JsonPrimitive("result"))
                                            put(
                                                "schemaDefinition",
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("object"))
                                                    put("properties", buildJsonObject {})
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put("presencePenalty", JsonPrimitive(0.1))
                            put("frequencyPenalty", JsonPrimitive(0.2))
                            put("parallelToolCalls", JsonPrimitive(true))
                            put("safePrompt", JsonPrimitive(true))
                        },
                    )
                },
                onResponse = { response, _ -> capturedStatus = response.status },
            ),
        )

        assertEquals(JsonPrimitive(123), capturedPayload?.get("maxTokens"))
        assertEquals(200, capturedStatus)
        val body = bodyOf(transport)
        assertEquals(JsonPrimitive(123), body["max_tokens"])
        assertEquals(JsonPrimitive(0.9), body["top_p"])
        assertEquals(JsonPrimitive(42), body["random_seed"])
        assertEquals(JsonPrimitive(0.1), body["presence_penalty"])
        assertEquals(JsonPrimitive(0.2), body["frequency_penalty"])
        assertEquals(JsonPrimitive(true), body["parallel_tool_calls"])
        assertEquals(JsonPrimitive(true), body["safe_prompt"])
        val responseFormat = body["response_format"] as JsonObject
        assertEquals("json_schema", responseFormat.stringAt("type"))
        val jsonSchema = responseFormat["json_schema"] as JsonObject
        assertEquals("result", jsonSchema.stringAt("name"))
        assertEquals("object", (jsonSchema["schema"] as JsonObject).stringAt("type"))
        assertNull(body["maxTokens"])
        assertNull(body["responseFormat"])
    }

    @Test
    fun serializesStrictToolSchemas() = withServer { server ->
        server.enqueue(okResponse(sse(terminalChunk(usage = null), "[DONE]")))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val strictTool = Tool(
            name = "inspect_schema",
            description = "Inspect the schema",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("strict", JsonPrimitive("require"))
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "nested",
                            buildJsonObject {
                                put("type", JsonPrimitive("object"))
                                put(
                                    "properties",
                                    buildJsonObject { put("value", buildJsonObject { put("type", JsonPrimitive("string")) }) },
                                )
                            },
                        )
                    },
                )
            },
        )
        runStream(
            MistralConversationsApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(strictTool)),
        )
        val tools = assertNotNull(bodyOf(transport).toolList())
        val function = tools[0]["function"] as JsonObject
        assertEquals(JsonPrimitive(true), function["strict"])
        val parameters = function["parameters"] as JsonObject
        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        assertEquals(JsonArray(listOf(JsonPrimitive("nested"))), parameters["required"])
    }

    @Test
    fun parsesCrlfSseAndUnicodeContent() = withServer { server ->
        val body = "data: ${textChunk("héllo 🌍")}\r\n\r\n" +
            "data: ${terminalChunk(usage = null)}\r\n\r\n" +
            "data: [DONE]\r\n\r\n"
        server.enqueue(okResponse(body))
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        val result = runStream(MistralConversationsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals(TextContent("héllo 🌍"), result.message.content[0])
    }

    @Test
    fun missingApiKeyThrowsForStreamSimple() = withServer { server ->
        val transport = MockTransport()
        val model = testModel(serverBaseUrl(server))
        runBlocking {
            val error = runCatching {
                MistralConversationsApi(transport).streamSimple(
                    model,
                    context(userText("Hi")),
                    SimpleStreamOptions(stream = StreamOptions()),
                )
            }
            assertTrue(error.isFailure)
            assertTrue(error.exceptionOrNull()!!.message!!.contains("No API key for provider: mistral"))
        }
    }
}
