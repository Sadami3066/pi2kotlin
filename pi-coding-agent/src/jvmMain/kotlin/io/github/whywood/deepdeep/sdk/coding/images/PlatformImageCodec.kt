package io.github.whywood.deepdeep.sdk.coding.images

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

internal actual fun platformImageCodec(): ImageCodec = JvmImageCodec

private object JvmImageCodec : ImageCodec {
    override suspend fun inspect(bytes: ByteArray, mimeType: String): ImageSize? {
        if (baseMimeType(mimeType) == "image/webp") return webpSize(bytes)
        return withReader(bytes) { reader -> ImageSize(reader.getWidth(0), reader.getHeight(0)) }
    }

    override suspend fun render(bytes: ByteArray, mimeType: String, width: Int, height: Int): DecodedImage? {
        val source = readSubsampled(bytes, width, height) ?: return null
        return DecodedImage(width = width, height = height, handle = scaled(source, width, height))
    }

    override suspend fun encode(image: DecodedImage, format: ImageFormat, quality: Int?): ByteArray? {
        val source = image.handle as? BufferedImage ?: return null
        return when (format) {
            ImageFormat.Png -> runCatching {
                val output = ByteArrayOutputStream()
                ImageIO.write(source, "png", output)
                output.toByteArray()
            }.getOrNull()

            ImageFormat.Jpeg -> runCatching { writeJpeg(source, quality ?: 80) }.getOrNull()
        }
    }

    override suspend fun convertToPng(bytes: ByteArray): ByteArray? {
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        return encode(DecodedImage(image.width, image.height, image), ImageFormat.Png, null)
    }

    override fun release(image: DecodedImage) = Unit
}

private fun <T> withReader(bytes: ByteArray, block: (ImageReader) -> T): T? {
    val input = runCatching { ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
    try {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        try {
            reader.setInput(input, true, true)
            return block(reader)
        } finally {
            reader.dispose()
        }
    } catch (error: Throwable) {
        return null
    } finally {
        runCatching { input.close() }
    }
}

private fun readSubsampled(bytes: ByteArray, width: Int, height: Int): BufferedImage? = withReader(bytes) { reader ->
    val originalWidth = reader.getWidth(0)
    val originalHeight = reader.getHeight(0)
    var sampleSize = 1
    while (originalWidth / (sampleSize * 2) >= width && originalHeight / (sampleSize * 2) >= height) {
        sampleSize *= 2
    }
    if (sampleSize == 1) {
        reader.read(0)
    } else {
        val param = reader.defaultReadParam
        param.setSourceSubsampling(sampleSize, sampleSize, 0, 0)
        reader.read(0, param)
    }
}

private fun scaled(source: BufferedImage, width: Int, height: Int): BufferedImage {
    if (source.width == width && source.height == height) return source
    val type = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
    val target = BufferedImage(width, height, type)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun writeJpeg(source: BufferedImage, quality: Int): ByteArray? {
    val writers = ImageIO.getImageWritersByFormatName("jpeg")
    if (!writers.hasNext()) return null
    val writer = writers.next()
    try {
        val output = ByteArrayOutputStream()
        val imageOutput = MemoryCacheImageOutputStream(output)
        try {
            writer.output = imageOutput
            val params = writer.defaultWriteParam
            params.compressionMode = ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = quality.coerceIn(0, 100) / 100f
            writer.write(null, IIOImage(opaque(source), null, null), params)
        } finally {
            imageOutput.close()
        }
        return output.toByteArray()
    } finally {
        writer.dispose()
    }
}

private fun opaque(source: BufferedImage): BufferedImage {
    if (source.type == BufferedImage.TYPE_INT_RGB) return source
    val target = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    try {
        graphics.drawImage(source, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return target
}

private fun webpSize(bytes: ByteArray): ImageSize? {
    if (bytes.size < 20) return null
    if (!hasAscii(bytes, 0, "RIFF") || !hasAscii(bytes, 8, "WEBP")) return null
    val dataStart = 20
    return when {
        hasAscii(bytes, 12, "VP8 ") -> {
            if (bytes.size < dataStart + 10) return null
            if (bytes[dataStart + 3].toInt() and 0xFF != 0x9D) return null
            if (bytes[dataStart + 4].toInt() and 0xFF != 0x01) return null
            if (bytes[dataStart + 5].toInt() and 0xFF != 0x2A) return null
            ImageSize(
                readUInt16Le(bytes, dataStart + 6) and 0x3FFF,
                readUInt16Le(bytes, dataStart + 8) and 0x3FFF,
            )
        }

        hasAscii(bytes, 12, "VP8L") -> {
            if (bytes.size < dataStart + 5) return null
            if (bytes[dataStart].toInt() and 0xFF != 0x2F) return null
            val bits = readUInt32Le(bytes, dataStart + 1)
            ImageSize(
                (bits and 0x3FFFL).toInt() + 1,
                ((bits ushr 14) and 0x3FFFL).toInt() + 1,
            )
        }

        hasAscii(bytes, 12, "VP8X") -> {
            if (bytes.size < dataStart + 10) return null
            ImageSize(
                readUInt24Le(bytes, dataStart + 4) + 1,
                readUInt24Le(bytes, dataStart + 7) + 1,
            )
        }

        else -> null
    }
}

private fun hasAscii(bytes: ByteArray, offset: Int, value: String): Boolean {
    if (offset < 0 || bytes.size < offset + value.length) return false
    for (index in value.indices) {
        if (bytes[offset + index].toInt() != value[index].code) return false
    }
    return true
}

private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readUInt24Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16)

private fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFF) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)
