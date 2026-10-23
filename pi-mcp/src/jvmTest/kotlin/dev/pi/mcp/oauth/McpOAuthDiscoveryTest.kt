package dev.pi.mcp.oauth

import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class JdkHttpTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.requestMethod = request.method.uppercase()
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            request.body?.let { bytes ->
                connection.doOutput = true
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields.entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") }
            HttpResponse(status, headers, Buffer().writeUtf8(body))
        } finally {
            connection.disconnect()
        }
    }
}

class McpOAuthDiscoveryTest {
    private lateinit var server: MockWebServer
    private lateinit var transport: HttpTransport

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        transport = JdkHttpTransport()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    private fun jsonResponse(body: String, status: Int = 200): MockResponse =
        MockResponse.Builder()
            .code(status)
            .addHeader("content-type", "application/json")
            .body(body)
            .build()

    private fun metadataDocument(issuer: String): String = metadataJson(issuer)

    @Test
    fun discoversMetadataFromConfiguredUrl() = oauthTest {
        val issuer = baseUrl() + "/"
        server.enqueue(jsonResponse(metadataDocument(issuer)))
        val metadata = discover("${baseUrl()}/mcp", transport, "${baseUrl()}/metadata")
        assertEquals(issuer, metadata.issuer)
        val recorded = server.takeRequest()
        assertEquals("/metadata", recorded.url.encodedPath)
        assertEquals("application/json", recorded.headers["Accept"])
        assertTrue(recorded.headers["MCP-Protocol-Version"] != null)
    }

    @Test
    fun discoversThroughProtectedResourceMetadata() = oauthTest {
        val base = baseUrl()
        val issuer = "$base/"
        server.enqueue(
            jsonResponse(
                """{"resource":"$base/mcp","authorization_servers":["$issuer"]}""",
            ),
        )
        server.enqueue(jsonResponse(metadataDocument(issuer)))
        val metadata = discover("$base/mcp", transport)
        assertEquals(issuer, metadata.issuer)
        assertEquals("/.well-known/oauth-protected-resource/mcp", server.takeRequest().url.encodedPath)
        assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().url.encodedPath)
    }

    @Test
    fun fallsBackToOpenIdConfigurationWhenOauthMetadataMissing() = oauthTest {
        val base = baseUrl()
        val issuer = "$base/"
        server.enqueue(jsonResponse("{}", status = 404))
        server.enqueue(jsonResponse("{}", status = 404))
        server.enqueue(jsonResponse("{}", status = 404))
        server.enqueue(jsonResponse(metadataDocument(issuer)))
        val metadata = discover("$base/mcp", transport)
        assertEquals(issuer, metadata.issuer)
        assertEquals("/.well-known/oauth-protected-resource/mcp", server.takeRequest().url.encodedPath)
        assertEquals("/.well-known/oauth-protected-resource", server.takeRequest().url.encodedPath)
        assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().url.encodedPath)
        assertEquals("/.well-known/openid-configuration", server.takeRequest().url.encodedPath)
    }

    @Test
    fun failsWhenNoMetadataIsDiscoverable() = oauthTest {
        repeat(5) { server.enqueue(jsonResponse("{}", status = 404)) }
        val error = assertFailsWith<OAuthDiscoveryException> {
            discover("${baseUrl()}/mcp", transport)
        }
        assertTrue(error.message!!.contains("Failed to discover OAuth authorization server metadata"))
    }

    @Test
    fun rejectsIssuerMismatch() = oauthTest {
        val base = baseUrl()
        server.enqueue(jsonResponse("{}", status = 404))
        server.enqueue(jsonResponse("{}", status = 404))
        server.enqueue(jsonResponse(metadataDocument("https://other.example.com")))
        assertFailsWith<OAuthIssuerMismatchException> {
            discover("$base/mcp", transport)
        }
    }

    @Test
    fun refusesInsecureNonLoopbackDiscovery() = oauthTest {
        assertFailsWith<OAuthInsecureEndpointException> {
            discover("http://mcp.example.com/mcp", transport, "http://auth.example.com/metadata")
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun honorsConfiguredMetadataWithoutIssuerValidation() = oauthTest {
        val base = baseUrl()
        server.enqueue(jsonResponse(metadataDocument("https://trusted.example.com")))
        val metadata = discover("$base/mcp", transport, "$base/configured-metadata")
        assertEquals("https://trusted.example.com", metadata.issuer)
    }
}

private fun oauthTest(block: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
    withTimeout(20_000) { coroutineScope { block() } }
}
