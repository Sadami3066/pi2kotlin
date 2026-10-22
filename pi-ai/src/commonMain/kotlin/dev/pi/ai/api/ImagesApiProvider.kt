package dev.pi.ai.api

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.AnyModel
import dev.pi.ai.AssistantImages
import dev.pi.ai.ClientIdentity
import dev.pi.ai.ImageModel
import dev.pi.ai.ImagesContext
import dev.pi.ai.ImagesOptions
import dev.pi.ai.ImagesStopReason
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.utils.ProviderErrors
import dev.pi.ai.utils.Retry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L

interface ImagesApiProvider {
    suspend fun generateImages(
        model: ImageModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages
}

internal class ImagesApiHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : RuntimeException("HTTP $status")

internal fun imageErrorResult(model: ImageModel, error: Throwable): AssistantImages = AssistantImages(
    api = model.api,
    provider = model.provider,
    model = model.id,
    output = emptyList(),
    stopReason = ImagesStopReason.Error,
    errorMessage = error.message ?: error.toString(),
    timestamp = platformCurrentTimeMillis(),
)

internal fun providerIdentityHeaders(model: AnyModel, identity: ClientIdentity?): MutableMap<String, String> {
    val headers = linkedMapOf<String, String>()
    headers["User-Agent"] = identity?.userAgent ?: "pi"
    val appName = identity?.appName
    val appUrl = identity?.appUrl
    if (appName == null && appUrl == null) return headers
    val isOpenRouter = model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")
    val isNvidia = model.provider == "nvidia" || model.baseUrl.contains("integrate.api.nvidia.com")
    val isOpenCode = model.provider == "opencode" || model.provider == "opencode-go" ||
        model.baseUrl.contains("opencode.ai")
    when {
        isOpenRouter -> {
            if (appUrl != null) headers["HTTP-Referer"] = appUrl
            if (appName != null) headers["X-OpenRouter-Title"] = appName
        }

        isNvidia -> if (appName != null) {
            headers["X-BILLING-INVOKE-ORIGIN"] = appName.replaceFirstChar { it.uppercase() }
        }

        isOpenCode -> if (appName != null) headers["x-opencode-client"] = appName
    }
    return headers
}

internal fun applyHeaderOverrides(headers: MutableMap<String, String>, overrides: Map<String, String?>?): MutableMap<String, String> {
    if (overrides == null) return headers
    for ((name, value) in overrides) {
        headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach { headers.remove(it) }
        if (value != null) headers[name] = value
    }
    return headers
}

internal fun hasHeaderName(headers: Map<String, String>, name: String): Boolean =
    headers.keys.any { it.equals(name, ignoreCase = true) }

internal fun imagesUsage(
    rawUsage: JsonElement?,
    model: ImageModel,
): Usage? {
    if (rawUsage !is JsonObject) return null
    fun number(key: String): Long {
        val primitive = rawUsage[key] as? JsonPrimitive ?: return 0L
        if (primitive.isString) return 0L
        return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong() ?: 0L
    }

    val promptTokens = number("prompt_tokens")
    val details = rawUsage["prompt_tokens_details"] as? JsonObject
    fun detail(key: String): Long {
        val primitive = details?.get(key) as? JsonPrimitive ?: return 0L
        if (primitive.isString) return 0L
        return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong() ?: 0L
    }

    val reportedCached = detail("cached_tokens")
    val cacheWrite = detail("cache_write_tokens")
    val cacheRead = if (cacheWrite > 0) maxOf(0L, reportedCached - cacheWrite) else reportedCached
    val input = maxOf(0L, promptTokens - cacheRead - cacheWrite)
    val output = number("completion_tokens")
    val cost = UsageCost(
        input = model.cost.input / 1_000_000.0 * input,
        output = model.cost.output / 1_000_000.0 * output,
        cacheRead = model.cost.cacheRead / 1_000_000.0 * cacheRead,
        cacheWrite = model.cost.cacheWrite / 1_000_000.0 * cacheWrite,
        total = 0.0,
    )
    cost.total = cost.input + cost.output + cost.cacheRead + cost.cacheWrite
    return Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + output + cacheRead + cacheWrite,
        cost = cost,
    )
}

internal suspend fun executeImagesRequest(
    transport: HttpTransport,
    url: String,
    headers: Map<String, String>,
    body: ByteArray,
    options: ImagesOptions,
): HttpResponse {
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    while (true) {
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = body,
            timeoutMs = options.timeoutMs,
            signal = null,
        )
        val response = try {
            transport.execute(request)
        } catch (error: HttpTransportException) {
            if (retriesRemaining <= 0) throw error
            val retryIndex = maxRetries - retriesRemaining
            retriesRemaining -= 1
            Retry.sleep(imagesRetryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), null)
            continue
        }
        if (response.isSuccessful()) return response
        if (retriesRemaining > 0 && isRetryableImagesResponse(response)) {
            val status = response.status
            val bodyText = try {
                response.body.readUtf8().trim()
            } catch (_: Throwable) {
                ""
            } finally {
                response.close()
            }
            val providerMessage = if (bodyText.isEmpty()) "HTTP $status" else "$status: $bodyText"
            val retryIndex = maxRetries - retriesRemaining
            val delayMs = imagesRetryDelayMs(response, retryIndex, options.maxRetryDelayMs, providerMessage)
            retriesRemaining -= 1
            Retry.sleep(delayMs, null)
            continue
        }
        throw imagesHttpError(response)
    }
}

private fun isRetryableImagesResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun imagesRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        val parsedMs = response.header("retry-after-ms")?.toDoubleOrNull()
        if (parsedMs != null) return validateImagesRetryDelayMs(parsedMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        val parsedSeconds = response.header("retry-after")?.toDoubleOrNull()
        if (parsedSeconds != null) {
            return validateImagesRetryDelayMs((parsedSeconds * 1000).toLong(), maxRetryDelayMs, providerErrorMessage)
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateImagesRetryDelayMs(delayMs: Long, maxRetryDelayMs: Long?, providerErrorMessage: String): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw IllegalStateException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun imagesHttpError(response: HttpResponse): ImagesApiHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return ImagesApiHttpException(response.status, response.headers, body)
}

internal fun imagesErrorMessage(error: Throwable): String = when (error) {
    is ImagesApiHttpException -> ProviderErrors.format(ProviderErrors.normalize(error.status, error.headers, error.body))
    else -> error.message ?: error.toString()
}
