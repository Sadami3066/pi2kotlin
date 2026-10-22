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
import dev.pi.ai.ModelCostTier
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

class OpenAiDecisionsTest {
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

    private fun decisionsModel(
        baseUrl: String,
        cost: ModelCost = ModelCost(
            input = 0.1,
            output = 0.0,
            cacheRead = 0.0,
            cacheWrite = 0.0,
            tiers = listOf(ModelCostTier(inputTokensAbove = 272_000, input = 0.2, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0)),
        ),
    ): ClassifierModel = ClassifierModel(
        id = "gpt-6-luna",
        name = "GPT-6 Luna",
        api = "openai-decisions",
        provider = "openai",
        baseUrl = baseUrl,
        input = listOf(ModelInputModality.Text, ModelInputModality.Image),
        cost = cost,
        contextWindow = 922_000,
    )

    private fun context(): ClassifierContext = ClassifierContext(
        state = buildJsonObject { put("text", JsonPrimitive("The deployment succeeded, thank you.")) },
        questions = linkedMapOf(
            "category" to ClassifierChoiceQuestion(
                instructions = "Classify the message",
                criteria = linkedMapOf("success" to "Successful", "failure" to ""),
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

    private fun answersJson(reverse: Boolean = false): String {
        val answers = listOf(
            """{"type":"choice","name":"category","choice":"success","probabilities":[{"value":"success","probability":0.9},{"value":"failure","probability":0.1}],"confidence":0.8}""",
            """{"type":"score","name":"satisfaction","score":1.8,"probabilities":[{"value":0,"label":"low","probability":0.05},{"value":2,"label":"high","probability":0.85}],"confidence":0.7}""",
            """{"type":"predicate","name":"approved","probability":0.95}""",
        )
        val ordered = if (reverse) answers.reversed() else answers
        return ordered.joinToString(",")
    }

    private fun response(
        answers: String = answersJson(),
        usage: String? = """{"input_tokens":164,"output_tokens":0}""",
    ): String {
        val usageField = if (usage == null) "" else ",\"usage\":$usage"
        return """{"model":"gpt-6-luna","answers":[$answers]$usageField}"""
    }

    private fun run(
        transport: MockTransport,
        model: ClassifierModel,
        context: ClassifierContext = context(),
        options: ClassifierOptions = ClassifierOptions(apiKey = "secret"),
    ): ClassifierResult = runBlocking {
        withTimeout(15_000) {
            OpenAiDecisionsApi(transport).classify(model, context, options)
        }
    }

    private fun bodyOf(transport: MockTransport): JsonObject {
        val body = assertNotNull(transport.lastRequest?.body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    @Test
    fun mapsQuestionsAndParsesAnswersByName() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response(answersJson(reverse = true))))
        val transport = MockTransport()
        val model = decisionsModel(server.url("/v1").toString())
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
        assertEquals(ClassifierScoreAnswer(score = 1.8, confidence = 0.7), result.answers["satisfaction"])
        assertEquals(ClassifierBoolAnswer(probability = 0.95), result.answers["approved"])
        val usage = assertNotNull(result.usage)
        assertEquals(164L, usage.input)
        assertEquals(164L, usage.totalTokens)
        assertEquals(164 * 0.1 / 1_000_000.0, usage.cost.total, 1e-12)

        val request = assertNotNull(transport.lastRequest)
        assertEquals("/v1/decisions", URL(request.url).path)
        assertEquals("Bearer secret", request.headers.entries.first { it.key.equals("authorization", true) }.value)
        val body = bodyOf(transport)
        assertEquals("gpt-6-luna", (body["model"] as JsonPrimitive).content)
        assertEquals("""{"text":"The deployment succeeded, thank you."}""", (body["input"] as JsonPrimitive).content)
        val questions = body["questions"] as JsonArray
        assertEquals(3, questions.size)
        val category = questions[0] as JsonObject
        assertEquals("choice", (category["type"] as JsonPrimitive).content)
        assertEquals("category", (category["name"] as JsonPrimitive).content)
        val choices = category["choices"] as JsonArray
        assertEquals("Successful", ((choices[0] as JsonObject)["description"] as JsonPrimitive).content)
        assertTrue(!(choices[1] as JsonObject).containsKey("description"))
        val satisfaction = questions[1] as JsonObject
        assertEquals("score", (satisfaction["type"] as JsonPrimitive).content)
        val levels = satisfaction["levels"] as JsonArray
        assertEquals("neutral", ((levels[1] as JsonObject)["label"] as JsonPrimitive).content)
        val approved = questions[2] as JsonObject
        assertEquals("predicate", (approved["type"] as JsonPrimitive).content)
        assertEquals(
            "Does the user approve?\n\nTrue means: Approval\nFalse means: No approval",
            (approved["instructions"] as JsonPrimitive).content,
        )
    }

    @Test
    fun pricesLongContextRequestsAtTierRate() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(response(usage = """{"input_tokens":300000,"output_tokens":0}""")),
        )
        val transport = MockTransport()
        val result = run(transport, decisionsModel(server.url("/v1").toString()))
        assertEquals(300_000 * 0.2 / 1_000_000.0, assertNotNull(result.usage).cost.total, 1e-12)
    }

    @Test
    fun sendsImagesAfterStateInOneUserMessage() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val context = context().copy(
            images = listOf(
                ImageContent(data = "aW1hZ2U=", mimeType = "image/png"),
                ImageContent(data = "aW1hZ2U=", mimeType = "image/jpeg"),
            ),
        )
        val result = run(transport, decisionsModel(server.url("/v1").toString()), context)
        assertEquals(ClassifierStopReason.Stop, result.stopReason)
        val input = bodyOf(transport)["input"] as JsonArray
        val message = input.first() as JsonObject
        assertEquals("user", (message["role"] as JsonPrimitive).content)
        val content = message["content"] as JsonArray
        assertEquals("input_text", ((content[0] as JsonObject)["type"] as JsonPrimitive).content)
        assertEquals(
            """{"text":"The deployment succeeded, thank you."}""",
            ((content[0] as JsonObject)["text"] as JsonPrimitive).content,
        )
        assertEquals(
            "data:image/png;base64,aW1hZ2U=",
            ((content[1] as JsonObject)["image_url"] as JsonPrimitive).content,
        )
        assertEquals(
            "data:image/jpeg;base64,aW1hZ2U=",
            ((content[2] as JsonObject)["image_url"] as JsonPrimitive).content,
        )
    }

    @Test
    fun rejectsTooManyImagesBeforeSending() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val context = context().copy(
            images = List(129) { ImageContent(data = "aW1hZ2U=", mimeType = "image/png") },
        )
        val result = run(transport, decisionsModel(server.url("/v1").toString()), context)
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertTrue(result.errorMessage!!.contains("at most 128 images, got 129"))
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun keepsBilledUsageWhenAnAnswerIsRefused() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                response(
                    answers = """{"type":"choice","name":"category","choice":"success","probabilities":[],"confidence":0.8},{"type":"score","name":"satisfaction","score":1.8,"confidence":0.7},{"type":"refusal","name":"approved"}""",
                ),
            ),
        )
        val transport = MockTransport()
        val result = run(transport, decisionsModel(server.url("/v1").toString()))
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertEquals("OpenAI Decisions refused to answer approved", result.errorMessage)
        assertTrue(result.answers.isEmpty())
        assertEquals(164L, assertNotNull(result.usage).input)
    }

    @Test
    fun reportsMissingAndMistypedAnswers() = withServer { server ->
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                response(
                    answers = """{"type":"choice","name":"category","choice":"success","probabilities":[{"value":"success","probability":0.9}],"confidence":0.8},{"type":"score","name":"satisfaction","score":1.8,"confidence":0.7}""",
                    usage = null,
                ),
            ),
        )
        val transport = MockTransport()
        val missing = run(transport, decisionsModel(server.url("/v1").toString()))
        assertEquals("OpenAI Decisions did not return an answer for approved", missing.errorMessage)

        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                response(
                    answers = """{"type":"choice","name":"category","choice":"success","probabilities":[{"value":"success","probability":0.9}],"confidence":0.8},{"type":"score","name":"satisfaction","score":1.8,"confidence":0.7},{"type":"score","name":"approved"}""",
                    usage = null,
                ),
            ),
        )
        val mistyped = run(transport, decisionsModel(server.url("/v1").toString()))
        assertEquals("OpenAI Decisions did not return a predicate answer for approved", mistyped.errorMessage)
    }

    @Test
    fun doesNotRetryGatewayTimeoutsAndExplainsThem() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(504)
                .setHeader("retry-after-ms", "0")
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html>Gateway time-out</html>"),
        )
        val transport = MockTransport()
        val result = run(transport, decisionsModel(server.url("/v1").toString()))
        assertEquals(1, transport.requestCount)
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertEquals(
            "OpenAI Decisions error (504): the request timed out at the gateway. " +
                "Very large inputs (above roughly 600K tokens) currently exceed its time limit.",
            result.errorMessage,
        )
    }

    @Test
    fun retriesOtherServerErrorsByDefault() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("retry-after-ms", "1")
                .setBody("busy"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val result = run(transport, decisionsModel(server.url("/v1").toString()))
        assertEquals(2, transport.requestCount)
        assertEquals(ClassifierStopReason.Stop, result.stopReason)
    }

    @Test
    fun includesApiErrorBodyForHttpFailures() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"Decision input exceeds the token limit.","type":"invalid_request_error"}}"""),
        )
        val transport = MockTransport()
        val result = run(
            transport,
            decisionsModel(server.url("/v1").toString()),
            options = ClassifierOptions(apiKey = "secret", maxRetries = 0),
        )
        assertEquals(ClassifierStopReason.Error, result.stopReason)
        assertTrue(result.errorMessage!!.startsWith("OpenAI Decisions error (400): "))
        assertTrue(assertNotNull(result.errorMessage).contains("Decision input exceeds the token limit."))
    }

    @Test
    fun rejectsOtherApisAndMissingKeys() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val otherApi = run(
            transport,
            decisionsModel(server.url("/v1").toString()).copy(api = "typesafe-system-one"),
        )
        assertEquals("Unsupported classifier API: typesafe-system-one", otherApi.errorMessage)
        val noKey = run(
            transport,
            decisionsModel(server.url("/v1").toString()),
            options = ClassifierOptions(),
        )
        assertEquals("No API key for provider: openai", noKey.errorMessage)
        assertEquals(0, transport.requestCount)
    }
}
