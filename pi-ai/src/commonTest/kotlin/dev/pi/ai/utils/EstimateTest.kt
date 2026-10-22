package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.Context
import dev.pi.ai.ImageContent
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolReference
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class EstimateTest {
    private fun usage(
        input: Long = 0,
        output: Long = 0,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        totalTokens: Long = 0,
    ): Usage = Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = totalTokens,
        cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
    )

    private fun tool(name: String): Tool = Tool(
        name = name,
        description = "$name tool",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
        },
    )

    private fun assistant(
        timestamp: Long,
        totalTokens: Long,
        stopReason: StopReason = StopReason.Stop,
    ): AssistantMessage = AssistantMessage(
        content = mutableListOf(TextContent("kept")),
        api = "openai-responses",
        provider = "openai",
        model = "test-model",
        usage = usage(input = totalTokens, totalTokens = totalTokens),
        stopReason = stopReason,
        timestamp = timestamp,
    )

    @Test
    fun calculateContextTokensPrefersTotalTokensAndFallsBackToSum() {
        assertEquals(100L, calculateContextTokens(usage(totalTokens = 100)))
        assertEquals(0L, calculateContextTokens(usage()))
        assertEquals(17L, calculateContextTokens(usage(input = 17)))
        assertEquals(10L, calculateContextTokens(usage(input = 1, output = 2, cacheRead = 3, cacheWrite = 4)))
    }

    @Test
    fun estimateTextTokensUsesThreeAndAHalfCharactersPerToken() {
        assertEquals(0L, estimateTextTokens(""))
        assertEquals(2L, estimateTextTokens("xxxx"))
        assertEquals(1000L, estimateTextTokens("x".repeat(3500)))
        assertEquals(4L, estimateTextTokens("hello world"))
    }

    @Test
    fun estimateMessageTokensCountsSystemFieldsToolsAndSections() {
        val message = SystemMessage(
            content = PlainTextContent("base"),
            sections = linkedMapOf("a" to "<a>1</a>"),
            toolsAdded = listOf(tool("a")),
            toolsRemoved = listOf(ToolReference("b")),
            timestamp = 0,
        )
        assertEquals(32L, estimateMessageTokens(message))
    }

    @Test
    fun estimateMessageTokensCountsTextAndImages() {
        assertEquals(2L, estimateMessageTokens(UserMessage(content = PlainTextContent("hello"), timestamp = 1)))
        val withImage = UserMessage(
            content = dev.pi.ai.BlockContent(
                listOf(TextContent("hello"), ImageContent(data = "x", mimeType = "image/png")),
            ),
            timestamp = 1,
        )
        assertEquals(1373L, estimateMessageTokens(withImage))
        val result = ToolResultMessage(
            toolCallId = "1",
            toolName = "read",
            content = listOf(TextContent("out"), ImageContent(data = "x", mimeType = "image/png")),
            isError = false,
            timestamp = 1,
        )
        assertEquals(1373L, estimateMessageTokens(result))
    }

    @Test
    fun estimateMessageTokensCountsAssistantTextThinkingAndToolCalls() {
        val message = AssistantMessage(
            content = mutableListOf(
                TextContent("hello"),
                ThinkingContent("think"),
                ToolCall(id = "1", name = "read", arguments = kotlinx.serialization.json.Json.parseToJsonElement("""{"path":"/x"}""") as JsonObject),
            ),
            api = "a",
            provider = "p",
            model = "m",
            usage = usage(),
            stopReason = StopReason.Stop,
            timestamp = 1,
        )
        assertEquals(8L, estimateMessageTokens(message))
    }

    @Test
    fun estimateContextTokensUsesLatestAssistantUsagePlusTrailingEstimate() {
        val context = normalizeContext(
            Context(
                messages = listOf(
                    assistant(100, 2000),
                    UserMessage(content = PlainTextContent("x".repeat(3500)), timestamp = 200),
                ),
            ),
        )
        assertEquals(
            ContextUsageEstimate(tokens = 3000, usageTokens = 2000, trailingTokens = 1000, lastUsageIndex = 0),
            estimateContextTokens(context.messages),
        )
    }

    @Test
    fun ignoresStaleAssistantUsageAfterNewerMessageIsInsertedBeforeIt() {
        val context = normalizeContext(
            Context(
                systemPrompt = "system",
                messages = listOf(
                    UserMessage(content = PlainTextContent("summary"), timestamp = 200),
                    assistant(100, 9500),
                    UserMessage(content = PlainTextContent("x".repeat(4000)), timestamp = 300),
                ),
            ),
        )
        assertEquals(
            ContextUsageEstimate(tokens = 1149, usageTokens = 0, trailingTokens = 1149, lastUsageIndex = -1),
            estimateContextTokens(context.messages),
        )
    }

    @Test
    fun usesAssistantUsageAgainAfterResponseToInsertedContext() {
        val context = normalizeContext(
            Context(
                messages = listOf(
                    UserMessage(content = PlainTextContent("summary"), timestamp = 200),
                    assistant(100, 9500),
                    UserMessage(content = PlainTextContent("new prompt"), timestamp = 300),
                    assistant(400, 2000),
                    UserMessage(content = PlainTextContent("tail"), timestamp = 500),
                ),
            ),
        )
        assertEquals(
            ContextUsageEstimate(tokens = 2002, usageTokens = 2000, trailingTokens = 2, lastUsageIndex = 3),
            estimateContextTokens(context.messages),
        )
    }

    @Test
    fun ignoresAssistantUsageWithoutTokensOrFromFailedStops() {
        val noTokens = normalizeContext(
            Context(
                messages = listOf(
                    assistant(100, 0),
                    UserMessage(content = PlainTextContent("a"), timestamp = 50),
                ),
            ),
        )
        assertEquals(
            ContextUsageEstimate(tokens = 3, usageTokens = 0, trailingTokens = 3, lastUsageIndex = -1),
            estimateContextTokens(noTokens.messages),
        )
        val failed = normalizeContext(
            Context(
                messages = listOf(
                    assistant(100, 5000, StopReason.Error),
                    UserMessage(content = PlainTextContent("b"), timestamp = 200),
                ),
            ),
        )
        assertEquals(
            ContextUsageEstimate(tokens = 3, usageTokens = 0, trailingTokens = 3, lastUsageIndex = -1),
            estimateContextTokens(failed.messages),
        )
        val aborted = normalizeContext(
            Context(
                messages = listOf(
                    assistant(100, 5000, StopReason.Aborted),
                    UserMessage(content = PlainTextContent("b"), timestamp = 200),
                ),
            ),
        )
        assertEquals(
            ContextUsageEstimate(tokens = 3, usageTokens = 0, trailingTokens = 3, lastUsageIndex = -1),
            estimateContextTokens(aborted.messages),
        )
    }
}
