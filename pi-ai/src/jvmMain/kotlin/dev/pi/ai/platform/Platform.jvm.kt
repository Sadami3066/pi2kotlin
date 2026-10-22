package dev.pi.ai.platform

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

private val secureRandom = SecureRandom()

actual fun platformCurrentTimeMillis(): Long = System.currentTimeMillis()

actual fun platformSecureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    secureRandom.nextBytes(bytes)
    return bytes
}

actual fun platformEnv(name: String): String? = System.getenv(name)

actual class PlatformAtomicBoolean actual constructor(initial: Boolean) {
    private val delegate = AtomicBoolean(initial)

    actual fun get(): Boolean = delegate.get()

    actual fun set(value: Boolean) {
        delegate.set(value)
    }

    actual fun compareAndSet(expect: Boolean, update: Boolean): Boolean =
        delegate.compareAndSet(expect, update)
}
