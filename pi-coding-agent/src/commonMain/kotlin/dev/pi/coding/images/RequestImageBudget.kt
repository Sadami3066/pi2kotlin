package dev.pi.coding.images

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.MessageContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.UserMessage

internal const val REQUEST_IMAGE_OMITTED_MESSAGE =
    "[Image omitted: this older inline image was dropped from the request to stay within the conversation " +
        "image budget. Re-read the file or take a new screenshot if you need to see it again.]"

internal fun limitRequestImages(messages: List<Message>, maxImages: Int, maxBytes: Long): List<Message> {
    var keptImages = 0
    var keptBytes = 0L
    var limited: MutableList<Message>? = null
    for (index in messages.indices.reversed()) {
        val images = messages[index].requestImages()
        if (images.isEmpty()) continue
        val keep = BooleanArray(images.size)
        for (position in images.indices.reversed()) {
            val size = images[position].data.length.toLong()
            val keepImage = keptImages == 0 || (keptImages < maxImages && keptBytes + size <= maxBytes)
            keep[position] = keepImage
            if (keepImage) {
                keptImages += 1
                keptBytes += size
            }
        }
        if (keep.all { it }) continue
        val target = limited ?: messages.toMutableList().also { limited = it }
        target[index] = messages[index].withImagesLimited(keep)
    }
    return limited ?: messages
}

private fun Message.requestImages(): List<ImageContent> = when (this) {
    is UserMessage -> content.requestImages()
    is SystemMessage -> content.requestImages()
    is ToolResultMessage -> content.filterIsInstance<ImageContent>()
    is AssistantMessage -> content.filterIsInstance<ImageContent>()
}

private fun MessageContent.requestImages(): List<ImageContent> =
    (this as? BlockContent)?.blocks?.filterIsInstance<ImageContent>().orEmpty()

private fun Message.withImagesLimited(keep: BooleanArray): Message = when (this) {
    is UserMessage -> copy(content = content.withImagesLimited(keep))
    is SystemMessage -> copy(content = content.withImagesLimited(keep))
    is ToolResultMessage -> copy(content = content.withImagesLimited(keep))
    is AssistantMessage -> copy(content = content.withImagesLimited(keep).toMutableList())
}

private fun MessageContent.withImagesLimited(keep: BooleanArray): MessageContent {
    val blocks = (this as? BlockContent)?.blocks ?: return this
    return BlockContent(blocks.withImagesLimited(keep))
}

private fun List<Content>.withImagesLimited(keep: BooleanArray): List<Content> {
    var imageIndex = 0
    return map { block ->
        if (block !is ImageContent) {
            block
        } else if (keep[imageIndex++]) {
            block
        } else {
            TextContent(REQUEST_IMAGE_OMITTED_MESSAGE)
        }
    }
}
