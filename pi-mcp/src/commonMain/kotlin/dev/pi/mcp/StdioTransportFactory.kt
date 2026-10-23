package dev.pi.mcp

fun interface StdioTransportFactory {
    fun create(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        cwd: String?,
    ): McpTransport?
}

expect fun platformStdioTransportFactory(): StdioTransportFactory
