package dev.pi.coding.scenarios

import dev.pi.agent.asLlmMessage
import dev.pi.ai.SystemMessage
import dev.pi.ai.providers.FauxResponseFactory
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxProviderState
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.providers.FauxProviderHandle
import dev.pi.ai.utils.getCurrentSystemMessage
import dev.pi.ai.utils.getSystemMessageText
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.CodingHooks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class SessionConfigScenarioTest {

    private fun captureFirstContext(faux: FauxProviderHandle, reply: String = "ok") {
        faux.setResponses(
            listOf(
                FauxResponseStep.Scripted(
                    object : FauxResponseFactory {
                        override suspend fun produce(
                            context: TranscriptContext,
                            options: SimpleStreamOptions?,
                            state: FauxProviderState,
                            model: dev.pi.ai.Model,
                        ): dev.pi.ai.AssistantMessage {
                            firstContext = context
                            return fauxAssistantMessage(reply)
                        }
                    },
                ),
            ),
        )
    }

    private var firstContext: TranscriptContext? = null

    @Test
    fun defaultToolsFilterTheActiveToolSet() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, """{"defaultTools":["read"]}""")
            val faux = ScenarioSupport.faux()
            val session = ScenarioSupport.create(fs, listOf(faux.provider))
            try {
                assertEquals(listOf("read"), session.activeToolNames)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun defaultToolsSupportAddAndRemoveModifiers() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, """{"defaultTools":["+grep","+find"]}""")
            val added = ScenarioSupport.create(fs, listOf(ScenarioSupport.faux().provider))
            try {
                assertEquals(
                    listOf("read", "bash", "edit", "write", "grep", "find"),
                    added.activeToolNames,
                )
            } finally {
                added.close()
            }

            ScenarioSupport.writeSettings(fs, """{"defaultTools":["-bash","-write"]}""")
            val removed = ScenarioSupport.create(fs, listOf(ScenarioSupport.faux().provider))
            try {
                assertEquals(listOf("read", "edit"), removed.activeToolNames)
            } finally {
                removed.close()
            }
        }
    }

    @Test
    fun extraSystemPromptSectionsArePresentInTheFirstRequest() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            captureFirstContext(faux)
            val session = ScenarioSupport.create(
                fs,
                listOf(faux.provider),
                extraSystemPromptSections = mapOf("environment" to "Node 22."),
            )
            try {
                session.prompt("hello")
                val context = firstContext
                assertTrue(context != null)
                val system = context!!.messages.first() as SystemMessage
                val sections = system.sections
                assertTrue(sections != null && sections.containsKey("environment"), sections?.keys?.toString())
                assertTrue(sections!!["environment"]!!.contains("Node 22."))
                assertTrue(getSystemMessageText(system).contains("Node 22."))
                assertTrue(session.agent.state.messages.first().asLlmMessage() is SystemMessage)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun initialSystemPromptSectionsSeedTheFirstSnapshotOnly() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            var sections = mapOf("environment" to "day one")
            val contexts = mutableListOf<TranscriptContext>()
            faux.setResponses(
                listOf(
                    FauxResponseStep.Scripted(
                        object : FauxResponseFactory {
                            override suspend fun produce(
                                context: TranscriptContext,
                                options: SimpleStreamOptions?,
                                state: FauxProviderState,
                                model: dev.pi.ai.Model,
                            ): dev.pi.ai.AssistantMessage {
                                contexts += context
                                return fauxAssistantMessage("ok")
                            }
                        },
                    ),
                    FauxResponseStep.Scripted(
                        object : FauxResponseFactory {
                            override suspend fun produce(
                                context: TranscriptContext,
                                options: SimpleStreamOptions?,
                                state: FauxProviderState,
                                model: dev.pi.ai.Model,
                            ): dev.pi.ai.AssistantMessage {
                                contexts += context
                                return fauxAssistantMessage("ok")
                            }
                        },
                    ),
                ),
            )
            val hooks = CodingHooks(extraSystemPromptSections = { sections })
            val session = ScenarioSupport.create(
                fs,
                listOf(faux.provider),
                hooks = hooks,
                initialSystemPromptSections = sections,
            )
            try {
                session.prompt("first")
                val first = getCurrentSystemMessage(contexts.first().messages.mapNotNull { it as? SystemMessage })
                assertEquals("day one", first?.sections?.get("environment"))
                assertEquals(1, contexts.first().messages.count { it is SystemMessage })

                sections = emptyMap()
                session.prompt("second")
                val second = getCurrentSystemMessage(contexts.last().messages.mapNotNull { it as? SystemMessage })
                assertFalse(second?.sections?.containsKey("environment") ?: false)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun projectTrustedControlsProjectSystemPrompt() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.write(fs, "/work/.pi/SYSTEM.md", "Project system prompt.")
            val untrusted = ScenarioSupport.create(
                fs,
                listOf(ScenarioSupport.faux().provider),
                projectTrusted = false,
            )
            try {
                assertFalse(untrusted.systemPrompt.contains("Project system prompt."))
            } finally {
                untrusted.close()
            }

            val trusted = ScenarioSupport.create(
                fs,
                listOf(ScenarioSupport.faux().provider),
                projectTrusted = true,
            )
            try {
                assertTrue(trusted.systemPrompt.contains("Project system prompt."))
            } finally {
                trusted.close()
            }
        }
    }

    @Test
    fun explicitModelAndThinkingLevelOverrideRestoredSession() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            val faux2 = ScenarioSupport.faux(provider = "faux2")
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("first answer"))))
            val first = ScenarioSupport.create(
                fs,
                listOf(faux.provider),
                model = "faux/faux-1",
                thinkingLevel = "low",
            )
            val sessionFile = try {
                first.prompt("first question")
                first.sessionFile
            } finally {
                first.close()
            }
            assertTrue(sessionFile != null)

            val reopened: AgentSession = ScenarioSupport.create(
                fs,
                listOf(faux.provider, faux2.provider),
                model = "faux2/faux-1",
                thinkingLevel = "high",
                sessionFile = sessionFile,
            )
            try {
                assertEquals("faux2", reopened.model.provider)
                assertEquals("faux-1", reopened.model.id)
                assertEquals("high", reopened.thinkingLevel)
            } finally {
                reopened.close()
            }
        }
    }

    @Test
    fun explicitModelIsUsedForTheNextRequest() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = ScenarioSupport.faux()
            val faux2 = ScenarioSupport.faux(provider = "faux2")
            faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("first answer"))))
            faux2.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("second answer"))))
            val first = ScenarioSupport.create(fs, listOf(faux.provider), model = "faux/faux-1")
            val sessionFile = try {
                first.prompt("first question")
                first.sessionFile
            } finally {
                first.close()
            }
            assertTrue(sessionFile != null)

            val reopened = ScenarioSupport.create(
                fs,
                listOf(faux.provider, faux2.provider),
                model = "faux2/faux-1",
                sessionFile = sessionFile,
            )
            try {
                reopened.prompt("second question")
                assertEquals(1, faux.state.callCount)
                assertEquals(1, faux2.state.callCount)
                assertEquals("second answer", reopened.getLastAssistantText())
            } finally {
                reopened.close()
            }
        }
    }
}
