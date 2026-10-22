package dev.pi.coding

import dev.pi.agent.Agent
import dev.pi.agent.AgentInitialState
import dev.pi.agent.AgentOptions
import dev.pi.agent.StreamFn
import dev.pi.agent.asAgentMessage
import dev.pi.agent.asLlmMessage
import dev.pi.ai.Context
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.auth.CredentialStore
import dev.pi.ai.http.HttpTransport
import dev.pi.coding.models.CustomModelsLoader
import dev.pi.coding.models.ModelResolver
import dev.pi.coding.models.ModelRuntime
import dev.pi.coding.models.ProviderComposer
import dev.pi.coding.paths.AgentPaths
import dev.pi.coding.prompt.ResourceLoader
import dev.pi.coding.prompt.SystemPromptInput
import dev.pi.coding.prompt.SystemPromptSections
import dev.pi.coding.prompt.buildSystemPrompt
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.CodingHooks
import dev.pi.coding.session.SessionManager
import dev.pi.coding.settings.SettingsManager
import dev.pi.coding.tools.CodingTool
import dev.pi.coding.tools.FileMutationQueue
import dev.pi.coding.tools.ShellExecutor
import dev.pi.coding.tools.defaultCodingTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
)

suspend fun createAgentSession(options: AgentSessionOptions): AgentSession {
    val paths = AgentPaths(options.cwd, options.agentDir)
    val settings = SettingsManager.forPaths(paths, options.fs)
    settings.reload()
    val credentialStore = options.credentials ?: CredentialStore(options.agentDir, options.fs)

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

    val resolvedFromSession = sessionContext.provider?.let { provider ->
        sessionContext.modelId?.let { modelId -> modelRuntime.getModel(provider, modelId) }
    }
    val model = resolvedFromSession
        ?: options.model?.let { resolver.resolve(it).model }
        ?: resolver.resolveDefault()?.model
        ?: throw IllegalStateException(
            "No model configured. Pass AgentSessionOptions.model or set settings defaultProvider/defaultModel.",
        )
    val thinkingLevel = options.thinkingLevel
        ?: sessionContext.thinkingLevel
        ?: settings.getDefaultThinkingLevel()
        ?: "off"

    val resources = ResourceLoader(paths, options.fs, settings).reload()
    val tools = options.tools ?: defaultCodingTools(
        cwd = options.cwd,
        fs = options.fs,
        mutationQueue = FileMutationQueue(),
        shellExecutor = options.shellExecutor,
    )

    val extraSections = options.extraSystemPromptSections
    val builtSections = buildSystemPrompt(
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
    val sections = if (extraSections.isEmpty()) {
        builtSections
    } else {
        SystemPromptSections(builtSections.sections + extraSections)
    }

    val restored = sessionContext.messages.map { it.asAgentMessage() }.toMutableList()
    val agentTools = tools.map { it.asAgentTool() }
    if (restored.firstOrNull()?.asLlmMessage() !is SystemMessage) {
        restored.add(
            0,
            SystemMessage(
                content = PlainTextContent(""),
                sections = sections.sections,
                toolsAdded = agentTools.map { it.toTool() },
                timestamp = 0,
            ).asAgentMessage(),
        )
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val streamFn: StreamFn = { requestedModel, transcript, streamOptions ->
        modelRuntime.streamSimple(
            model = requestedModel,
            context = Context(messages = transcript.messages),
            options = streamOptions,
            signal = streamOptions?.stream?.signal,
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
        ),
    )

    if (sessionManager.getEntries().isEmpty()) {
        sessionManager.appendModelChange(model.provider, model.id)
        sessionManager.appendThinkingLevelChange(thinkingLevel)
    }

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
        initialSections = sections,
        scope = scope,
    )
    session.start()
    options.hooks.onSessionStart?.invoke(session)
    return session
}

class AgentSessionRuntime private constructor(
    private val options: AgentSessionOptions,
    initial: AgentSession,
) : AutoCloseable {
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

    suspend fun fork(entryId: String? = null): AgentSession {
        val branched = session.sessionManager.createBranchedSession(entryId)
        val file = branched.sessionFile
        session.close()
        session = createAgentSession(options.copy(sessionFile = file))
        return session
    }

    override fun close() {
        session.abort()
    }
}
