package dev.pi.mcp.oauth

import dev.pi.mcp.McpTimeoutException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopbackCallbackServerTest {
    private suspend fun httpGet(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun servesCallbackAndReturnsRequestUrl() = oauthTest {
        val server = LoopbackCallbackServer()
        val redirectUri = server.start()
        try {
            assertTrue(redirectUri.startsWith("http://127.0.0.1:"))
            assertTrue(redirectUri.endsWith("/callback"))
            val pending = async { server.awaitCallback(5_000, { state -> state == "state-1" }) }
            delay(100)
            val (status, body) = httpGet("$redirectUri?code=the-code&state=state-1")
            assertEquals(200, status)
            assertContains(body, "Authorization complete")
            val callbackUrl = pending.await()
            assertContains(callbackUrl, "code=the-code")
            assertContains(callbackUrl, "state=state-1")
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsWrongStateAndAcceptsLaterValidCallback() = oauthTest {
        val server = LoopbackCallbackServer(port = null)
        val redirectUri = server.start()
        try {
            val pending = async { server.awaitCallback(5_000, { state -> state == "good-state" }) }
            delay(100)
            val (wrongStatus, wrongBody) = httpGet("$redirectUri?code=the-code&state=bad-state")
            assertEquals(400, wrongStatus)
            assertContains(wrongBody, "Invalid or expired OAuth state")
            val (status, _) = httpGet("$redirectUri?code=the-code&state=good-state")
            assertEquals(200, status)
            assertEquals("the-code", parseUrlQuery(pending.await())["code"])
        } finally {
            server.close()
        }
    }

    @Test
    fun reportsAuthorizationErrorFromCallback() = oauthTest {
        val server = LoopbackCallbackServer()
        val redirectUri = server.start()
        try {
            delay(100)
            val (status, body) = httpGet("$redirectUri?error=access_denied&error_description=user+denied&state=x")
            assertEquals(200, status)
            assertContains(body, "Authorization failed")
            assertContains(body, "user denied")
            val error = assertFailsWith<OAuthFlowException> { server.awaitCallback(5_000) }
            assertEquals("user denied", error.message)
        } finally {
            server.close()
        }
    }

    @Test
    fun timesOutWithoutCallback() = oauthTest {
        val server = LoopbackCallbackServer()
        server.start()
        try {
            val error = assertFailsWith<McpTimeoutException> {
                server.awaitCallback(100)
            }
            assertTrue(error.message!!.contains("timed out"))
        } finally {
            server.close()
        }
    }

    @Test
    fun closingServerFailsPendingWait() = oauthTest {
        val server = LoopbackCallbackServer()
        server.start()
        delay(100)
        server.close()
        val error = assertFailsWith<IllegalStateException> { server.awaitCallback(5_000) }
        assertContains(error.message!!, "closed")
    }

    @Test
    fun returnsNotFoundForUnknownPath() = oauthTest {
        val server = LoopbackCallbackServer()
        val redirectUri = server.start()
        try {
            val unknown = redirectUri.replace("/callback", "/other")
            val (status, body) = httpGet("$unknown?code=x&state=y")
            assertEquals(404, status)
            assertContains(body, "Not found")
        } finally {
            server.close()
        }
    }

    @Test
    fun handlesOnlyOneCallbackPerServer() = oauthTest {
        val server = LoopbackCallbackServer()
        val redirectUri = server.start()
        try {
            val pending = async { server.awaitCallback(5_000) }
            delay(100)
            httpGet("$redirectUri?code=first-code&state=s")
            assertEquals("first-code", parseUrlQuery(pending.await())["code"])
            val (status, body) = httpGet("$redirectUri?code=second-code&state=s")
            assertEquals(409, status)
            assertContains(body, "already been handled")
        } finally {
            server.close()
        }
    }
}

private fun oauthTest(block: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
    withTimeout(20_000) { coroutineScope { block() } }
}
