package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureOpenAIResponsesTest {
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
        output: JsonArray? = null,
        incompleteReason: String? = null,
        error: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("id", id)
        put("status", status)
        if (usage != null) put("usage", usage)
        if (output != null) put("output", output)
        if (error != null) put("error", error)
        if (incompleteReason != null) {
            put("incomplete_details", buildJsonObject { put("reason", incompleteReason) })
        }
    }

    private fun usageObject(input: Long, output: Long, cached: Long = 0): JsonObject = buildJsonObject {
        put("input_tokens", input)
        put("output_tokens", output)
        put("total_tokens", input + output)
        put(
            "input_tokens_details",
            buildJsonObject {
                put("cached_tokens", cached)
                put("cache_write_tokens", 0)
            },
        )
    }

    private fun textItem(id: String, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("id", id)
        put("role", "assistant")
        put("status", "completed")
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
    }

    private fun functionCallItem(id: String, callId: String, name: String, arguments: String): JsonObject =
        buildJsonObject {
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

    private fun testModel(
        server: MockWebServer? = null,
        baseUrl: String = "",
        reasoning: Boolean = false,
        thinkingLevelMap: Map<String, String?>? = null,
        compat: JsonObject? = null,
    ): Model = Model(
        id = "gpt-test",
        name = "GPT Test",
        api = "azure-openai-responses",
        provider = "azure",
        baseUrl = if (server != null) server.url("/openai/v1").toString() else baseUrl,
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 0.5, cacheWrite = 0.5),
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = 128_000,
        maxTokens = 4_096,
        compat = compat,
    )

    private fun context(vararg messages: dev.pi.ai.Message, tools: List<dev.pi.ai.Tool> = emptyList()): TranscriptContext =
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

    private fun runStream(
        transport: HttpTransport,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions = StreamOptions(apiKey = "test-key"),
    ): StreamResult = runBlocking {
        withTimeout(15_000) {
            val api = azureOpenAIResponsesApi(transport)
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
            val api = azureOpenAIResponsesApi(transport)
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
            is dev.pi.ai.ThinkingStartEvent -> "thinking_start"
            is dev.pi.ai.ThinkingDeltaEvent -> "thinking_delta"
            is dev.pi.ai.ThinkingEndEvent -> "thinking_end"
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

    private fun JsonObject.inputItems(): List<JsonObject> =
        arrayField("input").orEmpty().mapNotNull { it as? JsonObject }

    @Test
    fun normalizesAzureBaseUrlsLikeUpstream() {
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.openai.azure.com"),
        )
        assertEquals(
            "https://my-resource.cognitiveservices.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.cognitiveservices.azure.com/"),
        )
        assertEquals(
            "https://my-resource.ai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.ai.azure.com/openai"),
        )
        assertEquals(
            "https://my-resource.cognitiveservices.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.cognitiveservices.azure.com/openai/v1"),
        )
        assertEquals(
            "https://my-resource.services.ai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.services.ai.azure.com/openai/v1/responses"),
        )
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            normalizeAzureBaseUrl("https://my-resource.openai.azure.com/openai?api-version=2024-12-01"),
        )
        assertEquals(
            "https://my-proxy.example.com/v1",
            normalizeAzureBaseUrl("https://my-proxy.example.com/v1/"),
        )
        assertEquals(
            "https://my-proxy.example.com/v1?custom=true",
            normalizeAzureBaseUrl("https://my-proxy.example.com/v1?custom=true"),
        )
    }

    @Test
    fun rejectsInvalidAzureBaseUrl() {
        val failure = assertFailsWith<IllegalArgumentException> {
            normalizeAzureBaseUrl("not-a-url")
        }
        assertEquals("Invalid Azure OpenAI base URL: not-a-url", failure.message)
    }

    @Test
    fun requiresAzureBaseUrlWithUpstreamMessage() {
        val model = testModel()
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveAzureBaseUrl(model, emptyMap())
        }
        assertEquals(
            "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME, " +
                "or pass azureBaseUrl, azureResourceName, or model.baseUrl.",
            failure.message,
        )
    }

    @Test
    fun resolvesBaseUrlFromEnvironmentAndModel() {
        val model = testModel()
        assertEquals(
            "https://my-resource.openai.azure.com/openai/v1",
            resolveAzureBaseUrl(model, mapOf("AZURE_OPENAI_RESOURCE_NAME" to "my-resource")),
        )
        assertEquals(
            "https://proxy.example.com/v1",
            resolveAzureBaseUrl(model, mapOf("AZURE_OPENAI_BASE_URL" to "https://proxy.example.com/v1")),
        )
        assertEquals(
            "https://model.example.com/v1",
            resolveAzureBaseUrl(testModel(baseUrl = "https://model.example.com/v1"), emptyMap()),
        )
    }

    @Test
    fun resolvesDeploymentNamesAndApiVersions() {
        val model = testModel()
        val env = mapOf(
            "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-test=my-deployment, other=second=ignored, broken",
        )
        assertEquals("my-deployment", resolveAzureDeploymentName(model, env))
        assertEquals("gpt-test", resolveAzureDeploymentName(model, emptyMap()))
        assertEquals("second=ignored", resolveAzureDeploymentName(model, mapOf("AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-test=second=ignored")))
        assertEquals("v1", resolveAzureApiVersion(emptyMap()))
        assertEquals("2024-10-21", resolveAzureApiVersion(mapOf("AZURE_OPENAI_API_VERSION" to "2024-10-21")))
        val failure = assertFailsWith<IllegalArgumentException> {
            resolveAzureApiVersion(mapOf("AZURE_OPENAI_API_VERSION" to "bad version!"))
        }
        assertEquals("Invalid Azure OpenAI API version: bad version!", failure.message)
    }

    @Test
    fun buildsResponsesUrlWithApiVersion() {
        assertEquals(
            "https://res.openai.azure.com/openai/v1/responses?api-version=v1",
            buildAzureResponsesUrl("https://res.openai.azure.com/openai/v1", "v1"),
        )
        assertEquals(
            "https://proxy.example.com/v1/responses?api-version=2024-10-21",
            buildAzureResponsesUrl("https://proxy.example.com/v1", "2024-10-21"),
        )
    }

    @Test
    fun streamsTextWithApiKeyDeploymentAndApiVersion() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") {
                    put("response", responseObject("resp_azure", "in_progress"))
                },
                event("response.output_item.added") {
                    put("output_index", 0)
                    put("item", textItem("msg_azure", ""))
                },
                event("response.output_text.delta") {
                    put("output_index", 0)
                    put("delta", "Hello from Azure")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", textItem("msg_azure", "Hello from Azure"))
                },
                event("response.completed") {
                    put(
                        "response",
                        responseObject("resp_azure", "completed", usage = usageObject(20, 7, cached = 2)),
                    )
                },
            ),
        )
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(
                apiKey = "secret-key",
                sessionId = "session-azure",
                env = mapOf(
                    "AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-test=my-deployment",
                    "AZURE_OPENAI_API_VERSION" to "2024-10-21",
                ),
            ),
        )

        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("resp_azure", result.message.responseId)
        assertEquals("Hello from Azure", (result.message.content.first() as TextContent).text)
        assertEquals(18, result.message.usage.input)
        assertEquals(2, result.message.usage.cacheRead)
        val recorded = nextRecorded(server)
        assertEquals("/openai/v1/responses", recorded.url.encodedPath)
        assertEquals("2024-10-21", recorded.url.queryParameter("api-version"))
        assertEquals("secret-key", recorded.headers["api-key"])
        assertNull(recorded.headers["authorization"])
        val body = PiJson.parseToJsonElement(assertNotNull(recorded.body).utf8()) as JsonObject
        assertEquals("my-deployment", body.stringField("model"))
        assertEquals("false", (body["store"] as JsonPrimitive).content)
        assertEquals("session-azure", body.stringField("prompt_cache_key"))
    }

    @Test
    fun usesAzureBaseUrlEnvironmentVariable() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_env", "completed")) }),
        )
        val baseUrl = server.url("/custom/v1").toString()
        runStream(
            transport,
            testModel(),
            context(userText("Hi")),
            StreamOptions(apiKey = "secret-key", env = mapOf("AZURE_OPENAI_BASE_URL" to baseUrl)),
        )
        val recorded = nextRecorded(server)
        assertEquals("/custom/v1/responses", recorded.url.encodedPath)
        assertEquals("v1", recorded.url.queryParameter("api-version"))
        val sentBody = PiJson.parseToJsonElement(assertNotNull(recorded.body).utf8()) as JsonObject
        assertEquals("gpt-test", sentBody.stringField("model"))
    }

    @Test
    fun supportsBearerAuthorizationWithoutApiKeyHeader() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_bearer", "completed")) }),
        )
        val result = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(headers = mapOf("Authorization" to "Bearer entra-token")),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val recorded = nextRecorded(server)
        assertEquals("Bearer entra-token", recorded.headers["authorization"])
        assertNull(recorded.headers["api-key"])
    }

    @Test
    fun detectsEntraTokenPassedAsApiKey() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_jwt", "completed")) }),
        )
        val token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyIn0.signature"
        runStream(transport, testModel(server), context(userText("Hi")), StreamOptions(apiKey = token))
        val recorded = nextRecorded(server)
        assertEquals("Bearer $token", recorded.headers["authorization"])
        assertNull(recorded.headers["api-key"])
    }

    @Test
    fun streamsToolCallAndReplaysFunctionCallWithStrictToolsDefault() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.created") {
                    put("response", responseObject("resp_tool", "in_progress"))
                },
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
                event("response.completed") { put("response", responseObject("resp_tool", "completed")) },
            ),
        )
        val tool = dev.pi.ai.Tool(
            name = "edit",
            description = "Edit a file",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
                put("required", JsonArray(listOf(JsonPrimitive("path"))))
            },
        )
        val constrainedTool = dev.pi.ai.Tool(
            name = "lookup",
            description = "Look up a value",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
                put("required", JsonArray(listOf(JsonPrimitive("query"))))
            },
            constrainedSampling = buildJsonObject {
                put("type", "json_schema")
                put("strict", "prefer")
            },
        )
        val model = testModel(server)
        val first = runStream(
            transport,
            model,
            context(userText("edit"), tools = listOf(tool, constrainedTool)),
        )
        assertEquals(StopReason.ToolUse, first.message.stopReason)
        val toolCall = assertNotNull(first.message.content.filterIsInstance<ToolCall>().firstOrNull())
        assertEquals("call_1|fc_1", toolCall.id)
        assertEquals("a", (toolCall.arguments["path"] as JsonPrimitive).content)
        val body = nextRequest(server)
        val requestTools = assertNotNull(body.arrayField("tools")).mapNotNull { it as? JsonObject }
        val plainTool = assertNotNull(requestTools.firstOrNull { it.stringField("name") == "edit" })
        assertEquals("false", (plainTool["strict"] as JsonPrimitive).content)
        val strictTool = assertNotNull(requestTools.firstOrNull { it.stringField("name") == "lookup" })
        assertEquals("true", (strictTool["strict"] as JsonPrimitive).content)
        val convertedParameters = assertNotNull(strictTool["parameters"] as JsonObject)
        assertEquals("false", (convertedParameters["additionalProperties"] as JsonPrimitive).content)

        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_tool2", "completed")) }),
        )
        val toolResult = ToolResultMessage(
            toolCallId = "call_1|fc_1",
            toolName = "edit",
            content = listOf(TextContent("ok")),
            isError = false,
            timestamp = 2,
        )
        runStream(transport, model, TranscriptContext(listOf(userText("edit"), first.message, toolResult)))
        val replayBody = nextRequest(server)
        val replayCall = assertNotNull(
            replayBody.inputItems().firstOrNull { it.stringField("type") == "function_call" },
        )
        assertEquals("call_1", replayCall.stringField("call_id"))
        assertEquals("fc_1", replayCall.stringField("id"))
        val replayOutput = assertNotNull(
            replayBody.inputItems().firstOrNull { it.stringField("type") == "function_call_output" },
        )
        assertEquals("call_1", replayOutput.stringField("call_id"))
        assertEquals("ok", replayOutput.stringField("output"))
    }

    @Test
    fun honorsStrictModeDisabledCompat() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_nostrict", "completed")) }),
        )
        val compat = buildJsonObject { put("supportsStrictMode", false) }
        val tool = dev.pi.ai.Tool(
            name = "lookup",
            description = "Look up",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
            },
        )
        runStream(
            transport,
            testModel(server, compat = compat),
            context(userText("Hi"), tools = listOf(tool)),
            StreamOptions(apiKey = "test-key"),
        )
        val body = nextRequest(server)
        val functionTool = assertNotNull(body.arrayField("tools")).single() as JsonObject
        assertNull(functionTool["strict"])
    }

    @Test
    fun backfillsEncryptedContentFromCompletedResponseForReplay() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(
                event("response.output_item.added") {
                    put("output_index", 0)
                    put(
                        "item",
                        buildJsonObject {
                            put("type", "reasoning")
                            put("id", "rs_azure")
                            put("summary", JsonArray(emptyList()))
                        },
                    )
                },
                event("response.reasoning_summary_text.delta") {
                    put("output_index", 0)
                    put("delta", "thinking")
                },
                event("response.output_item.done") {
                    put("output_index", 0)
                    put("item", reasoningItem("rs_azure", "thinking"))
                },
                event("response.completed") {
                    put(
                        "response",
                        responseObject(
                            "resp_reason",
                            "completed",
                            output = JsonArray(listOf(reasoningItem("rs_azure", "thinking", "enc_from_completed"))),
                        ),
                    )
                },
            ),
        )
        val model = testModel(server, reasoning = true)
        val first = runStream(transport, model, context(userText("Hi")))
        val thinking = assertNotNull(first.message.content.filterIsInstance<ThinkingContent>().firstOrNull())
        assertTrue(assertNotNull(thinking.thinkingSignature).contains("enc_from_completed"))
        nextRequest(server)

        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_reason2", "completed")) }),
        )
        runStream(transport, model, TranscriptContext(listOf(userText("Hi"), first.message)))
        val replayBody = nextRequest(server)
        val reasoning = assertNotNull(
            replayBody.inputItems().firstOrNull { it.stringField("type") == "reasoning" },
        )
        assertEquals("rs_azure", reasoning.stringField("id"))
        assertEquals("enc_from_completed", reasoning.stringField("encrypted_content"))
    }

    @Test
    fun formatsAzureProviderErrorsWithContentFilterBody() = withServer { server, transport ->
        server.enqueue(
            MockResponse.Builder()
                .code(400)
                .addHeader("content-type", "application/json")
                .body(
                    """{"error":{"code":"content_filter","message":"ResponsibleAIPolicyViolation"}}""",
                )
                .build(),
        )
        val result = runStream(transport, testModel(server), context(userText("Hi")))
        assertEquals(StopReason.Error, result.message.stopReason)
        val errorMessage = assertNotNull(result.message.errorMessage)
        assertTrue(errorMessage.contains("Azure OpenAI API error (400)"))
        assertTrue(errorMessage.contains("content_filter"))
        assertTrue(errorMessage.contains("ResponsibleAIPolicyViolation"))
    }

    @Test
    fun mapsContentFilteredIncompleteResponseToError() = withServer { server, transport ->
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
    }

    @Test
    fun abortsStreamingResponse() = withServer { server, transport ->
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
        assertEquals(StopReason.Aborted, result.message.stopReason)
        assertEquals("Request was aborted", result.message.errorMessage)
        assertEquals("error", eventTypes(result.events).last())
    }

    @Test
    fun reportsMissingBaseUrlAndInvalidApiVersionAsErrorEvents() = withServer { server, transport ->
        enqueueSse(server, sse(event("response.completed") { put("response", responseObject("x", "completed")) }))
        val missingBase = runStream(transport, testModel(), context(userText("Hi")))
        assertEquals(StopReason.Error, missingBase.message.stopReason)
        assertEquals(
            "Azure OpenAI base URL is required. Set AZURE_OPENAI_BASE_URL or AZURE_OPENAI_RESOURCE_NAME, " +
                "or pass azureBaseUrl, azureResourceName, or model.baseUrl.",
            missingBase.message.errorMessage,
        )
        assertEquals(0, server.requestCount)

        val invalidVersion = runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(apiKey = "test-key", env = mapOf("AZURE_OPENAI_API_VERSION" to "bad version!")),
        )
        assertEquals(StopReason.Error, invalidVersion.message.stopReason)
        assertEquals("Invalid Azure OpenAI API version: bad version!", invalidVersion.message.errorMessage)
    }

    @Test
    fun reportsMissingApiKey() = withServer { server, transport ->
        enqueueSse(server, sse(event("response.completed") { put("response", responseObject("x", "completed")) }))
        val result = runStream(transport, testModel(server), context(userText("Hi")), StreamOptions())
        assertEquals(StopReason.Error, result.message.stopReason)
        assertEquals("No API key for provider: azure", result.message.errorMessage)
        val failure = assertFailsWith<IllegalStateException> {
            runSimple(
                transport,
                testModel(server),
                context(userText("Hi")),
                SimpleStreamOptions(stream = StreamOptions()),
            )
        }
        assertEquals("No API key for provider: azure", failure.message)
    }

    @Test
    fun retriesRetryableResponsesUsingRetryAfterMs() = withServer { server, transport ->
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
    fun streamSimpleMapsReasoningAndToolChoice() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_simple", "completed")) }),
        )
        val thinkingLevelMap = mapOf("off" to "none", "high" to "high")
        val result = runSimple(
            transport,
            testModel(server, reasoning = true, thinkingLevelMap = thinkingLevelMap),
            context(userText("Hi")),
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "test-key", maxTokens = 8),
                reasoning = dev.pi.ai.ThinkingLevel.High,
                toolChoice = ToolChoice.None,
            ),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        val body = nextRequest(server)
        assertEquals("none", body.stringField("tool_choice"))
        assertEquals("16", (body["max_output_tokens"] as JsonPrimitive).content)
        assertEquals("high", (body["reasoning"] as JsonObject).stringField("effort"))
        assertEquals("auto", (body["reasoning"] as JsonObject).stringField("summary"))
    }

    @Test
    fun clampsPromptCacheKeyAndUsesDeploymentAsModel() = withServer { server, transport ->
        enqueueSse(
            server,
            sse(event("response.completed") { put("response", responseObject("resp_cache", "completed")) }),
        )
        runStream(
            transport,
            testModel(server),
            context(userText("Hi")),
            StreamOptions(
                apiKey = "test-key",
                sessionId = "y".repeat(80),
                env = mapOf("AZURE_OPENAI_DEPLOYMENT_NAME_MAP" to "gpt-test=deployment-1"),
            ),
        )
        val body = nextRequest(server)
        assertEquals("deployment-1", body.stringField("model"))
        assertEquals("y".repeat(64), body.stringField("prompt_cache_key"))
    }
}
