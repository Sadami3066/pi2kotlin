package dev.pi.mcp.protocol

import kotlinx.serialization.json.JsonObject

data class McpRoot(
    val uri: String,
    val name: String? = null,
)

data class McpSamplingRequest(
    val messages: List<JsonObject>,
    val systemPrompt: String? = null,
    val maxTokens: Long = 4096,
    val temperature: Double? = null,
    val stopSequences: List<String> = emptyList(),
    val modelPreferences: JsonObject? = null,
    val raw: JsonObject,
)

data class McpSamplingResult(
    val role: String = "assistant",
    val content: List<JsonObject>,
    val model: String,
    val stopReason: String? = null,
) {
    companion object {
        fun of(content: JsonObject, model: String, role: String = "assistant", stopReason: String? = null): McpSamplingResult =
            McpSamplingResult(role = role, content = listOf(content), model = model, stopReason = stopReason)
    }
}

data class McpElicitationRequest(
    val message: String,
    val requestedSchema: JsonObject,
    val raw: JsonObject,
)

data class McpElicitationResult(
    val action: String,
    val content: JsonObject? = null,
)

data class McpClientHandlers(
    val listRoots: (suspend () -> List<McpRoot>)? = null,
    val sample: (suspend (McpSamplingRequest) -> McpSamplingResult)? = null,
    val elicit: (suspend (McpElicitationRequest) -> McpElicitationResult)? = null,
)
