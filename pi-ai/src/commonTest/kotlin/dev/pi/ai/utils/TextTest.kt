package dev.pi.ai.utils

import dev.pi.ai.BlockContent
import dev.pi.ai.ImageContent
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ToolCall
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class TextTest {
    private val assistantContent = BlockContent(
        listOf(
            ThinkingContent("reasoning"),
            TextContent("first"),
            ToolCall(id = "1", name = "read", arguments = JsonObject(emptyMap())),
            TextContent("second"),
        ),
    )

    @Test
    fun contentTextExtractsAssistantTextBlocks() {
        assertEquals("first\nsecond", contentText(assistantContent))
    }

    @Test
    fun contentTextSupportsCustomSeparators() {
        assertEquals("firstsecond", contentText(assistantContent, ""))
    }

    @Test
    fun contentTextPassesPlainTextThrough() {
        assertEquals("hello", contentText(PlainTextContent("hello")))
    }

    @Test
    fun contentTextExtractsTextFromToolResultContent() {
        val content = BlockContent(
            listOf(
                TextContent("first"),
                ImageContent(data = "...", mimeType = "image/png"),
                TextContent("second"),
            ),
        )
        assertEquals("firstsecond", contentText(content, ""))
    }

    @Test
    fun contentTextReturnsEmptyWithoutTextBlocks() {
        assertEquals("", contentText(BlockContent(listOf(ImageContent(data = "x", mimeType = "image/png")))))
        assertEquals("", contentText(BlockContent(emptyList())))
    }

    @Test
    fun systemMessageTextRendersContentThenSections() {
        val message = SystemMessage(
            content = PlainTextContent("base"),
            sections = linkedMapOf("a" to "<a>1</a>", "b" to "<b>1</b>"),
            timestamp = 0,
        )
        assertEquals("base\n\n<a>1</a>\n\n<b>1</b>", getSystemMessageText(message))
    }

    @Test
    fun systemMessageTextSkipsNullSectionsAndEmptyParts() {
        val message = SystemMessage(
            content = PlainTextContent(""),
            sections = linkedMapOf("a" to null, "b" to "", "c" to "<c>1</c>"),
            timestamp = 0,
        )
        assertEquals("<c>1</c>", getSystemMessageText(message))
    }

    @Test
    fun rendersCompletePromptForSystemMessagesWithTextBlocks() {
        val message = SystemMessage(
            content = BlockContent(listOf(TextContent("one"), TextContent("two"))),
            sections = linkedMapOf("s" to "section"),
            timestamp = 0,
        )
        assertEquals("one\ntwo\n\nsection", getSystemMessageText(message))
    }

    @Test
    fun renderSystemMessageUpdateFramesSectionChangesByName() {
        val message = SystemMessage(
            content = PlainTextContent(""),
            sections = linkedMapOf("a" to "<a>2</a>", "b" to null, "c" to "<c>1</c>"),
            timestamp = 14,
        )
        val expected = listOf(
            "Updated system prompt section \"a\":\n\n<a>2</a>",
            "Removed system prompt section \"b\".",
            "Updated system prompt section \"c\":\n\n<c>1</c>",
        ).joinToString("\n\n")
        assertEquals(expected, renderSystemMessageUpdate(message))
    }

    @Test
    fun renderSystemMessageUpdateKeepsContentFirst() {
        val message = SystemMessage(
            content = PlainTextContent("also do this"),
            sections = linkedMapOf("a" to "x"),
            timestamp = 14,
        )
        assertEquals("also do this\n\nUpdated system prompt section \"a\":\n\nx", renderSystemMessageUpdate(message))
    }
}
