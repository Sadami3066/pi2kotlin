package dev.pi.ai

import kotlinx.serialization.Serializable

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val constrainedSampling: JsonValue? = null,
)

@Serializable
data class ToolReference(
    val name: String,
)

@Serializable
data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<Tool>? = null,
)

class TranscriptContext(
    val messages: List<Message>,
)
