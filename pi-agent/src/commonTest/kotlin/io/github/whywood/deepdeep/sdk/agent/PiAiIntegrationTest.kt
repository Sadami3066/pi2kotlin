package io.github.whywood.deepdeep.sdk.agent

import io.github.whywood.deepdeep.sdk.ai.Context
import io.github.whywood.deepdeep.sdk.ai.Models
import io.github.whywood.deepdeep.sdk.ai.TextContent
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderOptions
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.FauxTokenSize
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxProvider
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PiAiIntegrationTest {
    @Test
    fun agentLoopStreamsThroughPiAiModels() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("Hello from faux"))))
        val models = Models().apply { setProvider(faux.provider) }
        val model = faux.getModel()!!

        val streamFn: StreamFn = { requestedModel, context, options ->
            models.streamSimple(requestedModel, Context(messages = context.messages), options)
        }

        val agent = Agent(
            AgentOptions(
                initialState = AgentInitialState(systemPrompt = "You are terse.", model = model),
                streamFn = streamFn,
            ),
        )

        val events = mutableListOf<AgentEvent>()
        agent.subscribe { event, _ -> events += event }
        agent.prompt("hi")

        assertIs<AgentEvent.AgentStart>(events.first())
        assertIs<AgentEvent.AgentEnd>(events.last())

        val assistant = agent.state.messages.mapNotNull { it.asAssistantMessage() }.single()
        val text = assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        assertEquals("Hello from faux", text)
        assertEquals(1, faux.state.callCount)

        val deltas = events.filterIsInstance<AgentEvent.MessageUpdate>()
            .mapNotNull { it.assistantMessageEvent as? io.github.whywood.deepdeep.sdk.ai.TextDeltaEvent }
        assertEquals("Hello from faux", deltas.joinToString("") { it.delta })
    }
}
