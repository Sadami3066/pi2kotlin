package dev.pi.coding.session

import dev.pi.agent.Agent
import dev.pi.agent.AgentEvent
import dev.pi.agent.AgentMessage
import dev.pi.agent.AgentTool
import dev.pi.agent.StandardAgentMessage
import dev.pi.agent.ThinkingLevel
import dev.pi.agent.asAgentMessage
import dev.pi.agent.asAssistantMessage
import dev.pi.agent.asLlmMessage
import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.CacheRetention
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.PlainTextContent
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.StreamOptions
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.UserMessage
import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.ai.utils.Retry
import dev.pi.ai.utils.isContextOverflow
import dev.pi.coding.compaction.CompactionResult
import dev.pi.coding.compaction.Summarize
import dev.pi.coding.compaction.SummarizeRequest
import dev.pi.coding.compaction.compact
import dev.pi.coding.compaction.estimateMessagesTokens
import dev.pi.coding.compaction.prepareCompaction
import dev.pi.coding.compaction.shouldCompact
import dev.pi.coding.models.ModelRuntime
import dev.pi.coding.platformWorkDispatcher
import dev.pi.coding.prompt.LoadedResources
import dev.pi.coding.prompt.SystemPromptInput
import dev.pi.coding.prompt.SystemPromptSections
import dev.pi.coding.prompt.buildSystemPrompt
import dev.pi.coding.prompt.diffSystemPromptSections
import dev.pi.coding.prompt.expandPromptTemplate
import dev.pi.coding.settings.SettingsManager
import dev.pi.coding.tools.CodingTool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.Path

enum class QueueBehavior { Steer, FollowUp }

sealed interface SessionEvent {
    data class AgentEventReceived(val event: AgentEvent) : SessionEvent

    data object Settled : SessionEvent

    data class ModelChanged(val provider: String, val modelId: String, val thinkingLevel: String) : SessionEvent

    data class SessionError(val message: String) : SessionEvent

    data class RetryScheduled(val attempt: Int, val delayMs: Long, val errorMessage: String?) : SessionEvent

    data class RetrySucceeded(val attempt: Int) : SessionEvent

    data class RetryExhausted(val attempts: Int, val errorMessage: String?) : SessionEvent

    data class CompactionStarted(val tokensBefore: Long) : SessionEvent

    data class CompactionFinished(val tokensBefore: Long, val tokensAfter: Long?) : SessionEvent

    data class CompactionFailed(val errorMessage: String?) : SessionEvent

    data class InputHandled(val text: String) : SessionEvent

    data class CustomMessageAppended(val customType: String, val entryId: String) : SessionEvent
}

data class InputRequest(val text: String, val images: List<ImageContent>? = null)

sealed interface InputDecision {
    data class Continue(val text: String, val images: List<ImageContent>? = null) : InputDecision

    data object Handled : InputDecision
}

class CodingHooks(
    val transformPrompt: (suspend (String) -> String)? = null,
    val onInput: (suspend (InputRequest) -> InputDecision)? = null,
    val extraSystemPromptSections: (suspend () -> Map<String, String>)? = null,
    val onSessionStart: (suspend (AgentSession) -> Unit)? = null,
    val onSessionShutdown: (suspend (AgentSession) -> Unit)? = null,
)

class AgentSession internal constructor(
    val cwd: Path,
    val agentDir: Path,
    val sessionManager: SessionManager,
    val settings: SettingsManager,
    val modelRuntime: ModelRuntime,
    val resources: LoadedResources,
    val hooks: CodingHooks,
    val agent: Agent,
    private val toolSet: List<CodingTool>,
    initialSections: SystemPromptSections,
    private val staticSystemPromptSections: Map<String, String>,
    private val scope: CoroutineScope,
) {
    private val sessionEvents = MutableSharedFlow<SessionEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val entryIdsByMessage = mutableMapOf<AgentMessage, String>()
    private val promptTemplates = resources.promptTemplates
    private var retryAttempt = 0
    private var sections: SystemPromptSections = initialSections
    private var closed = false
    private var internalUnsubscribe: (() -> Unit)? = null
    private var operationSignal: RunSignal? = null
    private var activeOperation: CompletableDeferred<Unit>? = null
    private var operationJob: Job? = null

    val events: SharedFlow<SessionEvent> = sessionEvents.asSharedFlow()

    val sessionId: String get() = sessionManager.sessionId

    val sessionFile: Path? get() = sessionManager.sessionFile

    val messages: List<AgentMessage> get() = agent.state.messages.toList()

    val model: Model get() = agent.state.model

    val thinkingLevel: String get() = agent.state.thinkingLevel.modelString()

    val isStreaming: Boolean get() = agent.state.isStreaming

    val isBusy: Boolean get() = activeOperation != null || agent.state.isStreaming

    val activeToolNames: List<String> get() = toolSet.map { it.name }

    val systemPrompt: String get() = agent.state.systemPrompt

    private val summarize = Summarize { request -> summarizeWithRuntime(request, operationSignal) }

    private suspend fun summarizeWithRuntime(request: SummarizeRequest, signal: RunSignal?): AssistantMessage {
        val context = dev.pi.ai.Context(systemPrompt = request.systemPrompt, messages = request.messages)
        val options = SimpleStreamOptions(
            stream = StreamOptions(
                cacheRetention = CacheRetention.None,
                sessionId = sessionManager.sessionId,
                signal = signal,
            ),
        )
        return modelRuntime.completeSimple(request.model, context, options, signal)
    }

    suspend fun compact(customInstructions: String? = null, signal: RunSignal? = null): CompactionResult? =
        withContext(platformWorkDispatcher()) {
            compactInternal(customInstructions, signal ?: operationSignal)
        }

    private suspend fun compactInternal(customInstructions: String?, signal: RunSignal?): CompactionResult? {
        val compactionSettings = this.settings.getCompactionSettings()
        if (!compactionSettings.enabled) return null
        val branch = sessionManager.getBranch()
        val preparation = prepareCompaction(branch, compactionSettings, agent.state.model, customInstructions)
            ?: return null
        val result = try {
            compact(preparation) { request ->
                summarizeWithRuntime(request, signal ?: operationSignal)
            }
        } catch (error: RequestAbortedException) {
            return null
        } catch (error: Exception) {
            sessionEvents.emit(SessionEvent.CompactionFailed(error.message))
            throw error
        } ?: return null
        applyCompaction(result)
        return result
    }

    private suspend fun maybeCompactBeforePrompt(signal: RunSignal) {
        val compactionSettings = this.settings.getCompactionSettings()
        if (!compactionSettings.enabled) return
        val messages = agent.state.messages.mapNotNull { it.asLlmMessage() }
        val tokens = estimateMessagesTokens(messages)
        if (!shouldCompact(tokens, agent.state.model.contextWindow, compactionSettings)) return
        val result = try {
            compactInternal(null, signal)
        } catch (error: RequestAbortedException) {
            return
        } catch (error: Exception) {
            sessionEvents.emit(SessionEvent.CompactionFailed(error.message))
            return
        } ?: return
        sessionEvents.emit(SessionEvent.CompactionFinished(result.tokensBefore, result.estimatedTokensAfter))
    }

    private suspend fun applyCompaction(result: CompactionResult) {
        sessionEvents.emit(SessionEvent.CompactionStarted(result.tokensBefore))
        val systemMessage = currentSystemMessage()
        sessionManager.appendCompaction(
            summary = result.summary,
            firstKeptEntryId = result.firstKeptEntryId,
            tokensBefore = result.tokensBefore,
            usage = result.usage,
            details = result.details,
            systemMessage = systemMessage,
        )
        rebuildContextFromSession()
        sessionEvents.emit(SessionEvent.CompactionFinished(result.tokensBefore, result.estimatedTokensAfter))
    }

    private suspend fun rebuildContextFromSession() {
        val context = sessionManager.buildSessionContext()
        val restored: MutableList<AgentMessage> = context.messages.map { it.asAgentMessage() }.toMutableList()
        if (restored.firstOrNull()?.asLlmMessage() !is SystemMessage) {
            restored.add(0, currentSystemMessage().asAgentMessage())
        }
        entryIdsByMessage.clear()
        agent.state.messages = restored
    }

    private fun currentSystemMessage(): SystemMessage = SystemMessage(
        content = PlainTextContent(""),
        sections = sections.sections,
        toolsAdded = agentTools().map { it.toTool() },
        timestamp = platformCurrentTimeMillis(),
    )

    internal fun start() {
        internalUnsubscribe = agent.subscribe { event, _ ->
            sessionEvents.emit(SessionEvent.AgentEventReceived(event))
            if (event is AgentEvent.MessageEnd) persistMessage(event.message)
        }
    }

    fun subscribe(listener: suspend (SessionEvent) -> Unit): () -> Unit {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionEvents.collect { listener(it) }
        }
        return { job.cancel() }
    }

    suspend fun prompt(text: String, images: List<ImageContent>? = null, behavior: QueueBehavior? = null) {
        check(!closed) { "Session is disposed" }
        if (activeOperation != null) {
            val message = StandardAgentMessage(buildUserMessage(text, images))
            when (behavior) {
                QueueBehavior.Steer -> agent.steer(message)
                QueueBehavior.FollowUp -> agent.followUp(message)
                null -> throw IllegalStateException(
                    "Session is busy; pass QueueBehavior.Steer or QueueBehavior.FollowUp, or call abort()",
                )
            }
            return
        }
        val operation = CompletableDeferred<Unit>()
        activeOperation = operation
        val signal = RunSignal()
        operationSignal = signal
        try {
            withContext(platformWorkDispatcher()) {
                runPrompt(text, images, signal)
            }
        } catch (error: RequestAbortedException) {
            Unit
        } finally {
            operationSignal = null
            activeOperation = null
            sessionEvents.emit(SessionEvent.Settled)
            operation.complete(Unit)
        }
    }

    private suspend fun runPrompt(text: String, images: List<ImageContent>?, signal: RunSignal) {
        val input = runInputHooks(text, images, signal)
        val expanded = expandPromptTemplate(input.text, promptTemplates)
        val transformed = hooks.transformPrompt?.invoke(expanded) ?: expanded
        syncSystemPromptIfNeeded()
        signal.throwIfAborted()
        maybeCompactBeforePrompt(signal)
        signal.throwIfAborted()
        agent.prompt(transformed, input.images)
        runRetryLoop(signal)
    }

    private suspend fun runInputHooks(text: String, images: List<ImageContent>?, signal: RunSignal): InputRequest {
        val hook = hooks.onInput ?: return InputRequest(text, images)
        val decision = hook(InputRequest(text, images))
        return when (decision) {
            is InputDecision.Handled -> {
                sessionEvents.emit(SessionEvent.InputHandled(text))
                throw RequestAbortedException("input handled")
            }

            is InputDecision.Continue -> InputRequest(decision.text, decision.images ?: images)
        }
    }

    private suspend fun runRetryLoop(signal: RunSignal) {
        val retrySettings = settings.getRetrySettings()
        var overflowRecoveryAttempted = false
        while (true) {
            if (signal.isAborted) return
            val failed = lastAssistantError() ?: run {
                if (retryAttempt > 0) {
                    sessionEvents.emit(SessionEvent.RetrySucceeded(retryAttempt))
                    retryAttempt = 0
                }
                return
            }
            if (isContextOverflow(failed, agent.state.model.contextWindow)) {
                if (overflowRecoveryAttempted) {
                    sessionEvents.emit(SessionEvent.RetryExhausted(retryAttempt, failed.errorMessage))
                    return
                }
                overflowRecoveryAttempted = true
                omitFailedTurn(failed)
                rebuildContextFromSession()
                val compacted = try {
                    compactInternal(null, signal)
                } catch (error: RequestAbortedException) {
                    return
                } catch (error: Exception) {
                    sessionEvents.emit(SessionEvent.CompactionFailed(error.message))
                    null
                }
                if (signal.isAborted) return
                if (compacted != null) {
                    agent.continueRun()
                    continue
                }
                if (canContinue()) {
                    agent.continueRun()
                    continue
                }
                sessionEvents.emit(SessionEvent.RetryExhausted(retryAttempt, failed.errorMessage))
                return
            }
            if (!Retry.isRetryableAssistantError(failed)) return
            if (!retrySettings.enabled || retryAttempt >= retrySettings.maxRetries.toInt()) {
                if (retryAttempt > 0) {
                    sessionEvents.emit(SessionEvent.RetryExhausted(retryAttempt, failed.errorMessage))
                    retryAttempt = 0
                }
                return
            }
            retryAttempt += 1
            val delayMs = Retry.retryDelayMs(retrySettings.baseDelayMs, retrySettings.maxAgentDelayMs, retryAttempt)
            sessionEvents.emit(SessionEvent.RetryScheduled(retryAttempt, delayMs, failed.errorMessage))
            try {
                Retry.sleep(delayMs, signal)
            } catch (error: RequestAbortedException) {
                return
            }
            if (signal.isAborted) return
            omitFailedTurn(failed)
            agent.continueRun()
        }
    }

    private fun canContinue(): Boolean {
        val last = agent.state.messages.lastOrNull() ?: return false
        return last.asAssistantMessage() == null
    }

    private fun lastAssistantError(): AssistantMessage? {
        for (message in agent.state.messages.asReversed()) {
            val assistant = message.asAssistantMessage() ?: continue
            return if (assistant.stopReason == StopReason.Error) assistant else null
        }
        return null
    }

    private suspend fun omitFailedTurn(failed: AssistantMessage) {
        val messages = agent.state.messages
        while (messages.isNotEmpty()) {
            val last = messages.last()
            val lastAssistant = last.asAssistantMessage() ?: break
            if (lastAssistant !== failed && lastAssistant.stopReason != StopReason.Error) break
            messages.removeAt(messages.size - 1)
            val entryId = entryIdsByMessage.remove(last)
            if (entryId != null) sessionManager.appendContextEdit(entryId, null)
        }
    }

    suspend fun steer(text: String, images: List<ImageContent>? = null) {
        if (agent.state.isStreaming) {
            agent.steer(StandardAgentMessage(buildUserMessage(text, images)))
        } else {
            prompt(text, images)
        }
    }

    suspend fun followUp(text: String, images: List<ImageContent>? = null) {
        if (agent.state.isStreaming) {
            agent.followUp(StandardAgentMessage(buildUserMessage(text, images)))
        } else {
            prompt(text, images)
        }
    }

    fun abort() {
        operationSignal?.abort("aborted by user")
        agent.abort()
    }

    suspend fun waitForIdle() {
        activeOperation?.await()
        agent.waitForIdle()
    }

    suspend fun setModel(model: Model, thinkingLevel: String? = null) {
        agent.state.model = model
        if (thinkingLevel != null) agent.state.thinkingLevel = parseThinkingLevel(thinkingLevel)
        sessionManager.appendModelChange(model.provider, model.id)
        sessionManager.appendThinkingLevelChange(agent.state.thinkingLevel.modelString())
        sessionEvents.emit(SessionEvent.ModelChanged(model.provider, model.id, thinkingLevel()))
    }

    suspend fun setThinkingLevel(level: String) {
        agent.state.thinkingLevel = parseThinkingLevel(level)
        sessionManager.appendThinkingLevelChange(agent.state.thinkingLevel.modelString())
        sessionEvents.emit(SessionEvent.ModelChanged(model.provider, model.id, thinkingLevel()))
    }

    suspend fun appendCustomMessage(
        customType: String,
        content: dev.pi.ai.MessageContent,
        display: Boolean,
        details: JsonElement? = null,
    ) {
        val entry = sessionManager.appendCustomMessageEntry(customType, content, display, details)
        sessionEvents.emit(SessionEvent.CustomMessageAppended(customType, entry.id))
        if (agent.state.isStreaming) return
        rebuildContextFromSession()
    }

    fun getLastAssistantText(): String? {
        val last = messages.lastOrNull()?.asAssistantMessage() ?: return null
        return last.content.filterIsInstance<TextContent>().joinToString("") { it.text }.ifBlank { null }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        abort()
        activeOperation?.await()
        agent.waitForIdle()
        hooks.onSessionShutdown?.invoke(this)
        internalUnsubscribe?.invoke()
        internalUnsubscribe = null
        sessionManager.close()
        scope.cancel()
    }

    private suspend fun persistMessage(message: AgentMessage) {
        val standard = message.asLlmMessage() ?: return
        when (standard) {
            is SystemMessage -> {
                entryIdsByMessage[message] = sessionManager.appendMessage(standard).id
            }

            is AssistantMessage -> if (standard.stopReason != StopReason.Pending) {
                entryIdsByMessage[message] = sessionManager.appendMessage(standard).id
            }

            is Message -> entryIdsByMessage[message] = sessionManager.appendMessage(standard).id
        }
    }

    private suspend fun syncSystemPromptIfNeeded() {
        val current = buildCurrentSections()
        val diff = diffSystemPromptSections(sections, current)
        if (diff.jsonObject.isEmpty()) return
        val patch = diff.jsonObject.mapValues { (_, value) ->
            if (value is JsonNull) null else (value as? JsonPrimitive)?.content ?: value.toString()
        }
        val message = StandardAgentMessage(
            SystemMessage(
                content = PlainTextContent(""),
                sections = patch,
                timestamp = platformCurrentTimeMillis(),
            ),
        )
        agent.state.messages.add(message)
        sections = current
        val standard = message.message as SystemMessage
        entryIdsByMessage[message] = sessionManager.appendMessage(standard).id
    }

    private suspend fun buildCurrentSections(): SystemPromptSections {
        val base = buildSystemPrompt(
            SystemPromptInput(
                preamble = resources.systemPromptOverride,
                toolSnippets = toolSet.mapNotNull { tool -> tool.promptSnippet?.let { "${tool.name}: $it" } },
                toolGuidelines = toolSet.flatMap { it.promptGuidelines.orEmpty() },
                docsPaths = emptyList(),
                addendum = resources.appendSystemPrompt,
                projectInstructions = resources.contextFiles,
                skills = resources.skills,
                cwd = cwd,
            ),
        )
        val hookExtras = hooks.extraSystemPromptSections?.invoke().orEmpty()
        val extras = staticSystemPromptSections + hookExtras
        return if (extras.isEmpty()) base else SystemPromptSections(base.sections + extras)
    }

    internal fun agentTools(): List<AgentTool> = toolSet.map { it.asAgentTool() }

    private fun ThinkingLevel.modelString(): String = when (this) {
        ThinkingLevel.Off -> "off"
        ThinkingLevel.Minimal -> "minimal"
        ThinkingLevel.Low -> "low"
        ThinkingLevel.Medium -> "medium"
        ThinkingLevel.High -> "high"
        ThinkingLevel.Xhigh -> "xhigh"
        ThinkingLevel.Max -> "max"
    }

    private fun thinkingLevel(): String = agent.state.thinkingLevel.modelString()

    companion object {
        fun parseThinkingLevel(value: String): ThinkingLevel = when (value.lowercase()) {
            "off" -> ThinkingLevel.Off
            "minimal" -> ThinkingLevel.Minimal
            "low" -> ThinkingLevel.Low
            "medium" -> ThinkingLevel.Medium
            "high" -> ThinkingLevel.High
            "xhigh" -> ThinkingLevel.Xhigh
            "max" -> ThinkingLevel.Max
            else -> ThinkingLevel.Off
        }

        fun thinkingLevelString(level: ThinkingLevel): String = when (level) {
            ThinkingLevel.Off -> "off"
            ThinkingLevel.Minimal -> "minimal"
            ThinkingLevel.Low -> "low"
            ThinkingLevel.Medium -> "medium"
            ThinkingLevel.High -> "high"
            ThinkingLevel.Xhigh -> "xhigh"
            ThinkingLevel.Max -> "max"
        }
    }
}

internal fun buildUserMessage(text: String, images: List<ImageContent>?): UserMessage {
    val content = if (images.isNullOrEmpty()) {
        PlainTextContent(text)
    } else {
        BlockContent(
            buildList<Content> {
                if (text.isNotEmpty()) add(TextContent(text))
                images.forEach { add(it) }
            },
        )
    }
    return UserMessage(content = content, timestamp = platformCurrentTimeMillis())
}
