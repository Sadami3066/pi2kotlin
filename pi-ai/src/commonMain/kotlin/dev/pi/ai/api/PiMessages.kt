package dev.pi.ai.api

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageDiagnostic
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.CacheRetention
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DiagnosticErrorInfo
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.PiJson
import dev.pi.ai.ProviderResponse
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingDeltaEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingLevel
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.ToolChoice
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.utils.PartialJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.BufferedSource

const val DEFAULT_RADIUS_GATEWAY = "https://radius.pi.dev"

fun normalizeRadiusGatewayUrl(value: String): String {
    val trimmed = value.trim()
    val lower = trimmed.lowercase()
    val withScheme = if (lower.startsWith("http://") || lower.startsWith("https://")) trimmed else "https://$trimmed"
    return withScheme.trimEnd('/')
}

private const val DIAGNOSTIC_STRING_MAX_LENGTH = 8192

class PiMessagesApi internal constructor(
    private val transport: HttpTransport,
    private val debug: Boolean = false,
) : ApiProvider {
    override val api: String = PI_MESSAGES_API_NAME

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = startStream(
        model,
        context,
        PiMessagesRequestOptions(options, null, null),
        debug,
    )

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream = startStream(
        model,
        context,
        PiMessagesRequestOptions(options.stream, options.reasoning, options.toolChoice),
        debug,
    )

    private suspend fun startStream(
        model: Model,
        context: TranscriptContext,
        options: PiMessagesRequestOptions,
        debug: Boolean,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runPiMessagesStream(transport, stream, model, context, options, debug)
        }
        return stream
    }
}

fun piMessagesApi(transport: HttpTransport, debug: Boolean = false): ApiProvider =
    PiMessagesApi(transport, debug)

private class PiMessagesRequestOptions(
    val stream: StreamOptions,
    val reasoning: ThinkingLevel?,
    val toolChoice: ToolChoice?,
)

private class PiMessagesStreamException(message: String) : RuntimeException(message)

private class PiMessagesResponseException(
    message: String,
    val code: String?,
    val diagnosticDetails: JsonObject,
) : RuntimeException(message)

private class PiMessagesBlockReader(private val body: BufferedSource) {
    private val buffer = StringBuilder()

    fun next(): String? {
        while (true) {
            val line = body.readUtf8Line()
            if (line == null) {
                if (buffer.isBlank()) return null
                val block = buffer.toString()
                buffer.setLength(0)
                return block
            }
            if (line.isEmpty()) {
                if (buffer.isNotBlank()) {
                    val block = buffer.toString()
                    buffer.setLength(0)
                    return block
                }
                continue
            }
            buffer.append(line).append('\n')
        }
    }
}

private class PiMessagesEventConverter(
    private val model: Model,
    private val partial: AssistantMessage,
) {
    private val toolJson = mutableMapOf<Int, String>()

    fun convert(event: PiMessagesEvent): AssistantMessageEvent = when (event) {
        PiMessagesEvent.Start -> StartEvent(partial)

        is PiMessagesEvent.TextStart -> {
            setContent(event.contentIndex, TextContent(""))
            TextStartEvent(event.contentIndex, partial)
        }

        is PiMessagesEvent.TextDelta -> {
            textContentAt(event.contentIndex).text += event.delta
            TextDeltaEvent(event.contentIndex, event.delta, partial)
        }

        is PiMessagesEvent.TextEnd -> {
            val content = textContentAt(event.contentIndex)
            content.text = event.content
            content.textSignature = event.contentSignature
            TextEndEvent(event.contentIndex, event.content, partial)
        }

        is PiMessagesEvent.ThinkingStart -> {
            setContent(event.contentIndex, ThinkingContent(""))
            ThinkingStartEvent(event.contentIndex, partial)
        }

        is PiMessagesEvent.ThinkingDelta -> {
            thinkingContentAt(event.contentIndex).thinking += event.delta
            ThinkingDeltaEvent(event.contentIndex, event.delta, partial)
        }

        is PiMessagesEvent.ThinkingEnd -> {
            val content = thinkingContentAt(event.contentIndex)
            content.thinking = event.content
            content.thinkingSignature = event.contentSignature
            content.redacted = event.redacted
            ThinkingEndEvent(event.contentIndex, event.content, partial)
        }

        is PiMessagesEvent.ToolCallStart -> {
            setContent(
                event.contentIndex,
                ToolCall(id = event.id, name = event.toolName, arguments = JsonObject(emptyMap())),
            )
            toolJson[event.contentIndex] = ""
            ToolCallStartEvent(event.contentIndex, partial)
        }

        is PiMessagesEvent.ToolCallDelta -> {
            val content = toolCallAt(event.contentIndex)
            val json = (toolJson[event.contentIndex] ?: "") + event.delta
            toolJson[event.contentIndex] = json
            content.arguments = parseStreamingJsonObject(json)
            ToolCallDeltaEvent(event.contentIndex, event.delta, partial)
        }

        is PiMessagesEvent.ToolCallEnd -> {
            val existing = toolCallAt(event.contentIndex)
            val merged = existing.copy(
                id = event.toolCall.id,
                name = event.toolCall.name,
                arguments = event.toolCall.arguments,
                thoughtSignature = event.toolCall.thoughtSignature ?: existing.thoughtSignature,
                namespace = event.toolCall.namespace ?: existing.namespace,
                argumentsError = event.toolCall.argumentsError ?: existing.argumentsError,
            )
            partial.content[event.contentIndex] = merged
            toolJson.remove(event.contentIndex)
            ToolCallEndEvent(event.contentIndex, merged, partial)
        }

        is PiMessagesEvent.Done -> {
            partial.stopReason = event.reason
            partial.usage = event.usage
            partial.responseId = event.responseId
            if (event.providerThinkingLevel != null) {
                partial.providerThinkingLevel = event.providerThinkingLevel
            }
            appendRewriteDiagnostic(partial, event.rewrite)
            DoneEvent(event.reason, partial)
        }

        is PiMessagesEvent.Error -> {
            partial.stopReason = event.reason
            partial.usage = event.usage
            partial.errorMessage = event.errorMessage
            partial.responseId = event.responseId
            if (event.providerThinkingLevel != null) {
                partial.providerThinkingLevel = event.providerThinkingLevel
            }
            appendRewriteDiagnostic(partial, event.rewrite)
            ErrorEvent(event.reason, partial)
        }
    }

    private fun setContent(index: Int, content: dev.pi.ai.Content) {
        while (partial.content.size <= index) {
            partial.content.add(TextContent(""))
        }
        partial.content[index] = content
    }

    private fun textContentAt(index: Int): TextContent =
        partial.content.getOrNull(index) as? TextContent
            ?: throw PiMessagesStreamException("Received text delta for non-text content at index $index")

    private fun thinkingContentAt(index: Int): ThinkingContent =
        partial.content.getOrNull(index) as? ThinkingContent
            ?: throw PiMessagesStreamException("Received thinking delta for non-thinking content at index $index")

    private fun toolCallAt(index: Int): ToolCall =
        partial.content.getOrNull(index) as? ToolCall
            ?: throw PiMessagesStreamException("Received toolcall event for non-toolCall content at index $index")
}

private fun parseStreamingJsonObject(json: String): JsonObject =
    (PartialJson.parse(json) as? JsonObject) ?: JsonObject(emptyMap())

private fun appendRewriteDiagnostic(message: AssistantMessage, rewrite: PiMessagesRewriteImpact?) {
    if (rewrite == null) return
    val diagnostics = message.diagnostics ?: mutableListOf<AssistantMessageDiagnostic>().also {
        message.diagnostics = it
    }
    diagnostics.add(
        AssistantMessageDiagnostic(
            type = "pi_messages_rewrite",
            timestamp = platformCurrentTimeMillis(),
            details = rewrite.toJson(),
        ),
    )
}

private fun createEmptyUsage(): Usage = Usage.empty()

private fun createFailureMessage(
    model: Model,
    message: String,
    aborted: Boolean,
    diagnostic: AssistantMessageDiagnostic?,
): AssistantMessage {
    val failure = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = createEmptyUsage(),
        stopReason = if (aborted) StopReason.Aborted else StopReason.Error,
        errorMessage = message,
        timestamp = platformCurrentTimeMillis(),
    )
    if (diagnostic != null) failure.diagnostics = mutableListOf(diagnostic)
    return failure
}

private suspend fun runPiMessagesStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: PiMessagesRequestOptions,
    debug: Boolean,
) {
    val signal = options.stream.signal
    val partial = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = createEmptyUsage(),
        stopReason = StopReason.Pending,
        timestamp = platformCurrentTimeMillis(),
    )
    val converter = PiMessagesEventConverter(model, partial)
    val url = piMessagesUrl(model, debug)
    var response: HttpResponse? = null
    val abortHandle = signal?.onAbort {
        response?.let { current -> runCatching { current.close() } }
    }
    try {
        val apiKey = options.stream.apiKey
            ?: throw PiMessagesStreamException("No API key provided for provider \"${model.provider}\"")

        var payload: JsonElement = buildPiMessagesPayload(model, context, options)
        val replaced = options.stream.onPayload?.invoke(payload, model)
        if (replaced != null) payload = replaced

        val headers = mergeProviderHeaders(
            identityHeaders(model, options.stream.identity),
            mapOf(
                "authorization" to "Bearer $apiKey",
                "accept" to "text/event-stream",
                "content-type" to "application/json",
            ),
            options.stream.headers,
        )
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = PiJson.encodeToString(JsonElement.serializer(), payload).encodeToByteArray(),
        )
        signal?.throwIfAborted()

        val httpResponse = transport.execute(request)
        response = httpResponse
        options.stream.onResponse?.invoke(ProviderResponse(httpResponse.status, httpResponse.headers), model)

        if (!httpResponse.isSuccessful()) {
            val body = runCatching { httpResponse.body.readUtf8() }.getOrDefault("")
            throw createResponseException(model, url, httpResponse, body)
        }
        if (options.stream.signal?.isAborted == true) throw RequestAbortedException("Request was aborted")

        val reader = PiMessagesBlockReader(httpResponse.body)
        while (true) {
            val block = reader.next() ?: break
            val rawEvent = parsePiMessagesEventBlock(block) ?: continue
            options.stream.onProviderStreamEvent?.invoke(rawEvent, model)
            val event = decodePiMessagesEvent(rawEvent)
            val converted = converter.convert(event)
            stream.push(converted)
            signal?.throwIfAborted()
            if (converted is DoneEvent || converted is ErrorEvent) return
        }

        throw PiMessagesStreamException("${model.provider} stream ended without a terminal event")
    } catch (error: Throwable) {
        val aborted = signal?.isAborted == true ||
            error is RequestAbortedException ||
            partial.stopReason == StopReason.Aborted
        val errorMessage = if (aborted) "Request was aborted" else (error.message ?: error.toString())
        val diagnostic = if (!aborted && error is PiMessagesResponseException) {
            AssistantMessageDiagnostic(
                type = "pi_messages_response_failure",
                timestamp = platformCurrentTimeMillis(),
                error = DiagnosticErrorInfo(
                    name = "PiMessagesResponseException",
                    message = error.message ?: "",
                    code = error.code,
                ),
                details = error.diagnosticDetails,
            )
        } else {
            null
        }
        val failure = createFailureMessage(model, errorMessage, aborted, diagnostic)
        stream.push(ErrorEvent(failure.stopReason, failure))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    } finally {
        abortHandle?.invoke()
        response?.let { current -> runCatching { current.close() } }
    }
}

private fun buildPiMessagesPayload(
    model: Model,
    context: TranscriptContext,
    options: PiMessagesRequestOptions,
): JsonObject = buildJsonObject {
    put("model", model.id)
    put(
        "context",
        buildJsonObject {
            put(
                "messages",
                PiJson.encodeToJsonElement(ListSerializer(Message.serializer()), context.messages),
            )
        },
    )
    put(
        "options",
        buildJsonObject {
            options.stream.temperature?.let { put("temperature", JsonPrimitive(it)) }
            options.stream.maxTokens?.let { put("maxTokens", JsonPrimitive(it)) }
            options.reasoning?.let {
                put("reasoning", PiJson.encodeToJsonElement(ThinkingLevel.serializer(), it))
            }
            resolvePiMessagesCacheRetention(options.stream.cacheRetention, options.stream.env)?.let {
                put("cacheRetention", PiJson.encodeToJsonElement(CacheRetention.serializer(), it))
            }
            options.stream.sessionId?.let { put("sessionId", JsonPrimitive(it)) }
            options.toolChoice?.let {
                put("toolChoice", PiJson.encodeToJsonElement(ToolChoice.serializer(), it))
            }
        },
    )
}

private fun piMessagesUrl(model: Model, debug: Boolean): String {
    val base = model.baseUrl.trimEnd('/') + "/messages"
    return if (debug) "$base?debug=1" else base
}

private fun resolvePiMessagesCacheRetention(cacheRetention: CacheRetention?, env: Map<String, String>?): CacheRetention? {
    if (cacheRetention != null) return cacheRetention
    return if (env?.get("PI_CACHE_RETENTION") == "long") CacheRetention.Long else null
}

private fun identityHeaders(model: Model, identity: ClientIdentity?): Map<String, String> {
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

private fun mergeProviderHeaders(vararg sources: Map<String, String?>?): Map<String, String> {
    val merged = LinkedHashMap<String, Pair<String, String>>()
    for (source in sources) {
        if (source == null) continue
        for ((name, value) in source) {
            val normalized = name.lowercase()
            merged.remove(normalized)
            if (value != null) merged[normalized] = name to value
        }
    }
    return merged.values.associate { (name, value) -> name to value }
}

private fun parsePiMessagesEventBlock(block: String): JsonElement? {
    var data: String? = null
    for (line in block.split("\n")) {
        if (line.startsWith("data:")) {
            data = line.substring(5).trim()
            break
        }
    }
    val value = data
    if (value.isNullOrEmpty() || value == "[DONE]") return null
    return try {
        PiJson.parseToJsonElement(value)
    } catch (error: Throwable) {
        throw PiMessagesStreamException("Invalid pi-messages event: ${error.message ?: "malformed JSON"}")
    }
}

private fun decodePiMessagesEvent(raw: JsonElement): PiMessagesEvent = try {
    PiJson.decodeFromJsonElement(PiMessagesEvent.serializer(), raw)
} catch (error: Throwable) {
    throw PiMessagesStreamException("Invalid pi-messages event: ${error.message ?: "malformed event"}")
}

private fun createResponseException(
    model: Model,
    url: String,
    response: HttpResponse,
    body: String,
): PiMessagesResponseException {
    val errorPayload = parseErrorPayload(body)
    val errorBodyElement = if (errorPayload == null) null else {
        (PiJson.parseToJsonElement(body) as? JsonObject)?.get("error")
    }
    val statusText = httpStatusText(response.status)
    val message = errorPayload?.message?.piStringOrNull()
    val code = errorPayload?.code?.piStringOrNull()
    val suffix = message ?: body
    val codeSuffix = if (code != null) " ($code)" else ""
    val formatted = buildString {
        append(response.status)
        if (statusText.isNotEmpty()) append(' ').append(statusText)
        append(": ").append(suffix).append(codeSuffix)
    }
    val details = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("provider", JsonPrimitive(model.provider))
        put("model", JsonPrimitive(model.id))
        put("url", JsonPrimitive(url))
        put("status", JsonPrimitive(response.status))
        put("statusText", JsonPrimitive(statusText))
        if (errorBodyElement != null) put("error", errorBodyElement)
        if (errorPayload == null) put("body", JsonPrimitive(truncateDiagnosticString(body)))
        put("timestampMs", JsonPrimitive(platformCurrentTimeMillis()))
    }
    return PiMessagesResponseException(formatted, code, details)
}

private fun parseErrorPayload(body: String): PiMessagesErrorPayload? {
    if (body.isBlank()) return null
    val element = try {
        PiJson.parseToJsonElement(body)
    } catch (_: Throwable) {
        return null
    }
    val errorElement = (element as? JsonObject)?.get("error") ?: return null
    if (errorElement !is JsonObject) return null
    return try {
        PiJson.decodeFromJsonElement(PiMessagesErrorPayload.serializer(), errorElement)
    } catch (_: Throwable) {
        null
    }
}

private fun truncateDiagnosticString(value: String): String =
    if (value.length > DIAGNOSTIC_STRING_MAX_LENGTH) value.take(DIAGNOSTIC_STRING_MAX_LENGTH) + "…" else value

private fun httpStatusText(status: Int): String = when (status) {
    200 -> "OK"
    201 -> "Created"
    202 -> "Accepted"
    204 -> "No Content"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    307 -> "Temporary Redirect"
    308 -> "Permanent Redirect"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    402 -> "Payment Required"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    406 -> "Not Acceptable"
    408 -> "Request Timeout"
    409 -> "Conflict"
    410 -> "Gone"
    413 -> "Content Too Large"
    415 -> "Unsupported Media Type"
    422 -> "Unprocessable Content"
    429 -> "Too Many Requests"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    502 -> "Bad Gateway"
    503 -> "Service Unavailable"
    504 -> "Gateway Timeout"
    else -> ""
}
