package dev.pi.agent

import dev.pi.ai.AssistantMessage
import dev.pi.ai.ClientIdentity
import dev.pi.ai.Content
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PlainTextContent
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Transport
import dev.pi.ai.Usage
import kotlinx.coroutines.CompletableDeferred
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

class AgentLoopAdvancedTest {

    private fun toolUseMessage(id: String, name: String, args: JsonObject): AssistantMessage =
        assistantMessage(listOf(toolCallBlock(id, name, args)), StopReason.ToolUse)

    @Test
    fun `runs finishTurn after tool-result messages and before turn_end`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            AgentToolResult(
                content = listOf(TextContent((args["value"] as JsonPrimitive).content)),
                terminate = true,
            )
        }
        val ordering = mutableListOf<String>()
        var finishTurnContext: AgentTurnContext? = null
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { turn, _ ->
                ordering.add("finishTurn")
                finishTurnContext = turn
                null
            },
        )
        val result = captureLoop { sink ->
            runAgentLoop(
                listOf(userMessage("echo")),
                AgentContext(tools = listOf(tool)),
                config,
                emit = { event ->
                    sink(event)
                    if (event is AgentEvent.MessageEnd) ordering.add("message_end:${event.message.role}")
                    if (event is AgentEvent.TurnEnd) ordering.add("turn_end")
                },
                signal = null,
                streamFn = { _, _, _ ->
                    doneStream(
                        assistantMessage(
                            listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("hello")))),
                            StopReason.ToolUse,
                        ),
                    )
                },
            )
        }
        assertEquals(listOf("message_end:toolResult", "finishTurn", "turn_end"), ordering.takeLast(3))
        assertEquals(1, finishTurnContext!!.toolResults.size)
        assertEquals("toolResult", finishTurnContext!!.context.messages.last().role)
        assertEquals("assistant", finishTurnContext!!.message.role)
        assertEquals(1, result.turnEnds().size)
    }

    @Test
    fun `runs finishTurn for error and aborted assistants before turn_end without changing the hard exit`() = runTest {
        for (reason in listOf(StopReason.Error, StopReason.Aborted)) {
            val ordering = mutableListOf<String>()
            var providerCalls = 0
            var steeringPolls = 0
            var followUpPolls = 0
            val config = AgentLoopConfig(
                model = createModel(),
                convertToLlm = identityConvertToLlm,
                finishTurn = { turn, _ ->
                    assertEquals(reason, turn.message.stopReason)
                    ordering.add("finishTurn")
                    AgentTurnDecision.Continue
                },
                getSteeringMessages = {
                    steeringPolls++
                    emptyList()
                },
                getFollowUpMessages = {
                    followUpPolls++
                    listOf(userMessage("queued"))
                },
            )
            val result = captureLoop { sink ->
                runAgentLoop(
                    listOf(userMessage("run")),
                    AgentContext(),
                    config,
                    emit = { event ->
                        sink(event)
                        if (event is AgentEvent.TurnEnd) ordering.add("turn_end")
                    },
                    signal = null,
                    streamFn = { _, _, _ ->
                        providerCalls++
                        val message = assistantMessage(emptyList(), reason)
                        message.errorMessage = reason.name.lowercase()
                        errorStream(message)
                    },
                )
            }
            assertEquals(listOf("finishTurn", "turn_end"), ordering)
            assertEquals(1, result.turnEnds().size)
            assertEquals(emptyList(), result.turnEnds().single().toolResults)
            assertEquals(1, providerCalls)
            assertEquals(1, steeringPolls)
            assertEquals(0, followUpPolls)
        }
    }

    @Test
    fun `action end skips queue polling and next-turn preparation`() = runTest {
        val tool = agentTool(name = "noop", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("done")))
        }
        var providerCalls = 0
        var steeringPolls = 0
        var followUpPolls = 0
        var prepareNextTurnCalls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { _, _ -> AgentTurnDecision.End },
            prepareNextTurn = { _ ->
                prepareNextTurnCalls++
                null
            },
            getSteeringMessages = {
                steeringPolls++
                emptyList()
            },
            getFollowUpMessages = {
                followUpPolls++
                listOf(userMessage("queued"))
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            providerCalls++
            doneStream(toolUseMessage("tool-1", "noop", jsonObjectOf()))
        }
        assertEquals(1, providerCalls)
        assertEquals(1, steeringPolls)
        assertEquals(0, followUpPolls)
        assertEquals(0, prepareNextTurnCalls)
    }

    @Test
    fun `makes exactly one context-only request when no natural request satisfies continuation`() = runTest {
        var providerCalls = 0
        var finishCalls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { _, _ ->
                finishCalls++
                if (finishCalls == 1) AgentTurnDecision.Continue else null
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(), config) { _, _, _ ->
            providerCalls++
            doneStream(assistantMessage(listOf(textBlock("response $providerCalls"))))
        }
        assertEquals(2, providerCalls)
        assertEquals(2, finishCalls)
    }

    @Test
    fun `lets a natural tool-result request satisfy continuation`() = runTest {
        val tool = agentTool(name = "noop", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("done")))
        }
        var providerCalls = 0
        var finishCalls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { _, _ ->
                finishCalls++
                if (finishCalls == 1) AgentTurnDecision.Continue else null
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            providerCalls++
            if (providerCalls == 1) {
                doneStream(toolUseMessage("tool-1", "noop", jsonObjectOf()))
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(2, providerCalls)
        assertEquals(2, finishCalls)
    }

    @Test
    fun `lets a natural steering request satisfy continuation`() = runTest {
        var providerCalls = 0
        var finishCalls = 0
        var steeringPolls = 0
        val queued = userMessage("steering")
        val secondRequestUsers = mutableListOf<String>()
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { _, _ ->
                finishCalls++
                if (finishCalls == 1) AgentTurnDecision.Continue else null
            },
            getSteeringMessages = {
                steeringPolls++
                if (steeringPolls == 2) listOf(queued) else emptyList()
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(), config) { _, context, _ ->
            providerCalls++
            if (providerCalls == 2) {
                secondRequestUsers.addAll(
                    context.messages.mapNotNull { raw ->
                        val user = raw as? dev.pi.ai.UserMessage ?: return@mapNotNull null
                        val content = user.content
                        if (content is dev.pi.ai.PlainTextContent) content.text else null
                    },
                )
            }
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals(2, providerCalls)
        assertEquals(2, finishCalls)
        assertTrue(secondRequestUsers.contains("steering"))
    }

    @Test
    fun `lets a natural follow-up request satisfy continuation`() = runTest {
        var providerCalls = 0
        var finishCalls = 0
        var followUpDelivered = false
        val queued = userMessage("follow-up")
        val secondRequestUsers = mutableListOf<String>()
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { _, _ ->
                finishCalls++
                if (finishCalls == 1) AgentTurnDecision.Continue else null
            },
            getFollowUpMessages = {
                if (followUpDelivered) {
                    emptyList()
                } else {
                    followUpDelivered = true
                    listOf(queued)
                }
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(), config) { _, context, _ ->
            providerCalls++
            if (providerCalls == 2) {
                secondRequestUsers.addAll(
                    context.messages.mapNotNull { raw ->
                        val content = (raw as? dev.pi.ai.UserMessage)?.content ?: return@mapNotNull null
                        if (content is dev.pi.ai.PlainTextContent) content.text else null
                    },
                )
            }
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals(2, providerCalls)
        assertEquals(2, finishCalls)
        assertTrue(secondRequestUsers.contains("follow-up"))
    }

    @Test
    fun `prepareRequest replacement reaches the stream function`() = runTest {
        val replacementModel = createModel(id = "replacement")
        val canonicalMessage = userMessage("canonical projection")
        var capturedModel: dev.pi.ai.Model? = null
        var capturedContext: List<dev.pi.ai.Message> = emptyList()
        var capturedReasoning: dev.pi.ai.ThinkingLevel? = null
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            prepareRequest = { request, _ ->
                AgentRequestUpdate(
                    context = AgentContext(messages = listOf(canonicalMessage), tools = request.context.tools),
                    model = replacementModel,
                    thinkingLevel = ThinkingLevel.High,
                )
            },
        )
        runLoop(listOf(userMessage("prompt")), AgentContext(), config) { model, context, options ->
            capturedModel = model
            capturedContext = context.messages
            capturedReasoning = options?.reasoning
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertSame(replacementModel, capturedModel)
        assertEquals(1, capturedContext.size)
        assertSame(canonicalMessage.asLlmMessage(), capturedContext[0])
        assertEquals<dev.pi.ai.ThinkingLevel?>(dev.pi.ai.ThinkingLevel.High, capturedReasoning)
    }

    @Test
    fun `does not poll steering after prepareRequest`() = runTest {
        val queued = mutableListOf<AgentMessage>()
        val lateSteering = userMessage("late steering")
        val requestIncludedSteering = mutableListOf<Boolean>()
        var requestPreparations = 0
        var steeringPolls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            getSteeringMessages = {
                steeringPolls++
                val drained = queued.toList()
                queued.clear()
                drained
            },
            prepareRequest = { _, _ ->
                requestPreparations++
                if (requestPreparations == 1) queued.add(lateSteering)
                null
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(), config) { _, context, _ ->
            requestIncludedSteering.add(context.messages.any { it === lateSteering.asLlmMessage() })
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals(listOf(false, true), requestIncludedSteering)
        assertEquals(2, requestPreparations)
        assertEquals(3, steeringPolls)
    }

    @Test
    fun `uses prepareNextTurn snapshot before continuing`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            AgentToolResult(content = listOf(TextContent((args["value"] as JsonPrimitive).content)))
        }
        var prepareCalls = 0
        var prepared = false
        var secondTurnHasUpdate = false
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            prepareNextTurn = { turn ->
                prepareCalls++
                if (prepared) {
                    null
                } else {
                    prepared = true
                    AgentLoopTurnUpdate(
                        context = AgentContext(messages = turn.context.messages.toList(), tools = turn.context.tools),
                        messages = listOf(systemMessage("updated guidance")),
                    )
                }
            },
        )
        var llmCalls = 0
        runLoop(listOf(userMessage("echo something")), AgentContext(tools = listOf(tool)), config) { _, context, _ ->
            llmCalls++
            if (llmCalls == 2) {
                secondTurnHasUpdate = context.messages.any { raw ->
                    val system = raw as? dev.pi.ai.SystemMessage ?: return@any false
                    (system.content as? dev.pi.ai.PlainTextContent)?.text == "updated guidance"
                }
            }
            if (llmCalls == 1) {
                doneStream(toolUseMessage("tool-1", "echo", jsonObjectOf("value" to jsonString("hello"))))
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(2, llmCalls)
        assertEquals(1, prepareCalls)
        assertTrue(secondTurnHasUpdate)
    }

    @Test
    fun `picks up steering queued during prepareNextTurn before the next request`() = runTest {
        val tool = agentTool(name = "noop", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("done")))
        }
        val queued = mutableListOf<AgentMessage>()
        val lateSteering = userMessage("late steering")
        var providerCalls = 0
        var secondRequestIncludedSteering = false
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            prepareNextTurn = {
                queued.add(lateSteering)
                null
            },
            getSteeringMessages = {
                val drained = queued.toList()
                queued.clear()
                drained
            },
        )
        runLoop(listOf(userMessage("run")), AgentContext(tools = listOf(tool)), config) { _, context, _ ->
            providerCalls++
            if (providerCalls == 2) secondRequestIncludedSteering = context.messages.any { it === lateSteering.asLlmMessage() }
            if (providerCalls == 1) {
                doneStream(toolUseMessage("tool-1", "noop", jsonObjectOf()))
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(2, providerCalls)
        assertTrue(secondRequestIncludedSteering)
    }

    @Test
    fun `action end receives finalized turn context and stops before queue polling`() = runTest {
        val executed = mutableListOf<String>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            val value = (args["value"] as JsonPrimitive).content
            executed.add(value)
            AgentToolResult(content = listOf(TextContent("echoed: $value")))
        }
        var steeringPolls = 0
        var followUpPolls = 0
        var callbackToolResultIds: List<String> = emptyList()
        var callbackContextRoles: List<String> = emptyList()
        var lastAssistantThinking: ModelThinkingLevel? = null
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            finishTurn = { turn, _ ->
                callbackToolResultIds = turn.toolResults.map { it.toolCallId }
                callbackContextRoles = turn.context.messages.map { it.role }
                assertNotNull(turn.newMessages)
                AgentTurnDecision.End
            },
            getSteeringMessages = {
                steeringPolls++
                emptyList()
            },
            getFollowUpMessages = {
                followUpPolls++
                listOf(userMessage("follow up should stay queued"))
            },
        )
        var llmCalls = 0
        val result = runLoop(listOf(userMessage("echo something")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            llmCalls++
            if (llmCalls == 1) {
                doneStream(toolUseMessage("tool-1", "echo", jsonObjectOf("value" to jsonString("hello"))))
            } else {
                doneStream(assistantMessage(listOf(textBlock("should not run"))))
            }
        }
        assertEquals(1, llmCalls)
        assertEquals(listOf("hello"), executed)
        assertEquals(1, steeringPolls)
        assertEquals(0, followUpPolls)
        assertEquals(listOf("tool-1"), callbackToolResultIds)
        assertEquals(listOf("system", "user", "assistant", "toolResult"), callbackContextRoles)
        assertEquals(listOf("system", "user", "assistant", "toolResult"), messageRoles(result.messages))
        assertEventTypes(
            result,
            listOf(
                "agent_start",
                "turn_start",
                "message_start",
                "message_end",
                "message_start",
                "message_end",
                "message_start",
                "message_end",
                "tool_execution_start",
                "tool_execution_end",
                "message_start",
                "message_end",
                "turn_end",
                "agent_end",
            ),
        )
        val declaration = (result.messages.first().asLlmMessage() as dev.pi.ai.SystemMessage)
        assertEquals("", (declaration.content as PlainTextContent).text)
        assertTrue(declaration.timestamp > 0)
        assertEquals(listOf("echo"), declaration.toolsAdded?.map { it.name })
    }

    @Test
    fun `stops after a tool batch when every tool result sets terminate`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            AgentToolResult(
                content = listOf(TextContent((args["value"] as JsonPrimitive).content)),
                terminate = true,
            )
        }
        var llmCalls = 0
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
        )) { _, _, _ ->
            llmCalls++
            doneStream(toolUseMessage("tool-1", "echo", jsonObjectOf("value" to jsonString("v"))))
        }
        assertEquals(1, llmCalls)
        assertEquals(listOf("system", "user", "assistant", "toolResult"), messageRoles(result.messages))
        assertEquals(1, result.turnEnds().size)
    }

    @Test
    fun `stops after a blocked tool call when beforeToolCall sets terminate`() = runTest {
        var executed = false
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            executed = true
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        var llmCalls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            beforeToolCall = { _, _ -> BeforeToolCallResult(block = true, reason = "Blocked by policy", terminate = true) },
        )
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            llmCalls++
            doneStream(toolUseMessage("tool-1", "echo", jsonObjectOf("value" to jsonString("v"))))
        }
        assertEquals(1, llmCalls)
        assertFalse(executed)
        val toolEnd = result.toolEnds().single()
        assertTrue(toolEnd.isError)
        assertEquals("Blocked by policy", textOf(toolEnd.result.content))
    }

    @Test
    fun `continues after a mixed batch with one terminating blocked call`() = runTest {
        val executed = mutableListOf<String>()
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            executed.add((args["value"] as JsonPrimitive).content)
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        var llmCalls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            beforeToolCall = { hookContext, _ ->
                if (hookContext.toolCall.id == "tool-1") BeforeToolCallResult(
                    block = true,
                    reason = "Blocked by policy",
                    terminate = true,
                ) else null
            },
        )
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            llmCalls++
            if (llmCalls == 1) {
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("a"))),
                            toolCallBlock("tool-2", "echo", jsonObjectOf("value" to jsonString("b"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(2, llmCalls)
        assertEquals(listOf("b"), executed)
    }

    @Test
    fun `continues after parallel tool calls when not all tool results terminate`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            AgentToolResult(
                content = listOf(TextContent((args["value"] as JsonPrimitive).content)),
                terminate = (args["value"] as JsonPrimitive).content == "a",
            )
        }
        var llmCalls = 0
        val result = runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
        )) { _, _, _ ->
            llmCalls++
            if (llmCalls == 1) {
                doneStream(
                    assistantMessage(
                        listOf(
                            toolCallBlock("tool-1", "echo", jsonObjectOf("value" to jsonString("a"))),
                            toolCallBlock("tool-2", "echo", jsonObjectOf("value" to jsonString("b"))),
                        ),
                        StopReason.ToolUse,
                    ),
                )
            } else {
                doneStream(assistantMessage(listOf(textBlock("done"))))
            }
        }
        assertEquals(2, llmCalls)
        assertEquals(
            listOf("system", "user", "assistant", "toolResult", "toolResult", "assistant"),
            messageRoles(result.messages),
        )
    }

    @Test
    fun `allows afterToolCall to mark a tool batch as terminating`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("ran")))
        }
        var llmCalls = 0
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            afterToolCall = { _, _ -> AfterToolCallResult(terminate = true) },
        )
        runLoop(listOf(userMessage("go")), AgentContext(tools = listOf(tool)), config) { _, _, _ ->
            llmCalls++
            doneStream(toolUseMessage("tool-1", "echo", jsonObjectOf("value" to jsonString("v"))))
        }
        assertEquals(1, llmCalls)
    }

    @Test
    fun `rejects continuation without messages and from an assistant tail`() = runTest {
        val config = AgentLoopConfig(model = createModel(), convertToLlm = identityConvertToLlm)
        val streamFn: StreamFn = { _, _, _ -> error("unexpected call") }
        val emptyError = assertFailsWith<IllegalStateException> {
            runLoopContinue(AgentContext(), config, streamFn = streamFn)
        }
        assertEquals("Cannot continue: no messages in context", emptyError.message)
        val assistantError = assertFailsWith<IllegalStateException> {
            runLoopContinue(
                AgentContext(messages = listOf(assistantAgentMessage(assistantMessage(listOf(textBlock("hi")))))),
                config,
                streamFn = streamFn,
            )
        }
        assertEquals("Cannot continue from message role: assistant", assistantError.message)
    }

    @Test
    fun `continue from existing context without emitting user message events`() = runTest {
        val context = AgentContext(messages = listOf(userMessage("Hello")))
        var providerCalls = 0
        val result = runLoopContinue(context, AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
        )) { _, _, _ ->
            providerCalls++
            doneStream(assistantMessage(listOf(textBlock("Hi"))))
        }
        assertEquals(1, providerCalls)
        assertEquals(1, result.messages.size)
        assertEquals("assistant", result.messages.single().role)
        assertEquals(listOf("assistant"), result.messageEnds().map { it.role })
        assertEventTypes(
            result,
            listOf(
                "agent_start",
                "turn_start",
                "message_start",
                "message_end",
                "turn_end",
                "agent_end",
            ),
        )
    }

    @Test
    fun `allows custom message types as the last context message`() = runTest {
        val artifact = ArtifactMessage("files")
        val context = AgentContext(messages = listOf(userMessage("Hello"), artifact))
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = { messages ->
                messages.mapNotNull { message ->
                    when (message) {
                        is StandardAgentMessage -> message.message
                        is ArtifactMessage -> dev.pi.ai.UserMessage(PlainTextContent(message.text), 2)
                        else -> null
                    }
                }
            },
        )
        var converted: List<dev.pi.ai.Message> = emptyList()
        val result = runLoopContinue(context, config.copyForCapture { converted = it }) { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("done"))))
        }
        assertEquals(1, result.messages.size)
        assertEquals("assistant", result.messages.single().role)
        assertEquals(2, converted.size)
    }

    private fun AgentLoopConfig.copyForCapture(onConvert: (List<dev.pi.ai.Message>) -> Unit): AgentLoopConfig =
        AgentLoopConfig(
            model = model,
            convertToLlm = { messages ->
                val result = convertToLlm(messages)
                onConvert(result)
                result
            },
            streamOptions = streamOptions,
            transformContext = transformContext,
            getApiKey = getApiKey,
            finishTurn = finishTurn,
            prepareRequest = prepareRequest,
            prepareNextTurn = prepareNextTurn,
            getSteeringMessages = getSteeringMessages,
            getFollowUpMessages = getFollowUpMessages,
            toolExecution = toolExecution,
            beforeToolCall = beforeToolCall,
            afterToolCall = afterToolCall,
        )
}
