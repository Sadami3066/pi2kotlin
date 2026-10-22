package dev.pi.ai.api

import dev.pi.ai.Context
import dev.pi.ai.Model
import dev.pi.ai.ModelCatalog
import dev.pi.ai.Models
import dev.pi.ai.Provider
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.auth.FileCredentialStore
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.CloudflareAuthKind
import dev.pi.ai.providers.CloudflareAuthResolver
import dev.pi.ai.providers.CLOUDFLARE_ACCOUNT_ID_ENV
import dev.pi.ai.providers.CLOUDFLARE_GATEWAY_ID_ENV
import dev.pi.ai.providers.CLOUDFLARE_GATEWAY_TOKEN_ENV
import dev.pi.ai.providers.builtinProviders
import dev.pi.ai.providers.cloudflareModelTransform
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.FileSystem
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareProvidersTest {
    private class MockTransport : HttpTransport {
        var lastRequest: HttpRequest? = null

        override suspend fun execute(request: HttpRequest): HttpResponse {
            lastRequest = request
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

    private class NoopTransport : HttpTransport {
        override suspend fun execute(request: HttpRequest): HttpResponse =
            throw UnsupportedOperationException("network is not available in tests")
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

    private fun serverBaseUrl(server: MockWebServer): String = server.url("/").toString().trimEnd('/')

    private fun openAiSse(): String =
        "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"

    private fun anthropicSse(): String =
        "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-test\",\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n" +
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

    private fun okResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun catalogModel(providerId: String, api: String, baseUrl: String): Model =
        ModelCatalog.findByProvider(providerId).filterIsInstance<Model>().first { it.api == api }
            .copy(baseUrl = baseUrl, provider = providerId)

    private fun cloudflareProvider(
        providerId: String,
        kind: CloudflareAuthKind,
        api: String,
        model: Model,
        transport: HttpTransport,
        credentialStore: FileCredentialStore? = null,
    ): Provider = Provider(
        id = providerId,
        name = providerId,
        baseUrl = null,
        apiProviders = mapOf(api to assertNotNull(chatApiFactory(api, transport))),
        models = listOf(model),
        allModels = listOf(model),
        auth = CloudflareAuthResolver(providerId, kind, credentialStore),
        modelTransform = cloudflareModelTransform(credentialStore, providerId),
    )

    private fun runSimple(
        models: Models,
        model: Model,
        options: SimpleStreamOptions,
    ): dev.pi.ai.AssistantMessage = runBlocking {
        withTimeout(15_000) {
            models.streamSimple(model, Context(messages = emptyList()), options).result()
        }
    }

    @Test
    fun workersAiMaterializesAccountIdAndSendsBearerAuthorization() = withServer { server ->
        server.enqueue(okResponse(openAiSse()))
        val transport = MockTransport()
        val base = serverBaseUrl(server)
        val model = catalogModel(
            "cloudflare-workers-ai",
            "openai-completions",
            "$base/client/v4/accounts/{$CLOUDFLARE_ACCOUNT_ID_ENV}/ai/v1",
        )
        val provider = cloudflareProvider(
            "cloudflare-workers-ai",
            CloudflareAuthKind.WorkersAi,
            "openai-completions",
            model,
            transport,
        )
        val models = Models()
        models.setProvider(provider)

        val message = runSimple(
            models,
            model,
            SimpleStreamOptions(
                stream = StreamOptions(apiKey = "cf-key", env = mapOf(CLOUDFLARE_ACCOUNT_ID_ENV to "acc-1")),
            ),
        )

        assertEquals("stop", message.rawStopReason)
        val request = assertNotNull(transport.lastRequest)
        assertEquals("$base/client/v4/accounts/acc-1/ai/v1/chat/completions", request.url)
        assertEquals("Bearer cf-key", request.headers["Authorization"])
        assertNull(request.headers["cf-aig-authorization"])
    }

    @Test
    fun aiGatewayMaterializesAccountAndGatewayIdsWithCfAigAuthorization() = withServer { server ->
        server.enqueue(okResponse(openAiSse()))
        val transport = MockTransport()
        val base = serverBaseUrl(server)
        val model = catalogModel(
            "cloudflare-ai-gateway",
            "openai-completions",
            "$base/v1/{$CLOUDFLARE_ACCOUNT_ID_ENV}/{$CLOUDFLARE_GATEWAY_ID_ENV}/compat",
        )
        val provider = cloudflareProvider(
            "cloudflare-ai-gateway",
            CloudflareAuthKind.AiGateway,
            "openai-completions",
            model,
            transport,
        )
        val models = Models()
        models.setProvider(provider)

        runSimple(
            models,
            model,
            SimpleStreamOptions(
                stream = StreamOptions(
                    apiKey = "cf-key",
                    env = mapOf(
                        CLOUDFLARE_ACCOUNT_ID_ENV to "acc-1",
                        CLOUDFLARE_GATEWAY_ID_ENV to "gw-2",
                    ),
                ),
            ),
        )

        val request = assertNotNull(transport.lastRequest)
        assertEquals("$base/v1/acc-1/gw-2/compat/chat/completions", request.url)
        assertEquals("Bearer cf-key", request.headers["cf-aig-authorization"])
        assertNull(request.headers["Authorization"])
        assertNull(request.headers["x-api-key"])
    }

    @Test
    fun aiGatewayAnthropicModelUsesCfAigWithoutProviderAuthHeaders() = withServer { server ->
        server.enqueue(okResponse(anthropicSse()))
        val transport = MockTransport()
        val base = serverBaseUrl(server)
        val model = catalogModel(
            "cloudflare-ai-gateway",
            "anthropic-messages",
            "$base/v1/{$CLOUDFLARE_ACCOUNT_ID_ENV}/{$CLOUDFLARE_GATEWAY_ID_ENV}/anthropic",
        )
        val provider = cloudflareProvider(
            "cloudflare-ai-gateway",
            CloudflareAuthKind.AiGateway,
            "anthropic-messages",
            model,
            transport,
        )
        val models = Models()
        models.setProvider(provider)

        val message = runSimple(
            models,
            model,
            SimpleStreamOptions(
                stream = StreamOptions(
                    apiKey = "cf-key",
                    env = mapOf(
                        CLOUDFLARE_ACCOUNT_ID_ENV to "acc-1",
                        CLOUDFLARE_GATEWAY_ID_ENV to "gw-2",
                    ),
                ),
            ),
        )

        assertEquals("end_turn", message.rawStopReason)
        val request = assertNotNull(transport.lastRequest)
        assertEquals("$base/v1/acc-1/gw-2/anthropic/v1/messages", request.url)
        assertEquals("Bearer cf-key", request.headers["cf-aig-authorization"])
        assertFalse(request.headers.containsKey("Authorization"))
        assertFalse(request.headers.containsKey("x-api-key"))
    }

    @Test
    fun credentialStoreSuppliesApiKeyAccountAndGatewayEnv() = withServer { server ->
        server.enqueue(okResponse(openAiSse()))
        val transport = MockTransport()
        val base = serverBaseUrl(server)
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-cloudflare-test-${kotlin.random.Random.nextLong()}"
        val store = FileCredentialStore(directory, FileSystem.SYSTEM)
        try {
            store.writeApiKey(
                "cloudflare-ai-gateway",
                "stored-key",
                mapOf(
                    CLOUDFLARE_ACCOUNT_ID_ENV to "cred-acc",
                    CLOUDFLARE_GATEWAY_ID_ENV to "cred-gw",
                ),
            )
            val model = catalogModel(
                "cloudflare-ai-gateway",
                "openai-completions",
                "$base/v1/{$CLOUDFLARE_ACCOUNT_ID_ENV}/{$CLOUDFLARE_GATEWAY_ID_ENV}/compat",
            )
            val provider = cloudflareProvider(
                "cloudflare-ai-gateway",
                CloudflareAuthKind.AiGateway,
                "openai-completions",
                model,
                transport,
                store,
            )
            val models = Models()
            models.setProvider(provider)

            runSimple(models, model, SimpleStreamOptions(stream = StreamOptions()))

            val request = assertNotNull(transport.lastRequest)
            assertEquals("$base/v1/cred-acc/cred-gw/compat/chat/completions", request.url)
            assertEquals("Bearer stored-key", request.headers["cf-aig-authorization"])
            assertNull(request.headers["Authorization"])
        } finally {
            runCatching { FileSystem.SYSTEM.deleteRecursively(directory) }
        }
    }

    @Test
    fun gatewayTokenIsPreferredAndProviderKeyAuthorizationIsPreserved() = withServer { server ->
        server.enqueue(okResponse(openAiSse()))
        val transport = MockTransport()
        val base = serverBaseUrl(server)
        val model = catalogModel(
            "cloudflare-ai-gateway",
            "openai-completions",
            "$base/v1/{$CLOUDFLARE_ACCOUNT_ID_ENV}/{$CLOUDFLARE_GATEWAY_ID_ENV}/compat",
        )
        val provider = cloudflareProvider(
            "cloudflare-ai-gateway",
            CloudflareAuthKind.AiGateway,
            "openai-completions",
            model,
            transport,
        )
        val models = Models()
        models.setProvider(provider)

        runSimple(
            models,
            model,
            SimpleStreamOptions(
                stream = StreamOptions(
                    env = mapOf(
                        "CLOUDFLARE_API_KEY" to "cf-provider-key",
                        CLOUDFLARE_ACCOUNT_ID_ENV to "acc-1",
                        CLOUDFLARE_GATEWAY_ID_ENV to "gw-2",
                        CLOUDFLARE_GATEWAY_TOKEN_ENV to "gateway-token",
                    ),
                ),
            ),
        )

        val request = assertNotNull(transport.lastRequest)
        assertEquals("Bearer gateway-token", request.headers["cf-aig-authorization"])
        assertEquals("Bearer cf-provider-key", request.headers["Authorization"])
        assertNull(request.headers["x-api-key"])
    }

    @Test
    fun builtinProvidersWireCloudflareAdaptersAndProtocolFactories() {
        val providers = builtinProviders(NoopTransport()).associateBy { it.id }

        val workersAi = assertNotNull(providers["cloudflare-workers-ai"])
        assertNotNull(workersAi.modelTransform)
        assertTrue(workersAi.apiProviders.keys.contains("openai-completions"))
        assertEquals("openai-completions", assertNotNull(workersAi.apiProviders["openai-completions"]).invoke().api)
        assertTrue(workersAi.classifierApis.keys.contains("cloudflare-workers-ai-system-one"))

        val gateway = assertNotNull(providers["cloudflare-ai-gateway"])
        assertNotNull(gateway.modelTransform)
        assertEquals(
            setOf("anthropic-messages", "openai-completions", "openai-responses"),
            gateway.apiProviders.keys,
        )
        for ((api, factory) in gateway.apiProviders) {
            assertEquals(api, factory().api)
        }
    }

    @Test
    fun builtinCloudflareTransformReplacesPlaceholders() {
        val providers = builtinProviders(NoopTransport()).associateBy { it.id }
        val workersAi = assertNotNull(providers["cloudflare-workers-ai"])
        val transform = assertNotNull(workersAi.modelTransform)
        val model = workersAi.models.first { it.api == "openai-completions" }
        val resolved = transform(
            model,
            mapOf(CLOUDFLARE_ACCOUNT_ID_ENV to "acc-9", CLOUDFLARE_GATEWAY_ID_ENV to "gw-9"),
        ) as Model
        assertTrue(resolved.baseUrl.contains("acc-9"))
        assertFalse(resolved.baseUrl.contains("{CLOUDFLARE_ACCOUNT_ID}"))
    }
}
