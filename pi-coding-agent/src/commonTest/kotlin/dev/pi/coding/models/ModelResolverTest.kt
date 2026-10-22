package dev.pi.coding.models

import dev.pi.ai.Models
import dev.pi.ai.Provider
import dev.pi.ai.auth.AmbientAuth
import dev.pi.ai.auth.EnvApiKeyAuth
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelResolverTest {
    private fun runtimeOf(
        providers: List<Provider>,
        env: Map<String, String> = emptyMap(),
    ): ModelRuntime {
        val models = Models()
        providers.forEach { models.setProvider(it) }
        return ModelRuntime(models, newCredentialStore(FakeFileSystem()), env)
    }

    private fun resolver(
        runtime: ModelRuntime,
        defaultProvider: String? = null,
        defaultModel: String? = null,
        enabledModels: List<String>? = null,
    ): ModelResolver = ModelResolver(runtime, defaultProvider, defaultModel, enabledModels)

    @Test
    fun resolvesExactProviderAndId() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val beta = fauxHarness(providerId = "beta", modelId = "m2")
        val model = resolver(runtimeOf(listOf(alpha.provider, beta.provider))).resolve("alpha/m1")
        assertEquals("alpha", model.model.provider)
        assertEquals("m1", model.model.id)
        assertNull(model.thinkingLevel)
    }

    @Test
    fun resolvesBareUniqueId() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val beta = fauxHarness(providerId = "beta", modelId = "m2")
        val model = resolver(runtimeOf(listOf(alpha.provider, beta.provider))).resolve("m2")
        assertEquals("beta", model.model.provider)
    }

    @Test
    fun resolvesThinkingSuffix() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val modelResolver = resolver(runtimeOf(listOf(alpha.provider)))
        assertEquals("high", modelResolver.resolve("alpha/m1:high").thinkingLevel)
        assertEquals("minimal", modelResolver.resolve("m1:minimal").thinkingLevel)
        assertEquals("off", modelResolver.resolve("m1:off").thinkingLevel)
    }

    @Test
    fun keepsColonInsideExactModelId() = runBlocking {
        val harness = fauxHarness(providerId = "openrouter", modelId = "qwen/qwen3-coder:exacto")
        val resolved = resolver(runtimeOf(listOf(harness.provider))).resolve("openrouter/qwen/qwen3-coder:exacto")
        assertEquals("qwen/qwen3-coder:exacto", resolved.model.id)
        assertNull(resolved.thinkingLevel)
    }

    @Test
    fun rejectsInvalidThinkingSuffix() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val error = assertFailsWith<IllegalArgumentException> {
            resolver(runtimeOf(listOf(alpha.provider))).resolve("alpha/m1:bogus")
        }
        assertTrue(error.message!!.contains("not found"))
    }

    @Test
    fun rejectsUnknownModel() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val error = assertFailsWith<IllegalArgumentException> {
            resolver(runtimeOf(listOf(alpha.provider))).resolve("alpha/unknown")
        }
        assertTrue(error.message!!.contains("not found"))
    }

    @Test
    fun rejectsAmbiguousBareId() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "shared", auth = AmbientAuth("faux"))
        val beta = fauxHarness(providerId = "beta", modelId = "shared", auth = AmbientAuth("faux"))
        val error = assertFailsWith<IllegalArgumentException> {
            resolver(runtimeOf(listOf(alpha.provider, beta.provider))).resolve("shared")
        }
        assertTrue(error.message!!.contains("ambiguous"))
        assertTrue(error.message!!.contains("alpha/shared"))
        assertTrue(error.message!!.contains("beta/shared"))
    }

    @Test
    fun prefersAuthenticatedProviderForAmbiguousId() = runBlocking {
        val unauthenticated = fauxHarness(
            providerId = "alpha",
            modelId = "shared",
            auth = EnvApiKeyAuth("Alpha key", listOf("ALPHA_KEY")),
        )
        val authenticated = fauxHarness(providerId = "beta", modelId = "shared", auth = AmbientAuth("faux"))
        val resolved = resolver(runtimeOf(listOf(unauthenticated.provider, authenticated.provider)))
            .resolve("shared")
        assertEquals("beta", resolved.model.provider)
    }

    @Test
    fun fuzzyMatchesAliasOverDatedVersion() = runBlocking {
        val harness = fauxHarness(providerId = "anthropic", modelId = "claude-sonnet-4-5")
        val dated = fauxHarness(providerId = "anthropic", modelId = "claude-sonnet-4-5-20250929")
        val models = Models()
        models.setProvider(
            Provider(
                id = "anthropic",
                name = "Anthropic",
                apiProviders = harness.provider.apiProviders,
                models = listOf(harness.model, dated.model),
                allModels = listOf(harness.model, dated.model),
                auth = AmbientAuth("faux"),
            ),
        )
        val resolved = resolver(ModelRuntime(models, newCredentialStore(FakeFileSystem()))).resolve("sonnet")
        assertEquals("claude-sonnet-4-5", resolved.model.id)
    }

    @Test
    fun fuzzyMatchesModelName() = runBlocking {
        val harness = fauxHarness(providerId = "alpha", modelId = "m1", modelName = "Fancy Model")
        val resolved = resolver(runtimeOf(listOf(harness.provider))).resolve("fancy")
        assertEquals("m1", resolved.model.id)
    }

    @Test
    fun enabledModelsGlobSelectsDefaultWithThinkingLevel() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val beta = fauxHarness(providerId = "beta", modelId = "m2")
        val runtime = runtimeOf(listOf(alpha.provider, beta.provider))
        val resolved = assertNotNull(
            resolver(runtime, enabledModels = listOf("alpha/*:high")).resolveDefault(),
        )
        assertEquals("alpha", resolved.model.provider)
        assertEquals("high", resolved.thinkingLevel)
    }

    @Test
    fun enabledModelsExactPatternSelectsDefault() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val beta = fauxHarness(providerId = "beta", modelId = "m2")
        val runtime = runtimeOf(listOf(alpha.provider, beta.provider))
        val resolved = assertNotNull(resolver(runtime, enabledModels = listOf("beta/m2")).resolveDefault())
        assertEquals("beta", resolved.model.provider)
    }

    @Test
    fun defaultModelWinsWhenConfiguredAndAuthenticated() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val beta = fauxHarness(providerId = "beta", modelId = "m2")
        val runtime = runtimeOf(listOf(alpha.provider, beta.provider))
        val resolved = assertNotNull(
            resolver(runtime, defaultProvider = "beta", defaultModel = "m2").resolveDefault(),
        )
        assertEquals("beta", resolved.model.provider)
        assertEquals("m2", resolved.model.id)
    }

    @Test
    fun defaultFallsBackToFirstAvailableModel() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val resolved = assertNotNull(resolver(runtimeOf(listOf(alpha.provider))).resolveDefault())
        assertEquals("m1", resolved.model.id)
        assertNull(resolved.thinkingLevel)
    }

    @Test
    fun emptySpecResolvesDefaultModel() = runBlocking {
        val alpha = fauxHarness(providerId = "alpha", modelId = "m1")
        val modelResolver = resolver(
            runtimeOf(listOf(alpha.provider)),
            defaultProvider = "alpha",
            defaultModel = "m1",
        )
        assertEquals("m1", modelResolver.resolve("").model.id)
    }

    @Test
    fun resolveDefaultReturnsNullWithoutAvailableModels() = runBlocking {
        val gated = fauxHarness(
            providerId = "gated",
            modelId = "m1",
            auth = EnvApiKeyAuth("Gated key", listOf("GATED_KEY")),
        )
        assertNull(resolver(runtimeOf(listOf(gated.provider))).resolveDefault())
    }

    @Test
    fun enabledModelsSkipsUnauthenticatedProviders() = runBlocking {
        val gated = fauxHarness(
            providerId = "gated",
            modelId = "m1",
            auth = EnvApiKeyAuth("Gated key", listOf("GATED_KEY")),
        )
        val open = fauxHarness(providerId = "open", modelId = "m2", auth = AmbientAuth("faux"))
        val runtime = runtimeOf(listOf(gated.provider, open.provider))
        val resolved = assertNotNull(resolver(runtime, enabledModels = listOf("gated/*")).resolveDefault())
        assertEquals("open", resolved.model.provider)
    }
}
