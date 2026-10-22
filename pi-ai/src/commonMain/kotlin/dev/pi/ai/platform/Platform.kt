package dev.pi.ai.platform

expect fun platformCurrentTimeMillis(): Long

expect fun platformSecureRandomBytes(size: Int): ByteArray

expect fun platformEnv(name: String): String?

expect class PlatformAtomicBoolean(initial: Boolean) {
    fun get(): Boolean

    fun set(value: Boolean)

    fun compareAndSet(expect: Boolean, update: Boolean): Boolean
}
