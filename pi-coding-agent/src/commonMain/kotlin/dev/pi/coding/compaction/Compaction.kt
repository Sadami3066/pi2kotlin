package dev.pi.coding.compaction

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.utils.getCurrentSystemMessage
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.ContextEditEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionMessageEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

typealias CompactionSettings = dev.pi.coding.settings.CompactionSettings

val DEFAULT_COMPACTION_SETTINGS: CompactionSettings = CompactionSettings()

data class ContextUsageEstimate(
    val tokens: Long,
    val usageTokens: Long,
    val trailingTokens: Long,
    val lastUsageIndex: Int?,
)

data class CompactionDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "readFiles" to JsonArray(readFiles.map { JsonPrimitive(it) }),
            "modifiedFiles" to JsonArray(modifiedFiles.map { JsonPrimitive(it) }),
        ),
    )
}

data class CompactionResult(
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Long,
    val estimatedTokensAfter: Long? = null,
    val usage: Usage? = null,
    val details: JsonObject? = null,
    val fromHook: Boolean? = null,
)

data class CompactionPreparation(
    val firstKeptEntryId: String,
    val messagesToSummarize: List<Message>,
    val turnPrefixMessages: List<Message>,
    val isSplitTurn: Boolean,
    val tokensBefore: Long,
    val previousSummary: String?,
    val fileOps: FileOperations,
    val settings: CompactionSettings,
    val model: Model,
    val customInstructions: String? = null,
)

data class SummarizeRequest(
    val model: Model,
    val systemPrompt: String?,
    val messages: List<Message>,
    val maxTokens: Long?,
)

fun interface Summarize {
    suspend fun summarize(request: SummarizeRequest): AssistantMessage
}

data class CutPointResult(
    val firstKeptEntryIndex: Int,
    val turnStartIndex: Int,
    val isSplitTurn: Boolean,
)

fun calculateContextTokens(usage: Usage): Long =
    if (usage.totalTokens != 0L) {
        usage.totalTokens
    } else {
        usage.input + usage.output + usage.cacheRead + usage.cacheWrite
    }

fun getAssistantUsage(message: Message): Usage? {
    val assistant = message as? AssistantMessage ?: return null
    if (assistant.stopReason == StopReason.Aborted || assistant.stopReason == StopReason.Error) return null
    return if (calculateContextTokens(assistant.usage) > 0L) assistant.usage else null
}

fun getLastAssistantUsage(entries: List<SessionEntry>): Usage? {
    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        if (entry is SessionMessageEntry) {
            val usage = getAssistantUsage(entry.message)
            if (usage != null) return usage
        }
    }
    return null
}

fun estimateTokens(message: Message): Long = when (message) {
    is SystemMessage -> {
        var chars = estimateTextAndImageContentChars(message.content)
        message.sections?.values?.forEach { section -> if (section != null) chars += section.length }
        message.toolsAdded?.let { tools -> chars += encodeToolsJson(tools).toString().length }
        estimateCharsToTokens(chars)
    }

    is UserMessage -> estimateCharsToTokens(estimateTextAndImageContentChars(message.content))

    is AssistantMessage -> {
        var chars = 0L
        for (block in message.content) {
            when (block) {
                is TextContent -> chars += block.text.length
                is ThinkingContent -> chars += block.thinking.length
                is ToolCall -> chars += block.name.length + block.arguments.toString().length
                else -> Unit
            }
        }
        estimateCharsToTokens(chars)
    }

    is ToolResultMessage -> estimateCharsToTokens(estimateTextAndImageContentChars(message.content))
}

fun estimateMessagesTokens(messages: List<Message>): Long = messages.sumOf { estimateTokens(it) }

fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate {
    val usageInfo = getLastAssistantUsageInfo(messages)
    if (usageInfo == null) {
        var estimated = 0L
        for (message in messages) estimated += estimateTokens(message)
        return ContextUsageEstimate(estimated, 0L, estimated, null)
    }
    val usageTokens = calculateContextTokens(usageInfo.second)
    var trailingTokens = 0L
    for (index in usageInfo.first + 1 until messages.size) {
        trailingTokens += estimateTokens(messages[index])
    }
    return ContextUsageEstimate(usageTokens + trailingTokens, usageTokens, trailingTokens, usageInfo.first)
}

private fun getLastAssistantUsageInfo(messages: List<Message>): Pair<Int, Usage>? {
    for (index in messages.indices.reversed()) {
        val usage = getAssistantUsage(messages[index])
        if (usage != null) return index to usage
    }
    return null
}

fun estimateProjectedContextTokens(
    projection: SessionProjection,
    branchEntries: List<SessionEntry>,
): ContextUsageEstimate {
    val estimate = estimateContextTokens(projection.messages)
    val lastUsageIndex = estimate.lastUsageIndex
    if (lastUsageIndex != null) {
        val entryIndex = projection.entryIndexForMessage(lastUsageIndex)
        val usageEntryId = if (entryIndex >= 0) projection.entries[entryIndex].sourceEntry.id else null
        val usageEntryIndex = if (usageEntryId != null) {
            branchEntries.indexOfFirst { it.id == usageEntryId }
        } else {
            -1
        }
        var latestInvalidatingEntryIndex = -1
        for (index in branchEntries.indices.reversed()) {
            val type = branchEntries[index].type
            if (type == "context_edit" || type == "compaction") {
                latestInvalidatingEntryIndex = index
                break
            }
        }
        if (usageEntryIndex > latestInvalidatingEntryIndex) return estimate
    }

    val currentSystem = getCurrentSystemMessage(projection.messages)
    var tokens = if (currentSystem != null) estimateTokens(currentSystem) else 0L
    for (message in projection.messages) {
        if (message !is SystemMessage) tokens += estimateTokens(message)
    }
    return ContextUsageEstimate(tokens, 0L, tokens, null)
}

fun shouldCompact(contextTokens: Long, contextWindow: Long, settings: CompactionSettings): Boolean {
    if (!settings.enabled) return false
    return contextTokens > contextWindow - settings.reserveTokens
}

fun isCutPointMessage(message: Message): Boolean = when (message) {
    is UserMessage, is AssistantMessage -> true
    else -> false
}

fun isTurnStartMessage(message: Message): Boolean = message is UserMessage

fun isTurnStartEntry(entry: SessionEntry): Boolean {
    if (entry is CompactionEntry) return false
    return sessionEntryToContextMessages(entry).any(::isTurnStartMessage)
}

fun findValidCutPoints(entries: List<SessionEntry>, startIndex: Int, endIndex: Int): List<Int> {
    val cutPoints = mutableListOf<Int>()
    for (index in startIndex until endIndex) {
        val entry = entries[index]
        if (entry is CompactionEntry) continue
        if (sessionEntryToContextMessages(entry).any(::isCutPointMessage)) cutPoints.add(index)
    }
    return cutPoints
}

fun findTurnStartIndex(entries: List<SessionEntry>, entryIndex: Int, startIndex: Int): Int {
    for (index in entryIndex downTo startIndex) {
        if (isTurnStartEntry(entries[index])) return index
    }
    return -1
}

fun findCutPoint(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Long,
): CutPointResult {
    val cutPoints = findValidCutPoints(entries, startIndex, endIndex)
    if (cutPoints.isEmpty()) return CutPointResult(startIndex, -1, false)

    var accumulatedTokens = 0L
    var cutIndex = cutPoints.first()
    for (index in endIndex - 1 downTo startIndex) {
        val messageTokens = sessionEntryToContextMessages(entries[index]).sumOf { estimateTokens(it) }
        if (messageTokens == 0L) continue
        accumulatedTokens += messageTokens
        if (accumulatedTokens >= keepRecentTokens) {
            cutIndex = cutPoints.firstOrNull { it >= index } ?: cutPoints.last()
            break
        }
    }

    while (cutIndex > startIndex) {
        val previous = entries[cutIndex - 1]
        if (previous is CompactionEntry || sessionEntryToContextMessages(previous).isNotEmpty()) break
        cutIndex--
    }

    val startsTurn = isTurnStartEntry(entries[cutIndex])
    val turnStartIndex = if (startsTurn) -1 else findTurnStartIndex(entries, cutIndex, startIndex)
    return CutPointResult(cutIndex, turnStartIndex, !startsTurn && turnStartIndex != -1)
}

private fun isProjectedTurnStart(entry: ProjectedSessionEntry): Boolean {
    if (entry.sourceEntry is CompactionEntry) return false
    return entry.messages.any(::isTurnStartMessage)
}

private fun findProjectedTurnStartIndex(entries: List<ProjectedSessionEntry>, entryIndex: Int, startIndex: Int): Int {
    for (index in entryIndex downTo startIndex) {
        if (isProjectedTurnStart(entries[index])) return index
    }
    return -1
}

private fun findProjectedCutPoint(
    entries: List<ProjectedSessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Long,
): CutPointResult {
    val cutPoints = mutableListOf<Int>()
    for (index in startIndex until endIndex) {
        val entry = entries[index]
        if (entry.sourceEntry !is CompactionEntry && entry.messages.any(::isCutPointMessage)) cutPoints.add(index)
    }
    if (cutPoints.isEmpty()) return CutPointResult(startIndex, -1, false)

    var accumulatedTokens = 0L
    var exceededBudget = false
    var cutIndex = cutPoints.first()
    for (index in endIndex - 1 downTo startIndex) {
        val messageTokens = entries[index].messages.sumOf { estimateTokens(it) }
        if (messageTokens == 0L) continue
        accumulatedTokens += messageTokens
        if (accumulatedTokens >= keepRecentTokens) {
            exceededBudget = true
            cutIndex = cutPoints.firstOrNull { it >= index } ?: cutPoints.last()
            break
        }
    }

    val suffix = entries.subList(cutIndex + 1, endIndex)
    val isIntrinsicallyVisible = { entry: ProjectedSessionEntry ->
        entry.sourceEntry !is ContextEditEntry && sessionEntryToContextMessages(entry.sourceEntry).isNotEmpty()
    }
    val isOmitted = { entry: ProjectedSessionEntry -> isIntrinsicallyVisible(entry) && entry.messages.isEmpty() }
    val omittedSuffixIds = suffix.filter { isOmitted(it) }.map { it.sourceEntry.id }.toSet()
    val hasExternalReplacement = suffix.any { entry ->
        val edit = entry.sourceEntry
        edit is ContextEditEntry && edit.replacement != null && edit.targetId !in omittedSuffixIds
    }
    val isRecoveryOmissionSuffix = exceededBudget &&
        !hasExternalReplacement &&
        suffix.any { entry ->
            val source = entry.sourceEntry
            source is SessionMessageEntry && source.message is AssistantMessage && isOmitted(entry)
        } &&
        suffix.all { entry ->
            entry.sourceEntry !is CompactionEntry && (!isIntrinsicallyVisible(entry) || isOmitted(entry))
        }
    if (isRecoveryOmissionSuffix) cutIndex++

    while (cutIndex > startIndex) {
        val previous = entries[cutIndex - 1]
        if (previous.sourceEntry is CompactionEntry || previous.messages.isNotEmpty()) break
        cutIndex--
    }
    val startsTurn = isProjectedTurnStart(entries[cutIndex])
    val turnStartIndex = if (startsTurn) -1 else findProjectedTurnStartIndex(entries, cutIndex, startIndex)
    return CutPointResult(cutIndex, turnStartIndex, !startsTurn && turnStartIndex != -1)
}

private fun getMessagesFromProjectedEntryForCompaction(entry: ProjectedSessionEntry): List<Message> {
    if (entry.sourceEntry is CompactionEntry) return emptyList()
    return entry.messages.filter { it !is SystemMessage }
}

private fun extractFileOperations(
    messages: List<Message>,
    entries: List<SessionEntry>,
    prevCompactionIndex: Int,
): FileOperations {
    val fileOps = createFileOps()
    if (prevCompactionIndex >= 0) {
        val previous = entries.getOrNull(prevCompactionIndex) as? CompactionEntry
        if (previous != null && previous.fromHook != true && previous.details != null) {
            val details = previous.details as? JsonObject
            if (details != null) {
                jsonStringList(details["readFiles"]).forEach { fileOps.read.add(it) }
                jsonStringList(details["modifiedFiles"]).forEach { fileOps.edited.add(it) }
            }
        }
    }
    for (message in messages) extractFileOpsFromMessage(message, fileOps)
    return fileOps
}

fun prepareCompaction(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings,
    model: Model,
    customInstructions: String? = null,
): CompactionPreparation? {
    if (pathEntries.isNotEmpty() && pathEntries.last() is CompactionEntry) return null

    val projection = buildSessionProjection(pathEntries)
    val projectedEntries = projection.entries
    val sourceEntries = projectedEntries.map { it.sourceEntry }
    val prevCompactionIndex = projectedEntries.indexOfFirst { entry ->
        entry.sourceEntry is CompactionEntry && entry.messages.isNotEmpty()
    }

    var previousSummary: String? = null
    var boundaryStart = 0
    if (prevCompactionIndex >= 0) {
        previousSummary = (projectedEntries[prevCompactionIndex].sourceEntry as CompactionEntry).summary
        boundaryStart = prevCompactionIndex + 1
    }
    val boundaryEnd = projectedEntries.size
    val tokensBefore = estimateProjectedContextTokens(projection, pathEntries).tokens
    val cutPoint = findProjectedCutPoint(projectedEntries, boundaryStart, boundaryEnd, settings.keepRecentTokens)

    val firstKeptEntry = projectedEntries.getOrNull(cutPoint.firstKeptEntryIndex)?.sourceEntry ?: return null
    val firstKeptEntryId = firstKeptEntry.id
    val historyEnd = if (cutPoint.isSplitTurn) cutPoint.turnStartIndex else cutPoint.firstKeptEntryIndex

    val messagesToSummarize = projectedEntries
        .subList(boundaryStart, historyEnd)
        .flatMap(::getMessagesFromProjectedEntryForCompaction)
    val turnPrefixMessages = if (cutPoint.isSplitTurn) {
        projectedEntries
            .subList(cutPoint.turnStartIndex, cutPoint.firstKeptEntryIndex)
            .flatMap(::getMessagesFromProjectedEntryForCompaction)
    } else {
        emptyList()
    }

    if (messagesToSummarize.isEmpty() && turnPrefixMessages.isEmpty()) return null

    val fileOps = extractFileOperations(messagesToSummarize, sourceEntries, prevCompactionIndex)
    if (cutPoint.isSplitTurn) {
        for (message in turnPrefixMessages) extractFileOpsFromMessage(message, fileOps)
    }

    return CompactionPreparation(
        firstKeptEntryId = firstKeptEntryId,
        messagesToSummarize = messagesToSummarize,
        turnPrefixMessages = turnPrefixMessages,
        isSplitTurn = cutPoint.isSplitTurn,
        tokensBefore = tokensBefore,
        previousSummary = previousSummary,
        fileOps = fileOps,
        settings = settings,
        model = model,
        customInstructions = customInstructions,
    )
}

fun getSummarizationFailure(response: AssistantMessage, label: String): String? {
    if (response.stopReason == StopReason.Error) {
        return "$label failed: ${response.errorMessage ?: "Unknown error"}"
    }
    if (response.stopReason == StopReason.Length) {
        return "$label failed: generation hit the token cap and the summary is incomplete"
    }
    return null
}

private class SummarizationAbortedException : Exception()

suspend fun completeSummarization(
    model: Model,
    systemPrompt: String?,
    promptText: String,
    maxTokens: Long? = null,
    summarize: Summarize,
): AssistantMessage {
    val messages = listOf(
        UserMessage(
            content = BlockContent(listOf(TextContent(promptText))),
            timestamp = currentTimeMillis(),
        ),
    )
    return summarize.summarize(SummarizeRequest(model, systemPrompt, messages, maxTokens))
}

private class SummaryResult(
    val text: String,
    val usage: Usage,
)

private suspend fun generateSummaryWithUsage(
    messages: List<Message>,
    model: Model,
    previousSummary: String?,
    customInstructions: String?,
    summarize: Summarize,
): SummaryResult {
    var basePrompt = if (previousSummary != null) {
        SummaryPrompts.UPDATE_SUMMARIZATION_PROMPT
    } else {
        SummaryPrompts.SUMMARIZATION_PROMPT
    }
    if (!customInstructions.isNullOrEmpty()) {
        basePrompt = "$basePrompt${SummaryPrompts.ADDITIONAL_FOCUS_PREFIX}$customInstructions"
    }

    val conversationText = serializeConversation(messages)
    var promptText = SummaryPrompts.CONVERSATION_OPEN + conversationText + SummaryPrompts.CONVERSATION_CLOSE
    if (previousSummary != null) {
        promptText += SummaryPrompts.PREVIOUS_SUMMARY_OPEN + previousSummary +
            SummaryPrompts.PREVIOUS_SUMMARY_CLOSE
    }
    promptText += basePrompt

    val response = completeSummarization(model, SummaryPrompts.SUMMARIZATION_SYSTEM_PROMPT, promptText, null, summarize)
    checkResponse(response, SummaryPrompts.SUMMARY_FAILED_SYSTEM_LABEL, "Summarization")
    return SummaryResult(contentText(response.content), response.usage)
}

private suspend fun generateTurnPrefixSummary(
    messages: List<Message>,
    model: Model,
    customInstructions: String?,
    summarize: Summarize,
): SummaryResult {
    val conversationText = serializeConversation(messages)
    var promptText = SummaryPrompts.TURN_PREFIX_INPUT_PREFIX + conversationText +
        SummaryPrompts.TURN_PREFIX_INSTRUCTIONS_PREFIX + SummaryPrompts.TURN_PREFIX_SUMMARIZATION_PROMPT
    if (!customInstructions.isNullOrEmpty()) {
        promptText = "$promptText${SummaryPrompts.ADDITIONAL_FOCUS_PREFIX}$customInstructions"
    }

    val response = completeSummarization(model, SummaryPrompts.SUMMARIZATION_SYSTEM_PROMPT, promptText, null, summarize)
    checkResponse(response, SummaryPrompts.SUMMARY_FAILED_TURN_PREFIX_LABEL, "Turn prefix summarization")
    return SummaryResult(contentText(response.content), response.usage)
}

private fun checkResponse(response: AssistantMessage, label: String, toolCallLabel: String) {
    if (response.stopReason == StopReason.Aborted) throw SummarizationAbortedException()
    val failure = getSummarizationFailure(response, label)
    if (failure != null) throw IllegalStateException(failure)
    if (response.content.any { it is ToolCall }) {
        throw IllegalStateException("$toolCallLabel attempted to call a tool")
    }
}

suspend fun compact(preparation: CompactionPreparation, summarize: Summarize): CompactionResult? = try {
    runCompaction(preparation, preparation.customInstructions, summarize)
} catch (_: SummarizationAbortedException) {
    null
}

suspend fun compact(
    preparation: CompactionPreparation,
    customInstructions: String?,
    summarize: Summarize,
): CompactionResult? = try {
    runCompaction(preparation, customInstructions ?: preparation.customInstructions, summarize)
} catch (_: SummarizationAbortedException) {
    null
}

private suspend fun runCompaction(
    preparation: CompactionPreparation,
    customInstructions: String?,
    summarize: Summarize,
): CompactionResult {
    val summary: String
    val summaryUsage: Usage
    if (preparation.isSplitTurn && preparation.turnPrefixMessages.isNotEmpty()) {
        var historyText = preparation.previousSummary ?: SummaryPrompts.NO_PRIOR_HISTORY
        var historyUsage: Usage? = null
        if (preparation.messagesToSummarize.isNotEmpty()) {
            val history = generateSummaryWithUsage(
                preparation.messagesToSummarize,
                preparation.model,
                preparation.previousSummary,
                customInstructions,
                summarize,
            )
            historyText = history.text
            historyUsage = history.usage
        }
        val turnPrefix = generateTurnPrefixSummary(
            preparation.turnPrefixMessages,
            preparation.model,
            customInstructions,
            summarize,
        )
        summary = "$historyText\n\n---\n\n${SummaryPrompts.SPLIT_TURN_MARKER}\n\n${turnPrefix.text}"
        summaryUsage = historyUsage?.let { combineUsage(it, turnPrefix.usage) } ?: turnPrefix.usage
    } else {
        val result = generateSummaryWithUsage(
            preparation.messagesToSummarize,
            preparation.model,
            preparation.previousSummary,
            customInstructions,
            summarize,
        )
        summary = result.text
        summaryUsage = result.usage
    }

    val fileLists = computeFileLists(preparation.fileOps)
    val summaryWithFiles = summary + formatFileOperations(fileLists.readFiles, fileLists.modifiedFiles)

    if (preparation.firstKeptEntryId.isEmpty()) {
        throw IllegalStateException("First kept entry has no UUID - session may need migration")
    }

    return CompactionResult(
        summary = summaryWithFiles,
        firstKeptEntryId = preparation.firstKeptEntryId,
        tokensBefore = preparation.tokensBefore,
        usage = summaryUsage,
        details = CompactionDetails(fileLists.readFiles, fileLists.modifiedFiles).toJson(),
    )
}
