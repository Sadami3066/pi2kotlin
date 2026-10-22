package dev.pi.coding.scenarios

import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.Provider
import dev.pi.ai.RunSignal
import dev.pi.ai.api.openAICompletionsApi
import dev.pi.ai.auth.FileCredentialStore
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.auth.oauth.OAuthAuth
import dev.pi.ai.auth.oauth.OAuthCredential
import dev.pi.ai.auth.oauth.OAuthInteraction
import dev.pi.ai.auth.oauth.OAuthLoginOptions
import dev.pi.ai.auth.oauth.oauthResolver
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.platform.platformCurrentTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthScenarioTest {

    private class FakeTransport : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests.add(request)
            return when {
                request.url.contains("/oauth/token") -> jsonResponse(
                    """{"access_token":"new-access","refresh_token":"refresh-2","expires_in":3600,"token_type":"Bearer"}""",
                )

                request.url.endsWith("/chat/completions") -> chatResponse()
                else -> HttpResponse(
                    status = 404,
                    headers = mapOf("content-type" to "application/json"),
                    body = Buffer().writeUtf8("""{"error":"not found"}"""),
                )
            }
        }

        private fun jsonResponse(body: String): HttpResponse = HttpResponse(
            status = 200,
            headers = mapOf("content-type" to "application/json"),
            body = Buffer().writeUtf8(body),
        )

        private fun chatResponse(): HttpResponse {
            val body = buildString {
                append("data: ")
                append(
                    """{"id":"c1","object":"chat.completion.chunk","model":"acme-1","choices":[{"index":0,"delta":{"role":"assistant","content":"hello"},"finish_reason":null}]}""",
                )
                append("\n\n")
                append("data: ")
                append(
                    """{"id":"c1","object":"chat.completion.chunk","model":"acme-1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                )
                append("\n\n")
                append("data: [DONE]\n\n")
            }
            return HttpResponse(
                status = 200,
                headers = mapOf("content-type" to "text/event-stream"),
                body = Buffer().writeUtf8(body),
            )
        }
    }

    private class FakeOAuthFlow : OAuthAuth {
        override val id: String = "acme"
        override val name: String = "Acme"
        var refreshCount: Int = 0

        override suspend fun login(
            interaction: OAuthInteraction,
            transport: HttpTransport,
            options: OAuthLoginOptions?,
        ): OAuthCredential = error("login is not used in scenario tests")

        override suspend fun refresh(
            credential: OAuthCredential,
            transport: HttpTransport,
            signal: RunSignal?,
        ): OAuthCredential {
            refreshCount += 1
            val request = HttpRequest(
                method = "POST",
                url = "https://acme.test/oauth/token",
                headers = mapOf("content-type" to "application/x-www-form-urlencoded"),
                body = "grant_type=refresh_token&refresh_token=${credential.refresh}".encodeToByteArray(),
            )
            val response = transport.execute(request)
            val json = PiJson.parseToJsonElement(response.body.readUtf8()).jsonObject
            val access = json.getValue("access_token").jsonPrimitive.content
            val refresh = json["refresh_token"]?.jsonPrimitive?.contentOrNull ?: credential.refresh
            return credential.copy(access = access, refresh = refresh, expires = platformCurrentTimeMillis() + 3_600_000L)
        }

        override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth = ResolvedAuth(
            apiKey = credential.access,
            headers = mapOf("x-acme-auth" to "Bearer ${credential.access}"),
            source = "oauth",
        )
    }

    private fun acmeModel(): Model = Model(
        id = "acme-1",
        name = "Acme 1",
        api = "openai-completions",
        provider = "acme",
        baseUrl = "https://acme.test/v1",
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
        reasoning = false,
        contextWindow = 128_000,
        maxTokens = 4096,
    )

    private fun acmeProvider(transport: HttpTransport, flow: FakeOAuthFlow, store: FileCredentialStore): Provider =
        Provider(
            id = "acme",
            name = "Acme",
            apiProviders = mapOf("openai-completions" to { openAICompletionsApi(transport) }),
            models = listOf(acmeModel()),
            auth = oauthResolver("acme", flow, store, transport),
        )

    private fun headerValue(request: HttpRequest, name: String): String? = request.headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    @Test
    fun refreshesExpiredOAuthCredentialBeforeTheRequest() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val store = FileCredentialStore(ScenarioSupport.AGENT_DIR.toPath(), fs)
            store.writeRawCredential(
                "acme",
                buildJsonObject {
                    put("type", "oauth")
                    put("refresh", "refresh-1")
                    put("access", "old-access")
                    put("expires", 1_000L)
                },
            )
            val transport = FakeTransport()
            val flow = FakeOAuthFlow()
            val provider = acmeProvider(transport, flow, store)
            val session = ScenarioSupport.create(
                fs = fs,
                providers = listOf(provider),
                model = "acme/acme-1",
                transport = transport,
                credentials = store,
            )
            try {
                session.prompt("hello")
                assertEquals("hello", session.getLastAssistantText())
                assertEquals(1, flow.refreshCount)
                assertTrue(transport.requests.any { it.url.contains("/oauth/token") })
                val refreshIndex = transport.requests.indexOfFirst { it.url.contains("/oauth/token") }
                val chatIndex = transport.requests.indexOfFirst { it.url.endsWith("/chat/completions") }
                assertTrue(refreshIndex >= 0 && chatIndex > refreshIndex)
                val chat = transport.requests[chatIndex]
                assertEquals("Bearer new-access", headerValue(chat, "authorization"))
                assertEquals("Bearer new-access", headerValue(chat, "x-acme-auth"))
                val stored = assertNotNull(store.readRaw()["acme"]).jsonObject
                assertEquals("new-access", stored["access"]!!.jsonPrimitive.content)
                assertEquals("refresh-2", stored["refresh"]!!.jsonPrimitive.content)
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun usesToAuthHeadersForValidCredentialsWithoutRefresh() = runBlocking {
        withTimeout(30_000) {
            val fs = ScenarioSupport.fileSystem()
            val store = FileCredentialStore(ScenarioSupport.AGENT_DIR.toPath(), fs)
            store.writeRawCredential(
                "acme",
                buildJsonObject {
                    put("type", "oauth")
                    put("refresh", "refresh-1")
                    put("access", "valid-access")
                    put("expires", platformCurrentTimeMillis() + 3_600_000L)
                },
            )
            val transport = FakeTransport()
            val flow = FakeOAuthFlow()
            val session = ScenarioSupport.create(
                fs = fs,
                providers = listOf(acmeProvider(transport, flow, store)),
                model = "acme/acme-1",
                transport = transport,
                credentials = store,
            )
            try {
                session.prompt("hello")
                assertEquals("hello", session.getLastAssistantText())
                assertEquals(0, flow.refreshCount)
                assertTrue(transport.requests.none { it.url.contains("/oauth/token") })
                val chat = transport.requests.last { it.url.endsWith("/chat/completions") }
                assertEquals("Bearer valid-access", headerValue(chat, "authorization"))
                assertEquals("Bearer valid-access", headerValue(chat, "x-acme-auth"))
            } finally {
                session.close()
            }
        }
    }
}
