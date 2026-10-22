package dev.pi.ai

import kotlin.concurrent.Volatile

class RequestAbortedException(
    val reason: String? = null,
) : Exception(if (reason.isNullOrBlank()) "Operation aborted" else "Operation aborted: $reason")

class RunSignal {
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    var isAborted: Boolean = false
        private set

    @Volatile
    var reason: String? = null
        private set

    fun abort(reason: String? = null) {
        if (isAborted) return
        this.reason = reason
        isAborted = true
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { it() }
    }

    fun onAbort(listener: () -> Unit): () -> Unit {
        synchronized(this) {
            if (isAborted) {
                listener()
                return {}
            }
            listeners.add(listener)
        }
        return {
            synchronized(this) { listeners.remove(listener) }
        }
    }

    fun throwIfAborted() {
        if (isAborted) throw RequestAbortedException(reason)
    }
}

fun <T> withRunSignal(signal: RunSignal?, block: () -> T): T {
    signal?.throwIfAborted()
    return block()
}
