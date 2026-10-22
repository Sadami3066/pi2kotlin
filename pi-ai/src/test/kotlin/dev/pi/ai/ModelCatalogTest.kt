package dev.pi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelCatalogTest {
    @Test
    fun manifestSchemaIsSupported() {
        assertEquals(6, ModelCatalog.manifest.schemaVersion)
    }

    @Test
    fun loadsAllProvidersAndModels() {
        assertTrue(ModelCatalog.providers.size >= 40, "providers: ${ModelCatalog.providers.size}")
        assertTrue(ModelCatalog.models.size >= 1500, "models: ${ModelCatalog.models.size}")
        assertTrue(ModelCatalog.modelsOfType(ModelType.Image).isNotEmpty())
        assertTrue(ModelCatalog.modelsOfType(ModelType.Classifier).isNotEmpty())
    }

    @Test
    fun deepseekCatalogEntryMatchesUpstreamData() {
        val model = assertNotNull(ModelCatalog.findChat("deepseek", "deepseek-flash"))
        assertEquals("DeepSeek V4.1 Flash", model.name)
        assertEquals("openai-completions", model.api)
        assertEquals("https://api.deepseek.com", model.baseUrl)
        assertEquals(0.3, model.cost.input)
        assertEquals(1000000L, model.contextWindow)
        assertEquals(384000L, model.maxTokens)
        assertTrue(model.reasoning)
        assertTrue(model.supportsImageInput())
        assertEquals("low", model.thinkingLevelMap["low"])
        assertEquals(null, model.thinkingLevelMap["minimal"])
    }

    @Test
    fun anthropicUsesAnthropicMessagesApi() {
        val models = ModelCatalog.findByProvider("anthropic").filterIsInstance<Model>()
        assertTrue(models.isNotEmpty())
        assertTrue(models.all { it.api == "anthropic-messages" })
    }

    @Test
    fun modelTypeIsDerivedFromEntryKind() {
        val image = ModelCatalog.modelsOfType(ModelType.Image).first()
        assertIs<ImageModel>(image)
        val classifier = ModelCatalog.modelsOfType(ModelType.Classifier).first()
        assertIs<ClassifierModel>(classifier)
        assertEquals(ModelType.Chat, ModelCatalog.models.first { it.modelType == ModelType.Chat }.modelType)
    }
}
