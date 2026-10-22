package dev.pi.agent

import dev.pi.ai.StreamOptions
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentE2eTest {

    private fun contextTexts(context: List<dev.pi.ai.Message>): List<String> = context.mapNotNull { raw ->
        when (raw) {
            is dev.pi.ai.UserMessage -> when (val content = raw.content) {
                is dev.pi.ai.PlainTextContent -> content.text
                is dev.pi.ai.BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
            }

            is dev.pi.ai.AssistantMessage -> raw.content.filterIsInstance<TextContent>().joinToString("") { it.text }
            else -> null
        }
    }

    @Test
    fun `handles a basic text prompt`() = runTest {
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are a helpful assistant.", model = createModel()),
                streamFn = { _, _, _ -> doneStream(assistantMessage(listOf(textBlock("4")))) },
            ),
        )
        agent.prompt("What is 2+2?")
        assertEquals(listOf("system", "user", "assistant"), messageRoles(agent.state.messages))
        assertEquals("4", (asAssistant(agent.state.messages.last()).content.single() as TextContent).text)
        assertFalse(agent.state.isStreaming)
        assertEquals(emptySet(), agent.state.pendingToolCalls)
    }

    @Test
    fun `executes tools and tracks pending tool calls`() = runTest {
        val tool = agentTool(name = "calculate", parameters = valueSchema()) { _, args, _, _ ->
            val expression = (args["value"] as JsonPrimitive).content
            AgentToolResult(content = listOf(TextContent("$expression = 56088")))
        }
        var calls = 0
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are a helpful assistant.", tools = listOf(tool)),
                streamFn = { _, _, _ ->
                    calls++
                    if (calls == 1) {
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("calc-1", "calculate", jsonObjectOf("value" to JsonPrimitive("123*456")))),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("The answer is 56088"))))
                    }
                },
            ),
        )
        val pendingSnapshots = mutableListOf<Set<String>>()
        agent.subscribe { event, _ ->
            when (event) {
                is AgentEvent.ToolExecutionStart -> pendingSnapshots.add(agent.state.pendingToolCalls)
                is AgentEvent.ToolExecutionEnd -> pendingSnapshots.add(agent.state.pendingToolCalls)
                else -> Unit
            }
        }
        agent.prompt("What is 123*456?")
        assertEquals(setOf("calc-1"), pendingSnapshots.first())
        assertEquals(emptySet(), pendingSnapshots.last())
        assertEquals(emptySet(), agent.state.pendingToolCalls)
        val toolResult = agent.state.messages.mapNotNull { it.asToolResultMessage() }.single()
        assertEquals("123*456 = 56088", textOf(toolResult.content))
        assertEquals("The answer is 56088", textOf(asAssistant(agent.state.messages.last()).content))
    }

    @Test
    fun `emits lifecycle updates while streaming`() = runTest {
        val agent = Agent(AgentOptions(streamFn = { _, _, _ -> streamingTextStream("Hello") }))
        val eventTypes = mutableListOf<String>()
        agent.subscribe { event, _ -> eventTypes.add(event.type) }
        agent.prompt("hi")
        assertTrue(eventTypes.first() == "agent_start")
        assertTrue(eventTypes.last() == "agent_end")
        assertTrue(eventTypes.contains("message_update"))
        assertTrue(eventTypes.indexOf("message_update") < eventTypes.lastIndexOf("message_end"))
        assertTrue(eventTypes.lastIndexOf("message_end") < eventTypes.indexOf("turn_end"))
        assertTrue(eventTypes.indexOf("turn_end") < eventTypes.indexOf("agent_end"))
    }

    @Test
    fun `maintains context across multiple turns`() = runTest {
        val requests = mutableListOf<List<String>>()
        var turn = 0
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are a helpful assistant."),
                streamFn = { _, context, _ ->
                    requests.add(contextTexts(context.messages))
                    turn++
                    doneStream(assistantMessage(listOf(textBlock(if (turn == 1) "Hi Alice" else "You are Alice"))))
                },
            ),
        )
        agent.prompt("My name is Alice")
        agent.prompt("What is my name?")
        assertEquals(5, agent.state.messages.size)
        assertEquals(2, requests.size)
        assertTrue(requests[1].any { it.contains("Alice") })
    }

    @Test
    fun `preserves thinking content blocks`() = runTest {
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            doneStream(
                assistantMessage(
                    listOf(ThinkingContent("step by step"), TextContent("4")),
                ),
            )
        }))
        agent.prompt("What is 2+2?")
        val content = asAssistant(agent.state.messages.last()).content
        assertEquals(2, content.size)
        assertEquals("step by step", (content[0] as ThinkingContent).thinking)
        assertEquals("4", (content[1] as TextContent).text)
    }

    @Test
    fun `continue from a user message`() = runTest {
        var calls = 0
        val agent = Agent(AgentOptions(streamFn = { _, _, _ ->
            calls++
            doneStream(assistantMessage(listOf(textBlock("HELLO WORLD"))))
        }))
        agent.state.messages = mutableListOf(userMessage("hello"))
        agent.continueRun()
        assertEquals(1, calls)
        assertEquals(listOf("user", "assistant"), messageRoles(agent.state.messages))
        assertEquals("HELLO WORLD", textOf(asAssistant(agent.state.messages.last()).content))
    }

    @Test
    fun `continue from a tool result`() = runTest {
        val tool = agentTool(name = "echo", parameters = valueSchema()) { _, args, _, _ ->
            AgentToolResult(content = listOf(TextContent("8")))
        }
        var calls = 0
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(tools = listOf(tool)),
                streamFn = { _, context, _ ->
                    calls++
                    if (calls == 1) {
                        doneStream(
                            assistantMessage(
                                listOf(toolCallBlock("call-1", "echo", jsonObjectOf("value" to JsonPrimitive("4+4")))),
                                StopReason.ToolUse,
                            ),
                        )
                    } else {
                        doneStream(assistantMessage(listOf(textBlock("The result is 8"))))
                    }
                },
            ),
        )
        agent.prompt("run the tool")
        assertEquals(2, calls)
        val roles = messageRoles(agent.state.messages)
        assertTrue(roles.last() == "assistant")
        assertTrue(roles.contains("toolResult"))
        assertEquals("The result is 8", textOf(asAssistant(agent.state.messages.last()).content))
    }
}
