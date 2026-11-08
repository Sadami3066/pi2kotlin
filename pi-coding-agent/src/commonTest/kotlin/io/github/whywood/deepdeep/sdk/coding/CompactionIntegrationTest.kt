package io.github.whywood.deepdeep.sdk.coding

import io.github.whywood.deepdeep.sdk.ai.http.HttpRequest
import io.github.whywood.deepdeep.sdk.ai.http.HttpResponse
import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport
import io.github.whywood.deepdeep.sdk.ai.providers.FauxModelDefinition
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderOptions
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.FauxTokenSize
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxProvider
import io.github.whywood.deepdeep.sdk.coding.session.CompactionEntry
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
