package dev.pi.coding.scenarios

import dev.pi.agent.asLlmMessage
import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
import dev.pi.ai.providers.FauxProviderState
import dev.pi.ai.providers.FauxResponseFactory
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxToolCall
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.images.REQUEST_IMAGE_OMITTED_MESSAGE
import dev.pi.coding.images.encodeBase64
import dev.pi.coding.session.AgentSession
import dev.pi.coding.tools.CodingTool
import dev.pi.coding.tools.imageResult
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestImageBudgetScenarioTest {

    private fun codedImage(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color(20, 80, 160)
            graphics.fillRect(0, 0, width, height)
            graphics.color = Color(250, 250, 250)
            graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
        } finally {
            graphics.dispose()
        }
        val output = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, "png", output))
        return output.toByteArray()
    }

    private fun screenshotTool(pngBytes: ByteArray): CodingTool = CodingTool(
        name = "screenshot",
        description = "Capture the current screen",
        parameters = buildJsonObject { },
        label = "Screenshot",
        execute = { _, _, _, _ -> imageResult(encodeBase64(pngBytes), "image/png", text = "Screenshot captured") },
    )

    private fun Message.images(): List<ImageContent> = when (this) {
        is UserMessage -> (content as? BlockContent)?.blocks?.filterIsInstance<ImageContent>().orEmpty()
        is ToolResultMessage -> content.filterIsInstance<ImageContent>()
        else -> emptyList()
    }

    private fun userBlocks(message: UserMessage): List<Content> = (message.content as BlockContent).blocks

    private fun imageData(message: Message): List<String> = message.images().map { it.data }

    private suspend fun createSession(
        fs: FileSystem,
        faux: dev.pi.ai.providers.FauxProviderHandle,
        tool: CodingTool,
    ): AgentSession = createAgentSession(
        AgentSessionOptions(
            cwd = ScenarioSupport.CWD.toPath(),
            agentDir = ScenarioSupport.AGENT_DIR.toPath(),
            transport = NoNetworkTransport,
            fs = fs,
            model = "faux/faux-1",
            tools = listOf(tool),
            providers = listOf(faux.provider),
        ),
    )

    @Test
    fun omitsOldImagesFromRequestsButKeepsSessionHistoryIntact() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, """{"images":{"autoResize":false,"requestMaxImages":2}}""")
            val faux = ScenarioSupport.faux()
            val contexts = mutableListOf<List<Message>>()
            val factory = object : FauxResponseFactory {
                override suspend fun produce(
                    context: TranscriptContext,
                    options: SimpleStreamOptions?,
                    state: FauxProviderState,
                    model: Model,
                ): AssistantMessage {
                    contexts.add(context.messages.toList())
                    return if (contexts.size == 1) {
                        fauxAssistantMessage(
                            content = listOf(fauxToolCall("screenshot", JsonObject(emptyMap()))),
                            stopReason = StopReason.ToolUse,
                        )
                    } else {
                        fauxAssistantMessage("done")
                    }
                }
            }
            faux.setResponses(listOf(FauxResponseStep.Scripted(factory), FauxResponseStep.Scripted(factory)))
            val promptImages = listOf(codedImage(20, 10), codedImage(30, 10), codedImage(40, 10))
            val promptData = promptImages.map { encodeBase64(it) }
            val toolImage = encodeBase64(codedImage(50, 10))
            val session = createSession(fs, faux, screenshotTool(codedImage(50, 10)))
            var sessionFile: okio.Path? = null
            try {
                session.prompt("look", images = promptData.map { ImageContent(it, "image/png") })
                sessionFile = session.sessionFile

                assertEquals(2, contexts.size)
                val firstRequest = contexts[0]
                val firstBlocks = userBlocks(firstRequest.filterIsInstance<UserMessage>().single())
                assertEquals(TextContent("look"), firstBlocks[0])
                assertEquals(TextContent(REQUEST_IMAGE_OMITTED_MESSAGE), firstBlocks[1])
                assertEquals(promptData.subList(1, 3), firstBlocks.filterIsInstance<ImageContent>().map { it.data })

                val secondRequest = contexts[1]
                val secondBlocks = userBlocks(secondRequest.filterIsInstance<UserMessage>().single())
                assertEquals(4, secondBlocks.size)
                assertEquals(TextContent("look"), secondBlocks[0])
                assertEquals(TextContent(REQUEST_IMAGE_OMITTED_MESSAGE), secondBlocks[1])
                assertEquals(TextContent(REQUEST_IMAGE_OMITTED_MESSAGE), secondBlocks[2])
                assertEquals(promptData[2], (secondBlocks[3] as ImageContent).data)
                val requestToolResult = secondRequest.filterIsInstance<ToolResultMessage>().single()
                assertEquals(TextContent("Screenshot captured"), requestToolResult.content[0])
                assertEquals(toolImage, (requestToolResult.content[1] as ImageContent).data)
                assertEquals(2, secondRequest.sumOf { it.images().size })

                val history = session.messages.mapNotNull { it.asLlmMessage() }
                val historyUser = history.filterIsInstance<UserMessage>().single()
                assertEquals(promptData, imageData(historyUser))
                val historyToolResult = history.filterIsInstance<ToolResultMessage>().single()
                assertEquals(listOf(toolImage), imageData(historyToolResult))
            } finally {
                session.close()
            }

            val sessionText = ScenarioSupport.read(fs, requireNotNull(sessionFile))
            promptData.forEach { assertTrue(sessionText.contains(it), "session history must keep the original image") }
            assertTrue(sessionText.contains(toolImage), "session history must keep the original tool result image")
            assertFalse(
                sessionText.contains(REQUEST_IMAGE_OMITTED_MESSAGE),
                "request image placeholders must not be persisted to the session jsonl",
            )
        }
    }
}
