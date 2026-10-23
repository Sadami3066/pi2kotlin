package dev.pi.mcp.config

enum class McpExposure {
    Direct,
    Deferred,
    Codemode,
    Hidden,
    ;

    companion object {
        fun parse(value: String?): McpExposure? = when (value?.lowercase()) {
            null -> null
            "direct" -> Direct
            "deferred" -> Deferred
            "codemode", "codemode-deferred" -> Codemode
            "hidden" -> Hidden
            else -> throw IllegalArgumentException("unknown MCP exposure: $value")
        }
    }
}

data class McpOAuthConfig(
    val clientId: String? = null,
    val clientSecret: String? = null,
    val callbackPort: Int? = null,
    val callbackUrl: String? = null,
    val scope: String? = null,
    val clientName: String? = null,
    val clientRegistration: String? = null,
    val authServerMetadataUrl: String? = null,
)

sealed interface McpServerConfig {
    val enabled: Boolean
    val timeoutSeconds: Long
    val description: String?
    val exposure: McpExposure
    val toolExposure: Map<String, McpExposure>

    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        val env: Map<String, String> = emptyMap(),
        val cwd: String? = null,
        override val enabled: Boolean = true,
        override val timeoutSeconds: Long = 60,
        override val description: String? = null,
        override val exposure: McpExposure = McpExposure.Deferred,
        override val toolExposure: Map<String, McpExposure> = emptyMap(),
    ) : McpServerConfig

    data class Http(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val oauth: McpOAuthConfig? = null,
        val authProvider: String? = null,
        override val enabled: Boolean = true,
        override val timeoutSeconds: Long = 60,
        override val description: String? = null,
        override val exposure: McpExposure = McpExposure.Deferred,
        override val toolExposure: Map<String, McpExposure> = emptyMap(),
    ) : McpServerConfig
}

fun McpServerConfig.blobKey(): String = when (this) {
    is McpServerConfig.Stdio -> "stdio:$command:${args.joinToString("\u0000")}"
    is McpServerConfig.Http -> "http:$url"
}
