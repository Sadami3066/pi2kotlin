package io.github.whywood.deepdeep.sdk.ai.api

import io.github.whywood.deepdeep.sdk.ai.AssistantMessage
import io.github.whywood.deepdeep.sdk.ai.Content
import io.github.whywood.deepdeep.sdk.ai.Message
import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.ModelCost
import io.github.whywood.deepdeep.sdk.ai.ModelInputModality
import io.github.whywood.deepdeep.sdk.ai.PlainTextContent
import io.github.whywood.deepdeep.sdk.ai.StopReason
import io.github.whywood.deepdeep.sdk.ai.TextContent
import io.github.whywood.deepdeep.sdk.ai.ToolCall
import io.github.whywood.deepdeep.sdk.ai.ToolResultMessage
import io.github.whywood.deepdeep.sdk.ai.Usage
import io.github.whywood.deepdeep.sdk.ai.UserMessage
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class TransformMessagesTest {
    private val model = Model(
        id = "test-model",
        name = "test-model",
        api = "openai-completions",
        provider = "test",
        baseUrl = "https://example.test",
        input = listOf(ModelInputModality.Text),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        reasoning = false,
        contextWindow = 128000,
        maxTokens = 4096,
    )

    private fun user(text: String, timestamp: Long) =
        UserMessage(content = PlainTextContent(text), timestamp = timestamp)

    private fun call(id: String) = ToolCall(id = id, name = "read", arguments = JsonObject(emptyMap()))

    private fun assistant(timestamp: Long, stopReason: StopReason = StopReason.Stop, vararg calls: ToolCall): AssistantMessage {
        val content = mutableListOf<Content>(TextContent("thinking"))
        content.addAll(calls)
        return AssistantMessage(
            content = content,
            api = "openai-completions",
            provider = "test",
            model = "test-model",
            usage = Usage.empty(),
            stopReason = stopReason,
            timestamp = timestamp,
        )
    }

    private fun toolResult(id: String, timestamp: Long) = ToolResultMessage(
        toolCallId = id,
        toolName = "read",
        content = listOf(TextContent("result")),
        isError = false,
        timestamp = timestamp,
    )

    @Test
    fun dropsOrphanToolResultAtStart() {
        val messages: List<Message> = listOf(
            user("hi", 1),
            toolResult("call-1", 2),
        )
        assertEquals(listOf(user("hi", 1)), transformMessages(messages, model))
    }

    @Test
    fun dropsOrphanToolResultAfterPairedTurn() {
        val messages: List<Message> = listOf(
            user("hi", 1),
            assistant(2, calls = arrayOf(call("call-1"))),
            toolResult("call-1", 3),
            toolResult("call-2", 4),
        )
        val result = transformMessages(messages, model)
        assertEquals(3, result.size)
        assertEquals(emptyList(), result.filterIsInstance<ToolResultMessage>().map { it.toolCallId }.filter { it == "call-2" })
    }

    @Test
    fun keepsPairedToolResult() {
        val messages: List<Message> = listOf(
            user("hi", 1),
            assistant(2, calls = arrayOf(call("call-1"))),
            toolResult("call-1", 3),
        )
        val result = transformMessages(messages, model)
        assertEquals(3, result.size)
        assertEquals(listOf("call-1"), result.filterIsInstance<ToolResultMessage>().map { it.toolCallId })
    }

    @Test
    fun dropsToolResultWhenAssistantWasDroppedForError() {
        val messages: List<Message> = listOf(
            user("hi", 1),
            assistant(2, stopReason = StopReason.Error, calls = arrayOf(call("call-1"))),
            toolResult("call-1", 3),
            user("again", 4),
        )
        val result = transformMessages(messages, model)
        assertEquals(listOf(user("hi", 1), user("again", 4)), result)
    }
}
