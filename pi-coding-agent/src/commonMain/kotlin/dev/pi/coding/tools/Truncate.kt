package dev.pi.coding.tools

const val DEFAULT_MAX_LINES = 2000
const val DEFAULT_MAX_BYTES = 50 * 1024
const val GREP_MAX_LINE_LENGTH = 500

data class Truncated(
    val text: String,
    val truncated: Boolean,
    val truncatedBy: String? = null,
)

fun truncateHead(
    text: String,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxBytes: Int = DEFAULT_MAX_BYTES,
): Truncated {
    val totalBytes = utf8Length(text)
    val lines = splitLinesForCounting(text)
    val totalLines = lines.size

    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return Truncated(text = text, truncated = false, truncatedBy = null)
    }

    val firstLineBytes = utf8Length(lines.firstOrNull().orEmpty())
    if (firstLineBytes > maxBytes) {
        return Truncated(text = "", truncated = true, truncatedBy = "bytes")
    }

    val outputLines = mutableListOf<String>()
    var outputBytes = 0
    var truncatedBy = "lines"

    var index = 0
    while (index < lines.size && index < maxLines) {
        val line = lines[index]
        val lineBytes = utf8Length(line) + if (index > 0) 1 else 0
        if (outputBytes + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            break
        }
        outputLines += line
        outputBytes += lineBytes
        index++
    }

    if (outputLines.size >= maxLines && outputBytes <= maxBytes) {
        truncatedBy = "lines"
    }

    return Truncated(text = outputLines.joinToString("\n"), truncated = true, truncatedBy = truncatedBy)
}

fun truncateTail(
    text: String,
    maxLines: Int = DEFAULT_MAX_LINES,
    maxBytes: Int = DEFAULT_MAX_BYTES,
): Truncated {
    val totalBytes = utf8Length(text)
    val lines = splitLinesForCounting(text)
    val totalLines = lines.size

    if (totalLines <= maxLines && totalBytes <= maxBytes) {
        return Truncated(text = text, truncated = false, truncatedBy = null)
    }

    val outputLines = ArrayDeque<String>()
    var outputBytes = 0
    var truncatedBy = "lines"

    var index = lines.size - 1
    while (index >= 0 && outputLines.size < maxLines) {
        val line = lines[index]
        val lineBytes = utf8Length(line) + if (outputLines.isNotEmpty()) 1 else 0
        if (outputBytes + lineBytes > maxBytes) {
            truncatedBy = "bytes"
            if (outputLines.isEmpty()) {
                val partial = truncateStringToBytesFromEnd(line, maxBytes)
                outputLines.addFirst(partial)
                outputBytes = utf8Length(partial)
            }
            break
        }
        outputLines.addFirst(line)
        outputBytes += lineBytes
        index--
    }

    if (outputLines.size >= maxLines && outputBytes <= maxBytes) {
        truncatedBy = "lines"
    }

    return Truncated(text = outputLines.joinToString("\n"), truncated = true, truncatedBy = truncatedBy)
}

fun truncateLine(line: String, maxChars: Int = GREP_MAX_LINE_LENGTH): String {
    if (line.length <= maxChars) return line
    return line.take(maxChars) + "... [truncated]"
}

fun formatSize(bytes: Int): String = formatSize(bytes.toLong())

fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${oneDecimal(bytes / 1024.0)}KB"
    else -> "${oneDecimal(bytes / (1024.0 * 1024.0))}MB"
}

private fun oneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

internal fun utf8Length(text: String): Int = text.encodeToByteArray().size

private fun splitLinesForCounting(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val lines = content.split("\n").toMutableList()
    if (content.endsWith("\n") && lines.isNotEmpty()) {
        lines.removeAt(lines.size - 1)
    }
    return lines
}

private fun truncateStringToBytesFromEnd(str: String, maxBytes: Int): String {
    val bytes = str.encodeToByteArray()
    if (bytes.size <= maxBytes) return str

    var start = bytes.size - maxBytes
    while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
        start++
    }
    return bytes.copyOfRange(start, bytes.size).decodeToString()
}
