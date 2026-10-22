package dev.pi.agent

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.RunSignal
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.ToolReference
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.UserMessage
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.utils.getCurrentSystemMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTest {

    private val unusedStreamFunction: StreamFn = { _, _, _ ->
        throw IllegalStateException("Unexpected stream call")
    }

    private fun abortableStreamFn(): StreamFn = { _, _, options ->
        val stream = createAssistantMessageEventStream()
        stream.push(StartEvent(assistantMessage(emptyList(), StopReason.Pending)))
        options?.stream?.signal?.onAbort {
            val aborted = assistantMessage(emptyList(), StopReason.Aborted)
            aborted.errorMessage = "Aborted"
            stream.push(ErrorEvent(StopReason.Aborted, aborted))
            stream.end()
        }
        stream
    }

    private fun systemOf(message: AgentMessage): dev.pi.ai.SystemMessage =
        message.asLlmMessage() as dev.pi.ai.SystemMessage

    @Test
    fun `uses the configured default when a legacy caller omits streamFn`() = runTest {
        var calls = 0
        setDefaultStreamFn { _, _, _ ->
            calls++
            doneStream(assistantMessage(listOf(textBlock("fallback"))))
        }
        try {
            val agent = Agent()
            agent.prompt("Hello")
            assertEquals(1, calls)
        } finally {
            setDefaultStreamFn(null)
        }
    }

    @Test
    fun `creates an agent instance with default state`() {
        val agent = Agent(AgentOptions(streamFn = unusedStreamFunction))
        assertNotNull(agent.state.model)
        assertEquals(ThinkingLevel.Off, agent.state.thinkingLevel)
        assertEquals(emptyList(), agent.state.tools)
        assertEquals(emptyList(), agent.state.messages)
        assertFalse(agent.state.isStreaming)
        assertNull(agent.state.streamingMessage)
        assertEquals(emptySet<String>(), agent.state.pendingToolCalls)
        assertNull(agent.state.errorMessage)
        assertEquals("", agent.state.systemPrompt)
    }

    @Test
    fun `creates an agent instance with custom initial state`() {
        val customModel = createModel(id = "gpt-4o-mini")
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(
                    systemPrompt = "You are a helpful assistant.",
                    model = customModel,
                    thinkingLevel = ThinkingLevel.Low,
                ),
                streamFn = unusedStreamFunction,
            ),
        )
        assertEquals(1, agent.state.messages.size)
        val initial = systemOf(agent.state.messages[0])
        assertEquals("You are a helpful assistant.", (initial.content as dev.pi.ai.PlainTextContent).text)
        assertEquals(0L, initial.timestamp)
        assertSame(customModel, agent.state.model)
        assertEquals(ThinkingLevel.Low, agent.state.thinkingLevel)
    }

    @Test
    fun `converts initial prompt and tools into transcript state`() {
        val tool = agentTool(name = "echo", description = "Echo input") { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("echo")))
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are helpful.", tools = listOf(tool)),
                streamFn = unusedStreamFunction,
            ),
        )
        val initial = systemOf(agent.state.messages[0])
        assertEquals("You are helpful.", (initial.content as dev.pi.ai.PlainTextContent).text)
        assertEquals(listOf("echo"), initial.toolsAdded?.map { it.name })
    }

    @Test
    fun `declares tool loadout changes to the model before the next request`() = runTest {
        val first = agentTool(name = "first", description = "first tool") { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("first")))
        }
        val second = agentTool(name = "second", description = "second tool") { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("second")))
        }
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are helpful.", tools = listOf(first)),
                streamFn = { _, context, _ ->
                    requests.add(
                        context.messages.flatMap { message ->
                            val system = message as? dev.pi.ai.SystemMessage
                            if (system == null) {
                                emptyList()
                            } else {
                                listOf(
                                    "+" + (system.toolsAdded ?: emptyList()).map { it.name }.joinToString(","),
                                    "-" + (system.toolsRemoved ?: emptyList()).map { it.name }.joinToString(","),
                                )
                            }
                        },
                    )
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.prompt("one")
        agent.state.tools = mutableListOf(second)
        agent.prompt("two")
        agent.prompt("three")
        assertEquals(
            listOf(
                listOf("+first", "-"),
                listOf("+first", "-", "+second", "-first"),
                listOf("+first", "-", "+second", "-first"),
            ),
            requests,
        )
        val update = agent.state.messages
            .map { it.asLlmMessage() as? dev.pi.ai.SystemMessage }
            .filterNotNull()
            .first { it.toolsRemoved != null }
        assertEquals("", (update.content as dev.pi.ai.PlainTextContent).text)
        assertEquals(listOf("second"), update.toolsAdded?.map { it.name })
        assertEquals(listOf(ToolReference("first")), update.toolsRemoved)
        assertTrue(update.timestamp > 0)
        val initial = systemOf(agent.state.messages[0])
        assertEquals(listOf("first"), initial.toolsAdded?.map { it.name })
    }

    @Test
    fun `merges tool changes into a pending system message`() = runTest {
        val tool = agentTool(name = "echo", description = "Echo input") { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("echo")))
        }
        var systemMessagesSeen = 0
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are helpful."),
                streamFn = { _, context, _ ->
                    systemMessagesSeen = context.messages.count { it.role == "system" }
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.state.tools = mutableListOf(tool)
        agent.prompt(
            listOf(
                systemMessage("", sections = mapOf("skills" to "<skills>x</skills>"), timestamp = 1),
                userMessage("hi"),
            ),
        )
        assertEquals(2, systemMessagesSeen)
        val merged = systemOf(agent.state.messages[1])
        assertEquals("", (merged.content as dev.pi.ai.PlainTextContent).text)
        assertEquals(mapOf("skills" to "<skills>x</skills>"), merged.sections)
        assertEquals(listOf("echo"), merged.toolsAdded?.map { it.name })
        assertEquals(1L, merged.timestamp)
    }

    @Test
    fun `rewrites pending tool declarations to match the executable set`() = runTest {
        val first = agentTool(name = "first", description = "first tool") { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("first")))
        }
        val secondDeclaration = dev.pi.ai.Tool("second", "second tool", objectSchema())
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are helpful.", tools = listOf(first)),
                streamFn = { _, _, _ -> doneStream(assistantMessage(listOf(textBlock("done")))) },
            ),
        )
        agent.prompt(
            listOf(
                systemMessage(
                    "",
                    sections = mapOf("note" to "<note>x</note>"),
                    toolsAdded = listOf(secondDeclaration),
                    toolsRemoved = listOf(ToolReference("first")),
                    timestamp = 1,
                ),
                userMessage("hi"),
            ),
        )
        val rewritten = systemOf(agent.state.messages[1])
        assertEquals("", (rewritten.content as dev.pi.ai.PlainTextContent).text)
        assertEquals(mapOf("note" to "<note>x</note>"), rewritten.sections)
        assertNull(rewritten.toolsAdded)
        assertNull(rewritten.toolsRemoved)
        assertEquals(1L, rewritten.timestamp)
        val current = getCurrentSystemMessage(agent.state.messages.mapNotNull { it.asLlmMessage() })
        assertEquals(listOf("first"), current?.toolsAdded?.map { it.name })
    }

    @Test
    fun `restores the transcript baseline when reset`() {
        val tool = agentTool(name = "echo", description = "Echo input") { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("echo")))
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(
                    systemPrompt = "You are helpful.",
                    tools = listOf(tool),
                    messages = listOf(userMessage("old")),
                ),
                streamFn = unusedStreamFunction,
            ),
        )
        agent.reset()
        assertEquals(1, agent.state.messages.size)
        val initial = systemOf(agent.state.messages[0])
        assertEquals("You are helpful.", (initial.content as dev.pi.ai.PlainTextContent).text)
        assertEquals(listOf("echo"), initial.toolsAdded?.map { it.name })
        assertEquals(emptyList(), agent.state.messages.let { messages -> messages.drop(1) })
        assertFalse(agent.state.isStreaming)
        assertNull(agent.state.errorMessage)
        assertEquals(emptySet<String>(), agent.state.pendingToolCalls)
        assertFalse(agent.hasQueuedMessages())
    }

    @Test
    fun `subscribes to events`() {
        val agent = Agent(AgentOptions(streamFn = unusedStreamFunction))
        var eventCount = 0
        val unsubscribe = agent.subscribe { _, _ -> eventCount++ }
        assertEquals(0, eventCount)
        agent.state.thinkingLevel = ThinkingLevel.Low
        assertEquals(0, eventCount)
        assertEquals(ThinkingLevel.Low, agent.state.thinkingLevel)
        unsubscribe()
        agent.state.thinkingLevel = ThinkingLevel.High
        assertEquals(0, eventCount)
    }

    @Test
    fun `emits full lifecycle events for thrown run failures`() = runTest {
        val agent = Agent(AgentOptions(streamFn = { _, _, _ -> throw IllegalStateException("provider exploded") }))
        val events = mutableListOf<String>()
        var agentEndMessages: List<AgentMessage> = emptyList()
        agent.subscribe { event, _ ->
            events.add(event.type)
            if (event is AgentEvent.AgentEnd) agentEndMessages = event.messages
        }
        agent.prompt("hello")
        assertEquals(1, agentEndMessages.size)
        assertSame(agent.state.messages.last(), agentEndMessages.single())
        assertEquals(
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
            events,
        )
        val lastMessage = asAssistant(agent.state.messages.last())
        assertEquals(StopReason.Error, lastMessage.stopReason)
        assertEquals("provider exploded", lastMessage.errorMessage)
        assertEquals("provider exploded", agent.state.errorMessage)
    }

    @Test
    fun `awaits async subscribers before prompt resolves`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("ok"))))
        }))
        var listenerFinished = false
        agent.subscribe { event, _ ->
            if (event is AgentEvent.AgentEnd) {
                barrier.await()
                listenerFinished = true
            }
        }
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        assertFalse(listenerFinished)
        assertTrue(agent.state.isStreaming)
        barrier.complete(Unit)
        job.join()
        assertTrue(listenerFinished)
        assertFalse(agent.state.isStreaming)
    }

    @Test
    fun `waitForIdle waits for async subscribers`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("ok"))))
        }))
        agent.subscribe { event, _ ->
            if (event is AgentEvent.MessageEnd && event.message.role == "assistant") {
                barrier.await()
            }
        }
        var idleResolved = false
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        val idleJob = launch {
            agent.waitForIdle()
            idleResolved = true
        }
        advanceUntilIdle()
        assertFalse(idleResolved)
        assertTrue(agent.state.isStreaming)
        barrier.complete(Unit)
        job.join()
        idleJob.join()
        assertTrue(idleResolved)
        assertFalse(agent.state.isStreaming)
    }

    @Test
    fun `passes the active abort signal to subscribers`() = runTest {
        var receivedSignal: RunSignal? = null
        val agent = Agent(AgentOptions(streamFn = abortableStreamFn()))
        agent.subscribe { event, signal ->
            if (event is AgentEvent.AgentStart) receivedSignal = signal
        }
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        val signal = assertNotNull(receivedSignal)
        assertFalse(signal.isAborted)
        agent.abort()
        job.join()
        assertTrue(signal.isAborted)
        assertEquals(StopReason.Aborted, asAssistant(agent.state.messages.last()).stopReason)
    }

    @Test
    fun `ignores tool updates after the tool execution settles`() = runTest {
        var delayedUpdate: AgentToolUpdateCallback? = null
        var calls = 0
        val tool = agentTool(name = "echo", parameters = objectSchema()) { _, _, _, onUpdate ->
            delayedUpdate = onUpdate
            AgentToolResult(content = listOf(TextContent("done")))
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = listOf(tool)),
                streamFn = { _, _, _ ->
                    calls++
                    if (calls == 1) {
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("tool-1", "echo", jsonObjectOf())),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("done"))))
                    }
                },
            ),
        )
        val updates = mutableListOf<AgentEvent.ToolExecutionUpdate>()
        agent.subscribe { event, _ ->
            if (event is AgentEvent.ToolExecutionUpdate) updates.add(event)
        }
        agent.prompt("hi")
        assertEquals(0, updates.size)
        val update = assertNotNull(delayedUpdate)
        val job = launch { update.invoke(AgentToolResult(content = listOf(TextContent("late")))) }
        job.join()
        assertEquals(0, updates.size)
    }

    @Test
    fun `awaits listeners in subscription order`() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("ok"))))
        }))
        val order = mutableListOf<String>()
        agent.subscribe { event, _ ->
            if (event is AgentEvent.AgentEnd) {
                order.add("first-start")
                barrier.await()
                order.add("first-end")
            }
        }
        agent.subscribe { event, _ ->
            if (event is AgentEvent.AgentEnd) order.add("second")
        }
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        assertEquals(listOf("first-start"), order)
        barrier.complete(Unit)
        job.join()
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun `updates state with mutators`() {
        val agent = Agent(AgentOptions(streamFn = unusedStreamFunction))
        val customModel = createModel(id = "custom")
        agent.state.model = customModel
        assertSame(customModel, agent.state.model)
        agent.state.thinkingLevel = ThinkingLevel.Medium
        assertEquals(ThinkingLevel.Medium, agent.state.thinkingLevel)
        val tool = agentTool(name = "tool", description = "tool") { _, _, _, _ ->
            AgentToolResult(content = emptyList())
        }
        val tools = mutableListOf(tool)
        agent.state.tools = tools
        tools.clear()
        assertEquals(1, agent.state.tools.size)
        val messages = mutableListOf(userMessage("hi"))
        agent.state.messages = messages
        messages.clear()
        assertEquals(1, agent.state.messages.size)
        agent.state.messages.add(assistantAgentMessage(assistantMessage(listOf(textBlock("done")))))
        assertEquals(2, agent.state.messages.size)
        agent.state.messages = mutableListOf()
        assertEquals(emptyList(), agent.state.messages)
    }

    @Test
    fun `keeps steering and follow-up messages out of state until drained`() {
        val agent = Agent(AgentOptions(streamFn = unusedStreamFunction))
        val steering = userMessage("steering")
        val followUp = userMessage("follow-up")
        agent.steer(steering)
        assertEquals(emptyList(), agent.state.messages)
        assertTrue(agent.hasQueuedMessages())
        assertEquals(listOf(steering), agent.peekQueuedMessages())
        agent.followUp(followUp)
        assertEquals(listOf(steering), agent.peekQueuedMessages())
        agent.clearSteeringQueue()
        assertEquals(listOf(followUp), agent.peekQueuedMessages())
        agent.clearAllQueues()
        assertFalse(agent.hasQueuedMessages())
    }

    @Test
    fun `abort does nothing when idle`() {
        val agent = Agent(AgentOptions(streamFn = unusedStreamFunction))
        agent.abort()
        assertNull(agent.signal)
    }

    @Test
    fun `rejects reset while processing without corrupting the transcript`() = runTest {
        val agent = Agent(AgentOptions(streamFn = abortableStreamFn()))
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        val messagesBefore = agent.state.messages.toList()
        val error = assertFailsWith<IllegalStateException> { agent.reset() }
        assertEquals("Agent is already processing. Wait for completion before resetting.", error.message)
        assertEquals(messagesBefore, agent.state.messages)
        agent.abort()
        job.join()
        assertEquals(listOf("user", "assistant"), messageRoles(agent.state.messages))
    }

    @Test
    fun `throws when prompt is called while streaming`() = runTest {
        val agent = Agent(AgentOptions(streamFn = abortableStreamFn()))
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        val error = assertFailsWith<IllegalStateException> { agent.prompt("again") }
        assertEquals(
            "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion.",
            error.message,
        )
        agent.abort()
        job.join()
        assertEquals(StopReason.Aborted, asAssistant(agent.state.messages.last()).stopReason)
    }

    @Test
    fun `throws when continue is called while streaming`() = runTest {
        val agent = Agent(AgentOptions(streamFn = abortableStreamFn()))
        agent.state.messages = mutableListOf(userMessage("existing"))
        val job = launch { agent.prompt("hello") }
        advanceUntilIdle()
        val error = assertFailsWith<IllegalStateException> { agent.continueRun() }
        assertEquals("Agent is already processing. Wait for completion before continuing.", error.message)
        agent.abort()
        job.join()
    }

    @Test
    fun `continue processes queued follow-up messages after an assistant turn`() = runTest {
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("Processed"))))
        }))
        agent.state.messages = mutableListOf(
            userMessage("Initial"),
            assistantAgentMessage(assistantMessage(listOf(textBlock("Initial response")))),
        )
        agent.followUp(userMessage("Queued follow-up"))
        agent.continueRun()
        assertTrue(agent.state.messages.any { userTextOf(it) == "Queued follow-up" })
        assertEquals("assistant", agent.state.messages.last().role)
    }

    @Test
    fun `continue keeps one-at-a-time steering semantics for assistant-tail fallback`() = runTest {
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                steeringMode = QueueMode.OneAtATime,
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("Processed"))))
                },
            ),
        )
        agent.state.messages = mutableListOf(
            userMessage("Initial"),
            assistantAgentMessage(assistantMessage(listOf(textBlock("Initial response")))),
        )
        agent.steer(userMessage("Steering 1"))
        agent.steer(userMessage("Steering 2"))
        agent.continueRun()
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("Steering 1"))
        assertFalse(requests[0].contains("Steering 2"))
        assertTrue(requests[1].contains("Steering 2"))
    }

    @Test
    fun `continue keeps all steering semantics for assistant-tail fallback`() = runTest {
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                steeringMode = QueueMode.All,
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("Processed"))))
                },
            ),
        )
        agent.state.messages = mutableListOf(
            userMessage("Initial"),
            assistantAgentMessage(assistantMessage(listOf(textBlock("Initial response")))),
        )
        agent.steer(userMessage("Steering 1"))
        agent.steer(userMessage("Steering 2"))
        agent.continueRun()
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("Steering 1"))
        assertTrue(requests[0].contains("Steering 2"))
    }

    @Test
    fun `keeps legacy prepareNextTurn signal callback behavior`() = runTest {
        val tool = agentTool(name = "noop", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("ok")))
        }
        var requestCount = 0
        var sawSignal = false
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = listOf(tool)),
                prepareNextTurn = { signal ->
                    sawSignal = signal != null
                    null
                },
                streamFn = { _, _, _ ->
                    requestCount++
                    if (requestCount == 1) {
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("tool-1", "noop", jsonObjectOf())),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("done"))))
                    }
                },
            ),
        )
        agent.prompt("start")
        assertEquals(2, requestCount)
        assertTrue(sawSignal)
    }

    @Test
    fun `forwards finishTurn through AgentOptions with the active abort signal`() = runTest {
        val tool = agentTool(name = "noop", parameters = objectSchema()) { _, _, _, _ ->
            AgentToolResult(content = listOf(TextContent("tool complete")))
        }
        var requestCount = 0
        var sawSignal = false
        var callbackContextRoles: List<String> = emptyList()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = listOf(tool)),
                finishTurn = { turn, signal ->
                    sawSignal = signal != null
                    callbackContextRoles = turn.context.messages.map { it.role }
                    AgentTurnDecision.End
                },
                streamFn = { _, _, _ ->
                    requestCount++
                    if (requestCount == 1) {
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("tool-1", "noop", jsonObjectOf())),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("should not run"))))
                    }
                },
            ),
        )
        agent.prompt("start")
        assertEquals(1, requestCount)
        assertTrue(sawSignal)
        assertEquals(listOf("system", "user", "assistant", "toolResult"), callbackContextRoles)
    }

    @Test
    fun `rejects queued continuation from empty context without draining queues`() = runTest {
        val agent = Agent(AgentOptions(streamFn = unusedStreamFunction))
        val steering = userMessage("steering")
        val followUp = userMessage("follow-up")
        agent.steer(steering)
        agent.followUp(followUp)
        val error = assertFailsWith<IllegalStateException> { agent.continueRun() }
        assertEquals("No messages to continue from", error.message)
        assertEquals(listOf(steering), agent.peekQueuedMessages())
        agent.clearSteeringQueue()
        assertEquals(listOf(followUp), agent.peekQueuedMessages())
    }

    @Test
    fun `rejects queued continuation from a system-only context without draining queues`() = runTest {
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(messages = listOf(systemMessage("system only"))),
                streamFn = unusedStreamFunction,
            ),
        )
        agent.steer(userMessage("steering"))
        agent.followUp(userMessage("follow-up"))
        val error = assertFailsWith<IllegalStateException> { agent.continueRun() }
        assertEquals("No messages to continue from", error.message)
        assertEquals(1, agent.peekQueuedMessages().size)
        agent.clearSteeringQueue()
        assertEquals("follow-up", userTextOf(agent.peekQueuedMessages().single()))
    }

    @Test
    fun `defers follow-up input on the first continuation request from a user tail`() = runTest {
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(messages = listOf(userMessage("existing user"))),
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.followUp(userMessage("follow-up"))
        agent.continueRun()
        assertEquals(2, requests.size)
        assertFalse(requests[0].contains("follow-up"))
        assertTrue(requests[1].contains("follow-up"))
    }

    @Test
    fun `defers follow-up input on the first continuation request from a toolResult tail`() = runTest {
        val toolResult = StandardAgentMessage(
            ToolResultMessage(
                toolCallId = "call-1",
                toolName = "noop",
                content = listOf(TextContent("done")),
                isError = false,
                timestamp = 1,
            ),
        )
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(
                    messages = listOf(
                        userMessage("existing user"),
                        assistantAgentMessage(
                            assistantMessage(
                                listOf(toolCallBlock("call-1", "noop", jsonObjectOf())),
                                StopReason.ToolUse,
                            ),
                        ),
                        toolResult,
                    ),
                ),
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.followUp(userMessage("follow-up"))
        agent.continueRun()
        assertEquals(2, requests.size)
        assertFalse(requests[0].contains("follow-up"))
        assertTrue(requests[1].contains("follow-up"))
    }

    @Test
    fun `polls one-at-a-time steering at continuation startup`() = runTest {
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(messages = listOf(userMessage("existing"))),
                steeringMode = QueueMode.OneAtATime,
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.steer(userMessage("first"))
        agent.steer(userMessage("second"))
        agent.continueRun()
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("first"))
        assertFalse(requests[0].contains("second"))
        assertTrue(requests[1].contains("second"))
    }

    @Test
    fun `polls all steering at continuation startup`() = runTest {
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(messages = listOf(userMessage("existing"))),
                steeringMode = QueueMode.All,
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.steer(userMessage("first"))
        agent.steer(userMessage("second"))
        agent.continueRun()
        assertEquals(1, requests.size)
        assertTrue(requests[0].contains("first"))
        assertTrue(requests[0].contains("second"))
    }

    @Test
    fun `keeps steering ahead of follow-up from a non-assistant continuation tail`() = runTest {
        val requests = mutableListOf<List<String>>()
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(messages = listOf(userMessage("existing"))),
                streamFn = { _, context, _ ->
                    requests.add(context.messages.mapNotNull { userTextOf(it.asAgentMessage()) })
                    doneStream(assistantMessage(listOf(textBlock("done"))))
                },
            ),
        )
        agent.steer(userMessage("steering"))
        agent.followUp(userMessage("follow-up"))
        agent.continueRun()
        assertEquals(2, requests.size)
        assertTrue(requests[0].contains("steering"))
        assertFalse(requests[0].contains("follow-up"))
        assertTrue(requests[1].contains("follow-up"))
    }

    @Test
    fun `keeps queues on an error response even when finishTurn requests continuation`() = runTest {
        val agent = Agent(
            AgentOptions(
                finishTurn = { _, _ -> AgentTurnDecision.Continue },
                streamFn = { _, _, _ ->
                    val error = assistantMessage(emptyList(), StopReason.Error)
                    error.errorMessage = "error"
                    errorStream(error)
                },
            ),
        )
        val steering = userMessage("steering")
        val followUp = userMessage("follow-up")
        agent.followUp(followUp)
        agent.subscribe { event, _ ->
            if (event is AgentEvent.MessageEnd && event.message.role == "assistant") {
                agent.steer(steering)
            }
        }
        agent.prompt("start")
        assertEquals(listOf(steering), agent.peekQueuedMessages())
        agent.clearSteeringQueue()
        assertEquals(listOf(followUp), agent.peekQueuedMessages())
    }

    @Test
    fun `keeps queues when finishTurn ends the run`() = runTest {
        val agent = Agent(
            AgentOptions(
                finishTurn = { _, _ -> AgentTurnDecision.End },
                streamFn = { _, _, _ -> doneStream(assistantMessage(listOf(textBlock("done")))) },
            ),
        )
        val steering = userMessage("steering")
        val followUp = userMessage("follow-up")
        agent.followUp(followUp)
        agent.subscribe { event, _ ->
            if (event is AgentEvent.MessageEnd && event.message.role == "assistant") {
                agent.steer(steering)
            }
        }
        agent.prompt("start")
        assertEquals(listOf(steering), agent.peekQueuedMessages())
        agent.clearSteeringQueue()
        assertEquals(listOf(followUp), agent.peekQueuedMessages())
    }

    @Test
    fun `forwards provider stream event observers through AgentOptions`() = runTest {
        val providerEvents = mutableListOf<dev.pi.ai.JsonValue>()
        val agent = Agent(
            AgentOptions(
                onProviderStreamEvent = { data, _ -> providerEvents.add(data) },
                streamFn = { model, _, options ->
                    options?.stream?.onProviderStreamEvent?.invoke(
                        kotlinx.serialization.json.buildJsonObject {
                            put("request_cost", JsonPrimitive(0.01))
                        },
                        model,
                    )
                    doneStream(assistantMessage(listOf(textBlock("ok"))))
                },
            ),
        )
        agent.prompt("hello")
        assertEquals(1, providerEvents.size)
        assertEquals<dev.pi.ai.JsonValue?>(
            JsonPrimitive(0.01),
            (providerEvents.single() as kotlinx.serialization.json.JsonObject)["request_cost"],
        )
    }

    @Test
    fun `forwards sessionId to stream function options`() = runTest {
        var receivedSessionId: String? = null
        val agent = Agent(
            AgentOptions(
                sessionId = "session-abc",
                streamFn = { _, _, options ->
                    receivedSessionId = options?.stream?.sessionId
                    doneStream(assistantMessage(listOf(textBlock("ok"))))
                },
            ),
        )
        agent.prompt("hello")
        assertEquals("session-abc", receivedSessionId)
        agent.sessionId = "session-def"
        agent.prompt("hello again")
        assertEquals("session-def", receivedSessionId)
    }

    @Test
    fun `attributes requests to pi by default and merges a configured identity over it`() = runTest {
        val received = mutableListOf<ClientIdentity?>()
        val agent = Agent(
            AgentOptions(
                streamFn = { _, _, options ->
                    received.add(options?.stream?.identity)
                    doneStream(assistantMessage(listOf(textBlock("ok"))))
                },
            ),
        )
        agent.prompt("hello")
        agent.identity = ClientIdentity(userAgent = "short", appName = "acme", appUrl = "https://acme.example")
        agent.prompt("hello again")
        agent.identity = ClientIdentity(userAgent = "short", appName = null, appUrl = null)
        agent.prompt("and again")
        assertEquals<List<ClientIdentity?>>(
            listOf(
                ClientIdentity(userAgent = null, appName = "pi", appUrl = "https://pi.dev"),
                ClientIdentity(userAgent = "short", appName = "acme", appUrl = "https://acme.example"),
                ClientIdentity(userAgent = "short", appName = "pi", appUrl = "https://pi.dev"),
            ),
            received,
        )
    }

    @Test
    fun `message_end events carry the objects stored in state`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            AgentToolResult(content = listOf(TextContent((args["value"] as JsonPrimitive).content)))
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = listOf(tool)),
                streamFn = { _, _, _ ->
                    if (toolCalls == 0) {
                        toolCalls++
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("tool-1", "echo", jsonObjectOf("value" to JsonPrimitive("x")))),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("done"))))
                    }
                },
            ),
        )
        val messageEnds = mutableListOf<AgentMessage>()
        val turnToolResults = mutableListOf<List<AgentMessage>>()
        agent.subscribe { event, _ ->
            if (event is AgentEvent.MessageEnd) messageEnds.add(event.message)
            if (event is AgentEvent.TurnEnd) turnToolResults.add(event.toolResults)
        }
        agent.prompt("hi")
        for (message in messageEnds) {
            assertTrue(agent.state.messages.any { it === message }, "message_end object must be stored in state")
        }
        for (toolResult in turnToolResults.first()) {
            assertTrue(agent.state.messages.any { it === toolResult }, "turn_end toolResult must be stored in state")
        }
        val assistantEnds = messageEnds.filter { it.role == "assistant" }
        assertSame(assistantEnds.last(), agent.state.messages.last())
    }

    private var toolCalls = 0

    @Test
    fun `tracks pending tool calls while tools execute`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val tool = agentTool(name = "calc", parameters = valueSchema()) { _, _, _, _ ->
            gate.await()
            AgentToolResult(content = listOf(TextContent("56088")))
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = listOf(tool)),
                streamFn = { _, _, _ ->
                    if (toolCalls == 0) {
                        toolCalls++
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("calc-1", "calc", jsonObjectOf("value" to JsonPrimitive("123*456")))),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("done"))))
                    }
                },
            ),
        )
        var pendingAtStart: Set<String>? = null
        var pendingAtEnd: Set<String>? = null
        agent.subscribe { event, _ ->
            when (event) {
                is AgentEvent.ToolExecutionStart -> pendingAtStart = agent.state.pendingToolCalls
                is AgentEvent.ToolExecutionEnd -> pendingAtEnd = agent.state.pendingToolCalls
                else -> Unit
            }
        }
        val job = launch { agent.prompt("hi") }
        advanceUntilIdle()
        assertEquals(setOf("calc-1"), pendingAtStart)
        gate.complete(Unit)
        job.join()
        assertEquals(emptySet(), pendingAtEnd)
        assertEquals(emptySet(), agent.state.pendingToolCalls)
    }

    @Test
    fun `prompt with images builds a content block user message`() = runTest {
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            doneStream(assistantMessage(listOf(textBlock("ok"))))
        }))
        agent.prompt("look", listOf(ImageContent(data = "aGVsbG8=", mimeType = "image/png")))
        val raw = agent.state.messages.first().asLlmMessage() as UserMessage
        val blocks = (raw.content as BlockContent).blocks
        assertEquals(2, blocks.size)
        assertEquals("look", (blocks[0] as TextContent).text)
        assertTrue(blocks[1] is ImageContent)
    }

    @Test
    fun `default convertToLlm keeps only standard message roles`() = runTest {
        var captured: List<dev.pi.ai.Message> = emptyList()
        val agent = Agent(
            AgentOptions(
                streamFn = { _, context, _ ->
                    captured = context.messages
                    doneStream(assistantMessage(listOf(textBlock("ok"))))
                },
            ),
        )
        agent.state.messages = mutableListOf(
            NotificationMessage("ui only"),
            userMessage("hello"),
        )
        agent.continueRun()
        assertEquals(listOf("user"), captured.map { it.role })
    }

    @Test
    fun `does not seed a system message when the transcript already starts with one`() {
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(
                    systemPrompt = "seed prompt",
                    messages = listOf(systemMessage("explicit prompt")),
                ),
                streamFn = unusedStreamFunction,
            ),
        )
        assertEquals(1, agent.state.messages.size)
        assertEquals("explicit prompt", (systemOf(agent.state.messages.single()).content as dev.pi.ai.PlainTextContent).text)
    }
}
