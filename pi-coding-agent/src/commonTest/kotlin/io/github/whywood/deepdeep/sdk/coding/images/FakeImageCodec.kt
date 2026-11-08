package io.github.whywood.deepdeep.sdk.coding.images

internal val FAKE_INPUT_BYTES: ByteArray = ByteArray(120) { (it + 1).toByte() }

internal val FAKE_PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a)

internal data class FakeEncodeCall(
    val format: ImageFormat,
    val quality: Int?,
    val width: Int,
    val height: Int,
)

internal class FakeImageCodec : ImageCodec {
    var inspectSize: ImageSize? = ImageSize(100, 100)
    var convertToPngResult: ByteArray? = FAKE_PNG_BYTES
    var renderFails: Boolean = false
    var encodeSize: (FakeEncodeCall) -> Int = { 90 }
    val inspectedMimeTypes: MutableList<String> = mutableListOf()
    val renderCalls: MutableList<ImageSize> = mutableListOf()
    val encodeCalls: MutableList<FakeEncodeCall> = mutableListOf()
    var convertedFromBytes: ByteArray? = null
    var releaseCount: Int = 0
        private set

    override suspend fun inspect(bytes: ByteArray, mimeType: String): ImageSize? {
        inspectedMimeTypes.add(mimeType)
        return inspectSize
    }

    override suspend fun render(bytes: ByteArray, mimeType: String, width: Int, height: Int): DecodedImage? {
        renderCalls.add(ImageSize(width, height))
        if (renderFails) return null
        return DecodedImage(width = width, height = height, handle = null)
    }

    override suspend fun encode(image: DecodedImage, format: ImageFormat, quality: Int?): ByteArray? {
        val call = FakeEncodeCall(format = format, quality = quality, width = image.width, height = image.height)
        encodeCalls.add(call)
        return ByteArray(encodeSize(call))
    }

    override suspend fun convertToPng(bytes: ByteArray): ByteArray? {
        convertedFromBytes = bytes
        return convertToPngResult
    }

    override fun release(image: DecodedImage) {
        releaseCount += 1
    }

    fun jpegQualities(): List<Int?> = encodeCalls.filter { it.format == ImageFormat.Jpeg }.map { it.quality }
}
