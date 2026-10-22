package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

private const val KIMI_CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
private const val KIMI_DEFAULT_OAUTH_HOST = "https://auth.kimi.com"
private const val KIMI_DEVICE_CODE_TIMEOUT_SECONDS = 15L * 60
private const val KIMI_DEFAULT_POLL_INTERVAL_SECONDS = 5L
private const val KIMI_REQUEST_TIMEOUT_MS = 30_000L
private const val KIMI_REFRESH_MAX_RETRIES = 3

class KimiCodingOAuth : OAuthAuth {
    override val id: String = "kimi-coding"
    override val name: String = "Kimi Code (subscription)"
    override val isSubscription: Boolean = true
    override val loginLabel: String = "Sign in with Kimi Code"

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential {
        val oauthHost = kimiOauthHost()
        val device = startKimiDeviceAuthorization(oauthHost, transport, interaction.signal)
        interaction.openUrl(
            device.verificationUriComplete,
            "Enter code ${device.userCode} to sign in.",
        )
        interaction.showMessage("Waiting for Kimi Code device authorization...")
        val token = pollKimiToken(oauthHost, device, transport, interaction.signal)
        return OAuthCredential(refresh = token.refresh, access = token.access, expires = token.expires)
    }

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential {
        val token = refreshKimiToken(kimiOauthHost(), credential.refresh, transport, signal)
        return OAuthCredential(refresh = token.refresh, access = token.access, expires = token.expires)
    }

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(
            apiKey = null,
            headers = mapOf("Authorization" to "Bearer ${credential.access}"),
            source = "OAuth",
        )
}

val kimiCodingOAuth: OAuthAuth = KimiCodingOAuth()

private class KimiDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long,
)

private class KimiToken(
    val access: String,
    val refresh: String,
    val expires: Long,
)

private fun kimiOauthHost(): String {
    val override = AuthEnvironment.env("KIMI_CODE_OAUTH_HOST")
        ?: AuthEnvironment.env("KIMI_OAUTH_HOST")
    return (override ?: KIMI_DEFAULT_OAUTH_HOST).trimEnd('/')
}

private fun trustedKimiHttpUrl(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    if (!value.startsWith("https://") && !value.startsWith("http://")) return null
    return value
}

private fun kimiPositiveNumber(value: JsonPrimitive?): Long? {
    val number = value?.doubleOrNull ?: return null
    if (!number.isFinite() || number <= 0) return null
    return number.toLong()
}

private suspend fun startKimiDeviceAuthorization(
    oauthHost: String,
    transport: HttpTransport,
    signal: RunSignal?,
): KimiDeviceAuthorization {
    val response = oauthPostForm(
        transport,
        "$oauthHost/api/oauth/device_authorization",
        listOf("client_id" to KIMI_CLIENT_ID),
        signal,
        timeoutMs = KIMI_REQUEST_TIMEOUT_MS,
    )
    if (!response.isSuccessful) {
        throw IllegalStateException(
            "Kimi Code device authorization failed with status ${response.status}${kimiBodySuffix(response.body)}",
        )
    }
    val json = response.jsonObject()
    val deviceCode = (json?.get("device_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val userCode = (json?.get("user_code") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val verificationUri = trustedKimiHttpUrl(
        (json?.get("verification_uri") as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )
    val verificationUriComplete = trustedKimiHttpUrl(
        (json?.get("verification_uri_complete") as? JsonPrimitive)?.takeIf { it.isString }?.content,
    )
    if (deviceCode.isNullOrEmpty() || userCode.isNullOrEmpty() || verificationUri == null || verificationUriComplete == null) {
        throw IllegalStateException("Invalid Kimi Code device authorization response: ${json ?: response.body}")
    }
    val interval = kimiPositiveNumber(json["interval"] as? JsonPrimitive) ?: KIMI_DEFAULT_POLL_INTERVAL_SECONDS
    val expiresIn = kimiPositiveNumber(json["expires_in"] as? JsonPrimitive) ?: KIMI_DEVICE_CODE_TIMEOUT_SECONDS
    return KimiDeviceAuthorization(
        deviceCode = deviceCode,
        userCode = userCode,
        verificationUri = verificationUri,
        verificationUriComplete = verificationUriComplete,
        intervalSeconds = interval,
        expiresInSeconds = expiresIn,
    )
}

private fun parseKimiTokenResponse(json: JsonObject?, operation: String): KimiToken {
    val access = (json?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val refresh = (json?.get("refresh_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
    val expiresIn = kimiPositiveNumber(json?.get("expires_in") as? JsonPrimitive)
    if (access.isNullOrEmpty() || refresh.isNullOrEmpty() || expiresIn == null) {
        throw IllegalStateException("Kimi Code token $operation response missing fields: ${json ?: "null"}")
    }
    return KimiToken(
        access = access,
        refresh = refresh,
        expires = oauthNowMillis() + expiresIn * 1000,
    )
}

private fun kimiBodySuffix(body: String): String = if (body.isBlank()) "" else ": $body"

private suspend fun pollKimiToken(
    oauthHost: String,
    device: KimiDeviceAuthorization,
    transport: HttpTransport,
    signal: RunSignal?,
): KimiToken = pollOAuthDeviceCodeFlow(
    intervalSeconds = device.intervalSeconds,
    expiresInSeconds = device.expiresInSeconds,
    waitBeforeFirstPoll = true,
    signal = signal,
    poll = poll@{
        val response = oauthPostForm(
            transport,
            "$oauthHost/api/oauth/token",
            listOf(
                "client_id" to KIMI_CLIENT_ID,
                "device_code" to device.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
            signal,
            timeoutMs = KIMI_REQUEST_TIMEOUT_MS,
        )
        if (response.status >= 500) {
            return@poll DeviceCodePollResult.Failed(
                "Kimi Code device token request failed with status ${response.status}${kimiBodySuffix(response.body)}",
            )
        }
        val json = response.jsonObject()
        if (response.isSuccessful && (json?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString } != null) {
            return@poll try {
                DeviceCodePollResult.Complete(parseKimiTokenResponse(json, "poll"))
            } catch (error: Throwable) {
                DeviceCodePollResult.Failed(error.message ?: error.toString())
            }
        }
        val error = (json?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val description = (json?.get("error_description") as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (error) {
            "authorization_pending" -> DeviceCodePollResult.Pending
            "slow_down" -> DeviceCodePollResult.SlowDown(kimiPositiveNumber(json["interval"] as? JsonPrimitive))
            "expired_token" -> DeviceCodePollResult.Failed("Kimi Code device authorization expired. Please restart login.")
            "access_denied" -> DeviceCodePollResult.Failed("Kimi Code login was denied.")
            else -> {
                val errorSuffix = if (error == null) {
                    ""
                } else {
                    val descriptionSuffix = if (description == null) "" else ": $description"
                    ": $error$descriptionSuffix"
                }
                DeviceCodePollResult.Failed(
                    "Kimi Code device token request failed (status ${response.status})$errorSuffix",
                )
            }
        }
    },
)

private fun isRetryableKimiRefreshFailure(status: Int): Boolean = status == 429 || status >= 500

private suspend fun refreshKimiToken(
    oauthHost: String,
    refreshToken: String,
    transport: HttpTransport,
    signal: RunSignal?,
): KimiToken {
    var lastError: Throwable? = null
    for (attempt in 0..KIMI_REFRESH_MAX_RETRIES) {
        if (attempt > 0) {
            abortableSleep(1000L * (1 shl (attempt - 1)), signal, "Kimi Code token refresh aborted")
        }
        if (signal?.isAborted == true) throw IllegalStateException("Kimi Code token refresh aborted")

        val response = try {
            oauthPostForm(
                transport,
                "$oauthHost/api/oauth/token",
                listOf(
                    "client_id" to KIMI_CLIENT_ID,
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                ),
                signal,
                timeoutMs = KIMI_REQUEST_TIMEOUT_MS,
            )
        } catch (error: Throwable) {
            lastError = error
            continue
        }

        val json = response.jsonObject()
        if (response.isSuccessful) {
            return parseKimiTokenResponse(json, "refresh")
        }
        val errorCode = (json?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val description = (json?.get("error_description") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (response.status == 401 || response.status == 403 || errorCode == "invalid_grant") {
            val descriptionSuffix = if (description == null) "" else ": $description"
            throw IllegalStateException(
                "Kimi Code token refresh unauthorized (status ${response.status})$descriptionSuffix",
            )
        }
        if (isRetryableKimiRefreshFailure(response.status) && attempt < KIMI_REFRESH_MAX_RETRIES) {
            lastError = IllegalStateException("Kimi Code token refresh failed with status ${response.status}")
            continue
        }
        val body = json?.toString() ?: response.body
        val suffix = if (body.isBlank()) "" else ": $body"
        throw IllegalStateException("Kimi Code token refresh failed with status ${response.status}$suffix")
    }
    throw lastError ?: IllegalStateException("Kimi Code token refresh failed")
}
