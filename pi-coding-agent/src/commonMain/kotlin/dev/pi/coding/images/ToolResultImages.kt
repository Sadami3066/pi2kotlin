package dev.pi.coding.images

import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.ModelImageResizeOptions
import dev.pi.ai.TextContent

internal suspend fun normalizeToolResultImages(
    content: List<Content>,
    autoResizeImages: Boolean,
    resizeOptions: ModelImageResizeOptions?,
    codec: ImageCodec = platformImageCodec(),
): List<Content> {
    if (content.none { it is ImageContent }) return content

    val normalized = mutableListOf<Content>()
    var changed = false
    for (block in content) {
        if (block !is ImageContent) {
            normalized.add(block)
            continue
        }
        val bytes = decodeBase64(block.data)
        val processed = if (bytes == null) {
            null
        } else {
            processImage(bytes, block.mimeType, autoResizeImages, resizeOptions, codec)
        }
        if (processed !is ProcessImageResult.Success) {
            normalized.add(block)
            continue
        }
        if (processed.data == block.data && processed.mimeType == block.mimeType && processed.hints.isEmpty()) {
            normalized.add(block)
            continue
        }
        normalized.add(ImageContent(data = processed.data, mimeType = processed.mimeType))
        if (processed.hints.isNotEmpty()) {
            normalized.add(TextContent(processed.hints.joinToString("\n")))
        }
        changed = true
    }
    return if (changed) normalized else content
}
