package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Model
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PiJson
import dev.pi.ai.ProviderResponse
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TranscriptContext
import dev.pi.ai.clampThinkingLevel
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.SseParser
import dev.pi.ai.utils.ProviderErrors
import dev.pi.ai.utils.Retry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

private const val AZURE_RESPONSES_API = "azure-openai-responses"
private const val AZURE_DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private const val AZURE_PROMPT_CACHE_KEY_MAX_LENGTH = 64
private const val AZURE_MIN_OUTPUT_TOKENS = 16L
private const val AZURE_ERROR_PREFIX = "Azure OpenAI API error"

private val AZURE_TOOL_CALL_PROVIDERS = setOf("openai", "openai-codex", "opencode", "azure")

internal class ResolvedAzureCompat(
    val supportsMidConvoSystemMessages: Boolean,
    val supportsStrictMode: Boolean,
    val supportsOpenAIGrammarTools: Boolean,
    val supportsAdditionalTools: Boolean,
    val supportsToolSearch: Boolean,
)

class AzureOpenAIResponsesApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = AZURE_RESPONSES_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = startStream(model, context, options, ResponsesRequestOptions())

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        requireAzureApiKey(model, options.stream)
        val base = buildBaseOptions(model, context, options)
        val clamped = options.reasoning?.let { clampThinkingLevel(model, it.toModelThinkingLevel()) }
        val reasoningEffort = if (clamped == null || clamped == ModelThinkingLevel.Off) null else clamped
        return startStream(
            model,
            context,
            base,
            ResponsesRequestOptions(
                reasoningEffort = reasoningEffort,
                toolChoice = options.toolChoice?.let { JsonPrimitive(azureToolChoiceName(it)) },
            ),
        )
    }

    private suspend fun startStream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
        requestOptions: ResponsesRequestOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runAzureResponsesStream(transport, stream, model, context, options, requestOptions)
        }
        return stream
    }
}

fun azureOpenAIResponsesApi(transport: HttpTransport): ApiProvider = AzureOpenAIResponsesApi(transport)

private fun azureToolChoiceName(choice: dev.pi.ai.ToolChoice): String = when (choice) {
    dev.pi.ai.ToolChoice.Auto -> "auto"
    dev.pi.ai.ToolChoice.None -> "none"
}

private class AzureProviderHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : RuntimeException("HTTP $status")

private fun resolveAzureCompat(model: Model): ResolvedAzureCompat {
    val compat = model.compat
    return ResolvedAzureCompat(
        supportsMidConvoSystemMessages = compat?.let { booleanField(it, "supportsMidConvoSystemMessages") } ?: false,
        supportsStrictMode = compat?.let { booleanField(it, "supportsStrictMode") } ?: true,
        supportsOpenAIGrammarTools = compat?.let { booleanField(it, "supportsOpenAIGrammarTools") } ?: false,
        supportsAdditionalTools = compat?.let { booleanField(it, "supportsAdditionalTools") } ?: false,
        supportsToolSearch = compat?.let { booleanField(it, "supportsToolSearch") } ?: false,
    )
}

private suspend fun runAzureResponsesStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    requestOptions: ResponsesRequestOptions,
) {
    val signal = options.signal
    val compat = resolveAzureCompat(model)
    val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = emptyResponsesUsage(),
        stopReason = StopReason.Pending,
        timestamp = nowMillis(),
    )
    try {
        val apiKey = requireAzureApiKey(model, options)
        val deploymentName = resolveAzureDeploymentName(model, options.env)
        val config = resolveAzureConfig(model, options.env)
        val grammarToolInputProperties = createResponsesGrammarToolInputProperties(
            getDeclaredTools(normalizedContext.messages),
            compat.supportsOpenAIGrammarTools,
        )
        val headers = buildAzureHeaders(model, options, apiKey)
        var params = buildAzureParams(
            model = model,
            context = normalizedContext,
            options = options,
            requestOptions = requestOptions,
            compat = compat,
            deploymentName = deploymentName,
            grammarToolInputProperties = grammarToolInputProperties,
        )
        val payload = options.onPayload?.invoke(params, model)
        if (payload is JsonObject) params = payload

        signal?.throwIfAborted()
        val response = executeAzureRequestWithRetry(
            transport = transport,
            url = buildAzureResponsesUrl(config.baseUrl, config.apiVersion),
            params = params,
            headers = headers,
            options = options,
        )
        options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()

        val processor = ResponsesStreamProcessor(
            output = output,
            stream = stream,
            model = model,
            options = ResponsesStreamOptions(
                onProviderStreamEvent = options.onProviderStreamEvent,
                grammarToolInputProperties = grammarToolInputProperties,
            ),
        )
        val sse = SseParser(response.body)
        try {
            while (true) {
                val event = sse.next() ?: break
                val data = event.data.trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val parsed = PiJson.parseToJsonElement(data) as? JsonObject ?: continue
                processor.handle(parsed)
                signal?.throwIfAborted()
            }
            processor.finish()
        } finally {
            sse.close()
            response.close()
        }

        signal?.throwIfAborted()
        if (output.stopReason == StopReason.Pending) {
            throw ResponsesStreamException("Azure OpenAI Responses stream ended without a stop reason")
        }
        if (output.stopReason == StopReason.Aborted || output.stopReason == StopReason.Error) {
            throw ResponsesStreamException(output.errorMessage ?: "An unknown error occurred")
        }
        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        val aborted = error is RequestAbortedException || signal?.isAborted == true ||
            output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else formatAzureError(error)
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun buildAzureParams(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    requestOptions: ResponsesRequestOptions,
    compat: ResolvedAzureCompat,
    deploymentName: String,
    grammarToolInputProperties: Map<String, String>,
): JsonObject {
    val toolSupport = when (responsesToolSupport(
        compat.supportsMidConvoSystemMessages,
        compat.supportsAdditionalTools,
        compat.supportsToolSearch,
    )) {
        ResponsesToolSupport.None -> TranscriptToolSupport.None
        ResponsesToolSupport.Additions -> TranscriptToolSupport.Additions
        ResponsesToolSupport.Changes -> TranscriptToolSupport.Changes
    }
    val transcriptTools = resolveTranscriptTools(context.messages, toolSupport)
    val toolOptions = ConvertResponsesToolsOptions(
        supportsStrictMode = compat.supportsStrictMode,
        supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools,
    )
    val messages = convertResponsesMessages(
        model = model,
        context = context,
        allowedToolCallProviders = AZURE_TOOL_CALL_PROVIDERS,
        options = ConvertResponsesMessagesOptions(
            includeSystemPrompt = true,
            grammarToolInputProperties = grammarToolInputProperties,
            supportsMidConvoSystemMessages = compat.supportsMidConvoSystemMessages,
            supportsAdditionalTools = compat.supportsAdditionalTools,
            supportsToolSearch = compat.supportsToolSearch,
            toolOptions = toolOptions,
        ),
    )
    val params = LinkedHashMap<String, JsonElement>()
    params["model"] = JsonPrimitive(deploymentName)
    params["input"] = messages
    params["stream"] = JsonPrimitive(true)
    azureClampPromptCacheKey(options.sessionId)?.let { params["prompt_cache_key"] = JsonPrimitive(it) }
    params["store"] = JsonPrimitive(false)
    val maxTokens = options.maxTokens
    if (maxTokens != null) {
        params["max_output_tokens"] = JsonPrimitive(maxOf(maxTokens, AZURE_MIN_OUTPUT_TOKENS))
    }
    if (options.temperature != null) {
        params["temperature"] = JsonPrimitive(options.temperature)
    }
    if (transcriptTools.requestTools.isNotEmpty()) {
        params["tools"] = convertResponsesTools(transcriptTools.requestTools, toolOptions)
    }
    requestOptions.toolChoice?.let { params["tool_choice"] = it }

    val reasoningEffortName = requestOptions.reasoningEffort?.jsonName()
    val reasoningEffort = reasoningEffortName ?: if (requestOptions.reasoningSummary != null) "medium" else null
    if (model.reasoning) {
        if (reasoningEffort != null) {
            val effort = if (reasoningEffortName != null) {
                model.thinkingLevelMap?.get(reasoningEffort) ?: reasoningEffort
            } else {
                reasoningEffort
            }
            params["reasoning"] = buildJsonObject {
                put("effort", effort)
                put("summary", requestOptions.reasoningSummary ?: "auto")
            }
            params["include"] = JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))
        } else if (!azureOffLevelUnsupported(model)) {
            val offEffort = model.thinkingLevelMap?.get("off") ?: "none"
            params["reasoning"] = buildJsonObject { put("effort", offEffort) }
        }
    }

    resolveSamplingParams(
        model,
        azureThinkingLevelOf(reasoningEffort ?: "off"),
        options.samplingParams,
    )?.forEach { (key, value) -> params[key] = value }
    return JsonObject(params)
}

private fun azureOffLevelUnsupported(model: Model): Boolean {
    val levels = model.thinkingLevelMap ?: return false
    return levels.containsKey("off") && levels["off"] == null
}

private fun azureThinkingLevelOf(name: String): ModelThinkingLevel = when (name) {
    "minimal" -> ModelThinkingLevel.Minimal
    "low" -> ModelThinkingLevel.Low
    "medium" -> ModelThinkingLevel.Medium
    "high" -> ModelThinkingLevel.High
    "xhigh" -> ModelThinkingLevel.Xhigh
    "max" -> ModelThinkingLevel.Max
    else -> ModelThinkingLevel.Off
}

private fun azureClampPromptCacheKey(key: String?): String? {
    if (key == null) return null
    if (key.length <= AZURE_PROMPT_CACHE_KEY_MAX_LENGTH) return key
    val builder = StringBuilder()
    var count = 0
    var index = 0
    while (index < key.length && count < AZURE_PROMPT_CACHE_KEY_MAX_LENGTH) {
        val char = key[index]
        if (char.isHighSurrogate() && index + 1 < key.length && key[index + 1].isLowSurrogate()) {
            builder.append(char).append(key[index + 1])
            index += 2
        } else {
            builder.append(char)
            index += 1
        }
        count += 1
    }
    return builder.toString()
}

private fun requireAzureApiKey(model: Model, options: StreamOptions): String? {
    options.apiKey?.let { return it }
    if (azureHasHeader(options.headers, "authorization") || azureHasHeader(options.headers, "api-key")) return null
    throw IllegalStateException("No API key for provider: ${model.provider}")
}

private fun isEntraToken(apiKey: String): Boolean =
    apiKey.startsWith("eyJ") && apiKey.count { it == '.' } == 2

private fun buildAzureHeaders(model: Model, options: StreamOptions, apiKey: String?): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers.putAll(azureIdentityHeaders(model, options.identity))
    model.headers?.let(headers::putAll)
    options.headers?.forEach { (name, value) ->
        if (value != null) {
            headers[name] = value
        } else {
            headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach(headers::remove)
        }
    }
    if (apiKey != null && !azureHasHeader(headers, "api-key") && !azureHasHeader(headers, "authorization")) {
        if (isEntraToken(apiKey)) {
            headers["Authorization"] = "Bearer $apiKey"
        } else {
            headers["api-key"] = apiKey
        }
    }
    if (!azureHasHeader(headers, "content-type")) headers["Content-Type"] = "application/json"
    if (!azureHasHeader(headers, "accept")) headers["Accept"] = "text/event-stream"
    return headers
}

private fun azureIdentityHeaders(model: Model, identity: ClientIdentity?): Map<String, String> {
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

private fun azureHasHeader(headers: Map<String, String?>?, name: String): Boolean {
    if (headers == null) return false
    val expected = name.lowercase()
    return headers.any { (key, value) ->
        key.lowercase() == expected && value != null && value.trim().isNotEmpty()
    }
}

private suspend fun executeAzureRequestWithRetry(
    transport: HttpTransport,
    url: String,
    params: JsonObject,
    headers: Map<String, String>,
    options: StreamOptions,
): HttpResponse {
    val signal = options.signal
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    val body = PiJson.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
    while (true) {
        signal?.throwIfAborted()
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = body,
            timeoutMs = options.timeoutMs,
            signal = signal,
        )
        val response = try {
            transport.execute(request)
        } catch (error: HttpTransportException) {
            if (signal?.isAborted == true) throw RequestAbortedException("Request was aborted")
            if (retriesRemaining <= 0) throw error
            val retryIndex = maxRetries - retriesRemaining
            retriesRemaining -= 1
            Retry.sleep(azureRetryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), signal)
            continue
        }
        if (response.isSuccessful()) return response
        val retryable = isRetryableAzureResponse(response)
        if (retriesRemaining > 0 && retryable) {
            val retryIndex = maxRetries - retriesRemaining
            val delayMs = azureRetryDelayMs(response, retryIndex, options.maxRetryDelayMs, "HTTP ${response.status}")
            retriesRemaining -= 1
            response.close()
            Retry.sleep(delayMs, signal)
            continue
        }
        throw azureHttpError(response)
    }
}

private fun isRetryableAzureResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private suspend fun azureHttpError(response: HttpResponse): AzureProviderHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return AzureProviderHttpException(response.status, response.headers, body)
}

private fun azureRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        response.header("retry-after-ms")?.toDoubleOrNull()?.let { retryAfterMs ->
            return validateAzureRetryDelayMs(retryAfterMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        }
        response.header("retry-after")?.toDoubleOrNull()?.let { seconds ->
            return validateAzureRetryDelayMs((seconds * 1000).toLong(), maxRetryDelayMs, providerErrorMessage)
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateAzureRetryDelayMs(
    delayMs: Long,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    val maxDelayMs = maxRetryDelayMs ?: AZURE_DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw ResponsesStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun formatAzureError(error: Throwable): String = when (error) {
    is AzureProviderHttpException -> ProviderErrors.format(
        ProviderErrors.normalize(error.status, error.headers, error.body),
        AZURE_ERROR_PREFIX,
    )

    else -> error.message ?: error.toString()
}
