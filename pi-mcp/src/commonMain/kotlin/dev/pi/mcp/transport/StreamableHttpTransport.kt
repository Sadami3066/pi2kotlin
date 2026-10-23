package dev.pi.mcp.transport

import dev.pi.ai.RunSignal
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.mcp.McpAuthRequiredException
import dev.pi.mcp.McpConnectionClosedException
import dev.pi.mcp.McpException
import dev.pi.mcp.McpHttpException
import dev.pi.mcp.McpProtocolException
import dev.pi.mcp.McpSessionExpiredException
import dev.pi.mcp.McpSseEventTooLargeException
import dev.pi.mcp.McpTransport
import dev.pi.mcp.McpTransportEvent
import dev.pi.mcp.oauth.McpAuthProvider
import dev.pi.mcp.oauth.McpUnauthorizedContext
import dev.pi.mcp.protocol.DEFAULT_MAX_SSE_EVENT_BYTES
import dev.pi.mcp.protocol.JsonRpcCodec
import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.consumeSseStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

private const val MAX_ERROR_BODY_BYTES = 8 * 1024
private const val ERROR_MESSAGE_BODY_CHARS = 500
private const val DEFAULT_RECONNECT_INITIAL_DELAY_MS = 1_000L
private const val DEFAULT_RECONNECT_MAX_DELAY_MS = 30_000L
private const val DEFAULT_RECONNECT_MAX_RETRIES = 5
private const val INTERNAL_ERROR_CODE = -32603

data class StreamableHttpReconnectOptions(
    val initialDelayMs: Long = DEFAULT_RECONNECT_INITIAL_DELAY_MS,
    val maxDelayMs: Long = DEFAULT_RECONNECT_MAX_DELAY_MS,
    val maxRetries: Int = DEFAULT_RECONNECT_MAX_RETRIES,
)

data class StreamableHttpTransportOptions(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val authProvider: McpAuthProvider? = null,
    val openGetStream: Boolean = true,
    val maxMessageBytes: Int = DEFAULT_MAX_SSE_EVENT_BYTES,
    val requestTimeoutMs: Long? = null,
    val reconnect: StreamableHttpReconnectOptions = StreamableHttpReconnectOptions(),
)

class StreamableHttpTransport(
    private val http: HttpTransport,
    private val options: StreamableHttpTransportOptions,
) : McpTransport {
    constructor(
        http: HttpTransport,
        url: String,
        headers: Map<String, String> = emptyMap(),
        openGetStream: Boolean = true,
    ) : this(
        http,
        StreamableHttpTransportOptions(url = url, headers = headers, openGetStream = openGetStream),
    )

    private val eventChannel = Channel<McpTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<McpTransportEvent> = eventChannel.receiveAsFlow()
    private val closeSignal = RunSignal()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    @kotlin.concurrent.Volatile
    private var started = false
    @kotlin.concurrent.Volatile
    private var closed = false
    @kotlin.concurrent.Volatile
    private var sessionIdValue: String? = null
    @kotlin.concurrent.Volatile
    private var protocolVersion: String? = null
    private var getStreamJob: Job? = null
    @kotlin.concurrent.Volatile
    private var activeGetSignal: RunSignal? = null
    private var getStreamStarted = false
    private var closedEmitted = false

    val sessionId: String?
        get() = sessionIdValue

    override suspend fun start() {
        synchronized(stateLock) {
            if (closed) throw McpConnectionClosedException()
            if (started) throw McpProtocolException("MCP Streamable HTTP transport already started")
            started = true
        }
    }

    fun setProtocolVersion(version: String) {
        protocolVersion = version
    }

    override suspend fun send(message: JsonRpcMessage, signal: RunSignal?) {
        synchronized(stateLock) {
            if (!started) throw McpConnectionClosedException()
            if (closed) throw McpConnectionClosedException()
        }
        val body = JsonRpcCodec.encode(message).encodeToByteArray()
        val cancellation = createRequestSignal(signal)
        val response = try {
            executeAuthorized(
                method = "POST",
                headers = mapOf(
                    "accept" to "application/json, text/event-stream",
                    "content-type" to "application/json",
                ),
                body = body,
                cancellation = cancellation,
            )
        } catch (error: Throwable) {
            cancellation.close()
            throw error
        }
        try {
            checkResponse(response)
            captureSession(response)
        } catch (error: Throwable) {
            response.close()
            cancellation.close()
            throw error
        }

        if (message !is JsonRpcRequest) {
            response.close()
            cancellation.close()
            if (message is JsonRpcNotification && message.method == "notifications/initialized") {
                startGetStreamInternal(signal)
            }
            return
        }

        try {
            if (response.status == 202 || response.status == 204) {
                throw McpHttpException(
                    response.status,
                    "",
                    "MCP server accepted request ${message.method} without a response",
                )
            }
            when (contentType(response)) {
                "application/json" -> {
                    val text = try {
                        response.body.readUtf8()
                    } catch (error: Throwable) {
                        throw HttpTransportException(error.message ?: "MCP response read failed", error)
                    }
                    val messages = try {
                        JsonRpcCodec.decodeBatch(text)
                    } catch (error: McpProtocolException) {
                        throw McpProtocolException("Invalid JSON-RPC response body: ${error.message}", error)
                    }
                    for (item in messages) emitMessage(item)
                }

                "text/event-stream" -> {
                    scope.launch(start = CoroutineStart.ATOMIC) { consumeResponseStream(response, message.id, cancellation) }
                    return
                }

                else -> throw McpHttpException(
                    response.status,
                    "",
                    "Unsupported MCP response content type: ${contentType(response) ?: "missing"}",
                )
            }
        } finally {
            if (contentType(response) != "text/event-stream") {
                response.close()
                cancellation.close()
            }
        }
    }

    private suspend fun executeAuthorized(
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        cancellation: RequestCancellation,
    ): HttpResponse {
        var attempt = 0
        while (true) {
            val token = options.authProvider?.token()
            val withAuth = if (token != null && headers.keys.none { it.equals("authorization", true) }) {
                headers + ("authorization" to "Bearer $token")
            } else {
                headers
            }
            val response = http.execute(
                HttpRequest(
                    method = method,
                    url = options.url,
                    headers = buildHeaders(withAuth),
                    body = body,
                    timeoutMs = options.requestTimeoutMs,
                    signal = cancellation.signal,
                ),
            )
            val challenge = response.status == 401 ||
                (response.status == 403 && asksForScope(response.header("www-authenticate")))
            val provider = options.authProvider
            if (challenge && provider != null && attempt == 0) {
                val header = response.header("www-authenticate")
                val responseHeaders = response.headers
                runCatching { response.body.readUtf8() }
                response.close()
                provider.onUnauthorized(
                    McpUnauthorizedContext(
                        serverUrl = options.url,
                        status = response.status,
                        headers = responseHeaders,
                        token = token,
                    ),
                )
                attempt++
                continue
            }
            return response
        }
    }

    suspend fun startGetStream(signal: RunSignal? = null) {
        startGetStreamInternal(signal)
    }

    suspend fun stopGetStream() {
        val job = synchronized(stateLock) {
            getStreamStarted = false
            val current = getStreamJob
            getStreamJob = null
            current
        }
        activeGetSignal?.abort("server-to-client stream stopped")
        job?.cancel()
        job?.join()
    }

    override suspend fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        closeSignal.abort("transport closed")
        stopGetStream()
        if (started && sessionIdValue != null) {
            runCatching {
                withTimeoutOrNull(1_000) {
                    val response = http.execute(
                        HttpRequest(
                            method = "DELETE",
                            url = options.url,
                            headers = buildHeaders(emptyMap()),
                            body = null,
                            timeoutMs = 1_000,
                            signal = RunSignal(),
                        ),
                    )
                    response.close()
                }
            }
        }
        emitClosed(null)
        scope.cancel()
    }

    private fun startGetStreamInternal(signal: RunSignal?) {
        if (!options.openGetStream || closed) return
        synchronized(stateLock) {
            if (getStreamStarted) return
            getStreamStarted = true
        }
        getStreamJob = scope.launch { runGetStream(signal) }
    }

    private suspend fun consumeResponseStream(
        response: HttpResponse,
        requestId: dev.pi.mcp.protocol.JsonRpcId,
        cancellation: RequestCancellation,
    ) {
        var answered = false
        var failure: Throwable? = null
        try {
            consumeSseStream(
                source = response.body,
                maxEventBytes = options.maxMessageBytes,
                onEvent = { event ->
                val data = event.data.trim()
                if (data.isEmpty() || (event.event != null && event.event != "message")) {
                    return@consumeSseStream
                }
                val message = try {
                    JsonRpcCodec.decode(data)
                } catch (_: McpProtocolException) {
                    return@consumeSseStream
                }
                if ((message is JsonRpcResponse && message.id == requestId) ||
                    (message is JsonRpcErrorResponse && message.id == requestId)
                ) {
                    answered = true
                }
                emitMessage(message)
                },
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            response.close()
            cancellation.close()
        }
        if (answered || closed) return
        val reason = failure?.message ?: "stream ended without a response"
        emitMessage(
            JsonRpcErrorResponse(
                id = requestId,
                code = INTERNAL_ERROR_CODE,
                message = "MCP response stream failed: $reason",
            ),
        )
    }

    private suspend fun runGetStream(externalSignal: RunSignal?) {
        val stop = RunSignal()
        val cleanups = mutableListOf<() -> Unit>()
        cleanups.add(closeSignal.onAbort { stop.abort("transport closed") })
        if (externalSignal != null) cleanups.add(externalSignal.onAbort { stop.abort(externalSignal.reason) })
        activeGetSignal = stop
        try {
            runGetStreamLoop(stop)
        } finally {
            activeGetSignal = null
            cleanups.forEach { it() }
        }
    }

    private suspend fun runGetStreamLoop(stop: RunSignal) {
        val cursor = StreamCursor()
        var attempt = 0
        while (!closed) {
            var receivedThisStream = false
            val openedAt = TimeSource.Monotonic.markNow()
            var stream: GetStream? = null
            try {
                stream = openGetStream(cursor.lastEventId, stop) ?: return
                val getResponse = stream.response
                consumeSseStream(
                    source = getResponse.body,
                    maxEventBytes = options.maxMessageBytes,
                    onId = { cursor.lastEventId = it },
                    onRetry = { cursor.retryMs = it },
                    onEvent = { event ->
                    val data = event.data.trim()
                    if (data.isEmpty() || (event.event != null && event.event != "message")) return@consumeSseStream
                    receivedThisStream = true
                    val message = try {
                        JsonRpcCodec.decode(data)
                    } catch (_: McpProtocolException) {
                        return@consumeSseStream
                    }
                    emitMessage(message)
                    },
                )
            } catch (error: Throwable) {
                if (closed) return
                if (!isRetryable(error)) {
                    emitClosed(error)
                    return
                }
            } finally {
                stream?.let {
                    it.response.close()
                    it.cancellation.close()
                }
            }
            if (closed) return
            if (receivedThisStream || openedAt.elapsedNow().inWholeMilliseconds > options.reconnect.maxDelayMs) {
                attempt = 0
            }
            if (attempt >= options.reconnect.maxRetries) {
                emitClosed(McpException("MCP server-to-client stream dropped and could not be reopened"))
                return
            }
            val delayMs = cursor.retryMs ?: reconnectDelay(attempt)
            if (!sleepUnlessClosed(delayMs, stop)) return
            attempt += 1
        }
    }

    private suspend fun openGetStream(lastEventId: String?, externalSignal: RunSignal?): GetStream? {
        val cancellation = createRequestSignal(externalSignal)
        val extraHeaders = LinkedHashMap<String, String>()
        extraHeaders["accept"] = "text/event-stream"
        if (lastEventId != null) extraHeaders["last-event-id"] = lastEventId
        val response = try {
            http.execute(
                HttpRequest(
                    method = "GET",
                    url = options.url,
                    headers = buildHeaders(extraHeaders),
                    body = null,
                    timeoutMs = options.requestTimeoutMs,
                    signal = cancellation.signal,
                ),
            )
        } catch (error: Throwable) {
            cancellation.close()
            throw error
        }
        if (response.status == 405) {
            response.close()
            cancellation.close()
            return null
        }
        try {
            checkResponse(response)
            captureSession(response)
        } catch (error: Throwable) {
            response.close()
            cancellation.close()
            throw error
        }
        val type = contentType(response)
        if (type != "text/event-stream") {
            response.close()
            cancellation.close()
            throw McpHttpException(
                response.status,
                "",
                "Unsupported MCP GET response content type: ${type ?: "missing"}",
            )
        }
        return GetStream(response, cancellation)
    }

    private fun reconnectDelay(attempt: Int): Long {
        val exponential = options.reconnect.initialDelayMs * (1L shl attempt.coerceAtMost(16))
        return minOf(exponential, options.reconnect.maxDelayMs)
    }

    private suspend fun sleepUnlessClosed(delayMs: Long, externalSignal: RunSignal?): Boolean {
        if (closed || closeSignal.isAborted) return false
        if (externalSignal?.isAborted == true) return false
        val aborted = CompletableDeferred<Unit>()
        val cleanups = mutableListOf<() -> Unit>()
        cleanups.add(closeSignal.onAbort { aborted.complete(Unit) })
        if (externalSignal != null) cleanups.add(externalSignal.onAbort { aborted.complete(Unit) })
        return try {
            val interrupted = withTimeoutOrNull(delayMs) { aborted.await() }
            interrupted == null
        } finally {
            cleanups.forEach { it() }
        }
    }

    private fun createRequestSignal(externalSignal: RunSignal?): RequestCancellation {
        val signal = RunSignal()
        val cleanups = mutableListOf<() -> Unit>()
        cleanups.add(closeSignal.onAbort { signal.abort("transport closed") })
        if (externalSignal != null) cleanups.add(externalSignal.onAbort { signal.abort(externalSignal.reason) })
        if (closeSignal.isAborted) signal.abort("transport closed")
        return RequestCancellation(signal) { cleanups.forEach { it() } }
    }

    private fun buildHeaders(extra: Map<String, String>): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for ((name, value) in options.headers) headers[name] = value
        for ((name, value) in extra) {
            headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach { headers.remove(it) }
            headers[name] = value
        }
        sessionIdValue?.let {
            headers.keys.filter { key -> key.equals("Mcp-Session-Id", ignoreCase = true) }.forEach(headers::remove)
            headers["Mcp-Session-Id"] = it
        }
        protocolVersion?.let {
            headers.keys.filter { key -> key.equals("MCP-Protocol-Version", ignoreCase = true) }.forEach(headers::remove)
            headers["MCP-Protocol-Version"] = it
        }
        return headers
    }

    private fun captureSession(response: HttpResponse) {
        val sessionId = response.header("mcp-session-id")
        if (!sessionId.isNullOrEmpty()) sessionIdValue = sessionId
    }

    private suspend fun checkResponse(response: HttpResponse) {
        if (response.isSuccessful()) return
        val rawBody = try {
            response.body.readUtf8()
        } catch (_: Throwable) {
            ""
        }
        val status = response.status
        val wwwAuthenticate = response.header("www-authenticate")
        response.close()
        val body = if (rawBody.length > MAX_ERROR_BODY_BYTES) rawBody.substring(0, MAX_ERROR_BODY_BYTES) else rawBody
        when {
            status == 401 -> throw McpAuthRequiredException(wwwAuthenticate, options.url)
            status == 403 && asksForScope(wwwAuthenticate) -> throw McpAuthRequiredException(wwwAuthenticate, options.url)
            status == 404 && sessionIdValue != null -> throw McpSessionExpiredException(body)
            else -> throw McpHttpException(status, body, describeHttpFailure(status, body))
        }
    }

    private fun asksForScope(wwwAuthenticate: String?): Boolean {
        if (wwwAuthenticate == null) return false
        return INSUFFICIENT_SCOPE_PATTERN.containsMatchIn(wwwAuthenticate)
    }

    private fun describeHttpFailure(status: Int, body: String): String {
        val text = body.trim()
        val snippet = if (text.length > ERROR_MESSAGE_BODY_CHARS) {
            text.substring(0, ERROR_MESSAGE_BODY_CHARS - 3) + "..."
        } else {
            text
        }
        return "MCP HTTP request failed with status $status" + if (snippet.isNotEmpty()) ": $snippet" else ""
    }

    private fun isRetryable(error: Throwable): Boolean = when (error) {
        is McpHttpException -> error.status == 408 || error.status == 429 || error.status >= 500
        is McpAuthRequiredException, is McpSessionExpiredException, is McpSseEventTooLargeException -> false
        else -> true
    }

    private fun contentType(response: HttpResponse): String? =
        response.header("content-type")?.substringBefore(';')?.trim()?.lowercase()

    private fun emitMessage(message: JsonRpcMessage) {
        eventChannel.trySend(McpTransportEvent.Message(message))
    }

    private fun emitClosed(error: Throwable?) {
        val shouldEmit = synchronized(stateLock) {
            if (closedEmitted) return
            closedEmitted = true
            true
        }
        if (shouldEmit) {
            eventChannel.trySend(McpTransportEvent.Closed(error))
            eventChannel.close()
        }
    }

    private class StreamCursor(
        var lastEventId: String? = null,
        var retryMs: Long? = null,
    )

    private class GetStream(
        val response: HttpResponse,
        val cancellation: RequestCancellation,
    )

    private class RequestCancellation(
        val signal: RunSignal,
        private val onClose: () -> Unit,
    ) {
        fun close() {
            onClose()
        }
    }

    private companion object {
        val INSUFFICIENT_SCOPE_PATTERN = Regex("(?:^|[\\s,])error=\"?insufficient_scope\"?", RegexOption.IGNORE_CASE)
    }
}
