package dev.pi.ai.auth

class EnvApiKeyAuth(
    val label: String,
    val envVars: List<String>,
    val inlineKey: String? = null,
) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth? {
        overrides?.apiKey?.let { return ResolvedAuth(it, source = "stored credential") }
        inlineKey?.let { return ResolvedAuth(it, source = "stored credential") }
        for (envVar in envVars) {
            val value = overrides?.env?.get(envVar) ?: AuthEnvironment.env(envVar)
            if (!value.isNullOrBlank()) return ResolvedAuth(value, source = envVar)
        }
        return null
    }
}

class StaticApiKeyAuth(val apiKey: String) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth =
        ResolvedAuth(overrides?.apiKey ?: apiKey, source = "stored credential")
}

class AmbientAuth(val source: String) : AuthResolver {
    override suspend fun resolve(overrides: AuthOverrides?): ResolvedAuth =
        ResolvedAuth(overrides?.apiKey, source = source)
}
