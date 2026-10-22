package dev.pi.ai

import dev.pi.ai.api.ApiProvider
import dev.pi.ai.auth.ApiKeyCredential
import dev.pi.ai.auth.AuthContext
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.EnvApiKeyAuth
import dev.pi.ai.auth.OAuthCredential
import dev.pi.ai.auth.StaticApiKeyAuth
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.builtinProviders
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {
    private fun chatModel(
        id: String = "m",
        api: String = "a",
        provider: String = "p",
        reasoning: Boolean = false,
        thinkingLevelMap: Map<String, String?>? = null,
        cost: ModelCost = ModelCost(0.0, 0.0, 0.0, 0.0),
        headers: Map<String, String>? = null,
    ): Model = Model(
        id = id,
        name = id,
        api = api,
        provider = provider,
        baseUrl = "https://example.test",
        input = listOf(ModelInputModality.Text),
        cost = cost,
        headers = headers,
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = 128000,
        maxTokens = 4096,
    )

    private fun imageModel(id: String = "image-model", provider: String = "p"): ImageModel = ImageModel(
        id = id,
        name = id,
        api = "image-api",
        provider = provider,
        baseUrl = "https://example.test",
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        output = listOf("image"),
    )

    private fun userContext(text: String = "hi"): Context =
        Context(messages = listOf(UserMessage(PlainTextContent(text), timestamp = 1L)))

    private fun provider(
        id: String = "p",
        name: String = id,
        headers: Map<String, String?>? = null,
        apiProviders: Map<String, () -> ApiProvider> = mapOf("a" to { capturing("a") }),
        models: List<Model> = listOf(chatModel()),
        allModels: List<AnyModel> = models,
        auth: AuthResolver = StaticApiKeyAuth("key"),
    ): Provider = Provider(
        id = id,
        name = name,
        headers = headers,
        apiProviders = apiProviders,
        models = models,
        allModels = allModels,
        auth = auth,
    )

    private fun capturing(api: String): CapturingApiProvider = CapturingApiProvider(api)

    private suspend fun withAuthEnv(env: Map<String, String>, block: suspend () -> Unit) {
        val previous = AuthEnvironment.context
        AuthEnvironment.context = AuthContext { name -> env[name] }
        try {
            block()
        } finally {
            AuthEnvironment.context = previous
        }
    }

    @Test
    fun builtinProvidersCoverEntireCatalog() {
        val providers = builtinProviders(NoopTransport)
        assertEquals(42, providers.size)
        assertEquals(ModelCatalog.providers.toSet(), providers.map { it.id }.toSet())
        for (provider in providers) {
            val catalogModels = ModelCatalog.findByProvider(provider.id)
            assertTrue(catalogModels.isNotEmpty(), "provider ${provider.id} has no catalog models")
            assertEquals(catalogModels.size, provider.allModels.size, "provider ${provider.id} model count")
            assertEquals(
                catalogModels.map { it.api }.distinct().toSet(),
                provider.apiProviders.keys,
                "provider ${provider.id} api providers",
            )
            assertEquals(
                catalogModels.filterIsInstance<Model>().map { it.id },
                provider.models.map { it.id },
                "provider ${provider.id} chat models",
            )
            assertTrue(provider.name.isNotBlank())
        }
    }

    @Test
    fun builtinProvidersExposeRealAndPlaceholderApis() {
        val providers = builtinProviders(NoopTransport).associateBy { it.id }
        val deepseek = assertNotNull(providers["deepseek"])
        assertEquals("openai-completions", assertNotNull(deepseek.apiProviders["openai-completions"]).invoke().api)

        val anthropic = assertNotNull(providers["anthropic"])
        assertEquals("https://api.anthropic.com", anthropic.baseUrl)
        val placeholder = assertNotNull(anthropic.apiProviders["anthropic-messages"]).invoke()
        assertEquals("anthropic-messages", placeholder.api)

        val openrouter = assertNotNull(providers["openrouter"])
        assertEquals(
            setOf("anthropic-messages", "openai-completions", "openrouter-images", "typesafe-system-one"),
            openrouter.apiProviders.keys,
        )

        val typesafe = assertNotNull(providers["typesafe"])
        assertTrue(typesafe.models.isEmpty())
        assertEquals(setOf("typesafe-system-one"), typesafe.apiProviders.keys)
        assertTrue(typesafe.allModels.isNotEmpty())
    }

    @Test
    fun keepsProviderRegistrationOrder() {
        val models = Models()
        models.setProvider(provider(id = "first"))
        models.setProvider(provider(id = "second"))
        models.setProvider(provider(id = "third"))
        assertEquals(listOf("first", "second", "third"), models.getProviders().map { it.id })
        models.setProvider(provider(id = "second", name = "renamed"))
        assertEquals(listOf("first", "second", "third"), models.getProviders().map { it.id })
        assertEquals("renamed", models.getProvider("second")?.name)
        models.deleteProvider("first")
        assertNull(models.getProvider("first"))
    }

    @Test
    fun createModelsRegistersEveryBuiltinProvider() {
        val models = createModels(NoopTransport)
        assertEquals(42, models.getProviders().size)
        assertEquals(41, models.getModels().map { it.provider }.distinct().size)
        assertEquals(ModelCatalog.models.filterIsInstance<Model>().size, models.getModels().size)
        assertEquals(ModelCatalog.modelsOfType(ModelType.Image).size, models.getModelsOfType(ModelType.Image).size)
        assertEquals(
            ModelCatalog.modelsOfType(ModelType.Classifier).size,
            models.getModelsOfType(ModelType.Classifier).size,
        )
        assertNotNull(models.getModel("anthropic", "claude-fable-5"))
        assertTrue(models.getModels("typesafe").isEmpty())
    }

    @Test
    fun looksUpModelsByIdTypeAndProvider() {
        val chat = chatModel(id = "chat-1")
        val image = imageModel()
        val models = Models()
        models.setProvider(
            provider(
                models = listOf(chat, chatModel(id = "chat-2")),
                allModels = listOf(chat, chatModel(id = "chat-2"), image),
            ),
        )
        assertEquals(2, models.getModels().size)
        assertEquals(2, models.getModels("p").size)
        assertTrue(models.getModels("missing").isEmpty())
        assertEquals("chat-1", models.getModel("p", "chat-1")?.id)
        assertNull(models.getModel("p", "image-model"))
        assertEquals(2, models.getModelsOfType(ModelType.Chat).size)
        assertEquals(listOf("image-model"), models.getModelsOfType(ModelType.Image).map { it.id })
        assertTrue(models.getModelsOfType(ModelType.Classifier).isEmpty())
        assertEquals(3, models.getAllModels().size)
    }

    @Test
    fun streamThrowsForUnknownProvider() = runTest {
        val models = Models()
        val error = assertFailsWith<IllegalStateException> {
            models.stream(chatModel(provider = "missing"), userContext())
        }
        assertEquals("Unknown provider: missing", error.message)
    }

    @Test
    fun streamThrowsForUnconfiguredProvider() = runTest {
        withAuthEnv(emptyMap()) {
            val models = Models()
            models.setProvider(provider(auth = EnvApiKeyAuth("Test", listOf("PI2KOTLIN_MISSING_KEY"))))
            val error = assertFailsWith<IllegalStateException> {
                models.stream(chatModel(), userContext())
            }
            assertEquals("Provider is not configured: p", error.message)
        }
    }

    @Test
    fun routesByModelApiAndAppliesAuth() = runTest {
        withAuthEnv(mapOf("PI2KOTLIN_AUTH_KEY" to "env-key")) {
            val first = capturing("api-a")
            val second = capturing("api-b")
            val modelFirst = chatModel(id = "m1", api = "api-a")
            val modelSecond = chatModel(id = "m2", api = "api-b")
            val models = Models()
            models.setProvider(
                provider(
                    headers = mapOf("X-Provider" to "provider", "X-Drop" to "drop"),
                    apiProviders = mapOf("api-a" to { first }, "api-b" to { second }),
                    models = listOf(modelFirst, modelSecond),
                    auth = EnvApiKeyAuth("Test", listOf("PI2KOTLIN_AUTH_KEY")),
                ),
            )

            val stream = models.stream(
                modelFirst,
                userContext(),
                StreamOptions(headers = mapOf("x-drop" to null, "X-Request" to "request")),
            )
            assertEquals("captured", (stream.result().content.single() as TextContent).text)
            assertEquals(1, first.calls.size)
            assertEquals(0, second.calls.size)
            val options = first.calls.single()
            assertEquals("env-key", options.apiKey)
            assertEquals(mapOf("X-Provider" to "provider", "X-Request" to "request"), options.headers)

            models.stream(modelSecond, userContext(), StreamOptions(apiKey = "explicit"))
            assertEquals(1, second.calls.size)
            assertEquals("explicit", second.calls.single().apiKey)
        }
    }

    @Test
    fun streamSimpleRoutesWithNestedOptions() = runTest {
        val api = capturing("a")
        val models = Models()
        models.setProvider(
            provider(
                apiProviders = mapOf("a" to { api }),
                auth = StaticApiKeyAuth("static-key"),
            ),
        )
        val stream = models.streamSimple(
            chatModel(),
            userContext(),
            SimpleStreamOptions(
                stream = StreamOptions(temperature = 0.5),
                reasoning = ThinkingLevel.High,
            ),
        )
        assertEquals("captured", (stream.result().content.single() as TextContent).text)
        val options = api.simpleCalls.single()
        assertEquals(0.5, options.stream.temperature)
        assertEquals("static-key", options.stream.apiKey)
        assertEquals(ThinkingLevel.High, options.reasoning)
    }

    @Test
    fun completeRoutesToFaux() = runTest {
        val faux = fauxProvider()
        faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("done"))))
        val models = Models()
        models.setProvider(faux.provider)
        val message = models.complete(faux.getModel()!!, userContext())
        assertEquals("done", (message.content.single() as TextContent).text)
        assertEquals(StopReason.Stop, message.stopReason)
    }

    @Test
    fun resolvesEnvAndOverridesInAuth() = runTest {
        withAuthEnv(mapOf("PI2KOTLIN_KEY" to "env-key")) {
            val resolver = EnvApiKeyAuth("Test key", listOf("MISSING_KEY", "PI2KOTLIN_KEY"))
            assertEquals("env-key", resolver.resolve()?.apiKey)
            assertEquals("PI2KOTLIN_KEY", resolver.resolve()?.source)
            assertEquals("override", resolver.resolve(AuthOverrides(apiKey = "override"))?.apiKey)
            assertEquals("override-env", resolver.resolve(AuthOverrides(env = mapOf("PI2KOTLIN_KEY" to "override-env")))?.apiKey)
            assertNull(EnvApiKeyAuth("Test key", listOf("MISSING_KEY")).resolve())
            assertEquals("inline", EnvApiKeyAuth("Test key", listOf("PI2KOTLIN_KEY"), inlineKey = "inline").resolve()?.apiKey)
            assertEquals("static", StaticApiKeyAuth("static").resolve().apiKey)
        }
    }

    @Test
    fun getAuthMergesModelHeaders() = runTest {
        val model = chatModel(headers = mapOf("X-Model" to "model"))
        val models = Models()
        models.setProvider(provider(models = listOf(model), auth = StaticApiKeyAuth("key")))
        val auth = assertNotNull(models.getAuth(model))
        assertEquals("key", auth.apiKey)
        assertEquals("model", auth.headers["X-Model"])
        assertNull(models.getAuth("missing"))
    }

    @Test
    fun calculateCostUsesBaseRatesBelowTier() {
        val model = chatModel(cost = ModelCost(1.0, 2.0, 0.5, 3.0))
        val usage = Usage(
            input = 1000,
            output = 500,
            cacheRead = 100,
            cacheWrite = 0,
            totalTokens = 1600,
            cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
        )
        val cost = calculateCost(model, usage)
        assertEquals(0.001, cost.input, 1e-12)
        assertEquals(0.001, cost.output, 1e-12)
        assertEquals(0.00005, cost.cacheRead, 1e-12)
        assertEquals(0.0, cost.cacheWrite, 1e-12)
        assertEquals(0.00205, cost.total, 1e-12)
    }

    @Test
    fun calculateCostAppliesTierAboveThreshold() {
        val model = chatModel(
            cost = ModelCost(
                input = 1.0,
                output = 2.0,
                cacheRead = 0.5,
                cacheWrite = 3.0,
                tiers = listOf(ModelCostTier(100, 2.0, 4.0, 1.0, 6.0)),
            ),
        )
        val usage = Usage(
            input = 1000,
            output = 500,
            cacheRead = 100,
            cacheWrite = 0,
            totalTokens = 1600,
            cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
        )
        val cost = calculateCost(model, usage)
        assertEquals(0.002, cost.input, 1e-12)
        assertEquals(0.002, cost.output, 1e-12)
        assertEquals(0.0001, cost.cacheRead, 1e-12)
        assertEquals(0.0041, cost.total, 1e-12)
    }

    @Test
    fun calculateCostBillsOneHourCacheWritesAtDoubleInput() {
        val model = chatModel(cost = ModelCost(1.0, 2.0, 0.5, 3.0))
        val usage = Usage(
            input = 0,
            output = 0,
            cacheRead = 0,
            cacheWrite = 1000,
            cacheWrite1h = 400,
            totalTokens = 1000,
            cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
        )
        val cost = calculateCost(model, usage)
        assertEquals((3.0 * 600 + 1.0 * 2 * 400) / 1_000_000.0, cost.cacheWrite, 1e-12)
        assertEquals(0.0026, cost.total, 1e-12)
    }

    @Test
    fun thinkingLevelsForNonReasoningModel() {
        val model = chatModel(reasoning = false)
        assertEquals(listOf(ModelThinkingLevel.Off), getSupportedThinkingLevels(model))
        assertEquals(ModelThinkingLevel.Off, clampThinkingLevel(model, ModelThinkingLevel.High))
    }

    @Test
    fun thinkingLevelsRespectThinkingLevelMap() {
        val model = chatModel(
            reasoning = true,
            thinkingLevelMap = mapOf(
                "off" to null,
                "minimal" to "minimal",
                "low" to "low",
                "medium" to "medium",
                "high" to "high",
                "xhigh" to null,
                "max" to "max",
            ),
        )
        assertEquals(
            listOf(
                ModelThinkingLevel.Minimal,
                ModelThinkingLevel.Low,
                ModelThinkingLevel.Medium,
                ModelThinkingLevel.High,
                ModelThinkingLevel.Max,
            ),
            getSupportedThinkingLevels(model),
        )
        assertEquals(ModelThinkingLevel.Minimal, clampThinkingLevel(model, ModelThinkingLevel.Off))
        assertEquals(ModelThinkingLevel.Max, clampThinkingLevel(model, ModelThinkingLevel.Xhigh))
    }

    @Test
    fun thinkingLevelsWithoutMapSupportOffToHigh() {
        val model = chatModel(reasoning = true)
        assertEquals(
            listOf(
                ModelThinkingLevel.Off,
                ModelThinkingLevel.Minimal,
                ModelThinkingLevel.Low,
                ModelThinkingLevel.Medium,
                ModelThinkingLevel.High,
            ),
            getSupportedThinkingLevels(model),
        )
        assertEquals(ModelThinkingLevel.High, clampThinkingLevel(model, ModelThinkingLevel.Max))
        assertEquals(ModelThinkingLevel.Off, clampThinkingLevel(model, ModelThinkingLevel.Off))
    }

    @Test
    fun credentialStoreRoundTripsApiKeys() {
        val directory = tempAuthDirectory()
        try {
            val store = CredentialStore(directory)
            store.writeApiKey("anthropic", "sk-test", mapOf("CLOUDFLARE_ACCOUNT_ID" to "account"))
            assertEquals("sk-test", store.readApiKey("anthropic"))
            assertEquals(mapOf("CLOUDFLARE_ACCOUNT_ID" to "account"), store.readEnv("anthropic"))
            assertEquals(listOf("anthropic"), store.keys())
            val raw = store.readRaw()
            assertEquals("api_key", raw["anthropic"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
            assertEquals(
                "account",
                raw["anthropic"]
                    ?.jsonObject
                    ?.get("env")
                    ?.jsonObject
                    ?.get("CLOUDFLARE_ACCOUNT_ID")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            )

            store.writeApiKey("openai", "sk-openai")
            assertEquals(2, store.list().size)
            store.delete("anthropic")
            assertNull(store.read("anthropic"))
            assertEquals(listOf("openai"), store.keys())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun credentialStoreReadsPiAuthJsonAndOAuthCredentials() {
        val directory = tempAuthDirectory()
        try {
            val store = CredentialStore(directory)
            FileSystem.SYSTEM.createDirectories(directory)
            FileSystem.SYSTEM.write(directory / "auth.json") {
                writeUtf8(
                    """{"openrouter":{"type":"api_key","key":"sk-or"},"anthropic":{"type":"oauth","refresh":"refresh-token","access":"access-token","expires":123456789}}""",
                )
            }
            assertEquals("sk-or", store.readApiKey("openrouter"))
            val oauth = assertIs<OAuthCredential>(assertNotNull(store.read("anthropic")))
            assertEquals("refresh-token", oauth.refresh)
            assertEquals("access-token", oauth.access)
            assertEquals(123456789L, oauth.expires)
            assertEquals(setOf("openrouter", "anthropic"), store.keys().toSet())

            val modified = store.modify("openrouter") { current -> (current as ApiKeyCredential).copy(key = "sk-or-2") }
            assertEquals("sk-or-2", (assertNotNull(modified) as ApiKeyCredential).key)
            assertEquals("sk-or-2", store.readApiKey("openrouter"))
            store.modify("missing") { null }
            assertNull(store.read("missing"))
            assertTrue(FileSystem.SYSTEM.exists(directory / "auth.json"))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory)
        }
    }

    @Test
    fun abortedSignalFailsFast() = runTest {
        val models = Models()
        models.setProvider(provider())
        val signal = RunSignal().apply { abort("test") }
        assertFailsWith<RequestAbortedException> {
            models.stream(chatModel(), userContext(), signal = signal)
        }
    }

    @Test
    fun placeholderApiThrowsNotImplemented() = runTest {
        val providers = builtinProviders(NoopTransport).associateBy { it.id }
        val anthropic = assertNotNull(providers["anthropic"])
        val models = Models()
        models.setProvider(anthropic)
        val model = anthropic.models.first()
        val error = assertFailsWith<NotImplementedError> {
            models.stream(model, userContext(), StreamOptions(apiKey = "test"))
        }
        assertTrue(error.message!!.contains("anthropic-messages"))
    }

    private fun tempAuthDirectory() =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi2kotlin-auth-${Random.nextLong().toString().replace("-", "")}"

    private object NoopTransport : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse = HttpResponse(200, emptyMap(), Buffer())
    }

    private class CapturingApiProvider(override val api: String) : ApiProvider {
        val calls = mutableListOf<StreamOptions>()
        val simpleCalls = mutableListOf<SimpleStreamOptions>()

        override suspend fun stream(
            model: Model,
            context: TranscriptContext,
            options: StreamOptions,
        ): AssistantMessageEventStream {
            calls += options
            return doneStream()
        }

        override suspend fun streamSimple(
            model: Model,
            context: TranscriptContext,
            options: SimpleStreamOptions,
        ): AssistantMessageEventStream {
            simpleCalls += options
            return doneStream()
        }

        private fun doneStream(): AssistantMessageEventStream {
            val stream = createAssistantMessageEventStream()
            val message = fauxAssistantMessage("captured")
            stream.push(DoneEvent(StopReason.Stop, message))
            stream.end(message)
            return stream
        }
    }
}
