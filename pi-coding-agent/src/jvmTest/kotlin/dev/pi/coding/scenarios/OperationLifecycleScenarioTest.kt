package dev.pi.coding.scenarios

import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.StopReason
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class OperationLifecycleScenarioTest {
    private fun retryableError(text: String = "server error") = FauxResponseStep.Fixed(
        fauxAssistantMessage(content = "", stopReason = StopReason.Error, errorMessage = text),
    )

    @Test
    fun manualCompactIsTrackedAndAbortable() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(
                fs,
                """{"compaction":{"enabled":true,"reserveTokens":0,"keepRecentTokens":1},"retry":{"enabled":false}}""",
            )
            val faux = ScenarioSupport.faux(tokensPerSecond = 20.0)
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("first answer"))))
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                session.prompt("first question")
                assertEquals("first answer", session.getLastAssistantText())

                faux.setResponses(
                    listOf(FauxResponseStep.Fixed(fauxAssistantMessage("long summary ".repeat(40)))),
                )
                val compactJob = async(Dispatchers.Default) { session.compact() }
                val started = events.await { it is SessionEvent.CompactionStarted }
                assertTrue(started is SessionEvent.CompactionStarted)
                assertTrue(session.isBusy)
                var idleReturned = false
                val idleJob = async(Dispatchers.Default) {
                    session.waitForIdle()
                    idleReturned = true
                }
                delay(150)
                assertFalse(idleReturned)

                session.abort()
                val result = withTimeout(10_000) { compactJob.await() }
                assertNull(result)
                withTimeout(5_000) { idleJob.await() }
                assertTrue(idleReturned)
                assertFalse(session.isBusy)
                assertEquals(0, session.sessionManager.getEntries().filterIsInstance<CompactionEntry>().size)

                val collected = events.drain()
                assertEquals(1, collected.filterIsInstance<SessionEvent.CompactionFailed>().size)
                assertEquals(0, collected.filterIsInstance<SessionEvent.CompactionFinished>().size)
            } finally {
                events.close()
                session.close()
            }
        }
    }

    @Test
    fun promptCancellationDoesNotHangWaiters() = runBlocking {
        withTimeout(40_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux(tokensPerSecond = 30.0)
            val reply = "x".repeat(3000)
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage(reply))))
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val subscriberScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val blocker = subscriberScope.launch(Dispatchers.Default) {
                session.events.collect { delay(60_000) }
            }
            val promptScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val promptJob = promptScope.launch { session.prompt("long answer please") }
                delay(350)
                assertTrue(session.isBusy)
                promptJob.cancel()
                withTimeout(10_000) { session.waitForIdle() }
                assertFalse(session.isBusy)
            } finally {
                blocker.cancel()
                promptScope.cancel()
                subscriberScope.cancel()
                session.close()
            }
        }
    }

    @Test
    fun retryAttemptDoesNotLeakAcrossPrompts() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(
                fs,
                """{"retry":{"enabled":true,"maxRetries":1,"baseDelayMs":400,"maxAgentDelayMs":2000}}""",
            )
            val faux = ScenarioSupport.faux()
            faux.setResponses(listOf(retryableError()))
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val firstJob = scope.launch { session.prompt("first") }
                events.await { it is SessionEvent.RetryScheduled }
                session.abort()
                withTimeout(5_000) { firstJob.join() }
                assertEquals(1, faux.state.callCount)

                faux.appendResponses(
                    listOf(retryableError(), FauxResponseStep.Fixed(fauxAssistantMessage("recovered now"))),
                )
                session.prompt("second")
                assertEquals("recovered now", session.getLastAssistantText())
                assertEquals(3, faux.state.callCount)
            } finally {
                scope.cancel()
                events.close()
                session.close()
            }
        }
    }

    @Test
    fun defaultModelThinkingSuffixIsApplied() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, """{"enabledModels":["faux/faux-1:high"]}""")
            val model = Model(
                id = "faux-1",
                name = "Faux 1",
                api = "faux",
                provider = "faux",
                baseUrl = "http://localhost:0",
                input = listOf(ModelInputModality.Text),
                cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                reasoning = true,
                contextWindow = 128_000,
                maxTokens = 4096,
            )
            val faux = fauxProvider(
                FauxProviderOptions(
                    tokenSize = FauxTokenSize(1, 1),
                    models = listOf(
                        dev.pi.ai.providers.FauxModelDefinition(
                            id = model.id,
                            name = model.name,
                            reasoning = true,
                            cost = model.cost,
                            contextWindow = model.contextWindow,
                            maxTokens = model.maxTokens,
                        ),
                    ),
                ),
            )
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("ok"))))
            val session = createAgentSession(
                AgentSessionOptions(
                    cwd = ScenarioSupport.CWD.toPath(),
                    agentDir = ScenarioSupport.AGENT_DIR.toPath(),
                    transport = NoNetworkTransport,
                    fs = fs,
                    model = null,
                    providers = listOf(faux.provider),
                    projectTrusted = true,
                ),
            )
            try {
                assertEquals("high", session.thinkingLevel)
                session.prompt("hello")
                assertEquals("ok", session.getLastAssistantText())
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun thresholdCompactionEmitsSingleLifecycle() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(
                fs,
                """{"compaction":{"enabled":true,"reserveTokens":0,"keepRecentTokens":1},"retry":{"enabled":false}}""",
            )
            val faux = ScenarioSupport.faux(contextWindow = 50)
            faux.setResponses(
                listOf(
                    FauxResponseStep.Fixed(fauxAssistantMessage("first answer")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("summary history")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("summary prefix")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("second answer")),
                ),
            )
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                session.prompt("first question")
                session.prompt("second question")
                val collected = events.drain(idleTimeoutMs = 2_000)
                assertEquals(1, collected.filterIsInstance<SessionEvent.CompactionStarted>().size)
                assertEquals(1, collected.filterIsInstance<SessionEvent.CompactionFinished>().size)
                assertEquals(0, collected.filterIsInstance<SessionEvent.CompactionFailed>().size)
                assertEquals(1, session.sessionManager.getEntries().filterIsInstance<CompactionEntry>().size)
                assertTrue(session.getLastAssistantText() != null)
            } finally {
                events.close()
                session.close()
            }
        }
    }
}
