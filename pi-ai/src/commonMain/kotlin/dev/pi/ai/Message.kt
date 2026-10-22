package dev.pi.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.jsonObject

@Serializable(with = MessageContentSerializer::class)
sealed interface MessageContent

data class PlainTextContent(val text: String) : MessageContent

data class BlockContent(val blocks: List<Content>) : MessageContent

object MessageContentSerializer : KSerializer<MessageContent> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: MessageContent) {
        val jsonEncoder = encoder as JsonEncoder
        when (value) {
            is PlainTextContent -> jsonEncoder.encodeJsonElement(JsonPrimitive(value.text))
            is BlockContent -> jsonEncoder.json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(Content.serializer()),
                value.blocks,
            ).let(jsonEncoder::encodeJsonElement)
        }
    }

    override fun deserialize(decoder: Decoder): MessageContent {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonPrimitive && element.isString) {
            PlainTextContent(element.content)
        } else {
            BlockContent(
                jsonDecoder.json.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(Content.serializer()),
                    element,
                ),
            )
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("role")
sealed interface Message

@Serializable
@SerialName("system")
data class SystemMessage(
    val content: MessageContent,
    val sections: Map<String, String?>? = null,
    val toolsAdded: List<Tool>? = null,
    val toolsRemoved: List<ToolReference>? = null,
    val timestamp: Long,
) : Message

@Serializable
@SerialName("user")
data class UserMessage(
    val content: MessageContent,
    val timestamp: Long,
) : Message

@Serializable
@SerialName("assistant")
data class AssistantMessage(
    var content: MutableList<Content>,
    var api: String,
    var provider: String,
    var model: String,
    var responseModel: String? = null,
    var responseId: String? = null,
    var providerThinkingLevel: String? = null,
    var thinkingLevel: ModelThinkingLevel? = null,
    var diagnostics: MutableList<AssistantMessageDiagnostic>? = null,
    var usage: Usage,
    var stopReason: StopReason,
    var deferred: DeferredHandle? = null,
    var errorMessage: String? = null,
    var rawStopReason: String? = null,
    var endTurn: Boolean? = null,
    val timestamp: Long,
    var durationMs: Long? = null,
) : Message

@Serializable
@SerialName("toolResult")
data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: List<Content>,
    val details: JsonValue? = null,
    val usage: Usage? = null,
    val nestedCalls: NestedToolCalls? = null,
    val isError: Boolean,
    val timestamp: Long,
    val durationMs: Long? = null,
) : Message

@Serializable
data class DeferredHandle(
    val provider: String,
    val modelId: String,
    val api: String,
    val id: String,
    val expiresAt: Long? = null,
    val pollAfterMs: Long? = null,
    val data: JsonValue? = null,
)

@Serializable
data class NestedToolCallRecord(
    val id: String,
    val name: String,
    val arguments: JsonObject? = null,
    val argumentsBytes: Long? = null,
    val status: String,
    val durationMs: Long? = null,
    val error: String? = null,
)

@Serializable
data class NestedToolCalls(
    val calls: List<NestedToolCallRecord>,
    val complete: Boolean,
)
