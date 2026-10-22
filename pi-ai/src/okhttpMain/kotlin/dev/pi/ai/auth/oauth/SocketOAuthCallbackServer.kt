package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SocketOAuthCallbackServerFactory : OAuthCallbackServerFactory {
    override suspend fun <T> start(options: OAuthCallbackServerOptions<T>): OAuthCallbackServer<T> =
        withContext(Dispatchers.IO) { SocketOAuthCallbackServer(options) }
}

fun socketOAuthCallbackServerFactory(): OAuthCallbackServerFactory = SocketOAuthCallbackServerFactory()

private class SocketOAuthCallbackServer<T>(
    private val options: OAuthCallbackServerOptions<T>,
) : OAuthCallbackServer<T> {
    private val signal: RunSignal? = options.signal
    private val completion = CompletableDeferred<T?>()
    private val settled = AtomicBoolean(false)
    private val claimed = AtomicBoolean(false)
    private val serverSocket = ServerSocket()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timeoutJob: Job? = null
    private var removeAbortListener: (() -> Unit)? = null

    override val redirectUri: String

    init {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(options.host, options.port))
        val redirectHost = options.redirectHost ?: options.host
        val renderedHost = if (redirectHost.contains(":")) "[$redirectHost]" else redirectHost
        redirectUri = "http://$renderedHost:${serverSocket.localPort}${options.path}"

        removeAbortListener = signal?.onAbort {
            finish(Result.failure(IllegalStateException(OAUTH_CANCEL_MESSAGE)))
        }
        timeoutJob = options.timeoutMs?.let { timeoutMs ->
            scope.launch {
                delay(timeoutMs)
                finish(Result.failure(IllegalStateException("${options.providerName} sign-in timed out")))
            }
        }
        scope.launch { acceptLoop() }
    }

    override suspend fun wait(): T? = completion.await()

    override fun cancel() {
        if (!claimed.get()) finish(Result.success(null))
    }

    override fun close() {
        finish(Result.failure(IllegalStateException("OAuth callback server closed")))
        runCatching { serverSocket.close() }
    }

    private fun finish(result: Result<T?>) {
        if (!settled.compareAndSet(false, true)) return
        timeoutJob?.cancel()
        removeAbortListener?.invoke()
        val error = result.exceptionOrNull()
        if (error != null) {
            completion.completeExceptionally(error)
        } else {
            completion.complete(result.getOrNull())
        }
    }

    private suspend fun acceptLoop() {
        while (!settled.get()) {
            val client = try {
                serverSocket.accept()
            } catch (error: Throwable) {
                if (settled.get()) return
                continue
            }
            scope.launch { handleConnection(client) }
        }
    }

    private suspend fun handleConnection(client: Socket) {
        client.use { socket ->
            try {
                val input = socket.getInputStream()
                val requestLine = readRequestLine(input) ?: return
                while (true) {
                    val header = readRequestLine(input) ?: break
                    if (header.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    sendPage(socket, 404, oauthErrorHtml("Callback route not found."))
                    return
                }
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = if (target.contains('?')) target.substringAfter('?') else ""
                if (path != options.path) {
                    sendPage(socket, 404, oauthErrorHtml("Callback route not found."))
                    return
                }
                val params = parseQuery(query)
                if (options.state != null && params["state"] != options.state) {
                    sendPage(socket, 400, oauthErrorHtml("State mismatch."))
                    return
                }
                if (claimed.get() || settled.get()) {
                    sendPage(socket, 409, oauthErrorHtml("This sign-in has already been handled."))
                    return
                }
                val error = params["error"]
                if (!error.isNullOrEmpty()) {
                    val description = params["error_description"] ?: error
                    sendPage(
                        socket,
                        400,
                        oauthErrorHtml("${options.providerName} authorization failed.", description),
                    )
                    finish(
                        Result.failure(
                            IllegalStateException("${options.providerName} authorization failed: $description"),
                        ),
                    )
                    return
                }
                val code = params["code"]
                if (code.isNullOrEmpty()) {
                    sendPage(socket, 400, oauthErrorHtml("Missing authorization code."))
                    return
                }
                claimed.set(true)
                try {
                    val value = options.complete(OAuthCallbackRequest(code, params["state"], params))
                    sendPage(
                        socket,
                        200,
                        oauthSuccessHtml("Signed in to ${options.providerName}. You may now close this page."),
                    )
                    finish(Result.success(value))
                } catch (failure: Throwable) {
                    val message = failure.message ?: failure.toString()
                    sendPage(
                        socket,
                        502,
                        oauthErrorHtml("${options.providerName} sign-in failed.", message),
                    )
                    finish(Result.failure(failure))
                }
            } catch (failure: Throwable) {
                finish(Result.failure(failure))
            }
        }
    }

    private fun sendPage(socket: Socket, status: Int, html: String) {
        val body = html.encodeToByteArray()
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            409 -> "Conflict"
            502 -> "Bad Gateway"
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
}

private fun readRequestLine(input: InputStream): String? {
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
        val decodedName = runCatching {
            URLDecoder.decode(name, StandardCharsets.UTF_8.name())
        }.getOrDefault(name)
        val decodedValue = runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
        result[decodedName] = decodedValue
    }
    return result
}

private const val OAUTH_LOGO_SVG =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 800\" aria-hidden=\"true\"><path fill=\"#F09082\" d=\"M165.29 165.29H517.36V400H400V282.65H165.29Z\"/><path fill=\"#4D9ABF\" d=\"M165.29 282.65H282.65V400H400V517.36H282.65V634.72H165.29Z\"/><path fill=\"#F1BE58\" d=\"M517.36 400H634.72V634.72H517.36Z\"/></svg>"

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun renderOAuthPage(title: String, heading: String, message: String, details: String?): String {
    val escapedTitle = escapeHtml(title)
    val escapedHeading = escapeHtml(heading)
    val escapedMessage = escapeHtml(message)
    val escapedDetails = details?.let(::escapeHtml)
    val detailsHtml = if (escapedDetails == null) "" else "<div class=\"details\">$escapedDetails</div>"
    return """<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>$escapedTitle</title>
  <style>
    :root {
      --text: #fafafa;
      --text-dim: #a1a1aa;
      --page-bg: #09090b;
      --font-sans: ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif;
      --font-mono: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
    }
    * { box-sizing: border-box; }
    html { color-scheme: dark; }
    body {
      margin: 0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      background: var(--page-bg);
      color: var(--text);
      font-family: var(--font-sans);
      text-align: center;
    }
    main {
      width: 100%;
      max-width: 560px;
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
    }
    .logo {
      width: 72px;
      height: 72px;
      display: block;
      margin-bottom: 24px;
    }
    h1 {
      margin: 0 0 10px;
      font-size: 28px;
      line-height: 1.15;
      font-weight: 650;
      color: var(--text);
    }
    p {
      margin: 0;
      line-height: 1.7;
      color: var(--text-dim);
      font-size: 15px;
    }
    .details {
      margin-top: 16px;
      font-family: var(--font-mono);
      font-size: 13px;
      color: var(--text-dim);
      white-space: pre-wrap;
      word-break: break-word;
    }
  </style>
</head>
<body>
  <main>
    <div class="logo">$OAUTH_LOGO_SVG</div>
    <h1>$escapedHeading</h1>
    <p>$escapedMessage</p>
    $detailsHtml
  </main>
</body>
</html>"""
}

private fun oauthSuccessHtml(message: String): String = renderOAuthPage(
    title = "Authentication successful",
    heading = "Authentication successful",
    message = message,
    details = null,
)

private fun oauthErrorHtml(message: String, details: String? = null): String = renderOAuthPage(
    title = "Authentication failed",
    heading = "Authentication failed",
    message = message,
    details = details,
)
