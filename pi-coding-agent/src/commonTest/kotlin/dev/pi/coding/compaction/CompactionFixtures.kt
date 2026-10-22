package dev.pi.coding.compaction

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.ContentReplacement
import dev.pi.coding.session.ContextEditEntry
import dev.pi.coding.session.CustomMessageEntry
import dev.pi.coding.session.BranchSummaryEntry
import dev.pi.coding.session.ModelChangeEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionMessageEntry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val model = Model(
    id = "mock",
    name = "mock",
    api = "openai-completions",
    provider = "openai",
    baseUrl = "",
    input = listOf(ModelInputModality.Text),
    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
    reasoning = false,
    contextWindow = 1000,
    maxTokens = 100,
)

internal fun ts(second: Int): String = "2025-01-01T00:00:${second.toString().padStart(2, '0')}.000Z"

internal fun millis(second: Int): Long = 1_735_689_600_000L + second * 1000L

internal fun emptyUsage(): Usage = Usage.empty()

internal fun usageTotal(total: Long): Usage = Usage(
    input = 0,
    output = 0,
    cacheRead = 0,
    cacheWrite = 0,
    totalTokens = total,
    cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
)

internal fun usageComponents(input: Long, output: Long, cacheRead: Long, cacheWrite: Long): Usage = Usage(
    input = input,
    output = output,
    cacheRead = cacheRead,
    cacheWrite = cacheWrite,
    totalTokens = 0,
    cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
)

internal fun u(id: String, parent: String?, text: String, second: Int = 0): SessionMessageEntry =
    SessionMessageEntry(
        id = id,
        parentId = parent,
        timestamp = ts(second),
        message = UserMessage(PlainTextContent(text), millis(second)),
    )

internal fun sys(id: String, parent: String?, text: String, second: Int = 0): SessionMessageEntry =
    SessionMessageEntry(
        id = id,
        parentId = parent,
        timestamp = ts(second),
        message = dev.pi.ai.SystemMessage(content = PlainTextContent(text), timestamp = millis(second)),
    )

internal fun a(
    id: String,
    parent: String?,
    text: String? = null,
    toolCalls: List<ToolCall> = emptyList(),
    usage: Usage = emptyUsage(),
    stopReason: StopReason = StopReason.Stop,
    second: Int = 0,
): SessionMessageEntry {
    val content = mutableListOf<Content>()
    if (text != null) content.add(TextContent(text))
    content.addAll(toolCalls)
    return SessionMessageEntry(
        id = id,
        parentId = parent,
        timestamp = ts(second),
        message = AssistantMessage(
            content = content,
            api = "openai-completions",
            provider = "openai",
            model = "mock",
            usage = usage,
            stopReason = stopReason,
            timestamp = millis(second),
        ),
    )
}

internal fun tr(id: String, parent: String?, toolCallId: String, text: String, second: Int = 0): SessionMessageEntry =
    SessionMessageEntry(
        id = id,
        parentId = parent,
        timestamp = ts(second),
        message = ToolResultMessage(
            toolCallId = toolCallId,
            toolName = "read",
            content = listOf(TextContent(text)),
            isError = false,
            timestamp = millis(second),
        ),
    )

internal fun compaction(
    id: String,
    parent: String?,
    summary: String,
    firstKeptEntryId: String,
    tokensBefore: Long = 100,
    details: JsonObject? = null,
    fromHook: Boolean? = null,
    second: Int = 0,
): CompactionEntry = CompactionEntry(
    id = id,
    parentId = parent,
    timestamp = ts(second),
    summary = summary,
    firstKeptEntryId = firstKeptEntryId,
    tokensBefore = tokensBefore,
    details = details,
    fromHook = fromHook,
)

internal fun customMessage(
    id: String,
    parent: String?,
    customType: String,
    text: String,
    second: Int = 0,
): CustomMessageEntry = CustomMessageEntry(
    id = id,
    parentId = parent,
    timestamp = ts(second),
    customType = customType,
    content = PlainTextContent(text),
    display = true,
)

internal fun contextEdit(
    id: String,
    parent: String?,
    targetId: String,
    replacement: ContentReplacement?,
    second: Int = 0,
): ContextEditEntry = ContextEditEntry(
    id = id,
    parentId = parent,
    timestamp = ts(second),
    targetId = targetId,
    replacement = replacement,
)

internal fun modelChange(id: String, parent: String?, second: Int = 0): ModelChangeEntry = ModelChangeEntry(
    id = id,
    parentId = parent,
    timestamp = ts(second),
    provider = "openai",
    modelId = "mock",
)

internal fun summaryMessage(
    text: String,
    usage: Usage = emptyUsage(),
    stopReason: StopReason = StopReason.Stop,
    toolCalls: List<ToolCall> = emptyList(),
    errorMessage: String? = null,
): AssistantMessage {
    val content = mutableListOf<Content>(TextContent(text))
    content.addAll(toolCalls)
    return AssistantMessage(
        content = content,
        api = "openai-completions",
        provider = "openai",
        model = "mock",
        usage = usage,
        stopReason = stopReason,
        errorMessage = errorMessage,
        timestamp = 0,
    )
}

internal class FakeSummarize(
    private val responses: List<AssistantMessage>,
) : Summarize {
    val requests = mutableListOf<SummarizeRequest>()
    private var index = 0

    override suspend fun summarize(request: SummarizeRequest): AssistantMessage {
        requests.add(request)
        val response = responses[minOf(index, responses.size - 1)]
        index++
        return response
    }
}

internal fun promptTextOf(request: SummarizeRequest): String {
    val message = request.messages.single() as UserMessage
    return (message.content as BlockContent).blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
}

internal fun cutPoint(index: Int, turnStart: Int = -1, split: Boolean = false) = CutPointResult(index, turnStart, split)


internal fun roleOf(message: dev.pi.ai.Message): String = when (message) {
    is dev.pi.ai.SystemMessage -> "system"
    is UserMessage -> "user"
    is AssistantMessage -> "assistant"
    is ToolResultMessage -> "toolResult"
}

internal fun plainAssistant(
    text: String,
    usage: Usage = emptyUsage(),
    stopReason: StopReason = StopReason.Stop,
): AssistantMessage = AssistantMessage(
    content = mutableListOf(TextContent(text)),
    api = "openai-completions",
    provider = "openai",
    model = "mock",
    usage = usage,
    stopReason = stopReason,
    timestamp = 0,
)

internal fun branchSummaryEntry(
    id: String,
    parent: String?,
    fromId: String,
    summary: String,
    details: JsonObject? = null,
    fromHook: Boolean? = null,
    second: Int = 0,
): BranchSummaryEntry = BranchSummaryEntry(
    id = id,
    parentId = parent,
    timestamp = ts(second),
    fromId = fromId,
    summary = summary,
    details = details,
    fromHook = fromHook,
)
