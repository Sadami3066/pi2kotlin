package dev.pi.coding.tools

import dev.pi.ai.JsonValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path

private const val GREP_DEFAULT_LIMIT = 100
private const val TOOLS_MAX_BYTES = 51200
private const val MAX_SCAN_FILE_BYTES = 16L * 1024 * 1024

private val ignoredDirectoryNames = setOf(
    ".git",
    ".gradle",
    ".kotlin",
    ".idea",
    "node_modules",
    "build",
    "out",
    "target",
    "__pycache__",
    ".venv",
    "venv",
)

private val toolsGrepParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("pattern") {
            put("type", "string")
            put("description", "Search pattern (regex or literal string)")
        }
        putJsonObject("path") {
            put("type", "string")
            put("description", "Directory or file to search (default: current directory)")
        }
        putJsonObject("glob") {
            put("type", "string")
            put("description", "Filter files by glob pattern, e.g. '*.ts' or '**/*.spec.ts'")
        }
        putJsonObject("ignoreCase") {
            put("type", "boolean")
            put("description", "Case-insensitive search (default: false)")
        }
        putJsonObject("literal") {
            put("type", "boolean")
            put("description", "Treat pattern as literal string instead of regex (default: false)")
        }
        putJsonObject("context") {
            put("type", "number")
            put("description", "Number of lines to show before and after each match (default: 0)")
        }
        putJsonObject("limit") {
            put("type", "number")
            put("description", "Maximum number of matches to return (default: 100)")
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
}

fun grepTool(cwd: Path, fs: FileSystem): CodingTool = CodingTool(
    name = "grep",
    description = "Search file contents for a pattern. Returns matching lines with file paths and line numbers. Respects .gitignore. Output is truncated to $GREP_DEFAULT_LIMIT matches or ${TOOLS_MAX_BYTES / 1024}KB (whichever is hit first). Long lines are truncated to $GREP_MAX_LINE_LENGTH chars.",
    parameters = toolsGrepParameters,
    label = "grep",
    promptSnippet = "Search file contents for patterns (respects .gitignore)",
    promptGuidelines = emptyList(),
    execute = { _toolCallId, args, signal, _onUpdate ->
        signal?.throwIfAborted()
        val pattern = args.toolRequiredString("pattern")
        val searchInput = args.toolStringOrNull("path")
        val globPattern = args.toolStringOrNull("glob")
        val ignoreCase = args.toolBooleanOrNull("ignoreCase") ?: false
        val literal = args.toolBooleanOrNull("literal") ?: false
        val contextLines = (args.toolIntOrNull("context") ?: 0).coerceAtLeast(0)
        val limit = (args.toolIntOrNull("limit") ?: GREP_DEFAULT_LIMIT).coerceAtLeast(1)

        val searchPath = if (searchInput.isNullOrEmpty()) cwd else resolveToolPath(cwd, searchInput)
        val searchMetadata = fs.metadataOrNull(searchPath)
            ?: throw IllegalArgumentException("Path not found: $searchPath")
        val searchingDirectory = searchMetadata.isDirectory
        val matches = buildMatcher(pattern, ignoreCase, literal)
        val fileFilter = globPattern?.takeIf { it.isNotEmpty() }?.let(::buildGrepGlobMatcher)

        val candidateFiles = if (searchingDirectory) {
            walkToolFiles(fs, searchPath, fileFilter)
        } else {
            listOf(searchPath)
        }

        val outputLines = mutableListOf<String>()
        var matchCount = 0
        var matchLimitReached = false
        var linesTruncated = false

        files@ for (file in candidateFiles) {
            if (matchCount >= limit) {
                break@files
            }
            signal?.throwIfAborted()
            val size = fs.metadataOrNull(file)?.size
            if (size != null && size > MAX_SCAN_FILE_BYTES) {
                continue@files
            }
            val text = runCatching { fs.read(file) { readUtf8() } }.getOrNull() ?: continue@files
            if (text.indexOf('\u0000') >= 0) {
                continue@files
            }
            val fileLines = text.split("\n").let { lines ->
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
            val displayPath = grepDisplayPath(searchPath, file, searchingDirectory)

            for (index in fileLines.indices) {
                val line = fileLines[index].removeSuffix("\r")
                if (!matches(line)) {
                    continue
                }
                matchCount++
                if (contextLines == 0) {
                    val truncated = truncateGrepLine(line)
                    if (truncated.second) {
                        linesTruncated = true
                    }
                    outputLines += "$displayPath:${index + 1}: ${truncated.first}"
                } else {
                    val start = (index - contextLines).coerceAtLeast(0)
                    val end = (index + contextLines).coerceAtMost(fileLines.size - 1)
                    for (current in start..end) {
                        val rawLine = fileLines[current].removeSuffix("\r")
                        val truncated = truncateGrepLine(rawLine)
                        if (truncated.second) {
                            linesTruncated = true
                        }
                        val lineNumber = current + 1
                        if (current == index) {
                            outputLines += "$displayPath:$lineNumber: ${truncated.first}"
                        } else {
                            outputLines += "$displayPath-$lineNumber- ${truncated.first}"
                        }
                    }
                }
                if (matchCount >= limit) {
                    matchLimitReached = true
                    break@files
                }
            }
        }

        if (matchCount == 0) {
            textResult("No matches found")
        } else {
            val rawOutput = outputLines.joinToString("\n")
            val truncation = truncateHead(rawOutput, maxLines = Int.MAX_VALUE, maxBytes = TOOLS_MAX_BYTES)
            val notices = mutableListOf<String>()
            if (matchLimitReached) {
                notices += "$limit matches limit reached. Use limit=${limit * 2} for more, or refine pattern"
            }
            if (truncation.truncated) {
                notices += "${formatToolSize(TOOLS_MAX_BYTES)} limit reached"
            }
            if (linesTruncated) {
                notices += "Some lines truncated to $GREP_MAX_LINE_LENGTH chars. Use read tool to see full lines"
            }
            var output = truncation.text
            if (notices.isNotEmpty()) {
                output += "\n\n[" + notices.joinToString(". ") + "]"
            }
            textResult(output, grepDetails(matchLimitReached, linesTruncated, truncation, limit))
        }
    },
)

internal fun JsonObject.toolStringOrNull(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.toolBooleanOrNull(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.toolIntOrNull(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.toolDoubleOrNull(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.toolRequiredString(name: String): String {
    val value = toolStringOrNull(name)
    require(!value.isNullOrEmpty()) { "Missing required argument: $name" }
    return value
}

private fun buildMatcher(pattern: String, ignoreCase: Boolean, literal: Boolean): (String) -> Boolean {
    if (literal) {
        val needle = if (ignoreCase) pattern.lowercase() else pattern
        return { line -> (if (ignoreCase) line.lowercase() else line).contains(needle) }
    }
    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
    val regex = Regex(pattern, options)
    return { line -> regex.containsMatchIn(line) }
}

private fun truncateGrepLine(line: String): Pair<String, Boolean> {
    if (line.length <= GREP_MAX_LINE_LENGTH) {
        return line to false
    }
    return line.take(GREP_MAX_LINE_LENGTH) + "... [truncated]" to true
}

private fun grepDisplayPath(searchPath: Path, file: Path, searchingDirectory: Boolean): String {
    if (!searchingDirectory) {
        return file.name
    }
    val relative = toolRelativePath(searchPath, file)
    return if (relative != null && relative.isNotEmpty() && !relative.startsWith("..")) relative else file.name
}

internal fun toolRelativePath(base: Path, target: Path): String? {
    val baseParts = base.toString().replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
    val targetParts = target.toString().replace('\\', '/').trim('/').split('/').filter { it.isNotEmpty() }
    if (targetParts.size < baseParts.size) {
        return null
    }
    for (index in baseParts.indices) {
        if (baseParts[index] != targetParts[index]) {
            return null
        }
    }
    return targetParts.drop(baseParts.size).joinToString("/")
}

internal suspend fun walkToolFiles(
    fs: FileSystem,
    root: Path,
    filter: ((Path) -> Boolean)? = null,
): List<Path> {
    val files = mutableListOf<Path>()
    val pending = ArrayDeque<Path>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val directory = pending.removeLast()
        val children = runCatching { fs.list(directory) }.getOrNull() ?: continue
        for (child in children.sortedBy { it.name }) {
            val metadata = runCatching { fs.metadataOrNull(child) }.getOrNull() ?: continue
            if (metadata.isDirectory) {
                if (child.name in ignoredDirectoryNames) {
                    continue
                }
                pending.addLast(child)
            } else if (metadata.isRegularFile) {
                if (filter == null || filter(child)) {
                    files += child
                }
            }
        }
    }
    return files.sortedBy { it.toString() }
}

internal fun formatToolSize(bytes: Int): String {
    val value = bytes.toLong()
    return when {
        value < 1024 -> "${value}B"
        value < 1024 * 1024 -> "${oneDecimal(value / 1024.0)}KB"
        else -> "${oneDecimal(value / (1024.0 * 1024.0))}MB"
    }
}

private fun oneDecimal(value: Double): String {
    val scaled = kotlin.math.round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

internal fun truncationJson(truncation: Truncated): JsonObject = buildJsonObject {
    put("truncated", truncation.truncated)
    truncation.truncatedBy?.let { put("truncatedBy", it) }
}

private fun grepDetails(
    matchLimitReached: Boolean,
    linesTruncated: Boolean,
    truncation: Truncated,
    limit: Int,
): JsonValue? {
    if (!matchLimitReached && !linesTruncated && !truncation.truncated) {
        return null
    }
    return buildJsonObject {
        if (matchLimitReached) {
            put("matchLimitReached", limit)
        }
        if (truncation.truncated) {
            put("truncation", truncationJson(truncation))
        }
        if (linesTruncated) {
            put("linesTruncated", true)
        }
    }
}

private fun buildGrepGlobMatcher(pattern: String): (Path) -> Boolean {
    val trimmed = pattern.removePrefix("./")
    val leadingSlash = trimmed.startsWith("/")
    val body = if (leadingSlash) trimmed.substring(1) else trimmed
    if (!body.contains("/")) {
        val regex = Regex("^" + globToRegex(body) + "$")
        return { path -> regex.matches(path.name) }
    }
    val effective = when {
        leadingSlash -> body
        body.startsWith("**/") || body == "**" -> body
        else -> "**/" + body
    }
    val regex = Regex("^" + globToRegex(effective) + "$")
    return { path -> regex.matches(path.toString().replace('\\', '/').trimStart('/')) }
}

internal fun globToRegex(pattern: String): String {
    val out = StringBuilder()
    var index = 0
    while (index < pattern.length) {
        when (val char = pattern[index]) {
            '*' -> {
                var stars = 1
                while (index + 1 < pattern.length && pattern[index + 1] == '*') {
                    stars++
                    index++
                }
                if (stars >= 2) {
                    if (index + 1 < pattern.length && pattern[index + 1] == '/') {
                        out.append("(?:.*/)?")
                        index++
                    } else {
                        out.append(".*")
                    }
                } else {
                    out.append("[^/]*")
                }
            }
            '?' -> out.append("[^/]")
            '[' -> {
                var close = index + 1
                if (close < pattern.length && (pattern[close] == '!' || pattern[close] == '^')) {
                    close++
                }
                if (close < pattern.length && pattern[close] == ']') {
                    close++
                }
                while (close < pattern.length && pattern[close] != ']') {
                    close++
                }
                if (close >= pattern.length) {
                    out.append("\\[")
                } else {
                    var content = pattern.substring(index + 1, close)
                    if (content.startsWith("!")) {
                        content = "^" + content.substring(1)
                    }
                    out.append('[').append(content).append(']')
                    index = close
                }
            }
            in ".+(){}|^$\\" -> out.append('\\').append(char)
            else -> out.append(char)
        }
        index++
    }
    return out.toString()
}
