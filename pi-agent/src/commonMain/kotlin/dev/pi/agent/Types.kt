package dev.pi.agent

import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.ClientIdentity
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

typealias JsonArgs = MutableMap<String, JsonElement>

typealias StreamFn = suspend (model: Model, context: TranscriptContext, options: SimpleStreamOptions?) -> AssistantMessageEventStream

enum class ThinkingLevel {
    Off,
    Minimal,
    Low,
    Medium,
    High,
    Xhigh,
    Max,
}

fun ThinkingLevel.toModelThinkingLevel(): ModelThinkingLevel = when (this) {
    ThinkingLevel.Off -> ModelThinkingLevel.Off
    ThinkingLevel.Minimal -> ModelThinkingLevel.Minimal
    ThinkingLevel.Low -> ModelThinkingLevel.Low
    ThinkingLevel.Medium -> ModelThinkingLevel.Medium
    ThinkingLevel.High -> ModelThinkingLevel.High
    ThinkingLevel.Xhigh -> ModelThinkingLevel.Xhigh
    ThinkingLevel.Max -> ModelThinkingLevel.Max
}

fun ThinkingLevel.toPiThinkingLevel(): dev.pi.ai.ThinkingLevel? = when (this) {
    ThinkingLevel.Off -> null
    ThinkingLevel.Minimal -> dev.pi.ai.ThinkingLevel.Minimal
    ThinkingLevel.Low -> dev.pi.ai.ThinkingLevel.Low
    ThinkingLevel.Medium -> dev.pi.ai.ThinkingLevel.Medium
    ThinkingLevel.High -> dev.pi.ai.ThinkingLevel.High
    ThinkingLevel.Xhigh -> dev.pi.ai.ThinkingLevel.Xhigh
    ThinkingLevel.Max -> dev.pi.ai.ThinkingLevel.Max
}

fun dev.pi.ai.ThinkingLevel.toThinkingLevel(): ThinkingLevel = when (this) {
    dev.pi.ai.ThinkingLevel.Minimal -> ThinkingLevel.Minimal
    dev.pi.ai.ThinkingLevel.Low -> ThinkingLevel.Low
    dev.pi.ai.ThinkingLevel.Medium -> ThinkingLevel.Medium
    dev.pi.ai.ThinkingLevel.High -> ThinkingLevel.High
    dev.pi.ai.ThinkingLevel.Xhigh -> ThinkingLevel.Xhigh
    dev.pi.ai.ThinkingLevel.Max -> ThinkingLevel.Max
}

fun ModelThinkingLevel.toThinkingLevel(): ThinkingLevel = when (this) {
    ModelThinkingLevel.Off -> ThinkingLevel.Off
    ModelThinkingLevel.Minimal -> ThinkingLevel.Minimal
    ModelThinkingLevel.Low -> ThinkingLevel.Low
    ModelThinkingLevel.Medium -> ThinkingLevel.Medium
    ModelThinkingLevel.High -> ThinkingLevel.High
    ModelThinkingLevel.Xhigh -> ThinkingLevel.Xhigh
    ModelThinkingLevel.Max -> ThinkingLevel.Max
}

enum class QueueMode {
    All,
    OneAtATime,
}

enum class ToolExecutionMode {
    Sequential,
    Parallel,
}

enum class ReplayPolicy {
    Never,
    Safe,
}

interface AgentMessage {
    val role: String
    val timestamp: Long
}

interface CustomAgentMessage : AgentMessage

val Message.role: String
    get() = when (this) {
        is SystemMessage -> "system"
        is dev.pi.ai.UserMessage -> "user"
        is AssistantMessage -> "assistant"
        is ToolResultMessage -> "toolResult"
    }

class StandardAgentMessage(val message: Message) : AgentMessage {
    override val role: String get() = message.role
    override val timestamp: Long get() = when (val value = message) {
        is SystemMessage -> value.timestamp
        is dev.pi.ai.UserMessage -> value.timestamp
        is AssistantMessage -> value.timestamp
        is ToolResultMessage -> value.timestamp
    }
}

fun AgentMessage.asLlmMessage(): Message? = (this as? StandardAgentMessage)?.message

fun AgentMessage.asAssistantMessage(): AssistantMessage? = (this as? StandardAgentMessage)?.message as? AssistantMessage

fun AgentMessage.asToolResultMessage(): ToolResultMessage? = (this as? StandardAgentMessage)?.message as? ToolResultMessage

fun Message.asAgentMessage(): StandardAgentMessage = StandardAgentMessage(this)

typealias AgentToolCall = ToolCall

typealias AgentToolUpdateCallback = suspend (partialResult: AgentToolResult) -> Unit

class AgentToolResult(
    var content: List<Content> = emptyList(),
    var details: JsonElement? = null,
    var structuredContent: JsonElement? = null,
    var usage: Usage? = null,
    var isError: Boolean? = null,
    var terminate: Boolean? = null,
)

class AgentTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val label: String,
    val constrainedSampling: JsonElement? = null,
    val prepareArguments: ((arguments: JsonObject) -> JsonObject)? = null,
    val outputSchema: JsonObject? = null,
    val execute: suspend (
        toolCallId: String,
        args: JsonArgs,
        signal: RunSignal?,
        onUpdate: AgentToolUpdateCallback?,
    ) -> AgentToolResult,
    val replay: ReplayPolicy? = null,
    val executionMode: ToolExecutionMode? = null,
) {
    fun toTool(): dev.pi.ai.Tool = dev.pi.ai.Tool(name, description, parameters, constrainedSampling)
}

class AgentToolCallOutcome(
    val toolCall: ToolCall,
    val result: AgentToolResult,
    val isError: Boolean,
    val durationMs: Long? = null,
)

class BeforeToolCallResult(
    val block: Boolean = false,
    val reason: String? = null,
    val terminate: Boolean? = null,
)

class AfterToolCallResult(
    val content: List<Content>? = null,
    val details: JsonElement? = null,
    val structuredContent: JsonElement? = null,
    val isError: Boolean? = null,
    val usage: Usage? = null,
    val terminate: Boolean? = null,
)

class BeforeToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: ToolCall,
    val args: JsonArgs,
    val context: AgentContext,
)

class AfterToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: ToolCall,
    val args: JsonArgs,
    val result: AgentToolResult,
    val isError: Boolean,
    val context: AgentContext,
)

typealias BeforeToolCall = suspend (context: BeforeToolCallContext, signal: RunSignal?) -> BeforeToolCallResult?

typealias AfterToolCall = suspend (context: AfterToolCallContext, signal: RunSignal?) -> AfterToolCallResult?

interface ToolCallHooks {
    val beforeToolCall: BeforeToolCall?
    val afterToolCall: AfterToolCall?
}

class AgentTurnContext(
    val message: AssistantMessage,
    val toolResults: List<ToolResultMessage>,
    val context: AgentContext,
    val newMessages: List<AgentMessage>,
)

typealias PrepareNextTurnContext = AgentTurnContext

typealias FinishTurn = suspend (turn: AgentTurnContext, signal: RunSignal?) -> AgentTurnDecision?

sealed interface AgentTurnDecision {
    data object Continue : AgentTurnDecision

    data object End : AgentTurnDecision
}

class AgentLoopTurnUpdate(
    val context: AgentContext? = null,
    val messages: List<AgentMessage>? = null,
    val model: Model? = null,
    val thinkingLevel: ThinkingLevel? = null,
)

class PrepareRequestContext(
    val context: AgentContext,
    val model: Model,
    val thinkingLevel: ThinkingLevel,
)

class AgentRequestUpdate(
    val context: AgentContext? = null,
    val model: Model? = null,
    val thinkingLevel: ThinkingLevel? = null,
)

typealias PrepareRequest = suspend (request: PrepareRequestContext, signal: RunSignal?) -> AgentRequestUpdate?

typealias PrepareNextTurn = suspend (context: PrepareNextTurnContext) -> AgentLoopTurnUpdate?

typealias ConvertToLlm = suspend (messages: List<AgentMessage>) -> List<Message>

typealias TransformContext = suspend (messages: List<AgentMessage>, signal: RunSignal?) -> List<AgentMessage>

typealias GetApiKey = suspend (provider: String) -> String?

class AgentContext(
    messages: List<AgentMessage> = emptyList(),
    var tools: List<AgentTool>? = null,
) {
    val messages: MutableList<AgentMessage> = messages.toMutableList()
}

class AgentState internal constructor(
    model: Model = DEFAULT_MODEL,
    thinkingLevel: ThinkingLevel = ThinkingLevel.Off,
    tools: List<AgentTool> = emptyList(),
    messages: List<AgentMessage> = emptyList(),
) {
    var model: Model = model

    var thinkingLevel: ThinkingLevel = thinkingLevel

    private var toolsValue: MutableList<AgentTool> = tools.toMutableList()

    private var messagesValue: MutableList<AgentMessage> = messages.toMutableList()

    var tools: MutableList<AgentTool>
        get() = toolsValue
        set(value) {
            toolsValue = value.toMutableList()
        }

    var messages: MutableList<AgentMessage>
        get() = messagesValue
        set(value) {
            messagesValue = value.toMutableList()
        }

    var isStreaming: Boolean = false
        internal set

    var streamingMessage: AgentMessage? = null
        internal set

    var pendingToolCalls: Set<String> = emptySet()
        internal set

    var errorMessage: String? = null
        internal set

    val systemPrompt: String
        get() = dev.pi.ai.utils.getCurrentSystemPrompt(messages.mapNotNull { it.asLlmMessage() })
}

class AgentInitialState(
    val systemPrompt: String? = null,
    val model: Model? = null,
    val thinkingLevel: ThinkingLevel? = null,
    val tools: List<AgentTool>? = null,
    val messages: List<AgentMessage>? = null,
)

class AgentOptions(
    val initialState: AgentInitialState? = null,
    val convertToLlm: ConvertToLlm? = null,
    val transformContext: TransformContext? = null,
    val streamFn: StreamFn? = null,
    val getApiKey: GetApiKey? = null,
    val onPayload: dev.pi.ai.PayloadHook? = null,
    val onResponse: dev.pi.ai.ResponseHook? = null,
    val onProviderStreamEvent: dev.pi.ai.ProviderStreamEventHook? = null,
    val beforeToolCall: BeforeToolCall? = null,
    val afterToolCall: AfterToolCall? = null,
    val finishTurn: FinishTurn? = null,
    val prepareRequest: PrepareRequest? = null,
    val prepareNextTurn: (suspend (signal: RunSignal?) -> AgentLoopTurnUpdate?)? = null,
    val prepareNextTurnWithContext: (suspend (context: PrepareNextTurnContext, signal: RunSignal?) -> AgentLoopTurnUpdate?)? = null,
    val steeringMode: QueueMode? = null,
    val followUpMode: QueueMode? = null,
    val sessionId: String? = null,
    val thinkingBudgets: dev.pi.ai.ThinkingBudgets? = null,
    val transport: dev.pi.ai.Transport? = null,
    val maxRetryDelayMs: Long? = null,
    val toolExecution: ToolExecutionMode? = null,
    val identity: ClientIdentity? = null,
    val getActiveTools: (suspend () -> List<AgentTool>)? = null,
)

class AgentLoopConfig(
    var model: Model,
    val convertToLlm: ConvertToLlm,
    var streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
    val transformContext: TransformContext? = null,
    val getApiKey: GetApiKey? = null,
    val finishTurn: FinishTurn? = null,
    val prepareRequest: PrepareRequest? = null,
    val prepareNextTurn: PrepareNextTurn? = null,
    val getSteeringMessages: (suspend () -> List<AgentMessage>)? = null,
    val getFollowUpMessages: (suspend () -> List<AgentMessage>)? = null,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.Parallel,
    override val beforeToolCall: BeforeToolCall? = null,
    override val afterToolCall: AfterToolCall? = null,
    val getActiveTools: (suspend () -> List<AgentTool>)? = null,
) : ToolCallHooks {
    val reasoning: ThinkingLevel
        get() = streamOptions.reasoning?.toThinkingLevel() ?: ThinkingLevel.Off

    internal fun snapshot(): AgentLoopConfig = AgentLoopConfig(
        model = model,
        convertToLlm = convertToLlm,
        streamOptions = streamOptions,
        transformContext = transformContext,
        getApiKey = getApiKey,
        finishTurn = finishTurn,
        prepareRequest = prepareRequest,
        prepareNextTurn = prepareNextTurn,
        getSteeringMessages = getSteeringMessages,
        getFollowUpMessages = getFollowUpMessages,
        toolExecution = toolExecution,
        beforeToolCall = beforeToolCall,
        afterToolCall = afterToolCall,
        getActiveTools = getActiveTools,
    )
}

sealed interface AgentEvent {
    val type: String

    data object AgentStart : AgentEvent {
        override val type: String get() = "agent_start"
    }

    data class AgentEnd(val messages: List<AgentMessage>) : AgentEvent {
        override val type: String get() = "agent_end"
    }

    data object TurnStart : AgentEvent {
        override val type: String get() = "turn_start"
    }

    data class TurnEnd(val message: AgentMessage, val toolResults: List<AgentMessage>) : AgentEvent {
        override val type: String get() = "turn_end"
    }

    data class MessageStart(val message: AgentMessage) : AgentEvent {
        override val type: String get() = "message_start"
    }

    data class MessageUpdate(
        val message: AgentMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentEvent {
        override val type: String get() = "message_update"
    }

    data class MessageEnd(val message: AgentMessage) : AgentEvent {
        override val type: String get() = "message_end"
    }

    data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : AgentEvent {
        override val type: String get() = "tool_execution_start"
    }

    data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val partialResult: AgentToolResult,
    ) : AgentEvent {
        override val type: String get() = "tool_execution_update"
    }

    data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult,
        val isError: Boolean,
        val durationMs: Long? = null,
    ) : AgentEvent {
        override val type: String get() = "tool_execution_end"
    }
}

class RunToolCallOptions(
    val tools: List<AgentTool>,
    val assistantMessage: AssistantMessage,
    val context: AgentContext,
    val signal: RunSignal? = null,
    val onUpdate: AgentToolUpdateCallback? = null,
    override val beforeToolCall: BeforeToolCall? = null,
    override val afterToolCall: AfterToolCall? = null,
) : ToolCallHooks

sealed interface ProxyAssistantMessageEvent {
    val type: String

    data object Start : ProxyAssistantMessageEvent {
        override val type: String get() = "start"
    }

    data class TextStart(val contentIndex: Int) : ProxyAssistantMessageEvent {
        override val type: String get() = "text_start"
    }

    data class TextDelta(val contentIndex: Int, val delta: String) : ProxyAssistantMessageEvent {
        override val type: String get() = "text_delta"
    }

    data class TextEnd(val contentIndex: Int, val contentSignature: String? = null) : ProxyAssistantMessageEvent {
        override val type: String get() = "text_end"
    }

    data class ThinkingStart(val contentIndex: Int) : ProxyAssistantMessageEvent {
        override val type: String get() = "thinking_start"
    }

    data class ThinkingDelta(val contentIndex: Int, val delta: String) : ProxyAssistantMessageEvent {
        override val type: String get() = "thinking_delta"
    }

    data class ThinkingEnd(val contentIndex: Int, val contentSignature: String? = null) : ProxyAssistantMessageEvent {
        override val type: String get() = "thinking_end"
    }

    data class ToolCallStart(
        val contentIndex: Int,
        val id: String,
        val toolName: String,
    ) : ProxyAssistantMessageEvent {
        override val type: String get() = "toolcall_start"
    }

    data class ToolCallDelta(val contentIndex: Int, val delta: String) : ProxyAssistantMessageEvent {
        override val type: String get() = "toolcall_delta"
    }

    data class ToolCallEnd(val contentIndex: Int, val toolCall: ToolCall) : ProxyAssistantMessageEvent {
        override val type: String get() = "toolcall_end"
    }

    data class Done(
        val reason: StopReason,
        val usage: Usage,
        val providerThinkingLevel: String? = null,
    ) : ProxyAssistantMessageEvent {
        override val type: String get() = "done"
    }

    data class Error(
        val reason: StopReason,
        val errorMessage: String? = null,
        val usage: Usage,
        val providerThinkingLevel: String? = null,
    ) : ProxyAssistantMessageEvent {
        override val type: String get() = "error"
    }
}

internal val DEFAULT_MODEL = Model(
    id = "unknown",
    name = "unknown",
    api = "unknown",
    provider = "unknown",
    baseUrl = "",
    input = emptyList(),
    cost = dev.pi.ai.ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
    reasoning = false,
    contextWindow = 0,
    maxTokens = 0,
)

internal val NO_TOOL_CHANGES = dev.pi.ai.utils.ToolStateChanges(emptyList(), emptyList())

internal fun createErrorToolResult(message: String): AgentToolResult = AgentToolResult(
    content = listOf(TextContent(message)),
    details = JsonObject(emptyMap()),
)
