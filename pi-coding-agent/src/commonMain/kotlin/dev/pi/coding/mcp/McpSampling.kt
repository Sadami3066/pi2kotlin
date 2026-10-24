package dev.pi.coding.mcp

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.Context
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.coding.models.ModelRuntime
import dev.pi.mcp.protocol.McpSamplingRequest
import dev.pi.mcp.protocol.McpSamplingResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object McpSampling {
    suspend fun complete(
        runtime: ModelRuntime,
        model: Model,
        request: McpSamplingRequest,
        settings: McpOptions,
    ): McpSamplingResult {
        val messages = request.messages.mapNotNull { raw -> toMessage(raw) }
        val options = SimpleStreamOptions(
            stream = StreamOptions(
                maxTokens = minOf(request.maxTokens, settings.samplingMaxTokens, model.maxTokens),
                temperature = request.temperature ?: settings.samplingTemperature,
                sessionId = "mcp-sampling",
            ),
        )
        val response = runtime.completeSimple(
            model,
            Context(systemPrompt = request.systemPrompt, messages = messages),
            options,
            null,
        )
        val content = response.content.mapNotNull { block ->
            when (block) {
                is TextContent -> buildJsonObject {
                    put("type", "text")
                    put("text", block.text)
                }

                is ImageContent -> buildJsonObject {
                    put("type", "image")
                    put("data", block.data)
                    put("mimeType", block.mimeType)
                }

                else -> null
            }
        }
        val blocks = content.ifEmpty {
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", response.content.filterIsInstance<TextContent>().joinToString("\n") { it.text })
                },
            )
        }
        return McpSamplingResult(
            role = "assistant",
            content = blocks,
            model = model.id,
            stopReason = samplingStopReason(response.stopReason),
        )
    }

    private fun toMessage(raw: JsonObject): Message? {
        val role = (raw["role"] as? JsonPrimitive)?.content ?: return null
        val blocks = contentBlocks(raw["content"])
        if (blocks.isEmpty()) return null
        return when (role) {
            "user" -> UserMessage(BlockContent(blocks), platformCurrentTimeMillis())
            "assistant" -> AssistantMessage(
                content = blocks.toMutableList(),
                api = "",
                provider = "",
                model = "",
                usage = Usage.empty(),
                stopReason = StopReason.Stop,
                timestamp = platformCurrentTimeMillis(),
            )
            else -> null
        }
    }

    private fun contentBlocks(element: kotlinx.serialization.json.JsonElement?): List<Content> {
        if (element == null) return emptyList()
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
        return items.mapNotNull { item ->
            val block = item as? JsonObject ?: return@mapNotNull null
            when ((block["type"] as? JsonPrimitive)?.content) {
                "text" -> (block["text"] as? JsonPrimitive)?.content?.let { TextContent(it) }
                "image" -> {
                    val data = (block["data"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val mimeType = (block["mimeType"] as? JsonPrimitive)?.content ?: "image/png"
                    ImageContent(data = data, mimeType = mimeType)
                }
                else -> null
            }
        }
    }

    private fun samplingStopReason(stopReason: StopReason): String = when (stopReason) {
        StopReason.Length -> "maxTokens"
        StopReason.Error -> "error"
        else -> "endTurn"
    }

    @Suppress("unused")
    private fun textOf(content: dev.pi.ai.MessageContent): String = when (content) {
        is PlainTextContent -> content.text
        is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
    }
}
