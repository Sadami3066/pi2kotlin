package dev.pi.ai.api

import dev.pi.ai.AssistantImages
import dev.pi.ai.ClassifierBoolAnswer
import dev.pi.ai.ClassifierContext
import dev.pi.ai.ClassifierModel
import dev.pi.ai.ClassifierOptions
import dev.pi.ai.ClassifierResult
import dev.pi.ai.ClassifierStopReason
import dev.pi.ai.ImageContent
import dev.pi.ai.ImageModel
import dev.pi.ai.ImagesContext
import dev.pi.ai.ImagesOptions
import dev.pi.ai.ImagesStopReason
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.Provider
import dev.pi.ai.Models
import dev.pi.ai.TextContent
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.auth.StaticApiKeyAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsImagesClassifierTest {
    private class NullAuth : AuthResolver {
        override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? = null
    }

    private class CapturingImagesApi(var result: AssistantImages? = null) : ImagesApiProvider {
        val models = mutableListOf<ImageModel>()
        val options = mutableListOf<ImagesOptions>()
        val contexts = mutableListOf<ImagesContext>()

        override suspend fun generateImages(
            model: ImageModel,
            context: ImagesContext,
            options: ImagesOptions,
        ): AssistantImages {
            models.add(model)
            contexts.add(context)
            this.options.add(options)
            return result ?: AssistantImages(
                api = model.api,
                provider = model.provider,
                model = model.id,
                output = listOf(TextContent("generated")),
                stopReason = ImagesStopReason.Stop,
                timestamp = 1,
            )
        }
    }

    private class CapturingClassifierApi(var result: ClassifierResult? = null) : ClassifierApiProvider {
        val models = mutableListOf<ClassifierModel>()
        val options = mutableListOf<ClassifierOptions>()

        override suspend fun classify(
            model: ClassifierModel,
            context: ClassifierContext,
            options: ClassifierOptions,
        ): ClassifierResult {
            models.add(model)
            this.options.add(options)
            return result ?: ClassifierResult(
                api = model.api,
                provider = model.provider,
                model = model.id,
                answers = linkedMapOf("approved" to ClassifierBoolAnswer(probability = 0.5)),
                stopReason = ClassifierStopReason.Stop,
                timestamp = 1,
            )
        }
    }

    private fun imageModel(
        provider: String = "p",
        headers: Map<String, String>? = null,
    ): ImageModel = ImageModel(
        id = "image-model",
        name = "Image Model",
        api = "images-api",
        provider = provider,
        baseUrl = "https://example.test",
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        headers = headers,
        output = listOf("image"),
    )

    private fun classifierModel(
        provider: String = "p",
        api: String = "classifier-api",
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        headers: Map<String, String>? = null,
    ): ClassifierModel = ClassifierModel(
        id = "classif",
        name = "Classifier",
        api = api,
        provider = provider,
        baseUrl = "https://example.test",
        input = input,
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        headers = headers,
        contextWindow = 1000,
    )

    private fun provider(
        auth: AuthResolver = StaticApiKeyAuth("secret"),
        imagesApi: ImagesApiProvider? = null,
        classifierApis: Map<String, () -> ClassifierApiProvider> = emptyMap(),
    ): Provider = Provider(
        id = "p",
        name = "p",
        apiProviders = emptyMap(),
        models = emptyList(),
        auth = auth,
        imagesApi = imagesApi?.let { api -> { api } },
        classifierApis = classifierApis,
    )

    private fun context(): ClassifierContext = ClassifierContext(
        state = buildJsonObject { },
        questions = linkedMapOf(),
    )

    @Test
    fun generateImagesRoutesToProviderAndAppliesAuth() = runBlocking {
        val api = CapturingImagesApi()
        val models = Models()
        models.setProvider(provider(imagesApi = api))
        val model = imageModel(headers = mapOf("X-Model" to "model"))
        val context = ImagesContext(listOf(TextContent("draw")))
        val result = models.generateImages(
            model,
            context,
            ImagesOptions(apiKey = "explicit", headers = mapOf("x-opt" to "opt")),
        )

        assertEquals(ImagesStopReason.Stop, result.stopReason)
        assertEquals("generated", (result.output.single() as TextContent).text)
        assertEquals(1, api.models.size)
        assertEquals("explicit", api.options.single().apiKey)
        assertEquals(mapOf("X-Model" to "model", "x-opt" to "opt"), api.options.single().headers)
        assertEquals("draw", (api.contexts.single().input.single() as TextContent).text)
    }

    @Test
    fun generateImagesUsesResolvedAuthWhenOptionsAreMissing() = runBlocking {
        val api = CapturingImagesApi()
        val models = Models()
        models.setProvider(provider(imagesApi = api))
        models.generateImages(imageModel(), ImagesContext(emptyList()))
        assertEquals("secret", api.options.single().apiKey)
    }

    @Test
    fun generateImagesReturnsErrorResultsInsteadOfRejecting() = runBlocking {
        val models = Models()
        val unknown = models.generateImages(imageModel(provider = "missing"), ImagesContext(emptyList()))
        assertEquals(ImagesStopReason.Error, unknown.stopReason)
        assertEquals("Unknown provider: missing", unknown.errorMessage)

        models.setProvider(provider())
        val unsupported = models.generateImages(imageModel(), ImagesContext(emptyList()))
        assertEquals(ImagesStopReason.Error, unsupported.stopReason)
        assertEquals("Provider p does not support image generation", unsupported.errorMessage)

        val unconfigured = Models()
        unconfigured.setProvider(provider(auth = NullAuth(), imagesApi = CapturingImagesApi()))
        val noAuth = unconfigured.generateImages(imageModel(), ImagesContext(emptyList()))
        assertEquals(ImagesStopReason.Error, noAuth.stopReason)
        assertEquals("Provider is not configured: p", noAuth.errorMessage)
    }

    @Test
    fun generateImagesPassesThroughProviderErrorResults() = runBlocking {
        val errorResult = AssistantImages(
            api = "images-api",
            provider = "p",
            model = "image-model",
            output = emptyList(),
            stopReason = ImagesStopReason.Error,
            errorMessage = "provider failure",
            timestamp = 2,
        )
        val api = CapturingImagesApi(result = errorResult)
        val models = Models()
        models.setProvider(provider(imagesApi = api))
        val result = models.generateImages(imageModel(), ImagesContext(emptyList()))
        assertEquals(ImagesStopReason.Error, result.stopReason)
        assertEquals("provider failure", result.errorMessage)
    }

    @Test
    fun classifyRoutesByModelApiAndAppliesAuth() = runBlocking {
        val api = CapturingClassifierApi()
        val models = Models()
        models.setProvider(provider(classifierApis = mapOf("classifier-api" to { api })))
        val model = classifierModel(headers = mapOf("X-Model" to "model"))
        val result = models.classify(
            model,
            context(),
            ClassifierOptions(apiKey = "explicit", headers = mapOf("x-opt" to "opt", "x-model" to null)),
        )

        assertEquals(ClassifierStopReason.Stop, result.stopReason)
        assertEquals(ClassifierBoolAnswer(probability = 0.5), result.answers["approved"])
        assertEquals(1, api.models.size)
        assertEquals("explicit", api.options.single().apiKey)
        assertEquals(mapOf("x-opt" to "opt"), api.options.single().headers)
    }

    @Test
    fun classifyRejectsImageInputForTextOnlyModelsBeforeDispatch() = runBlocking {
        val api = CapturingClassifierApi()
        val models = Models()
        models.setProvider(provider(classifierApis = mapOf("classifier-api" to { api })))
        val context = context().copy(images = listOf(ImageContent(data = "aW1hZ2U=", mimeType = "image/png")))
        val result = models.classify(classifierModel(), context)
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertEquals("Model p/classif does not accept image input", result.errorMessage)
        assertTrue(api.models.isEmpty())

        val imageModel = classifierModel(input = listOf(ModelInputModality.Text, ModelInputModality.Image))
        val imageResult = models.classify(imageModel, context)
        assertEquals(ClassifierStopReason.Stop, imageResult.stopReason)
        assertEquals(1, api.models.size)
    }

    @Test
    fun classifyReturnsErrorResultsInsteadOfRejecting() = runBlocking {
        val models = Models()
        val unknown = models.classify(classifierModel(provider = "missing"), context())
        assertEquals(ClassifierStopReason.Error, unknown.stopReason)
        assertEquals("Unknown provider: missing", unknown.errorMessage)

        models.setProvider(provider())
        val unsupported = models.classify(classifierModel(), context())
        assertEquals(ClassifierStopReason.Error, unsupported.stopReason)
        assertEquals("Provider p does not support classification", unsupported.errorMessage)

        val missingApi = Models()
        missingApi.setProvider(provider(classifierApis = mapOf("other-api" to { CapturingClassifierApi() })))
        val noImplementation = missingApi.classify(classifierModel(), context())
        assertEquals(ClassifierStopReason.Error, noImplementation.stopReason)
        assertEquals(
            "Provider p has no classifier implementation for \"classifier-api\"",
            noImplementation.errorMessage,
        )

        val unconfigured = Models()
        unconfigured.setProvider(
            provider(auth = NullAuth(), classifierApis = mapOf("classifier-api" to { CapturingClassifierApi() })),
        )
        val noAuth = unconfigured.classify(classifierModel(), context())
        assertEquals(ClassifierStopReason.Error, noAuth.stopReason)
        assertEquals("Provider is not configured: p", noAuth.errorMessage)
    }

    @Test
    fun classifierModelHeadersAreAppliedWhenOptionsOmitThem() = runBlocking {
        val api = CapturingClassifierApi()
        val models = Models()
        models.setProvider(provider(classifierApis = mapOf("classifier-api" to { api })))
        models.classify(classifierModel(headers = mapOf("X-Model" to "model")), context())
        assertEquals(mapOf("X-Model" to "model"), api.options.single().headers)
        assertEquals("secret", api.options.single().apiKey)
        assertNull(api.options.single().headers?.get("x-missing"))
    }
}
