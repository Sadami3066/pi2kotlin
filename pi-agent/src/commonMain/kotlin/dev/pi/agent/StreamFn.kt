package dev.pi.agent

object DefaultStreamFns {
    @Volatile
    private var streamFn: StreamFn? = null

    fun set(value: StreamFn?) {
        synchronized(this) { streamFn = value }
    }

    fun get(): StreamFn = synchronized(this) {
        streamFn ?: throw IllegalStateException(
            "No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn().",
        )
    }
}

fun setDefaultStreamFn(streamFn: StreamFn?) {
    DefaultStreamFns.set(streamFn)
}

fun getDefaultStreamFn(): StreamFn = DefaultStreamFns.get()
