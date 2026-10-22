package dev.pi.agent

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolReference
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.createAssistantMessageEventStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertEquals

fun createUsage(): Usage = Usage.empty()

fun createModel(
    id: String = "mock",
    provider: String = "openai",
    api: String = "openai-responses",
): Model = Model(
    id = id,
    name = id,
    api = api,
    provider = provider,
    baseUrl = "https://example.invalid",
    input = listOf(ModelInputModality.Text),
    cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    reasoning = false,
    contextWindow = 8192,
    maxTokens = 2048,
)

fun assistantMessage(
    content: List<Content>,
    stopReason: StopReason = StopReason.Stop,
): AssistantMessage = AssistantMessage(
    content = content.toMutableList(),
    api = "openai-responses",
    provider = "openai",
    model = "mock",
    usage = createUsage(),
    stopReason = stopReason,
    timestamp = System.currentTimeMillis(),
)

fun textBlock(text: String): Content = TextContent(text)

fun toolCallBlock(id: String, name: String, arguments: JsonObject): Content = ToolCall(
    id = id,
    name = name,
    arguments = arguments,
)

fun userMessage(text: String): AgentMessage = StandardAgentMessage(
    UserMessage(PlainTextContent(text), System.currentTimeMillis()),
)

fun blockUserMessage(text: String): AgentMessage = StandardAgentMessage(
    UserMessage(BlockContent(listOf(TextContent(text))), System.currentTimeMillis()),
)

fun assistantAgentMessage(message: AssistantMessage): AgentMessage = StandardAgentMessage(message)

fun systemMessage(
    content: String,
    sections: Map<String, String?>? = null,
    toolsAdded: List<dev.pi.ai.Tool>? = null,
    toolsRemoved: List<ToolReference>? = null,
    timestamp: Long = 1,
): AgentMessage = StandardAgentMessage(
    SystemMessage(
        content = PlainTextContent(content),
        sections = sections,
        toolsAdded = toolsAdded,
        toolsRemoved = toolsRemoved,
        timestamp = timestamp,
    ),
)

class NotificationMessage(
    val text: String,
    override val timestamp: Long = 0,
) : CustomAgentMessage {
    override val role: String get() = "notification"
}

class ArtifactMessage(
    val text: String,
    override val timestamp: Long = 0,
) : CustomAgentMessage {
    override val role: String get() = "artifact"
}

val identityConvertToLlm: ConvertToLlm = { messages ->
    messages.mapNotNull { message ->
        if (message.role in setOf("system", "user", "assistant", "toolResult")) message.asLlmMessage() else null
    }
}

fun jsonString(value: String): JsonElement = JsonPrimitive(value)

fun jsonNumber(value: Number): JsonElement = JsonPrimitive(value)

fun jsonObjectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
    JsonObject(linkedMapOf(*entries))

fun jsonObjectOf(): JsonObject = JsonObject(emptyMap())

fun stringSchema(): JsonObject = buildJsonObject { put("type", "string") }

fun numberSchema(): JsonObject = buildJsonObject { put("type", "number") }

fun booleanSchema(): JsonObject = buildJsonObject { put("type", "boolean") }

fun arraySchema(items: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("items", items)
}

fun objectSchema(
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = properties.keys.toList(),
): JsonObject = buildJsonObject {
    put("type", "object")
    if (properties.isNotEmpty()) {
        put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
    }
    if (required.isNotEmpty()) {
        put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
    }
}

fun valueSchema(): JsonObject = objectSchema(mapOf("value" to stringSchema()))

fun agentTool(
    name: String,
    parameters: JsonObject = objectSchema(),
    label: String = name,
    description: String = "$name tool",
    executionMode: ToolExecutionMode? = null,
    prepareArguments: ((JsonObject) -> JsonObject)? = null,
    outputSchema: JsonObject? = null,
    execute: suspend (
        toolCallId: String,
        args: JsonArgs,
        signal: dev.pi.ai.RunSignal?,
        onUpdate: AgentToolUpdateCallback?,
    ) -> AgentToolResult,
): AgentTool = AgentTool(
    name = name,
    description = description,
    parameters = parameters,
    label = label,
    prepareArguments = prepareArguments,
    outputSchema = outputSchema,
    execute = execute,
    executionMode = executionMode,
)

fun doneStream(message: AssistantMessage): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    stream.push(DoneEvent(message.stopReason, message))
    return stream
}

fun errorStream(message: AssistantMessage): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    stream.push(ErrorEvent(message.stopReason, message))
    return stream
}

fun streamingTextStream(
    text: String,
    stopReason: StopReason = StopReason.Stop,
): AssistantMessageEventStream {
    val stream = createAssistantMessageEventStream()
    val partial = assistantMessage(emptyList(), StopReason.Pending)
    val textContent = TextContent("")
    partial.content.add(textContent)
    stream.push(StartEvent(partial))
    stream.push(TextStartEvent(0, partial))
    textContent.text = text
    stream.push(TextDeltaEvent(0, text, partial))
    stream.push(TextEndEvent(0, text, partial))
    partial.stopReason = stopReason
    stream.push(DoneEvent(stopReason, partial))
    return stream
}

class LoopResult(
    val events: List<AgentEvent>,
    val messages: List<AgentMessage>,
) {
    fun types(): List<String> = events.map { it.type }

    fun messageEnds(): List<AgentMessage> =
        events.filterIsInstance<AgentEvent.MessageEnd>().map { it.message }

    fun messageStarts(): List<AgentMessage> =
        events.filterIsInstance<AgentEvent.MessageStart>().map { it.message }

    fun turnEnds(): List<AgentEvent.TurnEnd> = events.filterIsInstance<AgentEvent.TurnEnd>()

    fun toolEnds(): List<AgentEvent.ToolExecutionEnd> = events.filterIsInstance<AgentEvent.ToolExecutionEnd>()

    fun toolStarts(): List<AgentEvent.ToolExecutionStart> =
        events.filterIsInstance<AgentEvent.ToolExecutionStart>()
}

suspend fun captureLoop(block: suspend (AgentEventSink) -> List<AgentMessage>): LoopResult {
    val events = mutableListOf<AgentEvent>()
    val messages = block { event -> events.add(event) }
    return LoopResult(events, messages)
}

suspend fun runLoop(
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    signal: dev.pi.ai.RunSignal? = null,
    streamFn: StreamFn,
): LoopResult = captureLoop { sink ->
    runAgentLoop(prompts, context, config, sink, signal, streamFn)
}

suspend fun runLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    signal: dev.pi.ai.RunSignal? = null,
    streamFn: StreamFn,
): LoopResult = captureLoop { sink ->
    runAgentLoopContinue(context, config, sink, signal, streamFn)
}

suspend fun collectAgentStream(stream: AgentEventStream): LoopResult = kotlinx.coroutines.coroutineScope {
    val events = mutableListOf<AgentEvent>()
    val collector = launch { stream.events.collect { event -> events.add(event) } }
    val messages = stream.result()
    collector.join()
    LoopResult(events, messages)
}

fun assertEventTypes(result: LoopResult, expected: List<String>) {
    assertEquals(expected, result.types())
}

fun messageRoles(messages: List<AgentMessage>): List<String> = messages.map { it.role }

fun asAssistant(message: AgentMessage): AssistantMessage = message.asAssistantMessage()!!

fun asToolResult(message: AgentMessage): dev.pi.ai.ToolResultMessage = message.asToolResultMessage()!!

fun textOf(content: List<Content>): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }

fun Model.withId(id: String): Model = copy(id = id, name = id)

fun userTexts(messages: List<dev.pi.ai.Message>): List<String> = messages.mapNotNull { raw ->
    val user = raw as? dev.pi.ai.UserMessage ?: return@mapNotNull null
    when (val content = user.content) {
        is PlainTextContent -> content.text
        is BlockContent -> textOf(content.blocks)
    }
}

fun capturedUserTexts(context: dev.pi.ai.TranscriptContext): List<String> = userTexts(context.messages)

fun userTextOf(message: AgentMessage): String? {
    val user = message.asLlmMessage() as? dev.pi.ai.UserMessage ?: return null
    return when (val content = user.content) {
        is PlainTextContent -> content.text
        is BlockContent -> textOf(content.blocks)
    }
}
