package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull

private const val META_CLIENT_ID = "1031625952748946"
private const val META_AUTH_HOST = "https://auth.meta.com"
private const val META_DEVICE_AUTHORIZATION_URL = "$META_AUTH_HOST/oidc/device/authorization/"
private const val META_DEVICE_TOKEN_URL = "$META_AUTH_HOST/oidc/device/token/"
private const val META_API_KEY_MINT_URL = "https://api.meta.ai/muse-code/key"
private const val META_API_KEY_LIFETIME_MS = 24 * 60 * 60 * 1000L
private const val META_REQUEST_TIMEOUT_MS = 30_000L

class MetaOAuth : OAuthAuth {
    override val id: String = "meta"
    override val name: String = "Meta (Muse subscription)"
    override val isSubscription: Boolean = true
    override val loginLabel: String = "Sign in with Meta"

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential {
        try {
            val device = startMetaDeviceAuthorization(transport, interaction.signal)
            interaction.openUrl(
                device.verificationUri,
                "Enter code ${device.userCode} to sign in.",
            )
            interaction.showMessage("Waiting for Meta device authorization...")
            val identityToken = pollForMetaIdentityToken(device, transport, interaction.signal)
            interaction.showMessage("Enabling Meta Model API access...")
            return mintMetaApiKey(identityToken, transport, interaction.signal)
        } catch (error: Throwable) {
            if (interaction.signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
            throw error
        }
    }

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = mintMetaApiKey(credential.refresh, transport, signal)

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val metaOAuth: OAuthAuth = MetaOAuth()

private class MetaDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Long?,
    val expiresInSeconds: Long?,
)

private fun positiveMetaNumber(value: JsonPrimitive?): Long? {
    val number = value?.doubleOrNull ?: return null
    if (!number.isFinite() || number <= 0) return null
    return number.toLong()
}

private fun metaErrorDetail(json: JsonObject?): String {
    for (key in listOf("error_description", "detail", "message", "error")) {
        val value = (json?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!value.isNullOrBlank()) return ": ${value.trim()}"
    }
    return ""
}

private fun trustedMetaUrl(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    if (!value.startsWith("https://") && !value.startsWith("http://")) return null
    return value
}

private suspend fun metaPostForm(
    transport: HttpTransport,
    url: String,
    fields: List<Pair<String, String>>,
    signal: RunSignal?,
): OAuthHttpResponse = oauthPostForm(
    transport,
    url,
    fields,
    signal,
    timeoutMs = META_REQUEST_TIMEOUT_MS,
)

private fun metaJsonOrNull(response: OAuthHttpResponse): JsonObject? = response.jsonObject()

private suspend fun startMetaDeviceAuthorization(
    transport: HttpTransport,
    signal: RunSignal?,
): MetaDeviceAuthorization {
    val response = metaPostForm(
        transport,
        META_DEVICE_AUTHORIZATION_URL,
        listOf("client_id" to META_CLIENT_ID),
        signal,
    )
    val json = metaJsonOrNull(response)
    if (!response.isSuccessful) {
        throw IllegalStateException("Meta device authorization failed with status ${response.status}${metaErrorDetail(json)}")
    }
    val deviceCode = (json?.get("device_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val userCode = (json?.get("user_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val verificationUri = trustedMetaUrl(
        (json?.get("verification_uri_complete") as? JsonPrimitive)?.takeIf { it.isString }?.content,
    ) ?: trustedMetaUrl((json?.get("verification_uri") as? JsonPrimitive)?.takeIf { it.isString }?.content)
    if (deviceCode.isNullOrEmpty() || userCode.isNullOrEmpty() || verificationUri == null) {
        throw IllegalStateException("Invalid Meta device authorization response: ${json ?: response.body}")
    }
    return MetaDeviceAuthorization(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = verificationUri,
        intervalSeconds = positiveMetaNumber(json["interval"] as? JsonPrimitive),
        expiresInSeconds = positiveMetaNumber(json["expires_in"] as? JsonPrimitive),
    )
}

private suspend fun pollForMetaIdentityToken(
    device: MetaDeviceAuthorization,
    transport: HttpTransport,
    signal: RunSignal?,
): String = pollOAuthDeviceCodeFlow(
    intervalSeconds = device.intervalSeconds,
    expiresInSeconds = device.expiresInSeconds,
    waitBeforeFirstPoll = true,
    signal = signal,
    poll = poll@{
        val response = metaPostForm(
            transport,
            META_DEVICE_TOKEN_URL,
            listOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "device_code" to device.deviceCode,
                "client_id" to META_CLIENT_ID,
            ),
            signal,
        )
        val json = metaJsonOrNull(response)
        val accessToken = (json?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (response.isSuccessful && !accessToken.isNullOrEmpty()) {
            return@poll DeviceCodePollResult.Complete(accessToken)
        }
        when ((json?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "authorization_pending" -> DeviceCodePollResult.Pending
            "slow_down" -> DeviceCodePollResult.SlowDown(positiveMetaNumber(json["interval"] as? JsonPrimitive))
            "access_denied" -> DeviceCodePollResult.Failed("Meta login was denied.")
            "expired_token" -> DeviceCodePollResult.Failed(
                "Meta device authorization expired. Please restart login.",
            )

            else -> DeviceCodePollResult.Failed(
                "Meta device token request failed with status ${response.status}${metaErrorDetail(json)}",
            )
        }
    },
)

private suspend fun mintMetaApiKey(
    identityToken: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val response = oauthHttpRequest(
        transport,
        HttpRequest(
            method = "POST",
            url = META_API_KEY_MINT_URL,
            headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer $identityToken",
                "Content-Type" to "application/json",
                "x-api-version" to "1.0.0",
            ),
            body = buildJsonObject { }.toString().encodeToByteArray(),
            timeoutMs = META_REQUEST_TIMEOUT_MS,
            signal = signal,
        ),
    )
    val json = metaJsonOrNull(response)
    if (response.status == 401 || response.status == 403) {
        throw IllegalStateException(
            "Meta session expired (status ${response.status}). Run `/login meta` to sign in again.${metaErrorDetail(json)}",
        )
    }
    if (!response.isSuccessful) {
        throw IllegalStateException("Meta API key mint failed with status ${response.status}${metaErrorDetail(json)}")
    }
    val apiKey = (json?.get("api_key") as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (apiKey.isNullOrEmpty()) {
        val actionUrl = trustedMetaUrl((json?.get("action_url") as? JsonPrimitive)?.takeIf { it.isString }?.content)
        val suffix = if (actionUrl == null) "" else " Complete setup at $actionUrl"
        throw IllegalStateException("Meta did not issue an API key.$suffix")
    }
    return OAuthCredential(
        refresh = identityToken,
        access = apiKey,
        expires = oauthNowMillis() + META_API_KEY_LIFETIME_MS,
    )
}
