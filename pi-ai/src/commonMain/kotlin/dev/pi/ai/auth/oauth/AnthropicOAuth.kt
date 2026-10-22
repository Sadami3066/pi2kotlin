package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ANTHROPIC_CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
private const val ANTHROPIC_AUTHORIZE_URL = "https://claude.ai/oauth/authorize"
private const val ANTHROPIC_TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
private const val ANTHROPIC_CALLBACK_PORT = 53692
private const val ANTHROPIC_CALLBACK_PATH = "/callback"
private const val ANTHROPIC_REDIRECT_URI = "http://localhost:$ANTHROPIC_CALLBACK_PORT$ANTHROPIC_CALLBACK_PATH"
private const val ANTHROPIC_COPY_CODE_REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"
private const val ANTHROPIC_BROWSER_METHOD = "browser"
private const val ANTHROPIC_COPY_CODE_METHOD = "copy_code"
private const val ANTHROPIC_SCOPES =
    "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
private const val ANTHROPIC_EXPIRY_MARGIN_MS = 5 * 60 * 1000L

class AnthropicOAuth : OAuthAuth {
    override val id: String = "anthropic"
    override val name: String = "Anthropic (Claude Pro/Max)"
    override val isSubscription: Boolean = true

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential {
        val method = interaction.select(
            "Select Anthropic login method:",
            listOf(
                OAuthSelectOption(ANTHROPIC_BROWSER_METHOD, "Browser login (default)"),
                OAuthSelectOption(ANTHROPIC_COPY_CODE_METHOD, "Copy code login (headless)"),
            ),
        )
        return when (method) {
            ANTHROPIC_COPY_CODE_METHOD -> loginCopyCode(interaction, transport)
            ANTHROPIC_BROWSER_METHOD -> loginBrowser(interaction, transport)
            else -> throw IllegalStateException("Unknown Anthropic login method: $method")
        }
    }

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = refreshAnthropicToken(credential.refresh, transport, signal)

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val anthropicOAuth: OAuthAuth = AnthropicOAuth()

private class AuthorizationInput(val code: String?, val state: String?)

private fun parseAuthorizationInput(input: String): AuthorizationInput {
    val value = input.trim()
    if (value.isEmpty()) return AuthorizationInput(null, null)

    if (value.startsWith("http://") || value.startsWith("https://")) {
        val query = value.substringAfter('?', "")
        val params = parseUrlEncoded(query.substringBefore('#'))
        return AuthorizationInput(params["code"], params["state"])
    }

    if (value.contains("#")) {
        val parts = value.split("#", limit = 2)
        return AuthorizationInput(parts[0], parts.getOrNull(1))
    }

    if (value.contains("code=")) {
        val params = parseUrlEncoded(value)
        return AuthorizationInput(params["code"], params["state"])
    }

    return AuthorizationInput(value, null)
}

private suspend fun loginBrowser(
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val pkce = generatePkce()
    val callbackHost = AuthEnvironment.env("PI_OAUTH_CALLBACK_HOST") ?: "127.0.0.1"
    val callback = startAnthropicCallbackServer(interaction, callbackHost, ANTHROPIC_CALLBACK_PORT, pkce.verifier)
        ?: startAnthropicCallbackServer(interaction, callbackHost, 0, pkce.verifier)
    val redirectUri = callback?.redirectUri ?: ANTHROPIC_REDIRECT_URI

    try {
        val authorizationUrl = buildUrl(
            ANTHROPIC_AUTHORIZE_URL,
            listOf(
                "code" to "true",
                "client_id" to ANTHROPIC_CLIENT_ID,
                "response_type" to "code",
                "redirect_uri" to redirectUri,
                "scope" to ANTHROPIC_SCOPES,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "state" to pkce.verifier,
            ),
        )
        interaction.openUrl(
            authorizationUrl,
            "Complete login in your browser. If the browser is on another machine, paste the final redirect URL here.",
        )

        val result = waitForCallbackOrManualInput(
            interaction = interaction,
            callback = callback,
            message = "Complete login in your browser, or paste the authorization code / redirect URL here:",
            placeholder = redirectUri,
        )
        var code: String?
        var state = pkce.verifier
        when (result) {
            is OAuthCallbackOutcome.Callback -> code = result.value
            is OAuthCallbackOutcome.Manual -> {
                val parsed = parseAuthorizationInput(result.input)
                if (parsed.state != null && parsed.state != pkce.verifier) {
                    throw IllegalStateException("OAuth state mismatch")
                }
                code = parsed.code
                state = parsed.state ?: pkce.verifier
            }
        }
        if (code.isNullOrEmpty()) throw IllegalStateException("Missing authorization code")
        interaction.showMessage("Exchanging authorization code for tokens...")
        return exchangeAuthorizationCode(code, state, pkce.verifier, redirectUri, transport, interaction.signal)
    } finally {
        callback?.close()
    }
}

private suspend fun startAnthropicCallbackServer(
    interaction: OAuthInteraction,
    host: String,
    port: Int,
    state: String,
): OAuthCallbackServer<String>? {
    val factory = OAuthRuntime.callbackServerFactory ?: return null
    return runCatching {
        factory.start(
            OAuthCallbackServerOptions(
                providerName = "Anthropic",
                host = host,
                port = port,
                path = ANTHROPIC_CALLBACK_PATH,
                redirectHost = "localhost",
                state = state,
                signal = interaction.signal,
                complete = { request -> request.code },
            ),
        )
    }.getOrNull()
}

private suspend fun loginCopyCode(
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val pkce = generatePkce()
    val authorizationUrl = buildUrl(
        ANTHROPIC_AUTHORIZE_URL,
        listOf(
            "code" to "true",
            "client_id" to ANTHROPIC_CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to ANTHROPIC_COPY_CODE_REDIRECT_URI,
            "scope" to ANTHROPIC_SCOPES,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "state" to pkce.verifier,
        ),
    )
    interaction.openUrl(
        authorizationUrl,
        "Complete login in your browser, then copy the code Anthropic shows and paste it here.",
    )

    val input = interaction.prompt(
        "Paste the code Anthropic shows after you sign in:",
        placeholder = "code#state",
        secret = true,
    )
    val parsed = parseAuthorizationInput(input)
    if (parsed.state != null && parsed.state != pkce.verifier) throw IllegalStateException("OAuth state mismatch")
    val code = parsed.code
    if (code.isNullOrEmpty()) throw IllegalStateException("Missing authorization code")
    interaction.showMessage("Exchanging authorization code for tokens...")
    return exchangeAuthorizationCode(
        code,
        parsed.state ?: pkce.verifier,
        pkce.verifier,
        ANTHROPIC_COPY_CODE_REDIRECT_URI,
        transport,
        interaction.signal,
    )
}

private suspend fun exchangeAuthorizationCode(
    code: String,
    state: String,
    verifier: String,
    redirectUri: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val body = buildJsonObject {
        put("grant_type", "authorization_code")
        put("client_id", ANTHROPIC_CLIENT_ID)
        put("code", code)
        put("state", state)
        put("redirect_uri", redirectUri)
        put("code_verifier", verifier)
    }
    val response = try {
        oauthPostJson(transport, ANTHROPIC_TOKEN_URL, body, signal)
    } catch (error: Throwable) {
        throw IllegalStateException(
            "Token exchange request failed. url=$ANTHROPIC_TOKEN_URL; redirect_uri=$redirectUri; response_type=authorization_code; details=${formatErrorDetails(error)}",
        )
    }
    if (!response.isSuccessful) {
        throw IllegalStateException(
            "HTTP request failed. status=${response.status}; url=$ANTHROPIC_TOKEN_URL; body=${response.body}",
        )
    }
    val json = response.jsonObject()
        ?: throw IllegalStateException(
            "Token exchange returned invalid JSON. url=$ANTHROPIC_TOKEN_URL; body=${response.body}; details=${formatErrorDetails(IllegalStateException("Invalid JSON"))}",
        )
    return credentialFromTokenResponse(json, "Token exchange")
}

private suspend fun refreshAnthropicToken(
    refreshToken: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val body = buildJsonObject {
        put("grant_type", "refresh_token")
        put("client_id", ANTHROPIC_CLIENT_ID)
        put("refresh_token", refreshToken)
    }
    val response = try {
        oauthPostJson(transport, ANTHROPIC_TOKEN_URL, body, signal)
    } catch (error: Throwable) {
        throw IllegalStateException(
            "Anthropic token refresh request failed. url=$ANTHROPIC_TOKEN_URL; details=${formatErrorDetails(error)}",
        )
    }
    if (!response.isSuccessful) {
        throw IllegalStateException(
            "HTTP request failed. status=${response.status}; url=$ANTHROPIC_TOKEN_URL; body=${response.body}",
        )
    }
    val json = response.jsonObject()
        ?: throw IllegalStateException(
            "Anthropic token refresh returned invalid JSON. url=$ANTHROPIC_TOKEN_URL; body=${response.body}; details=${formatErrorDetails(IllegalStateException("Invalid JSON"))}",
        )
    return credentialFromTokenResponse(json, "Anthropic token refresh")
}

private fun credentialFromTokenResponse(json: kotlinx.serialization.json.JsonObject, context: String): OAuthCredential {
    val access = json.requiredString("access_token", "$context response has invalid access_token")
    val refresh = json.requiredString("refresh_token", "$context response has invalid refresh_token")
    val expiresIn = json.requiredLong("expires_in", "$context response has invalid expires_in")
    return OAuthCredential(
        refresh = refresh,
        access = access,
        expires = oauthNowMillis() + expiresIn * 1000 - ANTHROPIC_EXPIRY_MARGIN_MS,
    )
}
