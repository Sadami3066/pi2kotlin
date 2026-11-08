package io.github.whywood.deepdeep.sdk.coding.models

import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.ModelCost
import io.github.whywood.deepdeep.sdk.ai.ModelInputModality
import io.github.whywood.deepdeep.sdk.ai.Provider
import io.github.whywood.deepdeep.sdk.ai.auth.AmbientAuth
import io.github.whywood.deepdeep.sdk.ai.auth.AuthResolver
import io.github.whywood.deepdeep.sdk.ai.auth.CredentialStore
import io.github.whywood.deepdeep.sdk.ai.http.HttpRequest
import io.github.whywood.deepdeep.sdk.ai.http.HttpResponse
import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport
import io.github.whywood.deepdeep.sdk.ai.providers.FauxModelDefinition
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderHandle
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderOptions
import io.github.whywood.deepdeep.sdk.ai.providers.fauxProvider
import okio.FileSystem
import okio.Path.Companion.toPath

internal class FakeTransport : HttpTransport {
    var calls: Int = 0

    override suspend fun execute(request: HttpRequest): HttpResponse {
        calls += 1
        throw UnsupportedOperationException("network is not available in tests")
    }
}

internal class RecordingTransport(
    private val status: Int = 200,
    private val body: String = "",
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        return HttpResponse(status, mapOf("content-type" to "text/event-stream"), okio.Buffer().writeUtf8(body))
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
    io.github.whywood.deepdeep.sdk.ai.auth.fileCredentialStore(directory.toPath(), fs)

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
