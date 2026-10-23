package dev.pi.mcp

import dev.pi.ai.RunSignal
import dev.pi.mcp.protocol.JsonRpcMessage
import kotlinx.coroutines.flow.Flow

sealed interface McpTransportEvent {
    data class Message(val message: JsonRpcMessage) : McpTransportEvent

    data class Closed(val error: Throwable? = null) : McpTransportEvent
}

interface McpTransport {
    suspend fun start()

    suspend fun send(message: JsonRpcMessage, signal: RunSignal? = null)

    val events: Flow<McpTransportEvent>

    suspend fun close()
}
