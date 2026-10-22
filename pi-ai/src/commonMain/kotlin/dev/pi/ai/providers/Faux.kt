package dev.pi.ai.providers

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.BlockContent
import dev.pi.ai.CacheRetention
import dev.pi.ai.Content
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.MessageContent
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputLimits
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import dev.pi.ai.PlainTextContent
import dev.pi.ai.Provider
import dev.pi.ai.ProviderResponse
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
import dev.pi.ai.ToolReference
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.ai.createAssistantMessageEventStream
import dev.pi.ai.auth.AmbientAuth
import dev.pi.ai.api.ApiProvider
import dev.pi.ai.utils.getSystemMessageText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random

private const val DEFAULT_API = "faux"
private const val DEFAULT_PROVIDER = "faux"
private const val DEFAULT_MODEL_ID = "faux-1"
private const val DEFAULT_MODEL_NAME = "Faux Model"
private const val DEFAULT_BASE_URL = "http://localhost:0"
private const val DEFAULT_MIN_TOKEN_SIZE = 3
private const val DEFAULT_MAX_TOKEN_SIZE = 5

data class FauxModelDefinition(
    val id: String,
    val name: String? = null,
    val reasoning: Boolean? = null,
    val input: List<ModelInputModality>? = null,
    val inputLimits: ModelInputLimits? = null,
    val cost: ModelCost? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
)

data class FauxTokenSize(
    val min: Int = DEFAULT_MIN_TOKEN_SIZE,
    val max: Int = DEFAULT_MAX_TOKEN_SIZE,
)

data class FauxProviderOptions(
    val api: String = DEFAULT_API,
    val provider: String = DEFAULT_PROVIDER,
    val models: List<FauxModelDefinition> = emptyList(),
    val tokensPerSecond: Double? = null,
    val tokenSize: FauxTokenSize = FauxTokenSize(),
)

class FauxProviderState {
    var callCount: Int = 0
}

interface FauxResponseFactory {
    suspend fun produce(
        context: TranscriptContext,
        options: SimpleStreamOptions?,
        state: FauxProviderState,
        model: Model,
    ): AssistantMessage
}

sealed interface FauxResponseStep {
    data class Fixed(val message: AssistantMessage) : FauxResponseStep

    data class Scripted(val factory: FauxResponseFactory) : FauxResponseStep
}

fun fauxText(text: String): TextContent = TextContent(text)

fun fauxThinking(thinking: String): ThinkingContent = ThinkingContent(thinking)

fun fauxToolCall(name: String, arguments: JsonObject, id: String? = null): ToolCall =
    ToolCall(id = id ?: randomId("tool"), name = name, arguments = arguments)

fun fauxAssistantMessage(
    content: String,
    stopReason: StopReason = StopReason.Stop,
    errorMessage: String? = null,
    responseId: String? = null,
    timestamp: Long = System.currentTimeMillis(),
): AssistantMessage = fauxAssistantMessage(
    content = listOf(fauxText(content)),
    stopReason = stopReason,
    errorMessage = errorMessage,
    responseId = responseId,
    timestamp = timestamp,
)

fun fauxAssistantMessage(
    content: List<Content>,
    stopReason: StopReason = StopReason.Stop,
    errorMessage: String? = null,
    responseId: String? = null,
    timestamp: Long = System.currentTimeMillis(),
): AssistantMessage = AssistantMessage(
    content = content.toMutableList(),
    api = DEFAULT_API,
    provider = DEFAULT_PROVIDER,
    model = DEFAULT_MODEL_ID,
    responseId = responseId,
    usage = Usage.empty(),
    stopReason = stopReason,
    errorMessage = errorMessage,
    timestamp = timestamp,
)

class FauxProviderHandle internal constructor(
    val provider: Provider,
    val api: String,
    val models: List<Model>,
    private val core: FauxCore,
) {
    val state: FauxProviderState get() = core.state

    fun getModel(id: String? = null): Model? = core.getModel(id)

    fun setResponses(responses: List<FauxResponseStep>) = core.setResponses(responses)

    fun appendResponses(responses: List<FauxResponseStep>) = core.appendResponses(responses)

    fun getPendingResponseCount(): Int = core.pendingResponseCount()
}

fun fauxProvider(options: FauxProviderOptions = FauxProviderOptions()): FauxProviderHandle {
    val core = FauxCore(options)
    val provider = Provider(
        id = core.provider,
        name = core.provider,
        apiProviders = mapOf(core.api to { core.apiProvider }),
        models = core.models,
        allModels = core.models,
        auth = AmbientAuth("faux"),
    )
    return FauxProviderHandle(provider, core.api, core.models, core)
}

internal class FauxCore(options: FauxProviderOptions) {
    val api: String = options.api
    val provider: String = options.provider
    val state = FauxProviderState()

    private val minTokenSize = maxOf(1, minOf(options.tokenSize.min, options.tokenSize.max))
    private val maxTokenSize = maxOf(minTokenSize, options.tokenSize.max)
    private val tokensPerSecond = options.tokensPerSecond?.takeIf { it > 0 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val promptCache = mutableMapOf<String, List<String>>()
    private var pendingResponses: MutableList<FauxResponseStep> = mutableListOf()

    val models: List<Model> = (options.models.ifEmpty { listOf(defaultModelDefinition()) }).map { definition ->
        Model(
            id = definition.id,
            name = definition.name ?: definition.id,
            api = api,
            provider = provider,
            baseUrl = DEFAULT_BASE_URL,
            input = definition.input ?: listOf(ModelInputModality.Text, ModelInputModality.Image),
            inputLimits = definition.inputLimits,
            cost = definition.cost ?: ModelCost(0.0, 0.0, 0.0, 0.0),
            reasoning = definition.reasoning ?: false,
            contextWindow = definition.contextWindow ?: 128000,
            maxTokens = definition.maxTokens ?: 16384,
        )
    }

    val apiProvider: ApiProvider = object : ApiProvider {
        override val api: String get() = this@FauxCore.api

        override suspend fun stream(
            model: Model,
            context: TranscriptContext,
            options: StreamOptions,
        ): AssistantMessageEventStream = runStream(model, context, SimpleStreamOptions(stream = options))

        override suspend fun streamSimple(
            model: Model,
            context: TranscriptContext,
            options: SimpleStreamOptions,
        ): AssistantMessageEventStream = runStream(model, context, options)
    }

    fun setResponses(responses: List<FauxResponseStep>) {
        pendingResponses = responses.toMutableList()
    }

    fun appendResponses(responses: List<FauxResponseStep>) {
        pendingResponses.addAll(responses)
    }

    fun pendingResponseCount(): Int = pendingResponses.size

    fun getModel(id: String? = null): Model? =
        if (id == null) models.firstOrNull() else models.firstOrNull { it.id == id }

    private fun runStream(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        val outer = createAssistantMessageEventStream()
        val step = pendingResponses.removeFirstOrNull()
        state.callCount++
        scope.launch {
            try {
                options.stream.onResponse?.invoke(ProviderResponse(200, emptyMap()), model)
                if (step == null) {
                    val message = createErrorMessage(
                        IllegalStateException("No more faux responses queued"),
                        api,
                        provider,
                        model.id,
                    )
                    val estimated = withUsageEstimate(message, context, options.stream, promptCache)
                    outer.push(ErrorEvent(StopReason.Error, estimated))
                    outer.end(estimated)
                    return@launch
                }
                val message = resolveResponse(step, context, options, model)
                streamWithDeltas(outer, message)
            } catch (error: Throwable) {
                val message = createErrorMessage(error, api, provider, model.id)
                outer.push(ErrorEvent(StopReason.Error, message))
                outer.end(message)
            }
        }
        return outer
    }

    private suspend fun resolveResponse(
        step: FauxResponseStep,
        context: TranscriptContext,
        options: SimpleStreamOptions,
        requestModel: Model,
    ): AssistantMessage {
        val resolved = when (step) {
            is FauxResponseStep.Fixed -> step.message
            is FauxResponseStep.Scripted -> step.factory.produce(context, options, state, requestModel)
        }
        return withUsageEstimate(
            cloneMessage(resolved, api, provider, requestModel.id),
            context,
            options.stream,
            promptCache,
        )
    }

    private suspend fun streamWithDeltas(stream: AssistantMessageEventStream, message: AssistantMessage) {
        val partial = message.copy(content = mutableListOf(), stopReason = StopReason.Pending)
        stream.push(StartEvent(partial.copy()))

        for (index in message.content.indices) {
            when (val block = message.content[index]) {
                is ThinkingContent -> {
                    partial.content = (partial.content + ThinkingContent("")).toMutableList()
                    stream.push(ThinkingStartEvent(index, partial.copy()))
                    for (chunk in splitStringByTokenSize(block.thinking)) {
                        scheduleChunk(chunk)
                        (partial.content[index] as ThinkingContent).thinking += chunk
                        stream.push(ThinkingDeltaEvent(index, chunk, partial.copy()))
                    }
                    stream.push(ThinkingEndEvent(index, block.thinking, partial.copy()))
                }

                is TextContent -> {
                    partial.content = (partial.content + TextContent("")).toMutableList()
                    stream.push(TextStartEvent(index, partial.copy()))
                    for (chunk in splitStringByTokenSize(block.text)) {
                        scheduleChunk(chunk)
                        (partial.content[index] as TextContent).text += chunk
                        stream.push(TextDeltaEvent(index, chunk, partial.copy()))
                    }
                    stream.push(TextEndEvent(index, block.text, partial.copy()))
                }

                is ToolCall -> {
                    partial.content = (
                        partial.content + ToolCall(
                            id = block.id,
                            name = block.name,
                            arguments = JsonObject(emptyMap()),
                        )
                        ).toMutableList()
                    stream.push(ToolCallStartEvent(index, partial.copy()))
                    for (chunk in splitStringByTokenSize(block.arguments.toString())) {
                        scheduleChunk(chunk)
                        stream.push(ToolCallDeltaEvent(index, chunk, partial.copy()))
                    }
                    (partial.content[index] as ToolCall).arguments = block.arguments
                    stream.push(ToolCallEndEvent(index, block, partial.copy()))
                }

                else -> throw IllegalStateException("Unsupported faux assistant content block")
            }
        }

        when (message.stopReason) {
            StopReason.Pending -> throw IllegalStateException("Faux response ended without a stop reason")

            StopReason.Error, StopReason.Aborted -> {
                stream.push(ErrorEvent(message.stopReason, message))
                stream.end(message)
            }

            else -> {
                stream.push(DoneEvent(message.stopReason, message))
                stream.end(message)
            }
        }
    }

    private suspend fun scheduleChunk(chunk: String) {
        val rate = tokensPerSecond
        if (rate == null) {
            yield()
            return
        }
        delay((estimateTokens(chunk).toDouble() / rate * 1000.0).toLong().coerceAtLeast(1))
    }

    private fun splitStringByTokenSize(text: String): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val tokenSize = minTokenSize + Random.nextInt(maxTokenSize - minTokenSize + 1)
            val charSize = maxOf(1, tokenSize * 4)
            chunks += text.substring(index, minOf(index + charSize, text.length))
            index += charSize
        }
        return chunks.ifEmpty { listOf("") }
    }
}

private fun defaultModelDefinition(): FauxModelDefinition = FauxModelDefinition(
    id = DEFAULT_MODEL_ID,
    name = DEFAULT_MODEL_NAME,
    reasoning = false,
    input = listOf(ModelInputModality.Text, ModelInputModality.Image),
    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
    contextWindow = 128000,
    maxTokens = 16384,
)

private fun cloneMessage(
    message: AssistantMessage,
    api: String,
    provider: String,
    modelId: String,
): AssistantMessage = message.copy(
    content = message.content.map(::cloneContent).toMutableList(),
    api = api,
    provider = provider,
    model = modelId,
    usage = message.usage.copy(cost = message.usage.cost.copy()),
)

private fun cloneContent(content: Content): Content = when (content) {
    is TextContent -> content.copy()
    is ThinkingContent -> content.copy()
    is ToolCall -> content.copy(arguments = JsonObject(content.arguments.toMap()))
    is ImageContent -> content.copy()
}

private fun createErrorMessage(error: Throwable, api: String, provider: String, modelId: String): AssistantMessage =
    AssistantMessage(
        content = mutableListOf(),
        api = api,
        provider = provider,
        model = modelId,
        usage = Usage.empty(),
        stopReason = StopReason.Error,
        errorMessage = error.message ?: error.toString(),
        timestamp = System.currentTimeMillis(),
    )

private fun withUsageEstimate(
    message: AssistantMessage,
    context: TranscriptContext,
    options: StreamOptions?,
    promptCache: MutableMap<String, List<String>>,
): AssistantMessage {
    val prompt = context.messages.map { "${roleName(it)}:${messageToText(it)}" }
    val promptLength = joinedLength(prompt)
    val promptTokens = estimateTokensOfLength(promptLength)
    val outputTokens = estimateTokens(assistantContentToText(message.content))
    var input = promptTokens
    var cacheRead = 0L
    var cacheWrite = 0L
    val sessionId = options?.sessionId

    if (sessionId != null && options.cacheRetention != CacheRetention.None) {
        val previousPrompt = promptCache[sessionId]
        if (previousPrompt != null) {
            val cachedChars = commonPromptPrefixLength(previousPrompt, prompt)
            cacheRead = estimateTokensOfLength(cachedChars)
            cacheWrite = estimateTokensOfLength(maxOf(0, promptLength - cachedChars))
            input = maxOf(0, promptTokens - cacheRead)
        } else {
            cacheWrite = promptTokens
        }
        promptCache[sessionId] = prompt
    }

    message.usage = Usage(
        input = input,
        output = outputTokens,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + outputTokens + cacheRead + cacheWrite,
        cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
    )
    return message
}

private fun roleName(message: Message): String = when (message) {
    is SystemMessage -> "system"
    is UserMessage -> "user"
    is AssistantMessage -> "assistant"
    is ToolResultMessage -> "toolResult"
}

private fun messageToText(message: Message): String = when (message) {
    is SystemMessage -> {
        val parts = mutableListOf(getSystemMessageText(message))
        message.toolsRemoved?.forEach { parts += "tool-:${PiJson.encodeToString(ToolReference.serializer(), it)}" }
        message.toolsAdded?.forEach { parts += "tool+:${PiJson.encodeToString(Tool.serializer(), it)}" }
        parts.filter { it.isNotEmpty() }.joinToString("\n")
    }

    is UserMessage -> contentToText(message.content)
    is AssistantMessage -> assistantContentToText(message.content)
    is ToolResultMessage -> toolResultToText(message)
}

private fun contentToText(content: MessageContent): String = when (content) {
    is PlainTextContent -> content.text
    is BlockContent -> content.blocks.mapNotNull(::contentBlockText).joinToString("\n")
}

private fun contentBlockText(content: Content): String? = when (content) {
    is TextContent -> content.text
    is ImageContent -> "[image:${content.mimeType}:${content.data.length}]"
    else -> null
}

private fun assistantContentToText(content: List<Content>): String = content.mapNotNull { block ->
    when (block) {
        is TextContent -> block.text
        is ThinkingContent -> block.thinking
        is ToolCall -> "${block.name}:${block.arguments}"
        else -> null
    }
}.joinToString("\n")

private fun toolResultToText(message: ToolResultMessage): String =
    (listOf(message.toolName) + message.content.mapNotNull(::contentBlockText)).joinToString("\n")

private fun estimateTokensOfLength(length: Int): Long = ((length + 3) / 4).toLong()

private fun estimateTokens(text: String): Long = estimateTokensOfLength(text.length)

private fun joinedLength(messages: List<String>, count: Int = messages.size): Int {
    var length = if (count > 0) (count - 1) * 2 else 0
    for (index in 0 until count) length += messages[index].length
    return length
}

private fun commonPromptPrefixLength(previous: List<String>, current: List<String>): Int {
    var index = 0
    while (index < previous.size && index < current.size && previous[index] == current[index]) index++
    fun rest(messages: List<String>): String {
        if (index == messages.size) return ""
        val prefix = if (index > 0) "\n\n" else ""
        return prefix + messages.subList(index, messages.size).joinToString("\n\n")
    }
    return joinedLength(previous, index) + commonPrefixLength(rest(previous), rest(current))
}

private fun commonPrefixLength(a: String, b: String): Int {
    val length = minOf(a.length, b.length)
    var index = 0
    while (index < length && a[index] == b[index]) index++
    return index
}

private fun randomId(prefix: String): String =
    "$prefix:${System.currentTimeMillis()}:${Random.nextInt(1_000_000)}"
