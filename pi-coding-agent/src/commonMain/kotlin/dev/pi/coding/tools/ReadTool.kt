package dev.pi.coding.tools

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

private const val IMAGE_SNIFF_BYTES = 4100

private const val MAX_INLINE_IMAGE_BYTES = 8L * 1024 * 1024

private const val NEWLINE_BYTE = '\n'.code.toByte()

private val pngSignature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val amPmPattern = Regex(" (AM|PM)\\.", RegexOption.IGNORE_CASE)
private val inlineImageMimeTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

private val readParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("path") {
            put("type", "string")
            put("description", "Path to the file to read (relative or absolute)")
        }
        putJsonObject("offset") {
            put("type", "number")
            put("description", "Line number to start reading from (1-indexed)")
        }
        putJsonObject("limit") {
            put("type", "number")
            put("description", "Maximum number of lines to read")
        }
    }
    put("required", buildJsonArray { add("path") })
}

fun readTool(cwd: Path, fs: FileSystem): CodingTool = CodingTool(
    name = "read",
    description = "Read the contents of a file. Supports text files and images (jpg, png, gif, webp, bmp). Images are sent as attachments. For text files, output is truncated to $DEFAULT_MAX_LINES lines or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first). Use offset/limit for large files. When you need the full file, continue with offset until complete.",
    parameters = readParameters,
    label = "read",
    promptSnippet = "Read file contents",
    promptGuidelines = listOf("Use read to examine files instead of cat or sed."),
    execute = { _toolCallId, args, signal, _onUpdate ->
        signal?.throwIfAborted()
        val pathInput = args.argString("path")
        val offset = args.argIntOrNull("offset")
        val limit = args.argIntOrNull("limit")
        val absolutePath = resolveReadPath(cwd, pathInput, fs)
        val metadata = fs.metadataOrNull(absolutePath)
            ?: throw IllegalArgumentException("ENOENT: no such file or directory, access '$absolutePath'")
        if (metadata.isDirectory) {
            throw IllegalArgumentException("EISDIR: illegal operation on a directory, read")
        }
        signal?.throwIfAborted()
        val mimeType = detectImageMimeType(readHeadBytes(fs, absolutePath))
        when {
            mimeType == null -> textResult(readTextFile(fs, absolutePath, pathInput, offset, limit))
            mimeType in inlineImageMimeTypes -> imageResultForFile(fs, absolutePath, metadata.size, mimeType)
            else -> textResult(unsupportedImageMessage(mimeType))
        }
    },
)

private fun imageResultForFile(fs: FileSystem, path: Path, size: Long?, mimeType: String) = when {
    size != null && size > MAX_INLINE_IMAGE_BYTES -> textResult(oversizedImageMessage(mimeType, size))
    else -> {
        val bytes = if (size != null) {
            fs.read(path) { readByteArray() }
        } else {
            readBytesUpTo(fs, path, MAX_INLINE_IMAGE_BYTES)
        }
        if (bytes == null) {
            textResult("Read image file [$mimeType]\n[Image omitted: file exceeds ${formatSize(MAX_INLINE_IMAGE_BYTES)} inline limit.]")
        } else {
            imageResult(
                data = Base64.Default.encode(bytes),
                mimeType = mimeType,
                text = "Read image file [$mimeType]",
            )
        }
    }
}

private fun oversizedImageMessage(mimeType: String, size: Long): String =
    "Read image file [$mimeType]\n[Image omitted: file is ${formatSize(size)}, " +
        "exceeds ${formatSize(MAX_INLINE_IMAGE_BYTES)} inline limit.]"

private fun unsupportedImageMessage(mimeType: String): String =
    "Read image file [$mimeType]\n[Image omitted: could not be converted to a supported inline image format.]"

private fun readHeadBytes(fs: FileSystem, path: Path): ByteArray {
    val source = fs.source(path).buffer()
    try {
        val buffer = Buffer()
        var remaining = IMAGE_SNIFF_BYTES.toLong()
        while (remaining > 0L) {
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            remaining -= read
        }
        return buffer.readByteArray()
    } finally {
        source.close()
    }
}

private fun readBytesUpTo(fs: FileSystem, path: Path, maxBytes: Long): ByteArray? {
    val source = fs.source(path).buffer()
    try {
        val buffer = Buffer()
        var remaining = maxBytes + 1
        while (remaining > 0L) {
            val read = source.read(buffer, remaining)
            if (read == -1L) break
            remaining -= read
        }
        return if (buffer.size > maxBytes) null else buffer.readByteArray()
    } finally {
        source.close()
    }
}

private fun readTextFile(fs: FileSystem, path: Path, pathInput: String, offset: Int?, limit: Int?): String {
    val source = fs.source(path).buffer()
    try {
        return streamTextOutput(LineReader(source), pathInput, offset, limit)
    } finally {
        source.close()
    }
}

private fun streamTextOutput(
    reader: LineReader,
    pathInput: String,
    offset: Int?,
    limit: Int?,
): String {
    val startLine = if (offset != null) maxOf(0, offset - 1).toLong() else 0L
    val userLimit = limit?.toLong() ?: -1L
    val materialized = mutableListOf<String>()
    var materializedBytes = 0
    var truncated = false
    var truncatedBy: String? = null
    var firstSelectedLineBytes = 0
    var lineIndex = 0L
    while (true) {
        val selectedIndex = lineIndex - startLine
        val discarding = lineIndex < startLine ||
            truncated ||
            (userLimit >= 0L && selectedIndex >= userLimit)
        val line = reader.readLine(discarding) ?: break
        lineIndex += 1L
        if (discarding) continue
        val lineBytes = utf8Length(line)
        if (materialized.isEmpty()) {
            firstSelectedLineBytes = lineBytes
            if (lineBytes > DEFAULT_MAX_BYTES) {
                truncated = true
                truncatedBy = "bytes"
            } else {
                materialized.add(line)
                materializedBytes = lineBytes
            }
        } else if (materialized.size >= DEFAULT_MAX_LINES) {
            truncated = true
            truncatedBy = "lines"
        } else {
            val lineCost = lineBytes + 1
            if (materializedBytes + lineCost > DEFAULT_MAX_BYTES) {
                truncated = true
                truncatedBy = "bytes"
            } else {
                materialized.add(line)
                materializedBytes += lineCost
            }
        }
    }

    val totalLines = reader.newlineCount + 1
    if (startLine >= totalLines) {
        throw IllegalArgumentException("Offset $offset is beyond end of file ($totalLines lines total)")
    }
    val startLineDisplay = startLine + 1
    val output = materialized.joinToString("\n")
    val userLimitedLines = if (limit != null) minOf(limit.toLong(), totalLines - startLine) else null
    return when {
        truncated && output.isEmpty() -> {
            val firstLineSize = formatSize(firstSelectedLineBytes)
            "[Line $startLineDisplay is $firstLineSize, exceeds ${formatSize(DEFAULT_MAX_BYTES)} limit. Use bash: sed -n '${startLineDisplay}p' $pathInput | head -c $DEFAULT_MAX_BYTES]"
        }

        truncated -> {
            val outputLines = lineCount(output)
            val endLineDisplay = startLineDisplay + outputLines - 1
            val nextOffset = endLineDisplay + 1
            if (truncatedBy == "lines") {
                output +
                    "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalLines. Use offset=$nextOffset to continue.]"
            } else {
                output +
                    "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalLines (${formatSize(DEFAULT_MAX_BYTES)} limit). Use offset=$nextOffset to continue.]"
            }
        }

        userLimitedLines != null && startLine + userLimitedLines < totalLines -> {
            val remaining = totalLines - (startLine + userLimitedLines)
            val nextOffset = startLine + userLimitedLines + 1
            output + "\n\n[$remaining more lines in file. Use offset=$nextOffset to continue.]"
        }

        else -> output
    }
}

private class LineReader(private val source: BufferedSource) {
    var newlineCount: Long = 0L
        private set

    private var emitted = false
    private var lastTerminated = false
    private var finished = false

    fun readLine(discard: Boolean): String? {
        if (finished) return null
        if (source.exhausted()) {
            finished = true
            if (!emitted || lastTerminated) {
                emitted = true
                lastTerminated = false
                return ""
            }
            return null
        }
        val newlineIndex = source.indexOf(NEWLINE_BYTE)
        if (newlineIndex < 0L) {
            val text = if (discard) {
                source.skip(source.buffer.size)
                ""
            } else {
                source.readUtf8()
            }
            finished = true
            emitted = true
            lastTerminated = false
            return text
        }
        val text = if (discard) {
            source.skip(newlineIndex + 1)
            ""
        } else {
            val content = source.readUtf8(newlineIndex)
            source.skip(1)
            content
        }
        newlineCount += 1L
        emitted = true
        lastTerminated = true
        return text
    }
}

private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

private fun resolveReadPath(cwd: Path, input: String, fs: FileSystem): Path {
    val resolved = resolveToolPath(cwd, input)
    if (exists(fs, resolved)) return resolved

    val resolvedText = resolved.toString()
    val amPmVariant = replaceAmPm(resolvedText)
    if (amPmVariant != resolvedText) {
        val candidate = amPmVariant.toPath()
        if (exists(fs, candidate)) return candidate
    }
    val curlyVariant = resolvedText.replace('\'', '\u2019')
    if (curlyVariant != resolvedText) {
        val candidate = curlyVariant.toPath()
        if (exists(fs, candidate)) return candidate
    }
    if (amPmVariant != resolvedText) {
        val combined = amPmVariant.replace('\'', '\u2019')
        val candidate = combined.toPath()
        if (exists(fs, candidate)) return candidate
    }
    return resolved
}

private fun replaceAmPm(text: String): String =
    amPmPattern.replace(text) { match -> "\u202F${match.groupValues[1]}." }

private fun exists(fs: FileSystem, path: Path): Boolean = runCatching { fs.exists(path) }.getOrDefault(false)

private fun detectImageMimeType(bytes: ByteArray): String? {
    val buffer = if (bytes.size > IMAGE_SNIFF_BYTES) bytes.copyOf(IMAGE_SNIFF_BYTES) else bytes
    if (startsWith(buffer, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
        return if (buffer.size > 3 && buffer[3] == 0xF7.toByte()) null else "image/jpeg"
    }
    if (startsWith(buffer, pngSignature)) {
        return if (isPng(buffer) && !isAnimatedPng(buffer)) "image/png" else null
    }
    if (startsWithAscii(buffer, 0, "GIF87a") || startsWithAscii(buffer, 0, "GIF89a")) return "image/gif"
    if (startsWithAscii(buffer, 0, "RIFF") && startsWithAscii(buffer, 8, "WEBP")) return "image/webp"
    if (startsWithAscii(buffer, 0, "BM") && isBmp(buffer)) return "image/bmp"
    return null
}

private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
    if (bytes.size < prefix.size) return false
    for (index in prefix.indices) {
        if (bytes[index] != prefix[index]) return false
    }
    return true
}

private fun startsWithAscii(bytes: ByteArray, offset: Int, ascii: String): Boolean {
    if (offset < 0 || bytes.size < offset + ascii.length) return false
    for (index in ascii.indices) {
        if (bytes[offset + index].toInt() != ascii[index].code) return false
    }
    return true
}

private fun readUint32Be(bytes: ByteArray, offset: Int): Long {
    if (offset + 4 > bytes.size) return 0
    return ((bytes[offset].toLong() and 0xFF) shl 24) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
        (bytes[offset + 3].toLong() and 0xFF)
}

private fun readUint32Le(bytes: ByteArray, offset: Int): Long {
    if (offset + 4 > bytes.size) return 0
    return (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}

private fun readUint16Le(bytes: ByteArray, offset: Int): Int {
    if (offset + 2 > bytes.size) return 0
    return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}

private fun isPng(buffer: ByteArray): Boolean =
    buffer.size >= 16 && readUint32Be(buffer, pngSignature.size) == 13L && startsWithAscii(buffer, 12, "IHDR")

private fun isAnimatedPng(buffer: ByteArray): Boolean {
    var offset = pngSignature.size
    while (offset + 8 <= buffer.size) {
        val chunkLength = readUint32Be(buffer, offset)
        val chunkTypeOffset = offset + 4
        if (startsWithAscii(buffer, chunkTypeOffset, "acTL")) return true
        if (startsWithAscii(buffer, chunkTypeOffset, "IDAT")) return false
        val nextOffset = offset + 8 + chunkLength + 4
        if (nextOffset <= offset || nextOffset > buffer.size) return false
        offset = nextOffset.toInt()
    }
    return false
}

private fun isBmp(buffer: ByteArray): Boolean {
    if (buffer.size < 26) return false
    val declaredFileSize = readUint32Le(buffer, 2)
    val pixelDataOffset = readUint32Le(buffer, 10)
    val dibHeaderSize = readUint32Le(buffer, 14)
    if (declaredFileSize != 0L && declaredFileSize < 26) return false
    if (pixelDataOffset < 14 + dibHeaderSize) return false
    if (declaredFileSize != 0L && pixelDataOffset >= declaredFileSize) return false

    val colorPlanes: Int
    val bitsPerPixel: Int
    if (dibHeaderSize == 12L) {
        colorPlanes = readUint16Le(buffer, 22)
        bitsPerPixel = readUint16Le(buffer, 24)
    } else if (dibHeaderSize in 40L..124L) {
        if (buffer.size < 30) return false
        colorPlanes = readUint16Le(buffer, 26)
        bitsPerPixel = readUint16Le(buffer, 28)
    } else {
        return false
    }

    return colorPlanes == 1 && bitsPerPixel in listOf(1, 4, 8, 16, 24, 32)
}
