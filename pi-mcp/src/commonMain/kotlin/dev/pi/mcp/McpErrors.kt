package dev.pi.mcp

open class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)

class McpProtocolException(message: String, cause: Throwable? = null) : McpException(message, cause)

class McpConnectionClosedException(message: String = "MCP connection is closed", cause: Throwable? = null) :
    McpException(message, cause)

class McpTimeoutException(message: String = "MCP request timed out") : McpException(message)

class McpRpcException(
    val code: Int,
    message: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
) : McpException(message)

class McpHttpException(
    val status: Int,
    val body: String,
    message: String,
) : McpException(message)

class McpAuthRequiredException(
    val wwwAuthenticate: String?,
    val serverUrl: String,
    message: String = "MCP server requires authentication",
) : McpException(message)

class McpTransportUnsupportedException(message: String) : McpException(message)

class McpSessionExpiredException(
    val body: String = "",
    message: String = "MCP session expired",
) : McpException(message)

class McpSseEventTooLargeException(message: String) : McpException(message)
