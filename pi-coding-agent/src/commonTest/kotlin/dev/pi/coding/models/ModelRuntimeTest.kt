package dev.pi.coding.models

import dev.pi.ai.Context
import dev.pi.ai.Models
import dev.pi.ai.PlainTextContent
import dev.pi.ai.Provider
import dev.pi.ai.UserMessage
import dev.pi.ai.auth.AmbientAuth
import dev.pi.ai.auth.EnvApiKeyAuth
import dev.pi.ai.auth.ProviderAuthResolver
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.fauxAssistantMessage
import kotlinx.coroutines.runBlocking
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRuntimeTest {
    private fun context(): Context = Context(messages = listOf(UserMessage(PlainTextContent("hello"), timestamp = 1)))

    private fun modelsOf(vararg providers: Provider): Models {
        val models = Models()
        providers.forEach { models.setProvider(it) }
        return models
    }

    private val gatedAuth = EnvApiKeyAuth("Gated key", listOf("GATED_KEY"))

    @Test
    fun exposesModelsAndProviders() {
        val harness = fauxHarness(providerId = "alpha", modelId = "m1")
        val runtime = ModelRuntime(modelsOf(harness.provider), newCredentialStore(FakeFileSystem()))
        assertEquals(1, runtime.getModels().size)
        assertEquals("m1", runtime.getModels().first().id)
        assertEquals("m1", runtime.getModel("alpha", "m1")?.id)
        assertNull(runtime.getModel("alpha", "missing"))
        assertEquals("alpha", runtime.getProvider("alpha")?.id)
        assertNull(runtime.getProvider("missing"))
    }

    @Test
    fun hasConfiguredAuthUsesRuntimeEnvAndCredentials() = runBlocking {
        val envHarness = fauxHarness(providerId = "env-provider", modelId = "m1", auth = gatedAuth)
        val otherHarness = fauxHarness(
            providerId = "other-provider",
            modelId = "m2",
            auth = EnvApiKeyAuth("Other key", listOf("OTHER_KEY")),
        )
        val fs = FakeFileSystem()
        val store = newCredentialStore(fs)
        val runtime = ModelRuntime(
            modelsOf(envHarness.provider, otherHarness.provider),
            store,
            mapOf("GATED_KEY" to "sk-env"),
        )

        assertTrue(runtime.hasConfiguredAuth("env-provider"))
        assertTrue(!runtime.hasConfiguredAuth("other-provider"))
        assertTrue(!runtime.hasConfiguredAuth("missing"))

        store.writeApiKey("other-provider", "sk-stored")
        assertTrue(runtime.hasConfiguredAuth("other-provider"))
    }

    @Test
    fun getAvailableSnapshotFiltersUnauthenticatedProviders() = runBlocking {
        val configured = fauxHarness(providerId = "configured", modelId = "m1", auth = AmbientAuth("faux"))
        val unconfigured = fauxHarness(providerId = "unconfigured", modelId = "m2", auth = gatedAuth)
        val runtime = ModelRuntime(modelsOf(configured.provider, unconfigured.provider), newCredentialStore(FakeFileSystem()))

        val snapshot = runtime.getAvailableSnapshot()
        assertEquals(1, snapshot.size)
        assertEquals("configured", snapshot.first().provider)

        val refreshed = ModelRuntime(
            modelsOf(configured.provider, unconfigured.provider),
            newCredentialStore(FakeFileSystem()),
            mapOf("GATED_KEY" to "sk-env"),
        ).getAvailableSnapshot()
        assertEquals(2, refreshed.size)
    }

    @Test
    fun resolveModelSupportsProviderPrefixAndBareId() = runBlocking {
        val harness = fauxHarness(providerId = "alpha", modelId = "m1")
        val runtime = ModelRuntime(modelsOf(harness.provider), newCredentialStore(FakeFileSystem()))
        assertEquals("m1", runtime.resolveModel("alpha/m1")?.id)
        assertEquals("m1", runtime.resolveModel("ALPHA/m1")?.id)
        assertEquals("m1", runtime.resolveModel("m1")?.id)
        assertNull(runtime.resolveModel("alpha/missing"))
        assertNull(runtime.resolveModel("missing"))
        assertNull(runtime.resolveModel(""))
    }

    @Test
    fun streamSimpleUsesRuntimeEnvironmentAuth() = runBlocking {
        val harness = fauxHarness(providerId = "gated", modelId = "m1", auth = gatedAuth)
        harness.handle.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("hello from faux"))))
        val runtime = ModelRuntime(modelsOf(harness.provider), newCredentialStore(FakeFileSystem()), mapOf("GATED_KEY" to "sk-env"))

        val stream = runtime.streamSimple(harness.model, context())
        val message = stream.result()
        assertEquals("hello from faux", (message.content.first() as dev.pi.ai.TextContent).text)
    }

    @Test
    fun streamSimpleUsesStoredCredential() = runBlocking {
        val fs = FakeFileSystem()
        val store = newCredentialStore(fs)
        store.writeApiKey("gated", "sk-stored")
        val auth = dev.pi.ai.auth.ProviderAuthResolver("gated", store, listOf("GATED_KEY"))
        val harness = fauxHarness(providerId = "gated", modelId = "m1", auth = auth)
        harness.handle.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("stored auth works"))))
        val runtime = ModelRuntime(modelsOf(harness.provider), store)

        val message = runtime.completeSimple(harness.model, context())
        assertEquals("stored auth works", (message.content.first() as dev.pi.ai.TextContent).text)
    }

    @Test
    fun streamSimpleFailsWithoutConfiguredAuth() = runBlocking {
        val harness = fauxHarness(providerId = "gated", modelId = "m1", auth = gatedAuth)
        val runtime = ModelRuntime(modelsOf(harness.provider), newCredentialStore(FakeFileSystem()))
        val error = assertFailsWith<IllegalStateException> {
            runtime.streamSimple(harness.model, context())
        }
        assertTrue(error.message!!.contains("Provider is not configured: gated"))
    }

    @Test
    fun completesSimpleRequest() = runBlocking {
        val harness = fauxHarness(providerId = "alpha", modelId = "m1")
        harness.handle.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("done"))))
        val runtime = ModelRuntime(modelsOf(harness.provider), newCredentialStore(FakeFileSystem()))
        val message = runtime.completeSimple(harness.model, context())
        assertEquals("done", (message.content.first() as dev.pi.ai.TextContent).text)
        assertEquals(1, harness.handle.state.callCount)
    }

    @Test
    fun registerProviderInvalidatesSnapshot() = runBlocking {
        val harness = fauxHarness(providerId = "alpha", modelId = "m1", auth = AmbientAuth("faux"))
        val runtime = ModelRuntime(modelsOf(), newCredentialStore(FakeFileSystem()))
        assertTrue(runtime.getAvailableSnapshot().isEmpty())
        runtime.registerProvider(harness.provider)
        assertEquals(1, runtime.getAvailableSnapshot().size)
        assertEquals("m1", runtime.getAvailableSnapshot().single().id)
        assertNotNull(runtime.getProvider("alpha"))
        Unit
    }

    @Test
    fun refreshRecomputesAvailability() = runBlocking {
        val harness = fauxHarness(providerId = "gated", modelId = "m1", auth = gatedAuth)
        val fs = FakeFileSystem()
        val store = newCredentialStore(fs)
        val runtime = ModelRuntime(modelsOf(harness.provider), store)
        assertTrue(runtime.getAvailableSnapshot().isEmpty())
        store.writeApiKey("gated", "sk-stored")
        runtime.refresh()
        assertEquals(1, runtime.getAvailableSnapshot().size)
    }
}
