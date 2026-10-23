package dev.pi.mcp

import dev.pi.mcp.transport.StdioTransport

private object JvmStdioTransportFactory : StdioTransportFactory {
    override fun create(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        cwd: String?,
    ): McpTransport = StdioTransport(command = command, args = args, env = env, cwd = cwd)
}

actual fun platformStdioTransportFactory(): StdioTransportFactory = JvmStdioTransportFactory
