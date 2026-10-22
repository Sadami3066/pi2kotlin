package dev.pi.coding.models

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.Context
import dev.pi.ai.Model
import dev.pi.ai.Models
import dev.pi.ai.Provider
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.auth.ApiKeyCredential
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.Credential
import dev.pi.ai.auth.CredentialStore

class ModelRuntime(
    val models: Models,
    val credentialStore: CredentialStore,
    val env: Map<String, String> = emptyMap(),
) {
    private var availableSnapshot: List<Model>? = null

    fun getModels(): List<Model> = models.getModels()

    fun getModel(provider: String, id: String): Model? = models.getModel(provider, id)

    fun getProvider(id: String): Provider? = models.getProvider(id)

    suspend fun getAvailableSnapshot(): List<Model> {
        availableSnapshot?.let { return it }
        val available = LinkedHashMap<String, Model>()
        for (provider in models.getProviders()) {
            if (!hasConfiguredAuth(provider.id)) continue
            for (model in provider.models) available["${model.provider}\u0000${model.id}"] = model
        }
        val snapshot = available.values.toList()
        availableSnapshot = snapshot
        return snapshot
    }

    suspend fun resolveModel(spec: String): Model? {
        val text = spec.trim()
        if (text.isEmpty()) return null
        val slashIndex = text.indexOf('/')
        if (slashIndex > 0) {
            val providerText = text.substring(0, slashIndex)
            val provider = models.getProvider(providerText)
                ?: models.getProviders().firstOrNull { it.id.equals(providerText, ignoreCase = true) }
            if (provider != null) {
                val modelId = text.substring(slashIndex + 1)
                return provider.models.firstOrNull { it.id == modelId }
            }
        }
        val matches = models.getModels().filter { it.id == text }
        if (matches.size == 1) return matches[0]
        if (matches.size > 1) {
            return matches.firstOrNull { hasConfiguredAuth(it.provider) } ?: matches.first()
        }
        return null
    }

    suspend fun hasConfiguredAuth(providerId: String): Boolean {
        val provider = models.getProvider(providerId) ?: return false
        if (credentialStore.read(providerId) != null) return true
        return provider.auth.resolve(AuthOverrides(env = env)) != null
    }

    suspend fun streamSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
        signal: RunSignal? = null,
    ): AssistantMessageEventStream {
        val provider = models.getProvider(model.provider)
            ?: throw IllegalStateException("Unknown provider: ${model.provider}")
        val credential = credentialStore.read(provider.id)
        val credentialEnv = (credential as? ApiKeyCredential)?.env
        val requestEnv = mergeRequestEnv(env, credentialEnv, options?.stream?.env)
        val requestHeaders = mergeRequestHeaders(model.headers, options?.stream?.headers)
        val streamOptions = (options?.stream ?: StreamOptions()).copy(
            apiKey = options?.stream?.apiKey,
            env = requestEnv.ifEmpty { null },
            headers = requestHeaders,
        )
        val applied = (options ?: SimpleStreamOptions()).copy(stream = streamOptions)
        val effectiveSignal = signal ?: streamOptions.signal
        return models.streamSimple(model, context, applied, effectiveSignal)
    }

    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions? = null,
        signal: RunSignal? = null,
    ): AssistantMessage = streamSimple(model, context, options, signal).result()

    fun registerProvider(provider: Provider) {
        models.setProvider(provider)
        availableSnapshot = null
    }

    suspend fun refresh() {
        availableSnapshot = null
        getAvailableSnapshot()
    }

    private fun mergeRequestEnv(
        runtimeEnv: Map<String, String>,
        credentialEnv: Map<String, String>?,
        requestEnv: Map<String, String>?,
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        runtimeEnv.forEach { (key, value) -> merged[key] = value }
        credentialEnv?.forEach { (key, value) -> merged[key] = value }
        requestEnv?.forEach { (key, value) -> merged[key] = value }
        return merged
    }

    private fun mergeRequestHeaders(
        modelHeaders: Map<String, String>?,
        requestHeaders: Map<String, String?>?,
    ): Map<String, String>? {
        if (modelHeaders.isNullOrEmpty() && requestHeaders.isNullOrEmpty()) return null
        val merged = LinkedHashMap<String, String>()
        modelHeaders?.forEach { (name, value) -> merged[name] = value }
        requestHeaders?.forEach { (name, value) ->
            merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { merged.remove(it) }
            if (value != null) merged[name] = value
        }
        return merged.ifEmpty { null }
    }
}
