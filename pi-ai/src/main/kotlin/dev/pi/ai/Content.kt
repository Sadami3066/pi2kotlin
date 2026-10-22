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

@Serializable(with = ContentSerializer::class)
sealed interface Content

@Serializable
data class TextContent(
    var text: String,
    var textSignature: String? = null,
) : Content

@Serializable
data class ThinkingContent(
    var thinking: String,
    var thinkingSignature: String? = null,
    var redacted: Boolean? = null,
) : Content

@Serializable
data class ImageContent(
    val data: String,
    val mimeType: String,
) : Content

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    var arguments: JsonObject,
    var thoughtSignature: String? = null,
    var namespace: String? = null,
    var argumentsError: String? = null,
) : Content

object ContentSerializer : KSerializer<Content> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Content) {
        val jsonEncoder = encoder as JsonEncoder
        val json = jsonEncoder.json
        val body = when (value) {
            is TextContent -> json.encodeToJsonElement(TextContent.serializer(), value)
            is ThinkingContent -> json.encodeToJsonElement(ThinkingContent.serializer(), value)
            is ImageContent -> json.encodeToJsonElement(ImageContent.serializer(), value)
            is ToolCall -> json.encodeToJsonElement(ToolCall.serializer(), value)
        }.jsonObject
        val type = when (value) {
            is TextContent -> "text"
            is ThinkingContent -> "thinking"
            is ImageContent -> "image"
            is ToolCall -> "toolCall"
        }
        val result = buildJsonObject {
            put("type", JsonPrimitive(type))
            body.forEach { (key, element) -> put(key, element) }
        }
        jsonEncoder.encodeJsonElement(result)
    }

    override fun deserialize(decoder: Decoder): Content {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        val type = element.jsonObject["type"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("content object has no type: $element")
        val json = jsonDecoder.json
        return when (type) {
            "text" -> json.decodeFromJsonElement(TextContent.serializer(), element)
            "thinking" -> json.decodeFromJsonElement(ThinkingContent.serializer(), element)
            "image" -> json.decodeFromJsonElement(ImageContent.serializer(), element)
            "toolCall" -> json.decodeFromJsonElement(ToolCall.serializer(), element)
            else -> throw IllegalArgumentException("unknown content type: $type")
        }
    }
}
