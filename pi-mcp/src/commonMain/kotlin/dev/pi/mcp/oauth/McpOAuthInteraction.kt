package dev.pi.mcp.oauth

interface McpOAuthInteraction {
    suspend fun authorize(url: String, redirectUri: String): String
}

data class McpOAuthOptions(
    val serverName: String,
    val serverUrl: String,
    val scope: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val callbackPort: Int? = null,
    val callbackUrl: String? = null,
    val clientName: String = "pi2kotlin",
    val authorizationServerMetadataUrl: String? = null,
    val resourceMetadataUrl: String? = null,
)
