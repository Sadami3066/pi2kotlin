package dev.pi.ai.http

import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseParserTest {
    @Test
    fun parsesSingleEvent() = runTest {
        val parser = parser("data: hello\n\n")
        assertEquals(SseEvent(null, "hello", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun joinsMultipleDataLines() = runTest {
        val parser = parser("data: first\ndata: second\ndata: third\n\n")
        assertEquals(SseEvent(null, "first\nsecond\nthird", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun readsEventIdAndRetryFields() = runTest {
        val parser = parser("event: message_start\nid: 42\nretry: 1500\ndata: payload\n\n")
        assertEquals(SseEvent("message_start", "payload", "42", 1500L), parser.next())
    }

    @Test
    fun ignoresCommentsAndHeartbeats() = runTest {
        val parser = parser(": keep-alive\n\n: another\n\ndata: value\n\n")
        assertEquals(SseEvent(null, "value", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun dispatchesEventWithoutData() = runTest {
        val parser = parser("event: ping\n\n")
        assertEquals(SseEvent("ping", "", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun flushesTrailingEventAtEof() = runTest {
        val parser = parser("data: tail")
        assertEquals(SseEvent(null, "tail", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun flushesTrailingEventWithoutBlankLine() = runTest {
        val parser = parser("event: message_stop\ndata: {\"type\":\"message_stop\"}")
        assertEquals(SseEvent("message_stop", "{\"type\":\"message_stop\"}", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun handlesCrlfLineEndings() = runTest {
        val parser = parser("event: delta\r\ndata: one\r\ndata: two\r\n\r\n")
        assertEquals(SseEvent("delta", "one\ntwo", null, null), parser.next())
    }

    @Test
    fun ignoresUnknownFields() = runTest {
        val parser = parser("unknown: ignored\ndata: value\n\n")
        assertEquals(SseEvent(null, "value", null, null), parser.next())
    }

    @Test
    fun treatsFieldWithoutColonAsEmptyValue() = runTest {
        val parser = parser("data\n\n")
        assertEquals(SseEvent(null, "", null, null), parser.next())
    }

    @Test
    fun acceptsEmptyDataValue() = runTest {
        val parser = parser("data:\n\n")
        assertEquals(SseEvent(null, "", null, null), parser.next())
    }

    @Test
    fun stripsOnlyOneLeadingSpaceFromValue() = runTest {
        val parser = parser("data:  two spaces\n\n")
        assertEquals(SseEvent(null, " two spaces", null, null), parser.next())
    }

    @Test
    fun ignoresNonNumericRetry() = runTest {
        val parser = parser("retry: soon\ndata: value\n\n")
        assertEquals(SseEvent(null, "value", null, null), parser.next())
    }

    @Test
    fun resetsIdAndRetryPerEvent() = runTest {
        val parser = parser("id: one\nretry: 10\ndata: a\n\ndata: b\n\n")
        assertEquals(SseEvent(null, "a", "one", 10L), parser.next())
        assertEquals(SseEvent(null, "b", null, null), parser.next())
    }

    @Test
    fun passesDoneSentinelAsData() = runTest {
        val parser = parser("data: [DONE]\n\n")
        assertEquals(SseEvent(null, "[DONE]", null, null), parser.next())
    }

    @Test
    fun parsesEventsAcrossChunkBoundaries() = runTest {
        val source = ChunkedSource(listOf("da", "ta: hel", "lo\n\nda", "ta: world\n\n"))
        val parser = SseParser(source.buffer())
        assertEquals(SseEvent(null, "hello", null, null), parser.next())
        assertEquals(SseEvent(null, "world", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun parsesEventsAcrossSingleCharacterChunks() = runTest {
        val text = "event: delta\ndata: {\"value\":1}\n\ndata: {\"value\":2}\n\n"
        val source = ChunkedSource(text.map { it.toString() })
        val parser = SseParser(source.buffer())
        assertEquals(SseEvent("delta", "{\"value\":1}", null, null), parser.next())
        assertEquals(SseEvent(null, "{\"value\":2}", null, null), parser.next())
        assertNull(parser.next())
    }

    @Test
    fun returnsNullAfterClose() = runTest {
        val source = ChunkedSource(listOf("data: value\n\n"))
        val parser = SseParser(source.buffer())
        parser.close()
        assertNull(parser.next())
    }

    @Test
    fun closeIsIdempotent() = runTest {
        val source = ChunkedSource(listOf("data: value\n\n"))
        val parser = SseParser(source.buffer())
        parser.close()
        parser.close()
        assertNull(parser.next())
    }

    private fun parser(text: String): SseParser = SseParser(Buffer().writeUtf8(text))

    private class ChunkedSource(chunks: List<String>) : Source {
        private val parts = ArrayDeque(chunks.map { it.encodeToByteArray() })
        private var closed = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (closed) return -1L
            val part = parts.firstOrNull() ?: return -1L
            val count = minOf(byteCount, part.size.toLong()).toInt()
            sink.write(part, 0, count)
            if (count == part.size) {
                parts.removeFirst()
            } else {
                parts[0] = part.copyOfRange(count, part.size)
            }
            return count.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() {
            closed = true
            parts.clear()
        }
    }
}
