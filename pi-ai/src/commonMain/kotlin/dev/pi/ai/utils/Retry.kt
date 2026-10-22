package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.RunSignal
import dev.pi.ai.StopReason
import kotlin.math.pow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object Retry {
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

    fun isRetryableAssistantError(message: AssistantMessage): Boolean {
        if (message.stopReason != StopReason.Error) return false
        val errorMessage = message.errorMessage
        if (errorMessage.isNullOrEmpty()) return false
        if (nonRetryableProviderLimitPattern.containsMatchIn(errorMessage)) return false
        return retryableProviderErrorPattern.containsMatchIn(errorMessage)
    }

    fun retryDelayMs(baseDelayMs: Long, maxAgentDelayMs: Long, attempt: Int): Long {
        val exponent = (attempt - 1).coerceAtLeast(0)
        val delay = baseDelayMs.toDouble() * 2.0.pow(exponent)
        val safeDelay = if (delay.isFinite() && delay <= MAX_SAFE_INTEGER) delay.toLong() else MAX_SAFE_INTEGER.toLong()
        return minOf(safeDelay, maxAgentDelayMs)
    }

    suspend fun sleep(ms: Long, signal: RunSignal?) {
        if (signal == null) {
            if (ms > 0) delay(ms)
            return
        }
        signal.throwIfAborted()
        if (ms <= 0) return
        val aborted = CompletableDeferred<Unit>()
        val unsubscribe = signal.onAbort { aborted.complete(Unit) }
        try {
            withTimeoutOrNull(ms) { aborted.await() }
        } finally {
            unsubscribe()
        }
        signal.throwIfAborted()
    }
}

private val nonRetryableProviderLimitPattern: Regex = providerErrorPattern(
    listOf(
        "GoUsageLimitError",
        "FreeUsageLimitError",
        "Monthly usage limit reached",
        "available balance",
        "insufficient_quota",
        "out of budget",
        "quota exceeded",
        "billing",
        "subscription_sharing_usage_limit_exceeded",
        "subscription_sharing_workplace_individual_usage_limit_exceeded",
    ),
)

private val retryableProviderErrorPattern: Regex = providerErrorPattern(
    listOf(
        "overloaded",
        "server_busy",
        "servers are currently busy",
        "currently experiencing high demand",
        "model is at capacity",
        "rate.?limit",
        "too many requests",
        "(?<![a-z0-9])(?:429|500|502|503|504|520|524)(?![a-z0-9])",
        "service.?(?:is.?)?(?:temporarily.?)?unavailable",
        "server.?error",
        "internal.?error",
        "provider.?returned.?(?:an.?)?error",
        "exceeded request buffer limit while retrying upstream",
        "unable to forward upstream",
        "upstream.?request.?failed",
        "upstream_stream_read_error",
        "stream was interrupted",
        "http/?2 stream failed",
        "network.?error",
        "connection.?error",
        "connection.?refused",
        "connection.?lost",
        "other side closed",
        "fetch failed",
        "request to \\S+ failed, reason:",
        "getaddrinfo",
        "ENOTFOUND",
        "EAI_AGAIN",
        "upstream.?connect",
        "reset before headers",
        "socket hang up",
        "socket connection was closed",
        "timed? out",
        "timeout",
        "terminated",
        "websocket.?closed",
        "websocket.?error",
        "ended without",
        "stream ended before message_stop",
        "stream ended before a terminal response event",
        "http2 request did not get a response",
        "stream disconnected before completion",
        "pending stream has been canceled",
        "retry delay",
        "you can retry your request",
        "try your request again",
        "please retry your request",
        "ResourceExhausted",
        "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_unavailable",
    ),
)

private fun providerErrorPattern(patterns: List<String>): Regex =
    Regex(patterns.joinToString("|"), RegexOption.IGNORE_CASE)
