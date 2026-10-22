package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.StopReason
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverflowTest {
    private fun usage(
        input: Long = 0,
        output: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
    ): Usage = Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + output + cacheRead + cacheWrite,
        cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
    )

    private fun errorMessage(
        message: String,
        provider: String = "ollama",
        stopReason: StopReason = StopReason.Error,
    ): AssistantMessage = AssistantMessage(
        content = mutableListOf(),
        api = "openai-completions",
        provider = provider,
        model = "qwen3.5:35b",
        usage = usage(),
        stopReason = stopReason,
        errorMessage = message,
        timestamp = 1,
    )

    private fun lengthStop(
        input: Long,
        cacheRead: Long,
        output: Long = 0,
        cacheWrite: Long = 0,
        provider: String = "test-provider",
        model: String = "test-model",
    ): AssistantMessage = AssistantMessage(
        content = mutableListOf(),
        api = "openai-completions",
        provider = provider,
        model = model,
        usage = usage(input = input, output = output, cacheRead = cacheRead, cacheWrite = cacheWrite),
        stopReason = StopReason.Length,
        timestamp = 1,
    )

    @Test
    fun detectsProviderIndependentRequestSizeOverflow() {
        val messages = listOf(
            "github-copilot API error (413): {\"message\":\"failed to parse request\",\"code\":\"\"}",
            "custom-provider API error (413): Payload Too Large",
            "413 {\"error\":{\"message\":\"failed to parse request\",\"code\":\"\"}}",
            "413",
        )
        for (message in messages) {
            val assistant = errorMessage(message)
            assertTrue(isContextOverflow(assistant, 1_000_000), message)
            assertTrue(isContextOverflow(assistant), message)
        }
    }

    @Test
    fun doesNotMistakeUnrelatedErrorsForRequestSizeOverflow() {
        val messages = listOf(
            "github-copilot API error (400): {\"message\":\"failed to parse request\",\"code\":\"\"}",
            "custom-provider API error (429): Too many requests",
            "github-copilot API error (400): {\"message\":\"failed to parse request: invalid value 413\"}",
            "custom-provider API error (400): upstream mentioned OpenAI API error (413): invalid input",
            "custom-provider API error (4130): invalid input",
            "4130 failed to parse request",
        )
        for (message in messages) assertFalse(isContextOverflow(errorMessage(message)), message)
    }

    @Test
    fun limitsHttpStatusMatchingToFailedResponses() {
        val message = errorMessage("OpenAI API error (413): {\"message\":\"failed to parse request\"}")
        assertFalse(isContextOverflow(message.copy(stopReason = StopReason.Aborted)))
        assertFalse(isContextOverflow(message.copy(stopReason = StopReason.Stop)))
    }

    @Test
    fun detectsProviderSpecificOverflowErrors() {
        val messages = listOf(
            "400 `prompt too long; exceeded max context length by 100918 tokens`",
            "400 {\"code\":\"1261\",\"message\":\"Prompt too long\"}",
            "400 {\"code\":\"1261\",\"message\":\"Prompt exceeds max length\"}",
            "400 The input (516368 tokens) is longer than the model's context length (262144 tokens).",
            "Error: 503 litellm.ServiceUnavailableError: litellm.MidStreamFallbackError: litellm.APIConnectionError: APIConnectionError: OpenAIException - Requested token count exceeds the model's maximum context length of 131072 tokens.",
            "Error: 400 Input length (265330) exceeds model's maximum context length (262144).",
            "Provider returned error: Input length 131393 exceeds the maximum allowed input length of 131040 tokens.",
            "400 Prompt has 256468 tokens, but the configured context size is 256000 tokens",
            "Prompt has 5,958,968 tokens, but the configured context size is 256,000 tokens",
            "prompt is too long: 213462 tokens > 200000 maximum",
            "Your input exceeds the context window of this model",
            "input token count (1196265) exceeds the maximum number of tokens allowed (1048575)",
            "This model's maximum prompt length is 131072 but the request contains 537812 tokens",
            "Please reduce the length of the messages or completion",
            "This endpoint's maximum context length is 131072 tokens. However, you requested about 200000 tokens",
            "the request exceeds the available context size, try increasing it",
            "tokens to keep from the initial prompt is greater than the context length",
            "prompt token count of 200000 exceeds the limit of 131072",
            "invalid params, context window exceeds limit",
            "Your request exceeded model token limit: 131393 (requested: 131040)",
            "Prompt contains 200000 tokens which is too large for model with 131072 maximum context length",
            "Range of input length should be [1, 1048576]",
            "context_length_exceeded",
            "too many tokens",
            "token limit exceeded",
        )
        for (message in messages) assertTrue(isContextOverflow(errorMessage(message), 1_000_000), message)
    }

    @Test
    fun doesNotTreatNonOverflowErrorsAsOverflow() {
        val messages = listOf(
            "500 `model runner crashed unexpectedly`",
            "Throttling error: Too many tokens, please wait before trying again.",
            "Service unavailable: The service is temporarily unavailable.",
            "Rate limit exceeded, please retry after 30 seconds.",
            "Too many requests. Please slow down.",
            "429 Too Many Requests",
        )
        for (message in messages) assertFalse(isContextOverflow(errorMessage(message), 200_000), message)
    }

    @Test
    fun onlyTreatsBodyless400ErrorsAsOverflowForCerebras() {
        val message = "400 status code (no body)"
        assertTrue(isContextOverflow(errorMessage(message, provider = "cerebras"), 131_072))
        assertFalse(isContextOverflow(errorMessage(message, provider = "opencode-go"), 1_000_000))
    }

    @Test
    fun detectsSilentOverflowFromUsage() {
        val overflow = AssistantMessage(
            content = mutableListOf(),
            api = "openai-completions",
            provider = "zai",
            model = "m",
            usage = usage(input = 1200, output = 10),
            stopReason = StopReason.Stop,
            timestamp = 1,
        )
        assertTrue(isContextOverflow(overflow, 1000))
        assertFalse(isContextOverflow(overflow))
        assertFalse(isContextOverflow(overflow.copy(stopReason = StopReason.Length), 1000))
    }

    @Test
    fun detectsLengthStopOverflowWhenInputFillsContextAndOutputIsZero() {
        val message = lengthStop(input = 58, cacheRead = 1_048_512, provider = "xiaomi", model = "mimo-v2.6-pro")
        assertTrue(isContextOverflow(message, 1_048_576))
        assertFalse(isContextOverflow(lengthStop(input = 1000, cacheRead = 0, output = 4096), 200_000))
        assertFalse(isContextOverflow(lengthStop(input = 100, cacheRead = 0), 200_000))
    }

    @Test
    fun isRecoverableLengthComparesOutputWithDesiredLimit() {
        val message = lengthStop(input = 3, cacheRead = 253_584, output = 16, cacheWrite = 25_554, provider = "openai", model = "gpt-5.6-sol")
        assertTrue(isRecoverableLength(message, 128_000))
        assertFalse(isRecoverableLength(lengthStop(input = 4062, cacheRead = 0, output = 1024), 1024))
        assertTrue(isRecoverableLength(lengthStop(input = 100, cacheRead = 0), 128_000))
        assertFalse(isRecoverableLength(lengthStop(input = 100, cacheRead = 0), 0))
        assertFalse(isRecoverableLength(lengthStop(input = 100, cacheRead = 0).copy(stopReason = StopReason.Stop), 128_000))
    }

    @Test
    fun exposesAllOverflowPatterns() {
        val patterns = getOverflowPatterns()
        assertEquals(26, patterns.size)
        assertEquals("prompt (?:is )?too long", patterns.first().pattern)
    }
}
