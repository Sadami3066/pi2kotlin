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
                kotlinx.serialization.builtins.ListSerializer(ContentSerializer),
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
                    kotlinx.serialization.builtins.ListSerializer(ContentSerializer),
                    element,
                ),
            )
        }
    }
}

@Serializable(with = MessageSerializer::class)
sealed interface Message

@Serializable
data class SystemMessage(
    val content: MessageContent,
    val sections: Map<String, String?>? = null,
    val toolsAdded: List<Tool>? = null,
    val toolsRemoved: List<ToolReference>? = null,
    val timestamp: Long,
) : Message

@Serializable
data class UserMessage(
    val content: MessageContent,
    val timestamp: Long,
) : Message

@Serializable
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

object MessageSerializer : KSerializer<Message> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Message) {
        val jsonEncoder = encoder as JsonEncoder
        val json = jsonEncoder.json
        val body = when (value) {
            is SystemMessage -> json.encodeToJsonElement(SystemMessage.serializer(), value)
            is UserMessage -> json.encodeToJsonElement(UserMessage.serializer(), value)
            is AssistantMessage -> json.encodeToJsonElement(AssistantMessage.serializer(), value)
            is ToolResultMessage -> json.encodeToJsonElement(ToolResultMessage.serializer(), value)
        }.jsonObject
        val role = when (value) {
            is SystemMessage -> "system"
            is UserMessage -> "user"
            is AssistantMessage -> "assistant"
            is ToolResultMessage -> "toolResult"
        }
        val result = buildJsonObject {
            put("role", JsonPrimitive(role))
            body.forEach { (key, element) -> put(key, element) }
        }
        jsonEncoder.encodeJsonElement(result)
    }

    override fun deserialize(decoder: Decoder): Message {
        val jsonDecoder = decoder as JsonDecoder
        val element = jsonDecoder.decodeJsonElement()
        val role = element.jsonObject["role"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("message object has no role: $element")
        val json = jsonDecoder.json
        return when (role) {
            "system" -> json.decodeFromJsonElement(SystemMessage.serializer(), element)
            "user" -> json.decodeFromJsonElement(UserMessage.serializer(), element)
            "assistant" -> json.decodeFromJsonElement(AssistantMessage.serializer(), element)
            "toolResult" -> json.decodeFromJsonElement(ToolResultMessage.serializer(), element)
            else -> throw IllegalArgumentException("unknown message role: $role")
        }
    }
}
