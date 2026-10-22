package dev.pi.ai.api

import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelCostTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class AnthropicAllowedFallbackModel(
    val provider: String,
    val model: String,
    val cost: ModelCost,
)

internal enum class AnthropicSessionAffinityFormat(val wireName: String) {
    Openrouter("openrouter"),
}

internal data class AnthropicMessagesCompat(
    val supportsEagerToolInputStreaming: Boolean? = null,
    val supportsLongCacheRetention: Boolean? = null,
    val sendSessionAffinityHeaders: Boolean? = null,
    val sessionAffinityFormat: AnthropicSessionAffinityFormat? = null,
    val supportsCacheControlOnTools: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val forceAdaptiveThinking: Boolean? = null,
    val allowEmptySignature: Boolean? = null,
    val supportsStrictTools: Boolean? = null,
    val supportsMidConvoEffort: Boolean? = null,
    val supportsMidConvoSystemMessages: Boolean? = null,
    val supportsMidConvoToolChanges: Boolean? = null,
    val allowedFallbackModels: List<AnthropicAllowedFallbackModel>? = null,
) {
    companion object {
        fun fromJson(json: JsonObject?): AnthropicMessagesCompat {
            if (json == null) return AnthropicMessagesCompat()
            return AnthropicMessagesCompat(
                supportsEagerToolInputStreaming = booleanField(json, "supportsEagerToolInputStreaming"),
                supportsLongCacheRetention = booleanField(json, "supportsLongCacheRetention"),
                sendSessionAffinityHeaders = booleanField(json, "sendSessionAffinityHeaders"),
                sessionAffinityFormat = enumField(
                    json,
                    "sessionAffinityFormat",
                    AnthropicSessionAffinityFormat.entries.toTypedArray(),
                ) { it.wireName },
                supportsCacheControlOnTools = booleanField(json, "supportsCacheControlOnTools"),
                supportsTemperature = booleanField(json, "supportsTemperature"),
                forceAdaptiveThinking = booleanField(json, "forceAdaptiveThinking"),
                allowEmptySignature = booleanField(json, "allowEmptySignature"),
                supportsStrictTools = booleanField(json, "supportsStrictTools"),
                supportsMidConvoEffort = booleanField(json, "supportsMidConvoEffort"),
                supportsMidConvoSystemMessages = booleanField(json, "supportsMidConvoSystemMessages"),
                supportsMidConvoToolChanges = booleanField(json, "supportsMidConvoToolChanges"),
                allowedFallbackModels = parseAllowedFallbackModels(arrayField(json, "allowedFallbackModels")),
            )
        }
    }
}

internal data class ResolvedAnthropicMessagesCompat(
    val supportsEagerToolInputStreaming: Boolean,
    val supportsLongCacheRetention: Boolean,
    val sendSessionAffinityHeaders: Boolean,
    val sessionAffinityFormat: AnthropicSessionAffinityFormat?,
    val supportsCacheControlOnTools: Boolean,
    val supportsTemperature: Boolean,
    val forceAdaptiveThinking: Boolean,
    val allowEmptySignature: Boolean,
    val supportsStrictTools: Boolean,
    val supportsMidConvoEffort: Boolean,
    val supportsMidConvoSystemMessages: Boolean,
    val supportsMidConvoToolChanges: Boolean,
    val allowedFallbackModels: List<AnthropicAllowedFallbackModel>,
)

internal fun resolveAnthropicMessagesCompat(model: Model): ResolvedAnthropicMessagesCompat {
    val overrides = AnthropicMessagesCompat.fromJson(model.compat)
    val isOpenRouter = model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")
    return ResolvedAnthropicMessagesCompat(
        supportsEagerToolInputStreaming = overrides.supportsEagerToolInputStreaming ?: true,
        supportsLongCacheRetention = overrides.supportsLongCacheRetention ?: true,
        sendSessionAffinityHeaders = overrides.sendSessionAffinityHeaders ?: isOpenRouter,
        sessionAffinityFormat = overrides.sessionAffinityFormat
            ?: if (isOpenRouter) AnthropicSessionAffinityFormat.Openrouter else null,
        supportsCacheControlOnTools = overrides.supportsCacheControlOnTools ?: true,
        supportsTemperature = overrides.supportsTemperature ?: true,
        forceAdaptiveThinking = overrides.forceAdaptiveThinking ?: false,
        allowEmptySignature = overrides.allowEmptySignature ?: false,
        supportsStrictTools = overrides.supportsStrictTools ?: false,
        supportsMidConvoEffort = overrides.supportsMidConvoEffort ?: false,
        supportsMidConvoSystemMessages = overrides.supportsMidConvoSystemMessages ?: false,
        supportsMidConvoToolChanges = overrides.supportsMidConvoToolChanges ?: false,
        allowedFallbackModels = overrides.allowedFallbackModels.orEmpty(),
    )
}

private fun <T : Enum<T>> enumField(element: JsonObject, key: String, values: Array<T>, wireName: (T) -> String): T? {
    val raw = stringField(element, key) ?: return null
    return values.firstOrNull { wireName(it) == raw }
}

private fun parseAllowedFallbackModels(json: JsonArray?): List<AnthropicAllowedFallbackModel>? {
    if (json == null) return null
    return json.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val provider = stringField(obj, "provider") ?: return@mapNotNull null
        val model = stringField(obj, "model") ?: return@mapNotNull null
        val cost = objectField(obj, "cost") ?: return@mapNotNull null
        AnthropicAllowedFallbackModel(
            provider = provider,
            model = model,
            cost = ModelCost(
                input = doubleField(cost, "input") ?: 0.0,
                output = doubleField(cost, "output") ?: 0.0,
                cacheRead = doubleField(cost, "cacheRead") ?: 0.0,
                cacheWrite = doubleField(cost, "cacheWrite") ?: 0.0,
                tiers = parseCostTiers(arrayField(cost, "tiers")),
            ),
        )
    }
}

private fun parseCostTiers(json: JsonArray?): List<ModelCostTier>? {
    if (json == null) return null
    return json.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        ModelCostTier(
            inputTokensAbove = longField(obj, "inputTokensAbove") ?: return@mapNotNull null,
            input = doubleField(obj, "input") ?: 0.0,
            output = doubleField(obj, "output") ?: 0.0,
            cacheRead = doubleField(obj, "cacheRead") ?: 0.0,
            cacheWrite = doubleField(obj, "cacheWrite") ?: 0.0,
        )
    }
}

internal fun JsonElement.stringOrNullValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
