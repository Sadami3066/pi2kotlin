package dev.pi.ai.auth.oauth

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallbackServerTest {
    private val factory = SocketOAuthCallbackServerFactory()

    private suspend fun startServer(
        providerName: String = "TestProvider",
        state: String? = "expected-state",
        timeoutMs: Long? = null,
        complete: suspend (OAuthCallbackRequest) -> String = { request -> request.code },
    ): OAuthCallbackServer<String> = factory.start(
        OAuthCallbackServerOptions(
            providerName = providerName,
            host = "127.0.0.1",
            port = 0,
            path = "/callback",
            state = state,
            timeoutMs = timeoutMs,
            complete = complete,
        ),
    )

    private suspend fun httpGet(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to body
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun bindsRandomPortAndReturnsRedirectUri() = runBlocking {
        val server = startServer()
        try {
            val uri = server.redirectUri
            assertTrue(uri.startsWith("http://127.0.0.1:"), uri)
            assertTrue(uri.endsWith("/callback"), uri)
            val port = uri.removePrefix("http://127.0.0.1:").substringBefore('/').toInt()
            assertTrue(port > 0)
        } finally {
            server.close()
        }
    }

    @Test
    fun completesSignInAndRendersSuccessPage() = runBlocking {
        val server = startServer()
        try {
            val (status, body) = httpGet("${server.redirectUri}?code=the-code&state=expected-state")
            assertEquals(200, status)
            assertTrue(body.contains("Authentication successful"), body.take(500))
            assertTrue(body.contains("Signed in to TestProvider."), body.take(500))
            assertEquals("the-code", server.wait())
        } finally {
            server.close()
        }
    }

    @Test
    fun passesFullCallbackRequestToComplete() = runBlocking {
        var seen: OAuthCallbackRequest? = null
        val server = startServer(
            complete = { request ->
                seen = request
                "value-${request.code}"
            },
        )
        try {
            httpGet("${server.redirectUri}?code=code-1&state=expected-state&client_id=issued-client")
            assertEquals("value-code-1", server.wait())
            assertEquals("code-1", seen?.code)
            assertEquals("expected-state", seen?.state)
            assertEquals("issued-client", seen?.params?.get("client_id"))
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsStateMismatch() = runBlocking {
        val server = startServer()
        try {
            val (status, body) = httpGet("${server.redirectUri}?code=the-code&state=wrong-state")
            assertEquals(400, status)
            assertTrue(body.contains("State mismatch."), body.take(500))
            server.cancel()
            assertNull(server.wait())
        } finally {
            server.close()
        }
    }

    @Test
    fun reportsAuthorizationErrors() = runBlocking {
        val server = startServer()
        try {
            val (status, body) = httpGet(
                "${server.redirectUri}?error=access_denied&error_description=User%20denied&state=expected-state",
            )
            assertEquals(400, status)
            assertTrue(body.contains("User denied"), body.take(500))
            val error = assertFailsWith<IllegalStateException> { server.wait() }
            assertEquals("TestProvider authorization failed: User denied", error.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsUnknownRoutes() = runBlocking {
        val server = startServer()
        try {
            val uri = server.redirectUri.replace("/callback", "/other")
            val (status, body) = httpGet("$uri?code=the-code&state=expected-state")
            assertEquals(404, status)
            assertTrue(body.contains("Callback route not found."), body.take(500))
            server.cancel()
            assertNull(server.wait())
        } finally {
            server.close()
        }
    }

    @Test
    fun cancelResolvesWaitWithNull() = runBlocking {
        val server = startServer()
        try {
            server.cancel()
            assertNull(server.wait())
        } finally {
            server.close()
        }
    }

    @Test
    fun timesOutWithoutCallback() = runBlocking {
        val server = startServer(timeoutMs = 200)
        try {
            val error = assertFailsWith<IllegalStateException> { server.wait() }
            assertEquals("TestProvider sign-in timed out", error.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun completeFailuresRenderErrorPage() = runBlocking {
        val server = startServer(
            complete = { throw IllegalStateException("exchange failed") },
        )
        try {
            val (status, body) = httpGet("${server.redirectUri}?code=the-code&state=expected-state")
            assertEquals(502, status)
            assertTrue(body.contains("TestProvider sign-in failed."), body.take(500))
            assertTrue(body.contains("exchange failed"), body.take(500))
            val error = assertFailsWith<IllegalStateException> { server.wait() }
            assertEquals("exchange failed", error.message)
        } finally {
            server.close()
        }
    }
}
