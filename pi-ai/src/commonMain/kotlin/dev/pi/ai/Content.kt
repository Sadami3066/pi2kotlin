package dev.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface Content

@Serializable
@SerialName("text")
data class TextContent(
    var text: String,
    var textSignature: String? = null,
) : Content

@Serializable
@SerialName("thinking")
data class ThinkingContent(
    var thinking: String,
    var thinkingSignature: String? = null,
    var redacted: Boolean? = null,
) : Content

@Serializable
@SerialName("image")
data class ImageContent(
    val data: String,
    val mimeType: String,
) : Content

@Serializable
@SerialName("toolCall")
data class ToolCall(
    val id: String,
    val name: String,
    var arguments: JsonObject,
    var thoughtSignature: String? = null,
    var namespace: String? = null,
    var argumentsError: String? = null,
) : Content
