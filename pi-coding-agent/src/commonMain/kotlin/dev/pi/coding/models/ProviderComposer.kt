package dev.pi.coding.models

import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelImageInputLimits
import dev.pi.ai.ModelImageResizeOptions
import dev.pi.ai.ModelInputLimits
import dev.pi.ai.ModelInputModality
import dev.pi.ai.Models
import dev.pi.ai.Provider
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.TranscriptContext
import dev.pi.ai.api.ApiProvider
import dev.pi.ai.api.openAICompletionsApi
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.EnvApiKeyAuth
import dev.pi.ai.auth.StaticApiKeyAuth
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.builtinProviders
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private class UnimplementedApiProvider(override val api: String) : ApiProvider {
    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = throw NotImplementedError("API provider not implemented: $api")

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream = throw NotImplementedError("API provider not implemented: $api")
}

class ProviderComposer(
    val transport: HttpTransport,
    val credentialStore: CredentialStore,
    val customProviders: List<CustomProviderConfig>,
    val env: Map<String, String> = emptyMap(),
) {
    suspend fun compose(): Models {
        val models = Models()
        val builtins = builtinProviders(transport)
        val builtinById = LinkedHashMap<String, Provider>()
        for (provider in builtins) {
            builtinById[provider.id] = provider
            models.setProvider(provider)
        }
        for (config in customProviders) {
            models.setProvider(composeProvider(config, builtinById[config.id]))
        }
        return models
    }

    private fun composeProvider(config: CustomProviderConfig, base: Provider?): Provider {
        val models = composeModels(config, base)
        val auth = composeAuth(config, base)
            ?: throw IllegalArgumentException("Provider ${config.id}: no authentication method configured.")
        var headers = mergeStringHeaders(base?.headers, ConfigValues.resolveHeaders(config.headers, env))
        if (config.authHeader) {
            val apiKey = resolveStatically(config.apiKey)
            if (apiKey != null) {
                headers = mergeStringHeaders(headers, mapOf("Authorization" to "Bearer $apiKey"))
            }
        }
        val apiProviders = LinkedHashMap<String, () -> ApiProvider>()
        for (api in models.map { it.api }.distinct()) {
            apiProviders[api] = base?.apiProviders?.get(api) ?: defaultApiFactory(api)
        }
        return Provider(
            id = config.id,
            name = config.name ?: base?.name ?: config.id,
            baseUrl = config.baseUrl ?: base?.baseUrl,
            headers = headers,
            apiProviders = apiProviders,
            models = models,
            allModels = models,
            auth = auth,
        )
    }

    private fun defaultApiFactory(api: String): () -> ApiProvider = when (api) {
        "openai-completions" -> ({ openAICompletionsApi(transport) })
        else -> ({ UnimplementedApiProvider(api) })
    }

    private fun composeAuth(config: CustomProviderConfig, base: Provider?): AuthResolver? {
        val rawKey = config.apiKey ?: return base?.auth
        val resolved = resolveStatically(rawKey)
        if (resolved != null) return StaticApiKeyAuth(resolved)
        val envNames = ConfigValues.envVarNames(rawKey)
        if (envNames.isNotEmpty()) return EnvApiKeyAuth("API key", envNames)
        return base?.auth
    }

    private fun resolveStatically(rawKey: String?): String? {
        if (rawKey == null || ConfigValues.isCommand(rawKey)) return null
        return ConfigValues.resolve(rawKey, env)
    }

    private fun composeModels(config: CustomProviderConfig, base: Provider?): List<Model> {
        if (config.oauth != null && config.baseUrl == null) {
            throw IllegalArgumentException("Provider ${config.id}: \"baseUrl\" is required when \"oauth\" is set.")
        }
        val hasOverrides = config.modelOverrides.isNotEmpty()
        if (
            config.models.isEmpty() && config.baseUrl == null && config.headers == null &&
            config.compat == null && !hasOverrides && config.apiKey == null && config.oauth == null &&
            !config.authHeader
        ) {
            throw IllegalArgumentException(
                "Provider ${config.id}: must specify \"baseUrl\", \"headers\", \"compat\", " +
                    "\"modelOverrides\", or \"models\".",
            )
        }
        val baseModels = (base?.allModels.orEmpty()).filterIsInstance<Model>()
        val models = baseModels.map { model ->
            val baseUrl = if (config.oauth == "radius") model.baseUrl else (config.baseUrl ?: model.baseUrl)
            model.copy(baseUrl = baseUrl, compat = mergeCompat(model.compat, config.compat))
        }.toMutableList()
        for (definition in config.models) {
            val existingIndex = models.indexOfFirst { it.id == definition.id }
            val defaults = findModelDefaults(models, definition.id, definition.api ?: config.api)
            val model = modelFromJson(config.id, definition, config, defaults)
            if (existingIndex >= 0) {
                models[existingIndex] = model
            } else {
                models.add(model)
            }
        }
        return models.map { model ->
            val override = config.modelOverrides[model.id] ?: return@map model
            applyModelOverride(model, override)
        }
    }

    private fun findModelDefaults(models: List<Model>, modelId: String, api: String?): Model? =
        models.firstOrNull { it.id == modelId }
            ?: api?.let { expected -> models.firstOrNull { it.api == expected } }
            ?: models.firstOrNull { it.api == "openai-completions" }
            ?: models.firstOrNull()

    private fun modelFromJson(
        providerId: String,
        definition: CustomModelConfig,
        config: CustomProviderConfig,
        defaults: Model?,
    ): Model {
        val api = definition.api ?: config.api ?: defaults?.api
            ?: throw IllegalArgumentException(
                "Provider $providerId, model ${definition.id}: no \"api\" specified. " +
                    "Set at provider or model level.",
            )
        val baseUrl = definition.baseUrl ?: config.baseUrl ?: defaults?.baseUrl
            ?: throw IllegalArgumentException(
                "Provider $providerId: \"baseUrl\" is required when defining custom models.",
            )
        if (definition.contextWindow != null && definition.contextWindow <= 0) {
            throw IllegalArgumentException("Provider $providerId, model ${definition.id}: invalid contextWindow")
        }
        if (definition.maxTokens != null && definition.maxTokens <= 0) {
            throw IllegalArgumentException("Provider $providerId, model ${definition.id}: invalid maxTokens")
        }
        return Model(
            id = definition.id,
            name = definition.name ?: definition.id,
            api = api,
            provider = providerId,
            baseUrl = baseUrl,
            input = definition.input ?: listOf(ModelInputModality.Text),
            inputLimits = definition.inputLimits,
            cost = definition.cost?.toModelCost() ?: ModelCost(0.0, 0.0, 0.0, 0.0),
            headers = ConfigValues.resolveHeaders(definition.headers, env),
            reasoning = definition.reasoning ?: false,
            thinkingLevelMap = definition.thinkingLevelMap,
            promptCache = definition.promptCache,
            contextWindow = definition.contextWindow ?: 128000,
            maxTokens = definition.maxTokens ?: 16384,
            samplingParams = definition.samplingParams,
            samplingParamsByThinkingLevel = definition.samplingParamsByThinkingLevel,
            compat = mergeCompat(config.compat, definition.compat),
        )
    }

    private fun applyModelOverride(model: Model, override: CustomModelOverride): Model {
        val overrideHeaders = ConfigValues.resolveHeaders(override.headers, env)
        return model.copy(
            name = override.name ?: model.name,
            reasoning = override.reasoning ?: model.reasoning,
            thinkingLevelMap = override.thinkingLevelMap?.let { mergedStringMap(model.thinkingLevelMap, it) }
                ?: model.thinkingLevelMap,
            input = override.input ?: model.input,
            inputLimits = mergeInputLimits(model.inputLimits, override.inputLimits),
            cost = override.cost?.let { cost -> mergeCost(model.cost, cost) } ?: model.cost,
            promptCache = override.promptCache?.let { mergedDoubleMap(model.promptCache, it) } ?: model.promptCache,
            contextWindow = override.contextWindow ?: model.contextWindow,
            maxTokens = override.maxTokens ?: model.maxTokens,
            samplingParams = override.samplingParams?.let { mergeJsonObjects(model.samplingParams, it) }
                ?: model.samplingParams,
            samplingParamsByThinkingLevel = mergeSamplingParamsByThinkingLevel(
                model.samplingParamsByThinkingLevel,
                override.samplingParamsByThinkingLevel,
            ),
            headers = mergeStringHeaders(model.headers, overrideHeaders),
            compat = mergeCompat(model.compat, override.compat),
        )
    }

    private fun mergeCost(base: ModelCost, override: CustomModelOverrideCost): ModelCost = ModelCost(
        input = override.input ?: base.input,
        output = override.output ?: base.output,
        cacheRead = override.cacheRead ?: base.cacheRead,
        cacheWrite = override.cacheWrite ?: base.cacheWrite,
        tiers = override.tiers ?: base.tiers,
    )

    private fun mergeInputLimits(base: ModelInputLimits?, override: ModelInputLimits?): ModelInputLimits? {
        if (override == null) return base
        val baseImages = base?.images
        val overrideImages = override.images
        val images: ModelImageInputLimits? = if (overrideImages != null) {
            val baseResize = baseImages?.resize
            val overrideResize = overrideImages.resize
            val resize: ModelImageResizeOptions? = if (overrideResize != null) {
                ModelImageResizeOptions(
                    maxWidth = overrideResize.maxWidth ?: baseResize?.maxWidth,
                    maxHeight = overrideResize.maxHeight ?: baseResize?.maxHeight,
                    maxBytes = overrideResize.maxBytes ?: baseResize?.maxBytes,
                    jpegQuality = overrideResize.jpegQuality ?: baseResize?.jpegQuality,
                )
            } else {
                baseResize
            }
            ModelImageInputLimits(
                resize = resize,
                maxPerMessage = overrideImages.maxPerMessage ?: baseImages?.maxPerMessage,
                maxPerRequest = overrideImages.maxPerRequest ?: baseImages?.maxPerRequest,
            )
        } else {
            baseImages
        }
        return ModelInputLimits(
            maxRequestBytes = override.maxRequestBytes ?: base?.maxRequestBytes,
            images = images,
        )
    }
}

internal fun mergeCompat(base: JsonObject?, override: JsonObject?): JsonObject? {
    if (override == null) return base
    if (base == null) return override
    val merged = LinkedHashMap<String, JsonElement>()
    base.forEach { (key, value) -> merged[key] = value }
    for ((key, value) in override) {
        val baseValue = merged[key]
        merged[key] = if (key in NESTED_COMPAT_KEYS && baseValue is JsonObject && value is JsonObject) {
            JsonObject(baseValue + value)
        } else {
            value
        }
    }
    return JsonObject(merged)
}

private val NESTED_COMPAT_KEYS = setOf(
    "openRouterRouting",
    "vercelGatewayRouting",
    "chatTemplateKwargs",
    "chatTemplateArgs",
)

internal fun mergeJsonObjects(base: JsonObject?, override: JsonObject?): JsonObject? {
    if (override == null) return base
    if (base == null) return override
    val merged = LinkedHashMap<String, JsonElement>()
    base.forEach { (key, value) -> merged[key] = value }
    override.forEach { (key, value) -> merged[key] = value }
    return JsonObject(merged)
}

private fun mergeSamplingParamsByThinkingLevel(
    base: Map<String, JsonObject>?,
    override: Map<String, JsonObject>?,
): Map<String, JsonObject>? {
    if (override == null) return base
    val merged = LinkedHashMap<String, JsonObject>()
    base?.forEach { (level, params) -> merged[level] = params }
    for ((level, params) in override) {
        merged[level] = mergeJsonObjects(merged[level], params) ?: params
    }
    return merged
}

private fun mergedStringMap(base: Map<String, String?>?, override: Map<String, String?>): Map<String, String?> {
    val merged = LinkedHashMap<String, String?>()
    base?.forEach { (key, value) -> merged[key] = value }
    override.forEach { (key, value) -> merged[key] = value }
    return merged
}

private fun mergedDoubleMap(base: Map<String, Double>?, override: Map<String, Double>): Map<String, Double> {
    val merged = LinkedHashMap<String, Double>()
    base?.forEach { (key, value) -> merged[key] = value }
    override.forEach { (key, value) -> merged[key] = value }
    return merged
}

internal fun mergeStringHeaders(
    base: Map<String, String?>?,
    override: Map<String, String?>?,
): Map<String, String>? {
    if (base.isNullOrEmpty() && override.isNullOrEmpty()) return null
    val merged = LinkedHashMap<String, String>()
    base?.forEach { (name, value) -> if (value != null) merged[name] = value }
    override?.forEach { (name, value) ->
        merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { merged.remove(it) }
        if (value != null) merged[name] = value
    }
    return merged.ifEmpty { null }
}
