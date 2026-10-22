package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
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
import dev.pi.ai.UserMessage
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleGenerativeAITest {
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

    private fun sse(vararg chunks: String): String = chunks.joinToString("") { chunk -> "data: $chunk\n\n" }

    private fun chunk(
        parts: String,
        finishReason: String? = null,
        usage: JsonObject? = null,
        responseId: String = "resp-google-1",
    ): String = buildJsonObject {
        put("responseId", JsonPrimitive(responseId))
        put(
            "candidates",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonObject {
                                put("role", JsonPrimitive("model"))
                                put("parts", PiJson.parseToJsonElement(parts))
                            },
                        )
                        if (finishReason != null) put("finishReason", JsonPrimitive(finishReason))
                    },
                ),
            ),
        )
        if (usage != null) put("usageMetadata", usage)
    }.toString()

    private fun stopChunk(
        usage: JsonObject? = null,
        finishReason: String = "STOP",
    ): String = buildJsonObject {
        put(
            "candidates",
            JsonArray(
                listOf(
                    buildJsonObject { put("finishReason", JsonPrimitive(finishReason)) },
                ),
            ),
        )
        if (usage != null) put("usageMetadata", usage)
    }.toString()

    private fun usageJson(
        prompt: Long,
        candidates: Long,
        cached: Long = 0,
        thoughts: Long = 0,
        total: Long = prompt + candidates,
    ): JsonObject = buildJsonObject {
        put("promptTokenCount", JsonPrimitive(prompt))
        put("candidatesTokenCount", JsonPrimitive(candidates))
        if (cached > 0) put("cachedContentTokenCount", JsonPrimitive(cached))
        if (thoughts > 0) put("thoughtsTokenCount", JsonPrimitive(thoughts))
        put("totalTokenCount", JsonPrimitive(total))
    }

    private fun testModel(
        baseUrl: String,
        id: String = "gemini-test-model",
        reasoning: Boolean = false,
        thinkingLevelMap: Map<String, String?>? = null,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        cost: ModelCost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    ): Model = Model(
        id = id,
        name = "Test Model",
        api = "google-generative-ai",
        provider = "test-provider",
        baseUrl = baseUrl,
        input = input,
        cost = cost,
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = 1_000_000,
        maxTokens = 65_536,
    )

    private fun context(
        vararg messages: Message,
        systemPrompt: String? = "You are a test assistant.",
        tools: List<Tool> = emptyList(),
    ): TranscriptContext = TranscriptContext(
        listOfNotNull(
            systemPrompt?.let {
                SystemMessage(
                    content = PlainTextContent(it),
                    toolsAdded = tools.takeIf { list -> list.isNotEmpty() },
                    timestamp = 0,
                )
            },
        ) + messages,
    )

    private fun userText(text: String): UserMessage = UserMessage(PlainTextContent(text), timestamp = 1)

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

    private fun runStream(
        api: ApiProvider,
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
        api: ApiProvider,
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

    private fun bodyOf(transport: MockTransport): JsonObject {
        val body = assertNotNull(transport.lastRequest?.body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    private fun JsonObject.parts(): JsonArray = (this["parts"] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.contents(): List<JsonObject> = (this["contents"] as JsonArray).map { it as JsonObject }

    private fun JsonObject.thinkingConfig(): JsonObject? =
        (this["generationConfig"] as? JsonObject)?.get("thinkingConfig") as? JsonObject

    @Test
    fun streamsTextDeltasAndMapsUsageAndCost() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        chunk("""[{"text":"Hello"}]""", usage = usageJson(10, 5, cached = 2, thoughts = 3, total = 15)),
                        chunk("""[{"text":" world"}]"""),
                        stopChunk(usage = usageJson(10, 5, cached = 2, thoughts = 3, total = 15)),
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(
            baseUrl = server.url("/v1beta").toString(),
            cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.0),
        )
        val result = runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", temperature = 0.5, maxTokens = 1234),
        )

        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            eventTypes(result.events),
        )
        val message = result.message
        assertEquals(StopReason.Stop, message.stopReason)
        assertEquals("STOP", message.rawStopReason)
        assertEquals("resp-google-1", message.responseId)
        assertEquals(listOf<dev.pi.ai.Content>(TextContent("Hello world")), message.content.toList())
        assertEquals(8L, message.usage.input)
        assertEquals(8L, message.usage.output)
        assertEquals(2L, message.usage.cacheRead)
        assertEquals(3L, message.usage.reasoning)
        assertEquals(15L, message.usage.totalTokens)
        assertEquals(8e-6, message.usage.cost.input, 1e-12)
        assertEquals(16e-6, message.usage.cost.output, 1e-12)
        assertEquals(1e-6, message.usage.cost.cacheRead, 1e-12)
        assertEquals(25e-6, message.usage.cost.total, 1e-12)

        val request = assertNotNull(transport.lastRequest)
        assertEquals("/v1beta/models/gemini-test-model:streamGenerateContent?alt=sse", URL(request.url).path + "?" + URL(request.url).query)
        assertEquals("test-key", request.headers.entries.first { it.key.equals("x-goog-api-key", true) }.value)
        assertEquals("pi", request.headers.entries.first { it.key.equals("User-Agent", true) }.value)
        val body = bodyOf(transport)
        val generationConfig = assertIs<JsonObject>(body["generationConfig"])
        assertEquals(JsonPrimitive(0.5), generationConfig["temperature"])
        assertEquals(JsonPrimitive(1234), generationConfig["maxOutputTokens"])
        assertEquals("You are a test assistant.", systemInstructionText(body))
    }

    private fun systemInstructionText(body: JsonObject): String? {
        val systemInstruction = body["systemInstruction"] as? JsonObject ?: return null
        val parts = systemInstruction.parts()
        return ((parts.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.content
    }

    @Test
    fun streamsThinkingPartsAndRetainsThoughtSignature() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        chunk("""[{"text":"think","thought":true,"thoughtSignature":"SIG-1"}]"""),
                        chunk("""[{"text":"ing","thought":true}]"""),
                        chunk("""[{"text":"answer"}]"""),
                        stopChunk(),
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString(), reasoning = true)
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

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
        val thinking = assertIs<ThinkingContent>(result.message.content[0])
        assertEquals("thinking", thinking.thinking)
        assertEquals("SIG-1", thinking.thinkingSignature)
        val text = assertIs<TextContent>(result.message.content[1])
        assertEquals("answer", text.text)
    }

    @Test
    fun thoughtSignatureAloneDoesNotCreateThinkingBlock() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"hi","thoughtSignature":"SIG-1"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals(listOf("start", "text_start", "text_delta", "text_end", "done"), eventTypes(result.events))
        val text = assertIs<TextContent>(result.message.content.single())
        assertEquals("hi", text.text)
        assertEquals("SIG-1", text.textSignature)
    }

    @Test
    fun emitsToolCallEventsAndMapsStopToToolUse() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        chunk(
                            """[{"functionCall":{"id":"call-1","name":"echo","args":{"value":"x"}},"thoughtSignature":"SIG-2"}]""",
                        ),
                        stopChunk(),
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals(
            listOf("start", "toolcall_start", "toolcall_delta", "toolcall_end", "done"),
            eventTypes(result.events),
        )
        val toolCall = assertIs<ToolCall>(result.message.content.single())
        assertEquals("call-1", toolCall.id)
        assertEquals("echo", toolCall.name)
        assertEquals("x", (toolCall.arguments["value"] as JsonPrimitive).content)
        assertEquals("SIG-2", toolCall.thoughtSignature)
        assertEquals(StopReason.ToolUse, result.message.stopReason)
        val delta = assertIs<ToolCallDeltaEvent>(result.events[2])
        assertEquals("""{"value":"x"}""", delta.delta)
    }

    @Test
    fun generatesToolCallIdsWhenMissingOrDuplicated() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        chunk(
                            """
                            [
                              {"functionCall":{"name":"echo","args":{"n":1}}},
                              {"functionCall":{"name":"echo","args":{"n":2}}},
                              {"functionCall":{"id":"dup","name":"echo","args":{"n":3}}},
                              {"functionCall":{"id":"dup","name":"echo","args":{"n":4}}}
                            ]
                            """.trimIndent(),
                        ),
                        stopChunk(),
                    ),
                ),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        val toolCalls = result.message.content.filterIsInstance<ToolCall>()
        assertEquals(4, toolCalls.size)
        assertEquals(4, toolCalls.map { it.id }.toSet().size)
        assertTrue(toolCalls[0].id.startsWith("echo_"))
        assertTrue(toolCalls[1].id.startsWith("echo_"))
        assertEquals("dup", toolCalls[2].id)
        assertTrue(toolCalls[3].id.startsWith("echo_"))
    }

    @Test
    fun mapsProviderErrorFinishReasonsToErrorEvents() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"partial"}]"""), stopChunk(finishReason = "SAFETY"))),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals("error", eventTypes(result.events).last())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Provider stopped with: SAFETY", result.message.errorMessage)
        assertEquals("SAFETY", result.message.rawStopReason)
    }

    @Test
    fun reportsUnknownFinishReasonInError() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(stopChunk(finishReason = "SOMETHING_NEW"))),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Unhandled stop reason: SOMETHING_NEW", result.message.errorMessage)
    }

    @Test
    fun reportsStreamWithoutFinishReason() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"hi"}]"""))),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Google stream ended without a finish reason", result.message.errorMessage)
    }

    @Test
    fun formatsHttpErrors() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":{"message":"bad request"}}"""),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("""400: {"error":{"message":"bad request"}}""", result.message.errorMessage)
    }

    @Test
    fun retriesRetryableStatusesHonoringRetryAfter() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("retry-after-ms", "1")
                .setBody("""{"error":{"message":"slow down"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", maxRetries = 1),
        )

        assertEquals(2, transport.requestCount)
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("ok", (result.message.content.single() as TextContent).text)
    }

    @Test
    fun doesNotRetryWithoutRetriesConfigured() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(GoogleGenerativeAIApi(transport), transport, model, context(userText("Hi")))

        assertEquals(1, transport.requestCount)
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("500: boom", result.message.errorMessage)
    }

    @Test
    fun reportsAbortedRequests() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val signal = RunSignal()
        signal.abort()
        val result = runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", signal = signal),
        )

        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
        assertEquals(listOf("error"), eventTypes(result.events))
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun reportsMissingApiKey() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val result = runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(),
        )
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("No API key for provider: test-provider", result.message.errorMessage)

        runBlocking {
            assertFailsWith<IllegalStateException> {
                GoogleGenerativeAIApi(transport).streamSimple(model, context(userText("Hi")), SimpleStreamOptions())
            }
        }
    }

    @Test
    fun streamSimpleUsesTokenBudgetForBudgetModels() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString(), id = "gemini-2.5-flash", reasoning = true)
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key"),
                reasoning = ThinkingLevel.Minimal,
                thinkingBudgets = ThinkingBudgets(high = 4321),
            ),
        )
        val thinkingConfig = assertNotNull(bodyOf(transport).thinkingConfig())
        assertEquals(JsonPrimitive(true), thinkingConfig["includeThoughts"])
        assertEquals(JsonPrimitive(128), thinkingConfig["thinkingBudget"])
    }

    @Test
    fun streamSimpleUsesThinkingLevelForGemini3Models() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(
            baseUrl = server.url("/v1beta").toString(),
            id = "gemini-3-flash-preview",
            reasoning = true,
            thinkingLevelMap = linkedMapOf(
                "off" to null,
                "minimal" to "minimal",
                "low" to "low",
                "medium" to "medium",
                "high" to "high",
                "xhigh" to null,
                "max" to null,
            ),
        )
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.Medium),
        )
        val thinkingConfig = assertNotNull(bodyOf(transport).thinkingConfig())
        assertEquals(JsonPrimitive(true), thinkingConfig["includeThoughts"])
        assertEquals(JsonPrimitive("MEDIUM"), thinkingConfig["thinkingLevel"])
    }

    @Test
    fun streamSimpleDisablesThinkingWhenReasoningOmitted() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val budgetModel = testModel(server.url("/v1beta").toString(), id = "gemini-2.5-flash", reasoning = true)
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            budgetModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key")),
        )
        assertEquals(JsonPrimitive(0), assertNotNull(bodyOf(transport).thinkingConfig())["thinkingBudget"])

        val levelModel = testModel(
            baseUrl = server.url("/v1beta").toString(),
            id = "gemini-3.8-flash",
            reasoning = true,
            thinkingLevelMap = linkedMapOf(
                "off" to null,
                "minimal" to null,
                "low" to "low",
                "medium" to "medium",
                "high" to "high",
                "xhigh" to null,
                "max" to null,
            ),
        )
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            levelModel,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key")),
        )
        val thinkingConfig = assertNotNull(bodyOf(transport).thinkingConfig())
        assertNull(thinkingConfig["includeThoughts"])
        assertEquals(JsonPrimitive("LOW"), thinkingConfig["thinkingLevel"])
    }

    @Test
    fun buildsToolDeclarationsAndFunctionCallingMode() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val parameters = PiJson.parseToJsonElement(
            """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""",
        ) as JsonObject
        val tool = Tool(name = "echo", description = "Echo tool", parameters = parameters)
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), toolChoice = ToolChoice.None),
        )
        val body = bodyOf(transport)
        val tools = body["tools"] as JsonArray
        val declarations = ((tools.first() as JsonObject)["functionDeclarations"] as JsonArray)
        val declaration = declarations.first() as JsonObject
        assertEquals("echo", (declaration["name"] as JsonPrimitive).content)
        assertEquals("Echo tool", (declaration["description"] as JsonPrimitive).content)
        assertEquals(parameters, declaration["parametersJsonSchema"])
        assertTrue(!declaration.containsKey("parameters"))
        val toolConfig = body["toolConfig"] as JsonObject
        val mode = ((toolConfig["functionCallingConfig"] as JsonObject)["mode"] as JsonPrimitive).content
        assertEquals("NONE", mode)
    }

    @Test
    fun convertsStrictSchemasAndUsesValidatedMode() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString(), id = "gemini-3-flash-preview", reasoning = true)
        val parameters = PiJson.parseToJsonElement(
            """{"type":"object","properties":{"optional":{"type":"string"},"required":{"type":"number"}},"required":["required"]}""",
        ) as JsonObject
        val tool = Tool(
            name = "strict_echo",
            description = "Strict echo",
            parameters = parameters,
            constrainedSampling = PiJson.parseToJsonElement("""{"type":"json_schema","strict":"require"}"""),
        )
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi"), tools = listOf(tool)),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key")),
        )
        val body = bodyOf(transport)
        val declarations = (((body["tools"] as JsonArray).first() as JsonObject)["functionDeclarations"] as JsonArray)
        val schema = (declarations.first() as JsonObject)["parametersJsonSchema"] as JsonObject
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        val required = schema["required"] as JsonArray
        assertEquals(2, required.size)
        val properties = schema["properties"] as JsonObject
        val optional = properties["optional"] as JsonObject
        assertTrue(optional.containsKey("anyOf"))
        val toolConfig = body["toolConfig"] as JsonObject
        val mode = ((toolConfig["functionCallingConfig"] as JsonObject)["mode"] as JsonPrimitive).content
        assertEquals("VALIDATED", mode)
    }

    @Test
    fun collapsesSystemMessagesIntoSystemInstruction() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString())
        val messages = listOf(
            SystemMessage(content = PlainTextContent("base"), timestamp = 0),
            userText("hi"),
            SystemMessage(content = PlainTextContent("later"), timestamp = 2),
        )
        val result = runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            TranscriptContext(messages),
            StreamOptions(apiKey = "test-key"),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val body = bodyOf(transport)
        assertEquals("base\n\nlater", systemInstructionText(body))
        val contents = body.contents()
        assertEquals(listOf("user"), contents.map { (it["role"] as JsonPrimitive).content })
        assertEquals("hi", ((contents.first().parts().first() as JsonObject)["text"] as JsonPrimitive).content)
    }

    @Test
    fun streamSimpleHonorsUppercaseProviderThinkingMappings() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(
            baseUrl = server.url("/v1beta").toString(),
            id = "gemini-3.7-flash",
            reasoning = true,
            thinkingLevelMap = linkedMapOf("high" to "LOW"),
        )
        runSimple(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.High),
        )
        val thinkingConfig = assertNotNull(bodyOf(transport).thinkingConfig())
        assertEquals(JsonPrimitive(true), thinkingConfig["includeThoughts"])
        assertEquals(JsonPrimitive("LOW"), thinkingConfig["thinkingLevel"])
    }

    @Test
    fun streamSimpleReportsUnsupportedThinkingLevelMappings() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(
            baseUrl = server.url("/v1beta").toString(),
            id = "gemini-3.7-flash",
            reasoning = true,
            thinkingLevelMap = linkedMapOf("xhigh" to "extreme"),
        )
        runBlocking {
            val error = assertFailsWith<GoogleStreamException> {
                GoogleGenerativeAIApi(transport).streamSimple(
                    model,
                    context(userText("Hi")),
                    SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key"), reasoning = ThinkingLevel.Xhigh),
                )
            }
            assertEquals(
                "Unsupported Google thinking level mapping for test-provider/gemini-3.7-flash: xhigh -> extreme",
                error.message,
            )
        }
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun keepsSignedEmptyBlocksAndDropsUnsignedOnesOnReplay() {
        val model = testModel("https://example.invalid/v1beta", id = "gemini-3-pro-preview", reasoning = true)
        val assistant = AssistantMessage(
            content = mutableListOf(
                ThinkingContent(thinking = "", thinkingSignature = "AAAAAAAAAAAAAAAAAAAAAA=="),
                TextContent(text = "   "),
                ToolCall(id = "call_1", name = "bash", arguments = JsonObject(mapOf("command" to JsonPrimitive("ls")))),
            ),
            api = "google-generative-ai",
            provider = "test-provider",
            model = "gemini-3-pro-preview",
            usage = Usage.empty(),
            stopReason = StopReason.ToolUse,
            timestamp = 2,
        )
        val contents = convertGoogleMessages(model, TranscriptContext(listOf(userText("hi"), assistant)))
        val modelTurn = contents.first { (it as JsonObject)["role"] == JsonPrimitive("model") } as JsonObject
        val parts = modelTurn["parts"] as JsonArray
        assertEquals(2, parts.size)
        val thought = parts[0] as JsonObject
        assertEquals(JsonPrimitive(true), thought["thought"])
        assertEquals(JsonPrimitive("AAAAAAAAAAAAAAAAAAAAAA=="), thought["thoughtSignature"])
        assertTrue((parts[1] as JsonObject).containsKey("functionCall"))
    }

    @Test
    fun includesToolCallIdsOnlyForGemini3Models() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        fun history(modelId: String): TranscriptContext = TranscriptContext(
            listOf(
                SystemMessage(content = PlainTextContent("sys"), timestamp = 0),
                userText("hi"),
                AssistantMessage(
                    content = mutableListOf(
                        ToolCall(id = "call_1", name = "read", arguments = JsonObject(emptyMap())),
                    ),
                    api = "google-generative-ai",
                    provider = "test-provider",
                    model = modelId,
                    usage = Usage.empty(),
                    stopReason = StopReason.ToolUse,
                    timestamp = 2,
                ),
                ToolResultMessage(
                    toolCallId = "call_1",
                    toolName = "read",
                    content = listOf(TextContent("ok")),
                    isError = false,
                    timestamp = 3,
                ),
            ),
        )

        val gemini3Model = testModel(server.url("/v1beta").toString(), id = "gemini-3-flash-preview", reasoning = true)
        runStream(GoogleGenerativeAIApi(transport), transport, gemini3Model, history("gemini-3-flash-preview"))
        val gemini3Contents = bodyOf(transport).contents()
        val gemini3FunctionCall = gemini3Contents
            .filter { (it["role"] as JsonPrimitive).content == "model" }
            .flatMap { it.parts() }
            .map { it as JsonObject }
            .first { it.containsKey("functionCall") }
        assertEquals(JsonPrimitive("call_1"), ((gemini3FunctionCall["functionCall"] as JsonObject)["id"]))
        val gemini3FunctionResponse = gemini3Contents
            .flatMap { it.parts() }
            .map { it as JsonObject }
            .first { it.containsKey("functionResponse") }
        assertEquals(JsonPrimitive("call_1"), ((gemini3FunctionResponse["functionResponse"] as JsonObject)["id"]))

        val gemini25Model = testModel(server.url("/v1beta").toString(), id = "gemini-2.5-flash", reasoning = true)
        runStream(GoogleGenerativeAIApi(transport), transport, gemini25Model, history("gemini-2.5-flash"))
        val gemini25Contents = bodyOf(transport).contents()
        val gemini25FunctionCall = gemini25Contents
            .filter { (it["role"] as JsonPrimitive).content == "model" }
            .flatMap { it.parts() }
            .map { it as JsonObject }
            .first { it.containsKey("functionCall") }
        assertNull((gemini25FunctionCall["functionCall"] as JsonObject)["id"])
        val gemini25FunctionResponse = gemini25Contents
            .flatMap { it.parts() }
            .map { it as JsonObject }
            .first { it.containsKey("functionResponse") }
        assertNull((gemini25FunctionResponse["functionResponse"] as JsonObject)["id"])
    }

    @Test
    fun mergesConsecutiveToolResultsIntoOneUserTurn() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString(), id = "gemini-3-flash-preview", reasoning = true)
        val assistant = AssistantMessage(
            content = mutableListOf(
                ToolCall(id = "call_1", name = "read", arguments = JsonObject(emptyMap())),
                ToolCall(id = "call_2", name = "read", arguments = JsonObject(emptyMap())),
            ),
            api = "google-generative-ai",
            provider = "test-provider",
            model = "gemini-3-flash-preview",
            usage = Usage.empty(),
            stopReason = StopReason.ToolUse,
            timestamp = 2,
        )
        val messages = listOf(
            SystemMessage(content = PlainTextContent("sys"), timestamp = 0),
            userText("hi"),
            assistant,
            ToolResultMessage(
                toolCallId = "call_1",
                toolName = "read",
                content = listOf(TextContent("one")),
                isError = false,
                timestamp = 3,
            ),
            ToolResultMessage(
                toolCallId = "call_2",
                toolName = "read",
                content = listOf(TextContent("two")),
                isError = false,
                timestamp = 4,
            ),
        )
        runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            TranscriptContext(messages),
            StreamOptions(apiKey = "test-key"),
        )
        val functionTurns = bodyOf(transport).contents().filter { content ->
            content.parts().any { part -> (part as? JsonObject)?.containsKey("functionResponse") == true }
        }
        assertEquals(1, functionTurns.size)
        assertEquals(2, functionTurns.single().parts().size)
    }

    @Test
    fun omitsImagesForTextOnlyModelsInToolResults() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(chunk("""[{"text":"ok"}]"""), stopChunk())),
        )
        val transport = MockTransport()
        val model = testModel(server.url("/v1beta").toString(), id = "gemini-2.5-flash")
        val toolCall = dev.pi.ai.AssistantMessage(
            content = mutableListOf(ToolCall(id = "call_1", name = "read", arguments = JsonObject(emptyMap()))),
            api = "google-generative-ai",
            provider = "test-provider",
            model = "gemini-2.5-flash",
            usage = Usage.empty(),
            stopReason = StopReason.ToolUse,
            timestamp = 2,
        )
        val toolResult = ToolResultMessage(
            toolCallId = "call_1",
            toolName = "read",
            content = listOf(dev.pi.ai.ImageContent(data = "abc", mimeType = "image/png")),
            isError = false,
            timestamp = 3,
        )
        val result = runStream(
            GoogleGenerativeAIApi(transport),
            transport,
            model,
            context(userText("hi"), toolCall, toolResult),
            StreamOptions(apiKey = "test-key"),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val contents = bodyOf(transport).contents()
        val functionResponse = contents
            .flatMap { it.parts() }
            .mapNotNull { (it as? JsonObject)?.get("functionResponse") as? JsonObject }
            .firstOrNull()
        val response = (functionResponse!!["response"] as JsonObject)
        assertEquals(
            "(tool image omitted: model does not support images)",
            ((response["output"]) as JsonPrimitive).content,
        )
        assertNull(functionResponse["parts"])
    }
}
