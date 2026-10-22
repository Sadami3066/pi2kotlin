package dev.pi.ai.utils

import dev.pi.ai.Context
import dev.pi.ai.JsonValue
import dev.pi.ai.Message
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.Tool
import dev.pi.ai.ToolReference
import dev.pi.ai.TranscriptContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

fun createInitialSystemMessage(systemPrompt: String?, tools: List<Tool>?): SystemMessage? {
    val hasSystemPrompt = systemPrompt != null && systemPrompt.isNotEmpty()
    val hasTools = tools != null && tools.isNotEmpty()
    if (!hasSystemPrompt && !hasTools) return null
    return SystemMessage(
        content = PlainTextContent(systemPrompt ?: ""),
        toolsAdded = if (hasTools) tools else null,
        timestamp = 0,
    )
}

fun normalizeContext(context: Context): TranscriptContext {
    val initialMessage = createInitialSystemMessage(context.systemPrompt, context.tools)
    val messages = if (initialMessage != null) listOf(initialMessage) + context.messages else context.messages
    return TranscriptContext(messages)
}

fun getInitialSystemMessage(messages: List<Message>): SystemMessage? = messages.firstOrNull() as? SystemMessage

fun withoutInitialSystemMessage(messages: List<Message>): List<Message> =
    if (getInitialSystemMessage(messages) != null) messages.drop(1) else messages

fun getCurrentTools(messages: List<Message>): List<Tool> {
    val tools = LinkedHashMap<String, Tool>()
    for (message in messages) {
        if (message !is SystemMessage) continue
        message.toolsRemoved?.forEach { tool -> tools.remove(tool.name) }
        message.toolsAdded?.forEach { tool -> tools[tool.name] = tool }
    }
    return tools.values.toList()
}

fun getCurrentSystemMessage(messages: List<Message>): SystemMessage? {
    val content = mutableListOf<String>()
    val sections = LinkedHashMap<String, String>()
    var timestamp: Long? = null
    for (message in messages) {
        if (message !is SystemMessage) continue
        if (timestamp == null) timestamp = message.timestamp
        val text = contentText(message.content)
        if (text.isNotEmpty()) content.add(text)
        message.sections?.forEach { (name, value) ->
            if (value == null) sections.remove(name) else sections[name] = value
        }
    }
    val tools = getCurrentTools(messages)
    if (timestamp == null && tools.isEmpty()) return null
    return SystemMessage(
        content = PlainTextContent(content.joinToString("\n\n")),
        sections = if (sections.isEmpty()) null else sections,
        toolsAdded = if (tools.isEmpty()) null else tools,
        timestamp = timestamp ?: 0L,
    )
}

fun getCurrentSystemPrompt(messages: List<Message>): String {
    val message = getCurrentSystemMessage(messages)
    return message?.let(::getSystemMessageText) ?: ""
}

fun collapseSystemMessages(context: TranscriptContext): TranscriptContext {
    val head = getCurrentSystemMessage(context.messages)
    val messages = context.messages.filter { it !is SystemMessage }
    return TranscriptContext(if (head != null) listOf(head) + messages else messages)
}

fun toToolDeclaration(tool: Tool): Tool = Tool(
    name = tool.name,
    description = tool.description,
    parameters = deepCopyJson(tool.parameters) as JsonObject,
    constrainedSampling = tool.constrainedSampling,
)

fun declarationsEqual(a: Tool, b: Tool): Boolean =
    jsJsonStringify(declarationJson(a)) == jsJsonStringify(declarationJson(b))

private fun declarationJson(tool: Tool): JsonObject {
    val declaration = toToolDeclaration(tool)
    return buildJsonObject {
        put("name", JsonPrimitive(declaration.name))
        put("description", JsonPrimitive(declaration.description))
        put("parameters", declaration.parameters)
        val constrainedSampling = declaration.constrainedSampling
        if (constrainedSampling != null) put("constrainedSampling", constrainedSampling)
    }
}

data class ToolStateChanges(val added: List<Tool>, val removed: List<ToolReference>)

fun getToolStateChanges(previous: List<Tool>, current: List<Tool>): ToolStateChanges {
    val previousTools = previous.associateBy { it.name }
    val currentTools = current.associateBy { it.name }
    val added = current.filter { tool ->
        val previousTool = previousTools[tool.name]
        previousTool == null || !declarationsEqual(previousTool, tool)
    }.map(::toToolDeclaration)
    val removed = previous.filter { tool ->
        val currentTool = currentTools[tool.name]
        currentTool == null || !declarationsEqual(tool, currentTool)
    }.map { ToolReference(name = it.name) }
    return ToolStateChanges(added = added, removed = removed)
}

private fun deepCopyJson(value: JsonValue): JsonValue = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (_, nested) -> deepCopyJson(nested) })
    is JsonArray -> JsonArray(value.map(::deepCopyJson))
    else -> value
}
