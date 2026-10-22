package dev.pi.ai.utils

import dev.pi.ai.BlockContent
import dev.pi.ai.MessageContent
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent

fun contentText(content: MessageContent, separator: String = "\n"): String = when (content) {
    is PlainTextContent -> content.text
    is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString(separator) { it.text }
}

fun getSystemMessageText(message: SystemMessage): String {
    val parts = mutableListOf(contentText(message.content))
    message.sections?.values?.filterNotNull()?.forEach { parts.add(it) }
    return parts.filter { it.isNotEmpty() }.joinToString("\n\n")
}

fun renderSystemMessageUpdate(message: SystemMessage): String {
    val parts = mutableListOf<String>()
    val text = contentText(message.content)
    if (text.isNotEmpty()) parts.add(text)
    message.sections?.forEach { (name, value) ->
        parts.add(
            if (value == null) {
                "Removed system prompt section \"$name\"."
            } else {
                "Updated system prompt section \"$name\":\n\n$value"
            },
        )
    }
    return parts.joinToString("\n\n")
}
