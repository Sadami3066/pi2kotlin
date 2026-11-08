package io.github.whywood.deepdeep.sdk.coding.scenarios

import io.github.whywood.deepdeep.sdk.agent.asLlmMessage
import io.github.whywood.deepdeep.sdk.ai.AssistantMessage
import io.github.whywood.deepdeep.sdk.ai.BlockContent
import io.github.whywood.deepdeep.sdk.ai.ImageContent
import io.github.whywood.deepdeep.sdk.ai.Message
import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.ModelImageInputLimits
import io.github.whywood.deepdeep.sdk.ai.ModelImageResizeOptions
import io.github.whywood.deepdeep.sdk.ai.ModelInputLimits
import io.github.whywood.deepdeep.sdk.ai.SimpleStreamOptions
import io.github.whywood.deepdeep.sdk.ai.StopReason
import io.github.whywood.deepdeep.sdk.ai.TextContent
import io.github.whywood.deepdeep.sdk.ai.ToolResultMessage
import io.github.whywood.deepdeep.sdk.ai.TranscriptContext
import io.github.whywood.deepdeep.sdk.ai.UserMessage
import io.github.whywood.deepdeep.sdk.ai.providers.FauxModelDefinition
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderHandle
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderOptions
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderState
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseFactory
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.FauxTokenSize
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxProvider
import io.github.whywood.deepdeep.sdk.ai.providers.fauxToolCall
import io.github.whywood.deepdeep.sdk.coding.AgentSessionOptions
import io.github.whywood.deepdeep.sdk.coding.createAgentSession
import io.github.whywood.deepdeep.sdk.coding.images.decodeBase64
import io.github.whywood.deepdeep.sdk.coding.images.encodeBase64
import io.github.whywood.deepdeep.sdk.coding.session.AgentSession
import io.github.whywood.deepdeep.sdk.coding.tools.CodingTool
import io.github.whywood.deepdeep.sdk.coding.tools.imageResult
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageNormalizationScenarioTest {

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

    private fun decodeImage(data: String): BufferedImage =
        ImageIO.read(ByteArrayInputStream(requireNotNull(decodeBase64(data))))

    private fun captureResponses(
        contexts: MutableList<List<Message>>,
        toolName: String? = null,
        reply: String = "done",
    ): FauxResponseFactory = object : FauxResponseFactory {
        override suspend fun produce(
            context: TranscriptContext,
            options: SimpleStreamOptions?,
            state: FauxProviderState,
            model: Model,
        ): AssistantMessage {
            contexts.add(context.messages.toList())
            return if (contexts.size == 1 && toolName != null) {
                fauxAssistantMessage(
                    content = listOf(fauxToolCall(toolName, buildJsonObject { put("path", "screen") })),
                    stopReason = StopReason.ToolUse,
                )
            } else {
                fauxAssistantMessage(reply)
            }
        }
    }

    private fun fauxWithInputLimits(inputLimits: ModelInputLimits?): FauxProviderHandle = fauxProvider(
        FauxProviderOptions(
            provider = "faux",
            tokenSize = FauxTokenSize(1, 1),
            models = listOf(
                FauxModelDefinition(id = "faux-1", inputLimits = inputLimits),
            ),
        ),
    )

    private fun screenshotTool(pngBytes: ByteArray): CodingTool = CodingTool(
        name = "screenshot",
        description = "Capture the current screen",
        parameters = buildJsonObject { put("type", "object") },
        label = "Screenshot",
        execute = { _, _, _, _ -> imageResult(encodeBase64(pngBytes), "image/png", text = "Screenshot captured") },
    )

    private suspend fun createSession(
        fs: FileSystem,
        faux: FauxProviderHandle,
        tools: List<CodingTool>? = null,
    ): AgentSession = createAgentSession(
        AgentSessionOptions(
            cwd = ScenarioSupport.CWD.toPath(),
            agentDir = ScenarioSupport.AGENT_DIR.toPath(),
            transport = NoNetworkTransport,
            fs = fs,
            model = "faux/faux-1",
            tools = tools,
            providers = listOf(faux.provider),
        ),
    )

    private fun userBlocks(message: UserMessage): List<io.github.whywood.deepdeep.sdk.ai.Content> =
        (message.content as BlockContent).blocks

    @Test
    fun promptImagesAreNormalizedWithHints() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = fauxWithInputLimits(null)
            val contexts = mutableListOf<List<Message>>()
            faux.setResponses(listOf(FauxResponseStep.Scripted(captureResponses(contexts))))
            val session = createSession(fs, faux)
            try {
                session.prompt(
                    "describe",
                    images = listOf(ImageContent(encodeBase64(codedImage(2400, 1200)), "image/png")),
                )

                val request = contexts.single()
                val user = request.first { it is UserMessage } as UserMessage
                val blocks = userBlocks(user)
                val text = blocks.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                assertTrue(text.startsWith("describe"))
                assertTrue(
                    text.contains(
                        "[Image: original 2400x1200, displayed at 2000x1000. " +
                            "Multiply coordinates by 1.20 to map to original image.]",
                    ),
                    text,
                )
                val image = blocks.filterIsInstance<ImageContent>().single()
                val decoded = decodeImage(image.data)
                assertEquals(2000, decoded.width)
                assertEquals(1000, decoded.height)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun promptImagesUseModelInputLimits() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = fauxWithInputLimits(
                ModelInputLimits(
                    images = ModelImageInputLimits(
                        resize = ModelImageResizeOptions(maxWidth = 100, maxHeight = 100),
                    ),
                ),
            )
            val contexts = mutableListOf<List<Message>>()
            faux.setResponses(listOf(FauxResponseStep.Scripted(captureResponses(contexts))))
            val session = createSession(fs, faux)
            try {
                session.prompt(
                    "describe",
                    images = listOf(ImageContent(encodeBase64(codedImage(2400, 1200)), "image/png")),
                )

                val blocks = userBlocks(contexts.single().first { it is UserMessage } as UserMessage)
                assertTrue(
                    blocks.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                        .contains("displayed at 100x50"),
                )
                val decoded = decodeImage(blocks.filterIsInstance<ImageContent>().single().data)
                assertEquals(100, decoded.width)
                assertEquals(50, decoded.height)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun autoResizeDisabledKeepsPromptImageUntouched() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            ScenarioSupport.writeSettings(fs, """{"images":{"autoResize":false}}""")
            val faux = fauxWithInputLimits(null)
            val contexts = mutableListOf<List<Message>>()
            faux.setResponses(listOf(FauxResponseStep.Scripted(captureResponses(contexts))))
            val session = createSession(fs, faux)
            try {
                val original = codedImage(2400, 1200)
                session.prompt("describe", images = listOf(ImageContent(encodeBase64(original), "image/png")))

                val blocks = userBlocks(contexts.single().first { it is UserMessage } as UserMessage)
                assertEquals("describe", blocks.filterIsInstance<TextContent>().single().text)
                assertEquals(encodeBase64(original), blocks.filterIsInstance<ImageContent>().single().data)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun toolResultImagesAreNormalizedBeforeHistory() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = fauxWithInputLimits(null)
            val contexts = mutableListOf<List<Message>>()
            val factory = captureResponses(contexts, toolName = "screenshot")
            faux.setResponses(listOf(FauxResponseStep.Scripted(factory), FauxResponseStep.Scripted(factory)))
            val session = createSession(fs, faux, listOf(screenshotTool(codedImage(2400, 1200))))
            try {
                session.prompt("take a screenshot")

                val toolResult = session.messages.mapNotNull { it.asLlmMessage() as? ToolResultMessage }.single()
                val blocks = toolResult.content
                assertEquals("Screenshot captured", blocks.filterIsInstance<TextContent>().first().text)
                val decoded = decodeImage(blocks.filterIsInstance<ImageContent>().single().data)
                assertEquals(2000, decoded.width)
                assertEquals(1000, decoded.height)
                assertTrue(
                    blocks.filterIsInstance<TextContent>().last().text
                        .contains("[Image: original 2400x1200, displayed at 2000x1000."),
                )

                val historyBlocks = contexts[1].filterIsInstance<ToolResultMessage>().single().content
                val historyImage = decodeImage(historyBlocks.filterIsInstance<ImageContent>().single().data)
                assertEquals(2000, historyImage.width)
                assertEquals(1000, historyImage.height)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun toolResultImagesWithinLimitsAreNotRewritten() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = fauxWithInputLimits(null)
            val contexts = mutableListOf<List<Message>>()
            val factory = captureResponses(contexts, toolName = "screenshot")
            faux.setResponses(listOf(FauxResponseStep.Scripted(factory), FauxResponseStep.Scripted(factory)))
            val original = codedImage(100, 50)
            val session = createSession(fs, faux, listOf(screenshotTool(original)))
            try {
                session.prompt("take a screenshot")

                val blocks = session.messages.mapNotNull { it.asLlmMessage() as? ToolResultMessage }
                    .single()
                    .content
                assertEquals(2, blocks.size)
                assertEquals("Screenshot captured", (blocks[0] as TextContent).text)
                assertEquals(encodeBase64(original), (blocks[1] as ImageContent).data)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun keepsUndecodableToolResultImage() = runBlocking {
        withTimeout(20_000) {
            val fs = ScenarioSupport.fileSystem()
            val faux = fauxWithInputLimits(null)
            val contexts = mutableListOf<List<Message>>()
            val factory = captureResponses(contexts, toolName = "screenshot")
            faux.setResponses(listOf(FauxResponseStep.Scripted(factory), FauxResponseStep.Scripted(factory)))
            val invalid = encodeBase64("not-an-image".encodeToByteArray())
            val tool = CodingTool(
                name = "screenshot",
                description = "Capture the current screen",
                parameters = buildJsonObject { put("type", "object") },
                label = "Screenshot",
                execute = { _, _, _, _ -> imageResult(invalid, "image/png", text = "Screenshot captured") },
            )
            val session = createSession(fs, faux, listOf(tool))
            try {
                session.prompt("take a screenshot")

                val blocks = session.messages.mapNotNull { it.asLlmMessage() as? ToolResultMessage }
                    .single()
                    .content
                assertEquals(2, blocks.size)
                assertEquals(invalid, (blocks[1] as ImageContent).data)
            } finally {
                session.close()
            }
        }
    }
}
