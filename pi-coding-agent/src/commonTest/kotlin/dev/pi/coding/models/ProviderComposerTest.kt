package dev.pi.coding.models

import dev.pi.ai.Context
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.Provider
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.providers.builtinProviders
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderComposerTest {
    private val transport = FakeTransport()
    private val builtins: List<Provider> = builtinProviders(transport)

    private fun composer(
        config: String,
        env: Map<String, String> = emptyMap(),
        fs: FakeFileSystem = FakeFileSystem(),
    ): ProviderComposer {
        val providers = CustomModelsLoader().parse(config)
        return ProviderComposer(transport, newCredentialStore(fs), providers, env)
    }

    @Test
    fun composesNewCustomProvider() = runBlocking {
        val config = """
            {"providers":{"self-host":{
              "name":"Self Host",
              "baseUrl":"http://localhost:1234/v1",
              "api":"openai-completions",
              "apiKey":"sk-test",
              "models":[
                {"id":"model-1","name":"Model One","reasoning":true,"input":["text","image"],
                 "contextWindow":64000,"maxTokens":8192},
                {"id":"model-2"}
              ]
            }}}
        """.trimIndent()
        val models = composer(config).compose()
        val provider = assertNotNull(models.getProvider("self-host"))
        assertEquals("Self Host", provider.name)
        assertEquals("http://localhost:1234/v1", provider.baseUrl)
        assertEquals(2, provider.models.size)
        assertEquals(2, models.getModels("self-host").size)

        val first = assertNotNull(models.getModel("self-host", "model-1"))
        assertEquals("Model One", first.name)
        assertTrue(first.reasoning)
        assertEquals(listOf(ModelInputModality.Text, ModelInputModality.Image), first.input)
        assertEquals(64000L, first.contextWindow)
        assertEquals(8192L, first.maxTokens)
        assertEquals("openai-completions", first.api)
        assertEquals("self-host", first.provider)
        assertEquals("http://localhost:1234/v1", first.baseUrl)
        assertEquals(ModelCost(0.0, 0.0, 0.0, 0.0), first.cost)

        val second = assertNotNull(models.getModel("self-host", "model-2"))
        assertEquals("model-2", second.name)
        assertEquals(128000L, second.contextWindow)
        assertEquals(16384L, second.maxTokens)
        assertEquals(listOf(ModelInputModality.Text), second.input)

        val factory = assertNotNull(provider.apiProviders["openai-completions"])
        assertEquals("openai-completions", factory().api)

        val resolved = assertNotNull(provider.auth.resolve())
        assertEquals("sk-test", resolved.apiKey)
    }

    @Test
    fun proxiesBuiltinProviderBaseUrl() = runBlocking {
        val builtin = assertNotNull(builtins.firstOrNull { it.id == "deepseek" })
        val config = """{"providers":{"deepseek":{"baseUrl":"https://proxy.example.com/v1"}}}"""
        val models = composer(config, env = mapOf("DEEPSEEK_API_KEY" to "sk-env")).compose()
        val provider = assertNotNull(models.getProvider("deepseek"))
        assertEquals("https://proxy.example.com/v1", provider.baseUrl)
        assertEquals("DeepSeek", provider.name)
        assertEquals(builtin.models.size, provider.models.size)
        assertTrue(provider.models.all { it.baseUrl == "https://proxy.example.com/v1" })
        assertEquals(builtin.models.first().id, provider.models.first().id)

        val resolved = assertNotNull(
            provider.auth.resolve(AuthOverrides(env = mapOf("DEEPSEEK_API_KEY" to "sk-env"))),
        )
        assertEquals("sk-env", resolved.apiKey)
    }

    @Test
    fun replacesBuiltinModelById() = runBlocking {
        val builtin = assertNotNull(builtins.firstOrNull { it.id == "deepseek" })
        val builtinModel = builtin.models.first()
        val config = """
            {"providers":{"deepseek":{
              "models":[{"id":"${builtinModel.id}","name":"Custom Name","contextWindow":1111,"maxTokens":2222}]
            }}}
        """.trimIndent()
        val models = composer(config).compose()
        val provider = assertNotNull(models.getProvider("deepseek"))
        assertEquals(builtin.models.size, provider.models.size)
        val model = assertNotNull(models.getModel("deepseek", builtinModel.id))
        assertEquals("Custom Name", model.name)
        assertEquals(1111L, model.contextWindow)
        assertEquals(2222L, model.maxTokens)
        assertEquals(builtinModel.baseUrl, model.baseUrl)
        assertEquals(builtinModel.api, model.api)
        assertEquals("deepseek", model.provider)
    }

    @Test
    fun appliesModelOverrides() = runBlocking {
        val builtin = assertNotNull(builtins.firstOrNull { it.id == "deepseek" })
        val builtinModel = builtin.models.first()
        val config = """
            {"providers":{"deepseek":{
              "modelOverrides":{"${builtinModel.id}":{
                "name":"Overridden",
                "reasoning":true,
                "contextWindow":4242,
                "cost":{"input":5.0},
                "headers":{"X-Override":"yes"}
              }}
            }}}
        """.trimIndent()
        val models = composer(config).compose()
        val model = assertNotNull(models.getModel("deepseek", builtinModel.id))
        assertEquals("Overridden", model.name)
        assertTrue(model.reasoning)
        assertEquals(4242L, model.contextWindow)
        assertEquals(5.0, model.cost.input)
        assertEquals(builtinModel.cost.output, model.cost.output)
        assertEquals("yes", model.headers?.get("X-Override"))
        assertEquals(builtin.models.size, assertNotNull(models.getProvider("deepseek")).models.size)
    }

    @Test
    fun resolvesEnvironmentApiKeyAndHeaders() = runBlocking {
        val config = """
            {"providers":{"self-host":{
              "baseUrl":"http://localhost:1234/v1",
              "api":"openai-completions",
              "apiKey":"${'$'}SELF_HOST_KEY",
              "headers":{"X-Token":"${'$'}{HEADER_TOKEN}"},
              "models":[{"id":"model-1"}]
            }}}
        """.trimIndent()
        val fs = FakeFileSystem()
        val providers = CustomModelsLoader().parse(config)
        val composed = ProviderComposer(transport, newCredentialStore(fs), providers, emptyMap()).compose()
        val provider = assertNotNull(composed.getProvider("self-host"))
        assertEquals("sk-runtime", provider.auth.resolve(AuthOverrides(env = mapOf("SELF_HOST_KEY" to "sk-runtime")))?.apiKey)
        assertEquals(null, provider.auth.resolve())

        val composedWithEnv = ProviderComposer(
            transport,
            newCredentialStore(fs),
            providers,
            mapOf("SELF_HOST_KEY" to "sk-static", "HEADER_TOKEN" to "header-value"),
        ).compose()
        val staticProvider = assertNotNull(composedWithEnv.getProvider("self-host"))
        assertEquals("header-value", staticProvider.headers?.get("X-Token"))
        assertEquals("sk-static", staticProvider.auth.resolve()?.apiKey)
    }

    @Test
    fun authHeaderAddsAuthorizationHeader() = runBlocking {
        val config = """
            {"providers":{"self-host":{
              "baseUrl":"http://localhost:1234/v1",
              "api":"openai-completions",
              "apiKey":"sk-test",
              "authHeader":true,
              "models":[{"id":"model-1"}]
            }}}
        """.trimIndent()
        val models = composer(config).compose()
        val provider = assertNotNull(models.getProvider("self-host"))
        assertEquals("Bearer sk-test", provider.headers?.get("Authorization"))
    }

    @Test
    fun routesCustomAnthropicProviderToAnthropicApi() = runBlocking {
        val recording = RecordingTransport()
        val providers = CustomModelsLoader().parse(
            """
            {"providers":{"self-anthropic":{
              "baseUrl":"https://anthropic.example",
              "api":"anthropic-messages",
              "apiKey":"sk-ant-test",
              "models":[{"id":"claude-custom","maxTokens":2048}]
            }}}
            """.trimIndent(),
        )
        val models = ProviderComposer(recording, newCredentialStore(FakeFileSystem()), providers, emptyMap()).compose()
        val model = assertNotNull(models.getModel("self-anthropic", "claude-custom"))

        withTimeout(10_000) {
            models.streamSimple(
                model,
                Context(messages = emptyList()),
                SimpleStreamOptions(stream = StreamOptions(apiKey = "sk-ant-test")),
            ).result()
        }

        assertEquals(1, recording.requests.size)
        val request = recording.requests[0]
        assertEquals("https://anthropic.example/v1/messages", request.url)
        val body = PiJson.parseToJsonElement(request.body!!.decodeToString()) as JsonObject
        assertEquals("claude-custom", (body["model"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(2048), body["max_tokens"])
    }

    @Test
    fun routesCustomResponsesProviderToResponsesApi() = runBlocking {
        val recording = RecordingTransport()
        val providers = CustomModelsLoader().parse(
            """
            {"providers":{"self-responses":{
              "baseUrl":"https://responses.example",
              "api":"openai-responses",
              "apiKey":"sk-test",
              "models":[{"id":"gpt-custom","maxTokens":1024}]
            }}}
            """.trimIndent(),
        )
        val models = ProviderComposer(recording, newCredentialStore(FakeFileSystem()), providers, emptyMap()).compose()
        val model = assertNotNull(models.getModel("self-responses", "gpt-custom"))

        withTimeout(10_000) {
            models.streamSimple(
                model,
                Context(messages = emptyList()),
                SimpleStreamOptions(stream = StreamOptions(apiKey = "sk-test")),
            ).result()
        }

        assertEquals(1, recording.requests.size)
        assertEquals("https://responses.example/responses", recording.requests[0].url)
        val body = PiJson.parseToJsonElement(recording.requests[0].body!!.decodeToString()) as JsonObject
        assertEquals("gpt-custom", (body["model"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(true), body["stream"])
    }

    @Test
    fun customProviderSupportsModelLevelApiOverrides() = runBlocking {
        val providers = CustomModelsLoader().parse(
            """
            {"providers":{"mixed":{
              "baseUrl":"https://mixed.example",
              "api":"openai-completions",
              "apiKey":"sk-test",
              "models":[
                {"id":"plain"},
                {"id":"claude","api":"anthropic-messages"},
                {"id":"claude-full","api":"google-generative-ai"}
              ]
            }}}
            """.trimIndent(),
        )
        val models = ProviderComposer(FakeTransport(), newCredentialStore(FakeFileSystem()), providers, emptyMap()).compose()
        val provider = assertNotNull(models.getProvider("mixed"))
        assertEquals(
            setOf("openai-completions", "anthropic-messages", "google-generative-ai"),
            provider.apiProviders.keys,
        )
        for ((api, factory) in provider.apiProviders) {
            assertEquals(api, factory().api)
        }
    }

    @Test
    fun rejectsEmptyProviderConfig() {
        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking { composer("""{"providers":{"empty":{}}}""").compose() }
        }
        assertTrue(error.message!!.contains("must specify"))
    }

    @Test
    fun rejectsProviderWithoutAuthMethod() {
        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                composer("""{"providers":{"anonymous":{"baseUrl":"https://example.com/v1"}}}""").compose()
            }
        }
        assertTrue(error.message!!.contains("no authentication method configured"))
    }

    @Test
    fun rejectsModelWithoutApi() {
        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                composer(
                    """{"providers":{"custom":{"apiKey":"k","baseUrl":"https://example.com/v1","models":[{"id":"m"}]}}}""",
                ).compose()
            }
        }
        assertTrue(error.message!!.contains("no \"api\" specified"))
    }

    @Test
    fun rejectsModelWithoutBaseUrl() {
        val error = assertFailsWith<IllegalArgumentException> {
            runBlocking {
                composer(
                    """{"providers":{"custom":{"apiKey":"k","api":"openai-completions","models":[{"id":"m"}]}}}""",
                ).compose()
            }
        }
        assertTrue(error.message!!.contains("\"baseUrl\" is required"))
    }

    @Test
    fun keepsBuiltinProvidersWhenNoConfigApplies() = runBlocking {
        val models = ProviderComposer(transport, newCredentialStore(FakeFileSystem()), emptyList()).compose()
        assertEquals(builtins.size, models.getProviders().size)
        assertEquals(builtins.first().models.size, assertNotNull(models.getProvider(builtins.first().id)).models.size)
    }

    @Test
    fun mergesCompatForCustomModels() = runBlocking {
        val builtin = assertNotNull(builtins.firstOrNull { it.id == "deepseek" })
        val builtinModel = builtin.models.first()
        val config = """
            {"providers":{"deepseek":{
              "compat":{"supportsStrictMode":false},
              "modelOverrides":{"${builtinModel.id}":{"compat":{"maxTokensField":"max_tokens"}}}
            }}}
        """.trimIndent()
        val models = composer(config).compose()
        val model = assertNotNull(models.getModel("deepseek", builtinModel.id))
        val compat = assertNotNull(model.compat)
        assertEquals(JsonPrimitive(false), compat["supportsStrictMode"])
        assertEquals(JsonPrimitive("max_tokens"), compat["maxTokensField"])
    }
}
