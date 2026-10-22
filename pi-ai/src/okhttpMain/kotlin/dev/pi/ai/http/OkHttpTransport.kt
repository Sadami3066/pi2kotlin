package dev.pi.ai.http

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

fun okHttpTransport(): HttpTransport = okHttpTransport(defaultClient())

fun okHttpTransport(client: OkHttpClient): HttpTransport = OkHttpTransport(client)

fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(DEFAULT_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    .proxySelector(EnvProxySelector())
    .build()

private const val DEFAULT_CALL_TIMEOUT_MS = 600_000L

internal const val UNSUPPORTED_PROXY_PROTOCOL_MESSAGE =
    "Unsupported proxy protocol. SOCKS and PAC proxy URLs are not supported; use an HTTP or HTTPS proxy URL."

internal class EnvProxySelector(
    private val env: Map<String, String> = System.getenv(),
    private val fallback: ProxySelector? = ProxySelector.getDefault(),
) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> {
        if (hasEnvProxyConfiguration(env)) {
            return listOf(resolveProxy(uri.toString(), env) ?: Proxy.NO_PROXY)
        }
        return fallback?.select(uri) ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI, socketAddress: SocketAddress, error: IOException) {
        fallback?.connectFailed(uri, socketAddress, error)
    }
}

internal fun hasEnvProxyConfiguration(env: Map<String, String>): Boolean =
    envValue("http_proxy", env).isNotEmpty() ||
        envValue("https_proxy", env).isNotEmpty() ||
        envValue("all_proxy", env).isNotEmpty()

internal fun resolveProxy(targetUrl: String, env: Map<String, String>): Proxy? {
    val target = targetUrl.toHttpUrlOrNull() ?: return null
    val protocol = target.scheme
    if (!shouldProxyHostname(stripBrackets(target.host), target.port, env)) return null
    var proxy = envValue("${protocol}_proxy", env)
    if (proxy.isEmpty()) proxy = envValue("all_proxy", env)
    if (proxy.isEmpty()) return null
    if (!proxy.contains("://")) proxy = "$protocol://$proxy"
    val proxyUri = try {
        URI(proxy)
    } catch (error: URISyntaxException) {
        throw HttpTransportException("Invalid proxy URL \"$proxy\": ${error.message}")
    }
    val proxyScheme = proxyUri.scheme
    if (proxyScheme != "http" && proxyScheme != "https") {
        throw HttpTransportException("$UNSUPPORTED_PROXY_PROTOCOL_MESSAGE Got $proxyScheme:")
    }
    val proxyHost = proxyUri.host ?: throw HttpTransportException("Invalid proxy URL \"$proxy\": missing host")
    val proxyPort = when {
        proxyUri.port != -1 -> proxyUri.port
        proxyScheme == "https" -> 443
        else -> 80
    }
    return Proxy(Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
}

private fun envValue(key: String, env: Map<String, String>): String {
    val lowercase = env[key.lowercase()]
    if (!lowercase.isNullOrEmpty()) return lowercase
    return env[key.uppercase()].orEmpty()
}

private fun shouldProxyHostname(hostname: String, port: Int, env: Map<String, String>): Boolean {
    val noProxy = envValue("no_proxy", env).lowercase()
    if (noProxy.isEmpty()) return true
    if (noProxy == "*") return false
    val targetHost = stripBrackets(hostname.lowercase())
    return noProxy.split(NO_PROXY_SEPARATOR).all { entry ->
        val parsed = parseNoProxyEntry(entry) ?: return@all true
        if (parsed.second != 0 && parsed.second != port) return@all true
        var domain = stripBrackets(parsed.first)
        if (domain.startsWith("*.")) {
            domain = domain.substring(2)
        } else if (domain.startsWith(".") || domain.startsWith("*")) {
            domain = domain.substring(1)
        }
        if (domain.isEmpty()) return@all true
        if (targetHost == domain) return@all false
        if (targetHost.endsWith(".$domain")) return@all false
        true
    }
}

private val NO_PROXY_SEPARATOR = Regex("[,\\s]")

private fun parseNoProxyEntry(entry: String): Pair<String, Int>? {
    val trimmed = entry.trim().lowercase()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("[")) {
        val closingBracket = trimmed.indexOf(']')
        if (closingBracket != -1) {
            val host = trimmed.substring(1, closingBracket)
            val rest = trimmed.substring(closingBracket + 1)
            if (rest.startsWith(":")) {
                return host to (rest.substring(1).toIntOrNull() ?: 0)
            }
            return host to 0
        }
    }
    if (trimmed.count { it == ':' } > 2) return trimmed to 0
    val colonIndex = trimmed.lastIndexOf(':')
    if (colonIndex != -1 && colonIndex == trimmed.indexOf(':')) {
        val port = trimmed.substring(colonIndex + 1).toIntOrNull()
        if (port != null) return trimmed.substring(0, colonIndex) to port
    }
    return trimmed to 0
}

private fun stripBrackets(host: String): String =
    if (host.startsWith("[") && host.endsWith("]")) host.substring(1, host.length - 1) else host

private class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val signal = request.signal
        signal?.throwIfAborted()
        currentCoroutineContext().ensureActive()
        val call = clientFor(request.timeoutMs).newCall(buildRequest(request))
        signal?.onAbort { call.cancel() }
        val response = try {
            awaitResponse(call, signal)
        } catch (error: Throwable) {
            call.cancel()
            throw error
        }
        if (signal != null && signal.isAborted) {
            response.close()
            throw RequestAbortedException(signal.reason)
        }
        return HttpResponse(
            status = response.code,
            headers = response.headers.toHeaderMap(),
            body = response.body.source(),
        )
    }

    private fun clientFor(timeoutMs: Long?): OkHttpClient =
        if (timeoutMs == null) client else client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    private suspend fun awaitResponse(call: Call, signal: RunSignal?): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    val failure = if (signal?.isAborted == true) {
                        RequestAbortedException(signal.reason)
                    } else {
                        HttpTransportException(e.message ?: e.javaClass.simpleName, e)
                    }
                    continuation.resumeWithException(failure)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }
}

private fun buildRequest(request: HttpRequest): Request {
    val url = request.url.toHttpUrlOrNull() ?: throw HttpTransportException("Invalid URL: ${request.url}")
    val method = request.method.uppercase()
    val bytes = request.body
    val body: RequestBody? = when {
        bytes != null -> bytes.toRequestBody(null)
        method == "GET" || method == "HEAD" -> null
        else -> ByteArray(0).toRequestBody(null)
    }
    val builder = Request.Builder().url(url)
    for ((name, value) in request.headers) builder.header(name, value)
    return builder.method(method, body).build()
}

private fun Headers.toHeaderMap(): Map<String, String> {
    val result = LinkedHashMap<String, String>(size)
    for ((name, value) in this) result[name.lowercase()] = value
    return result
}
