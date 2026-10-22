package dev.pi.ai.auth

import dev.pi.ai.auth.oauth.OAuthAuth
import dev.pi.ai.auth.oauth.OAuthCredential as OAuthToken
import dev.pi.ai.auth.oauth.loadOAuthCredential
import dev.pi.ai.auth.oauth.saveOAuthCredential
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.platform.platformCurrentTimeMillis

const val AUTH_MINIMUM_OAUTH_VALIDITY_MS: Long = 5 * 60 * 1000L

class CompositeAuth(private val resolvers: List<AuthResolver>) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? {
        for (resolver in resolvers) {
            resolver.resolve(overrides)?.let { return it }
        }
        return null
    }
}

class StoredApiKeyAuth(
    private val providerId: String,
    private val store: CredentialStore,
    private val envVars: List<String>,
) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? {
        overrides?.apiKey?.let { return ResolvedAuth(it, source = "runtime") }
        store.readApiKey(providerId)?.takeIf { it.isNotBlank() }?.let {
            return ResolvedAuth(it, source = "stored credential")
        }
        for (name in envVars) {
            val value = overrides?.env?.get(name) ?: AuthEnvironment.env(name)
            if (!value.isNullOrBlank()) return ResolvedAuth(value, source = name)
        }
        return null
    }
}

class ProviderAuthResolver(
    val providerId: String,
    val store: CredentialStore?,
    val envVars: List<String> = emptyList(),
    val oauthFlow: OAuthAuth? = null,
    val transport: HttpTransport? = null,
) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? = resolveAuthForProvider(
        providerId = providerId,
        store = store,
        envVars = envVars,
        oauthFlow = oauthFlow,
        transport = transport,
        overrides = overrides,
    )
}

suspend fun resolveAuthForProvider(
    providerId: String,
    store: CredentialStore? = null,
    envVars: List<String> = emptyList(),
    oauthFlow: OAuthAuth? = null,
    transport: HttpTransport? = null,
    overrides: AuthOverrides? = null,
): ResolvedAuth? {
    overrides?.apiKey?.takeIf { it.isNotBlank() }?.let { return ResolvedAuth(it, source = "runtime") }

    if (store != null) {
        when (val stored = store.read(providerId)) {
            is OAuthCredential -> {
                val flow = oauthFlow ?: return null
                val credential = loadOAuthCredential(providerId, store) ?: stored.toOAuthToken()
                val current = if (credential.expires <= platformCurrentTimeMillis() + AUTH_MINIMUM_OAUTH_VALIDITY_MS) {
                    refreshStoredOAuthCredential(providerId, flow, credential, store, transport)
                } else {
                    credential
                }
                return flow.toAuth(current)
            }

            is ApiKeyCredential -> {
                return stored.key?.takeIf { it.isNotBlank() }
                    ?.let { ResolvedAuth(it, source = "stored credential") }
            }

            null -> Unit
        }
    }

    for (name in envVars) {
        val value = overrides?.env?.get(name) ?: AuthEnvironment.env(name)
        if (!value.isNullOrBlank()) return ResolvedAuth(value, source = name)
    }
    return null
}

suspend fun refreshStoredOAuthCredential(
    providerId: String,
    flow: OAuthAuth,
    credential: OAuthToken,
    store: CredentialStore,
    transport: HttpTransport?,
): OAuthToken {
    val http = transport
        ?: throw IllegalStateException("OAuth refresh for $providerId requires an HttpTransport")
    val refreshed = try {
        flow.refresh(credential, http)
    } catch (error: Throwable) {
        throw IllegalStateException("OAuth refresh failed for $providerId", error)
    }
    saveOAuthCredential(providerId, store, refreshed)
    return refreshed
}

private fun OAuthCredential.toOAuthToken(): OAuthToken = OAuthToken(
    refresh = refresh,
    access = access,
    expires = expires,
)
