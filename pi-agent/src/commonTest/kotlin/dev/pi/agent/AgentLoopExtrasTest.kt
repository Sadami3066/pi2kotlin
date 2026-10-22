package dev.pi.agent

import dev.pi.ai.AssistantMessage
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DoneEvent
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Transport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentLoopExtrasTest {

    @Test
    fun `tool_execution_start receives raw arguments before preparation`() = runTest {
        val rawArguments = jsonObjectOf("oldText" to jsonString("a"), "newText" to jsonString("b"))
        val tool = agentTool(
            name = "edit",
            parameters = objectSchema(mapOf("edits" to arraySchema(objectSchema()))),
            prepareArguments = { _ ->
                jsonObjectOf("edits" to kotlinx.serialization.json.JsonArray(emptyList()))
            },
        ) { _, _, _, _ -> AgentToolResult(content = listOf(TextContent("edited"))) }
        var callIndex = 0
        val result = runLoop(
            listOf(userMessage("edit")),
            AgentContext(tools = listOf(tool)),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
        ) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(assistantMessage(listOf(toolCallBlock("tool-1", "edit", rawArguments)), StopReason.ToolUse))
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(rawArguments, result.toolStarts().single().args)
    }

    @Test
    fun `tool preparation and validation failures become error results`() = runTest {
        val executed = mutableListOf<String>()
        val echo = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            executed.add((args["value"] as JsonPrimitive).content)
            AgentToolResult(content = listOf(TextContent("ok")))
        }
        val prepareFail = agentTool(
            name = "preparefail",
            parameters = objectSchema(),
            prepareArguments = { _ -> throw IllegalStateException("prepare boom") },
        ) { _, _, _, _ -> AgentToolResult(content = listOf(TextContent("ran"))) }
        val hookFail = agentTool(name = "hookfail", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            toolExecution = ToolExecutionMode.Sequential,
            beforeToolCall = { hookContext, _ ->
                if (hookContext.toolCall.name == "hookfail") throw IllegalStateException("hook boom")
                null
            },
        )
        var callIndex = 0
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(echo, prepareFail, hookFail)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "missing", jsonObjectOf()),
                            toolCallBlock("tool-2", "echo", jsonObjectOf("value" to kotlinx.serialization.json.JsonObject(mapOf("nested" to JsonPrimitive(true))))),
                            toolCallBlock("tool-3", "preparefail", jsonObjectOf()),
                            toolCallBlock("tool-4", "hookfail", jsonObjectOf()),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(emptyList(), executed)
        assertEquals(2, callIndex)
        val endings = result.toolEnds()
        assertEquals(4, endings.size)
        assertTrue(endings.all { it.isError })
        assertEquals("Tool missing not found", textOf(endings[0].result.content))
        assertTrue(textOf(endings[1].result.content).contains("Validation failed for tool \"echo\""))
        assertEquals("prepare boom", textOf(endings[2].result.content))
        assertEquals("hook boom", textOf(endings[3].result.content))
        assertTrue(endings.all { it.durationMs == null })
        assertEquals("assistant", result.messages.last().role)
    }

    @Test
    fun `blocked tool calls use the default reason`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            beforeToolCall = { _, _ -> BeforeToolCallResult(block = true) },
        )
        var calls = 0
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            calls++
            if (calls == 1) {
                doneStream(assistantMessage(listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("v")))), StopReason.ToolUse))
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals("Tool execution was blocked", textOf(result.toolEnds().single().result.content))
        val toolResult = result.messages
            .filterIsInstance<StandardAgentMessage>()
            .mapNotNull { it.message as? ToolResultMessage }
            .single()
        assertEquals(jsonObjectOf(), toolResult.details)
        assertTrue(toolResult.isError)
        assertNull(toolResult.durationMs)
        assertTrue(toolResult.timestamp > 0)
    }

    @Test
    fun `aborts tool preparation when the signal is already aborted`() = runTest {
        var executed = false
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            executed = true
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        val signal = RunSignal()
        signal.abort()
        var providerCalls = 0
        val result = runLoop(
            listOf(userMessage("go")),
            AgentContext(tools = listOf(tool)),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
            signal = signal,
        ) { _, _, _ ->
            providerCalls++
            if (providerCalls == 1) {
                doneStream(assistantMessage(listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("v")))), StopReason.ToolUse))
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertFalse(executed)
        assertEquals(2, providerCalls)
        assertEquals("Operation aborted", textOf(result.toolEnds().single().result.content))
    }

    @Test
    fun `abort wins over a blocking beforeToolCall result`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        val signal = RunSignal()
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            beforeToolCall = { _, _ ->
                signal.abort()
                BeforeToolCallResult(block = true, reason = "blocked")
            },
        )
        var calls = 0
        val result = runLoop(
            listOf(userMessage("go")),
            AgentContext(tools = listOf(tool)),
            config,
            signal = signal,
        ) { _, _, _ ->
            calls++
            if (calls == 1) {
                doneStream(assistantMessage(listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("v")))), StopReason.ToolUse))
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals("Operation aborted", textOf(result.toolEnds().single().result.content))
    }

    @Test
    fun `tool updates are accepted before settle and ignored after settle`() = runTest {
        var capturedUpdate: AgentToolUpdateCallback? = null
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, onUpdate ->
            capturedUpdate = onUpdate
            onUpdate?.invoke(AgentToolResult(content = listOf(TextContent("first"))))
            onUpdate?.invoke(AgentToolResult(content = listOf(TextContent("second"))))
            AgentToolResult(content = listOf(TextContent("final")))
        }
        var callIndex = 0
        val result = runLoop(
            listOf(userMessage("go")),
            AgentContext(tools = listOf(tool)),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
        ) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(assistantMessage(listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("v")))), StopReason.ToolUse))
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        val updates = result.events.filterIsInstance<AgentEvent.ToolExecutionUpdate>()
        assertEquals(2, updates.size)
        assertEquals("first", textOf(updates[0].partialResult.content))
        val update = assertNotNull(capturedUpdate)
        val job = launch {
            update.invoke(AgentToolResult(content = listOf(TextContent("late"))))
        }
        job.join()
        assertEquals(2, result.events.filterIsInstance<AgentEvent.ToolExecutionUpdate>().size)
    }

    @Test
    fun `forwards signal, api key and stream options to the stream function`() = runTest {
        val signal = RunSignal()
        var captured: SimpleStreamOptions? = null
        val apiKeyCalls = mutableListOf<String>()
        val identity = ClientIdentity(userAgent = "short", appName = "acme", appUrl = "https://acme.test")
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            getApiKey = { provider ->
                apiKeyCalls.add(provider)
                "resolved-key"
            },
            streamOptions = SimpleStreamOptions(
                stream = StreamOptions(
                    sessionId = "session-1",
                    transport = Transport.Sse,
                    maxRetryDelayMs = 999,
                    temperature = 0.5,
                    identity = identity,
                    apiKey = "fallback-key",
                ),
                reasoning = dev.pi.ai.ThinkingLevel.Medium,
                thinkingBudgets = ThinkingBudgets(high = 42),
            ),
        )
        runLoop(listOf(userMessage("hi")), AgentContext(), config, signal = signal) { _, _, options ->
            captured = options
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        val options = assertNotNull(captured)
        assertSame(signal, options.stream.signal)
        assertEquals("resolved-key", options.stream.apiKey)
        assertEquals("session-1", options.stream.sessionId)
        assertEquals(Transport.Sse, options.stream.transport)
        assertEquals(999L, options.stream.maxRetryDelayMs)
        assertEquals(0.5, options.stream.temperature)
        assertEquals(dev.pi.ai.ThinkingLevel.Medium, options.reasoning)
        assertEquals(ThinkingBudgets(high = 42), options.thinkingBudgets)
        assertEquals(listOf("openai"), apiKeyCalls)
        val merged = assertNotNull(options.stream.identity)
        assertEquals("short", merged.userAgent)
        assertEquals("acme", merged.appName)
        assertEquals("https://acme.test", merged.appUrl)
    }

    @Test
    fun `defaults identity attribution to pi and merges configured fields`() = runTest {
        val identities = mutableListOf<ClientIdentity?>()
        val plain: StreamFn = { _, _, options ->
            identities.add(options?.stream?.identity)
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        runLoop(
            listOf(userMessage("hi")),
            AgentContext(),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
            streamFn = plain,
        )
        assertEquals<ClientIdentity?>(ClientIdentity(appName = "pi", appUrl = "https://pi.dev"), identities[0])

        val configured = ClientIdentity(userAgent = "short", appName = "acme", appUrl = null)
        runLoop(listOf(userMessage("hi")), AgentContext(), AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            streamOptions = SimpleStreamOptions(stream = StreamOptions(identity = configured)),
        )) { _, _, options ->
            identities.add(options?.stream?.identity)
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals<ClientIdentity?>(ClientIdentity(userAgent = "short", appName = "acme", appUrl = "https://pi.dev"), identities[1])
    }

    @Test
    fun `falls back to the configured api key when getApiKey returns null`() = runTest {
        var captured: SimpleStreamOptions? = null
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            getApiKey = { null },
            streamOptions = SimpleStreamOptions(stream = StreamOptions(apiKey = "fallback-key")),
        )
        runLoop(listOf(userMessage("hi")), AgentContext(), config) { _, _, options ->
            captured = options
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals("fallback-key", assertNotNull(captured).stream.apiKey)
    }

    @Test
    fun `records the requested thinking level on the final assistant message`() = runTest {
        val highConfig = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            streamOptions = SimpleStreamOptions(reasoning = dev.pi.ai.ThinkingLevel.High),
        )
        val high = runLoop(listOf(userMessage("hi")), AgentContext(), highConfig) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals(ModelThinkingLevel.High, asAssistant(high.messages.last()).thinkingLevel)

        val off = runLoop(listOf(userMessage("hi")), AgentContext(), AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
        )) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals(ModelThinkingLevel.Off, asAssistant(off.messages.last()).thinkingLevel)
    }

    @Test
    fun `emits a synthesized message_start when the stream never sends start`() = runTest {
        val result = runLoop(
            listOf(userMessage("hi")),
            AgentContext(),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
        ) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEventTypes(
            result,
            listOf(
                "agent_start",
                "turn_start",
                "message_start",
                "message_end",
                "message_start",
                "message_end",
                "turn_end",
                "agent_end",
            ),
        )
        val assistantStarts = result.messageStarts().filter { it.role == "assistant" }
        assertEquals(1, assistantStarts.size)
        assertNotSame(assistantStarts.single(), result.messageEnds().last())
    }

    @Test
    fun `streams shallow copies for start and update but the final object for message_end`() = runTest {
        val started = mutableListOf<AssistantMessage>()
        val updates = mutableListOf<AssistantMessage>()
        val result = runLoop(
            listOf(userMessage("hi")),
            AgentContext(),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
        ) { _, _, _ ->
            val stream = dev.pi.ai.createAssistantMessageEventStream()
            val partial = assistantMessage(emptyList(), StopReason.Pending)
            stream.push(StartEvent(partial))
            val text = TextContent("hello")
            partial.content.add(text)
            stream.push(TextDeltaEvent(0, "hello", partial))
            partial.stopReason = StopReason.Stop
            stream.push(DoneEvent(StopReason.Stop, partial))
            stream
        }
        result.events.forEach { event ->
            when (event) {
                is AgentEvent.MessageStart -> if (event.message.role == "assistant") {
                    started.add(asAssistant(event.message))
                }

                is AgentEvent.MessageUpdate -> updates.add(asAssistant(event.message))

                else -> Unit
            }
        }
        assertEquals(1, started.size)
        assertEquals(1, updates.size)
        val finalMessage = asAssistant(result.messages.last())
        assertNotSame(finalMessage, started.single())
        assertNotSame(finalMessage, updates.single())
        assertSame(finalMessage.content, started.single().content)
        assertSame(finalMessage.content, updates.single().content)
        assertSame(result.messages.last(), result.messageEnds().last())
    }

    @Test
    fun `emits immediate preflight failures before later tools start`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            gate.await()
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        var callIndex = 0
        val result = runLoop(
            listOf(userMessage("go")),
            AgentContext(tools = listOf(tool)),
            AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
        ) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                gate.complete(Unit)
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "missing", jsonObjectOf()),
                            toolCallBlock("tool-2", "echo", jsonObjectOf("value" to jsonString("v"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        val toolEvents = result.events.filter { it.type.startsWith("tool_execution") }.map { it.type }
        assertEquals(
            listOf("tool_execution_start", "tool_execution_end", "tool_execution_start", "tool_execution_end"),
            toolEvents,
        )
        assertEquals("tool-1", result.toolEnds().first().toolCallId)
        assertTrue(result.toolEnds().first().isError)
    }

    @Test
    fun `agentLoop exposes events and result`() = runTest {
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        val stream = agentLoop(listOf(userMessage("Hi")), AgentContext(), config) { _, _, _ ->
            streamingTextStream("Hi there!")
        }
        val result = collectAgentStream(stream)
        assertEventTypes(
            result,
            listOf(
                "agent_start",
                "turn_start",
                "message_start",
                "message_end",
                "message_start",
                "message_update",
                "message_update",
                "message_update",
                "message_end",
                "turn_end",
                "agent_end",
            ),
        )
        assertEquals(listOf("user", "assistant"), messageRoles(result.messages))
    }

    @Test
    fun `agentLoopContinue rejects invalid contexts synchronously`() = runTest {
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        val streamFn: StreamFn = { _, _, _ -> error("unexpected") }
        val empty = assertFailsWith<IllegalStateException> {
            agentLoopContinue(AgentContext(), config, streamFn = streamFn)
        }
        assertEquals("Cannot continue: no messages in context", empty.message)
        val assistantTail = assertFailsWith<IllegalStateException> {
            agentLoopContinue(
                AgentContext(messages = listOf(assistantAgentMessage(assistantMessage(listOf(textBlock("x")))))),
                config,
                streamFn = streamFn,
            )
        }
        assertEquals("Cannot continue from message role: assistant", assistantTail.message)
    }

    @Test
    fun `runToolCall validates runs hooks and reports failures as error outcomes`() = runTest {
        val echo = agentTool(
            name = "echo",
            parameters = valueSchema(),
            outputSchema = valueSchema(),
        ) { _, args, _, onUpdate ->
            onUpdate?.invoke(AgentToolResult(content = listOf(TextContent("partial")), details = jsonObjectOf()))
            val value = (args["value"] as JsonPrimitive).content
            AgentToolResult(
                content = listOf(TextContent(value)),
                details = jsonObjectOf(),
                structuredContent = jsonObjectOf("value" to jsonString(value)),
            )
        }
        val failing = agentTool(name = "failing", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(
                content = listOf(TextContent("bad")),
                details = jsonObjectOf("partial" to JsonPrimitive(true)),
                isError = true,
            )
        }
        val assistant = assistantMessage(emptyList())
        val hookCalls = mutableListOf<String>()
        val updates = mutableListOf<AgentToolResult>()
        fun options() = RunToolCallOptions(
            tools = listOf(echo, failing),
            assistantMessage = assistant,
            context = AgentContext(),
            beforeToolCall = { hookContext, _ ->
                hookCalls.add("before ${hookContext.toolCall.id}")
                if ((hookContext.args["value"] as? JsonPrimitive)?.content == "blocked") {
                    BeforeToolCallResult(block = true, reason = "nope")
                } else {
                    null
                }
            },
            afterToolCall = { hookContext, _ ->
                hookCalls.add("after ${hookContext.toolCall.id}")
                null
            },
            onUpdate = { partial -> updates.add(partial) },
        )
        fun call(id: String, name: String, arguments: JsonObject): ToolCall = ToolCall(id, name, arguments)

        val a = runToolCall(call("a", "echo", jsonObjectOf("value" to jsonString("a"))), options())
        assertEquals("a", a.toolCall.id)
        assertEquals<JsonElement?>(jsonObjectOf("value" to jsonString("a")), a.result.structuredContent)
        assertFalse(a.isError)

        val b = runToolCall(
            call("b", "echo", jsonObjectOf("value" to JsonObject(mapOf("nested" to JsonPrimitive(true))))),
            options(),
        )
        assertTrue(b.isError)

        val c = runToolCall(call("c", "echo", jsonObjectOf("value" to jsonString("blocked"))), options())
        assertEquals("nope", textOf(c.result.content))
        assertTrue(c.isError)

        val d = runToolCall(call("d", "missing", jsonObjectOf()), options())
        assertEquals("Tool missing not found", textOf(d.result.content))
        assertTrue(d.isError)

        val e = runToolCall(call("e", "failing", jsonObjectOf()), options())
        assertEquals(jsonObjectOf("partial" to JsonPrimitive(true)), e.result.details)
        assertTrue(e.isError)

        assertEquals(1, updates.size)
        assertEquals("partial", textOf(updates.single().content))
        assertEquals(listOf("before a", "after a", "before c", "before e", "after e"), hookCalls)
    }

    @Test
    fun `runToolCall afterToolCall replaces structured content and drops it when content is replaced`() = runTest {
        val redacted = listOf<dev.pi.ai.Content>(TextContent("redacted"))
        val echo = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            val value = (args["value"] as JsonPrimitive).content
            AgentToolResult(
                content = listOf(TextContent(value)),
                structuredContent = jsonObjectOf("value" to jsonString("original")),
            )
        }
        val assistant = assistantMessage(emptyList())
        val seen = mutableListOf<JsonElement?>()
        val afterResults = listOf(
            AfterToolCallResult(content = redacted),
            AfterToolCallResult(structuredContent = jsonObjectOf("value" to jsonString("replaced"))),
            AfterToolCallResult(
                content = redacted,
                structuredContent = jsonObjectOf("value" to jsonString("both")),
            ),
            AfterToolCallResult(details = jsonObjectOf("note" to jsonString("kept"))),
        )
        for (afterResult in afterResults) {
            val outcome = runToolCall(
                ToolCall("x", "echo", jsonObjectOf("value" to jsonString("original"))),
                RunToolCallOptions(
                    tools = listOf(echo),
                    assistantMessage = assistant,
                    context = AgentContext(),
                    afterToolCall = { _, _ -> afterResult },
                ),
            )
            seen.add(outcome.result.structuredContent)
        }
        assertEquals<List<JsonElement?>>(
            listOf(
                null,
                jsonObjectOf("value" to jsonString("replaced")),
                jsonObjectOf("value" to jsonString("both")),
                jsonObjectOf("value" to jsonString("original")),
            ),
            seen,
        )
    }

    @Test
    fun `afterToolCall failures return error results but keep the execution duration`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("ran")), details = jsonObjectOf("note" to jsonString("original")))
        }
        val outcome = runToolCall(
            ToolCall("x", "echo", jsonObjectOf("value" to jsonString("v"))),
            RunToolCallOptions(
                tools = listOf(tool),
                assistantMessage = assistantMessage(emptyList()),
                context = AgentContext(),
                afterToolCall = { _, _ -> throw IllegalStateException("after boom") },
            ),
        )
        assertTrue(outcome.isError)
        assertEquals("after boom", textOf(outcome.result.content))
        assertNotNull(outcome.durationMs)
    }
}
