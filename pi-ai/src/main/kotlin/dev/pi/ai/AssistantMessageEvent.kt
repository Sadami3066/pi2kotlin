package dev.pi.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable(with = AssistantMessageEventSerializer::class)
sealed interface AssistantMessageEvent

@Serializable
data class StartEvent(val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
data class TextStartEvent(val contentIndex: Int, val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
data class TextDeltaEvent(val contentIndex: Int, val delta: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
data class TextEndEvent(val contentIndex: Int, val content: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
data class ThinkingStartEvent(val contentIndex: Int, val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
data class ThinkingDeltaEvent(val contentIndex: Int, val delta: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
data class ThinkingEndEvent(val contentIndex: Int, val content: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
data class ToolCallStartEvent(val contentIndex: Int, val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
data class ToolCallDeltaEvent(val contentIndex: Int, val delta: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
data class ToolCallEndEvent(
    val contentIndex: Int,
    val toolCall: ToolCall,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
data class DoneEvent(val reason: StopReason, val message: AssistantMessage) : AssistantMessageEvent

@Serializable
data class ErrorEvent(val reason: StopReason, val error: AssistantMessage) : AssistantMessageEvent

object AssistantMessageEventSerializer : KSerializer<AssistantMessageEvent> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: AssistantMessageEvent) {
        val jsonEncoder = encoder as JsonEncoder
        val json = jsonEncoder.json
        val (type, body) = when (value) {
            is StartEvent -> "start" to json.encodeToJsonElement(StartEvent.serializer(), value)
            is TextStartEvent -> "text_start" to json.encodeToJsonElement(TextStartEvent.serializer(), value)
            is TextDeltaEvent -> "text_delta" to json.encodeToJsonElement(TextDeltaEvent.serializer(), value)
            is TextEndEvent -> "text_end" to json.encodeToJsonElement(TextEndEvent.serializer(), value)
            is ThinkingStartEvent -> "thinking_start" to json.encodeToJsonElement(ThinkingStartEvent.serializer(), value)
            is ThinkingDeltaEvent -> "thinking_delta" to json.encodeToJsonElement(ThinkingDeltaEvent.serializer(), value)
            is ThinkingEndEvent -> "thinking_end" to json.encodeToJsonElement(ThinkingEndEvent.serializer(), value)
            is ToolCallStartEvent -> "toolcall_start" to json.encodeToJsonElement(ToolCallStartEvent.serializer(), value)
            is ToolCallDeltaEvent -> "toolcall_delta" to json.encodeToJsonElement(ToolCallDeltaEvent.serializer(), value)
            is ToolCallEndEvent -> "toolcall_end" to json.encodeToJsonElement(ToolCallEndEvent.serializer(), value)
            is DoneEvent -> "done" to json.encodeToJsonElement(DoneEvent.serializer(), value)
            is ErrorEvent -> "error" to json.encodeToJsonElement(ErrorEvent.serializer(), value)
        }
        val result = buildJsonObject {
            put("type", JsonPrimitive(type))
            body.jsonObject.forEach { (key, element) -> put(key, element) }
        }
        jsonEncoder.encodeJsonElement(result)
    }

    override fun deserialize(decoder: Decoder): AssistantMessageEvent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("assistant message event has no type: $element")
        val json = jsonDecoder.json
        return when (type) {
            "start" -> json.decodeFromJsonElement(StartEvent.serializer(), element)
            "text_start" -> json.decodeFromJsonElement(TextStartEvent.serializer(), element)
            "text_delta" -> json.decodeFromJsonElement(TextDeltaEvent.serializer(), element)
            "text_end" -> json.decodeFromJsonElement(TextEndEvent.serializer(), element)
            "thinking_start" -> json.decodeFromJsonElement(ThinkingStartEvent.serializer(), element)
            "thinking_delta" -> json.decodeFromJsonElement(ThinkingDeltaEvent.serializer(), element)
            "thinking_end" -> json.decodeFromJsonElement(ThinkingEndEvent.serializer(), element)
            "toolcall_start" -> json.decodeFromJsonElement(ToolCallStartEvent.serializer(), element)
            "toolcall_delta" -> json.decodeFromJsonElement(ToolCallDeltaEvent.serializer(), element)
            "toolcall_end" -> json.decodeFromJsonElement(ToolCallEndEvent.serializer(), element)
            "done" -> json.decodeFromJsonElement(DoneEvent.serializer(), element)
            "error" -> json.decodeFromJsonElement(ErrorEvent.serializer(), element)
            else -> throw IllegalArgumentException("unknown assistant message event type: $type")
        }
    }
}
