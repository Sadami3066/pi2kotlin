package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
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
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleVertexTest {
    private class ResponseSpec(val status: Int, val headers: Map<String, String>, val body: String)

    private class CapturingTransport : HttpTransport {
        var lastRequest: HttpRequest? = null
        var requestCount: Int = 0
        private val queue = ArrayDeque<ResponseSpec>()

        fun enqueue(
            status: Int = 200,
            headers: Map<String, String> = mapOf("Content-Type" to "text/event-stream"),
            body: String,
        ) {
            queue.addLast(ResponseSpec(status, headers, body))
        }

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            requestCount += 1
            val spec = if (queue.isEmpty()) {
                ResponseSpec(200, mapOf("Content-Type" to "text/event-stream"), "")
            } else {
                queue.removeFirst()
            }
            return HttpResponse(spec.status, spec.headers, Buffer().write(spec.body.encodeToByteArray()))
        }
    }

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
        responseId: String = "resp-vertex-1",
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

    private fun stopChunk(usage: JsonObject? = null, finishReason: String = "STOP"): String = buildJsonObject {
        put(
            "candidates",
            JsonArray(listOf(buildJsonObject { put("finishReason", JsonPrimitive(finishReason)) })),
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

    private fun vertexModel(
        baseUrl: String = "https://{location}-aiplatform.googleapis.com",
        id: String = "gemini-3-flash-preview",
        reasoning: Boolean = true,
        thinkingLevelMap: Map<String, String?>? = null,
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        cost: ModelCost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    ): Model = Model(
        id = id,
        name = "Vertex Test Model",
        api = "google-vertex",
        provider = "google-vertex",
        baseUrl = baseUrl,
        input = input,
        cost = cost,
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = 1_000_000,
        maxTokens = 65_536,
    )

    private fun context(
        vararg messages: dev.pi.ai.Message,
        systemPrompt: String? = "You are a test assistant.",
    ): TranscriptContext = TranscriptContext(
        listOfNotNull(
            systemPrompt?.let {
                SystemMessage(content = PlainTextContent(it), timestamp = 0)
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
        transport: HttpTransport,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val stream = api.stream(model, context, options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events)
        }
    }

    private fun runSimple(
        api: ApiProvider,
        transport: HttpTransport,
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val stream = api.streamSimple(model, context, options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events)
        }
    }

    private fun bodyOf(transport: HttpTransport): JsonObject {
        val request = when (transport) {
            is CapturingTransport -> transport.lastRequest
            is MockTransport -> transport.lastRequest
            else -> null
        }
        val body = assertNotNull(request?.body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    private fun headerOf(transport: HttpTransport, name: String): String? {
        val request = when (transport) {
            is CapturingTransport -> transport.lastRequest
            is MockTransport -> transport.lastRequest
            else -> null
        } ?: return null
        return request.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    @Test
    fun bearerTokenUsesRegionalProjectEndpoint() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"hello"}]"""), stopChunk()))
        val model = vertexModel()
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "ya29.access-token",
                env = mapOf(
                    "GOOGLE_CLOUD_PROJECT" to "my-project",
                    "GOOGLE_CLOUD_LOCATION" to "us-central1",
                ),
            ),
        )

        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("hello", (result.message.content.single() as TextContent).text)
        assertEquals(
            "https://us-central1-aiplatform.googleapis.com/v1/projects/my-project/locations/us-central1/publishers/google/models/gemini-3-flash-preview:streamGenerateContent?alt=sse",
            transport.lastRequest?.url,
        )
        assertEquals("Bearer ya29.access-token", headerOf(transport, "Authorization"))
        assertNull(headerOf(transport, "x-goog-api-key"))
        assertEquals("pi", headerOf(transport, "User-Agent"))
        assertNotNull(headerOf(transport, "Content-Type"))
        assertEquals("You are a test assistant.", systemInstructionText(bodyOf(transport)))
    }

    @Test
    fun bearerTokenUsesGlobalEndpointForGlobalLocation() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"hello"}]"""), stopChunk()))
        runStream(
            GoogleVertexApi(transport),
            transport,
            vertexModel(),
            context(userText("Hi")),
            StreamOptions(
                apiKey = "ya29.access-token",
                env = mapOf(
                    "GOOGLE_CLOUD_PROJECT" to "my-project",
                    "GOOGLE_CLOUD_LOCATION" to "global",
                ),
            ),
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/my-project/locations/global/publishers/google/models/gemini-3-flash-preview:streamGenerateContent?alt=sse",
            transport.lastRequest?.url,
        )
    }

    @Test
    fun apiKeyUsesGlobalExpressEndpointAndIgnoresLocation() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"hello"}]"""), stopChunk()))
        val model = vertexModel()
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "AIzaSyTESTKEY",
                env = mapOf(
                    "GOOGLE_CLOUD_PROJECT" to "my-project",
                    "GOOGLE_CLOUD_LOCATION" to "europe-west4",
                ),
            ),
        )

        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals(
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3-flash-preview:streamGenerateContent?alt=sse",
            transport.lastRequest?.url,
        )
        assertEquals("AIzaSyTESTKEY", headerOf(transport, "x-goog-api-key"))
        assertNull(headerOf(transport, "Authorization"))
    }

    @Test
    fun streamsOverCustomBaseUrlWithMockServer() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        chunk("""[{"text":"Hello"}]"""),
                        chunk("""[{"text":" vertex"}]"""),
                        stopChunk(usage = usageJson(10, 5, cached = 2, thoughts = 1, total = 16)),
                    ),
                ),
        )
        val transport = MockTransport()
        val model = vertexModel(
            baseUrl = server.url("/v1").toString(),
            cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.0),
        )
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "AIzaSyTESTKEY"),
        )

        assertEquals(listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"), eventTypes(result.events))
        assertEquals("Hello vertex", (result.message.content.single() as TextContent).text)
        assertEquals(8L, result.message.usage.input)
        assertEquals(6L, result.message.usage.output)
        assertEquals(2L, result.message.usage.cacheRead)
        assertEquals(1L, result.message.usage.reasoning)
        val requestUrl = URL(assertNotNull(transport.lastRequest).url)
        assertEquals(
            "/v1/publishers/google/models/gemini-3-flash-preview:streamGenerateContent",
            requestUrl.path,
        )
        assertEquals("alt=sse", requestUrl.query)
    }

    @Test
    fun customBaseUrlWithoutVersionGetsV1Prefix() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse(stopChunk())),
        )
        val transport = MockTransport()
        val model = vertexModel(baseUrl = server.url("/").toString().trimEnd('/'))
        runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "AIzaSyTESTKEY"),
        )
        val requestUrl = URL(assertNotNull(transport.lastRequest).url)
        assertEquals(
            "/v1/publishers/google/models/gemini-3-flash-preview:streamGenerateContent",
            requestUrl.path,
        )
    }

    @Test
    fun reportsMissingProjectForBearerTokens() {
        val transport = CapturingTransport()
        val model = vertexModel()
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "ya29.token", env = mapOf("GOOGLE_CLOUD_LOCATION" to "us-central1")),
        )
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals(
            "Vertex AI requires a project ID. Set GOOGLE_CLOUD_PROJECT/GCLOUD_PROJECT or pass project in options.",
            result.message.errorMessage,
        )
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun reportsMissingLocationForBearerTokens() {
        val transport = CapturingTransport()
        val model = vertexModel()
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(apiKey = "ya29.token", env = mapOf("GOOGLE_CLOUD_PROJECT" to "proj")),
        )
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals(
            "Vertex AI requires a location. Set GOOGLE_CLOUD_LOCATION or pass location in options.",
            result.message.errorMessage,
        )
    }

    @Test
    fun reportsMissingCredentialsForPlaceholderKeys() {
        val transport = CapturingTransport()
        val model = vertexModel()
        for (apiKey in listOf("<authenticated>", "gcp-vertex-credentials", "   ")) {
            val result = runStream(
                GoogleVertexApi(transport),
                transport,
                model,
                context(userText("Hi")),
                StreamOptions(apiKey = apiKey),
            )
            assertEquals(StopReason.Error, result.message.stopReason)
            assertEquals(
                "Vertex AI requires credentials. Pass a Google Cloud API key or an OAuth access token as apiKey.",
                result.message.errorMessage,
            )
        }
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun streamsThinkingAndToolCallsWithSignatures() {
        val transport = CapturingTransport()
        transport.enqueue(
            body = sse(
                chunk("""[{"text":"think","thought":true,"thoughtSignature":"SIG-1"}]"""),
                chunk("""[{"functionCall":{"id":"call-1","name":"echo","args":{"value":"x"}}}]"""),
                stopChunk(),
            ),
        )
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            vertexModel(),
            context(userText("Hi")),
            StreamOptions(apiKey = "ya29.token", env = mapOf("GOOGLE_CLOUD_PROJECT" to "p", "GOOGLE_CLOUD_LOCATION" to "l")),
        )
        assertEquals(
            listOf(
                "start",
                "thinking_start",
                "thinking_delta",
                "thinking_end",
                "toolcall_start",
                "toolcall_delta",
                "toolcall_end",
                "done",
            ),
            eventTypes(result.events),
        )
        val thinking = assertIs<ThinkingContent>(result.message.content[0])
        assertEquals("SIG-1", thinking.thinkingSignature)
        val toolCall = assertIs<ToolCall>(result.message.content[1])
        assertEquals("call-1", toolCall.id)
        assertEquals(StopReason.ToolUse, result.message.stopReason)
    }

    @Test
    fun formatsHttpErrors() {
        val transport = CapturingTransport()
        transport.enqueue(status = 401, body = """{"error":{"message":"unauthorized"}}""")
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            vertexModel(),
            context(userText("Hi")),
            StreamOptions(apiKey = "AIzaSyTESTKEY"),
        )
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("""401: {"error":{"message":"unauthorized"}}""", result.message.errorMessage)
    }

    @Test
    fun retriesRetryableStatuses() {
        val transport = CapturingTransport()
        transport.enqueue(status = 503, headers = mapOf("retry-after-ms" to "1"), body = "unavailable")
        transport.enqueue(body = sse(chunk("""[{"text":"ok"}]"""), stopChunk()))
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            vertexModel(),
            context(userText("Hi")),
            StreamOptions(apiKey = "AIzaSyTESTKEY", maxRetries = 1),
        )
        assertEquals(2, transport.requestCount)
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("ok", (result.message.content.single() as TextContent).text)
    }

    @Test
    fun reportsAbortedRequests() {
        val transport = CapturingTransport()
        val signal = RunSignal()
        signal.abort()
        val result = runStream(
            GoogleVertexApi(transport),
            transport,
            vertexModel(),
            context(userText("Hi")),
            StreamOptions(apiKey = "AIzaSyTESTKEY", signal = signal),
        )
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
        assertEquals(listOf("error"), eventTypes(result.events))
    }

    @Test
    fun streamSimpleDisablesThinkingByDefault() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"ok"}]"""), stopChunk()))
        runSimple(
            GoogleVertexApi(transport),
            transport,
            vertexModel(id = "gemini-2.5-flash"),
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "AIzaSyTESTKEY")),
        )
        val body = bodyOf(transport)
        val thinkingConfig = (body["generationConfig"] as JsonObject)["thinkingConfig"] as JsonObject
        assertEquals(JsonPrimitive(0), thinkingConfig["thinkingBudget"])
    }

    @Test
    fun streamSimpleMapsThinkingLevelsForGemini3() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"ok"}]"""), stopChunk()))
        val thinkingLevelMap = linkedMapOf(
            "off" to null,
            "minimal" to "minimal",
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to null,
            "max" to null,
        )
        runSimple(
            GoogleVertexApi(transport),
            transport,
            vertexModel(id = "gemini-3-flash-preview", thinkingLevelMap = thinkingLevelMap),
            context(userText("Hi")),
            SimpleStreamOptions(stream = StreamOptions(apiKey = "AIzaSyTESTKEY"), reasoning = ThinkingLevel.High),
        )
        val body = bodyOf(transport)
        val thinkingConfig = (body["generationConfig"] as JsonObject)["thinkingConfig"] as JsonObject
        assertEquals(JsonPrimitive(true), thinkingConfig["includeThoughts"])
        assertEquals(JsonPrimitive("HIGH"), thinkingConfig["thinkingLevel"])
    }

    @Test
    fun mergesCustomHeadersWithModelHeaders() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"ok"}]"""), stopChunk()))
        val model = vertexModel().copy(headers = mapOf("x-model" to "model-value", "user-agent" to "model-agent"))
        runStream(
            GoogleVertexApi(transport),
            transport,
            model,
            context(userText("Hi")),
            StreamOptions(
                apiKey = "AIzaSyTESTKEY",
                headers = mapOf(
                    "x-custom" to "custom-value",
                    "user-agent" to null,
                ),
            ),
        )
        assertEquals("model-value", headerOf(transport, "x-model"))
        assertEquals("custom-value", headerOf(transport, "x-custom"))
        assertNull(headerOf(transport, "User-Agent"))
        assertEquals("AIzaSyTESTKEY", headerOf(transport, "x-goog-api-key"))
    }

    @Test
    fun buildsRequestWithToolsAndTemperature() {
        val transport = CapturingTransport()
        transport.enqueue(body = sse(chunk("""[{"text":"ok"}]"""), stopChunk()))
        val parameters = PiJson.parseToJsonElement(
            """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""",
        ) as JsonObject
        val tool = dev.pi.ai.Tool(name = "echo", description = "Echo tool", parameters = parameters)
        val modelContext = TranscriptContext(
            listOf(
                SystemMessage(
                    content = PlainTextContent("base prompt"),
                    toolsAdded = listOf(tool),
                    timestamp = 0,
                ),
                userText("Hi"),
            ),
        )
        runStream(
            GoogleVertexApi(transport),
            transport,
            vertexModel(),
            modelContext,
            StreamOptions(
                apiKey = "ya29.token",
                env = mapOf("GOOGLE_CLOUD_PROJECT" to "p", "GOOGLE_CLOUD_LOCATION" to "l"),
                temperature = 0.2,
                maxTokens = 512,
            ),
        )
        val body = bodyOf(transport)
        val generationConfig = body["generationConfig"] as JsonObject
        assertEquals(JsonPrimitive(0.2), generationConfig["temperature"])
        assertEquals(JsonPrimitive(512), generationConfig["maxOutputTokens"])
        val declarations = (((body["tools"] as JsonArray).first() as JsonObject)["functionDeclarations"] as JsonArray)
        assertEquals(1, declarations.size)
        assertEquals("echo", ((declarations.first() as JsonObject)["name"] as JsonPrimitive).content)
    }

    private fun systemInstructionText(body: JsonObject): String? {
        val systemInstruction = body["systemInstruction"] as? JsonObject ?: return null
        val parts = (systemInstruction["parts"] as? JsonArray) ?: return null
        return ((parts.firstOrNull() as? JsonObject)?.get("text") as? JsonPrimitive)?.content
    }
}
