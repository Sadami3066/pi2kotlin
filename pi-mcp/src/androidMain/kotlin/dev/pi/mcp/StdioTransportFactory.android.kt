package dev.pi.mcp

private object AndroidStdioTransportFactory : StdioTransportFactory {
    override fun create(
        command: String,
        args: List<String>,
        env: Map<String, String>,
        cwd: String?,
    ): McpTransport? = null
}

actual fun platformStdioTransportFactory(): StdioTransportFactory = AndroidStdioTransportFactory
