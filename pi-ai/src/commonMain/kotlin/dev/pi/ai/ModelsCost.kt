package dev.pi.ai

fun calculateCost(model: AnyModel, usage: Usage): UsageCost {
    val inputTokens = usage.input + usage.cacheRead + usage.cacheWrite
    var rates = ModelCostRates(model.cost.input, model.cost.output, model.cost.cacheRead, model.cost.cacheWrite)
    var matchedThreshold = -1L
    for (tier in model.cost.tiers.orEmpty()) {
        if (inputTokens > tier.inputTokensAbove && tier.inputTokensAbove > matchedThreshold) {
            rates = ModelCostRates(tier.input, tier.output, tier.cacheRead, tier.cacheWrite)
            matchedThreshold = tier.inputTokensAbove
        }
    }

    val longWrite = usage.cacheWrite1h ?: 0L
    val shortWrite = usage.cacheWrite - longWrite
    usage.cost.input = rates.input / 1_000_000.0 * usage.input.toDouble()
    usage.cost.output = rates.output / 1_000_000.0 * usage.output.toDouble()
    usage.cost.cacheRead = rates.cacheRead / 1_000_000.0 * usage.cacheRead.toDouble()
    usage.cost.cacheWrite = (rates.cacheWrite * shortWrite.toDouble() + rates.input * 2.0 * longWrite.toDouble()) / 1_000_000.0
    usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite
    return usage.cost
}

private val EXTENDED_THINKING_LEVELS = listOf(
    ModelThinkingLevel.Off,
    ModelThinkingLevel.Minimal,
    ModelThinkingLevel.Low,
    ModelThinkingLevel.Medium,
    ModelThinkingLevel.High,
    ModelThinkingLevel.Xhigh,
    ModelThinkingLevel.Max,
)

private fun ModelThinkingLevel.serialName(): String = when (this) {
    ModelThinkingLevel.Off -> "off"
    ModelThinkingLevel.Minimal -> "minimal"
    ModelThinkingLevel.Low -> "low"
    ModelThinkingLevel.Medium -> "medium"
    ModelThinkingLevel.High -> "high"
    ModelThinkingLevel.Xhigh -> "xhigh"
    ModelThinkingLevel.Max -> "max"
}

fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel> {
    if (!model.reasoning) return listOf(ModelThinkingLevel.Off)
    val levels = model.thinkingLevelMap
    return EXTENDED_THINKING_LEVELS.filter { level ->
        val name = level.serialName()
        val present = levels?.containsKey(name) == true
        if (present && levels[name] == null) return@filter false
        if (level == ModelThinkingLevel.Xhigh || level == ModelThinkingLevel.Max) present else true
    }
}

fun clampThinkingLevel(model: Model, level: ModelThinkingLevel): ModelThinkingLevel {
    val available = getSupportedThinkingLevels(model)
    if (available.contains(level)) return level

    val requestedIndex = EXTENDED_THINKING_LEVELS.indexOf(level)
    if (requestedIndex == -1) return available.firstOrNull() ?: ModelThinkingLevel.Off

    for (index in requestedIndex until EXTENDED_THINKING_LEVELS.size) {
        val candidate = EXTENDED_THINKING_LEVELS[index]
        if (available.contains(candidate)) return candidate
    }
    for (index in requestedIndex - 1 downTo 0) {
        val candidate = EXTENDED_THINKING_LEVELS[index]
        if (available.contains(candidate)) return candidate
    }
    return available.firstOrNull() ?: ModelThinkingLevel.Off
}

fun modelsAreEqual(a: AnyModel?, b: AnyModel?): Boolean {
    if (a == null || b == null) return false
    return a.modelType == b.modelType && a.id == b.id && a.provider == b.provider
}
