package dev.pi.coding.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

private const val LS_DEFAULT_LIMIT = 500

private val lsParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("path") {
            put("type", "string")
            put("description", "Directory to list (default: current directory)")
        }
        putJsonObject("limit") {
            put("type", "number")
            put("description", "Maximum number of entries to return (default: $LS_DEFAULT_LIMIT)")
        }
    }
}

fun lsTool(cwd: Path, fs: FileSystem): CodingTool = CodingTool(
    name = "ls",
    description = "List directory contents. Returns entries sorted alphabetically, with '/' suffix for directories. Includes dotfiles. Output is truncated to $LS_DEFAULT_LIMIT entries or ${DEFAULT_MAX_BYTES / 1024}KB (whichever is hit first).",
    parameters = lsParameters,
    label = "ls",
    promptSnippet = "List directory contents",
    promptGuidelines = emptyList(),
    execute = { _toolCallId, args, signal, _onUpdate ->
        signal?.throwIfAborted()
        val pathInput = args.argStringOrNull("path")
        val limit = args.argIntOrNull("limit")
        val dirPath = if (pathInput.isNullOrEmpty()) cwd else resolveToolPath(cwd, pathInput)
        val effectiveLimit = limit ?: LS_DEFAULT_LIMIT

        val directoryMetadata = fs.metadataOrNull(dirPath)
            ?: throw IllegalArgumentException("Path not found: $dirPath")
        if (!directoryMetadata.isDirectory) {
            throw IllegalArgumentException("Not a directory: $dirPath")
        }

        val entries = try {
            fs.list(dirPath)
        } catch (error: Throwable) {
            throw IllegalArgumentException("Cannot read directory: ${error.message}")
        }

        val sortedEntries = entries.sortedWith(compareBy { it.name.lowercase() })
        val results = mutableListOf<String>()
        var entryLimitReached = false
        for (entry in sortedEntries) {
            signal?.throwIfAborted()
            if (results.size >= effectiveLimit) {
                entryLimitReached = true
                break
            }
            val entryPath = dirPath / entry.name
            val entryMetadata = try {
                fs.metadataOrNull(entryPath)
            } catch (error: Throwable) {
                null
            } ?: continue
            results += if (entryMetadata.isDirectory) entry.name + "/" else entry.name
        }

        if (results.isEmpty()) {
            textResult("(empty directory)")
        } else {
            val rawOutput = results.joinToString("\n")
            val truncation = truncateHead(rawOutput, maxLines = Int.MAX_VALUE)
            var output = truncation.text
            val notices = mutableListOf<String>()
            if (entryLimitReached) {
                notices += "$effectiveLimit entries limit reached. Use limit=${effectiveLimit * 2} for more"
            }
            if (truncation.truncated) {
                notices += "${formatSize(DEFAULT_MAX_BYTES)} limit reached"
            }
            if (notices.isNotEmpty()) {
                output += "\n\n[" + notices.joinToString(". ") + "]"
            }
            val details = buildJsonObject {
                if (entryLimitReached) {
                    put("entryLimitReached", effectiveLimit)
                }
                if (truncation.truncated) {
                    putJsonObject("truncation") {
                        put("truncated", true)
                        truncation.truncatedBy?.let { put("truncatedBy", it) }
                    }
                }
            }
            textResult(output, details.ifEmpty { null })
        }
    },
)
