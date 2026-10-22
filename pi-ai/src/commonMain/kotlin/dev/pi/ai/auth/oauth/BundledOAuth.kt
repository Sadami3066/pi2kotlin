package dev.pi.ai.auth.oauth

import dev.pi.ai.auth.AuthOverrides
import dev.pi.ai.auth.AuthResolver
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.OAuthCredential as StoredOAuthCredential
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport

private const val OAUTH_MINIMUM_VALIDITY_MS = 5 * 60 * 1000L

fun bundledOAuthFlows(): Map<String, OAuthAuth> = mapOf(
    "anthropic" to anthropicOAuth,
    "openai" to openaiChatGPTOAuth,
    "github-copilot" to githubCopilotOAuth,
    "openai-codex" to openAICodexOAuth,
    "xai" to xaiOAuth,
    "openrouter" to openRouterOAuth,
    "kimi-coding" to kimiCodingOAuth,
    "meta" to metaOAuth,
    "radius" to radiusOAuth,
)

fun oauthResolver(
    providerId: String,
    flow: OAuthAuth,
    store: CredentialStore,
    transport: HttpTransport,
): AuthResolver = OAuthAuthResolver(providerId, flow, store, transport)

fun loadOAuthCredential(providerId: String, store: CredentialStore): OAuthCredential? {
    val raw = store.readRaw()[providerId]
    if (raw != null) {
        val parsed = oauthCredentialFromJson(raw)
        if (parsed != null) return parsed
    }
    val stored = store.read(providerId) as? StoredOAuthCredential ?: return null
    return stored.toOAuthCredential()
}

fun saveOAuthCredential(providerId: String, store: CredentialStore, credential: OAuthCredential) {
    store.writeRawCredential(providerId, credential.toJsonObject())
}

suspend fun loginAndStoreOAuthCredential(
    providerId: String,
    flow: OAuthAuth,
    store: CredentialStore,
    transport: HttpTransport,
    interaction: OAuthInteraction,
    options: OAuthLoginOptions? = null,
): OAuthCredential {
    val credential = flow.login(interaction, transport, options)
    saveOAuthCredential(providerId, store, credential)
    return credential
}

class OAuthAuthResolver(
    private val providerId: String,
    private val flow: OAuthAuth,
    private val store: CredentialStore,
    private val transport: HttpTransport,
) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? {
        overrides?.apiKey?.let { return ResolvedAuth(it, source = "stored credential") }
        val stored = loadOAuthCredential(providerId, store) ?: return null
        val credential = if (stored.expires <= oauthNowMillis() + OAUTH_MINIMUM_VALIDITY_MS) {
            refreshCredential(stored)
        } else {
            stored
        }
        return flow.toAuth(credential)
    }

    suspend fun refreshCredential(credential: OAuthCredential): OAuthCredential {
        val refreshed = try {
            flow.refresh(credential, transport)
        } catch (error: Throwable) {
            throw IllegalStateException("OAuth refresh failed for $providerId", error)
        }
        saveOAuthCredential(providerId, store, refreshed)
        return refreshed
    }
}
