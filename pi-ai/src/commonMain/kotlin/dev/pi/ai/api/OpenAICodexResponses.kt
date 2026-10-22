package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.CacheRetention
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
import dev.pi.ai.ToolChoice
import dev.pi.ai.TranscriptContext
import dev.pi.ai.clampThinkingLevel
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.SseParser
import dev.pi.ai.utils.Retry
import dev.pi.ai.utils.getInitialSystemMessage
import dev.pi.ai.utils.getSystemMessageText
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal const val OPENAI_CODEX_RESPONSES_API = "openai-codex-responses"

private const val DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api"
private const val JWT_CLAIM_PATH = "https://api.openai.com/auth"
private const val DEFAULT_MAX_RETRIES = 0
private const val BASE_DELAY_MS = 1000L
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private const val OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64
private const val DEFAULT_CODEX_INSTRUCTIONS = "You are a helpful assistant."
private const val DEFAULT_CODEX_TEXT_VERBOSITY = "low"

private val CODEX_TOOL_CALL_PROVIDERS = setOf("openai", "openai-codex", "opencode")
private val CODEX_RESPONSE_STATUSES =
    setOf("completed", "incomplete", "failed", "cancelled", "queued", "in_progress")
private val TERMINAL_CODEX_RESPONSE_TYPES = setOf("response.done", "response.completed", "response.incomplete")
private val NON_RETRYABLE_CODEX_RATE_LIMIT_PATTERN =
    Regex("GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|insufficient_quota|out of budget|quota exceeded|billing", RegexOption.IGNORE_CASE)
private val RETRYABLE_CODEX_ERROR_PATTERN =
    Regex("rate.?limit|overloaded|service.?unavailable|upstream.?connect|connection.?refused", RegexOption.IGNORE_CASE)
private val USAGE_LIMIT_CODE_PATTERN = Regex("usage_limit_reached|usage_not_included|rate_limit_exceeded", RegexOption.IGNORE_CASE)

internal class CodexApiException(message: String) : RuntimeException(message)

internal class CodexRequestOptions(
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
    val serviceTier: String? = null,
    val textVerbosity: String? = null,
    val toolChoice: String? = null,
)

private class CodexCompat(
    val supportsStrictMode: Boolean = true,
    val supportsOpenAIGrammarTools: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val supportsMidConvoSystemMessages: Boolean = false,
)

private fun resolveCodexCompat(model: Model): CodexCompat {
    val compat = model.compat
    fun flag(name: String, default: Boolean): Boolean =
        (compat?.get(name) as? JsonPrimitive)?.booleanOrNull ?: default
    return CodexCompat(
        supportsStrictMode = flag("supportsStrictMode", true),
        supportsOpenAIGrammarTools = flag("supportsOpenAIGrammarTools", false),
        supportsAdditionalTools = flag("supportsAdditionalTools", false),
        supportsToolSearch = flag("supportsToolSearch", false),
        supportsMidConvoSystemMessages = flag("supportsMidConvoSystemMessages", false),
    )
}

class OpenAICodexResponsesApi(private val transport: HttpTransport) : ApiProvider {
    override val api: String = OPENAI_CODEX_RESPONSES_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = startStream(model, context, options, CodexRequestOptions())

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        requireCodexApiKey(model, options.stream)
        val base = buildBaseOptions(model, context, options)
        val clamped = options.reasoning?.let { clampThinkingLevel(model, it.toModelThinkingLevel()) }
        val reasoningEffort = when (clamped) {
            null, ModelThinkingLevel.Off -> null
            else -> clamped.jsonName()
        }
        val toolChoice = when (options.toolChoice) {
            ToolChoice.Auto -> "auto"
            ToolChoice.None -> "none"
            null -> null
        }
        return startStream(
            model,
            context,
            base,
            CodexRequestOptions(reasoningEffort = reasoningEffort, toolChoice = toolChoice),
        )
    }

    private suspend fun startStream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
        requestOptions: CodexRequestOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runCodexStream(transport, stream, model, context, options, requestOptions)
        }
        return stream
    }
}

fun openAICodexResponsesApi(transport: HttpTransport): ApiProvider = OpenAICodexResponsesApi(transport)

private fun requireCodexApiKey(model: Model, options: StreamOptions): String {
    val apiKey = options.apiKey
    if (apiKey.isNullOrEmpty()) {
        throw IllegalStateException("No API key for provider: ${model.provider}")
    }
    return apiKey
}

private suspend fun runCodexStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    requestOptions: CodexRequestOptions,
) {
    val signal = options.signal
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
        val apiKey = requireCodexApiKey(model, options)
        val accountId = extractCodexAccountId(apiKey)
        val compat = resolveCodexCompat(model)
        val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
        val grammarToolInputProperties = createResponsesGrammarToolInputProperties(
            getDeclaredTools(normalizedContext.messages),
            compat.supportsOpenAIGrammarTools,
        )
        val cacheSessionId = if (options.cacheRetention == CacheRetention.None) null else options.sessionId
        val codexSessionId = clampCodexPromptCacheKey(cacheSessionId)
        var body = buildCodexRequestBody(
            model,
            normalizedContext,
            options,
            requestOptions,
            codexSessionId,
            compat,
            grammarToolInputProperties,
        )
        val payload = options.onPayload?.invoke(body, model)
        if (payload is JsonObject) body = payload
        val headers = buildCodexRequestHeaders(model, options, apiKey, accountId, codexSessionId)
        signal?.throwIfAborted()
        val response = executeCodexRequest(transport, model, body, headers, options)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()
        val processor = ResponsesStreamProcessor(
            output = output,
            stream = stream,
            model = model,
            options = ResponsesStreamOptions(
                onProviderStreamEvent = null,
                serviceTier = requestOptions.serviceTier,
                grammarToolInputProperties = grammarToolInputProperties,
                applyServiceTierPricing = { usage, serviceTier ->
                    applyCodexServiceTierPricing(usage, resolveCodexServiceTier(serviceTier, requestOptions.serviceTier), model)
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
                options.onProviderStreamEvent?.let { hook ->
                    try {
                        hook(parsed, model)
                    } catch (error: Throwable) {
                        throw CodexApiException(error.message ?: error.toString())
                    }
                }
                val terminal = dispatchCodexEvent(parsed, output, processor)
                signal?.throwIfAborted()
                if (terminal) break
            }
            processor.finish()
        } finally {
            sse.close()
            response.close()
        }
        signal?.throwIfAborted()
        if (output.stopReason == StopReason.Pending) {
            throw CodexApiException("Codex stream ended without a stop reason")
        }
        if (output.stopReason == StopReason.Aborted || output.stopReason == StopReason.Error) {
            throw CodexApiException(output.errorMessage ?: "An unknown error occurred")
        }
        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        val aborted = error is RequestAbortedException || signal?.isAborted == true ||
            output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else formatCodexError(error)
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun dispatchCodexEvent(
    event: JsonObject,
    output: AssistantMessage,
    processor: ResponsesStreamProcessor,
): Boolean {
    val type = event.stringFieldValue("type")
    return when (type) {
        "error" -> {
            val details = extractCodexEventError(event)
            throw CodexApiException("Codex error: ${details.second ?: details.first ?: event.toString()}")
        }

        "response.failed" -> {
            val response = event.objectFieldValue("response")
            val error = response?.objectFieldValue("error")
            val message = error?.stringFieldValue("message") ?: "Codex response failed"
            throw CodexApiException(message)
        }

        in TERMINAL_CODEX_RESPONSE_TYPES -> {
            val mapped = mutableMapOf<String, JsonElement>()
            event.forEach { (key, value) -> mapped[key] = value }
            mapped["type"] = JsonPrimitive("response.completed")
            val response = event.objectFieldValue("response")
            if (response != null) {
                val endTurn = (response["end_turn"] as? JsonPrimitive)?.booleanOrNull
                if (endTurn != null) output.endTurn = endTurn
                val normalized = mutableMapOf<String, JsonElement>()
                response.forEach { (key, value) -> normalized[key] = value }
                normalized["status"] = normalizeCodexStatus(response["status"])
                mapped["response"] = JsonObject(normalized)
            }
            processor.handle(JsonObject(mapped))
            true
        }

        else -> {
            processor.handle(event)
            false
        }
    }
}

private fun extractCodexEventError(event: JsonObject): Pair<String?, String?> {
    val nested = event.objectFieldValue("error")
    val code = event.stringFieldValue("code") ?: nested?.stringFieldValue("code")
    val message = event.stringFieldValue("message") ?: nested?.stringFieldValue("message")
    return code to message
}

private fun normalizeCodexStatus(status: JsonElement?): JsonElement {
    val value = (status as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (value != null && value in CODEX_RESPONSE_STATUSES) JsonPrimitive(value) else JsonNull
}

private fun buildCodexRequestBody(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    requestOptions: CodexRequestOptions,
    codexSessionId: String?,
    compat: CodexCompat,
    grammarToolInputProperties: Map<String, String>,
): JsonObject {
    val transcriptSupport = when (
        responsesToolSupport(
            compat.supportsMidConvoSystemMessages,
            compat.supportsAdditionalTools,
            compat.supportsToolSearch,
        )
    ) {
        ResponsesToolSupport.None -> TranscriptToolSupport.None
        ResponsesToolSupport.Additions -> TranscriptToolSupport.Additions
        ResponsesToolSupport.Changes -> TranscriptToolSupport.Changes
    }
    val transcriptTools = resolveTranscriptTools(context.messages, transcriptSupport)
    val toolOptions = ConvertResponsesToolsOptions(
        strict = null,
        supportsStrictMode = compat.supportsStrictMode,
        supportsOpenAIGrammarTools = compat.supportsOpenAIGrammarTools,
    )
    val messages = convertResponsesMessages(
        model = model,
        context = context,
        allowedToolCallProviders = CODEX_TOOL_CALL_PROVIDERS,
        options = ConvertResponsesMessagesOptions(
            includeSystemPrompt = false,
            grammarToolInputProperties = grammarToolInputProperties,
            supportsMidConvoSystemMessages = compat.supportsMidConvoSystemMessages,
            supportsAdditionalTools = compat.supportsAdditionalTools,
            supportsToolSearch = compat.supportsToolSearch,
            toolOptions = toolOptions,
        ),
    )
    val initialSystemMessage = getInitialSystemMessage(context.messages)
    val instructions = initialSystemMessage?.let(::getSystemMessageText).orEmpty()
    val params = LinkedHashMap<String, JsonElement>()
    params["model"] = JsonPrimitive(model.id)
    params["store"] = JsonPrimitive(false)
    params["stream"] = JsonPrimitive(true)
    params["instructions"] = JsonPrimitive(instructions.ifEmpty { DEFAULT_CODEX_INSTRUCTIONS })
    params["input"] = messages
    params["text"] = buildJsonObject {
        put("verbosity", requestOptions.textVerbosity ?: DEFAULT_CODEX_TEXT_VERBOSITY)
    }
    params["include"] = JsonArray(listOf(JsonPrimitive("reasoning.encrypted_content")))
    if (codexSessionId != null) {
        params["prompt_cache_key"] = JsonPrimitive(codexSessionId)
    }
    params["tool_choice"] = JsonPrimitive(requestOptions.toolChoice ?: "auto")
    params["parallel_tool_calls"] = JsonPrimitive(true)
    options.temperature?.let { params["temperature"] = JsonPrimitive(it) }
    requestOptions.serviceTier?.let { params["service_tier"] = JsonPrimitive(it) }
    if (transcriptTools.requestTools.isNotEmpty()) {
        params["tools"] = convertResponsesTools(transcriptTools.requestTools, toolOptions)
    }
    buildCodexReasoning(model, requestOptions)?.let { params["reasoning"] = it }
    return JsonObject(params)
}

private fun buildCodexReasoning(model: Model, requestOptions: CodexRequestOptions): JsonObject? {
    val requested = requestOptions.reasoningEffort
    if (requested != null) {
        val effort = if (requested == "none") {
            model.thinkingLevelMap?.get("off") ?: "none"
        } else {
            model.thinkingLevelMap?.get(requested) ?: requested
        }
        if (effort == null) return null
        return buildJsonObject {
            put("effort", effort)
            put("summary", requestOptions.reasoningSummary ?: "auto")
        }
    }
    if (model.reasoning && (model.thinkingLevelMap == null || model.thinkingLevelMap?.get("off") != null)) {
        return buildJsonObject { put("effort", model.thinkingLevelMap?.get("off") ?: "none") }
    }
    return null
}

private fun buildCodexRequestHeaders(
    model: Model,
    options: StreamOptions,
    apiKey: String,
    accountId: String,
    sessionId: String?,
): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    codexHeaderSet(headers, "originator", "pi")
    codexHeaderSet(headers, "User-Agent", options.identity?.userAgent ?: "pi")
    model.headers?.forEach { (name, value) -> codexHeaderSet(headers, name, value) }
    options.headers?.forEach { (name, value) ->
        if (value != null) codexHeaderSet(headers, name, value) else codexHeaderRemove(headers, name)
    }
    codexHeaderSet(headers, "Authorization", "Bearer $apiKey")
    codexHeaderSet(headers, "chatgpt-account-id", accountId)
    codexHeaderSet(headers, "OpenAI-Beta", "responses=experimental")
    codexHeaderSet(headers, "accept", "text/event-stream")
    codexHeaderSet(headers, "content-type", "application/json")
    if (sessionId != null) {
        codexHeaderSet(headers, "session-id", sessionId)
        codexHeaderSet(headers, "x-client-request-id", sessionId)
    }
    return headers
}

private fun codexHeaderSet(headers: MutableMap<String, String>, name: String, value: String) {
    codexHeaderRemove(headers, name)
    headers[name] = value
}

private fun codexHeaderRemove(headers: MutableMap<String, String>, name: String) {
    val keys = headers.keys.filter { it.equals(name, ignoreCase = true) }
    for (key in keys) headers.remove(key)
}

private fun resolveCodexUrl(baseUrl: String?): String {
    val raw = if (baseUrl.isNullOrBlank()) DEFAULT_CODEX_BASE_URL else baseUrl.trim()
    val normalized = raw.trimEnd('/')
    return when {
        normalized.endsWith("/codex/responses") -> normalized
        normalized.endsWith("/codex") -> "$normalized/responses"
        else -> "$normalized/codex/responses"
    }
}

private suspend fun executeCodexRequest(
    transport: HttpTransport,
    model: Model,
    params: JsonObject,
    headers: Map<String, String>,
    options: StreamOptions,
): HttpResponse {
    val signal = options.signal
    val url = resolveCodexUrl(model.baseUrl)
    val body = PiJson.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
    val maxRetries = options.maxRetries ?: DEFAULT_MAX_RETRIES
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt <= maxRetries) {
        signal?.throwIfAborted()
        val response = try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = url,
                    headers = headers,
                    body = body,
                    timeoutMs = options.timeoutMs,
                    signal = signal,
                ),
            )
        } catch (error: HttpTransportException) {
            if (signal?.isAborted == true) throw RequestAbortedException("Request was aborted")
            lastError = error
            if (attempt >= maxRetries) throw error
            val delayMs = retryDelayMs(options.maxRetryDelayMs, attempt)
            Retry.sleep(delayMs, signal)
            attempt++
            continue
        }
        options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        if (response.isSuccessful()) return response
        val errorText = try {
            response.body.readUtf8()
        } catch (_: Throwable) {
            ""
        } finally {
            response.close()
        }
        if (attempt < maxRetries && isRetryableCodexError(response.status, errorText)) {
            val retryAfterDelay = retryAfterDelayMs(response.headers, options)
            val delayMs = retryAfterDelay ?: (BASE_DELAY_MS shl attempt.coerceAtMost(20))
            Retry.sleep(delayMs, signal)
            attempt++
            continue
        }
        throw CodexApiException(parseCodexErrorMessage(response.status, errorText))
    }
    throw lastError ?: CodexApiException("Failed after retries")
}

private fun isRetryableCodexError(status: Int, errorText: String): Boolean {
    if (status == 429 && NON_RETRYABLE_CODEX_RATE_LIMIT_PATTERN.containsMatchIn(errorText)) return false
    if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) return true
    return RETRYABLE_CODEX_ERROR_PATTERN.containsMatchIn(errorText)
}

private fun retryAfterDelayMs(headers: Map<String, String>, options: StreamOptions): Long? {
    val header = { name: String ->
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }
    header("retry-after-ms")?.toDoubleOrNull()?.let { millis ->
        return validateCodexRetryDelayMs(millis.toLong().coerceAtLeast(0), options.maxRetryDelayMs)
    }
    header("retry-after")?.toDoubleOrNull()?.let { seconds ->
        return validateCodexRetryDelayMs((seconds * 1000).toLong().coerceAtLeast(0), options.maxRetryDelayMs)
    }
    return null
}

private fun validateCodexRetryDelayMs(delayMs: Long, maxRetryDelayMs: Long?): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw CodexApiException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s)",
        )
    }
    return delayMs
}

private fun retryDelayMs(maxRetryDelayMs: Long?, attempt: Int): Long {
    val delayMs = BASE_DELAY_MS shl attempt.coerceAtMost(20)
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw CodexApiException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s)",
        )
    }
    return delayMs
}

private fun parseCodexErrorMessage(status: Int, rawBody: String): String {
    val raw = rawBody.trim()
    var message = raw.ifEmpty { "HTTP $status" }
    var friendlyMessage: String? = null
    try {
        val parsed = PiJson.parseToJsonElement(raw) as? JsonObject
        val error = parsed?.objectFieldValue("error")
        if (error != null) {
            val code = error.stringFieldValue("code") ?: error.stringFieldValue("type") ?: ""
            if (USAGE_LIMIT_CODE_PATTERN.containsMatchIn(code) || status == 429) {
                val plan = error.stringFieldValue("plan_type")?.let { " (${it.lowercase()} plan)" } ?: ""
                val resetsAt = (error["resets_at"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                val minutes = resetsAt?.let {
                    maxOf(0L, kotlin.math.round((it * 1000 - nowMillis()) / 60_000.0).toLong())
                }
                val whenText = if (minutes != null) " Try again in ~$minutes min." else ""
                friendlyMessage = "You have hit your ChatGPT usage limit$plan.$whenText".trim()
            }
            message = error.stringFieldValue("message") ?: friendlyMessage ?: message
        }
    } catch (_: Throwable) {
    }
    return friendlyMessage ?: message
}

private fun formatCodexError(error: Throwable): String {
    val message = error.message ?: error.toString()
    if (message.contains("subscription_sharing_usage_limit_exceeded")) {
        return "$message\nCheck your ChatGPT usage: https://chatgpt.com/settings/usage"
    }
    if (message.contains("subscription_sharing_workplace_individual_usage_limit_exceeded")) {
        return "$message\nYou've reached your ChatGPT workspace's usage limit. " +
            "Ask your workspace admin to increase it at admin.openai.com \u2192 Workspace \u2192 Usage limits."
    }
    return message
}

private fun extractCodexAccountId(token: String): String {
    val parts = token.split(".")
    if (parts.size != 3) throw CodexApiException("Failed to extract accountId from token")
    val payloadText = try {
        decodeCodexBase64Url(parts[1]).decodeToString()
    } catch (_: Throwable) {
        throw CodexApiException("Failed to extract accountId from token")
    }
    val payload = try {
        PiJson.parseToJsonElement(payloadText) as? JsonObject
    } catch (_: Throwable) {
        null
    } ?: throw CodexApiException("Failed to extract accountId from token")
    val claim = payload[JWT_CLAIM_PATH] as? JsonObject
    val accountId = claim?.stringFieldValue("chatgpt_account_id")
    if (accountId.isNullOrEmpty()) throw CodexApiException("Failed to extract accountId from token")
    return accountId
}

private fun decodeCodexBase64Url(input: String): ByteArray {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val output = ArrayList<Byte>((input.length * 3) / 4)
    var buffer = 0
    var bits = 0
    for (char in input) {
        if (char == '=') break
        val value = alphabet.indexOf(char)
        if (value < 0) throw CodexApiException("Failed to extract accountId from token")
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return output.toByteArray()
}

private fun clampCodexPromptCacheKey(key: String?): String? {
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

private fun getCodexServiceTierCostMultiplier(model: Model, serviceTier: String?): Double = when (serviceTier) {
    "flex" -> 0.5
    "priority", "fast" -> if (model.id == "gpt-5.5") 2.5 else 2.0
    else -> 1.0
}

private fun resolveCodexServiceTier(responseServiceTier: String?, requestServiceTier: String?): String? {
    if (responseServiceTier == "default" && (requestServiceTier == "flex" || requestServiceTier == "priority")) {
        return requestServiceTier
    }
    return responseServiceTier ?: requestServiceTier
}

private fun applyCodexServiceTierPricing(usage: dev.pi.ai.Usage, serviceTier: String?, model: Model) {
    val multiplier = getCodexServiceTierCostMultiplier(model, serviceTier)
    if (multiplier == 1.0) return
    usage.cost.input *= multiplier
    usage.cost.output *= multiplier
    usage.cost.cacheRead *= multiplier
    usage.cost.cacheWrite *= multiplier
    usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite
}
