package dev.pi.mcp

import dev.pi.ai.RunSignal
import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class InMemoryTransport : McpTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val channel = Channel<McpTransportEvent>(Channel.UNLIMITED)
    private val sentMutex = Mutex()
    private val sentMessages = mutableListOf<JsonRpcMessage>()

    var responder: (suspend (JsonRpcRequest) -> Unit)? = null

    var started: Boolean = false
        private set

    var closed: Boolean = false
        private set

    override val events: Flow<McpTransportEvent> = channel.receiveAsFlow()

    override suspend fun start() {
        started = true
    }

    override suspend fun send(message: JsonRpcMessage, signal: RunSignal?) {
        if (!started || closed) throw McpConnectionClosedException()
        sentMutex.withLock { sentMessages += message }
        val request = message as? JsonRpcRequest ?: return
        val handler = responder ?: return
        scope.launch { handler(request) }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        channel.send(McpTransportEvent.Closed())
        scope.cancel()
    }

    suspend fun sent(): List<JsonRpcMessage> = sentMutex.withLock { sentMessages.toList() }

    suspend fun awaitSent(
        timeoutMs: Long = 5_000,
        predicate: (JsonRpcMessage) -> Boolean,
    ): JsonRpcMessage = withTimeout(timeoutMs) {
        while (true) {
            val match = sentMutex.withLock { sentMessages.toList() }.firstOrNull(predicate)
            if (match != null) return@withTimeout match
            delay(5)
        }
        error("unreachable")
    }

    suspend fun emit(message: JsonRpcMessage) {
        channel.send(McpTransportEvent.Message(message))
    }

    suspend fun respond(result: JsonObject?, requestId: JsonRpcId) {
        emit(JsonRpcResponse(requestId, result))
    }

    suspend fun respondError(
        requestId: JsonRpcId,
        code: Int,
        message: String,
        data: JsonElement? = null,
    ) {
        emit(JsonRpcErrorResponse(requestId, code, message, data))
    }

    suspend fun notify(method: String, params: JsonObject? = null) {
        emit(JsonRpcNotification(method, params))
    }

    suspend fun fail(error: Throwable) {
        closed = true
        channel.send(McpTransportEvent.Closed(error))
    }
}
