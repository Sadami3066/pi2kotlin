package dev.pi.agent

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.CacheRetention
import dev.pi.ai.Content
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.PiJson
import dev.pi.ai.RunSignal
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingDeltaEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.TranscriptContext
import dev.pi.ai.TextContent
import dev.pi.ai.Transport
import dev.pi.ai.Usage
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.utils.PartialJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ProxyStreamOptions(
    val authToken: String,
    val proxyUrl: String,
    val http: HttpTransport,
    val signal: RunSignal? = null,
    val temperature: Double? = null,
    val samplingParams: JsonObject? = null,
    val maxTokens: Long? = null,
    val reasoning: dev.pi.ai.ThinkingLevel? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val headers: Map<String, String?>? = null,
    val metadata: JsonObject? = null,
    val transport: Transport? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val maxRetryDelayMs: Long? = null,
)

fun streamProxy(
    scope: CoroutineScope,
    model: Model,
    context: TranscriptContext,
    options: ProxyStreamOptions,
): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    scope.launch { runProxyRequest(model, context, options, stream) }
    return stream
}

fun proxyStreamFn(scope: CoroutineScope, options: ProxyStreamOptions): StreamFn =
    { model, context, streamOptions ->
        streamProxy(
            scope,
            model,
            context,
            options.copy(
                signal = options.signal ?: streamOptions?.stream?.signal,
                temperature = options.temperature ?: streamOptions?.stream?.temperature,
                samplingParams = options.samplingParams ?: streamOptions?.stream?.samplingParams,
                maxTokens = options.maxTokens ?: streamOptions?.stream?.maxTokens,
                reasoning = options.reasoning ?: streamOptions?.reasoning,
                cacheRetention = options.cacheRetention ?: streamOptions?.stream?.cacheRetention,
                sessionId = options.sessionId ?: streamOptions?.stream?.sessionId,
                headers = options.headers ?: streamOptions?.stream?.headers,
                metadata = options.metadata ?: streamOptions?.stream?.metadata,
                transport = options.transport ?: streamOptions?.stream?.transport,
                thinkingBudgets = options.thinkingBudgets ?: streamOptions?.thinkingBudgets,
                maxRetryDelayMs = options.maxRetryDelayMs ?: streamOptions?.stream?.maxRetryDelayMs,
            ),
        )
    }

private suspend fun runProxyRequest(
    model: Model,
    context: TranscriptContext,
    options: ProxyStreamOptions,
    stream: AssistantMessageEventStream,
) {
    val partial = createPartialAssistantMessage(model)
    val partialJson = mutableMapOf<Int, String>()
    var sawTerminalEvent = false
    var response: HttpResponse? = null

    suspend fun processLine(line: String) {
        if (!line.startsWith("data: ")) return
        val data = line.removePrefix("data: ").trim()
        if (data.isEmpty()) return
        val proxyEvent = parseProxyEvent(PiJson.parseToJsonElement(data)) ?: return
        val event = processProxyEvent(proxyEvent, partial, partialJson) ?: return
        if (event is DoneEvent || event is ErrorEvent) sawTerminalEvent = true
        stream.push(event)
    }

    val abortHandle = options.signal?.onAbort {
        response?.let { current ->
            runCatching { current.close() }
        }
    }

    try {
        val request = HttpRequest(
            method = "POST",
            url = options.proxyUrl.trimEnd('/') + "/api/stream",
            headers = mapOf(
                "Authorization" to "Bearer ${options.authToken}",
                "Content-Type" to "application/json",
            ),
            body = buildProxyRequestBody(model, context, options).toString().encodeToByteArray(),
        )
        val httpResponse = options.http.execute(request)
        response = httpResponse

        if (!httpResponse.isSuccessful()) {
            throw IllegalStateException(proxyErrorMessage(httpResponse))
        }

        while (true) {
            if (options.signal?.isAborted == true) throw IllegalStateException("Request aborted by user")
            val line = try {
                httpResponse.body.readUtf8Line()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (options.signal?.isAborted == true) throw IllegalStateException("Request aborted by user")
                throw error
            } ?: break
            processLine(line)
        }

        if (options.signal?.isAborted == true) throw IllegalStateException("Request aborted by user")

        if (!sawTerminalEvent) {
            partial.stopReason = StopReason.Error
            partial.errorMessage = "Connection closed by proxy server before the response completed"
            stream.push(ErrorEvent(StopReason.Error, partial))
        }
        stream.end()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val errorMessage = error.message ?: error.toString()
        val reason = if (options.signal?.isAborted == true) StopReason.Aborted else StopReason.Error
        partial.stopReason = reason
        partial.errorMessage = errorMessage
        stream.push(ErrorEvent(reason, partial))
        stream.end()
    } finally {
        abortHandle?.invoke()
        response?.let { current ->
            runCatching { current.close() }
        }
    }
}

private fun proxyErrorMessage(response: HttpResponse): String {
    val bodyText = runCatching { response.body.readUtf8() }.getOrNull()
    val parsedError = bodyText?.let { text ->
        runCatching {
            PiJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
    }
    return if (!parsedError.isNullOrEmpty()) "Proxy error: $parsedError" else "Proxy error: ${response.status}"
}

private fun createPartialAssistantMessage(model: Model): AssistantMessage = AssistantMessage(
    content = mutableListOf(),
    api = model.api,
    provider = model.provider,
    model = model.id,
    usage = Usage.empty(),
    stopReason = StopReason.Pending,
    timestamp = platformCurrentTimeMillis(),
)

private fun buildProxyRequestBody(
    model: Model,
    context: TranscriptContext,
    options: ProxyStreamOptions,
): JsonObject = buildJsonObject {
    put("model", PiJson.encodeToJsonElement(Model.serializer(), model))
    put(
        "context",
        buildJsonObject {
            put("messages", PiJson.encodeToJsonElement(ListSerializer(Message.serializer()), context.messages))
        },
    )
    put("options", buildProxyOptionsJson(options))
}

private fun buildProxyOptionsJson(options: ProxyStreamOptions): JsonObject = buildJsonObject {
    options.temperature?.let { put("temperature", it) }
    options.samplingParams?.let { put("samplingParams", it) }
    options.maxTokens?.let { put("maxTokens", it) }
    options.reasoning?.let { put("reasoning", PiJson.encodeToJsonElement(dev.pi.ai.ThinkingLevel.serializer(), it)) }
    options.cacheRetention?.let { put("cacheRetention", PiJson.encodeToJsonElement(CacheRetention.serializer(), it)) }
    options.sessionId?.let { put("sessionId", it) }
    options.headers?.let { headers ->
        put(
            "headers",
            buildJsonObject {
                headers.forEach { (name, value) -> put(name, value?.let { JsonPrimitive(it) } ?: JsonNull) }
            },
        )
    }
    options.metadata?.let { put("metadata", it) }
    options.transport?.let { put("transport", PiJson.encodeToJsonElement(Transport.serializer(), it)) }
    options.thinkingBudgets?.let { put("thinkingBudgets", PiJson.encodeToJsonElement(ThinkingBudgets.serializer(), it)) }
    options.maxRetryDelayMs?.let { put("maxRetryDelayMs", it) }
}

private fun parseProxyEvent(element: JsonElement): ProxyAssistantMessageEvent? {
    val obj = element.jsonObject
    val type = obj["type"]?.jsonPrimitive?.content
    return when (type) {
        "start" -> ProxyAssistantMessageEvent.Start
        "text_start" -> ProxyAssistantMessageEvent.TextStart(contentIndexOf(obj))
        "text_delta" -> ProxyAssistantMessageEvent.TextDelta(contentIndexOf(obj), stringOf(obj, "delta"))
        "text_end" -> ProxyAssistantMessageEvent.TextEnd(contentIndexOf(obj), optionalStringOf(obj, "contentSignature"))
        "thinking_start" -> ProxyAssistantMessageEvent.ThinkingStart(contentIndexOf(obj))
        "thinking_delta" -> ProxyAssistantMessageEvent.ThinkingDelta(contentIndexOf(obj), stringOf(obj, "delta"))
        "thinking_end" -> ProxyAssistantMessageEvent.ThinkingEnd(contentIndexOf(obj), optionalStringOf(obj, "contentSignature"))
        "toolcall_start" -> ProxyAssistantMessageEvent.ToolCallStart(
            contentIndexOf(obj),
            stringOf(obj, "id"),
            stringOf(obj, "toolName"),
        )

        "toolcall_delta" -> ProxyAssistantMessageEvent.ToolCallDelta(contentIndexOf(obj), stringOf(obj, "delta"))
        "toolcall_end" -> ProxyAssistantMessageEvent.ToolCallEnd(
            contentIndexOf(obj),
            parseToolCall(obj.getValue("toolCall")),
        )

        "done" -> ProxyAssistantMessageEvent.Done(
            reason = parseStopReason(obj.getValue("reason")),
            usage = PiJson.decodeFromJsonElement(Usage.serializer(), obj.getValue("usage")),
            providerThinkingLevel = optionalStringOf(obj, "providerThinkingLevel"),
        )

        "error" -> ProxyAssistantMessageEvent.Error(
            reason = parseStopReason(obj.getValue("reason")),
            errorMessage = optionalStringOf(obj, "errorMessage"),
            usage = PiJson.decodeFromJsonElement(Usage.serializer(), obj.getValue("usage")),
            providerThinkingLevel = optionalStringOf(obj, "providerThinkingLevel"),
        )

        else -> null
    }
}

private fun contentIndexOf(obj: JsonObject): Int = obj.getValue("contentIndex").jsonPrimitive.content.toInt()

private fun stringOf(obj: JsonObject, key: String): String = obj.getValue(key).jsonPrimitive.content

private fun optionalStringOf(obj: JsonObject, key: String): String? =
    obj[key]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }

private fun parseStopReason(element: JsonElement): StopReason =
    PiJson.decodeFromJsonElement(StopReason.serializer(), element)

private fun parseToolCall(element: JsonElement): ToolCall =
    PiJson.decodeFromJsonElement(Content.serializer(), element) as ToolCall

private fun processProxyEvent(
    proxyEvent: ProxyAssistantMessageEvent,
    partial: AssistantMessage,
    partialJson: MutableMap<Int, String>,
): AssistantMessageEvent? = when (proxyEvent) {
    is ProxyAssistantMessageEvent.Start -> StartEvent(partial)

    is ProxyAssistantMessageEvent.TextStart -> {
        setContent(partial, proxyEvent.contentIndex, TextContent(""))
        TextStartEvent(proxyEvent.contentIndex, partial)
    }

    is ProxyAssistantMessageEvent.TextDelta -> {
        val content = partial.content.getOrNull(proxyEvent.contentIndex)
        if (content !is TextContent) throw IllegalStateException("Received text_delta for non-text content")
        content.text += proxyEvent.delta
        TextDeltaEvent(proxyEvent.contentIndex, proxyEvent.delta, partial)
    }

    is ProxyAssistantMessageEvent.TextEnd -> {
        val content = partial.content.getOrNull(proxyEvent.contentIndex)
        if (content !is TextContent) throw IllegalStateException("Received text_end for non-text content")
        content.textSignature = proxyEvent.contentSignature
        TextEndEvent(proxyEvent.contentIndex, content.text, partial)
    }

    is ProxyAssistantMessageEvent.ThinkingStart -> {
        setContent(partial, proxyEvent.contentIndex, ThinkingContent(""))
        ThinkingStartEvent(proxyEvent.contentIndex, partial)
    }

    is ProxyAssistantMessageEvent.ThinkingDelta -> {
        val content = partial.content.getOrNull(proxyEvent.contentIndex)
        if (content !is ThinkingContent) throw IllegalStateException("Received thinking_delta for non-thinking content")
        content.thinking += proxyEvent.delta
        ThinkingDeltaEvent(proxyEvent.contentIndex, proxyEvent.delta, partial)
    }

    is ProxyAssistantMessageEvent.ThinkingEnd -> {
        val content = partial.content.getOrNull(proxyEvent.contentIndex)
        if (content !is ThinkingContent) throw IllegalStateException("Received thinking_end for non-thinking content")
        content.thinkingSignature = proxyEvent.contentSignature
        ThinkingEndEvent(proxyEvent.contentIndex, content.thinking, partial)
    }

    is ProxyAssistantMessageEvent.ToolCallStart -> {
        setContent(
            partial,
            proxyEvent.contentIndex,
            ToolCall(id = proxyEvent.id, name = proxyEvent.toolName, arguments = JsonObject(emptyMap())),
        )
        partialJson[proxyEvent.contentIndex] = ""
        ToolCallStartEvent(proxyEvent.contentIndex, partial)
    }

    is ProxyAssistantMessageEvent.ToolCallDelta -> {
        val content = partial.content.getOrNull(proxyEvent.contentIndex)
        if (content !is ToolCall) throw IllegalStateException("Received toolcall_delta for non-toolCall content")
        val accumulated = (partialJson[proxyEvent.contentIndex] ?: "") + proxyEvent.delta
        partialJson[proxyEvent.contentIndex] = accumulated
        content.arguments = parseStreamingJsonObject(accumulated)
        partial.content[proxyEvent.contentIndex] = content.copy()
        ToolCallDeltaEvent(proxyEvent.contentIndex, proxyEvent.delta, partial)
    }

    is ProxyAssistantMessageEvent.ToolCallEnd -> {
        val content = partial.content.getOrNull(proxyEvent.contentIndex)
        if (content !is ToolCall) {
            null
        } else {
            partialJson.remove(proxyEvent.contentIndex)
            partial.content[proxyEvent.contentIndex] = proxyEvent.toolCall
            ToolCallEndEvent(proxyEvent.contentIndex, proxyEvent.toolCall, partial)
        }
    }

    is ProxyAssistantMessageEvent.Done -> {
        partial.stopReason = proxyEvent.reason
        partial.usage = proxyEvent.usage
        if (proxyEvent.providerThinkingLevel != null) {
            partial.providerThinkingLevel = proxyEvent.providerThinkingLevel
        }
        DoneEvent(proxyEvent.reason, partial)
    }

    is ProxyAssistantMessageEvent.Error -> {
        partial.stopReason = proxyEvent.reason
        partial.errorMessage = proxyEvent.errorMessage
        partial.usage = proxyEvent.usage
        if (proxyEvent.providerThinkingLevel != null) {
            partial.providerThinkingLevel = proxyEvent.providerThinkingLevel
        }
        ErrorEvent(proxyEvent.reason, partial)
    }
}

private fun setContent(partial: AssistantMessage, index: Int, content: Content) {
    while (partial.content.size <= index) {
        partial.content.add(TextContent(""))
    }
    partial.content[index] = content
}

private fun parseStreamingJsonObject(text: String): JsonObject =
    (PartialJson.parse(text) as? JsonObject) ?: JsonObject(emptyMap())
