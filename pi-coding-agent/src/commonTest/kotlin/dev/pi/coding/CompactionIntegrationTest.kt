package dev.pi.coding

import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.FauxModelDefinition
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.coding.session.CompactionEntry
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private object SilentTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        throw IllegalStateException("network is disabled in this test")
}

class CompactionIntegrationTest {
    @Test
    fun autoCompactsBeforePromptWhenContextIsFull() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/agent".toPath())
        fs.write("/agent/settings.json".toPath()) {
            writeUtf8("""{"compaction":{"enabled":true,"reserveTokens":0,"keepRecentTokens":1}}""")
        }

        val faux = fauxProvider(
            FauxProviderOptions(
                models = listOf(FauxModelDefinition(id = "faux-1", contextWindow = 1L, maxTokens = 100L)),
                tokenSize = FauxTokenSize(1, 1),
            ),
        )
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(fauxAssistantMessage("first reply")),
                FauxResponseStep.Fixed(fauxAssistantMessage("compacted summary of the conversation")),
                FauxResponseStep.Fixed(fauxAssistantMessage("second reply")),
            ),
        )

        val session = createAgentSession(
            AgentSessionOptions(
                cwd = "/work".toPath(),
                agentDir = "/agent".toPath(),
                transport = SilentTransport,
                fs = fs,
                model = "faux/faux-1",
                tools = emptyList(),
                providers = listOf(faux.provider),
            ),
        )
        try {
            session.prompt("first question")
            assertEquals("first reply", session.getLastAssistantText())

            session.prompt("second question")
            assertEquals("second reply", session.getLastAssistantText())

            val compactionEntries = session.sessionManager.getEntries().filterIsInstance<CompactionEntry>()
            assertEquals(1, compactionEntries.size)
            assertTrue(compactionEntries.single().summary.contains("compacted summary"))
            assertEquals(3, faux.state.callCount)
        } finally {
            session.close()
        }
    }
}
