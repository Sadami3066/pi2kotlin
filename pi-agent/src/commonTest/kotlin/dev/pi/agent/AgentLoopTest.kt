package dev.pi.agent

import dev.pi.ai.Content
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PlainTextContent
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingBudgets as PiThinkingBudgets
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Transport
import dev.pi.ai.Usage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentLoopTest {

    @Test
    fun `uses the configured default when a legacy caller omits streamFn`() = runTest {
        var calls = 0
        setDefaultStreamFn { _, _, _ ->
            calls++
            doneStream(assistantMessage(listOf(textBlock("fallback"))))
        }
        try {
            val context = AgentContext()
            val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
            captureLoop { sink -> runAgentLoop(listOf(userMessage("Hello")), context, config, sink) }
            assertEquals(1, calls)
        } finally {
            setDefaultStreamFn(null)
        }
    }

    @Test
    fun `throws the documented error when no default stream function is configured`() = runTest {
        setDefaultStreamFn(null)
        val error = assertFailsWith<IllegalStateException> {
            captureLoop { sink ->
                runAgentLoop(
                    listOf(userMessage("Hello")),
                    AgentContext(),
                    AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm),
                    sink,
                )
            }
        }
        assertEquals(
            "No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn().",
            error.message,
        )
    }

    @Test
    fun `should emit events with AgentMessage types`() = runTest {
        val context = AgentContext()
        val prompts = listOf(userMessage("Hello"))
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        val result = runLoop(prompts, context, config) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("Hi there!"))))
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
        assertEquals(listOf("user", "assistant"), messageRoles(result.messages))
        assertEquals("assistant", result.turnEnds().single().message.role)
        assertEquals(emptyList(), result.turnEnds().single().toolResults)
    }

    @Test
    fun `should build provider context exclusively from transcript messages`() = runTest {
        val system = systemMessage("Transcript prompt")
        val context = AgentContext()
        var providerContext: dev.pi.ai.TranscriptContext? = null
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        captureLoop { sink ->
            runAgentLoop(
                listOf(system, userMessage("Hello")),
                context,
                config,
                sink,
                null,
            ) { _, llmContext, _ ->
                providerContext = llmContext
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        val messages = assertNotNull(providerContext).messages
        assertEquals(2, messages.size)
        assertSame(system.asLlmMessage(), messages[0])
        assertEquals("system", messages[0].role)
        assertEquals("Transcript prompt", (messages[0] as dev.pi.ai.SystemMessage).content.let { (it as PlainTextContent).text })
    }

    @Test
    fun `should handle custom message types via convertToLlm`() = runTest {
        val notification = NotificationMessage("This is a notification")
        val context = AgentContext(messages = listOf(notification))
        var converted: List<dev.pi.ai.Message> = emptyList()
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = { messages ->
                converted = messages
                    .filter { it.role != "notification" }
                    .mapNotNull { it.asLlmMessage() }
                converted
            },
        )
        runLoop(listOf(userMessage("Hello")), context, config) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("Response"))))
        }
        assertEquals(listOf("user"), converted.map { it.role })
    }

    @Test
    fun `should apply transformContext before convertToLlm`() = runTest {
        val context = AgentContext(
            messages = listOf(
                userMessage("old message 1"),
                assistantAgentMessage(assistantMessage(listOf(textBlock("old response 1")))),
                userMessage("old message 2"),
                assistantAgentMessage(assistantMessage(listOf(textBlock("old response 2")))),
            ),
        )
        var transformed: List<AgentMessage> = emptyList()
        var converted: List<dev.pi.ai.Message> = emptyList()
        val config = AgentLoopConfig(
            model = createModel(),
            transformContext = { messages, _ ->
                transformed = messages.takeLast(2)
                transformed
            },
            convertToLlm = { messages ->
                converted = messages.mapNotNull { it.asLlmMessage() }
                converted
            },
        )
        runLoop(listOf(userMessage("new message")), context, config) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("Response"))))
        }
        assertEquals(2, transformed.size)
        assertEquals(2, converted.size)
    }

    @Test
    fun `should handle tool calls and results`() = runTest {
        val executed = mutableListOf<String>()
        val toolUsage = Usage(
            input = 1,
            output = 2,
            cacheRead = 3,
            cacheWrite = 4,
            totalTokens = 10,
            cost = dev.pi.ai.UsageCost(0.1, 0.2, 0.3, 0.4, 1.0),
        )
        val patchedUsage = Usage(
            input = 5,
            output = 6,
            cacheRead = 7,
            cacheWrite = 8,
            totalTokens = 26,
            cost = dev.pi.ai.UsageCost(0.5, 0.6, 0.7, 0.8, 2.6),
        )
        var observedUsage: Usage? = null
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            val value = (args["value"] as JsonPrimitive).content
            executed.add(value)
            AgentToolResult(
                content = listOf(TextContent("echoed: $value")),
                details = jsonObjectOf("value" to jsonString(value)),
                usage = toolUsage,
            )
        }
        val context = AgentContext(tools = listOf(tool))
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            afterToolCall = { hookContext, _ ->
                observedUsage = hookContext.result.usage
                AfterToolCallResult(usage = patchedUsage)
            },
        )
        var callIndex = 0
        val result = runLoop(listOf(userMessage("echo something")), context, config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("hello")))),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(listOf("hello"), executed)
        assertEquals(toolUsage, observedUsage)
        val toolEnd = result.toolEnds().single()
        assertEquals("tool-1", toolEnd.toolCallId)
        assertFalse(toolEnd.isError)
        assertEquals(patchedUsage, toolEnd.result.usage)
        val toolResult = result.messages
            .filterIsInstance<StandardAgentMessage>()
            .mapNotNull { it.message as? ToolResultMessage }
            .single()
        assertEquals(patchedUsage, toolResult.usage)
        assertEquals(listOf("toolResult", "assistant"), result.messages.takeLast(2).map { it.role })
    }

    @Test
    fun `records how long execute took on the tool result excluding hooks`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            withContext(Dispatchers.Default) { delay(30) }
            AgentToolResult(content = listOf(TextContent((args["value"] as JsonPrimitive).content)))
        }
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            beforeToolCall = { hookContext, _ ->
                withContext(Dispatchers.Default) { delay(100) }
                if (hookContext.toolCall.id == "blocked") BeforeToolCallResult(block = true, reason = "no") else null
            },
        )
        var callIndex = 0
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("ran", "echo", jsonObjectOf("value" to jsonString("a"))),
                            toolCallBlock("blocked", "echo", jsonObjectOf("value" to jsonString("b"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        val results = result.messages
            .filterIsInstance<StandardAgentMessage>()
            .mapNotNull { it.message as? ToolResultMessage }
        val ran = results[0]
        val blocked = results[1]
        val duration = assertNotNull(ran.durationMs)
        assertTrue(duration >= 25 && duration < 100, "duration was $duration")
        assertTrue(blocked.isError)
        assertNull(blocked.durationMs)
    }

    @Test
    fun `should not execute tool calls from a length-truncated assistant message`() = runTest {
        val executed = mutableListOf<String>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            executed.add((args["value"] as JsonPrimitive).content)
            AgentToolResult(content = listOf(TextContent("x")))
        }
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        var callIndex = 0
        val result = runLoop(listOf(userMessage("echo something")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("hel")))),
                        StopReason.Length,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(emptyList(), executed)
        val toolEnd = result.toolEnds().single()
        assertTrue(toolEnd.isError)
        assertTrue(textOf(toolEnd.result.content).contains("output token limit"))
        assertEquals(2, callIndex)
        assertEquals("assistant", result.messages.last().role)
    }

    @Test
    fun `should execute mutated beforeToolCall args without revalidation`() = runTest {
        val executed = mutableListOf<JsonElement>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            executed.add(args.getValue("value"))
            AgentToolResult(content = listOf(TextContent("ok")))
        }
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            beforeToolCall = { hookContext, _ ->
                hookContext.args["value"] = JsonPrimitive(123)
                null
            },
        )
        var callIndex = 0
        runLoop(listOf(userMessage("echo something")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("hello")))),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(listOf<JsonElement>(JsonPrimitive(123)), executed)
    }

    @Test
    fun `should prepare tool arguments for validation`() = runTest {
        val executed = mutableListOf<JsonElement>()
        val replaceSchema = objectSchema(mapOf("oldText" to stringSchema(), "newText" to stringSchema()))
        val editSchema = objectSchema(mapOf("edits" to arraySchema(replaceSchema)))
        val tool = agentTool(
            name = "edit",
            parameters = editSchema,
            prepareArguments = { arguments ->
                val oldText = arguments["oldText"]
                val newText = arguments["newText"]
                if (oldText == null || newText == null) {
                    arguments
                } else {
                    jsonObjectOf(
                        "edits" to kotlinx.serialization.json.JsonArray(
                            listOf(
                                jsonObjectOf("oldText" to oldText, "newText" to newText),
                            ),
                        ),
                    )
                }
            },
        ) { _, args, _, _ ->
            executed.add(args.getValue("edits"))
            AgentToolResult(content = listOf(TextContent("edited")))
        }
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        var callIndex = 0
        runLoop(listOf(userMessage("edit something")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock(
                                "tool-1",
                                "edit",
                                jsonObjectOf("oldText" to jsonString("before"), "newText" to jsonString("after")),
                            ),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        val edits = assertNotNull(executed.single() as? kotlinx.serialization.json.JsonArray)
        assertEquals(1, edits.size)
        assertEquals(
            jsonObjectOf("oldText" to jsonString("before"), "newText" to jsonString("after")),
            edits[0],
        )
    }

    @Test
    fun `emits tool_execution_end in completion order but persists tool results in source order`() = runTest {
        var firstResolved = false
        var parallelObserved = false
        val gate = CompletableDeferred<Unit>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            val value = (args["value"] as JsonPrimitive).content
            if (value == "first") {
                gate.await()
                firstResolved = true
            }
            if (value == "second" && !firstResolved) {
                parallelObserved = true
                gate.complete(Unit)
            }
            AgentToolResult(content = listOf(TextContent("echoed: $value")))
        }
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            toolExecution = ToolExecutionMode.Parallel,
        )
        var callIndex = 0
        val result = runLoop(listOf(userMessage("echo both")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("first"))),
                            toolCallBlock("tool-2", "echo", jsonObjectOf("value" to jsonString("second"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertTrue(parallelObserved)
        assertEquals(listOf("tool-1", "tool-2"), result.toolStarts().map { it.toolCallId })
        assertEquals(listOf("tool-2", "tool-1"), result.toolEnds().map { it.toolCallId })
        assertEquals(listOf("tool-1", "tool-2"), result.turnEnds().first().toolResults.map { asToolResult(it).toolCallId })
        assertEquals(
            listOf("tool-1", "tool-2"),
            result.messageStarts()
                .filterIsInstance<StandardAgentMessage>()
                .mapNotNull { it.message as? ToolResultMessage }
                .map { it.toolCallId },
        )
    }

    @Test
    fun `should inject queued messages after all tool calls complete`() = runTest {
        val executed = mutableListOf<String>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            executed.add((args["value"] as JsonPrimitive).content)
            AgentToolResult(content = listOf(TextContent("ok")))
        }
        val queuedUserMessage = userMessage("interrupt")
        var queuedDelivered = false
        var callIndex = 0
        var sawInterruptInContext = false
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            toolExecution = ToolExecutionMode.Sequential,
            getSteeringMessages = {
                if (executed.size >= 1 && !queuedDelivered) {
                    queuedDelivered = true
                    listOf(queuedUserMessage)
                } else {
                    emptyList()
                }
            },
        )
        val result = runLoop(listOf(userMessage("start")), AgentContext(tools = listOf(tool)), config) { _, context, _ ->
            if (callIndex == 1) {
                sawInterruptInContext = context.messages.any { message ->
                    message is dev.pi.ai.UserMessage && message.content is PlainTextContent &&
                        (message.content as PlainTextContent).text == "interrupt"
                }
            }
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("first"))),
                            toolCallBlock("tool-2", "echo", jsonObjectOf("value" to jsonString("second"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(listOf("first", "second"), executed)
        assertEquals(2, result.toolEnds().size)
        assertTrue(sawInterruptInContext)
        val sequence = result.messageStarts().map { message ->
            when {
                message.role == "toolResult" -> "tool:${asToolResult(message).toolCallId}"
                message.role == "user" -> (message.asLlmMessage() as? dev.pi.ai.UserMessage)?.content
                    ?.let { if (it is PlainTextContent) it.text else null } ?: ""

                else -> message.role
            }
        }
        assertTrue(sequence.contains("interrupt"))
        assertTrue(sequence.indexOf("tool:tool-1") < sequence.indexOf("interrupt"))
        assertTrue(sequence.indexOf("tool:tool-2") < sequence.indexOf("interrupt"))
    }

    @Test
    fun `forces sequential execution when a tool has executionMode sequential with default parallel config`() = runTest {
        var firstResolved = false
        var parallelObserved = false
        val gate = CompletableDeferred<Unit>()
        val tool = agentTool(
            name = "slow",
            parameters = valueSchema(),
            executionMode = ToolExecutionMode.Sequential,
        ) { _, args, _, _ ->
            val value = (args["value"] as JsonPrimitive).content
            if (value == "first") {
                gate.await()
                firstResolved = true
            }
            if (value == "second") {
                if (!firstResolved) parallelObserved = true
                gate.complete(Unit)
            }
            AgentToolResult(content = listOf(TextContent("slow: $value")))
        }
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        var callIndex = 0
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                gate.complete(Unit)
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "slow", jsonObjectOf("value" to jsonString("first"))),
                            toolCallBlock("tool-2", "slow", jsonObjectOf("value" to jsonString("second"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertFalse(parallelObserved)
        assertEquals(listOf("tool-1", "tool-2"), result.toolEnds().map { it.toolCallId })
    }

    @Test
    fun `forces sequential execution when one of multiple tools is sequential`() = runTest {
        val executionOrder = mutableListOf<String>()
        val slow = agentTool(
            name = "slow",
            parameters = valueSchema(),
            executionMode = ToolExecutionMode.Sequential,
        ) { _, args, _, _ ->
            executionOrder.add("slow:${(args["value"] as JsonPrimitive).content}")
            AgentToolResult(content = listOf(TextContent("slow")))
        }
        val fast = agentTool(name = "fast", parameters = valueSchema()) { _, args, _, _ ->
            executionOrder.add("fast:${(args["value"] as JsonPrimitive).content}")
            AgentToolResult(content = listOf(TextContent("fast")))
        }
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        var callIndex = 0
        runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(slow, fast)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "slow", jsonObjectOf("value" to jsonString("a"))),
                            toolCallBlock("tool-2", "fast", jsonObjectOf("value" to jsonString("b"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals("slow:a", executionOrder.first())
        assertEquals(listOf("slow:a", "fast:b"), executionOrder)
    }

    @Test
    fun `allows parallel execution when all tools are parallel`() = runTest {
        var firstSettled = false
        var secondStartedBeforeFirstSettled = false
        val gate = CompletableDeferred<Unit>()
        val first = agentTool(name = "first", parameters = valueSchema()) { _, _, _, _ ->
            gate.await()
            firstSettled = true
            AgentToolResult(content = listOf(TextContent("first")))
        }
        val second = agentTool(name = "second", parameters = valueSchema()) { _, _, _, _ ->
            if (!firstSettled) secondStartedBeforeFirstSettled = true
            gate.complete(Unit)
            AgentToolResult(content = listOf(TextContent("second")))
        }
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        var callIndex = 0
        runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(first, second)), config) { _, _, _ ->
            if (callIndex == 0) {
                callIndex++
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "first", jsonObjectOf("value" to jsonString("a"))),
                            toolCallBlock("tool-2", "second", jsonObjectOf("value" to jsonString("b"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                callIndex++
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertTrue(secondStartedBeforeFirstSettled)
    }
}
