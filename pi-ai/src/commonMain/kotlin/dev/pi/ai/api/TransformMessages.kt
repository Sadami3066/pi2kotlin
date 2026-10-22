package dev.pi.ai.api

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelInputModality
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
import dev.pi.ai.utils.collapseSystemMessages
import dev.pi.ai.utils.getCurrentTools
import dev.pi.ai.utils.getInitialSystemMessage

internal const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
internal const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"

internal fun sanitizeSurrogates(text: String): String {
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                    builder.append(char)
                    builder.append(text[index + 1])
                    index += 2
                } else {
                    index += 1
                }
            }

            char.isLowSurrogate() -> index += 1
            else -> {
                builder.append(char)
                index += 1
            }
        }
    }
    return builder.toString()
}

private fun toBase36(value: Long): String {
    if (value == 0L) return "0"
    var remaining = value
    val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
    val builder = StringBuilder()
    while (remaining > 0) {
        builder.append(digits[(remaining % 36).toInt()])
        remaining /= 36
    }
    return builder.reverse().toString()
}

internal fun shortHash(input: String): String {
    var h1 = 0xdeadbeefL
    var h2 = 0x41c6ce57L
    val mask = 0xffffffffL
    for (char in input) {
        val code = char.code.toLong()
        h1 = ((h1 xor code) * 2654435761L) and mask
        h2 = ((h2 xor code) * 1597334677L) and mask
    }
    h1 = (((h1 xor (h1 ushr 16)) * 2246822507L) and mask) xor (((h2 xor (h2 ushr 13)) * 3266489909L) and mask)
    h2 = (((h2 xor (h2 ushr 16)) * 2246822507L) and mask) xor (((h1 xor (h1 ushr 13)) * 3266489909L) and mask)
    return toBase36(h2 and mask) + toBase36(h1 and mask)
}

internal fun normalizeOpenAIToolCallId(model: Model, id: String): String {
    if (id.contains("|")) {
        val separatorIndex = id.indexOf('|')
        val callId = id.substring(0, separatorIndex).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val itemId = id.substring(separatorIndex + 1).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val combinedId = if (itemId.isNotEmpty()) "${callId}_$itemId" else callId
        if (combinedId.length <= 40) return combinedId
        val hash = shortHash(id).take(8)
        val prefix = callId.take(maxOf(1, 40 - hash.length - 1))
        return "${prefix}_$hash"
    }
    if (model.provider == "openai") return id.take(40)
    return id
}

private fun replaceImagesWithPlaceholder(content: List<Content>, placeholder: String): List<Content> {
    val result = mutableListOf<Content>()
    var previousWasPlaceholder = false
    for (block in content) {
        if (block is ImageContent) {
            if (!previousWasPlaceholder) result.add(TextContent(placeholder))
            previousWasPlaceholder = true
            continue
        }
        result.add(block)
        previousWasPlaceholder = block is TextContent && block.text == placeholder
    }
    return result
}

private fun downgradeUnsupportedImages(messages: List<Message>, model: Model): List<Message> {
    if (model.input.contains(ModelInputModality.Image)) return messages
    return messages.map { msg ->
        when (msg) {
            is UserMessage -> {
                val content = msg.content
                if (content is BlockContent) {
                    UserMessage(BlockContent(replaceImagesWithPlaceholder(content.blocks, NON_VISION_USER_IMAGE_PLACEHOLDER)), msg.timestamp)
                } else {
                    msg
                }
            }

            is ToolResultMessage -> ToolResultMessage(
                toolCallId = msg.toolCallId,
                toolName = msg.toolName,
                content = replaceImagesWithPlaceholder(msg.content, NON_VISION_TOOL_IMAGE_PLACEHOLDER),
                details = msg.details,
                usage = msg.usage,
                nestedCalls = msg.nestedCalls,
                isError = msg.isError,
                timestamp = msg.timestamp,
                durationMs = msg.durationMs,
            )

            else -> msg
        }
    }
}

internal fun transformMessages(
    messages: List<Message>,
    model: Model,
    normalizeToolCallId: ((String) -> String)? = null,
): List<Message> {
    val toolCallIdMap = mutableMapOf<String, String>()
    val imageAwareMessages = downgradeUnsupportedImages(messages, model)

    val transformed = imageAwareMessages.map { msg ->
        when (msg) {
            is UserMessage, is SystemMessage -> msg
            is ToolResultMessage -> {
                val normalizedId = toolCallIdMap[msg.toolCallId]
                if (normalizedId != null && normalizedId != msg.toolCallId) {
                    msg.copy(toolCallId = normalizedId)
                } else {
                    msg
                }
            }

            is AssistantMessage -> {
                val isSameModel = msg.provider == model.provider && msg.api == model.api && msg.model == model.id
                val transformedContent = msg.content.flatMap { block ->
                    when (block) {
                        is ThinkingContent -> {
                            if (!isSameModel) {
                                emptyList()
                            } else if (block.redacted == true || !block.thinkingSignature.isNullOrEmpty()) {
                                listOf(block)
                            } else if (block.thinking.isBlank()) {
                                emptyList()
                            } else {
                                listOf(block)
                            }
                        }

                        is TextContent -> if (isSameModel) {
                            listOf(block)
                        } else {
                            listOf(TextContent(block.text))
                        }

                        is ToolCall -> {
                            var normalized: ToolCall = block
                            if (!isSameModel && !block.thoughtSignature.isNullOrEmpty()) {
                                normalized = normalized.copy(thoughtSignature = null)
                            }
                            if (!isSameModel && normalizeToolCallId != null) {
                                val normalizedId = normalizeToolCallId(block.id)
                                if (normalizedId != block.id) {
                                    toolCallIdMap[block.id] = normalizedId
                                    normalized = normalized.copy(id = normalizedId)
                                }
                            }
                            listOf(normalized)
                        }

                        else -> listOf(block)
                    }
                }
                msg.copy(content = transformedContent.toMutableList())
            }
        }
    }

    val result = mutableListOf<Message>()
    var pendingToolCalls: List<ToolCall> = emptyList()
    var existingToolResultIds: MutableSet<String> = mutableSetOf()
    val heldSystemMessages = mutableListOf<Message>()

    fun closePendingToolCalls() {
        if (pendingToolCalls.isNotEmpty()) {
            for (toolCall in pendingToolCalls) {
                if (!existingToolResultIds.contains(toolCall.id)) {
                    result.add(
                        ToolResultMessage(
                            toolCallId = toolCall.id,
                            toolName = toolCall.name,
                            content = listOf(TextContent("No result provided")),
                            isError = true,
                            timestamp = nowMillis(),
                        ),
                    )
                }
            }
            pendingToolCalls = emptyList()
            existingToolResultIds = mutableSetOf()
        }
        result.addAll(heldSystemMessages)
        heldSystemMessages.clear()
    }

    for (msg in transformed) {
        when (msg) {
            is AssistantMessage -> {
                closePendingToolCalls()
                if (msg.stopReason == dev.pi.ai.StopReason.Error || msg.stopReason == dev.pi.ai.StopReason.Aborted) continue
                val toolCalls = msg.content.filterIsInstance<ToolCall>()
                if (toolCalls.isNotEmpty()) {
                    pendingToolCalls = toolCalls
                    existingToolResultIds = mutableSetOf()
                }
                result.add(msg)
            }

            is ToolResultMessage -> {
                existingToolResultIds.add(msg.toolCallId)
                result.add(msg)
            }

            is SystemMessage -> {
                if (pendingToolCalls.isNotEmpty()) {
                    heldSystemMessages.add(msg)
                } else {
                    result.add(msg)
                }
            }

            is UserMessage -> {
                closePendingToolCalls()
                result.add(msg)
            }
        }
    }

    closePendingToolCalls()
    return result
}

internal fun resolveTranscript(context: TranscriptContext, supportsMidConvoSystemMessages: Boolean): TranscriptContext =
    if (supportsMidConvoSystemMessages) context else collapseSystemMessages(context)

internal enum class TranscriptToolSupport { None, Additions, Changes }

internal data class TranscriptTools(
    val requestTools: List<Tool>,
    val foldedSystemMessages: Int,
)

private fun lastNonAdditiveToolChangeIndex(systemMessages: List<SystemMessage>): Int {
    val declared = mutableSetOf<String>()
    var last = -1
    for (index in systemMessages.indices) {
        val message = systemMessages[index]
        if (!message.toolsRemoved.isNullOrEmpty()) last = index
        for (tool in message.toolsAdded.orEmpty()) {
            if (declared.contains(tool.name)) last = index
            declared.add(tool.name)
        }
    }
    return last
}

internal fun resolveTranscriptTools(messages: List<Message>, support: TranscriptToolSupport): TranscriptTools {
    val systemMessages = messages.filterIsInstance<SystemMessage>()
    if (support == TranscriptToolSupport.None) {
        return TranscriptTools(getCurrentTools(systemMessages), systemMessages.size)
    }
    if (support == TranscriptToolSupport.Changes) return TranscriptTools(emptyList(), 0)
    val folded = maxOf(
        if (getInitialSystemMessage(messages) != null) 1 else 0,
        lastNonAdditiveToolChangeIndex(systemMessages) + 1,
    )
    return TranscriptTools(getCurrentTools(systemMessages.take(folded)), folded)
}

internal fun getDeclaredTools(messages: List<Message>): List<Tool> {
    val definitions = LinkedHashMap<String, Tool>()
    for (message in messages) {
        if (message !is SystemMessage) continue
        for (tool in message.toolsAdded.orEmpty()) definitions[tool.name] = tool
    }
    return definitions.values.toList()
}

internal fun nowMillis(): Long = platformCurrentTimeMillis()
