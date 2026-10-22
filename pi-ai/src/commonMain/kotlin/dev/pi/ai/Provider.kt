package dev.pi.ai

import dev.pi.ai.api.ApiProvider
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.builtinProviders
import dev.pi.ai.utils.normalizeContext

interface ApiFactory {
    fun create(): ApiProvider
}

class Provider(
    val id: String,
    val name: String,
    val baseUrl: String? = null,
    val headers: Map<String, String?>? = null,
    val apiProviders: Map<String, () -> ApiProvider>,
    val models: List<Model>,
    val allModels: List<AnyModel> = models,
    val auth: AuthResolver,
)

class Models(private val providers: MutableMap<String, Provider> = linkedMapOf()) {
    fun setProvider(provider: Provider) {
        providers[provider.id] = provider
    }

    fun deleteProvider(id: String) {
        providers.remove(id)
    }

    fun getProviders(): List<Provider> = providers.values.toList()

    fun getProvider(id: String): Provider? = providers[id]

    fun getModels(provider: String? = null): List<Model> {
        if (provider != null) return providers[provider]?.models.orEmpty()
        return providers.values.flatMap { it.models }
    }

    fun getAllModels(provider: String? = null): List<AnyModel> {
        if (provider != null) return providers[provider]?.allModels.orEmpty()
        return providers.values.flatMap { it.allModels }
    }

    fun getModel(provider: String, id: String): Model? =
        providers[provider]?.models?.firstOrNull { it.id == id }

    fun getModelsOfType(type: ModelType, provider: String? = null): List<AnyModel> =
        getAllModels(provider).filter { it.modelType == type }

    suspend fun getAuth(providerId: String, overrides: AuthOverrides? = null): ResolvedAuth? =
        getProvider(providerId)?.auth?.resolve(overrides)

    suspend fun getAuth(model: AnyModel, overrides: AuthOverrides? = null): ResolvedAuth? {
        val provider = getProvider(model.provider) ?: return null
        val resolved = provider.auth.resolve(overrides) ?: return null
        val modelHeaders = modelHeaders(model) ?: return resolved
        return resolved.copy(headers = mergeHeaders(resolved.headers, modelHeaders).orEmpty())
    }

    suspend fun stream(
        model: Model,
        context: Context,
        options: StreamOptions? = null,
        signal: RunSignal? = null,
    ): AssistantMessageEventStream {
        signal?.throwIfAborted()
        val provider = requireProvider(model)
        val transcript = normalizeContext(context)
        val resolved = resolveAuth(provider, options)
        val api = createApiProvider(provider, model)
        signal?.throwIfAborted()
        val applied = applyAuthOptions(provider, resolved, options)
        return api.stream(model, transcript, applied.copy(signal = signal ?: applied.signal))
    }

    suspend fun complete(
        model: Model,
        context: Context,
        options: StreamOptions? = null,
        signal: RunSignal? = null,
    ): AssistantMessage = stream(model, context, options, signal).result()

    suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
        signal: RunSignal? = null,
    ): AssistantMessageEventStream {
        signal?.throwIfAborted()
        val provider = requireProvider(model)
        val transcript = normalizeContext(context)
        val streamOptions = options?.stream ?: StreamOptions()
        val resolved = resolveAuth(provider, streamOptions)
        val api = createApiProvider(provider, model)
        signal?.throwIfAborted()
        val applied = applyAuthOptions(provider, resolved, streamOptions)
        return api.streamSimple(
            model,
            transcript,
            (options ?: SimpleStreamOptions()).copy(stream = applied.copy(signal = signal ?: applied.signal)),
        )
    }

    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
        signal: RunSignal? = null,
    ): AssistantMessage = streamSimple(model, context, options, signal).result()

    private suspend fun resolveAuth(provider: Provider, options: StreamOptions?): ResolvedAuth =
        provider.auth.resolve(AuthOverrides(apiKey = options?.apiKey, env = options?.env))
            ?: throw IllegalStateException("Provider is not configured: ${provider.id}")

    private fun requireProvider(model: AnyModel): Provider =
        getProvider(model.provider) ?: throw IllegalStateException("Unknown provider: ${model.provider}")

    private fun createApiProvider(provider: Provider, model: Model): ApiProvider =
        provider.apiProviders[model.api]?.invoke()
            ?: throw IllegalStateException("Provider ${provider.id} has no API implementation for \"${model.api}\"")

    private fun applyAuthOptions(
        provider: Provider,
        resolved: ResolvedAuth,
        options: StreamOptions?,
    ): StreamOptions {
        val source = options ?: StreamOptions()
        return source.copy(
            apiKey = source.apiKey ?: resolved.apiKey,
            headers = mergeHeaders(mergeHeaders(provider.headers, resolved.headers), source.headers),
        )
    }

    private fun modelHeaders(model: AnyModel): Map<String, String>? = when (model) {
        is Model -> model.headers
        is ImageModel -> model.headers
        is ClassifierModel -> model.headers
    }
}

fun createModels(transport: HttpTransport): Models {
    val models = Models()
    builtinProviders(transport).forEach { models.setProvider(it) }
    return models
}

private fun mergeHeaders(
    base: Map<String, String?>?,
    override: Map<String, String?>?,
): Map<String, String>? {
    if (base == null && override == null) return null
    val merged = linkedMapOf<String, String>()
    base?.forEach { (name, value) -> if (value != null) merged[name] = value }
    override?.forEach { (name, value) ->
        val lowerName = name.lowercase()
        merged.keys.firstOrNull { it.lowercase() == lowerName }?.let { merged.remove(it) }
        if (value != null) merged[name] = value
    }
    return merged.ifEmpty { null }
}
