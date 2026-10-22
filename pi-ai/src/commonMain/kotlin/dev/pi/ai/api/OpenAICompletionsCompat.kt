package dev.pi.ai.api

import dev.pi.ai.CacheRetention
import dev.pi.ai.Model
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class MaxTokensField(val wireName: String) {
    MaxCompletionTokens("max_completion_tokens"),
    MaxTokens("max_tokens"),
}

internal enum class ThinkingFormat(val wireName: String) {
    Openai("openai"),
    Openrouter("openrouter"),
    Deepseek("deepseek"),
    Together("together"),
    Baseten("baseten"),
    Zai("zai"),
    Qwen("qwen"),
    ChatTemplate("chat-template"),
    QwenChatTemplate("qwen-chat-template"),
    StringThinking("string-thinking"),
    AntLing("ant-ling"),
}

internal enum class SessionAffinityFormat(val wireName: String) {
    Openai("openai"),
    OpenaiNosession("openai-nosession"),
    Openrouter("openrouter"),
}

internal enum class CacheControlFormat(val wireName: String) {
    Anthropic("anthropic"),
}

internal enum class ThinkingTokenBudgetField(val wireName: String) {
    ThinkingTokenBudget("thinking_token_budget"),
    ThinkingBudget("thinking_budget"),
    ThinkingBudgetTokens("thinking_budget_tokens"),
}

internal fun stringField(element: JsonObject, key: String): String? {
    val value = element[key] as? JsonPrimitive ?: return null
    return value.takeIf { it.isString }?.content
}

internal fun booleanField(element: JsonObject, key: String): Boolean? {
    val value = element[key] as? JsonPrimitive ?: return null
    if (value is JsonNull) return null
    return when (value.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun objectField(element: JsonObject, key: String): JsonObject? = element[key] as? JsonObject

internal fun arrayField(element: JsonObject, key: String): JsonArray? = element[key] as? JsonArray

internal fun intField(element: JsonObject, key: String): Int? = longField(element, key)?.toInt()

internal fun longField(element: JsonObject, key: String): Long? {
    val value = element[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.content.toLongOrNull()
}

internal fun doubleField(element: JsonObject, key: String): Double? {
    val value = element[key] as? JsonPrimitive ?: return null
    if (value.isString) return null
    return value.content.toDoubleOrNull()
}

private fun <T : Enum<T>> enumField(element: JsonObject, key: String, values: Array<T>, wireName: (T) -> String): T? {
    val raw = stringField(element, key) ?: return null
    return values.firstOrNull { wireName(it) == raw }
}

internal data class OpenAICompletionsCompat(
    val supportsStore: Boolean? = null,
    val supportsDeveloperRole: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportsUsageInStreaming: Boolean? = null,
    val supportsFinishReason: Boolean? = null,
    val maxTokensField: MaxTokensField? = null,
    val requiresToolResultName: Boolean? = null,
    val requiresAssistantAfterToolResult: Boolean? = null,
    val requiresThinkingAsText: Boolean? = null,
    val requiresReasoningContentOnAssistantMessages: Boolean? = null,
    val thinkingFormat: ThinkingFormat? = null,
    val chatTemplateKwargs: JsonObject? = null,
    val chatTemplateArgs: JsonObject? = null,
    val openRouterRouting: JsonObject? = null,
    val vercelGatewayRouting: JsonObject? = null,
    val zaiToolStream: Boolean? = null,
    val thinkingTokenBudgetField: ThinkingTokenBudgetField? = null,
    val supportsThinkingTokenBudget: Boolean? = null,
    val supportsOpenAIGrammarTools: Boolean? = null,
    val supportsMidConvoSystemMessages: Boolean? = null,
    val supportsMidConvoToolAdditions: Boolean? = null,
    val supportsStrictMode: Boolean? = null,
    val cacheControlFormat: CacheControlFormat? = null,
    val sendSessionAffinityHeaders: Boolean? = null,
    val sessionAffinityFormat: SessionAffinityFormat? = null,
    val supportsLongCacheRetention: Boolean? = null,
    val vllmPriority: Double? = null,
) {
    companion object {
        fun fromJson(json: JsonObject?): OpenAICompletionsCompat {
            if (json == null) return OpenAICompletionsCompat()
            return OpenAICompletionsCompat(
                supportsStore = booleanField(json, "supportsStore"),
                supportsDeveloperRole = booleanField(json, "supportsDeveloperRole"),
                supportsReasoningEffort = booleanField(json, "supportsReasoningEffort"),
                supportsUsageInStreaming = booleanField(json, "supportsUsageInStreaming"),
                supportsFinishReason = booleanField(json, "supportsFinishReason"),
                maxTokensField = enumField(json, "maxTokensField", MaxTokensField.entries.toTypedArray()) { it.wireName },
                requiresToolResultName = booleanField(json, "requiresToolResultName"),
                requiresAssistantAfterToolResult = booleanField(json, "requiresAssistantAfterToolResult"),
                requiresThinkingAsText = booleanField(json, "requiresThinkingAsText"),
                requiresReasoningContentOnAssistantMessages =
                booleanField(json, "requiresReasoningContentOnAssistantMessages"),
                thinkingFormat = enumField(json, "thinkingFormat", ThinkingFormat.entries.toTypedArray()) { it.wireName },
                chatTemplateKwargs = objectField(json, "chatTemplateKwargs"),
                chatTemplateArgs = objectField(json, "chatTemplateArgs"),
                openRouterRouting = objectField(json, "openRouterRouting"),
                vercelGatewayRouting = objectField(json, "vercelGatewayRouting"),
                zaiToolStream = booleanField(json, "zaiToolStream"),
                thinkingTokenBudgetField = enumField(
                    json,
                    "thinkingTokenBudgetField",
                    ThinkingTokenBudgetField.entries.toTypedArray(),
                ) { it.wireName },
                supportsThinkingTokenBudget = booleanField(json, "supportsThinkingTokenBudget"),
                supportsOpenAIGrammarTools = booleanField(json, "supportsOpenAIGrammarTools"),
                supportsMidConvoSystemMessages = booleanField(json, "supportsMidConvoSystemMessages"),
                supportsMidConvoToolAdditions = booleanField(json, "supportsMidConvoToolAdditions"),
                supportsStrictMode = booleanField(json, "supportsStrictMode"),
                cacheControlFormat = enumField(json, "cacheControlFormat", CacheControlFormat.entries.toTypedArray()) {
                    it.wireName
                },
                sendSessionAffinityHeaders = booleanField(json, "sendSessionAffinityHeaders"),
                sessionAffinityFormat = enumField(
                    json,
                    "sessionAffinityFormat",
                    SessionAffinityFormat.entries.toTypedArray(),
                ) { it.wireName },
                supportsLongCacheRetention = booleanField(json, "supportsLongCacheRetention"),
                vllmPriority = doubleField(json, "vllmPriority"),
            )
        }
    }
}

internal data class ResolvedOpenAICompletionsCompat(
    val supportsStore: Boolean,
    val supportsDeveloperRole: Boolean,
    val supportsReasoningEffort: Boolean,
    val supportsUsageInStreaming: Boolean,
    val supportsFinishReason: Boolean,
    val maxTokensField: MaxTokensField,
    val requiresToolResultName: Boolean,
    val requiresAssistantAfterToolResult: Boolean,
    val requiresThinkingAsText: Boolean,
    val requiresReasoningContentOnAssistantMessages: Boolean,
    val thinkingFormat: ThinkingFormat,
    val chatTemplateKwargs: JsonObject,
    val chatTemplateArgs: JsonObject,
    val openRouterRouting: JsonObject?,
    val vercelGatewayRouting: JsonObject,
    val zaiToolStream: Boolean,
    val thinkingTokenBudgetField: ThinkingTokenBudgetField?,
    val supportsThinkingTokenBudget: Boolean,
    val supportsOpenAIGrammarTools: Boolean,
    val supportsMidConvoSystemMessages: Boolean,
    val supportsMidConvoToolAdditions: Boolean,
    val supportsStrictMode: Boolean,
    val cacheControlFormat: CacheControlFormat?,
    val sendSessionAffinityHeaders: Boolean,
    val sessionAffinityFormat: SessionAffinityFormat,
    val supportsLongCacheRetention: Boolean,
    val vllmPriority: Double?,
)

internal fun detectCompat(model: Model): ResolvedOpenAICompletionsCompat {
    val provider = model.provider
    val baseUrl = model.baseUrl

    val isZai = provider == "zai" || provider == "zai-coding-cn" ||
        baseUrl.contains("api.z.ai") || baseUrl.contains("open.bigmodel.cn")
    val isTogether = provider == "together" ||
        baseUrl.contains("api.together.ai") || baseUrl.contains("api.together.xyz")
    val isMoonshot = provider == "moonshotai" || provider == "moonshotai-cn" || baseUrl.contains("api.moonshot.")
    val isOpenRouter = provider == "openrouter" || baseUrl.contains("openrouter.ai")
    val isCloudflareWorkersAi = provider == "cloudflare-workers-ai" || baseUrl.contains("api.cloudflare.com")
    val isCloudflareAiGateway = provider == "cloudflare-ai-gateway" ||
        baseUrl.contains("gateway.ai.cloudflare.com")
    val isNvidia = provider == "nvidia" || baseUrl.contains("integrate.api.nvidia.com")
    val isAntLing = provider == "ant-ling" || baseUrl.contains("api.ant-ling.com")
    val isCerebras = provider == "cerebras" || baseUrl.contains("cerebras.ai")
    val isDeepSeek = provider == "deepseek" || baseUrl.lowercase().contains("deepseek.com")

    val isNonStandard = isNvidia || isCerebras || provider == "xai" || baseUrl.contains("api.x.ai") ||
        isTogether || baseUrl.contains("chutes.ai") || isDeepSeek || isZai || isMoonshot ||
        provider == "opencode" || baseUrl.contains("opencode.ai") || isCloudflareWorkersAi ||
        isCloudflareAiGateway || isAntLing

    val useMaxTokens = baseUrl.contains("chutes.ai") || isDeepSeek || isMoonshot || isCloudflareAiGateway ||
        isTogether || isNvidia || isAntLing || isZai

    val isGrok = provider == "xai" || baseUrl.contains("api.x.ai")
    val isOpenRouterDeveloperRoleModel = isOpenRouter &&
        (model.id.startsWith("anthropic/") || model.id.startsWith("openai/"))
    val cacheControlFormat = if (provider == "openrouter" && model.id.startsWith("anthropic/")) {
        CacheControlFormat.Anthropic
    } else {
        null
    }

    return ResolvedOpenAICompletionsCompat(
        supportsStore = !isNonStandard,
        supportsDeveloperRole = isOpenRouterDeveloperRoleModel || (!isNonStandard && !isOpenRouter),
        supportsReasoningEffort = !isGrok && !isZai && !isMoonshot && !isTogether && !isCloudflareAiGateway &&
            !isNvidia && !isAntLing,
        supportsUsageInStreaming = true,
        supportsFinishReason = true,
        maxTokensField = if (useMaxTokens) MaxTokensField.MaxTokens else MaxTokensField.MaxCompletionTokens,
        requiresToolResultName = false,
        requiresAssistantAfterToolResult = false,
        requiresThinkingAsText = false,
        requiresReasoningContentOnAssistantMessages = isDeepSeek,
        thinkingFormat = when {
            isDeepSeek -> ThinkingFormat.Deepseek
            isZai -> ThinkingFormat.Zai
            isTogether -> ThinkingFormat.Together
            isAntLing -> ThinkingFormat.AntLing
            isOpenRouter -> ThinkingFormat.Openrouter
            else -> ThinkingFormat.Openai
        },
        chatTemplateKwargs = JsonObject(emptyMap()),
        chatTemplateArgs = JsonObject(emptyMap()),
        openRouterRouting = null,
        vercelGatewayRouting = JsonObject(emptyMap()),
        zaiToolStream = false,
        thinkingTokenBudgetField = null,
        supportsThinkingTokenBudget = false,
        supportsOpenAIGrammarTools = false,
        supportsMidConvoSystemMessages = false,
        supportsMidConvoToolAdditions = false,
        supportsStrictMode = false,
        cacheControlFormat = cacheControlFormat,
        sendSessionAffinityHeaders = isOpenRouter,
        sessionAffinityFormat = if (isOpenRouter) SessionAffinityFormat.Openrouter else SessionAffinityFormat.Openai,
        supportsLongCacheRetention = !(isTogether || isCloudflareWorkersAi || isCloudflareAiGateway ||
            isNvidia || isAntLing),
        vllmPriority = null,
    )
}

internal fun resolveOpenAICompletionsCompat(model: Model): ResolvedOpenAICompletionsCompat {
    val detected = detectCompat(model)
    val overrides = OpenAICompletionsCompat.fromJson(model.compat)
    return ResolvedOpenAICompletionsCompat(
        supportsStore = overrides.supportsStore ?: detected.supportsStore,
        supportsDeveloperRole = overrides.supportsDeveloperRole ?: detected.supportsDeveloperRole,
        supportsReasoningEffort = overrides.supportsReasoningEffort ?: detected.supportsReasoningEffort,
        supportsUsageInStreaming = overrides.supportsUsageInStreaming ?: detected.supportsUsageInStreaming,
        supportsFinishReason = overrides.supportsFinishReason ?: detected.supportsFinishReason,
        maxTokensField = overrides.maxTokensField ?: detected.maxTokensField,
        requiresToolResultName = overrides.requiresToolResultName ?: detected.requiresToolResultName,
        requiresAssistantAfterToolResult = overrides.requiresAssistantAfterToolResult
            ?: detected.requiresAssistantAfterToolResult,
        requiresThinkingAsText = overrides.requiresThinkingAsText ?: detected.requiresThinkingAsText,
        requiresReasoningContentOnAssistantMessages = overrides.requiresReasoningContentOnAssistantMessages
            ?: detected.requiresReasoningContentOnAssistantMessages,
        thinkingFormat = overrides.thinkingFormat ?: detected.thinkingFormat,
        chatTemplateKwargs = overrides.chatTemplateKwargs ?: detected.chatTemplateKwargs,
        chatTemplateArgs = overrides.chatTemplateArgs ?: detected.chatTemplateArgs,
        openRouterRouting = overrides.openRouterRouting ?: detected.openRouterRouting,
        vercelGatewayRouting = overrides.vercelGatewayRouting ?: detected.vercelGatewayRouting,
        zaiToolStream = overrides.zaiToolStream ?: detected.zaiToolStream,
        thinkingTokenBudgetField = overrides.thinkingTokenBudgetField ?: detected.thinkingTokenBudgetField,
        supportsThinkingTokenBudget = overrides.supportsThinkingTokenBudget ?: detected.supportsThinkingTokenBudget,
        supportsOpenAIGrammarTools = overrides.supportsOpenAIGrammarTools ?: detected.supportsOpenAIGrammarTools,
        supportsMidConvoSystemMessages = overrides.supportsMidConvoSystemMessages
            ?: detected.supportsMidConvoSystemMessages,
        supportsMidConvoToolAdditions = overrides.supportsMidConvoToolAdditions
            ?: detected.supportsMidConvoToolAdditions,
        supportsStrictMode = overrides.supportsStrictMode ?: detected.supportsStrictMode,
        cacheControlFormat = overrides.cacheControlFormat ?: detected.cacheControlFormat,
        sendSessionAffinityHeaders = overrides.sendSessionAffinityHeaders ?: detected.sendSessionAffinityHeaders,
        sessionAffinityFormat = overrides.sessionAffinityFormat ?: detected.sessionAffinityFormat,
        supportsLongCacheRetention = overrides.supportsLongCacheRetention ?: detected.supportsLongCacheRetention,
        vllmPriority = overrides.vllmPriority,
    )
}

internal fun resolveCacheRetention(cacheRetention: CacheRetention?, env: Map<String, String>?): CacheRetention {
    if (cacheRetention != null) return cacheRetention
    if (env?.get("PI_CACHE_RETENTION") == "long") return CacheRetention.Long
    return CacheRetention.Short
}

internal fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
