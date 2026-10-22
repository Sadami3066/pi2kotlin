package dev.pi.ai.api

import dev.pi.ai.ImageContent
import dev.pi.ai.ImageModel
import dev.pi.ai.ImagesContext
import dev.pi.ai.ImagesOptions
import dev.pi.ai.ImagesStopReason
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.TextContent
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class OpenRouterImagesTest {
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

    private fun imageModel(
        baseUrl: String,
        id: String = "google/gemini-3.1-flash-image-preview",
        output: List<String> = listOf("text", "image"),
        headers: Map<String, String>? = null,
        cost: ModelCost = ModelCost(input = 15.0, output = 30.0, cacheRead = 0.0, cacheWrite = 0.0),
    ): ImageModel = ImageModel(
        id = id,
        name = "Image Model",
        api = "openrouter-images",
        provider = "openrouter",
        baseUrl = baseUrl,
        input = listOf(ModelInputModality.Text, ModelInputModality.Image),
        cost = cost,
        headers = headers,
        output = output,
    )

    private fun textContext(text: String = "Generate a dog"): ImagesContext =
        ImagesContext(listOf(TextContent(text)))

    private fun response(
        id: String = "img-1",
        content: String? = "Here is your image.",
        images: List<Pair<String?, String?>> = listOf("image/png" to "ZmFrZS1wbmc="),
        usage: String? = """{"prompt_tokens":12,"completion_tokens":34,"prompt_tokens_details":{"cached_tokens":0}}""",
    ): String {
        val messageFields = mutableListOf<String>()
        if (content != null) messageFields.add("\"content\":${quote(content)}")
        val imageEntries = images.map { (mime, data) ->
            when {
                mime == null -> "{\"image_url\":${quote(data ?: "")}}"
                else -> "{\"image_url\":{\"url\":${quote("data:$mime;base64,$data")}}}"
            }
        }
        if (imageEntries.isNotEmpty()) messageFields.add("\"images\":[${imageEntries.joinToString(",")}]")
        val usageField = if (usage == null) "" else ",\"usage\":$usage"
        return """{"id":"$id","choices":[{"message":{${messageFields.joinToString(",")}}}]$usageField}"""
    }

    private fun quote(value: String): String = PiJson.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun run(
        transport: MockTransport,
        model: ImageModel,
        context: ImagesContext,
        options: ImagesOptions = ImagesOptions(apiKey = "test-key"),
    ) = runBlocking {
        withTimeout(15_000) {
            OpenRouterImagesApi(transport).generateImages(model, context, options)
        }
    }

    private fun bodyOf(transport: MockTransport): JsonObject {
        val body = assertNotNull(transport.lastRequest?.body)
        return PiJson.parseToJsonElement(body.decodeToString()) as JsonObject
    }

    @Test
    fun returnsTextAndImagesWithUsageAndCost() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val model = imageModel(server.url("/api/v1").toString())
        val result = run(transport, model, textContext())

        assertEquals(ImagesStopReason.Stop, result.stopReason)
        assertNull(result.errorMessage)
        assertEquals("img-1", result.responseId)
        assertEquals(2, result.output.size)
        val text = result.output[0] as TextContent
        assertEquals("Here is your image.", text.text)
        val image = result.output[1] as ImageContent
        assertEquals("image/png", image.mimeType)
        assertEquals("ZmFrZS1wbmc=", image.data)
        val usage = assertNotNull(result.usage)
        assertEquals(12L, usage.input)
        assertEquals(34L, usage.output)
        assertEquals(0L, usage.cacheRead)
        assertEquals(46L, usage.totalTokens)
        assertEquals(12 * 15.0 / 1_000_000.0, usage.cost.input, 1e-12)
        assertEquals(34 * 30.0 / 1_000_000.0, usage.cost.output, 1e-12)
        assertEquals(usage.cost.input + usage.cost.output, usage.cost.total, 1e-12)

        val request = assertNotNull(transport.lastRequest)
        assertEquals("/api/v1/chat/completions", URL(request.url).path)
        assertEquals("Bearer test-key", request.headers.entries.first { it.key.equals("authorization", true) }.value)
        assertEquals("pi", request.headers.entries.first { it.key.equals("User-Agent", true) }.value)
        val body = bodyOf(transport)
        assertEquals(model.id, (body["model"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(false), body["stream"])
        assertEquals(
            listOf("image", "text"),
            (body["modalities"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        val message = (body["messages"] as JsonArray).first() as JsonObject
        assertEquals("user", (message["role"] as JsonPrimitive).content)
        val content = message["content"] as JsonArray
        val textPart = content.first() as JsonObject
        assertEquals("text", (textPart["type"] as JsonPrimitive).content)
        assertEquals("Generate a dog", (textPart["text"] as JsonPrimitive).content)
    }

    @Test
    fun imageOnlyModelsRequestImageModalityOnly() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response(content = null)))
        val transport = MockTransport()
        val model = imageModel(server.url("/api/v1").toString(), output = listOf("image"))
        val result = run(transport, model, textContext())
        assertEquals(ImagesStopReason.Stop, result.stopReason)
        assertEquals(
            listOf("image"),
            (bodyOf(transport)["modalities"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        assertEquals(1, result.output.size)
    }

    @Test
    fun sendsImageInputAsDataUrlParts() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val model = imageModel(server.url("/api/v1").toString())
        val context = ImagesContext(
            listOf(
                TextContent("Create a variation"),
                ImageContent(data = "aW1hZ2U=", mimeType = "image/png"),
            ),
        )
        run(transport, model, context)
        val content = ((bodyOf(transport)["messages"] as JsonArray).first() as JsonObject)["content"] as JsonArray
        val imagePart = content[1] as JsonObject
        assertEquals("image_url", (imagePart["type"] as JsonPrimitive).content)
        val url = ((imagePart["image_url"] as JsonObject)["url"] as JsonPrimitive).content
        assertEquals("data:image/png;base64,aW1hZ2U=", url)
    }

    @Test
    fun supportsBothImageUrlShapesAndSkipsUnsupportedOnes() = withServer { server ->
        val wire = """
            {
              "id": "img-2",
              "choices": [{
                "message": {
                  "images": [
                    { "image_url": "https://example.com/image.png" },
                    { "image_url": "data:image/jpeg;base64,aW1n" },
                    { "image_url": { "url": "data:image/webp;base64,d2Vi" } },
                    { "image_url": "data:image/png;not-base64" }
                  ]
                }
              }]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(wire))
        val transport = MockTransport()
        val result = run(transport, imageModel(server.url("/api/v1").toString()), textContext())
        assertEquals(ImagesStopReason.Stop, result.stopReason)
        assertEquals(2, result.output.size)
        assertEquals("image/jpeg", (result.output[0] as ImageContent).mimeType)
        assertEquals("image/webp", (result.output[1] as ImageContent).mimeType)
        assertEquals("img-2", result.responseId)
    }

    @Test
    fun reportsHttpErrorsWithBody() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"message":"bad image prompt"}}"""),
        )
        val transport = MockTransport()
        val result = run(transport, imageModel(server.url("/api/v1").toString()), textContext())
        assertEquals(ImagesStopReason.Error, result.stopReason)
        assertEquals("""400: {"error":{"message":"bad image prompt"}}""", result.errorMessage)
        assertTrue(result.output.isEmpty())
    }

    @Test
    fun retriesRetryableStatusesHonoringRetryAfter() = withServer { server ->
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("retry-after-ms", "1")
                .setBody("busy"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val result = run(
            transport,
            imageModel(server.url("/api/v1").toString()),
            textContext(),
            ImagesOptions(apiKey = "test-key", maxRetries = 1),
        )
        assertEquals(2, transport.requestCount)
        assertEquals(ImagesStopReason.Stop, result.stopReason)
    }

    @Test
    fun doesNotRetryWithoutRetriesConfigured() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val transport = MockTransport()
        val result = run(transport, imageModel(server.url("/api/v1").toString()), textContext())
        assertEquals(1, transport.requestCount)
        assertEquals(ImagesStopReason.Error, result.stopReason)
        assertEquals("500: boom", result.errorMessage)
    }

    @Test
    fun reportsMissingApiKey() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val result = run(
            transport,
            imageModel(server.url("/api/v1").toString()),
            textContext(),
            ImagesOptions(),
        )
        assertEquals(ImagesStopReason.Error, result.stopReason)
        assertEquals("No API key for provider: openrouter", result.errorMessage)
        assertEquals(0, transport.requestCount)
    }

    @Test
    fun mergesModelAndRequestHeaders() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(response()))
        val transport = MockTransport()
        val model = imageModel(
            server.url("/api/v1").toString(),
            headers = mapOf("HTTP-Referer" to "https://model.example", "x-model" to "model"),
        )
        run(
            transport,
            model,
            textContext(),
            ImagesOptions(
                apiKey = "test-key",
                headers = mapOf("x-model" to "request", "x-request" to "request", "user-agent" to null),
            ),
        )
        val request = assertNotNull(transport.lastRequest)
        fun header(name: String): String? = request.headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        assertEquals("https://model.example", header("HTTP-Referer"))
        assertEquals("request", header("x-model"))
        assertEquals("request", header("x-request"))
        assertNull(header("User-Agent"))
        assertEquals("Bearer test-key", header("Authorization"))
    }
}
