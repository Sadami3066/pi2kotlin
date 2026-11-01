package dev.pi.coding.images

import dev.pi.ai.ModelImageResizeOptions
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.imageio.ImageIO
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImagePipelineJvmTest {

    private fun codedImage(
        width: Int,
        height: Int,
        format: String = "png",
        noisy: Boolean = false,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        if (noisy) {
            val random = Random(42)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    image.setRGB(x, y, random.nextInt(0xFFFFFF))
                }
            }
        } else {
            val graphics = image.createGraphics()
            try {
                graphics.color = Color(30, 90, 160)
                graphics.fillRect(0, 0, width, height)
                graphics.color = Color(240, 240, 240)
                graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
            } finally {
                graphics.dispose()
            }
        }
        val output = ByteArrayOutputStream()
        assertTrue(ImageIO.write(image, format, output))
        return output.toByteArray()
    }

    private fun decoded(base64Data: String): BufferedImage =
        ImageIO.read(ByteArrayInputStream(requireNotNull(decodeBase64(base64Data))))

    private fun webpLossless(width: Int, height: Int): ByteArray {
        val bits = (width - 1) or ((height - 1) shl 14)
        val bytes = ByteArray(25)
        "RIFF".encodeToByteArray().copyInto(bytes, 0)
        bytes[4] = 17
        "WEBP".encodeToByteArray().copyInto(bytes, 8)
        "VP8L".encodeToByteArray().copyInto(bytes, 12)
        bytes[16] = 5
        bytes[20] = 0x2F
        bytes[21] = (bits and 0xFF).toByte()
        bytes[22] = ((bits ushr 8) and 0xFF).toByte()
        bytes[23] = ((bits ushr 16) and 0xFF).toByte()
        bytes[24] = ((bits ushr 24) and 0xFF).toByte()
        return bytes
    }

    @Test
    fun resizesOversizedPng() = runTest {
        val result = processImage(codedImage(2400, 1200), "image/png")

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/png", success.mimeType)
        val image = decoded(success.data)
        assertEquals(2000, image.width)
        assertEquals(1000, image.height)
        assertEquals(
            "[Image: original 2400x1200, displayed at 2000x1000. " +
                "Multiply coordinates by 1.20 to map to original image.]",
            success.hints.single(),
        )
    }

    @Test
    fun resizesOversizedJpeg() = runTest {
        val result = processImage(codedImage(3000, 4000, format = "jpeg"), "image/jpeg")

        val success = assertIs<ProcessImageResult.Success>(result)
        val image = decoded(success.data)
        assertEquals(1500, image.width)
        assertEquals(2000, image.height)
        assertTrue(success.hints.single().startsWith("[Image: original 3000x4000"))
    }

    @Test
    fun convertsBmpToPng() = runTest {
        val result = processImage(codedImage(300, 200, format = "bmp"), "image/bmp")

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals("image/png", success.mimeType)
        assertEquals(listOf("[Image converted from image/bmp to image/png.]"), success.hints)
        val image = decoded(success.data)
        assertEquals(300, image.width)
        assertEquals(200, image.height)
    }

    @Test
    fun keepsSmallImageByteForByte() = runTest {
        val bytes = codedImage(10, 10)

        val result = processImage(bytes, "image/png")

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals(encodeBase64(bytes), success.data)
        assertEquals("image/png", success.mimeType)
        assertTrue(success.hints.isEmpty())
    }

    @Test
    fun staysWithinBase64Budget() = runTest {
        val result = processImage(
            codedImage(400, 400, noisy = true),
            "image/png",
            resizeOptions = ModelImageResizeOptions(maxBytes = 30_000),
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        assertTrue(success.data.length < 30_000)
        val image = decoded(success.data)
        assertTrue(image.width <= 400)
        assertTrue(image.height <= 400)
        assertTrue(success.hints.single().startsWith("[Image: original 400x400, displayed at "))
    }

    @Test
    fun respectsModelResizeLimits() = runTest {
        val result = processImage(
            codedImage(400, 200),
            "image/png",
            resizeOptions = ModelImageResizeOptions(maxWidth = 100, maxHeight = 100),
        )

        val success = assertIs<ProcessImageResult.Success>(result)
        val image = decoded(success.data)
        assertEquals(100, image.width)
        assertEquals(50, image.height)
    }

    @Test
    fun keepsBytesWhenAutoResizeIsDisabled() = runTest {
        val bytes = codedImage(2400, 1200)

        val result = processImage(bytes, "image/png", autoResizeImages = false)

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals(encodeBase64(bytes), success.data)
        assertTrue(success.hints.isEmpty())
    }

    @Test
    fun passesThroughSmallWebpWithoutDecoding() = runTest {
        val bytes = webpLossless(10, 10)

        val result = processImage(bytes, "image/webp")

        val success = assertIs<ProcessImageResult.Success>(result)
        assertEquals(encodeBase64(bytes), success.data)
        assertTrue(success.hints.isEmpty())
    }

    @Test
    fun failsToResizeWebpBecauseJvmHasNoDecoder() = runTest {
        val result = processImage(webpLossless(3000, 3000), "image/webp")

        assertEquals(ProcessImageResult.Failure(IMAGE_RESIZE_FAILED_MESSAGE), result)
    }
}
