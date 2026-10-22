package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

const val DEFAULT_RADIUS_GATEWAY = "https://radius.pi.dev"

private const val RADIUS_CALLBACK_HOST = "127.0.0.1"
private const val RADIUS_CALLBACK_PORT = 1456
private const val RADIUS_CALLBACK_PATH = "/oauth/callback"
private const val RADIUS_REDIRECT_URI = "http://$RADIUS_CALLBACK_HOST:$RADIUS_CALLBACK_PORT$RADIUS_CALLBACK_PATH"
private const val RADIUS_TOKEN_EXPIRY_SKEW_MS = 60_000L
private const val RADIUS_LOGIN_METHOD_BROWSER = "browser"
private const val RADIUS_LOGIN_METHOD_DEVICE_CODE = "device-code"
private const val RADIUS_OAUTH_CLIENT_ID = "pi-gateway"
private const val RADIUS_OAUTH_SCOPE = "gateway offline_access"
private const val RADIUS_OAUTH_DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

fun normalizeRadiusGatewayUrl(value: String): String {
    val withScheme = if (value.startsWith("https://") || value.startsWith("http://")) value else "https://$value"
    return withScheme.trimEnd('/')
}

fun radiusOAuthFlow(
    gateway: String = DEFAULT_RADIUS_GATEWAY,
    name: String = "Radius",
    id: String = "radius",
): OAuthAuth = createRadiusOAuth(id = id, name = name, gateway = gateway)

val radiusOAuth: OAuthAuth = createRadiusOAuth(id = "radius", name = "Radius", gateway = DEFAULT_RADIUS_GATEWAY)

private class RadiusDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Long?,
    val expiresInSeconds: Long,
)

private class RadiusOAuthResponseError(
    message: String,
    val oauthError: String?,
) : IllegalStateException(message)

private fun radiusPositiveNumber(value: JsonPrimitive?): Long? {
    val number = value?.doubleOrNull ?: return null
    if (!number.isFinite() || number <= 0) return null
    return number.toLong()
}

private fun radiusRandomUuid(): String {
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

private suspend fun loadRadiusOAuthDiscovery(gateway: String, transport: HttpTransport, signal: RunSignal?): String {
    val response = oauthGetJson(transport, "$gateway/v1/oauth", emptyMap(), signal)
    if (!response.isSuccessful) {
        throw IllegalStateException(
            "Could not load Radius OAuth config from $gateway: ${response.status} ${response.body}",
        )
    }
    val json = response.jsonObject()
    val authorizationEndpoint = (json?.get("authorizationEndpoint") as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
    if (authorizationEndpoint.isNullOrEmpty()) {
        throw IllegalStateException("Invalid Radius OAuth config from $gateway")
    }
    return authorizationEndpoint
}

private fun radiusResponseError(response: OAuthHttpResponse, message: String): RadiusOAuthResponseError {
    val json = response.jsonObject()
    val oauthError = (json?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val description = (json?.get("error_description") as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: if (json == null && response.body.isNotEmpty()) response.body else null
    val detail = when {
        oauthError != null && description != null -> "$oauthError: $description"
        oauthError != null -> oauthError
        description != null -> description
        else -> response.status.toString()
    }
    return RadiusOAuthResponseError("$message: $detail", oauthError)
}

private suspend fun requestRadiusOAuthToken(
    gateway: String,
    fields: List<Pair<String, String>>,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val response = try {
        oauthPostForm(transport, "$gateway/v1/oauth/token", fields, signal)
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        throw error
    }
    if (!response.isSuccessful) {
        throw radiusResponseError(response, "Radius OAuth token request failed")
    }
    val json = response.jsonObject()
    val access = (json?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val refresh = (json?.get("refresh_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val expiresIn = (json?.get("expires_in") as? JsonPrimitive)?.doubleOrNull
    if (access.isNullOrEmpty() || refresh.isNullOrEmpty() || expiresIn == null) {
        throw IllegalStateException("Radius OAuth token response is missing required fields")
    }
    val scope = (json["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val extra = if (scope.isNullOrEmpty()) emptyMap() else mapOf("scope" to JsonPrimitive(scope))
    return OAuthCredential(
        refresh = refresh,
        access = access,
        expires = oauthNowMillis() + expiresIn.toLong() * 1000 - RADIUS_TOKEN_EXPIRY_SKEW_MS,
        extra = extra,
    )
}

private suspend fun loginRadiusWithBrowser(
    gateway: String,
    authorizationEndpoint: String,
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val pkce = generatePkce()
    val state = radiusRandomUuid()
    val authorizeUrl = buildUrl(
        authorizationEndpoint,
        listOf(
            "response_type" to "code",
            "client_id" to RADIUS_OAUTH_CLIENT_ID,
            "redirect_uri" to RADIUS_REDIRECT_URI,
            "scope" to RADIUS_OAUTH_SCOPE,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
            "handoff" to "url",
            "state" to state,
        ),
    )
    val factory = OAuthRuntime.callbackServerFactory
        ?: throw IllegalStateException("OAuth callback server is not available on this platform")
    val callback = factory.start(
        OAuthCallbackServerOptions(
            providerName = "Radius",
            host = RADIUS_CALLBACK_HOST,
            port = RADIUS_CALLBACK_PORT,
            path = RADIUS_CALLBACK_PATH,
            state = state,
            signal = interaction.signal,
            complete = { request ->
                requestRadiusOAuthToken(
                    gateway,
                    listOf(
                        "grant_type" to "authorization_code",
                        "client_id" to RADIUS_OAUTH_CLIENT_ID,
                        "redirect_uri" to RADIUS_REDIRECT_URI,
                        "code" to request.code,
                        "code_verifier" to pkce.verifier,
                    ),
                    transport,
                    interaction.signal,
                )
            },
        ),
    )
    interaction.showMessage("Listening for OAuth callback on $RADIUS_REDIRECT_URI")
    interaction.openUrl(authorizeUrl, "Continue in your browser.")
    try {
        return callback.wait() ?: throw IllegalStateException("OAuth callback did not complete.")
    } finally {
        callback.close()
    }
}

private suspend fun requestRadiusDeviceAuthorization(
    gateway: String,
    transport: HttpTransport,
    signal: RunSignal?,
): RadiusDeviceAuthorization {
    val response = try {
        oauthPostForm(
            transport,
            "$gateway/v1/oauth/device",
            listOf("client_id" to RADIUS_OAUTH_CLIENT_ID, "scope" to RADIUS_OAUTH_SCOPE),
            signal,
        )
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        throw error
    }
    if (!response.isSuccessful) {
        throw radiusResponseError(response, "Radius OAuth device authorization failed")
    }
    val json = response.jsonObject()
    val deviceCode = (json?.get("device_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val userCode = (json?.get("user_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val verificationUri = (json?.get("verification_uri") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val expiresIn = (json?.get("expires_in") as? JsonPrimitive)?.doubleOrNull
    if (deviceCode.isNullOrEmpty() || userCode.isNullOrEmpty() || verificationUri.isNullOrEmpty() || expiresIn == null) {
        throw IllegalStateException("Radius OAuth device authorization response is missing required fields")
    }
    return RadiusDeviceAuthorization(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = verificationUri,
        intervalSeconds = radiusPositiveNumber(json["interval"] as? JsonPrimitive),
        expiresInSeconds = expiresIn.toLong(),
    )
}

private suspend fun loginRadiusWithDeviceCode(
    gateway: String,
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val device = requestRadiusDeviceAuthorization(gateway, transport, interaction.signal)
    interaction.openUrl(
        device.verificationUri,
        "Enter code ${device.userCode} to sign in.",
    )
    interaction.showMessage("Waiting for Radius device authorization...")
    return pollOAuthDeviceCodeFlow(
        intervalSeconds = device.intervalSeconds,
        expiresInSeconds = device.expiresInSeconds,
        signal = interaction.signal,
        poll = poll@{
            try {
                DeviceCodePollResult.Complete(
                    requestRadiusOAuthToken(
                        gateway,
                        listOf(
                            "grant_type" to RADIUS_OAUTH_DEVICE_CODE_GRANT_TYPE,
                            "client_id" to RADIUS_OAUTH_CLIENT_ID,
                            "device_code" to device.deviceCode,
                        ),
                        transport,
                        interaction.signal,
                    ),
                )
            } catch (error: RadiusOAuthResponseError) {
                when (error.oauthError) {
                    "authorization_pending" -> DeviceCodePollResult.Pending
                    "slow_down" -> DeviceCodePollResult.SlowDown()
                    "expired_token" -> DeviceCodePollResult.Failed("Device authorization expired.")
                    "access_denied" -> DeviceCodePollResult.Failed("Device authorization was denied.")
                    else -> throw error
                }
            }
        },
    )
}

class RadiusOAuth(
    override val id: String,
    override val name: String,
    gateway: String,
) : OAuthAuth {
    private val gatewayUrl: String = normalizeRadiusGatewayUrl(gateway)

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential {
        val loginMethod = interaction.select(
            "Sign in to $name:",
            listOf(
                OAuthSelectOption(RADIUS_LOGIN_METHOD_BROWSER, "Sign in with browser (recommended)"),
                OAuthSelectOption(
                    RADIUS_LOGIN_METHOD_DEVICE_CODE,
                    "Sign in with device code (when signing in from another device)",
                ),
            ),
        )
        return when (loginMethod) {
            RADIUS_LOGIN_METHOD_DEVICE_CODE -> loginRadiusWithDeviceCode(gatewayUrl, interaction, transport)
            RADIUS_LOGIN_METHOD_BROWSER -> {
                val authorizationEndpoint = loadRadiusOAuthDiscovery(gatewayUrl, transport, interaction.signal)
                loginRadiusWithBrowser(gatewayUrl, authorizationEndpoint, interaction, transport)
            }

            else -> throw IllegalStateException("Unknown $name sign-in method: $loginMethod")
        }
    }

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = requestRadiusOAuthToken(
        gatewayUrl,
        listOf(
            "grant_type" to "refresh_token",
            "client_id" to RADIUS_OAUTH_CLIENT_ID,
            "refresh_token" to credential.refresh,
        ),
        transport,
        signal,
    )

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

private fun createRadiusOAuth(id: String, name: String, gateway: String): OAuthAuth =
    RadiusOAuth(id = id, name = name, gateway = gateway)
