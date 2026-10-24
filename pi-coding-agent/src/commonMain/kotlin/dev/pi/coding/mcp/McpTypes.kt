package dev.pi.coding.mcp

import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.protocol.McpImplementation
import kotlinx.serialization.json.JsonObject
import okio.Path

data class McpOptions(
    val enabled: Boolean = true,
    val loadConfig: Boolean = true,
    val servers: Map<String, McpServerConfig> = emptyMap(),
    val connectTimeoutMs: Long = 10_000,
    val requestTimeoutMs: Long = 60_000,
    val maxOutputBytes: Int = 20 * 1024,
    val outputDirectory: Path? = null,
    val autoEnableToolSearch: Boolean = true,
    val transportFactory: McpTransportFactory? = null,
    val samplingEnabled: Boolean = true,
    val samplingMaxTokens: Long = 4096,
    val samplingTemperature: Double? = null,
    val rootsEnabled: Boolean = true,
    val roots: List<dev.pi.mcp.protocol.McpRoot> = emptyList(),
    val elicitationHandler: (suspend (dev.pi.mcp.protocol.McpElicitationRequest) -> dev.pi.mcp.protocol.McpElicitationResult)? = null,
)

enum class McpServerState {
    Disabled,
    Connecting,
    Connected,
    AuthRequired,
    Failed,
    Closed,
    Unsupported,
}

data class McpServerStatus(
    val name: String,
    val state: McpServerState,
    val toolCount: Int = 0,
    val serverInfo: McpImplementation? = null,
    val description: String? = null,
    val instructions: String? = null,
    val lastError: String? = null,
    val exposure: McpExposure = McpExposure.Deferred,
) {
    val isConnected: Boolean get() = state == McpServerState.Connected
}

data class McpServerHealth(
    val name: String,
    val healthy: Boolean,
    val latencyMs: Long? = null,
    val error: String? = null,
)

data class McpSignInResult(
    val server: String,
    val signedIn: Boolean,
    val error: String? = null,
)

data class McpToolInfo(
    val server: String,
    val name: String,
    val exposedName: String,
    val description: String? = null,
    val title: String? = null,
    val annotations: JsonObject? = null,
    val exposure: McpExposure = McpExposure.Deferred,
    val parameters: JsonObject = JsonObject(emptyMap()),
)

data class McpToolUiInfo(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
) {
    companion object {
        fun fromAnnotations(annotations: JsonObject?): McpToolUiInfo? {
            if (annotations == null) return null
            fun bool(name: String): Boolean? = (annotations[name] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString == false }
                ?.content
                ?.toBooleanStrictOrNull()
            val title = (annotations["title"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            return McpToolUiInfo(
                title = title,
                readOnlyHint = bool("readOnlyHint"),
                destructiveHint = bool("destructiveHint"),
                idempotentHint = bool("idempotentHint"),
                openWorldHint = bool("openWorldHint"),
            )
        }
    }
}
