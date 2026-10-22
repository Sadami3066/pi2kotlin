package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val OPENROUTER_AUTHORIZE_URL = "https://openrouter.ai/auth"
private const val OPENROUTER_TOKEN_URL = "https://openrouter.ai/api/v1/auth/keys"
private const val OPENROUTER_LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
private const val OPENROUTER_TOKEN_EXCHANGE_TIMEOUT_MS = 30_000L

class OpenRouterOAuth : OAuthAuth {
    override val id: String = "openrouter"
    override val name: String = "OpenRouter OAuth"
    override val loginLabel: String = "Sign in with OpenRouter"

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential = loginOpenRouter(interaction, transport)

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = credential

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val openRouterOAuth: OAuthAuth = OpenRouterOAuth()

private fun parseOpenRouterAuthorizationInput(input: String): String? {
    val value = input.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("http://") || value.startsWith("https://")) {
        val query = value.substringAfter('?', "").substringBefore('#')
        return parseUrlEncoded(query)["code"]
    }
    if (value.contains("code=")) {
        return parseUrlEncoded(value)["code"]
    }
    return value
}

private fun openRouterErrorDetail(body: JsonObject): String? {
    (body["error_description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
    (body["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
    val error = body["error"]
    if (error is JsonPrimitive && error.isString) return error.content
    if (error is JsonObject) {
        (error["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
    }
    return null
}

private suspend fun exchangeOpenRouterCode(
    code: String,
    verifier: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
    val response = try {
        oauthPostJson(
            transport,
            OPENROUTER_TOKEN_URL,
            buildJsonObject {
                put("code", code)
                put("code_verifier", verifier)
                put("code_challenge_method", "S256")
            },
            signal,
            timeoutMs = OPENROUTER_TOKEN_EXCHANGE_TIMEOUT_MS,
        )
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        val message = error.message.orEmpty()
        if (message.contains("timeout", ignoreCase = true) || message.contains("timed out", ignoreCase = true)) {
            throw IllegalStateException("OpenRouter OAuth token exchange timed out")
        }
        throw error
    }

    val body = response.jsonObject()
    if (body == null && response.isSuccessful) {
        throw IllegalStateException("OpenRouter OAuth returned invalid JSON")
    }
    if (!response.isSuccessful) {
        val detail = body?.let(::openRouterErrorDetail)
        val suffix = if (detail.isNullOrEmpty()) "" else ": $detail"
        throw IllegalStateException("OpenRouter OAuth key exchange failed (HTTP ${response.status})$suffix")
    }
    val bodyObject = body ?: JsonObject(emptyMap())
    val key = (bodyObject["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (key.isNullOrEmpty()) throw IllegalStateException("OpenRouter OAuth response carries no \"key\"")
    return OAuthCredential(
        refresh = "",
        access = key,
        expires = Long.MAX_VALUE,
    )
}

private fun randomUuid(): String {
    val bytes = oauthRandomBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append("0123456789abcdef"[value shr 4])
            append("0123456789abcdef"[value and 0x0F])
        }
    }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20, 32)}"
}

private suspend fun loginOpenRouter(
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val pkce = generatePkce()
    val callbackHost = AuthEnvironment.env("PI_OAUTH_CALLBACK_HOST") ?: "127.0.0.1"
    val factory = OAuthRuntime.callbackServerFactory
        ?: throw IllegalStateException("OAuth callback server is not available on this platform")
    val callback = factory.start(
        OAuthCallbackServerOptions(
            providerName = "OpenRouter",
            host = callbackHost,
            port = 0,
            path = "/oauth/callback/${randomUuid()}",
            state = null,
            signal = interaction.signal,
            timeoutMs = OPENROUTER_LOGIN_TIMEOUT_MS,
            complete = { request -> exchangeOpenRouterCode(request.code, pkce.verifier, transport, interaction.signal) },
        ),
    )
    try {
        val authorizeUrl = buildUrl(
            OPENROUTER_AUTHORIZE_URL,
            listOf(
                "callback_url" to callback.redirectUri,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
            ),
        )
        interaction.showMessage("Listening for OpenRouter OAuth callback on ${callback.redirectUri}")
        interaction.openUrl(
            authorizeUrl,
            "Complete sign-in in your browser. If the browser is on another machine, paste the final redirect URL here.",
        )

        val outcome = waitForCallbackOrManualInput(
            interaction = interaction,
            callback = callback,
            message = "Complete sign-in in your browser, or paste the authorization code / redirect URL here:",
            placeholder = callback.redirectUri,
        )
        if (outcome is OAuthCallbackOutcome.Callback) return outcome.value
        val input = (outcome as OAuthCallbackOutcome.Manual).input
        val code = parseOpenRouterAuthorizationInput(input)
        if (code.isNullOrEmpty()) throw IllegalStateException("Missing authorization code")
        interaction.showMessage("Exchanging authorization code for an API key...")
        return exchangeOpenRouterCode(code, pkce.verifier, transport, interaction.signal)
    } finally {
        callback.close()
    }
}
