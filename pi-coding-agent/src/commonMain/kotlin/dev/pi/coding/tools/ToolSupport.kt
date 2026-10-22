package dev.pi.coding.tools

import dev.pi.agent.AgentTool
import dev.pi.agent.AgentToolResult
import dev.pi.agent.AgentToolUpdateCallback
import dev.pi.ai.ImageContent
import dev.pi.ai.JsonValue
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class CodingTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val label: String,
    val promptSnippet: String? = null,
    val promptGuidelines: List<String>? = null,
    val prepareArguments: ((JsonObject) -> JsonObject)? = null,
    val execute: suspend (
        toolCallId: String,
        args: JsonObject,
        signal: RunSignal?,
        onUpdate: AgentToolUpdateCallback?,
    ) -> AgentToolResult,
) {
    fun asAgentTool(): AgentTool = AgentTool(
        name = name,
        description = description,
        parameters = parameters,
        label = label,
        prepareArguments = prepareArguments,
        execute = { toolCallId, args, signal, onUpdate ->
            execute(toolCallId, JsonObject(args), signal, onUpdate)
        },
    )
}

class ToolOutput(
    val content: String,
    val details: JsonValue? = null,
)

fun textResult(text: String, details: JsonValue? = null, isError: Boolean = false): AgentToolResult =
    AgentToolResult(
        content = listOf(TextContent(text)),
        details = details,
        isError = if (isError) true else null,
    )

fun imageResult(
    data: String,
    mimeType: String,
    text: String? = null,
    details: JsonValue? = null,
): AgentToolResult {
    val content = if (text == null) {
        listOf(ImageContent(data = data, mimeType = mimeType))
    } else {
        listOf(TextContent(text), ImageContent(data = data, mimeType = mimeType))
    }
    return AgentToolResult(content = content, details = details)
}

internal fun JsonObject.argStringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.argString(name: String): String =
    argStringOrNull(name) ?: throw IllegalArgumentException("Missing required argument: $name")

internal fun JsonObject.argIntOrNull(name: String): Int? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.toDoubleOrNull()?.toInt()
}

internal fun JsonObject.argBooleanOrNull(name: String): Boolean? {
    val primitive = this[name] as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.toBooleanStrictOrNull()
}
