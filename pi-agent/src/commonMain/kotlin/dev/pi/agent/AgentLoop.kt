package dev.pi.agent

import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.AssistantMessage
import dev.pi.ai.AssistantMessageEvent
import dev.pi.ai.ClientIdentity
import dev.pi.ai.Content
import dev.pi.ai.Context
import dev.pi.ai.DoneEvent
import dev.pi.ai.ErrorEvent
import dev.pi.ai.Model
import dev.pi.ai.PlainTextContent
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StartEvent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextEndEvent
import dev.pi.ai.TextStartEvent
import dev.pi.ai.ThinkingEndEvent
import dev.pi.ai.ThinkingStartEvent
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolCallEndEvent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.utils.ToolStateChanges
import dev.pi.ai.utils.getCurrentTools
import dev.pi.ai.utils.getToolStateChanges
import dev.pi.ai.utils.normalizeContext
import dev.pi.ai.utils.toToolDeclaration
import dev.pi.ai.utils.validateToolArguments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

typealias AgentEventSink = suspend (event: AgentEvent) -> Unit

val PI_ATTRIBUTION: ClientIdentity = ClientIdentity(appName = "pi", appUrl = "https://pi.dev")

val DEFAULT_AGENT_IDENTITY: ClientIdentity = PI_ATTRIBUTION

class AgentEventStream internal constructor(
    val events: Flow<AgentEvent>,
    private val resultDeferred: CompletableDeferred<List<AgentMessage>>,
) {
    suspend fun result(): List<AgentMessage> = resultDeferred.await()
}

fun CoroutineScope.agentLoop(
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    signal: RunSignal? = null,
    streamFn: StreamFn? = null,
): AgentEventStream = launchAgentStream { sink ->
    runAgentLoop(prompts, context, config, sink, signal, streamFn)
}

fun CoroutineScope.agentLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    signal: RunSignal? = null,
    streamFn: StreamFn? = null,
): AgentEventStream {
    if (context.messages.isEmpty()) {
        throw IllegalStateException("Cannot continue: no messages in context")
    }
    if (context.messages.last().role == "assistant") {
        throw IllegalStateException("Cannot continue from message role: assistant")
    }
    return launchAgentStream { sink ->
        runAgentLoopContinue(context, config, sink, signal, streamFn)
    }
}

private fun CoroutineScope.launchAgentStream(
    runner: suspend (AgentEventSink) -> List<AgentMessage>,
): AgentEventStream {
    val channel = Channel<AgentEvent>(Channel.UNLIMITED)
    val deferred = CompletableDeferred<List<AgentMessage>>()
    launch {
        try {
            val messages = runner { event -> channel.send(event) }
            deferred.complete(messages)
            channel.close()
        } catch (cancellation: CancellationException) {
            deferred.cancel(cancellation)
            channel.close(cancellation)
            throw cancellation
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            channel.close(error)
        }
    }
    return AgentEventStream(channel.receiveAsFlow(), deferred)
}

suspend fun runAgentLoop(
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    signal: RunSignal? = null,
    streamFn: StreamFn? = null,
): List<AgentMessage> {
    refreshActiveTools(context, config)
    val initialMessages = declareToolChanges(context, prompts)
    val newMessages = initialMessages.toMutableList()
    val currentContext = AgentContext(context.messages + initialMessages, context.tools)

    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)
    for (message in initialMessages) {
        emit(AgentEvent.MessageStart(message))
        emit(AgentEvent.MessageEnd(message))
    }

    runLoop(currentContext, newMessages, config, signal, emit, streamFn ?: getDefaultStreamFn())
    return newMessages
}

suspend fun runAgentLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    signal: RunSignal? = null,
    streamFn: StreamFn? = null,
): List<AgentMessage> {
    if (context.messages.isEmpty()) {
        throw IllegalStateException("Cannot continue: no messages in context")
    }
    if (context.messages.last().role == "assistant") {
        throw IllegalStateException("Cannot continue from message role: assistant")
    }
    val newMessages = mutableListOf<AgentMessage>()
    val currentContext = AgentContext(context.messages.toList(), context.tools)

    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)

    runLoop(currentContext, newMessages, config, signal, emit, streamFn ?: getDefaultStreamFn())
    return newMessages
}

private suspend fun runLoop(
    initialContext: AgentContext,
    newMessages: MutableList<AgentMessage>,
    initialConfig: AgentLoopConfig,
    signal: RunSignal?,
    emit: AgentEventSink,
    streamFunction: StreamFn,
) {
    var currentContext = initialContext
    var config = initialConfig.snapshot()
    var lastCompletedTurn: AgentTurnContext? = null
    var explicitContinuation = false
    var pendingMessages: List<AgentMessage> = config.getSteeringMessages?.invoke() ?: emptyList()

    while (true) {
        var hasMoreToolCalls = true

        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            var preparedMessages: List<AgentMessage> = emptyList()
            val previousTurn = lastCompletedTurn
            if (previousTurn != null) {
                val nextTurnSnapshot = config.prepareNextTurn?.invoke(previousTurn)
                if (nextTurnSnapshot != null) {
                    currentContext = nextTurnSnapshot.context ?: currentContext
                    preparedMessages = nextTurnSnapshot.messages ?: emptyList()
                    config.model = nextTurnSnapshot.model ?: config.model
                    config.streamOptions = config.streamOptions.copy(
                        reasoning = resolveReasoning(config.streamOptions.reasoning, nextTurnSnapshot.thinkingLevel),
                    )
                }
                if (pendingMessages.isEmpty()) {
                    pendingMessages = config.getSteeringMessages?.invoke() ?: emptyList()
                }
                emit(AgentEvent.TurnStart)
            }

            refreshActiveTools(currentContext, config)
            for (message in declareToolChanges(currentContext, preparedMessages + pendingMessages)) {
                emit(AgentEvent.MessageStart(message))
                emit(AgentEvent.MessageEnd(message))
                currentContext.messages.add(message)
                newMessages.add(message)
            }
            pendingMessages = emptyList()

            val requestUpdate = config.prepareRequest?.invoke(
                PrepareRequestContext(currentContext, config.model, config.reasoning),
                signal,
            )
            if (requestUpdate != null) {
                currentContext = requestUpdate.context ?: currentContext
                config.model = requestUpdate.model ?: config.model
                config.streamOptions = config.streamOptions.copy(
                    reasoning = resolveReasoning(config.streamOptions.reasoning, requestUpdate.thinkingLevel),
                )
            }

            val streamed = streamAssistantResponse(currentContext, config, signal, emit, streamFunction)
            val assistant = streamed.message
            newMessages.add(streamed.agentMessage)

            if (assistant.stopReason == StopReason.Error || assistant.stopReason == StopReason.Aborted) {
                val turn = AgentTurnContext(assistant, emptyList(), currentContext, newMessages.toList())
                lastCompletedTurn = turn
                config.finishTurn?.invoke(turn, signal)
                emit(AgentEvent.TurnEnd(streamed.agentMessage, emptyList()))
                emit(AgentEvent.AgentEnd(newMessages.toList()))
                return
            }

            val toolCalls = assistant.content.filterIsInstance<ToolCall>()
            val toolResults = mutableListOf<ToolResultArtifact>()
            hasMoreToolCalls = false
            if (toolCalls.isNotEmpty()) {
                val batch = if (assistant.stopReason == StopReason.Length) {
                    failToolCallsFromTruncatedMessage(toolCalls, emit)
                } else {
                    executeToolCalls(currentContext, assistant, config, signal, emit)
                }
                toolResults.addAll(batch.messages)
                hasMoreToolCalls = !batch.terminate
                for (artifact in toolResults) {
                    currentContext.messages.add(artifact.agentMessage)
                    newMessages.add(artifact.agentMessage)
                }
            }

            val turn = AgentTurnContext(
                message = assistant,
                toolResults = toolResults.map { it.message },
                context = currentContext,
                newMessages = newMessages.toList(),
            )
            lastCompletedTurn = turn
            val decision = config.finishTurn?.invoke(turn, signal)
            emit(AgentEvent.TurnEnd(streamed.agentMessage, toolResults.map { it.agentMessage }))

            if (decision == AgentTurnDecision.End) {
                emit(AgentEvent.AgentEnd(newMessages.toList()))
                return
            }

            explicitContinuation = decision == AgentTurnDecision.Continue
            pendingMessages = config.getSteeringMessages?.invoke() ?: emptyList()
            if (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
                explicitContinuation = false
            }
        }

        val followUpMessages = config.getFollowUpMessages?.invoke() ?: emptyList()
        if (followUpMessages.isNotEmpty()) {
            explicitContinuation = false
            pendingMessages = followUpMessages
            continue
        }

        if (explicitContinuation) {
            explicitContinuation = false
            continue
        }

        break
    }

    emit(AgentEvent.AgentEnd(newMessages.toList()))
}

private fun resolveReasoning(
    current: dev.pi.ai.ThinkingLevel?,
    update: ThinkingLevel?,
): dev.pi.ai.ThinkingLevel? = when {
    update == null -> current
    update == ThinkingLevel.Off -> null
    else -> update.toPiThinkingLevel()
}

private suspend fun refreshActiveTools(context: AgentContext, config: AgentLoopConfig) {
    val getActiveTools = config.getActiveTools ?: return
    val activeTools = getActiveTools()
    val currentTools = context.tools
    if (currentTools != null && currentTools.size == activeTools.size &&
        currentTools.indices.all { currentTools[it] === activeTools[it] }
    ) {
        return
    }
    context.tools = activeTools
}

private fun declareToolChanges(context: AgentContext, pendingMessages: List<AgentMessage>): List<AgentMessage> {
    var systemIndex = -1
    for (index in pendingMessages.indices.reversed()) {
        if (pendingMessages[index].role == "system") {
            systemIndex = index
            break
        }
    }
    val pending = (pendingMessages.getOrNull(systemIndex) as? StandardAgentMessage)?.message as? SystemMessage
    val baseline = if (pending != null) {
        pendingMessages.mapIndexed { index, message ->
            if (index == systemIndex) StandardAgentMessage(withToolChanges(pending, NO_TOOL_CHANGES)) else message
        }
    } else {
        pendingMessages
    }
    val changes = getToolStateChanges(
        getCurrentTools((context.messages + baseline).mapNotNull { it.asLlmMessage() }),
        (context.tools ?: emptyList()).map { toToolDeclaration(it.toTool()) },
    )
    val unchanged = changes.added.isEmpty() && changes.removed.isEmpty()

    if (pending != null) {
        if (unchanged && pending.toolsAdded.isNullOrEmpty() && pending.toolsRemoved.isNullOrEmpty()) {
            return pendingMessages
        }
        return baseline.mapIndexed { index, message ->
            if (index == systemIndex) StandardAgentMessage(withToolChanges(pending, changes)) else message
        }
    }
    if (unchanged) return pendingMessages
    val update = withToolChanges(SystemMessage(content = PlainTextContent(""), timestamp = platformCurrentTimeMillis()), changes)
    val insertIndex = pendingMessages.indexOfFirst { it.role != "system" }
    val index = if (insertIndex == -1) pendingMessages.size else insertIndex
    return pendingMessages.take(index) + StandardAgentMessage(update) + pendingMessages.drop(index)
}

private fun withToolChanges(message: SystemMessage, changes: ToolStateChanges): SystemMessage = message.copy(
    toolsAdded = changes.added.ifEmpty { null },
    toolsRemoved = changes.removed.ifEmpty { null },
)

private suspend fun streamAssistantResponse(
    context: AgentContext,
    config: AgentLoopConfig,
    signal: RunSignal?,
    emit: AgentEventSink,
    streamFunction: StreamFn,
): StreamedAssistant {
    var messages = context.messages.toList()
    val transformContext = config.transformContext
    if (transformContext != null) {
        messages = transformContext(messages, signal)
    }
    val llmMessages = config.convertToLlm(messages)
    val llmContext = normalizeContext(Context(messages = llmMessages))
    val resolvedApiKey = config.getApiKey?.invoke(config.model.provider) ?: config.streamOptions.stream.apiKey
    val options = config.streamOptions.copy(
        stream = config.streamOptions.stream.copy(
            signal = signal,
            apiKey = resolvedApiKey,
            identity = mergeIdentity(DEFAULT_AGENT_IDENTITY, config.streamOptions.stream.identity),
        ),
    )
    val response = streamFunction(config.model, llmContext, options)

    var sawStart = false
    var finished: StreamedAssistant? = null
    response.events.collect { event ->
        when (event) {
            is StartEvent -> {
                sawStart = true
                context.messages.add(StandardAgentMessage(event.partial))
                emit(AgentEvent.MessageStart(StandardAgentMessage(event.partial.copy())))
            }

            is DoneEvent, is ErrorEvent -> {
                finished = finalizeAssistantStream(context, config, emit, response, sawStart)
            }

            else -> {
                val partial = event.partialOrNull()
                if (sawStart && partial != null) {
                    context.messages[context.messages.lastIndex] = StandardAgentMessage(partial)
                    emit(AgentEvent.MessageUpdate(StandardAgentMessage(partial.copy()), event))
                }
            }
        }
    }
    return finished ?: finalizeAssistantStream(context, config, emit, response, sawStart)
}

private suspend fun finalizeAssistantStream(
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    response: dev.pi.ai.AssistantMessageEventStream,
    addedPartial: Boolean,
): StreamedAssistant {
    val finalMessage = response.result()
    finalMessage.thinkingLevel = config.reasoning.toModelThinkingLevel()
    val agentMessage = StandardAgentMessage(finalMessage)
    if (addedPartial) {
        context.messages[context.messages.lastIndex] = agentMessage
    } else {
        context.messages.add(agentMessage)
        emit(AgentEvent.MessageStart(StandardAgentMessage(finalMessage.copy())))
    }
    emit(AgentEvent.MessageEnd(agentMessage))
    return StreamedAssistant(agentMessage, finalMessage)
}

private fun AssistantMessageEvent.partialOrNull(): AssistantMessage? = when (this) {
    is StartEvent -> partial
    is TextStartEvent -> partial
    is dev.pi.ai.TextDeltaEvent -> partial
    is TextEndEvent -> partial
    is ThinkingStartEvent -> partial
    is dev.pi.ai.ThinkingDeltaEvent -> partial
    is ThinkingEndEvent -> partial
    is dev.pi.ai.ToolCallStartEvent -> partial
    is dev.pi.ai.ToolCallDeltaEvent -> partial
    is ToolCallEndEvent -> partial
    is DoneEvent -> null
    is ErrorEvent -> null
}

private suspend fun failToolCallsFromTruncatedMessage(
    toolCalls: List<ToolCall>,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val messages = mutableListOf<ToolResultArtifact>()
    for (toolCall in toolCalls) {
        emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val finalized = FinalizedToolCall(
            toolCall = toolCall,
            result = createErrorToolResult(
                "Tool call \"${toolCall.name}\" was not executed: the response hit the output token limit, " +
                    "so its arguments may be truncated. Re-issue the tool call with complete arguments.",
            ),
            isError = true,
        )
        emitToolExecutionEnd(finalized, emit)
        val artifact = createToolResultMessage(finalized)
        emitToolResultMessage(artifact, emit)
        messages.add(artifact)
    }
    return ExecutedToolCallBatch(messages, false)
}

private suspend fun executeToolCalls(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    config: AgentLoopConfig,
    signal: RunSignal?,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val toolCalls = assistantMessage.content.filterIsInstance<ToolCall>()
    val hasSequentialToolCall = toolCalls.any { toolCall ->
        currentContext.tools?.find { it.name == toolCall.name }?.executionMode == ToolExecutionMode.Sequential
    }
    return if (config.toolExecution == ToolExecutionMode.Sequential || hasSequentialToolCall) {
        executeToolCallsSequential(currentContext, assistantMessage, toolCalls, config, signal, emit)
    } else {
        executeToolCallsParallel(currentContext, assistantMessage, toolCalls, config, signal, emit)
    }
}

private suspend fun executeToolCallsSequential(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    signal: RunSignal?,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val finalizedCalls = mutableListOf<FinalizedToolCall>()
    val messages = mutableListOf<ToolResultArtifact>()

    for (toolCall in toolCalls) {
        emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val preparation = prepareToolCall(currentContext, assistantMessage, toolCall, config, signal)
        val finalized = when (preparation) {
            is ToolCallPreparation.Immediate -> FinalizedToolCall(toolCall, preparation.result, preparation.isError)
            is ToolCallPreparation.Prepared -> {
                val executed = executePreparedToolCall(preparation, signal, emitToolExecutionUpdate(toolCall, emit))
                finalizeExecutedToolCall(currentContext, assistantMessage, preparation, executed, config, signal)
            }
        }
        emitToolExecutionEnd(finalized, emit)
        val artifact = createToolResultMessage(finalized)
        emitToolResultMessage(artifact, emit)
        finalizedCalls.add(finalized)
        messages.add(artifact)
        if (signal?.isAborted == true) break
    }

    return ExecutedToolCallBatch(messages, shouldTerminateToolBatch(finalizedCalls))
}

private suspend fun executeToolCallsParallel(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    toolCalls: List<ToolCall>,
    config: AgentLoopConfig,
    signal: RunSignal?,
    emit: AgentEventSink,
): ExecutedToolCallBatch {
    val entries = mutableListOf<suspend () -> FinalizedToolCall>()

    for (toolCall in toolCalls) {
        emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val preparation = prepareToolCall(currentContext, assistantMessage, toolCall, config, signal)
        if (preparation is ToolCallPreparation.Immediate) {
            val finalized = FinalizedToolCall(toolCall, preparation.result, preparation.isError)
            emitToolExecutionEnd(finalized, emit)
            entries.add { finalized }
            if (signal?.isAborted == true) break
            continue
        }
        val prepared = preparation as ToolCallPreparation.Prepared
        entries.add {
            if (signal?.isAborted == true) {
                val finalized = FinalizedToolCall(toolCall, createErrorToolResult("Operation aborted"), true)
                emitToolExecutionEnd(finalized, emit)
                finalized
            } else {
                val executed = executePreparedToolCall(prepared, signal, emitToolExecutionUpdate(toolCall, emit))
                val finalized = finalizeExecutedToolCall(currentContext, assistantMessage, prepared, executed, config, signal)
                emitToolExecutionEnd(finalized, emit)
                finalized
            }
        }
        if (signal?.isAborted == true) break
    }

    val orderedFinalizedCalls = coroutineScope {
        entries.map { entry -> async { entry() } }.awaitAll()
    }
    val messages = mutableListOf<ToolResultArtifact>()
    for (finalized in orderedFinalizedCalls) {
        val artifact = createToolResultMessage(finalized)
        emitToolResultMessage(artifact, emit)
        messages.add(artifact)
    }

    return ExecutedToolCallBatch(messages, shouldTerminateToolBatch(orderedFinalizedCalls))
}

private fun shouldTerminateToolBatch(finalizedCalls: List<FinalizedToolCall>): Boolean =
    finalizedCalls.isNotEmpty() && finalizedCalls.all { it.result.terminate == true }

private fun prepareToolCallArguments(tool: AgentTool, toolCall: ToolCall): ToolCall {
    val prepareArguments = tool.prepareArguments ?: return toolCall
    val prepared = prepareArguments(toolCall.arguments)
    return if (prepared === toolCall.arguments) toolCall else toolCall.copy(arguments = prepared)
}

private suspend fun prepareToolCall(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    toolCall: ToolCall,
    hooks: ToolCallHooks,
    signal: RunSignal?,
    tools: List<AgentTool> = currentContext.tools ?: emptyList(),
): ToolCallPreparation {
    val tool = tools.find { it.name == toolCall.name }
        ?: return ToolCallPreparation.Immediate(createErrorToolResult("Tool ${toolCall.name} not found"), true)

    return try {
        val preparedToolCall = prepareToolCallArguments(tool, toolCall)
        val validated = validateToolArguments(tool.toTool(), preparedToolCall)
        val args: JsonArgs = LinkedHashMap(validated)
        val beforeToolCall = hooks.beforeToolCall
        if (beforeToolCall != null) {
            val beforeResult = beforeToolCall(
                BeforeToolCallContext(assistantMessage, toolCall, args, currentContext),
                signal,
            )
            if (signal?.isAborted == true) {
                return ToolCallPreparation.Immediate(createErrorToolResult("Operation aborted"), true)
            }
            if (beforeResult?.block == true) {
                val result = createErrorToolResult(beforeResult.reason ?: "Tool execution was blocked")
                if (beforeResult.terminate == true) {
                    result.terminate = true
                }
                return ToolCallPreparation.Immediate(result, true)
            }
        }
        if (signal?.isAborted == true) {
            return ToolCallPreparation.Immediate(createErrorToolResult("Operation aborted"), true)
        }
        ToolCallPreparation.Prepared(preparedToolCall, tool, args)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        ToolCallPreparation.Immediate(createErrorToolResult(error.message ?: error.toString()), true)
    }
}

private fun emitToolExecutionUpdate(toolCall: ToolCall, emit: AgentEventSink): AgentToolUpdateCallback =
    { partialResult ->
        emit(AgentEvent.ToolExecutionUpdate(toolCall.id, toolCall.name, toolCall.arguments, partialResult))
    }

suspend fun runToolCall(toolCall: ToolCall, options: RunToolCallOptions): AgentToolCallOutcome {
    val preparation = prepareToolCall(
        currentContext = options.context,
        assistantMessage = options.assistantMessage,
        toolCall = toolCall,
        hooks = options,
        signal = options.signal,
        tools = options.tools,
    )
    if (preparation is ToolCallPreparation.Immediate) {
        return AgentToolCallOutcome(toolCall, preparation.result, preparation.isError)
    }
    val prepared = preparation as ToolCallPreparation.Prepared
    val executed = executePreparedToolCall(prepared, options.signal, options.onUpdate)
    val finalized = finalizeExecutedToolCall(
        options.context,
        options.assistantMessage,
        prepared,
        executed,
        options,
        options.signal,
    )
    return AgentToolCallOutcome(finalized.toolCall, finalized.result, finalized.isError, finalized.durationMs)
}

private suspend fun executePreparedToolCall(
    prepared: ToolCallPreparation.Prepared,
    signal: RunSignal?,
    onUpdate: AgentToolUpdateCallback?,
): ExecutedToolCall {
    val startedAt = TimeSource.Monotonic.markNow()
    var acceptingUpdates = true
    fun elapsed(): Long = (startedAt.elapsedNow().inWholeMicroseconds / 1000.0).toLong()
    try {
        val result = prepared.tool.execute(prepared.toolCall.id, prepared.args, signal) { partialResult ->
            if (acceptingUpdates) {
                onUpdate?.invoke(partialResult)
            }
        }
        val durationMs = elapsed()
        acceptingUpdates = false
        return ExecutedToolCall(result, result.isError == true, durationMs)
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        val durationMs = elapsed()
        acceptingUpdates = false
        return ExecutedToolCall(createErrorToolResult(error.message ?: error.toString()), true, durationMs)
    }
}

private suspend fun finalizeExecutedToolCall(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    prepared: ToolCallPreparation.Prepared,
    executed: ExecutedToolCall,
    hooks: ToolCallHooks,
    signal: RunSignal?,
): FinalizedToolCall {
    var result = executed.result
    var isError = executed.isError

    val afterToolCall = hooks.afterToolCall
    if (afterToolCall != null) {
        try {
            val afterResult = afterToolCall(
                AfterToolCallContext(
                    assistantMessage = assistantMessage,
                    toolCall = prepared.toolCall,
                    args = prepared.args,
                    result = result,
                    isError = isError,
                    context = currentContext,
                ),
                signal,
            )
            if (afterResult != null) {
                val structuredContent = afterResult.structuredContent
                    ?: if (afterResult.content != null) null else result.structuredContent
                result = AgentToolResult(
                    content = afterResult.content ?: result.content,
                    details = afterResult.details ?: result.details,
                    structuredContent = structuredContent,
                    usage = afterResult.usage ?: result.usage,
                    isError = result.isError,
                    terminate = afterResult.terminate ?: result.terminate,
                )
                isError = afterResult.isError ?: isError
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            result = createErrorToolResult(error.message ?: error.toString())
            isError = true
        }
    }

    return FinalizedToolCall(prepared.toolCall, result, isError, executed.durationMs)
}

private suspend fun emitToolExecutionEnd(finalized: FinalizedToolCall, emit: AgentEventSink) {
    emit(
        AgentEvent.ToolExecutionEnd(
            toolCallId = finalized.toolCall.id,
            toolName = finalized.toolCall.name,
            result = finalized.result,
            isError = finalized.isError,
            durationMs = finalized.durationMs,
        ),
    )
}

private fun createToolResultMessage(finalized: FinalizedToolCall): ToolResultArtifact {
    val message = ToolResultMessage(
        toolCallId = finalized.toolCall.id,
        toolName = finalized.toolCall.name,
        content = finalized.result.content,
        details = finalized.result.details,
        usage = finalized.result.usage,
        isError = finalized.isError,
        timestamp = platformCurrentTimeMillis(),
        durationMs = finalized.durationMs,
    )
    return ToolResultArtifact(message, StandardAgentMessage(message))
}

private suspend fun emitToolResultMessage(artifact: ToolResultArtifact, emit: AgentEventSink) {
    emit(AgentEvent.MessageStart(artifact.agentMessage))
    emit(AgentEvent.MessageEnd(artifact.agentMessage))
}

internal fun mergeIdentity(base: ClientIdentity?, override: ClientIdentity?): ClientIdentity? {
    if (base == null) return override
    if (override == null) return base
    return ClientIdentity(
        userAgent = override.userAgent ?: base.userAgent,
        appName = override.appName ?: base.appName,
        appUrl = override.appUrl ?: base.appUrl,
    )
}

private class StreamedAssistant(
    val agentMessage: AgentMessage,
    val message: AssistantMessage,
)

private class FinalizedToolCall(
    val toolCall: ToolCall,
    val result: AgentToolResult,
    val isError: Boolean,
    val durationMs: Long? = null,
)

private class ExecutedToolCall(
    val result: AgentToolResult,
    val isError: Boolean,
    val durationMs: Long,
)

private class ToolResultArtifact(
    val message: ToolResultMessage,
    val agentMessage: AgentMessage,
)

private class ExecutedToolCallBatch(
    val messages: List<ToolResultArtifact>,
    val terminate: Boolean,
)

private sealed interface ToolCallPreparation {
    class Prepared(
        val toolCall: ToolCall,
        val tool: AgentTool,
        val args: JsonArgs,
    ) : ToolCallPreparation

    class Immediate(
        val result: AgentToolResult,
        val isError: Boolean,
    ) : ToolCallPreparation
}
