package dev.pi.ai.http

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.BufferedSource

data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?,
    val retry: Long?,
)

class SseParser(private val source: BufferedSource) {
    private var event: String? = null
    private val dataLines = mutableListOf<String>()
    private var id: String? = null
    private var retry: Long? = null
    private var finished = false
    private var closed = false

    suspend fun next(): SseEvent? {
        if (closed || finished) return null
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line()
            if (line == null) {
                finished = true
                return flushEvent()
            }
            if (line.isEmpty()) {
                val dispatched = flushEvent()
                if (dispatched != null) return dispatched
                continue
            }
            if (line.startsWith(":")) continue
            val colonIndex = line.indexOf(':')
            val field = if (colonIndex == -1) line else line.substring(0, colonIndex)
            var value = if (colonIndex == -1) "" else line.substring(colonIndex + 1)
            if (value.startsWith(" ")) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> dataLines.add(value)
                "id" -> id = value
                "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                    retry = value.toLongOrNull()
                }
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        source.close()
    }

    private fun flushEvent(): SseEvent? {
        val pendingEvent = event
        val hasData = dataLines.isNotEmpty()
        if (pendingEvent == null && !hasData) {
            id = null
            retry = null
            return null
        }
        val flushed = SseEvent(
            event = pendingEvent,
            data = dataLines.joinToString("\n"),
            id = id,
            retry = retry,
        )
        event = null
        dataLines.clear()
        id = null
        retry = null
        return flushed
    }
}
