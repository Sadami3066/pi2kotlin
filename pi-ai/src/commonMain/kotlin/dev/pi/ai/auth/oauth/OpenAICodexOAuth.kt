package dev.pi.ai.auth.oauth

import dev.pi.ai.PiJson
import dev.pi.ai.RunSignal
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

private const val OPENAI_CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
private const val OPENAI_CODEX_AUTH_BASE_URL = "https://auth.openai.com"
private const val OPENAI_CODEX_AUTHORIZE_URL = "$OPENAI_CODEX_AUTH_BASE_URL/oauth/authorize"
private const val OPENAI_CODEX_TOKEN_URL = "$OPENAI_CODEX_AUTH_BASE_URL/oauth/token"
private const val OPENAI_CODEX_REDIRECT_URI = "http://localhost:1455/auth/callback"
private const val OPENAI_CODEX_DEVICE_USER_CODE_URL = "$OPENAI_CODEX_AUTH_BASE_URL/api/accounts/deviceauth/usercode"
private const val OPENAI_CODEX_DEVICE_TOKEN_URL = "$OPENAI_CODEX_AUTH_BASE_URL/api/accounts/deviceauth/token"
private const val OPENAI_CODEX_DEVICE_VERIFICATION_URI = "$OPENAI_CODEX_AUTH_BASE_URL/codex/device"
private const val OPENAI_CODEX_DEVICE_REDIRECT_URI = "$OPENAI_CODEX_AUTH_BASE_URL/deviceauth/callback"
private const val OPENAI_CODEX_DEVICE_CODE_TIMEOUT_SECONDS = 15L * 60
private const val OPENAI_CODEX_BROWSER_LOGIN_METHOD = "browser"
private const val OPENAI_CODEX_DEVICE_CODE_LOGIN_METHOD = "device_code"
private const val OPENAI_CODEX_SCOPE = "openid profile email offline_access"
private const val OPENAI_CODEX_JWT_CLAIM_PATH = "https://api.openai.com/auth"
private const val OPENAI_CODEX_CALLBACK_PORT = 1455
private const val OPENAI_CODEX_CALLBACK_PATH = "/auth/callback"

class OpenAICodexOAuth : OAuthAuth {
    override val id: String = "openai-codex"
    override val name: String = "OpenAI (ChatGPT Plus/Pro)"
    override val isSubscription: Boolean = true

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential {
        val method = interaction.select(
            "Select OpenAI Codex login method:",
            listOf(
                OAuthSelectOption(OPENAI_CODEX_BROWSER_LOGIN_METHOD, "Browser login (default)"),
                OAuthSelectOption(OPENAI_CODEX_DEVICE_CODE_LOGIN_METHOD, "Device code login (headless)"),
            ),
        )
        return when (method) {
            OPENAI_CODEX_DEVICE_CODE_LOGIN_METHOD -> loginDeviceCode(interaction, transport)
            OPENAI_CODEX_BROWSER_LOGIN_METHOD -> loginBrowser(interaction, transport, options)
            else -> throw IllegalStateException("Unknown OpenAI Codex login method: $method")
        }
    }

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = credentialsFromToken(refreshCodexAccessToken(credential.refresh, transport, signal))

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val openAICodexOAuth: OAuthAuth = OpenAICodexOAuth()

fun OAuthCredential.openAICodexAccountId(): String? =
    (extra["accountId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

private class OpenAICodexToken(
    val access: String,
    val refresh: String,
    val expires: Long,
)

private class OpenAICodexDeviceAuth(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Long,
)

private class OpenAICodexDeviceToken(
    val authorizationCode: String,
    val codeVerifier: String,
)

private fun openAICodexCallbackHost(): String =
    AuthEnvironment.env("PI_OAUTH_CALLBACK_HOST") ?: "127.0.0.1"

private fun createCodexState(): String {
    val bytes = oauthRandomBytes(16)
    return buildString {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append("0123456789abcdef"[value shr 4])
            append("0123456789abcdef"[value and 0x0F])
        }
    }
}

private class CodexAuthorizationInput(val code: String?, val state: String?)

private fun parseCodexAuthorizationInput(input: String): CodexAuthorizationInput {
    val value = input.trim()
    if (value.isEmpty()) return CodexAuthorizationInput(null, null)
    if (value.startsWith("http://") || value.startsWith("https://")) {
        val query = value.substringAfter('?', "").substringBefore('#')
        val params = parseUrlEncoded(query)
        return CodexAuthorizationInput(params["code"], params["state"])
    }
    if (value.contains("#")) {
        val parts = value.split("#", limit = 2)
        return CodexAuthorizationInput(parts[0], parts.getOrNull(1))
    }
    if (value.contains("code=")) {
        val params = parseUrlEncoded(value)
        return CodexAuthorizationInput(params["code"], params["state"])
    }
    return CodexAuthorizationInput(value, null)
}

private fun base64UrlDecode(value: String): ByteArray? {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val output = ArrayList<Byte>(value.length * 3 / 4)
    var buffer = 0
    var bits = 0
    for (char in value) {
        if (char == '=') break
        val index = alphabet.indexOf(char)
        if (index == -1) return null
        buffer = (buffer shl 6) or index
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return ByteArray(output.size) { output[it] }
}

private fun decodeJwtPayload(token: String): JsonObject? {
    val parts = token.split(".")
    if (parts.size != 3) return null
    val decoded = base64UrlDecode(parts[1]) ?: return null
    return runCatching { PiJson.parseToJsonElement(decoded.decodeToString()) as? JsonObject }.getOrNull()
}

private fun getCodexAccountId(accessToken: String): String? {
    val payload = decodeJwtPayload(accessToken) ?: return null
    val auth = payload[OPENAI_CODEX_JWT_CLAIM_PATH] as? JsonObject ?: return null
    val accountId = (auth["chatgpt_account_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return accountId?.takeIf { it.isNotEmpty() }
}

private fun credentialsFromToken(token: OpenAICodexToken): OAuthCredential {
    val accountId = getCodexAccountId(token.access)
        ?: throw IllegalStateException("Failed to extract accountId from token")
    return OAuthCredential(
        refresh = token.refresh,
        access = token.access,
        expires = token.expires,
        extra = mapOf("accountId" to JsonPrimitive(accountId)),
    )
}

private fun readCodexTokenResponse(response: OAuthHttpResponse, operation: String): OpenAICodexToken {
    if (!response.isSuccessful) {
        val text = response.body.ifEmpty { "HTTP ${response.status}" }
        throw IllegalStateException("OpenAI Codex token $operation failed (${response.status}): $text")
    }
    val json = response.jsonObject()
    val access = (json?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val refresh = (json?.get("refresh_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val expiresIn = (json?.get("expires_in") as? JsonPrimitive)?.doubleOrNull
    if (access.isNullOrEmpty() || refresh.isNullOrEmpty() || expiresIn == null) {
        throw IllegalStateException(
            "OpenAI Codex token $operation response missing fields: ${json ?: response.body}",
        )
    }
    return OpenAICodexToken(
        access = access,
        refresh = refresh,
        expires = oauthNowMillis() + expiresIn.toLong() * 1000,
    )
}

private suspend fun exchangeCodexAuthorizationCode(
    code: String,
    verifier: String,
    redirectUri: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val response = try {
        oauthPostForm(
            transport,
            OPENAI_CODEX_TOKEN_URL,
            listOf(
                "grant_type" to "authorization_code",
                "client_id" to OPENAI_CODEX_CLIENT_ID,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectUri,
            ),
            signal,
        )
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        throw error
    }
    return credentialsFromToken(readCodexTokenResponse(response, "exchange"))
}

private suspend fun refreshCodexAccessToken(
    refreshToken: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OpenAICodexToken {
    val response = try {
        oauthPostForm(
            transport,
            OPENAI_CODEX_TOKEN_URL,
            listOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to OPENAI_CODEX_CLIENT_ID,
            ),
            signal,
        )
    } catch (error: Throwable) {
        throw IllegalStateException("OpenAI Codex token refresh error: ${error.message ?: error.toString()}")
    }
    return readCodexTokenResponse(response, "refresh")
}

private suspend fun startCodexDeviceAuth(
    transport: HttpTransport,
    signal: RunSignal?,
): OpenAICodexDeviceAuth {
    val body = buildJsonObject { put("client_id", OPENAI_CODEX_CLIENT_ID) }
    val response = try {
        oauthPostJson(transport, OPENAI_CODEX_DEVICE_USER_CODE_URL, body, signal)
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        throw error
    }
    if (!response.isSuccessful) {
        if (response.status == 404) {
            throw IllegalStateException(
                "OpenAI Codex device code login is not enabled for this server. Use browser login or verify the server URL.",
            )
        }
        val suffix = if (response.body.isEmpty()) "" else ": ${response.body}"
        throw IllegalStateException("OpenAI Codex device code request failed with status ${response.status}$suffix")
    }
    val json = response.jsonObject()
    val deviceAuthId = (json?.get("device_auth_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val userCode = (json?.get("user_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val intervalRaw = json?.get("interval")
    val intervalDouble = (intervalRaw as? JsonPrimitive)?.let { primitive ->
        if (primitive.isString) primitive.content.trim().toDoubleOrNull() else primitive.doubleOrNull
    }
    if (
        deviceAuthId.isNullOrEmpty() ||
        userCode.isNullOrEmpty() ||
        intervalDouble == null ||
        !intervalDouble.isFinite() ||
        intervalDouble < 0
    ) {
        throw IllegalStateException("Invalid OpenAI Codex device code response: ${json ?: response.body}")
    }
    return OpenAICodexDeviceAuth(deviceAuthId, userCode, intervalDouble.toLong())
}

private suspend fun pollCodexDeviceAuth(
    device: OpenAICodexDeviceAuth,
    transport: HttpTransport,
    signal: RunSignal?,
): OpenAICodexDeviceToken = pollOAuthDeviceCodeFlow(
    intervalSeconds = device.intervalSeconds,
    expiresInSeconds = OPENAI_CODEX_DEVICE_CODE_TIMEOUT_SECONDS,
    signal = signal,
    poll = poll@{
        val body = buildJsonObject {
            put("device_auth_id", device.deviceAuthId)
            put("user_code", device.userCode)
        }
        val response = try {
            oauthPostJson(transport, OPENAI_CODEX_DEVICE_TOKEN_URL, body, signal)
        } catch (error: Throwable) {
            if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
            throw error
        }
        if (response.isSuccessful) {
            val json = response.jsonObject()
            val authorizationCode = (json?.get("authorization_code") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            val codeVerifier = (json?.get("code_verifier") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (authorizationCode.isNullOrEmpty() || codeVerifier.isNullOrEmpty()) {
                return@poll DeviceCodePollResult.Failed(
                    "Invalid OpenAI Codex device auth token response: ${json ?: response.body}",
                )
            }
            return@poll DeviceCodePollResult.Complete(OpenAICodexDeviceToken(authorizationCode, codeVerifier))
        }
        if (response.status == 403 || response.status == 404) {
            return@poll DeviceCodePollResult.Pending
        }
        val errorCode = parseCodexDeviceErrorCode(response.body)
        when (errorCode) {
            "deviceauth_authorization_pending" -> DeviceCodePollResult.Pending
            "slow_down" -> DeviceCodePollResult.SlowDown()
            else -> {
                val suffix = if (response.body.isEmpty()) "" else ": ${response.body}"
                DeviceCodePollResult.Failed(
                    "OpenAI Codex device auth failed with status ${response.status}$suffix",
                )
            }
        }
    },
)

private fun parseCodexDeviceErrorCode(body: String): String? {
    val json = runCatching { PiJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
    val error = json["error"]
    return when (error) {
        is JsonPrimitive -> if (error.isString) error.content else null
        is JsonObject -> (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        else -> null
    }
}

private suspend fun createCodexAuthorizationFlow(originator: String): Triple<String, String, String> {
    val pkce = generatePkce()
    val state = createCodexState()
    val url = buildUrl(
        OPENAI_CODEX_AUTHORIZE_URL,
        listOf(
            "response_type" to "code",
            "client_id" to OPENAI_CODEX_CLIENT_ID,
            "redirect_uri" to OPENAI_CODEX_REDIRECT_URI,
            "scope" to OPENAI_CODEX_SCOPE,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "id_token_add_organizations" to "true",
            "codex_cli_simplified_flow" to "true",
            "originator" to originator,
        ),
    )
    return Triple(pkce.verifier, state, url)
}

private suspend fun loginBrowser(
    interaction: OAuthInteraction,
    transport: HttpTransport,
    options: OAuthLoginOptions?,
): OAuthCredential {
    val (verifier, state, url) = createCodexAuthorizationFlow(options?.agentName ?: "pi")
    val callback = startCodexCallbackServer(interaction, state)
    interaction.openUrl(url, "A browser window should open. Complete login to finish.")
    try {
        val outcome = waitForCallbackOrManualInput(
            interaction = interaction,
            callback = callback,
            message = "Complete login in your browser, or paste the authorization code / redirect URL here:",
            placeholder = OPENAI_CODEX_REDIRECT_URI,
        )
        val code = when (outcome) {
            is OAuthCallbackOutcome.Callback -> outcome.value
            is OAuthCallbackOutcome.Manual -> {
                val parsed = parseCodexAuthorizationInput(outcome.input)
                if (parsed.state != null && parsed.state != state) throw IllegalStateException("State mismatch")
                parsed.code
            }
        }
        if (code.isNullOrEmpty()) throw IllegalStateException("Missing authorization code")
        return exchangeCodexAuthorizationCode(code, verifier, OPENAI_CODEX_REDIRECT_URI, transport, interaction.signal)
    } finally {
        callback?.close()
    }
}

private suspend fun startCodexCallbackServer(
    interaction: OAuthInteraction,
    state: String,
): OAuthCallbackServer<String>? {
    val factory = OAuthRuntime.callbackServerFactory ?: return null
    return runCatching {
        factory.start(
            OAuthCallbackServerOptions(
                providerName = "OpenAI",
                host = openAICodexCallbackHost(),
                port = OPENAI_CODEX_CALLBACK_PORT,
                path = OPENAI_CODEX_CALLBACK_PATH,
                state = state,
                signal = interaction.signal,
                complete = { request -> request.code },
            ),
        )
    }.getOrNull()
}

private suspend fun loginDeviceCode(
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val device = startCodexDeviceAuth(transport, interaction.signal)
    interaction.openUrl(
        OPENAI_CODEX_DEVICE_VERIFICATION_URI,
        "Enter code ${device.userCode} to sign in.",
    )
    interaction.showMessage("Waiting for OpenAI Codex device authorization...")
    val code = pollCodexDeviceAuth(device, transport, interaction.signal)
    return exchangeCodexAuthorizationCode(
        code.authorizationCode,
        code.codeVerifier,
        OPENAI_CODEX_DEVICE_REDIRECT_URI,
        transport,
        interaction.signal,
    )
}
