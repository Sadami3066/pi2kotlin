package dev.pi.ai

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class SmokeSample(val id: String, val count: Int)

class ToolchainSmokeTest {
    @Test
    fun jsonRoundTrip() {
        val json = Json { encodeDefaults = true }
        val sample = SmokeSample("hello", 42)
        assertEquals(sample, json.decodeFromString<SmokeSample>(json.encodeToString(sample)))
    }

    @Test
    fun flowWorks() = runTest {
        assertEquals(listOf(1, 2, 3), flowOf(1, 2, 3).toList())
    }
}
