package dev.pi.ai.api

import dev.pi.ai.ClassifierBoolAnswer
import dev.pi.ai.ClassifierBoolCriteria
import dev.pi.ai.ClassifierBoolQuestion
import dev.pi.ai.ClassifierChoiceAnswer
import dev.pi.ai.ClassifierChoiceQuestion
import dev.pi.ai.ClassifierContext
import dev.pi.ai.ClassifierModel
import dev.pi.ai.ClassifierOptions
import dev.pi.ai.ClassifierResult
import dev.pi.ai.ClassifierScoreAnswer
import dev.pi.ai.ClassifierScoreQuestion
import dev.pi.ai.ClassifierStopReason
import dev.pi.ai.ImageContent
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TypesafeSystemOneTest {
    private class MockTransport : HttpTransport {
        var lastRequest: HttpRequest? = null
        var requestCount: Int = 0

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
            requestCount += 1
            val connection = URL(request.url).openConnection() as HttpURLConnection
            connection.requestMethod = request.method
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val headers = mutableMapOf<String, String>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null && values != null) headers[name] = values.joinToString(", ")
            }
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))
            }
            val bytes = stream.use { it.readBytes() }
            return HttpResponse(status, headers, Buffer().write(bytes))
        }
    }

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try {
            block(server)
        } finally {
            server.shutdown()
        }
    }

    private fun classifierModel(
        baseUrl: String,
        id: String = "jev-latest",
        api: String = "typesafe-system-one",
        provider: String = "typesafe",
        input: List<ModelInputModality> = listOf(ModelInputModality.Text),
        headers: Map<String, String>? = null,
        cost: ModelCost = ModelCost(input = 0.042, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    ): ClassifierModel = ClassifierModel(
        id = id,
        name = "Jev",
        api = api,
        provider = provider,
        baseUrl = baseUrl,
        input = input,
        cost = cost,
        headers = headers,
        contextWindow = 64_000,
    )

    private fun context(): ClassifierContext = ClassifierContext(
        state = buildJsonObject { put("text", JsonPrimitive("The deployment succeeded, thank you.")) },
        questions = linkedMapOf(
            "category" to ClassifierChoiceQuestion(
                instructions = "Classify the message",
                criteria = linkedMapOf("success" to "Successful", "failure" to "Failed"),
            ),
            "satisfaction" to ClassifierScoreQuestion(
                instructions = "Score satisfaction",
                criteria = listOf("low", "neutral", "high"),
            ),
            "approved" to ClassifierBoolQuestion(
                instructions = "Does the user approve?",
                criteria = ClassifierBoolCriteria(trueText = "Approval", falseText = "No approval"),
            ),
        ),
    )

    private fun systemOneAnswers(): String = """
        {
          "category": {
            "type": "choice",
            "choice": "success",
            "probabilities": { "success": 0.9, "failure": 0.1 },
            "confidence": 0.8
          },
          "satisfaction": { "type": "score", "score": 2, "confidence": 0.7 },
          "approved": { "type": "noul", "noul": 0.95 }
        }
    """.trimIndent()

    private fun response(answers: String = systemOneAnswers(), usage: String? = null): String {
        val usageField = if (usage == null) "" else ",\"usage\":$usage"
        return """{"answers":$answers$usageField}"""
    }

    private fun run(
        transport: MockTransport,
        model: ClassifierModel,
        context: ClassifierContext = context(),
        options: ClassifierOptions = ClassifierOptions(apiKey = "secret"),
    ): ClassifierResult = runBlocking {
        withTimeout(15_000) {
            TypesafeSystemOneApi(transport).classify(model, context, options)
        }
    }

    private fun runCloudflare(
        transport: MockTransport,
        model: ClassifierModel,
        context: ClassifierContext = context(),
        options: ClassifierOptions = ClassifierOptions(apiKey = "secret"),
    ): ClassifierResult = runBlocking {
        withTimeout(15_000) {
            CloudflareWorkersAiSystemOneApi(transport).classify(model, context, options)
        }
    }

    private fun bodyOf(transport: MockTransport): JsonObject {
        val body = assertNotNull(transport.lastRequest?.body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    @Test
    fun mapsBoolQuestionsToNoulAndParsesAnswers() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val model = classifierModel(server.url("/v1/").toString())
        val result = run(transport, model)

        assertEquals(ClassifierStopReason.Stop, result.stopReason)
        assertNull(result.errorMessage)
        assertEquals(
            ClassifierChoiceAnswer(
                choice = "success",
                probabilities = linkedMapOf("success" to 0.9, "failure" to 0.1),
                confidence = 0.8,
            ),
            result.answers["category"],
        )
        assertEquals(ClassifierScoreAnswer(score = 2.0, confidence = 0.7), result.answers["satisfaction"])
        assertEquals(ClassifierBoolAnswer(probability = 0.95), result.answers["approved"])
        assertNull(result.usage)

        val request = assertNotNull(transport.lastRequest)
        assertEquals("/v1/systemone", URL(request.url).path)
        assertEquals("Bearer secret", request.headers.entries.first { it.key.equals("authorization", true) }.value)
        val body = bodyOf(transport)
        assertEquals("jev-latest", (body["model"] as JsonPrimitive).content)
        assertEquals("""{"text":"The deployment succeeded, thank you."}""", (body["state"] as JsonObject).toString())
        val questions = body["questions"] as JsonObject
        val approved = questions["approved"] as JsonObject
        assertEquals("noul", (approved["type"] as JsonPrimitive).content)
        assertEquals(
            "Approval",
            (((approved["criteria"] as JsonObject)["true"]) as JsonPrimitive).content,
        )
        assertEquals("choice", ((questions["category"] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals("score", ((questions["satisfaction"] as JsonObject)["type"] as JsonPrimitive).content)
        assertTrue(!body.containsKey("temperature"))
    }

    @Test
    fun pricesReportedUsageFromCatalog() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                response(usage = """{"input_tokens":308,"output_tokens":23}"""),
            ),
        )
        val transport = MockTransport()
        val result = run(transport, classifierModel(server.url("/v1/").toString()))
        val usage = assertNotNull(result.usage)
        assertEquals(308L, usage.input)
        assertEquals(23L, usage.output)
        assertEquals(331L, usage.totalTokens)
        assertEquals(308 * 0.042 / 1_000_000.0, usage.cost.total, 1e-12)
    }

    @Test
    fun postsOpenRouterSystemOneRequestsToItsEndpoint() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"gen-dec-1","provider":"TypeSafe","answers":${systemOneAnswers()},"usage":{"input_tokens":308,"output_tokens":23,"cost":0.000012936}}""",
            ),
        )
        val transport = MockTransport()
        val model = classifierModel(
            server.url("/api/v1").toString(),
            provider = "openrouter",
        )
        val result = run(transport, model)
        assertEquals(ClassifierStopReason.Stop, result.stopReason)
        assertEquals("/api/v1/systemone", URL(assertNotNull(transport.lastRequest).url).path)
        assertEquals(ClassifierBoolAnswer(probability = 0.95), result.answers["approved"])
    }

    @Test
    fun rejectsImageInputBeforeSending() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val context = context().copy(images = listOf(ImageContent(data = "aW1hZ2U=", mimeType = "image/png")))
        val result = run(transport, classifierModel(server.url("/v1/").toString()), context)
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertEquals("System One API does not support image input", result.errorMessage)
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun rejectsOtherClassifierApis() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val result = run(
            transport,
            classifierModel(server.url("/v1/").toString()).copy(api = "cloudflare-workers-ai-system-one"),
        )
        assertEquals("Unsupported classifier API: cloudflare-workers-ai-system-one", result.errorMessage)
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun reportsMissingAndMistypedAnswers() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"answers":{"category":{"type":"choice","choice":"success","probabilities":{"success":0.9},"confidence":0.8},"satisfaction":{"type":"score","score":2,"confidence":0.7}}}""",
            ),
        )
        val transport = MockTransport()
        val missing = run(transport, classifierModel(server.url("/v1/").toString()))
        assertEquals("System One API did not return an answer for approved", missing.errorMessage)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"answers":{"category":{"type":"choice","choice":"success","probabilities":{"success":0.9},"confidence":0.8},"satisfaction":{"type":"score","score":2,"confidence":0.7},"approved":{"type":"predicate","probability":0.5}}}""".trimIndent(),
            ),
        )
        val mistyped = run(transport, classifierModel(server.url("/v1/").toString()))
        assertEquals("System One API did not return a bool answer for approved", mistyped.errorMessage)
    }

    @Test
    fun formatsHttpErrorsWithApiPrefix() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad classifier input"}"""))
        val transport = MockTransport()
        val result = run(
            transport,
            classifierModel(server.url("/v1/").toString()),
            options = ClassifierOptions(apiKey = "secret", maxRetries = 0),
        )
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertEquals("""System One API error (400): {"error":"bad classifier input"}""", result.errorMessage)
    }

    @Test
    fun mergesHeadersCaseInsensitivelyAndSupportsNullSuppression() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val model = classifierModel(
            server.url("/v1/").toString(),
            headers = mapOf("x-model" to "model", "authorization" to "Bearer model-token"),
        )
        run(
            transport,
            model,
            options = ClassifierOptions(
                apiKey = "secret",
                headers = mapOf("X-Model" to null, "x-opt" to "opt"),
            ),
        )
        val request = assertNotNull(transport.lastRequest)
        fun header(name: String): String? = request.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        assertNull(header("x-model"))
        assertEquals("opt", header("x-opt"))
        assertEquals("Bearer model-token", header("authorization"))
    }

    @Test
    fun cloudflareReadsDirectResultShape() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"result":{"model":"@cf/cloudflare/clef","answers":${systemOneAnswers()}}}""",
            ),
        )
        val transport = MockTransport()
        val model = classifierModel(
            server.url("/v1").toString(),
            id = "@cf/cloudflare/clef",
            api = "cloudflare-workers-ai-system-one",
            provider = "cloudflare-workers-ai",
            input = listOf(ModelInputModality.Text),
        )
        val result = runCloudflare(transport, model)
        assertEquals(ClassifierStopReason.Stop, result.stopReason)
        assertEquals(ClassifierBoolAnswer(probability = 0.95), result.answers["approved"])
        val request = assertNotNull(transport.lastRequest)
        assertEquals("/v1/run", URL(request.url).path)
        val body = bodyOf(transport)
        assertEquals("@cf/cloudflare/clef", (body["model"] as JsonPrimitive).content)
        val input = body["input"] as JsonObject
        assertTrue(input.containsKey("state"))
        assertTrue(input.containsKey("questions"))
    }

    @Test
    fun cloudflareReadsNestedRunRecordShape() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":true,"result":{"state":"Completed","result":{"answers":${systemOneAnswers()}}}}""",
            ),
        )
        val transport = MockTransport()
        val model = classifierModel(
            server.url("/v1").toString(),
            api = "cloudflare-workers-ai-system-one",
            provider = "cloudflare-workers-ai",
        )
        val result = runCloudflare(transport, model)
        assertEquals(ClassifierStopReason.Stop, result.stopReason)
        assertEquals(ClassifierScoreAnswer(score = 2.0, confidence = 0.7), result.answers["satisfaction"])
    }

    @Test
    fun cloudflareReportsEnvelopeFailures() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"success":false,"errors":[{"message":"bad input"},{"message":"try again"}]}""",
            ),
        )
        val transport = MockTransport()
        val model = classifierModel(
            server.url("/v1").toString(),
            api = "cloudflare-workers-ai-system-one",
            provider = "cloudflare-workers-ai",
        )
        val failed = runCloudflare(transport, model)
        assertEquals("Cloudflare Workers AI error: bad input; try again", failed.errorMessage)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"success":true,"result":{"state":"Queued"}}"""),
        )
        val unfinished = runCloudflare(transport, model)
        assertEquals("Cloudflare Workers AI run did not complete (state: Queued)", unfinished.errorMessage)
    }

    @Test
    fun cloudflareRejectsImagesWithItsLabel() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val transport = MockTransport()
        val model = classifierModel(
            server.url("/v1").toString(),
            api = "cloudflare-workers-ai-system-one",
            provider = "cloudflare-workers-ai",
        )
        val context = context().copy(images = listOf(ImageContent(data = "aW1hZ2U=", mimeType = "image/png")))
        val result = runCloudflare(transport, model, context)
        assertEquals("Cloudflare Workers AI does not support image input", result.errorMessage)
        assertEquals(0, transport.requestCount)
    }
}
