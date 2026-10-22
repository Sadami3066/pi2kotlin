package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.CacheRetention
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
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingLevel
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolChoice
import dev.pi.ai.TranscriptContext
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class PiMessagesTest {
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

    private fun sse(vararg events: String): String = events.joinToString("") { event ->
        "data: $event\n\n"
    }

    private fun usageJson(
        input: Long = 10,
        output: Long = 5,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
    ): String =
        """{"input":$input,"output":$output,"cacheRead":$cacheRead,"cacheWrite":$cacheWrite,""" +
            """"totalTokens":${input + output + cacheRead + cacheWrite},"cost":{"input":0.1,"output":0.2,""" +
            """"cacheRead":0.0,"cacheWrite":0.0,"total":0.3}}"""

    private fun testModel(baseUrl: String): Model = Model(
        id = "auto",
        name = "Radius Auto",
        api = "pi-messages",
        provider = "radius",
        baseUrl = baseUrl,
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.1, cacheWrite = 0.2),
        reasoning = false,
        contextWindow = 128_000,
        maxTokens = 16_384,
    )

    private fun context(text: String = "Hello"): TranscriptContext =
        TranscriptContext(listOf(UserMessage(PlainTextContent(text), timestamp = 1)))

    private fun streamResult(
        transport: MockTransport,
        options: SimpleStreamOptions,
        build: () -> ApiProvider = { piMessagesApi(transport) },
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val api = build()
            val stream = api.streamSimple(testModel(serverBaseUrl), context(), options)
            val message = stream.result()
            val events = stream.events.toList()
            StreamResult(message, events, transport)
        }
    }

    private var serverBaseUrl: String = ""

    private fun withPiServer(
        response: MockResponse,
        block: (MockTransport, MockWebServer) -> Unit,
    ) = withServer { server ->
        server.enqueue(response)
        val transport = MockTransport()
        serverBaseUrl = server.url("/v1").toString().trimEnd('/')
        block(transport, server)
    }

    private fun requestBody(transport: MockTransport): JsonObject {
        val request = assertNotNull(transport.lastRequest)
        return PiJson.parseToJsonElement(request.body!!.decodeToString()).jsonObject
    }

    private fun options(
        maxTokens: Long? = null,
        sessionId: String? = null,
        toolChoice: ToolChoice? = null,
        reasoning: ThinkingLevel? = null,
        headers: Map<String, String?>? = null,
        cacheRetention: CacheRetention? = null,
        env: Map<String, String>? = null,
        signal: RunSignal? = null,
        onResponse: ((dev.pi.ai.ProviderResponse) -> Unit)? = null,
        onProviderStreamEvent: ((JsonElement) -> Unit)? = null,
        onPayload: ((JsonElement) -> JsonElement?)? = null,
    ): SimpleStreamOptions = SimpleStreamOptions(
        stream = StreamOptions(
            apiKey = "test-key",
            headers = headers,
            maxTokens = maxTokens,
            sessionId = sessionId,
            cacheRetention = cacheRetention,
            env = env,
            signal = signal,
            onResponse = onResponse?.let { hook -> { response, _ -> hook(response) } },
            onProviderStreamEvent = onProviderStreamEvent?.let { hook -> { data, _ -> hook(data) } },
            onPayload = onPayload?.let { hook -> { payload, _ -> hook(payload) } },
        ),
        toolChoice = toolChoice,
        reasoning = reasoning,
    )

    @Test
    fun streamsTextAndToolCallsAndResolvesTerminalMessage() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"text_start","contentIndex":0}""",
                    """{"type":"text_delta","contentIndex":0,"delta":"Hel"}""",
                    """{"type":"text_delta","contentIndex":0,"delta":"lo"}""",
                    """{"type":"text_end","contentIndex":0,"content":"Hello"}""",
                    """{"type":"toolcall_start","contentIndex":1,"id":"call_1","toolName":"read"}""",
                    """{"type":"toolcall_delta","contentIndex":1,"delta":"{\"path\":"}""",
                    """{"type":"toolcall_delta","contentIndex":1,"delta":"\"a.txt\"}"}""",
                    """{"type":"toolcall_end","contentIndex":1,"toolCall":{"type":"toolCall","id":"call_1","name":"read","arguments":{"path":"a.txt"}}}""",
                    """{"type":"done","reason":"toolUse","usage":${usageJson()},"responseId":"resp_1","providerThinkingLevel":"high"}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(
            transport,
            options(
                maxTokens = 100,
                sessionId = "session-1",
                toolChoice = ToolChoice.Auto,
                headers = mapOf("x-custom" to "1"),
            ),
        )
        val message = result.message
        assertEquals(StopReason.ToolUse, message.stopReason)
        assertEquals(15L, message.usage.totalTokens)
        assertEquals(0.3, message.usage.cost.total)
        assertEquals("resp_1", message.responseId)
        assertEquals("high", message.providerThinkingLevel)
        assertEquals("auto", message.model)
        assertEquals("radius", message.provider)
        assertEquals(2, message.content.size)
        val text = message.content[0] as TextContent
        assertEquals("Hello", text.text)
        assertNull(text.textSignature)
        val toolCall = message.content[1] as ToolCall
        assertEquals("call_1", toolCall.id)
        assertEquals("read", toolCall.name)
        assertEquals("a.txt", (toolCall.arguments["path"] as JsonPrimitive).content)

        val startedPartials = result.events.filterIsInstance<StartEvent>().map { it.partial }
        assertEquals(1, startedPartials.size)
        assertTrue(startedPartials.single() === message)
        assertTrue(result.events.any { it is TextDeltaEvent })
        assertEquals(1, result.events.filterIsInstance<ToolCallEndEvent>().size)

        val request = assertNotNull(transport.lastRequest)
        assertEquals("POST", request.method)
        assertTrue(request.url.endsWith("/v1/messages"))
        assertEquals("Bearer test-key", request.headers.entries.first { it.key.equals("authorization", true) }.value)
        assertEquals("1", request.headers.entries.first { it.key.equals("x-custom", true) }.value)

        val body = requestBody(transport)
        assertEquals("auto", body["model"]!!.jsonPrimitive.content)
        val requestContext = body["context"]!!.jsonObject
        val messages = requestContext["messages"] as JsonArray
        assertEquals(1, messages.size)
        val user = messages[0].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", user["content"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("maxTokens", "sessionId", "toolChoice"),
            (body["options"]!!.jsonObject).keys,
        )
        val options = body["options"]!!.jsonObject
        assertEquals("100", options["maxTokens"]!!.jsonPrimitive.content)
        assertEquals("session-1", options["sessionId"]!!.jsonPrimitive.content)
        assertEquals("auto", options["toolChoice"]!!.jsonPrimitive.content)
    }

    @Test
    fun forwardsParsedWireEventsInOrderBeforeConversion() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"text_start","contentIndex":0}""",
                    """{"type":"text_delta","contentIndex":0,"delta":"Hello","gatewayField":"upstream-value"}""",
                    """{"type":"text_end","contentIndex":0,"content":"Hello"}""",
                    """{"type":"done","reason":"stop","usage":${usageJson()},"responseId":"resp_1"}""",
                ),
            ),
    ) { transport, _ ->
        val received = mutableListOf<JsonElement>()
        val receivedModels = mutableListOf<Model>()
        val result = runBlocking {
            withTimeout(15_000) {
                val api = piMessagesApi(transport)
                val options = SimpleStreamOptions(
                    stream = StreamOptions(
                        apiKey = "test-key",
                        onProviderStreamEvent = { data, model ->
                            received.add(data)
                            receivedModels.add(model)
                        },
                    ),
                )
                val stream = api.streamSimple(testModel(serverBaseUrl), context(), options)
                val message = stream.result()
                val events = stream.events.toList()
                StreamResult(message, events, transport)
            }
        }
        assertEquals(5, received.size)
        assertEquals("upstream-value", (received[2] as JsonObject)["gatewayField"]!!.jsonPrimitive.content)
        assertTrue(receivedModels.all { it === receivedModels.first() })
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("resp_1", result.message.responseId)
        assertEquals("Hello", (result.message.content.single() as TextContent).text)
    }

    @Test
    fun appendsDebugFlagAndReportsResponseHeaders() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("x-pi-gateway-upstream-provider", "anthropic")
                .setBody(sse("""{"type":"done","reason":"stop","usage":${usageJson()}}""")),
        )
        val transport = MockTransport()
        serverBaseUrl = server.url("/v1").toString().trimEnd('/')
        var observedHeaders: Map<String, String>? = null
        val result = streamResult(
            transport,
            options(onResponse = { response -> observedHeaders = response.headers }),
            build = { piMessagesApi(transport, debug = true) },
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertTrue(assertNotNull(transport.lastRequest).url.endsWith("/v1/messages?debug=1"))
        val header = assertNotNull(observedHeaders).entries.first { it.key.equals("x-pi-gateway-upstream-provider", true) }
        assertEquals("anthropic", header.value)
    }

    @Test
    fun surfacesBackendErrorResponsesWithDiagnostics() = withPiServer(
        MockResponse()
            .setResponseCode(401)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"message":"Token expired","code":"unauthorized"}}"""),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        val message = result.message
        assertEquals(StopReason.Error, message.stopReason)
        val errorMessage = assertNotNull(message.errorMessage)
        assertTrue(errorMessage.contains("401"))
        assertTrue(errorMessage.contains("Token expired"))
        assertTrue(errorMessage.contains("unauthorized"))
        val diagnostic = assertNotNull(message.diagnostics).single()
        assertEquals("pi_messages_response_failure", diagnostic.type)
        assertEquals("401", diagnostic.details!!["status"]!!.jsonPrimitive.content)
        assertEquals("unauthorized", diagnostic.error?.code)
    }

    @Test
    fun propagatesServerSentErrorEvents() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"error","reason":"error","usage":${usageJson()},"errorMessage":"Upstream failed"}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("Upstream failed", result.message.errorMessage)
        assertEquals(15L, result.message.usage.totalTokens)
    }

    @Test
    fun errorsWhenNoApiKeyIsProvided() {
        val transport = MockTransport()
        val result = runBlocking {
            withTimeout(15_000) {
                val api = piMessagesApi(transport)
                val stream = api.streamSimple(testModel("http://127.0.0.1:1/v1"), context(), SimpleStreamOptions())
                val message = stream.result()
                StreamResult(message, stream.events.toList(), transport)
            }
        }
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(assertNotNull(result.message.errorMessage).contains("No API key provided"))
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun errorsWhenStreamEndsWithoutTerminalEvent() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"text_start","contentIndex":0}""",
                    """{"type":"text_delta","contentIndex":0,"delta":"partial"}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertTrue(assertNotNull(result.message.errorMessage).contains("stream ended without a terminal event"))
    }

    @Test
    fun streamsThinkingBlocksWithSignatures() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"thinking_start","contentIndex":0}""",
                    """{"type":"thinking_delta","contentIndex":0,"delta":"step by step"}""",
                    """{"type":"thinking_end","contentIndex":0,"content":"step by step","contentSignature":"sig","redacted":true}""",
                    """{"type":"done","reason":"stop","usage":${usageJson()}}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        val thinking = result.message.content.single() as ThinkingContent
        assertEquals("step by step", thinking.thinking)
        assertEquals("sig", thinking.thinkingSignature)
        assertEquals(true, thinking.redacted)
    }

    @Test
    fun abortsStreamingWhenTheSignalIsAborted() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"text_start","contentIndex":0}""",
                    """{"type":"text_delta","contentIndex":0,"delta":"Hello"}""",
                    """{"type":"done","reason":"stop","usage":${usageJson()}}""",
                ),
            ),
    ) { transport, _ ->
        val signal = RunSignal()
        var eventCount = 0
        val result = streamResult(
            transport,
            options(
                signal = signal,
                onProviderStreamEvent = {
                    eventCount += 1
                    if (eventCount == 2) signal.abort()
                },
            ),
        )
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
    }

    @Test
    fun appendsRewriteDiagnostics() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"done","reason":"stop","usage":${usageJson()},"rewrite":{"policyId":"p1","policyVersion":2,"changed":true,"tokenCountChange":-3,"messageCountChange":0,"systemPromptChanged":false}}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        val diagnostic = assertNotNull(result.message.diagnostics).single()
        assertEquals("pi_messages_rewrite", diagnostic.type)
        val details = assertNotNull(diagnostic.details)
        assertEquals("p1", details["policyId"]!!.jsonPrimitive.content)
        assertEquals("-3", details["tokenCountChange"]!!.jsonPrimitive.content)
    }

    @Test
    fun resolvesCacheRetentionFromOptionsAndEnvironment() {
        withPiServer(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse("""{"type":"done","reason":"stop","usage":${usageJson()}}""")),
        ) { transport, _ ->
            streamResult(transport, options(env = mapOf("PI_CACHE_RETENTION" to "long")))
            assertEquals(
                "long",
                requestBody(transport)["options"]!!.jsonObject["cacheRetention"]!!.jsonPrimitive.content,
            )
        }
        withPiServer(
            MockResponse()
                .setResponseCode(200)
                .setBody(sse("""{"type":"done","reason":"stop","usage":${usageJson()}}""")),
        ) { transport, _ ->
            streamResult(
                transport,
                options(env = mapOf("PI_CACHE_RETENTION" to "long"), cacheRetention = CacheRetention.None),
            )
            assertEquals(
                "none",
                requestBody(transport)["options"]!!.jsonObject["cacheRetention"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun appliesPayloadHook() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(sse("""{"type":"done","reason":"stop","usage":${usageJson()}}""")),
    ) { transport, _ ->
        streamResult(
            transport,
            options(
                onPayload = { payload ->
                    buildJsonObject {
                        payload.jsonObject.forEach { (key, value) -> put(key, value) }
                        put("gatewayExtra", JsonPrimitive(true))
                    }
                },
            ),
        )
        assertEquals("true", requestBody(transport)["gatewayExtra"]!!.jsonPrimitive.content)
    }

    @Test
    fun sendsModelAndEmptyOptionsWhenNothingIsConfigured() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(sse("""{"type":"done","reason":"stop","usage":${usageJson()}}""")),
    ) { transport, _ ->
        streamResult(transport, SimpleStreamOptions(stream = StreamOptions(apiKey = "test-key")))
        assertEquals("auto", requestBody(transport)["model"]!!.jsonPrimitive.content)
        assertEquals(emptySet(), requestBody(transport)["options"]!!.jsonObject.keys)
    }

    @Test
    fun convertsForcedAbortErrorForACompletedErrorEvent() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"error","reason":"aborted","usage":${usageJson()},"errorMessage":"aborted upstream"}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("aborted upstream", result.message.errorMessage)
        assertTrue(result.events.last() is ErrorEvent)
    }

    @Test
    fun returnsDoneEventsForStopAndLength() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(
                sse(
                    """{"type":"start"}""",
                    """{"type":"text_start","contentIndex":0}""",
                    """{"type":"text_delta","contentIndex":0,"delta":"Hi"}""",
                    """{"type":"done","reason":"length","usage":${usageJson()}}""",
                ),
            ),
    ) { transport, _ ->
        val result = streamResult(transport, options())
        assertEquals(StopReason.Length, result.message.stopReason)
        assertTrue(result.events.last() is DoneEvent)
        assertEquals("Hi", (result.message.content.single() as TextContent).text)
    }

    @Test
    fun normalizesRadiusGatewayUrls() {
        assertEquals("https://radius.pi.dev", normalizeRadiusGatewayUrl("radius.pi.dev"))
        assertEquals("https://radius.pi.dev", normalizeRadiusGatewayUrl("https://radius.pi.dev/"))
        assertEquals("HTTPS://radius.pi.dev", normalizeRadiusGatewayUrl("HTTPS://radius.pi.dev//"))
        assertEquals("http://localhost:8080/v1", normalizeRadiusGatewayUrl("http://localhost:8080/v1/"))
        assertEquals(DEFAULT_RADIUS_GATEWAY, normalizeRadiusGatewayUrl(DEFAULT_RADIUS_GATEWAY))
    }

    @Test
    fun mapsRadiusGatewayModelsToPiMessagesModels() {
        val config = RadiusGatewayConfig(
            baseUrl = "https://radius.pi.dev",
            models = listOf(
                RadiusGatewayModel(
                    id = "auto",
                    name = "Radius Auto",
                    reasoning = true,
                    thinkingLevelMap = mapOf("off" to null, "high" to "high"),
                    input = listOf(ModelInputModality.Text, ModelInputModality.Image),
                    cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.1, cacheWrite = 0.2),
                    contextWindow = 128_000,
                    maxTokens = 16_384,
                ),
            ),
        )
        val models = radiusGatewayModels("radius", config)
        val model = models.single()
        assertEquals("pi-messages", model.api)
        assertEquals("radius", model.provider)
        assertEquals("https://radius.pi.dev", model.baseUrl)
        assertEquals("auto", model.id)
        assertTrue(model.reasoning)
        assertEquals(128_000L, model.contextWindow)
        assertEquals(16_384L, model.maxTokens)
        assertEquals(listOf(ModelInputModality.Text, ModelInputModality.Image), model.input)
    }

    @Test
    fun usesTheProvidedTransportForEachRequest() = withPiServer(
        MockResponse()
            .setResponseCode(200)
            .setBody(sse("""{"type":"done","reason":"stop","usage":${usageJson()}}""")),
    ) { transport, _ ->
        streamResult(transport, options())
        assertEquals(1, transport.requestCount)
    }
}
