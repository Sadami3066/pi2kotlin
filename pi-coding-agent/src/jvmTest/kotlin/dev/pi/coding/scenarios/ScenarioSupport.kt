package dev.pi.coding.scenarios

import dev.pi.ai.Provider
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.FauxModelDefinition
import dev.pi.ai.providers.FauxProviderHandle
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxProvider
import dev.pi.ai.auth.CredentialStore
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.CodingHooks
import dev.pi.coding.session.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

internal object ScenarioSupport {
    const val CWD: String = "/work"
    const val AGENT_DIR: String = "/agent"

    fun fileSystem(): FakeFileSystem {
        val fs = FakeFileSystem()
        fs.createDirectories(CWD.toPath())
        fs.createDirectories(AGENT_DIR.toPath())
        return fs
    }

    fun write(fs: FileSystem, path: String, text: String) {
        val target = path.toPath()
        target.parent?.let { parent ->
            if (!fs.exists(target)) fs.createDirectories(parent)
        }
        fs.write(target) { writeUtf8(text) }
    }

    fun writeSettings(fs: FileSystem, json: String) = write(fs, "$AGENT_DIR/settings.json", json)

    fun read(fs: FileSystem, path: Path): String = fs.read(path) { readUtf8() }

    fun faux(
        provider: String = "faux",
        contextWindow: Long = 128_000,
        maxTokens: Long = 16_384,
        tokenSize: FauxTokenSize = FauxTokenSize(1, 1),
        tokensPerSecond: Double? = null,
    ): FauxProviderHandle = fauxProvider(
        FauxProviderOptions(
            provider = provider,
            tokenSize = tokenSize,
            tokensPerSecond = tokensPerSecond,
            models = listOf(
                FauxModelDefinition(id = "faux-1", contextWindow = contextWindow, maxTokens = maxTokens),
            ),
        ),
    )

    fun options(
        fs: FileSystem,
        providers: List<Provider>,
        model: String = "faux/faux-1",
        thinkingLevel: String? = null,
        sessionFile: Path? = null,
        transport: HttpTransport = NoNetworkTransport,
        extraSystemPromptSections: Map<String, String> = emptyMap(),
        initialSystemPromptSections: Map<String, String> = emptyMap(),
        projectTrusted: Boolean? = null,
        hooks: CodingHooks = CodingHooks(),
        credentials: CredentialStore? = null,
    ): AgentSessionOptions = AgentSessionOptions(
        cwd = CWD.toPath(),
        agentDir = AGENT_DIR.toPath(),
        transport = transport,
        fs = fs,
        model = model,
        thinkingLevel = thinkingLevel,
        sessionFile = sessionFile,
        providers = providers,
        extraSystemPromptSections = extraSystemPromptSections,
        initialSystemPromptSections = initialSystemPromptSections,
        projectTrusted = projectTrusted,
        hooks = hooks,
        credentials = credentials,
    )

    suspend fun create(
        fs: FileSystem,
        providers: List<Provider>,
        model: String = "faux/faux-1",
        thinkingLevel: String? = null,
        sessionFile: Path? = null,
        extraSystemPromptSections: Map<String, String> = emptyMap(),
        initialSystemPromptSections: Map<String, String> = emptyMap(),
        projectTrusted: Boolean? = null,
        hooks: CodingHooks = CodingHooks(),
        transport: HttpTransport = NoNetworkTransport,
        credentials: CredentialStore? = null,
    ): AgentSession = createAgentSession(
        options(
            fs = fs,
            providers = providers,
            model = model,
            thinkingLevel = thinkingLevel,
            sessionFile = sessionFile,
            transport = transport,
            extraSystemPromptSections = extraSystemPromptSections,
            initialSystemPromptSections = initialSystemPromptSections,
            projectTrusted = projectTrusted,
            hooks = hooks,
            credentials = credentials,
        ),
    )
}

internal object NoNetworkTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        throw IllegalStateException("network is disabled in scenario tests")
}

internal class SessionEventBuffer(
    session: AgentSession,
    scope: CoroutineScope,
) {
    private val channel = Channel<SessionEvent>(Channel.UNLIMITED)
    private val job = scope.launch(Dispatchers.Default) {
        session.events.collect { event -> channel.send(event) }
    }

    suspend fun next(timeoutMs: Long = 15_000): SessionEvent = withTimeout(timeoutMs) { channel.receive() }

    suspend fun await(timeoutMs: Long = 15_000, predicate: (SessionEvent) -> Boolean): SessionEvent =
        withTimeout(timeoutMs) {
            while (true) {
                val event = channel.receive()
                if (predicate(event)) return@withTimeout event
            }
            error("unreachable")
        }

    suspend fun collectUntil(timeoutMs: Long = 20_000, terminate: (SessionEvent) -> Boolean): List<SessionEvent> =
        withTimeout(timeoutMs) {
            val collected = mutableListOf<SessionEvent>()
            while (true) {
                val event = channel.receive()
                collected.add(event)
                if (terminate(event)) break
            }
            collected
        }

    suspend fun drain(idleTimeoutMs: Long = 1_000): List<SessionEvent> {
        val collected = mutableListOf<SessionEvent>()
        while (true) {
            val event = kotlinx.coroutines.withTimeoutOrNull(idleTimeoutMs) { channel.receive() } ?: break
            collected.add(event)
        }
        return collected
    }

    fun close() {
        job.cancel()
        channel.close()
    }
}

internal fun agentEventTypes(events: List<SessionEvent>): List<String> = events
    .filterIsInstance<SessionEvent.AgentEventReceived>()
    .map { it.event.type }
