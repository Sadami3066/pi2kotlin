package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.Message
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolReference
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import kotlin.math.ceil
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val CHARS_PER_TOKEN = 3.5
private const val ESTIMATED_IMAGE_CHARS = 4800

data class ContextUsageEstimate(
    val tokens: Long,
    val usageTokens: Long,
    val trailingTokens: Long,
    val lastUsageIndex: Int,
)

fun calculateContextTokens(usage: Usage): Long =
    if (usage.totalTokens != 0L) {
        usage.totalTokens
    } else {
        usage.input + usage.output + usage.cacheRead + usage.cacheWrite
    }

fun estimateTextTokens(text: String): Long = ceil(text.length / CHARS_PER_TOKEN).toLong()

fun estimateMessageTokens(message: Message): Long = when (message) {
    is SystemMessage -> estimateTextTokens(getSystemMessageText(message)) +
        estimateSerializedTokens(message.toolsAdded?.let { jsonArrayOfTools(it) }) +
        estimateSerializedTokens(message.toolsRemoved?.let { jsonArrayOfToolReferences(it) })

    is UserMessage -> estimateMessageContentTokens(message.content)

    is ToolResultMessage -> ceil(estimateContentChars(message.content) / CHARS_PER_TOKEN).toLong()

    is AssistantMessage -> {
        var chars = 0
        for (block in message.content) {
            chars += when (block) {
                is TextContent -> block.text.length
                is ThinkingContent -> block.thinking.length
                is ToolCall -> block.name.length + jsJsonStringify(block.arguments).length
                else -> 0
            }
        }
        ceil(chars / CHARS_PER_TOKEN).toLong()
    }
}

fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate {
    val usageInfo = getLastAssistantUsageInfo(messages)
    if (usageInfo != null) {
        val usageTokens = calculateContextTokens(usageInfo.usage)
        var trailingTokens = 0L
        for (index in usageInfo.index + 1 until messages.size) {
            trailingTokens += estimateMessageTokens(messages[index])
        }
        return ContextUsageEstimate(
            tokens = usageTokens + trailingTokens,
            usageTokens = usageTokens,
            trailingTokens = trailingTokens,
            lastUsageIndex = usageInfo.index,
        )
    }

    var tokens = 0L
    for (message in messages) tokens += estimateMessageTokens(message)
    return ContextUsageEstimate(
        tokens = tokens,
        usageTokens = 0L,
        trailingTokens = tokens,
        lastUsageIndex = NO_USAGE_INDEX,
    )
}

private const val NO_USAGE_INDEX = -1

private class UsageInfo(val usage: Usage, val index: Int)

private fun getLastAssistantUsageInfo(messages: List<Message>): UsageInfo? {
    var latestPrefixTimestamp = Long.MIN_VALUE
    var usageInfo: UsageInfo? = null
    for (index in messages.indices) {
        val message = messages[index]
        if (message is AssistantMessage) {
            val usageAppliesToPrefix = message.timestamp >= latestPrefixTimestamp
            if (
                usageAppliesToPrefix &&
                message.stopReason != StopReason.Aborted &&
                message.stopReason != StopReason.Error &&
                calculateContextTokens(message.usage) > 0
            ) {
                usageInfo = UsageInfo(message.usage, index)
            }
        }
        latestPrefixTimestamp = maxOf(latestPrefixTimestamp, messageTimestamp(message))
    }
    return usageInfo
}

private fun messageTimestamp(message: Message): Long = when (message) {
    is AssistantMessage -> message.timestamp
    is SystemMessage -> message.timestamp
    is UserMessage -> message.timestamp
    is ToolResultMessage -> message.timestamp
}

private fun estimateMessageContentTokens(content: dev.pi.ai.MessageContent): Long {
    val chars = when (content) {
        is PlainTextContent -> content.text.length
        is BlockContent -> content.blocks.sumOf { block ->
            if (block is TextContent) block.text.length else ESTIMATED_IMAGE_CHARS
        }
    }
    return ceil(chars / CHARS_PER_TOKEN).toLong()
}

private fun estimateContentChars(content: List<Content>): Int = content.sumOf { block ->
    if (block is TextContent) block.text.length else ESTIMATED_IMAGE_CHARS
}

private fun estimateSerializedTokens(element: JsonElement?): Long =
    if (element == null) 0L else estimateTextTokens(jsJsonStringify(element))

private fun jsonArrayOfTools(tools: List<Tool>): JsonArray = JsonArray(
    tools.map { tool ->
        val result = LinkedHashMap<String, JsonElement>()
        result["name"] = kotlinx.serialization.json.JsonPrimitive(tool.name)
        result["description"] = kotlinx.serialization.json.JsonPrimitive(tool.description)
        result["parameters"] = tool.parameters
        val constrainedSampling = tool.constrainedSampling
        if (constrainedSampling != null) result["constrainedSampling"] = constrainedSampling
        JsonObject(result)
    },
)

private fun jsonArrayOfToolReferences(tools: List<ToolReference>): JsonArray = JsonArray(
    tools.map { tool -> JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(tool.name))) },
)
