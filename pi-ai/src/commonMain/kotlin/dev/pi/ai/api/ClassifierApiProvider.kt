package dev.pi.ai.api

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.ClassifierContext
import dev.pi.ai.ClassifierModel
import dev.pi.ai.ClassifierOptions
import dev.pi.ai.ClassifierResult
import dev.pi.ai.ClassifierStopReason
import dev.pi.ai.PiJson
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.calculateCost
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

private const val DEFAULT_CLASSIFIER_MAX_RETRY_DELAY_MS = 60_000L

interface ClassifierApiProvider {
    suspend fun classify(
        model: ClassifierModel,
        context: ClassifierContext,
        options: ClassifierOptions,
    ): ClassifierResult
}

internal class ClassifierApiHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : RuntimeException("HTTP $status")

internal fun classifierErrorResult(model: ClassifierModel, error: Throwable): ClassifierResult = ClassifierResult(
    api = model.api,
    provider = model.provider,
    model = model.id,
    answers = emptyMap(),
    stopReason = ClassifierStopReason.Error,
    errorMessage = error.message ?: error.toString(),
    timestamp = platformCurrentTimeMillis(),
)

internal fun isJsonRecord(value: JsonElement?): Boolean = value is JsonObject

internal fun jsonNumberOrNull(value: JsonElement?): Double? {
    val primitive = value as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toDoubleOrNull()
}

internal fun requiredClassifierNumber(label: String, value: JsonElement?, field: String): Double {
    val number = jsonNumberOrNull(value)
    if (number == null || !number.isFinite()) throw IllegalStateException("$label returned an invalid $field")
    return number
}

internal fun classifierTokenCount(value: JsonElement?): Long {
    val number = jsonNumberOrNull(value) ?: return 0L
    return if (number > 0) number.toLong() else 0L
}

internal fun parseClassifierUsage(value: JsonElement?, model: ClassifierModel): Usage? {
    if (value !is JsonObject) return null
    if (!value.containsKey("input_tokens") && !value.containsKey("output_tokens")) return null
    val input = classifierTokenCount(value["input_tokens"])
    val output = classifierTokenCount(value["output_tokens"])
    val usage = Usage(
        input = input,
        output = output,
        cacheRead = 0L,
        cacheWrite = 0L,
        totalTokens = input + output,
        cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
    )
    calculateCost(model, usage)
    return usage
}

internal fun classifierHttpErrorMessage(error: Throwable, prefix: String): String = when (error) {
    is ClassifierApiHttpException -> ProviderErrors.format(ProviderErrors.normalize(error.status, error.headers, error.body), prefix)
    else -> error.message ?: error.toString()
}

internal fun classifierRequestHeaders(
    model: ClassifierModel,
    options: ClassifierOptions,
    apiKey: String,
): Map<String, String> {
    val headers = providerIdentityHeaders(model, options.identity)
    headers["authorization"] = "Bearer $apiKey"
    headers["content-type"] = "application/json"
    model.headers?.let { modelHeaders ->
        for ((name, value) in modelHeaders) {
            headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach { headers.remove(it) }
            headers[name] = value
        }
    }
    return applyHeaderOverrides(headers, options.headers)
}

internal suspend fun postClassifierRequest(
    transport: HttpTransport,
    label: String,
    url: String,
    model: ClassifierModel,
    body: JsonObject,
    options: ClassifierOptions,
    noRetryStatuses: List<Int> = emptyList(),
): JsonElement {
    val apiKey = options.apiKey ?: throw IllegalStateException("No API key for provider: ${model.provider}")
    val headers = classifierRequestHeaders(model, options, apiKey)
    val bodyBytes = PiJson.encodeToString(JsonObject.serializer(), body).encodeToByteArray()
    val maxRetries = options.maxRetries ?: 2
    var retriesRemaining = maxRetries
    while (true) {
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = bodyBytes,
            timeoutMs = options.timeoutMs,
            signal = null,
        )
        val response = try {
            transport.execute(request)
        } catch (error: HttpTransportException) {
            if (retriesRemaining <= 0) throw error
            val retryIndex = maxRetries - retriesRemaining
            retriesRemaining -= 1
            Retry.sleep(classifierRetryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), null)
            continue
        }
        if (response.isSuccessful()) {
            val text = try {
                response.body.readUtf8()
            } finally {
                response.close()
            }
            return try {
                PiJson.parseToJsonElement(text)
            } catch (error: Exception) {
                throw IllegalStateException("$label returned an invalid response: ${error.message}")
            }
        }
        if (response.status in noRetryStatuses) throw classifierHttpError(response)
        if (retriesRemaining > 0 && isRetryableClassifierResponse(response)) {
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
            val delayMs = classifierRetryDelayMs(response, retryIndex, options.maxRetryDelayMs, providerMessage)
            retriesRemaining -= 1
            Retry.sleep(delayMs, null)
            continue
        }
        throw classifierHttpError(response)
    }
}

private fun isRetryableClassifierResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun classifierRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        val parsedMs = response.header("retry-after-ms")?.toDoubleOrNull()
        if (parsedMs != null) {
            return validateClassifierRetryDelayMs(parsedMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        }
        val parsedSeconds = response.header("retry-after")?.toDoubleOrNull()
        if (parsedSeconds != null) {
            return validateClassifierRetryDelayMs((parsedSeconds * 1000).toLong(), maxRetryDelayMs, providerErrorMessage)
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateClassifierRetryDelayMs(delayMs: Long, maxRetryDelayMs: Long?, providerErrorMessage: String): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_CLASSIFIER_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw IllegalStateException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun classifierHttpError(response: HttpResponse): ClassifierApiHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return ClassifierApiHttpException(response.status, response.headers, body)
}
