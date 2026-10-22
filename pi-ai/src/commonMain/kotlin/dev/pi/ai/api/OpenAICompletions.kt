package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.BlockContent
import dev.pi.ai.CacheRetention
import dev.pi.ai.ClientIdentity
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelInputModality
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
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
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.ai.calculateCost
import dev.pi.ai.clampThinkingLevel
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.SseParser
import dev.pi.ai.utils.PartialJson
import dev.pi.ai.utils.ProviderErrors
import dev.pi.ai.utils.Retry
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
import kotlin.math.pow
import kotlin.random.Random

private const val COMPLETIONS_API = "openai-completions"
private const val OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L

private val REASONING_FIELDS = listOf("reasoning", "reasoning_content", "reasoning_text")

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

class OpenAICompletionsApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = COMPLETIONS_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = startStream(model, context, options, null, null, null)

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        getClientApiKey(model, options.stream)
        val base = buildBaseOptions(model, context, options)
        val clamped = options.reasoning?.let { clampThinkingLevel(model, it.toModelThinkingLevel()) }
        val reasoningEffort = if (clamped == null || clamped == ModelThinkingLevel.Off) null else clamped
        return startStream(
            model,
            context,
            base,
            options.toolChoice?.let { JsonPrimitive(it.wireName()) },
            reasoningEffort,
            options.thinkingBudgets,
        )
    }

    private suspend fun startStream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
        toolChoice: JsonElement?,
        reasoningEffort: ModelThinkingLevel?,
        thinkingBudgets: ThinkingBudgets?,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runCompletionsStream(
                transport,
                stream,
                model,
                context,
                options,
                toolChoice,
                reasoningEffort,
                thinkingBudgets,
            )
        }
        return stream
    }
}

fun openAICompletionsApi(transport: HttpTransport): ApiProvider = OpenAICompletionsApi(transport)

private fun ToolChoice.wireName(): String = when (this) {
    ToolChoice.Auto -> "auto"
    ToolChoice.None -> "none"
}

private class ProviderHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : RuntimeException("HTTP $status")

private class CompletionsStreamException(message: String) : RuntimeException(message)

private class GrammarInputBuffer(
    val property: String,
    var input: String = "",
    var started: Boolean = false,
    var closed: Boolean = false,
)

private data class GrammarSampling(
    val format: String,
    val definition: String,
    val inputProperty: String,
)

private fun zeroCost(): UsageCost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonObject.intOrNull(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.content.toIntOrNull()
}

private fun JsonObject.plusField(key: String, element: JsonElement?): JsonObject {
    if (element == null) return this
    val updated = toMutableMap()
    updated[key] = element
    return JsonObject(updated)
}

private fun hasHeader(headers: Map<String, String?>?, name: String): Boolean {
    if (headers == null) return false
    val expected = name.lowercase()
    return headers.any { (key, value) ->
        key.lowercase() == expected && value != null && value.trim().isNotEmpty()
    }
}

private fun getClientApiKey(model: Model, options: StreamOptions): String {
    options.apiKey?.let { return it }
    if (hasHeader(options.headers, "authorization") || hasHeader(options.headers, "cf-aig-authorization")) {
        return "unused"
    }
    throw IllegalStateException("No API key for provider: ${model.provider}")
}

private fun hasCopilotVisionInput(messages: List<Message>): Boolean = messages.any { message ->
    when (message) {
        is UserMessage -> {
            val content = message.content
            content is BlockContent && content.blocks.any { it is ImageContent }
        }

        is ToolResultMessage -> message.content.any { it is ImageContent }
        else -> false
    }
}

private fun copilotDynamicHeaders(messages: List<Message>): Map<String, String> {
    val last = messages.lastOrNull()
    val initiator = if (last != null && last !is UserMessage) "agent" else "user"
    val headers = linkedMapOf(
        "X-Initiator" to initiator,
        "Openai-Intent" to "conversation-edits",
    )
    if (hasCopilotVisionInput(messages)) headers["Copilot-Vision-Request"] = "true"
    return headers
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

private fun buildRequestHeaders(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    apiKey: String,
    sessionId: String?,
    compat: ResolvedOpenAICompletionsCompat,
): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers.putAll(identityHeaders(model, options.identity))
    model.headers?.let(headers::putAll)
    if (model.provider == "github-copilot") headers.putAll(copilotDynamicHeaders(context.messages))
    if (sessionId != null && compat.sendSessionAffinityHeaders) {
        when (compat.sessionAffinityFormat) {
            SessionAffinityFormat.Openrouter -> headers["x-session-id"] = sessionId
            else -> {
                if (compat.sessionAffinityFormat == SessionAffinityFormat.Openai) headers["session_id"] = sessionId
                headers["x-client-request-id"] = sessionId
                headers["x-session-affinity"] = sessionId
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
    if (!hasHeader(headers, "authorization") && !hasHeader(headers, "cf-aig-authorization")) {
        headers["Authorization"] = "Bearer $apiKey"
    }
    if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/json"
    if (!hasHeader(headers, "accept")) headers["Accept"] = "text/event-stream"
    return headers
}

private fun chatCompletionsUrl(model: Model): String = model.baseUrl.trimEnd('/') + "/chat/completions"

private fun hasToolHistory(messages: List<Message>): Boolean {
    for (message in messages) {
        if (message is ToolResultMessage) return true
        if (message is AssistantMessage && message.content.any { it is ToolCall }) return true
    }
    return false
}

private fun clampOpenAIPromptCacheKey(key: String?): String? {
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

private fun getCompatCacheControl(
    compat: ResolvedOpenAICompletionsCompat,
    cacheRetention: CacheRetention,
): JsonObject? {
    if (compat.cacheControlFormat != CacheControlFormat.Anthropic || cacheRetention == CacheRetention.None) {
        return null
    }
    val ttl = if (cacheRetention == CacheRetention.Long && compat.supportsLongCacheRetention) "1h" else null
    return buildJsonObject {
        put("type", JsonPrimitive("ephemeral"))
        if (ttl != null) put("ttl", JsonPrimitive(ttl))
    }
}

private fun buildParams(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    compat: ResolvedOpenAICompletionsCompat,
    cacheRetention: CacheRetention,
    grammarToolInputProperties: Map<String, String>,
    toolChoice: JsonElement?,
    reasoningEffort: ModelThinkingLevel?,
    thinkingBudgets: ThinkingBudgets?,
): JsonObject {
    val toolSupport = if (compat.supportsMidConvoSystemMessages && compat.supportsMidConvoToolAdditions) {
        TranscriptToolSupport.Additions
    } else {
        TranscriptToolSupport.None
    }
    val transcriptTools = resolveTranscriptTools(context.messages, toolSupport)
    val messages = convertMessages(model, context, compat, grammarToolInputProperties)
    val cacheControl = getCompatCacheControl(compat, cacheRetention)

    val tools: MutableList<JsonObject>? = when {
        transcriptTools.requestTools.isNotEmpty() -> convertTools(transcriptTools.requestTools, compat).toMutableList()
        hasToolHistory(context.messages) -> mutableListOf()
        else -> null
    }
    if (cacheControl != null) applyAnthropicCacheControl(messages, tools, cacheControl)

    val params = LinkedHashMap<String, JsonElement>()
    params["model"] = JsonPrimitive(model.id)
    params["messages"] = JsonArray(messages)
    params["stream"] = JsonPrimitive(true)

    val promptCacheKey = if (
        (model.baseUrl.contains("api.openai.com") && cacheRetention != CacheRetention.None) ||
        (cacheRetention == CacheRetention.Long && compat.supportsLongCacheRetention)
    ) {
        clampOpenAIPromptCacheKey(options.sessionId)
    } else {
        null
    }
    if (promptCacheKey != null) params["prompt_cache_key"] = JsonPrimitive(promptCacheKey)
    if (cacheRetention == CacheRetention.Long && compat.supportsLongCacheRetention) {
        params["prompt_cache_retention"] = JsonPrimitive("24h")
    }

    if (compat.supportsUsageInStreaming) {
        params["stream_options"] = buildJsonObject { put("include_usage", JsonPrimitive(true)) }
    }
    if (compat.supportsStore) params["store"] = JsonPrimitive(false)

    options.maxTokens?.let { maxTokens ->
        if (compat.maxTokensField == MaxTokensField.MaxTokens) {
            params["max_tokens"] = JsonPrimitive(maxTokens)
        } else {
            params["max_completion_tokens"] = JsonPrimitive(maxTokens)
        }
    }
    options.temperature?.let { params["temperature"] = JsonPrimitive(it) }

    if (tools != null) {
        params["tools"] = JsonArray(tools)
        if (tools.isNotEmpty() && compat.zaiToolStream) params["tool_stream"] = JsonPrimitive(true)
    }

    toolChoice?.let { params["tool_choice"] = it }
    compat.vllmPriority?.let { params["priority"] = JsonPrimitive(it) }

    val thinkingTokenBudgetField = compat.thinkingTokenBudgetField
        ?: if (compat.supportsThinkingTokenBudget) ThinkingTokenBudgetField.ThinkingTokenBudget else null
    val thinkingBudget = resolveClampedThinkingBudget(model, options, reasoningEffort, thinkingBudgets)

    if (model.reasoning) {
        when (compat.thinkingFormat) {
            ThinkingFormat.Zai -> {
                params["thinking"] = if (reasoningEffort != null) {
                    buildJsonObject {
                        put("type", JsonPrimitive("enabled"))
                        put("clear_thinking", JsonPrimitive(false))
                    }
                } else {
                    buildJsonObject { put("type", JsonPrimitive("disabled")) }
                }
                if (reasoningEffort != null && compat.supportsReasoningEffort) {
                    val lookup = mappedValue(model, reasoningEffort)
                    val effort = if (lookup.first) lookup.second else reasoningEffort.jsonName()
                    if (effort != null) params["reasoning_effort"] = JsonPrimitive(effort)
                }
            }

            ThinkingFormat.Qwen -> {
                params["enable_thinking"] = JsonPrimitive(reasoningEffort != null)
                if (reasoningEffort != null && compat.supportsReasoningEffort) {
                    params["reasoning_effort"] = JsonPrimitive(mappedOrLevel(model, reasoningEffort))
                }
            }

            ThinkingFormat.QwenChatTemplate -> {
                params["chat_template_kwargs"] = buildJsonObject {
                    put("enable_thinking", JsonPrimitive(reasoningEffort != null))
                    put("preserve_thinking", JsonPrimitive(true))
                }
            }

            ThinkingFormat.ChatTemplate -> {
                val values = buildChatTemplateValues(model, reasoningEffort, compat.chatTemplateKwargs, thinkingBudget)
                if (values != null) params["chat_template_kwargs"] = values
            }

            ThinkingFormat.Baseten -> {
                val args = buildChatTemplateValues(model, reasoningEffort, compat.chatTemplateArgs, thinkingBudget)
                if (args != null) params["chat_template_args"] = args
                if (compat.supportsReasoningEffort) {
                    val effort = if (reasoningEffort != null) {
                        val lookup = mappedValue(model, reasoningEffort)
                        if (lookup.first) lookup.second else reasoningEffort.jsonName()
                    } else {
                        val lookup = mappedOffValue(model)
                        if (lookup.first) lookup.second else null
                    }
                    if (effort != null) params["reasoning_effort"] = JsonPrimitive(effort)
                }
            }

            ThinkingFormat.Deepseek -> {
                if (reasoningEffort != null) {
                    params["thinking"] = buildJsonObject { put("type", JsonPrimitive("enabled")) }
                } else if (!isOffExplicitlyUnsupported(model)) {
                    params["thinking"] = buildJsonObject { put("type", JsonPrimitive("disabled")) }
                }
                if (reasoningEffort != null && compat.supportsReasoningEffort) {
                    params["reasoning_effort"] = JsonPrimitive(mappedOrLevel(model, reasoningEffort))
                }
            }

            ThinkingFormat.Openrouter -> {
                if (reasoningEffort != null) {
                    params["reasoning"] = buildJsonObject {
                        put("effort", JsonPrimitive(mappedOrLevel(model, reasoningEffort)))
                    }
                } else if (!isOffExplicitlyUnsupported(model)) {
                    params["reasoning"] = buildJsonObject { put("effort", JsonPrimitive(offValueOrNone(model))) }
                }
            }

            ThinkingFormat.AntLing -> {
                if (reasoningEffort != null) {
                    val lookup = mappedValue(model, reasoningEffort)
                    val mapped = lookup.second
                    if (mapped != null) params["reasoning"] = buildJsonObject { put("effort", JsonPrimitive(mapped)) }
                }
            }

            ThinkingFormat.Together -> {
                params["reasoning"] = buildJsonObject { put("enabled", JsonPrimitive(reasoningEffort != null)) }
                if (reasoningEffort != null && compat.supportsReasoningEffort) {
                    params["reasoning_effort"] = JsonPrimitive(mappedOrLevel(model, reasoningEffort))
                }
            }

            ThinkingFormat.StringThinking -> {
                if (reasoningEffort != null) {
                    params["thinking"] = JsonPrimitive(mappedOrLevel(model, reasoningEffort))
                } else if (!isOffExplicitlyUnsupported(model)) {
                    params["thinking"] = JsonPrimitive(offValueOrNone(model))
                }
            }

            ThinkingFormat.Openai -> {
                if (reasoningEffort != null && compat.supportsReasoningEffort) {
                    params["reasoning_effort"] = JsonPrimitive(mappedOrLevel(model, reasoningEffort))
                } else if (reasoningEffort == null && compat.supportsReasoningEffort) {
                    val offValue = model.thinkingLevelMap?.get("off")
                    if (offValue != null) params["reasoning_effort"] = JsonPrimitive(offValue)
                }
            }
        }
    }

    if (thinkingTokenBudgetField != null && thinkingBudget != null) {
        params[thinkingTokenBudgetField.wireName] = JsonPrimitive(thinkingBudget)
    }

    compat.openRouterRouting?.let { params["provider"] = it }

    val gatewayOnly = compat.vercelGatewayRouting["only"] as? JsonArray
    val gatewayOrder = compat.vercelGatewayRouting["order"] as? JsonArray
    if (gatewayOnly != null || gatewayOrder != null) {
        params["providerOptions"] = buildJsonObject {
            put(
                "gateway",
                buildJsonObject {
                    if (gatewayOnly != null) put("only", gatewayOnly)
                    if (gatewayOrder != null) put("order", gatewayOrder)
                },
            )
        }
    }

    resolveSamplingParams(model, reasoningEffort ?: ModelThinkingLevel.Off, options.samplingParams)?.forEach {
            (key, value) ->
        params[key] = value
    }

    return JsonObject(params)
}

private fun mappedValue(model: Model, level: ModelThinkingLevel): Pair<Boolean, String?> {
    val map = model.thinkingLevelMap ?: return Pair(false, null)
    val key = level.jsonName()
    if (!map.containsKey(key)) return Pair(false, null)
    return Pair(true, map[key])
}

private fun mappedOffValue(model: Model): Pair<Boolean, String?> {
    val map = model.thinkingLevelMap ?: return Pair(false, null)
    if (!map.containsKey("off")) return Pair(false, null)
    return Pair(true, map["off"])
}

private fun mappedOrLevel(model: Model, level: ModelThinkingLevel): String =
    model.thinkingLevelMap?.get(level.jsonName()) ?: level.jsonName()

private fun isOffExplicitlyUnsupported(model: Model): Boolean {
    val map = model.thinkingLevelMap ?: return false
    return map.containsKey("off") && map["off"] == null
}

private fun offValueOrNone(model: Model): String = model.thinkingLevelMap?.get("off") ?: "none"

private fun resolveClampedThinkingBudget(
    model: Model,
    options: StreamOptions,
    reasoningEffort: ModelThinkingLevel?,
    thinkingBudgets: ThinkingBudgets?,
): Long? {
    if (reasoningEffort == null || !model.reasoning) return null
    val ceiling = options.maxTokens ?: model.maxTokens
    val budget = clampThinkingBudgetToAnswerRoom(thinkingBudgetForLevel(reasoningEffort, thinkingBudgets), ceiling)
    return if (budget > 0) budget else null
}

private fun buildChatTemplateValues(
    model: Model,
    reasoningEffort: ModelThinkingLevel?,
    values: JsonObject,
    thinkingBudget: Long?,
): JsonObject? {
    val resolved = LinkedHashMap<String, JsonElement>()
    for ((key, value) in values) {
        val resolvedValue = resolveChatTemplateKwargValue(model, reasoningEffort, value, thinkingBudget)
        if (resolvedValue != null) resolved[key] = resolvedValue
    }
    return if (resolved.isEmpty()) null else JsonObject(resolved)
}

private fun resolveChatTemplateKwargValue(
    model: Model,
    reasoningEffort: ModelThinkingLevel?,
    value: JsonElement,
    thinkingBudget: Long?,
): JsonElement? {
    if (value !is JsonObject) return value
    if (reasoningEffort == null && booleanField(value, "omitWhenOff") == true) return null
    when (stringField(value, "\$var")) {
        "thinking.enabled" -> return JsonPrimitive(reasoningEffort != null)
        "thinking.budget" -> return thinkingBudget?.let { JsonPrimitive(it) }
    }
    val lookup = if (reasoningEffort != null) mappedValue(model, reasoningEffort) else mappedOffValue(model)
    if (!lookup.first) return reasoningEffort?.let { JsonPrimitive(it.jsonName()) }
    return lookup.second?.let { JsonPrimitive(it) }
}

private fun convertTools(tools: List<Tool>, compat: ResolvedOpenAICompletionsCompat): List<JsonObject> =
    tools.map { tool ->
        val grammar = resolveGrammarConstrainedSampling(tool, compat.supportsOpenAIGrammarTools)
        if (grammar != null) {
            buildJsonObject {
                put("type", JsonPrimitive("custom"))
                put(
                    "custom",
                    buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put(
                            "format",
                            buildJsonObject {
                                put("type", JsonPrimitive("grammar"))
                                put(
                                    "grammar",
                                    buildJsonObject {
                                        put("syntax", JsonPrimitive(grammar.format))
                                        put("definition", JsonPrimitive(grammar.definition))
                                    },
                                )
                            },
                        )
                    },
                )
            }
        } else {
            val strict = resolveJsonSchemaStrictSampling(tool, compat.supportsStrictMode)
            val parameters = if (strict == true) makeStrictJsonSchema(tool.parameters) else tool.parameters
            buildJsonObject {
                put("type", JsonPrimitive("function"))
                put(
                    "function",
                    buildJsonObject {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put("parameters", parameters)
                        if (compat.supportsStrictMode) put("strict", JsonPrimitive(strict ?: false))
                    },
                )
            }
        }
    }

private class UnsupportedStrictJsonSchemaError(message: String) : RuntimeException(message)

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

private fun makeStrictNode(schema: JsonElement): JsonObject {
    val obj = schema as? JsonObject ?: throw UnsupportedStrictJsonSchemaError("boolean schemas are unsupported")
    for (key in UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (obj.containsKey(key)) throw UnsupportedStrictJsonSchemaError("$key schemas are unsupported")
    }
    val result = LinkedHashMap(obj)
    obj["anyOf"]?.let { anyOf ->
        if (anyOf !is JsonArray || anyOf.isEmpty()) {
            throw UnsupportedStrictJsonSchemaError("anyOf must contain at least one schema")
        }
        result["anyOf"] = JsonArray(
            anyOf.map { variant ->
                if (isStructuredSchema(variant)) {
                    throw UnsupportedStrictJsonSchemaError("object and array unions are unsupported")
                }
                makeStrictNode(variant)
            },
        )
    }
    obj["items"]?.let { items ->
        if (items is JsonArray) throw UnsupportedStrictJsonSchemaError("tuple schemas are unsupported")
        result["items"] = makeStrictNode(items)
    }
    val isObjectSchema = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object"
    if (obj.containsKey("properties") && !isObjectSchema) {
        throw UnsupportedStrictJsonSchemaError("properties require type object")
    }
    if (!isObjectSchema) return JsonObject(result)

    obj["additionalProperties"]?.let { additional ->
        if (additional != JsonPrimitive(false)) {
            throw UnsupportedStrictJsonSchemaError("schema-valued or true additionalProperties is unsupported")
        }
    }
    val propertiesElement = obj["properties"]
    if (propertiesElement != null && propertiesElement !is JsonObject) {
        throw UnsupportedStrictJsonSchemaError("object properties must be a schema map")
    }
    val properties = propertiesElement as? JsonObject ?: JsonObject(emptyMap())
    val requiredElement = obj["required"]
    if (requiredElement != null) {
        val valid = requiredElement is JsonArray && requiredElement.all { element ->
            (element as? JsonPrimitive)?.isString == true
        }
        if (!valid) throw UnsupportedStrictJsonSchemaError("object required must be a string array")
    }
    val required = (requiredElement as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.toSet()
        .orEmpty()
    val propertyNames = properties.keys.toList()
    if (required.any { it !in propertyNames }) {
        throw UnsupportedStrictJsonSchemaError("required contains an unknown property")
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
        throw UnsupportedStrictJsonSchemaError("root schema must have type object")
    }
    return root
}

private fun resolveJsonSchemaStrictSampling(tool: Tool, supportsStrictMode: Boolean): Boolean? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "json_schema") return null
    if (supportsStrictMode) {
        try {
            makeStrictJsonSchema(tool.parameters)
            return true
        } catch (error: UnsupportedStrictJsonSchemaError) {
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

private fun inferGrammarInputProperty(tool: Tool): String {
    val schema = tool.parameters
    if ((schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "object") {
        throw IllegalStateException("grammar constrained sampling requires an object parameter schema")
    }
    val required = schema["required"] as? JsonArray
    val requiredNames = required?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    if (requiredNames == null || requiredNames.size != 1) {
        throw IllegalStateException("grammar constrained sampling requires exactly one required string property")
    }
    val inputProperty = requiredNames.first()
    val properties = schema["properties"] as? JsonObject
    val property = properties?.get(inputProperty) as? JsonObject
    if (property == null) {
        throw IllegalStateException("grammar constrained sampling requires a properties entry for $inputProperty")
    }
    if ((property["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "string") {
        throw IllegalStateException("grammar constrained sampling property $inputProperty must have type string")
    }
    return inputProperty
}

private fun resolveGrammarConstrainedSampling(tool: Tool, supportsOpenAIGrammarTools: Boolean): GrammarSampling? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "grammar") return null
    if (!supportsOpenAIGrammarTools) return null
    val variants = objectField(config, "variants")
    val lark = variants?.let { stringField(it, "openai_lark") }
    val regex = variants?.let { stringField(it, "openai_regex") }
    val hasLark = !lark.isNullOrBlank()
    val hasRegex = !regex.isNullOrBlank()
    if (!hasLark && !hasRegex) {
        throw IllegalStateException(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: no supported grammar variant was provided.",
        )
    }
    val inputProperty = try {
        inferGrammarInputProperty(tool)
    } catch (error: IllegalStateException) {
        throw IllegalStateException(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: ${error.message}.",
        )
    }
    return if (hasLark) {
        GrammarSampling("lark", lark!!, inputProperty)
    } else {
        GrammarSampling("regex", regex!!, inputProperty)
    }
}

private fun createGrammarToolInputProperties(tools: List<Tool>, supportsOpenAIGrammarTools: Boolean): Map<String, String> {
    val properties = LinkedHashMap<String, String>()
    for (tool in tools) {
        val grammar = resolveGrammarConstrainedSampling(tool, supportsOpenAIGrammarTools) ?: continue
        properties[tool.name] = grammar.inputProperty
    }
    return properties
}

private fun getGrammarToolInput(toolName: String, arguments: JsonObject, inputProperty: String): String {
    val input = (arguments[inputProperty] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (input == null) {
        throw IllegalStateException(
            "Grammar tool call \"$toolName\" requires argument \"$inputProperty\" to be a string.",
        )
    }
    return input
}

private fun appendGrammarToolInputJsonDelta(
    buffer: GrammarInputBuffer,
    inputProperty: String,
    nextInput: String,
    close: Boolean,
): String? {
    if (buffer.closed) {
        if (close && nextInput == buffer.input) return null
        throw IllegalStateException("grammar tool input for property \"$inputProperty\" changed after it was closed")
    }
    if (!nextInput.startsWith(buffer.input)) {
        throw IllegalStateException("grammar tool input for property \"$inputProperty\" changed non-monotonically")
    }
    val inputDelta = nextInput.substring(buffer.input.length)
    if (!close && inputDelta.isEmpty()) return null
    var delta = ""
    if (!buffer.started) {
        delta += "{" + JsonPrimitive(inputProperty).toString() + ":\""
        buffer.started = true
    }
    val quotedDelta = JsonPrimitive(inputDelta).toString()
    delta += quotedDelta.substring(1, quotedDelta.length - 1)
    buffer.input = nextInput
    if (close) {
        delta += "\"}"
        buffer.closed = true
    }
    return delta
}

private fun convertMessages(
    model: Model,
    context: TranscriptContext,
    compat: ResolvedOpenAICompletionsCompat,
    grammarToolInputProperties: Map<String, String>,
): MutableList<JsonObject> {
    val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
    val messages = mutableListOf<JsonObject>()
    val toolSupport = if (compat.supportsMidConvoSystemMessages && compat.supportsMidConvoToolAdditions) {
        TranscriptToolSupport.Additions
    } else {
        TranscriptToolSupport.None
    }
    val transformed = transformMessages(normalizedContext.messages, model) { id -> normalizeOpenAIToolCallId(model, id) }
    val transcriptTools = resolveTranscriptTools(normalizedContext.messages, toolSupport)
    val instructionRole = if (model.reasoning && compat.supportsDeveloperRole) "developer" else "system"

    var lastRole: String? = null
    var systemIndex = 0
    var index = 0

    while (index < transformed.size) {
        val message = transformed[index]
        if (compat.requiresAssistantAfterToolResult && lastRole == "toolResult" && message is UserMessage) {
            messages.add(
                buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", JsonPrimitive("I have processed the tool results."))
                },
            )
        }

        when (message) {
            is SystemMessage -> {
                val addedTools = if (systemIndex++ >= transcriptTools.foldedSystemMessages) {
                    message.toolsAdded.orEmpty()
                } else {
                    emptyList()
                }
                if (addedTools.isNotEmpty()) {
                    messages.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("tools", JsonArray(convertTools(addedTools, compat)))
                        },
                    )
                }
                val text = if (index == 0) getSystemMessageText(message) else renderSystemMessageUpdate(message)
                if (text.isNotEmpty()) {
                    messages.add(
                        buildJsonObject {
                            put("role", JsonPrimitive(instructionRole))
                            put("content", JsonPrimitive(sanitizeSurrogates(text)))
                        },
                    )
                }
            }

            is UserMessage -> when (val content = message.content) {
                is PlainTextContent -> {
                    messages.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(sanitizeSurrogates(content.text)))
                        },
                    )
                }

                is BlockContent -> {
                    val parts = content.blocks.mapNotNull { block ->
                        when (block) {
                            is TextContent -> {
                                if (block.text.isEmpty()) {
                                    null
                                } else {
                                    buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
                                    }
                                }
                            }

                            is ImageContent -> buildJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                put(
                                    "image_url",
                                    buildJsonObject {
                                        put("url", JsonPrimitive("data:${block.mimeType};base64,${block.data}"))
                                    },
                                )
                            }

                            else -> null
                        }
                    }
                    if (parts.isEmpty()) {
                        index += 1
                        continue
                    }
                    messages.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonArray(parts))
                        },
                    )
                }
            }

            is AssistantMessage -> {
                val assistantMessage = LinkedHashMap<String, JsonElement>()
                assistantMessage["role"] = JsonPrimitive("assistant")
                assistantMessage["content"] = if (compat.requiresAssistantAfterToolResult) {
                    JsonPrimitive("")
                } else {
                    JsonNull
                }

                val assistantTextParts = message.content
                    .filterIsInstance<TextContent>()
                    .filter { it.text.trim().isNotEmpty() }
                    .map { sanitizeSurrogates(it.text) }
                val assistantText = assistantTextParts.joinToString("")

                val thinkingBlocks = message.content.filterIsInstance<ThinkingContent>()
                val toolCalls = message.content.filterIsInstance<ToolCall>()
                val signedReasoningDetails = thinkingBlocks
                    .mapNotNull { parseOpenAIReasoningDetails(it.thinkingSignature) }
                    .firstOrNull()
                val legacyReasoningDetails = toolCalls.mapNotNull {
                    parseLegacyEncryptedReasoningDetail(it.thoughtSignature)
                }
                val preservedReasoningDetails = signedReasoningDetails
                    ?: legacyReasoningDetails.takeIf { it.isNotEmpty() }

                val nonEmptyThinkingBlocks = thinkingBlocks.filter { it.thinking.trim().isNotEmpty() }
                if (nonEmptyThinkingBlocks.isNotEmpty()) {
                    if (compat.requiresThinkingAsText) {
                        val thinkingText = nonEmptyThinkingBlocks.joinToString("\n\n") {
                            sanitizeSurrogates(it.thinking)
                        }
                        assistantMessage["content"] = JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(thinkingText))
                                },
                            ) + assistantTextParts.map { text ->
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(text))
                                }
                            },
                        )
                    } else {
                        if (assistantText.isNotEmpty()) assistantMessage["content"] = JsonPrimitive(assistantText)
                        if (preservedReasoningDetails == null) {
                            var signature = nonEmptyThinkingBlocks.first().thinkingSignature
                            if (model.provider == "opencode-go" && signature == "reasoning") {
                                signature = "reasoning_content"
                            }
                            if (signature != null && REASONING_FIELDS.contains(signature)) {
                                assistantMessage[signature] = JsonPrimitive(
                                    nonEmptyThinkingBlocks.joinToString("\n") { it.thinking },
                                )
                            }
                        }
                    }
                } else if (assistantText.isNotEmpty()) {
                    assistantMessage["content"] = JsonPrimitive(assistantText)
                }

                if (toolCalls.isNotEmpty()) {
                    assistantMessage["tool_calls"] = JsonArray(
                        toolCalls.map { toolCall ->
                            val customInputProperty = grammarToolInputProperties[toolCall.name]
                            if (customInputProperty != null) {
                                buildJsonObject {
                                    put("id", JsonPrimitive(toolCall.id))
                                    put("type", JsonPrimitive("custom"))
                                    put(
                                        "custom",
                                        buildJsonObject {
                                            put("name", JsonPrimitive(toolCall.name))
                                            put(
                                                "input",
                                                JsonPrimitive(
                                                    sanitizeSurrogates(
                                                        getGrammarToolInput(
                                                            toolCall.name,
                                                            toolCall.arguments,
                                                            customInputProperty,
                                                        ),
                                                    ),
                                                ),
                                            )
                                        },
                                    )
                                }
                            } else {
                                buildJsonObject {
                                    put("id", JsonPrimitive(toolCall.id))
                                    put("type", JsonPrimitive("function"))
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", JsonPrimitive(toolCall.name))
                                            put("arguments", JsonPrimitive(toolCall.arguments.toString()))
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
                if (preservedReasoningDetails != null) {
                    assistantMessage["reasoning_details"] = JsonArray(preservedReasoningDetails)
                }
                if (
                    compat.requiresReasoningContentOnAssistantMessages && model.reasoning &&
                    assistantMessage["reasoning_content"] == null
                ) {
                    assistantMessage["reasoning_content"] = JsonPrimitive("")
                }

                val hasContent = when (val content = assistantMessage["content"]) {
                    null, is JsonNull -> false
                    is JsonPrimitive -> content.isString && content.content.isNotEmpty()
                    is JsonArray -> content.isNotEmpty()
                    else -> false
                }
                if (!hasContent && assistantMessage["tool_calls"] == null) {
                    index += 1
                    continue
                }
                messages.add(JsonObject(assistantMessage))
            }

            is ToolResultMessage -> {
                val imageBlocks = mutableListOf<JsonObject>()
                var next = index
                while (next < transformed.size && transformed[next] is ToolResultMessage) {
                    val toolMessage = transformed[next] as ToolResultMessage
                    val textResult = toolMessage.content
                        .filterIsInstance<TextContent>()
                        .joinToString("\n") { it.text }
                    val hasImages = toolMessage.content.any { it is ImageContent }
                    val hasText = textResult.trim().isNotEmpty()
                    val toolResultText = when {
                        hasText -> textResult
                        hasImages -> "(see attached image)"
                        else -> "(no tool output)"
                    }
                    messages.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("tool"))
                            put("content", JsonPrimitive(sanitizeSurrogates(toolResultText)))
                            put("tool_call_id", JsonPrimitive(toolMessage.toolCallId))
                            if (compat.requiresToolResultName && toolMessage.toolName.isNotEmpty()) {
                                put("name", JsonPrimitive(toolMessage.toolName))
                            }
                        },
                    )
                    if (hasImages && model.input.contains(ModelInputModality.Image)) {
                        for (block in toolMessage.content) {
                            if (block is ImageContent) {
                                imageBlocks.add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("image_url"))
                                        put(
                                            "image_url",
                                            buildJsonObject {
                                                put("url", JsonPrimitive("data:${block.mimeType};base64,${block.data}"))
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    next += 1
                }
                index = next - 1
                if (imageBlocks.isNotEmpty()) {
                    if (compat.requiresAssistantAfterToolResult) {
                        messages.add(
                            buildJsonObject {
                                put("role", JsonPrimitive("assistant"))
                                put("content", JsonPrimitive("I have processed the tool results."))
                            },
                        )
                    }
                    messages.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("text"))
                                            put("text", JsonPrimitive("Attached image(s) from tool result:"))
                                        },
                                    ) + imageBlocks,
                                ),
                            )
                        },
                    )
                    lastRole = "user"
                } else {
                    lastRole = "toolResult"
                }
                index += 1
                continue
            }
        }

        lastRole = when (message) {
            is SystemMessage -> "system"
            is UserMessage -> "user"
            is AssistantMessage -> "assistant"
            is ToolResultMessage -> "toolResult"
        }
        index += 1
    }

    return messages
}

private fun applyAnthropicCacheControl(
    messages: MutableList<JsonObject>,
    tools: MutableList<JsonObject>?,
    cacheControl: JsonObject,
) {
    addCacheControlToSystemPrompt(messages, cacheControl)
    addCacheControlToLastTool(tools, cacheControl)
    addCacheControlToLastConversationMessage(messages, cacheControl)
}

private fun addCacheControlToSystemPrompt(messages: MutableList<JsonObject>, cacheControl: JsonObject) {
    for (index in messages.indices) {
        val role = messages[index].str("role")
        if (role == "system" || role == "developer") {
            addCacheControlToMessage(messages, index, cacheControl)
            return
        }
    }
}

private fun addCacheControlToLastTool(tools: MutableList<JsonObject>?, cacheControl: JsonObject) {
    if (tools.isNullOrEmpty()) return
    val last = tools.last()
    tools[tools.size - 1] = last.plusField("cache_control", cacheControl)
}

private fun addCacheControlToLastConversationMessage(messages: MutableList<JsonObject>, cacheControl: JsonObject) {
    for (index in messages.indices.reversed()) {
        val role = messages[index].str("role")
        if (role == "user" || role == "assistant" || role == "tool") {
            if (addCacheControlToMessage(messages, index, cacheControl)) return
        }
    }
}

private fun addCacheControlToMessage(messages: MutableList<JsonObject>, index: Int, cacheControl: JsonObject): Boolean {
    val message = messages[index]
    val newContent = when (val content = message["content"]) {
        is JsonPrimitive -> {
            if (!content.isString || content.content.isEmpty()) return false
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(content.content))
                        put("cache_control", cacheControl)
                    },
                ),
            )
        }

        is JsonArray -> {
            val parts = content.toMutableList()
            var target = -1
            for (position in parts.indices.reversed()) {
                val part = parts[position] as? JsonObject ?: continue
                if (part.str("type") == "text") {
                    target = position
                    break
                }
            }
            if (target < 0) return false
            val part = parts[target] as JsonObject
            parts[target] = part.plusField("cache_control", cacheControl)
            JsonArray(parts)
        }

        else -> return false
    }
    messages[index] = message.plusField("content", newContent)
    return true
}

private fun parseOpenAIReasoningDetails(signature: String?): List<JsonObject>? {
    if (signature.isNullOrEmpty()) return null
    val parsed = try {
        PiJson.parseToJsonElement(signature)
    } catch (_: Throwable) {
        return null
    }
    val array = parsed as? JsonArray ?: return null
    if (array.isEmpty()) return null
    val details = array.map { element ->
        val detail = element as? JsonObject ?: return null
        if (!isOpenAIReasoningDetail(detail)) return null
        detail
    }
    return details
}

private fun parseLegacyEncryptedReasoningDetail(signature: String?): JsonObject? {
    if (signature.isNullOrEmpty()) return null
    val parsed = try {
        PiJson.parseToJsonElement(signature)
    } catch (_: Throwable) {
        return null
    }
    val detail = parsed as? JsonObject ?: return null
    if (!isOpenAIReasoningDetail(detail)) return null
    if (stringField(detail, "type") != "reasoning.encrypted") return null
    if (stringField(detail, "id").isNullOrEmpty()) return null
    if (stringField(detail, "data").isNullOrEmpty()) return null
    return detail
}

private fun isOpenAIReasoningDetail(detail: JsonObject): Boolean {
    if (!hasValidCommonReasoningDetailFields(detail)) return false
    return when (stringField(detail, "type")) {
        "reasoning.summary" -> stringField(detail, "summary") != null
        "reasoning.encrypted" -> stringField(detail, "data") != null
        "reasoning.text" -> {
            if (stringField(detail, "text") == null) {
                false
            } else {
                val signature = detail["signature"]
                signature == null || signature is JsonNull || (signature is JsonPrimitive && signature.isString)
            }
        }

        else -> false
    }
}

private fun hasValidCommonReasoningDetailFields(detail: JsonObject): Boolean {
    val id = detail["id"]
    if (id != null && id !is JsonNull && !(id is JsonPrimitive && id.isString)) return false
    val format = detail["format"]
    if (format != null && !(format is JsonPrimitive && format.isString)) return false
    val index = detail["index"]
    if (index != null) {
        val validIndex = index is JsonPrimitive && !index.isString && index.content.toDoubleOrNull() != null
        if (!validIndex) return false
    }
    return true
}

private fun appendOpenAIReasoningDetail(details: MutableList<JsonObject>, detail: JsonObject) {
    val last = details.lastOrNull()
    val type = stringField(detail, "type")
    if (type == "reasoning.text" && last != null && stringField(last, "type") == "reasoning.text") {
        var merged = last.plusField(
            "text",
            JsonPrimitive((stringField(last, "text") ?: "") + (stringField(detail, "text") ?: "")),
        )
        val lastSignature = stringField(merged, "signature")
        val detailSignature = stringField(detail, "signature")
        if (lastSignature.isNullOrEmpty() && !detailSignature.isNullOrEmpty()) {
            merged = merged.plusField("signature", JsonPrimitive(detailSignature))
        }
        details[details.size - 1] = fillMissingCommonReasoningDetailFields(merged, detail)
        return
    }
    if (type == "reasoning.summary" && last != null && stringField(last, "type") == "reasoning.summary") {
        val summary = (stringField(last, "summary") ?: "") + (stringField(detail, "summary") ?: "")
        details[details.size - 1] = fillMissingCommonReasoningDetailFields(
            last.plusField("summary", JsonPrimitive(summary)),
            detail,
        )
        return
    }
    details.add(detail)
}

private fun fillMissingCommonReasoningDetailFields(target: JsonObject, source: JsonObject): JsonObject {
    var result = target
    if (result["id"] == null || result["id"] is JsonNull) source["id"]?.let { result = result.plusField("id", it) }
    if (stringField(result, "format").isNullOrEmpty()) {
        source["format"]?.let { result = result.plusField("format", it) }
    }
    if (result["index"] == null || result["index"] is JsonNull) {
        source["index"]?.let { result = result.plusField("index", it) }
    }
    return result
}

private fun mapStopReason(reason: String?): Pair<StopReason, String?> = when (reason) {
    null -> Pair(StopReason.Stop, null)
    "stop", "end" -> Pair(StopReason.Stop, null)
    "length" -> Pair(StopReason.Length, null)
    "function_call", "tool_calls" -> Pair(StopReason.ToolUse, null)
    "content_filter" -> Pair(StopReason.Error, "Provider finish_reason: content_filter")
    "network_error" -> Pair(StopReason.Error, "Provider finish_reason: network_error")
    else -> Pair(StopReason.Error, "Provider finish_reason: $reason")
}

private fun parseChunkUsage(rawUsage: JsonObject, model: Model): Usage {
    val promptTokens = longField(rawUsage, "prompt_tokens") ?: 0L
    val promptDetails = objectField(rawUsage, "prompt_tokens_details") ?: JsonObject(emptyMap())
    val cacheReadTokens = longField(promptDetails, "cached_tokens")
        ?: longField(rawUsage, "prompt_cache_hit_tokens")
        ?: longField(rawUsage, "cached_tokens")
        ?: 0L
    val cacheWriteTokens = longField(promptDetails, "cache_write_tokens") ?: 0L
    val input = maxOf(0L, promptTokens - cacheReadTokens - cacheWriteTokens)
    val outputTokens = longField(rawUsage, "completion_tokens") ?: 0L
    val completionDetails = objectField(rawUsage, "completion_tokens_details") ?: JsonObject(emptyMap())
    val reasoningTokens = longField(completionDetails, "reasoning_tokens") ?: 0L
    val usage = Usage(
        input = input,
        output = outputTokens,
        cacheRead = cacheReadTokens,
        cacheWrite = cacheWriteTokens,
        reasoning = reasoningTokens,
        totalTokens = input + outputTokens + cacheReadTokens + cacheWriteTokens,
        cost = zeroCost(),
    )
    usage.cost = calculateCost(model, usage)
    return usage
}

private class CompletionsStreamState(
    val output: AssistantMessage,
    val stream: AssistantMessageEventStream,
    val model: Model,
    val grammarToolInputProperties: Map<String, String>,
) {
    var textIndex: Int = -1
    var thinkingIndex: Int = -1
    var hasFinishReason: Boolean = false
    var streamedReasoningDetails: MutableList<JsonObject>? = null

    private val toolCallByStreamIndex = mutableMapOf<Int, Int>()
    private val toolCallById = mutableMapOf<String, Int>()
    private val streamIndexByContentIndex = mutableMapOf<Int, Int>()
    private val partialArgs = mutableMapOf<Int, String>()
    private val customInputs = mutableMapOf<Int, GrammarInputBuffer>()

    fun applyStreamedReasoningDetails(block: ThinkingContent) {
        val details = streamedReasoningDetails ?: return
        block.thinkingSignature = JsonArray(details).toString()
    }

    fun applyReasoningDetailsToAll() {
        for (block in output.content) {
            if (block is ThinkingContent) applyStreamedReasoningDetails(block)
        }
    }

    fun ensureTextBlock(): Int {
        if (textIndex < 0) {
            textIndex = output.content.size
            output.content.add(TextContent(text = ""))
            stream.push(TextStartEvent(textIndex, output))
        }
        return textIndex
    }

    fun ensureThinkingBlock(thinkingSignature: String): Int {
        if (thinkingIndex < 0) {
            thinkingIndex = output.content.size
            output.content.add(ThinkingContent(thinking = "", thinkingSignature = thinkingSignature))
            stream.push(ThinkingStartEvent(thinkingIndex, output))
        }
        return thinkingIndex
    }

    fun ensureToolCallBlock(toolCall: JsonObject): Int {
        val streamIndex = toolCall.intOrNull("index")
        val function = toolCall.obj("function")
        val custom = toolCall.obj("custom")
        val name = function?.str("name") ?: custom?.str("name") ?: ""
        val id = toolCall.str("id") ?: ""

        var contentIndex = streamIndex?.let { toolCallByStreamIndex[it] }
        if (contentIndex == null && id.isNotEmpty()) contentIndex = toolCallById[id]
        if (contentIndex == null) {
            val customInputProperty = if (custom != null && function == null) {
                grammarToolInputProperties[name] ?: "input"
            } else {
                null
            }
            val arguments = if (customInputProperty != null) {
                buildJsonObject { put(customInputProperty, JsonPrimitive("")) }
            } else {
                JsonObject(emptyMap())
            }
            contentIndex = output.content.size
            output.content.add(ToolCall(id = id, name = name, arguments = arguments))
            if (customInputProperty != null) {
                customInputs[contentIndex] = GrammarInputBuffer(customInputProperty)
            } else {
                partialArgs[contentIndex] = ""
            }
            if (streamIndex != null) {
                toolCallByStreamIndex[streamIndex] = contentIndex
                streamIndexByContentIndex[contentIndex] = streamIndex
            }
            if (id.isNotEmpty()) toolCallById[id] = contentIndex
            stream.push(ToolCallStartEvent(contentIndex, output))
        }
        if (streamIndex != null && streamIndexByContentIndex[contentIndex] == null) {
            streamIndexByContentIndex[contentIndex] = streamIndex
            toolCallByStreamIndex[streamIndex] = contentIndex
        }
        if (id.isNotEmpty()) toolCallById[id] = contentIndex

        var block = output.content[contentIndex] as ToolCall
        if (block.name.isEmpty() && name.isNotEmpty()) {
            block = block.copy(name = name)
            output.content[contentIndex] = block
        }
        if (custom != null && function == null && !customInputs.containsKey(contentIndex)) {
            val property = grammarToolInputProperties[block.name] ?: "input"
            block = block.copy(arguments = buildJsonObject { put(property, JsonPrimitive("")) })
            output.content[contentIndex] = block
            customInputs[contentIndex] = GrammarInputBuffer(property)
            partialArgs.remove(contentIndex)
        }
        return contentIndex
    }

    fun accumulateArguments(contentIndex: Int, delta: String) {
        val accumulated = (partialArgs[contentIndex] ?: "") + delta
        partialArgs[contentIndex] = accumulated
        val parsed = PartialJson.parse(accumulated)
        val block = output.content[contentIndex] as ToolCall
        output.content[contentIndex] = block.copy(arguments = parsed as? JsonObject ?: JsonObject(emptyMap()))
    }

    fun currentCustomInput(contentIndex: Int): String {
        val buffer = customInputs[contentIndex] ?: return ""
        val block = output.content[contentIndex] as? ToolCall ?: return ""
        val value = block.arguments[buffer.property] as? JsonPrimitive ?: return ""
        return if (value.isString) value.content else ""
    }

    fun appendCustomInput(contentIndex: Int, nextInput: String, close: Boolean): String? {
        val buffer = customInputs[contentIndex] ?: return null
        val delta = appendGrammarToolInputJsonDelta(buffer, buffer.property, nextInput, close)
        val block = output.content[contentIndex] as ToolCall
        output.content[contentIndex] = block.copy(
            arguments = buildJsonObject { put(buffer.property, JsonPrimitive(nextInput)) },
        )
        return delta
    }

    fun finishBlock(contentIndex: Int) {
        when (val block = output.content.getOrNull(contentIndex)) {
            null -> return
            is TextContent -> stream.push(TextEndEvent(contentIndex, block.text, output))
            is ThinkingContent -> {
                applyStreamedReasoningDetails(block)
                stream.push(ThinkingEndEvent(contentIndex, block.thinking, output))
            }

            is ToolCall -> {
                if (customInputs.containsKey(contentIndex)) {
                    val delta = appendCustomInput(contentIndex, currentCustomInput(contentIndex), true)
                    if (delta != null) stream.push(ToolCallDeltaEvent(contentIndex, delta, output))
                    stream.push(ToolCallEndEvent(contentIndex, output.content[contentIndex] as ToolCall, output))
                } else {
                    val parsed = PartialJson.parseToolCallArguments(partialArgs[contentIndex])
                    val updated = block.copy(
                        arguments = parsed.arguments,
                        argumentsError = parsed.argumentsError,
                    )
                    output.content[contentIndex] = updated
                    stream.push(ToolCallEndEvent(contentIndex, updated, output))
                }
            }

            else -> Unit
        }
    }
}

private fun processChunk(state: CompletionsStreamState, chunk: JsonObject, model: Model) {
    val output = state.output
    val stream = state.stream
    val chunkId = chunk.str("id")
    if (output.responseId.isNullOrEmpty() && !chunkId.isNullOrEmpty()) output.responseId = chunkId
    val chunkModel = chunk.str("model")
    if (!chunkModel.isNullOrEmpty() && chunkModel != model.id && output.responseModel == null) {
        output.responseModel = chunkModel
    }
    val chunkUsage = chunk.obj("usage")
    if (chunkUsage != null) output.usage = parseChunkUsage(chunkUsage, model)

    val choice = chunk.arr("choices")?.firstOrNull() as? JsonObject ?: return

    if (chunkUsage == null) {
        val choiceUsage = choice.obj("usage")
        if (choiceUsage != null) output.usage = parseChunkUsage(choiceUsage, model)
    }

    val finishReason = choice.str("finish_reason")
    if (finishReason != null) {
        output.rawStopReason = finishReason
        val mapped = mapStopReason(finishReason)
        output.stopReason = mapped.first
        mapped.second?.let { output.errorMessage = it }
        state.hasFinishReason = true
    }

    val delta = choice.obj("delta") ?: return

    var foundReasoningField: String? = null
    for (field in REASONING_FIELDS) {
        val value = delta.str(field)
        if (!value.isNullOrEmpty()) {
            foundReasoningField = field
            break
        }
    }
    if (foundReasoningField != null) {
        val reasoningDelta = delta.str(foundReasoningField)
        if (!reasoningDelta.isNullOrEmpty()) {
            val thinkingSignature = if (model.provider == "opencode-go" && foundReasoningField == "reasoning") {
                "reasoning_content"
            } else {
                foundReasoningField
            }
            val contentIndex = state.ensureThinkingBlock(thinkingSignature)
            val block = output.content[contentIndex] as ThinkingContent
            block.thinking += reasoningDelta
            stream.push(ThinkingDeltaEvent(contentIndex, reasoningDelta, output))
        }
    }

    val reasoningDetails = delta.arr("reasoning_details")
    if (reasoningDetails != null) {
        for (element in reasoningDetails) {
            val detail = element as? JsonObject ?: continue
            if (!isOpenAIReasoningDetail(detail)) continue
            state.ensureThinkingBlock("")
            val details = state.streamedReasoningDetails ?: mutableListOf<JsonObject>().also {
                state.streamedReasoningDetails = it
            }
            appendOpenAIReasoningDetail(details, detail)
        }
    }

    val content = delta.str("content")
    if (!content.isNullOrEmpty()) {
        val contentIndex = state.ensureTextBlock()
        (output.content[contentIndex] as TextContent).text += content
        stream.push(TextDeltaEvent(contentIndex, content, output))
    }

    val toolCalls = delta.arr("tool_calls")
    if (toolCalls != null) {
        for (element in toolCalls) {
            val toolCall = element as? JsonObject ?: continue
            val contentIndex = state.ensureToolCallBlock(toolCall)
            var block = output.content[contentIndex] as ToolCall
            val toolCallId = toolCall.str("id")
            if (block.id.isEmpty() && !toolCallId.isNullOrEmpty()) {
                block = block.copy(id = toolCallId)
                output.content[contentIndex] = block
            }
            val name = toolCall.obj("function")?.str("name") ?: toolCall.obj("custom")?.str("name")
            if (block.name.isEmpty() && !name.isNullOrEmpty()) {
                block = block.copy(name = name)
                output.content[contentIndex] = block
            }

            var deltaText = ""
            val functionArguments = toolCall.obj("function")?.str("arguments")
            if (!functionArguments.isNullOrEmpty()) {
                deltaText = functionArguments
                state.accumulateArguments(contentIndex, functionArguments)
            } else {
                val customInput = toolCall.obj("custom")?.str("input")
                if (!customInput.isNullOrEmpty()) {
                    val nextInput = state.currentCustomInput(contentIndex) + customInput
                    deltaText = state.appendCustomInput(contentIndex, nextInput, false) ?: ""
                }
            }
            stream.push(ToolCallDeltaEvent(contentIndex, deltaText, output))
        }
    }
}

private suspend fun runCompletionsStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    toolChoice: JsonElement?,
    reasoningEffort: ModelThinkingLevel?,
    thinkingBudgets: ThinkingBudgets?,
) {
    val signal = options.signal
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage.empty(),
        stopReason = StopReason.Pending,
        timestamp = nowMillis(),
    )
    var streamState: CompletionsStreamState? = null
    try {
        val apiKey = getClientApiKey(model, options)
        val compat = resolveOpenAICompletionsCompat(model)
        val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
        val grammarToolInputProperties = createGrammarToolInputProperties(
            getDeclaredTools(normalizedContext.messages),
            compat.supportsOpenAIGrammarTools,
        )
        val state = CompletionsStreamState(output, stream, model, grammarToolInputProperties)
        streamState = state
        val cacheRetention = resolveCacheRetention(options.cacheRetention, options.env)
        val cacheSessionId = if (cacheRetention == CacheRetention.None) null else options.sessionId
        val headers = buildRequestHeaders(model, normalizedContext, options, apiKey, cacheSessionId, compat)
        var params = buildParams(
            model,
            normalizedContext,
            options,
            compat,
            cacheRetention,
            grammarToolInputProperties,
            toolChoice,
            reasoningEffort,
            thinkingBudgets,
        )
        val payload = options.onPayload?.invoke(params, model)
        if (payload is JsonObject) params = payload

        signal?.throwIfAborted()
        val response = executeWithRetry(transport, model, params, headers, options)
        options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()

        val sse = SseParser(response.body)
        try {
            while (true) {
                val event = sse.next() ?: break
                val data = event.data.trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val chunk = PiJson.parseToJsonElement(data)
                options.onProviderStreamEvent?.invoke(chunk, model)
                if (chunk is JsonObject) processChunk(state, chunk, model)
                signal?.throwIfAborted()
            }
        } finally {
            sse.close()
            response.close()
        }

        for (contentIndex in output.content.indices) state.finishBlock(contentIndex)
        signal?.throwIfAborted()

        if (output.stopReason == StopReason.Aborted) throw RequestAbortedException("Request was aborted")
        if (!state.hasFinishReason && !compat.supportsFinishReason) {
            output.stopReason = if (output.content.any { it is ToolCall }) StopReason.ToolUse else StopReason.Stop
        }
        if (output.stopReason == StopReason.Error) {
            throw CompletionsStreamException(output.errorMessage ?: "Provider returned an error stop reason")
        }
        if ((compat.supportsFinishReason && !state.hasFinishReason) || output.stopReason == StopReason.Pending) {
            throw CompletionsStreamException("Stream ended without finish_reason")
        }

        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        streamState?.applyReasoningDetailsToAll()
        val aborted = error is RequestAbortedException || signal?.isAborted == true ||
            output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else formatStreamError(error)
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun extractOpenRouterRawMetadata(body: String): String? {
    val parsed = try {
        PiJson.parseToJsonElement(body) as? JsonObject
    } catch (_: Throwable) {
        return null
    }
    return parsed?.obj("error")?.obj("metadata")?.str("raw")
}

private fun formatStreamError(error: Throwable): String = when (error) {
    is ProviderHttpException -> {
        var message = ProviderErrors.format(ProviderErrors.normalize(error.status, error.headers, error.body))
        val rawMetadata = extractOpenRouterRawMetadata(error.body)
        if (rawMetadata != null && !message.contains(rawMetadata)) message += "\n$rawMetadata"
        message
    }

    else -> error.message ?: error.toString()
}

private suspend fun executeWithRetry(
    transport: HttpTransport,
    model: Model,
    params: JsonObject,
    headers: Map<String, String>,
    options: StreamOptions,
): HttpResponse {
    val signal = options.signal
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    val url = chatCompletionsUrl(model)
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
            Retry.sleep(retryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), signal)
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
            val delayMs = retryDelayMs(response, retryIndex, options.maxRetryDelayMs, providerMessage)
            retriesRemaining -= 1
            Retry.sleep(delayMs, signal)
            continue
        }
        throw providerHttpError(response)
    }
}

private fun isRetryableResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun retryDelayMs(
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
        throw CompletionsStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun providerHttpError(response: HttpResponse): ProviderHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return ProviderHttpException(response.status, response.headers, body)
}
