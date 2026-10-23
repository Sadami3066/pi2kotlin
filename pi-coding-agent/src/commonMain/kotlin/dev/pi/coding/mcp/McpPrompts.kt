package dev.pi.coding.mcp

import dev.pi.mcp.McpClient
import dev.pi.mcp.protocol.McpPromptArgument
import dev.pi.mcp.protocol.McpPromptResult
import dev.pi.mcp.protocol.McpPromptsPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class McpPromptInfo(
    val server: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val arguments: List<McpPromptArgument> = emptyList(),
)

interface McpPromptSource {
    suspend fun serverNames(): List<String>

    suspend fun client(server: String): McpClient?
}

interface McpPromptOperations {
    val serverName: String

    suspend fun listPrompts(cursor: String?): McpPromptsPage

    suspend fun getPrompt(name: String, arguments: Map<String, String>): McpPromptResult
}

fun McpClient.promptOperations(): McpPromptOperations = object : McpPromptOperations {
    override val serverName: String get() = this@promptOperations.serverName

    override suspend fun listPrompts(cursor: String?): McpPromptsPage =
        this@promptOperations.listPrompts(cursor)

    override suspend fun getPrompt(name: String, arguments: Map<String, String>): McpPromptResult =
        this@promptOperations.getPrompt(name, arguments)
}

private const val MAX_PROMPT_PAGES = 1000
private const val IMAGE_CONTENT_NOTE = "[image content omitted]"
private const val AUDIO_CONTENT_NOTE = "[audio content omitted]"

class McpPrompts(private val source: McpPromptSource? = null) {
    suspend fun list(
        server: String? = null,
        clientProvider: (suspend (String) -> McpClient?)? = null,
    ): List<McpPromptInfo> {
        val provider = clientProvider ?: source?.let { promptSource ->
            val resolve: suspend (String) -> McpClient? = { name -> promptSource.client(name) }
            resolve
        } ?: return emptyList()
        val servers = when {
            server != null -> listOf(server)
            else -> source?.serverNames().orEmpty()
        }
        return listOperations(servers) { name -> provider(name)?.promptOperations() }
    }

    suspend fun listOperations(
        servers: List<String>,
        operationsProvider: suspend (String) -> McpPromptOperations?,
    ): List<McpPromptInfo> {
        val result = mutableListOf<McpPromptInfo>()
        for (name in servers.distinct()) {
            val client = operationsProvider(name) ?: continue
            val prompts = runCatching { collectPrompts(name, client) }.getOrNull() ?: continue
            result += prompts
        }
        return result.sortedWith(compareBy({ it.server }, { it.name }))
    }

    suspend fun expand(client: McpClient, name: String, args: Map<String, String> = emptyMap()): String =
        expandOperations(client.promptOperations(), name, args)

    suspend fun expandOperations(
        client: McpPromptOperations,
        name: String,
        args: Map<String, String> = emptyMap(),
    ): String {
        val result = client.getPrompt(name, args)
        val paragraphs = result.messages.mapNotNull { message ->
            val role = (message["role"] as? JsonPrimitive)?.contentOrNull
            if (role != "user" && role != "assistant") return@mapNotNull null
            renderPromptContent(message["content"])
        }.filter { it.isNotEmpty() }
        return paragraphs.joinToString("\n\n")
    }

    private suspend fun collectPrompts(server: String, client: McpPromptOperations): List<McpPromptInfo> {
        val prompts = mutableListOf<McpPromptInfo>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        var pages = 0
        while (pages < MAX_PROMPT_PAGES) {
            val page = client.listPrompts(cursor)
            for (prompt in page.prompts) {
                prompts += McpPromptInfo(
                    server = server,
                    name = prompt.name,
                    title = prompt.title,
                    description = prompt.description,
                    arguments = prompt.arguments,
                )
            }
            val next = page.nextCursor ?: break
            if (!seenCursors.add(next)) break
            cursor = next
            pages++
        }
        return prompts
    }
}

private fun renderPromptContent(content: kotlinx.serialization.json.JsonElement?): String = when (content) {
    null -> ""
    is JsonArray -> content.mapNotNull { element ->
        renderPromptContent(element).takeIf { it.isNotEmpty() }
    }.joinToString("\n\n")
    is JsonObject -> renderPromptContentObject(content)
    is JsonPrimitive -> if (content.isString) content.content else ""
}

private fun renderPromptContentObject(content: JsonObject): String {
    val type = (content["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return when (type) {
        "text" -> (content["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        "image" -> IMAGE_CONTENT_NOTE
        "audio" -> AUDIO_CONTENT_NOTE
        "resource" -> renderPromptResource(content["resource"] as? JsonObject)
        else -> (content["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
}

private fun renderPromptResource(resource: JsonObject?): String {
    if (resource == null) return "[resource content omitted]"
    val text = (resource["text"] as? JsonPrimitive)?.contentOrNull
    if (!text.isNullOrEmpty()) return text
    val uri = (resource["uri"] as? JsonPrimitive)?.contentOrNull
    return if (uri.isNullOrEmpty()) "[resource content omitted]" else "[resource content omitted: $uri]"
}
