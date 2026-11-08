package io.github.whywood.deepdeep.sdk.coding.images

import kotlin.io.encoding.Base64

internal enum class ImageFormat(val mimeType: String) {
    Png("image/png"),
    Jpeg("image/jpeg"),
}

internal data class ImageSize(val width: Int, val height: Int)

internal class DecodedImage(
    val width: Int,
    val height: Int,
    internal val handle: Any?,
)

internal interface ImageCodec {
    suspend fun inspect(bytes: ByteArray, mimeType: String): ImageSize?

    suspend fun render(bytes: ByteArray, mimeType: String, width: Int, height: Int): DecodedImage?

    suspend fun encode(image: DecodedImage, format: ImageFormat, quality: Int?): ByteArray?

    suspend fun convertToPng(bytes: ByteArray): ByteArray?

    fun release(image: DecodedImage)
}

internal expect fun platformImageCodec(): ImageCodec

internal fun decodeBase64(data: String): ByteArray? = runCatching { Base64.Default.decode(data) }.getOrNull()

internal fun encodeBase64(bytes: ByteArray): String = Base64.Default.encode(bytes)

internal fun base64Length(bytes: ByteArray): Long = ((bytes.size.toLong() + 2L) / 3L) * 4L

internal fun baseMimeType(mimeType: String): String = mimeType.substringBefore(';').trim().lowercase()
