package dev.pi.agent

import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolCall
import dev.pi.ai.TranscriptContext
import dev.pi.ai.utils.getCurrentTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DynamicToolsTest {

    private fun textResult(text: String): AgentToolResult =
        AgentToolResult(content = listOf(TextContent(text)))

    private fun declarationNames(transcript: TranscriptContext): List<String> =
        getCurrentTools(transcript.messages).map { it.name }

    private fun toolPatches(events: List<AgentEvent>): List<SystemMessage> =
        events.filterIsInstance<AgentEvent.MessageEnd>()
            .mapNotNull { it.message.asLlmMessage() as? SystemMessage }
            .filter { !it.toolsAdded.isNullOrEmpty() || !it.toolsRemoved.isNullOrEmpty() }

    private fun addedNames(patch: SystemMessage): List<String> = patch.toolsAdded.orEmpty().map { it.name }

    private fun removedNames(patch: SystemMessage): List<String> =
        patch.toolsRemoved.orEmpty().map { it.name }

    private fun messageText(content: List<dev.pi.ai.Content>): String =
        content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun toolCallAssistant(id: String, name: String): dev.pi.ai.AssistantMessage =
        assistantMessage(listOf(toolCallBlock(id, name, jsonObjectOf())))

    private fun finalAssistant(text: String = "done"): dev.pi.ai.AssistantMessage =
        assistantMessage(listOf(textBlock(text)))

    private fun eventIndex(events: List<AgentEvent>, predicate: (AgentEvent) -> Boolean): Int =
        events.indexOfFirst(predicate)

    @Test
    fun agentExposesToolsAddedDuringRunToTheNextRequest() = runTest {
        val executed = mutableListOf<String>()
        val extra = agentTool(
            name = "mcp_extra",
            parameters = objectSchema(),
            execute = { _, _, _, _ ->
                executed.add("mcp_extra")
                textResult("extra result")
            },
        )
        lateinit var agent: Agent
        val search = agentTool(
            name = "tool_search",
            parameters = objectSchema(),
            execute = { _, _, _, _ ->
                agent.state.tools.add(extra)
                textResult("found mcp_extra")
            },
        )
        val captured = mutableListOf<List<String>>()
        var callIndex = 0
        val streamFn: StreamFn = { _, context, _ ->
            captured.add(declarationNames(context))
            when (callIndex++) {
                0 -> doneStream(toolCallAssistant("c1", "tool_search"))
                1 -> doneStream(toolCallAssistant("c2", "mcp_extra"))
                else -> doneStream(finalAssistant())
            }
        }
        agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(model = createModel(), tools = listOf(search)),
                streamFn = streamFn,
            ),
        )
        val events = mutableListOf<AgentEvent>()
        agent.subscribe { event, _ -> events.add(event) }

        agent.prompt("start")

        assertEquals(listOf("tool_search"), captured[0])
        assertEquals(listOf("tool_search", "mcp_extra"), captured[1])
        assertEquals(listOf("tool_search", "mcp_extra"), captured[2])
        assertEquals(listOf("mcp_extra"), executed)

        val patches = toolPatches(events)
        assertEquals(1, patches.size)
        assertEquals(listOf("mcp_extra"), addedNames(patches.single()))
        assertTrue(removedNames(patches.single()).isEmpty())

        val patchStart = eventIndex(events) { event ->
            event is AgentEvent.MessageStart && (event.message.asLlmMessage() as? SystemMessage)?.toolsAdded?.isNotEmpty() == true
        }
        val patchEnd = eventIndex(events) { event ->
            event is AgentEvent.MessageEnd && (event.message.asLlmMessage() as? SystemMessage)?.toolsAdded?.isNotEmpty() == true
        }
        val nextAssistantEnd = eventIndex(events) { event ->
            event is AgentEvent.MessageEnd && event.message.asLlmMessage() is dev.pi.ai.AssistantMessage &&
                (event.message.asLlmMessage() as dev.pi.ai.AssistantMessage).content.any { it is ToolCall && it.id == "c2" }
        }
        assertTrue(patchStart >= 0 && patchEnd > patchStart)
        assertTrue(nextAssistantEnd > patchEnd)

        val transcriptPatches = agent.state.messages
            .drop(1)
            .mapNotNull { it.asLlmMessage() as? SystemMessage }
            .filter { !it.toolsAdded.isNullOrEmpty() || !it.toolsRemoved.isNullOrEmpty() }
        assertEquals(1, transcriptPatches.size)
        assertEquals(listOf("mcp_extra"), addedNames(transcriptPatches.single()))
        assertEquals(listOf("tool_search", "mcp_extra"), agent.state.tools.map { it.name })
    }

    @Test
    fun agentHidesToolsRemovedDuringRunFromTheNextRequest() = runTest {
        lateinit var agent: Agent
        val alpha = agentTool(name = "alpha", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("alpha") })
        val beta = agentTool(name = "beta", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("beta") })
        val search = agentTool(
            name = "tool_search",
            parameters = objectSchema(),
            execute = { _, _, _, _ ->
                agent.state.tools.removeAll { tool -> tool.name == "beta" }
                textResult("removed beta")
            },
        )
        val captured = mutableListOf<List<String>>()
        var callIndex = 0
        val streamFn: StreamFn = { _, context, _ ->
            captured.add(declarationNames(context))
            when (callIndex++) {
                0 -> doneStream(toolCallAssistant("c1", "tool_search"))
                1 -> doneStream(toolCallAssistant("c2", "beta"))
                else -> doneStream(finalAssistant())
            }
        }
        agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(
                    model = createModel(),
                    tools = listOf(search, alpha, beta),
                ),
                streamFn = streamFn,
            ),
        )
        val events = mutableListOf<AgentEvent>()
        agent.subscribe { event, _ -> events.add(event) }

        agent.prompt("start")

        assertEquals(listOf("tool_search", "alpha", "beta"), captured[0])
        assertEquals(listOf("tool_search", "alpha"), captured[1])
        assertEquals(listOf("tool_search", "alpha"), captured[2])

        val patches = toolPatches(events)
        assertEquals(1, patches.size)
        assertEquals(listOf("beta"), removedNames(patches.single()))
        assertTrue(addedNames(patches.single()).isEmpty())

        val toolResults = events.filterIsInstance<AgentEvent.MessageEnd>()
            .mapNotNull { it.message.asLlmMessage() as? dev.pi.ai.ToolResultMessage }
        val betaResult = toolResults.single { result -> messageText(result.content).contains("not found") }
        assertEquals("beta", betaResult.toolName)
        assertTrue(betaResult.isError)
        assertEquals(listOf("tool_search", "alpha"), agent.state.tools.map { it.name })
    }

    @Test
    fun agentOptionsGetActiveToolsOverridesTheStateSnapshot() = runTest {
        val alpha = agentTool(name = "alpha", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("alpha") })
        val beta = agentTool(name = "beta", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("beta") })
        val captured = mutableListOf<List<String>>()
        val streamFn: StreamFn = { _, context, _ ->
            captured.add(declarationNames(context))
            doneStream(finalAssistant())
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(model = createModel(), tools = listOf(alpha)),
                getActiveTools = { listOf(alpha, beta) },
                streamFn = streamFn,
            ),
        )
        val events = mutableListOf<AgentEvent>()
        agent.subscribe { event, _ -> events.add(event) }

        agent.prompt("start")

        assertEquals(listOf(listOf("alpha", "beta")), captured)
        val patches = toolPatches(events)
        assertEquals(1, patches.size)
        assertEquals(listOf("beta"), addedNames(patches.single()))
    }

    @Test
    fun agentLoopConfigGetActiveToolsRefreshesEveryRequest() = runTest {
        val alpha = agentTool(name = "alpha", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("alpha") })
        val beta = agentTool(name = "beta", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("beta") })
        var activeTools = listOf(alpha)
        val captured = mutableListOf<List<String>>()
        var callIndex = 0
        val streamFn: StreamFn = { _, context, _ ->
            captured.add(declarationNames(context))
            when (callIndex++) {
                0 -> {
                    activeTools = listOf(alpha, beta)
                    doneStream(toolCallAssistant("c1", "alpha"))
                }

                1 -> {
                    activeTools = listOf(alpha)
                    doneStream(toolCallAssistant("c2", "alpha"))
                }

                else -> doneStream(finalAssistant())
            }
        }
        val initialSystem = systemMessage(content = "", toolsAdded = listOf(alpha.toTool()))
        val context = AgentContext(messages = listOf(initialSystem, userMessage("start")), tools = listOf(alpha))
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
            getActiveTools = { activeTools },
        )

        val result = runLoop(emptyList(), context, config, streamFn = streamFn)

        assertEquals(listOf("alpha"), captured[0])
        assertEquals(listOf("alpha", "beta"), captured[1])
        assertEquals(listOf("alpha"), captured[2])
        assertEquals(listOf(alpha), context.tools)

        val patches = toolPatches(result.events)
        assertEquals(2, patches.size)
        assertEquals(listOf("beta"), addedNames(patches[0]))
        assertTrue(removedNames(patches[0]).isEmpty())
        assertTrue(addedNames(patches[1]).isEmpty())
        assertEquals(listOf("beta"), removedNames(patches[1]))
        assertEquals(
            2,
            result.messages.mapNotNull { it.asLlmMessage() as? SystemMessage }
                .count { !it.toolsAdded.isNullOrEmpty() || !it.toolsRemoved.isNullOrEmpty() },
        )
    }

    @Test
    fun missingGetActiveToolsKeepsStaticToolDeclarations() = runTest {
        val alpha = agentTool(name = "alpha", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("alpha") })
        val captured = mutableListOf<List<String>>()
        var callIndex = 0
        val streamFn: StreamFn = { _, context, _ ->
            captured.add(declarationNames(context))
            when (callIndex++) {
                0 -> doneStream(toolCallAssistant("c1", "alpha"))
                else -> doneStream(finalAssistant())
            }
        }
        val initialSystem = systemMessage(
            content = "",
            toolsAdded = listOf(alpha.toTool()),
        )
        val context = AgentContext(messages = listOf(initialSystem, userMessage("start")), tools = listOf(alpha))
        val config = AgentLoopConfig(
            model = createModel(),
            convertToLlm = identityConvertToLlm,
        )

        val result = runLoop(emptyList(), context, config, streamFn = streamFn)

        assertEquals(listOf(listOf("alpha"), listOf("alpha")), captured)
        assertTrue(toolPatches(result.events).isEmpty())
        assertEquals(listOf(alpha), context.tools)
        assertEquals(
            listOf("assistant", "toolResult", "assistant"),
            result.messageEnds().map { it.role },
        )
        assertIs<dev.pi.ai.AssistantMessage>(result.messages.last().asLlmMessage())
    }

    @Test
    fun agentWithoutToolChangesKeepsTheTranscriptUnpatched() = runTest {
        val alpha = agentTool(name = "alpha", parameters = objectSchema(), execute = { _, _, _, _ -> textResult("alpha") })
        val captured = mutableListOf<List<String>>()
        val streamFn: StreamFn = { _, context, _ ->
            captured.add(declarationNames(context))
            doneStream(finalAssistant())
        }
        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(model = createModel(), tools = listOf(alpha)),
                streamFn = streamFn,
            ),
        )
        val events = mutableListOf<AgentEvent>()
        agent.subscribe { event, _ -> events.add(event) }

        agent.prompt("hello")

        assertEquals(listOf(listOf("alpha")), captured)
        assertTrue(toolPatches(events).isEmpty())
    }
}
