package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.BlockContent
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
import dev.pi.ai.ThinkingBudgets
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
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

class OpenAICompletionsTest {
    private class MockTransport : HttpTransport {
        var lastRequest: HttpRequest? = null
        var requestCount: Int = 0

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            requestCount += 1
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

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { chunk ->
        "data: $chunk\n\n"
    }

    private fun chatChunk(
        delta: String,
        finishReason: String? = null,
        id: String = "chatcmpl-test",
        model: String = "test-model",
    ): String {
        val finish = if (finishReason == null) "null" else "\"$finishReason\""
        return """{"id":"$id","object":"chat.completion.chunk","model":"$model","choices":[{"index":0,"delta":$delta,"finish_reason":$finish}]}"""
    }

    private fun usageChunk(
        prompt: Long = 10,
        completion: Long = 5,
        cached: Long = 0,
        cacheWrite: Long = 0,
        reasoning: Long = 0,
    ): String =
        """{"id":"chatcmpl-test","choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":${prompt + completion},"prompt_tokens_details":{"cached_tokens":$cached,"cache_write_tokens":$cacheWrite},"completion_tokens_details":{"reasoning_tokens":$reasoning}}}"""

    private fun testModel(
        baseUrl: String,
        provider: String = "test-provider",
        id: String = "test-model",
        reasoning: Boolean = false,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        thinkingLevelMap: Map<String, String?>? = null,
        compat: JsonObject? = null,
        maxTokens: Long = 4096,
        contextWindow: Long = 128_000,
    ): Model = Model(
        id = id,
        name = "Test Model",
        api = "openai-completions",
        provider = provider,
        baseUrl = baseUrl,
        input = input,
        cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
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
                    content = PlainTextContent("You are a test assistant."),
                    toolsAdded = tools.takeIf { it.isNotEmpty() },
                    timestamp = 0,
                ),
            ) + messages,
        )

    private fun userText(text: String): UserMessage = UserMessage(PlainTextContent(text), timestamp = 1)

    private fun emptyUsage(): Usage = Usage(
        input = 0,
        output = 0,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = 0,
        cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
    )

    private fun assistantWithToolCall(
        toolCallId: String,
        name: String,
        arguments: JsonObject,
        provider: String = "test-provider",
        modelId: String = "test-model",
    ): AssistantMessage = AssistantMessage(
        content = mutableListOf(ToolCall(id = toolCallId, name = name, arguments = arguments)),
        api = "openai-completions",
        provider = provider,
        model = modelId,
        usage = emptyUsage(),
        stopReason = StopReason.ToolUse,
        timestamp = 2,
    )

    private fun toolResult(
        toolCallId: String,
        name: String,
        content: List<dev.pi.ai.Content>,
    ): ToolResultMessage = ToolResultMessage(
        toolCallId = toolCallId,
        toolName = name,
        content = content,
        isError = false,
        timestamp = 3,
    )

    private fun runStream(
        api: OpenAICompletionsApi,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions = StreamOptions(apiKey = "test-key"),
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val stream = api.stream(model, context, options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events, MockTransport())
        }
    }

    private fun collect(
        api: OpenAICompletionsApi,
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
        api: OpenAICompletionsApi,
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

    private fun JsonObject.toolList(): List<JsonObject>? = (this["tools"] as? JsonArray)?.map { it as JsonObject }

    private fun JsonObject.stringFieldOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    @Test
    fun streamsTextDeltasAndParsesUsage() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        chatChunk("""{"role":"assistant","content":"Hello"}"""),
                        chatChunk("""{"content":" world"}"""),
                        chatChunk("""{}""", "stop"),
                        usageChunk(prompt = 10, completion = 5, cached = 2, cacheWrite = 1, reasoning = 3),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi")),
        )

        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            eventTypes(result.events),
        )
        val message = result.message
        assertEquals(StopReason.Stop, message.stopReason)
        assertEquals("stop", message.rawStopReason)
        assertEquals("chatcmpl-test", message.responseId)
        assertNull(message.responseModel)
        assertEquals(listOf<dev.pi.ai.Content>(TextContent("Hello world")), message.content.toList())
        assertEquals(7L, message.usage.input)
        assertEquals(2L, message.usage.cacheRead)
        assertEquals(1L, message.usage.cacheWrite)
        assertEquals(5L, message.usage.output)
        assertEquals(3L, message.usage.reasoning)
        assertEquals(15L, message.usage.totalTokens)

        val body = bodyOf(transport)
        assertEquals("test-model", body.stringFieldOrNull("model"))
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive(true), (body["stream_options"] as JsonObject)["include_usage"])
        assertEquals(JsonPrimitive(false), body["store"])
        val messages = body.messageList()
        assertEquals("system", messages[0].stringFieldOrNull("role"))
        assertEquals("You are a test assistant.", messages[0].stringFieldOrNull("content"))
        assertEquals("user", messages[1].stringFieldOrNull("role"))
        assertEquals("Hi", messages[1].stringFieldOrNull("content"))
        assertEquals("Bearer test-key", transport.lastRequest?.headers?.get("Authorization"))
        assertEquals("application/json", transport.lastRequest?.headers?.get("Content-Type"))
    }

    @Test
    fun streamsThinkingBeforeTextWithSignature() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk("""{"reasoning_content":"think "}"""),
                        chatChunk("""{"reasoning_content":"hard","content":"answer"}"""),
                        chatChunk("""{}""", "stop"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString(), reasoning = true)
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(
            listOf(
                "start",
                "thinking_start",
                "thinking_delta",
                "thinking_delta",
                "text_start",
                "text_delta",
                "thinking_end",
                "text_end",
                "done",
            ),
            eventTypes(result.events),
        )
        val thinking = result.message.content[0] as ThinkingContent
        assertEquals("think hard", thinking.thinking)
        assertEquals("reasoning_content", thinking.thinkingSignature)
        assertEquals(TextContent("answer"), result.message.content[1])
    }

    @Test
    fun streamsMultipleToolCallsAndParsesArguments() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk(
                            """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"path\":"}}]}""",
                        ),
                        chatChunk(
                            """{"tool_calls":[{"index":0,"function":{"arguments":"\"README.md\"}"}},{"index":1,"id":"call_2","type":"function","function":{"name":"grep","arguments":"{\"pattern\":\"foo\"}"}}]}""",
                        ),
                        chatChunk("""{}""", "tool_calls"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        val types = eventTypes(result.events)
        assertEquals(2, types.count { it == "toolcall_start" })
        assertEquals(2, types.count { it == "toolcall_end" })
        assertEquals("done", types.last())
        val first = result.message.content[0] as ToolCall
        assertEquals("call_1", first.id)
        assertEquals("read", first.name)
        assertEquals("""{"path":"README.md"}""", first.arguments.toString())
        val second = result.message.content[1] as ToolCall
        assertEquals("call_2", second.id)
        assertEquals("grep", second.name)
        assertEquals("""{"pattern":"foo"}""", second.arguments.toString())
        assertEquals(StopReason.ToolUse, result.message.stopReason)
        assertEquals("tool_calls", result.message.rawStopReason)
    }

    @Test
    fun incompleteToolArgumentsBecomeArgumentsError() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk(
                            """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"path\":"}}]}""",
                        ),
                        chatChunk("""{}""", "tool_calls"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.ToolUse, result.message.stopReason)
        val toolCall = result.message.content[0] as ToolCall
        assertNotNull(toolCall.argumentsError)
        assertTrue(toolCall.argumentsError!!.contains("Received arguments:"))
        assertEquals(JsonObject(emptyMap()), toolCall.arguments)
    }

    @Test
    fun streamEndingWithoutFinishReasonIsAnError() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"partial"}"""), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        val types = eventTypes(result.events)
        assertEquals("error", types.last())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Stream ended without finish_reason", result.message.errorMessage)
        assertTrue(types.contains("text_end"))
    }

    @Test
    fun httpErrorBecomesErrorEvent() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"rate limited"}}"""),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(1, transport.requestCount)
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("429"))
        assertTrue(result.message.errorMessage!!.contains("rate limited"))
        assertEquals("error", eventTypes(result.events).last())
    }

    @Test
    fun retriesRetryableResponseUsingRetryAfterMs() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("retry-after-ms", "1")
                .setBody("server exploded"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk("""{"content":"recovered"}"""),
                        chatChunk("""{}""", "stop"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", maxRetries = 1, maxRetryDelayMs = 1_000),
        )

        assertEquals(2, transport.requestCount)
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals(TextContent("recovered"), result.message.content[0])
    }

    @Test
    fun oversizedServerRetryDelayFailsImmediately() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("retry-after", "277403")
                .setBody("rate limited"),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", maxRetries = 2, maxRetryDelayMs = 1_000),
        )

        assertEquals(1, transport.requestCount)
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("Server requested 277403s retry delay (max: 1s)"))
        assertTrue(result.message.errorMessage!!.contains("rate limited"))
    }

    @Test
    fun abortedSignalProducesAbortedErrorEvent() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk("""{"content":"first"}"""),
                        chatChunk("""{"content":"second"}"""),
                        chatChunk("""{}""", "stop"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val signal = RunSignal()
        val options = StreamOptions(
            apiKey = "test-key",
            signal = signal,
            onProviderStreamEvent = { _, _ -> signal.abort() },
        )
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")), options)

        val types = eventTypes(result.events)
        assertEquals("error", types.last())
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
        assertTrue(types.contains("text_delta"))
    }

    @Test
    fun routedResponseModelIsPreserved() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk(
                            """{"content":"ok"}""",
                            model = "anthropic/claude-opus-4.8",
                        ),
                        chatChunk("""{}""", "stop", model = "anthropic/claude-opus-4.8"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString(), provider = "openrouter", id = "openrouter/auto")
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        assertEquals("openrouter/auto", result.message.model)
        assertEquals("anthropic/claude-opus-4.8", result.message.responseModel)
        assertEquals(StopReason.Stop, result.message.stopReason)
    }

    @Test
    fun reasoningDetailsArePreservedAndReplayed() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk(
                            """{"reasoning_details":[{"type":"reasoning.encrypted","id":"call_1","data":"encrypted-signature"}]}""",
                            model = "google/gemini-test",
                        ),
                        chatChunk(
                            """{"reasoning_details":[{"type":"reasoning.text","text":"I should read.","signature":"sha256:x","id":"r1","format":"f","index":0},{"type":"reasoning.summary","summary":"Decided.","id":"r2","index":1}]}""",
                            model = "google/gemini-test",
                        ),
                        chatChunk(
                            """{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read","arguments":"{\"path\":\"x\"}"}}]}""",
                            model = "google/gemini-test",
                        ),
                        chatChunk("""{}""", "tool_calls", model = "google/gemini-test"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val api = OpenAICompletionsApi(transport)
        val model = testModel(server.url("/v1").toString(), provider = "openrouter", id = "google/gemini-test")
        val first = collect(api, transport, model, context(userText("Hi")))

        val thinking = first.message.content[0] as ThinkingContent
        assertEquals("", thinking.thinking)
        val details = PiJson.parseToJsonElement(thinking.thinkingSignature!!) as JsonArray
        assertEquals(3, details.size)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val replayContext = TranscriptContext(
            listOf(
                SystemMessage(PlainTextContent("You are a test assistant."), timestamp = 0),
                userText("Hi"),
                first.message,
                toolResult("call_1", "read", listOf(TextContent("content"))),
            ),
        )
        collect(api, transport, model, replayContext)
        val replayMessages = bodyOf(transport).messageList()
        val assistantMessage = replayMessages.first { it.stringFieldOrNull("role") == "assistant" }
        assertEquals(details, assistantMessage["reasoning_details"])
    }

    @Test
    fun replaysToolCallsAndSynthesizesOrphanResults() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val firstAssistant = assistantWithToolCall("call_1", "read", buildJsonObject { put("path", JsonPrimitive("x")) })
        val secondAssistant = assistantWithToolCall("call_2", "grep", buildJsonObject { put("pattern", JsonPrimitive("y")) })
        val replayContext = TranscriptContext(
            listOf(
                SystemMessage(PlainTextContent("You are a test assistant."), timestamp = 0),
                userText("hi"),
                firstAssistant,
                toolResult("call_1", "read", listOf(TextContent("file body"))),
                secondAssistant,
                userText("again"),
            ),
        )
        collect(OpenAICompletionsApi(transport), transport, model, replayContext)

        val body = bodyOf(transport)
        val messages = body.messageList()
        assertEquals(
            listOf("system", "user", "assistant", "tool", "assistant", "tool", "user"),
            messages.map { it.stringFieldOrNull("role") },
        )
        val assistant = messages[2]
        assertTrue(assistant["content"] is JsonNull)
        val toolCalls = assistant["tool_calls"] as JsonArray
        val function = (toolCalls[0] as JsonObject)["function"] as JsonObject
        assertEquals("read", function.stringFieldOrNull("name"))
        assertEquals("""{"path":"x"}""", function.stringFieldOrNull("arguments"))
        assertEquals(JsonArray(emptyList()), body["tools"])
        val synthesized = messages[5]
        assertEquals("read", "read")
        assertEquals("call_2", synthesized.stringFieldOrNull("tool_call_id"))
        assertEquals("No result provided", synthesized.stringFieldOrNull("content"))
    }

    @Test
    fun batchesToolResultImagesAfterToolResults() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            input = listOf(ModelInputModality.Text, ModelInputModality.Image),
        )
        val assistant = AssistantMessage(
            content = mutableListOf(
                ToolCall(id = "call_1", name = "read", arguments = JsonObject(emptyMap())),
                ToolCall(id = "call_2", name = "read", arguments = JsonObject(emptyMap())),
            ),
            api = "openai-completions",
            provider = "test-provider",
            model = "test-model",
            usage = emptyUsage(),
            stopReason = StopReason.ToolUse,
            timestamp = 2,
        )
        val replayContext = TranscriptContext(
            listOf(
                SystemMessage(PlainTextContent("You are a test assistant."), timestamp = 0),
                userText("look"),
                assistant,
                toolResult(
                    "call_1",
                    "read",
                    listOf(TextContent("text result"), ImageContent(data = "AAA", mimeType = "image/png")),
                ),
                toolResult("call_2", "read", listOf(ImageContent(data = "BBB", mimeType = "image/png"))),
            ),
        )
        collect(OpenAICompletionsApi(transport), transport, model, replayContext)

        val messages = bodyOf(transport).messageList()
        assertEquals(
            listOf("system", "user", "assistant", "tool", "tool", "user"),
            messages.map { it.stringFieldOrNull("role") },
        )
        assertEquals("text result", messages[3].stringFieldOrNull("content"))
        assertEquals("(see attached image)", messages[4].stringFieldOrNull("content"))
        val imageMessage = messages[5]
        val parts = imageMessage["content"] as JsonArray
        assertEquals(3, parts.size)
        assertEquals("Attached image(s) from tool result:", (parts[0] as JsonObject).stringFieldOrNull("text"))
        val firstImageUrl = (((parts[1] as JsonObject)["image_url"]) as JsonObject).stringFieldOrNull("url")
        assertEquals("data:image/png;base64,AAA", firstImageUrl)
    }

    @Test
    fun downgradesImagesForNonVisionModels() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val replayContext = TranscriptContext(
            listOf(
                SystemMessage(PlainTextContent("You are a test assistant."), timestamp = 0),
                UserMessage(
                    BlockContent(listOf(ImageContent(data = "AAA", mimeType = "image/png"))),
                    timestamp = 1,
                ),
                toolResult("call_1", "read", listOf(ImageContent(data = "BBB", mimeType = "image/png"))),
            ),
        )
        collect(OpenAICompletionsApi(transport), transport, model, replayContext)

        val messages = bodyOf(transport).messageList()
        val userMessage = messages[1]
        val parts = userMessage["content"] as JsonArray
        assertEquals(
            "(image omitted: model does not support images)",
            (parts[0] as JsonObject).stringFieldOrNull("text"),
        )
        val resultIndex = messages.indexOfFirst { it.stringFieldOrNull("role") == "tool" }
        assertEquals(
            "(tool image omitted: model does not support images)",
            messages[resultIndex].stringFieldOrNull("content"),
        )
    }

    @Test
    fun streamSimpleMapsReasoningAndToolChoiceForOpenRouter() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            provider = "openrouter",
            id = "anthropic/claude-test",
            reasoning = true,
            thinkingLevelMap = mapOf("medium" to "high"),
        )
        val result = runSimple(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key", temperature = 0.3, maxTokens = 222),
                toolChoice = ToolChoice.Auto,
                reasoning = ThinkingLevel.Medium,
            ),
        )

        assertEquals(StopReason.Stop, result.message.stopReason)
        val body = bodyOf(transport)
        assertEquals("developer", body.messageList()[0].stringFieldOrNull("role"))
        assertEquals(JsonPrimitive(0.3), body["temperature"])
        assertEquals(JsonPrimitive(222), body["max_completion_tokens"])
        assertEquals(JsonPrimitive("auto"), body["tool_choice"])
        assertEquals(JsonPrimitive(false), body["store"])
        assertEquals(JsonPrimitive("high"), (body["reasoning"] as JsonObject)["effort"])
    }

    @Test
    fun thinkingTokenBudgetIsClampedToLeaveAnswerRoom() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            reasoning = true,
            compat = buildJsonObject {
                put("thinkingFormat", JsonPrimitive("zai"))
                put("supportsThinkingTokenBudget", JsonPrimitive(true))
            },
        )
        runSimple(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key", maxTokens = 4096),
                reasoning = ThinkingLevel.High,
                thinkingBudgets = ThinkingBudgets(high = 8192),
            ),
        )

        val body = bodyOf(transport)
        assertEquals(JsonPrimitive(3072), body["thinking_token_budget"])
        val thinking = body["thinking"] as JsonObject
        assertEquals(JsonPrimitive("enabled"), thinking["type"])
        assertEquals(JsonPrimitive(false), thinking["clear_thinking"])
        assertEquals(JsonPrimitive("high"), body["reasoning_effort"])
    }

    @Test
    fun promptCacheAndSessionAffinityHeadersFollowCompat() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val api = OpenAICompletionsApi(transport)
        val model = testModel(server.url("/v1").toString(), provider = "openrouter", id = "openai/gpt-test")
        collect(
            api,
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "test-key",
                sessionId = "x".repeat(80),
                cacheRetention = CacheRetention.Long,
            ),
        )
        val body = bodyOf(transport)
        assertEquals(64, body.stringFieldOrNull("prompt_cache_key")?.length)
        assertEquals(JsonPrimitive("24h"), body["prompt_cache_retention"])
        assertEquals("x".repeat(80), transport.lastRequest?.headers?.get("x-session-id"))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        collect(
            api,
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "test-key",
                sessionId = "session-abc",
                cacheRetention = CacheRetention.None,
            ),
        )
        val secondBody = bodyOf(transport)
        assertNull(secondBody["prompt_cache_key"])
        assertNull(secondBody["prompt_cache_retention"])
        assertNull(transport.lastRequest?.headers?.get("x-session-id"))
    }

    @Test
    fun strictModeConvertsToolSchema() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            compat = buildJsonObject { put("supportsStrictMode", JsonPrimitive(true)) },
        )
        val tool = Tool(
            name = "read",
            description = "Read a file",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("json_schema"))
                put("strict", JsonPrimitive("prefer"))
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject {
                        put("path", buildJsonObject { put("type", JsonPrimitive("string")) })
                        put("limit", buildJsonObject { put("type", JsonPrimitive("integer")) })
                    },
                )
                put("required", JsonArray(listOf(JsonPrimitive("path"))))
            },
        )
        collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
        )

        val body = bodyOf(transport)
        val tools = assertNotNull(body.toolList())
        val function = tools[0]["function"] as JsonObject
        assertEquals(JsonPrimitive(true), function["strict"])
        val parameters = function["parameters"] as JsonObject
        assertEquals(JsonPrimitive(false), parameters["additionalProperties"])
        assertEquals(
            JsonArray(listOf(JsonPrimitive("path"), JsonPrimitive("limit"))),
            parameters["required"],
        )
        val limit = ((parameters["properties"] as JsonObject)["limit"]) as JsonObject
        val anyOf = limit["anyOf"] as JsonArray
        assertEquals(2, anyOf.size)
        assertEquals(JsonPrimitive("null"), (anyOf[1] as JsonObject)["type"])
    }

    @Test
    fun requiresReasoningContentOnAssistantMessagesAddsEmptyField() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            reasoning = true,
            compat = buildJsonObject {
                put("requiresReasoningContentOnAssistantMessages", JsonPrimitive(true))
            },
        )
        val assistant = AssistantMessage(
            content = mutableListOf(TextContent("previous answer")),
            api = "openai-completions",
            provider = "test-provider",
            model = "test-model",
            usage = emptyUsage(),
            stopReason = StopReason.Stop,
            timestamp = 2,
        )
        collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            TranscriptContext(
                listOf(
                    SystemMessage(PlainTextContent("You are a test assistant."), timestamp = 0),
                    userText("hi"),
                    assistant,
                ),
            ),
        )
        val assistantMessage = bodyOf(transport).messageList().first {
            it.stringFieldOrNull("role") == "assistant"
        }
        assertEquals("previous answer", assistantMessage.stringFieldOrNull("content"))
        assertEquals("", assistantMessage.stringFieldOrNull("reasoning_content"))
    }

    @Test
    fun compatFlagsControlRequestFields() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            compat = buildJsonObject {
                put("supportsUsageInStreaming", JsonPrimitive(false))
                put("supportsFinishReason", JsonPrimitive(false))
                put("supportsStore", JsonPrimitive(false))
                put("maxTokensField", JsonPrimitive("max_tokens"))
            },
        )
        val result = collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", maxTokens = 64),
        )

        val body = bodyOf(transport)
        assertNull(body["stream_options"])
        assertNull(body["store"])
        assertEquals(JsonPrimitive(64), body["max_tokens"])
        assertNull(body["max_completion_tokens"])
        assertEquals(StopReason.Stop, result.message.stopReason)
    }

    @Test
    fun detectsDeepseekCompatFromProvider() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val api = OpenAICompletionsApi(transport)
        val model = testModel(
            server.url("/v1").toString(),
            provider = "deepseek",
            id = "deepseek-flash",
            reasoning = true,
        )
        collect(
            api,
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", maxTokens = 128),
        )
        var body = bodyOf(transport)
        assertEquals(JsonPrimitive(128), body["max_tokens"])
        assertEquals(JsonPrimitive("disabled"), (body["thinking"] as JsonObject)["type"])

        runSimple(
            api,
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key"),
                reasoning = ThinkingLevel.High,
            ),
        )
        body = bodyOf(transport)
        assertEquals(JsonPrimitive("enabled"), (body["thinking"] as JsonObject)["type"])
        assertEquals(JsonPrimitive("high"), body["reasoning_effort"])
    }

    @Test
    fun grammarToolsUseCustomToolFormat() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        chatChunk(
                            """{"tool_calls":[{"index":0,"id":"call_1","type":"custom","custom":{"name":"shell","input":"ls"}}]}""",
                        ),
                        chatChunk(
                            """{"tool_calls":[{"index":0,"custom":{"input":" -la"}}]}""",
                        ),
                        chatChunk("""{}""", "tool_calls"),
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            compat = buildJsonObject { put("supportsOpenAIGrammarTools", JsonPrimitive(true)) },
        )
        val tool = Tool(
            name = "shell",
            description = "Run a command",
            constrainedSampling = buildJsonObject {
                put("type", JsonPrimitive("grammar"))
                put(
                    "variants",
                    buildJsonObject { put("openai_lark", JsonPrimitive("start: /.+/")) },
                )
            },
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject { put("command", buildJsonObject { put("type", JsonPrimitive("string")) }) },
                )
                put("required", JsonArray(listOf(JsonPrimitive("command"))))
            },
        )
        val result = collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
        )

        val toolsBody = assertNotNull(bodyOf(transport).toolList())
        assertEquals(JsonPrimitive("custom"), toolsBody[0]["type"])
        val custom = toolsBody[0]["custom"] as JsonObject
        assertEquals("shell", custom.stringFieldOrNull("name"))
        val format = custom["format"] as JsonObject
        assertEquals(JsonPrimitive("grammar"), format["type"])
        val grammar = format["grammar"] as JsonObject
        assertEquals(JsonPrimitive("lark"), grammar["syntax"])
        assertEquals(JsonPrimitive("start: /.+/"), grammar["definition"])

        val deltas = result.events.filterIsInstance<ToolCallDeltaEvent>().joinToString("") { it.delta }
        assertEquals("""{"command":"ls -la"}""", deltas)
        val toolCall = result.message.content[0] as ToolCall
        assertEquals("shell", toolCall.name)
        assertEquals("""{"command":"ls -la"}""", toolCall.arguments.toString())
    }

    @Test
    fun anthropicCacheControlMarksInstructionsToolsAndLastMessage() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            compat = buildJsonObject { put("cacheControlFormat", JsonPrimitive("anthropic")) },
        )
        val tool = Tool(
            name = "read",
            description = "Read a file",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put(
                    "properties",
                    buildJsonObject { put("path", buildJsonObject { put("type", JsonPrimitive("string")) }) },
                )
                put("required", JsonArray(listOf(JsonPrimitive("path"))))
            },
        )
        collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
        )

        val body = bodyOf(transport)
        val messages = body.messageList()
        val systemParts = messages[0]["content"] as JsonArray
        val systemCacheControl = (systemParts[0] as JsonObject)["cache_control"] as JsonObject
        assertEquals(JsonPrimitive("ephemeral"), systemCacheControl["type"])
        assertNull(systemCacheControl["ttl"])
        val toolsBody = assertNotNull(body.toolList())
        val toolCacheControl = toolsBody[0]["cache_control"] as JsonObject
        assertEquals(JsonPrimitive("ephemeral"), toolCacheControl["type"])
        val lastMessage = messages.last()
        val lastParts = lastMessage["content"] as JsonArray
        assertEquals(JsonPrimitive("ephemeral"), (lastParts[0] as JsonObject)["cache_control"]?.let {
            (it as JsonObject)["type"]
        })
    }

    @Test
    fun payloadAndStreamHooksAreInvoked() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        var responseStatus: Int? = null
        val streamedChunks = mutableListOf<dev.pi.ai.JsonValue>()
        val options = StreamOptions(
            apiKey = "test-key",
            onPayload = { payload, _ ->
                val obj = payload as JsonObject
                JsonObject(obj.toMutableMap().apply { put("temperature", JsonPrimitive(0.9)) })
            },
            onResponse = { response, _ -> responseStatus = response.status },
            onProviderStreamEvent = { chunk, _ -> streamedChunks.add(chunk) },
        )
        collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")), options)

        assertEquals(JsonPrimitive(0.9), bodyOf(transport)["temperature"])
        assertEquals(200, responseStatus)
        assertEquals(2, streamedChunks.size)
    }

    @Test
    fun missingApiKeyForStreamProducesErrorEvent() = withServer { server ->
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = runBlocking {
            withTimeout(15_000) {
                val stream = OpenAICompletionsApi(transport).stream(
                    model,
                    context(userText("Hi")),
                    StreamOptions(),
                )
                val message = stream.result()
                StreamResult(message, stream.events.toList(), transport)
            }
        }
        assertEquals("error", eventTypes(result.events).last())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(result.message.errorMessage!!.contains("No API key for provider: test-provider"))
    }

    @Test
    fun mapsLengthAndContentFilterStopReasons() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"truncated"}"""), chatChunk("""{}""", "length"), "[DONE]")),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"blocked"}"""), chatChunk("""{}""", "content_filter"), "[DONE]")),
        )
        val transport = MockTransport()
        val api = OpenAICompletionsApi(transport)
        val model = testModel(server.url("/v1").toString())

        val length = collect(api, transport, model, context(userText("Hi")))
        assertEquals(StopReason.Length, length.message.stopReason)
        assertEquals("length", length.message.rawStopReason)
        assertEquals("done", eventTypes(length.events).last())

        val filtered = collect(api, transport, model, context(userText("Hi")))
        assertEquals(StopReason.Error, filtered.message.stopReason)
        assertEquals("content_filter", filtered.message.rawStopReason)
        assertEquals("Provider finish_reason: content_filter", filtered.message.errorMessage)
        assertEquals("error", eventTypes(filtered.events).last())
    }

    @Test
    fun parsesUsageFromChoiceFallback() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    sse(
                        """{"id":"chatcmpl-test","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop","usage":{"prompt_tokens":7,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":1},"completion_tokens_details":{"reasoning_tokens":2}}}]}""",
                        "[DONE]",
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        val result = collect(OpenAICompletionsApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals(6L, result.message.usage.input)
        assertEquals(3L, result.message.usage.output)
        assertEquals(1L, result.message.usage.cacheRead)
        assertEquals(2L, result.message.usage.reasoning)
        assertEquals(10L, result.message.usage.totalTokens)
    }

    @Test
    fun midConversationSystemMessagesCanDeclareTools() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            compat = buildJsonObject {
                put("supportsMidConvoSystemMessages", JsonPrimitive(true))
                put("supportsMidConvoToolAdditions", JsonPrimitive(true))
            },
        )
        val toolA = Tool(
            name = "read",
            description = "Read",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject { put("path", buildJsonObject { put("type", JsonPrimitive("string")) }) })
            },
        )
        val toolB = Tool(
            name = "write",
            description = "Write",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject { put("path", buildJsonObject { put("type", JsonPrimitive("string")) }) })
            },
        )
        val replayContext = TranscriptContext(
            listOf(
                SystemMessage(
                    content = PlainTextContent("base prompt"),
                    toolsAdded = listOf(toolA),
                    timestamp = 0,
                ),
                userText("question"),
                SystemMessage(
                    content = PlainTextContent("update"),
                    toolsAdded = listOf(toolB),
                    timestamp = 5,
                ),
            ),
        )
        collect(OpenAICompletionsApi(transport), transport, model, replayContext)

        val body = bodyOf(transport)
        val tools = assertNotNull(body.toolList())
        assertEquals(1, tools.size)
        assertEquals("read", (tools[0]["function"] as JsonObject).stringFieldOrNull("name"))
        val messages = body.messageList()
        assertEquals(
            listOf("system", "user", "system", "system"),
            messages.map { it.stringFieldOrNull("role") },
        )
        val declaredTools = messages[2]["tools"] as JsonArray
        assertEquals("write", (((declaredTools[0] as JsonObject)["function"]) as JsonObject).stringFieldOrNull("name"))
        assertEquals("update", messages[3].stringFieldOrNull("content"))
    }

    @Test
    fun requiresThinkingAsTextReplayUsesTextParts() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse(chatChunk("""{"content":"ok"}"""), chatChunk("""{}""", "stop"), "[DONE]")),
        )
        val transport = MockTransport()
        val model = testModel(
            server.url("/v1").toString(),
            reasoning = true,
            compat = buildJsonObject { put("requiresThinkingAsText", JsonPrimitive(true)) },
        )
        val assistant = AssistantMessage(
            content = mutableListOf(ThinkingContent(thinking = "internal"), TextContent("visible")),
            api = "openai-completions",
            provider = "test-provider",
            model = "test-model",
            usage = emptyUsage(),
            stopReason = StopReason.Stop,
            timestamp = 2,
        )
        collect(
            OpenAICompletionsApi(transport),
            transport,
            model,
            TranscriptContext(
                listOf(
                    SystemMessage(PlainTextContent("You are a test assistant."), timestamp = 0),
                    userText("hi"),
                    assistant,
                ),
            ),
        )
        val assistantMessage = bodyOf(transport).messageList().first {
            it.stringFieldOrNull("role") == "assistant"
        }
        val parts = assistantMessage["content"] as JsonArray
        assertEquals(2, parts.size)
        assertEquals("internal", (parts[0] as JsonObject).stringFieldOrNull("text"))
        assertEquals("visible", (parts[1] as JsonObject).stringFieldOrNull("text"))
    }

    @Test
    fun missingApiKeyThrowsForStreamSimple() = withServer { server ->
        val transport = MockTransport()
        val model = testModel(server.url("/v1").toString())
        runBlocking {
            val error = runCatching {
                OpenAICompletionsApi(transport).streamSimple(
                    model,
                    context(userText("Hi")),
                    SimpleStreamOptions(stream = StreamOptions()),
                )
            }
            assertTrue(error.isFailure)
            assertTrue(error.exceptionOrNull()!!.message!!.contains("No API key for provider: test-provider"))
        }
    }
}
