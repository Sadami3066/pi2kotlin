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
import dev.pi.mcp.McpException
import dev.pi.mcp.McpHttpException
import dev.pi.mcp.McpRequestOptions
import dev.pi.mcp.McpRpcException
import dev.pi.mcp.McpTimeoutException
import dev.pi.mcp.protocol.McpResourceContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val LIST_MCP_RESOURCES_TOOL = "list_mcp_resources"
private const val LIST_MCP_RESOURCE_TEMPLATES_TOOL = "list_mcp_resource_templates"
private const val READ_MCP_RESOURCE_TOOL = "read_mcp_resource"

private const val LIST_RESOURCES_DESCRIPTION =
    "Lists resources provided by MCP servers. Resources allow servers to share data that provides context " +
        "to language models, such as files, database schemas, or application-specific information. " +
        "Prefer resources over web search when possible."

private const val LIST_RESOURCE_TEMPLATES_DESCRIPTION =
    "Lists resource templates provided by MCP servers. Parameterized resource templates allow servers to " +
        "share data that takes parameters and provides context to language models, such as files, database " +
        "schemas, or application-specific information. Prefer resource templates over web search when possible."

private const val READ_RESOURCE_DESCRIPTION =
    "Read a specific resource from an MCP server given the server name and resource URI."

private val LIST_PARAMETERS: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put(
        "properties",
        buildJsonObject {
            put(
                "server",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("MCP server name. Omit to use every configured server."))
                },
            )
            put(
                "cursor",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Opaque cursor from a previous call; omit for the first page."))
                },
            )
        },
    )
    put("additionalProperties", JsonPrimitive(false))
}

private val READ_PARAMETERS: JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put(
        "properties",
        buildJsonObject {
            put(
                "server",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("MCP server name. Required when more than one server is configured."))
                },
            )
            put(
                "uri",
                buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Resource URI to read. Must be one returned by list_mcp_resources."))
                },
            )
        },
    )
    put("required", JsonArray(listOf(JsonPrimitive("uri"))))
    put("additionalProperties", JsonPrimitive(false))
}

fun mcpResourceTools(
    serverName: String,
    client: McpClient,
    options: McpToolAdapterOptions = McpToolAdapterOptions(),
): List<CodingTool> = mcpResourceTools(linkedMapOf(serverName to client), options)

fun mcpResourceTools(
    servers: Map<String, McpClient>,
    options: McpToolAdapterOptions = McpToolAdapterOptions(),
): List<CodingTool> = listOf(
    listResourcesTool(servers, options),
    listResourceTemplatesTool(servers, options),
    readResourceTool(servers, options),
)

private typealias PageFetcher = suspend (
    name: String,
    client: McpClient,
    cursor: String?,
    options: McpRequestOptions,
) -> Pair<List<JsonObject>, String?>

private fun listResourcesTool(servers: Map<String, McpClient>, options: McpToolAdapterOptions): CodingTool = CodingTool(
    name = LIST_MCP_RESOURCES_TOOL,
    description = LIST_RESOURCES_DESCRIPTION,
    parameters = LIST_PARAMETERS,
    label = LIST_MCP_RESOURCES_TOOL,
    execute = { _, args, signal, _ ->
        resourceToolCall(LIST_MCP_RESOURCES_TOOL) {
            val fetcher: PageFetcher = { name, client, cursor, requestOptions ->
                val page = client.listResources(cursor, requestOptions)
                page.resources.map { resource -> mcpListedItem(name, resource.raw) } to page.nextCursor
            }
            listPageResult(
                servers = servers,
                key = "resources",
                tool = LIST_MCP_RESOURCES_TOOL,
                requestedServer = mcpString(args, "server"),
                cursor = mcpString(args, "cursor"),
                signal = signal,
                options = options,
                fetcher = fetcher,
            )
        }
    },
)

private fun listResourceTemplatesTool(servers: Map<String, McpClient>, options: McpToolAdapterOptions): CodingTool = CodingTool(
    name = LIST_MCP_RESOURCE_TEMPLATES_TOOL,
    description = LIST_RESOURCE_TEMPLATES_DESCRIPTION,
    parameters = LIST_PARAMETERS,
    label = LIST_MCP_RESOURCE_TEMPLATES_TOOL,
    execute = { _, args, signal, _ ->
        resourceToolCall(LIST_MCP_RESOURCE_TEMPLATES_TOOL) {
            val fetcher: PageFetcher = { name, client, cursor, requestOptions ->
                val page = client.listResourceTemplates(cursor, requestOptions)
                page.templates.map { template -> mcpListedItem(name, template.raw) } to page.nextCursor
            }
            listPageResult(
                servers = servers,
                key = "resourceTemplates",
                tool = LIST_MCP_RESOURCE_TEMPLATES_TOOL,
                requestedServer = mcpString(args, "server"),
                cursor = mcpString(args, "cursor"),
                signal = signal,
                options = options,
                fetcher = fetcher,
            )
        }
    },
)

private fun readResourceTool(servers: Map<String, McpClient>, options: McpToolAdapterOptions): CodingTool = CodingTool(
    name = READ_MCP_RESOURCE_TOOL,
    description = READ_RESOURCE_DESCRIPTION,
    parameters = READ_PARAMETERS,
    label = READ_MCP_RESOURCE_TOOL,
    execute = { _, args, signal, _ ->
        resourceToolCall(READ_MCP_RESOURCE_TOOL) {
            val uri = mcpString(args, "uri") ?: return@resourceToolCall textResult("uri must be provided", isError = true)
            val requestedServer = mcpString(args, "server")
            val serverName: String
            val client: McpClient
            if (requestedServer != null) {
                client = servers[requestedServer]
                    ?: return@resourceToolCall textResult(unknownServerMessage(requestedServer, servers.keys), isError = true)
                serverName = requestedServer
            } else {
                if (servers.size != 1) {
                    return@resourceToolCall textResult(
                        "server must be specified when multiple MCP servers are configured " +
                            "(available: ${servers.keys.joinToString(", ")}). Cannot read $uri.",
                        isError = true,
                    )
                }
                val entry = servers.entries.single()
                serverName = entry.key
                client = entry.value
            }
            readResourceResult(
                server = serverName,
                client = client,
                uri = uri,
                signal = signal,
                options = options,
            )
        }
    },
)

private suspend fun listPageResult(
    servers: Map<String, McpClient>,
    key: String,
    tool: String,
    requestedServer: String?,
    cursor: String?,
    signal: RunSignal?,
    options: McpToolAdapterOptions,
    fetcher: PageFetcher,
): AgentToolResult {
    if (requestedServer != null) {
        val client = servers[requestedServer]
            ?: return textResult(unknownServerMessage(requestedServer, servers.keys), isError = true)
        val (items, nextCursor) = fetcher(requestedServer, client, cursor, McpRequestOptions(signal = signal))
        val payload = singlePagePayload(requestedServer, key, items, nextCursor)
        return jsonToolResult(tool, requestedServer, payload, options)
    }
    val available = servers.filterValues { client -> client.serverCapabilities?.hasResources != false }
    if (available.isEmpty()) return textResult("No MCP servers with resources are configured.", isError = true)
    if (servers.size == 1) {
        val (name, client) = available.entries.single()
        val (items, nextCursor) = fetcher(name, client, cursor, McpRequestOptions(signal = signal))
        val payload = singlePagePayload(name, key, items, nextCursor)
        return jsonToolResult(tool, name, payload, options)
    }
    if (cursor != null) return textResult("cursor can only be used when a server is specified", isError = true)
    if (available.size == 1) {
        val (name, client) = available.entries.single()
        val (items, nextCursor) = fetcher(name, client, null, McpRequestOptions(signal = signal))
        val payload = singlePagePayload(name, key, items, nextCursor)
        return jsonToolResult(tool, name, payload, options)
    }
    val serverPayloads = mutableListOf<JsonElement>()
    val errors = mutableListOf<JsonElement>()
    for ((name, client) in available) {
        try {
            val (items, nextCursor) = fetcher(name, client, null, McpRequestOptions(signal = signal))
            serverPayloads.add(singlePagePayload(name, key, items, nextCursor))
        } catch (error: CancellationException) {
            throw error
        } catch (error: RequestAbortedException) {
            throw error
        } catch (error: McpException) {
            errors.add(
                buildJsonObject {
                    put("server", JsonPrimitive(name))
                    put("error", JsonPrimitive(error.message ?: error.toString()))
                },
            )
        }
    }
    if (serverPayloads.isEmpty() && errors.isNotEmpty()) {
        return textResult("Failed to list MCP resources from every configured server.", isError = true)
    }
    val payload = buildJsonObject {
        put("servers", JsonArray(serverPayloads))
        if (errors.isNotEmpty()) put("errors", JsonArray(errors))
    }
    return jsonToolResult(tool, "", payload, options)
}

private fun singlePagePayload(
    server: String,
    key: String,
    items: List<JsonObject>,
    nextCursor: String?,
): JsonObject = buildJsonObject {
    put("server", JsonPrimitive(server))
    put(key, JsonArray(items))
    if (nextCursor != null) put("nextCursor", JsonPrimitive(nextCursor))
}

private suspend fun readResourceResult(
    server: String,
    client: McpClient,
    uri: String,
    signal: RunSignal?,
    options: McpToolAdapterOptions,
): AgentToolResult {
    val read = client.readResource(uri, McpRequestOptions(signal = signal))
    val content = mutableListOf<Content>()
    for (item in read.contents) {
        if (read.contents.size > 1) content.add(TextContent("${item.uri}:"))
        content.addAll(readResourceContent(server, item, options))
    }
    if (content.isEmpty()) content.add(TextContent("Resource $uri is empty."))
    val limited = limitMcpContent(server, READ_MCP_RESOURCE_TOOL, content, options)
    val payload = buildJsonObject {
        put("server", JsonPrimitive(server))
        put("uri", JsonPrimitive(uri))
        put(
            "contents",
            JsonArray(
                read.contents.map { item -> JsonObject(item.raw.filterKeys { key -> key != "_meta" }) },
            ),
        )
    }
    return AgentToolResult(
        content = limited.content,
        details = mcpDetails(server, READ_MCP_RESOURCE_TOOL, limited.fullOutputPath),
        structuredContent = payload,
    )
}

private suspend fun readResourceContent(
    serverName: String,
    item: McpResourceContent,
    options: McpToolAdapterOptions,
): List<Content> {
    val text = item.text
    if (text != null) return listOf(TextContent(text))
    val blob = item.blob ?: return emptyList()
    val mimeType = item.mimeType
    if (mimeType != null && mimeType.startsWith("image/")) {
        return listOf(ImageContent(data = blob, mimeType = mimeType))
    }
    val bytes = mcpDecodeBase64(blob)
    if (bytes == null) {
        return listOf(TextContent("[binary resource ${item.uri} (${mimeType ?: "unknown type"}) omitted]"))
    }
    if (mcpIsTextMimeType(mimeType)) return listOf(TextContent(bytes.decodeToString()))
    val kind = "${mimeType ?: "unknown type"}, ${formatSize(bytes.size.toLong())}"
    val path = mcpSaveBinary(serverName, "resource:${item.uri}", mimeType, item.uri, bytes, options)
    return listOf(
        TextContent(
            if (path != null) {
                "[Binary resource ${item.uri} ($kind) saved to $path]"
            } else {
                "[Binary resource ${item.uri} ($kind) omitted]"
            },
        ),
    )
}

private suspend fun jsonToolResult(
    tool: String,
    server: String,
    payload: JsonObject,
    options: McpToolAdapterOptions,
): AgentToolResult {
    val text = PiJson.encodeToString(JsonElement.serializer(), payload)
    val limited = limitMcpContent(server, tool, listOf(TextContent(text)), options)
    return AgentToolResult(
        content = limited.content,
        details = mcpDetails(server, tool, limited.fullOutputPath),
        structuredContent = payload,
    )
}

private fun unknownServerMessage(requestedServer: String, available: Collection<String>): String =
    if (available.isEmpty()) {
        "MCP server \"$requestedServer\" is not configured."
    } else {
        "MCP server \"$requestedServer\" is not configured. Available servers: ${available.joinToString(", ")}"
    }

private suspend fun resourceToolCall(
    tool: String,
    block: suspend () -> AgentToolResult,
): AgentToolResult = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: RequestAbortedException) {
    throw error
} catch (error: McpRpcException) {
    textResult(error.message ?: "MCP $tool failed", isError = true)
} catch (error: McpHttpException) {
    textResult(error.message ?: "MCP $tool failed", isError = true)
} catch (error: McpTimeoutException) {
    textResult(error.message ?: "MCP $tool timed out", isError = true)
}

internal fun mcpListedItem(server: String, raw: JsonObject): JsonObject {
    val result = LinkedHashMap<String, JsonElement>()
    result["server"] = JsonPrimitive(server)
    for ((key, value) in raw) {
        if (key == "_meta" || key == "icons") continue
        result[key] = value
    }
    return JsonObject(result)
}
