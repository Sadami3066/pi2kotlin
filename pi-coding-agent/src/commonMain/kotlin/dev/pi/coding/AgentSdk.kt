package dev.pi.coding

import dev.pi.agent.Agent
import dev.pi.agent.AgentInitialState
import dev.pi.agent.AgentOptions
import dev.pi.agent.QueueMode
import dev.pi.agent.StreamFn
import dev.pi.agent.asAgentMessage
import dev.pi.agent.asLlmMessage
import dev.pi.ai.CacheRetention
import dev.pi.ai.Context
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.SystemMessage
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.auth.fileCredentialStore
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.platform.platformCurrentTimeMillis
import dev.pi.coding.compaction.GenerateBranchSummaryOptions
import dev.pi.coding.compaction.collectEntriesForBranchSummary
import dev.pi.coding.compaction.generateBranchSummary
import dev.pi.coding.models.CustomModelsLoader
import dev.pi.coding.models.ModelResolver
import dev.pi.coding.models.ModelRuntime
import dev.pi.coding.models.ProviderComposer
import dev.pi.coding.paths.AgentPaths
import dev.pi.coding.prompt.LoadedResources
import dev.pi.coding.prompt.ResourceLoader
import dev.pi.coding.prompt.SystemPromptInput
import dev.pi.coding.prompt.SystemPromptSections
import dev.pi.coding.prompt.buildSystemPrompt
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.CodingHooks
import dev.pi.coding.mcp.DefaultMcpTransportFactory
import dev.pi.coding.mcp.McpConfigLoadResult
import dev.pi.coding.mcp.McpConfigLoader
import dev.pi.coding.mcp.McpManager
import dev.pi.coding.mcp.McpManagerCallbacks
import dev.pi.coding.mcp.McpOptions
import dev.pi.coding.mcp.McpToolSearch
import dev.pi.coding.session.CustomEntry
import dev.pi.coding.session.SessionEvent
import dev.pi.coding.session.SessionManager
import dev.pi.coding.settings.SettingsManager
import dev.pi.coding.settings.TrustManager
import dev.pi.coding.tools.CodingTool
import dev.pi.coding.tools.FileMutationQueue
import dev.pi.coding.tools.ShellExecutor
import dev.pi.coding.tools.defaultCodingTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.pi.ai.RunSignal
import okio.FileSystem
import okio.Path

data class AgentSessionOptions(
    val cwd: Path,
    val agentDir: Path,
    val transport: HttpTransport,
    val fs: FileSystem = FileSystem.SYSTEM,
    val model: String? = null,
    val thinkingLevel: String? = null,
    val sessionFile: Path? = null,
    val tools: List<CodingTool>? = null,
    val shellExecutor: ShellExecutor? = null,
    val hooks: CodingHooks = CodingHooks(),
    val credentials: CredentialStore? = null,
    val providers: List<dev.pi.ai.Provider> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val extraSystemPromptSections: Map<String, String> = emptyMap(),
    val projectTrusted: Boolean? = null,
    val mcp: dev.pi.coding.mcp.McpOptions = dev.pi.coding.mcp.McpOptions(),
)

suspend fun createAgentSession(options: AgentSessionOptions): AgentSession {
    val paths = AgentPaths(options.cwd, options.agentDir)
    val settings = SettingsManager.forPaths(paths, options.fs)
    val trusted = options.projectTrusted ?: TrustManager(paths.trustFile, options.fs)
        .resolveTrusted(options.cwd, settings.getDefaultProjectTrust())
    settings.setProjectTrusted(trusted)
    settings.reload()
    val credentialStore = options.credentials ?: fileCredentialStore(options.agentDir, options.fs)

    val customModelsText = if (options.fs.exists(paths.modelsFile)) {
        options.fs.read(paths.modelsFile) { readUtf8() }
    } else {
        null
    }
    val customProviders = customModelsText?.let { CustomModelsLoader().parse(it) }.orEmpty()
    val models = ProviderComposer(
        transport = options.transport,
        credentialStore = credentialStore,
        customProviders = customProviders,
        env = options.env,
    ).compose()
    options.providers.forEach { models.setProvider(it) }
    val modelRuntime = ModelRuntime(models, credentialStore, options.env)

    val sessionManager = if (options.sessionFile != null) {
        SessionManager.open(options.sessionFile, options.fs)
    } else {
        SessionManager.create(paths, options.fs)
    }
    val sessionContext = sessionManager.buildSessionContext()
    val defaultModel = settings.getDefaultModel()
    val resolver = ModelResolver(
        runtime = modelRuntime,
        defaultProvider = defaultModel?.first,
        defaultModel = defaultModel?.second,
        enabledModels = settings.getEnabledModels(),
    )

    val explicitResolved = options.model?.let { resolver.resolve(it) }
    val restoredModel = sessionContext.provider?.let { provider ->
        sessionContext.modelId?.let { modelId -> modelRuntime.getModel(provider, modelId) }
    }
    val defaultResolved = resolver.resolveDefault()
    val model = explicitResolved?.model
        ?: restoredModel
        ?: defaultResolved?.model
        ?: throw IllegalStateException(
            "No model configured. Pass AgentSessionOptions.model or set settings defaultProvider/defaultModel.",
        )
    val thinkingLevel: String = options.thinkingLevel
        ?: explicitResolved?.thinkingLevel
        ?: (if (options.model == null) sessionContext.thinkingLevel else null)
        ?: (if (options.model == null && restoredModel == null) defaultResolved?.thinkingLevel else null)
        ?: settings.getModelThinkingLevel(model.provider, model.id)
        ?: settings.getDefaultThinkingLevel()
        ?: "off"

    if (sessionManager.getEntries().isEmpty()) {
        sessionManager.appendModelChange(model.provider, model.id)
        sessionManager.appendThinkingLevelChange(thinkingLevel)
    }

    val resources = ResourceLoader(paths, options.fs, settings).reload()
    val defaultToolNames = settings.getDefaultTools().toSet()
    val tools = options.tools ?: defaultCodingTools(
        cwd = options.cwd,
        fs = options.fs,
        mutationQueue = FileMutationQueue(),
        shellExecutor = options.shellExecutor,
    ).filter { it.name in defaultToolNames }
    val agentTools = tools.map { it.asAgentTool() }

    val freshSections = buildFreshSections(options, resources, tools)
    val restored = sessionContext.messages.map { it.asAgentMessage() }.toMutableList()
    val replayedSections = replaySystemSections(restored)
    val initialSections = if (replayedSections.isEmpty()) {
        freshSections
    } else {
        SystemPromptSections(replayedSections)
    }
    if (restored.firstOrNull()?.asLlmMessage() !is SystemMessage) {
        val initialSystem = SystemMessage(
            content = PlainTextContent(""),
            sections = initialSections.sections,
            toolsAdded = agentTools.map { it.toTool() },
            timestamp = platformCurrentTimeMillis(),
        )
        sessionManager.appendMessage(initialSystem)
        restored.add(0, initialSystem.asAgentMessage())
    }

    val providerRetry = settings.getProviderRetrySettings()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val streamFn: StreamFn = { requestedModel, transcript, streamOptions ->
        val base = streamOptions ?: SimpleStreamOptions()
        val enriched = base.copy(
            stream = base.stream.copy(
                maxRetries = base.stream.maxRetries ?: providerRetry.maxRetries?.toInt(),
                maxRetryDelayMs = base.stream.maxRetryDelayMs ?: providerRetry.maxRetryDelayMs,
                timeoutMs = base.stream.timeoutMs ?: providerRetry.timeoutMs,
            ),
        )
        modelRuntime.streamSimple(
            model = requestedModel,
            context = Context(messages = transcript.messages),
            options = enriched,
            signal = enriched.stream.signal,
        )
    }
    val agent = Agent(
        AgentOptions(
            initialState = AgentInitialState(
                model = model,
                thinkingLevel = AgentSession.parseThinkingLevel(thinkingLevel),
                messages = restored,
                tools = agentTools,
            ),
            streamFn = streamFn,
            steeringMode = parseQueueMode(settings.getSteeringMode()),
            followUpMode = parseQueueMode(settings.getFollowUpMode()),
            maxRetryDelayMs = providerRetry.maxRetryDelayMs,
            sessionId = sessionManager.sessionId,
        ),
    )

    val session = AgentSession(
        cwd = options.cwd,
        agentDir = options.agentDir,
        sessionManager = sessionManager,
        settings = settings,
        modelRuntime = modelRuntime,
        resources = resources,
        hooks = options.hooks,
        agent = agent,
        toolSet = tools,
        initialSections = initialSections,
        staticSystemPromptSections = options.extraSystemPromptSections,
        scope = scope,
    )
    session.start()
    if (options.mcp.enabled) {
        attachMcp(options, paths, settings, scope, session, options.mcp)
    }
    options.hooks.onSessionStart?.invoke(session)
    return session
}

private fun buildFreshSections(
    options: AgentSessionOptions,
    resources: LoadedResources,
    tools: List<CodingTool>,
): SystemPromptSections {
    val built = buildSystemPrompt(
        SystemPromptInput(
            preamble = resources.systemPromptOverride,
            toolSnippets = tools.mapNotNull { tool -> tool.promptSnippet?.let { "${tool.name}: $it" } },
            toolGuidelines = tools.flatMap { it.promptGuidelines.orEmpty() },
            docsPaths = emptyList(),
            addendum = resources.appendSystemPrompt,
            projectInstructions = resources.contextFiles,
            skills = resources.skills,
            cwd = options.cwd,
        ),
    )
    return if (options.extraSystemPromptSections.isEmpty()) {
        built
    } else {
        SystemPromptSections(built.sections + options.extraSystemPromptSections)
    }
}

private fun replaySystemSections(messages: List<dev.pi.agent.AgentMessage>): Map<String, String> {
    val sections = LinkedHashMap<String, String>()
    for (message in messages) {
        val system = message.asLlmMessage() as? SystemMessage ?: continue
        system.sections?.forEach { (key, value) ->
            if (value == null) sections.remove(key) else sections[key] = value
        }
    }
    return sections
}

private fun parseQueueMode(value: String): QueueMode = when (value.lowercase()) {
    "all" -> QueueMode.All
    else -> QueueMode.OneAtATime
}

private const val MCP_LOADED_TOOLS_ENTRY = "mcp_loaded_tools"
private val MCP_RESOURCE_TOOL_NAMES = setOf("list_mcp_resources", "list_mcp_resource_templates", "read_mcp_resource")

private suspend fun attachMcp(
    options: AgentSessionOptions,
    paths: AgentPaths,
    settings: SettingsManager,
    scope: CoroutineScope,
    session: AgentSession,
    mcpOptions: McpOptions,
) {
    val loaded = if (mcpOptions.loadConfig) {
        McpConfigLoader(paths, options.fs, settings).load(options.env)
    } else {
        McpConfigLoadResult(emptyList(), emptyList())
    }
    val merged = LinkedHashMap<String, dev.pi.mcp.config.McpServerConfig>()
    for ((name, config) in loaded.servers) merged[name] = config
    merged.putAll(mcpOptions.servers)
    for (error in loaded.errors) session.emitSessionEvent(SessionEvent.SessionError(error))
    val callbacks = McpManagerCallbacks(
        toolsAdded = { _, tools -> session.addTools(tools) },
        toolsRemoved = { _, names -> session.removeTools(names) },
        statusChanged = { status -> session.emitSessionEvent(SessionEvent.McpServerStatusChanged(status.name, status)) },
        log = { server, level, message -> session.emitSessionEvent(SessionEvent.McpLog(server, level, message)) },
        resourceToolsChanged = { tools ->
            session.removeTools(MCP_RESOURCE_TOOL_NAMES)
            if (tools.isNotEmpty()) session.addTools(tools)
        },
        existingToolNames = { session.toolNames() },
    )
    val manager = McpManager(
        options = mcpOptions,
        factory = mcpOptions.transportFactory ?: DefaultMcpTransportFactory(options.transport, options.env),
        scope = scope,
        initialServers = merged,
        callbacks = callbacks,
        oauthHttp = options.transport,
        oauthTokenStore = dev.pi.mcp.oauth.FileMcpTokenStore(
            dev.pi.mcp.oauth.defaultMcpAuthFile(paths.agentDir),
            options.fs,
        ),
    )
    session.attachMcpManager(manager)
    manager.start()
    if (mcpOptions.autoEnableToolSearch && manager.hasDeferredTools()) {
        val search = McpToolSearch(
            catalog = { manager.deferredCatalog() },
            onLoaded = { names -> onMcpToolsLoaded(session, manager, names) },
        )
        session.addTools(listOf(search.toCodingTool()))
    }
    restoreLoadedMcpTools(session, manager)
}

private suspend fun onMcpToolsLoaded(session: AgentSession, manager: McpManager, names: List<String>) {
    val tools = manager.loadDeferredTools(names)
    if (tools.isEmpty()) return
    session.addTools(tools)
    val payload = buildJsonObject {
        put(
            "loaded",
            JsonArray(names.map { JsonPrimitive(it) }),
        )
    }
    session.sessionManager.appendCustomEntry(MCP_LOADED_TOOLS_ENTRY, payload)
}

private suspend fun restoreLoadedMcpTools(session: AgentSession, manager: McpManager) {
    val entry = session.sessionManager.getEntries()
        .filterIsInstance<CustomEntry>()
        .lastOrNull { it.customType == MCP_LOADED_TOOLS_ENTRY }
        ?: return
    val data = entry.data as? JsonObject ?: return
    val names = (data["loaded"] as? JsonArray)
        ?.mapNotNull { element -> runCatching { element.jsonPrimitive.content }.getOrNull() }
        .orEmpty()
    if (names.isEmpty()) return
    val tools = manager.loadDeferredTools(names)
    if (tools.isNotEmpty()) session.addTools(tools)
}

class AgentSessionRuntime private constructor(
    private val options: AgentSessionOptions,
    initial: AgentSession,
) {
    var session: AgentSession = initial
        private set

    companion object {
        suspend fun create(options: AgentSessionOptions): AgentSessionRuntime =
            AgentSessionRuntime(options, createAgentSession(options))
    }

    suspend fun newSession(): AgentSession {
        session.close()
        session = createAgentSession(options.copy(sessionFile = null))
        return session
    }

    suspend fun switchSession(file: Path): AgentSession {
        session.close()
        session = createAgentSession(options.copy(sessionFile = file))
        return session
    }

    suspend fun fork(entryId: String? = null, summarizeAbandonedBranch: Boolean = true): AgentSession {
        val source = session
        val oldLeafId = source.sessionManager.getLeafId()
        val targetId = entryId ?: oldLeafId
        val branched = source.sessionManager.createBranchedSession(targetId)
        try {
            if (summarizeAbandonedBranch && oldLeafId != null && targetId != null && oldLeafId != targetId) {
                appendBranchSummary(branched, source, oldLeafId, targetId)
            }
        } finally {
            source.close()
        }
        val file = branched.sessionFile
        session = createAgentSession(options.copy(sessionFile = file))
        return session
    }

    suspend fun close() {
        session.close()
    }

    private suspend fun appendBranchSummary(
        branched: SessionManager,
        source: AgentSession,
        oldLeafId: String,
        targetId: String,
    ) {
        val collected = collectEntriesForBranchSummary(source.sessionManager, oldLeafId, targetId)
        if (collected.entries.isEmpty()) return
        val model = source.model
        val signal = RunSignal()
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) signal.abort("branch summary cancelled")
        }
        val summarize = dev.pi.coding.compaction.Summarize { request ->
            source.modelRuntime.completeSimple(
                request.model,
                Context(systemPrompt = request.systemPrompt, messages = request.messages),
                SimpleStreamOptions(
                    stream = StreamOptions(cacheRetention = CacheRetention.None, signal = signal),
                ),
                signal,
            )
        }
        val result = try {
            generateBranchSummary(
                collected.entries,
                GenerateBranchSummaryOptions(model = model),
                summarize,
            )
        } finally {
            cancellation?.dispose()
        }
        val summary = result.summary ?: return
        val details = dev.pi.coding.compaction.BranchSummaryDetails(
            readFiles = result.readFiles.orEmpty(),
            modifiedFiles = result.modifiedFiles.orEmpty(),
        ).toJson()
        branched.branch(targetId)
        branched.appendBranchSummary(
            fromId = oldLeafId,
            summary = summary,
            details = details,
            usage = result.usage,
        )
    }
}
