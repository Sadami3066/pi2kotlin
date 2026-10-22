package dev.pi.agent

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.ClientIdentity
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.PayloadHook
import dev.pi.ai.ProviderStreamEventHook
import dev.pi.ai.ResponseHook
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingBudgets
import dev.pi.ai.Transport
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.utils.createInitialSystemMessage
import dev.pi.ai.utils.getCurrentSystemMessage
import dev.pi.ai.utils.toToolDeclaration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

typealias AgentListener = suspend (event: AgentEvent, signal: RunSignal) -> Unit

private val STANDARD_MESSAGE_ROLES = setOf("system", "user", "assistant", "toolResult")

private val defaultConvertToLlm: ConvertToLlm = { messages ->
    messages.mapNotNull { message -> if (message.role in STANDARD_MESSAGE_ROLES) message.asLlmMessage() else null }
}

class Agent(options: AgentOptions = AgentOptions()) {
    private val stateValue: AgentState = createAgentState(options.initialState)

    private val listeners = LinkedHashSet<AgentListener>()

    private val steeringQueue = PendingMessageQueue(options.steeringMode ?: QueueMode.OneAtATime)

    private val followUpQueue = PendingMessageQueue(options.followUpMode ?: QueueMode.OneAtATime)

    private var activeRun: ActiveRun? = null

    var convertToLlm: ConvertToLlm = options.convertToLlm ?: defaultConvertToLlm

    var transformContext: TransformContext? = options.transformContext

    var streamFunction: StreamFn = options.streamFn ?: getDefaultStreamFn()

    var getApiKey: GetApiKey? = options.getApiKey

    var onPayload: PayloadHook? = options.onPayload

    var onResponse: ResponseHook? = options.onResponse

    var onProviderStreamEvent: ProviderStreamEventHook? = options.onProviderStreamEvent

    var beforeToolCall: BeforeToolCall? = options.beforeToolCall

    var afterToolCall: AfterToolCall? = options.afterToolCall

    var finishTurn: FinishTurn? = options.finishTurn

    var prepareRequest: PrepareRequest? = options.prepareRequest

    var prepareNextTurn: (suspend (signal: RunSignal?) -> AgentLoopTurnUpdate?)? = options.prepareNextTurn

    var prepareNextTurnWithContext:
        (suspend (context: PrepareNextTurnContext, signal: RunSignal?) -> AgentLoopTurnUpdate?)? =
        options.prepareNextTurnWithContext

    var sessionId: String? = options.sessionId

    var thinkingBudgets: ThinkingBudgets? = options.thinkingBudgets

    var transport: Transport = options.transport ?: Transport.Auto

    var maxRetryDelayMs: Long? = options.maxRetryDelayMs

    var toolExecution: ToolExecutionMode = options.toolExecution ?: ToolExecutionMode.Parallel

    var identity: ClientIdentity? = options.identity

    val state: AgentState get() = stateValue

    var steeringMode: QueueMode
        get() = steeringQueue.mode
        set(value) {
            steeringQueue.mode = value
        }

    var followUpMode: QueueMode
        get() = followUpQueue.mode
        set(value) {
            followUpQueue.mode = value
        }

    val signal: RunSignal? get() = activeRun?.signal

    fun subscribe(listener: AgentListener): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun steer(message: AgentMessage) {
        steeringQueue.enqueue(message)
    }

    fun followUp(message: AgentMessage) {
        followUpQueue.enqueue(message)
    }

    fun clearSteeringQueue() {
        steeringQueue.clear()
    }

    fun clearFollowUpQueue() {
        followUpQueue.clear()
    }

    fun clearAllQueues() {
        clearSteeringQueue()
        clearFollowUpQueue()
    }

    fun hasQueuedMessages(): Boolean = steeringQueue.hasItems() || followUpQueue.hasItems()

    fun peekQueuedMessages(): List<AgentMessage> {
        val steering = steeringQueue.peek()
        return if (steering.isNotEmpty()) steering else followUpQueue.peek()
    }

    fun abort() {
        activeRun?.signal?.abort()
    }

    suspend fun waitForIdle() {
        activeRun?.deferred?.await()
    }

    fun reset() {
        if (activeRun != null) {
            throw IllegalStateException("Agent is already processing. Wait for completion before resetting.")
        }
        val baseline = getCurrentSystemMessage(stateValue.messages.mapNotNull { it.asLlmMessage() })
        stateValue.messages = if (baseline != null) {
            mutableListOf<AgentMessage>(StandardAgentMessage(baseline))
        } else {
            mutableListOf<AgentMessage>()
        }
        stateValue.isStreaming = false
        stateValue.streamingMessage = null
        stateValue.pendingToolCalls = emptySet()
        stateValue.errorMessage = null
        clearFollowUpQueue()
        clearSteeringQueue()
    }

    suspend fun prompt(message: AgentMessage) {
        promptMessages(listOf(message))
    }

    suspend fun prompt(messages: List<AgentMessage>) {
        promptMessages(messages)
    }

    suspend fun prompt(text: String, images: List<ImageContent>? = null) {
        promptMessages(normalizePromptInput(text, images))
    }

    suspend fun continueRun() {
        if (activeRun != null) {
            throw IllegalStateException("Agent is already processing. Wait for completion before continuing.")
        }
        val messages = stateValue.messages
        val lastMessage = messages.lastOrNull()
        if (lastMessage == null || messages.all { it.role == "system" }) {
            throw IllegalStateException("No messages to continue from")
        }
        if (lastMessage.role == "assistant") {
            val queuedSteering = steeringQueue.drain()
            if (queuedSteering.isNotEmpty()) {
                runPromptMessages(queuedSteering, skipInitialSteeringPoll = true)
                return
            }
            val queuedFollowUps = followUpQueue.drain()
            if (queuedFollowUps.isNotEmpty()) {
                runPromptMessages(queuedFollowUps)
                return
            }
            throw IllegalStateException("Cannot continue from message role: assistant")
        }
        runWithLifecycle { signal ->
            runAgentLoopContinue(
                createContextSnapshot(),
                createLoopConfig(),
                { event -> processEvents(event) },
                signal,
                streamFunction,
            )
        }
    }

    private fun normalizePromptInput(text: String, images: List<ImageContent>?): List<AgentMessage> {
        val blocks: MutableList<Content> = mutableListOf(TextContent(text))
        if (!images.isNullOrEmpty()) blocks.addAll(images)
        return listOf(
            StandardAgentMessage(UserMessage(BlockContent(blocks), platformCurrentTimeMillis())),
        )
    }

    private suspend fun promptMessages(messages: List<AgentMessage>, skipInitialSteeringPoll: Boolean = false) {
        if (activeRun != null) {
            throw IllegalStateException(
                "Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion.",
            )
        }
        runPromptMessages(messages, skipInitialSteeringPoll)
    }

    private suspend fun runPromptMessages(messages: List<AgentMessage>, skipInitialSteeringPoll: Boolean = false) {
        runWithLifecycle { signal ->
            runAgentLoop(
                messages,
                createContextSnapshot(),
                createLoopConfig(skipInitialSteeringPoll),
                { event -> processEvents(event) },
                signal,
                streamFunction,
            )
        }
    }

    private fun createContextSnapshot(): AgentContext = AgentContext(
        messages = stateValue.messages.toList(),
        tools = stateValue.tools.toList(),
    )

    private fun createLoopConfig(skipInitialSteeringPoll: Boolean = false): AgentLoopConfig {
        var skipSteeringPoll = skipInitialSteeringPoll
        val streamOptions = SimpleStreamOptions(
            stream = StreamOptions(
                onPayload = onPayload,
                onResponse = onResponse,
                onProviderStreamEvent = onProviderStreamEvent,
                identity = identity,
                maxRetryDelayMs = maxRetryDelayMs,
                transport = transport,
                sessionId = sessionId,
            ),
            reasoning = stateValue.thinkingLevel.toPiThinkingLevel(),
            thinkingBudgets = thinkingBudgets,
        )
        val prepareNextTurnWithContext = this.prepareNextTurnWithContext
        val prepareNextTurn = this.prepareNextTurn
        return AgentLoopConfig(
            model = stateValue.model,
            convertToLlm = convertToLlm,
            streamOptions = streamOptions,
            transformContext = transformContext,
            getApiKey = getApiKey,
            finishTurn = finishTurn,
            prepareRequest = prepareRequest,
            prepareNextTurn = if (prepareNextTurnWithContext != null || prepareNextTurn != null) {
                { turn ->
                    if (prepareNextTurnWithContext != null) {
                        prepareNextTurnWithContext.invoke(turn, signal)
                    } else {
                        prepareNextTurn?.invoke(signal)
                    }
                }
            } else {
                null
            },
            getSteeringMessages = {
                if (skipSteeringPoll) {
                    skipSteeringPoll = false
                    emptyList()
                } else {
                    steeringQueue.drain()
                }
            },
            getFollowUpMessages = { followUpQueue.drain() },
            toolExecution = toolExecution,
            beforeToolCall = beforeToolCall,
            afterToolCall = afterToolCall,
        )
    }

    private suspend fun runWithLifecycle(executor: suspend (RunSignal) -> Unit) {
        if (activeRun != null) {
            throw IllegalStateException("Agent is already processing.")
        }
        val runSignal = RunSignal()
        val deferred = CompletableDeferred<Unit>()
        activeRun = ActiveRun(deferred, runSignal)
        stateValue.isStreaming = true
        stateValue.streamingMessage = null
        stateValue.errorMessage = null
        try {
            executor(runSignal)
        } catch (cancellation: CancellationException) {
            runSignal.abort()
            throw cancellation
        } catch (error: Throwable) {
            handleRunFailure(error, runSignal.isAborted)
        } finally {
            finishRun()
        }
    }

    private suspend fun handleRunFailure(error: Throwable, aborted: Boolean) {
        val model = stateValue.model
        val message = AssistantMessage(
            content = mutableListOf(TextContent("")),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = Usage.empty(),
            stopReason = if (aborted) StopReason.Aborted else StopReason.Error,
            errorMessage = error.message ?: error.toString(),
            timestamp = platformCurrentTimeMillis(),
        )
        val agentMessage = StandardAgentMessage(message)
        processEvents(AgentEvent.MessageStart(agentMessage))
        processEvents(AgentEvent.MessageEnd(agentMessage))
        processEvents(AgentEvent.TurnEnd(agentMessage, emptyList()))
        processEvents(AgentEvent.AgentEnd(listOf(agentMessage)))
    }

    private fun finishRun() {
        stateValue.isStreaming = false
        stateValue.streamingMessage = null
        stateValue.pendingToolCalls = emptySet()
        activeRun?.deferred?.complete(Unit)
        activeRun = null
    }

    private suspend fun processEvents(event: AgentEvent) {
        when (event) {
            is AgentEvent.MessageStart -> stateValue.streamingMessage = event.message
            is AgentEvent.MessageUpdate -> stateValue.streamingMessage = event.message
            is AgentEvent.MessageEnd -> {
                stateValue.streamingMessage = null
                stateValue.messages.add(event.message)
            }

            is AgentEvent.ToolExecutionStart -> {
                stateValue.pendingToolCalls = stateValue.pendingToolCalls + event.toolCallId
            }

            is AgentEvent.ToolExecutionEnd -> {
                stateValue.pendingToolCalls = stateValue.pendingToolCalls - event.toolCallId
            }

            is AgentEvent.TurnEnd -> {
                val assistant = event.message.asAssistantMessage()
                if (assistant?.errorMessage != null) {
                    stateValue.errorMessage = assistant.errorMessage
                }
            }

            is AgentEvent.AgentEnd -> stateValue.streamingMessage = null

            else -> Unit
        }
        val activeSignal = activeRun?.signal
            ?: throw IllegalStateException("Agent listener invoked outside active run")
        for (listener in listeners.toList()) {
            listener(event, activeSignal)
        }
    }

    private class ActiveRun(
        val deferred: CompletableDeferred<Unit>,
        val signal: RunSignal,
    )

    internal class PendingMessageQueue(var mode: QueueMode) {
        private val messages = ArrayDeque<AgentMessage>()

        fun enqueue(message: AgentMessage) {
            synchronized(this) { messages.addLast(message) }
        }

        fun hasItems(): Boolean = synchronized(this) { messages.isNotEmpty() }

        fun peek(): List<AgentMessage> = synchronized(this) {
            when (mode) {
                QueueMode.All -> messages.toList()
                QueueMode.OneAtATime -> listOfNotNull(messages.firstOrNull())
            }
        }

        fun drain(): List<AgentMessage> = synchronized(this) {
            val drained = peek()
            repeat(drained.size) { messages.removeFirstOrNull() }
            drained
        }

        fun clear() {
            synchronized(this) { messages.clear() }
        }
    }
}

private fun createAgentState(initialState: AgentInitialState?): AgentState {
    val tools = initialState?.tools?.toList() ?: emptyList()
    val messages = initialState?.messages?.toMutableList() ?: mutableListOf<AgentMessage>()
    val initialMessage = createInitialSystemMessage(
        initialState?.systemPrompt,
        tools.map { toToolDeclaration(it.toTool()) },
    )
    if (messages.firstOrNull()?.role != "system" && initialMessage != null) {
        messages.add(0, StandardAgentMessage(initialMessage))
    }
    return AgentState(
        model = initialState?.model ?: DEFAULT_MODEL,
        thinkingLevel = initialState?.thinkingLevel ?: ThinkingLevel.Off,
        tools = tools,
        messages = messages,
    )
}
