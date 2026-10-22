package dev.pi.coding.compaction

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.Message
import dev.pi.ai.MessageContent
import dev.pi.ai.PlainTextContent
import dev.pi.ai.TextContent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import kotlin.math.ceil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val ESTIMATED_IMAGE_CHARS = 4800
internal const val TOOL_RESULT_MAX_CHARS = 2000

class FileOperations internal constructor(
    val read: MutableSet<String> = LinkedHashSet(),
    val written: MutableSet<String> = LinkedHashSet(),
    val edited: MutableSet<String> = LinkedHashSet(),
)

fun createFileOps(): FileOperations = FileOperations()

data class FileLists(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

fun extractFileOpsFromMessage(message: Message, fileOps: FileOperations) {
    when (message) {
        is ToolResultMessage -> message.nestedCalls?.calls?.forEach { call ->
            addFileOp(call.name, call.arguments, fileOps)
        }

        is AssistantMessage -> message.content.forEach { block ->
            if (block is ToolCall) addFileOp(block.name, block.arguments, fileOps)
        }

        else -> Unit
    }
}

private fun addFileOp(toolName: String, args: JsonObject?, fileOps: FileOperations) {
    val path = args?.get("path")?.let { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    } ?: return
    when (toolName) {
        "read" -> fileOps.read.add(path)
        "write" -> fileOps.written.add(path)
        "edit" -> fileOps.edited.add(path)
    }
}

fun computeFileLists(fileOps: FileOperations): FileLists {
    val modified = LinkedHashSet<String>()
    modified.addAll(fileOps.edited)
    modified.addAll(fileOps.written)
    val readOnly = fileOps.read.filter { it !in modified }.sorted()
    val modifiedFiles = modified.sorted()
    return FileLists(readOnly, modifiedFiles)
}

fun formatFileOperations(readFiles: List<String>, modifiedFiles: List<String>): String {
    val sections = mutableListOf<String>()
    if (readFiles.isNotEmpty()) {
        sections.add("<read-files>\n${readFiles.joinToString("\n")}\n</read-files>")
    }
    if (modifiedFiles.isNotEmpty()) {
        sections.add("<modified-files>\n${modifiedFiles.joinToString("\n")}\n</modified-files>")
    }
    if (sections.isEmpty()) return ""
    return "\n\n${sections.joinToString("\n\n")}"
}

fun truncateForSummary(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    val truncatedChars = text.length - maxChars
    return "${text.take(maxChars)}\n\n[... $truncatedChars more characters truncated]"
}

fun contentText(content: MessageContent, separator: String = "\n"): String = when (content) {
    is PlainTextContent -> content.text
    is BlockContent -> contentText(content.blocks, separator)
}

fun contentText(content: List<Content>, separator: String = "\n"): String = content
    .filterIsInstance<TextContent>()
    .joinToString(separator) { it.text }

fun serializeConversation(messages: List<Message>): String {
    val parts = mutableListOf<String>()
    for (message in messages) {
        when (message) {
            is dev.pi.ai.UserMessage -> {
                val content = contentText(message.content, "")
                if (content.isNotEmpty()) parts.add("[User]: $content")
            }

            is AssistantMessage -> {
                val toolCalls = mutableListOf<String>()
                for (block in message.content) {
                    if (block is ToolCall) {
                        val argsString = block.arguments.entries.joinToString(", ") { (key, value) ->
                            "$key=${jsonStringify(value)}"
                        }
                        toolCalls.add("${block.name}($argsString)")
                    }
                }
                if (message.content.any { it is TextContent }) {
                    parts.add("[Assistant]: ${contentText(message.content)}")
                }
                if (toolCalls.isNotEmpty()) {
                    parts.add("[Assistant tool calls]: ${toolCalls.joinToString("; ")}")
                }
            }

            is ToolResultMessage -> {
                val content = contentText(message.content, "")
                if (content.isNotEmpty()) {
                    parts.add("[Tool result]: ${truncateForSummary(content, TOOL_RESULT_MAX_CHARS)}")
                }
            }

            else -> Unit
        }
    }
    return parts.joinToString("\n\n")
}

internal fun jsonStringify(element: JsonElement): String = element.toString()

internal fun estimateTextAndImageContentChars(content: MessageContent): Long = when (content) {
    is PlainTextContent -> content.text.length.toLong()
    is BlockContent -> estimateTextAndImageContentChars(content.blocks)
}

internal fun estimateTextAndImageContentChars(blocks: List<Content>): Long {
    var chars = 0L
    for (block in blocks) {
        when (block) {
            is TextContent -> chars += block.text.length
            is dev.pi.ai.ImageContent -> chars += ESTIMATED_IMAGE_CHARS
            else -> Unit
        }
    }
    return chars
}

internal fun estimateCharsToTokens(chars: Long): Long = ceil(chars / 4.0).toLong()

internal fun combineUsage(first: Usage, second: Usage): Usage = Usage(
    input = first.input + second.input,
    output = first.output + second.output,
    cacheRead = first.cacheRead + second.cacheRead,
    cacheWrite = first.cacheWrite + second.cacheWrite,
    cacheWrite1h = if (first.cacheWrite1h != null || second.cacheWrite1h != null) {
        (first.cacheWrite1h ?: 0L) + (second.cacheWrite1h ?: 0L)
    } else {
        null
    },
    reasoning = if (first.reasoning != null || second.reasoning != null) {
        (first.reasoning ?: 0L) + (second.reasoning ?: 0L)
    } else {
        null
    },
    totalTokens = first.totalTokens + second.totalTokens,
    cost = UsageCost(
        input = first.cost.input + second.cost.input,
        output = first.cost.output + second.cost.output,
        cacheRead = first.cost.cacheRead + second.cost.cacheRead,
        cacheWrite = first.cost.cacheWrite + second.cost.cacheWrite,
        total = first.cost.total + second.cost.total,
    ),
)

internal fun jsonStringList(value: JsonElement?): List<String> {
    if (value !is JsonArray) return emptyList()
    return value.mapNotNull { element ->
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

internal fun encodeToolsJson(tools: List<dev.pi.ai.Tool>): JsonArray = JsonArray(
    tools.map { tool ->
        val result = LinkedHashMap<String, JsonElement>()
        result["name"] = JsonPrimitive(tool.name)
        result["description"] = JsonPrimitive(tool.description)
        result["parameters"] = tool.parameters
        tool.constrainedSampling?.let { result["constrainedSampling"] = it }
        JsonObject(result)
    },
)

internal fun sessionTimestampToEpochMillis(value: String): Long {
    if (value.length < 19) return 0L
    if (value[4] != '-' || value[7] != '-' || value[13] != ':' || value[16] != ':') return 0L
    if (value[10] != 'T' && value[10] != 't' && value[10] != ' ') return 0L
    val year = value.substring(0, 4).toIntOrNull() ?: return 0L
    val month = value.substring(5, 7).toIntOrNull() ?: return 0L
    val day = value.substring(8, 10).toIntOrNull() ?: return 0L
    val hour = value.substring(11, 13).toIntOrNull() ?: return 0L
    val minute = value.substring(14, 16).toIntOrNull() ?: return 0L
    val second = value.substring(17, 19).toIntOrNull() ?: return 0L
    if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return 0L
    var position = 19
    var millis = 0L
    if (position < value.length && (value[position] == '.' || value[position] == ',')) {
        position += 1
        val fractionStart = position
        while (position < value.length && value[position].isDigit()) position += 1
        val fraction = value.substring(fractionStart, position)
        if (fraction.isEmpty()) return 0L
        millis = fraction.padEnd(3, '0').substring(0, 3).toLongOrNull() ?: return 0L
    }
    var offsetMinutes = 0L
    if (position < value.length) {
        when (val zone = value[position]) {
            'Z', 'z' -> position += 1
            '+', '-' -> {
                val compact = position + 5 <= value.length && value[position + 3] == ':'
                val offsetHourStart = position + 1
                val offsetHourEnd = offsetHourStart + 2
                if (offsetHourEnd > value.length) return 0L
                val offsetHour = value.substring(offsetHourStart, offsetHourEnd).toIntOrNull() ?: return 0L
                val offsetMinute = when {
                    compact && position + 6 <= value.length ->
                        value.substring(position + 4, position + 6).toIntOrNull() ?: return 0L

                    !compact && position + 5 <= value.length ->
                        value.substring(position + 3, position + 5).toIntOrNull() ?: return 0L

                    else -> 0
                }
                offsetMinutes = offsetHour * 60L + offsetMinute
                if (zone == '-') offsetMinutes = -offsetMinutes
                position += if (compact) 6 else 5
            }

            else -> return 0L
        }
    }
    if (position != value.length) return 0L
    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3_600L + minute * 60L + second
    return (seconds - offsetMinutes * 60L) * 1_000L + millis
}

private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    var adjustedYear = year.toLong()
    if (month <= 2) adjustedYear -= 1
    val era = (if (adjustedYear >= 0) adjustedYear else adjustedYear - 399L) / 400L
    val yearOfEra = adjustedYear - era * 400L
    val monthPrime = if (month > 2) month - 3L else month + 9L
    val dayOfYear = (153L * monthPrime + 2L) / 5L + day - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}

internal fun currentTimeMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
