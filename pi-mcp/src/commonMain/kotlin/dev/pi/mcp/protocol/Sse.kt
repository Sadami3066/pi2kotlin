package dev.pi.mcp.protocol

import dev.pi.mcp.McpSseEventTooLargeException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

const val DEFAULT_MAX_SSE_EVENT_BYTES: Int = 16 * 1024 * 1024

data class SseEvent(
    val data: String,
    val event: String? = null,
    val id: String? = null,
)

suspend fun consumeSseStream(
    source: BufferedSource,
    maxEventBytes: Int = DEFAULT_MAX_SSE_EVENT_BYTES,
    onEvent: (SseEvent) -> Unit,
    onId: ((String) -> Unit)? = null,
    onRetry: ((Long) -> Unit)? = null,
) {
    var eventName: String? = null
    var eventId: String? = null
    val dataLines = mutableListOf<String>()
    var dataBytes = 0

    fun dispatch() {
        if (dataLines.isEmpty()) {
            eventName = null
            eventId = null
            return
        }
        onEvent(SseEvent(data = dataLines.joinToString("\n"), event = eventName, id = eventId))
        eventName = null
        eventId = null
        dataLines.clear()
        dataBytes = 0
    }

    fun processLine(rawLine: String) {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) return
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "data" -> {
                dataBytes += value.encodeToByteArray().size + if (dataLines.isNotEmpty()) 1 else 0
                if (dataBytes > maxEventBytes) {
                    throw McpSseEventTooLargeException("MCP SSE event exceeds $maxEventBytes bytes")
                }
                dataLines.add(value)
            }

            "event" -> eventName = value

            "id" -> if (!value.contains('\u0000')) {
                eventId = value
                onId?.invoke(value)
            }

            "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                onRetry?.invoke(value.toLongOrNull() ?: 0L)
            }
        }
    }

    while (true) {
        currentCoroutineContext().ensureActive()
        val line = source.readUtf8Line() ?: break
        processLine(line)
    }
    dispatch()
}
