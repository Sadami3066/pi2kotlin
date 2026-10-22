package dev.pi.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean

class AssistantMessageEventStream {
    private val channel = Channel<AssistantMessageEvent>(Channel.UNLIMITED)
    private val resultDeferred = CompletableDeferred<AssistantMessage>()
    private val done = AtomicBoolean(false)
    private val startedAt = System.currentTimeMillis()
    private val startedAtNanos = System.nanoTime()

    val events: Flow<AssistantMessageEvent> = channel.receiveAsFlow()

    fun push(event: AssistantMessageEvent) {
        if (done.get()) return
        when (event) {
            is DoneEvent -> {
                done.set(true)
                time(event.message)
            }

            is ErrorEvent -> {
                done.set(true)
                time(event.error)
            }

            else -> Unit
        }
        channel.trySend(event)
        when (event) {
            is DoneEvent -> resultDeferred.complete(event.message)
            is ErrorEvent -> resultDeferred.complete(event.error)
            else -> Unit
        }
        if (done.get()) channel.close()
    }

    fun end(result: AssistantMessage? = null) {
        if (!done.compareAndSet(false, true)) return
        if (result != null) {
            time(result)
            resultDeferred.complete(result)
        }
        channel.close()
    }

    suspend fun result(): AssistantMessage = resultDeferred.await()

    private fun time(message: AssistantMessage) {
        if (message.durationMs != null) return
        if (message.timestamp < startedAt) return
        message.durationMs = ((System.nanoTime() - startedAtNanos) / 1_000_000).coerceAtLeast(0)
    }
}

fun createAssistantMessageEventStream(): AssistantMessageEventStream = AssistantMessageEventStream()
