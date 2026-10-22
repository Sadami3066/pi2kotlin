package dev.pi.ai.api

import kotlin.io.encoding.Base64
import okio.Buffer
import okio.BufferedSource
import okio.EOFException

data class AwsEventStreamMessage(
    val headers: Map<String, String>,
    val payload: ByteArray,
)

class AwsEventStreamException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

object AwsEventStream {
    const val PRELUDE_LENGTH: Int = 12
    private const val MAX_MESSAGE_BYTES: Int = 16 * 1024 * 1024

    fun readMessage(source: BufferedSource): AwsEventStreamMessage? {
        if (source.exhausted()) return null
        val prelude = try {
            source.readByteArray(PRELUDE_LENGTH.toLong())
        } catch (error: EOFException) {
            throw AwsEventStreamException("Truncated event stream prelude", error)
        }
        val totalLength = readInt32(prelude, 0)
        val headersLength = readInt32(prelude, 4)
        val preludeCrc = readInt32(prelude, 8)
        if (crc32(prelude, 0, 8) != preludeCrc) {
            throw AwsEventStreamException("Event stream prelude CRC mismatch")
        }
        if (totalLength < PRELUDE_LENGTH + 4 || headersLength < 0 ||
            headersLength > totalLength - PRELUDE_LENGTH - 4
        ) {
            throw AwsEventStreamException("Invalid event stream frame lengths: total=$totalLength headers=$headersLength")
        }
        if (totalLength > MAX_MESSAGE_BYTES) {
            throw AwsEventStreamException("Event stream frame too large: $totalLength bytes")
        }
        val remaining = try {
            source.readByteArray((totalLength - PRELUDE_LENGTH).toLong())
        } catch (error: EOFException) {
            throw AwsEventStreamException("Truncated event stream frame", error)
        }
        val message = ByteArray(totalLength)
        prelude.copyInto(message)
        remaining.copyInto(message, PRELUDE_LENGTH)
        val messageCrc = readInt32(message, totalLength - 4)
        if (crc32(message, 0, totalLength - 4) != messageCrc) {
            throw AwsEventStreamException("Event stream message CRC mismatch")
        }
        val headers = parseHeaders(message, PRELUDE_LENGTH, PRELUDE_LENGTH + headersLength)
        val payload = message.copyOfRange(PRELUDE_LENGTH + headersLength, totalLength - 4)
        return AwsEventStreamMessage(headers = headers, payload = payload)
    }

    fun encodeMessage(headers: Map<String, String>, payload: ByteArray): ByteArray {
        val headerBuffer = Buffer()
        for ((name, value) in headers) {
            val nameBytes = name.encodeToByteArray()
            val valueBytes = value.encodeToByteArray()
            if (nameBytes.size > 0xFF || valueBytes.size > 0xFFFF) {
                throw AwsEventStreamException("Event stream header too large: $name")
            }
            headerBuffer.writeByte(nameBytes.size)
            headerBuffer.write(nameBytes)
            headerBuffer.writeByte(HEADER_TYPE_STRING)
            headerBuffer.writeShort(valueBytes.size)
            headerBuffer.write(valueBytes)
        }
        val headerBytes = headerBuffer.readByteArray()
        val totalLength = PRELUDE_LENGTH + headerBytes.size + payload.size + 4
        val prefix = Buffer()
            .writeInt(totalLength)
            .writeInt(headerBytes.size)
            .readByteArray()
        val preludeCrc = Buffer().writeInt(crc32(prefix, 0, prefix.size)).readByteArray()
        val body = Buffer()
            .write(prefix)
            .write(preludeCrc)
            .write(headerBytes)
            .write(payload)
            .readByteArray()
        val messageCrc = crc32(body, 0, body.size)
        return Buffer().write(body).writeInt(messageCrc).readByteArray()
    }

    fun crc32(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        var crc = -1
        val end = offset + length
        for (index in offset until end) {
            crc = crc xor (bytes[index].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor CRC32_POLYNOMIAL else crc ushr 1
            }
        }
        return crc.inv()
    }

    private const val CRC32_POLYNOMIAL: Int = 0xEDB88320.toInt()
    private const val HEADER_TYPE_TRUE: Int = 0
    private const val HEADER_TYPE_FALSE: Int = 1
    private const val HEADER_TYPE_BYTE: Int = 2
    private const val HEADER_TYPE_SHORT: Int = 3
    private const val HEADER_TYPE_INTEGER: Int = 4
    private const val HEADER_TYPE_LONG: Int = 5
    private const val HEADER_TYPE_BYTE_ARRAY: Int = 6
    private const val HEADER_TYPE_STRING: Int = 7
    private const val HEADER_TYPE_TIMESTAMP: Int = 8
    private const val HEADER_TYPE_UUID: Int = 9

    private fun parseHeaders(bytes: ByteArray, start: Int, end: Int): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        var position = start
        while (position < end) {
            if (position + 1 > end) throw AwsEventStreamException("Truncated event stream header name")
            val nameLength = bytes[position].toInt() and 0xFF
            position += 1
            if (position + nameLength + 1 > end) throw AwsEventStreamException("Truncated event stream header")
            val name = bytes.decodeToString(position, position + nameLength)
            position += nameLength
            val type = bytes[position].toInt() and 0xFF
            position += 1
            val (value, next) = readHeaderValue(bytes, position, end, type)
            position = next
            headers[name] = value
        }
        return headers
    }

    private fun readHeaderValue(
        bytes: ByteArray,
        position: Int,
        end: Int,
        type: Int,
    ): Pair<String, Int> = when (type) {
        HEADER_TYPE_TRUE -> "true" to position
        HEADER_TYPE_FALSE -> "false" to position
        HEADER_TYPE_BYTE -> {
            requireBytes(position, 1, end)
            (bytes[position].toInt() and 0xFF).toString() to position + 1
        }

        HEADER_TYPE_SHORT -> {
            requireBytes(position, 2, end)
            readInt16(bytes, position).toString() to position + 2
        }

        HEADER_TYPE_INTEGER -> {
            requireBytes(position, 4, end)
            readInt32(bytes, position).toString() to position + 4
        }

        HEADER_TYPE_LONG -> {
            requireBytes(position, 8, end)
            readInt64(bytes, position).toString() to position + 8
        }

        HEADER_TYPE_BYTE_ARRAY -> {
            requireBytes(position, 2, end)
            val length = readInt16(bytes, position)
            requireBytes(position + 2, length, end)
            Base64.encode(bytes.copyOfRange(position + 2, position + 2 + length)) to position + 2 + length
        }

        HEADER_TYPE_STRING -> {
            requireBytes(position, 2, end)
            val length = readInt16(bytes, position)
            requireBytes(position + 2, length, end)
            bytes.decodeToString(position + 2, position + 2 + length) to position + 2 + length
        }

        HEADER_TYPE_TIMESTAMP -> {
            requireBytes(position, 8, end)
            readInt64(bytes, position).toString() to position + 8
        }

        HEADER_TYPE_UUID -> {
            requireBytes(position, 16, end)
            AwsSigV4.toHex(bytes.copyOfRange(position, position + 16)) to position + 16
        }

        else -> throw AwsEventStreamException("Unknown event stream header type: $type")
    }

    private fun requireBytes(position: Int, count: Int, end: Int) {
        if (count < 0 || position + count > end) {
            throw AwsEventStreamException("Truncated event stream header value")
        }
    }

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readInt64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
