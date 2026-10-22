package dev.pi.coding.models

import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.Provider
import dev.pi.ai.auth.AmbientAuth
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.FauxModelDefinition
import dev.pi.ai.providers.FauxProviderHandle
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.fauxProvider
import okio.FileSystem
import okio.Path.Companion.toPath

internal class FakeTransport : HttpTransport {
    var calls: Int = 0

    override suspend fun execute(request: HttpRequest): HttpResponse {
        calls += 1
        throw UnsupportedOperationException("network is not available in tests")
    }
}

internal fun testModel(
    provider: String,
    id: String,
    api: String = "openai-completions",
    name: String = id,
    baseUrl: String = "https://$provider.example/v1",
    reasoning: Boolean = false,
    input: List<ModelInputModality> = listOf(ModelInputModality.Text),
    contextWindow: Long = 128000,
    maxTokens: Long = 16384,
    cost: ModelCost = ModelCost(0.0, 0.0, 0.0, 0.0),
    thinkingLevelMap: Map<String, String?>? = null,
    headers: Map<String, String>? = null,
): Model = Model(
    id = id,
    name = name,
    api = api,
    provider = provider,
    baseUrl = baseUrl,
    input = input,
    cost = cost,
    headers = headers,
    reasoning = reasoning,
    thinkingLevelMap = thinkingLevelMap,
    contextWindow = contextWindow,
    maxTokens = maxTokens,
)

internal fun newCredentialStore(fs: FileSystem, directory: String = "/agent"): CredentialStore =
    CredentialStore(directory.toPath(), fs)

internal class FauxHarness(
    val handle: FauxProviderHandle,
    val provider: Provider,
    val model: Model,
)

internal fun fauxHarness(
    providerId: String = "faux-test",
    modelId: String = "faux-1",
    modelName: String? = null,
    auth: AuthResolver = AmbientAuth("faux"),
): FauxHarness {
    val options = FauxProviderOptions(
        provider = providerId,
        models = listOf(FauxModelDefinition(id = modelId, name = modelName)),
    )
    val handle = fauxProvider(options)
    val base = handle.provider
    val provider = Provider(
        id = providerId,
        name = providerId,
        apiProviders = base.apiProviders,
        models = base.models,
        allModels = base.allModels,
        auth = auth,
    )
    return FauxHarness(handle, provider, base.models.first())
}
