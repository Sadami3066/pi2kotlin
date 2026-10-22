package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.BlockContent
import dev.pi.ai.Context
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
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.UserMessage
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.okHttpTransport
import dev.pi.ai.utils.normalizeContext
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

class BedrockConverseStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpTransport

    private val credentials = mapOf(
        "AWS_ACCESS_KEY_ID" to "AKIDEXAMPLE",
        "AWS_SECRET_ACCESS_KEY" to "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
        "AWS_REGION" to "us-east-1",
    )

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        transport = okHttpTransport(client)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private class StreamResult(
        val message: AssistantMessage,
        val events: List<AssistantMessageEvent>,
    )

    private fun testModel(
        id: String = "anthropic.claude-3-5-sonnet-20240620-v1:0",
        reasoning: Boolean = true,
    ): Model = Model(
        id = id,
        name = "Claude Test",
        api = "bedrock-converse-stream",
        provider = "amazon-bedrock",
        baseUrl = server.url("/").toString().trimEnd('/'),
        input = listOf(ModelInputModality.Text, ModelInputModality.Image),
        cost = ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        reasoning = reasoning,
        contextWindow = 200_000,
        maxTokens = 8192,
    )

    private fun eventFrame(eventType: String, payload: String): ByteArray = AwsEventStream.encodeMessage(
        headers = mapOf(
            ":message-type" to "event",
            ":event-type" to eventType,
            ":content-type" to "application/json",
        ),
        payload = payload.encodeToByteArray(),
    )

    private fun exceptionFrame(exceptionType: String, payload: String): ByteArray = AwsEventStream.encodeMessage(
        headers = mapOf(
            ":message-type" to "exception",
            ":exception-type" to exceptionType,
            ":content-type" to "application/json",
        ),
        payload = payload.encodeToByteArray(),
    )

    private fun enqueueStream(vararg frames: ByteArray) {
        val body = Buffer()
        frames.forEach { body.write(it) }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/vnd.amazon.eventstream")
                .setBody(body),
        )
    }

    private fun transcript(prompt: String = "hello", tools: List<Tool>? = null) = normalizeContext(
        Context(
            systemPrompt = "You are a test assistant",
            messages = listOf(UserMessage(PlainTextContent(prompt), timestamp = 1L)),
            tools = tools,
        ),
    )

    private suspend fun collect(stream: dev.pi.ai.AssistantMessageEventStream): StreamResult {
        val events = withTimeout(10_000L) { stream.events.toList() }
        val message = withTimeout(10_000L) { stream.result() }
        return StreamResult(message, events)
    }

    private fun eventName(event: AssistantMessageEvent): String = when (event) {
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

    @Test
    fun streamsTextUsageAndSigV4Request() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"Hello"}}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":" world"}}"""),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
            eventFrame(
                "metadata",
                """{"usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15}}""",
            ),
        )
        val model = testModel()
        val stream = bedrockConverseStreamApi(transport).stream(
            model,
            transcript(),
            StreamOptions(env = credentials),
        )
        val result = collect(stream)
        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            result.events.map(::eventName),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("Hello world", (result.message.content[0] as TextContent).text)
        assertEquals(10L, result.message.usage.input)
        assertEquals(5L, result.message.usage.output)
        assertEquals(15L, result.message.usage.totalTokens)
        assertEquals(3.0 / 1_000_000.0 * 10, result.message.usage.cost.input, 1e-12)
        assertEquals(15.0 / 1_000_000.0 * 5, result.message.usage.cost.output, 1e-12)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse-stream",
            recorded.path,
        )
        val authorization = recorded.getHeader("Authorization")
        assertNotNull(authorization)
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"))
        assertNotNull(recorded.getHeader("x-amz-date"))
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        val body = PiJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals("hello", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(8192L, body["inferenceConfig"]!!.jsonObject["maxTokens"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun streamsToolUseAndSerializesToolConfig() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame(
                "contentBlockStart",
                """{"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"tool-1","name":"get_weather"}}}""",
            ),
            eventFrame(
                "contentBlockDelta",
                """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"{\"city\":"}}}""",
            ),
            eventFrame(
                "contentBlockDelta",
                """{"contentBlockIndex":0,"delta":{"toolUse":{"input":"\"Paris\"}"}}}""",
            ),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("messageStop", """{"stopReason":"tool_use"}"""),
            eventFrame("metadata", """{"usage":{"inputTokens":12,"outputTokens":7}}"""),
        )
        val tool = Tool(
            name = "get_weather",
            description = "Get the weather",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("city", buildJsonObject { put("type", "string") }) })
                put("required", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("city"))))
            },
        )
        val model = testModel()
        val stream = bedrockConverseStreamApi(transport).stream(
            model,
            transcript(tools = listOf(tool)),
            StreamOptions(env = credentials),
        )
        val result = collect(stream)
        val names = result.events.map(::eventName)
        assertTrue(names.contains("toolcall_start"))
        assertTrue(names.contains("toolcall_delta"))
        assertTrue(names.contains("toolcall_end"))
        assertEquals(StopReason.ToolUse, result.message.stopReason)
        val toolCall = result.message.content[0] as ToolCall
        assertEquals("tool-1", toolCall.id)
        assertEquals("get_weather", toolCall.name)
        assertEquals("Paris", toolCall.arguments["city"]!!.jsonPrimitive.content)

        val body = PiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val toolConfig = body["toolConfig"]!!.jsonObject
        val toolSpec = toolConfig["tools"]!!.jsonArray[0].jsonObject["toolSpec"]!!.jsonObject
        assertEquals("get_weather", toolSpec["name"]!!.jsonPrimitive.content)
        assertEquals(
            "object",
            toolSpec["inputSchema"]!!.jsonObject["json"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun streamSimpleAddsThinkingBudgetAndCachePoints() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame(
                "contentBlockDelta",
                """{"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"Let me think"}}}""",
            ),
            eventFrame(
                "contentBlockDelta",
                """{"contentBlockIndex":0,"delta":{"reasoningContent":{"signature":"sig-abc"}}}""",
            ),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":1,"delta":{"text":"Answer"}}"""),
            eventFrame("contentBlockStop", """{"contentBlockIndex":1}"""),
            eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
            eventFrame("metadata", """{"usage":{"inputTokens":20,"outputTokens":9}}"""),
        )
        val model = testModel(id = "anthropic.claude-sonnet-4-5-20250929-v1:0")
        val stream = bedrockConverseStreamApi(transport).streamSimple(
            model,
            transcript(),
            SimpleStreamOptions(
                stream = StreamOptions(env = credentials),
                reasoning = ThinkingLevel.High,
            ),
        )
        val result = collect(stream)
        val names = result.events.map(::eventName)
        assertTrue(names.contains("thinking_start"))
        assertTrue(names.contains("thinking_delta"))
        assertTrue(names.contains("thinking_end"))
        val thinking = result.message.content[0] as ThinkingContent
        assertEquals("Let me think", thinking.thinking)
        assertEquals("sig-abc", thinking.thinkingSignature)
        assertEquals("Answer", (result.message.content[1] as TextContent).text)

        val body = PiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val additional = body["additionalModelRequestFields"]!!.jsonObject
        val thinkingJson = additional["thinking"]!!.jsonObject
        assertEquals("enabled", thinkingJson["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", thinkingJson["display"]!!.jsonPrimitive.content)
        assertEquals(7168L, thinkingJson["budget_tokens"]!!.jsonPrimitive.content.toLong())
        assertTrue(additional.containsKey("anthropic_beta"))
        val system = body["system"]!!.jsonArray
        assertTrue(system.last().jsonObject.containsKey("cachePoint"))
        val lastUserContent = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertTrue(lastUserContent.last().jsonObject.containsKey("cachePoint"))
    }

    @Test
    fun mapsCacheUsageAndCosts() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"ok"}}"""),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
            eventFrame(
                "metadata",
                """{"usage":{"inputTokens":100,"outputTokens":20,"totalTokens":170,"cacheReadInputTokens":30,"cacheWriteInputTokens":20,"cacheDetails":[{"ttl":"1h","inputTokens":20}]}}""",
            ),
        )
        val result = collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                transcript(),
                StreamOptions(env = credentials),
            ),
        )
        assertEquals(30L, result.message.usage.cacheRead)
        assertEquals(20L, result.message.usage.cacheWrite)
        assertEquals(20L, result.message.usage.cacheWrite1h)
        assertEquals(170L, result.message.usage.totalTokens)
        assertEquals((3.0 * 2.0 * 20.0) / 1_000_000.0, result.message.usage.cost.cacheWrite, 1e-12)
    }

    @Test
    fun mapsEventStreamExceptionFrameToErrorEvent() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            exceptionFrame("ThrottlingException", """{"message":"Too many requests"}"""),
        )
        val result = collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                transcript(),
                StreamOptions(env = credentials),
            ),
        )
        val error = assertIs<ErrorEvent>(result.events.last())
        assertEquals(StopReason.Error, error.reason)
        assertEquals("Throttling error: Too many requests", error.error.errorMessage)
        assertEquals("bedrock_response_failure", error.error.diagnostics?.first()?.type)
    }

    @Test
    fun mapsHttpErrorResponseToErrorEvent() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"bad request"}"""),
        )
        val result = collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                transcript(),
                StreamOptions(env = credentials),
            ),
        )
        val error = assertIs<ErrorEvent>(result.events.last())
        assertEquals(StopReason.Error, error.reason)
        assertTrue(error.error.errorMessage!!.contains("Bedrock API error (400)"))
        assertTrue(error.error.errorMessage!!.contains("bad request"))
    }

    @Test
    fun retriesRetryableHttpStatuses() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setHeader("retry-after-ms", "0"))
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"retried"}}"""),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
            eventFrame("metadata", """{"usage":{"inputTokens":1,"outputTokens":1}}"""),
        )
        val result = collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                transcript(),
                StreamOptions(env = credentials, maxRetries = 1, maxRetryDelayMs = 1000L),
            ),
        )
        assertEquals(StopReason.Stop, result.message.stopReason)
        assertEquals("retried", (result.message.content[0] as TextContent).text)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun reportsAbortedStreams() = runBlocking {
        val signal = RunSignal()
        signal.abort("test abort")
        val result = collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                transcript(),
                StreamOptions(env = credentials, signal = signal),
            ),
        )
        val error = assertIs<ErrorEvent>(result.events.last())
        assertEquals(StopReason.Aborted, error.reason)
        assertEquals("Request was aborted", error.error.errorMessage)
    }

    @Test
    fun usesBearerTokenInsteadOfSigV4() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"bearer"}}"""),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
            eventFrame("metadata", """{"usage":{"inputTokens":1,"outputTokens":1}}"""),
        )
        val result = collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                transcript(),
                StreamOptions(apiKey = "ABSK-test-bearer", env = credentials),
            ),
        )
        assertEquals("bearer", (result.message.content[0] as TextContent).text)
        val recorded = server.takeRequest()
        assertEquals("Bearer ABSK-test-bearer", recorded.getHeader("Authorization"))
        assertEquals(null, recorded.getHeader("x-amz-date"))
    }

    @Test
    fun replaysThinkingAndToolResultsIntoRequest() = runBlocking {
        enqueueStream(
            eventFrame("messageStart", """{"role":"assistant"}"""),
            eventFrame("contentBlockDelta", """{"contentBlockIndex":0,"delta":{"text":"done"}}"""),
            eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
            eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
            eventFrame("metadata", """{"usage":{"inputTokens":1,"outputTokens":1}}"""),
        )
        val context = dev.pi.ai.TranscriptContext(
            listOf(
                dev.pi.ai.SystemMessage(PlainTextContent("system"), timestamp = 0),
                UserMessage(PlainTextContent("question"), timestamp = 1),
                AssistantMessage(
                    content = mutableListOf(
                        ThinkingContent(
                            thinking = "reasoning",
                            thinkingSignature = "c2ln",
                        ),
                        ToolCall(
                            id = "call-1",
                            name = "lookup",
                            arguments = buildJsonObject { put("q", "x") },
                        ),
                    ),
                    api = "bedrock-converse-stream",
                    provider = "amazon-bedrock",
                    model = "anthropic.claude-3-5-sonnet-20240620-v1:0",
                    usage = dev.pi.ai.Usage.empty(),
                    stopReason = StopReason.ToolUse,
                    timestamp = 2,
                ),
                ToolResultMessage(
                    toolCallId = "call-1",
                    toolName = "lookup",
                    content = listOf(TextContent("result")),
                    isError = false,
                    timestamp = 3,
                ),
            ),
        )
        collect(
            bedrockConverseStreamApi(transport).stream(
                testModel(),
                context,
                StreamOptions(env = credentials),
            ),
        )
        val body = PiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        val assistant = messages[1].jsonObject
        val assistantContent = assistant["content"]!!.jsonArray
        val reasoning = assistantContent[0].jsonObject["reasoningContent"]!!.jsonObject["reasoningText"]!!.jsonObject
        assertEquals("reasoning", reasoning["text"]!!.jsonPrimitive.content)
        assertEquals("c2ln", reasoning["signature"]!!.jsonPrimitive.content)
        val toolUse = assistantContent[1].jsonObject["toolUse"]!!.jsonObject
        assertEquals("call-1", toolUse["toolUseId"]!!.jsonPrimitive.content)
        assertEquals("lookup", toolUse["name"]!!.jsonPrimitive.content)
        val toolResult = messages[2].jsonObject["content"]!!.jsonArray[0].jsonObject["toolResult"]!!.jsonObject
        assertEquals("call-1", toolResult["toolUseId"]!!.jsonPrimitive.content)
        assertEquals("success", toolResult["status"]!!.jsonPrimitive.content)
    }
}
