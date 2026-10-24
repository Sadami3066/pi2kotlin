package dev.pi.coding.scenarios

import dev.pi.ai.Model
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
import dev.pi.agent.asLlmMessage
import dev.pi.ai.providers.FauxProviderState
import dev.pi.ai.providers.FauxResponseFactory
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxProviderHandle
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.coding.session.CodingHooks
import dev.pi.coding.session.SessionEvent
import dev.pi.coding.session.SessionMessageEntry
import dev.pi.coding.session.decodeSessionEntry
import dev.pi.coding.session.sessionJson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionContextScenarioTest {

    private fun captureContexts(faux: FauxProviderHandle, reply: String = "ok"): MutableList<TranscriptContext> {
        val contexts = mutableListOf<TranscriptContext>()
        faux.setResponses(
            listOf(
                FauxResponseStep.Scripted(
                    object : FauxResponseFactory {
                        override suspend fun produce(
                            context: TranscriptContext,
                            options: SimpleStreamOptions?,
                            state: FauxProviderState,
                            model: Model,
                        ): dev.pi.ai.AssistantMessage {
                            contexts.add(context)
                            return fauxAssistantMessage(reply)
                        }
                    },
                ),
            ),
        )
        return contexts
    }

    private fun userText(message: dev.pi.ai.Message): String? {
        val user = message as? UserMessage ?: return null
        return when (val content = user.content) {
            is dev.pi.ai.PlainTextContent -> content.text
            is dev.pi.ai.BlockContent -> content.blocks.filterIsInstance<dev.pi.ai.TextContent>().joinToString("") { it.text }
        }
    }

    @Test
    fun persistsTheInitialSystemSnapshotAndRestoresIt() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("hello back"))))
            val first = ScenarioSupport.create(fs, listOf(faux.provider))
            val firstSystemPrompt: String
            val firstSections: Map<String, String?>
            val firstToolNames: List<String>
            val sessionFile = try {
                first.prompt("hello")
                firstSystemPrompt = first.systemPrompt
                val system = first.agent.state.messages.first().asLlmMessage() as SystemMessage
                firstSections = system.sections.orEmpty()
                firstToolNames = system.toolsAdded?.map { it.name }.orEmpty()
                first.sessionFile
            } finally {
                first.close()
            }
            assertTrue(sessionFile != null)
            assertTrue(firstSections.isNotEmpty())
            assertTrue(firstToolNames.isNotEmpty())
            assertTrue(firstSystemPrompt.isNotEmpty())

            val lines = ScenarioSupport.read(fs, sessionFile!!).trim().lines()
            assertTrue(lines.size >= 2)
            val entries = lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                decodeSessionEntry(sessionJson.parseToJsonElement(line).jsonObject)
            }
            val systemEntries = entries.filterIsInstance<SessionMessageEntry>()
                .map { it.message }
                .filterIsInstance<SystemMessage>()
            assertTrue(systemEntries.isNotEmpty(), "expected the initial system message in the session jsonl")
            val initial = systemEntries.first()
            assertEquals(firstSections.keys, initial.sections.orEmpty().keys)
            assertEquals(firstToolNames, initial.toolsAdded?.map { it.name }.orEmpty())
            val systemIndex = entries.indexOfFirst { entry ->
                entry is SessionMessageEntry && entry.message is SystemMessage
            }
            val userIndex = entries.indexOfFirst { entry ->
                entry is SessionMessageEntry && entry.message is UserMessage
            }
            assertTrue(systemIndex >= 0 && userIndex > systemIndex)

            val reopened = ScenarioSupport.create(fs, listOf(faux.provider), sessionFile = sessionFile)
            try {
                assertEquals(firstSystemPrompt, reopened.systemPrompt)
                val restoredSystem = reopened.agent.state.messages.first().asLlmMessage() as SystemMessage
                assertEquals(firstSections.keys, restoredSystem.sections.orEmpty().keys)
                assertEquals(firstToolNames, restoredSystem.toolsAdded?.map { it.name }.orEmpty())
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun persistsSystemPromptPatches() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            var extraSections: Map<String, String> = mapOf("environment" to "one")
            val hooks = CodingHooks(extraSystemPromptSections = { extraSections })
            val faux = ScenarioSupport.faux()
            faux.setResponses(
                listOf(
                    FauxResponseStep.Fixed(fauxAssistantMessage("one")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("two")),
                ),
            )
            val session = ScenarioSupport.create(fs, listOf(faux.provider), hooks = hooks)
            val sessionFile: okio.Path
            try {
                session.prompt("first")
                extraSections = mapOf("environment" to "two")
                session.prompt("second")
                sessionFile = session.sessionFile!!
            } finally {
                session.close()
            }
            val lines = ScenarioSupport.read(fs, sessionFile).trim().lines()
            val entries = lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                decodeSessionEntry(sessionJson.parseToJsonElement(line).jsonObject)
            }
            val systemMessages = entries.filterIsInstance<SessionMessageEntry>()
                .map { it.message }
                .filterIsInstance<SystemMessage>()
            assertTrue(systemMessages.size >= 2, "expected system patches in the session jsonl")
            val patchValues = systemMessages.mapNotNull { it.sections?.get("environment") }
            assertTrue(patchValues.any { it.contains("one") }, patchValues.toString())
            assertTrue(patchValues.any { it.contains("two") }, patchValues.toString())
        }
    }

    @Test
    fun stableHookSectionsDoNotAppendSystemPatchesEachTurn() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val sections = mapOf("environment" to "stable environment")
            val hooks = CodingHooks(extraSystemPromptSections = { sections })
            val faux = ScenarioSupport.faux()
            faux.setResponses(
                listOf(
                    FauxResponseStep.Fixed(fauxAssistantMessage("one")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("two")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("three")),
                ),
            )
            val session = ScenarioSupport.create(
                fs,
                listOf(faux.provider),
                hooks = hooks,
                initialSystemPromptSections = sections,
            )
            val sessionFile: okio.Path
            try {
                session.prompt("first")
                session.prompt("second")
                session.prompt("third")
                sessionFile = session.sessionFile!!
            } finally {
                session.close()
            }
            val lines = ScenarioSupport.read(fs, sessionFile).trim().lines()
            val entries = lines.drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                decodeSessionEntry(sessionJson.parseToJsonElement(line).jsonObject)
            }
            val systemMessages = entries.filterIsInstance<SessionMessageEntry>()
                .map { it.message }
                .filterIsInstance<SystemMessage>()
            assertEquals(1, systemMessages.size)
            assertEquals("stable environment", systemMessages.single().sections?.get("environment"))
        }
    }

    @Test
    fun expandsPromptTemplatesBeforeSending() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.write(
                fs,
                "/agent/prompts/greet.md",
                "---\ndescription: Greet someone\n---\nHello \${1:-world}.\n",
            )
            val faux = ScenarioSupport.faux()
            val contexts = captureContexts(faux, "greeting sent")
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            try {
                session.prompt("/greet Alice")
                val userTexts = ctxUserTexts(contexts, ::userText)
                val last = userTexts.last()
                assertEquals("Hello Alice.", last)
                assertTrue(userTexts.none { it.contains("/greet") })
            } finally {
                session.close()
            }
        }
    }

    private fun ctxUserTexts(
        contexts: List<TranscriptContext>,
        extract: (dev.pi.ai.Message) -> String?,
    ): List<String> {
        val context = contexts.lastOrNull() ?: return emptyList()
        return context.messages.mapNotNull(extract)
    }

    @Test
    fun emitsTheFullAgentEventSequenceThroughTheSessionFlow() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("Hello!"))))
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            val events = SessionEventBuffer(session, this)
            try {
                session.prompt("hi")
                val collected = events.collectUntil { it is SessionEvent.Settled }
                assertTrue(collected.last() is SessionEvent.Settled)
                val types = agentEventTypes(collected)
                assertEquals("agent_start", types.first())
                assertEquals("agent_end", types.last())
                assertEquals(2, types.count { it == "message_start" })
                assertEquals(2, types.count { it == "message_end" })
                assertTrue(types.count { it == "message_update" } >= 1)
                assertTrue(types.indexOf("turn_end") in 0 until types.lastIndexOf("agent_end"))
                val messageEndRoles = collected
                    .filterIsInstance<SessionEvent.AgentEventReceived>()
                    .map { it.event }
                    .filterIsInstance<dev.pi.agent.AgentEvent.MessageEnd>()
                    .map { it.message.role }
                assertEquals(listOf("user", "assistant"), messageEndRoles)
                assertEquals(listOf("system", "user", "assistant"), session.messages.map { it.role })
            } finally {
                events.close()
                session.close()
            }
        }
    }

    @Test
    fun restoredSessionContinuesFromItsTranscript() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            faux.setResponses(
                listOf(
                    FauxResponseStep.Fixed(fauxAssistantMessage("first answer")),
                    FauxResponseStep.Fixed(fauxAssistantMessage("second answer")),
                ),
            )
            val first = ScenarioSupport.create(fs, listOf(faux.provider))
            val sessionFile = try {
                first.prompt("first question")
                first.sessionFile
            } finally {
                first.close()
            }
            val reopened = ScenarioSupport.create(fs, listOf(faux.provider), sessionFile = sessionFile)
            try {
                reopened.prompt("second question")
                assertEquals("second answer", reopened.getLastAssistantText())
                assertEquals(2, faux.state.callCount)
                assertEquals(StopReason.Stop, (reopened.messages.last().asLlmMessage() as dev.pi.ai.AssistantMessage).stopReason)
            } finally {
                reopened.close()
            }
        }
    }
}
