package dev.pi.ai.auth.oauth

import dev.pi.ai.ModelCatalog
import dev.pi.ai.RunSignal
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val GITHUB_COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98"
private const val GITHUB_COPILOT_API_VERSION = "2026-06-01"
private const val GITHUB_COPILOT_DEFAULT_DOMAIN = "github.com"
private const val GITHUB_COPILOT_EXPIRY_MARGIN_MS = 5 * 60 * 1000L
private const val GITHUB_COPILOT_RETRY_ATTEMPT_TIMEOUT_MS = 5000L

private val copilotHeaders = mapOf(
    "User-Agent" to "GitHubCopilotChat/0.35.0",
    "Editor-Version" to "vscode/1.107.0",
    "Editor-Plugin-Version" to "copilot-chat/0.35.0",
    "Copilot-Integration-Id" to "vscode-chat",
)

class GitHubCopilotOAuth : OAuthAuth {
    override val id: String = "github-copilot"
    override val name: String = "GitHub Copilot"
    override val isSubscription: Boolean = true

    override suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions?,
    ): OAuthCredential = loginGitHubCopilot(interaction, transport)

    override suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal?,
    ): OAuthCredential = refreshGitHubCopilotToken(
        credential.refresh,
        copilotEnterpriseDomain(credential),
        transport,
        signal,
    )

    override suspend fun toAuth(credential: OAuthCredential): ResolvedAuth =
        ResolvedAuth(apiKey = credential.access, source = "OAuth")
}

val githubCopilotOAuth: OAuthAuth = GitHubCopilotOAuth()

fun githubCopilotBaseUrl(token: String? = null, enterpriseDomain: String? = null): String =
    getGitHubCopilotBaseUrl(token, enterpriseDomain)

fun normalizeGitHubEnterpriseDomain(input: String): String? = normalizeDomain(input)

private class CopilotDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val interval: Long?,
    val expiresIn: Long,
)

private class CopilotModelCatalog(
    val availableModelIds: List<String>,
    val policyModelIds: List<String>,
)

private class CopilotTokenRefreshPolicy(val maxRetries: Int, val maxElapsedMs: Long)

private fun normalizeDomain(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val afterScheme = withScheme.substringAfter("://")
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPort = authority.substringAfter('@')
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringBefore(']').removePrefix("[") + "]"
    } else {
        hostPort.substringBefore(':')
    }
    if (host.isEmpty()) return null
    val validHost = if (host.startsWith("[")) {
        host.length > 2
    } else {
        Regex("^[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?$").matches(host)
    }
    return if (validHost) host else null
}

private class CopilotUrls(
    val deviceCodeUrl: String,
    val accessTokenUrl: String,
    val copilotTokenUrl: String,
)

private fun getUrls(domain: String): CopilotUrls = CopilotUrls(
    deviceCodeUrl = "https://$domain/login/device/code",
    accessTokenUrl = "https://$domain/login/oauth/access_token",
    copilotTokenUrl = "https://api.$domain/copilot_internal/v2/token",
)

private fun getBaseUrlFromToken(token: String): String? {
    val match = Regex("proxy-ep=([^;]+)").find(token) ?: return null
    val proxyHost = match.groupValues[1]
    val apiHost = if (proxyHost.startsWith("proxy.")) "api." + proxyHost.removePrefix("proxy.") else proxyHost
    return "https://$apiHost"
}

private fun getGitHubCopilotBaseUrl(token: String? = null, enterpriseDomain: String? = null): String {
    if (token != null) {
        val fromToken = getBaseUrlFromToken(token)
        if (fromToken != null) return fromToken
    }
    if (enterpriseDomain != null) return "https://copilot-api.$enterpriseDomain"
    return "https://api.individual.githubcopilot.com"
}

private fun asJsonObjectOrNull(value: JsonElement?): JsonObject? = value as? JsonObject

private fun parseGitHubCopilotModelCatalog(raw: JsonElement?, allowPolicyFallback: Boolean): CopilotModelCatalog {
    val data = asJsonObjectOrNull(raw)?.get("data") as? JsonArray
        ?: throw IllegalStateException("Invalid Copilot models response")
    val knownModelIds = ModelCatalog.findByProvider("github-copilot").map { it.id }.toSet()

    class AccountModel(val id: String, val pickerEnabled: Boolean, val policyState: String?)

    val accountModels = data.mapNotNull { rawItem ->
        val item = asJsonObjectOrNull(rawItem) ?: return@mapNotNull null
        val id = item.stringOrNull("id") ?: return@mapNotNull null
        val supports = asJsonObjectOrNull(asJsonObjectOrNull(item["capabilities"])?.get("supports"))
        if (supports?.get("tool_calls")?.let { (it as? JsonPrimitive)?.booleanOrNull } == false) {
            return@mapNotNull null
        }
        val pickerEnabled = (item["model_picker_enabled"] as? JsonPrimitive)?.booleanOrNull == true
        val policyState = asJsonObjectOrNull(item["policy"])?.stringOrNull("state")
        AccountModel(id, pickerEnabled, policyState)
    }
    val pickerModelIds = accountModels
        .filter { it.pickerEnabled && it.policyState != "disabled" }
        .map { it.id }
    val usePolicyFallback = allowPolicyFallback && pickerModelIds.isEmpty()
    val availableModelIds = if (pickerModelIds.isNotEmpty() || !allowPolicyFallback) {
        pickerModelIds
    } else {
        accountModels.filter { it.policyState == "enabled" }.map { it.id }
    }
    val policyModelIds = accountModels
        .filter { model ->
            model.policyState == "unconfigured" &&
                knownModelIds.contains(model.id) &&
                (model.pickerEnabled || usePolicyFallback)
        }
        .map { it.id }
    return CopilotModelCatalog(availableModelIds, policyModelIds)
}

private suspend fun fetchWithRateLimitRetry(
    transport: HttpTransport,
    url: String,
    method: String,
    headers: Map<String, String>,
    body: ByteArray?,
    signal: RunSignal?,
    retryPolicy: CopilotTokenRefreshPolicy,
): OAuthHttpResponse {
    val retryDeadline = if (retryPolicy.maxRetries > 0 && retryPolicy.maxElapsedMs > 0) {
        oauthNowMillis() + retryPolicy.maxElapsedMs
    } else {
        null
    }
    var retry = 0
    while (true) {
        val response = oauthHttpRequest(
            transport,
            HttpRequest(
                method = method,
                url = url,
                headers = headers,
                body = body,
                timeoutMs = GITHUB_COPILOT_RETRY_ATTEMPT_TIMEOUT_MS,
                signal = signal,
            ),
        )
        if (response.status != 429 || retry == retryPolicy.maxRetries) return response
        val retryAfter = response.headers.entries.firstOrNull { it.key.equals("retry-after", ignoreCase = true) }?.value
        var delayMs = 500L * (1 shl retry)
        if (!retryAfter.isNullOrEmpty()) {
            val seconds = retryAfter.trim().toDoubleOrNull()
            delayMs = if (seconds == null) return response else (seconds * 1000).toLong()
        }
        delayMs = maxOf(0L, delayMs)
        if (retryDeadline != null && delayMs >= retryDeadline - oauthNowMillis()) return response
        delay(delayMs)
        retry += 1
    }
}

private suspend fun fetchGitHubCopilotModels(
    copilotToken: String,
    enterpriseDomain: String?,
    transport: HttpTransport,
    signal: RunSignal?,
    retryPolicy: CopilotTokenRefreshPolicy,
): CopilotModelCatalog {
    val baseUrl = getGitHubCopilotBaseUrl(copilotToken, enterpriseDomain)
    val allowPolicyFallback = baseUrl == "https://api.individual.githubcopilot.com"
    val headers = buildMap {
        put("Accept", "application/json")
        put("Authorization", "Bearer $copilotToken")
        putAll(copilotHeaders)
        put("X-GitHub-Api-Version", GITHUB_COPILOT_API_VERSION)
    }
    val response = fetchWithRateLimitRetry(
        transport,
        "$baseUrl/models",
        "GET",
        headers,
        null,
        signal,
        retryPolicy,
    )
    if (!response.isSuccessful) {
        throw IllegalStateException("${response.status} ${response.body}")
    }
    return parseGitHubCopilotModelCatalog(response.json(), allowPolicyFallback)
}

private suspend fun startDeviceFlow(
    domain: String,
    transport: HttpTransport,
    signal: RunSignal?,
): CopilotDeviceCode {
    val urls = getUrls(domain)
    val response = oauthPostForm(
        transport,
        urls.deviceCodeUrl,
        listOf("client_id" to GITHUB_COPILOT_CLIENT_ID, "scope" to "read:user"),
        signal,
        extraHeaders = mapOf("User-Agent" to "GitHubCopilotChat/0.35.0"),
    )
    if (!response.isSuccessful) {
        throw IllegalStateException("${response.status} ${response.body}")
    }
    val data = response.jsonObject() ?: throw IllegalStateException("Invalid device code response")
    val deviceCode = data.stringOrNull("device_code")
        ?: throw IllegalStateException("Invalid device code response fields")
    val userCode = data.stringOrNull("user_code")
        ?: throw IllegalStateException("Invalid device code response fields")
    val verificationUri = data.stringOrNull("verification_uri")
        ?: throw IllegalStateException("Invalid device code response fields")
    val interval = data.longOrNull("interval")
    val expiresIn = data.longOrNull("expires_in")
        ?: throw IllegalStateException("Invalid device code response fields")
    if (!verificationUri.startsWith("https://") && !verificationUri.startsWith("http://")) {
        throw IllegalStateException("Untrusted verification_uri in device code response")
    }
    return CopilotDeviceCode(deviceCode, userCode, verificationUri, interval, expiresIn)
}

private suspend fun pollForGitHubAccessToken(
    domain: String,
    device: CopilotDeviceCode,
    transport: HttpTransport,
    signal: RunSignal?,
): String {
    val urls = getUrls(domain)
    return pollOAuthDeviceCodeFlow(
        intervalSeconds = device.interval,
        expiresInSeconds = device.expiresIn,
        waitBeforeFirstPoll = true,
        signal = signal,
        poll = poll@{
            val response = oauthPostForm(
                transport,
                urls.accessTokenUrl,
                listOf(
                    "client_id" to GITHUB_COPILOT_CLIENT_ID,
                    "device_code" to device.deviceCode,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                ),
                signal,
                extraHeaders = mapOf("User-Agent" to "GitHubCopilotChat/0.35.0"),
            )
            if (!response.isSuccessful) {
                DeviceCodePollResult.Failed("${response.status} ${response.body}")
            } else {
                val raw = response.jsonObject() ?: return@poll DeviceCodePollResult.Failed(
                    "Invalid device token response",
                )
                val accessToken = raw.stringOrNull("access_token")
                if (!accessToken.isNullOrEmpty()) {
                    DeviceCodePollResult.Complete(accessToken)
                } else {
                    val error = raw.stringOrNull("error")
                    if (error.isNullOrEmpty()) {
                        DeviceCodePollResult.Failed("Invalid device token response")
                    } else if (error == "authorization_pending") {
                        DeviceCodePollResult.Pending
                    } else if (error == "slow_down") {
                        DeviceCodePollResult.SlowDown(raw.longOrNull("interval"))
                    } else {
                        val description = raw.stringOrNull("error_description")
                        val suffix = if (description.isNullOrEmpty()) "" else ": $description"
                        DeviceCodePollResult.Failed("Device flow failed: $error$suffix")
                    }
                }
            }
        },
    )
}

private suspend fun refreshGitHubCopilotAccessToken(
    refreshToken: String,
    enterpriseDomain: String?,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val domain = enterpriseDomain ?: GITHUB_COPILOT_DEFAULT_DOMAIN
    val urls = getUrls(domain)
    val response = oauthGetJson(
        transport,
        urls.copilotTokenUrl,
        buildMap {
            put("Accept", "application/json")
            put("Authorization", "Bearer $refreshToken")
            putAll(copilotHeaders)
        },
        signal,
    )
    if (!response.isSuccessful) {
        throw IllegalStateException("${response.status} ${response.body}")
    }
    val raw = response.jsonObject() ?: throw IllegalStateException("Invalid Copilot token response")
    val token = raw.stringOrNull("token") ?: throw IllegalStateException("Invalid Copilot token response fields")
    val expiresAt = raw.longOrNull("expires_at")
        ?: throw IllegalStateException("Invalid Copilot token response fields")
    return OAuthCredential(
        refresh = refreshToken,
        access = token,
        expires = expiresAt * 1000 - GITHUB_COPILOT_EXPIRY_MARGIN_MS,
        enterpriseUrl = enterpriseDomain,
    )
}

private suspend fun refreshGitHubCopilotToken(
    refreshToken: String,
    enterpriseDomain: String?,
    transport: HttpTransport,
    signal: RunSignal?,
): OAuthCredential {
    val credentials = refreshGitHubCopilotAccessToken(refreshToken, enterpriseDomain, transport, signal)
    val models = fetchGitHubCopilotModels(
        credentials.access,
        enterpriseDomain,
        transport,
        signal,
        CopilotTokenRefreshPolicy(0, 0),
    )
    return credentials.copy(availableModelIds = models.availableModelIds)
}

private suspend fun enableGitHubCopilotModel(
    token: String,
    modelId: String,
    enterpriseDomain: String?,
    transport: HttpTransport,
    signal: RunSignal?,
): Boolean {
    val baseUrl = getGitHubCopilotBaseUrl(token, enterpriseDomain)
    val url = "$baseUrl/models/$modelId/policy"
    val headers = buildMap {
        put("Content-Type", "application/json")
        put("Authorization", "Bearer $token")
        putAll(copilotHeaders)
        put("openai-intent", "chat-policy")
        put("x-interaction-type", "chat-policy")
    }
    val response = try {
        fetchWithRateLimitRetry(
            transport,
            url,
            "POST",
            headers,
            buildJsonObject { put("state", "enabled") }.toString().encodeToByteArray(),
            signal,
            CopilotTokenRefreshPolicy(2, 5000),
        )
    } catch (error: Throwable) {
        if (signal?.isAborted == true) throw error
        return false
    }
    if (response.status == 429) {
        throw IllegalStateException("${response.status} ${response.body}")
    }
    return response.isSuccessful
}

private suspend fun enableGitHubCopilotModels(
    token: String,
    modelIds: List<String>,
    enterpriseDomain: String?,
    transport: HttpTransport,
    signal: RunSignal?,
): List<String> {
    val enabled = mutableListOf<String>()
    for (modelId in modelIds) {
        try {
            if (enableGitHubCopilotModel(token, modelId, enterpriseDomain, transport, signal)) {
                enabled += modelId
            }
        } catch (error: Throwable) {
            if (signal?.isAborted == true) throw error
            break
        }
    }
    return enabled
}

private suspend fun loginGitHubCopilot(
    interaction: OAuthInteraction,
    transport: HttpTransport,
): OAuthCredential {
    val input = interaction.prompt(
        "GitHub Enterprise URL/domain (blank for github.com)",
        placeholder = "company.ghe.com",
    )
    if (interaction.signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)

    val trimmed = input.trim()
    val enterpriseDomain = normalizeDomain(input)
    if (trimmed.isNotEmpty() && enterpriseDomain == null) {
        throw IllegalStateException("Invalid GitHub Enterprise URL/domain")
    }
    val domain = enterpriseDomain ?: GITHUB_COPILOT_DEFAULT_DOMAIN

    val device = startDeviceFlow(domain, transport, interaction.signal)
    interaction.openUrl(device.verificationUri, "Enter code ${device.userCode}")
    interaction.showMessage("Waiting for GitHub device authorization...")

    val githubAccessToken = pollForGitHubAccessToken(domain, device, transport, interaction.signal)
    val credentials = refreshGitHubCopilotAccessToken(githubAccessToken, enterpriseDomain, transport, interaction.signal)
    val models = fetchGitHubCopilotModels(
        credentials.access,
        enterpriseDomain,
        transport,
        interaction.signal,
        CopilotTokenRefreshPolicy(2, 5000),
    )
    var enabledModelIds: List<String> = emptyList()
    if (models.policyModelIds.isNotEmpty()) {
        interaction.showMessage("Enabling models...")
        enabledModelIds = enableGitHubCopilotModels(
            credentials.access,
            models.policyModelIds,
            enterpriseDomain,
            transport,
            interaction.signal,
        )
    }
    return credentials.copy(
        availableModelIds = (models.availableModelIds + enabledModelIds).distinct(),
    )
}

private fun copilotEnterpriseDomain(credential: OAuthCredential): String? {
    val enterpriseUrl = credential.enterpriseUrl
    if (enterpriseUrl.isNullOrEmpty()) return null
    return normalizeDomain(enterpriseUrl)
}
