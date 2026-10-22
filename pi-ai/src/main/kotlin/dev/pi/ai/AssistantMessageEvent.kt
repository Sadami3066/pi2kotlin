package dev.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@JsonClassDiscriminator("type")
sealed interface AssistantMessageEvent

@Serializable
@SerialName("start")
data class StartEvent(val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
@SerialName("text_start")
data class TextStartEvent(val contentIndex: Int, val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
@SerialName("text_delta")
data class TextDeltaEvent(val contentIndex: Int, val delta: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
@SerialName("text_end")
data class TextEndEvent(val contentIndex: Int, val content: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
@SerialName("thinking_start")
data class ThinkingStartEvent(val contentIndex: Int, val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
@SerialName("thinking_delta")
data class ThinkingDeltaEvent(val contentIndex: Int, val delta: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
@SerialName("thinking_end")
data class ThinkingEndEvent(val contentIndex: Int, val content: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
@SerialName("toolcall_start")
data class ToolCallStartEvent(val contentIndex: Int, val partial: AssistantMessage) : AssistantMessageEvent

@Serializable
@SerialName("toolcall_delta")
data class ToolCallDeltaEvent(val contentIndex: Int, val delta: String, val partial: AssistantMessage) :
    AssistantMessageEvent

@Serializable
@SerialName("toolcall_end")
data class ToolCallEndEvent(
    val contentIndex: Int,
    val toolCall: ToolCall,
    val partial: AssistantMessage,
) : AssistantMessageEvent

@Serializable
@SerialName("done")
data class DoneEvent(val reason: StopReason, val message: AssistantMessage) : AssistantMessageEvent

@Serializable
@SerialName("error")
data class ErrorEvent(val reason: StopReason, val error: AssistantMessage) : AssistantMessageEvent
