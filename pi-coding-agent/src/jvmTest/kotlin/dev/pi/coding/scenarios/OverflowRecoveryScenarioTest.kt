package dev.pi.coding.scenarios

import dev.pi.ai.Model
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.TranscriptContext
import dev.pi.ai.providers.FauxProviderHandle
import dev.pi.ai.providers.FauxProviderState
import dev.pi.ai.providers.FauxResponseFactory
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.SessionEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverflowRecoveryScenarioTest {

    private val overflowError: FauxResponseStep = FauxResponseStep.Fixed(
        fauxAssistantMessage(
            content = "",
            stopReason = StopReason.Error,
            errorMessage = "This model's maximum context length is 200 tokens.",
        ),
    )

    private fun settings(): String =
        """{"compaction":{"enabled":true,"reserveTokens":100,"keepRecentTokens":1},""" +
            """"retry":{"enabled":true,"maxRetries":3,"baseDelayMs":200,"maxAgentDelayMs":5000}}"""

    private fun fauxWithContextWindow(contextWindow: Long = 100_000): FauxProviderHandle =
        ScenarioSupport.faux(contextWindow = contextWindow, maxTokens = 1000)

    @Test
    fun recoversFromContextOverflowByCompactingOnce() = runBlocking {
        withTimeout(40_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, settings())
            val faux = fauxWithContextWindow()
            faux.setResponses(
                listOf(
                    FauxResponseStep.Fixed(fauxAssistantMessage("first answer")),
                    overflowError,
                    FauxResponseStep.Fixed(fauxAssistantMessage("SUMMARY: first question discussion")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("SUMMARY: turn prefix")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("Recovered answer")),
                ),
            )
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                session.prompt("first question")
                assertEquals("first answer", session.getLastAssistantText())
                val outcome = runCatching { session.prompt("second question") }
                assertTrue(outcome.isSuccess, outcome.exceptionOrNull()?.toString())
                assertEquals("Recovered answer", session.getLastAssistantText())
                assertEquals(5, faux.state.callCount)
                val compactionEntries = session.sessionManager.getEntries().filterIsInstance<CompactionEntry>()
                assertEquals(1, compactionEntries.size)
                val collected = events.drain()
                assertEquals(1, collected.filterIsInstance<SessionEvent.CompactionStarted>().size)
                assertEquals(1, collected.filterIsInstance<SessionEvent.CompactionFinished>().size)
                assertFalse(session.isStreaming)
            } finally {
                events.close()
                session.close()
            }
        }
    }

    @Test
    fun waitForIdleCoversCompactionSummarization() = runBlocking {
        withTimeout(40_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, settings())
            val faux = fauxWithContextWindow()
            val summaryGate = CompletableDeferred<Unit>()
            val summaryStarted = CompletableDeferred<Unit>()
            val gatedSummary = FauxResponseStep.Scripted(
                object : FauxResponseFactory {
                    override suspend fun produce(
                        context: TranscriptContext,
                        options: SimpleStreamOptions?,
                        state: FauxProviderState,
                        model: Model,
                    ): dev.pi.ai.AssistantMessage {
                        summaryStarted.complete(Unit)
                        summaryGate.await()
                        return fauxAssistantMessage("SUMMARY from gate")
                    }
                },
            )
            faux.setResponses(
                listOf(
                    FauxResponseStep.Fixed(fauxAssistantMessage("first answer")),
                    overflowError,
                    gatedSummary,
                    FauxResponseStep.Fixed(fauxAssistantMessage("SUMMARY: turn prefix")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("Recovered after gate")),
                ),
            )
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                session.prompt("first question")
                val promptJob = launch(Dispatchers.Default) { session.prompt("second question") }
                withTimeout(15_000) { summaryStarted.await() }
                var idleReturned = false
                val idleJob = async(Dispatchers.Default) {
                    session.waitForIdle()
                    idleReturned = true
                }
                delay(150)
                assertFalse(idleReturned)
                summaryGate.complete(Unit)
                promptJob.join()
                idleJob.await()
                assertTrue(idleReturned)
                assertEquals("Recovered after gate", session.getLastAssistantText())
                assertEquals(1, session.sessionManager.getEntries().filterIsInstance<CompactionEntry>().size)
                assertFalse(session.isBusy)
                val collected = events.drain()
                assertTrue(collected.any { it is SessionEvent.AgentEventReceived && it.event.type == "agent_end" })
            } finally {
                events.close()
                session.close()
            }
        }
    }
}
