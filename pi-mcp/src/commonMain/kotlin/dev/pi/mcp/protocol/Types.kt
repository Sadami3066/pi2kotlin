package dev.pi.mcp.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val LATEST_PROTOCOL_VERSION: String = "2025-11-25"

val SUPPORTED_PROTOCOL_VERSIONS: List<String> = listOf(
    LATEST_PROTOCOL_VERSION,
    "2025-06-18",
    "2025-03-26",
    "2024-11-05",
)

const val DEFAULT_PROTOCOL_VERSION: String = LATEST_PROTOCOL_VERSION

data class McpImplementation(
    val name: String,
    val version: String,
    val title: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement?): McpImplementation {
            val obj = value as? JsonObject ?: return McpImplementation("unknown", "0")
            return McpImplementation(
                name = obj.stringOrNull("name") ?: "unknown",
                version = obj.stringOrNull("version") ?: "0",
                title = obj.stringOrNull("title"),
                raw = obj,
            )
        }
    }
}

data class McpServerCapabilities(val raw: JsonObject = JsonObject(emptyMap())) {
    val tools: JsonObject? get() = raw["tools"] as? JsonObject

    val resources: JsonObject? get() = raw["resources"] as? JsonObject

    val prompts: JsonObject? get() = raw["prompts"] as? JsonObject

    val logging: JsonObject? get() = raw["logging"] as? JsonObject

    val hasTools: Boolean get() = tools != null

    val hasResources: Boolean get() = resources != null

    val hasPrompts: Boolean get() = prompts != null
}

data class McpInitializeResult(
    val protocolVersion: String,
    val capabilities: McpServerCapabilities,
    val serverInfo: McpImplementation,
    val instructions: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): McpInitializeResult {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("initialize result must be an object")
            return McpInitializeResult(
                protocolVersion = obj.stringOrNull("protocolVersion")
                    ?: throw IllegalArgumentException("initialize result has no protocolVersion"),
                capabilities = McpServerCapabilities(obj["capabilities"] as? JsonObject ?: JsonObject(emptyMap())),
                serverInfo = McpImplementation.fromJson(obj["serverInfo"]),
                instructions = obj.stringOrNull("instructions"),
                raw = obj,
            )
        }
    }
}

data class McpTool(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
    val title: String? = null,
    val annotations: JsonObject? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): McpTool {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("tool must be an object")
            val name = obj.stringOrNull("name") ?: throw IllegalArgumentException("tool has no name")
            val schema = obj["inputSchema"] as? JsonObject ?: buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
            }
            return McpTool(
                name = name,
                description = obj.stringOrNull("description"),
                inputSchema = schema,
                outputSchema = obj["outputSchema"] as? JsonObject,
                title = obj.stringOrNull("title"),
                annotations = obj["annotations"] as? JsonObject,
                raw = obj,
            )
        }
    }
}

data class McpToolsPage(
    val tools: List<McpTool>,
    val nextCursor: String? = null,
)

data class McpCallResult(
    val content: List<JsonObject>,
    val structuredContent: JsonElement? = null,
    val isError: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement?): McpCallResult {
            val obj = value as? JsonObject ?: return McpCallResult(emptyList())
            val content = (obj["content"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val isError = (obj["isError"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
            return McpCallResult(
                content = content,
                structuredContent = obj["structuredContent"],
                isError = isError,
                raw = obj,
            )
        }
    }
}

data class McpResource(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): McpResource {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("resource must be an object")
            return McpResource(
                uri = obj.stringOrNull("uri") ?: throw IllegalArgumentException("resource has no uri"),
                name = obj.stringOrNull("name"),
                description = obj.stringOrNull("description"),
                mimeType = obj.stringOrNull("mimeType"),
                size = (obj["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull(),
                raw = obj,
            )
        }
    }
}

data class McpResourcesPage(
    val resources: List<McpResource>,
    val nextCursor: String? = null,
)

data class McpResourceTemplate(
    val uriTemplate: String,
    val name: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): McpResourceTemplate {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("resource template must be an object")
            return McpResourceTemplate(
                uriTemplate = obj.stringOrNull("uriTemplate")
                    ?: throw IllegalArgumentException("resource template has no uriTemplate"),
                name = obj.stringOrNull("name"),
                description = obj.stringOrNull("description"),
                mimeType = obj.stringOrNull("mimeType"),
                raw = obj,
            )
        }
    }
}

data class McpResourceTemplatesPage(
    val templates: List<McpResourceTemplate>,
    val nextCursor: String? = null,
)

data class McpResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class McpReadResourceResult(val contents: List<McpResourceContent>, val raw: JsonObject = JsonObject(emptyMap())) {
    companion object {
        fun fromJson(value: JsonElement?): McpReadResourceResult {
            val obj = value as? JsonObject ?: return McpReadResourceResult(emptyList())
            val contents = (obj["contents"] as? JsonArray)?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                McpResourceContent(
                    uri = item.stringOrNull("uri") ?: "",
                    mimeType = item.stringOrNull("mimeType"),
                    text = item.stringOrNull("text"),
                    blob = item.stringOrNull("blob"),
                    raw = item,
                )
            }.orEmpty()
            return McpReadResourceResult(contents, obj)
        }
    }
}

data class McpPromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean = false,
    val raw: JsonObject = JsonObject(emptyMap()),
)

data class McpPrompt(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement): McpPrompt {
            val obj = value as? JsonObject ?: throw IllegalArgumentException("prompt must be an object")
            val args = (obj["arguments"] as? JsonArray)?.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val name = item.stringOrNull("name") ?: return@mapNotNull null
                McpPromptArgument(
                    name = name,
                    description = item.stringOrNull("description"),
                    required = (item["required"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                    raw = item,
                )
            }.orEmpty()
            return McpPrompt(
                name = obj.stringOrNull("name") ?: throw IllegalArgumentException("prompt has no name"),
                title = obj.stringOrNull("title"),
                description = obj.stringOrNull("description"),
                arguments = args,
                raw = obj,
            )
        }
    }
}

data class McpPromptsPage(val prompts: List<McpPrompt>, val nextCursor: String? = null)

data class McpPromptResult(
    val description: String? = null,
    val messages: List<JsonObject> = emptyList(),
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun fromJson(value: JsonElement?): McpPromptResult {
            val obj = value as? JsonObject ?: return McpPromptResult()
            val messages = (obj["messages"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return McpPromptResult(obj.stringOrNull("description"), messages, obj)
        }
    }
}

data class McpProgress(
    val progressToken: JsonElement?,
    val progress: Double,
    val total: Double? = null,
    val message: String? = null,
)

data class McpLogMessage(
    val level: String,
    val logger: String? = null,
    val data: JsonElement? = null,
)

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
