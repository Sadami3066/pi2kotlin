package dev.pi.ai.api

import dev.pi.ai.Model
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.ThinkingLevel
import dev.pi.ai.TranscriptContext
import dev.pi.ai.clampThinkingLevel
import dev.pi.ai.utils.estimateContextTokens
import kotlinx.serialization.json.JsonObject

internal const val CONTEXT_SAFETY_TOKENS: Long = 4096
internal const val MIN_MAX_TOKENS: Long = 1
internal const val MIN_ANSWER_TOKENS: Long = 1024

internal val DEFAULT_THINKING_BUDGETS: ThinkingBudgets = ThinkingBudgets(
    minimal = 1024,
    low = 2048,
    medium = 8192,
    high = 16384,
)

internal fun ThinkingLevel.toModelThinkingLevel(): ModelThinkingLevel = when (this) {
    ThinkingLevel.Minimal -> ModelThinkingLevel.Minimal
    ThinkingLevel.Low -> ModelThinkingLevel.Low
    ThinkingLevel.Medium -> ModelThinkingLevel.Medium
    ThinkingLevel.High -> ModelThinkingLevel.High
    ThinkingLevel.Xhigh -> ModelThinkingLevel.Xhigh
    ThinkingLevel.Max -> ModelThinkingLevel.Max
}

internal fun ModelThinkingLevel.jsonName(): String = when (this) {
    ModelThinkingLevel.Off -> "off"
    ModelThinkingLevel.Minimal -> "minimal"
    ModelThinkingLevel.Low -> "low"
    ModelThinkingLevel.Medium -> "medium"
    ModelThinkingLevel.High -> "high"
    ModelThinkingLevel.Xhigh -> "xhigh"
    ModelThinkingLevel.Max -> "max"
}

internal fun clampMaxTokensToContext(model: Model, context: TranscriptContext, maxTokens: Long): Long {
    if (model.contextWindow <= 0) return maxOf(MIN_MAX_TOKENS, maxTokens)
    val available = model.contextWindow - estimateContextTokens(context.messages).tokens - CONTEXT_SAFETY_TOKENS
    return minOf(maxTokens, maxOf(MIN_MAX_TOKENS, available))
}

internal fun resolveSamplingParams(
    model: Model,
    thinkingLevel: ModelThinkingLevel,
    requestParams: JsonObject?,
): JsonObject? {
    val effective = clampThinkingLevel(model, thinkingLevel)
    val levelParams = model.samplingParamsByThinkingLevel?.get(effective.jsonName())
    if (model.samplingParams == null && levelParams == null && requestParams == null) return null
    val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    model.samplingParams?.forEach { (key, value) -> merged[key] = value }
    levelParams?.forEach { (key, value) -> merged[key] = value }
    requestParams?.forEach { (key, value) -> merged[key] = value }
    return JsonObject(merged)
}

internal fun buildBaseOptions(model: Model, context: TranscriptContext, options: SimpleStreamOptions): StreamOptions {
    val reasoningLevel = options.reasoning?.toModelThinkingLevel() ?: ModelThinkingLevel.Off
    val samplingParams = resolveSamplingParams(model, reasoningLevel, options.stream.samplingParams)
    return options.stream.copy(
        samplingParams = samplingParams,
        maxTokens = clampMaxTokensToContext(model, context, options.stream.maxTokens ?: model.maxTokens),
    )
}

internal fun clampReasoning(level: ModelThinkingLevel): ModelThinkingLevel = when (level) {
    ModelThinkingLevel.Xhigh, ModelThinkingLevel.Max -> ModelThinkingLevel.High
    else -> level
}

internal fun thinkingBudgetForLevel(level: ModelThinkingLevel, customBudgets: ThinkingBudgets?): Long {
    val budgets = ThinkingBudgets(
        minimal = customBudgets?.minimal ?: DEFAULT_THINKING_BUDGETS.minimal,
        low = customBudgets?.low ?: DEFAULT_THINKING_BUDGETS.low,
        medium = customBudgets?.medium ?: DEFAULT_THINKING_BUDGETS.medium,
        high = customBudgets?.high ?: DEFAULT_THINKING_BUDGETS.high,
    )
    return when (clampReasoning(level)) {
        ModelThinkingLevel.Minimal -> budgets.minimal ?: 0
        ModelThinkingLevel.Low -> budgets.low ?: 0
        ModelThinkingLevel.Medium -> budgets.medium ?: 0
        else -> budgets.high ?: 0
    }
}

internal fun clampThinkingBudgetToAnswerRoom(thinkingBudget: Long, ceiling: Long): Long =
    minOf(thinkingBudget, maxOf(0L, ceiling - MIN_ANSWER_TOKENS))
