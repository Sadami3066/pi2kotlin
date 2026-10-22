package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val XAI_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
private const val XAI_SCOPE = "openid profile email offline_access grok-cli:access api:access"
private const val XAI_DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
private const val XAI_TOKEN_URL = "https://auth.x.ai/oauth2/token"
private const val XAI_REFRESH_SKEW_MS = 5 * 60 * 1000L
private const val XAI_DEFAULT_TOKEN_LIFETIME_SECONDS = 3600L

class XaiOAuth : OAuthAuth {
    override val id: String = "xai"
    override val name: String = "xAI (Grok/X subscription)"
    override val isSubscription: Boolean = true
    override val loginLabel: String = "Sign in with SuperGrok or X Premium"

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential {
        val device = requestXaiDeviceCode(transport, interaction.signal)
        interaction.openUrl(
            device.verificationUriComplete ?: device.verificationUri,
            "Enter code ${device.userCode} to sign in.",
        )
        interaction.showMessage("Waiting for xAI device authorization...")
        return pollXaiTokens(device, transport, interaction.signal)
    }

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = refreshXaiToken(credential.refresh, transport, signal)

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val xaiOAuth: OAuthAuth = XaiOAuth()

private class XaiDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Long?,
    val expiresInSeconds: Long,
)

private fun requiredXaiString(body: JsonObject, field: String): String {
    val value = (body[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (value.isNullOrEmpty()) throw IllegalStateException("Invalid xAI OAuth response field: $field")
    return value
}

private fun positiveXaiNumber(body: JsonObject, field: String): Double {
    val value = (body[field] as? JsonPrimitive)?.doubleOrNull
    if (value == null || value <= 0 || !value.isFinite()) {
        throw IllegalStateException("Invalid xAI OAuth response field: $field")
    }
    return value
}

private fun validateXaiVerificationUri(raw: String): String {
    if (!raw.startsWith("https://") || raw.length <= "https://".length) {
        throw IllegalStateException("Untrusted verification URI in xAI OAuth response")
    }
    return raw
}

private suspend fun xaiPostForm(
    transport: HttpTransport,
    url: String,
    fields: List<Pair<String, String>>,
    signal: RunSignal?,
): OAuthHttpResponse {
    val response = try {
        oauthPostForm(transport, url, fields, signal, timeoutMs = 30_000)
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        throw error
    }
    if (response.jsonObject() == null && !response.body.isBlank()) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        throw IllegalStateException("xAI OAuth returned invalid JSON (HTTP ${response.status})")
    }
    return response
}

private fun xaiBody(response: OAuthHttpResponse): JsonObject = response.jsonObject() ?: JsonObject(emptyMap())

private fun xaiRequestFailure(action: String, response: OAuthHttpResponse): IllegalStateException {
    val body = xaiBody(response)
    val error = (body["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val description = (body["error_description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val detail = listOfNotNull(error, description).joinToString(": ")
    val suffix = if (detail.isEmpty()) "" else ": $detail"
    return IllegalStateException("xAI OAuth $action failed (HTTP ${response.status})$suffix")
}

private fun parseXaiDeviceCode(body: JsonObject): XaiDeviceCode {
    val interval = (body["interval"] as? JsonPrimitive)?.doubleOrNull
    val intervalSeconds = if (interval != null && interval.isFinite() && interval > 0) interval.toLong() else null
    val verificationUriComplete = (body["verification_uri_complete"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it.isNotEmpty() }
        ?.let(::validateXaiVerificationUri)
    return XaiDeviceCode(
        deviceCode = requiredXaiString(body, "device_code"),
        userCode = requiredXaiString(body, "user_code"),
        verificationUri = validateXaiVerificationUri(requiredXaiString(body, "verification_uri")),
        verificationUriComplete = verificationUriComplete,
        intervalSeconds = intervalSeconds,
        expiresInSeconds = positiveXaiNumber(body, "expires_in").toLong(),
    )
}

private fun xaiCredentialsFromTokenResponse(body: JsonObject, previousRefreshToken: String? = null): OAuthCredential {
    val access = requiredXaiString(body, "access_token")
    val refreshValue = (body["refresh_token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val refresh = if (refreshValue == null && previousRefreshToken != null) {
        previousRefreshToken
    } else {
        requiredXaiString(body, "refresh_token")
    }
    val expiresInSeconds = if (body["expires_in"] == null) {
        XAI_DEFAULT_TOKEN_LIFETIME_SECONDS.toDouble()
    } else {
        positiveXaiNumber(body, "expires_in")
    }
    return OAuthCredential(
        refresh = refresh,
        access = access,
        expires = oauthNowMillis() + expiresInSeconds.toLong() * 1000 - XAI_REFRESH_SKEW_MS,
    )
}

private suspend fun requestXaiDeviceCode(transport: HttpTransport, signal: RunSignal?): XaiDeviceCode {
    val response = xaiPostForm(
        transport,
        XAI_DEVICE_CODE_URL,
        listOf(
            "client_id" to XAI_CLIENT_ID,
            "scope" to XAI_SCOPE,
            "referrer" to "pi",
        ),
        signal,
    )
    if (!response.isSuccessful) {
        throw xaiRequestFailure("device authorization", response)
    }
    return parseXaiDeviceCode(xaiBody(response))
}

private suspend fun pollXaiTokens(
    device: XaiDeviceCode,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential = pollOAuthDeviceCodeFlow(
    intervalSeconds = device.intervalSeconds,
    expiresInSeconds = device.expiresInSeconds,
    waitBeforeFirstPoll = true,
    signal = signal,
    poll = poll@{
        val response = xaiPostForm(
            transport,
            XAI_TOKEN_URL,
            listOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to XAI_CLIENT_ID,
                "device_code" to device.deviceCode,
            ),
            signal,
        )
        if (response.isSuccessful) {
            return@poll DeviceCodePollResult.Complete(xaiCredentialsFromTokenResponse(xaiBody(response)))
        }
        val body = xaiBody(response)
        val error = (body["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (error) {
            "authorization_pending" -> DeviceCodePollResult.Pending
            "slow_down" -> {
                val interval = (body["interval"] as? JsonPrimitive)?.doubleOrNull?.toLong()
                DeviceCodePollResult.SlowDown(interval)
            }

            "access_denied", "authorization_denied" -> DeviceCodePollResult.Failed(
                "xAI device authorization was denied",
            )

            "expired_token" -> DeviceCodePollResult.Failed("xAI device code expired")
            else -> DeviceCodePollResult.Failed(xaiRequestFailure("device token polling", response).message!!)
        }
    },
)

private suspend fun refreshXaiToken(
    refreshToken: String,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val response = xaiPostForm(
        transport,
        XAI_TOKEN_URL,
        listOf(
            "grant_type" to "refresh_token",
            "client_id" to XAI_CLIENT_ID,
            "refresh_token" to refreshToken,
        ),
        signal,
    )
    if (!response.isSuccessful) {
        throw xaiRequestFailure("token refresh", response)
    }
    return xaiCredentialsFromTokenResponse(xaiBody(response), refreshToken)
}
