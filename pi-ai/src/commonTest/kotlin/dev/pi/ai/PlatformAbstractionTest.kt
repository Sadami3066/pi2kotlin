package dev.pi.ai

import dev.pi.ai.platform.PlatformAtomicBoolean
import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.platform.platformEnv
import dev.pi.ai.platform.platformSecureRandomBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformAbstractionTest {
    @Test
    fun currentTimeMillisReturnsPlausibleWallClockAndIsNonDecreasing() {
        val first = platformCurrentTimeMillis()
        val second = platformCurrentTimeMillis()
        assertTrue(first > 1_500_000_000_000L)
        assertTrue(second >= first)
        assertTrue(second - first < 60_000L)
    }

    @Test
    fun secureRandomReturnsRequestedLengthAndDistinctValues() {
        val first = platformSecureRandomBytes(32)
        val second = platformSecureRandomBytes(32)
        assertEquals(32, first.size)
        assertEquals(32, second.size)
        assertFalse(first.all { it == 0.toByte() })
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun secureRandomHandlesEmptyAndSingleByteRequests() {
        assertEquals(0, platformSecureRandomBytes(0).size)
        assertEquals(1, platformSecureRandomBytes(1).size)
    }

    @Test
    fun atomicBooleanCompareAndSetSemantics() {
        val flag = PlatformAtomicBoolean(false)
        assertFalse(flag.get())
        assertTrue(flag.compareAndSet(false, true))
        assertTrue(flag.get())
        assertFalse(flag.compareAndSet(false, true))
        assertTrue(flag.compareAndSet(true, false))
        assertFalse(flag.get())
        flag.set(true)
        assertTrue(flag.get())
    }

    @Test
    fun platformEnvResolvesMissingVariablesToNull() {
        assertNull(platformEnv("PI_PLATFORM_ABSTRACTION_TEST_MISSING_VARIABLE"))
    }
}
