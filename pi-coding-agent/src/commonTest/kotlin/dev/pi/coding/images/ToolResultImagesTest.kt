package dev.pi.coding.images

import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolResultImagesTest {

    private fun image(data: ByteArray = FAKE_INPUT_BYTES, mimeType: String = "image/png"): ImageContent =
        ImageContent(data = encodeBase64(data), mimeType = mimeType)

    @Test
    fun returnsSameListWhenThereAreNoImages() = runTest {
        val content = listOf<Content>(TextContent("no images here"))

        val normalized = normalizeToolResultImages(content, true, null, FakeImageCodec())

        assertSame(content, normalized)
    }

    @Test
    fun returnsSameListWhenImagesAreWithinLimits() = runTest {
        val content = listOf<Content>(TextContent("screenshot"), image())
        val codec = FakeImageCodec().apply { inspectSize = ImageSize(100, 50) }

        val normalized = normalizeToolResultImages(content, true, null, codec)

        assertSame(content, normalized)
    }

    @Test
    fun replacesOversizedImageAndAppendsHints() = runTest {
        val original = image()
        val content = listOf<Content>(TextContent("screenshot"), original)
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(2400, 4800)
            encodeSize = { call -> if (call.format == ImageFormat.Png) 600 else 3000 }
        }

        val normalized = normalizeToolResultImages(content, true, null, codec)

        assertEquals(3, normalized.size)
        assertSame(content[0], normalized[0])
        val image = assertIs<ImageContent>(normalized[1])
        assertEquals("image/png", image.mimeType)
        assertNotEquals(original.data, image.data)
        val hint = assertIs<TextContent>(normalized[2])
        assertEquals(
            "[Image: original 2400x4800, displayed at 1000x2000. " +
                "Multiply coordinates by 2.40 to map to original image.]",
            hint.text,
        )
    }

    @Test
    fun keepsOriginalImageWhenProcessingFails() = runTest {
        val content = listOf<Content>(TextContent("screenshot"), image())
        val codec = FakeImageCodec().apply { inspectSize = null }

        val normalized = normalizeToolResultImages(content, true, null, codec)

        assertSame(content, normalized)
    }

    @Test
    fun leavesOversizedImagesAloneWhenAutoResizeIsDisabled() = runTest {
        val content = listOf<Content>(image())
        val codec = FakeImageCodec().apply { inspectSize = ImageSize(2400, 4800) }

        val normalized = normalizeToolResultImages(content, false, null, codec)

        assertSame(content, normalized)
    }

    @Test
    fun convertsUnsupportedFormatsWithHints() = runTest {
        val content = listOf<Content>(image(mimeType = "image/bmp"))
        val codec = FakeImageCodec()

        val normalized = normalizeToolResultImages(content, false, null, codec)

        assertEquals(2, normalized.size)
        val converted = assertIs<ImageContent>(normalized[0])
        assertEquals("image/png", converted.mimeType)
        val hint = assertIs<TextContent>(normalized[1])
        assertEquals("[Image converted from image/bmp to image/png.]", hint.text)
    }

    @Test
    fun preservesSurroundingTextBlocksAndOrder() = runTest {
        val content = listOf<Content>(TextContent("before"), image(), TextContent("after"))
        val codec = FakeImageCodec().apply { inspectSize = ImageSize(2400, 4800) }

        val normalized = normalizeToolResultImages(content, true, null, codec)

        val types = normalized.map { block ->
            when (block) {
                is TextContent -> "text"
                is ImageContent -> "image"
                else -> "other"
            }
        }
        assertEquals(listOf("text", "image", "text", "text"), types)
        assertEquals("before", assertIs<TextContent>(normalized[0]).text)
        assertEquals("after", assertIs<TextContent>(normalized[3]).text)
        assertTrue(assertIs<TextContent>(normalized[2]).text.startsWith("[Image: original 2400x4800"))
    }
}
