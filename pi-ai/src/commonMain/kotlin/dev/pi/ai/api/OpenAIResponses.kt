package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.BlockContent
import dev.pi.ai.CacheRetention
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.JsonValue
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelInputModality
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PiJson
import dev.pi.ai.ProviderResponse
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.ToolChoice
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
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

private const val RESPONSES_API = "openai-responses"
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private const val OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64
private const val OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16L
private const val CONFIGURATION_UPDATE_REQUEST_EFFORT = "medium"
private const val CHATGPT_USAGE_URL = "https://chatgpt.com/settings/usage"
private const val CHATGPT_WORKSPACE_LIMIT_HINT =
    "You've reached your ChatGPT workspace's usage limit. Ask your workspace admin to increase it at admin.openai.com → Workspace → Usage limits."

private val OPENAI_TOOL_CALL_PROVIDERS = setOf("openai", "openai-codex", "opencode")

internal class ResponsesRequestOptions(
    val reasoningEffort: ModelThinkingLevel? = null,
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    val toolChoice: JsonElement? = null,
)

class OpenAIResponsesApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = RESPONSES_API

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
        getResponsesClientApiKey(model, options.stream)
        val base = buildBaseOptions(model, context, options)
        val clamped = options.reasoning?.let { clampThinkingLevel(model, it.toModelThinkingLevel()) }
        val reasoningEffort = if (clamped == null || clamped == ModelThinkingLevel.Off) null else clamped
        return startStream(
            model,
            context,
            base,
            ResponsesRequestOptions(
                reasoningEffort = reasoningEffort,
                toolChoice = options.toolChoice?.let { JsonPrimitive(responsesWireName(it)) },
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
            runResponsesStream(transport, stream, model, context, options, requestOptions)
        }
        return stream
    }
}

fun openAIResponsesApi(transport: HttpTransport): ApiProvider = OpenAIResponsesApi(transport)

private fun responsesWireName(choice: ToolChoice): String = when (choice) {
    ToolChoice.Auto -> "auto"
    ToolChoice.None -> "none"
}

private class ResponsesHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : RuntimeException("HTTP $status")

private suspend fun runResponsesStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    requestOptions: ResponsesRequestOptions,
) {
    val signal = options.signal
    val compat = resolveResponsesCompat(model)
    val configurationUpdateEffort = resolveConfigurationUpdateEffort(model, options, requestOptions, compat)
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        providerThinkingLevel = configurationUpdateEffort,
        usage = emptyResponsesUsage(),
        stopReason = StopReason.Pending,
        timestamp = dev.pi.ai.api.nowMillis(),
    )
    try {
        val apiKey = getResponsesClientApiKey(model, options)
        val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
        val cacheRetention = resolveCacheRetention(options.cacheRetention, options.env)
        val cacheSessionId = if (cacheRetention == CacheRetention.None) null else options.sessionId
        val grammarToolInputProperties = createResponsesGrammarToolInputProperties(
            getDeclaredTools(normalizedContext.messages),
            compat.supportsOpenAIGrammarTools,
        )
        val headers = buildRequestHeaders(model, normalizedContext, options, apiKey, cacheSessionId, compat)
        var params = buildResponsesParams(
            model,
            normalizedContext,
            options,
            requestOptions,
            compat,
            grammarToolInputProperties,
            configurationUpdateEffort,
        )
        val payload = options.onPayload?.invoke(params, model)
        if (payload is JsonObject) params = payload

        signal?.throwIfAborted()
        val response = executeResponsesRequestWithRetry(transport, model, params, headers, options)
        options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()

        val processor = ResponsesStreamProcessor(
            output = output,
            stream = stream,
            model = model,
            options = ResponsesStreamOptions(
                onProviderStreamEvent = options.onProviderStreamEvent,
                serviceTier = requestOptions.serviceTier,
                grammarToolInputProperties = grammarToolInputProperties,
                applyServiceTierPricing = { usage, serviceTier ->
                    applyServiceTierPricing(usage, serviceTier, model)
                },
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
            throw ResponsesStreamException("OpenAI Responses stream ended without a stop reason")
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
        output.errorMessage = if (aborted) "Request was aborted" else formatResponsesStreamError(error, model)
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun buildResponsesParams(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    requestOptions: ResponsesRequestOptions,
    compat: ResolvedOpenAIResponsesCompat,
    grammarToolInputProperties: Map<String, String>,
    configurationUpdateEffort: String?,
): JsonObject {
    val transcriptTools = resolveTranscriptTools(
        context.messages,
        when (responsesToolSupport(
            compat.supportsMidConvoSystemMessages,
            compat.supportsAdditionalTools,
            compat.supportsToolSearch,
        )) {
            ResponsesToolSupport.None -> TranscriptToolSupport.None
            ResponsesToolSupport.Additions -> TranscriptToolSupport.Additions
            ResponsesToolSupport.Changes -> TranscriptToolSupport.Changes
        },
    )
    val cacheRetention = resolveCacheRetention(options.cacheRetention, options.env)
    val messages = convertResponsesMessages(
        model = model,
        context = context,
        allowedToolCallProviders = OPENAI_TOOL_CALL_PROVIDERS,
        options = ConvertResponsesMessagesOptions(
            includeSystemPrompt = true,
            grammarToolInputProperties = grammarToolInputProperties,
            supportsMidConvoSystemMessages = compat.supportsMidConvoSystemMessages,
            supportsAdditionalTools = compat.supportsAdditionalTools,
            supportsToolSearch = compat.supportsToolSearch,
            toolOptions = ConvertResponsesToolsOptions(
                supportsStrictMode = compat.supportsStrictMode,
                supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools,
            ),
            configurationUpdateEffort = configurationUpdateEffort,
        ),
    )
    val omitUnsupportedFields = isChatGPTSignIn(model, options.apiKey)
    val params = LinkedHashMap<String, JsonElement>()
    params["model"] = JsonPrimitive(model.id)
    params["input"] = messages
    params["stream"] = JsonPrimitive(true)
    if (cacheRetention != CacheRetention.None) {
        clampResponsesPromptCacheKey(options.sessionId)?.let { params["prompt_cache_key"] = JsonPrimitive(it) }
    }
    if (!omitUnsupportedFields) {
        promptCacheRetention(compat, cacheRetention)?.let { params["prompt_cache_retention"] = JsonPrimitive(it) }
        promptCacheOptions(compat, cacheRetention)?.let { params["prompt_cache_options"] = it }
    }
    params["store"] = JsonPrimitive(false)
    val maxTokens = options.maxTokens
    if (maxTokens != null && compat.supportsMaxOutputTokens && !omitUnsupportedFields) {
        params["max_output_tokens"] = JsonPrimitive(maxOf(maxTokens, OPENAI_RESPONSES_MIN_OUTPUT_TOKENS))
    }
    if (options.temperature != null && !omitUnsupportedFields) {
        params["temperature"] = JsonPrimitive(options.temperature)
    }
    requestOptions.serviceTier?.let { params["service_tier"] = JsonPrimitive(it) }
    if (transcriptTools.requestTools.isNotEmpty()) {
        params["tools"] = convertResponsesTools(
            transcriptTools.requestTools,
            ConvertResponsesToolsOptions(
                supportsStrictMode = compat.supportsStrictMode,
                supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools,
            ),
        )
    }
    requestOptions.toolChoice?.let { params["tool_choice"] = it }

    val reasoningEffortName = requestOptions.reasoningEffort?.jsonName()
    val reasoningEffort = reasoningEffortName ?: if (requestOptions.reasoningSummary != null) "medium" else null
    if (model.reasoning) {
        if (reasoningEffort != null) {
            val requestedEffort = if (reasoningEffortName != null) {
                model.thinkingLevelMap?.get(reasoningEffort) ?: reasoningEffort
            } else {
                reasoningEffort
            }
            val effort = if (configurationUpdateEffort == null) requestedEffort else CONFIGURATION_UPDATE_REQUEST_EFFORT
            params["reasoning"] = buildJsonObject {
                put("effort", effort)
                put("summary", requestOptions.reasoningSummary ?: "auto")
            }
            params["include"] = JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))
        } else if (model.provider != "github-copilot" && !offLevelExplicitlyUnsupported(model)) {
            val offEffort = model.thinkingLevelMap?.get("off") ?: "none"
            params["reasoning"] = buildJsonObject { put("effort", offEffort) }
        }
        if (model.provider == "xai") {
            params["include"] = JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))
        }
    }

    val samplingLevel = thinkingLevelOf(reasoningEffort ?: "off")
    resolveSamplingParams(model, samplingLevel, options.samplingParams)?.forEach { (key, value) ->
        params[key] = value
    }
    return JsonObject(params)
}

private fun resolveConfigurationUpdateEffort(
    model: Model,
    options: StreamOptions,
    requestOptions: ResponsesRequestOptions,
    compat: ResolvedOpenAIResponsesCompat,
): String? {
    val level = requestOptions.reasoningEffort ?: return null
    if (!compat.supportsMidConvoEffort) return null
    if (isChatGPTSignIn(model, options.apiKey)) return null
    val name = level.jsonName()
    return model.thinkingLevelMap?.get(name) ?: name
}

private fun offLevelExplicitlyUnsupported(model: Model): Boolean {
    val levels = model.thinkingLevelMap ?: return false
    return levels.containsKey("off") && levels["off"] == null
}

private fun thinkingLevelOf(name: String): ModelThinkingLevel = when (name) {
    "minimal" -> ModelThinkingLevel.Minimal
    "low" -> ModelThinkingLevel.Low
    "medium" -> ModelThinkingLevel.Medium
    "high" -> ModelThinkingLevel.High
    "xhigh" -> ModelThinkingLevel.Xhigh
    "max" -> ModelThinkingLevel.Max
    else -> ModelThinkingLevel.Off
}

private fun promptCacheRetention(
    compat: ResolvedOpenAIResponsesCompat,
    cacheRetention: CacheRetention,
): String? = if (cacheRetention == CacheRetention.Long && compat.supportsLongCacheRetention &&
    !compat.supportsExplicitPromptCacheMode
) {
    "24h"
} else {
    null
}

private fun promptCacheOptions(
    compat: ResolvedOpenAIResponsesCompat,
    cacheRetention: CacheRetention,
): JsonObject? {
    if (!compat.supportsExplicitPromptCacheMode) return null
    return when {
        cacheRetention == CacheRetention.None -> buildJsonObject { put("mode", "explicit") }
        cacheRetention == CacheRetention.Long && compat.supportsLongCacheRetention -> buildJsonObject {
            put("ttl", "30m")
        }

        else -> null
    }
}

private fun isChatGPTSignIn(model: Model, apiKey: String?): Boolean =
    model.provider == "openai" &&
        model.baseUrl == "https://api.openai.com/v1" &&
        apiKey != null &&
        !apiKey.startsWith("sk-")

private fun getResponsesClientApiKey(model: Model, options: StreamOptions): String {
    options.apiKey?.let { return it }
    if (responsesHasHeader(options.headers, "authorization") || responsesHasHeader(options.headers, "cf-aig-authorization")) {
        return "unused"
    }
    throw IllegalStateException("No API key for provider: ${model.provider}")
}

private fun responsesHasHeader(headers: Map<String, String?>?, name: String): Boolean {
    if (headers == null) return false
    val expected = name.lowercase()
    return headers.any { (key, value) ->
        key.lowercase() == expected && value != null && value.trim().isNotEmpty()
    }
}

private fun buildRequestHeaders(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    apiKey: String,
    sessionId: String?,
    compat: ResolvedOpenAIResponsesCompat,
): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers.putAll(responsesIdentityHeaders(model, options.identity))
    model.headers?.let(headers::putAll)
    if (model.provider == "github-copilot") headers.putAll(responsesCopilotDynamicHeaders(context.messages))
    if (sessionId != null) {
        when (compat.sessionAffinityFormat) {
            ResponsesSessionAffinityFormat.Openrouter -> headers["x-session-id"] = sessionId
            ResponsesSessionAffinityFormat.OpenaiNosession -> headers["x-client-request-id"] = sessionId
            ResponsesSessionAffinityFormat.Openai -> {
                headers["session_id"] = sessionId
                headers["x-client-request-id"] = sessionId
            }
        }
    }
    options.headers?.forEach { (name, value) ->
        if (value != null) {
            headers[name] = value
        } else {
            headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach(headers::remove)
        }
    }
    if (!responsesHasHeader(headers, "authorization") && !responsesHasHeader(headers, "cf-aig-authorization")) {
        headers["Authorization"] = "Bearer $apiKey"
    }
    if (!responsesHasHeader(headers, "content-type")) headers["Content-Type"] = "application/json"
    if (!responsesHasHeader(headers, "accept")) headers["Accept"] = "text/event-stream"
    return headers
}

private fun responsesIdentityHeaders(model: Model, identity: ClientIdentity?): Map<String, String> {
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

private fun responsesHasCopilotVisionInput(messages: List<Message>): Boolean = messages.any { message ->
    when (message) {
        is UserMessage -> {
            val content = message.content
            content is BlockContent && content.blocks.any { it is ImageContent }
        }

        is ToolResultMessage -> message.content.any { it is ImageContent }
        else -> false
    }
}

private fun responsesCopilotDynamicHeaders(messages: List<Message>): Map<String, String> {
    val last = messages.lastOrNull()
    val initiator = if (last != null && last !is UserMessage) "agent" else "user"
    val headers = linkedMapOf(
        "X-Initiator" to initiator,
        "Openai-Intent" to "conversation-edits",
    )
    if (responsesHasCopilotVisionInput(messages)) headers["Copilot-Vision-Request"] = "true"
    return headers
}

private fun clampResponsesPromptCacheKey(key: String?): String? {
    if (key == null) return null
    if (key.length <= OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) return key
    val builder = StringBuilder()
    var count = 0
    var index = 0
    while (index < key.length && count < OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) {
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

private fun responsesUrl(model: Model): String = model.baseUrl.trimEnd('/') + "/responses"

private suspend fun executeResponsesRequestWithRetry(
    transport: HttpTransport,
    model: Model,
    params: JsonObject,
    headers: Map<String, String>,
    options: StreamOptions,
): HttpResponse {
    val signal = options.signal
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    val url = responsesUrl(model)
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
            Retry.sleep(responsesRetryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), signal)
            continue
        }
        if (response.isSuccessful()) return response
        val retryable = isRetryableResponsesResponse(response)
        if (retriesRemaining > 0 && retryable) {
            val retryIndex = maxRetries - retriesRemaining
            val delayMs = responsesRetryDelayMs(response, retryIndex, options.maxRetryDelayMs, "HTTP ${response.status}")
            retriesRemaining -= 1
            response.close()
            Retry.sleep(delayMs, signal)
            continue
        }
        throw responsesHttpError(response)
    }
}

private fun isRetryableResponsesResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private suspend fun responsesHttpError(response: HttpResponse): ResponsesHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return ResponsesHttpException(response.status, response.headers, body)
}

private fun responsesRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        response.header("retry-after-ms")?.toDoubleOrNull()?.let { retryAfterMs ->
            return validateResponsesRetryDelayMs(retryAfterMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        }
        response.header("retry-after")?.toDoubleOrNull()?.let { seconds ->
            return validateResponsesRetryDelayMs((seconds * 1000).toLong(), maxRetryDelayMs, providerErrorMessage)
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateResponsesRetryDelayMs(
    delayMs: Long,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw ResponsesStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun formatResponsesStreamError(error: Throwable, model: Model): String {
    var message = when (error) {
        is ResponsesHttpException -> ProviderErrors.format(
            ProviderErrors.normalize(error.status, error.headers, error.body),
            "${if (model.provider == "openai") "OpenAI" else model.provider} API error",
        )

        else -> error.message ?: error.toString()
    }
    if (message.contains("subscription_sharing_usage_limit_exceeded")) {
        message += "\nCheck your ChatGPT usage: $CHATGPT_USAGE_URL"
    } else if (message.contains("subscription_sharing_workplace_individual_usage_limit_exceeded")) {
        message += "\n$CHATGPT_WORKSPACE_LIMIT_HINT"
    }
    return message
}

private fun getServiceTierCostMultiplier(model: Model, serviceTier: String?): Double = when (serviceTier) {
    "flex" -> 0.5
    "priority", "fast" -> if (model.id == "gpt-5.5") 2.5 else 2.0
    else -> 1.0
}

private fun applyServiceTierPricing(usage: dev.pi.ai.Usage, serviceTier: String?, model: Model) {
    val multiplier = getServiceTierCostMultiplier(model, serviceTier)
    if (multiplier == 1.0) return
    usage.cost.input *= multiplier
    usage.cost.output *= multiplier
    usage.cost.cacheRead *= multiplier
    usage.cost.cacheWrite *= multiplier
    usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite
}
