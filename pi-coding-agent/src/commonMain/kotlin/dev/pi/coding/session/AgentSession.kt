package dev.pi.coding.session

import dev.pi.agent.Agent
import dev.pi.agent.AgentEvent
import dev.pi.agent.AgentMessage
import dev.pi.agent.AgentTool
import dev.pi.agent.StandardAgentMessage
import dev.pi.agent.ThinkingLevel
import dev.pi.agent.asAssistantMessage
import dev.pi.agent.asLlmMessage
import dev.pi.agent.asAgentMessage
import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.ImageContent
import dev.pi.ai.Message
import dev.pi.ai.Model
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.UserMessage
import dev.pi.ai.CacheRetention
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
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
import dev.pi.coding.prompt.LoadedResources
import dev.pi.coding.prompt.SystemPromptInput
import dev.pi.coding.prompt.SystemPromptSections
import dev.pi.coding.prompt.buildSystemPrompt
import dev.pi.coding.prompt.diffSystemPromptSections
import dev.pi.coding.settings.SettingsManager
import dev.pi.coding.tools.CodingTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
}

class CodingHooks(
    val transformPrompt: (suspend (String) -> String)? = null,
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
    private val scope: CoroutineScope,
) {
    private val sessionEvents = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 1024)
    private val entryIdsByMessage = mutableMapOf<AgentMessage, String>()
    private var retryAttempt = 0
    private var sections: SystemPromptSections = initialSections
    private var closed = false
    private var internalUnsubscribe: (() -> Unit)? = null

    val events: SharedFlow<SessionEvent> = sessionEvents.asSharedFlow()

    val sessionId: String get() = sessionManager.sessionId

    val sessionFile: Path? get() = sessionManager.sessionFile

    val messages: List<AgentMessage> get() = agent.state.messages.toList()

    val model: Model get() = agent.state.model

    val thinkingLevel: String get() = agent.state.thinkingLevel.modelString()

    val isStreaming: Boolean get() = agent.state.isStreaming

    val activeToolNames: List<String> get() = toolSet.map { it.name }

    val systemPrompt: String get() = agent.state.systemPrompt

    private val summarize = Summarize { request -> summarizeWithRuntime(request) }

    private suspend fun summarizeWithRuntime(request: SummarizeRequest): AssistantMessage {
        val context = dev.pi.ai.Context(systemPrompt = request.systemPrompt, messages = request.messages)
        val options = SimpleStreamOptions(
            stream = StreamOptions(cacheRetention = CacheRetention.None, sessionId = sessionManager.sessionId),
        )
        return modelRuntime.completeSimple(request.model, context, options, null)
    }

    suspend fun compact(customInstructions: String? = null): CompactionResult? {
        val settings = this.settings.getCompactionSettings()
        if (!settings.enabled) return null
        val branch = sessionManager.getBranch()
        val preparation = prepareCompaction(branch, settings, agent.state.model, customInstructions) ?: return null
        val result = try {
            compact(preparation, summarize)
        } catch (error: Exception) {
            sessionEvents.tryEmit(SessionEvent.CompactionFailed(error.message))
            throw error
        } ?: return null
        applyCompaction(result)
        return result
    }

    private suspend fun maybeCompactBeforePrompt() {
        val settings = this.settings.getCompactionSettings()
        if (!settings.enabled) return
        val messages = agent.state.messages.mapNotNull { it.asLlmMessage() }
        val tokens = estimateMessagesTokens(messages)
        if (!shouldCompact(tokens, agent.state.model.contextWindow, settings)) return
        val result = try {
            compact()
        } catch (error: Exception) {
            sessionEvents.tryEmit(SessionEvent.CompactionFailed(error.message))
            return
        } ?: return
        sessionEvents.tryEmit(SessionEvent.CompactionFinished(result.tokensBefore, result.estimatedTokensAfter))
    }

    private suspend fun applyCompaction(result: CompactionResult) {
        sessionEvents.tryEmit(SessionEvent.CompactionStarted(result.tokensBefore))
        val systemMessage = SystemMessage(
            content = PlainTextContent(""),
            sections = sections.sections,
            toolsAdded = agentTools().map { it.toTool() },
            timestamp = System.currentTimeMillis(),
        )
        sessionManager.appendCompaction(
            summary = result.summary,
            firstKeptEntryId = result.firstKeptEntryId,
            tokensBefore = result.tokensBefore,
            usage = result.usage,
            details = result.details,
            systemMessage = systemMessage,
        )
        rebuildContextFromSession()
        sessionEvents.tryEmit(SessionEvent.CompactionFinished(result.tokensBefore, result.estimatedTokensAfter))
    }

    private suspend fun rebuildContextFromSession() {
        val context = sessionManager.buildSessionContext()
        val restored: MutableList<AgentMessage> = context.messages.map { it.asAgentMessage() }.toMutableList()
        if (restored.firstOrNull()?.asLlmMessage() !is SystemMessage) {
            restored.add(
                0,
                SystemMessage(
                    content = PlainTextContent(""),
                    sections = sections.sections,
                    toolsAdded = agentTools().map { it.toTool() },
                    timestamp = System.currentTimeMillis(),
                ).asAgentMessage(),
            )
        }
        entryIdsByMessage.clear()
        agent.state.messages = restored
    }

    internal fun start() {
        internalUnsubscribe = agent.subscribe { event, _ ->
            sessionEvents.tryEmit(SessionEvent.AgentEventReceived(event))
            if (event is AgentEvent.MessageEnd) persistMessage(event.message)
        }
    }

    fun subscribe(listener: suspend (SessionEvent) -> Unit): () -> Unit {
        val job = scope.launch { sessionEvents.collect { listener(it) } }
        return { job.cancel() }
    }

    suspend fun prompt(text: String, images: List<ImageContent>? = null, behavior: QueueBehavior? = null) {
        check(!closed) { "Session is disposed" }
        val transformed = hooks.transformPrompt?.invoke(text) ?: text
        if (agent.state.isStreaming) {
            val message = StandardAgentMessage(buildUserMessage(transformed, images))
            when (behavior) {
                QueueBehavior.Steer -> agent.steer(message)
                QueueBehavior.FollowUp -> agent.followUp(message)
                null -> throw IllegalStateException(
                    "Session is already streaming; pass QueueBehavior.Steer or QueueBehavior.FollowUp",
                )
            }
            return
        }
        syncSystemPromptIfNeeded()
        maybeCompactBeforePrompt()
        agent.prompt(transformed, images)
        runRetryLoop()
        sessionEvents.tryEmit(SessionEvent.Settled)
    }

    private suspend fun runRetryLoop() {
        val retrySettings = settings.getRetrySettings()
        while (true) {
            val failed = lastAssistantError() ?: run {
                if (retryAttempt > 0) {
                    sessionEvents.tryEmit(SessionEvent.RetrySucceeded(retryAttempt))
                    retryAttempt = 0
                }
                return
            }
            if (!retrySettings.enabled || retryAttempt >= retrySettings.maxRetries.toInt()) {
                if (retryAttempt > 0) {
                    sessionEvents.tryEmit(SessionEvent.RetryExhausted(retryAttempt, failed.errorMessage))
                    retryAttempt = 0
                }
                return
            }
            if (isContextOverflow(failed, agent.state.model.contextWindow)) {
                val compacted = try {
                    compact()
                } catch (error: Exception) {
                    sessionEvents.tryEmit(SessionEvent.CompactionFailed(error.message))
                    null
                }
                if (compacted != null) {
                    agent.continueRun()
                    continue
                }
                sessionEvents.tryEmit(SessionEvent.RetryExhausted(retryAttempt, failed.errorMessage))
                retryAttempt = 0
                return
            }
            if (!Retry.isRetryableAssistantError(failed)) return
            retryAttempt += 1
            val delayMs = Retry.retryDelayMs(retrySettings.baseDelayMs, retrySettings.maxAgentDelayMs, retryAttempt)
            sessionEvents.tryEmit(SessionEvent.RetryScheduled(retryAttempt, delayMs, failed.errorMessage))
            Retry.sleep(delayMs, null)
            omitFailedTurn(failed)
            agent.continueRun()
        }
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
        agent.abort()
    }

    suspend fun waitForIdle() {
        agent.waitForIdle()
    }

    suspend fun setModel(model: Model, thinkingLevel: String? = null) {
        agent.state.model = model
        if (thinkingLevel != null) agent.state.thinkingLevel = parseThinkingLevel(thinkingLevel)
        sessionManager.appendModelChange(model.provider, model.id)
        sessionManager.appendThinkingLevelChange(agent.state.thinkingLevel.modelString())
        sessionEvents.tryEmit(SessionEvent.ModelChanged(model.provider, model.id, thinkingLevel()))
    }

    suspend fun setThinkingLevel(level: String) {
        agent.state.thinkingLevel = parseThinkingLevel(level)
        sessionManager.appendThinkingLevelChange(agent.state.thinkingLevel.modelString())
        sessionEvents.tryEmit(SessionEvent.ModelChanged(model.provider, model.id, thinkingLevel()))
    }

    fun getLastAssistantText(): String? {
        val last = messages.lastOrNull()?.asAssistantMessage() ?: return null
        return last.content.filterIsInstance<TextContent>().joinToString("") { it.text }.ifBlank { null }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        internalUnsubscribe?.invoke()
        internalUnsubscribe = null
        agent.abort()
        hooks.onSessionShutdown?.invoke(this)
        sessionManager.close()
        scope.cancel()
    }

    private suspend fun persistMessage(message: AgentMessage) {
        when (val standard = message.asLlmMessage()) {
            null -> return
            is SystemMessage -> return
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
        agent.state.messages.add(
            StandardAgentMessage(
                SystemMessage(
                    content = PlainTextContent(""),
                    sections = patch,
                    timestamp = System.currentTimeMillis(),
                ),
            ),
        )
        sections = current
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
        val extra = hooks.extraSystemPromptSections?.invoke().orEmpty()
        return if (extra.isEmpty()) base else SystemPromptSections(base.sections + extra)
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
    return UserMessage(content = content, timestamp = System.currentTimeMillis())
}
