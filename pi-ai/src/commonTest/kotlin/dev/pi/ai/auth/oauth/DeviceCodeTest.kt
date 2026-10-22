package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCodeTest {
    @Test
    fun returnsCompletedValue() = runTest {
        var now = 0L
        var polls = 0
        val value = pollOAuthDeviceCodeFlow(
            intervalSeconds = 1,
            expiresInSeconds = 60,
            now = { now },
            poll = {
                polls += 1
                DeviceCodePollResult.Complete("token")
            },
        )
        assertEquals("token", value)
        assertEquals(1, polls)
    }

    @Test
    fun pollsUntilComplete() = runTest {
        var polls = 0
        val value = pollOAuthDeviceCodeFlow(
            intervalSeconds = 1,
            expiresInSeconds = 60,
            now = { testScheduler.currentTime },
            poll = {
                polls += 1
                when (polls) {
                    1, 2 -> DeviceCodePollResult.Pending
                    else -> DeviceCodePollResult.Complete("token")
                }
            },
        )
        assertEquals("token", value)
        assertEquals(3, polls)
        assertEquals(2000L, testScheduler.currentTime)
    }

    @Test
    fun waitsBeforeFirstPollWhenRequested() = runTest {
        val pollTimes = mutableListOf<Long>()
        pollOAuthDeviceCodeFlow(
            intervalSeconds = 1,
            expiresInSeconds = 60,
            waitBeforeFirstPoll = true,
            now = { testScheduler.currentTime },
            poll = {
                pollTimes += testScheduler.currentTime
                DeviceCodePollResult.Complete("token")
            },
        )
        assertEquals(listOf(1000L), pollTimes)
    }

    @Test
    fun appliesServerProvidedSlowDownInterval() = runTest {
        var polls = 0
        val pollTimes = mutableListOf<Long>()
        pollOAuthDeviceCodeFlow(
            intervalSeconds = 1,
            expiresInSeconds = 60,
            now = { testScheduler.currentTime },
            poll = {
                pollTimes += testScheduler.currentTime
                polls += 1
                when (polls) {
                    1 -> DeviceCodePollResult.SlowDown(intervalSeconds = 2)
                    else -> DeviceCodePollResult.Complete("token")
                }
            },
        )
        assertEquals(listOf(0L, 2000L), pollTimes)
    }

    @Test
    fun increasesIntervalOnSlowDownWithoutServerInterval() = runTest {
        var polls = 0
        val pollTimes = mutableListOf<Long>()
        pollOAuthDeviceCodeFlow(
            intervalSeconds = 1,
            expiresInSeconds = 60,
            now = { testScheduler.currentTime },
            poll = {
                pollTimes += testScheduler.currentTime
                polls += 1
                when (polls) {
                    1 -> DeviceCodePollResult.SlowDown()
                    else -> DeviceCodePollResult.Complete("token")
                }
            },
        )
        assertEquals(listOf(0L, 6000L), pollTimes)
    }

    @Test
    fun failsWithPendingErrorMessage() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                intervalSeconds = 1,
                expiresInSeconds = 60,
                now = { testScheduler.currentTime },
                poll = { DeviceCodePollResult.Failed("Device flow failed: access_denied") },
            )
        }
        assertEquals("Device flow failed: access_denied", error.message)
    }

    @Test
    fun timesOutWithoutSlowDown() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                intervalSeconds = 1,
                expiresInSeconds = 0,
                now = { testScheduler.currentTime },
                poll = { DeviceCodePollResult.Pending },
            )
        }
        assertEquals(OAUTH_DEVICE_TIMEOUT_MESSAGE, error.message)
    }

    @Test
    fun timesOutWithSlowDownMessageAfterSlowDown() = runTest {
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                intervalSeconds = 1,
                expiresInSeconds = 2,
                now = { testScheduler.currentTime },
                poll = { DeviceCodePollResult.SlowDown() },
            )
        }
        assertEquals(OAUTH_SLOW_DOWN_TIMEOUT_MESSAGE, error.message)
    }

    @Test
    fun throwsWhenAborted() = runTest {
        val signal = RunSignal().apply { abort() }
        val error = assertFailsWith<IllegalStateException> {
            pollOAuthDeviceCodeFlow(
                intervalSeconds = 1,
                expiresInSeconds = 60,
                signal = signal,
                now = { testScheduler.currentTime },
                poll = { DeviceCodePollResult.Pending },
            )
        }
        assertEquals(OAUTH_CANCEL_MESSAGE, error.message)
    }
}
