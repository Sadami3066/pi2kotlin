package dev.pi.ai.fixtures

import dev.pi.ai.AssistantMessage
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
import dev.pi.ai.api.anthropicMessagesApi
import dev.pi.ai.api.openAICompletionsApi
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
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.fail

private class FixtureTransport(private val body: String) : HttpTransport {
    var lastRequest: HttpRequest? = null

    override suspend fun execute(request: HttpRequest): HttpResponse {
        lastRequest = request
        return HttpResponse(
            status = 200,
            headers = mapOf("content-type" to "text/event-stream"),
            body = Buffer().writeUtf8(body),
        )
    }
}

private object OpenAICompletionsFixture {
    val request = """
        {
          "model": "fixture-model",
          "messages": [
            { "role": "system", "content": "You are a fixture." },
            { "role": "user", "content": "hi" }
          ],
          "stream": true,
          "temperature": 0.2,
          "max_completion_tokens": 512,
          "tools": [
            {
              "type": "function",
              "function": {
                "name": "read",
                "description": "Read a file"
              }
            }
          ]
        }
    """.trimIndent()

    val sse = buildString {
        append(
            """data: {"id":"chatcmpl-fixture","object":"chat.completion.chunk","created":1700000000,"model":"fixture-model","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
        )
        append("\n\n")
        append(
            """data: {"id":"chatcmpl-fixture","object":"chat.completion.chunk","created":1700000000,"model":"fixture-model","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}""",
        )
        append("\n\n")
        append(
            """data: {"id":"chatcmpl-fixture","object":"chat.completion.chunk","created":1700000000,"model":"fixture-model","choices":[{"index":0,"delta":{"content":" fixture"},"finish_reason":null}]}""",
        )
        append("\n\n")
        append(
            """data: {"id":"chatcmpl-fixture","object":"chat.completion.chunk","created":1700000000,"model":"fixture-model","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        )
        append("\n\n")
        append(
            """data: {"id":"chatcmpl-fixture","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14,"prompt_tokens_details":{"cached_tokens":2,"cache_write_tokens":1},"completion_tokens_details":{"reasoning_tokens":0}}}""",
        )
        append("\n\n")
        append("data: [DONE]\n\n")
    }

    val expectedEvents = listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done")
}

private object AnthropicMessagesFixture {
    val request = """
        {
          "model": "fixture-model",
          "messages": [
            { "role": "user", "content": [ { "type": "text", "text": "hi" } ] }
          ],
          "max_tokens": 512,
          "stream": true,
          "system": [ { "type": "text", "text": "You are a fixture." } ],
          "tools": [
            {
              "name": "read",
              "description": "Read a file",
              "input_schema": {
                "type": "object",
                "properties": { "path": { "type": "string" } },
                "required": ["path"]
              }
            }
          ],
          "temperature": 0.2
        }
    """.trimIndent()

    val sse = buildString {
        fun event(name: String, data: String) {
            append("event: ").append(name).append('\n')
            append("data: ").append(data).append("\n\n")
        }

        event(
            "message_start",
            """{"type":"message_start","message":{"id":"msg_fixture","type":"message","role":"assistant","model":"fixture-model","content":[],"stop_reason":null,"usage":{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":3,"cache_creation_input_tokens":2}}}""",
        )
        event(
            "content_block_start",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        )
        event(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi "}}""",
        )
        event(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}""",
        )
        event("content_block_stop", """{"type":"content_block_stop","index":0}""")
        event(
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_fixture","name":"read","input":{}}}""",
        )
        event(
            "content_block_delta",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.txt\"}"}}""",
        )
        event("content_block_stop", """{"type":"content_block_stop","index":1}""")
        event(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":5}}""",
        )
        event("message_stop", """{"type":"message_stop"}""")
    }

    val expectedEvents = listOf(
        "start",
        "text_start",
        "text_delta",
        "text_delta",
        "text_end",
        "toolcall_start",
        "toolcall_delta",
        "toolcall_end",
        "done",
    )
}

class TsProtocolFixturesTest {
    private fun model(api: String, baseUrl: String, provider: String = "fixture-provider"): Model = Model(
        id = "fixture-model",
        name = "Fixture Model",
        api = api,
        provider = provider,
        baseUrl = baseUrl,
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
        reasoning = false,
        contextWindow = 100_000,
        maxTokens = 512,
    )

    private fun context(): TranscriptContext = TranscriptContext(
        listOf(
            SystemMessage(
                content = PlainTextContent("You are a fixture."),
                toolsAdded = listOf(
                    Tool(
                        name = "read",
                        description = "Read a file",
                        parameters = buildParameters(),
                    ),
                ),
                timestamp = 0,
            ),
            UserMessage(content = PlainTextContent("hi"), timestamp = 1),
        ),
    )

    private fun buildParameters(): JsonObject = PiJson.parseToJsonElement(
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    ) as JsonObject

    private fun options(): StreamOptions = StreamOptions(apiKey = "fixture-key", temperature = 0.2, maxTokens = 512)

    private fun eventTypes(events: List<dev.pi.ai.AssistantMessageEvent>): List<String> = events.map { event ->
        when (event) {
            is StartEvent -> "start"
            is TextStartEvent -> "text_start"
            is TextDeltaEvent -> "text_delta"
            is TextEndEvent -> "text_end"
            is ThinkingStartEvent -> "thinking_start"
            is dev.pi.ai.ThinkingDeltaEvent -> "thinking_delta"
            is dev.pi.ai.ThinkingEndEvent -> "thinking_end"
            is dev.pi.ai.ToolCallStartEvent -> "toolcall_start"
            is dev.pi.ai.ToolCallDeltaEvent -> "toolcall_delta"
            is ToolCallEndEvent -> "toolcall_end"
            is DoneEvent -> "done"
            is ErrorEvent -> "error"
        }
    }

    private fun requestBody(transport: FixtureTransport): JsonObject {
        val body = assertNotNull(transport.lastRequest?.body, "no request body captured")
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    private fun assertJsonSubset(expected: JsonElement, actual: JsonElement?, path: String = "$") {
        if (actual == null) fail("missing JSON value at $path")
        when (expected) {
            is JsonObject -> {
                val actualObject = assertIs<JsonObject>(actual, "expected object at $path")
                for ((key, expectedValue) in expected) {
                    assertJsonSubset(expectedValue, actualObject[key], "$path.$key")
                }
            }

            is JsonArray -> {
                val actualArray = assertIs<JsonArray>(actual, "expected array at $path")
                assertEquals(expected.size, actualArray.size, "array length at $path")
                expected.forEachIndexed { index, expectedValue ->
                    assertJsonSubset(expectedValue, actualArray[index], "$path[$index]")
                }
            }

            is JsonPrimitive -> assertEquals(expected, actual, "primitive mismatch at $path")
        }
    }

    private fun runFixture(
        transport: FixtureTransport,
        api: dev.pi.ai.api.ApiProvider,
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): Pair<AssistantMessage, List<dev.pi.ai.AssistantMessageEvent>> = runBlocking {
        withTimeout(15_000) {
            val stream = api.stream(model, context, options)
            val message = stream.result()
            message to stream.events.toList()
        }
    }

    @Test
    fun openAICompletionsParsesTsFormatSseAndBuildsTsFormatRequest() {
        val fixture = OpenAICompletionsFixture
        val transport = FixtureTransport(fixture.sse)
        val (message, events) = runFixture(
            transport,
            openAICompletionsApi(transport),
            model("openai-completions", "https://fixture.test/v1", provider = "fixture-provider"),
            context(),
            options(),
        )

        assertEquals(fixture.expectedEvents, eventTypes(events))
        assertEquals(StopReason.Stop, message.stopReason)
        assertEquals("chatcmpl-fixture", message.responseId)
        assertEquals(listOf<dev.pi.ai.Content>(TextContent("Hello fixture")), message.content.toList())
        assertEquals(7, message.usage.input)
        assertEquals(4, message.usage.output)
        assertEquals(2, message.usage.cacheRead)
        assertEquals(1, message.usage.cacheWrite)
        assertEquals(14, message.usage.totalTokens)
        assertNull(errorOrNull(message))

        val request = assertNotNull(transport.lastRequest)
        assertEquals("https://fixture.test/v1/chat/completions", request.url)
        assertEquals("Bearer fixture-key", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        val actualBody = requestBody(transport)
        val expectedBody = PiJson.parseToJsonElement(fixture.request) as JsonObject
        assertJsonSubset(expectedBody, actualBody)
    }

    @Test
    fun anthropicMessagesParsesTsFormatSseAndBuildsTsFormatRequest() {
        val fixture = AnthropicMessagesFixture
        val transport = FixtureTransport(fixture.sse)
        val (message, events) = runFixture(
            transport,
            anthropicMessagesApi(transport),
            model("anthropic-messages", "https://fixture.test", provider = "fixture-provider"),
            context(),
            options(),
        )

        assertEquals(fixture.expectedEvents, eventTypes(events))
        assertEquals(StopReason.ToolUse, message.stopReason)
        assertEquals("tool_use", message.rawStopReason)
        assertEquals("msg_fixture", message.responseId)
        val text = assertIs<TextContent>(message.content[0])
        assertEquals("Hi there", text.text)
        val toolCall = assertIs<ToolCall>(message.content[1])
        assertEquals("toolu_fixture", toolCall.id)
        assertEquals("read", toolCall.name)
        assertEquals("a.txt", (toolCall.arguments["path"] as JsonPrimitive).content)
        assertEquals(12, message.usage.input)
        assertEquals(5, message.usage.output)
        assertEquals(3, message.usage.cacheRead)
        assertEquals(2, message.usage.cacheWrite)
        assertEquals(22, message.usage.totalTokens)
        assertNull(errorOrNull(message))
        assertEquals(2, message.content.size)

        val request = assertNotNull(transport.lastRequest)
        assertEquals("https://fixture.test/v1/messages", request.url)
        assertEquals("fixture-key", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertEquals("application/json", request.headers["Content-Type"])
        val actualBody = requestBody(transport)
        val expectedBody = PiJson.parseToJsonElement(fixture.request) as JsonObject
        assertJsonSubset(expectedBody, actualBody)
    }

    private fun errorOrNull(message: AssistantMessage): String? = message.errorMessage

    private fun assertNull(value: String?) = kotlin.test.assertNull(value)
}
