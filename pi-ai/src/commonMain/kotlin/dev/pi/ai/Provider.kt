package dev.pi.ai

import dev.pi.ai.api.ApiProvider
import dev.pi.ai.api.ClassifierApiProvider
import dev.pi.ai.api.ImagesApiProvider
import dev.pi.ai.api.classifierErrorResult
import dev.pi.ai.api.imageErrorResult
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.builtinProviders
import dev.pi.ai.utils.normalizeContext
import kotlinx.coroutines.CancellationException

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
    val imagesApi: (() -> ImagesApiProvider)? = null,
    val classifierApis: Map<String, () -> ClassifierApiProvider> = emptyMap(),
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

    suspend fun generateImages(
        model: ImageModel,
        context: ImagesContext,
        options: ImagesOptions? = null,
    ): AssistantImages = try {
        val provider = requireProvider(model)
        val imagesApi = provider.imagesApi
            ?: throw IllegalStateException("Provider ${provider.id} does not support image generation")
        val resolved = getAuth(model, AuthOverrides(apiKey = options?.apiKey, env = options?.env))
            ?: throw IllegalStateException("Provider is not configured: ${model.provider}")
        imagesApi().generateImages(model, context, applyImagesOptions(resolved, options))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        imageErrorResult(model, error)
    }

    suspend fun classify(
        model: ClassifierModel,
        context: ClassifierContext,
        options: ClassifierOptions? = null,
    ): ClassifierResult = try {
        if (!context.images.isNullOrEmpty() && !model.input.contains(ModelInputModality.Image)) {
            throw IllegalStateException("Model ${model.provider}/${model.id} does not accept image input")
        }
        val provider = requireProvider(model)
        val classifierApi = provider.classifierApis[model.api]
            ?: throw IllegalStateException(
                if (provider.classifierApis.isEmpty()) {
                    "Provider ${provider.id} does not support classification"
                } else {
                    "Provider ${provider.id} has no classifier implementation for \"${model.api}\""
                },
            )
        val resolved = getAuth(model, AuthOverrides(apiKey = options?.apiKey, env = options?.env))
            ?: throw IllegalStateException("Provider is not configured: ${model.provider}")
        classifierApi().classify(model, context, applyClassifierOptions(resolved, options))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        classifierErrorResult(model, error)
    }

    private suspend fun resolveAuth(provider: Provider, options: StreamOptions?): ResolvedAuth =
        provider.auth.resolve(AuthOverrides(apiKey = options?.apiKey, env = options?.env))
            ?: throw IllegalStateException("Provider is not configured: ${provider.id}")

    private fun requireProvider(model: AnyModel): Provider =
        getProvider(model.provider) ?: throw IllegalStateException("Unknown provider: ${model.provider}")

    private fun createApiProvider(provider: Provider, model: Model): ApiProvider =
        provider.apiProviders[model.api]?.invoke()
            ?: throw IllegalStateException("Provider ${provider.id} has no API implementation for \"${model.api}\"")

    private fun applyImagesOptions(resolved: ResolvedAuth, options: ImagesOptions?): ImagesOptions {
        val source = options ?: ImagesOptions()
        return source.copy(
            apiKey = source.apiKey ?: resolved.apiKey,
            headers = mergeHeaders(resolved.headers, source.headers),
        )
    }

    private fun applyClassifierOptions(resolved: ResolvedAuth, options: ClassifierOptions?): ClassifierOptions {
        val source = options ?: ClassifierOptions()
        return source.copy(
            apiKey = source.apiKey ?: resolved.apiKey,
            headers = mergeHeaders(resolved.headers, source.headers),
        )
    }

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
