package dev.pi.ai.auth

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
