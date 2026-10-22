package dev.pi.ai.providers

import dev.pi.ai.auth.ApiKeyCredential
import dev.pi.ai.auth.AuthEnvironment
import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.ResolvedAuth

enum class CloudflareAuthKind {
    WorkersAi,
    AiGateway,
}

class CloudflareAuthResolver(
    private val providerId: String,
    private val kind: CloudflareAuthKind,
    private val credentialStore: CredentialStore? = null,
) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? {
        val credential = credentialStore?.read(providerId) as? ApiKeyCredential
        val apiKey = overrides?.apiKey?.takeIf { it.isNotBlank() }
            ?: credential?.key?.takeIf { it.isNotBlank() }
            ?: envValue(CLOUDFLARE_API_KEY_ENV, overrides)
            ?: return null
        val accountId = credential?.env?.get(CLOUDFLARE_ACCOUNT_ID_ENV)?.takeIf { it.isNotBlank() }
            ?: envValue(CLOUDFLARE_ACCOUNT_ID_ENV, overrides)
            ?: return null
        val source = if (credential != null) "stored credential" else CLOUDFLARE_API_KEY_ENV
        val resolvedEnv = linkedMapOf(
            CLOUDFLARE_API_KEY_ENV to apiKey,
            CLOUDFLARE_ACCOUNT_ID_ENV to accountId,
        )
        if (kind == CloudflareAuthKind.WorkersAi) {
            return ResolvedAuth(apiKey = apiKey, source = source, env = resolvedEnv)
        }
        val gatewayId = credential?.env?.get(CLOUDFLARE_GATEWAY_ID_ENV)?.takeIf { it.isNotBlank() }
            ?: envValue(CLOUDFLARE_GATEWAY_ID_ENV, overrides)
            ?: return null
        resolvedEnv[CLOUDFLARE_GATEWAY_ID_ENV] = gatewayId
        val gatewayToken = credential?.env?.get(CLOUDFLARE_GATEWAY_TOKEN_ENV)?.takeIf { it.isNotBlank() }
            ?: envValue(CLOUDFLARE_GATEWAY_TOKEN_ENV, overrides)
        if (gatewayToken != null) resolvedEnv[CLOUDFLARE_GATEWAY_TOKEN_ENV] = gatewayToken
        val headers = if (gatewayToken != null) {
            linkedMapOf(
                "cf-aig-authorization" to "Bearer $gatewayToken",
                "Authorization" to "Bearer $apiKey",
            )
        } else {
            linkedMapOf("cf-aig-authorization" to "Bearer $apiKey")
        }
        return ResolvedAuth(apiKey = null, headers = headers, source = source, env = resolvedEnv)
    }

    private fun envValue(name: String, overrides: AuthOverrides?): String? =
        overrides?.env?.get(name)?.takeIf { it.isNotBlank() } ?: AuthEnvironment.env(name)
}
