package dev.pi.coding.tools

import dev.pi.ai.JsonValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path
import okio.buffer

private const val GREP_DEFAULT_LIMIT = 100
private const val TOOLS_MAX_BYTES = 51200
private const val MAX_SCAN_FILE_BYTES = 16L * 1024 * 1024
private const val LINE_SIGNAL_CHECK_INTERVAL = 1024

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

fun grepTool(
    cwd: Path,
    fs: FileSystem,
    maxScanFileBytes: Long = MAX_SCAN_FILE_BYTES,
): CodingTool = CodingTool(
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
        val ignoreRoot = if (toolRelativePath(cwd, searchPath) != null) cwd else searchPath

        val outputLines = mutableListOf<String>()
        var matchCount = 0
        var skippedFiles = 0
        var linesTruncated = false

        val scanFile: suspend (Path) -> Boolean = { file ->
            signal?.throwIfAborted()
            val size = fs.metadataOrNull(file)?.size
            if (size != null && size > maxScanFileBytes) {
                skippedFiles++
            } else {
                scanGrepFile(
                    fs = fs,
                    file = file,
                    displayPath = grepDisplayPath(searchPath, file, searchingDirectory),
                    matches = matches,
                    contextLines = contextLines,
                    limit = limit,
                    signal = signal,
                    outputLines = outputLines,
                    state = GrepScanState(
                        matchCount = matchCount,
                        linesTruncated = linesTruncated,
                        skippedFiles = skippedFiles,
                    ),
                ).let { result ->
                    matchCount = result.matchCount
                    linesTruncated = result.linesTruncated
                    skippedFiles = result.skippedFiles
                }
            }
            matchCount < limit
        }

        if (searchingDirectory) {
            walkToolFilesStreaming(
                fs = fs,
                root = searchPath,
                hierarchy = GitIgnoreHierarchy(fs, ignoreRoot),
                signal = signal,
                filter = fileFilter,
            ) { file -> scanFile(file) }
        } else {
            scanFile(searchPath)
        }

        val matchLimitReached = matchCount >= limit
        if (matchCount == 0) {
            textResult(noMatchesText(skippedFiles, maxScanFileBytes))
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

private class GrepScanState(
    val matchCount: Int,
    val linesTruncated: Boolean,
    val skippedFiles: Int,
)

private class GrepScanResult(
    val matchCount: Int,
    val linesTruncated: Boolean,
    val skippedFiles: Int,
)

private suspend fun scanGrepFile(
    fs: FileSystem,
    file: Path,
    displayPath: String,
    matches: (String) -> Boolean,
    contextLines: Int,
    limit: Int,
    signal: dev.pi.ai.RunSignal?,
    outputLines: MutableList<String>,
    state: GrepScanState,
): GrepScanResult {
    val source = runCatching { fs.source(file).buffer() }.getOrNull() ?: return GrepScanResult(
        state.matchCount,
        state.linesTruncated,
        state.skippedFiles,
    )
    val fileStartOutput = outputLines.size
    val fileStartMatches = state.matchCount
    val fileStartTruncated = state.linesTruncated
    val ring = ArrayDeque<Pair<Int, String>>()
    var afterRemaining = 0
    var lastEmitted = 0
    var matchCount = state.matchCount
    var linesTruncated = state.linesTruncated
    var binary = false
    try {
        var lineNumber = 0
        while (true) {
            val raw = source.readUtf8Line() ?: break
            lineNumber++
            if (lineNumber % LINE_SIGNAL_CHECK_INTERVAL == 0) signal?.throwIfAborted()
            val line = raw.removeSuffix("\r")
            if (line.indexOf('\u0000') >= 0) {
                binary = true
                break
            }
            val lineMatches = matches(line)
            if (contextLines == 0) {
                if (!lineMatches) continue
                val truncated = truncateGrepLine(line)
                if (truncated.second) linesTruncated = true
                outputLines += "$displayPath:$lineNumber: ${truncated.first}"
                matchCount++
                if (matchCount >= limit) break
            } else {
                if (lineMatches) {
                    for ((pendingNumber, pendingLine) in ring) {
                        if (pendingNumber <= lastEmitted) continue
                        val truncated = truncateGrepLine(pendingLine)
                        if (truncated.second) linesTruncated = true
                        outputLines += "$displayPath-$pendingNumber- ${truncated.first}"
                        lastEmitted = pendingNumber
                    }
                    val truncated = truncateGrepLine(line)
                    if (truncated.second) linesTruncated = true
                    outputLines += "$displayPath:$lineNumber: ${truncated.first}"
                    lastEmitted = lineNumber
                    matchCount++
                    afterRemaining = contextLines
                    if (matchCount >= limit) break
                } else if (afterRemaining > 0) {
                    val truncated = truncateGrepLine(line)
                    if (truncated.second) linesTruncated = true
                    outputLines += "$displayPath-$lineNumber- ${truncated.first}"
                    lastEmitted = lineNumber
                    afterRemaining--
                }
                ring.addLast(lineNumber to line)
                while (ring.size > contextLines) {
                    ring.removeFirst()
                }
            }
        }
    } finally {
        runCatching { source.close() }
    }
    if (binary) {
        while (outputLines.size > fileStartOutput) {
            outputLines.removeAt(outputLines.size - 1)
        }
        matchCount = fileStartMatches
        linesTruncated = fileStartTruncated
    }
    return GrepScanResult(matchCount, linesTruncated, state.skippedFiles)
}

private fun noMatchesText(skippedFiles: Int, maxScanFileBytes: Long): String {
    if (skippedFiles <= 0) return "No matches found"
    val limitBytes = maxScanFileBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return "No matches found ($skippedFiles file(s) skipped: larger than ${formatToolSize(limitBytes)})"
}

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
