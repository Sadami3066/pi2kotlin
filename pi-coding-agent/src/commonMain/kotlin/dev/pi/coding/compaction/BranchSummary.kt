package dev.pi.coding.compaction

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.coding.session.BranchSummaryEntry
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.CustomMessageEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionManager
import dev.pi.coding.session.SessionMessageEntry
import dev.pi.coding.session.toContentList
import kotlinx.serialization.json.JsonObject

data class BranchSummaryResult(
    val summary: String? = null,
    val usage: Usage? = null,
    val readFiles: List<String>? = null,
    val modifiedFiles: List<String>? = null,
    val aborted: Boolean? = null,
    val error: String? = null,
)

data class BranchSummaryDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "readFiles" to kotlinx.serialization.json.JsonArray(readFiles.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            "modifiedFiles" to kotlinx.serialization.json.JsonArray(
                modifiedFiles.map { kotlinx.serialization.json.JsonPrimitive(it) },
            ),
        ),
    )
}

data class BranchPreparation(
    val messages: List<Message>,
    val fileOps: FileOperations,
    val totalTokens: Long,
)

data class CollectEntriesResult(
    val entries: List<SessionEntry>,
    val commonAncestorId: String?,
)

data class GenerateBranchSummaryOptions(
    val model: Model,
    val reserveTokens: Long = 16384,
    val customInstructions: String? = null,
    val replaceInstructions: Boolean = false,
)

fun collectEntriesForBranchSummary(
    session: SessionManager,
    oldLeafId: String?,
    targetId: String,
): CollectEntriesResult {
    if (oldLeafId == null) return CollectEntriesResult(emptyList(), null)

    val oldPath = session.getBranch(oldLeafId).mapTo(HashSet()) { it.id }
    val targetPath = session.getBranch(targetId)

    var commonAncestorId: String? = null
    for (index in targetPath.indices.reversed()) {
        if (oldPath.contains(targetPath[index].id)) {
            commonAncestorId = targetPath[index].id
            break
        }
    }

    val entries = mutableListOf<SessionEntry>()
    var current: String? = oldLeafId
    while (current != null && current != commonAncestorId) {
        val entry = session.getEntry(current) ?: break
        entries.add(entry)
        current = entry.parentId
    }
    entries.reverse()
    return CollectEntriesResult(entries, commonAncestorId)
}

private fun getMessageFromEntry(entry: SessionEntry): Message? = when (entry) {
    is SessionMessageEntry -> if (entry.message is ToolResultMessage) null else entry.message
    is CustomMessageEntry -> UserMessage(
        content = BlockContent(toContentList(entry.content)),
        timestamp = sessionTimestampToEpochMillis(entry.timestamp),
    )

    is BranchSummaryEntry -> UserMessage(
        content = BlockContent(
            listOf(
                TextContent(
                    SummaryPrompts.BRANCH_SUMMARY_PREFIX + entry.summary + SummaryPrompts.BRANCH_SUMMARY_SUFFIX,
                ),
            ),
        ),
        timestamp = sessionTimestampToEpochMillis(entry.timestamp),
    )

    is CompactionEntry -> UserMessage(
        content = BlockContent(
            listOf(
                TextContent(
                    SummaryPrompts.COMPACTION_SUMMARY_PREFIX + entry.summary +
                        SummaryPrompts.COMPACTION_SUMMARY_SUFFIX,
                ),
            ),
        ),
        timestamp = sessionTimestampToEpochMillis(entry.timestamp),
    )

    else -> null
}

fun prepareBranchEntries(entries: List<SessionEntry>, tokenBudget: Long = 0): BranchPreparation {
    val messages = mutableListOf<Message>()
    val fileOps = createFileOps()
    var totalTokens = 0L

    for (entry in entries) {
        if (entry is BranchSummaryEntry && entry.fromHook != true && entry.details != null) {
            val details = entry.details as? JsonObject
            if (details != null) {
                jsonStringList(details["readFiles"]).forEach { fileOps.read.add(it) }
                jsonStringList(details["modifiedFiles"]).forEach { fileOps.edited.add(it) }
            }
        }
    }

    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        val message = getMessageFromEntry(entry) ?: continue

        extractFileOpsFromMessage(message, fileOps)
        val tokens = estimateTokens(message)

        if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
            if (entry is CompactionEntry || entry is BranchSummaryEntry) {
                if (totalTokens < tokenBudget * 0.9) {
                    messages.add(0, message)
                    totalTokens += tokens
                }
            }
            break
        }

        messages.add(0, message)
        totalTokens += tokens
    }

    return BranchPreparation(messages, fileOps, totalTokens)
}

suspend fun generateBranchSummary(
    entries: List<SessionEntry>,
    options: GenerateBranchSummaryOptions,
    summarize: Summarize,
): BranchSummaryResult {
    val contextWindow = if (options.model.contextWindow != 0L) options.model.contextWindow else 128_000L
    val tokenBudget = contextWindow - options.reserveTokens

    val preparation = prepareBranchEntries(entries, tokenBudget)
    if (preparation.messages.isEmpty()) return BranchSummaryResult(summary = "No content to summarize")

    val conversationText = serializeConversation(preparation.messages)
    val instructions = when {
        options.replaceInstructions && options.customInstructions != null -> options.customInstructions
        options.customInstructions != null ->
            SummaryPrompts.BRANCH_SUMMARY_PROMPT + SummaryPrompts.ADDITIONAL_FOCUS_PREFIX + options.customInstructions

        else -> SummaryPrompts.BRANCH_SUMMARY_PROMPT
    }
    val promptText = SummaryPrompts.CONVERSATION_OPEN + conversationText + SummaryPrompts.CONVERSATION_CLOSE +
        instructions

    val response = completeSummarization(
        options.model,
        SummaryPrompts.SUMMARIZATION_SYSTEM_PROMPT,
        promptText,
        null,
        summarize,
    )

    if (response.stopReason == StopReason.Aborted) return BranchSummaryResult(aborted = true)
    val failure = getSummarizationFailure(response, SummaryPrompts.SUMMARY_FAILED_BRANCH_LABEL)
    if (failure != null) return BranchSummaryResult(error = failure)
    if (response.content.any { it is ToolCall }) {
        return BranchSummaryResult(error = "Branch summarization attempted to call a tool")
    }

    var summary = SummaryPrompts.BRANCH_SUMMARY_PREAMBLE + contentText(response.content)
    val fileLists = computeFileLists(preparation.fileOps)
    summary += formatFileOperations(fileLists.readFiles, fileLists.modifiedFiles)

    return BranchSummaryResult(
        summary = summary.ifEmpty { "No summary generated" },
        usage = response.usage,
        readFiles = fileLists.readFiles,
        modifiedFiles = fileLists.modifiedFiles,
    )
}
