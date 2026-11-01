package dev.pi.coding.images

import dev.pi.ai.ModelImageResizeOptions
import kotlin.math.roundToLong

internal const val IMAGE_CONVERSION_FAILED_MESSAGE =
    "[Image omitted: could not be converted to a supported inline image format.]"

internal const val IMAGE_RESIZE_FAILED_MESSAGE =
    "[Image omitted: could not be resized below the inline image size limit.]"

private const val DEFAULT_MAX_WIDTH = 2000
private const val DEFAULT_MAX_HEIGHT = 2000
private const val DEFAULT_MAX_BYTES = 4_718_592L
private const val DEFAULT_JPEG_QUALITY = 80
private val LOWER_JPEG_QUALITIES = listOf(85, 70, 55, 40)

internal data class ResolvedResizeOptions(
    val maxWidth: Int,
    val maxHeight: Int,
    val maxBytes: Long,
    val jpegQuality: Int,
)

internal data class ResizedImage(
    val data: String,
    val mimeType: String,
    val originalWidth: Int,
    val originalHeight: Int,
    val width: Int,
    val height: Int,
    val wasResized: Boolean,
)

internal sealed interface ProcessImageResult {
    data class Success(
        val data: String,
        val mimeType: String,
        val hints: List<String>,
    ) : ProcessImageResult

    data class Failure(val message: String) : ProcessImageResult
}

internal fun resolveResizeOptions(options: ModelImageResizeOptions?): ResolvedResizeOptions = ResolvedResizeOptions(
    maxWidth = (options?.maxWidth ?: DEFAULT_MAX_WIDTH.toLong()).coerceAtLeast(1L).toInt(),
    maxHeight = (options?.maxHeight ?: DEFAULT_MAX_HEIGHT.toLong()).coerceAtLeast(1L).toInt(),
    maxBytes = (options?.maxBytes ?: DEFAULT_MAX_BYTES).coerceAtLeast(1L),
    jpegQuality = (options?.jpegQuality ?: DEFAULT_JPEG_QUALITY.toLong()).coerceIn(0L, 100L).toInt(),
)

internal fun normalizeSupportedImageMimeType(mimeType: String): String? = when (baseMimeType(mimeType)) {
    "image/png" -> "image/png"
    "image/jpeg", "image/jpg" -> "image/jpeg"
    "image/gif" -> "image/gif"
    "image/webp" -> "image/webp"
    else -> null
}

internal suspend fun processImage(
    bytes: ByteArray,
    mimeType: String,
    autoResizeImages: Boolean = true,
    resizeOptions: ModelImageResizeOptions? = null,
    codec: ImageCodec = platformImageCodec(),
): ProcessImageResult {
    val supportedMimeType = normalizeSupportedImageMimeType(mimeType)
    val normalizedBytes: ByteArray
    val normalizedMimeType: String
    val convertedFrom: String?
    if (supportedMimeType != null) {
        normalizedBytes = bytes
        normalizedMimeType = supportedMimeType
        convertedFrom = null
    } else {
        val pngBytes = codec.convertToPng(bytes) ?: return ProcessImageResult.Failure(IMAGE_CONVERSION_FAILED_MESSAGE)
        normalizedBytes = pngBytes
        normalizedMimeType = ImageFormat.Png.mimeType
        convertedFrom = baseMimeType(mimeType)
    }

    if (!autoResizeImages) {
        return ProcessImageResult.Success(
            data = encodeBase64(normalizedBytes),
            mimeType = normalizedMimeType,
            hints = conversionHints(convertedFrom, normalizedMimeType),
        )
    }

    val resized = resizeImage(normalizedBytes, normalizedMimeType, resolveResizeOptions(resizeOptions), codec)
        ?: return ProcessImageResult.Failure(IMAGE_RESIZE_FAILED_MESSAGE)
    return ProcessImageResult.Success(
        data = resized.data,
        mimeType = resized.mimeType,
        hints = conversionHints(convertedFrom, resized.mimeType) + listOfNotNull(formatDimensionNote(resized)),
    )
}

internal suspend fun resizeImage(
    bytes: ByteArray,
    mimeType: String,
    options: ResolvedResizeOptions,
    codec: ImageCodec = platformImageCodec(),
): ResizedImage? {
    val original = codec.inspect(bytes, mimeType) ?: return null
    val originalWidth = original.width
    val originalHeight = original.height
    if (originalWidth <= options.maxWidth &&
        originalHeight <= options.maxHeight &&
        base64Length(bytes) < options.maxBytes
    ) {
        return ResizedImage(
            data = encodeBase64(bytes),
            mimeType = mimeType,
            originalWidth = originalWidth,
            originalHeight = originalHeight,
            width = originalWidth,
            height = originalHeight,
            wasResized = false,
        )
    }

    var targetWidth = originalWidth
    var targetHeight = originalHeight
    if (targetWidth > options.maxWidth) {
        targetHeight = aspectRound(targetHeight, options.maxWidth, targetWidth)
        targetWidth = options.maxWidth
    }
    if (targetHeight > options.maxHeight) {
        targetWidth = aspectRound(targetWidth, options.maxHeight, targetHeight)
        targetHeight = options.maxHeight
    }

    val qualitySteps = (listOf(options.jpegQuality) + LOWER_JPEG_QUALITIES).distinct()
    var currentWidth = targetWidth
    var currentHeight = targetHeight
    while (true) {
        val candidates = encodeCandidates(codec, bytes, mimeType, currentWidth, currentHeight, qualitySteps)
        val png = candidates.firstOrNull { it.format == ImageFormat.Png }
        val jpeg = candidates.firstOrNull { it.format == ImageFormat.Jpeg && it.quality == options.jpegQuality }
        val preferred: List<EncodedCandidate>
        val rest: List<EncodedCandidate>
        if (jpeg != null && png != null && jpeg.encodedSize < png.encodedSize) {
            preferred = listOf(jpeg, png)
            rest = candidates.filter { it !== jpeg && it !== png }
        } else {
            preferred = listOfNotNull(png, jpeg)
            rest = candidates.filter { it !== png && it !== jpeg }
        }
        for (candidate in preferred + rest) {
            if (candidate.encodedSize < options.maxBytes) {
                return ResizedImage(
                    data = candidate.data,
                    mimeType = candidate.mimeType,
                    originalWidth = originalWidth,
                    originalHeight = originalHeight,
                    width = currentWidth,
                    height = currentHeight,
                    wasResized = true,
                )
            }
        }

        if (currentWidth == 1 && currentHeight == 1) return null
        val nextWidth = if (currentWidth == 1) 1 else maxOf(1, (currentWidth * 3) / 4)
        val nextHeight = if (currentHeight == 1) 1 else maxOf(1, (currentHeight * 3) / 4)
        if (nextWidth == currentWidth && nextHeight == currentHeight) return null
        currentWidth = nextWidth
        currentHeight = nextHeight
    }
}

internal fun formatDimensionNote(result: ResizedImage): String? {
    if (!result.wasResized) return null
    val scale = result.originalWidth.toDouble() / result.width
    return "[Image: original ${result.originalWidth}x${result.originalHeight}, displayed at " +
        "${result.width}x${result.height}. Multiply coordinates by ${formatScale(scale)} to map to original image.]"
}

internal fun formatScale(scale: Double): String {
    val hundredths = (scale * 100.0).roundToLong()
    val whole = hundredths / 100L
    val fraction = (hundredths % 100L).toInt()
    return "$whole.${fraction.toString().padStart(2, '0')}"
}

private fun aspectRound(value: Int, target: Int, current: Int): Int =
    ((value.toDouble() * target) / current).roundToLong().toInt().coerceAtLeast(1)

private fun conversionHints(from: String?, to: String): List<String> =
    if (from == null || from == to) emptyList() else listOf("[Image converted from $from to $to.]")

private data class EncodedCandidate(
    val data: String,
    val encodedSize: Long,
    val mimeType: String,
    val format: ImageFormat,
    val quality: Int?,
)

private suspend fun encodeCandidates(
    codec: ImageCodec,
    bytes: ByteArray,
    mimeType: String,
    width: Int,
    height: Int,
    qualitySteps: List<Int>,
): List<EncodedCandidate> {
    val rendered = codec.render(bytes, mimeType, width, height) ?: return emptyList()
    try {
        val candidates = mutableListOf<EncodedCandidate>()
        codec.encode(rendered, ImageFormat.Png, null)?.let { png ->
            candidates.add(
                EncodedCandidate(
                    data = encodeBase64(png),
                    encodedSize = base64Length(png),
                    mimeType = ImageFormat.Png.mimeType,
                    format = ImageFormat.Png,
                    quality = null,
                ),
            )
        }
        for (quality in qualitySteps) {
            codec.encode(rendered, ImageFormat.Jpeg, quality)?.let { jpeg ->
                candidates.add(
                    EncodedCandidate(
                        data = encodeBase64(jpeg),
                        encodedSize = base64Length(jpeg),
                        mimeType = ImageFormat.Jpeg.mimeType,
                        format = ImageFormat.Jpeg,
                        quality = quality,
                    ),
                )
            }
        }
        return candidates
    } finally {
        codec.release(rendered)
    }
}
