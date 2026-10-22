package dev.pi.ai.auth

import kotlinx.serialization.Serializable

fun interface AuthContext {
    fun env(name: String): String?
}

object SystemAuthContext : AuthContext {
    override fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
}

object AuthEnvironment {
    var context: AuthContext = SystemAuthContext

    fun env(name: String): String? = context.env(name)?.takeIf { it.isNotBlank() }
}

data class AuthOverrides(
    val apiKey: String? = null,
    val env: Map<String, String>? = null,
)

data class ResolvedAuth(
    val apiKey: String?,
    val headers: Map<String, String> = emptyMap(),
    val source: String,
)

interface AuthResolver {
    suspend fun resolve(overrides: AuthOverrides? = null): ResolvedAuth?
}

enum class AuthType {
    ApiKey,
    OAuth,
}

@Serializable
data class ApiKeyCredential(
    val type: String = "api_key",
    val key: String? = null,
    val env: Map<String, String>? = null,
) : Credential

@Serializable
data class OAuthCredential(
    val type: String = "oauth",
    val refresh: String,
    val access: String,
    val expires: Long,
) : Credential

sealed interface Credential
