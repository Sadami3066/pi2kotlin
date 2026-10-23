package dev.pi.coding.mcp

import dev.pi.agent.AgentToolResult
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.PiJson
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import dev.pi.coding.tools.CodingTool
import dev.pi.coding.tools.formatSize
import dev.pi.coding.tools.textResult
import dev.pi.mcp.McpClient
import dev.pi.mcp.McpHttpException
import dev.pi.mcp.McpRequestOptions
import dev.pi.mcp.McpRpcException
import dev.pi.mcp.McpTimeoutException
import dev.pi.mcp.protocol.McpCallResult
import dev.pi.mcp.protocol.McpTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import kotlin.math.ceil

const val MCP_OUTPUT_MAX_BYTES: Int = 20 * 1024

data class McpToolAdapterOptions(
    val maxOutputBytes: Int = MCP_OUTPUT_MAX_BYTES,
    val outputDirectory: Path? = null,
    val fileSystem: FileSystem = FileSystem.SYSTEM,
)

private const val MAX_TOOL_NAME_LENGTH = 64
private const val MAX_DESCRIPTION_LENGTH = 1024

class McpToolAdapter(
    val serverName: String,
    val client: McpClient,
    val options: McpToolAdapterOptions = McpToolAdapterOptions(),
) {
    fun adapt(tool: McpTool, existingNames: Set<String> = emptySet()): CodingTool {
        val exposedName = mcpToolName(serverName, tool.name, existingNames)
        val fallback = tool.title?.takeIf { it.isNotBlank() } ?: "MCP tool ${tool.name} from server $serverName"
        val description = (tool.description?.trim()?.takeIf { it.isNotEmpty() } ?: fallback).take(MAX_DESCRIPTION_LENGTH)
        return CodingTool(
            name = exposedName,
            description = description,
            parameters = normalizeToolParameters(tool.inputSchema),
            label = "$serverName/${tool.name}",
            execute = { _, args, signal, onUpdate ->
                executeTool(tool, args, signal, onUpdate)
            },
        )
    }

    private suspend fun executeTool(
        tool: McpTool,
        args: JsonObject,
        signal: RunSignal?,
        onUpdate: (suspend (AgentToolResult) -> Unit)?,
    ): AgentToolResult = try {
        val result = callTool(tool, args, signal, onUpdate)
        mcpCallResultToToolResult(serverName, tool.name, result, options)
    } catch (error: CancellationException) {
        throw error
    } catch (error: RequestAbortedException) {
        throw error
    } catch (error: McpRpcException) {
        textResult(error.message ?: "MCP tool call failed", isError = true)
    } catch (error: McpHttpException) {
        textResult(error.message ?: "MCP tool call failed", isError = true)
    } catch (error: McpTimeoutException) {
        textResult(error.message ?: "MCP tool call timed out", isError = true)
    }

    private suspend fun callTool(
        tool: McpTool,
        args: JsonObject,
        signal: RunSignal?,
        onUpdate: (suspend (AgentToolResult) -> Unit)?,
    ): McpCallResult {
        if (onUpdate == null) {
            return client.callTool(tool.name, args, McpRequestOptions(signal = signal))
        }
        return coroutineScope {
            val channel = Channel<AgentToolResult>(Channel.UNLIMITED)
            val consumer = launch {
                for (update in channel) onUpdate(update)
            }
            try {
                client.callTool(
                    tool.name,
                    args,
                    McpRequestOptions(
                        signal = signal,
                        onProgress = { _, progress, total, message ->
                            channel.trySend(progressResult(serverName, tool.name, progress, total, message))
                        },
                    ),
                )
            } finally {
                channel.close()
                consumer.join()
            }
        }
    }
}

internal fun mcpToolName(server: String, tool: String, existingNames: Set<String> = emptySet()): String {
    val name = "mcp__${server}__${tool}".map { char ->
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_') char else '_'
    }.joinToString("")
    if (name.length <= MAX_TOOL_NAME_LENGTH && name !in existingNames) return name
    val hash = mcpStableHash("$server\u0000$tool")
    val prefixLength = MAX_TOOL_NAME_LENGTH - hash.length - 1
    return "${name.take(prefixLength)}_$hash"
}

internal fun mcpStableHash(value: String): String {
    var hash = -2128831035
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toInt() and 0xFF)
        hash *= 16777619
    }
    return (hash.toUInt()).toString(16).padStart(8, '0').takeLast(6)
}

private fun normalizeToolParameters(schema: JsonObject): JsonObject {
    val normalized = LinkedHashMap<String, JsonElement>(schema)
    if (!normalized.containsKey("type")) normalized["type"] = JsonPrimitive("object")
    if (!normalized.containsKey("properties")) normalized["properties"] = JsonObject(emptyMap())
    return JsonObject(normalized)
}

private fun progressResult(
    server: String,
    tool: String,
    progress: Double,
    total: Double?,
    message: String?,
): AgentToolResult {
    val totalText = if (total == null) "" else "/${formatProgressNumber(total)}"
    val text = message ?: "Progress ${formatProgressNumber(progress)}$totalText"
    return textResult(text, details = mcpDetails(server, tool))
}

private fun formatProgressNumber(value: Double): String =
    if (value.isFinite() && value % 1.0 == 0.0) value.toLong().toString() else value.toString()

internal fun mcpDetails(server: String, tool: String, fullOutputPath: String? = null): JsonObject = buildJsonObject {
    put("server", server)
    put("tool", tool)
    if (fullOutputPath != null) put("fullOutputPath", fullOutputPath)
}

internal class McpLimitedContent(
    val content: List<Content>,
    val fullOutputPath: String?,
)

internal suspend fun mcpCallResultToToolResult(
    server: String,
    tool: String,
    result: McpCallResult,
    options: McpToolAdapterOptions,
): AgentToolResult {
    val converted = result.content.flatMap { block -> mcpBlockToContent(server, block, options) }.toMutableList()
    val structuredContent = result.structuredContent
    if (converted.isEmpty() && structuredContent != null) {
        converted.add(TextContent(prettyJson(structuredContent)))
    }
    if (result.isError && converted.filterIsInstance<TextContent>().joinToString("") { it.text }.isEmpty()) {
        converted.add(TextContent("MCP tool $server/$tool returned an error"))
    }
    val limited = limitMcpContent(server, tool, converted, options)
    return AgentToolResult(
        content = limited.content,
        details = mcpDetails(server, tool, limited.fullOutputPath),
        structuredContent = mcpScriptResult(result),
        isError = if (result.isError) true else null,
    )
}

internal fun mcpScriptResult(result: McpCallResult): JsonObject {
    if (result.raw.isNotEmpty()) {
        return JsonObject(result.raw.filterKeys { it != "_meta" })
    }
    val script = LinkedHashMap<String, JsonElement>()
    script["content"] = JsonArray(result.content)
    val structuredContent = result.structuredContent
    if (structuredContent != null) script["structuredContent"] = structuredContent
    if (result.isError) script["isError"] = JsonPrimitive(true)
    return JsonObject(script)
}

private fun prettyJson(value: JsonElement): String = PiJson.encodeToString(JsonElement.serializer(), value)

private suspend fun mcpBlockToContent(
    server: String,
    block: JsonObject,
    options: McpToolAdapterOptions,
): List<Content> = when (val type = mcpString(block, "type")) {
    "text" -> listOf(TextContent(mcpString(block, "text") ?: ""))
    "image" -> listOf(ImageContent(data = mcpString(block, "data") ?: "", mimeType = mcpString(block, "mimeType") ?: "image/png"))
    "audio" -> audioContent(server, block, options)
    "resource_link" -> listOf(TextContent(resourceLinkText(server, block)))
    "resource" -> embeddedResourceContent(server, block["resource"] as? JsonObject, options)
    else -> listOf(TextContent("[unsupported MCP content ${type ?: "unknown"}]"))
}

private suspend fun audioContent(
    server: String,
    block: JsonObject,
    options: McpToolAdapterOptions,
): List<Content> {
    val mimeType = mcpString(block, "mimeType") ?: "audio/unknown"
    val data = mcpString(block, "data")
    if (data == null || options.outputDirectory == null) {
        return listOf(TextContent("[audio $mimeType omitted]"))
    }
    val bytes = mcpDecodeBase64(data)
    if (bytes == null) return listOf(TextContent("[audio $mimeType omitted]"))
    val path = mcpSaveBinary(server, "audio:$mimeType:$data", mimeType, null, bytes, options)
    return listOf(TextContent(if (path != null) "[Audio content saved to $path]" else "[audio $mimeType omitted]"))
}

private fun resourceLinkText(server: String, block: JsonObject): String {
    val uri = mcpString(block, "uri") ?: ""
    val title = mcpString(block, "title") ?: mcpString(block, "name") ?: ""
    val mimeType = mcpString(block, "mimeType")
    val size = (block["size"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
    val details = buildList {
        if (mimeType != null) add(mimeType)
        if (size != null) add(formatSize(size))
    }
    val detailText = if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
    val description = mcpString(block, "description")?.let { ": $it" } ?: ""
    val read = ". Read it with read_mcp_resource (server \"$server\")"
    return "[Resource $uri \"$title\"$detailText$description$read]"
}

private suspend fun embeddedResourceContent(
    server: String,
    resource: JsonObject?,
    options: McpToolAdapterOptions,
): List<Content> {
    if (resource == null) return listOf(TextContent("[unsupported MCP resource content]"))
    val uri = mcpString(resource, "uri") ?: ""
    val text = mcpString(resource, "text")
    if (text != null) return listOf(TextContent(text))
    val blob = mcpString(resource, "blob") ?: return listOf(TextContent("[unsupported MCP resource content]"))
    val mimeType = mcpString(resource, "mimeType")
    if (mimeType != null && mimeType.startsWith("image/")) {
        return listOf(ImageContent(data = blob, mimeType = mimeType))
    }
    val bytes = mcpDecodeBase64(blob)
    if (bytes != null && mcpIsTextMimeType(mimeType)) {
        return listOf(TextContent(bytes.decodeToString()))
    }
    if (bytes == null) return listOf(TextContent("[binary resource $uri (${mimeType ?: "unknown type"}) omitted]"))
    val kind = "${mimeType ?: "unknown type"}, ${formatSize(bytes.size.toLong())}"
    val path = mcpSaveBinary(server, "resource:$uri", mimeType, uri, bytes, options)
    return listOf(
        TextContent(
            if (path != null) {
                "[Binary resource $uri ($kind) saved to $path]"
            } else {
                "[Binary resource $uri ($kind) omitted]"
            },
        ),
    )
}

internal fun mcpIsTextMimeType(mimeType: String?): Boolean {
    if (mimeType == null) return false
    val type = mimeType.substringBefore(';').trim().lowercase()
    return type.startsWith("text/") || type == "application/json" || type.endsWith("+json") || type.endsWith("+xml")
}

internal fun mcpDecodeBase64(value: String): ByteArray? = try {
    kotlin.io.encoding.Base64.decode(value)
} catch (_: IllegalArgumentException) {
    null
}

internal fun mcpSaveBinary(
    server: String,
    identity: String,
    mimeType: String?,
    uri: String?,
    bytes: ByteArray,
    options: McpToolAdapterOptions,
): String? {
    val directory = options.outputDirectory ?: return null
    val extension = extensionFor(mimeType, uri)
    val fileName = "mcp-${sanitizeFileName(server)}-${mcpStableHash("$server\u0000$identity")}.$extension"
    return try {
        options.fileSystem.createDirectories(directory)
        val path = directory.resolve(fileName)
        options.fileSystem.write(path) { write(bytes) }
        path.toString()
    } catch (_: Throwable) {
        null
    }
}

private fun extensionFor(mimeType: String?, uri: String?): String {
    val normalized = mimeType?.substringBefore(';')?.trim()?.lowercase()
    val byMime = when (normalized) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "application/pdf" -> "pdf"
        "application/json" -> "json"
        "text/plain" -> "txt"
        else -> null
    }
    if (byMime != null) return byMime
    val tail = uri?.substringAfterLast('/')?.substringAfterLast('?')?.substringAfterLast('#') ?: ""
    val match = Regex("\\.[A-Za-z0-9]{1,8}$").find(tail)
    return match?.value?.removePrefix(".") ?: "bin"
}

private fun sanitizeFileName(value: String): String = value.map { char ->
    if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') char else '_'
}.joinToString("")

internal suspend fun limitMcpContent(
    server: String,
    tool: String,
    content: List<Content>,
    options: McpToolAdapterOptions,
): McpLimitedContent {
    val text = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val images = content.filterIsInstance<ImageContent>()
    val totalBytes = text.encodeToByteArray().size
    if (totalBytes <= options.maxOutputBytes) return McpLimitedContent(content, null)
    val truncation = truncateMiddle(text, options.maxOutputBytes)
    var fullOutputPath: String? = null
    if (options.outputDirectory != null) {
        val fileName = "mcp-${sanitizeFileName(server)}-${mcpStableHash("$server\u0000$tool:text")}.txt"
        fullOutputPath = try {
            options.fileSystem.createDirectories(options.outputDirectory)
            val path = options.outputDirectory.resolve(fileName)
            options.fileSystem.write(path) { writeUtf8(text) }
            path.toString()
        } catch (_: Throwable) {
            null
        }
    }
    val where = if (fullOutputPath != null) {
        "[Full output: $fullOutputPath (read it with offset/limit)]"
    } else {
        "[Full output not saved]"
    }
    val tokens = ceil(totalBytes / 4.0).toLong()
    val totalLines = text.split("\n").size
    val truncatedText = "Warning: truncated output (original token count: $tokens)\n" +
        "Total output lines: $totalLines\n\n" +
        truncation +
        "\n\n" +
        where
    return McpLimitedContent(listOf(TextContent(truncatedText)) + images, fullOutputPath)
}

private fun truncateMiddle(content: String, maxBytes: Int): String {
    val bytes = content.encodeToByteArray()
    if (bytes.size <= maxBytes) return content
    val isBoundary = { index: Int -> index >= bytes.size || (bytes[index].toInt() and 0xC0) != 0x80 }
    var headEnd = maxBytes / 2
    while (headEnd > 0 && !isBoundary(headEnd)) headEnd--
    var tailStart = bytes.size - (maxBytes - maxBytes / 2)
    while (tailStart < bytes.size && !isBoundary(tailStart)) tailStart++
    val head = bytes.copyOfRange(0, headEnd).decodeToString()
    val tail = bytes.copyOfRange(tailStart, bytes.size).decodeToString()
    val removedChars = bytes.copyOfRange(headEnd, tailStart).decodeToString().length
    return "$head\u2026$removedChars chars truncated\u2026$tail"
}

internal fun mcpString(value: JsonObject, key: String): String? =
    (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
