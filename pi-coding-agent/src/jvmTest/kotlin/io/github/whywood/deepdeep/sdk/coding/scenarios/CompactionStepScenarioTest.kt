package io.github.whywood.deepdeep.sdk.coding.scenarios

import io.github.whywood.deepdeep.sdk.ai.AssistantMessage
import io.github.whywood.deepdeep.sdk.ai.BlockContent
import io.github.whywood.deepdeep.sdk.ai.Content
import io.github.whywood.deepdeep.sdk.ai.Message
import io.github.whywood.deepdeep.sdk.ai.MessageContent
import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.PlainTextContent
import io.github.whywood.deepdeep.sdk.ai.SimpleStreamOptions
import io.github.whywood.deepdeep.sdk.ai.StopReason
import io.github.whywood.deepdeep.sdk.ai.SystemMessage
import io.github.whywood.deepdeep.sdk.ai.TextContent
import io.github.whywood.deepdeep.sdk.ai.ToolResultMessage
import io.github.whywood.deepdeep.sdk.ai.TranscriptContext
import io.github.whywood.deepdeep.sdk.ai.UserMessage
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderHandle
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderState
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseFactory
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxToolCall
import io.github.whywood.deepdeep.sdk.ai.utils.getSystemMessageText
import io.github.whywood.deepdeep.sdk.coding.AgentSessionOptions
import io.github.whywood.deepdeep.sdk.coding.createAgentSession
import io.github.whywood.deepdeep.sdk.coding.session.CompactionEntry
import io.github.whywood.deepdeep.sdk.coding.session.SessionEvent
import io.github.whywood.deepdeep.sdk.coding.tools.CodingTool
import io.github.whywood.deepdeep.sdk.coding.tools.textResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ORIGINAL_PROMPT = "ORIGINAL-PROMPT-MARKER run the echo tool"
private const val TOOL_OUTPUT_PREFIX = "TOOL-OUTPUT-MARKER "
private const val SUMMARY_TEXT = "SUMMARY-OF-THE-SUMMARIZED-PREFIX"

private class StepResponses : FauxResponseFactory {
    val requests = mutableListOf<List<Message>>()
    val summaries = mutableListOf<String>()

    override suspend fun produce(
        context: TranscriptContext,
        options: SimpleStreamOptions?,
        state: FauxProviderState,
        model: Model,
    ): AssistantMessage {
        val systemText = (context.messages.firstOrNull() as? SystemMessage)?.let(::getSystemMessageText).orEmpty()
        if (systemText.contains("context summarization assistant")) {
            summaries.add(SUMMARY_TEXT)
            return fauxAssistantMessage(SUMMARY_TEXT)
        }
        requests.add(context.messages.toList())
        return if (requests.size == 1) {
            fauxAssistantMessage(
                content = listOf(fauxToolCall("echo", buildJsonObject { put("text", "hello") })),
                stopReason = StopReason.ToolUse,
            )
        } else {
            fauxAssistantMessage("final answer")
        }
    }
}

private fun messageText(message: Message): String = when (message) {
    is SystemMessage -> getSystemMessageText(message)
    is UserMessage -> contentText(message.content)
    is AssistantMessage -> blocksText(message.content)
    is ToolResultMessage -> blocksText(message.content)
}

private fun blocksText(blocks: List<Content>): String = blocks.filterIsInstance<TextContent>().joinToString("") { it.text }

private fun contentText(content: MessageContent): String = when (content) {
    is PlainTextContent -> content.text
    is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
}

private fun texts(messages: List<Message>): List<String> = messages.map(::messageText)

private fun echoTool(output: String): CodingTool = CodingTool(
    name = "echo",
    description = "Echo a fixed output",
    parameters = buildJsonObject { put("type", "object") },
    label = "Echo",
    execute = { _, _, _, _ -> textResult(output) },
)

private fun settings(enabled: Boolean, reserveTokens: Long): String =
    """{"compaction":{"enabled":$enabled,"reserveTokens":$reserveTokens,"keepRecentTokens":1}}"""

private suspend fun newSession(fs: FileSystem, faux: FauxProviderHandle, toolOutput: String) = createAgentSession(
    AgentSessionOptions(
        cwd = ScenarioSupport.CWD.toPath(),
        agentDir = ScenarioSupport.AGENT_DIR.toPath(),
        transport = NoNetworkTransport,
        fs = fs,
        model = "faux/faux-1",
        tools = listOf(echoTool(toolOutput)),
        providers = listOf(faux.provider),
    ),
)

class CompactionStepScenarioTest {

    @Test
    fun compactsBetweenToolBatchAndNextTurnOnceThresholdIsCrossed() = runBlocking {
        withTimeout(40_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, settings(enabled = true, reserveTokens = 500))
            val faux = ScenarioSupport.faux(contextWindow = 20_000, maxTokens = 1000)
            val responses = StepResponses()
            faux.setResponses(List(6) { FauxResponseStep.Scripted(responses) })
            val session = newSession(fs, faux, TOOL_OUTPUT_PREFIX + "x".repeat(200_000))
            val captured = mutableListOf<SessionEvent>()
            val settled = CompletableDeferred<Unit>()
            session.subscribe { event ->
                captured.add(event)
                if (event is SessionEvent.Settled) settled.complete(Unit)
            }
            try {
                session.prompt(ORIGINAL_PROMPT)
                withTimeout(10_000) { settled.await() }
                assertEquals("final answer", session.getLastAssistantText())
                assertEquals(2, responses.requests.size)
                assertEquals(1, responses.summaries.size)
                assertEquals(3, faux.state.callCount)

                val compactionEntries = session.sessionManager.getEntries().filterIsInstance<CompactionEntry>()
                assertEquals(1, compactionEntries.size)
                assertTrue(compactionEntries.single().summary.contains(SUMMARY_TEXT))

                val firstRequest = texts(responses.requests[0])
                assertTrue(firstRequest.any { it.contains("ORIGINAL-PROMPT-MARKER") })
                val nextRequest = texts(responses.requests[1])
                assertFalse(nextRequest.any { it.contains("ORIGINAL-PROMPT-MARKER") })
                assertTrue(nextRequest.any { it.contains(SUMMARY_TEXT) })
                assertTrue(nextRequest.any { it.contains("TOOL-OUTPUT-MARKER") })

                val events = captured.toList()
                val started = events.indexOfFirst { it is SessionEvent.CompactionStarted }
                val finished = events.indexOfFirst { it is SessionEvent.CompactionFinished }
                assertTrue(started > 0 && finished > started)
                assertEquals("turn_end", agentEventTypes(events.take(started)).last())
                assertEquals("turn_start", agentEventTypes(events.drop(finished + 1)).first())
                assertFalse(events.any { it is SessionEvent.CompactionFailed })
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun doesNotCompactBetweenTurnsBelowThreshold() = runBlocking {
        withTimeout(40_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, settings(enabled = true, reserveTokens = 500))
            val faux = ScenarioSupport.faux(contextWindow = 200_000, maxTokens = 1000)
            val responses = StepResponses()
            faux.setResponses(List(6) { FauxResponseStep.Scripted(responses) })
            val session = newSession(fs, faux, TOOL_OUTPUT_PREFIX + "x".repeat(200_000))
            try {
                session.prompt(ORIGINAL_PROMPT)
                assertEquals("final answer", session.getLastAssistantText())
                assertEquals(2, responses.requests.size)
                assertEquals(0, responses.summaries.size)
                assertEquals(2, faux.state.callCount)
                assertEquals(0, session.sessionManager.getEntries().filterIsInstance<CompactionEntry>().size)
                val nextRequest = texts(responses.requests[1])
                assertTrue(nextRequest.any { it.contains("ORIGINAL-PROMPT-MARKER") })
                assertTrue(nextRequest.any { it.contains("TOOL-OUTPUT-MARKER") })
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun doesNotCompactBetweenTurnsWhenCompactionIsDisabled() = runBlocking {
        withTimeout(40_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, settings(enabled = false, reserveTokens = 0))
            val faux = ScenarioSupport.faux(contextWindow = 20_000, maxTokens = 1000)
            val responses = StepResponses()
            faux.setResponses(List(6) { FauxResponseStep.Scripted(responses) })
            val session = newSession(fs, faux, TOOL_OUTPUT_PREFIX + "x".repeat(200_000))
            val compactionEvents = mutableListOf<SessionEvent>()
            val settled = CompletableDeferred<Unit>()
            session.subscribe { event ->
                if (event is SessionEvent.CompactionStarted || event is SessionEvent.CompactionFailed) {
                    compactionEvents.add(event)
                }
                if (event is SessionEvent.Settled) settled.complete(Unit)
            }
            try {
                session.prompt(ORIGINAL_PROMPT)
                withTimeout(10_000) { settled.await() }
                assertEquals("final answer", session.getLastAssistantText())
                assertEquals(2, responses.requests.size)
                assertEquals(0, responses.summaries.size)
                assertEquals(2, faux.state.callCount)
                assertEquals(0, session.sessionManager.getEntries().filterIsInstance<CompactionEntry>().size)
                assertTrue(compactionEvents.isEmpty())
                val nextRequest = texts(responses.requests[1])
                assertTrue(nextRequest.any { it.contains("ORIGINAL-PROMPT-MARKER") })
            } finally {
                session.close()
            }
        }
    }
}
