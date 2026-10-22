package dev.pi.coding.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TruncateTest {
    @Test
    fun keepsShortContentUnchanged() {
        val result = truncateHead("line1\nline2")
        assertFalse(result.truncated)
        assertEquals(null, result.truncatedBy)
        assertEquals("line1\nline2", result.text)
    }

    @Test
    fun keepsEmptyContentUnchanged() {
        val result = truncateHead("")
        assertFalse(result.truncated)
        assertEquals("", result.text)
    }

    @Test
    fun truncatesHeadByLines() {
        val content = (1..5).joinToString("\n") { "line$it" }
        val result = truncateHead(content, maxLines = 3)
        assertTrue(result.truncated)
        assertEquals("lines", result.truncatedBy)
        assertEquals("line1\nline2\nline3", result.text)
    }

    @Test
    fun ignoresTrailingNewlineWhenCountingLines() {
        val result = truncateHead("a\nb\nc\n", maxLines = 3)
        assertFalse(result.truncated)
        assertEquals("a\nb\nc\n", result.text)
    }

    @Test
    fun truncatesHeadByBytesWithoutSplittingLines() {
        val lines = listOf("你你你", "你你你", "你你你").joinToString("\n")
        val result = truncateHead(lines, maxBytes = 20)
        assertTrue(result.truncated)
        assertEquals("bytes", result.truncatedBy)
        assertEquals("你你你\n你你你", result.text)
        assertEquals(19, utf8Length(result.text))
    }

    @Test
    fun reportsFirstLineExceedingByteLimit() {
        val result = truncateHead("a".repeat(100), maxBytes = 50)
        assertTrue(result.truncated)
        assertEquals("bytes", result.truncatedBy)
        assertEquals("", result.text)
    }

    @Test
    fun truncatesTailByLines() {
        val content = (1..5).joinToString("\n") { "line$it" }
        val result = truncateTail(content, maxLines = 2)
        assertTrue(result.truncated)
        assertEquals("lines", result.truncatedBy)
        assertEquals("line4\nline5", result.text)
    }

    @Test
    fun truncatesTailByBytes() {
        val content = (1..5).joinToString("\n") { "line$it" }
        val result = truncateTail(content, maxBytes = 20)
        assertTrue(result.truncated)
        assertEquals("bytes", result.truncatedBy)
        assertEquals("line3\nline4\nline5", result.text)
    }

    @Test
    fun takesPartialLineOnlyAtTailStart() {
        val result = truncateTail("a".repeat(100), maxBytes = 10)
        assertTrue(result.truncated)
        assertEquals("bytes", result.truncatedBy)
        assertEquals("aaaaaaaaaa", result.text)
    }

    @Test
    fun partialTailKeepsValidUtf8Boundaries() {
        val result = truncateTail("你你你", maxBytes = 4)
        assertTrue(result.truncated)
        assertEquals("你", result.text)
    }

    @Test
    fun truncatesSingleLines() {
        assertEquals("short", truncateLine("short", maxChars = 10))
        assertEquals("12345... [truncated]", truncateLine("123456789", maxChars = 5))
    }

    @Test
    fun formatsSizesLikeUpstream() {
        assertEquals("100B", formatSize(100))
        assertEquals("2.0KB", formatSize(2048))
        assertEquals("50.0KB", formatSize(DEFAULT_MAX_BYTES))
        assertEquals("1.0MB", formatSize(1024 * 1024))
    }
}
