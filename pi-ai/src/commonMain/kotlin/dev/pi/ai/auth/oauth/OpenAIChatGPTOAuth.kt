package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject

private const val OPENAI_DYNAMIC_CLIENT_ID = "dynamic_agent_client"
private const val OPENAI_AGENT_NAME_HINT = "Pi"
private const val OPENAI_AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
private const val OPENAI_TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
private const val OPENAI_RESOURCE = "https://api.openai.com/v1"
private const val OPENAI_CALLBACK_PORT = 1455
private const val OPENAI_CALLBACK_PATH = "/auth/callback"
private const val OPENAI_REDIRECT_URI = "http://127.0.0.1:$OPENAI_CALLBACK_PORT$OPENAI_CALLBACK_PATH"
private const val OPENAI_DIRECT_TOKEN_SCOPE = "chatgpt.tokens.use.direct"
private const val OPENAI_SCOPE = "openid profile email offline_access resource.invoke $OPENAI_DIRECT_TOKEN_SCOPE"
private const val OPENAI_EXPIRY_MARGIN_MS = 3 * 60 * 1000L
private val openAiUuidPattern = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    RegexOption.IGNORE_CASE,
)

class OpenAIChatGPTOAuth : OAuthAuth {
    override val id: String = "openai"
    override val name: String = "OpenAI (ChatGPT subscription)"
    override val isSubscription: Boolean = true
    override val loginLabel: String = "Sign in with ChatGPT"

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential = loginOpenAIChatGPT(interaction, transport, options)

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = refreshOpenAIAccessToken(credential, transport, signal)

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val openaiChatGPTOAuth: OAuthAuth = OpenAIChatGPTOAuth()

private class OpenAIAuthorizationResult(val code: String, val clientId: String)

private fun randomValue(): String = base64UrlEncode(oauthRandomBytes(32))

private fun authorizationResultFromParams(params: Map<String, String>, expectedState: String): OpenAIAuthorizationResult {
    val code = params["code"]
    if (code.isNullOrEmpty()) throw IllegalStateException("Missing authorization code")
    val state = params["state"]
    if (state.isNullOrEmpty()) throw IllegalStateException("Missing OAuth state")
    if (state != expectedState) throw IllegalStateException("OAuth state mismatch")
    val clientId = params["client_id"]?.trim()
    if (clientId.isNullOrEmpty()) {
        throw IllegalStateException("OpenAI OAuth registration callback did not contain an issued client ID")
    }
    return OpenAIAuthorizationResult(code, clientId)
}

private fun authorizationResultFromManualInput(input: String, expectedState: String): OpenAIAuthorizationResult {
    val trimmed = input.trim()
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        throw IllegalStateException("Paste the full callback URL from the browser")
    }
    val base = trimmed.substringBefore('?')
    if (base != OPENAI_REDIRECT_URI) {
        throw IllegalStateException("The pasted callback URL must start with $OPENAI_REDIRECT_URI")
    }
    val params = parseUrlEncoded(trimmed.substringAfter('?', "").substringBefore('#'))
    val error = params["error"]
    if (!error.isNullOrEmpty()) throw IllegalStateException("ChatGPT authorization failed: $error")
    return authorizationResultFromParams(params, expectedState)
}

private suspend fun loginOpenAIChatGPT(
    interaction: OAuthInteraction,
    transport: HttpTransport,
    options: OAuthLoginOptions?,
): OAuthCredential {
    val hostId = agentHostId(options?.deviceId)
    val pkce = generatePkce()
    val state = randomValue()
    val nonce = randomValue()
    val callbackHost = AuthEnvironment.env("PI_OAUTH_CALLBACK_HOST") ?: "127.0.0.1"
    val callback = startOpenAICallbackServer(interaction, callbackHost, state)

    try {
        val authorizationUrl = buildUrl(
            OPENAI_AUTHORIZE_URL,
            listOf(
                "client_id" to OPENAI_DYNAMIC_CLIENT_ID,
                "agent_name_hint" to (options?.agentName ?: OPENAI_AGENT_NAME_HINT),
                "ext_agent_host_id" to hostId,
                "response_type" to "code",
                "redirect_uri" to OPENAI_REDIRECT_URI,
                "resource" to OPENAI_RESOURCE,
                "scope" to OPENAI_SCOPE,
                "state" to state,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "nonce" to nonce,
            ),
        )
        interaction.openUrl(
            authorizationUrl,
            "Complete sign-in in your browser. If the callback does not complete, paste the final redirect URL here.",
        )

        val outcome = waitForCallbackOrManualInput(
            interaction = interaction,
            callback = callback,
            message = "Complete login in your browser, or paste the final redirect URL here:",
            placeholder = OPENAI_REDIRECT_URI,
        )
        val result = when (outcome) {
            is OAuthCallbackOutcome.Callback -> outcome.value
            is OAuthCallbackOutcome.Manual -> authorizationResultFromManualInput(outcome.input, state)
        }
        interaction.showMessage("Exchanging authorization code for tokens...")
        return exchangeOpenAIAuthorizationCode(result.code, pkce.verifier, result.clientId, transport, interaction.signal)
    } finally {
        callback?.close()
    }
}

private suspend fun startOpenAICallbackServer(
    interaction: OAuthInteraction,
    host: String,
    state: String,
): OAuthCallbackServer<OpenAIAuthorizationResult>? {
    val factory = OAuthRuntime.callbackServerFactory ?: return null
    return try {
        factory.start(
            OAuthCallbackServerOptions(
                providerName = "OpenAI",
                host = host,
                port = OPENAI_CALLBACK_PORT,
                path = OPENAI_CALLBACK_PATH,
                redirectHost = "127.0.0.1",
                state = state,
                signal = interaction.signal,
                complete = { request -> authorizationResultFromParams(request.params, state) },
            ),
        )
    } catch (error: Throwable) {
        throw IllegalStateException(
            "Port $OPENAI_CALLBACK_PORT is in use, probably by an unfinished login in another pi session or by the Codex CLI. Cancel that login and try again.",
        )
    }
}

private fun agentHostId(deviceId: String?): String {
    if (deviceId.isNullOrEmpty() || !openAiUuidPattern.matches(deviceId)) {
        throw IllegalStateException("Sign in with ChatGPT requires a device ID (UUID) for this installation")
    }
    return "urn:uuid:${deviceId.lowercase()}"
}

private suspend fun requestOpenAIToken(
    form: List<Pair<String, String>>,
    transport: HttpTransport,
    signal: RunSignal?,
): JsonObject {
    val response = oauthPostForm(transport, OPENAI_TOKEN_URL, form, signal)
    if (!response.isSuccessful) {
        throw IllegalStateException(
            "OpenAI OAuth token request failed (${response.status}): ${response.body.ifEmpty { "HTTP ${response.status}" }}",
        )
    }
    return response.jsonObject() ?: throw IllegalStateException("OpenAI OAuth token response must be an object")
}

private fun credentialFromOpenAITokenResponse(token: JsonObject, clientId: String): OAuthCredential {
    val access = token.requiredString("access_token", "OpenAI OAuth token response has invalid access_token")
    val refresh = token.requiredString("refresh_token", "OpenAI OAuth token response has invalid refresh_token")
    val scope = token.requiredString("scope", "OpenAI OAuth token response has invalid scope")
    val expiresIn = token.longOrNull("expires_in")
    if (expiresIn == null || expiresIn <= 0) {
        throw IllegalStateException("OpenAI OAuth token response has invalid expires_in")
    }
    val scopes = scope.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (!scopes.contains(OPENAI_DIRECT_TOKEN_SCOPE)) {
        throw IllegalStateException("OpenAI OAuth grant did not include $OPENAI_DIRECT_TOKEN_SCOPE")
    }
    return OAuthCredential(
        refresh = refresh,
        access = access,
        expires = oauthNowMillis() + expiresIn * 1000 - OPENAI_EXPIRY_MARGIN_MS,
        clientId = clientId,
        scopes = scopes,
    )
}

private suspend fun exchangeOpenAIAuthorizationCode(
    code: String,
    verifier: String,
    clientId: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val token = requestOpenAIToken(
        listOf(
            "grant_type" to "authorization_code",
            "client_id" to clientId,
            "code" to code,
            "code_verifier" to verifier,
            "redirect_uri" to OPENAI_REDIRECT_URI,
            "resource" to OPENAI_RESOURCE,
        ),
        transport,
        signal,
    )
    val idToken = token.stringOrNull("id_token")
    if (idToken.isNullOrEmpty()) {
        throw IllegalStateException("OpenAI OAuth token response did not contain an ID token")
    }
    return credentialFromOpenAITokenResponse(token, clientId)
}

private suspend fun refreshOpenAIAccessToken(
    credential: OAuthCredential,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val clientId = credential.clientId
    if (clientId.isNullOrEmpty()) {
        throw IllegalStateException(
            "Stored OpenAI OAuth credential does not contain an issued client ID; reconnect ChatGPT",
        )
    }
    val token = requestOpenAIToken(
        listOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to credential.refresh,
            "resource" to OPENAI_RESOURCE,
        ),
        transport,
        signal,
    )
    return credentialFromOpenAITokenResponse(token, clientId)
}
