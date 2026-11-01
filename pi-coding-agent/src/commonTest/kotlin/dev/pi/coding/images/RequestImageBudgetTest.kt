package dev.pi.coding.images

import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.PlainTextContent
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.UserMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RequestImageBudgetTest {

    private fun image(data: String): ImageContent = ImageContent(data = data, mimeType = "image/png")

    private fun user(text: String, vararg images: String): UserMessage = UserMessage(
        content = BlockContent(listOf<Content>(TextContent(text)) + images.map(::image)),
        timestamp = 0L,
    )

    private fun userText(text: String): UserMessage = UserMessage(PlainTextContent(text), timestamp = 0L)

    private fun toolResult(vararg blocks: Content): ToolResultMessage = ToolResultMessage(
        toolCallId = "call",
        toolName = "read",
        content = blocks.toList(),
        isError = false,
        timestamp = 0L,
    )

    private fun Message.images(): List<ImageContent> = when (this) {
        is UserMessage -> userBlocks(this).filterIsInstance<ImageContent>()
        is ToolResultMessage -> content.filterIsInstance<ImageContent>()
        else -> emptyList()
    }

    private fun List<Message>.imageData(): List<String> = flatMap { it.images() }.map { it.data }

    private fun userBlocks(message: UserMessage): List<Content> = (message.content as BlockContent).blocks

    private fun omittedTexts(message: Message): List<String> {
        val blocks = when (message) {
            is UserMessage -> userBlocks(message)
            is ToolResultMessage -> message.content
            else -> emptyList()
        }
        return blocks.filterIsInstance<TextContent>().map { it.text }.filter { it == REQUEST_IMAGE_OMITTED_MESSAGE }
    }

    @Test
    fun returnsSameListWhenNoImages() {
        val messages = listOf(userText("a"), userText("b"))
        assertSame(messages, limitRequestImages(messages, 1, 1))
    }

    @Test
    fun returnsSameListWhenImagesFitBudget() {
        val messages = listOf(user("a", "aaa"), toolResult(TextContent("done"), image("bbb")))
        assertSame(messages, limitRequestImages(messages, 2, 6))
    }

    @Test
    fun keepsOnlyTheNewestImagesWithinMaxImages() {
        val messages = listOf(user("one", "first"), user("two", "second"), user("three", "third"))

        val result = limitRequestImages(messages, 2, 1000)

        assertEquals(listOf("second", "third"), result.imageData())
        val omitted = userBlocks(result[0] as UserMessage)
        assertEquals(2, omitted.size)
        assertEquals(TextContent("one"), omitted[0])
        assertEquals(TextContent(REQUEST_IMAGE_OMITTED_MESSAGE), omitted[1])
    }

    @Test
    fun keepsImagesAtExactByteBudget() {
        val messages = listOf(user("a", "a".repeat(10)), user("b", "b".repeat(10)), user("c", "c".repeat(10)))

        val result = limitRequestImages(messages, 100, 20)

        assertEquals(listOf("b".repeat(10), "c".repeat(10)), result.imageData())
        assertEquals(1, result.sumOf { omittedTexts(it).size })
    }

    @Test
    fun replacesOldestImageWhenBytesExceedBudget() {
        val messages = listOf(user("a", "a".repeat(10)), user("b", "b".repeat(10)), user("c", "c".repeat(10)))

        val result = limitRequestImages(messages, 100, 19)

        assertEquals(listOf("c".repeat(10)), result.imageData())
        assertEquals(2, result.sumOf { omittedTexts(it).size })
    }

    @Test
    fun keepsNewestImageEvenWhenItExceedsMaxBytes() {
        val messages = listOf(user("a", "a".repeat(10)), user("b", "b".repeat(100)))

        val result = limitRequestImages(messages, 100, 10)

        assertEquals(listOf("b".repeat(100)), result.imageData())
        assertEquals(1, result.sumOf { omittedTexts(it).size })
    }

    @Test
    fun scansNewestImagesAcrossMessages() {
        val messages = listOf(
            user("user", "user-image"),
            toolResult(TextContent("Screenshot captured"), image("tool-image")),
        )

        val result = limitRequestImages(messages, 1, 1000)

        assertEquals(listOf("tool-image"), result.imageData())
        assertEquals(1, omittedTexts(result[0]).size)
        assertTrue(omittedTexts(result[1]).isEmpty())
    }

    @Test
    fun replacesImagesInPlaceAndKeepsBlockOrder() {
        val messages = listOf(userText("go"), toolResult(
            TextContent("before"),
            image("old-1"),
            TextContent("middle"),
            image("new"),
            TextContent("after"),
        ))

        val result = limitRequestImages(messages, 1, 1000)

        val blocks = (result[1] as ToolResultMessage).content
        assertEquals(
            listOf(
                TextContent("before"),
                TextContent(REQUEST_IMAGE_OMITTED_MESSAGE),
                TextContent("middle"),
                image("new"),
                TextContent("after"),
            ),
            blocks,
        )
    }

    @Test
    fun keepsOnlyNewestImageWhenMaxImagesIsZero() {
        val messages = listOf(user("a", "first"), user("b", "second"), user("c", "third"))

        val result = limitRequestImages(messages, 0, 1000)

        assertEquals(listOf("third"), result.imageData())
        assertEquals(2, result.sumOf { omittedTexts(it).size })
    }

    @Test
    fun countsBase64LengthWithoutDecoding() {
        val messages = listOf(user("a", "%%%not-base64%%%"), user("b", "###not-base64###"))

        val result = limitRequestImages(messages, 2, 16L * 2)

        assertSame(messages, result)
    }

    @Test
    fun isDeterministicAndIdempotent() {
        val messages = listOf(user("a", "aaaa"), user("b", "bbbb"), toolResult(image("cccc")))

        val first = limitRequestImages(messages, 2, 8)

        assertEquals(first, limitRequestImages(messages, 2, 8))
        assertEquals(first, limitRequestImages(first, 2, 8))
    }

    @Test
    fun handlesEmptyMessageList() {
        val messages = emptyList<Message>()
        assertSame(messages, limitRequestImages(messages, 2, 1000))
    }
}
