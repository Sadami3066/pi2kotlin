package dev.pi.ai

import dev.pi.ai.providers.FauxModelDefinition
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxProviderState
import dev.pi.ai.providers.FauxResponseFactory
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.ai.providers.fauxText
import dev.pi.ai.providers.fauxThinking
import dev.pi.ai.providers.fauxToolCall
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FauxProviderTest {
    private fun userContext(text: String = "hi"): Context =
        Context(messages = listOf(UserMessage(PlainTextContent(text), timestamp = 1L)))

    private fun fauxModels(handle: dev.pi.ai.providers.FauxProviderHandle): Models =
        Models().apply { setProvider(handle.provider) }

    @Test
    fun streamsFixedTextResponse() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("hello world"))))
        val models = fauxModels(faux)
        val stream = models.stream(faux.getModel()!!, userContext())
        val events = stream.events.toList()

        assertIs<StartEvent>(events.first())
        assertEquals(1, events.count { it is TextStartEvent })
        val deltas = events.filterIsInstance<TextDeltaEvent>()
        assertEquals(3, deltas.size)
        assertEquals("hello world", deltas.joinToString("") { it.delta })
        assertIs<TextEndEvent>(events[events.size - 2])
        val done = assertIs<DoneEvent>(events.last())
        assertEquals(StopReason.Stop, done.reason)
        assertEquals("hello world", (done.message.content.single() as TextContent).text)
        assertEquals(1, faux.state.callCount)
        assertEquals(0, faux.getPendingResponseCount())
    }

    @Test
    fun streamsThinkingTextAndToolCallInOrder() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(
                    fauxAssistantMessage(
                        listOf(
                            fauxThinking("plan"),
                            fauxText("run"),
                            fauxToolCall("bash", buildJsonObject { put("command", "ls") }, id = "call-1"),
                        ),
                    ),
                ),
            ),
        )
        val models = fauxModels(faux)
        val events = models.stream(faux.getModel()!!, userContext()).events.toList()

        assertEquals(
            listOf(
                "start",
                "thinking_start",
                "thinking_delta",
                "thinking_end",
                "text_start",
                "text_delta",
                "text_end",
                "toolcall_start",
                "toolcall_delta",
                "toolcall_delta",
                "toolcall_delta",
                "toolcall_delta",
                "toolcall_end",
                "done",
            ),
            events.map { event ->
                when (event) {
                    is StartEvent -> "start"
                    is ThinkingStartEvent -> "thinking_start"
                    is ThinkingDeltaEvent -> "thinking_delta"
                    is ThinkingEndEvent -> "thinking_end"
                    is TextStartEvent -> "text_start"
                    is TextDeltaEvent -> "text_delta"
                    is TextEndEvent -> "text_end"
                    is ToolCallStartEvent -> "toolcall_start"
                    is ToolCallDeltaEvent -> "toolcall_delta"
                    is ToolCallEndEvent -> "toolcall_end"
                    is DoneEvent -> "done"
                    is ErrorEvent -> "error"
                }
            },
        )
        val thinkingEnd = events.filterIsInstance<ThinkingEndEvent>().single()
        assertEquals("plan", thinkingEnd.content)
        val toolEnd = events.filterIsInstance<ToolCallEndEvent>().single()
        assertEquals("call-1", toolEnd.toolCall.id)
        assertEquals("bash", toolEnd.toolCall.name)
        assertEquals("ls", toolEnd.toolCall.arguments["command"]?.toString()?.trim('"'))
        val done = assertIs<DoneEvent>(events.last())
        assertEquals(3, done.message.content.size)
        assertIs<ThinkingContent>(done.message.content[0])
        assertIs<TextContent>(done.message.content[1])
        assertIs<ToolCall>(done.message.content[2])
    }

    @Test
    fun consumesQueuedResponsesInOrder() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(fauxAssistantMessage("first")),
                FauxResponseStep.Fixed(fauxAssistantMessage("second")),
            ),
        )
        val models = fauxModels(faux)
        val model = faux.getModel()!!

        val first = models.complete(model, userContext())
        assertEquals("first", (first.content.single() as TextContent).text)
        faux.appendResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("third"))))
        assertEquals(2, faux.getPendingResponseCount())
        val second = models.complete(model, userContext())
        assertEquals("second", (second.content.single() as TextContent).text)
        val third = models.complete(model, userContext())
        assertEquals("third", (third.content.single() as TextContent).text)
        assertEquals(3, faux.state.callCount)
        assertEquals(0, faux.getPendingResponseCount())
    }

    @Test
    fun errorsWhenQueueIsEmpty() = runTest {
        val faux = fauxProvider()
        val models = fauxModels(faux)
        val events = models.stream(faux.getModel()!!, userContext()).events.toList()
        val error = assertIs<ErrorEvent>(events.last())
        assertEquals(StopReason.Error, error.reason)
        assertTrue(error.error.errorMessage!!.contains("No more faux responses queued"))
    }

    @Test
    fun scriptedErrorProducesErrorEvent() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(
                    fauxAssistantMessage("", stopReason = StopReason.Error, errorMessage = "boom"),
                ),
            ),
        )
        val models = fauxModels(faux)
        val stream = models.stream(faux.getModel()!!, userContext())
        val events = stream.events.toList()
        val error = assertIs<ErrorEvent>(events.last())
        assertEquals(StopReason.Error, error.reason)
        assertEquals("boom", error.error.errorMessage)
    }

    @Test
    fun scriptedFactoryReceivesContextStateAndModel() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        var seenModel: String? = null
        var seenState: FauxProviderState? = null
        var seenMessages = 0
        faux.setResponses(
            listOf(
                FauxResponseStep.Scripted(
                    object : FauxResponseFactory {
                        override suspend fun produce(
                            context: TranscriptContext,
                            options: SimpleStreamOptions?,
                            state: FauxProviderState,
                            model: Model,
                        ): AssistantMessage {
                            seenModel = model.id
                            seenState = state
                            seenMessages = context.messages.size
                            return fauxAssistantMessage("scripted")
                        }
                    },
                ),
            ),
        )
        val models = fauxModels(faux)
        val result = models.complete(faux.getModel()!!, userContext())
        assertEquals("scripted", (result.content.single() as TextContent).text)
        assertEquals("faux-1", seenModel)
        assertEquals(1, seenMessages)
        assertEquals(faux.state, seenState)
    }

    @Test
    fun estimatesUsageFromPromptAndOutput() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("hi"))))
        val models = fauxModels(faux)
        val result = models.complete(faux.getModel()!!, userContext("abcdefgh"))
        assertEquals(4L, result.usage.input)
        assertEquals(1L, result.usage.output)
        assertEquals(0L, result.usage.cacheRead)
        assertEquals(0L, result.usage.cacheWrite)
        assertEquals(5L, result.usage.totalTokens)
        assertEquals(0.0, result.usage.cost.total)
    }

    @Test
    fun estimatesPromptCacheUsageWithSessionId() = runTest {
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(fauxAssistantMessage("one")),
                FauxResponseStep.Fixed(fauxAssistantMessage("two")),
            ),
        )
        val models = fauxModels(faux)
        val model = faux.getModel()!!
        val options = SimpleStreamOptions(
            stream = StreamOptions(sessionId = "session-1", cacheRetention = CacheRetention.Short),
        )
        val first = models.completeSimple(model, userContext("abcdefgh"), options)
        assertEquals(4L, first.usage.input)
        assertEquals(4L, first.usage.cacheWrite)
        assertEquals(0L, first.usage.cacheRead)

        val secondContext = Context(
            messages = listOf(
                UserMessage(PlainTextContent("abcdefgh"), timestamp = 1L),
                fauxAssistantMessage("hello"),
            ),
        )
        val second = models.completeSimple(model, secondContext, options)
        assertEquals(4L, second.usage.cacheRead)
        assertEquals(4L, second.usage.input)
        assertEquals(5L, second.usage.cacheWrite)
    }

    @Test
    fun usesCustomModelDefinitions() {
        val faux = fauxProvider(
            FauxProviderOptions(
                models = listOf(
                    FauxModelDefinition(id = "faux-2", name = "Faux Two", reasoning = true),
                    FauxModelDefinition(id = "faux-3"),
                ),
            ),
        )
        val model = assertNotNull(faux.getModel("faux-2"))
        assertEquals("Faux Two", model.name)
        assertEquals(faux.api, model.api)
        assertEquals(faux.provider.id, model.provider)
        assertEquals(ModelInputModality.Image, model.input.last())
        assertEquals("faux-2", faux.getModel()?.id)
        assertNull(faux.getModel("missing"))
    }

    @Test
    fun resolvesAuthWithoutApiKey() = runTest {
        val faux = fauxProvider()
        val auth = assertNotNull(faux.provider.auth.resolve())
        assertNull(auth.apiKey)
        assertEquals("faux", auth.source)
    }
}
