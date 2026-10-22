package dev.pi.coding.session

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.MessageContent
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.JsonValue
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

const val CURRENT_SESSION_VERSION: Int = 3

const val COMPACTION_SUMMARY_PREFIX: String =
    "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"

const val COMPACTION_SUMMARY_SUFFIX: String = "\n</summary>"

const val BRANCH_SUMMARY_PREFIX: String =
    "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n"

const val BRANCH_SUMMARY_SUFFIX: String = "</summary>"

@Serializable
data class SessionHeader(
    val type: String = "session",
    val version: Int? = null,
    val id: String,
    val timestamp: String,
    val cwd: String,
    val parentSession: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface SessionEntry {
    val type: String
    val id: String
    val parentId: String?
    val timestamp: String
}

@Serializable(with = SessionMessageEntrySerializer::class)
@SerialName("message")
data class SessionMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val message: Message,
    val rawMessage: JsonObject? = null,
) : SessionEntry {
    override val type: String get() = "message"
}

@Serializable
@SerialName("thinking_level_change")
data class ThinkingLevelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String,
) : SessionEntry {
    override val type: String get() = "thinking_level_change"
}

@Serializable
@SerialName("model_change")
data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val provider: String,
    val modelId: String,
) : SessionEntry {
    override val type: String get() = "model_change"
}

@Serializable
@SerialName("usage")
data class UsageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val kind: String? = null,
    val provider: String,
    val model: String,
    val usage: Usage,
    val note: String? = null,
) : SessionEntry {
    override val type: String get() = "usage"
}

@Serializable
@SerialName("compaction")
data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Long,
    val usage: Usage? = null,
    val details: JsonValue? = null,
    val fromHook: Boolean? = null,
    val systemMessage: SystemMessage? = null,
) : SessionEntry {
    override val type: String get() = "compaction"
}

@Serializable
@SerialName("branch_summary")
data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val fromId: String,
    val summary: String,
    val details: JsonValue? = null,
    val usage: Usage? = null,
    val fromHook: Boolean? = null,
) : SessionEntry {
    override val type: String get() = "branch_summary"
}

@Serializable
@SerialName("custom")
data class CustomEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val data: JsonValue? = null,
) : SessionEntry {
    override val type: String get() = "custom"
}

@Serializable
@SerialName("custom_message")
data class CustomMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val content: MessageContent,
    val display: Boolean,
    val details: JsonValue? = null,
) : SessionEntry {
    override val type: String get() = "custom_message"
}

@Serializable
data class ContentReplacement(
    val content: MessageContent,
)

@Serializable
@SerialName("context_edit")
data class ContextEditEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val replacement: ContentReplacement? = null,
) : SessionEntry {
    override val type: String get() = "context_edit"
}

@Serializable
@SerialName("label")
data class LabelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val label: String? = null,
) : SessionEntry {
    override val type: String get() = "label"
}

@Serializable
@SerialName("session_info")
data class SessionInfoEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val name: String? = null,
) : SessionEntry {
    override val type: String get() = "session_info"
}

internal val sessionJson: Json = createSessionJson()

@OptIn(ExperimentalSerializationApi::class)
private fun createSessionJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

internal fun encodeSessionHeader(header: SessionHeader): String =
    sessionJson.encodeToString(SessionHeader.serializer(), header)

internal fun encodeSessionEntry(entry: SessionEntry): String =
    sessionJson.encodeToString(SessionEntry.serializer(), entry)

internal fun decodeSessionHeader(obj: JsonObject): SessionHeader = sessionJson.decodeFromJsonElement(
    SessionHeader.serializer(),
    obj,
)

internal fun decodeSessionEntry(obj: JsonObject): SessionEntry? = try {
    sessionJson.decodeFromJsonElement(SessionEntry.serializer(), obj)
} catch (_: Exception) {
    null
}

internal fun SessionMessageEntry.isExcludedFromContext(): Boolean {
    val raw = rawMessage ?: return false
    if (raw.stringValue("role") != "bashExecution") return false
    return raw.booleanValue("excludeFromContext") == true
}

internal fun bashExecutionToText(raw: JsonObject): String {
    val command = raw.stringValue("command") ?: ""
    val output = raw.stringValue("output") ?: ""
    val builder = StringBuilder("Ran `")
    builder.append(command).append("`\n")
    if (output.isNotEmpty()) {
        builder.append("```\n").append(output).append("\n```")
    } else {
        builder.append("(no output)")
    }
    val cancelled = raw.booleanValue("cancelled") == true
    val exitCode = raw.longValue("exitCode")
    if (cancelled) {
        builder.append("\n\n(command cancelled)")
    } else if (exitCode != null && exitCode != 0L) {
        builder.append("\n\nCommand exited with code ").append(exitCode)
    }
    val truncated = raw.booleanValue("truncated") == true
    val fullOutputPath = raw.stringValue("fullOutputPath")
    if (truncated && fullOutputPath != null) {
        builder.append("\n\n[Output truncated. Full output: ").append(fullOutputPath).append("]")
    }
    return builder.toString()
}

internal object SessionMessageEntrySerializer : KSerializer<SessionMessageEntry> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("message") {
        element<String>("id")
        element<String?>("parentId", isOptional = true)
        element<String>("timestamp")
        element<JsonObject>("message")
    }

    override fun serialize(encoder: Encoder, value: SessionMessageEntry) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeStringElement(descriptor, 0, value.id)
        composite.encodeNullableSerializableElement(descriptor, 1, String.serializer(), value.parentId)
        composite.encodeStringElement(descriptor, 2, value.timestamp)
        composite.encodeSerializableElement(
            descriptor,
            3,
            JsonObject.serializer(),
            value.rawMessage ?: encodeSessionMessage(value.message),
        )
        composite.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): SessionMessageEntry {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Session message entry decoder is not a JSON decoder")
        val obj = jsonDecoder.decodeJsonElement() as? JsonObject
            ?: throw SerializationException("Session message entry must be a JSON object")
        val id = obj.stringValue("id") ?: throw SerializationException("Session message entry is missing id")
        val timestamp = obj.stringValue("timestamp")
            ?: throw SerializationException("Session message entry is missing timestamp")
        val rawMessage = obj["message"] as? JsonObject
            ?: throw SerializationException("Session message entry is missing message")
        return SessionMessageEntry(
            id = id,
            parentId = obj.nullableStringValue("parentId"),
            timestamp = timestamp,
            message = decodeSessionMessage(rawMessage),
            rawMessage = rawMessage,
        )
    }
}

private fun encodeSessionMessage(message: Message): JsonObject =
    PiJson.encodeToJsonElement(Message.serializer(), message) as? JsonObject ?: buildJsonObject { }

private fun decodeSessionMessage(raw: JsonObject): Message {
    val role = raw.stringValue("role") ?: throw SerializationException("Session message is missing role")
    return when (role) {
        "system", "user", "assistant", "toolResult" -> {
            val normalized = normalizeMessageContent(raw, role)
            PiJson.decodeFromJsonElement(Message.serializer(), normalized)
        }

        "bashExecution" -> UserMessage(
            content = BlockContent(listOf(TextContent(bashExecutionToText(raw)))),
            timestamp = raw.longValue("timestamp") ?: 0L,
        )

        "custom", "hookMessage" -> UserMessage(
            content = BlockContent(customMessageBlocks(raw)),
            timestamp = raw.longValue("timestamp") ?: 0L,
        )

        else -> UserMessage(
            content = BlockContent(customMessageBlocks(raw)),
            timestamp = raw.longValue("timestamp") ?: 0L,
        )
    }
}

private fun normalizeMessageContent(raw: JsonObject, role: String): JsonObject {
    val content = raw["content"]
    if (content != null && content !is JsonNull) return raw
    val replacement: JsonElement = if (role == "system") JsonPrimitive("") else JsonArray(emptyList())
    return JsonObject(raw.toMutableMap().apply { put("content", replacement) })
}

private fun customMessageBlocks(raw: JsonObject): List<Content> {
    val content = raw["content"] ?: return emptyList()
    val blocks = mutableListOf<Content>()
    when (content) {
        is JsonPrimitive -> if (content.isString) blocks.add(TextContent(content.content))
        is JsonArray -> for (element in content) {
            blocks.addAll(decodeContentBlock(element))
        }

        else -> Unit
    }
    return blocks
}

private fun decodeContentBlock(element: JsonElement): List<Content> = try {
    val block = PiJson.decodeFromJsonElement(Content.serializer(), element)
    if (block is TextContent || block is ImageContent) listOf(block) else emptyList()
} catch (_: Exception) {
    emptyList()
}

internal fun toBlockContent(content: MessageContent): BlockContent = when (content) {
    is PlainTextContent -> BlockContent(listOf(TextContent(content.text)))
    is BlockContent -> content
}

internal fun toContentList(content: MessageContent): MutableList<Content> = when (content) {
    is PlainTextContent -> mutableListOf(TextContent(content.text))
    is BlockContent -> content.blocks.toMutableList()
}

internal fun JsonObject.stringValue(key: String): String? {
    val element = this[key] as? JsonPrimitive ?: return null
    if (!element.isString) return null
    return element.content
}

internal fun JsonObject.nullableStringValue(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else null
}

internal fun JsonObject.longValue(key: String): Long? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

internal fun JsonObject.intValue(key: String): Int? = longValue(key)?.toInt()

internal fun JsonObject.booleanValue(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
