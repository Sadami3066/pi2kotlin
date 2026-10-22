package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

const val OAUTH_CANCEL_MESSAGE = "Login cancelled"
const val OAUTH_DEVICE_TIMEOUT_MESSAGE = "Device flow timed out"
const val OAUTH_SLOW_DOWN_TIMEOUT_MESSAGE =
    "Device flow timed out after one or more slow_down responses. This is often caused by clock drift in WSL or VM environments. Please sync or restart the VM clock and try again."

private const val MINIMUM_INTERVAL_MS = 1000L
private const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
private const val SLOW_DOWN_INTERVAL_INCREMENT_MS = 5000L

sealed interface DeviceCodePollResult<out T> {
    data object Pending : DeviceCodePollResult<Nothing>

    data class SlowDown(val intervalSeconds: Long? = null) : DeviceCodePollResult<Nothing>

    data class Failed(val message: String) : DeviceCodePollResult<Nothing>

    data class Complete<T>(val value: T) : DeviceCodePollResult<T>
}

private val monotonicOrigin = TimeSource.Monotonic.markNow()

private fun defaultNowMs(): Long = monotonicOrigin.elapsedNow().inWholeMilliseconds

suspend fun abortableSleep(
    ms: Long,
    signal: RunSignal?,
    cancelMessage: String = OAUTH_CANCEL_MESSAGE,
) {
    if (signal?.isAborted == true) throw IllegalStateException(cancelMessage)
    if (ms <= 0) return
    val deferred = CompletableDeferred<Unit>()
    val remove = signal?.onAbort { deferred.completeExceptionally(IllegalStateException(cancelMessage)) }
    try {
        withTimeoutOrNull(ms) { deferred.await() }
    } finally {
        remove?.invoke()
    }
}

suspend fun <T> pollOAuthDeviceCodeFlow(
    intervalSeconds: Long? = null,
    expiresInSeconds: Long? = null,
    waitBeforeFirstPoll: Boolean = false,
    signal: RunSignal? = null,
    now: () -> Long = ::defaultNowMs,
    poll: suspend () -> DeviceCodePollResult<T>,
): T {
    val deadline = expiresInSeconds?.let { now() + it * 1000 } ?: Long.MAX_VALUE
    var intervalMs = maxOf(MINIMUM_INTERVAL_MS, (intervalSeconds ?: DEFAULT_POLL_INTERVAL_SECONDS) * 1000)
    var slowDownResponses = 0

    if (waitBeforeFirstPoll) {
        val remainingMs = deadline - now()
        if (remainingMs > 0) {
            abortableSleep(minOf(intervalMs, remainingMs), signal, OAUTH_CANCEL_MESSAGE)
        }
    }

    while (now() < deadline) {
        if (signal?.isAborted == true) throw IllegalStateException(OAUTH_CANCEL_MESSAGE)
        when (val result = poll()) {
            is DeviceCodePollResult.Complete -> return result.value
            is DeviceCodePollResult.Failed -> throw IllegalStateException(result.message)
            is DeviceCodePollResult.SlowDown -> {
                slowDownResponses += 1
                val serverInterval = result.intervalSeconds
                intervalMs = if (serverInterval != null && serverInterval > 0) {
                    maxOf(MINIMUM_INTERVAL_MS, serverInterval * 1000)
                } else {
                    maxOf(MINIMUM_INTERVAL_MS, intervalMs + SLOW_DOWN_INTERVAL_INCREMENT_MS)
                }
            }

            DeviceCodePollResult.Pending -> Unit
        }

        val remainingMs = deadline - now()
        if (remainingMs <= 0) break
        abortableSleep(minOf(intervalMs, remainingMs), signal, OAUTH_CANCEL_MESSAGE)
    }

    throw IllegalStateException(
        if (slowDownResponses > 0) OAUTH_SLOW_DOWN_TIMEOUT_MESSAGE else OAUTH_DEVICE_TIMEOUT_MESSAGE,
    )
}
