package dev.pi.ai.auth.oauth

import dev.pi.ai.PiJson
import dev.pi.ai.RunSignal
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

class FakeHttpResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
)

class FakeHttpTransport(
    private val handler: suspend (HttpRequest) -> FakeHttpResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val response = handler(request)
        return HttpResponse(response.status, response.headers, Buffer().writeUtf8(response.body))
    }
}

fun jsonHttpResponse(body: String, status: Int = 200): FakeHttpResponse =
    FakeHttpResponse(status, mapOf("content-type" to "application/json"), body)

fun HttpRequest.bodyText(): String = body?.decodeToString().orEmpty()

fun HttpRequest.jsonBody(): JsonObject = PiJson.parseToJsonElement(bodyText()) as JsonObject

fun HttpRequest.formBody(): Map<String, String> = parseUrlEncoded(bodyText())

fun HttpRequest.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

fun JsonObject.string(name: String): String = (this[name] ?: error("missing $name")).jsonPrimitive.content

class FakeOAuthInteraction(
    val selectAnswers: MutableList<String> = mutableListOf(),
    val promptAnswers: MutableList<String> = mutableListOf(),
    override val signal: RunSignal? = null,
) : OAuthInteraction {
    val openedUrls = mutableListOf<String>()
    val messages = mutableListOf<String>()
    val selectMessages = mutableListOf<String>()
    val promptMessages = mutableListOf<String>()

    override suspend fun openUrl(url: String, instructions: String?) {
        openedUrls += url
    }

    override suspend fun showMessage(message: String) {
        messages += message
    }

    override suspend fun prompt(message: String, placeholder: String?, secret: Boolean): String {
        promptMessages += message
        return promptAnswers.removeFirstOrNull() ?: error("no prompt answer for: $message")
    }

    override suspend fun select(message: String, options: List<OAuthSelectOption>): String {
        selectMessages += message
        return selectAnswers.removeFirstOrNull() ?: error("no select answer for: $message")
    }
}

fun deterministicRandom(): (Int) -> ByteArray = { count -> ByteArray(count) { 7 } }

fun deterministicVerifier(): String = base64UrlEncode(ByteArray(32) { 7 })

suspend fun <T> withOAuthRuntime(
    randomBytes: ((Int) -> ByteArray)? = null,
    callbackServerFactory: OAuthCallbackServerFactory? = null,
    block: suspend () -> T,
): T {
    val previousRandom = OAuthRuntime.randomBytes
    val previousFactory = OAuthRuntime.callbackServerFactory
    OAuthRuntime.randomBytes = randomBytes
    OAuthRuntime.callbackServerFactory = callbackServerFactory
    try {
        return block()
    } finally {
        OAuthRuntime.randomBytes = previousRandom
        OAuthRuntime.callbackServerFactory = previousFactory
    }
}
