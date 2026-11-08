package io.github.whywood.deepdeep.sdk.agent

import io.github.whywood.deepdeep.sdk.ai.AssistantMessage
import io.github.whywood.deepdeep.sdk.ai.AssistantMessageEvent
import io.github.whywood.deepdeep.sdk.ai.AssistantMessageEventStream
import io.github.whywood.deepdeep.sdk.ai.DoneEvent
import io.github.whywood.deepdeep.sdk.ai.ErrorEvent
import io.github.whywood.deepdeep.sdk.ai.PiJson
import io.github.whywood.deepdeep.sdk.ai.StartEvent
import io.github.whywood.deepdeep.sdk.ai.StopReason
import io.github.whywood.deepdeep.sdk.ai.TextContent
import io.github.whywood.deepdeep.sdk.ai.ToolCall
import io.github.whywood.deepdeep.sdk.ai.TranscriptContext
import io.github.whywood.deepdeep.sdk.ai.http.HttpRequest
import io.github.whywood.deepdeep.sdk.ai.http.HttpResponse
import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProxyTest {

    private class FakeHttpTransport(
        private val handler: (HttpRequest) -> HttpResponse,
    ) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return handler(request)
        }
    }

    private fun sseResponse(payload: String, status: Int = 200): HttpResponse = HttpResponse(
        status = status,
        headers = mapOf("Content-Type" to "text/event-stream"),
        body = Buffer().writeUtf8(payload),
    )

    private fun sseEvent(json: String): String = "data: $json\n\n"

    private fun usageJson(): String =
        """{"input":1,"output":2,"cacheRead":0,"cacheWrite":0,"totalTokens":3,"cost":{"input":0.1,"output":0.2,"cacheRead":0.0,"cacheWrite":0.0,"total":0.3}}"""

    private fun proxyOptions(transport: HttpTransport, signal: io.github.whywood.deepdeep.sdk.ai.RunSignal? = null): ProxyStreamOptions =
        ProxyStreamOptions(
            authToken = "token-1",
            proxyUrl = "https://proxy.example/",
            http = transport,
            signal = signal,
            temperature = 0.2,
            maxTokens = 128,
            sessionId = "session-9",
            transport = io.github.whywood.deepdeep.sdk.ai.Transport.Sse,
        )

    private suspend fun collect(stream: AssistantMessageEventStream): Pair<List<AssistantMessageEvent>, AssistantMessage> =
        kotlinx.coroutines.coroutineScope {
            val events = mutableListOf<AssistantMessageEvent>()
            val job = launch { stream.events.collect { event -> events.add(event) } }
            val result = stream.result()
            job.join()
            events to result
        }

    @Test
    fun `preserves tool-call metadata received only on toolcall_end`() = runTest {
        val payload = buildString {
            append(sseEvent("""{"type":"start"}"""))
            append(sseEvent("""{"type":"toolcall_start","contentIndex":0,"id":"call-1","toolName":"echo"}"""))
            append(sseEvent("""{"type":"toolcall_delta","contentIndex":0,"delta":"{\"value\":\"a\"}"}"""))
            append(
                sseEvent(
                    """{"type":"toolcall_end","contentIndex":0,"toolCall":{"type":"toolCall","id":"call-1","name":"echo","arguments":{"value":"a"},"namespace":"ns"}}""",
                ),
            )
            append(sseEvent("""{"type":"done","reason":"toolUse","usage":${usageJson()}}"""))
        }
        val transport = FakeHttpTransport { sseResponse(payload) }
        val stream = streamProxy(
            this,
            createModel(),
            TranscriptContext(emptyList()),
            proxyOptions(transport),
        )
        val (events, result) = collect(stream)
        assertEquals(
            listOf("start", "toolcall_start", "toolcall_delta", "toolcall_end", "done"),
            events.map { event -> eventTypeName(event) },
        )
        val toolCallEnd = events[3] as io.github.whywood.deepdeep.sdk.ai.ToolCallEndEvent
        assertEquals("ns", toolCallEnd.toolCall.namespace)
        assertEquals(StopReason.ToolUse, result.stopReason)
        val finalCall = result.content.filterIsInstance<ToolCall>().single()
        assertEquals("ns", finalCall.namespace)
        assertEquals("a", (finalCall.arguments["value"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `processes terminal metadata when the event is not newline-terminated`() = runTest {
        val payload = buildString {
            append(sseEvent("""{"type":"start"}"""))
            append("""data: {"type":"done","reason":"stop","usage":${usageJson()},"providerThinkingLevel":"high"}""")
        }
        val transport = FakeHttpTransport { sseResponse(payload) }
        val stream = streamProxy(
            this,
            createModel(),
            TranscriptContext(emptyList()),
            proxyOptions(transport),
        )
        val (events, result) = collect(stream)
        assertEquals(listOf("start", "done"), events.map { event -> eventTypeName(event) })
        assertEquals("high", result.providerThinkingLevel)
        assertEquals(StopReason.Stop, result.stopReason)
    }

    @Test
    fun `emits an error instead of hanging when the stream ends without a terminal event`() = runTest {
        val payload = sseEvent("""{"type":"start"}""")
        val transport = FakeHttpTransport { sseResponse(payload) }
        val stream = streamProxy(
            this,
            createModel(),
            TranscriptContext(emptyList()),
            proxyOptions(transport),
        )
        val (events, result) = collect(stream)
        assertEquals(listOf("start", "error"), events.map { event -> eventTypeName(event) })
        assertEquals(StopReason.Error, result.stopReason)
        assertTrue(result.errorMessage!!.contains("Connection closed by proxy server"))
    }

    @Test
    fun `rebuilds streamed text content from delta events`() = runTest {
        val payload = buildString {
            append(sseEvent("""{"type":"start"}"""))
            append(sseEvent("""{"type":"text_start","contentIndex":0}"""))
            append(sseEvent("""{"type":"text_delta","contentIndex":0,"delta":"Hel"}"""))
            append(sseEvent("""{"type":"text_delta","contentIndex":0,"delta":"lo"}"""))
            append(sseEvent("""{"type":"text_end","contentIndex":0}"""))
            append(sseEvent("""{"type":"done","reason":"stop","usage":${usageJson()}}"""))
        }
        val transport = FakeHttpTransport { sseResponse(payload) }
        val stream = streamProxy(
            this,
            createModel(),
            TranscriptContext(emptyList()),
            proxyOptions(transport),
        )
        val (events, result) = collect(stream)
        assertEquals(
            listOf("start", "text_start", "text_delta", "text_delta", "text_end", "done"),
            events.map { event -> eventTypeName(event) },
        )
        assertEquals("Hello", (result.content.single() as TextContent).text)
    }

    @Test
    fun `posts the model transcript and whitelisted options`() = runTest {
        val payload = sseEvent("""{"type":"start"}""") + sseEvent("""{"type":"done","reason":"stop","usage":${usageJson()}}""")
        val transport = FakeHttpTransport { sseResponse(payload) }
        val context = TranscriptContext(listOf(userMessage("hello").asLlmMessage()!!))
        val stream = streamProxy(this, createModel(), context, proxyOptions(transport))
        collect(stream)
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://proxy.example/api/stream", request.url)
        assertEquals("Bearer token-1", request.headers["Authorization"])
        val body = PiJson.parseToJsonElement(request.body!!.decodeToString()).jsonObject
        assertEquals("mock", (body["model"] as JsonObject)["id"]!!.jsonPrimitive.content)
        val contextJson = body["context"] as JsonObject
        assertEquals(1, (contextJson["messages"] as kotlinx.serialization.json.JsonArray).size)
        val options = body["options"] as JsonObject
        assertEquals(0.2, options["temperature"]!!.jsonPrimitive.content.toDouble())
        assertEquals("128", options["maxTokens"]!!.jsonPrimitive.content)
        assertEquals("session-9", options["sessionId"]!!.jsonPrimitive.content)
        assertEquals("sse", options["transport"]!!.jsonPrimitive.content)
        assertTrue(!options.containsKey("apiKey"))
        assertTrue(!options.containsKey("signal"))
        assertTrue(!options.containsKey("headers"))
    }

    @Test
    fun `proxyStreamFn drives an Agent run`() = runTest {
        val payload = buildString {
            append(sseEvent("""{"type":"start"}"""))
            append(sseEvent("""{"type":"text_start","contentIndex":0}"""))
            append(sseEvent("""{"type":"text_delta","contentIndex":0,"delta":"Hello from proxy"}"""))
            append(sseEvent("""{"type":"text_end","contentIndex":0}"""))
            append(sseEvent("""{"type":"done","reason":"stop","usage":${usageJson()}}"""))
        }
        val transport = FakeHttpTransport { sseResponse(payload) }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are helpful."),
                streamFn = proxyStreamFn(this, proxyOptions(transport)),
            ),
        )
        agent.prompt("hi")
        assertEquals(listOf("system", "user", "assistant"), messageRoles(agent.state.messages))
        assertEquals("Hello from proxy", textOf(asAssistant(agent.state.messages.last()).content))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `reports non successful responses as error events`() = runTest {
        val transport = FakeHttpTransport {
            sseResponse("""{"error":"bad token"}""", status = 401)
        }
        val stream = streamProxy(
            this,
            createModel(),
            TranscriptContext(emptyList()),
            proxyOptions(transport),
        )
        val (events, result) = collect(stream)
        assertEquals(listOf("error"), events.map { event -> eventTypeName(event) })
        assertEquals(StopReason.Error, result.stopReason)
        assertEquals("Proxy error: bad token", result.errorMessage)
    }

    @Test
    fun `reports aborted requests with the aborted reason`() = runTest {
        val payload = buildString {
            append(sseEvent("""{"type":"start"}"""))
            append(sseEvent("""{"type":"done","reason":"stop","usage":${usageJson()}}"""))
        }
        val signal = io.github.whywood.deepdeep.sdk.ai.RunSignal()
        signal.abort()
        val transport = FakeHttpTransport { sseResponse(payload) }
        val stream = streamProxy(
            this,
            createModel(),
            TranscriptContext(emptyList()),
            proxyOptions(transport, signal = signal),
        )
        val (events, result) = collect(stream)
        assertEquals(listOf("error"), events.map { event -> eventTypeName(event) })
        assertEquals(StopReason.Aborted, result.stopReason)
        assertEquals("Request aborted by user", result.errorMessage)
    }

    private fun eventTypeName(event: AssistantMessageEvent): String = when (event) {
        is StartEvent -> "start"
        is io.github.whywood.deepdeep.sdk.ai.TextStartEvent -> "text_start"
        is io.github.whywood.deepdeep.sdk.ai.TextDeltaEvent -> "text_delta"
        is io.github.whywood.deepdeep.sdk.ai.TextEndEvent -> "text_end"
        is io.github.whywood.deepdeep.sdk.ai.ThinkingStartEvent -> "thinking_start"
        is io.github.whywood.deepdeep.sdk.ai.ThinkingDeltaEvent -> "thinking_delta"
        is io.github.whywood.deepdeep.sdk.ai.ThinkingEndEvent -> "thinking_end"
        is io.github.whywood.deepdeep.sdk.ai.ToolCallStartEvent -> "toolcall_start"
        is io.github.whywood.deepdeep.sdk.ai.ToolCallDeltaEvent -> "toolcall_delta"
        is io.github.whywood.deepdeep.sdk.ai.ToolCallEndEvent -> "toolcall_end"
        is DoneEvent -> "done"
        is ErrorEvent -> "error"
    }
}
