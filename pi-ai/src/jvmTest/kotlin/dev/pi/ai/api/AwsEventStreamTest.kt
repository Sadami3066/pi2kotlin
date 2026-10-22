package dev.pi.ai.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.Buffer

class AwsEventStreamTest {
    private fun frame(eventType: String, payload: String): ByteArray = AwsEventStream.encodeMessage(
        headers = mapOf(
            ":message-type" to "event",
            ":event-type" to eventType,
            ":content-type" to "application/json",
        ),
        payload = payload.encodeToByteArray(),
    )

    @Test
    fun computesStandardCrc32() {
        assertEquals(0xCBF43926.toInt(), AwsEventStream.crc32("123456789".encodeToByteArray()))
    }

    @Test
    fun roundTripsHeadersAndPayload() {
        val payload = """{"role":"assistant"}""".encodeToByteArray()
        val encoded = AwsEventStream.encodeMessage(
            headers = mapOf(
                ":message-type" to "event",
                ":event-type" to "messageStart",
                ":content-type" to "application/json",
            ),
            payload = payload,
        )
        val message = AwsEventStream.readMessage(Buffer().write(encoded))
        assertTrue(message != null)
        assertEquals("event", message.headers[":message-type"])
        assertEquals("messageStart", message.headers[":event-type"])
        assertContentEquals(payload, message.payload)
        assertNull(AwsEventStream.readMessage(Buffer().write(encoded).also { it.readByteArray() }))
    }

    @Test
    fun encodesFrameLengthsAndCrcFields() {
        val encoded = frame("contentBlockDelta", """{"contentBlockIndex":0}""")
        val buffer = Buffer().write(encoded)
        val totalLength = buffer.readInt()
        val headersLength = buffer.readInt()
        val preludeCrc = buffer.readInt()
        assertEquals(encoded.size, totalLength)
        assertEquals(AwsEventStream.crc32(encoded, 0, 8), preludeCrc)
        assertTrue(headersLength > 0)
        assertEquals(AwsEventStream.crc32(encoded, 0, encoded.size - 4), Buffer().write(
            encoded.copyOfRange(encoded.size - 4, encoded.size),
        ).readInt())
    }

    @Test
    fun readsMultipleFramesInOrder() {
        val first = frame("messageStart", """{"role":"assistant"}""")
        val second = frame("messageStop", """{"stopReason":"end_turn"}""")
        val source = Buffer().write(first).write(second)
        val firstMessage = AwsEventStream.readMessage(source)
        val secondMessage = AwsEventStream.readMessage(source)
        assertEquals("messageStart", firstMessage?.headers?.get(":event-type"))
        assertEquals("messageStop", secondMessage?.headers?.get(":event-type"))
        assertNull(AwsEventStream.readMessage(source))
    }

    @Test
    fun decodesExceptionFrames() {
        val encoded = AwsEventStream.encodeMessage(
            headers = mapOf(
                ":message-type" to "exception",
                ":exception-type" to "ThrottlingException",
                ":content-type" to "application/json",
            ),
            payload = """{"message":"Too many requests"}""".encodeToByteArray(),
        )
        val message = AwsEventStream.readMessage(Buffer().write(encoded))
        assertEquals("exception", message?.headers?.get(":message-type"))
        assertEquals("ThrottlingException", message?.headers?.get(":exception-type"))
    }

    @Test
    fun rejectsCorruptedPreludeCrc() {
        val encoded = frame("text", """{"text":"hi"}""")
        encoded[8] = (encoded[8].toInt() xor 0xFF).toByte()
        val error = assertFailsWith<AwsEventStreamException> {
            AwsEventStream.readMessage(Buffer().write(encoded))
        }
        assertTrue(error.message!!.contains("prelude CRC"))
    }

    @Test
    fun rejectsCorruptedMessageCrc() {
        val encoded = frame("text", """{"text":"hi"}""")
        encoded[encoded.size - 5] = (encoded[encoded.size - 5].toInt() xor 0xFF).toByte()
        val error = assertFailsWith<AwsEventStreamException> {
            AwsEventStream.readMessage(Buffer().write(encoded))
        }
        assertTrue(error.message!!.contains("message CRC"))
    }

    @Test
    fun rejectsTruncatedFrames() {
        val encoded = frame("text", """{"text":"hi"}""")
        val error = assertFailsWith<AwsEventStreamException> {
            AwsEventStream.readMessage(Buffer().write(encoded.copyOfRange(0, encoded.size - 3)))
        }
        assertTrue(error.message!!.contains("Truncated"))
    }

    @Test
    fun rejectsTruncatedPrelude() {
        val encoded = frame("text", """{"text":"hi"}""")
        assertFailsWith<AwsEventStreamException> {
            AwsEventStream.readMessage(Buffer().write(encoded.copyOfRange(0, 6)))
        }
    }
}
