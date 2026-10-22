package dev.pi.coding.scenarios

import dev.pi.ai.StopReason
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.coding.session.SessionEvent
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

class SessionLifecycleScenarioTest {

    private fun retryableError(text: String = "upstream request failed") = FauxResponseStep.Fixed(
        fauxAssistantMessage(content = "", stopReason = StopReason.Error, errorMessage = text),
    )

    @Test
    fun abortDuringRetryWaitStopsFurtherRequests() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(
                fs,
                """{"retry":{"enabled":true,"maxRetries":3,"baseDelayMs":800,"maxAgentDelayMs":8000}}""",
            )
            val faux = ScenarioSupport.faux()
            faux.setResponses(listOf(retryableError(), retryableError()))
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                val promptJob = launch(Dispatchers.Default) {
                    session.prompt("hello")
                }
                events.await { it is SessionEvent.RetryScheduled }
                session.abort()
                withTimeout(5_000) { promptJob.join() }
                assertEquals(1, faux.state.callCount)
                assertFalse(session.isStreaming)
                assertFalse(session.isBusy)
            } finally {
                events.close()
                session.close()
            }
        }
    }

    @Test
    fun waitForIdleCoversTheRetryWait() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(
                fs,
                """{"retry":{"enabled":true,"maxRetries":2,"baseDelayMs":700,"maxAgentDelayMs":5000}}""",
            )
            val faux = ScenarioSupport.faux()
            faux.setResponses(
                listOf(
                    retryableError("server error"),
                    FauxResponseStep.Fixed(fauxAssistantMessage("recovered")),
                ),
            )
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                val promptJob = launch(Dispatchers.Default) { session.prompt("hello") }
                events.await { it is SessionEvent.RetryScheduled }
                var idleReturned = false
                val idleJob = async(Dispatchers.Default) {
                    session.waitForIdle()
                    idleReturned = true
                }
                delay(150)
                assertFalse(idleReturned)
                promptJob.join()
                idleJob.await()
                assertTrue(idleReturned)
                assertEquals(2, faux.state.callCount)
                assertEquals("recovered", session.getLastAssistantText())
                assertFalse(session.isStreaming)
                assertFalse(session.isBusy)
            } finally {
                events.close()
                session.close()
            }
        }
    }

    @Test
    fun waitForIdleCoversTheWholePromptOperation() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("done"))))
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                val promptJob = launch(Dispatchers.Default) { session.prompt("hello") }
                events.await { event ->
                    event is SessionEvent.AgentEventReceived && event.event.type == "agent_start"
                }
                val idleJob = async(Dispatchers.Default) {
                    session.waitForIdle()
                    session.isBusy
                }
                assertFalse(idleJob.await())
                promptJob.join()
                assertFalse(session.isStreaming)
            } finally {
                events.close()
                session.close()
            }
        }
    }
}
