package dev.pi.coding.prompt

class ParsedFrontmatter(
    val frontmatter: Map<String, String?>,
    val body: String,
)

fun stripFrontmatter(content: String): String = Frontmatter.parse(content).body

object Frontmatter {
    fun parse(content: String): ParsedFrontmatter {
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n").removePrefix("\uFEFF")
        if (!normalized.startsWith("---")) {
            return ParsedFrontmatter(emptyMap(), normalized)
        }
        val endIndex = normalized.indexOf("\n---", 3)
        if (endIndex == -1) {
            return ParsedFrontmatter(emptyMap(), normalized)
        }
        val yaml = if (endIndex <= 4) "" else normalized.substring(4, endIndex)
        val body = normalized.substring(endIndex + 4).trim()
        return ParsedFrontmatter(parseYamlMapping(yaml), body)
    }
}

private fun parseYamlMapping(yaml: String): Map<String, String?> {
    val result = LinkedHashMap<String, String?>()
    val lines = yaml.split("\n")
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index++
            continue
        }
        if (line.startsWith(" ") || line.startsWith("\t")) {
            index++
            continue
        }
        if (line.trimStart().startsWith("#")) {
            index++
            continue
        }
        val separator = findKeySeparator(line)
        if (separator == -1) {
            index++
            continue
        }
        val key = unquoteScalar(line.substring(0, separator).trim())
        val rawValue = line.substring(separator + 1).trim()
        if (rawValue.startsWith("|") || rawValue.startsWith(">")) {
            val folded = rawValue.startsWith(">")
            val block = mutableListOf<String>()
            var cursor = index + 1
            while (cursor < lines.size) {
                val candidate = lines[cursor]
                if (candidate.isBlank()) {
                    block.add("")
                    cursor++
                    continue
                }
                if (!candidate.startsWith(" ") && !candidate.startsWith("\t")) break
                block.add(candidate)
                cursor++
            }
            while (block.isNotEmpty() && block.first().isBlank()) block.removeAt(0)
            while (block.isNotEmpty() && block.last().isBlank()) block.removeAt(block.lastIndex)
            val indentation = block.filter { it.isNotBlank() }.minOfOrNull { indentationOf(it) } ?: 0
            val stripped = block.map { if (it.length >= indentation) it.substring(indentation) else "" }
            result[key] = if (folded) stripped.joinToString("\n").replace("\n", " ") else stripped.joinToString("\n")
            index = cursor
            continue
        }
        if (rawValue.isEmpty()) {
            var cursor = index + 1
            while (cursor < lines.size && lines[cursor].isBlank()) cursor++
            if (cursor < lines.size && (lines[cursor].startsWith(" ") || lines[cursor].startsWith("\t"))) {
                index = cursor
                while (index < lines.size) {
                    val candidate = lines[index]
                    if (candidate.isBlank()) {
                        index++
                        continue
                    }
                    if (!candidate.startsWith(" ") && !candidate.startsWith("\t")) break
                    index++
                }
                result[key] = null
                continue
            }
            result[key] = null
            index++
            continue
        }
        result[key] = parseScalar(rawValue)
        index++
    }
    return result
}

private fun findKeySeparator(line: String): Int {
    var inSingle = false
    var inDouble = false
    var index = 0
    while (index < line.length) {
        val char = line[index]
        when {
            char == '\'' && !inDouble -> inSingle = !inSingle
            char == '"' && !inSingle -> inDouble = !inDouble
            char == ':' && !inSingle && !inDouble -> {
                if (index + 1 >= line.length || line[index + 1] == ' ') return index
            }
        }
        index++
    }
    return -1
}

private fun indentationOf(line: String): Int {
    var count = 0
    while (count < line.length && (line[count] == ' ' || line[count] == '\t')) count++
    return count
}

private fun parseScalar(value: String): String? {
    val trimmed = value.trim()
    if (trimmed == "~" || trimmed == "null" || trimmed == "Null" || trimmed == "NULL") return null
    if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length >= 2) {
        return trimmed.substring(1, trimmed.length - 1).replace("''", "'")
    }
    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
        return unescapeDoubleQuoted(trimmed.substring(1, trimmed.length - 1))
    }
    val comment = findInlineComment(trimmed)
    return (if (comment == -1) trimmed else trimmed.substring(0, comment)).trim()
}

private fun findInlineComment(value: String): Int {
    var index = 1
    while (index < value.length) {
        if (value[index] == '#' && (value[index - 1] == ' ' || value[index - 1] == '\t')) return index
        index++
    }
    return -1
}

private fun unquoteScalar(value: String): String = parseScalar(value) ?: value

private fun unescapeDoubleQuoted(value: String): String {
    val builder = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '\\' && index + 1 < value.length) {
            when (val next = value[index + 1]) {
                'n' -> builder.append('\n')
                't' -> builder.append('\t')
                'r' -> builder.append('\r')
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '0' -> builder.append('\u0000')
                else -> builder.append(next)
            }
            index += 2
        } else {
            builder.append(char)
            index++
        }
    }
    return builder.toString()
}
