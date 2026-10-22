package dev.pi.ai.api

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.BlockContent
import dev.pi.ai.ImageContent
import dev.pi.ai.Model
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.ProviderStreamEventHook
import dev.pi.ai.StopReason
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
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.calculateCost
import dev.pi.ai.UsageCost
import dev.pi.ai.utils.PartialJson
import dev.pi.ai.utils.getSystemMessageText
import dev.pi.ai.utils.renderSystemMessageUpdate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class ResponsesSessionAffinityFormat(val wireName: String) {
    Openai("openai"),
    OpenaiNosession("openai-nosession"),
    Openrouter("openrouter"),
}

internal data class ResolvedOpenAIResponsesCompat(
    val supportsDeveloperRole: Boolean = true,
    val supportsMidConvoSystemMessages: Boolean = false,
    val sessionAffinityFormat: ResponsesSessionAffinityFormat = ResponsesSessionAffinityFormat.Openai,
    val supportsLongCacheRetention: Boolean = true,
    val supportsStrictMode: Boolean = false,
    val supportsOpenAIGrammarTools: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val supportsExplicitPromptCacheMode: Boolean = false,
    val supportsMaxOutputTokens: Boolean = true,
    val supportsMidConvoEffort: Boolean = false,
)

internal fun resolveResponsesCompat(model: Model): ResolvedOpenAIResponsesCompat {
    val overrides = model.compat
    val defaults = ResolvedOpenAIResponsesCompat()
    return ResolvedOpenAIResponsesCompat(
        supportsDeveloperRole = overrides?.let { booleanField(it, "supportsDeveloperRole") } ?: defaults.supportsDeveloperRole,
        supportsMidConvoSystemMessages = overrides?.let { booleanField(it, "supportsMidConvoSystemMessages") }
            ?: defaults.supportsMidConvoSystemMessages,
        sessionAffinityFormat = overrides?.let { sessionAffinityOf(stringField(it, "sessionAffinityFormat")) }
            ?: detectSessionAffinityFormat(model),
        supportsLongCacheRetention = overrides?.let { booleanField(it, "supportsLongCacheRetention") }
            ?: defaults.supportsLongCacheRetention,
        supportsStrictMode = overrides?.let { booleanField(it, "supportsStrictMode") } ?: defaults.supportsStrictMode,
        supportsOpenAIGrammarTools = overrides?.let { booleanField(it, "supportsOpenAIGrammarTools") }
            ?: defaults.supportsOpenAIGrammarTools,
        supportsAdditionalTools = overrides?.let { booleanField(it, "supportsAdditionalTools") }
            ?: defaults.supportsAdditionalTools,
        supportsToolSearch = overrides?.let { booleanField(it, "supportsToolSearch") } ?: defaults.supportsToolSearch,
        supportsExplicitPromptCacheMode = overrides?.let { booleanField(it, "supportsExplicitPromptCacheMode") }
            ?: defaults.supportsExplicitPromptCacheMode,
        supportsMaxOutputTokens = overrides?.let { booleanField(it, "supportsMaxOutputTokens") }
            ?: defaults.supportsMaxOutputTokens,
        supportsMidConvoEffort = overrides?.let { booleanField(it, "supportsMidConvoEffort") }
            ?: defaults.supportsMidConvoEffort,
    )
}

private fun sessionAffinityOf(value: String?): ResponsesSessionAffinityFormat? = when (value) {
    "openai" -> ResponsesSessionAffinityFormat.Openai
    "openai-nosession" -> ResponsesSessionAffinityFormat.OpenaiNosession
    "openrouter" -> ResponsesSessionAffinityFormat.Openrouter
    else -> null
}

private fun detectSessionAffinityFormat(model: Model): ResponsesSessionAffinityFormat =
    if (model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")) {
        ResponsesSessionAffinityFormat.Openrouter
    } else {
        ResponsesSessionAffinityFormat.Openai
    }

internal enum class ResponsesToolSupport { None, Additions, Changes }

internal fun responsesToolSupport(
    supportsMidConvoSystemMessages: Boolean,
    supportsAdditionalTools: Boolean,
    supportsToolSearch: Boolean,
): ResponsesToolSupport = when {
    supportsMidConvoSystemMessages && supportsAdditionalTools -> ResponsesToolSupport.Changes
    supportsToolSearch -> ResponsesToolSupport.Additions
    else -> ResponsesToolSupport.None
}

internal class ConvertResponsesToolsOptions(
    val strict: Boolean? = null,
    val supportsStrictMode: Boolean? = null,
    val supportsOpenAIGrammarTools: Boolean? = null,
    val toolSearchResult: Boolean = false,
)

internal class ConvertResponsesMessagesOptions(
    val includeSystemPrompt: Boolean = true,
    val grammarToolInputProperties: Map<String, String> = emptyMap(),
    val supportsMidConvoSystemMessages: Boolean = false,
    val supportsAdditionalTools: Boolean = false,
    val supportsToolSearch: Boolean = false,
    val toolOptions: ConvertResponsesToolsOptions? = null,
    val configurationUpdateEffort: String? = null,
)

private const val INCREMENTAL_TOOLS_NOTICE =
    "This is an incremental tools update. Previously declared tools remain available unless explicitly marked unavailable. " +
        "A tool declared again here is available again, and its latest definition replaces the earlier one."

private fun renderIncrementalToolsNotice(unavailable: Set<String>): String =
    if (unavailable.isEmpty()) INCREMENTAL_TOOLS_NOTICE else "$INCREMENTAL_TOOLS_NOTICE\nStill unavailable: ${unavailable.joinToString(", ")}."

private fun renderToolRemovalNotice(names: List<String>): String =
    "The following tools are no longer available. Do not call them:\n" + names.joinToString("\n") { "- $it" }

internal fun convertResponsesMessages(
    model: Model,
    context: TranscriptContext,
    allowedToolCallProviders: Set<String>,
    options: ConvertResponsesMessagesOptions = ConvertResponsesMessagesOptions(),
): JsonArray {
    val normalizedContext = resolveTranscript(context, options.supportsMidConvoSystemMessages)
    val messages = mutableListOf<JsonObject>()

    fun normalizeIdPart(part: String): String {
        val sanitized = part.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val truncated = if (sanitized.length > 64) sanitized.substring(0, 64) else sanitized
        return truncated.trimEnd('_')
    }

    fun buildForeignResponsesItemId(itemId: String): String {
        val normalized = "fc_${shortHash(itemId)}"
        return if (normalized.length > 64) normalized.substring(0, 64) else normalized
    }

    val toolCallSources = LinkedHashMap<String, Pair<String, String>>()
    for (message in normalizedContext.messages) {
        if (message !is AssistantMessage) continue
        for (block in message.content) {
            if (block is ToolCall) toolCallSources[block.id] = message.provider to message.api
        }
    }

    val normalizeToolCallId: (String) -> String = { id ->
        val source = toolCallSources[id]
        normalizeResponsesToolCallId(
            model = model,
            id = id,
            allowedToolCallProviders = allowedToolCallProviders,
            sourceProvider = source?.first,
            sourceApi = source?.second,
            normalizeIdPart = ::normalizeIdPart,
            buildForeignItemId = ::buildForeignResponsesItemId,
        )
    }

    val transformedMessages = transformMessages(normalizedContext.messages, model, normalizeToolCallId)
    val toolSupport = responsesToolSupport(
        options.supportsMidConvoSystemMessages,
        options.supportsAdditionalTools,
        options.supportsToolSearch,
    )
    val transcriptTools = resolveTranscriptTools(
        normalizedContext.messages,
        when (toolSupport) {
            ResponsesToolSupport.None -> TranscriptToolSupport.None
            ResponsesToolSupport.Additions -> TranscriptToolSupport.Additions
            ResponsesToolSupport.Changes -> TranscriptToolSupport.Changes
        },
    )
    val rawCompat = model.compat
    val supportsDeveloperRole = rawCompat?.let { booleanField(it, "supportsDeveloperRole") } ?: true
    val instructionRole = if (model.reasoning && supportsDeveloperRole) "developer" else "system"
    var declaredAdditionalTools = false
    val unavailableTools = LinkedHashSet<String>()

    fun pushConfigurationUpdate(effort: String) {
        messages.add(
            buildJsonObject {
                put("type", "configuration_update")
                put(
                    "reasoning",
                    buildJsonObject {
                        put("effort", effort)
                    },
                )
            },
        )
    }

    fun appendSystemToolAdditions(message: SystemMessage, seed: String) {
        val tools = message.toolsAdded.orEmpty()
        for (tool in tools) unavailableTools.remove(tool.name)
        if (tools.isEmpty()) return
        if (toolSupport == ResponsesToolSupport.Changes) {
            if (declaredAdditionalTools) {
                messages.add(
                    buildJsonObject {
                        put("role", instructionRole)
                        put("content", renderIncrementalToolsNotice(unavailableTools))
                    },
                )
            }
            declaredAdditionalTools = true
            messages.add(
                buildJsonObject {
                    put("type", "additional_tools")
                    put("role", "developer")
                    put("tools", convertResponsesTools(tools, options.toolOptions ?: ConvertResponsesToolsOptions()))
                },
            )
            return
        }
        if (toolSupport != ResponsesToolSupport.Additions) return
        val names = tools.map { it.name }
        val callId = "pi_tool_load_${shortHash("$seed:${names.joinToString(",")}")}"
        messages.add(
            buildJsonObject {
                put("type", "tool_search_call")
                put("call_id", callId)
                put("execution", "client")
                put("status", "completed")
                put(
                    "arguments",
                    buildJsonObject {
                        put("query", names.joinToString(" "))
                        put("limit", names.size)
                    },
                )
            },
        )
        messages.add(
            buildJsonObject {
                put("type", "tool_search_output")
                put("call_id", callId)
                put("execution", "client")
                put("status", "completed")
                put(
                    "tools",
                    convertResponsesTools(
                        tools,
                        ConvertResponsesToolsOptions(
                            strict = options.toolOptions?.strict,
                            supportsStrictMode = options.toolOptions?.supportsStrictMode,
                            supportsOpenAIGrammarTools = options.toolOptions?.supportsOpenAIGrammarTools,
                            toolSearchResult = true,
                        ),
                    ),
                )
            },
        )
    }

    val includeInitialSystemMessage = options.includeSystemPrompt

    var msgIndex = 0
    var sourceIndex = 0
    var systemIndex = 0
    for (originalMessage in transformedMessages) {
        val isLeadingSystemMessage = sourceIndex++ == 0 && originalMessage is SystemMessage
        when (originalMessage) {
            is SystemMessage -> {
                val anchorsToolChanges = systemIndex++ >= transcriptTools.foldedSystemMessages
                if (anchorsToolChanges) appendSystemToolAdditions(originalMessage, "system:$msgIndex")
                if (!isLeadingSystemMessage || includeInitialSystemMessage) {
                    val parts = mutableListOf(
                        if (isLeadingSystemMessage) {
                            getSystemMessageText(originalMessage)
                        } else {
                            renderSystemMessageUpdate(originalMessage)
                        },
                    )
                    if (anchorsToolChanges) {
                        val added = originalMessage.toolsAdded.orEmpty().map { it.name }.toSet()
                        val removed = originalMessage.toolsRemoved.orEmpty().map { it.name }.filter { it !in added }
                        if (removed.isNotEmpty()) parts.add(renderToolRemovalNotice(removed))
                        unavailableTools.addAll(removed)
                    }
                    val text = parts.filter { it.isNotEmpty() }.joinToString("\n\n")
                    if (text.isNotEmpty()) {
                        messages.add(
                            buildJsonObject {
                                put("role", instructionRole)
                                put("content", sanitizeSurrogates(text))
                            },
                        )
                    }
                }
            }

            is UserMessage -> {
                val content = originalMessage.content
                if (content is PlainTextContent) {
                    messages.add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "content",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("type", "input_text")
                                            put("text", sanitizeSurrogates(content.text))
                                        },
                                    ),
                                ),
                            )
                        },
                    )
                } else if (content is BlockContent) {
                    val items = mutableListOf<JsonObject>()
                    for (block in content.blocks) {
                        when (block) {
                            is TextContent -> items.add(
                                buildJsonObject {
                                    put("type", "input_text")
                                    put("text", sanitizeSurrogates(block.text))
                                },
                            )

                            is ImageContent -> items.add(inputImageItem(block))
                            else -> Unit
                        }
                    }
                    if (items.isNotEmpty()) {
                        messages.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", JsonArray(items))
                            },
                        )
                    }
                }
            }

            is AssistantMessage -> {
                val output = mutableListOf<JsonObject>()
                val isSameProviderAndApi = originalMessage.provider == model.provider && originalMessage.api == model.api
                val isSameModel = isSameProviderAndApi && originalMessage.model == model.id
                val isDifferentModel = isSameProviderAndApi && originalMessage.model != model.id
                var textBlockIndex = 0
                for (block in originalMessage.content) {
                    when (block) {
                        is ThinkingContent -> {
                            val signature = block.thinkingSignature
                            if (!signature.isNullOrEmpty()) {
                                val parsed = PiJson.parseToJsonElement(signature)
                                if (parsed is JsonObject) output.add(parsed)
                            }
                        }

                        is TextContent -> {
                            val parsedSignature = parseTextSignature(block.textSignature)
                            val fallbackMessageId = if (textBlockIndex == 0) {
                                "msg_pi_$msgIndex"
                            } else {
                                "msg_pi_${msgIndex}_$textBlockIndex"
                            }
                            textBlockIndex += 1
                            var messageId = parsedSignature?.first
                            if (messageId == null) {
                                messageId = fallbackMessageId
                            } else if (messageId.length > 64) {
                                messageId = "msg_${shortHash(messageId)}"
                            }
                            output.add(
                                buildJsonObject {
                                    put("type", "message")
                                    put("role", "assistant")
                                    put(
                                        "content",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("type", "output_text")
                                                    put("text", sanitizeSurrogates(block.text))
                                                    put("annotations", JsonArray(emptyList()))
                                                },
                                            ),
                                        ),
                                    )
                                    put("status", "completed")
                                    put("id", messageId)
                                    val phase = parsedSignature?.second
                                    if (phase != null) put("phase", phase)
                                },
                            )
                        }

                        is ToolCall -> {
                            val idParts = block.id.split("|", limit = 2)
                            val callId = idParts[0]
                            val itemIdRaw = idParts.getOrNull(1)
                            val customInputProperty = options.grammarToolInputProperties[block.name]
                            var itemId = itemIdRaw
                            val itemIdPrefix = if (customInputProperty == null) "fc_" else "ctc_"
                            if (isDifferentModel || itemId == null || !itemId.startsWith(itemIdPrefix)) {
                                itemId = null
                            }
                            if (customInputProperty != null) {
                                val input = sanitizeSurrogates(
                                    responsesGrammarToolInput(block.name, block.arguments, customInputProperty),
                                )
                                output.add(
                                    buildJsonObject {
                                        put("type", "custom_tool_call")
                                        itemId?.let { put("id", it) }
                                        put("call_id", callId)
                                        put("name", block.name)
                                        put("input", input)
                                        if (isSameModel && block.namespace != null) put("namespace", block.namespace)
                                    },
                                )
                            } else {
                                output.add(
                                    buildJsonObject {
                                        put("type", "function_call")
                                        itemId?.let { put("id", it) }
                                        put("call_id", callId)
                                        put("name", block.name)
                                        put("arguments", PiJson.encodeToString(JsonObject.serializer(), block.arguments))
                                        if (isSameModel && block.namespace != null) put("namespace", block.namespace)
                                    },
                                )
                            }
                        }

                        else -> Unit
                    }
                }
                if (output.isEmpty()) {
                    if (!isLeadingSystemMessage) msgIndex += 1
                    continue
                }
                if (options.configurationUpdateEffort != null && isSameModel && originalMessage.providerThinkingLevel != null) {
                    pushConfigurationUpdate(originalMessage.providerThinkingLevel!!)
                }
                messages.addAll(output)
            }

            is ToolResultMessage -> {
                val callId = originalMessage.toolCallId.split("|", limit = 2)[0]
                val output = convertToolResultOutput(model, originalMessage.content)
                val itemType = if (options.grammarToolInputProperties.containsKey(originalMessage.toolName)) {
                    "custom_tool_call_output"
                } else {
                    "function_call_output"
                }
                messages.add(
                    buildJsonObject {
                        put("type", itemType)
                        put("call_id", callId)
                        put("output", output)
                    },
                )
            }
        }
        if (!isLeadingSystemMessage) msgIndex += 1
    }
    if (options.configurationUpdateEffort != null) pushConfigurationUpdate(options.configurationUpdateEffort)
    return JsonArray(messages)
}

private fun inputImageItem(image: ImageContent): JsonObject = buildJsonObject {
    put("type", "input_image")
    put("detail", "auto")
    put("image_url", "data:${image.mimeType};base64,${image.data}")
}

private fun normalizeResponsesToolCallId(
    model: Model,
    id: String,
    allowedToolCallProviders: Set<String>,
    sourceProvider: String?,
    sourceApi: String?,
    normalizeIdPart: (String) -> String,
    buildForeignItemId: (String) -> String,
): String {
    if (!allowedToolCallProviders.contains(model.provider)) return normalizeIdPart(id)
    if (!id.contains("|")) return normalizeIdPart(id)
    val separatorIndex = id.indexOf('|')
    val callId = id.substring(0, separatorIndex)
    val itemId = id.substring(separatorIndex + 1)
    val normalizedCallId = normalizeIdPart(callId)
    val isForeignToolCall = sourceProvider == null || sourceProvider != model.provider || sourceApi != model.api
    var normalizedItemId = if (isForeignToolCall) buildForeignItemId(itemId) else normalizeIdPart(itemId)
    if (!normalizedItemId.startsWith("fc_")) {
        normalizedItemId = normalizeIdPart("fc_$normalizedItemId")
    }
    return "$normalizedCallId|$normalizedItemId"
}

private fun convertToolResultOutput(model: Model, content: List<dev.pi.ai.Content>): JsonElement {
    val textResult = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
    val images = content.filterIsInstance<ImageContent>()
    val hasText = textResult.trim().isNotEmpty()
    if (images.isEmpty() || !model.input.contains(ModelInputModality.Image)) {
        val text = when {
            hasText -> textResult
            images.isNotEmpty() -> "(see attached image)"
            else -> "(no tool output)"
        }
        return JsonPrimitive(sanitizeSurrogates(text))
    }
    val items = mutableListOf<JsonElement>()
    if (hasText) {
        items.add(
            buildJsonObject {
                put("type", "input_text")
                put("text", sanitizeSurrogates(textResult))
            },
        )
    }
    for (image in images) {
        items.add(inputImageItem(image))
    }
    return JsonArray(items)
}

internal fun convertResponsesTools(tools: List<Tool>, options: ConvertResponsesToolsOptions = ConvertResponsesToolsOptions()): JsonArray {
    val defaultStrict = options.strict ?: false
    val supportsStrictMode = options.supportsStrictMode ?: true
    val supportsOpenAIGrammarTools = options.supportsOpenAIGrammarTools ?: false
    val converted = tools.map { tool ->
        val grammar = resolveResponsesGrammarSampling(tool, supportsOpenAIGrammarTools)
        if (grammar != null) {
            return@map buildJsonObject {
                put("type", "custom")
                put("name", tool.name)
                put("description", tool.description)
                put(
                    "format",
                    buildJsonObject {
                        put("type", "grammar")
                        put("syntax", grammar.format)
                        put("definition", grammar.definition)
                    },
                )
                if (options.toolSearchResult) put("defer_loading", true)
            }
        }
        val constrainedStrict = resolveResponsesJsonSchemaStrictSampling(tool, supportsStrictMode)
        val strict = constrainedStrict ?: defaultStrict
        buildJsonObject {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", responsesJsonSchemaToolParameters(tool, strict))
            if (options.toolSearchResult) put("defer_loading", true)
            if (supportsStrictMode) put("strict", strict)
        }
    }
    return JsonArray(converted)
}

private fun encodeTextSignatureV1(id: String, phase: String?): String = buildJsonObject {
    put("v", 1)
    put("id", id)
    if (phase != null) put("phase", phase)
}.toString()

private fun parseTextSignature(signature: String?): Pair<String, String?>? {
    if (signature.isNullOrEmpty()) return null
    if (signature.startsWith("{")) {
        val parsed = runCatching { PiJson.parseToJsonElement(signature) as? JsonObject }.getOrNull()
        if (parsed != null) {
            val version = parsed.longFieldValue("v")
            val id = parsed.stringFieldValue("id")
            if (version == 1L && id != null) {
                val phase = parsed.stringFieldValue("phase")
                val validPhase = when (phase) {
                    "commentary", "final_answer" -> phase
                    else -> null
                }
                return id to validPhase
            }
        }
    }
    return signature to null
}

internal class ResponsesStreamOptions(
    val onProviderStreamEvent: ProviderStreamEventHook? = null,
    val serviceTier: String? = null,
    val grammarToolInputProperties: Map<String, String> = emptyMap(),
    val applyServiceTierPricing: ((usage: Usage, serviceTier: String?) -> Unit)? = null,
)

internal class ResponsesStreamException(message: String) : RuntimeException(message)

private sealed interface OutputSlot {
    val contentIndex: Int
}

private class ThinkingSlot(val block: ThinkingContent, override val contentIndex: Int) : OutputSlot

private class TextSlot(val block: TextContent, override val contentIndex: Int) : OutputSlot

private class CustomInputState(
    val property: String,
    val buffer: ResponsesGrammarBuffer,
)

private class ToolScratch(
    val block: ToolCall,
    var partialJson: String?,
    var customInput: CustomInputState?,
)

private class ToolSlot(val scratch: ToolScratch, override val contentIndex: Int) : OutputSlot

internal class ResponsesStreamProcessor(
    private val output: AssistantMessage,
    private val stream: AssistantMessageEventStream,
    private val model: Model,
    private val options: ResponsesStreamOptions = ResponsesStreamOptions(),
) {
    private val outputSlots = LinkedHashMap<Int?, OutputSlot>()
    private val reasoningBlocksById = LinkedHashMap<String, ThinkingContent>()
    private val unfinishedToolCalls = mutableListOf<ToolScratch>()
    private var sawTerminalResponseEvent = false

    fun handle(event: JsonObject) {
        options.onProviderStreamEvent?.invoke(event, model)
        when (event.stringFieldValue("type")) {
            "response.created" -> {
                val response = event.objectFieldValue("response")
                response?.stringFieldValue("id")?.let { output.responseId = it }
            }

            "response.output_item.added" -> {
                createSlot(event.intFieldValueOrNull("output_index"), event.objectFieldValue("item"))
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val slot = thinkingSlot(event.intFieldValueOrNull("output_index")) ?: return
                val delta = event.stringFieldValue("delta") ?: return
                slot.block.thinking += delta
                stream.push(ThinkingDeltaEvent(slot.contentIndex, delta, output))
            }

            "response.reasoning_summary_part.done" -> {
                val slot = thinkingSlot(event.intFieldValueOrNull("output_index")) ?: return
                slot.block.thinking += "\n\n"
                stream.push(ThinkingDeltaEvent(slot.contentIndex, "\n\n", output))
            }

            "response.output_text.delta", "response.refusal.delta" -> {
                val slot = textSlot(event.intFieldValueOrNull("output_index")) ?: return
                val delta = event.stringFieldValue("delta") ?: return
                slot.block.text += delta
                stream.push(TextDeltaEvent(slot.contentIndex, delta, output))
            }

            "response.function_call_arguments.delta" -> {
                val slot = toolSlot(event.intFieldValueOrNull("output_index")) ?: return
                val scratch = slot.scratch
                val partialJson = scratch.partialJson ?: return
                val delta = event.stringFieldValue("delta") ?: return
                scratch.partialJson = partialJson + delta
                scratch.block.arguments = parseStreamingArguments(scratch.partialJson!!)
                stream.push(ToolCallDeltaEvent(slot.contentIndex, delta, output))
            }

            "response.function_call_arguments.done" -> {
                val slot = toolSlot(event.intFieldValueOrNull("output_index")) ?: return
                val scratch = slot.scratch
                val previousPartialJson = scratch.partialJson ?: return
                val arguments = event.stringFieldValue("arguments") ?: return
                scratch.partialJson = arguments
                scratch.block.arguments = parseStreamingArguments(arguments)
                if (arguments.startsWith(previousPartialJson)) {
                    val delta = arguments.substring(previousPartialJson.length)
                    if (delta.isNotEmpty()) {
                        stream.push(ToolCallDeltaEvent(slot.contentIndex, delta, output))
                    }
                }
            }

            "response.custom_tool_call_input.delta" -> {
                val slot = toolSlot(event.intFieldValueOrNull("output_index")) ?: return
                val scratch = slot.scratch
                if (scratch.customInput == null) return
                val delta = event.stringFieldValue("delta") ?: return
                val nextInput = getCustomToolCallInput(scratch) + delta
                appendCustomToolCallInput(scratch, nextInput, false)?.let {
                    stream.push(ToolCallDeltaEvent(slot.contentIndex, it, output))
                }
            }

            "response.custom_tool_call_input.done" -> {
                val slot = toolSlot(event.intFieldValueOrNull("output_index")) ?: return
                val scratch = slot.scratch
                if (scratch.customInput == null) return
                val input = event.stringFieldValue("input") ?: getCustomToolCallInput(scratch)
                appendCustomToolCallInput(scratch, input, true)?.let {
                    stream.push(ToolCallDeltaEvent(slot.contentIndex, it, output))
                }
            }

            "response.output_item.done" -> {
                val item = event.objectFieldValue("item") ?: return
                applyMessagePhaseStopReason(item)
                val outputIndex = event.intFieldValueOrNull("output_index")
                val slot = outputSlots[outputIndex] ?: createSlot(outputIndex, item)
                when (item.stringFieldValue("type")) {
                    "reasoning" -> if (slot is ThinkingSlot) finishReasoning(outputIndex, slot, item)
                    "message" -> if (slot is TextSlot) finishText(outputIndex, slot, item)
                    "function_call" -> if (slot is ToolSlot && slot.scratch.partialJson != null) {
                        finishFunctionCall(outputIndex, slot, item)
                    }

                    "custom_tool_call" -> if (slot is ToolSlot && slot.scratch.customInput != null) {
                        finishCustomToolCall(outputIndex, slot, item)
                    }
                }
            }

            "response.completed", "response.incomplete" -> finalizeResponse(event.objectFieldValue("response"))

            "error" -> {
                val code = event.stringFieldValue("code") ?: "unknown"
                val message = event.stringFieldValue("message") ?: "Unknown error"
                throw ResponsesStreamException("Error Code $code: $message")
            }

            "response.failed" -> {
                sawTerminalResponseEvent = true
                val response = event.objectFieldValue("response")
                response?.stringFieldValue("status")?.let { output.rawStopReason = it }
                val error = response?.objectFieldValue("error")
                val details = response?.objectFieldValue("incomplete_details")
                val message = when {
                    error != null -> {
                        val code = error.stringFieldValue("code") ?: "unknown"
                        val text = error.stringFieldValue("message") ?: "no message"
                        "$code: $text"
                    }

                    details?.stringFieldValue("reason") != null -> "incomplete: ${details.stringFieldValue("reason")}"
                    else -> "Unknown error (no error details in response)"
                }
                throw ResponsesStreamException(message)
            }
        }
    }

    fun finish() {
        if (!sawTerminalResponseEvent) {
            throw ResponsesStreamException("OpenAI Responses stream ended before a terminal response event")
        }
        if (output.stopReason == StopReason.ToolUse) {
            val unfinished = unfinishedToolCalls.firstOrNull()
            if (unfinished != null) {
                throw ResponsesStreamException(
                    "OpenAI Responses stream completed with an unfinished tool call: " +
                        "${unfinished.block.name} (${unfinished.block.id})",
                )
            }
        }
    }

    private fun applyMessagePhaseStopReason(item: JsonObject) {
        if (item.stringFieldValue("type") == "message" && item.stringFieldValue("phase") == "final_answer") {
            output.stopReason = StopReason.Stop
        }
    }

    private fun thinkingSlot(outputIndex: Int?): ThinkingSlot? = outputSlots[outputIndex] as? ThinkingSlot

    private fun textSlot(outputIndex: Int?): TextSlot? = outputSlots[outputIndex] as? TextSlot

    private fun toolSlot(outputIndex: Int?): ToolSlot? = outputSlots[outputIndex] as? ToolSlot

    private fun createSlot(outputIndex: Int?, item: JsonObject?): OutputSlot? {
        if (item == null) return null
        return when (item.stringFieldValue("type")) {
            "reasoning" -> {
                val block = ThinkingContent(thinking = "")
                output.content.add(block)
                val slot = ThinkingSlot(block, output.content.size - 1)
                outputSlots[outputIndex] = slot
                stream.push(ThinkingStartEvent(slot.contentIndex, output))
                slot
            }

            "message" -> {
                applyMessagePhaseStopReason(item)
                val block = TextContent(text = "")
                output.content.add(block)
                val slot = TextSlot(block, output.content.size - 1)
                outputSlots[outputIndex] = slot
                stream.push(TextStartEvent(slot.contentIndex, output))
                slot
            }

            "function_call" -> {
                val callId = item.stringFieldValue("call_id").orEmpty()
                val itemId = item.stringFieldValue("id").orEmpty()
                val block = ToolCall(
                    id = "$callId|$itemId",
                    name = item.stringFieldValue("name").orEmpty(),
                    arguments = JsonObject(emptyMap()),
                    namespace = item.stringFieldValue("namespace"),
                )
                val scratch = ToolScratch(block, item.stringFieldValue("arguments") ?: "", null)
                output.content.add(block)
                val slot = ToolSlot(scratch, output.content.size - 1)
                unfinishedToolCalls.add(scratch)
                outputSlots[outputIndex] = slot
                stream.push(ToolCallStartEvent(slot.contentIndex, output))
                slot
            }

            "custom_tool_call" -> {
                val callId = item.stringFieldValue("call_id").orEmpty()
                val itemId = item.stringFieldValue("id").orEmpty()
                val toolName = item.stringFieldValue("name").orEmpty()
                val inputProperty = options.grammarToolInputProperties[toolName] ?: "input"
                val input = item.stringFieldValue("input") ?: ""
                val block = ToolCall(
                    id = "$callId|$itemId",
                    name = toolName,
                    arguments = buildJsonObject { put(inputProperty, input) },
                    namespace = item.stringFieldValue("namespace"),
                )
                val scratch = ToolScratch(
                    block = block,
                    partialJson = null,
                    customInput = CustomInputState(inputProperty, ResponsesGrammarBuffer(property = inputProperty)),
                )
                output.content.add(block)
                val slot = ToolSlot(scratch, output.content.size - 1)
                unfinishedToolCalls.add(scratch)
                outputSlots[outputIndex] = slot
                stream.push(ToolCallStartEvent(slot.contentIndex, output))
                slot
            }

            else -> null
        }
    }

    private fun finishReasoning(outputIndex: Int?, slot: ThinkingSlot, item: JsonObject) {
        val summaryText = textBlocks(item.arrayFieldValue("summary"))
        val contentText = textBlocks(item.arrayFieldValue("content"))
        slot.block.thinking = when {
            summaryText.isNotEmpty() -> summaryText
            contentText.isNotEmpty() -> contentText
            else -> slot.block.thinking
        }
        slot.block.thinkingSignature = item.toString()
        val itemId = item.stringFieldValue("id")
        if (itemId != null) reasoningBlocksById[itemId] = slot.block
        stream.push(ThinkingEndEvent(slot.contentIndex, slot.block.thinking, output))
        outputSlots.remove(outputIndex)
    }

    private fun finishText(outputIndex: Int?, slot: TextSlot, item: JsonObject) {
        val content = item.arrayFieldValue("content").orEmpty()
        slot.block.text = content.joinToString("") { element ->
            val block = element as? JsonObject ?: return@joinToString ""
            when (block.stringFieldValue("type")) {
                "output_text" -> block.stringFieldValue("text").orEmpty()
                "refusal" -> block.stringFieldValue("refusal").orEmpty()
                else -> ""
            }
        }
        slot.block.textSignature = encodeTextSignatureV1(item.stringFieldValue("id").orEmpty(), item.stringFieldValue("phase"))
        stream.push(TextEndEvent(slot.contentIndex, slot.block.text, output))
        outputSlots.remove(outputIndex)
    }

    private fun finishFunctionCall(outputIndex: Int?, slot: ToolSlot, item: JsonObject) {
        val scratch = slot.scratch
        val parsed = PartialJson.parseToolCallArguments(item.stringFieldValue("arguments") ?: scratch.partialJson)
        scratch.block.arguments = parsed.arguments
        scratch.block.argumentsError = parsed.argumentsError
        item.stringFieldValue("namespace")?.let { scratch.block.namespace = it }
        scratch.partialJson = null
        unfinishedToolCalls.remove(scratch)
        stream.push(ToolCallEndEvent(slot.contentIndex, scratch.block, output))
        outputSlots.remove(outputIndex)
    }

    private fun finishCustomToolCall(outputIndex: Int?, slot: ToolSlot, item: JsonObject) {
        val scratch = slot.scratch
        val input = item.stringFieldValue("input") ?: getCustomToolCallInput(scratch)
        appendCustomToolCallInput(scratch, input, true)?.let {
            stream.push(ToolCallDeltaEvent(slot.contentIndex, it, output))
        }
        item.stringFieldValue("namespace")?.let { scratch.block.namespace = it }
        scratch.customInput = null
        unfinishedToolCalls.remove(scratch)
        stream.push(ToolCallEndEvent(slot.contentIndex, scratch.block, output))
        outputSlots.remove(outputIndex)
    }

    private fun finalizeResponse(response: JsonObject?) {
        sawTerminalResponseEvent = true
        backfillReasoningSignatures(response?.arrayFieldValue("output"))
        response?.stringFieldValue("id")?.let { output.responseId = it }
        val usageObject = response?.objectFieldValue("usage")
        if (usageObject != null) {
            val inputDetails = usageObject.objectFieldValue("input_tokens_details")
            val cachedTokens = inputDetails?.longFieldValue("cached_tokens") ?: 0L
            val cacheWriteTokens = inputDetails?.longFieldValue("cache_write_tokens") ?: 0L
            val inputTokens = usageObject.longFieldValue("input_tokens") ?: 0L
            output.usage = Usage(
                input = maxOf(0L, inputTokens - cachedTokens - cacheWriteTokens),
                output = usageObject.longFieldValue("output_tokens") ?: 0L,
                cacheRead = cachedTokens,
                cacheWrite = cacheWriteTokens,
                reasoning = usageObject.objectFieldValue("output_tokens_details")?.longFieldValue("reasoning_tokens") ?: 0L,
                totalTokens = usageObject.longFieldValue("total_tokens") ?: 0L,
                cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
            )
        }
        calculateCost(model, output.usage)
        options.applyServiceTierPricing?.let { apply ->
            val serviceTier = response?.stringFieldValue("service_tier") ?: options.serviceTier
            apply(output.usage, serviceTier)
        }
        val status = response?.stringFieldValue("status")
        val incompleteDetails = response?.objectFieldValue("incomplete_details")
        val incompleteReason = incompleteDetails?.stringFieldValue("reason")
        output.rawStopReason = if (incompleteReason != null) "$status.$incompleteReason" else status
        val mapped = mapResponsesStopReason(status, incompleteReason)
        output.stopReason = mapped.first
        output.errorMessage = mapped.second
        if (output.content.any { it is ToolCall } && output.stopReason == StopReason.Stop) {
            output.stopReason = StopReason.ToolUse
        }
    }

    private fun backfillReasoningSignatures(responseOutput: JsonArray?) {
        for (element in responseOutput.orEmpty()) {
            val item = element as? JsonObject ?: continue
            if (item.stringFieldValue("type") != "reasoning") continue
            val encryptedContent = item.stringFieldValue("encrypted_content") ?: continue
            val block = reasoningBlocksById[item.stringFieldValue("id") ?: continue] ?: continue
            val signature = block.thinkingSignature ?: continue
            val storedItem = runCatching { PiJson.parseToJsonElement(signature) as? JsonObject }.getOrNull() ?: continue
            if (!storedItem.stringFieldValue("encrypted_content").isNullOrEmpty()) continue
            block.thinkingSignature = JsonObject(
                storedItem.toMutableMap().apply { put("encrypted_content", JsonPrimitive(encryptedContent)) },
            ).toString()
        }
    }

    private fun getCustomToolCallInput(scratch: ToolScratch): String {
        val property = scratch.customInput?.property ?: return ""
        val value = scratch.block.arguments[property] as? JsonPrimitive ?: return ""
        return if (value.isString) value.content else ""
    }

    private fun appendCustomToolCallInput(scratch: ToolScratch, nextInput: String, close: Boolean): String? {
        val customInput = scratch.customInput ?: return null
        val delta = responsesGrammarInputDelta(customInput.buffer, customInput.property, nextInput, close)
        scratch.block.arguments = buildJsonObject { put(customInput.property, nextInput) }
        return delta
    }
}

private fun parseStreamingArguments(partialJson: String): JsonObject =
    PartialJson.parse(partialJson) as? JsonObject ?: JsonObject(emptyMap())

private fun textBlocks(array: JsonArray?): String = array.orEmpty().joinToString("\n\n") { element ->
    (element as? JsonObject)?.stringFieldValue("text").orEmpty()
}

internal fun mapResponsesStopReason(status: String?, incompleteReason: String?): Pair<StopReason, String?> = when (status) {
    null -> StopReason.Stop to null
    "completed" -> StopReason.Stop to null
    "incomplete" -> when (incompleteReason) {
        "max_output_tokens" -> StopReason.Length to null
        null -> StopReason.Error to "Response incomplete without a provider reason"
        else -> StopReason.Error to "Response incomplete: $incompleteReason"
    }

    "failed", "cancelled" -> StopReason.Error to null
    "in_progress", "queued" -> StopReason.Stop to null
    else -> throw ResponsesStreamException("Unhandled stop reason: $status")
}

internal fun JsonObject.stringFieldValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.objectFieldValue(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrayFieldValue(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.longFieldValue(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
}

internal fun JsonObject.intFieldValueOrNull(key: String): Int? = longFieldValue(key)?.toInt()


internal fun emptyResponsesUsage(): Usage = Usage(
    input = 0,
    output = 0,
    cacheRead = 0,
    cacheWrite = 0,
    totalTokens = 0,
    cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
)

internal class ResponsesGrammarSampling(
    val format: String,
    val definition: String,
    val inputProperty: String,
)

internal class ResponsesGrammarBuffer(
    val property: String,
    var input: String = "",
    var started: Boolean = false,
    var closed: Boolean = false,
)

internal fun responsesGrammarToolInput(toolName: String, arguments: JsonObject, inputProperty: String): String {
    val input = arguments[inputProperty]
    if (input !is JsonPrimitive || !input.isString) {
        throw IllegalStateException("Grammar tool call \"$toolName\" requires argument \"$inputProperty\" to be a string.")
    }
    return input.content
}

internal fun responsesGrammarInputDelta(
    buffer: ResponsesGrammarBuffer,
    inputProperty: String,
    nextInput: String,
    close: Boolean,
): String? {
    if (buffer.closed) {
        if (close && nextInput == buffer.input) return null
        throw IllegalStateException("grammar tool input for property \"$inputProperty\" changed after it was closed")
    }
    if (!nextInput.startsWith(buffer.input)) {
        throw IllegalStateException("grammar tool input for property \"$inputProperty\" changed non-monotonically")
    }
    val inputDelta = nextInput.substring(buffer.input.length)
    if (!close && inputDelta.isEmpty()) return null
    val delta = StringBuilder()
    if (!buffer.started) {
        delta.append("{").append(JsonPrimitive(inputProperty).toString()).append(":\"")
        buffer.started = true
    }
    val encoded = JsonPrimitive(inputDelta).toString()
    delta.append(encoded, 1, encoded.length - 1)
    buffer.input = nextInput
    if (close) {
        delta.append("\"}")
        buffer.closed = true
    }
    return delta.toString()
}

private val RESPONSES_UNSUPPORTED_STRICT_SCHEMA_KEYS = listOf(
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

private class ResponsesUnsupportedStrictJsonSchemaException(message: String) : Exception(message)

private fun responsesIsStructuredSchema(schema: JsonElement?): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeValue = obj["type"]
    val types = when (typeValue) {
        is JsonPrimitive -> if (typeValue.isString) listOf(typeValue.content) else emptyList()
        is JsonArray -> typeValue.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        else -> emptyList()
    }
    return types.contains("object") || types.contains("array") ||
        obj.containsKey("properties") || obj.containsKey("items")
}

private fun responsesSchemaAllowsNull(schema: JsonElement?): Boolean {
    val obj = schema as? JsonObject ?: return false
    val typeValue = obj["type"]
    if (typeValue is JsonPrimitive && typeValue.isString && typeValue.content == "null") return true
    if (typeValue is JsonArray) {
        if (typeValue.any { (it as? JsonPrimitive)?.let { p -> p.isString && p.content == "null" } == true }) return true
    }
    val constValue = obj["const"]
    if (constValue is JsonNull) return true
    val enumValue = obj["enum"]
    if (enumValue is JsonArray && enumValue.any { it is JsonNull }) return true
    val anyOf = obj["anyOf"]
    if (anyOf is JsonArray && anyOf.any { responsesSchemaAllowsNull(it) }) return true
    return false
}

private fun responsesMakeStrictJsonSchema(schema: JsonObject): JsonObject {
    val rootMap = (PiJson.parseToJsonElement(schema.toString()) as? JsonObject)?.toMutableMap()
        ?: throw ResponsesUnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
    responsesMakeJsonSchemaNodeStrict(rootMap)
    val root = JsonObject(rootMap)
    if (!responsesIsObjectSchema(root)) {
        throw ResponsesUnsupportedStrictJsonSchemaException("root schema must have type object")
    }
    return root
}

private fun responsesIsObjectSchema(schema: JsonObject): Boolean {
    val typeValue = schema["type"] as? JsonPrimitive ?: return false
    return typeValue.isString && typeValue.content == "object"
}

private fun responsesMakeJsonSchemaNodeStrict(schema: MutableMap<String, JsonElement>) {
    for (key in RESPONSES_UNSUPPORTED_STRICT_SCHEMA_KEYS) {
        if (schema.containsKey(key)) throw ResponsesUnsupportedStrictJsonSchemaException("$key schemas are unsupported")
    }
    val anyOf = schema["anyOf"]
    if (anyOf != null) {
        if (anyOf !is JsonArray || anyOf.isEmpty()) {
            throw ResponsesUnsupportedStrictJsonSchemaException("anyOf must contain at least one schema")
        }
        val variants = mutableListOf<JsonElement>()
        for (variant in anyOf) {
            if (responsesIsStructuredSchema(variant)) {
                throw ResponsesUnsupportedStrictJsonSchemaException("object and array unions are unsupported")
            }
            val variantObject = variant as? JsonObject
                ?: throw ResponsesUnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
            val variantMap = variantObject.toMutableMap()
            responsesMakeJsonSchemaNodeStrict(variantMap)
            variants.add(JsonObject(variantMap))
        }
        schema["anyOf"] = JsonArray(variants)
    }
    val items = schema["items"]
    if (items != null) {
        if (items is JsonArray) throw ResponsesUnsupportedStrictJsonSchemaException("tuple schemas are unsupported")
        val itemsObject = items as? JsonObject
            ?: throw ResponsesUnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
        val itemsMap = itemsObject.toMutableMap()
        responsesMakeJsonSchemaNodeStrict(itemsMap)
        schema["items"] = JsonObject(itemsMap)
    }
    val objectSchema = responsesIsObjectSchema(JsonObject(schema))
    if (schema["properties"] != null && !objectSchema) {
        throw ResponsesUnsupportedStrictJsonSchemaException("properties require type object")
    }
    if (!objectSchema) return
    val additionalProperties = schema["additionalProperties"]
    if (additionalProperties != null) {
        val isFalse = additionalProperties is JsonPrimitive && !additionalProperties.isString &&
            additionalProperties.content == "false"
        if (!isFalse) {
            throw ResponsesUnsupportedStrictJsonSchemaException("schema-valued or true additionalProperties is unsupported")
        }
    }
    val propertiesElement = schema["properties"]
    if (propertiesElement != null && propertiesElement !is JsonObject) {
        throw ResponsesUnsupportedStrictJsonSchemaException("object properties must be a schema map")
    }
    val requiredElement = schema["required"]
    if (requiredElement != null) {
        val valid = requiredElement is JsonArray && requiredElement.all { element ->
            val primitive = element as? JsonPrimitive
            primitive != null && primitive.isString
        }
        if (!valid) throw ResponsesUnsupportedStrictJsonSchemaException("object required must be a string array")
    }
    val properties = (propertiesElement as? JsonObject) ?: JsonObject(emptyMap())
    val propertyNames = properties.keys.toList()
    val required = (requiredElement as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        .toSet()
    if (required.any { name -> name !in propertyNames }) {
        throw ResponsesUnsupportedStrictJsonSchemaException("required contains an unknown property")
    }
    val updatedProperties = LinkedHashMap<String, JsonElement>()
    for (name in propertyNames) {
        val propertyElement = properties[name]
        val property = propertyElement as? JsonObject
            ?: throw ResponsesUnsupportedStrictJsonSchemaException("boolean schemas are unsupported")
        val propertyMap = property.toMutableMap()
        responsesMakeJsonSchemaNodeStrict(propertyMap)
        val strictProperty = JsonObject(propertyMap)
        updatedProperties[name] = if (name !in required && !responsesSchemaAllowsNull(strictProperty)) {
            buildJsonObject {
                put("anyOf", JsonArray(listOf(strictProperty, buildJsonObject { put("type", "null") })))
            }
        } else {
            strictProperty
        }
    }
    schema["properties"] = JsonObject(updatedProperties)
    schema["required"] = JsonArray(propertyNames.map { JsonPrimitive(it) })
    schema["additionalProperties"] = JsonPrimitive(false)
}

private fun responsesJsonSchemaToolParameters(tool: Tool, strict: Boolean): JsonObject =
    if (strict) responsesMakeStrictJsonSchema(tool.parameters) else tool.parameters

internal fun resolveResponsesJsonSchemaStrictSampling(tool: Tool, supportsStrictMode: Boolean): Boolean? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "json_schema") return null
    if (supportsStrictMode) {
        try {
            responsesMakeStrictJsonSchema(tool.parameters)
            return true
        } catch (error: ResponsesUnsupportedStrictJsonSchemaException) {
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

private fun inferResponsesGrammarInputProperty(tool: Tool): String {
    val schema = tool.parameters
    if (stringField(schema, "type") != "object") {
        throw IllegalStateException("grammar constrained sampling requires an object parameter schema")
    }
    val required = schema["required"] as? JsonArray
    if (required == null || required.size != 1) {
        throw IllegalStateException("grammar constrained sampling requires exactly one required string property")
    }
    val inputProperty = (required[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw IllegalStateException("grammar constrained sampling requires exactly one required string property")
    val properties = schema["properties"] as? JsonObject
        ?: throw IllegalStateException("grammar constrained sampling requires a properties entry for $inputProperty")
    val property = properties[inputProperty]
        ?: throw IllegalStateException("grammar constrained sampling requires a properties entry for $inputProperty")
    if (property is JsonObject && stringField(property, "type") != "string") {
        throw IllegalStateException("grammar constrained sampling property $inputProperty must have type string")
    }
    return inputProperty
}

internal fun resolveResponsesGrammarSampling(tool: Tool, supportsOpenAIGrammarTools: Boolean): ResponsesGrammarSampling? {
    val config = tool.constrainedSampling as? JsonObject ?: return null
    if (stringField(config, "type") != "grammar") return null
    if (!supportsOpenAIGrammarTools) return null
    val variants = config["variants"] as? JsonObject
    val larkDefinition = variants?.let { stringField(it, "openai_lark") }
    val regexDefinition = variants?.let { stringField(it, "openai_regex") }
    val hasLarkDefinition = !larkDefinition.isNullOrBlank()
    val hasRegexDefinition = !regexDefinition.isNullOrBlank()
    if (!hasLarkDefinition && !hasRegexDefinition) {
        throw IllegalStateException(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: no supported grammar variant was provided.",
        )
    }
    return try {
        ResponsesGrammarSampling(
            format = if (hasLarkDefinition) "lark" else "regex",
            definition = if (hasLarkDefinition) larkDefinition!! else regexDefinition!!,
            inputProperty = inferResponsesGrammarInputProperty(tool),
        )
    } catch (error: Exception) {
        throw IllegalStateException(
            "Tool \"${tool.name}\" cannot use grammar constrained sampling: ${error.message}.",
        )
    }
}

internal fun createResponsesGrammarToolInputProperties(
    tools: List<Tool>?,
    supportsOpenAIGrammarTools: Boolean,
): Map<String, String> {
    val properties = LinkedHashMap<String, String>()
    for (tool in tools.orEmpty()) {
        val grammar = resolveResponsesGrammarSampling(tool, supportsOpenAIGrammarTools)
        if (grammar != null) properties[tool.name] = grammar.inputProperty
    }
    return properties
}
