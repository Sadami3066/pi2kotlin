package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageDiagnostic
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.CacheRetention
import dev.pi.ai.ClientIdentity
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
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.ai.calculateCost
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.SseParser
import dev.pi.ai.utils.PartialJson
import dev.pi.ai.utils.ProviderErrors
import dev.pi.ai.utils.Retry
import dev.pi.ai.utils.getCurrentTools
import dev.pi.ai.utils.getInitialSystemMessage
import dev.pi.ai.utils.getSystemMessageText
import dev.pi.ai.utils.renderSystemMessageUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private const val ANTHROPIC_API = "anthropic-messages"
private const val ANTHROPIC_VERSION = "2023-06-01"
private const val CLAUDE_CODE_VERSION = "2.1.280"
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private const val ANTHROPIC_MIN_ANSWER_TOKENS = 1024L

private const val FINE_GRAINED_TOOL_STREAMING_BETA = "fine-grained-tool-streaming-2025-05-14"
private const val INTERLEAVED_THINKING_BETA = "interleaved-thinking-2025-05-14"
private const val SERVER_SIDE_FALLBACK_BETA = "server-side-fallback-2026-07-01"
private const val MID_CONVERSATION_OUTPUT_CONFIG_BETA = "mid-conversation-output-config-2026-07-01"
private const val THINKING_BINDING_CONTROLS_BETA = "thinking-binding-controls-2026-08-01"
private const val INLINE_TOOLS_BETA = "inline-tools-2026-09-15"

private const val CLAUDE_CODE_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

private val CLAUDE_CODE_TOOLS = listOf(
    "Read",
    "Write",
    "Edit",
    "Bash",
    "Grep",
    "Glob",
    "AskUserQuestion",
    "EnterPlanMode",
    "ExitPlanMode",
    "KillShell",
    "NotebookEdit",
    "Skill",
    "Task",
    "TaskOutput",
    "TodoWrite",
    "WebFetch",
    "WebSearch",
)

private val CLAUDE_CODE_TOOL_LOOKUP: Map<String, String> =
    CLAUDE_CODE_TOOLS.associateBy { it.lowercase() }

private val MESSAGE_EVENTS = setOf(
    "message_start",
    "message_delta",
    "message_stop",
    "content_block_start",
    "content_block_delta",
    "content_block_stop",
)

private val EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh", "max")

private val ANTHROPIC_STRICT_UNSUPPORTED_KEYWORDS = setOf(
    "minimum",
    "maximum",
    "exclusiveMinimum",
    "exclusiveMaximum",
    "multipleOf",
    "maxItems",
    "uniqueItems",
    "minContains",
    "maxContains",
    "minProperties",
    "maxProperties",
)

private val ANTHROPIC_STRICT_STRING_FORMATS = setOf(
    "date-time",
    "time",
    "date",
    "duration",
    "email",
    "hostname",
    "uri",
    "ipv4",
    "ipv6",
    "uuid",
)

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

private data class AnthropicOptions(
    val stream: StreamOptions,
    val thinkingEnabled: Boolean? = null,
    val thinkingBudgetTokens: Long? = null,
    val effort: String? = null,
    val thinkingDisplay: String? = null,
    val interleavedThinking: Boolean? = null,
    val toolChoice: JsonObject? = null,
)

private class AnthropicHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
    val errorType: String?,
    override val message: String,
) : RuntimeException(message)

private class AnthropicStreamException(message: String) : RuntimeException(message)

private class BuiltParams(
    val params: JsonObject,
    val hasPreferredStrictTools: Boolean,
    val providerThinkingLevel: String?,
    val betaFeatures: List<String>,
)

private class AnthropicStreamState(
    val output: AssistantMessage,
    val stream: AssistantMessageEventStream,
    val model: Model,
    val isOAuth: Boolean,
    val currentTools: List<Tool>,
) {
    val contentIndexByAnthropicIndex = mutableMapOf<Int, Int>()
    val partialJson = mutableMapOf<Int, String>()
    var inputTransformations: JsonArray? = null
    var usageModel: Model = model

    fun contentIndexFor(index: Int): Int? = contentIndexByAnthropicIndex[index]
}

class AnthropicMessagesApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = ANTHROPIC_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runAnthropicStream(transport, stream, model, context, AnthropicOptions(stream = options))
        }
        return stream
    }

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        assertRequestAuth(model, options.stream)
        val base = buildBaseOptions(model, context, options)
        val toolChoice = options.toolChoice?.let { choice ->
            buildJsonObject { put("type", JsonPrimitive(choice.wireName())) }
        }
        if (options.reasoning == null) {
            return stream(
                model,
                context,
                base,
                AnthropicOptions(stream = base, thinkingEnabled = false, toolChoice = toolChoice),
            )
        }
        val compat = resolveAnthropicMessagesCompat(model)
        val reasoningLevel = options.reasoning.toModelThinkingLevel()
        if (compat.forceAdaptiveThinking) {
            return stream(
                model,
                context,
                base,
                AnthropicOptions(
                    stream = base,
                    thinkingEnabled = true,
                    effort = mapThinkingLevelToEffort(model, reasoningLevel),
                    toolChoice = toolChoice,
                ),
            )
        }
        val adjusted = adjustMaxTokensForThinking(
            base.maxTokens,
            model.maxTokens,
            reasoningLevel,
            options.thinkingBudgets?.let { budgets ->
                dev.pi.ai.ThinkingBudgets(
                    minimal = budgets.minimal,
                    low = budgets.low,
                    medium = budgets.medium,
                    high = budgets.high,
                )
            },
        )
        val maxTokens = clampMaxTokensToContext(model, context, adjusted.first)
        val budget = min(adjusted.second, maxOf(0L, maxTokens - ANTHROPIC_MIN_ANSWER_TOKENS))
        return stream(
            model,
            context,
            base.copy(maxTokens = maxTokens),
            AnthropicOptions(
                stream = base.copy(maxTokens = maxTokens),
                thinkingEnabled = true,
                thinkingBudgetTokens = budget,
                toolChoice = toolChoice,
            ),
        )
    }

    private suspend fun stream(
        model: Model,
        context: TranscriptContext,
        streamOptions: StreamOptions,
        anthropicOptions: AnthropicOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runAnthropicStream(transport, stream, model, context, anthropicOptions)
        }
        return stream
    }
}

fun anthropicMessagesApi(transport: HttpTransport): ApiProvider = AnthropicMessagesApi(transport)

private fun ToolChoice.wireName(): String = when (this) {
    ToolChoice.Auto -> "auto"
    ToolChoice.None -> "none"
}

private fun toClaudeCodeName(name: String): String = CLAUDE_CODE_TOOL_LOOKUP[name.lowercase()] ?: name

private fun fromClaudeCodeName(name: String, tools: List<Tool>): String {
    if (tools.isNotEmpty()) {
        val lowerName = name.lowercase()
        tools.firstOrNull { it.name.lowercase() == lowerName }?.let { return it.name }
    }
    return name
}

private fun isOAuthToken(apiKey: String?): Boolean = apiKey?.contains("sk-ant-oat") == true

private fun hasHeader(headers: Map<String, String?>?, name: String): Boolean {
    if (headers == null) return false
    val expected = name.lowercase()
    return headers.any { (key, value) ->
        key.lowercase() == expected && value != null && value.trim().isNotEmpty()
    }
}

private fun hasRequestAuth(apiKey: String?, headers: Map<String, String?>?): Boolean =
    !apiKey.isNullOrEmpty() || hasHeader(headers, "authorization") || hasHeader(headers, "x-api-key") ||
        hasHeader(headers, "cf-aig-authorization")

private fun assertRequestAuth(model: Model, options: StreamOptions) {
    if (!hasRequestAuth(options.apiKey, options.headers)) {
        throw IllegalStateException("No API key for provider: ${model.provider}")
    }
}

private fun zeroCost(): UsageCost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0)

private fun mergeHeaders(
    base: Map<String, String?>?,
    override: Map<String, String?>?,
): Map<String, String> {
    val merged = LinkedHashMap<String, String>()
    base?.forEach { (key, value) -> if (value != null) merged[key] = value }
    override?.forEach { (key, value) ->
        merged.keys.firstOrNull { it.equals(key, ignoreCase = true) }?.let { merged.remove(it) }
        if (value != null) merged[key] = value
    }
    return merged
}

private fun standardHeaders(): Map<String, String> = linkedMapOf(
    "accept" to "application/json",
    "anthropic-dangerous-direct-browser-access" to "true",
)

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

private fun hasCopilotVisionInput(messages: List<Message>): Boolean = messages.any { message ->
    when (message) {
        is UserMessage -> {
            val content = message.content
            content is dev.pi.ai.BlockContent && content.blocks.any { it is ImageContent }
        }

        is ToolResultMessage -> message.content.any { it is ImageContent }
        else -> false
    }
}

private fun copilotDynamicHeaders(messages: List<Message>): Map<String, String> {
    val last = messages.lastOrNull()
    val initiator = if (last != null && last !is UserMessage) "agent" else "user"
    val headers = linkedMapOf("X-Initiator" to initiator, "Openai-Intent" to "conversation-edits")
    if (hasCopilotVisionInput(messages)) headers["Copilot-Vision-Request"] = "true"
    return headers
}

private fun getCacheControl(
    model: Model,
    compat: ResolvedAnthropicMessagesCompat,
    cacheRetention: CacheRetention,
): JsonObject? {
    if (cacheRetention == CacheRetention.None) return null
    val ttl = if (cacheRetention == CacheRetention.Long && compat.supportsLongCacheRetention) "1h" else null
    return buildJsonObject {
        put("type", JsonPrimitive("ephemeral"))
        if (ttl != null) put("ttl", JsonPrimitive(ttl))
    }
}

private fun resolveCacheSessionId(options: StreamOptions): String? {
    val retention = resolveCacheRetention(options.cacheRetention, options.env)
    return if (retention == CacheRetention.None) null else options.sessionId
}

private fun buildRequestHeaders(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    compat: ResolvedAnthropicMessagesCompat,
    isOAuth: Boolean,
    betaFeatures: List<String>,
    cacheSessionId: String?,
): Map<String, String> {
    val apiKey = options.apiKey
    val base = LinkedHashMap<String, String>()
    if (model.provider == "github-copilot") {
        base.putAll(identityHeaders(model, options.identity))
        base.putAll(standardHeaders())
        model.headers?.let(base::putAll)
        base.putAll(copilotDynamicHeaders(context.messages))
    } else if (isOAuth) {
        base.putAll(standardHeaders())
        base["user-agent"] = "claude-cli/$CLAUDE_CODE_VERSION"
        base["x-app"] = "cli"
        model.headers?.let(base::putAll)
    } else {
        base.putAll(identityHeaders(model, options.identity))
        base.putAll(standardHeaders())
        if (cacheSessionId != null && compat.sendSessionAffinityHeaders) {
            val header = if (compat.sessionAffinityFormat == AnthropicSessionAffinityFormat.Openrouter) {
                "x-session-id"
            } else {
                "x-session-affinity"
            }
            base[header] = cacheSessionId
        }
        model.headers?.let(base::putAll)
    }
    options.headers?.forEach { (name, value) ->
        base.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { base.remove(it) }
        if (value != null) base[name] = value
    }
    if (model.provider == "github-copilot" || isOAuth) {
        if (!hasHeader(base, "authorization") && !apiKey.isNullOrEmpty()) {
            base["Authorization"] = "Bearer $apiKey"
        }
    } else if (!apiKey.isNullOrEmpty()) {
        if (!hasHeader(base, "x-api-key")) base["x-api-key"] = apiKey
    }
    if (!hasHeader(base, "anthropic-version")) base["anthropic-version"] = ANTHROPIC_VERSION
    if (!hasHeader(base, "content-type")) base["Content-Type"] = "application/json"
    if (betaFeatures.isNotEmpty()) {
        base["anthropic-beta"] = betaFeatures.joinToString(",")
    } else {
        base.keys.firstOrNull { it.equals("anthropic-beta", ignoreCase = true) }?.let(base::remove)
    }
    return base
}

private fun getBetaFeatures(
    model: Model,
    context: TranscriptContext,
    isOAuth: Boolean,
    nativeToolChanges: Boolean,
    options: AnthropicOptions,
    compat: ResolvedAnthropicMessagesCompat,
): List<String> {
    var configuredFeatures: String? = null
    var configuredExplicitlyNull = false
    for (headers in listOf(model.headers, options.stream.headers)) {
        headers?.forEach { (name, value) ->
            if (name.lowercase() == "anthropic-beta") {
                if (value == null) {
                    configuredExplicitlyNull = true
                    configuredFeatures = null
                } else {
                    configuredExplicitlyNull = false
                    configuredFeatures = value
                }
            }
        }
    }
    if (configuredExplicitlyNull) return emptyList()
    if (configuredFeatures != null) {
        return configuredFeatures!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
    val features = mutableListOf<String>()
    if (isOAuth) {
        features.add("claude-code-20250219")
        features.add("oauth-2025-04-20")
    }
    if (getCurrentTools(context.messages).isNotEmpty() && !compat.supportsEagerToolInputStreaming) {
        features.add(FINE_GRAINED_TOOL_STREAMING_BETA)
    }
    if (model.reasoning && options.thinkingEnabled == true && (options.interleavedThinking ?: true) &&
        !compat.forceAdaptiveThinking
    ) {
        features.add(INTERLEAVED_THINKING_BETA)
    }
    if (compat.allowedFallbackModels.isNotEmpty()) features.add(SERVER_SIDE_FALLBACK_BETA)
    if (compat.supportsMidConvoEffort) {
        features.add(MID_CONVERSATION_OUTPUT_CONFIG_BETA)
        features.add(THINKING_BINDING_CONTROLS_BETA)
    }
    if (nativeToolChanges) features.add(INLINE_TOOLS_BETA)
    return features.distinct()
}

private fun mapThinkingLevelToEffort(model: Model, level: ModelThinkingLevel): String {
    model.thinkingLevelMap?.get(level.jsonName())?.let { if (it.isNotEmpty()) return it }
    return when (level) {
        ModelThinkingLevel.Minimal, ModelThinkingLevel.Low -> "low"
        ModelThinkingLevel.Medium -> "medium"
        ModelThinkingLevel.High -> "high"
        else -> "high"
    }
}

private fun adjustMaxTokensForThinking(
    baseMaxTokens: Long?,
    modelMaxTokens: Long,
    reasoningLevel: ModelThinkingLevel,
    budgets: dev.pi.ai.ThinkingBudgets?,
): Pair<Long, Long> {
    var thinkingBudget = thinkingBudgetForLevel(reasoningLevel, budgets)
    val maxTokens = if (baseMaxTokens == null) modelMaxTokens else min(baseMaxTokens + thinkingBudget, modelMaxTokens)
    if (maxTokens <= thinkingBudget) {
        thinkingBudget = clampThinkingBudgetToAnswerRoom(thinkingBudget, maxTokens)
    }
    return maxTokens to thinkingBudget
}

private fun isOffExplicitlyUnsupported(model: Model): Boolean {
    val map = model.thinkingLevelMap ?: return false
    return map.containsKey("off") && map["off"] == null
}

private fun isEffort(value: String?): Boolean = value != null && value in EFFORT_LEVELS

private fun buildParams(
    model: Model,
    context: TranscriptContext,
    options: AnthropicOptions,
    compat: ResolvedAnthropicMessagesCompat,
    isOAuth: Boolean,
    disablePreferredStrictTools: Boolean,
): BuiltParams {
    val cacheRetention = resolveCacheRetention(options.stream.cacheRetention, options.stream.env)
    val cacheControl = getCacheControl(model, compat, cacheRetention)
    val initialSystemMessage = getInitialSystemMessage(context.messages)
    val initialSystemText = initialSystemMessage?.let { getSystemMessageText(it) }.orEmpty()
    val transformedMessages = transformMessages(context.messages, model) { id ->
        id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    }
    val conversationMessages = if (initialSystemMessage != null) transformedMessages.drop(1) else transformedMessages

    var hasPreferredStrictTools = false
    val convertToolDefinitions: (List<Tool>, JsonObject?) -> List<JsonObject> = { tools, toolCacheControl ->
        val definitions = convertTools(
            tools,
            isOAuth,
            compat.supportsEagerToolInputStreaming,
            compat.supportsStrictTools,
            toolCacheControl,
            disablePreferredStrictTools,
        )
        if (definitions.indices.any { index ->
                definitions[index]["strict"] == JsonPrimitive(true) &&
                    tools[index].constrainedSampling.let { config ->
                        config is JsonObject && stringField(config, "type") == "json_schema" &&
                            stringField(config, "strict") == "prefer"
                    }
            }
        ) {
            hasPreferredStrictTools = true
        }
        definitions
    }

    val initialTools = initialSystemMessage?.toolsAdded.orEmpty()
    val nativeToolChanges = compat.supportsMidConvoSystemMessages && compat.supportsMidConvoToolChanges &&
        initialTools.isNotEmpty()
    val thinkingDisabled = model.reasoning && options.thinkingEnabled == false && !isOffExplicitlyUnsupported(model)
    val effortMessages = compat.supportsMidConvoEffort && !thinkingDisabled
    val converted = convertMessages(
        conversationMessages,
        isOAuth,
        cacheControl,
        compat.allowEmptySignature,
        if (effortMessages) model.provider else null,
        if (nativeToolChanges) convertToolDefinitions else null,
    )
    val activeEffort = options.effort ?: "high"
    val betaFeatures = getBetaFeatures(model, context, isOAuth, nativeToolChanges, options, compat)
    val toolCacheControl = if (compat.supportsCacheControlOnTools) cacheControl else null
    val params = LinkedHashMap<String, JsonElement>()
    params["model"] = JsonPrimitive(model.id)
    params["messages"] = JsonArray(
        if (effortMessages) insertThinkingLevelMessages(converted, activeEffort) else converted.messages,
    )
    params["max_tokens"] = JsonPrimitive(options.stream.maxTokens ?: model.maxTokens)
    params["stream"] = JsonPrimitive(true)

    if (isOAuth) {
        val system = mutableListOf<JsonObject>()
        system.add(
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(CLAUDE_CODE_IDENTITY))
                if (cacheControl != null) put("cache_control", cacheControl)
            },
        )
        if (initialSystemText.isNotEmpty()) {
            system.add(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(sanitizeSurrogates(initialSystemText)))
                    if (cacheControl != null) put("cache_control", cacheControl)
                },
            )
        }
        params["system"] = JsonArray(system)
    } else if (initialSystemText.isNotEmpty()) {
        params["system"] = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(sanitizeSurrogates(initialSystemText)))
                    if (cacheControl != null) put("cache_control", cacheControl)
                },
            ),
        )
    }

    if (
        options.stream.temperature != null && options.thinkingEnabled != true &&
        !compat.supportsMidConvoEffort && compat.supportsTemperature
    ) {
        params["temperature"] = JsonPrimitive(options.stream.temperature)
    }

    if (nativeToolChanges) {
        val tools = convertToolDefinitions(initialTools, toolCacheControl).toMutableList()
        tools.add(deferredToolPlaceholder())
        params["tools"] = JsonArray(tools)
    } else {
        val tools = getCurrentTools(context.messages)
        if (tools.isNotEmpty()) params["tools"] = JsonArray(convertToolDefinitions(tools, toolCacheControl))
    }

    if (thinkingDisabled) {
        params["thinking"] = buildJsonObject { put("type", JsonPrimitive("disabled")) }
    } else if (effortMessages) {
        params["thinking"] = buildJsonObject {
            put("type", JsonPrimitive("adaptive"))
            put("display", JsonPrimitive(options.thinkingDisplay ?: "summarized"))
            put(
                "block_binding",
                buildJsonObject { put("prefix_mismatch_behavior", JsonPrimitive("drop_block")) },
            )
        }
        params["output_config"] = buildJsonObject { put("effort", JsonPrimitive("high")) }
    } else if (model.reasoning && options.thinkingEnabled == true) {
        val display = options.thinkingDisplay ?: "summarized"
        if (compat.forceAdaptiveThinking) {
            params["thinking"] = buildJsonObject {
                put("type", JsonPrimitive("adaptive"))
                put("display", JsonPrimitive(display))
            }
            options.effort?.let { effort ->
                params["output_config"] = buildJsonObject { put("effort", JsonPrimitive(effort)) }
            }
        } else {
            params["thinking"] = buildJsonObject {
                put("type", JsonPrimitive("enabled"))
                put("budget_tokens", JsonPrimitive(options.thinkingBudgetTokens ?: 1024L))
                put("display", JsonPrimitive(display))
            }
        }
    }

    options.stream.metadata?.let { metadata ->
        val userId = metadata["user_id"] as? JsonPrimitive
        if (userId != null && userId.isString) {
            params["metadata"] = buildJsonObject { put("user_id", JsonPrimitive(userId.content)) }
        }
    }

    options.toolChoice?.let { params["tool_choice"] = it }

    if (compat.allowedFallbackModels.isNotEmpty()) {
        params["fallbacks"] = JsonArray(
            compat.allowedFallbackModels.map { fallback ->
                buildJsonObject { put("model", JsonPrimitive(fallback.model)) }
            },
        )
    }

    resolveSamplingParams(model, ModelThinkingLevel.Off, options.stream.samplingParams)?.forEach {
            (key, value) ->
        params[key] = value
    }

    return BuiltParams(
        params = JsonObject(params),
        hasPreferredStrictTools = hasPreferredStrictTools,
        providerThinkingLevel = if (effortMessages) activeEffort else null,
        betaFeatures = betaFeatures,
    )
}

private fun deferredToolPlaceholder(): JsonObject = buildJsonObject {
    put("name", JsonPrimitive("__pi_deferred_placeholder__"))
    put("description", JsonPrimitive("Reserved placeholder. Never available. Never call this."))
    put(
        "input_schema",
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
            put("required", JsonArray(emptyList()))
        },
    )
    put("defer_loading", JsonPrimitive(true))
}

private class ConvertedMessages(
    val messages: List<JsonObject>,
    val assistantLevels: Map<Int, String>,
)

private fun convertImageBlock(image: ImageContent): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("image"))
    put(
        "source",
        buildJsonObject {
            put("type", JsonPrimitive("base64"))
            put("media_type", JsonPrimitive(image.mimeType))
            put("data", JsonPrimitive(image.data))
        },
    )
}

private fun convertContentBlocks(content: List<Content>): JsonElement {
    val hasImages = content.any { it is ImageContent }
    if (!hasImages) {
        return JsonPrimitive(sanitizeSurrogates(content.filterIsInstance<TextContent>().joinToString("\n") { it.text }))
    }
    val blocks = content.mapNotNull { block ->
        when (block) {
            is TextContent -> buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
            }

            is ImageContent -> convertImageBlock(block)
            else -> null
        }
    }.toMutableList()
    if (blocks.none { stringField(it, "type") == "text" }) {
        blocks.add(
            0,
            buildJsonObject {
                put("type", JsonPrimitive("text"))
                put("text", JsonPrimitive("(see attached image)"))
            },
        )
    }
    return JsonArray(blocks)
}

private data class SplitErrorToolResult(
    val content: List<Content>,
    val caption: String,
    val images: List<ImageContent>,
)

private fun splitErrorToolResultImages(message: ToolResultMessage): SplitErrorToolResult? {
    if (!message.isError) return null
    val images = message.content.filterIsInstance<ImageContent>()
    if (images.isEmpty()) return null
    return SplitErrorToolResult(
        content = replaceImagesWithPlaceholder(message.content),
        caption = "Images from the error result of tool call ${message.toolCallId} (${message.toolName}):",
        images = images,
    )
}

private fun replaceImagesWithPlaceholder(content: List<Content>): List<Content> {
    val placeholder = "(image attached after the tool results)"
    val result = mutableListOf<Content>()
    var previousWasPlaceholder = false
    for (block in content) {
        if (block is ImageContent) {
            if (!previousWasPlaceholder) result.add(TextContent(placeholder))
            previousWasPlaceholder = true
            continue
        }
        result.add(block)
        previousWasPlaceholder = block is TextContent && block.text == placeholder
    }
    return result
}

private fun convertToolResult(message: ToolResultMessage, content: List<Content>): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("tool_result"))
    put("tool_use_id", JsonPrimitive(message.toolCallId))
    put("content", convertContentBlocks(content))
    put("is_error", JsonPrimitive(message.isError))
}

private fun convertMessages(
    messages: List<Message>,
    isOAuth: Boolean,
    cacheControl: JsonObject?,
    allowEmptySignature: Boolean,
    managedProvider: String?,
    convertToolDefinitions: ((List<Tool>, JsonObject?) -> List<JsonObject>)?,
): ConvertedMessages {
    val params = mutableListOf<JsonObject>()
    val assistantLevels = LinkedHashMap<Int, String>()
    val pendingSystemMessages = mutableListOf<JsonObject>()
    fun flushPendingSystemMessages() {
        params.addAll(pendingSystemMessages)
        pendingSystemMessages.clear()
    }

    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        when (message) {
            is SystemMessage -> {
                val text = renderSystemMessageUpdate(message)
                val blocks = mutableListOf<JsonObject>()
                if (text.isNotEmpty()) {
                    blocks.add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(sanitizeSurrogates(text)))
                        },
                    )
                }
                if (convertToolDefinitions != null) {
                    val added = message.toolsAdded.orEmpty()
                    val redefined = added.map { it.name }.toSet()
                    for (tool in message.toolsRemoved.orEmpty()) {
                        if (tool.name in redefined) continue
                        blocks.add(
                            buildJsonObject {
                                put("type", JsonPrimitive("tool_removal"))
                                put(
                                    "tool",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("tool_reference"))
                                        put(
                                            "name",
                                            JsonPrimitive(if (isOAuth) toClaudeCodeName(tool.name) else tool.name),
                                        )
                                    },
                                )
                            },
                        )
                    }
                    for (definition in convertToolDefinitions(added, null)) {
                        blocks.add(
                            buildJsonObject {
                                put("type", JsonPrimitive("tool_addition"))
                                put(
                                    "tool",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("tool_definition"))
                                        put("definition", definition)
                                    },
                                )
                            },
                        )
                    }
                }
                if (blocks.isNotEmpty()) pendingSystemMessages.add(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonArray(blocks))))
            }

            is UserMessage -> {
                when (val content = message.content) {
                    is dev.pi.ai.PlainTextContent -> {
                        if (content.text.trim().isNotEmpty()) {
                            params.add(
                                JsonObject(
                                    mapOf(
                                        "role" to JsonPrimitive("user"),
                                        "content" to JsonPrimitive(sanitizeSurrogates(content.text)),
                                    ),
                                ),
                            )
                        }
                    }

                    is dev.pi.ai.BlockContent -> {
                        val blocks = content.blocks.mapNotNull { block ->
                            when (block) {
                                is TextContent -> buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
                                }

                                is ImageContent -> convertImageBlock(block)
                                else -> null
                            }
                        }.filter { block ->
                            if (stringField(block, "type") == "text") {
                                (stringField(block, "text") ?: "").trim().isNotEmpty()
                            } else {
                                true
                            }
                        }
                        if (blocks.isEmpty()) {
                            index += 1
                            continue
                        }
                        params.add(
                            JsonObject(
                                mapOf(
                                    "role" to JsonPrimitive("user"),
                                    "content" to JsonArray(blocks),
                                ),
                            ),
                        )
                    }
                }
            }

            is AssistantMessage -> {
                flushPendingSystemMessages()
                val blocks = mutableListOf<JsonObject>()
                for (block in message.content) {
                    when (block) {
                        is TextContent -> {
                            if (block.text.trim().isEmpty()) continue
                            blocks.add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
                                },
                            )
                        }

                        is ThinkingContent -> {
                            if (block.redacted == true) {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("redacted_thinking"))
                                        put("data", JsonPrimitive(block.thinkingSignature ?: ""))
                                    },
                                )
                                continue
                            }
                            val signature = block.thinkingSignature
                            val hasSignature = !signature.isNullOrBlank()
                            if (block.thinking.trim().isEmpty() && !hasSignature) continue
                            if (!hasSignature) {
                                if (allowEmptySignature) {
                                    blocks.add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("thinking"))
                                            put("thinking", JsonPrimitive(sanitizeSurrogates(block.thinking)))
                                            put("signature", JsonPrimitive(""))
                                        },
                                    )
                                } else {
                                    blocks.add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("text"))
                                            put("text", JsonPrimitive(sanitizeSurrogates(block.thinking)))
                                        },
                                    )
                                }
                            } else {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("thinking"))
                                        put("thinking", JsonPrimitive(sanitizeSurrogates(block.thinking)))
                                        put("signature", JsonPrimitive(signature!!))
                                    },
                                )
                            }
                        }

                        is ToolCall -> {
                            blocks.add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("tool_use"))
                                    put("id", JsonPrimitive(block.id))
                                    put(
                                        "name",
                                        JsonPrimitive(if (isOAuth) toClaudeCodeName(block.name) else block.name),
                                    )
                                    put("input", block.arguments)
                                },
                            )
                        }

                        else -> Unit
                    }
                }
                if (blocks.isEmpty()) {
                    index += 1
                    continue
                }
                val messageIndex = params.size
                params.add(
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive("assistant"),
                            "content" to JsonArray(blocks),
                        ),
                    ),
                )
                if (managedProvider != null && message.api == ANTHROPIC_API && message.provider == managedProvider &&
                    isEffort(message.providerThinkingLevel)
                ) {
                    assistantLevels[messageIndex] = message.providerThinkingLevel!!
                }
            }

            is ToolResultMessage -> {
                val toolResults = mutableListOf<JsonObject>()
                val errorImages = mutableListOf<JsonObject>()
                var next = index
                while (next < messages.size && messages[next] is ToolResultMessage) {
                    val toolMessage = messages[next] as ToolResultMessage
                    val split = splitErrorToolResultImages(toolMessage)
                    toolResults.add(convertToolResult(toolMessage, split?.content ?: toolMessage.content))
                    if (split != null) {
                        errorImages.add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(split.caption))
                            },
                        )
                        for (image in split.images) errorImages.add(convertImageBlock(image))
                    }
                    next += 1
                }
                index = next - 1
                val content = toolResults + errorImages
                params.add(
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive("user"),
                            "content" to JsonArray(content),
                        ),
                    ),
                )
            }
        }
        index += 1
    }

    flushPendingSystemMessages()

    if (cacheControl != null && params.isNotEmpty()) {
        val lastIndex = params.size - 1
        val lastMessage = params[lastIndex]
        val role = stringField(lastMessage, "role")
        if (role == "user" || role == "system") {
            when (val content = lastMessage["content"]) {
                is JsonArray -> {
                    if (content.isNotEmpty()) {
                        val lastBlockIndex = content.size - 1
                        val lastBlock = content[lastBlockIndex] as? JsonObject
                        val blockType = lastBlock?.let { stringField(it, "type") }
                        if (lastBlock != null && blockType in setOf(
                                "text",
                                "image",
                                "tool_result",
                                "tool_addition",
                                "tool_removal",
                            )
                        ) {
                            val updated = lastBlock.toMutableMap()
                            updated["cache_control"] = cacheControl
                            val newContent = content.toMutableList()
                            newContent[lastBlockIndex] = JsonObject(updated)
                            val newMessage = lastMessage.toMutableMap()
                            newMessage["content"] = JsonArray(newContent)
                            params[lastIndex] = JsonObject(newMessage)
                        }
                    }
                }

                is JsonPrimitive -> {
                    if (content.isString) {
                        val newMessage = lastMessage.toMutableMap()
                        newMessage["content"] = JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(content.content))
                                    put("cache_control", cacheControl)
                                },
                            ),
                        )
                        params[lastIndex] = JsonObject(newMessage)
                    }
                }

                else -> Unit
            }
        }
    }

    return ConvertedMessages(params, assistantLevels)
}

private fun insertThinkingLevelMessages(converted: ConvertedMessages, activeEffort: String): List<JsonObject> {
    val messages = mutableListOf<JsonObject>()
    for (index in converted.messages.indices) {
        val historicalEffort = converted.assistantLevels[index]
        if (historicalEffort != null) {
            messages.add(
                buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonArray(emptyList()))
                    put("output_config", buildJsonObject { put("effort", JsonPrimitive(historicalEffort)) })
                },
            )
        }
        messages.add(converted.messages[index])
    }
    messages.add(
        buildJsonObject {
            put("role", JsonPrimitive("system"))
            put("content", JsonArray(emptyList()))
            put("output_config", buildJsonObject { put("effort", JsonPrimitive(activeEffort)) })
        },
    )
    return messages
}

private fun convertTools(
    tools: List<Tool>,
    isOAuth: Boolean,
    supportsEagerToolInputStreaming: Boolean,
    supportsStrictTools: Boolean,
    cacheControl: JsonObject?,
    disablePreferredStrictTools: Boolean,
): List<JsonObject> = tools.mapIndexed { index, tool ->
    val config = tool.constrainedSampling
    val strict = if (
        disablePreferredStrictTools && config is JsonObject && stringField(config, "type") == "json_schema" &&
        stringField(config, "strict") == "prefer"
    ) {
        null
    } else {
        resolveAnthropicStrictSampling(tool, supportsStrictTools)
    }
    val parameters = if (strict == true) makeStrictJsonSchema(tool.parameters) else tool.parameters
    val schemaProperties = parameters["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val schemaRequired = parameters["required"] as? JsonArray ?: JsonArray(emptyList())
    val legacyInputSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", schemaProperties)
        put("required", schemaRequired)
    }
    val inputSchema = if (strict == true) {
        JsonObject(parameters.toMutableMap().apply { putAll(legacyInputSchema) })
    } else {
        legacyInputSchema
    }
    buildJsonObject {
        put("name", JsonPrimitive(if (isOAuth) toClaudeCodeName(tool.name) else tool.name))
        put("description", JsonPrimitive(tool.description))
        if (supportsEagerToolInputStreaming) put("eager_input_streaming", JsonPrimitive(true))
        if (strict == true) put("strict", JsonPrimitive(true))
        put("input_schema", inputSchema)
        if (cacheControl != null && index == tools.size - 1) put("cache_control", cacheControl)
    }
}

private class UnsupportedAnthropicStrictSchemaException(message: String) : RuntimeException(message)

private fun isStructuredSchema(schema: JsonElement): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeElement = obj["type"]
    val types = when {
        typeElement is JsonPrimitive && typeElement.isString -> listOf(typeElement.content)
        typeElement is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }
    return types.contains("object") || types.contains("array") ||
        obj.containsKey("properties") || obj.containsKey("items")
}

private fun schemaAllowsNull(schema: JsonElement?): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeElement = obj["type"]
    if (typeElement is JsonPrimitive && typeElement.isString && typeElement.content == "null") return true
    if (typeElement is JsonArray && typeElement.any {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "null"
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

private fun isAnthropicStrictUnsupportedKeyword(key: String, value: JsonElement): Boolean {
    if (key in ANTHROPIC_STRICT_UNSUPPORTED_KEYWORDS) return true
    if (key == "minItems") {
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        return number != 0.0 && number != 1.0
    }
    if (key == "format") {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return true
        return text !in ANTHROPIC_STRICT_STRING_FORMATS
    }
    return false
}

private fun makeStrictNode(schema: JsonElement): JsonObject {
    val obj = schema as? JsonObject
        ?: throw UnsupportedAnthropicStrictSchemaException("boolean schemas are unsupported")
    for (key in UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (obj.containsKey(key)) throw UnsupportedAnthropicStrictSchemaException("$key schemas are unsupported")
    }
    for ((key, value) in obj) {
        if (isAnthropicStrictUnsupportedKeyword(key, value)) {
            throw UnsupportedAnthropicStrictSchemaException("$key: ${value} is unsupported")
        }
    }
    val result = LinkedHashMap(obj)
    obj["anyOf"]?.let { anyOf ->
        if (anyOf !is JsonArray || anyOf.isEmpty()) {
            throw UnsupportedAnthropicStrictSchemaException("anyOf must contain at least one schema")
        }
        result["anyOf"] = JsonArray(
            anyOf.map { variant ->
                if (isStructuredSchema(variant)) {
                    throw UnsupportedAnthropicStrictSchemaException("object and array unions are unsupported")
                }
                makeStrictNode(variant)
            },
        )
    }
    obj["items"]?.let { items ->
        if (items is JsonArray) throw UnsupportedAnthropicStrictSchemaException("tuple schemas are unsupported")
        result["items"] = makeStrictNode(items)
    }
    val isObjectSchema = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object"
    if (obj.containsKey("properties") && !isObjectSchema) {
        throw UnsupportedAnthropicStrictSchemaException("properties require type object")
    }
    if (!isObjectSchema) return JsonObject(result)
    obj["additionalProperties"]?.let { additional ->
        if (additional != JsonPrimitive(false)) {
            throw UnsupportedAnthropicStrictSchemaException(
                "schema-valued or true additionalProperties is unsupported",
            )
        }
    }
    val propertiesElement = obj["properties"]
    if (propertiesElement != null && propertiesElement !is JsonObject) {
        throw UnsupportedAnthropicStrictSchemaException("object properties must be a schema map")
    }
    val properties = propertiesElement as? JsonObject ?: JsonObject(emptyMap())
    val requiredElement = obj["required"]
    if (requiredElement != null) {
        val valid = requiredElement is JsonArray && requiredElement.all { element ->
            (element as? JsonPrimitive)?.isString == true
        }
        if (!valid) throw UnsupportedAnthropicStrictSchemaException("object required must be a string array")
    }
    val required = (requiredElement as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.toSet()
        .orEmpty()
    val propertyNames = properties.keys.toList()
    if (required.any { it !in propertyNames }) {
        throw UnsupportedAnthropicStrictSchemaException("required contains an unknown property")
    }
    val strictProperties = LinkedHashMap<String, JsonElement>()
    for ((key, property) in properties) {
        val strictProperty = makeStrictNode(property)
        strictProperties[key] = if (key !in required && !schemaAllowsNull(property)) {
            buildJsonObject {
                put(
                    "anyOf",
                    JsonArray(listOf(strictProperty, buildJsonObject { put("type", JsonPrimitive("null")) })),
                )
            }
        } else {
            strictProperty
        }
    }
    result["properties"] = JsonObject(strictProperties)
    result["required"] = JsonArray(propertyNames.map { JsonPrimitive(it) })
    result["additionalProperties"] = JsonPrimitive(false)
    return JsonObject(result)
}

private fun makeStrictJsonSchema(parameters: JsonObject): JsonObject {
    val root = makeStrictNode(parameters)
    if ((root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "object") {
        throw UnsupportedAnthropicStrictSchemaException("root schema must have type object")
    }
    return root
}

private fun resolveAnthropicStrictSampling(tool: Tool, supportsStrictTools: Boolean): Boolean? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "json_schema") return null
    if (supportsStrictTools) {
        return try {
            makeStrictJsonSchema(tool.parameters)
            true
        } catch (error: UnsupportedAnthropicStrictSchemaException) {
            if (stringField(config, "strict") != "require") return null
            throw IllegalStateException(
                "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but ${error.message}.",
            )
        }
    }
    if (stringField(config, "strict") == "require") {
        throw IllegalStateException(
            "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
        )
    }
    return null
}

private fun mapStopReason(reason: String, stopDetails: JsonObject?): Pair<StopReason, String?> = when (reason) {
    "end_turn" -> Pair(StopReason.Stop, null)
    "max_tokens" -> Pair(StopReason.Length, null)
    "tool_use" -> Pair(StopReason.ToolUse, null)
    "refusal" -> Pair(
        StopReason.Error,
        stringField(stopDetails ?: JsonObject(emptyMap()), "explanation")
            ?: "The model refused to complete the request",
    )

    "pause_turn" -> Pair(StopReason.Stop, null)
    "stop_sequence" -> Pair(StopReason.Stop, null)
    "sensitive" -> Pair(StopReason.Error, "Provider stopped with: sensitive")
    else -> throw AnthropicStreamException("Unhandled stop reason: $reason")
}

private fun parseChunkJson(text: String): JsonObject? = try {
    PiJson.parseToJsonElement(text) as? JsonObject
} catch (_: Throwable) {
    PartialJson.parse(text) as? JsonObject
}

private suspend fun runAnthropicStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: AnthropicOptions,
) {
    val signal = options.stream.signal
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage.empty(),
        stopReason = StopReason.Pending,
        timestamp = nowMillis(),
    )
    var state: AnthropicStreamState? = null
    try {
        val compat = resolveAnthropicMessagesCompat(model)
        val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
        val currentTools = getCurrentTools(normalizedContext.messages)
        val apiKey = options.stream.apiKey
        assertRequestAuth(model, options.stream)
        val isOAuth = isOAuthToken(apiKey)
        val streamState = AnthropicStreamState(output, stream, model, isOAuth, currentTools)
        state = streamState
        val cacheSessionId = resolveCacheSessionId(options.stream)
        var disablePreferredStrictTools = false
        var response: HttpResponse
        var built: BuiltParams
        while (true) {
            signal?.throwIfAborted()
            built = buildParams(model, normalizedContext, options, compat, isOAuth, disablePreferredStrictTools)
            if (isEffort(built.providerThinkingLevel)) {
                output.providerThinkingLevel = built.providerThinkingLevel
            }
            var params = built.params
            val payload = options.stream.onPayload?.invoke(params, model)
            if (payload is JsonObject) {
                val updated = payload.toMutableMap()
                updated["stream"] = JsonPrimitive(true)
                params = JsonObject(updated)
            }
            val headers = buildRequestHeaders(
                model,
                normalizedContext,
                options.stream,
                compat,
                isOAuth,
                built.betaFeatures,
                cacheSessionId,
            )
            try {
                response = executeAnthropicRequest(transport, model, params, headers, options.stream)
                break
            } catch (error: Throwable) {
                if (signal?.isAborted == true || disablePreferredStrictTools || !built.hasPreferredStrictTools ||
                    error !is AnthropicHttpException || error.status != 400 ||
                    error.errorType != "invalid_request_error" ||
                    !isPreferredStrictFallbackMessage(error.message)
                ) {
                    throw error
                }
                disablePreferredStrictTools = true
                appendAnthropicDiagnostic(
                    output,
                    type = "anthropic_strict_tools_fallback",
                    message = error.body,
                    details = buildJsonObject { put("reason", JsonPrimitive("strict_tool_limits_exceeded")) },
                )
            }
        }
        options.stream.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()

        val sse = SseParser(response.body)
        var sawMessageStart = false
        var sawMessageEnd = false
        try {
            while (true) {
                val event = sse.next() ?: break
                val eventName = event.event
                if (eventName == "error") throw AnthropicStreamException(event.data)
                if (eventName == null || eventName !in MESSAGE_EVENTS) continue
                val data = parseChunkJson(event.data)
                if (data == null) {
                    throw AnthropicStreamException("Could not parse Anthropic SSE event $eventName: data=${event.data}")
                }
                options.stream.onProviderStreamEvent?.invoke(data, model)
                when (stringField(data, "type")) {
                    "message_start" -> {
                        sawMessageStart = true
                        handleMessageStart(streamState, data)
                    }

                    "content_block_start" -> handleContentBlockStart(streamState, data)
                    "content_block_delta" -> handleContentBlockDelta(streamState, data)
                    "content_block_stop" -> handleContentBlockStop(streamState, data)
                    "message_delta" -> handleMessageDelta(streamState, data)
                    "message_stop" -> sawMessageEnd = true
                }
                signal?.throwIfAborted()
            }
        } finally {
            sse.close()
            response.close()
        }

        if (sawMessageStart && !sawMessageEnd) {
            throw AnthropicStreamException("Anthropic stream ended before message_stop")
        }
        signal?.throwIfAborted()
        if (output.stopReason == StopReason.Pending) {
            throw AnthropicStreamException("Anthropic stream ended without a stop reason")
        }
        if (output.stopReason == StopReason.Aborted || output.stopReason == StopReason.Error) {
            throw AnthropicStreamException(output.errorMessage ?: "An unknown error occurred")
        }
        val transformations = streamState.inputTransformations
        if (transformations != null && transformations.isNotEmpty()) {
            val details = buildJsonObject {
                put(
                    "transformations",
                    JsonArray(
                        transformations.mapNotNull { element ->
                            val obj = element as? JsonObject ?: return@mapNotNull null
                            buildJsonObject {
                                stringField(obj, "type")?.let { put("type", JsonPrimitive(it)) }
                                stringField(obj, "path")?.let { put("path", JsonPrimitive(it)) }
                                stringField(obj, "reason")?.let { put("reason", JsonPrimitive(it)) }
                            }
                        },
                    ),
                )
            }
            appendAnthropicDiagnostic(output, "anthropic_input_transformations", null, details)
        }
        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        val aborted = error is RequestAbortedException || signal?.isAborted == true ||
            output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else (error.message ?: error.toString())
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun isPreferredStrictFallbackMessage(message: String): Boolean = listOf(
    "The compiled grammar is too large",
    "Too many strict tools (",
    "Schemas contains too many parameters with union types (",
).any { message.contains(it) }

private fun appendAnthropicDiagnostic(
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
            timestamp = nowMillis(),
            error = message?.let { DiagnosticErrorInfo(message = it) },
            details = details,
        ),
    )
}

private fun handleMessageStart(state: AnthropicStreamState, data: JsonObject) {
    val output = state.output
    val message = objectField(data, "message") ?: return
    stringField(message, "id")?.let { output.responseId = it }
    (message["input_transformations"] as? JsonArray)?.let { state.inputTransformations = it }
    val responseModel = stringField(message, "model")
    if (responseModel != null && responseModel != state.model.id) {
        output.responseModel = responseModel
        val fallbackCost = state.model.compat?.let { _ ->
            resolveAnthropicMessagesCompat(state.model).allowedFallbackModels
                .firstOrNull { it.provider == state.model.provider && it.model == responseModel }
                ?.cost
        }
        if (fallbackCost != null) {
            state.usageModel = state.model.copy(id = responseModel, cost = fallbackCost)
        }
    }
    val usage = objectField(message, "usage") ?: JsonObject(emptyMap())
    output.usage.input = longField(usage, "input_tokens") ?: 0L
    output.usage.output = longField(usage, "output_tokens") ?: 0L
    output.usage.cacheRead = longField(usage, "cache_read_input_tokens") ?: 0L
    output.usage.cacheWrite = longField(usage, "cache_creation_input_tokens") ?: 0L
    val cacheCreation = objectField(usage, "cache_creation")
    output.usage.cacheWrite1h = cacheCreation?.let { longField(it, "ephemeral_1h_input_tokens") } ?: 0L
    output.usage.totalTokens =
        output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite
    output.usage.cost = calculateCost(state.usageModel, output.usage)
}

private fun handleContentBlockStart(state: AnthropicStreamState, data: JsonObject) {
    val output = state.output
    val index = intField(data, "index") ?: return
    val block = objectField(data, "content_block") ?: return
    when (stringField(block, "type")) {
        "fallback" -> {
            if (output.content.isNotEmpty()) {
                throw AnthropicStreamException("Anthropic performed an unsupported mid-output model fallback")
            }
        }

        "text" -> {
            val contentIndex = output.content.size
            output.content.add(TextContent(text = stringField(block, "text") ?: ""))
            state.contentIndexByAnthropicIndex[index] = contentIndex
            state.stream.push(TextStartEvent(contentIndex, output))
        }

        "thinking" -> {
            val contentIndex = output.content.size
            output.content.add(
                ThinkingContent(
                    thinking = stringField(block, "thinking") ?: "",
                    thinkingSignature = stringField(block, "signature") ?: "",
                ),
            )
            state.contentIndexByAnthropicIndex[index] = contentIndex
            state.stream.push(ThinkingStartEvent(contentIndex, output))
        }

        "redacted_thinking" -> {
            val contentIndex = output.content.size
            output.content.add(
                ThinkingContent(
                    thinking = "[Reasoning redacted]",
                    thinkingSignature = stringField(block, "data") ?: "",
                    redacted = true,
                ),
            )
            state.contentIndexByAnthropicIndex[index] = contentIndex
            state.stream.push(ThinkingStartEvent(contentIndex, output))
        }

        "tool_use" -> {
            val contentIndex = output.content.size
            val name = stringField(block, "name") ?: ""
            output.content.add(
                ToolCall(
                    id = stringField(block, "id") ?: "",
                    name = if (state.isOAuth) fromClaudeCodeName(name, state.currentTools) else name,
                    arguments = objectField(block, "input") ?: JsonObject(emptyMap()),
                ),
            )
            state.contentIndexByAnthropicIndex[index] = contentIndex
            state.partialJson[contentIndex] = ""
            state.stream.push(ToolCallStartEvent(contentIndex, output))
        }
    }
}

private fun handleContentBlockDelta(state: AnthropicStreamState, data: JsonObject) {
    val output = state.output
    val index = intField(data, "index") ?: return
    val contentIndex = state.contentIndexFor(index) ?: return
    val delta = objectField(data, "delta") ?: return
    when (stringField(delta, "type")) {
        "text_delta" -> {
            val text = stringField(delta, "text") ?: return
            val block = output.content[contentIndex] as? TextContent ?: return
            block.text += text
            state.stream.push(TextDeltaEvent(contentIndex, text, output))
        }

        "thinking_delta" -> {
            val thinking = stringField(delta, "thinking") ?: return
            val block = output.content[contentIndex] as? ThinkingContent ?: return
            block.thinking += thinking
            state.stream.push(ThinkingDeltaEvent(contentIndex, thinking, output))
        }

        "input_json_delta" -> {
            val partial = stringField(delta, "partial_json") ?: return
            val block = output.content[contentIndex] as? ToolCall ?: return
            val accumulated = (state.partialJson[contentIndex] ?: "") + partial
            state.partialJson[contentIndex] = accumulated
            val parsed = PartialJson.parse(accumulated)
            output.content[contentIndex] = block.copy(arguments = parsed as? JsonObject ?: JsonObject(emptyMap()))
            state.stream.push(ToolCallDeltaEvent(contentIndex, partial, output))
        }

        "signature_delta" -> {
            val signature = stringField(delta, "signature") ?: return
            val block = output.content[contentIndex] as? ThinkingContent ?: return
            block.thinkingSignature = (block.thinkingSignature ?: "") + signature
        }
    }
}

private fun handleContentBlockStop(state: AnthropicStreamState, data: JsonObject) {
    val output = state.output
    val index = intField(data, "index") ?: return
    val contentIndex = state.contentIndexFor(index) ?: return
    when (val block = output.content.getOrNull(contentIndex)) {
        null -> return
        is TextContent -> state.stream.push(TextEndEvent(contentIndex, block.text, output))
        is ThinkingContent -> state.stream.push(ThinkingEndEvent(contentIndex, block.thinking, output))
        is ToolCall -> {
            val parsed = PartialJson.parseToolCallArguments(state.partialJson[contentIndex])
            val updated = block.copy(arguments = parsed.arguments, argumentsError = parsed.argumentsError)
            output.content[contentIndex] = updated
            state.partialJson.remove(contentIndex)
            state.stream.push(ToolCallEndEvent(contentIndex, updated, output))
        }

        else -> Unit
    }
}

private fun handleMessageDelta(state: AnthropicStreamState, data: JsonObject) {
    val output = state.output
    (data["input_transformations"] as? JsonArray)?.let { state.inputTransformations = it }
    val delta = objectField(data, "delta")
    val stopReason = delta?.let { stringField(it, "stop_reason") }
    if (stopReason != null) {
        output.rawStopReason = stopReason
        val mapped = mapStopReason(stopReason, delta?.let { objectField(it, "stop_details") })
        output.stopReason = mapped.first
        mapped.second?.let { output.errorMessage = it }
    }
    val usage = objectField(data, "usage")
    if (usage != null) {
        longField(usage, "input_tokens")?.let { output.usage.input = it }
        longField(usage, "output_tokens")?.let { output.usage.output = it }
        longField(usage, "cache_read_input_tokens")?.let { output.usage.cacheRead = it }
        longField(usage, "cache_creation_input_tokens")?.let { output.usage.cacheWrite = it }
        objectField(usage, "cache_creation")?.let { cacheCreation ->
            longField(cacheCreation, "ephemeral_1h_input_tokens")?.let { output.usage.cacheWrite1h = it }
        }
        objectField(usage, "output_tokens_details")?.let { details ->
            longField(details, "thinking_tokens")?.let { output.usage.reasoning = it }
        }
    }
    output.usage.totalTokens =
        output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite
    output.usage.cost = calculateCost(state.usageModel, output.usage)
}

private suspend fun executeAnthropicRequest(
    transport: HttpTransport,
    model: Model,
    params: JsonObject,
    headers: Map<String, String>,
    options: StreamOptions,
): HttpResponse {
    val signal = options.signal
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    val url = model.baseUrl.trimEnd('/') + "/v1/messages"
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
            Retry.sleep(anthropicRetryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), signal)
            continue
        }
        if (response.isSuccessful()) return response
        val retryable = isRetryableResponse(response)
        if (retriesRemaining > 0 && retryable) {
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
            val delayMs = anthropicRetryDelayMs(response, retryIndex, options.maxRetryDelayMs, providerMessage)
            retriesRemaining -= 1
            Retry.sleep(delayMs, signal)
            continue
        }
        throw anthropicHttpError(response)
    }
}

private fun isRetryableResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun anthropicRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        val parsedMs = response.header("retry-after-ms")?.toDoubleOrNull()
        if (parsedMs != null) {
            return validateServerRetryDelayMs(parsedMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        }
        val parsedSeconds = response.header("retry-after")?.toDoubleOrNull()
        if (parsedSeconds != null) {
            return validateServerRetryDelayMs(
                (parsedSeconds * 1000).toLong(),
                maxRetryDelayMs,
                providerErrorMessage,
            )
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateServerRetryDelayMs(
    delayMs: Long,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw AnthropicStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun anthropicHttpError(response: HttpResponse): AnthropicHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    val parsed = parseChunkJson(body)
    val errorObject = parsed?.let { objectField(it, "error") }
    val errorType = errorObject?.let { stringField(it, "type") }
    val providerMessage = errorObject?.let { stringField(it, "message") }
        ?: parsed?.let { stringField(it, "message") }
        ?: body
    val formatted = ProviderErrors.format(ProviderErrors.normalize(response.status, response.headers, body))
    return AnthropicHttpException(
        status = response.status,
        headers = response.headers,
        body = body,
        errorType = errorType,
        message = if (formatted.isBlank()) providerMessage else "$formatted\n$providerMessage",
    )
}
