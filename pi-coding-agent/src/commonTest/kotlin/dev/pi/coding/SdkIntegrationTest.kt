package dev.pi.coding

import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.agent.asAssistantMessage
import dev.pi.ai.TextContent
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.coding.session.SessionManager
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private object NoHttpTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        throw IllegalStateException("network is disabled in this test")
}

class SdkIntegrationTest {
    private fun options(
        fs: FakeFileSystem,
        faux: dev.pi.ai.providers.FauxProviderHandle,
        sessionFile: okio.Path? = null,
    ) = AgentSessionOptions(
        cwd = "/work".toPath(),
        agentDir = "/agent".toPath(),
        transport = NoHttpTransport,
        fs = fs,
        model = "faux/faux-1",
        thinkingLevel = "low",
        sessionFile = sessionFile,
        providers = listOf(faux.provider),
    )

    @Test
    fun promptsThroughFauxProviderAndPersistsSession() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/agent".toPath())
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("Hi from SDK"))))

        val session = createAgentSession(options(fs, faux))
        try {
            assertEquals("faux", session.model.provider)
            assertEquals("low", session.thinkingLevel)
            session.prompt("hello")
            assertEquals("Hi from SDK", session.getLastAssistantText())

            val assistant = session.messages.last().asAssistantMessage()
            assertNotNull(assistant)
            assertEquals(
                "Hi from SDK",
                assistant.content.filterIsInstance<TextContent>().joinToString("") { it.text },
            )
            assertTrue(session.sessionFile != null)
            val lines = fs.read(session.sessionFile!!) { readUtf8() }.trim().lines()
            assertTrue(lines.size >= 4)
            assertTrue(lines.first().contains("\"type\":\"session\""))
        } finally {
            session.close()
        }
    }

    @Test
    fun resumesPersistedSession() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/agent".toPath())
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(fauxAssistantMessage("first turn")),
                FauxResponseStep.Fixed(fauxAssistantMessage("second turn")),
            ),
        )

        val first = createAgentSession(options(fs, faux))
        val file = first.sessionFile
        try {
            first.prompt("turn one")
            assertEquals("first turn", first.getLastAssistantText())
        } finally {
            first.close()
        }
        assertNotNull(file)

        val reopened = createAgentSession(options(fs, faux, sessionFile = file))
        try {
            val assistantTexts = reopened.messages.mapNotNull { it.asAssistantMessage() }
                .map { message -> message.content.filterIsInstance<TextContent>().joinToString("") { it.text } }
            assertEquals(listOf("first turn"), assistantTexts)
            reopened.prompt("turn two")
            assertEquals("second turn", reopened.getLastAssistantText())
            assertEquals(2, faux.state.callCount)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun sessionManagerReadsBackAppendedEntries() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/agent".toPath())
        val manager = SessionManager.create(dev.pi.coding.paths.AgentPaths("/work".toPath(), "/agent".toPath()), fs)
        manager.appendModelChange("faux", "faux-1")
        manager.appendThinkingLevelChange("high")
        val entries = manager.getEntries()
        assertEquals(2, entries.size)
        assertEquals("faux", manager.buildSessionContext().provider)
        assertEquals("high", manager.buildSessionContext().thinkingLevel)
        val file = manager.sessionFile
        assertNotNull(file)
        assertEquals(false, fs.exists(file))
    }
}
