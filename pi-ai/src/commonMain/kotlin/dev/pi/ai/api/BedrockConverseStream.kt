package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageDiagnostic
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.CacheRetention
import dev.pi.ai.Content
import dev.pi.ai.DiagnosticErrorInfo
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PiJson
import dev.pi.ai.ProviderResponse
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingDeltaEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.ToolChoice
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.calculateCost
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.utils.PartialJson
import dev.pi.ai.utils.ProviderErrors
import dev.pi.ai.utils.Retry
import dev.pi.ai.utils.getCurrentTools
import dev.pi.ai.utils.getInitialSystemMessage
import dev.pi.ai.utils.getSystemMessageText
import dev.pi.ai.utils.withoutInitialSystemMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.utils.collapseSystemMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.io.encoding.Base64
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private const val BEDROCK_API = "bedrock-converse-stream"
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private const val EMPTY_TEXT_PLACEHOLDER = "<empty>"
private const val REDACTED_THINKING_PLACEHOLDER = "[Reasoning redacted]"
private const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"
private const val INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14"
private const val CACHE_POINT_TTL_ONE_HOUR = "1h"

private val UNSUPPORTED_STRICT_SCHEMA_KEYS = listOf(
    "\$ref",
    "\$defs",
    "definitions",
    "allOf",
    "oneOf",
    "patternProperties",
    "dependentSchemas",
    "dependencies",
    "unevaluatedProperties",
    "propertyNames",
    "contains",
    "prefixItems",
    "not",
    "if",
    "then",
    "else",
)

private val OPENAI_GPT_EFFORT = mapOf(
    ModelThinkingLevel.Minimal to "low",
    ModelThinkingLevel.Low to "low",
    ModelThinkingLevel.Medium to "medium",
    ModelThinkingLevel.High to "high",
    ModelThinkingLevel.Xhigh to "xhigh",
    ModelThinkingLevel.Max to "max",
)

private val OPENAI_GPT_OSS_EFFORT = mapOf(
    ModelThinkingLevel.Minimal to "low",
    ModelThinkingLevel.Low to "low",
    ModelThinkingLevel.Medium to "medium",
    ModelThinkingLevel.High to "high",
    ModelThinkingLevel.Xhigh to "high",
    ModelThinkingLevel.Max to "high",
)

private val DEFAULT_BEDROCK_THINKING_BUDGETS = mapOf(
    ModelThinkingLevel.Minimal to 1024L,
    ModelThinkingLevel.Low to 2048L,
    ModelThinkingLevel.Medium to 8192L,
    ModelThinkingLevel.High to 16384L,
    ModelThinkingLevel.Xhigh to 16384L,
    ModelThinkingLevel.Max to 16384L,
)

private class BedrockRequestConfig(
    val reasoning: ModelThinkingLevel? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val interleavedThinking: Boolean = true,
    val toolChoice: String? = null,
)

private class BedrockAuth(
    val credentials: AwsCredentials?,
    val bearerToken: String?,
    val region: String,
)

private class BedrockStreamException(message: String) : RuntimeException(message)

private class BedrockHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    override val message: String,
) : RuntimeException(message)

private class BedrockEventException(
    val exceptionType: String,
    override val message: String,
    val body: String?,
) : RuntimeException(message)

private class BedrockStrictSchemaException(message: String) : RuntimeException(message)

private class BedrockStreamState(
    val output: AssistantMessage,
    val stream: AssistantMessageEventStream,
    val model: Model,
) {
    val contentIndexByBlock = LinkedHashMap<Int, Int>()
    val partialJson = LinkedHashMap<Int, String>()
    val redactedChunks = LinkedHashMap<Int, MutableList<ByteArray>>()
    var usageModel: Model = model
    var sawMessageStart = false
    var started = false
}

private class BedrockConverseStreamApi(private val transport: HttpTransport) : ApiProvider {
    override val api: String = BEDROCK_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = launchStream(model, context, options, BedrockRequestConfig())

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val base = buildBaseOptions(model, context, options)
        val toolChoice = when (options.toolChoice) {
            ToolChoice.None -> "none"
            ToolChoice.Auto -> "auto"
            null -> null
        }
        val reasoning = options.reasoning?.toModelThinkingLevel()
        if (reasoning == null || !model.reasoning) {
            return launchStream(
                model,
                context,
                base,
                BedrockRequestConfig(toolChoice = toolChoice),
            )
        }
        var streamOptions = base
        var requestConfig = BedrockRequestConfig(
            reasoning = reasoning,
            thinkingBudgets = options.thinkingBudgets,
            toolChoice = toolChoice,
        )
        if (isAnthropicClaudeModel(model) && !supportsAdaptiveThinking(model)) {
            val adjusted = adjustMaxTokensForThinking(
                base.maxTokens ?: model.maxTokens,
                model.maxTokens,
                reasoning,
                options.thinkingBudgets,
            )
            val maxTokens = clampMaxTokensToContext(model, context, adjusted.first)
            val budgetLevel = clampReasoning(reasoning)
            val budget = minOf(adjusted.second, maxOf(0L, maxTokens - 1024L))
            streamOptions = base.copy(maxTokens = maxTokens)
            requestConfig = BedrockRequestConfig(
                reasoning = requestConfig.reasoning,
                thinkingBudgets = withThinkingBudget(options.thinkingBudgets, budgetLevel, budget),
                interleavedThinking = requestConfig.interleavedThinking,
                toolChoice = requestConfig.toolChoice,
            )
        }
        return launchStream(model, context, streamOptions, requestConfig)
    }

    private suspend fun launchStream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
        config: BedrockRequestConfig,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runBedrockStream(transport, stream, model, context, options, config)
        }
        return stream
    }
}

fun bedrockConverseStreamApi(transport: HttpTransport): ApiProvider = BedrockConverseStreamApi(transport)

private fun ToolChoice.toModelToolChoiceWire(): String = when (this) {
    ToolChoice.Auto -> "auto"
    ToolChoice.None -> "none"
}

private suspend fun runBedrockStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    config: BedrockRequestConfig,
) {
    val signal = options.signal
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage.empty(),
        stopReason = StopReason.Pending,
        timestamp = System.currentTimeMillis(),
    )
    var state: BedrockStreamState? = null
    try {
        val normalizedContext = collapseSystemMessages(context)
        val headerAuth = hasHeaderAuth(options.headers)
        val auth = if (headerAuth) null else resolveBedrockAuth(model, options)
        val region = auth?.region ?: resolveBedrockRegion(model, options)
        val endpoint = resolveBedrockEndpoint(model, region)
        val url = endpoint.trimEnd('/') + "/model/" + AwsSigV4.awsPercentEncode(model.id) + "/converse-stream"
        var body = buildRequestBody(model, normalizedContext, options, config)
        val transformed = options.onPayload?.invoke(body, model)
        if (transformed is JsonObject) body = transformed
        val payload = PiJson.encodeToString(JsonObject.serializer(), body).encodeToByteArray()
        val response = executeBedrockRequest(transport, url, payload, model, options, auth)
        val streamState = BedrockStreamState(output, stream, model)
        state = streamState
        ensureStarted(streamState)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                signal?.throwIfAborted()
                val message = AwsEventStream.readMessage(response.body) ?: break
                val messageType = message.headers[":message-type"]
                if (messageType == "exception" || messageType == "error") {
                    val exceptionType = message.headers[":exception-type"] ?: "BedrockException"
                    val rawBody = message.payload.decodeToString()
                    throw BedrockEventException(
                        exceptionType = exceptionType,
                        message = formatBedrockEventMessage(exceptionType, rawBody),
                        body = rawBody,
                    )
                }
                val eventType = message.headers[":event-type"] ?: continue
                val data = parseBedrockEventPayload(eventType, message.payload)
                options.onProviderStreamEvent?.invoke(data, model)
                when (eventType) {
                    "messageStart" -> handleMessageStart(streamState, data)
                    "contentBlockStart" -> handleContentBlockStart(streamState, data)
                    "contentBlockDelta" -> handleContentBlockDelta(streamState, data)
                    "contentBlockStop" -> handleContentBlockStop(streamState, data)
                    "messageStop" -> handleMessageStop(streamState, data)
                    "metadata" -> handleMetadata(streamState, data)
                }
            }
        } finally {
            response.close()
        }
        signal?.throwIfAborted()
        if (output.stopReason == StopReason.Pending) {
            throw BedrockStreamException("Bedrock stream ended without a stop reason")
        }
        if (output.stopReason == StopReason.Error || output.stopReason == StopReason.Aborted) {
            throw BedrockStreamException(output.errorMessage ?: "An unknown error occurred")
        }
        finalizeBlocks(streamState)
        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        state?.let(::finalizeBlocks)
        val aborted = error is RequestAbortedException || error is CancellationException ||
            signal?.isAborted == true || output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else (error.message ?: error.toString())
        if (!aborted) {
            when (error) {
                is BedrockEventException -> appendDiagnostic(
                    output,
                    type = "bedrock_response_failure",
                    message = error.message,
                    details = buildJsonObject { put("errorCode", JsonPrimitive(error.exceptionType)) },
                )

                is BedrockHttpException -> appendDiagnostic(
                    output,
                    type = "bedrock_response_failure",
                    message = error.message,
                    details = buildJsonObject { put("status", JsonPrimitive(error.status)) },
                )

                else -> Unit
            }
        }
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun ensureStarted(state: BedrockStreamState) {
    if (state.started) return
    state.started = true
    state.stream.push(StartEvent(state.output))
}

private fun handleMessageStart(state: BedrockStreamState, data: JsonObject) {
    state.sawMessageStart = true
    val role = stringField(data, "role")
    if (role != null && role != "assistant") {
        throw BedrockStreamException("Unexpected Bedrock message start role: $role")
    }
    ensureStarted(state)
}

private fun handleContentBlockStart(state: BedrockStreamState, data: JsonObject) {
    val blockIndex = intField(data, "contentBlockIndex") ?: return
    val start = objectField(data, "start") ?: return
    val toolUse = objectField(start, "toolUse") ?: return
    val contentIndex = state.output.content.size
    val toolCall = ToolCall(
        id = stringField(toolUse, "toolUseId") ?: "",
        name = stringField(toolUse, "name") ?: "",
        arguments = JsonObject(emptyMap()),
    )
    state.output.content.add(toolCall)
    state.contentIndexByBlock[blockIndex] = contentIndex
    state.partialJson[contentIndex] = ""
    ensureStarted(state)
    state.stream.push(ToolCallStartEvent(contentIndex, state.output))
}

private fun handleContentBlockDelta(state: BedrockStreamState, data: JsonObject) {
    val blockIndex = intField(data, "contentBlockIndex") ?: return
    val delta = objectField(data, "delta") ?: return
    stringField(delta, "text")?.let { text ->
        if (text.isNotEmpty()) {
            val contentIndex = ensureTextBlock(state, blockIndex)
            val block = state.output.content[contentIndex] as? TextContent ?: return
            block.text += text
            state.stream.push(TextDeltaEvent(contentIndex, text, state.output))
        }
    }
    objectField(delta, "toolUse")?.let { toolUse ->
        val input = stringField(toolUse, "input") ?: return@let
        val contentIndex = state.contentIndexByBlock[blockIndex] ?: return@let
        val block = state.output.content.getOrNull(contentIndex) as? ToolCall ?: return@let
        val accumulated = (state.partialJson[contentIndex] ?: "") + input
        state.partialJson[contentIndex] = accumulated
        val parsed = PartialJson.parse(accumulated)
        state.output.content[contentIndex] = block.copy(
            arguments = parsed as? JsonObject ?: JsonObject(emptyMap()),
        )
        state.stream.push(ToolCallDeltaEvent(contentIndex, input, state.output))
    }
    objectField(delta, "reasoningContent")?.let { reasoning ->
        val contentIndex = ensureThinkingBlock(state, blockIndex)
        stringField(reasoning, "text")?.let { text ->
            if (text.isNotEmpty()) {
                val block = state.output.content[contentIndex] as? ThinkingContent ?: return@let
                block.thinking += text
                state.stream.push(ThinkingDeltaEvent(contentIndex, text, state.output))
            }
        }
        stringField(reasoning, "signature")?.let { signature ->
            if (signature.isNotEmpty()) {
                (state.output.content[contentIndex] as? ThinkingContent)?.let { block ->
                    if (block.redacted != true) {
                        block.thinkingSignature = (block.thinkingSignature ?: "") + signature
                    }
                }
            }
        }
        stringField(reasoning, "redactedContent")?.let { redacted ->
            if (redacted.isNotEmpty()) {
                val block = state.output.content[contentIndex] as? ThinkingContent ?: return@let
                if (block.redacted != true) {
                    block.redacted = true
                    block.thinkingSignature = ""
                    block.thinking += REDACTED_THINKING_PLACEHOLDER
                    state.stream.push(
                        ThinkingDeltaEvent(contentIndex, REDACTED_THINKING_PLACEHOLDER, state.output),
                    )
                }
                runCatching { Base64.decode(redacted) }.getOrNull()?.let { bytes ->
                    state.redactedChunks.getOrPut(contentIndex) { mutableListOf() }.add(bytes)
                }
            }
        }
    }
}

private fun handleContentBlockStop(state: BedrockStreamState, data: JsonObject) {
    val blockIndex = intField(data, "contentBlockIndex") ?: return
    val contentIndex = state.contentIndexByBlock.remove(blockIndex) ?: return
    when (val block = state.output.content.getOrNull(contentIndex)) {
        null -> return
        is TextContent -> state.stream.push(TextEndEvent(contentIndex, block.text, state.output))
        is ThinkingContent -> {
            flushRedactedContent(state, contentIndex, block)
            state.stream.push(ThinkingEndEvent(contentIndex, block.thinking, state.output))
        }

        is ToolCall -> {
            val parsed = PartialJson.parseToolCallArguments(state.partialJson.remove(contentIndex))
            val updated = block.copy(arguments = parsed.arguments, argumentsError = parsed.argumentsError)
            state.output.content[contentIndex] = updated
            state.stream.push(ToolCallEndEvent(contentIndex, updated, state.output))
        }

        else -> Unit
    }
}

private fun handleMessageStop(state: BedrockStreamState, data: JsonObject) {
    val rawReason = stringField(data, "stopReason")
    if (rawReason != null) {
        state.output.rawStopReason = rawReason
        val mapped = mapBedrockStopReason(rawReason)
        state.output.stopReason = mapped.first
        mapped.second?.let { state.output.errorMessage = it }
    }
}

private fun handleMetadata(state: BedrockStreamState, data: JsonObject) {
    val usage = objectField(data, "usage") ?: return
    val output = state.output
    output.usage.input = longField(usage, "inputTokens") ?: 0L
    output.usage.output = longField(usage, "outputTokens") ?: 0L
    output.usage.cacheRead = longField(usage, "cacheReadInputTokens") ?: 0L
    output.usage.cacheWrite = longField(usage, "cacheWriteInputTokens") ?: 0L
    var cacheWrite1h = 0L
    (usage["cacheDetails"] as? JsonArray)?.forEach { detail ->
        val entry = detail as? JsonObject ?: return@forEach
        if (stringField(entry, "ttl") == CACHE_CACHE_DETAIL_ONE_HOUR) {
            cacheWrite1h += longField(entry, "inputTokens") ?: 0L
        }
    }
    output.usage.cacheWrite1h = cacheWrite1h
    output.usage.totalTokens = longField(usage, "totalTokens")
        ?: (output.usage.input + output.usage.output)
    output.usage.cost = calculateCost(state.usageModel, output.usage)
}

private const val CACHE_CACHE_DETAIL_ONE_HOUR = "1h"

private fun finalizeBlocks(state: BedrockStreamState) {
    state.output.content.forEachIndexed { index, content ->
        if (content is ThinkingContent) flushRedactedContent(state, index, content)
    }
}

private fun flushRedactedContent(state: BedrockStreamState, contentIndex: Int, block: ThinkingContent) {
    val chunks = state.redactedChunks.remove(contentIndex) ?: return
    if (chunks.isEmpty()) return
    val size = chunks.sumOf { it.size }
    val bytes = ByteArray(size)
    var offset = 0
    for (chunk in chunks) {
        chunk.copyInto(bytes, offset)
        offset += chunk.size
    }
    block.thinkingSignature = Base64.encode(bytes)
}

private fun ensureTextBlock(state: BedrockStreamState, blockIndex: Int): Int {
    state.contentIndexByBlock[blockIndex]?.let { return it }
    val contentIndex = state.output.content.size
    state.output.content.add(TextContent(text = ""))
    state.contentIndexByBlock[blockIndex] = contentIndex
    ensureStarted(state)
    state.stream.push(TextStartEvent(contentIndex, state.output))
    return contentIndex
}

private fun ensureThinkingBlock(state: BedrockStreamState, blockIndex: Int): Int {
    state.contentIndexByBlock[blockIndex]?.let { return it }
    val contentIndex = state.output.content.size
    state.output.content.add(ThinkingContent(thinking = "", thinkingSignature = ""))
    state.contentIndexByBlock[blockIndex] = contentIndex
    ensureStarted(state)
    state.stream.push(ThinkingStartEvent(contentIndex, state.output))
    return contentIndex
}

private fun mapBedrockStopReason(reason: String): Pair<StopReason, String?> = when (reason) {
    "end_turn", "stop_sequence" -> Pair(StopReason.Stop, null)
    "max_tokens", "model_context_window_exceeded" -> Pair(StopReason.Length, null)
    "tool_use" -> Pair(StopReason.ToolUse, null)
    else -> Pair(StopReason.Error, "Provider stopped with: $reason")
}

private fun parseBedrockEventPayload(eventType: String, payload: ByteArray): JsonObject {
    val text = payload.decodeToString()
    return try {
        PiJson.parseToJsonElement(text) as? JsonObject ?: throw BedrockStreamException(
            "Could not parse Bedrock event $eventType: payload is not a JSON object",
        )
    } catch (error: BedrockStreamException) {
        throw error
    } catch (error: Throwable) {
        throw BedrockStreamException(
            "Could not parse Bedrock event $eventType: ${error.message ?: error.toString()}",
        )
    }
}

private fun formatBedrockEventMessage(exceptionType: String, body: String): String {
    val parsed = runCatching { PiJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    val providerMessage = parsed?.let {
        stringField(it, "message") ?: stringField(it, "Message")
    } ?: body
    val prefix = when (exceptionType.lowercase()) {
        "internalserverexception" -> "Internal server error"
        "modelstreamerrorexception" -> "Model stream error"
        "validationexception" -> "Validation error"
        "throttlingexception" -> "Throttling error"
        "serviceunavailableexception" -> "Service unavailable"
        else -> exceptionType
    }
    return "$prefix: $providerMessage"
}

private fun appendDiagnostic(
    output: AssistantMessage,
    type: String,
    message: String?,
    details: JsonObject? = null,
) {
    val diagnostics = output.diagnostics
        ?: mutableListOf<AssistantMessageDiagnostic>().also { output.diagnostics = it }
    diagnostics.add(
        AssistantMessageDiagnostic(
            type = type,
            timestamp = System.currentTimeMillis(),
            error = message?.let { DiagnosticErrorInfo(message = it) },
            details = details,
        ),
    )
}

private fun buildRequestBody(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    config: BedrockRequestConfig,
): JsonObject {
    val cacheRetention = resolveCacheRetention(options)
    val supportsCaching = supportsPromptCaching(model, options)
    val initialSystemMessage = getInitialSystemMessage(context.messages)
    val body = LinkedHashMap<String, JsonElement>()
    body["messages"] = convertMessages(context, model, cacheRetention, supportsCaching)
    if (initialSystemMessage != null) {
        val systemText = getSystemMessageText(initialSystemMessage)
        if (systemText.isNotEmpty()) {
            val blocks = mutableListOf<JsonElement>(
                buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(systemText))) },
            )
            if (cacheRetention != CacheRetention.None && supportsCaching) {
                blocks.add(cachePoint(cacheRetention))
            }
            body["system"] = JsonArray(blocks)
        }
    }
    val inferenceConfig = buildJsonObject {
        val maxTokens = options.maxTokens ?: if (isAnthropicClaudeModel(model)) model.maxTokens else null
        if (maxTokens != null) put("maxTokens", JsonPrimitive(maxTokens))
        options.temperature?.let { put("temperature", JsonPrimitive(it)) }
    }
    if (inferenceConfig.isNotEmpty()) body["inferenceConfig"] = inferenceConfig
    convertToolConfig(getCurrentTools(context.messages), config.toolChoice, model)?.let {
        body["toolConfig"] = it
    }
    buildAdditionalModelRequestFields(model, options, config)?.let {
        body["additionalModelRequestFields"] = it
    }
    options.metadata?.let { metadata ->
        val entries = metadata.entries.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            if (primitive is JsonNull || primitive.isString || primitive.content.toLongOrNull() != null ||
                primitive.content.toDoubleOrNull() != null
            ) {
                key to JsonPrimitive(primitive.content)
            } else {
                null
            }
        }
        if (entries.isNotEmpty()) body["requestMetadata"] = JsonObject(entries.toMap())
    }
    return JsonObject(body)
}

private fun cachePoint(retention: CacheRetention): JsonObject = buildJsonObject {
    put(
        "cachePoint",
        buildJsonObject {
            put("type", JsonPrimitive("default"))
            if (retention == CacheRetention.Long) put("ttl", JsonPrimitive(CACHE_POINT_TTL_ONE_HOUR))
        },
    )
}

private fun resolveCacheRetention(options: StreamOptions): CacheRetention {
    options.cacheRetention?.let { return it }
    return if (envValue(options, "PI_CACHE_RETENTION") == "long") CacheRetention.Long else CacheRetention.Short
}

private fun convertMessages(
    context: TranscriptContext,
    model: Model,
    cacheRetention: CacheRetention,
    supportsCaching: Boolean,
): JsonArray {
    val result = mutableListOf<JsonElement>()
    val transformed = transformMessages(withoutInitialSystemMessage(context.messages), model) { id ->
        normalizeToolCallId(id)
    }
    var index = 0
    while (index < transformed.size) {
        when (val message = transformed[index]) {
            is UserMessage -> {
                val blocks = mutableListOf<JsonElement>()
                when (val content = message.content) {
                    is dev.pi.ai.PlainTextContent -> {
                        blocks.add(requiredTextBlock(content.text))
                    }

                    is BlockContent -> {
                        for (block in content.blocks) {
                            when (block) {
                                is TextContent -> createTextBlock(block.text)?.let(blocks::add)
                                is ImageContent -> blocks.add(createImageBlock(block))
                                else -> Unit
                            }
                        }
                        if (blocks.isEmpty()) blocks.add(requiredTextBlock(""))
                    }
                }
                result.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonArray(blocks))
                    },
                )
                index += 1
            }

            is AssistantMessage -> {
                if (message.content.isEmpty()) {
                    index += 1
                    continue
                }
                val blocks = mutableListOf<JsonElement>()
                for (block in message.content) {
                    when (block) {
                        is TextContent -> createTextBlock(block.text)?.let(blocks::add)
                        is ToolCall -> blocks.add(
                            buildJsonObject {
                                put(
                                    "toolUse",
                                    buildJsonObject {
                                        put("toolUseId", JsonPrimitive(block.id))
                                        put("name", JsonPrimitive(block.name))
                                        put("input", sanitizeBedrockDocument(block.arguments))
                                    },
                                )
                            },
                        )

                        is ThinkingContent -> convertThinkingBlock(block, model)?.let(blocks::add)
                        else -> Unit
                    }
                }
                if (blocks.isNotEmpty()) {
                    result.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonArray(blocks))
                        },
                    )
                }
                index += 1
            }

            is ToolResultMessage -> {
                val toolResults = mutableListOf<JsonElement>()
                val errorImages = mutableListOf<JsonElement>()
                var next = index
                while (next < transformed.size && transformed[next] is ToolResultMessage) {
                    val toolMessage = transformed[next] as ToolResultMessage
                    val split = if (isAnthropicClaudeModel(model)) splitBedrockErrorToolResultImages(toolMessage) else null
                    toolResults.add(
                        buildJsonObject {
                            put(
                                "toolResult",
                                buildJsonObject {
                                    put("toolUseId", JsonPrimitive(toolMessage.toolCallId))
                                    put(
                                        "content",
                                        JsonArray(convertToolResultContent(split?.content ?: toolMessage.content)),
                                    )
                                    put("status", JsonPrimitive(if (toolMessage.isError) "error" else "success"))
                                },
                            )
                        },
                    )
                    if (split != null) {
                        errorImages.add(buildJsonObject { put("text", JsonPrimitive(split.caption)) })
                        split.images.forEach { image ->
                            errorImages.add(createImageBlock(image))
                        }
                    }
                    next += 1
                }
                result.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonArray(toolResults + errorImages))
                    },
                )
                index = next
            }

            else -> index += 1
        }
    }
    if (cacheRetention != CacheRetention.None && supportsCaching && result.isNotEmpty()) {
        val last = result.last() as? JsonObject
        if (last != null && stringField(last, "role") == "user") {
            val content = (last["content"] as? JsonArray)?.toMutableList() ?: mutableListOf()
            content.add(cachePoint(cacheRetention))
            result[result.size - 1] = JsonObject(last.toMutableMap().apply { put("content", JsonArray(content)) })
        }
    }
    return JsonArray(result)
}

private fun convertThinkingBlock(block: ThinkingContent, model: Model): JsonElement? {
    if (block.redacted == true) {
        val signature = block.thinkingSignature
        if (signature.isNullOrEmpty()) return null
        val bytes = runCatching { Base64.decode(signature) }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return buildJsonObject {
            put(
                "reasoningContent",
                buildJsonObject { put("redactedContent", JsonPrimitive(Base64.encode(bytes))) },
            )
        }
    }
    val thinking = sanitizeSurrogates(block.thinking)
    if (thinking.isBlank()) return null
    return if (supportsThinkingSignature(model)) {
        val signature = block.thinkingSignature
        if (signature.isNullOrBlank()) {
            buildJsonObject { put("text", JsonPrimitive(thinking)) }
        } else {
            buildJsonObject {
                put(
                    "reasoningContent",
                    buildJsonObject {
                        put(
                            "reasoningText",
                            buildJsonObject {
                                put("text", JsonPrimitive(thinking))
                                put("signature", JsonPrimitive(signature))
                            },
                        )
                    },
                )
            }
        }
    } else {
        buildJsonObject {
            put(
                "reasoningContent",
                buildJsonObject {
                    put("reasoningText", buildJsonObject { put("text", JsonPrimitive(thinking)) })
                },
            )
        }
    }
}

private data class BedrockSplitErrorToolResult(
    val content: List<Content>,
    val caption: String,
    val images: List<ImageContent>,
)

private const val ERROR_TOOL_IMAGE_PLACEHOLDER = "(image attached after the tool results)"

private fun splitBedrockErrorToolResultImages(message: ToolResultMessage): BedrockSplitErrorToolResult? {
    if (!message.isError) return null
    val images = message.content.filterIsInstance<ImageContent>()
    if (images.isEmpty()) return null
    val replaced = mutableListOf<Content>()
    var previousWasPlaceholder = false
    for (block in message.content) {
        if (block is ImageContent) {
            if (!previousWasPlaceholder) replaced.add(TextContent(ERROR_TOOL_IMAGE_PLACEHOLDER))
            previousWasPlaceholder = true
            continue
        }
        replaced.add(block)
        previousWasPlaceholder = block is TextContent && block.text == ERROR_TOOL_IMAGE_PLACEHOLDER
    }
    return BedrockSplitErrorToolResult(
        content = replaced,
        caption = "Images from the error result of tool call ${message.toolCallId} (${message.toolName}):",
        images = images,
    )
}

private fun convertToolResultContent(content: List<Content>): List<JsonElement> {
    val result = mutableListOf<JsonElement>()
    for (block in content) {
        when (block) {
            is TextContent -> createTextBlock(block.text)?.let(result::add)
            is ImageContent -> result.add(createImageBlock(block))
            else -> Unit
        }
    }
    if (result.isEmpty()) result.add(requiredTextBlock(""))
    return result
}

private fun createTextBlock(text: String): JsonElement? {
    val sanitized = sanitizeSurrogates(text)
    if (sanitized.isBlank()) return null
    return buildJsonObject { put("text", JsonPrimitive(sanitized)) }
}

private fun requiredTextBlock(text: String): JsonElement {
    val sanitized = sanitizeSurrogates(text)
    return buildJsonObject {
        put("text", JsonPrimitive(if (sanitized.isBlank()) EMPTY_TEXT_PLACEHOLDER else sanitized))
    }
}

private fun createImageBlock(image: ImageContent): JsonElement {
    val format = when (image.mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpeg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> throw BedrockStreamException("Unknown image type: ${image.mimeType}")
    }
    return buildJsonObject {
        put(
            "image",
            buildJsonObject {
                put("format", JsonPrimitive(format))
                put(
                    "source",
                    buildJsonObject { put("bytes", JsonPrimitive(image.data)) },
                )
            },
        )
    }
}

private fun sanitizeBedrockDocument(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(
        value.entries.filter { it.key.isNotEmpty() }.associate { (key, nested) ->
            key to sanitizeBedrockDocument(nested)
        },
    )

    is JsonArray -> JsonArray(value.map(::sanitizeBedrockDocument))
    else -> value
}

private fun convertToolConfig(
    tools: List<Tool>,
    toolChoice: String?,
    model: Model,
): JsonObject? {
    if (tools.isEmpty() || toolChoice == "none") return null
    val supportsStrictMode = model.compat?.let { booleanField(it, "supportsStrictMode") } ?: false
    val definitions = tools.map { tool ->
        val strict = resolveBedrockStrictSampling(tool, supportsStrictMode)
        val parameters = if (strict == true) makeStrictBedrockSchema(tool.parameters) else tool.parameters
        buildJsonObject {
            put(
                "toolSpec",
                buildJsonObject {
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    put("inputSchema", buildJsonObject { put("json", parameters) })
                    if (strict == true) put("strict", JsonPrimitive(true))
                },
            )
        }
    }
    return buildJsonObject {
        put("tools", JsonArray(definitions))
        if (toolChoice == "auto") {
            put("toolChoice", buildJsonObject { put("auto", JsonObject(emptyMap())) })
        }
    }
}

private fun resolveBedrockStrictSampling(tool: Tool, supportsStrictMode: Boolean): Boolean? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "json_schema") return null
    val strictMode = stringField(config, "strict")
    if (supportsStrictMode) {
        return try {
            makeStrictBedrockSchema(tool.parameters)
            true
        } catch (error: BedrockStrictSchemaException) {
            if (strictMode != "require") return null
            throw BedrockStreamException(
                "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but ${error.message}.",
            )
        }
    }
    if (strictMode == "require") {
        throw BedrockStreamException(
            "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
        )
    }
    return null
}

private fun makeStrictBedrockSchema(schema: JsonObject): JsonObject {
    val strict = strictSchemaNode(schema)
    if ((strict["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "object") {
        throw BedrockStrictSchemaException("root schema must have type object")
    }
    return strict
}

private fun strictSchemaNode(schema: JsonElement): JsonObject {
    if (schema == JsonNull || schema is JsonPrimitive) {
        throw BedrockStrictSchemaException("boolean schemas are unsupported")
    }
    val obj = schema as? JsonObject ?: throw BedrockStrictSchemaException("schema must be an object")
    for (key in UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (obj.containsKey(key)) throw BedrockStrictSchemaException("$key schemas are unsupported")
    }
    val result = LinkedHashMap(obj)
    obj["anyOf"]?.let { anyOf ->
        val variants = anyOf as? JsonArray ?: throw BedrockStrictSchemaException("anyOf must be an array")
        if (variants.isEmpty()) throw BedrockStrictSchemaException("anyOf must contain at least one schema")
        result["anyOf"] = JsonArray(
            variants.map { variant ->
                if (isStructuredSchema(variant)) {
                    throw BedrockStrictSchemaException("object and array unions are unsupported")
                }
                strictSchemaNode(variant)
            },
        )
    }
    obj["items"]?.let { items ->
        if (items is JsonArray) throw BedrockStrictSchemaException("tuple schemas are unsupported")
        result["items"] = strictSchemaNode(items)
    }
    val isObjectSchema = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object"
    if (obj.containsKey("properties") && !isObjectSchema) {
        throw BedrockStrictSchemaException("properties require type object")
    }
    if (!isObjectSchema) return JsonObject(result)
    obj["additionalProperties"]?.let { additional ->
        if (additional != JsonPrimitive(false)) {
            throw BedrockStrictSchemaException("schema-valued or true additionalProperties is unsupported")
        }
    }
    val propertiesElement = obj["properties"]
    if (propertiesElement != null && propertiesElement !is JsonObject) {
        throw BedrockStrictSchemaException("object properties must be a schema map")
    }
    val properties = propertiesElement as? JsonObject ?: JsonObject(emptyMap())
    val requiredElement = obj["required"]
    if (requiredElement != null) {
        val valid = requiredElement is JsonArray && requiredElement.all { element ->
            (element as? JsonPrimitive)?.isString == true
        }
        if (!valid) throw BedrockStrictSchemaException("object required must be a string array")
    }
    val required = (requiredElement as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
        ?.toSet()
        .orEmpty()
    val propertyNames = properties.keys.toList()
    if (required.any { it !in propertyNames }) {
        throw BedrockStrictSchemaException("required contains an unknown property")
    }
    val strictProperties = LinkedHashMap<String, JsonElement>()
    for ((key, property) in properties) {
        var strictProperty: JsonElement = strictSchemaNode(property)
        if (!required.contains(key) && !schemaAllowsNull(property)) {
            strictProperty = buildJsonObject {
                put(
                    "anyOf",
                    JsonArray(
                        listOf(
                            strictProperty,
                            buildJsonObject { put("type", JsonPrimitive("null")) },
                        ),
                    ),
                )
            }
        }
        strictProperties[key] = strictProperty
    }
    result["properties"] = JsonObject(strictProperties)
    result["required"] = JsonArray(propertyNames.map { JsonPrimitive(it) })
    result["additionalProperties"] = JsonPrimitive(false)
    return JsonObject(result)
}

private fun isStructuredSchema(schema: JsonElement): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeElement = obj["type"]
    val types = when {
        typeElement is JsonPrimitive && typeElement.isString -> listOf(typeElement.content)
        typeElement is JsonArray -> typeElement.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

        else -> emptyList()
    }
    return types.contains("object") || types.contains("array") ||
        obj.containsKey("properties") || obj.containsKey("items")
}

private fun schemaAllowsNull(schema: JsonElement?): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeElement = obj["type"]
    if (typeElement is JsonPrimitive && typeElement.isString && typeElement.content == "null") return true
    if (typeElement is JsonArray && typeElement.any { element ->
            (element as? JsonPrimitive)?.takeIf { it.isString }?.content == "null"
        }
    ) {
        return true
    }
    if (obj["const"] is JsonNull) return true
    val enumValues = obj["enum"] as? JsonArray
    if (enumValues != null && enumValues.any { it is JsonNull }) return true
    val anyOf = obj["anyOf"] as? JsonArray ?: return false
    return anyOf.any { schemaAllowsNull(it) }
}

private fun buildAdditionalModelRequestFields(
    model: Model,
    options: StreamOptions,
    config: BedrockRequestConfig,
): JsonObject? {
    val reasoning = config.reasoning ?: return null
    if (!model.reasoning) return null
    if (isAnthropicClaudeModel(model)) {
        val result = if (supportsAdaptiveThinking(model)) {
            buildJsonObject {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", JsonPrimitive("adaptive"))
                        put("display", JsonPrimitive("summarized"))
                        if (supportsThinkingBlockBinding(model)) {
                            put(
                                "block_binding",
                                buildJsonObject { put("prefix_mismatch_behavior", JsonPrimitive("drop_block")) },
                            )
                        }
                    },
                )
                put(
                    "output_config",
                    buildJsonObject { put("effort", JsonPrimitive(mapThinkingLevelToEffort(model, reasoning))) },
                )
                if (supportsThinkingBlockBinding(model)) {
                    put("anthropic_beta", JsonArray(listOf(JsonPrimitive(THINKING_BINDING_CONTROLS_BETA))))
                }
            }
        } else {
            val budgetLevel = if (reasoning == ModelThinkingLevel.Xhigh || reasoning == ModelThinkingLevel.Max) {
                ModelThinkingLevel.High
            } else {
                reasoning
            }
            val budget = budgetForLevel(config.thinkingBudgets, budgetLevel)
                ?: DEFAULT_BEDROCK_THINKING_BUDGETS[reasoning]
                ?: 16384L
            buildJsonObject {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", JsonPrimitive("enabled"))
                        put("budget_tokens", JsonPrimitive(budget))
                        put("display", JsonPrimitive("summarized"))
                    },
                )
            }
        }
        val withBeta = if (!supportsAdaptiveThinking(model) && config.interleavedThinking) {
            JsonObject(
                result.toMutableMap().apply {
                    put("anthropic_beta", JsonArray(listOf(JsonPrimitive(INTERLEAVED_THINKING_BETA))))
                },
            )
        } else {
            result
        }
        return withBeta
    }
    if (modelMatches(model, "gpt-oss")) {
        return buildJsonObject {
            put("reasoning_effort", JsonPrimitive(OPENAI_GPT_OSS_EFFORT[reasoning] ?: "high"))
        }
    }
    if (modelMatches(model, "gpt-")) {
        val mapped = model.thinkingLevelMap?.get(reasoning.jsonName())
        val effort = if (mapped != null) mapped else OPENAI_GPT_EFFORT[reasoning] ?: "high"
        return buildJsonObject {
            put("reasoning", buildJsonObject { put("effort", JsonPrimitive(effort)) })
        }
    }
    return null
}

private fun budgetForLevel(budgets: ThinkingBudgets?, level: ModelThinkingLevel): Long? = when (level) {
    ModelThinkingLevel.Minimal -> budgets?.minimal
    ModelThinkingLevel.Low -> budgets?.low
    ModelThinkingLevel.Medium -> budgets?.medium
    else -> budgets?.high
}

private fun withThinkingBudget(
    budgets: ThinkingBudgets?,
    level: ModelThinkingLevel,
    budget: Long,
): ThinkingBudgets {
    val base = budgets ?: ThinkingBudgets()
    return when (level) {
        ModelThinkingLevel.Minimal -> base.copy(minimal = budget)
        ModelThinkingLevel.Low -> base.copy(low = budget)
        ModelThinkingLevel.Medium -> base.copy(medium = budget)
        else -> base.copy(high = budget)
    }
}

private fun mapThinkingLevelToEffort(model: Model, level: ModelThinkingLevel): String {
    if (level == ModelThinkingLevel.Xhigh && supportsNativeXhighEffort(model)) return "xhigh"
    val mapped = model.thinkingLevelMap?.get(level.jsonName())
    if (mapped != null) return mapped
    return when (level) {
        ModelThinkingLevel.Minimal, ModelThinkingLevel.Low -> "low"
        ModelThinkingLevel.Medium -> "medium"
        ModelThinkingLevel.High -> "high"
        else -> "high"
    }
}

private fun adjustMaxTokensForThinking(
    baseMaxTokens: Long,
    modelMaxTokens: Long,
    reasoningLevel: ModelThinkingLevel,
    customBudgets: ThinkingBudgets?,
): Pair<Long, Long> {
    var thinkingBudget = thinkingBudgetForLevel(reasoningLevel, customBudgets)
    val maxTokens = minOf(baseMaxTokens + thinkingBudget, modelMaxTokens)
    if (maxTokens <= thinkingBudget) {
        thinkingBudget = minOf(thinkingBudget, maxOf(0L, maxTokens - 1024L))
    }
    return maxTokens to thinkingBudget
}

private fun modelCandidates(model: Model): List<String> {
    val values = if (model.name.isNotEmpty()) listOf(model.id, model.name) else listOf(model.id)
    return values.flatMap { value ->
        val lower = value.lowercase()
        listOf(lower, lower.replace(Regex("[\\s_.:]+"), "-"))
    }
}

private fun modelMatches(model: Model, needle: String): Boolean =
    modelCandidates(model).any { it.contains(needle) }

private fun isAnthropicClaudeModel(model: Model): Boolean {
    val id = model.id.lowercase()
    val name = model.name.lowercase()
    return id.contains("anthropic.claude") || id.contains("anthropic/claude") ||
        name.contains("anthropic.claude") || name.contains("anthropic/claude") || name.contains("claude")
}

private fun supportsAdaptiveThinking(model: Model): Boolean = modelCandidates(model).any { candidate ->
    listOf(
        "opus-4-6",
        "opus-4-7",
        "opus-4-8",
        "opus-5",
        "sonnet-4-6",
        "sonnet-5",
        "haiku-5",
        "fable-5",
    ).any(candidate::contains)
}

private fun supportsNativeXhighEffort(model: Model): Boolean = modelCandidates(model).any { candidate ->
    listOf("opus-4-7", "opus-4-8", "opus-5", "sonnet-5", "haiku-5", "fable-5").any(candidate::contains)
}

private fun supportsThinkingBlockBinding(model: Model): Boolean = modelCandidates(model).any { candidate ->
    listOf("opus-4-7", "opus-4-8", "opus-5", "sonnet-5", "haiku-5", "fable-5").any(candidate::contains)
}

private fun supportsThinkingSignature(model: Model): Boolean = isAnthropicClaudeModel(model)

private fun supportsPromptCaching(model: Model, options: StreamOptions): Boolean {
    val candidates = modelCandidates(model)
    val hasClaudeRef = candidates.any { it.contains("claude") }
    if (!hasClaudeRef) return envValue(options, "AWS_BEDROCK_FORCE_CACHE") == "1"
    if (candidates.any { candidate ->
            listOf("fable-5", "opus-5", "sonnet-5", "haiku-5").any(candidate::contains)
        }
    ) {
        return true
    }
    if (candidates.any { it.contains("-4-") }) return true
    if (candidates.any { it.contains("claude-3-7-sonnet") }) return true
    if (candidates.any { it.contains("claude-3-5-haiku") }) return true
    return false
}

private fun normalizeToolCallId(id: String): String {
    val sanitized = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return if (sanitized.length > 64) sanitized.take(64) else sanitized
}

private fun envValue(options: StreamOptions, name: String): String? =
    options.env?.get(name)?.takeIf { it.isNotBlank() } ?: AuthEnvironment.env(name)

private fun hasHeaderAuth(headers: Map<String, String?>?): Boolean {
    if (headers == null) return false
    val authHeaders = setOf("authorization", "x-api-key", "cf-aig-authorization")
    return headers.any { (name, value) ->
        value != null && value.isNotBlank() && name.lowercase() in authHeaders
    }
}

private fun resolveBedrockAuth(model: Model, options: StreamOptions): BedrockAuth {
    val accessKeyId = envValue(options, "AWS_ACCESS_KEY_ID")
    val secretAccessKey = envValue(options, "AWS_SECRET_ACCESS_KEY")
    val sessionToken = envValue(options, "AWS_SESSION_TOKEN")
    val profile = envValue(options, "AWS_PROFILE")
    val bearerFromEnv = envValue(options, "AWS_BEARER_TOKEN_BEDROCK")
    val explicitKey = options.apiKey?.takeIf { it.isNotBlank() }
    val bearerToken = bearerFromEnv ?: explicitKey?.takeIf { it != profile && it != accessKeyId }
    val credentials = if (accessKeyId != null && secretAccessKey != null) {
        AwsCredentials(accessKeyId = accessKeyId, secretAccessKey = secretAccessKey, sessionToken = sessionToken)
    } else {
        null
    }
    if (bearerToken == null && credentials == null) {
        throw IllegalStateException("No AWS credentials or bearer token for provider: ${model.provider}")
    }
    return BedrockAuth(
        credentials = credentials,
        bearerToken = bearerToken,
        region = resolveBedrockRegion(model, options),
    )
}

private fun resolveBedrockRegion(model: Model, options: StreamOptions): String =
    envValue(options, "AWS_REGION")
        ?: envValue(options, "AWS_DEFAULT_REGION")
        ?: regionFromArn(model.id)
        ?: regionFromBaseUrl(model.baseUrl)
        ?: "us-east-1"

private fun regionFromArn(modelId: String): String? {
    val match = Regex("^arn:aws(?:-[a-z0-9-]+)?:bedrock:([a-z0-9-]+):").find(modelId.lowercase())
    return match?.groupValues?.getOrNull(1)
}

private fun regionFromBaseUrl(baseUrl: String): String? {
    val match = Regex("^https?://bedrock-runtime(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws\\.com(?:\\.cn)?")
        .find(baseUrl.lowercase())
    return match?.groupValues?.getOrNull(1)
}

private fun resolveBedrockEndpoint(model: Model, region: String): String {
    val baseUrl = model.baseUrl.trim()
    if (baseUrl.isEmpty()) return "https://bedrock-runtime.$region.amazonaws.com"
    val endpointRegion = regionFromBaseUrl(baseUrl)
    if (endpointRegion == null) return baseUrl.trimEnd('/')
    if (endpointRegion == region) return baseUrl.trimEnd('/')
    return "https://bedrock-runtime.$region.amazonaws.com"
}

private fun buildRequestHeaders(
    model: Model,
    options: StreamOptions,
    auth: BedrockAuth?,
    url: String,
    payload: ByteArray,
): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers["User-Agent"] = options.identity?.userAgent ?: "pi"
    model.headers?.forEach { (name, value) -> headers[name] = value }
    options.headers?.forEach { (name, value) ->
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
        if (value != null) headers[name] = value
    }
    if (!hasHeaderKey(headers, "content-type")) headers["content-type"] = "application/json"
    if (!hasHeaderKey(headers, "accept")) headers["accept"] = "application/vnd.amazon.eventstream"
    if (auth == null || hasHeaderKey(headers, "authorization")) return headers
    val bearer = auth.bearerToken
    if (bearer != null) {
        headers["Authorization"] = "Bearer $bearer"
        return headers
    }
    val credentials = auth.credentials ?: return headers
    return AwsSigV4.sign(
        method = "POST",
        url = url,
        headers = headers,
        payload = payload,
        credentials = credentials,
        region = auth.region,
        service = AwsSigV4.BEDROCK_SERVICE,
    ).headers
}

private fun hasHeaderKey(headers: Map<String, String>, name: String): Boolean =
    headers.keys.any { it.equals(name, ignoreCase = true) }

private suspend fun executeBedrockRequest(
    transport: HttpTransport,
    url: String,
    payload: ByteArray,
    model: Model,
    options: StreamOptions,
    auth: BedrockAuth?,
): HttpResponse {
    val signal = options.signal
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    while (true) {
        signal?.throwIfAborted()
        val headers = buildRequestHeaders(model, options, auth, url, payload)
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = payload,
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
            Retry.sleep(
                bedrockRetryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""),
                signal,
            )
            continue
        }
        options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        if (response.isSuccessful()) return response
        if (retriesRemaining > 0 && isRetryableBedrockStatus(response)) {
            val retryIndex = maxRetries - retriesRemaining
            val delayMs = bedrockRetryDelayMs(
                response,
                retryIndex,
                options.maxRetryDelayMs,
                "HTTP ${response.status}",
            )
            val bodyText = try {
                response.body.readUtf8().trim()
            } catch (_: Throwable) {
                ""
            } finally {
                response.close()
            }
            retriesRemaining -= 1
            Retry.sleep(delayMs, signal)
            continue
        }
        throw bedrockHttpError(response)
    }
}

private fun isRetryableBedrockStatus(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun bedrockRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        val parsedMs = response.header("retry-after-ms")?.toDoubleOrNull()
        if (parsedMs != null) {
            return validateBedrockRetryDelayMs(parsedMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        }
        val parsedSeconds = response.header("retry-after")?.toDoubleOrNull()
        if (parsedSeconds != null) {
            return validateBedrockRetryDelayMs(
                (parsedSeconds * 1000).toLong(),
                maxRetryDelayMs,
                providerErrorMessage,
            )
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateBedrockRetryDelayMs(
    delayMs: Long,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw BedrockStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun bedrockHttpError(response: HttpResponse): BedrockHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    val message = ProviderErrors.format(
        ProviderErrors.normalize(response.status, response.headers, body),
        "Bedrock API error",
    )
    return BedrockHttpException(
        status = response.status,
        headers = response.headers,
        body = body,
        message = message,
    )
}
