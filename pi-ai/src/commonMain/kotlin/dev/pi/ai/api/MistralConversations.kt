package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.CacheRetention
import dev.pi.ai.ClientIdentity
import dev.pi.ai.Content
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelInputModality
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.ProviderResponse
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingDeltaEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.ToolChoice
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.ai.calculateCost
import dev.pi.ai.clampThinkingLevel
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.SseParser
import dev.pi.ai.utils.PartialJson
import dev.pi.ai.utils.Retry
import dev.pi.ai.utils.getCurrentTools
import dev.pi.ai.utils.getSystemMessageText
import dev.pi.ai.utils.renderSystemMessageUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

private const val MISTRAL_API = "mistral-conversations"
private const val MISTRAL_TOOL_CALL_ID_LENGTH = 9
private const val MAX_MISTRAL_ERROR_BODY_CHARS = 4000
private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L

private val MISTRAL_UNSUPPORTED_STRICT_SCHEMA_KEYS = listOf(
    "\$ref",
    "\$defs",
    "definitions",
    "allOf",
    "oneOf",
    "patternProperties",
    "dependentSchemas",
    "dependencies",
    "unevaluatedProperties",
    "propertyNames",
    "contains",
    "prefixItems",
    "not",
    "if",
    "then",
    "else",
)

private data class MistralOptions(
    val stream: StreamOptions,
    val toolChoice: JsonElement? = null,
    val promptMode: String? = null,
    val reasoningEffort: String? = null,
)

private class MistralHttpException(
    val statusCode: Int,
    val body: String,
    val statusText: String,
) : RuntimeException(statusText.ifEmpty { "Request failed with status $statusCode" })

private class MistralStreamException(message: String) : RuntimeException(message)

private class MistralToolCallIdNormalizer {
    private val idMap = mutableMapOf<String, String>()
    private val reverseMap = mutableMapOf<String, String>()

    fun normalize(id: String): String {
        idMap[id]?.let { return it }
        var attempt = 0
        while (true) {
            val candidate = deriveMistralToolCallId(id, attempt)
            val owner = reverseMap[candidate]
            if (owner == null || owner == id) {
                idMap[id] = candidate
                reverseMap[candidate] = id
                return candidate
            }
            attempt += 1
        }
    }
}

private fun deriveMistralToolCallId(id: String, attempt: Int): String {
    val normalized = id.replace(Regex("[^a-zA-Z0-9]"), "")
    if (attempt == 0 && normalized.length == MISTRAL_TOOL_CALL_ID_LENGTH) return normalized
    val seedBase = normalized.ifEmpty { id }
    val seed = if (attempt == 0) seedBase else "$seedBase:$attempt"
    return shortHash(seed)
        .replace(Regex("[^a-zA-Z0-9]"), "")
        .take(MISTRAL_TOOL_CALL_ID_LENGTH)
}

private fun mistralZeroCost(): UsageCost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0)

private fun mistralIdentityHeaders(model: Model, identity: ClientIdentity?): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    headers["User-Agent"] = identity?.userAgent ?: "pi"
    val appName = identity?.appName
    val appUrl = identity?.appUrl
    if (appName == null && appUrl == null) return headers
    val isOpenRouter = model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")
    val isNvidia = model.provider == "nvidia" || model.baseUrl.contains("integrate.api.nvidia.com")
    val isOpenCode = model.provider == "opencode" || model.provider == "opencode-go" ||
        model.baseUrl.contains("opencode.ai")
    when {
        isOpenRouter -> {
            if (appUrl != null) headers["HTTP-Referer"] = appUrl
            if (appName != null) headers["X-OpenRouter-Title"] = appName
        }

        isNvidia -> if (appName != null) {
            headers["X-BILLING-INVOKE-ORIGIN"] = appName.replaceFirstChar { it.uppercase() }
        }

        isOpenCode -> if (appName != null) headers["x-opencode-client"] = appName
    }
    return headers
}

private fun applyMistralHeaderOverrides(headers: MutableMap<String, String>, overrides: Map<String, String?>?) {
    if (overrides == null) return
    for ((name, value) in overrides) {
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let { headers.remove(it) }
        if (value != null) headers[name] = value
    }
}

private fun hasMistralHeaderOverride(overrides: Map<String, String?>?, target: String): Boolean =
    overrides != null && overrides.keys.any { it.lowercase() == target.lowercase() }

private fun shouldUseMistralPromptCaching(options: MistralOptions): Boolean =
    options.stream.cacheRetention != CacheRetention.None && options.stream.sessionId != null

private fun buildMistralHeaders(model: Model, apiKey: String, options: MistralOptions): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers.putAll(mistralIdentityHeaders(model, options.stream.identity))
    headers["accept"] = "text/event-stream"
    headers["authorization"] = "Bearer $apiKey"
    headers["content-type"] = "application/json"
    applyMistralHeaderOverrides(headers, model.headers)
    applyMistralHeaderOverrides(headers, options.stream.headers)
    val hasExplicitAffinity = hasMistralHeaderOverride(model.headers, "x-affinity") ||
        hasMistralHeaderOverride(options.stream.headers, "x-affinity")
    if (shouldUseMistralPromptCaching(options) && !hasExplicitAffinity) {
        headers["x-affinity"] = options.stream.sessionId!!
    }
    return headers
}

private fun remapMistralProperty(record: MutableMap<String, JsonElement>, source: String, target: String) {
    val value = record.remove(source) ?: return
    record[target] = value
}

private fun remapMistralProperties(record: MutableMap<String, JsonElement>, pairs: List<Pair<String, String>>) {
    for ((source, target) in pairs) remapMistralProperty(record, source, target)
}

private val MISTRAL_PAYLOAD_PROPERTY_REMAPPINGS = listOf(
    "topP" to "top_p",
    "maxTokens" to "max_tokens",
    "randomSeed" to "random_seed",
    "responseFormat" to "response_format",
    "toolChoice" to "tool_choice",
    "presencePenalty" to "presence_penalty",
    "frequencyPenalty" to "frequency_penalty",
    "parallelToolCalls" to "parallel_tool_calls",
    "reasoningEffort" to "reasoning_effort",
    "promptMode" to "prompt_mode",
    "promptCacheKey" to "prompt_cache_key",
    "safePrompt" to "safe_prompt",
)

private val MISTRAL_MESSAGE_PROPERTY_REMAPPINGS = listOf(
    "toolCalls" to "tool_calls",
    "toolCallId" to "tool_call_id",
)

private val MISTRAL_CONTENT_CHUNK_PROPERTY_REMAPPINGS = listOf(
    "imageUrl" to "image_url",
    "documentUrl" to "document_url",
    "documentName" to "document_name",
    "fileId" to "file_id",
    "referenceIds" to "reference_ids",
    "inputAudio" to "input_audio",
)

private fun toWireContentChunk(chunk: JsonObject): JsonObject {
    val wire = LinkedHashMap(chunk)
    remapMistralProperties(wire, MISTRAL_CONTENT_CHUNK_PROPERTY_REMAPPINGS)
    return JsonObject(wire)
}

private fun toWireMessage(message: JsonObject): JsonObject {
    val wire = LinkedHashMap(message)
    remapMistralProperties(wire, MISTRAL_MESSAGE_PROPERTY_REMAPPINGS)
    val content = wire["content"]
    if (content is JsonArray) {
        wire["content"] = JsonArray(content.map { chunk -> chunk as? JsonObject }.mapNotNull { chunk ->
            chunk?.let(::toWireContentChunk)
        })
    }
    return JsonObject(wire)
}

private fun toWirePayload(payload: JsonObject): JsonObject {
    val wire = LinkedHashMap(payload)
    remapMistralProperties(wire, MISTRAL_PAYLOAD_PROPERTY_REMAPPINGS)
    val messages = wire["messages"]
    if (messages is JsonArray) {
        wire["messages"] = JsonArray(messages.map { message -> message as? JsonObject }.mapNotNull { message ->
            message?.let(::toWireMessage)
        })
    }
    val responseFormat = wire["response_format"] as? JsonObject
    if (responseFormat != null) {
        val wireResponseFormat = LinkedHashMap(responseFormat)
        remapMistralProperty(wireResponseFormat, "jsonSchema", "json_schema")
        val jsonSchema = wireResponseFormat["json_schema"] as? JsonObject
        if (jsonSchema != null) {
            val wireJsonSchema = LinkedHashMap(jsonSchema)
            remapMistralProperty(wireJsonSchema, "schemaDefinition", "schema")
            wireResponseFormat["json_schema"] = JsonObject(wireJsonSchema)
        }
        wire["response_format"] = JsonObject(wireResponseFormat)
    }
    return JsonObject(wire)
}

private fun mistralSanitize(text: String): String = sanitizeSurrogates(text)

private fun toMistralImageChunk(image: ImageContent): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("image_url"))
    put("imageUrl", JsonPrimitive("data:${image.mimeType};base64,${image.data}"))
}

private fun buildMistralToolResultText(
    text: String,
    hasImages: Boolean,
    supportsImages: Boolean,
    isError: Boolean,
): String {
    val trimmed = text.trim()
    val errorPrefix = if (isError) "[tool error] " else ""
    if (trimmed.isNotEmpty()) {
        val imageSuffix = if (hasImages && !supportsImages) {
            "\n[tool image omitted: model does not support images]"
        } else {
            ""
        }
        return "$errorPrefix$trimmed$imageSuffix"
    }
    if (hasImages) {
        return if (supportsImages) {
            if (isError) "[tool error] (see attached image)" else "(see attached image)"
        } else {
            if (isError) {
                "[tool error] (image omitted: model does not support images)"
            } else {
                "(image omitted: model does not support images)"
            }
        }
    }
    return if (isError) "[tool error] (no tool output)" else "(no tool output)"
}

private fun toMistralChatMessages(messages: List<Message>, supportsImages: Boolean): List<JsonObject> {
    val result = mutableListOf<JsonObject>()
    for ((index, message) in messages.withIndex()) {
        when (message) {
            is SystemMessage -> {
                val text = if (index == 0) getSystemMessageText(message) else renderSystemMessageUpdate(message)
                if (text.isNotEmpty()) {
                    result.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("system"))
                            put("content", JsonPrimitive(mistralSanitize(text)))
                        },
                    )
                }
            }

            is UserMessage -> {
                when (val content = message.content) {
                    is PlainTextContent -> {
                        result.add(
                            buildJsonObject {
                                put("role", JsonPrimitive("user"))
                                put("content", JsonPrimitive(mistralSanitize(content.text)))
                            },
                        )
                    }

                    is dev.pi.ai.BlockContent -> {
                        val hadImages = content.blocks.any { it is ImageContent }
                        val chunks = content.blocks.mapNotNull { block ->
                            when (block) {
                                is TextContent -> buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", JsonPrimitive(mistralSanitize(block.text)))
                                }

                                is ImageContent -> if (supportsImages) toMistralImageChunk(block) else null
                                else -> null
                            }
                        }
                        if (chunks.isNotEmpty()) {
                            result.add(
                                buildJsonObject {
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonArray(chunks))
                                },
                            )
                        } else if (hadImages && !supportsImages) {
                            result.add(
                                buildJsonObject {
                                    put("role", JsonPrimitive("user"))
                                    put("content", JsonPrimitive("(image omitted: model does not support images)"))
                                },
                            )
                        }
                    }
                }
            }

            is AssistantMessage -> {
                val contentParts = mutableListOf<JsonObject>()
                val toolCalls = mutableListOf<JsonObject>()
                for (block in message.content) {
                    when (block) {
                        is TextContent -> {
                            if (block.text.trim().isNotEmpty()) {
                                contentParts.add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(mistralSanitize(block.text)))
                                    },
                                )
                            }
                        }

                        is ThinkingContent -> {
                            if (block.thinking.trim().isNotEmpty()) {
                                contentParts.add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("thinking"))
                                        put(
                                            "thinking",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("text"))
                                                        put("text", JsonPrimitive(mistralSanitize(block.thinking)))
                                                    },
                                                ),
                                            ),
                                        )
                                    },
                                )
                            }
                        }

                        is ToolCall -> {
                            toolCalls.add(
                                buildJsonObject {
                                    put("id", JsonPrimitive(block.id))
                                    put("type", JsonPrimitive("function"))
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", JsonPrimitive(block.name))
                                            put("arguments", JsonPrimitive(block.arguments.toString()))
                                        },
                                    )
                                    put("index", JsonPrimitive(0))
                                },
                            )
                        }

                        else -> Unit
                    }
                }
                if (contentParts.isEmpty() && toolCalls.isEmpty()) continue
                result.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("assistant"))
                        put("prefix", JsonPrimitive(false))
                        if (contentParts.isNotEmpty()) put("content", JsonArray(contentParts))
                        if (toolCalls.isNotEmpty()) put("toolCalls", JsonArray(toolCalls))
                    },
                )
            }

            is ToolResultMessage -> {
                val textResult = message.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
                val hasImages = message.content.any { it is ImageContent }
                val toolText = buildMistralToolResultText(textResult, hasImages, supportsImages, message.isError)
                val toolContent = mutableListOf<JsonObject>(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(toolText))
                    },
                )
                if (supportsImages) {
                    for (block in message.content) {
                        if (block is ImageContent) toolContent.add(toMistralImageChunk(block))
                    }
                }
                result.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("tool"))
                        put("toolCallId", JsonPrimitive(message.toolCallId))
                        put("name", JsonPrimitive(message.toolName))
                        put("content", JsonArray(toolContent))
                    },
                )
            }
        }
    }
    return result
}

private fun toMistralFunctionTools(tools: List<Tool>): List<JsonObject> = tools.map { tool ->
    val strict = resolveMistralStrictSampling(tool, true)
    val parameters = if (strict == true) makeMistralStrictJsonSchema(tool.parameters) else tool.parameters
    buildJsonObject {
        put("type", JsonPrimitive("function"))
        put(
            "function",
            buildJsonObject {
                put("name", JsonPrimitive(tool.name))
                put("description", JsonPrimitive(tool.description))
                put("parameters", parameters)
                put("strict", JsonPrimitive(strict ?: false))
            },
        )
    }
}

private fun buildMistralChatPayload(
    model: Model,
    context: TranscriptContext,
    messages: List<Message>,
    options: MistralOptions,
): JsonObject {
    val payload = LinkedHashMap<String, JsonElement>()
    payload["model"] = JsonPrimitive(model.id)
    payload["stream"] = JsonPrimitive(true)
    payload["messages"] = JsonArray(toMistralChatMessages(messages, model.input.contains(ModelInputModality.Image)))
    val currentTools = getCurrentTools(context.messages)
    if (currentTools.isNotEmpty()) payload["tools"] = JsonArray(toMistralFunctionTools(currentTools))
    options.stream.temperature?.let { payload["temperature"] = JsonPrimitive(it) }
    options.stream.maxTokens?.let { payload["maxTokens"] = JsonPrimitive(it) }
    options.toolChoice?.let { payload["toolChoice"] = it }
    options.promptMode?.let { payload["promptMode"] = JsonPrimitive(it) }
    options.reasoningEffort?.let { payload["reasoningEffort"] = JsonPrimitive(it) }
    if (shouldUseMistralPromptCaching(options)) payload["promptCacheKey"] = JsonPrimitive(options.stream.sessionId!!)
    return JsonObject(payload)
}

private class UnsupportedMistralStrictSchemaException(message: String) : RuntimeException(message)

private fun isMistralStructuredSchema(schema: JsonElement): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeElement = obj["type"]
    val types = when {
        typeElement is JsonPrimitive && typeElement.isString -> listOf(typeElement.content)
        typeElement is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }
    return types.contains("object") || types.contains("array") ||
        obj.containsKey("properties") || obj.containsKey("items")
}

private fun mistralSchemaAllowsNull(schema: JsonElement?): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeElement = obj["type"]
    if (typeElement is JsonPrimitive && typeElement.isString && typeElement.content == "null") return true
    if (typeElement is JsonArray && typeElement.any {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "null"
        }
    ) {
        return true
    }
    if (obj["const"] is JsonNull) return true
    val enumValues = obj["enum"] as? JsonArray
    if (enumValues != null && enumValues.any { it is JsonNull }) return true
    val anyOf = obj["anyOf"] as? JsonArray ?: return false
    return anyOf.any { mistralSchemaAllowsNull(it) }
}

private fun makeMistralStrictNode(schema: JsonElement): JsonObject {
    val obj = schema as? JsonObject
        ?: throw UnsupportedMistralStrictSchemaException("boolean schemas are unsupported")
    for (key in MISTRAL_UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (obj.containsKey(key)) throw UnsupportedMistralStrictSchemaException("$key schemas are unsupported")
    }
    val result = LinkedHashMap(obj)
    obj["anyOf"]?.let { anyOf ->
        if (anyOf !is JsonArray || anyOf.isEmpty()) {
            throw UnsupportedMistralStrictSchemaException("anyOf must contain at least one schema")
        }
        result["anyOf"] = JsonArray(
            anyOf.map { variant ->
                if (isMistralStructuredSchema(variant)) {
                    throw UnsupportedMistralStrictSchemaException("object and array unions are unsupported")
                }
                makeMistralStrictNode(variant)
            },
        )
    }
    obj["items"]?.let { items ->
        if (items is JsonArray) throw UnsupportedMistralStrictSchemaException("tuple schemas are unsupported")
        result["items"] = makeMistralStrictNode(items)
    }
    val isObjectSchema = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object"
    if (obj.containsKey("properties") && !isObjectSchema) {
        throw UnsupportedMistralStrictSchemaException("properties require type object")
    }
    if (!isObjectSchema) return JsonObject(result)
    obj["additionalProperties"]?.let { additional ->
        if (additional != JsonPrimitive(false)) {
            throw UnsupportedMistralStrictSchemaException("schema-valued or true additionalProperties is unsupported")
        }
    }
    val propertiesElement = obj["properties"]
    if (propertiesElement != null && propertiesElement !is JsonObject) {
        throw UnsupportedMistralStrictSchemaException("object properties must be a schema map")
    }
    val properties = propertiesElement as? JsonObject ?: JsonObject(emptyMap())
    val requiredElement = obj["required"]
    if (requiredElement != null) {
        val valid = requiredElement is JsonArray && requiredElement.all { element ->
            (element as? JsonPrimitive)?.isString == true
        }
        if (!valid) throw UnsupportedMistralStrictSchemaException("object required must be a string array")
    }
    val required = (requiredElement as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.toSet()
        .orEmpty()
    val propertyNames = properties.keys.toList()
    if (required.any { it !in propertyNames }) {
        throw UnsupportedMistralStrictSchemaException("required contains an unknown property")
    }
    val strictProperties = LinkedHashMap<String, JsonElement>()
    for ((key, property) in properties) {
        val strictProperty = makeMistralStrictNode(property)
        strictProperties[key] = if (key !in required && !mistralSchemaAllowsNull(property)) {
            buildJsonObject {
                put(
                    "anyOf",
                    JsonArray(listOf(strictProperty, buildJsonObject { put("type", JsonPrimitive("null")) })),
                )
            }
        } else {
            strictProperty
        }
    }
    result["properties"] = JsonObject(strictProperties)
    result["required"] = JsonArray(propertyNames.map { JsonPrimitive(it) })
    result["additionalProperties"] = JsonPrimitive(false)
    return JsonObject(result)
}

private fun makeMistralStrictJsonSchema(parameters: JsonObject): JsonObject {
    val root = makeMistralStrictNode(parameters)
    if ((root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "object") {
        throw UnsupportedMistralStrictSchemaException("root schema must have type object")
    }
    return root
}

private fun resolveMistralStrictSampling(tool: Tool, supportsStrictMode: Boolean): Boolean? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "json_schema") return null
    if (supportsStrictMode) {
        return try {
            makeMistralStrictJsonSchema(tool.parameters)
            true
        } catch (error: UnsupportedMistralStrictSchemaException) {
            if (stringField(config, "strict") != "require") return null
            throw IllegalStateException(
                "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but ${error.message}.",
            )
        }
    }
    if (stringField(config, "strict") == "require") {
        throw IllegalStateException(
            "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
        )
    }
    return null
}

private fun mapMistralChatStopReason(reason: String?): Pair<StopReason, String?> = when (reason) {
    null -> Pair(StopReason.Stop, null)
    "stop" -> Pair(StopReason.Stop, null)
    "length", "model_length" -> Pair(StopReason.Length, null)
    "tool_calls" -> Pair(StopReason.ToolUse, null)
    "error" -> Pair(StopReason.Error, "Provider stopped with: error (server error)")
    else -> Pair(StopReason.Error, "Provider stopped with: $reason")
}

private fun getMistralCachedPromptTokens(usage: JsonObject, promptTokens: Long): Long {
    val cached = mistralCachedTokensFrom(usage, listOf("cachedTokens", "cached_tokens"))
        ?: listOf("promptTokensDetails", "prompt_tokens_details", "promptTokenDetails", "prompt_token_details")
            .firstNotNullOfOrNull { key -> objectField(usage, key)?.let { mistralCachedTokensFrom(it, listOf("cachedTokens", "cached_tokens")) } }
        ?: mistralCachedTokensFrom(usage, listOf("numCachedTokens", "num_cached_tokens"))
        ?: 0L
    return minOf(promptTokens, maxOf(0L, cached))
}

private fun mistralCachedTokensFrom(usage: JsonObject, keys: List<String>): Long? {
    for (key in keys) {
        val element = usage[key] as? JsonPrimitive ?: continue
        if (element.isString) continue
        val value = element.content.toDoubleOrNull() ?: continue
        if (value.isFinite()) return value.toLong()
    }
    return null
}

private fun formatMistralError(error: Throwable): String = when (error) {
    is MistralHttpException -> {
        if (error.statusCode != 0 && error.body.isNotBlank()) {
            "Mistral API error (${error.statusCode}): ${truncateMistralErrorText(error.body.trim(), MAX_MISTRAL_ERROR_BODY_CHARS)}"
        } else if (error.statusCode != 0) {
            "Mistral API error (${error.statusCode}): ${error.message}"
        } else {
            error.message ?: error.toString()
        }
    }

    else -> error.message ?: error.toString()
}

private fun truncateMistralErrorText(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    return text.take(maxChars) + "... [truncated ${text.length - maxChars} chars]"
}

private class MistralStreamState(
    val model: Model,
    val output: AssistantMessage,
    val stream: AssistantMessageEventStream,
) {
    var currentBlock: Content? = null
    val toolPartialArgs = mutableMapOf<Int, String>()
    val toolBlocksByKey = mutableMapOf<Any, Int>()

    fun blockIndex(): Int = output.content.size - 1

    fun finishCurrentBlock() {
        val block = currentBlock ?: return
        when (block) {
            is TextContent -> stream.push(TextEndEvent(blockIndex(), block.text, output))
            is ThinkingContent -> stream.push(ThinkingEndEvent(blockIndex(), block.thinking, output))
            else -> Unit
        }
    }
}

class MistralConversationsApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = MISTRAL_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream {
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runMistralStream(transport, stream, model, context, MistralOptions(stream = options))
        }
        return stream
    }

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val apiKey = options.stream.apiKey
        if (apiKey.isNullOrEmpty()) throw IllegalStateException("No API key for provider: ${model.provider}")
        val base = buildBaseOptions(model, context, options)
        val toolChoice = options.toolChoice?.let { choice -> JsonPrimitive(mapMistralToolChoice(choice)) }
        val clampedReasoning = options.reasoning?.let { clampThinkingLevel(model, it.toModelThinkingLevel()) }
        val reasoning = if (clampedReasoning == null || clampedReasoning == ModelThinkingLevel.Off) {
            null
        } else {
            clampedReasoning
        }
        val effortMap = if (model.reasoning) model.thinkingLevelMap else null
        val reasoningEffort = if (effortMap != null) {
            if (reasoning != null) {
                effortMap[reasoning.jsonName()] ?: "high"
            } else if (effortMap.containsKey("off")) {
                effortMap["off"]
            } else {
                null
            }
        } else {
            null
        }
        val promptMode = if (model.reasoning && effortMap == null && reasoning != null) "reasoning" else null
        val stream = createAssistantMessageEventStream()
        val scope = CoroutineScope(currentCoroutineContext())
        scope.launch {
            runMistralStream(
                transport,
                stream,
                model,
                context,
                MistralOptions(
                    stream = base,
                    toolChoice = toolChoice,
                    promptMode = promptMode,
                    reasoningEffort = reasoningEffort,
                ),
            )
        }
        return stream
    }
}

fun mistralConversationsApi(transport: HttpTransport): ApiProvider = MistralConversationsApi(transport)

private fun mapMistralToolChoice(choice: ToolChoice): String = when (choice) {
    ToolChoice.Auto -> "auto"
    ToolChoice.None -> "none"
}

private suspend fun runMistralStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: MistralOptions,
) {
    val signal = options.stream.signal
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage.empty(),
        stopReason = StopReason.Pending,
        timestamp = nowMillis(),
    )
    try {
        val apiKey = options.stream.apiKey
        if (apiKey.isNullOrEmpty()) throw IllegalStateException("No API key for provider: ${model.provider}")
        val compat = resolveMistralConversationsCompat(model)
        val normalizedContext = resolveTranscript(context, compat.supportsMidConvoSystemMessages)
        val idNormalizer = MistralToolCallIdNormalizer()
        val transformedMessages = transformMessages(normalizedContext.messages, model) { id ->
            idNormalizer.normalize(id)
        }
        var payload = buildMistralChatPayload(model, normalizedContext, transformedMessages, options)
        val nextPayload = options.stream.onPayload?.invoke(payload, model)
        if (nextPayload is JsonObject) payload = nextPayload
        signal?.throwIfAborted()
        val response = executeMistralRequest(transport, model, payload, apiKey, options)
        options.stream.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()

        val state = MistralStreamState(model, output, stream)
        val sse = SseParser(response.body)
        try {
            while (true) {
                val event = sse.next() ?: break
                val data = event.data.trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                val parsed = try {
                    PiJson.parseToJsonElement(data) as? JsonObject
                } catch (_: Throwable) {
                    null
                }
                if (parsed == null || parsed["choices"] !is JsonArray) {
                    throw MistralStreamException("Invalid Mistral streaming event")
                }
                options.stream.onProviderStreamEvent?.invoke(parsed, model)
                mistralConsumeChatChunk(state, parsed)
                signal?.throwIfAborted()
            }
        } finally {
            sse.close()
            response.close()
        }

        state.finishCurrentBlock()
        for (index in state.toolBlocksByKey.values) {
            val block = output.content.getOrNull(index) as? ToolCall ?: continue
            val parsed = PartialJson.parseToolCallArguments(state.toolPartialArgs[index])
            val updated = block.copy(arguments = parsed.arguments, argumentsError = parsed.argumentsError)
            output.content[index] = updated
            state.toolPartialArgs.remove(index)
            stream.push(ToolCallEndEvent(index, updated, output))
        }

        if (signal?.isAborted == true) throw RequestAbortedException("Request was aborted")
        if (output.stopReason == StopReason.Pending) {
            throw MistralStreamException("Mistral stream ended without a finish reason")
        }
        if (output.stopReason == StopReason.Aborted || output.stopReason == StopReason.Error) {
            throw MistralStreamException(output.errorMessage ?: "An unknown error occurred")
        }
        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        val aborted = error is RequestAbortedException || signal?.isAborted == true ||
            output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else formatMistralError(error)
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private fun mistralEnsureTextBlock(state: MistralStreamState): TextContent {
    val current = state.currentBlock
    if (current is TextContent) return current
    state.finishCurrentBlock()
    val block = TextContent(text = "")
    state.output.content.add(block)
    state.currentBlock = block
    state.stream.push(TextStartEvent(state.blockIndex(), state.output))
    return block
}

private fun mistralEnsureThinkingBlock(state: MistralStreamState): ThinkingContent {
    val current = state.currentBlock
    if (current is ThinkingContent) return current
    state.finishCurrentBlock()
    val block = ThinkingContent(thinking = "")
    state.output.content.add(block)
    state.currentBlock = block
    state.stream.push(ThinkingStartEvent(state.blockIndex(), state.output))
    return block
}

private fun mistralConsumeChatChunk(state: MistralStreamState, chunk: JsonObject) {
    val model = state.model
    val output = state.output
    val stream = state.stream
    val chunkId = stringField(chunk, "id")
    if (output.responseId.isNullOrEmpty() && !chunkId.isNullOrEmpty()) output.responseId = chunkId
    val usage = objectField(chunk, "usage")
    if (usage != null) {
        val promptTokens = longField(usage, "prompt_tokens") ?: 0L
        val cachedPromptTokens = getMistralCachedPromptTokens(usage, promptTokens)
        output.usage.input = maxOf(0L, promptTokens - cachedPromptTokens)
        output.usage.output = longField(usage, "completion_tokens") ?: 0L
        output.usage.cacheRead = cachedPromptTokens
        output.usage.cacheWrite = 0L
        output.usage.totalTokens = longField(usage, "total_tokens")
            ?: (output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite)
        output.usage.cost = calculateCost(model, output.usage)
    }
    val choice = (chunk["choices"] as JsonArray).firstOrNull() as? JsonObject ?: return
    val finishReason = stringField(choice, "finish_reason")
    if (finishReason != null) {
        output.rawStopReason = finishReason
        val mapped = mapMistralChatStopReason(finishReason)
        output.stopReason = mapped.first
        mapped.second?.let { output.errorMessage = it }
    }
    val delta = objectField(choice, "delta") ?: return
    processMistralContentDelta(state, delta)
    processMistralToolCallDelta(state, delta)
}

private fun processMistralContentDelta(state: MistralStreamState, delta: JsonObject) {
    val output = state.output
    val stream = state.stream
    val content = delta["content"]
    if (content == null || content is JsonNull) return
    val items = when (content) {
        is JsonPrimitive -> listOf(content)
        is JsonArray -> content.toList()
        else -> return
    }
    for (item in items) {
        if (item is JsonPrimitive) {
            if (!item.isString) continue
            val textDelta = mistralSanitize(item.content)
            if (textDelta.isEmpty()) continue
            val block = mistralEnsureTextBlock(state)
            block.text += textDelta
            stream.push(TextDeltaEvent(state.blockIndex(), textDelta, output))
            continue
        }
        val itemObject = item as? JsonObject ?: continue
        when (stringField(itemObject, "type")) {
            "thinking" -> {
                val thinkingParts = itemObject["thinking"] as? JsonArray
                val deltaText = thinkingParts.orEmpty().mapNotNull { part ->
                    (part as? JsonObject)?.let { stringField(it, "text") }
                }.filter { it.isNotEmpty() }.joinToString("")
                val thinkingDelta = mistralSanitize(deltaText)
                if (thinkingDelta.isEmpty()) continue
                val block = mistralEnsureThinkingBlock(state)
                block.thinking += thinkingDelta
                stream.push(ThinkingDeltaEvent(state.blockIndex(), thinkingDelta, output))
            }

            "text" -> {
                val textDelta = mistralSanitize(stringField(itemObject, "text") ?: "")
                if (textDelta.isEmpty()) continue
                val block = mistralEnsureTextBlock(state)
                block.text += textDelta
                stream.push(TextDeltaEvent(state.blockIndex(), textDelta, output))
            }
        }
    }
}

private fun processMistralToolCallDelta(state: MistralStreamState, delta: JsonObject) {
    val output = state.output
    val stream = state.stream
    val toolCalls = delta["tool_calls"] as? JsonArray ?: return
    for (element in toolCalls) {
        val toolCall = element as? JsonObject ?: continue
        if (state.currentBlock != null) {
            state.finishCurrentBlock()
            state.currentBlock = null
        }
        val indexValue = longField(toolCall, "index")
        val rawId = stringField(toolCall, "id")
        val callId = if (!rawId.isNullOrEmpty() && rawId != "null") {
            rawId
        } else {
            deriveMistralToolCallId("toolcall:${indexValue ?: 0}", 0)
        }
        val key: Any = indexValue ?: callId
        val function = objectField(toolCall, "function")
        val name = function?.let { stringField(it, "name") } ?: ""
        var blockIndex = state.toolBlocksByKey[key]
        if (blockIndex != null) {
            val existing = output.content.getOrNull(blockIndex)
            if (existing !is ToolCall) blockIndex = null
        }
        if (blockIndex == null) {
            val block = ToolCall(id = callId, name = name, arguments = JsonObject(emptyMap()))
            output.content.add(block)
            blockIndex = output.content.size - 1
            state.toolBlocksByKey[key] = blockIndex
            state.toolPartialArgs[blockIndex] = ""
            stream.push(ToolCallStartEvent(blockIndex, output))
        }
        val block = output.content[blockIndex] as ToolCall
        if (block.name.isEmpty() && name.isNotEmpty()) {
            output.content[blockIndex] = block.copy(name = name)
        }
        val argumentsElement = function?.get("arguments")
        val argsDelta = when (argumentsElement) {
            is JsonPrimitive -> if (argumentsElement.isString) argumentsElement.content else argumentsElement.toString()
            is JsonObject -> argumentsElement.toString()
            is JsonArray -> argumentsElement.toString()
            else -> "{}"
        }
        val accumulated = (state.toolPartialArgs[blockIndex] ?: "") + argsDelta
        state.toolPartialArgs[blockIndex] = accumulated
        val parsed = PartialJson.parse(accumulated)
        val updated = (output.content[blockIndex] as ToolCall).copy(
            arguments = parsed as? JsonObject ?: JsonObject(emptyMap()),
        )
        output.content[blockIndex] = updated
        stream.push(ToolCallDeltaEvent(blockIndex, argsDelta, output))
    }
}

private suspend fun executeMistralRequest(
    transport: HttpTransport,
    model: Model,
    payload: JsonObject,
    apiKey: String,
    options: MistralOptions,
): HttpResponse {
    val signal = options.stream.signal
    val headers = buildMistralHeaders(model, apiKey, options)
    val url = model.baseUrl.trimEnd('/') + "/v1/chat/completions"
    val body = PiJson.encodeToString(JsonObject.serializer(), toWirePayload(payload)).encodeToByteArray()
    val maxRetries = options.stream.maxRetries ?: 0
    var retriesRemaining = maxRetries
    while (true) {
        signal?.throwIfAborted()
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = body,
            timeoutMs = options.stream.timeoutMs,
            signal = signal,
        )
        val response = try {
            transport.execute(request)
        } catch (error: HttpTransportException) {
            if (signal?.isAborted == true) throw RequestAbortedException("Request was aborted")
            if (retriesRemaining <= 0) throw error
            val retryIndex = maxRetries - retriesRemaining
            retriesRemaining -= 1
            Retry.sleep(mistralRetryDelayMs(null, retryIndex, options.stream.maxRetryDelayMs, error.message ?: ""), signal)
            continue
        }
        if (response.isSuccessful()) return response
        val retryable = isMistralRetryableResponse(response)
        if (retriesRemaining > 0 && retryable) {
            val status = response.status
            val bodyText = try {
                response.body.readUtf8().trim()
            } catch (_: Throwable) {
                ""
            } finally {
                response.close()
            }
            val providerMessage = if (bodyText.isEmpty()) "HTTP $status" else "$status: $bodyText"
            val retryIndex = maxRetries - retriesRemaining
            val delayMs = mistralRetryDelayMs(response, retryIndex, options.stream.maxRetryDelayMs, providerMessage)
            retriesRemaining -= 1
            Retry.sleep(delayMs, signal)
            continue
        }
        throw mistralHttpError(response)
    }
}

private fun isMistralRetryableResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private fun mistralRetryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        val parsedMs = response.header("retry-after-ms")?.toDoubleOrNull()
        if (parsedMs != null) {
            return validateMistralRetryDelayMs(parsedMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        }
        val parsedSeconds = response.header("retry-after")?.toDoubleOrNull()
        if (parsedSeconds != null) {
            return validateMistralRetryDelayMs(
                (parsedSeconds * 1000).toLong(),
                maxRetryDelayMs,
                providerErrorMessage,
            )
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateMistralRetryDelayMs(
    delayMs: Long,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw MistralStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun mistralHttpError(response: HttpResponse): MistralHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return MistralHttpException(response.status, body, "")
}
