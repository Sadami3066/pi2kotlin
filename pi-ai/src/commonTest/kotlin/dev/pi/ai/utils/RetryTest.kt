package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.StopReason
import dev.pi.ai.Usage
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetryTest {
    @Test
    fun ignoresMessagesThatAreNotErrors() {
        assertFalse(Retry.isRetryableAssistantError(message(StopReason.Stop)))
        assertFalse(Retry.isRetryableAssistantError(message(StopReason.ToolUse)))
        assertFalse(Retry.isRetryableAssistantError(message(StopReason.Aborted, "overloaded")))
    }

    @Test
    fun ignoresErrorsWithoutMessage() {
        assertFalse(Retry.isRetryableAssistantError(message(StopReason.Error)))
        assertFalse(Retry.isRetryableAssistantError(message(StopReason.Error, "")))
    }

    @Test
    fun matchesTransientProviderErrors() = assertRetryable(
        "overloaded_error",
        "server_busy",
        "The servers are currently busy",
        "The system is currently experiencing high demand and cannot process your request.",
        "model is at capacity",
        "rate limit exceeded",
        "Too many requests",
        "429 status code (no body)",
        "500 status code (no body)",
        "520 status code (no body)",
        "524 status code (no body)",
        "The service is temporarily unavailable. Please retry later.",
        "Service unavailable: The service is temporarily unavailable.",
        "internal server error",
        "provider returned error",
        "The upstream provider returned an error",
        "Error: exceeded request buffer limit while retrying upstream",
        "unknown: Unable to forward upstream stream",
        "upstream_error: Upstream request failed",
        "upstream_stream_read_error: Upstream response stream was interrupted",
        "stream was interrupted",
        "Upstream HTTP/2 stream failed",
        "network error",
        "connection refused",
        "other side closed",
        "fetch failed",
        "getaddrinfo ENOTFOUND api.example.com",
        "EAI_AGAIN api.example.com",
        "connect ENOTFOUND api.example.com",
        "socket hang up",
        "The socket connection was closed unexpectedly.",
        "request timed out",
        "request to https://oauth2.googleapis.com/token failed, reason:",
        "request to https://oauth2.googleapis.com/token failed, reason: connect ECONNREFUSED 127.0.0.1:9",
        "The pending stream has been canceled",
        "The pending stream has been canceled (caused by: getaddrinfo ENOTFOUND bedrock-runtime.us-east-1.amazonaws.com)",
        "stream ended without finish_reason",
        "Anthropic stream ended before message_stop",
        "OpenAI Responses stream ended before a terminal response event",
        "stream disconnected before completion: stream closed before response.completed",
        "retry delay",
        "An error occurred while processing your request. You can retry your request, or contact us.",
        "The system encountered an unexpected error during processing. Try your request again.",
        "ResourceExhausted: Worker local total request limit reached (288/48)",
        "subscription_sharing_usage_unavailable: Usage cannot be checked.",
        "subscription_sharing_user_unavailable: User cannot be loaded.",
    )

    @Test
    fun keepsProviderLimitErrorsNonRetryable() = assertNotRetryable(
        "429 quota exceeded",
        "GoUsageLimitError",
        "FreeUsageLimitError",
        "Monthly usage limit reached",
        "Please enable available balance usage to continue",
        "insufficient_quota",
        "out of budget",
        "quota exceeded",
        "billing",
        """OpenAI API error (429): {"code":"subscription_sharing_usage_limit_exceeded","message":"Usage limit reached."}""",
        """OpenAI API error (429): {"code":"subscription_sharing_workplace_individual_usage_limit_exceeded","message":"Usage limit reached."}""",
    )

    @Test
    fun matchesErrorTextCaseInsensitively() = assertRetryable(
        "OVERLOADED",
        "Rate Limit",
        "SOCKET HANG UP",
        "SERVICE UNAVAILABLE",
    )

    @Test
    fun doesNotMatchStatusCodesInsideLongerNumbersOrIds() = assertNotRetryable(
        """401 {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},"request_id":"req_011CU503xyz"}""",
        "400 image exceeds 5 MB maximum: 5316784 bytes > 5242880 bytes",
        "request id 15002 failed",
    )

    @Test
    fun computesExponentialRetryDelay() {
        assertEquals(2_000L, Retry.retryDelayMs(2_000, 60_000, 1))
        assertEquals(4_000L, Retry.retryDelayMs(2_000, 60_000, 2))
        assertEquals(16_000L, Retry.retryDelayMs(2_000, 60_000, 4))
    }

    @Test
    fun capsRetryDelay() {
        assertEquals(60_000L, Retry.retryDelayMs(2_000, 60_000, 6))
        assertEquals(5_000L, Retry.retryDelayMs(2_000, 5_000, 5))
        assertEquals(0L, Retry.retryDelayMs(2_000, 0, 5))
        assertEquals(60_000L, Retry.retryDelayMs(2_000, 60_000, 1_000))
    }

    @Test
    fun treatsNonPositiveAttemptAsFirstAttempt() {
        assertEquals(2_000L, Retry.retryDelayMs(2_000, 60_000, 0))
        assertEquals(2_000L, Retry.retryDelayMs(2_000, 60_000, -3))
    }

    @Test
    fun sleepWaitsForDelay() = runTest {
        var completed = false
        val job = launch {
            Retry.sleep(1_000, null)
            completed = true
        }
        testScheduler.runCurrent()
        assertFalse(completed)
        testScheduler.advanceTimeBy(999)
        testScheduler.runCurrent()
        assertFalse(completed)
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        assertTrue(completed)
        job.join()
    }

    @Test
    fun sleepStopsWhenSignalAborts() = runTest {
        val signal = RunSignal()
        var failure: Throwable? = null
        val job = launch {
            try {
                Retry.sleep(60_000, signal)
            } catch (error: Throwable) {
                failure = error
            }
        }
        testScheduler.runCurrent()
        signal.abort("user aborted")
        job.join()
        val aborted = assertIs<RequestAbortedException>(failure)
        assertEquals("user aborted", aborted.reason)
    }

    @Test
    fun sleepFailsImmediatelyWhenSignalAlreadyAborted() = runTest {
        val signal = RunSignal()
        signal.abort("gone")
        val aborted = assertFailsWith<RequestAbortedException> {
            Retry.sleep(60_000, signal)
        }
        assertEquals("gone", aborted.reason)
    }

    @Test
    fun sleepCompletesImmediatelyForZeroDelay() = runTest {
        Retry.sleep(0, RunSignal())
        Retry.sleep(-5, RunSignal())
    }

    @Test
    fun sleepWithoutSignalIsCancellable() = runTest {
        var completed = false
        val job = launch {
            Retry.sleep(60_000, null)
            completed = true
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertFalse(completed)
    }

    @Test
    fun successfulMessageHasNoError() {
        assertNull(message(StopReason.Stop).errorMessage)
    }

    private fun assertRetryable(vararg messages: String) {
        for (text in messages) {
            assertTrue(Retry.isRetryableAssistantError(message(StopReason.Error, text)), "expected retryable: $text")
        }
    }

    private fun assertNotRetryable(vararg messages: String) {
        for (text in messages) {
            assertFalse(Retry.isRetryableAssistantError(message(StopReason.Error, text)), "expected non-retryable: $text")
        }
    }

    private fun message(stopReason: StopReason, errorMessage: String? = null): AssistantMessage = AssistantMessage(
        content = mutableListOf(),
        api = "test",
        provider = "test",
        model = "test-model",
        usage = Usage.empty(),
        stopReason = stopReason,
        errorMessage = errorMessage,
        timestamp = 0L,
    )
}
