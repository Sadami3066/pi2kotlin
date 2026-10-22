package dev.pi.coding.models

import dev.pi.ai.Model
import dev.pi.ai.modelsAreEqual

data class ResolvedModel(
    val model: Model,
    val thinkingLevel: String?,
)

val THINKING_LEVELS: List<String> = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

val DEFAULT_MODEL_PER_PROVIDER: Map<String, String> = linkedMapOf(
    "amazon-bedrock" to "us.anthropic.claude-opus-4-6-v1",
    "ant-ling" to "Ring-2.6-1T",
    "anthropic" to "claude-opus-4-8",
    "openai" to "gpt-5.5",
    "azure" to "gpt-5.4",
    "openai-codex" to "gpt-6.1-sol",
    "radius" to "balanced",
    "nvidia" to "nvidia/nemotron-3.5-super-vl-120b-a12b",
    "deepseek" to "deepseek-v4-pro",
    "google" to "gemini-3.1-pro-preview",
    "google-vertex" to "gemini-3.1-pro-preview",
    "github-copilot" to "gpt-6.1-sol",
    "openrouter" to "moonshotai/kimi-k2.6",
    "vercel-ai-gateway" to "zai/glm-5.1",
    "xai" to "grok-4.7",
    "groq" to "openai/gpt-oss-120b",
    "cerebras" to "gpt-oss-120b",
    "zai" to "glm-5.3",
    "zai-coding-cn" to "glm-5.3",
    "mistral" to "devstral-medium-latest",
    "minimax" to "MiniMax-M2.7",
    "minimax-cn" to "MiniMax-M2.7",
    "moonshotai" to "kimi-k2.6",
    "moonshotai-cn" to "kimi-k2.6",
    "huggingface" to "moonshotai/Kimi-K2.6",
    "fireworks" to "accounts/fireworks/models/kimi-k3",
    "together" to "moonshotai/Kimi-K3",
    "baseten" to "zai-org/GLM-5.2",
    "opencode" to "kimi-k2.6",
    "opencode-go" to "kimi-k3",
    "kimi-coding" to "kimi-for-coding",
    "meta" to "muse-spark-1.3",
    "cloudflare-workers-ai" to "@cf/moonshotai/kimi-k2.6",
    "cloudflare-ai-gateway" to "workers-ai/@cf/moonshotai/kimi-k2.6",
    "qwen-token-plan" to "qwen3.7-max",
    "qwen-token-plan-cn" to "qwen3.7-max",
    "qwen-token-plan-individual" to "qwen3.8-max",
    "xiaomi" to "mimo-v2.6-pro",
    "xiaomi-token-plan-cn" to "mimo-v2.6-pro",
    "xiaomi-token-plan-ams" to "mimo-v2.6-pro",
    "xiaomi-token-plan-sgp" to "mimo-v2.6-pro",
)

private enum class ThinkingSuffix { None, Strict, Lenient }

private sealed interface ReferenceResult {
    data class Resolved(
        val model: Model,
        val thinkingLevel: String? = null,
        val warning: String? = null,
    ) : ReferenceResult

    data class Ambiguous(
        val candidates: List<Model>,
        val usableCount: Int,
        val thinkingLevel: String? = null,
        val warning: String? = null,
    ) : ReferenceResult

    data object NotFound : ReferenceResult
}

class ModelResolver(
    val runtime: ModelRuntime,
    val defaultProvider: String?,
    val defaultModel: String?,
    val enabledModels: List<String>?,
) {
    suspend fun resolve(spec: String): ResolvedModel {
        val text = spec.trim()
        if (text.isEmpty()) {
            return resolveDefault() ?: throw IllegalArgumentException("No model configured.")
        }
        val models = runtime.getModels()
        val usableByProvider = usableProviders(models)
        val usable: (String) -> Boolean = { provider -> usableByProvider[provider] == true }
        val result = resolveReference(text, models, usable, ThinkingSuffix.Strict, fuzzy = true)
        return when (result) {
            is ReferenceResult.Resolved -> ResolvedModel(result.model, result.thinkingLevel)
            is ReferenceResult.Ambiguous -> throw IllegalArgumentException(
                ambiguousMessage(text, result.candidates, result.usableCount),
            )

            ReferenceResult.NotFound -> throw IllegalArgumentException(
                "Model \"$text\" not found. Use --list-models to see available models.",
            )
        }
    }

    suspend fun resolveDefault(): ResolvedModel? {
        val enabled = resolveEnabledModels()
        if (enabled.isNotEmpty()) return enabled.first()
        if (defaultProvider != null && defaultModel != null) {
            val found = runtime.getModel(defaultProvider, defaultModel)
            if (found != null && runtime.hasConfiguredAuth(found.provider)) {
                return ResolvedModel(found, null)
            }
        }
        val available = runtime.getAvailableSnapshot()
        if (available.isEmpty()) return null
        for ((provider, modelId) in DEFAULT_MODEL_PER_PROVIDER) {
            val match = available.firstOrNull { it.provider == provider && it.id == modelId }
            if (match != null) return ResolvedModel(match, null)
        }
        return ResolvedModel(available.first(), null)
    }

    private suspend fun usableProviders(models: List<Model>): Map<String, Boolean> {
        val result = LinkedHashMap<String, Boolean>()
        for (providerId in models.map { it.provider }.distinct()) {
            result[providerId] = runtime.hasConfiguredAuth(providerId)
        }
        return result
    }

    private suspend fun resolveEnabledModels(): List<ResolvedModel> {
        val patterns = enabledModels?.filter { it.isNotBlank() }.orEmpty()
        if (patterns.isEmpty()) return emptyList()
        val available = runtime.getAvailableSnapshot()
        if (available.isEmpty()) return emptyList()
        val resolved = mutableListOf<ResolvedModel>()
        for (pattern in patterns) {
            for (candidate in resolveScopePattern(pattern, available)) {
                if (resolved.none { modelsAreEqual(it.model, candidate.model) }) resolved.add(candidate)
            }
        }
        return resolved
    }

    private fun resolveScopePattern(pattern: String, models: List<Model>): List<ResolvedModel> {
        if (pattern.contains('*') || pattern.contains('?') || pattern.contains('[')) {
            val thinking = splitThinkingSuffix(pattern)
            val globPattern = thinking?.first ?: pattern
            val thinkingLevel = thinking?.second
            val exact = resolveReference(
                globPattern,
                models,
                { true },
                ThinkingSuffix.None,
                fuzzy = false,
            )
            val matches = when (exact) {
                is ReferenceResult.Resolved -> listOf(exact.model)
                is ReferenceResult.Ambiguous -> exact.candidates
                ReferenceResult.NotFound -> {
                    val regex = globToRegex(globPattern)
                    models.filter { model ->
                        regex.matches("${model.provider}/${model.id}") || regex.matches(model.id)
                    }
                }
            }
            return matches.map { ResolvedModel(it, thinkingLevel) }
        }
        val result = resolveReference(pattern, models, { true }, ThinkingSuffix.Lenient, fuzzy = true)
        return when (result) {
            is ReferenceResult.Resolved -> listOf(ResolvedModel(result.model, result.thinkingLevel))
            is ReferenceResult.Ambiguous -> result.candidates.map { ResolvedModel(it, result.thinkingLevel) }
            ReferenceResult.NotFound -> emptyList()
        }
    }

    private fun resolveReference(
        text: String,
        pool: List<Model>,
        usable: (String) -> Boolean,
        thinkingSuffix: ThinkingSuffix,
        fuzzy: Boolean,
    ): ReferenceResult = resolveText(text, pool, usable, thinkingSuffix, fuzzy)

    private fun resolveText(
        text: String,
        pool: List<Model>,
        usable: (String) -> Boolean,
        thinkingSuffix: ThinkingSuffix,
        fuzzy: Boolean,
    ): ReferenceResult {
        selectReference(text, pool, fuzzy, usable)?.let { return it }
        if (fuzzy) {
            selectFuzzy(fuzzyMatches(text, pool), usable)?.let { return it }
        }
        if (thinkingSuffix == ThinkingSuffix.None) return ReferenceResult.NotFound
        splitThinkingSuffix(text)?.let { (base, level) ->
            return when (val inner = resolveText(base, pool, usable, thinkingSuffix, fuzzy)) {
                is ReferenceResult.Resolved -> inner.copy(thinkingLevel = if (inner.warning != null) null else level)
                is ReferenceResult.Ambiguous -> inner.copy(thinkingLevel = if (inner.warning != null) null else level)
                ReferenceResult.NotFound -> inner
            }
        }
        if (thinkingSuffix == ThinkingSuffix.Strict) return ReferenceResult.NotFound
        val colonIndex = text.lastIndexOf(':')
        if (colonIndex == -1) return ReferenceResult.NotFound
        val suffix = text.substring(colonIndex + 1)
        return when (val inner = resolveText(text.substring(0, colonIndex), pool, usable, thinkingSuffix, fuzzy)) {
            is ReferenceResult.Resolved -> inner.copy(
                thinkingLevel = null,
                warning = "Invalid thinking level \"$suffix\" in pattern \"$text\". Using default instead.",
            )

            is ReferenceResult.Ambiguous -> inner.copy(
                thinkingLevel = null,
                warning = "Invalid thinking level \"$suffix\" in pattern \"$text\". Using default instead.",
            )

            ReferenceResult.NotFound -> inner
        }
    }

    private fun selectReference(
        text: String,
        pool: List<Model>,
        fuzzy: Boolean,
        usable: (String) -> Boolean,
    ): ReferenceResult? {
        val lowerText = text.lowercase()
        val raw = pool.filter { it.id.lowercase() == lowerText }
        val split = splitProviderPrefix(text, pool)
        var named: Model? = null
        if (split != null) {
            val (provider, modelText) = split
            val providerModels = pool.filter { it.provider == provider }
            val lowerModelText = modelText.lowercase()
            named = providerModels.firstOrNull { it.id.lowercase() == lowerModelText }
                ?: raw.firstOrNull { it.provider == provider }
                ?: if (fuzzy) bestFuzzyMatch(fuzzyMatches(modelText, providerModels)) else null
        }
        val namedModel = named
        val others = raw.filter { it !== namedModel }
        val candidates = if (namedModel != null) listOf(namedModel) + others else others
        if (candidates.isEmpty()) return null
        if (candidates.map { it.provider }.distinct().size == 1) {
            return ReferenceResult.Resolved(candidates[0])
        }
        if (namedModel != null && usable(namedModel.provider)) {
            return ReferenceResult.Resolved(namedModel)
        }
        val usableOthers = others.filter { usable(it.provider) }
        if (usableOthers.size == 1) return ReferenceResult.Resolved(usableOthers[0])
        if (usableOthers.isEmpty() && namedModel != null) return ReferenceResult.Resolved(namedModel)
        return ReferenceResult.Ambiguous(candidates, usableOthers.size)
    }

    private fun selectFuzzy(matches: List<Model>, usable: (String) -> Boolean): ReferenceResult? {
        if (matches.isEmpty()) return null
        val multipleProviders = matches.map { it.provider }.distinct().size > 1
        val usableMatches = if (multipleProviders) matches.filter { usable(it.provider) } else emptyList()
        val pool = if (usableMatches.isNotEmpty()) usableMatches else matches
        val best = bestFuzzyMatch(pool) ?: return null
        val tied = pool.filter { it.id == best.id }
        if (tied.map { it.provider }.distinct().size == 1) return ReferenceResult.Resolved(best)
        return ReferenceResult.Ambiguous(
            candidates = tied,
            usableCount = if (usableMatches.isNotEmpty()) tied.size else 0,
        )
    }

    private fun fuzzyMatches(text: String, models: List<Model>): List<Model> {
        val lowerText = text.lowercase()
        return models.filter { model ->
            model.id.lowercase().contains(lowerText) || model.name.lowercase().contains(lowerText)
        }
    }

    private fun bestFuzzyMatch(matches: List<Model>): Model? {
        if (matches.isEmpty()) return null
        val aliases = matches.filter { isAlias(it.id) }
        val pool = if (aliases.isNotEmpty()) aliases else matches
        return pool.maxWithOrNull(compareBy { it.id })
    }

    private fun isAlias(id: String): Boolean {
        if (id.endsWith("-latest")) return true
        return !Regex("-\\d{8}$").containsMatchIn(id)
    }

    private fun splitProviderPrefix(text: String, models: List<Model>): Pair<String, String>? {
        val slashIndex = text.indexOf('/')
        if (slashIndex == -1) return null
        val prefix = text.substring(0, slashIndex).trim().lowercase()
        val modelText = text.substring(slashIndex + 1).trim()
        if (prefix.isEmpty() || modelText.isEmpty()) return null
        val provider = models.firstOrNull { it.provider.lowercase() == prefix }?.provider ?: return null
        return provider to modelText
    }

    private fun splitThinkingSuffix(text: String): Pair<String, String>? {
        val colonIndex = text.lastIndexOf(':')
        if (colonIndex == -1) return null
        val suffix = text.substring(colonIndex + 1)
        if (suffix !in THINKING_LEVELS) return null
        return text.substring(0, colonIndex) to suffix
    }

    private fun ambiguousMessage(text: String, candidates: List<Model>, usableCount: Int): String {
        val matches = candidates.map { "${it.provider}/${it.id}" }.sorted().joinToString(", ")
        val authHint = when {
            usableCount == 0 -> "No matching provider is authenticated."
            else -> "More than one matching provider is authenticated."
        }
        return "Model \"$text\" is ambiguous across providers: $matches. $authHint Use provider/model to choose one."
    }
}

private fun globToRegex(pattern: String): Regex {
    val builder = StringBuilder("^")
    var index = 0
    while (index < pattern.length) {
        val char = pattern[index]
        when (char) {
            '*' -> {
                if (index + 1 < pattern.length && pattern[index + 1] == '*') {
                    builder.append(".*")
                    index += 2
                } else {
                    builder.append("[^/]*")
                    index += 1
                }
            }

            '?' -> {
                builder.append("[^/]")
                index += 1
            }

            '[' -> {
                val end = pattern.indexOf(']', index + 1)
                if (end == -1) {
                    builder.append("\\[")
                    index += 1
                } else {
                    val content = pattern.substring(index + 1, end)
                    if (content.isEmpty()) {
                        builder.append("\\[\\]")
                    } else {
                        val negated = content.startsWith("!") || content.startsWith("^")
                        val body = if (negated) content.substring(1) else content
                        builder.append('[')
                        if (negated) builder.append('^')
                        builder.append(body.replace("\\", "\\\\").replace("]", "\\]"))
                        builder.append(']')
                    }
                    index = end + 1
                }
            }

            else -> {
                if (char in "\\^$.|+(){}") builder.append('\\')
                builder.append(char)
                index += 1
            }
        }
    }
    builder.append('$')
    return Regex(builder.toString(), RegexOption.IGNORE_CASE)
}
