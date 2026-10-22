package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.StopReason

private val OVERFLOW_PATTERNS: List<Regex> = listOf(
    Regex("prompt (?:is )?too long", RegexOption.IGNORE_CASE),
    Regex("prompt exceeds max length", RegexOption.IGNORE_CASE),
    Regex("request_too_large", RegexOption.IGNORE_CASE),
    Regex("input is too long for requested model", RegexOption.IGNORE_CASE),
    Regex("exceeds the context window", RegexOption.IGNORE_CASE),
    Regex("exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))", RegexOption.IGNORE_CASE),
    Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE),
    Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE),
    Regex("reduce the length of the messages", RegexOption.IGNORE_CASE),
    Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE),
    Regex("exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?", RegexOption.IGNORE_CASE),
    Regex("input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)", RegexOption.IGNORE_CASE),
    Regex("exceeds the limit of \\d+", RegexOption.IGNORE_CASE),
    Regex("exceeds the available context size", RegexOption.IGNORE_CASE),
    Regex("greater than the context length", RegexOption.IGNORE_CASE),
    Regex("context window exceeds limit", RegexOption.IGNORE_CASE),
    Regex("exceeded model token limit", RegexOption.IGNORE_CASE),
    Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE),
    Regex("prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?", RegexOption.IGNORE_CASE),
    Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE),
    Regex("prompt too long; exceeded (?:max )?context length", RegexOption.IGNORE_CASE),
    Regex("range of input length should be", RegexOption.IGNORE_CASE),
    Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE),
    Regex("too many tokens", RegexOption.IGNORE_CASE),
    Regex("token limit exceeded", RegexOption.IGNORE_CASE),
    Regex("^(?:[\\w -]+ API error \\(413\\):|413(?:\\s|:|$))", RegexOption.IGNORE_CASE),
)

private val CEREBRAS_BODYLESS_OVERFLOW_PATTERN = Regex("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", RegexOption.IGNORE_CASE)

private val NON_OVERFLOW_PATTERNS: List<Regex> = listOf(
    Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
    Regex("rate limit", RegexOption.IGNORE_CASE),
    Regex("too many requests", RegexOption.IGNORE_CASE),
)

fun isContextOverflow(message: AssistantMessage, contextWindow: Long? = null): Boolean {
    val errorMessage = message.errorMessage
    if (message.stopReason == StopReason.Error && !errorMessage.isNullOrEmpty()) {
        val isNonOverflow = NON_OVERFLOW_PATTERNS.any { it.containsMatchIn(errorMessage) }
        if (!isNonOverflow) {
            if (OVERFLOW_PATTERNS.any { it.containsMatchIn(errorMessage) }) return true
            if (message.provider == "cerebras" && CEREBRAS_BODYLESS_OVERFLOW_PATTERN.containsMatchIn(errorMessage)) return true
        }
    }

    if (contextWindow != null && contextWindow != 0L && message.stopReason == StopReason.Stop) {
        val inputTokens = message.usage.input + message.usage.cacheRead
        if (inputTokens > contextWindow) return true
    }

    if (contextWindow != null && contextWindow != 0L && message.stopReason == StopReason.Length && message.usage.output == 0L) {
        val inputTokens = message.usage.input + message.usage.cacheRead
        if (inputTokens.toDouble() >= contextWindow.toDouble() * 0.99) return true
    }

    return false
}

fun isRecoverableLength(message: AssistantMessage, desiredMaxOutput: Long): Boolean =
    message.stopReason == StopReason.Length && desiredMaxOutput > 0 && message.usage.output < desiredMaxOutput

fun getOverflowPatterns(): List<Regex> = OVERFLOW_PATTERNS.toList()
