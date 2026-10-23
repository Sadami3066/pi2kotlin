package dev.pi.coding.mcp

import dev.pi.ai.http.HttpTransport
import dev.pi.mcp.McpTransport
import dev.pi.mcp.McpTransportUnsupportedException
import dev.pi.mcp.transport.StreamableHttpTransportOptions
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.platformStdioTransportFactory
import dev.pi.mcp.transport.StreamableHttpTransport

fun interface McpTransportFactory {
    suspend fun create(name: String, config: McpServerConfig, authProvider: dev.pi.mcp.oauth.McpAuthProvider?): McpTransport
}

class DefaultMcpTransportFactory(
    private val http: HttpTransport,
    private val env: Map<String, String> = emptyMap(),
) : McpTransportFactory {
    override suspend fun create(
        name: String,
        config: McpServerConfig,
        authProvider: dev.pi.mcp.oauth.McpAuthProvider?,
    ): McpTransport = when (config) {
        is McpServerConfig.Http -> StreamableHttpTransport(
            http = http,
            options = StreamableHttpTransportOptions(
                url = config.url,
                headers = config.headers,
                authProvider = authProvider,
            ),
        )

        is McpServerConfig.Stdio -> {
            val mergedEnv = LinkedHashMap(env).apply { putAll(config.env) }
            platformStdioTransportFactory().create(
                command = config.command,
                args = config.args,
                env = mergedEnv,
                cwd = config.cwd,
            ) ?: throw McpTransportUnsupportedException(
                "stdio MCP servers are not supported on this platform; use an HTTP MCP server instead",
            )
        }
    }
}
