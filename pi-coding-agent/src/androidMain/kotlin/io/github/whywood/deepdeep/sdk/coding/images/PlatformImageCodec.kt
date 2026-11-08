package io.github.whywood.deepdeep.sdk.coding.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

internal actual fun platformImageCodec(): ImageCodec = AndroidImageCodec

private object AndroidImageCodec : ImageCodec {
    override suspend fun inspect(bytes: ByteArray, mimeType: String): ImageSize? {
        val bounds = decodeBounds(bytes) ?: return null
        return if (readOrientation(bytes) in 5..8) ImageSize(bounds.height, bounds.width) else bounds
    }

    override suspend fun render(bytes: ByteArray, mimeType: String, width: Int, height: Int): DecodedImage? {
        val bounds = decodeBounds(bytes) ?: return null
        val orientation = readOrientation(bytes)
        val swapped = orientation in 5..8
        val rawWidth = if (swapped) height else width
        val rawHeight = if (swapped) width else height
        var sampleSize = 1
        while (bounds.width / (sampleSize * 2) >= rawWidth && bounds.height / (sampleSize * 2) >= rawHeight) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options()
        options.inSampleSize = sampleSize
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
            ?: return null
        val oriented = runCatching { applyOrientation(decoded, orientation) }.getOrDefault(decoded)
        if (oriented !== decoded) decoded.recycle()
        val result = if (oriented.width == width && oriented.height == height) {
            oriented
        } else {
            val scaled = runCatching { Bitmap.createScaledBitmap(oriented, width, height, true) }.getOrNull()
            if (scaled == null) {
                oriented.recycle()
                return null
            }
            if (scaled !== oriented) oriented.recycle()
            scaled
        }
        return DecodedImage(width = width, height = height, handle = result)
    }

    override suspend fun encode(image: DecodedImage, format: ImageFormat, quality: Int?): ByteArray? {
        val bitmap = image.handle as? Bitmap ?: return null
        val compressFormat = when (format) {
            ImageFormat.Png -> Bitmap.CompressFormat.PNG
            ImageFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        }
        val output = ByteArrayOutputStream()
        val encoded = runCatching { bitmap.compress(compressFormat, quality ?: 100, output) }.getOrDefault(false)
        return if (encoded) output.toByteArray() else null
    }

    override suspend fun convertToPng(bytes: ByteArray): ByteArray? {
        val decoded = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: return null
        val oriented = runCatching { applyOrientation(decoded, readOrientation(bytes)) }.getOrDefault(decoded)
        if (oriented !== decoded) decoded.recycle()
        return try {
            val output = ByteArrayOutputStream()
            if (oriented.compress(Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
        } finally {
            oriented.recycle()
        }
    }

    override fun release(image: DecodedImage) {
        (image.handle as? Bitmap)?.recycle()
    }
}

private fun decodeBounds(bytes: ByteArray): ImageSize? {
    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    val width = options.outWidth
    val height = options.outHeight
    return if (width > 0 && height > 0) ImageSize(width, height) else null
}

private fun readOrientation(bytes: ByteArray): Int = runCatching {
    ExifInterface(ByteArrayInputStream(bytes))
        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
}.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { postRotate(180f) }
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { postScale(1f, -1f) }
        ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
            postRotate(90f)
            postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { postRotate(90f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
            postRotate(270f)
            postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { postRotate(270f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
