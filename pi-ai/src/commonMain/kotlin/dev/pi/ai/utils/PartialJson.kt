package dev.pi.ai.utils

import dev.pi.ai.JsonObject
import dev.pi.ai.JsonValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

data class ToolArguments(
    val arguments: JsonObject,
    val argumentsError: String? = null,
)

object PartialJson {
    fun parse(text: String): JsonValue? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        parseStrict(trimmed)?.let { return it }
        val repaired = repair(trimmed)
        val hasRepairs = repaired != trimmed
        if (hasRepairs) {
            parseStrict(repaired)?.let { return it }
        }
        parsePartial(trimmed)?.let { return it }
        if (hasRepairs) {
            parsePartial(repaired)?.let { return it }
        }
        return null
    }

    fun repair(text: String): String {
        val repaired = StringBuilder(text.length)
        var inString = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (!inString) {
                repaired.append(char)
                if (char == '"') inString = true
                index += 1
                continue
            }
            when {
                char == '"' -> {
                    repaired.append(char)
                    inString = false
                    index += 1
                }

                char == '\\' -> {
                    val next = text.getOrNull(index + 1)
                    when {
                        next == null -> {
                            repaired.append("\\\\")
                            index += 1
                        }

                        next == 'u' && isUnicodeEscape(text, index) -> {
                            repaired.append("\\u").append(text, index + 2, index + 6)
                            index += 6
                        }

                        next == 'u' -> {
                            repaired.append("\\\\")
                            index += 1
                        }

                        isJsonEscape(next) -> {
                            repaired.append('\\').append(next)
                            index += 2
                        }

                        else -> {
                            repaired.append("\\\\")
                            index += 1
                        }
                    }
                }

                isControlCharacter(char) -> {
                    repaired.append(escapeControlCharacter(char))
                    index += 1
                }

                else -> {
                    repaired.append(char)
                    index += 1
                }
            }
        }
        return repaired.toString()
    }

    fun parseToolCallArguments(json: String?): ToolArguments {
        val text = json?.takeUnless { it.isBlank() } ?: return ToolArguments(JsonObject(emptyMap()))
        val parsed = try {
            parseWithRepair(text)
        } catch (error: Exception) {
            return ToolArguments(
                arguments = JsonObject(emptyMap()),
                argumentsError = "${error.message ?: "Invalid JSON"}\n\nReceived arguments:\n$text",
            )
        }
        if (parsed !is JsonObject) {
            return ToolArguments(
                arguments = JsonObject(emptyMap()),
                argumentsError = "Tool arguments must be a JSON object\n\nReceived arguments:\n$text",
            )
        }
        return ToolArguments(parsed)
    }

    private fun parseWithRepair(text: String): JsonValue {
        try {
            return StrictJsonParser(text).parse()
        } catch (original: JsonSyntaxException) {
            val repaired = repair(text)
            if (repaired == text) throw original
            return StrictJsonParser(repaired).parse()
        }
    }

    private fun parseStrict(text: String): JsonValue? = try {
        StrictJsonParser(text).parse()
    } catch (_: JsonSyntaxException) {
        null
    }

    private fun parsePartial(text: String): JsonValue? = try {
        PartialJsonParser(text).parse()
    } catch (_: Exception) {
        null
    }
}

private fun isUnicodeEscape(text: String, backslashIndex: Int): Boolean {
    val digitsStart = backslashIndex + 2
    val digitsEnd = minOf(backslashIndex + 6, text.length)
    if (digitsEnd - digitsStart != 4) return false
    for (position in digitsStart until digitsEnd) {
        if (!isHexDigit(text[position])) return false
    }
    return true
}

private fun isHexDigit(char: Char): Boolean =
    char in '0'..'9' || char in 'a'..'f' || char in 'A'..'F'

private fun isJsonEscape(char: Char): Boolean = when (char) {
    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> true
    else -> false
}

private fun isControlCharacter(char: Char): Boolean = char.code in 0x00..0x1F

private fun escapeControlCharacter(char: Char): String = when (char) {
    '\b' -> "\\b"
    '\u000C' -> "\\f"
    '\n' -> "\\n"
    '\r' -> "\\r"
    '\t' -> "\\t"
    else -> "\\u" + char.code.toString(16).padStart(4, '0')
}

private class PartialJsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue = parseAny()

    private fun parseAny(): JsonValue {
        skipWhitespace()
        if (index >= text.length) throw PartialJsonException("Unexpected end of input")
        return when (text[index]) {
            '"' -> JsonPrimitive(parseString())
            '{' -> parseObject()
            '[' -> parseArray()
            else -> parseKeywordOrNumber()
        }
    }

    private fun parseKeywordOrNumber(): JsonValue {
        val remaining = text.length - index
        val rest = text.substring(index)
        if (text.startsWith("null", index) || (remaining < 4 && "null".startsWith(rest))) {
            index += 4
            return JsonNull
        }
        if (text.startsWith("true", index) || (remaining < 4 && "true".startsWith(rest))) {
            index += 4
            return JsonPrimitive(true)
        }
        if (text.startsWith("false", index) || (remaining < 5 && "false".startsWith(rest))) {
            index += 5
            return JsonPrimitive(false)
        }
        if (text.startsWith("Infinity", index) || (remaining < 8 && "Infinity".startsWith(rest))) {
            index += 8
            return JsonPrimitive(Double.POSITIVE_INFINITY)
        }
        if (text.startsWith("-Infinity", index) || (remaining in 2 until 9 && "-Infinity".startsWith(rest))) {
            index += 9
            return JsonPrimitive(Double.NEGATIVE_INFINITY)
        }
        if (text.startsWith("NaN", index) || (remaining < 3 && "NaN".startsWith(rest))) {
            index += 3
            return JsonPrimitive(Double.NaN)
        }
        return parseNumber()
    }

    private fun parseString(): String {
        val start = index
        var escaped = false
        index += 1
        while (index < text.length && (text[index] != '"' || (escaped && text[index - 1] == '\\'))) {
            escaped = if (text[index] == '\\') !escaped else false
            index += 1
        }
        if (index < text.length) {
            val literal = text.substring(start, index + 1)
            index += 1
            return decodeStringLiteral(literal) ?: throw MalformedJsonException("Invalid string literal")
        }
        val cut = if (escaped) index - 1 else index
        decodeStringLiteral(text.substring(start, cut) + "\"")?.let { return it }
        val lastBackslash = text.lastIndexOf('\\')
        if (lastBackslash > start) {
            decodeStringLiteral(text.substring(start, lastBackslash) + "\"")?.let { return it }
        }
        throw MalformedJsonException("Unterminated string literal")
    }

    private fun parseObject(): JsonValue {
        index += 1
        skipWhitespace()
        val entries = LinkedHashMap<String, JsonValue>()
        try {
            while (index >= text.length || text[index] != '}') {
                skipWhitespace()
                if (index >= text.length) return JsonObject(entries)
                val key = parseString()
                skipWhitespace()
                index += 1
                try {
                    entries[key] = parseAny()
                } catch (_: Exception) {
                    return JsonObject(entries)
                }
                skipWhitespace()
                if (index < text.length && text[index] == ',') index += 1
            }
        } catch (_: Exception) {
            return JsonObject(entries)
        }
        index += 1
        return JsonObject(entries)
    }

    private fun parseArray(): JsonValue {
        index += 1
        val items = mutableListOf<JsonValue>()
        try {
            while (index >= text.length || text[index] != ']') {
                items.add(parseAny())
                skipWhitespace()
                if (index < text.length && text[index] == ',') index += 1
            }
        } catch (_: Exception) {
            return JsonArray(items)
        }
        index += 1
        return JsonArray(items)
    }

    private fun parseNumber(): JsonValue {
        if (index == 0) {
            if (text == "-") throw MalformedJsonException("Not sure what '-' is")
            decodeNumber(text)?.let { return it }
            val exponentIndex = text.lastIndexOf('e')
            if (exponentIndex >= 0) {
                decodeNumber(text.substring(0, exponentIndex))?.let { return it }
            }
            throw MalformedJsonException("Invalid number")
        }
        val start = index
        if (text[index] == '-') index += 1
        while (index < text.length && text[index] !in ",]}") index += 1
        val candidate = text.substring(start, index)
        decodeNumber(candidate)?.let { return it }
        if (candidate == "-") throw PartialJsonException("Not sure what '-' is")
        val exponentIndex = text.lastIndexOf('e')
        if (exponentIndex >= start) {
            decodeNumber(text.substring(start, exponentIndex))?.let { return it }
        }
        throw MalformedJsonException("Invalid number")
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \n\r\t") index += 1
    }
}

private class StrictJsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) throw JsonSyntaxException("Unexpected trailing characters")
        return value
    }

    private fun parseValue(): JsonValue {
        if (index >= text.length) throw JsonSyntaxException("Unexpected end of input")
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonPrimitive(parseString())
            't' -> {
                expectKeyword("true")
                JsonPrimitive(true)
            }

            'f' -> {
                expectKeyword("false")
                JsonPrimitive(false)
            }

            'n' -> {
                expectKeyword("null")
                JsonNull
            }

            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonValue {
        index += 1
        skipWhitespace()
        val entries = LinkedHashMap<String, JsonValue>()
        if (index < text.length && text[index] == '}') {
            index += 1
            return JsonObject(entries)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            entries[key] = parseValue()
            skipWhitespace()
            if (index >= text.length) throw JsonSyntaxException("Unterminated object")
            when (text[index]) {
                ',' -> {
                    index += 1
                    skipWhitespace()
                    if (index < text.length && text[index] == '}') throw JsonSyntaxException("Trailing comma")
                }

                '}' -> {
                    index += 1
                    return JsonObject(entries)
                }

                else -> throw JsonSyntaxException("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): JsonValue {
        index += 1
        skipWhitespace()
        val items = mutableListOf<JsonValue>()
        if (index < text.length && text[index] == ']') {
            index += 1
            return JsonArray(items)
        }
        while (true) {
            skipWhitespace()
            items.add(parseValue())
            skipWhitespace()
            if (index >= text.length) throw JsonSyntaxException("Unterminated array")
            when (text[index]) {
                ',' -> {
                    index += 1
                    skipWhitespace()
                    if (index < text.length && text[index] == ']') throw JsonSyntaxException("Trailing comma")
                }

                ']' -> {
                    index += 1
                    return JsonArray(items)
                }

                else -> throw JsonSyntaxException("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (index >= text.length) throw JsonSyntaxException("Unterminated string")
            val char = text[index]
            index += 1
            when {
                char == '"' -> return builder.toString()
                char == '\\' -> {
                    if (index >= text.length) throw JsonSyntaxException("Unterminated escape")
                    when (val escaped = text[index]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            val digitsEnd = index + 5
                            if (digitsEnd > text.length) throw JsonSyntaxException("Incomplete unicode escape")
                            var code = 0
                            for (position in index + 1 until digitsEnd) {
                                val digit = text[position]
                                if (!isHexDigit(digit)) throw JsonSyntaxException("Invalid unicode escape")
                                code = code * 16 + digit.digitToInt(16)
                            }
                            builder.append(code.toChar())
                            index = digitsEnd - 1
                        }

                        else -> throw JsonSyntaxException("Invalid escape sequence")
                    }
                    index += 1
                }

                char.code < 0x20 -> throw JsonSyntaxException("Unescaped control character")
                else -> builder.append(char)
            }
        }
    }

    private fun parseNumber(): JsonValue {
        val start = index
        if (index < text.length && text[index] == '-') index += 1
        if (index >= text.length) throw JsonSyntaxException("Invalid number")
        val firstDigit = text[index]
        when {
            firstDigit == '0' -> index += 1
            firstDigit in '1'..'9' -> {
                index += 1
                while (index < text.length && text[index] in '0'..'9') index += 1
            }

            else -> throw JsonSyntaxException("Invalid number")
        }
        if (index < text.length && text[index] == '.') {
            index += 1
            if (index >= text.length || text[index] !in '0'..'9') throw JsonSyntaxException("Invalid fraction")
            while (index < text.length && text[index] in '0'..'9') index += 1
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
            if (index >= text.length || text[index] !in '0'..'9') throw JsonSyntaxException("Invalid exponent")
            while (index < text.length && text[index] in '0'..'9') index += 1
        }
        val raw = text.substring(start, index)
        return Json.parseToJsonElement(raw)
    }

    private fun expectKeyword(keyword: String) {
        if (!text.startsWith(keyword, index)) throw JsonSyntaxException("Invalid literal")
        index += keyword.length
    }

    private fun expect(char: Char) {
        if (index >= text.length || text[index] != char) throw JsonSyntaxException("Expected '$char'")
        index += 1
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index] in " \n\r\t") index += 1
    }
}

private fun isJsonNumber(raw: String): Boolean {
    if (raw.isEmpty()) return false
    var position = 0
    if (raw[position] == '-') position += 1
    if (position >= raw.length) return false
    when {
        raw[position] == '0' -> position += 1
        raw[position] in '1'..'9' -> {
            position += 1
            while (position < raw.length && raw[position] in '0'..'9') position += 1
        }

        else -> return false
    }
    if (position < raw.length && raw[position] == '.') {
        position += 1
        if (position >= raw.length || raw[position] !in '0'..'9') return false
        while (position < raw.length && raw[position] in '0'..'9') position += 1
    }
    if (position < raw.length && (raw[position] == 'e' || raw[position] == 'E')) {
        position += 1
        if (position < raw.length && (raw[position] == '+' || raw[position] == '-')) position += 1
        if (position >= raw.length || raw[position] !in '0'..'9') return false
        while (position < raw.length && raw[position] in '0'..'9') position += 1
    }
    return position == raw.length
}

private fun decodeStringLiteral(literal: String): String? = try {
    val element = StrictJsonParser(literal).parse()
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
} catch (_: JsonSyntaxException) {
    null
}

private fun decodeNumber(literal: String): JsonValue? {
    if (!isJsonNumber(literal)) return null
    return Json.parseToJsonElement(literal)
}

private open class PartialJsonException(message: String) : Exception(message)

private class MalformedJsonException(message: String) : PartialJsonException(message)

private class JsonSyntaxException(message: String) : Exception(message)
