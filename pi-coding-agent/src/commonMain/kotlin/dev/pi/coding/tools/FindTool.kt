package dev.pi.coding.tools

import dev.pi.ai.JsonValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path

private const val FIND_DEFAULT_LIMIT = 1000
private const val FIND_MAX_BYTES = 51200

private val findParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("pattern") {
            put("type", "string")
            put("description", "Glob pattern to match files, e.g. '*.ts', '**/*.json', or 'src/**/*.spec.ts'")
        }
        putJsonObject("path") {
            put("type", "string")
            put("description", "Directory to search in (default: current directory)")
        }
        putJsonObject("limit") {
            put("type", "number")
            put("description", "Maximum number of results (default: 1000)")
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("pattern")) })
}

fun findTool(cwd: Path, fs: FileSystem): CodingTool = CodingTool(
    name = "find",
    description = "Search for files by glob pattern. Returns matching file paths relative to the search directory. Respects .gitignore. Output is truncated to $FIND_DEFAULT_LIMIT results or ${FIND_MAX_BYTES / 1024}KB (whichever is hit first).",
    parameters = findParameters,
    label = "find",
    promptSnippet = "Find files by glob pattern (respects .gitignore)",
    promptGuidelines = emptyList(),
    execute = { _toolCallId, args, signal, _onUpdate ->
        signal?.throwIfAborted()
        val pattern = args.toolRequiredString("pattern")
        val searchInput = args.toolStringOrNull("path")
        val limit = (args.toolIntOrNull("limit") ?: FIND_DEFAULT_LIMIT).coerceAtLeast(1)
        val searchPath = if (searchInput.isNullOrEmpty()) cwd else resolveToolPath(cwd, searchInput)
        val searchMetadata = fs.metadataOrNull(searchPath)
            ?: throw IllegalArgumentException("Path not found: $searchPath")
        val matcher = buildFileGlobMatcher(pattern)

        val results = mutableListOf<String>()
        val collectFile: suspend (Path) -> Boolean = { file ->
            val relative = toolRelativePath(searchPath, file).orEmpty().ifEmpty { file.name }
            if (matcher(relative)) {
                results += relative
                results.size < limit
            } else {
                true
            }
        }

        if (searchMetadata.isDirectory) {
            walkToolFilesStreaming(
                fs = fs,
                root = searchPath,
                hierarchy = GitIgnoreHierarchy(fs, searchPath),
                signal = signal,
                filter = null,
            ) { file -> collectFile(file) }
        } else {
            if (matcher(searchPath.name)) {
                results += searchPath.name
            }
        }

        val resultLimitReached = results.size >= limit
        if (results.isEmpty()) {
            textResult("No files found matching pattern")
        } else {
            val rawOutput = results.joinToString("\n")
            val truncation = truncateHead(rawOutput, maxLines = Int.MAX_VALUE, maxBytes = FIND_MAX_BYTES)
            val notices = mutableListOf<String>()
            if (resultLimitReached) {
                notices += "$limit results limit reached. Use limit=${limit * 2} for more, or refine pattern"
            }
            if (truncation.truncated) {
                notices += "${formatToolSize(FIND_MAX_BYTES)} limit reached"
            }
            var output = truncation.text
            if (notices.isNotEmpty()) {
                output += "\n\n[" + notices.joinToString(". ") + "]"
            }
            textResult(output, findDetails(resultLimitReached, truncation, limit))
        }
    },
)

private fun buildFileGlobMatcher(pattern: String): (String) -> Boolean {
    if (pattern.isEmpty()) {
        return { false }
    }
    val trimmed = pattern.removePrefix("./")
    val leadingSlash = trimmed.startsWith("/")
    val body = if (leadingSlash) trimmed.substring(1) else trimmed
    if (!body.contains("/")) {
        val regex = Regex("^" + globToRegex(body) + "$")
        return { path -> regex.matches(path.substringAfterLast('/')) }
    }
    val effective = when {
        leadingSlash -> body
        body.startsWith("**/") || body == "**" -> body
        else -> "**/" + body
    }
    val regex = Regex("^" + globToRegex(effective) + "$")
    return { path -> regex.matches(path) }
}

private fun findDetails(resultLimitReached: Boolean, truncation: Truncated, limit: Int): JsonValue? {
    if (!resultLimitReached && !truncation.truncated) {
        return null
    }
    return buildJsonObject {
        if (resultLimitReached) {
            put("resultLimitReached", limit)
        }
        if (truncation.truncated) {
            put("truncation", truncationJson(truncation))
        }
    }
}
