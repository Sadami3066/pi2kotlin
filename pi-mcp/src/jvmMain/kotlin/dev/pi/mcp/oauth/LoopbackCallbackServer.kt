package dev.pi.mcp.oauth

import dev.pi.mcp.McpTimeoutException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class LoopbackCallbackServer(private val port: Int? = null) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val callback = CompletableDeferred<String>()
    private val handled = AtomicBoolean(false)

    @Volatile
    private var redirectUri: String? = null

    suspend fun start(): String = withContext(Dispatchers.IO) {
        if (serverSocket != null) throw IllegalStateException("OAuth callback server already started")
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(HOST, port ?: 0))
        serverSocket = socket
        val uri = "http://$HOST:${socket.localPort}$PATH"
        redirectUri = uri
        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope
        serverScope.launch { acceptLoop(socket) }
        uri
    }

    suspend fun awaitCallback(
        timeoutMs: Long,
        stateValidator: (String?) -> Boolean = { true },
    ): String {
        result = stateValidator
        val value = withTimeoutOrNull(timeoutMs) { callback.await() }
            ?: throw McpTimeoutException("MCP OAuth callback timed out")
        return value
    }

    fun close() {
        callback.completeExceptionally(IllegalStateException("OAuth callback server closed"))
        runCatching { serverSocket?.close() }
        scope?.cancel()
    }

    private var result: ((String?) -> Boolean)? = null

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (error: Throwable) {
                return
            }
            scope?.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(client: Socket) {
        client.use { socket ->
            val input = socket.getInputStream()
            val requestLine = readLine(input) ?: return
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
            }
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "GET") {
                sendPage(socket, 404, failurePage("Not found", null))
                return
            }
            val target = parts[1]
            val path = target.substringBefore('?')
            if (path != PATH) {
                sendPage(socket, 404, failurePage("Not found", null))
                return
            }
            if (handled.get()) {
                sendPage(socket, 409, failurePage("This sign-in has already been handled.", null))
                return
            }
            val params = parseQuery(if (target.contains('?')) target.substringAfter('?') else "")
            val validator = result ?: { _ -> true }
            if (!validator(params["state"])) {
                sendPage(socket, 400, failurePage("Invalid or expired OAuth state", null))
                return
            }
            val error = params["error"]
            if (!error.isNullOrEmpty()) {
                handled.set(true)
                val description = params["error_description"] ?: error
                sendPage(socket, 200, failurePage("Authorization failed. You may close this window.", description))
                callback.completeExceptionally(OAuthFlowException(description))
                return
            }
            val code = params["code"]
            if (code.isNullOrEmpty()) {
                sendPage(socket, 400, failurePage("Missing authorization code", null))
                return
            }
            if (!handled.compareAndSet(false, true)) {
                sendPage(socket, 409, failurePage("This sign-in has already been handled.", null))
                return
            }
            sendPage(socket, 200, successPage())
            callback.complete(target)
        }
    }

    private fun sendPage(socket: Socket, status: Int, html: String) {
        val body = html.encodeToByteArray()
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = socket.getOutputStream()
        output.write(head.encodeToByteArray())
        output.write(body)
        output.flush()
    }

    companion object {
        private const val HOST = "127.0.0.1"
        private const val PATH = "/callback"
    }
}

private fun readLine(input: InputStream): String? {
    val line = StringBuilder()
    while (true) {
        val byte = input.read()
        if (byte == -1) return if (line.isEmpty()) null else line.toString()
        if (byte == '\n'.code) {
            if (line.isNotEmpty() && line[line.length - 1] == '\r') {
                line.setLength(line.length - 1)
            }
            return line.toString()
        }
        line.append(byte.toChar())
    }
}

private fun parseQuery(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (part in query.split("&")) {
        if (part.isEmpty()) continue
        val name = part.substringBefore('=')
        val value = if (part.contains('=')) part.substringAfter('=') else ""
        result[decode(name)] = decode(value)
    }
    return result
}

private fun decode(value: String): String = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrDefault(value)

private fun successPage(): String = page(
    title = "Authorization complete",
    heading = "Authorization complete",
    message = "You may close this window.",
    details = null,
)

private fun failurePage(message: String, details: String?): String = page(
    title = "Authorization failed",
    heading = "Authorization failed",
    message = message,
    details = details,
)

private fun page(title: String, heading: String, message: String, details: String?): String {
    val escapedDetails = details?.let(::escapeHtml)?.let { "<p class=\"details\">$it</p>" }.orEmpty()
    return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${escapeHtml(title)}</title>
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #09090b;
      color: #fafafa;
      font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
      text-align: center;
    }
    main { max-width: 560px; padding: 24px; }
    h1 { margin: 0 0 10px; font-size: 28px; line-height: 1.15; }
    p { margin: 0; line-height: 1.7; color: #a1a1aa; font-size: 15px; }
    .details { margin-top: 16px; font-family: ui-monospace, monospace; font-size: 13px; white-space: pre-wrap; word-break: break-word; }
  </style>
</head>
<body>
  <main>
    <h1>${escapeHtml(heading)}</h1>
    <p>${escapeHtml(message)}</p>
    $escapedDetails
  </main>
</body>
</html>"""
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")
