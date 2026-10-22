package dev.pi.coding.tools

import dev.pi.ai.PiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.FileSystem
import okio.Path

private const val UNIFIED_PATCH_SEPARATOR = "==================================================================="
private const val DIFF_CONTEXT_LINES = 4
private const val LCS_CELL_LIMIT = 4_000_000

private val editParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("path") {
            put("type", "string")
            put("description", "Path to the file to edit (relative or absolute)")
        }
        putJsonObject("edits") {
            put("type", "array")
            put("description", "One or more targeted replacements. Each edit is matched against the original file, not incrementally. Do not include overlapping or nested edits. If two changes touch the same block or nearby lines, merge them into one edit instead.")
            putJsonObject("items") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("oldText") {
                        put("type", "string")
                        put("description", "Exact text for one targeted replacement. It must be unique in the original file and must not overlap with any other edits[].oldText in the same call.")
                    }
                    putJsonObject("newText") {
                        put("type", "string")
                        put("description", "Replacement text for this targeted edit.")
                    }
                }
                put("required", buildJsonArray { add("oldText"); add("newText") })
            }
        }
    }
    put("required", buildJsonArray { add("path"); add("edits") })
}

fun editTool(cwd: Path, fs: FileSystem, mutationQueue: FileMutationQueue): CodingTool = CodingTool(
    name = "edit",
    description = "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, non-overlapping region of the original file. If two changes affect the same block or nearby lines, merge them into one edit instead of emitting overlapping edits. Do not include large unchanged regions just to connect distant changes.",
    parameters = editParameters,
    label = "edit",
    promptSnippet = "Make precise file edits with exact text replacement, including multiple disjoint edits in one call",
    promptGuidelines = listOf(
        "Use edit for precise changes (edits[].oldText must match exactly)",
        "When changing multiple separate locations in one file, use one edit call with multiple entries in edits[] instead of multiple edit calls",
        "Each edits[].oldText is matched against the original file, not after earlier edits are applied. Do not emit overlapping or nested edits. Merge nearby changes into one edit.",
        "Keep edits[].oldText as small as possible while still being unique in the file. Do not pad with large unchanged regions.",
    ),
    prepareArguments = ::prepareEditArguments,
    execute = { _toolCallId, args, signal, _onUpdate ->
        val pathInput = args.argString("path")
        val edits = parseEdits(args)
        val absolutePath = resolveToolPath(cwd, pathInput)
        mutationQueue.withFile(absolutePath) {
            signal?.throwIfAborted()
            val metadata = fs.metadataOrNull(absolutePath)
                ?: throw IllegalArgumentException("Could not edit file: $pathInput. Error code: ENOENT.")
            if (metadata.isDirectory) {
                throw IllegalArgumentException("Could not edit file: $pathInput. Error code: EISDIR.")
            }
            signal?.throwIfAborted()

            val rawContent = fs.read(absolutePath) { readUtf8() }
            signal?.throwIfAborted()

            val bom = if (rawContent.startsWith("\uFEFF")) "\uFEFF" else ""
            val content = if (bom.isEmpty()) rawContent else rawContent.substring(1)
            val originalEnding = detectLineEnding(content)
            val normalizedContent = normalizeToLF(content)
            val applied = applyEditsToNormalizedContent(normalizedContent, edits, pathInput)
            signal?.throwIfAborted()

            val finalContent = bom + restoreLineEndings(applied.newContent, originalEnding)
            fs.write(absolutePath) { writeUtf8(finalContent) }
            signal?.throwIfAborted()

            val diffResult = generateDiffString(applied.baseContent, applied.newContent)
            val patch = generateUnifiedPatch(pathInput, applied.baseContent, applied.newContent)
            val details = buildJsonObject {
                put("diff", diffResult.diff)
                put("patch", patch)
                diffResult.firstChangedLine?.let { put("firstChangedLine", it) }
            }
            textResult("Successfully replaced ${edits.size} block(s) in $pathInput.", details)
        }
    },
)

private data class Edit(val oldText: String, val newText: String)

private data class AppliedEdits(val baseContent: String, val newContent: String)

private class MatchedEdit(
    val editIndex: Int,
    val matchIndex: Int,
    val matchLength: Int,
    val newText: String,
)

private class TextReplacement(
    val matchIndex: Int,
    val matchLength: Int,
    val newText: String,
)

private class FuzzyMatchResult(
    val found: Boolean,
    val index: Int,
    val matchLength: Int,
    val usedFuzzyMatch: Boolean,
    val contentForReplacement: String,
)

private fun prepareEditArguments(input: JsonObject): JsonObject {
    val args = input.toMutableMap()
    val editsValue = args["edits"]
    when {
        editsValue is JsonPrimitive && editsValue.isString -> {
            val parsed = runCatching { PiJson.parseToJsonElement(editsValue.content) }.getOrNull()
            when {
                parsed is JsonArray -> args["edits"] = parsed
                isSingleEditInput(parsed) -> args["edits"] = JsonArray(listOf(parsed as JsonObject))
            }
        }

        isSingleEditInput(editsValue) -> args["edits"] = JsonArray(listOf(editsValue as JsonObject))
    }

    val legacyOldText = args["oldText"]
    val legacyNewText = args["newText"]
    if (legacyOldText !is JsonPrimitive || !legacyOldText.isString) return JsonObject(args)
    if (legacyNewText !is JsonPrimitive || !legacyNewText.isString) return JsonObject(args)

    val edits = (args["edits"] as? JsonArray)?.toMutableList() ?: mutableListOf()
    edits += JsonObject(
        mapOf(
            "oldText" to JsonPrimitive(legacyOldText.content),
            "newText" to JsonPrimitive(legacyNewText.content),
        ),
    )
    args.remove("oldText")
    args.remove("newText")
    args["edits"] = JsonArray(edits)
    return JsonObject(args)
}

private fun isSingleEditInput(value: JsonElement?): Boolean {
    val obj = value as? JsonObject ?: return false
    val oldText = obj["oldText"]
    val newText = obj["newText"]
    return oldText is JsonPrimitive && oldText.isString && newText is JsonPrimitive && newText.isString
}

private fun parseEdits(args: JsonObject): List<Edit> {
    val editsValue = args["edits"]
    val array = editsValue as? JsonArray
        ?: throw IllegalArgumentException("Edit tool input is invalid. edits must contain at least one replacement.")
    if (array.isEmpty()) {
        throw IllegalArgumentException("Edit tool input is invalid. edits must contain at least one replacement.")
    }
    return array.map { element ->
        val obj = element as? JsonObject
            ?: throw IllegalArgumentException("Edit tool input is invalid. edits must contain at least one replacement.")
        val oldText = (obj["oldText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val newText = (obj["newText"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (oldText == null || newText == null) {
            throw IllegalArgumentException("Edit tool input is invalid. edits must contain at least one replacement.")
        }
        Edit(oldText, newText)
    }
}

private fun detectLineEnding(content: String): String {
    val crlfIndex = content.indexOf("\r\n")
    val lfIndex = content.indexOf("\n")
    if (lfIndex == -1) return "\n"
    if (crlfIndex == -1) return "\n"
    return if (crlfIndex < lfIndex) "\r\n" else "\n"
}

private fun normalizeToLF(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

private fun restoreLineEndings(text: String, ending: String): String =
    if (ending == "\r\n") text.replace("\n", "\r\n") else text

private fun normalizeForFuzzyMatch(text: String): String =
    text.split("\n").joinToString("\n") { it.trimEnd() }
        .replace(Regex("[\u2018\u2019\u201A\u201B]"), "'")
        .replace(Regex("[\u201C\u201D\u201E\u201F]"), "\"")
        .replace(Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"), "-")
        .replace(Regex("[\u00A0\u2002-\u200A\u202F\u205F\u3000]"), " ")

private fun fuzzyFindText(content: String, oldText: String): FuzzyMatchResult {
    val exactIndex = content.indexOf(oldText)
    if (exactIndex != -1) {
        return FuzzyMatchResult(true, exactIndex, oldText.length, false, content)
    }

    val fuzzyContent = normalizeForFuzzyMatch(content)
    val fuzzyOldText = normalizeForFuzzyMatch(oldText)
    val fuzzyIndex = fuzzyContent.indexOf(fuzzyOldText)
    if (fuzzyIndex == -1) {
        return FuzzyMatchResult(false, -1, 0, false, content)
    }
    return FuzzyMatchResult(true, fuzzyIndex, fuzzyOldText.length, true, fuzzyContent)
}

private fun countOccurrences(content: String, oldText: String): Int {
    val fuzzyContent = normalizeForFuzzyMatch(content)
    val fuzzyOldText = normalizeForFuzzyMatch(oldText)
    if (fuzzyOldText.isEmpty()) return 0
    var count = 0
    var index = fuzzyContent.indexOf(fuzzyOldText)
    while (index != -1) {
        count++
        index = fuzzyContent.indexOf(fuzzyOldText, index + fuzzyOldText.length)
    }
    return count
}

private fun notFoundError(path: String, editIndex: Int, totalEdits: Int): String =
    if (totalEdits == 1) {
        "Could not find the exact text in $path. The old text must match exactly including all whitespace and newlines."
    } else {
        "Could not find edits[$editIndex] in $path. The oldText must match exactly including all whitespace and newlines."
    }

private fun duplicateError(path: String, editIndex: Int, totalEdits: Int, occurrences: Int): String =
    if (totalEdits == 1) {
        "Found $occurrences occurrences of the text in $path. The text must be unique. Please provide more context to make it unique."
    } else {
        "Found $occurrences occurrences of edits[$editIndex] in $path. Each oldText must be unique. Please provide more context to make it unique."
    }

private fun emptyOldTextError(path: String, editIndex: Int, totalEdits: Int): String =
    if (totalEdits == 1) {
        "oldText must not be empty in $path."
    } else {
        "edits[$editIndex].oldText must not be empty in $path."
    }

private fun noChangeError(path: String, totalEdits: Int): String =
    if (totalEdits == 1) {
        "No changes made to $path. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected."
    } else {
        "No changes made to $path. The replacements produced identical content."
    }

private fun applyEditsToNormalizedContent(
    normalizedContent: String,
    edits: List<Edit>,
    path: String,
): AppliedEdits {
    val normalizedEdits = edits.map { Edit(normalizeToLF(it.oldText), normalizeToLF(it.newText)) }

    for (index in normalizedEdits.indices) {
        if (normalizedEdits[index].oldText.isEmpty()) {
            throw IllegalArgumentException(emptyOldTextError(path, index, normalizedEdits.size))
        }
    }

    val initialMatches = normalizedEdits.map { fuzzyFindText(normalizedContent, it.oldText) }
    val usedFuzzyMatch = initialMatches.any { it.usedFuzzyMatch }
    val replacementBaseContent = if (usedFuzzyMatch) normalizeForFuzzyMatch(normalizedContent) else normalizedContent

    val matchedEdits = mutableListOf<MatchedEdit>()
    for (index in normalizedEdits.indices) {
        val edit = normalizedEdits[index]
        val matchResult = fuzzyFindText(replacementBaseContent, edit.oldText)
        if (!matchResult.found) {
            throw IllegalArgumentException(notFoundError(path, index, normalizedEdits.size))
        }
        val occurrences = countOccurrences(replacementBaseContent, edit.oldText)
        if (occurrences > 1) {
            throw IllegalArgumentException(duplicateError(path, index, normalizedEdits.size, occurrences))
        }
        matchedEdits += MatchedEdit(index, matchResult.index, matchResult.matchLength, edit.newText)
    }

    matchedEdits.sortBy { it.matchIndex }
    for (index in 1 until matchedEdits.size) {
        val previous = matchedEdits[index - 1]
        val current = matchedEdits[index]
        if (previous.matchIndex + previous.matchLength > current.matchIndex) {
            throw IllegalArgumentException(
                "edits[${previous.editIndex}] and edits[${current.editIndex}] overlap in $path. Merge them into one edit or target disjoint regions.",
            )
        }
    }

    val baseContent = normalizedContent
    val newContent = if (usedFuzzyMatch) {
        applyReplacementsPreservingUnchangedLines(normalizedContent, replacementBaseContent, matchedEdits)
    } else {
        applyReplacements(
            replacementBaseContent,
            matchedEdits.map { TextReplacement(it.matchIndex, it.matchLength, it.newText) },
        )
    }

    if (baseContent == newContent) {
        throw IllegalArgumentException(noChangeError(path, normalizedEdits.size))
    }

    return AppliedEdits(baseContent, newContent)
}

private fun splitLinesWithEndings(content: String): List<String> {
    val lines = mutableListOf<String>()
    var index = 0
    while (index < content.length) {
        val newline = content.indexOf('\n', index)
        if (newline == -1) {
            lines += content.substring(index)
            break
        }
        lines += content.substring(index, newline + 1)
        index = newline + 1
    }
    return lines
}

private class LineSpan(val start: Int, val end: Int)

private fun getLineSpans(content: String): List<LineSpan> {
    var offset = 0
    return splitLinesWithEndings(content).map { line ->
        val span = LineSpan(offset, offset + line.length)
        offset = span.end
        span
    }
}

private fun getReplacementLineRange(lines: List<LineSpan>, replacement: TextReplacement): IntRange {
    val replacementStart = replacement.matchIndex
    val replacementEnd = replacement.matchIndex + replacement.matchLength

    var startLine = -1
    for (index in lines.indices) {
        val line = lines[index]
        if (replacementStart >= line.start && replacementStart < line.end) {
            startLine = index
            break
        }
    }
    if (startLine == -1) {
        throw IllegalArgumentException("Replacement range is outside the base content.")
    }

    var endLine = startLine
    while (endLine < lines.size && lines[endLine].end < replacementEnd) {
        endLine++
    }
    if (endLine >= lines.size) {
        throw IllegalArgumentException("Replacement range is outside the base content.")
    }
    return startLine until (endLine + 1)
}

private fun applyReplacements(
    content: String,
    replacements: List<TextReplacement>,
    offset: Int = 0,
): String {
    var result = content
    for (index in replacements.indices.reversed()) {
        val replacement = replacements[index]
        val matchIndex = replacement.matchIndex - offset
        result = result.substring(0, matchIndex) + replacement.newText +
            result.substring(matchIndex + replacement.matchLength)
    }
    return result
}

private fun applyReplacementsPreservingUnchangedLines(
    originalContent: String,
    baseContent: String,
    replacements: List<MatchedEdit>,
): String {
    val originalLines = splitLinesWithEndings(originalContent)
    val baseLines = getLineSpans(baseContent)
    if (originalLines.size != baseLines.size) {
        throw IllegalArgumentException(
            "Cannot preserve unchanged lines because the base content has a different line count.",
        )
    }

    class Group(val startLine: Int, var endLine: Int, val replacements: MutableList<MatchedEdit>)

    val groups = mutableListOf<Group>()
    val sortedReplacements = replacements.sortedBy { it.matchIndex }
    for (replacement in sortedReplacements) {
        val range = getReplacementLineRange(
            baseLines,
            TextReplacement(replacement.matchIndex, replacement.matchLength, replacement.newText),
        )
        val current = groups.lastOrNull()
        if (current != null && range.first < current.endLine) {
            current.endLine = maxOf(current.endLine, range.last + 1)
            current.replacements += replacement
            continue
        }
        groups += Group(range.first, range.last + 1, mutableListOf(replacement))
    }

    var originalLineIndex = 0
    val result = StringBuilder()
    for (group in groups) {
        for (index in originalLineIndex until group.startLine) {
            result.append(originalLines[index])
        }

        val groupStartOffset = baseLines[group.startLine].start
        val groupEndOffset = baseLines[group.endLine - 1].end
        result.append(
            applyReplacements(
                baseContent.substring(groupStartOffset, groupEndOffset),
                group.replacements.map { TextReplacement(it.matchIndex, it.matchLength, it.newText) },
                groupStartOffset,
            ),
        )
        originalLineIndex = group.endLine
    }
    for (index in originalLineIndex until originalLines.size) {
        result.append(originalLines[index])
    }
    return result.toString()
}

private enum class DiffType { Equal, Added, Removed }

private class DiffPart(val type: DiffType, val lines: MutableList<String>)

private fun diffLines(oldContent: String, newContent: String): List<DiffPart> {
    val oldLines = splitLinesWithEndings(oldContent)
    val newLines = splitLinesWithEndings(newContent)

    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
        prefix++
    }
    var suffix = 0
    while (
        suffix < oldLines.size - prefix &&
        suffix < newLines.size - prefix &&
        oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]
    ) {
        suffix++
    }

    val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
    val newMiddle = newLines.subList(prefix, newLines.size - suffix)

    val parts = mutableListOf<DiffPart>()
    fun addPart(type: DiffType, line: String) {
        val last = parts.lastOrNull()
        if (last != null && last.type == type) {
            last.lines += line
        } else {
            parts += DiffPart(type, mutableListOf(line))
        }
    }

    for (index in 0 until prefix) {
        addPart(DiffType.Equal, oldLines[index])
    }

    val middleOps = diffMiddleLines(oldMiddle, newMiddle)
    for (op in middleOps) {
        addPart(op.first, op.second)
    }

    for (index in oldLines.size - suffix until oldLines.size) {
        addPart(DiffType.Equal, oldLines[index])
    }
    return parts
}

private fun diffMiddleLines(oldLines: List<String>, newLines: List<String>): List<Pair<DiffType, String>> {
    val result = mutableListOf<Pair<DiffType, String>>()
    if (oldLines.isEmpty() && newLines.isEmpty()) return result
    if (oldLines.isEmpty()) {
        newLines.forEach { result += DiffType.Added to it }
        return result
    }
    if (newLines.isEmpty()) {
        oldLines.forEach { result += DiffType.Removed to it }
        return result
    }

    if (oldLines.size.toLong() * newLines.size.toLong() > LCS_CELL_LIMIT) {
        oldLines.forEach { result += DiffType.Removed to it }
        newLines.forEach { result += DiffType.Added to it }
        return result
    }

    val rows = oldLines.size
    val columns = newLines.size
    val dp = Array(rows + 1) { IntArray(columns + 1) }
    for (i in rows - 1 downTo 0) {
        for (j in columns - 1 downTo 0) {
            dp[i][j] = if (oldLines[i] == newLines[j]) {
                dp[i + 1][j + 1] + 1
            } else {
                maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
    }

    var i = 0
    var j = 0
    while (i < rows && j < columns) {
        if (oldLines[i] == newLines[j]) {
            result += DiffType.Equal to oldLines[i]
            i++
            j++
        } else if (dp[i + 1][j] >= dp[i][j + 1]) {
            result += DiffType.Removed to oldLines[i]
            i++
        } else {
            result += DiffType.Added to newLines[j]
            j++
        }
    }
    while (i < rows) {
        result += DiffType.Removed to oldLines[i]
        i++
    }
    while (j < columns) {
        result += DiffType.Added to newLines[j]
        j++
    }
    return result
}

private class DiffStringResult(val diff: String, val firstChangedLine: Int?)

private fun generateDiffString(
    oldContent: String,
    newContent: String,
    contextLines: Int = DIFF_CONTEXT_LINES,
): DiffStringResult {
    val parts = diffLines(oldContent, newContent)
    val output = mutableListOf<String>()

    val oldLines = oldContent.split("\n")
    val newLines = newContent.split("\n")
    val maxLineNum = maxOf(oldLines.size, newLines.size)
    val lineNumWidth = maxLineNum.toString().length

    var oldLineNum = 1
    var newLineNum = 1
    var lastWasChange = false
    var firstChangedLine: Int? = null

    for (index in parts.indices) {
        val part = parts[index]
        val raw = part.lines.map { it.removeSuffix("\n") }

        if (part.type != DiffType.Equal) {
            if (firstChangedLine == null) {
                firstChangedLine = newLineNum
            }
            for (line in raw) {
                if (part.type == DiffType.Added) {
                    output += "+" + newLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                    newLineNum++
                } else {
                    output += "-" + oldLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                    oldLineNum++
                }
            }
            lastWasChange = true
        } else {
            val nextPartIsChange = index < parts.size - 1 && parts[index + 1].type != DiffType.Equal
            val hasLeadingChange = lastWasChange
            val hasTrailingChange = nextPartIsChange

            if (hasLeadingChange && hasTrailingChange) {
                if (raw.size <= contextLines * 2) {
                    for (line in raw) {
                        output += " " + oldLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                        oldLineNum++
                        newLineNum++
                    }
                } else {
                    val leadingLines = raw.subList(0, contextLines)
                    val trailingLines = raw.subList(raw.size - contextLines, raw.size)
                    val skippedLines = raw.size - leadingLines.size - trailingLines.size

                    for (line in leadingLines) {
                        output += " " + oldLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                        oldLineNum++
                        newLineNum++
                    }
                    output += " " + "".padStart(lineNumWidth, ' ') + " ..."
                    oldLineNum += skippedLines
                    newLineNum += skippedLines
                    for (line in trailingLines) {
                        output += " " + oldLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                        oldLineNum++
                        newLineNum++
                    }
                }
            } else if (hasLeadingChange) {
                val shownLines = raw.subList(0, minOf(contextLines, raw.size))
                val skippedLines = raw.size - shownLines.size
                for (line in shownLines) {
                    output += " " + oldLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                    oldLineNum++
                    newLineNum++
                }
                if (skippedLines > 0) {
                    output += " " + "".padStart(lineNumWidth, ' ') + " ..."
                    oldLineNum += skippedLines
                    newLineNum += skippedLines
                }
            } else if (hasTrailingChange) {
                val skippedLines = maxOf(0, raw.size - contextLines)
                if (skippedLines > 0) {
                    output += " " + "".padStart(lineNumWidth, ' ') + " ..."
                    oldLineNum += skippedLines
                    newLineNum += skippedLines
                }
                for (line in raw.subList(skippedLines, raw.size)) {
                    output += " " + oldLineNum.toString().padStart(lineNumWidth, ' ') + " " + line
                    oldLineNum++
                    newLineNum++
                }
            } else {
                oldLineNum += raw.size
                newLineNum += raw.size
            }
            lastWasChange = false
        }
    }

    return DiffStringResult(output.joinToString("\n"), firstChangedLine)
}

private class FlatDiffEntry(
    val type: DiffType,
    val text: String,
    var oldIndexBefore: Int = 0,
    var newIndexBefore: Int = 0,
)

private fun generateUnifiedPatch(
    path: String,
    oldContent: String,
    newContent: String,
    contextLines: Int = DIFF_CONTEXT_LINES,
): String {
    val entries = mutableListOf<FlatDiffEntry>()
    for (part in diffLines(oldContent, newContent)) {
        for (line in part.lines) {
            entries += FlatDiffEntry(part.type, line)
        }
    }

    var oldIndex = 0
    var newIndex = 0
    for (entry in entries) {
        entry.oldIndexBefore = oldIndex
        entry.newIndexBefore = newIndex
        when (entry.type) {
            DiffType.Equal -> {
                oldIndex++
                newIndex++
            }

            DiffType.Removed -> oldIndex++
            DiffType.Added -> newIndex++
        }
    }

    val changeIndices = entries.indices.filter { entries[it].type != DiffType.Equal }
    if (changeIndices.isEmpty()) {
        return "$UNIFIED_PATCH_SEPARATOR\n--- $path\n+++ $path\n"
    }

    val ranges = mutableListOf<IntRange>()
    var rangeStart = maxOf(0, changeIndices.first() - contextLines)
    var rangeEnd = minOf(entries.size - 1, changeIndices.first() + contextLines)
    for (changeIndex in changeIndices.drop(1)) {
        val start = maxOf(0, changeIndex - contextLines)
        val end = minOf(entries.size - 1, changeIndex + contextLines)
        if (start <= rangeEnd + 1) {
            rangeEnd = maxOf(rangeEnd, end)
        } else {
            ranges += rangeStart..rangeEnd
            rangeStart = start
            rangeEnd = end
        }
    }
    ranges += rangeStart..rangeEnd

    val output = StringBuilder()
    output.append(UNIFIED_PATCH_SEPARATOR).append('\n')
    output.append("--- ").append(path).append('\n')
    output.append("+++ ").append(path).append('\n')

    for (range in ranges) {
        val oldStart = entries[range.first].oldIndexBefore
        val newStart = entries[range.first].newIndexBefore
        val oldCount = range.count { entries[it].type != DiffType.Added }
        val newCount = range.count { entries[it].type != DiffType.Removed }
        output.append("@@ -").append(formatUnifiedRange(oldStart, oldCount))
            .append(" +").append(formatUnifiedRange(newStart, newCount)).append(" @@\n")
        for (index in range) {
            val entry = entries[index]
            val prefix = when (entry.type) {
                DiffType.Equal -> ' '
                DiffType.Added -> '+'
                DiffType.Removed -> '-'
            }
            output.append(prefix).append(entry.text)
            if (!entry.text.endsWith("\n")) output.append('\n')
        }
    }

    return output.toString()
}

private fun formatUnifiedRange(startIndex: Int, count: Int): String = when {
    count == 0 -> "$startIndex,0"
    count == 1 -> "${startIndex + 1}"
    else -> "${startIndex + 1},$count"
}
