package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal class OAuthHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
) {
    val isSuccessful: Boolean get() = status in 200..299

    fun json(): JsonElement? = parseJsonOrNull(body)

    fun jsonObject(): JsonObject? = json() as? JsonObject
}

internal suspend fun oauthHttpRequest(
    transport: HttpTransport,
    request: HttpRequest,
): OAuthHttpResponse {
    val response = transport.execute(request)
    return try {
        val text = response.body.readUtf8()
        OAuthHttpResponse(response.status, text, response.headers)
    } finally {
        response.close()
    }
}

internal suspend fun oauthPostJson(
    transport: HttpTransport,
    url: String,
    body: JsonObject,
    signal: RunSignal? = null,
    timeoutMs: Long = 30_000,
): OAuthHttpResponse = oauthHttpRequest(
    transport,
    HttpRequest(
        method = "POST",
        url = url,
        headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
        body = body.toString().encodeToByteArray(),
        timeoutMs = timeoutMs,
        signal = signal,
    ),
)

internal suspend fun oauthPostForm(
    transport: HttpTransport,
    url: String,
    form: List<Pair<String, String>>,
    signal: RunSignal? = null,
    timeoutMs: Long = 30_000,
    extraHeaders: Map<String, String> = emptyMap(),
): OAuthHttpResponse = oauthHttpRequest(
    transport,
    HttpRequest(
        method = "POST",
        url = url,
        headers = buildMap {
            put("Accept", "application/json")
            put("Content-Type", "application/x-www-form-urlencoded")
            putAll(extraHeaders)
        },
        body = encodeForm(form).encodeToByteArray(),
        timeoutMs = timeoutMs,
        signal = signal,
    ),
)

internal suspend fun oauthGetJson(
    transport: HttpTransport,
    url: String,
    headers: Map<String, String> = emptyMap(),
    signal: RunSignal? = null,
    timeoutMs: Long = 30_000,
): OAuthHttpResponse = oauthHttpRequest(
    transport,
    HttpRequest(
        method = "GET",
        url = url,
        headers = buildMap {
            put("Accept", "application/json")
            putAll(headers)
        },
        timeoutMs = timeoutMs,
        signal = signal,
    ),
)

internal fun encodeForm(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }

internal fun formEncode(value: String): String {
    val output = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '*' || char == '-' || char == '.' || char == '_') {
            output.append(char)
        } else if (char == ' ') {
            output.append('+')
        } else {
            output.append('%')
            output.append(HEX_UPPER[code shr 4])
            output.append(HEX_UPPER[code and 0x0F])
        }
    }
    return output.toString()
}

internal fun buildUrl(base: String, params: List<Pair<String, String>>): String =
    base + "?" + encodeForm(params)

internal fun parseUrlEncoded(input: String): Map<String, String> {
    if (input.isEmpty()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (part in input.split("&")) {
        if (part.isEmpty()) continue
        val name = part.substringBefore('=')
        val value = if (part.contains('=')) part.substringAfter('=') else ""
        result[percentDecode(name)] = percentDecode(value)
    }
    return result
}

internal fun percentDecode(value: String): String {
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '+' -> {
                bytes += ' '.code.toByte()
                index++
            }

            char == '%' && index + 2 < value.length -> {
                val hex = value.substring(index + 1, index + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded == null) {
                    bytes += char.code.toByte()
                    index++
                } else {
                    bytes += decoded.toByte()
                    index += 3
                }
            }

            else -> {
                bytes += value[index].code.toByte()
                index++
            }
        }
    }
    val array = ByteArray(bytes.size) { bytes[it] }
    return array.decodeToString()
}

private const val HEX_UPPER = "0123456789ABCDEF"

internal fun formatErrorDetails(error: Throwable): String {
    val details = mutableListOf("${error::class.simpleName}: ${error.message}")
    val cause = error.cause
    if (cause != null) {
        details += "cause=${formatErrorDetails(cause)}"
    }
    return details.joinToString("; ")
}

internal fun JsonObject.requiredString(name: String, message: String): String {
    val value = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (value.isNullOrEmpty()) throw IllegalStateException(message)
    return value
}

internal fun JsonObject.requiredLong(name: String, message: String): Long {
    val value = (this[name] as? JsonPrimitive)?.longOrNull
    if (value == null) throw IllegalStateException(message)
    return value
}

internal fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.longOrNull(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.stringArrayOrNull(name: String): List<String>? {
    val array = this[name] as? JsonArray ?: return null
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
