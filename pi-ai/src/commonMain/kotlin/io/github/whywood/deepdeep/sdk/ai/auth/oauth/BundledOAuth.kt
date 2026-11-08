package io.github.whywood.deepdeep.sdk.ai.auth.oauth

import io.github.whywood.deepdeep.sdk.ai.auth.AuthOverrides
import io.github.whywood.deepdeep.sdk.ai.auth.AuthResolver
import io.github.whywood.deepdeep.sdk.ai.auth.CredentialStore
import io.github.whywood.deepdeep.sdk.ai.auth.OAuthCredential as StoredOAuthCredential
import io.github.whywood.deepdeep.sdk.ai.auth.ProviderAuthResolver
import io.github.whywood.deepdeep.sdk.ai.auth.ResolvedAuth
import io.github.whywood.deepdeep.sdk.ai.auth.refreshStoredOAuthCredential
import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport

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
    private val delegate = ProviderAuthResolver(
        providerId = providerId,
        store = store,
        oauthFlow = flow,
        transport = transport,
    )

    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? = delegate.resolve(overrides)

    suspend fun refreshCredential(credential: OAuthCredential): OAuthCredential =
        refreshStoredOAuthCredential(providerId, flow, credential, store, transport)
}
