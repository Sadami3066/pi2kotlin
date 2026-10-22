package dev.pi.ai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageJsonTest {
    private val assistantJson = """
        {
          "role": "assistant",
          "content": [
            {"type": "thinking", "thinking": "Let me check.", "thinkingSignature": "reasoning_content"},
            {"type": "toolCall", "id": "call_1", "name": "fetch_content", "arguments": {"url": "https://example.com"}}
          ],
          "api": "openai-completions",
          "provider": "deepseek",
          "model": "deepseek-v4-flash",
          "usage": {
            "input": 21940,
            "output": 86,
            "cacheRead": 0,
            "cacheWrite": 0,
            "totalTokens": 22026,
            "cost": {"input": 0.003, "output": 0.000024, "cacheRead": 0, "cacheWrite": 0, "total": 0.003024}
          },
          "stopReason": "toolUse",
          "timestamp": 1781005062563,
          "responseId": "6127fae9-ac87-4ce3-b1bc-1991336d0140"
        }
    """.trimIndent()

    @Test
    fun decodesAssistantMessage() {
        val message = PiJson.decodeFromString(Message.serializer(), assistantJson)
        val assistant = assertIs<AssistantMessage>(message)
        assertEquals("deepseek", assistant.provider)
        assertEquals(StopReason.ToolUse, assistant.stopReason)
        assertEquals(2, assistant.content.size)
        val thinking = assertIs<ThinkingContent>(assistant.content[0])
        assertEquals("reasoning_content", thinking.thinkingSignature)
        val toolCall = assertIs<ToolCall>(assistant.content[1])
        assertEquals("fetch_content", toolCall.name)
        assertEquals("https://example.com", toolCall.arguments["url"]!!.jsonPrimitive.content)
        assertEquals(22026L, assistant.usage.totalTokens)
    }

    @Test
    fun reencodesAssistantMessageLosslessly() {
        val decoded = PiJson.decodeFromString(Message.serializer(), assistantJson)
        val encoded = PiJson.encodeToJsonElement(Message.serializer(), decoded)
        val redecode = PiJson.decodeFromString(Message.serializer(), encoded.toString())
        assertEquals(decoded, redecode)
    }

    @Test
    fun nullableFieldsAreOmittedFromJson() {
        val decoded = PiJson.decodeFromString(Message.serializer(), assistantJson)
        val encoded = PiJson.encodeToJsonElement(Message.serializer(), decoded).toString()
        assertTrue(!encoded.contains("responseModel"))
        assertTrue(!encoded.contains("null"))
    }

    @Test
    fun decodesUserMessageWithTextAndBlocks() {
        val single = """{"role":"user","content":"hello","timestamp":1}"""
        val singleMessage = assertIs<UserMessage>(PiJson.decodeFromString(Message.serializer(), single))
        assertEquals(PlainTextContent("hello"), singleMessage.content)

        val blocks = """
            {"role":"user","content":[{"type":"text","text":"hi"},{"type":"image","data":"aGk=","mimeType":"image/png"}],"timestamp":2}
        """.trimIndent()
        val blockMessage = assertIs<UserMessage>(PiJson.decodeFromString(Message.serializer(), blocks))
        assertEquals(2, (blockMessage.content as BlockContent).blocks.size)
        assertEquals(
            PiJson.parseToJsonElement(blocks),
            PiJson.encodeToJsonElement(Message.serializer(), blockMessage),
        )
    }

    @Test
    fun decodesToolResultMessage() {
        val json = """
            {"role":"toolResult","toolCallId":"call_1","toolName":"read","content":[{"type":"text","text":"ok"}],"isError":false,"timestamp":3}
        """.trimIndent()
        val message = assertIs<ToolResultMessage>(PiJson.decodeFromString(Message.serializer(), json))
        assertEquals("read", message.toolName)
        assertEquals(false, message.isError)
        assertEquals(PiJson.parseToJsonElement(json), PiJson.encodeToJsonElement(Message.serializer(), message))
    }

    @Test
    fun decodesSystemMessageWithTools() {
        val json = """
            {"role":"system","content":"You are pi.","toolsAdded":[{"name":"read","description":"Read a file","parameters":{"type":"object","properties":{}}}],"timestamp":4}
        """.trimIndent()
        val message = assertIs<SystemMessage>(PiJson.decodeFromString(Message.serializer(), json))
        assertEquals("read", message.toolsAdded!!.single().name)
        assertEquals(PiJson.parseToJsonElement(json), PiJson.encodeToJsonElement(Message.serializer(), message))
    }
}

class AssistantMessageEventStreamTest {
    private fun partial() = AssistantMessage(
        content = mutableListOf(),
        api = "faux",
        provider = "faux",
        model = "faux",
        usage = Usage.empty(),
        stopReason = StopReason.Pending,
        timestamp = System.currentTimeMillis(),
    )

    @Test
    fun collectsEventsAndResult() = runTest {
        val stream = createAssistantMessageEventStream()
        val message = partial()
        val seen = mutableListOf<AssistantMessageEvent>()
        val consumer = launch { stream.events.toList(seen) }
        stream.push(StartEvent(message))
        stream.push(TextStartEvent(0, message))
        stream.push(TextDeltaEvent(0, "hello", message))
        stream.push(DoneEvent(StopReason.Stop, message))
        consumer.join()
        assertEquals("hello", ((seen[2]) as TextDeltaEvent).delta)
        assertTrue(seen.last() is DoneEvent)
        assertEquals(message, stream.result())
    }

    @Test
    fun serializesEventDiscriminator() {
        val message = partial()
        val encoded = PiJson.encodeToJsonElement(AssistantMessageEvent.serializer(), TextDeltaEvent(0, "hey", message))
        assertEquals("text_delta", encoded.jsonObject["type"]!!.jsonPrimitive.content)
        val decoded = PiJson.decodeFromString(AssistantMessageEvent.serializer(), encoded.toString())
        assertIs<TextDeltaEvent>(decoded)
    }

    @Test
    fun eventJsonKeepsCamelCaseFields() {
        val message = partial()
        val encoded = PiJson.encodeToJsonElement(
            AssistantMessageEvent.serializer(),
            DoneEvent(StopReason.Stop, message),
        ).jsonObject
        assertEquals("done", encoded["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive("stop"), encoded["reason"])
    }
}
