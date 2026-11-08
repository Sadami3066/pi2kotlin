package io.github.whywood.deepdeep.sdk.mcp

import io.github.whywood.deepdeep.sdk.ai.RunSignal
import io.github.whywood.deepdeep.sdk.mcp.protocol.JsonRpcMessage
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
