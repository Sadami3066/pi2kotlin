package dev.pi.ai.http

import okio.BufferedSource

class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class HttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: BufferedSource,
) : AutoCloseable {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun isSuccessful(): Boolean = status in 200..299

    override fun close() {
        body.close()
    }
}

interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class HttpTransportException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
