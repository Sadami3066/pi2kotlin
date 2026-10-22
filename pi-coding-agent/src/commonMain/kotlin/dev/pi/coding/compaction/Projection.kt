package dev.pi.coding.compaction

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Message
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.UserMessage
import dev.pi.coding.session.BranchSummaryEntry
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.ContextEditEntry
import dev.pi.coding.session.CustomMessageEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionMessageEntry
import dev.pi.coding.session.isExcludedFromContext
import dev.pi.coding.session.toContentList

data class ProjectedSessionEntry(
    val sourceEntry: SessionEntry,
    val messages: List<Message>,
)

class SessionProjection(val entries: List<ProjectedSessionEntry>) {
    val messages: List<Message> = entries.flatMap { it.messages }

    fun entryIndexForMessage(messageIndex: Int): Int {
        var projectedIndex = 0
        for (index in entries.indices) {
            projectedIndex += entries[index].messages.size
            if (messageIndex < projectedIndex) return index
        }
        return -1
    }
}

fun sessionEntryToContextMessages(entry: SessionEntry): List<Message> = when (entry) {
    is SessionMessageEntry -> if (entry.isExcludedFromContext()) emptyList() else listOf(entry.message)
    is CustomMessageEntry -> listOf(
        UserMessage(
            content = BlockContent(toContentList(entry.content)),
            timestamp = sessionTimestampToEpochMillis(entry.timestamp),
        ),
    )

    is BranchSummaryEntry -> if (entry.summary.isEmpty()) {
        emptyList()
    } else {
        listOf(
            UserMessage(
                content = BlockContent(
                    listOf(
                        TextContent(
                            SummaryPrompts.BRANCH_SUMMARY_PREFIX + entry.summary + SummaryPrompts.BRANCH_SUMMARY_SUFFIX,
                        ),
                    ),
                ),
                timestamp = sessionTimestampToEpochMillis(entry.timestamp),
            ),
        )
    }

    is CompactionEntry -> {
        val summaryMessage = UserMessage(
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
        val systemMessage = entry.systemMessage
        if (systemMessage == null) listOf(summaryMessage) else listOf(systemMessage, summaryMessage)
    }

    else -> emptyList()
}

fun buildContextEntries(pathEntries: List<SessionEntry>): List<SessionEntry> {
    val compaction = pathEntries.lastOrNull { it is CompactionEntry } as CompactionEntry? ?: return pathEntries
    val compactionIndex = pathEntries.indexOfFirst { it.id == compaction.id }
    if (compactionIndex < 0) return pathEntries
    val contextEntries = mutableListOf<SessionEntry>(compaction)
    var foundFirstKept = false
    for (index in 0 until compactionIndex) {
        val entry = pathEntries[index]
        if (entry.id == compaction.firstKeptEntryId) foundFirstKept = true
        if (foundFirstKept && !(entry is SessionMessageEntry && entry.message is dev.pi.ai.SystemMessage)) {
            contextEntries.add(entry)
        }
    }
    contextEntries.addAll(pathEntries.subList(compactionIndex + 1, pathEntries.size))
    return contextEntries
}

fun buildSessionProjection(pathEntries: List<SessionEntry>): SessionProjection {
    val contextEntries = buildContextEntries(pathEntries)
    val edits = HashMap<String, ContextEditEntry>()
    for (entry in contextEntries) {
        if (entry is ContextEditEntry) edits[entry.targetId] = entry
    }
    val projectedEntries = contextEntries.mapIndexed { index, sourceEntry ->
        ProjectedSessionEntry(
            sourceEntry = sourceEntry,
            messages = if (sourceEntry is CompactionEntry && index > 0) {
                emptyList()
            } else {
                projectContextEntry(sourceEntry, edits[sourceEntry.id])
            },
        )
    }
    return SessionProjection(projectedEntries)
}

private fun projectContextEntry(entry: SessionEntry, edit: ContextEditEntry?): List<Message> {
    val messages = sessionEntryToContextMessages(entry)
    if (edit == null) return messages
    val replacement = edit.replacement ?: return emptyList()
    return messages.map { message ->
        when (message) {
            is UserMessage -> message.copy(content = replacement.content)
            is AssistantMessage -> message.copy(content = toContentList(replacement.content))
            is ToolResultMessage -> message.copy(content = toContentList(replacement.content))
            else -> message
        }
    }
}
