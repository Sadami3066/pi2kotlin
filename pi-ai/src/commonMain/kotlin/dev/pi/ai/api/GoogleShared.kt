package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.BlockContent
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.ProviderResponse
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ThinkingDeltaEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallDeltaEvent
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolCallStartEvent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.ai.calculateCost
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.HttpTransportException
import dev.pi.ai.http.SseParser
import dev.pi.ai.utils.ProviderErrors
import dev.pi.ai.utils.Retry
import dev.pi.ai.utils.collapseSystemMessages
import dev.pi.ai.utils.getCurrentTools
import dev.pi.ai.utils.getInitialSystemMessage
import dev.pi.ai.utils.getSystemMessageText
import dev.pi.ai.utils.jsJsonStringify
import dev.pi.ai.utils.withoutInitialSystemMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

internal const val GOOGLE_GENERATIVE_AI_API = "google-generative-ai"
internal const val GOOGLE_VERTEX_API = "google-vertex"
internal const val GCP_VERTEX_CREDENTIALS_MARKER = "gcp-vertex-credentials"

private const val DEFAULT_MAX_RETRY_DELAY_MS = 60_000L
private val GOOGLE_API_KEY_PATTERN = Regex("^AIza[0-9A-Za-z_-]+$")
private val GEMINI_MAJOR_VERSION_PATTERN = Regex("^gemini(?:-live)?-(\\d+)")
private val GEMINI_3_OR_NEWER_PATTERN = Regex("gemini-3(?:\\.\\d+)?-(?:pro|flash)")
private val GEMINI_4_OR_NEWER_PATTERN = Regex("gemini-(?:[4-9]|[1-9]\\d)")
private val GEMMA_4_PATTERN = Regex("gemma-?4")
private val THOUGHT_SIGNATURE_PATTERN = Regex("^[A-Za-z0-9+/]+={0,2}$")

internal data class GoogleThinkingOptions(
    val enabled: Boolean,
    val budgetTokens: Long? = null,
    val level: String? = null,
)

internal enum class GoogleVertexCredentialMode { ApiKey, BearerToken }

internal class GoogleProviderHttpException(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
) : RuntimeException("HTTP $status")

internal class GoogleStreamException(message: String) : RuntimeException(message)

private class UnsupportedStrictJsonSchemaException(message: String) : Exception(message)

private var toolCallCounter = 0

internal fun resolveGoogleThinkingLevel(model: Model, level: String): String {
    val mapping = model.thinkingLevelMap
    val mapped = mapping?.get(level)
    val display = when {
        mapping != null && mapping.containsKey(level) -> mapped ?: "null"
        else -> "undefined"
    }
    val resolved = if (mapped is String) mapped.lowercase() else level
    return when (resolved) {
        "minimal", "low", "medium", "high" -> resolved
        else -> throw GoogleStreamException(
            "Unsupported Google thinking level mapping for ${model.provider}/${model.id}: $level -> $display",
        )
    }
}

internal fun usesGoogleThinkingLevel(model: Model): Boolean {
    val id = model.id.lowercase()
    return GEMINI_3_OR_NEWER_PATTERN.containsMatchIn(id) ||
        GEMINI_4_OR_NEWER_PATTERN.containsMatchIn(id) ||
        id == "gemini-flash-latest" ||
        id == "gemini-flash-lite-latest" ||
        GEMMA_4_PATTERN.containsMatchIn(id)
}

internal fun toGoogleThinkingLevel(level: String): String = when (level) {
    "minimal" -> "MINIMAL"
    "low" -> "LOW"
    "medium" -> "MEDIUM"
    else -> "HIGH"
}

internal fun getDisabledGoogleThinkingConfig(model: Model): JsonObject {
    if (!usesGoogleThinkingLevel(model)) {
        return buildJsonObject { put("thinkingBudget", JsonPrimitive(0)) }
    }
    val fallback = clampThinkingLevelName(model, "off")
    if (fallback == "off") {
        return buildJsonObject { put("thinkingBudget", JsonPrimitive(0)) }
    }
    val resolvedLevel = resolveGoogleThinkingLevel(model, fallback)
    return buildJsonObject { put("thinkingLevel", JsonPrimitive(toGoogleThinkingLevel(resolvedLevel))) }
}

private fun clampThinkingLevelName(model: Model, level: String): String {
    val clamped = dev.pi.ai.clampThinkingLevel(model, googleModelThinkingLevel(level))
    return when (clamped) {
        dev.pi.ai.ModelThinkingLevel.Off -> "off"
        dev.pi.ai.ModelThinkingLevel.Minimal -> "minimal"
        dev.pi.ai.ModelThinkingLevel.Low -> "low"
        dev.pi.ai.ModelThinkingLevel.Medium -> "medium"
        dev.pi.ai.ModelThinkingLevel.High -> "high"
        dev.pi.ai.ModelThinkingLevel.Xhigh -> "xhigh"
        dev.pi.ai.ModelThinkingLevel.Max -> "max"
    }
}

internal fun googleModelThinkingLevel(name: String): dev.pi.ai.ModelThinkingLevel = when (name) {
    "off" -> dev.pi.ai.ModelThinkingLevel.Off
    "minimal" -> dev.pi.ai.ModelThinkingLevel.Minimal
    "low" -> dev.pi.ai.ModelThinkingLevel.Low
    "high" -> dev.pi.ai.ModelThinkingLevel.High
    "xhigh" -> dev.pi.ai.ModelThinkingLevel.Xhigh
    "max" -> dev.pi.ai.ModelThinkingLevel.Max
    else -> dev.pi.ai.ModelThinkingLevel.Medium
}

internal fun isGoogleThinkingPart(part: JsonObject): Boolean {
    val thought = part["thought"] as? JsonPrimitive ?: return false
    return !thought.isString && thought.content == "true"
}

internal fun retainGoogleThoughtSignature(existing: String?, incoming: String?): String? {
    if (incoming != null && incoming.isNotEmpty()) return incoming
    return existing
}

private fun isValidThoughtSignature(signature: String?): Boolean {
    if (signature.isNullOrEmpty()) return false
    if (signature.length % 4 != 0) return false
    return THOUGHT_SIGNATURE_PATTERN.matches(signature)
}

private fun resolveThoughtSignature(isSameProviderAndModel: Boolean, signature: String?): String? =
    if (isSameProviderAndModel && isValidThoughtSignature(signature)) signature else null

internal fun googleGeminiMajorVersion(modelId: String): Int? {
    val match = GEMINI_MAJOR_VERSION_PATTERN.find(modelId.lowercase()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

internal fun requiresGoogleToolCallId(modelId: String): Boolean {
    val geminiMajor = googleGeminiMajorVersion(modelId)
    return modelId.startsWith("claude-") ||
        modelId.startsWith("gpt-oss-") ||
        (geminiMajor != null && geminiMajor >= 3)
}

private fun supportsMultimodalFunctionResponse(modelId: String): Boolean {
    val geminiMajor = googleGeminiMajorVersion(modelId)
    return geminiMajor == null || geminiMajor >= 3
}

internal fun supportsGoogleStrictToolSampling(modelId: String): Boolean {
    val major = googleGeminiMajorVersion(modelId)
    return major != null && major >= 3
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun convertGoogleMessages(model: Model, context: TranscriptContext): JsonArray {
    val conversation = withoutInitialSystemMessage(collapseSystemMessages(context).messages)
    val contents = mutableListOf<JsonElement>()
    val normalizeToolCallId: (String) -> String = { id ->
        if (!requiresGoogleToolCallId(model.id)) {
            id
        } else {
            id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        }
    }
    val transformedMessages = transformMessages(conversation, model, normalizeToolCallId)

    for (message in transformedMessages) {
        when (message) {
            is UserMessage -> {
                val content = message.content
                if (content is PlainTextContent) {
                    contents.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(content.text))) })
                                },
                            )
                        },
                    )
                } else if (content is BlockContent) {
                    val parts = buildJsonArray {
                        for (block in content.blocks) {
                            when (block) {
                                is TextContent -> add(
                                    buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(block.text))) },
                                )

                                is ImageContent -> add(
                                    buildJsonObject {
                                        put(
                                            "inlineData",
                                            buildJsonObject {
                                                put("mimeType", JsonPrimitive(block.mimeType))
                                                put("data", JsonPrimitive(block.data))
                                            },
                                        )
                                    },
                                )

                                else -> Unit
                            }
                        }
                    }
                    if (parts.isEmpty()) continue
                    contents.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("parts", parts)
                        },
                    )
                }
            }

            is dev.pi.ai.AssistantMessage -> {
                val parts = buildJsonArray {
                    val isSameProviderAndModel = message.provider == model.provider && message.model == model.id
                    for (block in message.content) {
                        when (block) {
                            is TextContent -> {
                                val thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, block.textSignature)
                                if ((block.text.isBlank()) && thoughtSignature == null) continue
                                add(
                                    buildJsonObject {
                                        put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
                                        if (thoughtSignature != null) put("thoughtSignature", JsonPrimitive(thoughtSignature))
                                    },
                                )
                            }

                            is ThinkingContent -> {
                                if (isSameProviderAndModel) {
                                    val thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, block.thinkingSignature)
                                    if ((block.thinking.isBlank()) && thoughtSignature == null) continue
                                    add(
                                        buildJsonObject {
                                            put("thought", JsonPrimitive(true))
                                            put("text", JsonPrimitive(sanitizeSurrogates(block.thinking)))
                                            if (thoughtSignature != null) put("thoughtSignature", JsonPrimitive(thoughtSignature))
                                        },
                                    )
                                } else {
                                    if (block.thinking.isBlank()) continue
                                    add(
                                        buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(block.thinking))) },
                                    )
                                }
                            }

                            is ToolCall -> {
                                val thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, block.thoughtSignature)
                                add(
                                    buildJsonObject {
                                        put(
                                            "functionCall",
                                            buildJsonObject {
                                                put("name", JsonPrimitive(block.name))
                                                put("args", block.arguments)
                                                if (requiresGoogleToolCallId(model.id)) put("id", JsonPrimitive(block.id))
                                            },
                                        )
                                        if (thoughtSignature != null) put("thoughtSignature", JsonPrimitive(thoughtSignature))
                                    },
                                )
                            }

                            else -> Unit
                        }
                    }
                }
                if (parts.isEmpty()) continue
                contents.add(
                    buildJsonObject {
                        put("role", JsonPrimitive("model"))
                        put("parts", parts)
                    },
                )
            }

            is ToolResultMessage -> {
                val textContent = message.content.filterIsInstance<TextContent>()
                val textResult = textContent.joinToString("\n") { it.text }
                val imageContent = if (model.input.contains(ModelInputModality.Image)) {
                    message.content.filterIsInstance<ImageContent>()
                } else {
                    emptyList()
                }
                val hasText = textResult.trim().isNotEmpty()
                val hasImages = imageContent.isNotEmpty()
                val supportsMultimodal = supportsMultimodalFunctionResponse(model.id)
                val responseValue = when {
                    hasText -> sanitizeSurrogates(textResult)
                    hasImages -> "(see attached image)"
                    else -> ""
                }
                val imageParts = imageContent.map { image ->
                    buildJsonObject {
                        put(
                            "inlineData",
                            buildJsonObject {
                                put("mimeType", JsonPrimitive(image.mimeType))
                                put("data", JsonPrimitive(image.data))
                            },
                        )
                    }
                }
                val functionResponse = buildJsonObject {
                    put("name", JsonPrimitive(message.toolName))
                    put(
                        "response",
                        if (message.isError) {
                            buildJsonObject { put("error", JsonPrimitive(responseValue)) }
                        } else {
                            buildJsonObject { put("output", JsonPrimitive(responseValue)) }
                        },
                    )
                    if (hasImages && supportsMultimodal) put("parts", JsonArray(imageParts))
                    if (requiresGoogleToolCallId(model.id)) put("id", JsonPrimitive(message.toolCallId))
                }
                val functionResponsePart = buildJsonObject { put("functionResponse", functionResponse) }
                var mergedIntoLastUserTurn = false
                val lastContent = contents.lastOrNull() as? JsonObject
                if (lastContent != null) {
                    val lastParts = lastContent.arr("parts")
                    if (lastContent.str("role") == "user" && lastParts != null) {
                        val hasFunctionResponses = lastParts.any { element ->
                            element is JsonObject && element.containsKey("functionResponse")
                        }
                        if (hasFunctionResponses) {
                            val parts = lastParts + functionResponsePart
                            contents[contents.size - 1] = JsonObject(
                                lastContent.toMutableMap().apply { put("parts", JsonArray(parts)) },
                            )
                            mergedIntoLastUserTurn = true
                        }
                    }
                }
                if (!mergedIntoLastUserTurn) {
                    contents.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("parts", JsonArray(listOf(functionResponsePart)))
                        },
                    )
                }
                if (hasImages && !supportsMultimodal) {
                    contents.add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put(
                                "parts",
                                JsonArray(
                                    listOf(buildJsonObject { put("text", JsonPrimitive("Tool result image:")) }) + imageParts,
                                ),
                            )
                        },
                    )
                }
            }

            else -> Unit
        }
    }
    return JsonArray(contents)
}

private val JSON_SCHEMA_META_DECLARATIONS = setOf(
    "\$schema",
    "\$id",
    "\$anchor",
    "\$dynamicAnchor",
    "\$vocabulary",
    "\$comment",
    "\$defs",
    "definitions",
)

private fun sanitizeForOpenApi(schema: JsonElement): JsonElement = when (schema) {
    is JsonObject -> JsonObject(
        schema.mapNotNull { (key, value) ->
            if (key in JSON_SCHEMA_META_DECLARATIONS) null else key to sanitizeForOpenApi(value)
        }.toMap(),
    )

    is JsonArray -> JsonArray(schema.map(::sanitizeForOpenApi))
    else -> schema
}

internal fun convertGoogleTools(
    tools: List<Tool>,
    useParameters: Boolean = false,
    supportsStrictMode: Boolean = true,
): JsonArray? {
    if (tools.isEmpty()) return null
    val declarations = tools.map { tool ->
        val strict = resolveGoogleJsonSchemaStrictSampling(tool, supportsStrictMode)
        val parameters = if (strict == true) makeStrictJsonSchema(tool.parameters) else tool.parameters
        buildJsonObject {
            put("name", JsonPrimitive(tool.name))
            put("description", JsonPrimitive(tool.description))
            if (useParameters) {
                put("parameters", sanitizeForOpenApi(parameters))
            } else {
                put("parametersJsonSchema", parameters)
            }
        }
    }
    return JsonArray(
        listOf(
            buildJsonObject { put("functionDeclarations", JsonArray(declarations)) },
        ),
    )
}

internal fun mapGoogleToolChoice(choice: String): String = when (choice) {
    "auto" -> "AUTO"
    "none" -> "NONE"
    "any" -> "ANY"
    else -> "AUTO"
}

internal fun resolveGoogleFunctionCallingMode(
    tools: List<Tool>,
    toolChoice: String?,
    supportsStrictMode: Boolean,
): String? {
    val useStrictMode = tools.any { resolveGoogleJsonSchemaStrictSampling(it, supportsStrictMode) == true }
    if (toolChoice == "none" || toolChoice == "any") return mapGoogleToolChoice(toolChoice)
    if (useStrictMode) return "VALIDATED"
    return if (toolChoice != null) mapGoogleToolChoice(toolChoice) else null
}

internal fun mapGoogleStopReason(reason: String): StopReason = when (reason) {
    "STOP" -> StopReason.Stop
    "MAX_TOKENS" -> StopReason.Length
    "BLOCKLIST",
    "PROHIBITED_CONTENT",
    "SPII",
    "SAFETY",
    "IMAGE_SAFETY",
    "IMAGE_PROHIBITED_CONTENT",
    "IMAGE_RECITATION",
    "IMAGE_OTHER",
    "RECITATION",
    "FINISH_REASON_UNSPECIFIED",
    "OTHER",
    "LANGUAGE",
    "MALFORMED_FUNCTION_CALL",
    "UNEXPECTED_TOOL_CALL",
    "TOO_MANY_TOOL_CALLS",
    "NO_IMAGE",
    -> StopReason.Error

    else -> throw GoogleStreamException("Unhandled stop reason: $reason")
}

internal fun mapGoogleStopReasonString(reason: String): StopReason = when (reason) {
    "STOP" -> StopReason.Stop
    "MAX_TOKENS" -> StopReason.Length
    else -> StopReason.Error
}

internal fun getGoogleBudget(model: Model, level: String, customBudgets: ThinkingBudgets?): Long {
    val custom = when (level) {
        "minimal" -> customBudgets?.minimal
        "low" -> customBudgets?.low
        "medium" -> customBudgets?.medium
        else -> customBudgets?.high
    }
    if (custom != null) return custom
    val id = model.id
    return when {
        id.contains("2.5-pro") -> when (level) {
            "minimal" -> 128L
            "low" -> 2048L
            "medium" -> 8192L
            else -> 32768L
        }

        id.contains("2.5-flash-lite") -> when (level) {
            "minimal" -> 512L
            "low" -> 2048L
            "medium" -> 8192L
            else -> 24576L
        }

        id.contains("2.5-flash") -> when (level) {
            "minimal" -> 128L
            "low" -> 2048L
            "medium" -> 8192L
            else -> 24576L
        }

        else -> -1L
    }
}

internal fun buildGoogleRequest(
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    toolChoice: String?,
    thinking: GoogleThinkingOptions?,
): JsonObject {
    val contents = convertGoogleMessages(model, context)
    val initialSystemMessage = getInitialSystemMessage(context.messages)
    val currentTools = getCurrentTools(context.messages)
    val generationConfig = LinkedHashMap<String, JsonElement>()
    options.temperature?.let { generationConfig["temperature"] = JsonPrimitive(it) }
    options.maxTokens?.let { generationConfig["maxOutputTokens"] = JsonPrimitive(it) }
    val supportsStrictMode = supportsGoogleStrictToolSampling(model.id)
    val functionCallingMode = if (currentTools.isNotEmpty()) {
        resolveGoogleFunctionCallingMode(currentTools, toolChoice, supportsStrictMode)
    } else {
        null
    }
    val systemInstruction = initialSystemMessage?.let(::getSystemMessageText) ?: ""
    if (thinking != null) {
        if (thinking.enabled && model.reasoning) {
            val thinkingConfig = LinkedHashMap<String, JsonElement>()
            thinkingConfig["includeThoughts"] = JsonPrimitive(true)
            if (thinking.level != null) {
                thinkingConfig["thinkingLevel"] = JsonPrimitive(thinking.level)
            } else if (thinking.budgetTokens != null) {
                thinkingConfig["thinkingBudget"] = JsonPrimitive(thinking.budgetTokens)
            }
            generationConfig["thinkingConfig"] = JsonObject(thinkingConfig)
        } else if (model.reasoning && !thinking.enabled) {
            generationConfig["thinkingConfig"] = getDisabledGoogleThinkingConfig(model)
        }
    }
    return buildJsonObject {
        put("contents", contents)
        if (generationConfig.isNotEmpty()) put("generationConfig", JsonObject(generationConfig))
        if (systemInstruction.isNotEmpty()) {
            put(
                "systemInstruction",
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put(
                        "parts",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("text", JsonPrimitive(sanitizeSurrogates(systemInstruction)))
                                },
                            )
                        },
                    )
                },
            )
        }
        if (currentTools.isNotEmpty()) {
            convertGoogleTools(currentTools, false, supportsStrictMode)?.let { put("tools", it) }
        }
        if (functionCallingMode != null) {
            put(
                "toolConfig",
                buildJsonObject {
                    put(
                        "functionCallingConfig",
                        buildJsonObject { put("mode", JsonPrimitive(functionCallingMode)) },
                    )
                },
            )
        }
    }
}

private fun hasHeader(headers: Map<String, String>, name: String): Boolean =
    headers.keys.any { it.equals(name, ignoreCase = true) }

internal fun buildGoogleBaseHeaders(model: Model, options: StreamOptions): LinkedHashMap<String, String> {
    val headers = LinkedHashMap<String, String>()
    headers["User-Agent"] = options.identity?.userAgent ?: "pi"
    model.headers?.forEach { (name, value) -> headers[name] = value }
    options.headers?.forEach { (name, value) ->
        headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach { headers.remove(it) }
        if (value != null) headers[name] = value
    }
    return headers
}

internal fun finishGoogleHeaders(headers: LinkedHashMap<String, String>): Map<String, String> {
    if (!hasHeader(headers, "content-type")) headers["Content-Type"] = "application/json"
    if (!hasHeader(headers, "accept")) headers["Accept"] = "text/event-stream"
    return headers
}

internal fun isGoogleApiKey(value: String): Boolean = GOOGLE_API_KEY_PATTERN.matches(value)

internal fun resolveGoogleVertexApiKey(apiKey: String?): String? {
    val trimmed = apiKey?.trim()
    if (trimmed.isNullOrEmpty()) return null
    if (trimmed == GCP_VERTEX_CREDENTIALS_MARKER) return null
    if (isPlaceholderApiKey(trimmed)) return null
    return trimmed
}

private fun isPlaceholderApiKey(apiKey: String): Boolean {
    if (apiKey.length <= 2 || !apiKey.startsWith("<") || !apiKey.endsWith(">")) return false
    return !apiKey.substring(1, apiKey.length - 1).contains(">")
}

internal fun resolveGoogleVertexProject(options: StreamOptions): String? =
    options.env?.get("GOOGLE_CLOUD_PROJECT") ?: options.env?.get("GCLOUD_PROJECT")

internal fun resolveGoogleVertexLocation(options: StreamOptions): String? =
    options.env?.get("GOOGLE_CLOUD_LOCATION")

private fun baseUrlIncludesApiVersion(baseUrl: String): Boolean =
    Regex("(?:^|/)v\\d+(?:beta\\d*)?(?:/|$)").containsMatchIn(baseUrl)

internal fun googleGenerativeAIUrl(model: Model): String {
    val configuredBaseUrl = model.baseUrl.trim()
    val base = if (configuredBaseUrl.isEmpty()) {
        "https://generativelanguage.googleapis.com/v1beta"
    } else {
        configuredBaseUrl.trimEnd('/')
    }
    return "$base/models/${model.id}:streamGenerateContent?alt=sse"
}

private fun googleVertexLocationBaseUrl(location: String): String = when (location) {
    "global" -> "https://aiplatform.googleapis.com"
    "us", "eu" -> "https://aiplatform.$location.rep.googleapis.com"
    else -> "https://$location-aiplatform.googleapis.com"
}

internal fun googleVertexUrl(
    model: Model,
    credentialMode: GoogleVertexCredentialMode,
    project: String?,
    location: String?,
): String {
    val configuredBaseUrl = model.baseUrl.trim()
    val hasLocationPlaceholder = configuredBaseUrl.contains("{location}")
    val customBaseUrl = configuredBaseUrl.takeIf { it.isNotEmpty() && !hasLocationPlaceholder }
    if (customBaseUrl != null) {
        val base = customBaseUrl.trimEnd('/')
        val version = if (baseUrlIncludesApiVersion(base)) "" else "/v1"
        return "$base$version/publishers/google/models/${model.id}:streamGenerateContent?alt=sse"
    }
    if (credentialMode == GoogleVertexCredentialMode.ApiKey) {
        return "https://aiplatform.googleapis.com/v1/publishers/google/models/${model.id}:streamGenerateContent?alt=sse"
    }
    val base = if (configuredBaseUrl.isEmpty() || hasLocationPlaceholder) {
        googleVertexLocationBaseUrl(location ?: "")
    } else {
        configuredBaseUrl.trimEnd('/')
    }
    return "$base/v1/projects/$project/locations/$location/publishers/google/models/${model.id}:streamGenerateContent?alt=sse"
}

internal suspend fun streamGoogle(
    transport: HttpTransport,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    toolChoice: String?,
    thinking: GoogleThinkingOptions?,
    resolveUrlAndHeaders: suspend () -> Pair<String, Map<String, String>>,
    endLabel: String,
): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    val scope = CoroutineScope(currentCoroutineContext())
    scope.launch {
        runGoogleStream(transport, stream, model, context, options, toolChoice, thinking, resolveUrlAndHeaders, endLabel)
    }
    return stream
}

private suspend fun runGoogleStream(
    transport: HttpTransport,
    stream: AssistantMessageEventStream,
    model: Model,
    context: TranscriptContext,
    options: StreamOptions,
    toolChoice: String?,
    thinking: GoogleThinkingOptions?,
    resolveUrlAndHeaders: suspend () -> Pair<String, Map<String, String>>,
    endLabel: String,
) {
    val signal = options.signal
    val output = AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage.empty(),
        stopReason = StopReason.Pending,
        timestamp = nowMillis(),
    )
    val state = GoogleStreamState(output, stream)
    try {
        signal?.throwIfAborted()
        val normalizedContext = collapseSystemMessages(context)
        var params = buildGoogleRequest(model, normalizedContext, options, toolChoice, thinking)
        val payload = options.onPayload?.invoke(params, model)
        if (payload is JsonObject) params = payload
        val (url, headers) = resolveUrlAndHeaders()
        signal?.throwIfAborted()
        val body = PiJson.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
        val response = executeGoogleRequest(transport, url, headers, body, options)
        options.onResponse?.invoke(ProviderResponse(response.status, response.headers), model)
        stream.push(StartEvent(output))
        signal?.throwIfAborted()
        val sse = SseParser(response.body)
        try {
            while (true) {
                val event = sse.next() ?: break
                val data = event.data.trim()
                if (data.isEmpty()) continue
                val chunk = PiJson.parseToJsonElement(data) as? JsonObject ?: continue
                options.onProviderStreamEvent?.invoke(chunk, model)
                state.processChunk(chunk, model)
                signal?.throwIfAborted()
            }
        } finally {
            sse.close()
            response.close()
        }
        state.endCurrentBlock()
        signal?.throwIfAborted()
        if (output.stopReason == StopReason.Pending) {
            throw GoogleStreamException("$endLabel stream ended without a finish reason")
        }
        if (output.stopReason == StopReason.Aborted || output.stopReason == StopReason.Error) {
            throw GoogleStreamException(
                output.rawStopReason?.let { "Provider stopped with: $it" } ?: "An unknown error occurred",
            )
        }
        stream.push(DoneEvent(output.stopReason, output))
        stream.end()
    } catch (error: Throwable) {
        val aborted = error is RequestAbortedException || signal?.isAborted == true ||
            output.stopReason == StopReason.Aborted
        output.stopReason = if (aborted) StopReason.Aborted else StopReason.Error
        output.errorMessage = if (aborted) "Request was aborted" else formatGoogleError(error)
        stream.push(ErrorEvent(output.stopReason, output))
        stream.end()
        if (error is CancellationException && !aborted) throw error
    }
}

private class GoogleStreamState(
    val output: AssistantMessage,
    val stream: AssistantMessageEventStream,
) {
    private var currentBlock: dev.pi.ai.Content? = null

    private fun blockIndex(): Int = output.content.size - 1

    fun endCurrentBlock() {
        val block = currentBlock ?: return
        when (block) {
            is TextContent -> stream.push(TextEndEvent(output.content.size - 1, block.text, output))
            is ThinkingContent -> stream.push(ThinkingEndEvent(output.content.size - 1, block.thinking, output))
            else -> Unit
        }
        currentBlock = null
    }

    fun processChunk(chunk: JsonObject, model: Model) {
        val responseId = chunk.str("responseId")
        if (output.responseId.isNullOrEmpty() && !responseId.isNullOrEmpty()) output.responseId = responseId
        val candidate = chunk.arr("candidates")?.firstOrNull() as? JsonObject
        val parts = candidate?.obj("content")?.arr("parts")
        if (parts != null) {
            for (element in parts) {
                val part = element as? JsonObject ?: continue
                handlePart(part)
            }
        }
        val finishReason = candidate?.str("finishReason")
        if (finishReason != null) {
            output.rawStopReason = finishReason
            output.stopReason = mapGoogleStopReason(finishReason)
            if (output.content.any { it is ToolCall } && output.stopReason == StopReason.Stop) {
                output.stopReason = StopReason.ToolUse
            }
        }
        val usageMetadata = chunk.obj("usageMetadata")
        if (usageMetadata != null) {
            output.usage = parseGoogleUsage(usageMetadata)
            calculateCost(model, output.usage)
        }
    }

    private fun handlePart(part: JsonObject) {
        val textElement = part["text"] as? JsonPrimitive
        if (textElement != null && textElement.isString) {
            val text = textElement.content
            val isThinking = isGoogleThinkingPart(part)
            val block = currentBlock
            if (block == null ||
                (isThinking && block !is ThinkingContent) ||
                (!isThinking && block !is TextContent)
            ) {
                endCurrentBlock()
                if (isThinking) {
                    val created = ThinkingContent(thinking = "")
                    output.content.add(created)
                    currentBlock = created
                    stream.push(ThinkingStartEvent(blockIndex(), output))
                } else {
                    val created = TextContent(text = "")
                    output.content.add(created)
                    currentBlock = created
                    stream.push(TextStartEvent(blockIndex(), output))
                }
            }
            val active = currentBlock
            if (active is ThinkingContent) {
                active.thinking += text
                active.thinkingSignature = retainGoogleThoughtSignature(active.thinkingSignature, part.str("thoughtSignature"))
                stream.push(ThinkingDeltaEvent(blockIndex(), text, output))
            } else if (active is TextContent) {
                active.text += text
                active.textSignature = retainGoogleThoughtSignature(active.textSignature, part.str("thoughtSignature"))
                stream.push(TextDeltaEvent(blockIndex(), text, output))
            }
        }

        val functionCall = part.obj("functionCall")
        if (functionCall != null) {
            if (currentBlock != null) endCurrentBlock()
            val name = functionCall.str("name") ?: ""
            val providedId = functionCall.str("id")
            val needsNewId = providedId.isNullOrEmpty() ||
                output.content.any { it is ToolCall && it.id == providedId }
            val toolCallId = if (needsNewId) "${name}_${nowMillis()}_${++toolCallCounter}" else providedId
            val arguments = functionCall["args"] as? JsonObject ?: JsonObject(emptyMap())
            val signature = part.str("thoughtSignature")?.takeIf { it.isNotEmpty() }
            val toolCall = ToolCall(id = toolCallId, name = name, arguments = arguments, thoughtSignature = signature)
            output.content.add(toolCall)
            stream.push(ToolCallStartEvent(blockIndex(), output))
            stream.push(ToolCallDeltaEvent(blockIndex(), jsJsonStringify(arguments), output))
            stream.push(ToolCallEndEvent(blockIndex(), toolCall, output))
        }
    }
}

private fun parseGoogleUsage(metadata: JsonObject): Usage {
    fun number(key: String): Long {
        val primitive = metadata[key] as? JsonPrimitive ?: return 0L
        if (primitive.isString) return 0L
        return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong() ?: 0L
    }

    val prompt = number("promptTokenCount")
    val cached = number("cachedContentTokenCount")
    val candidates = number("candidatesTokenCount")
    val thoughts = number("thoughtsTokenCount")
    val total = number("totalTokenCount")
    return Usage(
        input = prompt - cached,
        output = candidates + thoughts,
        cacheRead = cached,
        cacheWrite = 0L,
        reasoning = thoughts,
        totalTokens = total,
        cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
    )
}

private fun isRetryableResponse(response: HttpResponse): Boolean {
    val shouldRetry = response.header("x-should-retry")
    if (shouldRetry == "true") return true
    if (shouldRetry == "false") return false
    val status = response.status
    return status == 408 || status == 409 || status == 429 || status >= 500
}

private suspend fun executeGoogleRequest(
    transport: HttpTransport,
    url: String,
    headers: Map<String, String>,
    body: ByteArray,
    options: StreamOptions,
): HttpResponse {
    val signal = options.signal
    val maxRetries = options.maxRetries ?: 0
    var retriesRemaining = maxRetries
    while (true) {
        signal?.throwIfAborted()
        val request = HttpRequest(
            method = "POST",
            url = url,
            headers = headers,
            body = body,
            timeoutMs = options.timeoutMs,
            signal = signal,
        )
        val response = try {
            transport.execute(request)
        } catch (error: HttpTransportException) {
            if (signal?.isAborted == true) throw RequestAbortedException("Request was aborted")
            if (retriesRemaining <= 0) throw error
            val retryIndex = maxRetries - retriesRemaining
            retriesRemaining -= 1
            Retry.sleep(retryDelayMs(null, retryIndex, options.maxRetryDelayMs, error.message ?: ""), signal)
            continue
        }
        if (response.isSuccessful()) return response
        if (retriesRemaining > 0 && isRetryableResponse(response)) {
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
            val delayMs = retryDelayMs(response, retryIndex, options.maxRetryDelayMs, providerMessage)
            retriesRemaining -= 1
            Retry.sleep(delayMs, signal)
            continue
        }
        throw providerHttpError(response)
    }
}

private fun retryDelayMs(
    response: HttpResponse?,
    retryIndex: Int,
    maxRetryDelayMs: Long?,
    providerErrorMessage: String,
): Long {
    if (response != null) {
        val parsedMs = response.header("retry-after-ms")?.toDoubleOrNull()
        if (parsedMs != null) return validateServerRetryDelayMs(parsedMs.toLong(), maxRetryDelayMs, providerErrorMessage)
        val parsedSeconds = response.header("retry-after")?.toDoubleOrNull()
        if (parsedSeconds != null) {
            return validateServerRetryDelayMs((parsedSeconds * 1000).toLong(), maxRetryDelayMs, providerErrorMessage)
        }
    }
    val exponentialDelay = minOf(0.5 * 2.0.pow(retryIndex), 8.0) * 1000.0
    return (exponentialDelay * (1 - Random.nextDouble() * 0.25)).toLong()
}

private fun validateServerRetryDelayMs(delayMs: Long, maxRetryDelayMs: Long?, providerErrorMessage: String): Long {
    val maxDelayMs = maxRetryDelayMs ?: DEFAULT_MAX_RETRY_DELAY_MS
    if (maxDelayMs > 0 && delayMs > maxDelayMs) {
        throw GoogleStreamException(
            "Server requested ${ceil(delayMs / 1000.0).toLong()}s retry delay " +
                "(max: ${ceil(maxDelayMs / 1000.0).toLong()}s). $providerErrorMessage",
        )
    }
    return delayMs
}

private fun providerHttpError(response: HttpResponse): GoogleProviderHttpException {
    val body = try {
        response.body.readUtf8()
    } catch (_: Throwable) {
        ""
    } finally {
        response.close()
    }
    return GoogleProviderHttpException(response.status, response.headers, body)
}

private fun formatGoogleError(error: Throwable): String = when (error) {
    is GoogleProviderHttpException -> ProviderErrors.format(ProviderErrors.normalize(error.status, error.headers, error.body))
    else -> error.message ?: error.toString()
}

private fun makeStrictJsonSchema(parameters: JsonObject): JsonObject {
    val cloned = Json.parseToJsonElement(parameters.toString()) as? JsonObject
        ?: throw UnsupportedStrictJsonSchemaException("root schema must have type object")
    val strict = makeJsonSchemaNodeStrict(cloned)
    val type = (strict["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (type != "object") throw UnsupportedStrictJsonSchemaException("root schema must have type object")
    return strict
}

private val UNSUPPORTED_STRICT_SCHEMA_KEYS = listOf(
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

private fun isJsonSchemaObject(value: JsonElement?): Boolean = value is JsonObject

private fun schemaTypes(schema: JsonObject): List<String> = when (val type = schema["type"]) {
    is JsonPrimitive -> if (type.isString) listOf(type.content) else emptyList()
    is JsonArray -> type.filterIsInstance<JsonPrimitive>().filter { it.isString }.map { it.content }
    else -> emptyList()
}

private fun isStructuredSchema(schema: JsonObject): Boolean {
    val types = schemaTypes(schema)
    return types.contains("object") ||
        types.contains("array") ||
        schema.containsKey("properties") ||
        schema.containsKey("items")
}

private fun schemaAllowsNull(schema: JsonElement): Boolean {
    if (schema !is JsonObject) return false
    val types = schemaTypes(schema)
    if (types.contains("null")) return true
    if (schema["const"] is JsonNull) return true
    if ((schema["enum"] as? JsonArray)?.any { it is JsonNull } == true) return true
    val anyOf = schema["anyOf"] as? JsonArray
    return anyOf?.any { schemaAllowsNull(it) } == true
}

private fun makeJsonSchemaNodeStrict(schema: JsonObject): JsonObject {
    for (key in UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (schema.containsKey(key)) throw UnsupportedStrictJsonSchemaException("$key schemas are unsupported")
    }
    val result = LinkedHashMap<String, JsonElement>(schema)

    val anyOf = schema["anyOf"]
    if (anyOf != null) {
        if (anyOf !is JsonArray || anyOf.isEmpty()) {
            throw UnsupportedStrictJsonSchemaException("anyOf must contain at least one schema")
        }
        val variants = anyOf.map { variant ->
            if (variant is JsonObject && isStructuredSchema(variant)) {
                throw UnsupportedStrictJsonSchemaException("object and array unions are unsupported")
            }
            val variantObject = variant as? JsonObject
                ?: throw UnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
            makeJsonSchemaNodeStrict(variantObject)
        }
        result["anyOf"] = JsonArray(variants)
    }

    val items = schema["items"]
    if (items != null) {
        if (items is JsonArray) throw UnsupportedStrictJsonSchemaException("tuple schemas are unsupported")
        if (items !is JsonObject) throw UnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
        result["items"] = makeJsonSchemaNodeStrict(items)
    }

    val isObjectSchema = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object"
    if (schema.containsKey("properties") && !isObjectSchema) {
        throw UnsupportedStrictJsonSchemaException("properties require type object")
    }
    if (!isObjectSchema) return JsonObject(result)

    val additionalProperties = schema["additionalProperties"]
    if (additionalProperties != null && additionalProperties !is JsonPrimitive ||
        (additionalProperties is JsonPrimitive && (additionalProperties.isString || additionalProperties.content != "false"))
    ) {
        throw UnsupportedStrictJsonSchemaException("schema-valued or true additionalProperties is unsupported")
    }
    val propertyMap: JsonObject = when (val rawProperties = schema["properties"]) {
        null -> JsonObject(emptyMap())
        is JsonObject -> rawProperties
        else -> throw UnsupportedStrictJsonSchemaException("object properties must be a schema map")
    }
    val requiredArray: JsonArray? = when (val rawRequired = schema["required"]) {
        null -> null
        is JsonArray -> {
            if (rawRequired.any { !(it is JsonPrimitive && it.isString) }) {
                throw UnsupportedStrictJsonSchemaException("object required must be a string array")
            }
            rawRequired
        }

        else -> throw UnsupportedStrictJsonSchemaException("object required must be a string array")
    }
    val propertyNames = propertyMap.keys.toList()
    val requiredNames = requiredArray
        ?.filterIsInstance<JsonPrimitive>()
        ?.filter { it.isString }
        ?.map { it.content }
        ?.toSet()
        ?: emptySet()
    if (requiredNames.any { it !in propertyNames }) {
        throw UnsupportedStrictJsonSchemaException("required contains an unknown property")
    }
    val strictProperties = LinkedHashMap<String, JsonElement>()
    for ((key, property) in propertyMap) {
        val propertyObject = property as? JsonObject
            ?: throw UnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
        val strictProperty = makeJsonSchemaNodeStrict(propertyObject)
        strictProperties[key] = if (!requiredNames.contains(key) && !schemaAllowsNull(propertyObject)) {
            buildJsonObject {
                put(
                    "anyOf",
                    buildJsonArray {
                        add(strictProperty)
                        add(buildJsonObject { put("type", JsonPrimitive("null")) })
                    },
                )
            }
        } else {
            strictProperty
        }
    }
    result["properties"] = JsonObject(strictProperties)
    result["required"] = JsonArray(propertyNames.map(::JsonPrimitive))
    result["additionalProperties"] = JsonPrimitive(false)
    return JsonObject(result)
}

internal fun resolveGoogleJsonSchemaStrictSampling(tool: Tool, supportsStrictMode: Boolean): Boolean? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (config.str("type") != "json_schema") return null
    if (supportsStrictMode) {
        return try {
            makeStrictJsonSchema(tool.parameters)
            true
        } catch (error: UnsupportedStrictJsonSchemaException) {
            if (config.str("strict") != "require") return null
            throw GoogleStreamException(
                "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but ${error.message}.",
            )
        }
    }
    if (config.str("strict") == "require") {
        throw GoogleStreamException(
            "Tool \"${tool.name}\" requires JSON-schema constrained sampling, but strict tools are unsupported.",
        )
    }
    return null
}
