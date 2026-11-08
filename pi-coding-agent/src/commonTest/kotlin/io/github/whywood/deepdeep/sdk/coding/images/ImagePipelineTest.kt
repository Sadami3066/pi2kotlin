package io.github.whywood.deepdeep.sdk.coding.images

import io.github.whywood.deepdeep.sdk.ai.ModelImageResizeOptions
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImagePipelineTest {

    private fun imageOptions(
        maxWidth: Long? = null,
        maxHeight: Long? = null,
        maxBytes: Long? = null,
        jpegQuality: Long? = null,
    ): ModelImageResizeOptions = ModelImageResizeOptions(
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        maxBytes = maxBytes,
        jpegQuality = jpegQuality,
    )

    private fun sizedEncoder(png: Int = 600, jpeg: Int = 300): (FakeEncodeCall) -> Int =
        { call -> if (call.format == ImageFormat.Png) png else jpeg }

    @Test
    fun resizesOversizedImageWithinDefaultLimits() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(4000, 3000)
            encodeSize = sizedEncoder()
        }

        val result = processImage(FAKE_INPUT_BYTES, "image/png", codec = codec)

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/jpeg", success.mimeType)
        assertEquals(encodeBase64(ByteArray(300)), success.data)
        assertEquals(listOf(ImageSize(2000, 1500)), codec.renderCalls)
        assertEquals(1, codec.releaseCount)
        assertEquals(1, success.hints.size)
        assertEquals(
            "[Image: original 4000x3000, displayed at 2000x1500. " +
                "Multiply coordinates by 2.00 to map to original image.]",
            success.hints.single(),
        )
    }

    @Test
    fun preservesAspectRatioWhenCappingWidth() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(4000, 800)
            encodeSize = sizedEncoder()
        }

        processImage(FAKE_INPUT_BYTES, "image/png", codec = codec)

        assertEquals(listOf(ImageSize(2000, 400)), codec.renderCalls)
    }

    @Test
    fun usesModelResizeProfile() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(4000, 3000)
            encodeSize = sizedEncoder()
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxWidth = 500, maxHeight = 500, jpegQuality = 55),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/jpeg", success.mimeType)
        assertEquals(listOf(ImageSize(500, 375)), codec.renderCalls)
        assertEquals(listOf<Int?>(null, 55, 85, 70, 40), codec.encodeCalls.map { it.quality })
    }

    @Test
    fun honorsCustomJpegQualityAsFirstCandidate() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(2400, 2400)
            encodeSize = { call ->
                when {
                    call.format == ImageFormat.Png -> 10_000
                    call.quality == 55 -> 600
                    else -> 60_000
                }
            }
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxBytes = 10_000, jpegQuality = 55),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/jpeg", success.mimeType)
        assertEquals(encodeBase64(ByteArray(600)), success.data)
        assertEquals(listOf<Int?>(55, 85, 70, 40), codec.jpegQualities().take(4))
    }

    @Test
    fun keepsImageUnchangedWhenWithinLimits() = runTest {
        val codec = FakeImageCodec().apply { inspectSize = ImageSize(100, 50) }

        val result = processImage(FAKE_INPUT_BYTES, "image/png", codec = codec)

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals(encodeBase64(FAKE_INPUT_BYTES), success.data)
        assertEquals("image/png", success.mimeType)
        assertTrue(success.hints.isEmpty())
        assertTrue(codec.renderCalls.isEmpty())
    }

    @Test
    fun prefersSmallerOfPngAndJpeg() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(50, 50)
            encodeSize = sizedEncoder(png = 3000, jpeg = 600)
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxWidth = 10),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/jpeg", success.mimeType)
        assertEquals(encodeBase64(ByteArray(600)), success.data)
    }

    @Test
    fun prefersPngWhenPngIsSmaller() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(50, 50)
            encodeSize = sizedEncoder(png = 600, jpeg = 3000)
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxWidth = 10),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/png", success.mimeType)
    }

    @Test
    fun fallsBackToLowerJpegQuality() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(2400, 2400)
            encodeSize = { call ->
                when {
                    call.format == ImageFormat.Png -> 10_000
                    call.quality == 80 -> 10_000
                    call.quality == 85 -> 9_000
                    call.quality == 70 -> 6_000
                    else -> 300
                }
            }
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxBytes = 10_000),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/jpeg", success.mimeType)
        assertEquals(encodeBase64(ByteArray(6_000)), success.data)
        assertEquals(listOf<Int?>(80, 85, 70), codec.jpegQualities().take(3))
    }

    @Test
    fun shrinksDimensionsUntilEncodingFits() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(400, 300)
            encodeSize = { call -> if (call.width > 100) 10_000 else 30 }
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxBytes = 100),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals(
            listOf(
                ImageSize(400, 300),
                ImageSize(300, 225),
                ImageSize(225, 168),
                ImageSize(168, 126),
                ImageSize(126, 94),
                ImageSize(94, 70),
            ),
            codec.renderCalls,
        )
        assertTrue(success.hints.single().contains("displayed at 94x70"))
        assertTrue(success.hints.single().contains("Multiply coordinates by 4.26"))
    }

    @Test
    fun failsWhenNoEncodingFits() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(400, 300)
            encodeSize = { 10_000 }
        }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/png",
            resizeOptions = imageOptions(maxBytes = 100),
            codec = codec,
        )

        assertEquals(ProcessImageResult.Failure(IMAGE_RESIZE_FAILED_MESSAGE), result)
        assertEquals(ImageSize(1, 1), codec.renderCalls.last())
    }

    @Test
    fun failsWhenImageCannotBeInspected() = runTest {
        val codec = FakeImageCodec().apply { inspectSize = null }

        val result = processImage(FAKE_INPUT_BYTES, "image/png", codec = codec)

        assertEquals(ProcessImageResult.Failure(IMAGE_RESIZE_FAILED_MESSAGE), result)
    }

    @Test
    fun failsWhenRenderFails() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(4000, 3000)
            renderFails = true
        }

        val result = processImage(FAKE_INPUT_BYTES, "image/png", codec = codec)

        assertEquals(ProcessImageResult.Failure(IMAGE_RESIZE_FAILED_MESSAGE), result)
        assertEquals(0, codec.releaseCount)
    }

    @Test
    fun keepsOriginalWhenAutoResizeIsDisabled() = runTest {
        val codec = FakeImageCodec().apply { inspectSize = ImageSize(8000, 8000) }

        val result = processImage(
            FAKE_INPUT_BYTES,
            "image/jpg",
            autoResizeImages = false,
            resizeOptions = imageOptions(maxWidth = 100),
            codec = codec,
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals(encodeBase64(FAKE_INPUT_BYTES), success.data)
        assertEquals("image/jpeg", success.mimeType)
        assertTrue(success.hints.isEmpty())
        assertTrue(codec.inspectedMimeTypes.isEmpty())
        assertTrue(codec.renderCalls.isEmpty())
    }

    @Test
    fun convertsUnsupportedFormatWhenAutoResizeIsDisabled() = runTest {
        val codec = FakeImageCodec()

        val result = processImage(FAKE_INPUT_BYTES, "image/bmp", autoResizeImages = false, codec = codec)

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/png", success.mimeType)
        assertEquals(encodeBase64(FAKE_PNG_BYTES), success.data)
        assertEquals(listOf("[Image converted from image/bmp to image/png.]"), success.hints)
        assertEquals(FAKE_INPUT_BYTES, codec.convertedFromBytes)
    }

    @Test
    fun convertsUnsupportedFormatThenResizes() = runTest {
        val codec = FakeImageCodec().apply {
            inspectSize = ImageSize(4000, 3000)
            encodeSize = { call -> if (call.format == ImageFormat.Png) 300 else 600 }
        }

        val result = processImage(FAKE_INPUT_BYTES, "image/bmp", codec = codec)

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/png", success.mimeType)
        assertEquals(2, success.hints.size)
        assertEquals("[Image converted from image/bmp to image/png.]", success.hints[0])
        assertTrue(success.hints[1].contains("displayed at 2000x1500"))
        assertEquals(listOf("image/png"), codec.inspectedMimeTypes)
    }

    @Test
    fun failsWhenFormatConversionFails() = runTest {
        val codec = FakeImageCodec().apply { convertToPngResult = null }

        val result = processImage(FAKE_INPUT_BYTES, "image/avif", codec = codec)

        assertEquals(ProcessImageResult.Failure(IMAGE_CONVERSION_FAILED_MESSAGE), result)
    }

    @Test
    fun formatsCoordinateScale() {
        assertEquals("2.00", formatScale(2.0))
        assertEquals("1.20", formatScale(1.2))
        assertEquals("1.41", formatScale(1080.0 / 768.0))
    }
}
