package dev.pi.coding.models

import dev.pi.ai.ModelInputModality
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomModelsTest {
    private val fullConfig = """
        {
          "${'$'}schema": "https://pi.dev/schemas/models.schema.json",
          "providers": {
            "self-host": {
              "name": "Self Host",
              "baseUrl": "http://localhost:1234/v1",
              "api": "openai-completions",
              "apiKey": "sk-test",
              "headers": { "X-Foo": "bar" },
              "compat": { "supportsStrictMode": true },
              "authHeader": true,
              "models": [
                {
                  "id": "model-1",
                  "name": "Model One",
                  "api": "openai-completions",
                  "baseUrl": "http://other.example/v1",
                  "reasoning": true,
                  "thinkingLevelMap": { "minimal": null, "high": "HIGH" },
                  "input": ["text", "image"],
                  "inputLimits": {
                    "maxRequestBytes": 1000,
                    "images": {
                      "resize": { "maxWidth": 512, "maxHeight": 256, "maxBytes": 100, "jpegQuality": 70 },
                      "maxPerMessage": 2,
                      "maxPerRequest": 3
                    }
                  },
                  "cost": {
                    "input": 1.0, "output": 2.0, "cacheRead": 0.1, "cacheWrite": 0.2,
                    "tiers": [
                      { "inputTokensAbove": 1000, "input": 0.5, "output": 0.6, "cacheRead": 0.7, "cacheWrite": 0.8 }
                    ]
                  },
                  "promptCache": { "short": 300, "long": 3600 },
                  "contextWindow": 64000,
                  "maxTokens": 8192,
                  "samplingParams": { "temperature": 0.5 },
                  "samplingParamsByThinkingLevel": { "high": { "temperature": 0.9 } },
                  "headers": { "X-Model": "m" },
                  "compat": { "thinkingFormat": "openai" }
                }
              ],
              "modelOverrides": {
                "other-model": {
                  "name": "Other Model",
                  "reasoning": true,
                  "cost": { "output": 9.0 },
                  "contextWindow": 32000,
                  "maxTokens": 4096,
                  "headers": { "X-Override": "o" }
                }
              }
            },
            "proxy-only": {
              "baseUrl": "https://proxy.example.com/v1"
            }
          }
        }
    """.trimIndent()

    @Test
    fun parsesFullProviderConfig() {
        val providers = CustomModelsLoader().parse(fullConfig)
        assertEquals(2, providers.size)

        val provider = providers.first { it.id == "self-host" }
        assertEquals("Self Host", provider.name)
        assertEquals("http://localhost:1234/v1", provider.baseUrl)
        assertEquals("openai-completions", provider.api)
        assertEquals("sk-test", provider.apiKey)
        assertEquals("bar", provider.headers?.get("X-Foo"))
        assertTrue(provider.authHeader)
        assertEquals(JsonPrimitive(true), provider.compat?.get("supportsStrictMode"))
        assertEquals(1, provider.models.size)

        val model = provider.models.first()
        assertEquals("model-1", model.id)
        assertEquals("Model One", model.name)
        assertEquals("http://other.example/v1", model.baseUrl)
        assertEquals(true, model.reasoning)
        assertEquals(null, model.thinkingLevelMap?.get("minimal"))
        assertEquals("HIGH", model.thinkingLevelMap?.get("high"))
        assertTrue(model.thinkingLevelMap?.containsKey("minimal") == true)
        assertEquals(listOf(ModelInputModality.Text, ModelInputModality.Image), model.input)
        assertEquals(1000L, model.inputLimits?.maxRequestBytes)
        assertEquals(512L, model.inputLimits?.images?.resize?.maxWidth)
        assertEquals(256L, model.inputLimits?.images?.resize?.maxHeight)
        assertEquals(100L, model.inputLimits?.images?.resize?.maxBytes)
        assertEquals(70L, model.inputLimits?.images?.resize?.jpegQuality)
        assertEquals(2L, model.inputLimits?.images?.maxPerMessage)
        assertEquals(3L, model.inputLimits?.images?.maxPerRequest)
        val cost = assertNotNull(model.cost)
        assertEquals(1.0, cost.input)
        assertEquals(2.0, cost.output)
        assertEquals(0.1, cost.cacheRead)
        assertEquals(0.2, cost.cacheWrite)
        val tier = assertNotNull(cost.tiers).first()
        assertEquals(1000L, tier.inputTokensAbove)
        assertEquals(0.8, tier.cacheWrite)
        assertEquals(300.0, model.promptCache?.get("short"))
        assertEquals(3600.0, model.promptCache?.get("long"))
        assertEquals(64000L, model.contextWindow)
        assertEquals(8192L, model.maxTokens)
        assertEquals(JsonPrimitive(0.5), model.samplingParams?.get("temperature"))
        assertEquals(JsonPrimitive(0.9), model.samplingParamsByThinkingLevel?.get("high")?.get("temperature"))
        assertEquals("m", model.headers?.get("X-Model"))
        assertEquals(JsonPrimitive("openai"), model.compat?.get("thinkingFormat"))

        val override = assertNotNull(provider.modelOverrides["other-model"])
        assertEquals("Other Model", override.name)
        assertEquals(true, override.reasoning)
        assertEquals(9.0, override.cost?.output)
        assertNull(override.cost?.input)
        assertEquals(32000L, override.contextWindow)
        assertEquals("o", override.headers?.get("X-Override"))

        val proxyOnly = providers.first { it.id == "proxy-only" }
        assertNull(proxyOnly.api)
        assertTrue(proxyOnly.models.isEmpty())
    }

    @Test
    fun parsesJsoncWithBomCommentsAndTrailingCommas() {
        val text = "\uFEFF{\n" +
            "  // providers\n" +
            "  \"providers\": {\n" +
            "    \"local\": {\n" +
            "      \"baseUrl\": \"http://localhost:8080/v1\",\n" +
            "      \"api\": \"openai-completions\",\n" +
            "      \"models\": [ { \"id\": \"m1\", }, ],\n" +
            "    },\n" +
            "  },\n" +
            "}\n"
        val providers = CustomModelsLoader().parse(text)
        assertEquals(1, providers.size)
        assertEquals("m1", providers[0].models[0].id)
    }

    @Test
    fun companionParseMatchesInstanceParse() {
        val providers = CustomModelsLoader.parse(fullConfig)
        assertEquals(2, providers.size)
    }

    @Test
    fun returnsEmptyWhenProvidersMissing() {
        assertTrue(CustomModelsLoader().parse("""{"${'$'}schema":"x"}""").isEmpty())
        assertTrue(CustomModelsLoader().parse("{}").isEmpty())
        assertTrue(CustomModelsLoader().parse("  ").isEmpty())
    }

    @Test
    fun rejectsMalformedJson() {
        val error = assertFailsWith<IllegalArgumentException> { CustomModelsLoader().parse("{") }
        assertTrue(error.message!!.contains("Failed to parse models.json"))
        val rootError = assertFailsWith<IllegalArgumentException> { CustomModelsLoader().parse("[]") }
        assertTrue(rootError.message!!.contains("root must be a JSON object"))
        val providersError = assertFailsWith<IllegalArgumentException> {
            CustomModelsLoader().parse("""{"providers":[]}""")
        }
        assertTrue(providersError.message!!.contains("providers must be an object"))
    }

    @Test
    fun rejectsInvalidModelEntries() {
        val missingId = assertFailsWith<IllegalArgumentException> {
            CustomModelsLoader().parse(
                """{"providers":{"p":{"baseUrl":"http://x","api":"openai-completions","models":[{"name":"n"}]}}}""",
            )
        }
        assertTrue(missingId.message!!.contains("models[0].id"))

        val badInput = assertFailsWith<IllegalArgumentException> {
            CustomModelsLoader().parse(
                """{"providers":{"p":{"baseUrl":"http://x","api":"openai-completions","models":[{"id":"m","input":["audio"]}]}}}""",
            )
        }
        assertTrue(badInput.message!!.contains("must be \"text\" or \"image\""))

        val badCost = assertFailsWith<IllegalArgumentException> {
            CustomModelsLoader().parse(
                """{"providers":{"p":{"baseUrl":"http://x","api":"openai-completions","models":[{"id":"m","cost":{"input":1.0}}]}}}""",
            )
        }
        assertTrue(badCost.message!!.contains("cost.output is required"))

        val badContextWindow = assertFailsWith<IllegalArgumentException> {
            CustomModelsLoader().parse(
                """{"providers":{"p":{"baseUrl":"http://x","api":"openai-completions","models":[{"id":"m","contextWindow":-1}]}}}""",
            )
        }
        assertTrue(badContextWindow.message!!.contains("contextWindow must be greater than 0"))
    }

    @Test
    fun parsesRealModelsJsonWhenAvailable() {
        val home = System.getenv("HOME") ?: return
        val path = "$home/.pi/agent/models.json".toPath()
        val fs = FileSystem.SYSTEM
        if (!fs.exists(path)) return
        val text = fs.read(path) { readUtf8() }
        val providers = CustomModelsLoader().parse(text)
        assertTrue(providers.isNotEmpty())
        for (provider in providers) {
            assertTrue(provider.baseUrl != null || provider.models.isNotEmpty())
        }
    }

    @Test
    fun ignoresUnknownProviderFields() {
        val providers = CustomModelsLoader().parse(
            """{"providers":{"p":{"baseUrl":"http://x","api":"openai-completions","futureField":123,"models":[{"id":"m","unknown":true}]}}}""",
        )
        assertEquals(1, providers.size)
        val model = providers[0].models[0]
        assertTrue(model.toString().isNotEmpty())
        val compat = JsonObject(emptyMap())
        assertTrue(compat.isEmpty())
    }
}
