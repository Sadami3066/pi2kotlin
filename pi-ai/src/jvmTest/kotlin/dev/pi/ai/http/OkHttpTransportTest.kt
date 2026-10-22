package dev.pi.ai.http

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OkHttpTransportTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun noArgFactoryCreatesTransport() {
        assertIs<HttpTransport>(okHttpTransport())
    }

    @Test
    fun defaultClientUsesStreamingTimeoutsAndEnvProxySelector() {
        val client = defaultClient()
        assertEquals(0, client.readTimeoutMillis)
        assertEquals(600_000, client.callTimeoutMillis)
        assertIs<EnvProxySelector>(client.proxySelector)
    }

    @Test
    fun streamsResponseBodyToSseParser() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("content-type", "text/event-stream")
                .body("data: one\n\ndata: two\n\n")
                .build(),
        )
        val response = transport().execute(HttpRequest("GET", server.url("/stream").toString()))
        try {
            assertEquals(200, response.status)
            assertEquals("text/event-stream", response.header("Content-Type"))
            val parser = SseParser(response.body)
            assertEquals(SseEvent(null, "one", null, null), parser.next())
            assertEquals(SseEvent(null, "two", null, null), parser.next())
            assertNull(parser.next())
        } finally {
            response.close()
        }
    }

    @Test
    fun returnsNonSuccessfulResponseWithoutThrowing() = runBlocking {
        val body = """{"error":{"message":"rate limited"}}"""
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .addHeader("content-type", "application/json")
                .body(body)
                .build(),
        )
        val response = transport().execute(HttpRequest("GET", server.url("/limited").toString()))
        try {
            assertEquals(429, response.status)
            assertFalse(response.isSuccessful())
            assertEquals(body, response.body.readUtf8())
        } finally {
            response.close()
        }
    }

    @Test
    fun sendsMethodHeadersAndBody() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val response = transport().execute(
            HttpRequest(
                method = "POST",
                url = server.url("/v1/chat/completions").toString(),
                headers = mapOf(
                    "authorization" to "Bearer test-key",
                    "content-type" to "application/json",
                ),
                body = """{"model":"test-model","stream":true}""".encodeToByteArray(),
            ),
        )
        response.close()
        val recorded = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer test-key", recorded.headers["authorization"])
        assertEquals("application/json", recorded.headers["content-type"])
        assertEquals("""{"model":"test-model","stream":true}""", assertNotNull(recorded.body).utf8())
    }

    @Test
    fun exposesResponseHeadersCaseInsensitively() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("X-Request-Id", "req-123")
                .body("ok")
                .build(),
        )
        val response = transport().execute(HttpRequest("GET", server.url("/headers").toString()))
        try {
            assertEquals("req-123", response.headers["x-request-id"])
            assertEquals("req-123", response.header("X-REQUEST-ID"))
        } finally {
            response.close()
        }
    }

    @Test
    fun wrapsConnectionFailuresAsTransportException() {
        runBlocking {
            val deadServer = MockWebServer()
            deadServer.start()
            val deadUrl = deadServer.url("/gone").toString()
            deadServer.close()
            val failure = runCatching { transport().execute(HttpRequest("GET", deadUrl)) }.exceptionOrNull()
            assertIs<HttpTransportException>(failure)
        }
    }

    @Test
    fun cancelsInFlightRequestWhenCoroutineCancelled() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("late").build())
        val job = launch(Dispatchers.IO) {
            transport().execute(HttpRequest("GET", server.url("/slow").toString()))
        }
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun appliesPerRequestTimeout() {
        runBlocking {
            server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("late").build())
            val failure = runCatching {
                transport().execute(HttpRequest("GET", server.url("/slow").toString(), timeoutMs = 200))
            }.exceptionOrNull()
            assertIs<HttpTransportException>(failure)
        }
    }

    @Test
    fun abortsInFlightRequestWhenSignalAborts() {
        runBlocking {
            server.enqueue(MockResponse.Builder().headersDelay(2, TimeUnit.SECONDS).body("late").build())
            val signal = RunSignal()
            var failure: Throwable? = null
            val job = launch(Dispatchers.IO) {
                try {
                    transport().execute(HttpRequest("GET", server.url("/slow").toString(), signal = signal))
                } catch (error: Throwable) {
                    failure = error
                }
            }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            signal.abort("user aborted")
            job.join()
            assertIs<RequestAbortedException>(failure)
        }
    }

    @Test
    fun abortsStreamingBodyReadWhenSignalAborts() = runBlocking {
        val body = buildString { repeat(40) { append("data: {\"index\":$it}\n\n") } }
        server.enqueue(
            MockResponse.Builder()
                .addHeader("content-type", "text/event-stream")
                .body(body)
                .throttleBody(32, 250, TimeUnit.MILLISECONDS)
                .build(),
        )
        val signal = RunSignal()
        val response = transport().execute(HttpRequest("GET", server.url("/stream").toString(), signal = signal))
        val parser = SseParser(response.body)
        try {
            assertNotNull(parser.next())
            signal.abort("user aborted")
            val failure = runCatching {
                while (parser.next() != null) {
                }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(signal.isAborted)
        } finally {
            parser.close()
            response.close()
        }
    }

    @Test
    fun resolvesProxyFromUppercaseEnvironment() {
        val proxy = assertNotNull(
            resolveProxy("https://api.example.com/v1", mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888")),
        )
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = assertIs<InetSocketAddress>(proxy.address())
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(8888, address.port)
    }

    @Test
    fun resolvesProxyFromLowercaseEnvironment() {
        val proxy = assertNotNull(
            resolveProxy("http://api.example.com/v1", mapOf("http_proxy" to "http://proxy.local:3128")),
        )
        val address = assertIs<InetSocketAddress>(proxy.address())
        assertEquals("proxy.local", address.hostString)
        assertEquals(3128, address.port)
    }

    @Test
    fun prefersProtocolProxyOverAllProxy() {
        val proxy = assertNotNull(
            resolveProxy(
                "https://api.example.com",
                mapOf("all_proxy" to "http://all.local:1080", "https_proxy" to "http://specific.local:8888"),
            ),
        )
        assertEquals("specific.local", (proxy.address() as InetSocketAddress).hostString)
    }

    @Test
    fun fallsBackToAllProxy() {
        val proxy = assertNotNull(
            resolveProxy("https://api.example.com", mapOf("ALL_PROXY" to "http://all.local:1080")),
        )
        assertEquals("all.local", (proxy.address() as InetSocketAddress).hostString)
        assertEquals(1080, (proxy.address() as InetSocketAddress).port)
    }

    @Test
    fun defaultsProxySchemeToTargetScheme() {
        val proxy = assertNotNull(
            resolveProxy("https://api.example.com", mapOf("https_proxy" to "127.0.0.1:8888")),
        )
        assertEquals(8888, (proxy.address() as InetSocketAddress).port)
    }

    @Test
    fun usesHttpProxyForHttpTargetsOnly() {
        assertNotNull(resolveProxy("http://api.example.com", mapOf("http_proxy" to "http://127.0.0.1:8888")))
        assertNull(resolveProxy("https://api.example.com", mapOf("http_proxy" to "http://127.0.0.1:8888")))
    }

    @Test
    fun noProxyWildcardDisablesProxying() {
        assertNull(
            resolveProxy(
                "https://api.example.com",
                mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888", "NO_PROXY" to "*"),
            ),
        )
    }

    @Test
    fun noProxyMatchesExactHostAndSuffix() {
        val env = mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888", "NO_PROXY" to "api.example.com")
        assertNull(resolveProxy("https://api.example.com", env))
        assertNull(resolveProxy("https://sub.api.example.com", env))
        assertNotNull(resolveProxy("https://other.example.com", env))
    }

    @Test
    fun noProxyMatchesLeadingDotAndWildcardForms() {
        val env = mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888", "NO_PROXY" to ".internal.example")
        assertNull(resolveProxy("https://db.internal.example", env))
        assertNull(resolveProxy("https://internal.example", env))
        assertNotNull(resolveProxy("https://other.example", env))
        val wildcardEnv = mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888", "NO_PROXY" to "*.internal.example")
        assertNull(resolveProxy("https://db.internal.example", wildcardEnv))
    }

    @Test
    fun noProxyHonorsPort() {
        val env = mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888", "NO_PROXY" to "api.example.com:443")
        assertNull(resolveProxy("https://api.example.com", env))
        assertNotNull(resolveProxy("https://api.example.com:8443", env))
    }

    @Test
    fun rejectsSocksProxy() {
        val failure = runCatching {
            resolveProxy("https://api.example.com", mapOf("HTTPS_PROXY" to "socks5://127.0.0.1:1080"))
        }.exceptionOrNull()
        val error = assertIs<HttpTransportException>(failure)
        assertTrue(error.message!!.contains("Unsupported proxy protocol"))
    }

    @Test
    fun ignoresInvalidTargetUrl() {
        assertNull(resolveProxy("not a url", mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888")))
    }

    @Test
    fun envProxySelectorUsesEnvironmentWhenConfigured() {
        val selector = EnvProxySelector(mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888"), fallback = null)
        val proxies = selector.select(URI("https://api.example.com"))
        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.HTTP, proxies.first().type())
    }

    @Test
    fun envProxySelectorHonorsNoProxy() {
        val selector = EnvProxySelector(
            mapOf("HTTPS_PROXY" to "http://127.0.0.1:8888", "NO_PROXY" to "api.example.com"),
            fallback = null,
        )
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://api.example.com")))
    }

    @Test
    fun envProxySelectorUsesFallbackWithoutProxyEnvironment() {
        val fallback = ProxySelector.getDefault()
        val selector = EnvProxySelector(emptyMap(), fallback = fallback)
        val expected = fallback?.select(URI("https://api.example.com")) ?: listOf(Proxy.NO_PROXY)
        assertEquals(expected, selector.select(URI("https://api.example.com")))
    }

    @Test
    fun hasEnvProxyConfigurationDetectsVariables() {
        assertFalse(hasEnvProxyConfiguration(emptyMap()))
        assertTrue(hasEnvProxyConfiguration(mapOf("HTTP_PROXY" to "http://proxy:80")))
        assertTrue(hasEnvProxyConfiguration(mapOf("https_proxy" to "http://proxy:80")))
        assertTrue(hasEnvProxyConfiguration(mapOf("ALL_PROXY" to "http://proxy:80")))
    }

    private fun transport(client: OkHttpClient = testClient()): HttpTransport = okHttpTransport(client)

    private fun testClient(): OkHttpClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
}
