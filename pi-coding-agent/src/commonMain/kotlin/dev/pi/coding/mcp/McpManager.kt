package dev.pi.coding.mcp

import dev.pi.coding.tools.CodingTool
import dev.pi.mcp.McpClient
import dev.pi.mcp.McpClientOptions
import dev.pi.mcp.McpNotificationListener
import dev.pi.mcp.McpTransport
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.protocol.McpLogMessage
import dev.pi.mcp.protocol.McpPromptResult
import dev.pi.mcp.protocol.McpReadResourceResult
import dev.pi.mcp.protocol.McpTool
import dev.pi.mcp.transport.StreamableHttpTransport
import dev.pi.coding.prompt.parseCommandArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class McpManagerCallbacks(
    val toolsAdded: suspend (server: String, tools: List<CodingTool>) -> Unit,
    val toolsRemoved: suspend (server: String, names: Set<String>) -> Unit,
    val statusChanged: suspend (status: McpServerStatus) -> Unit,
    val log: suspend (server: String, level: String, message: String) -> Unit,
    val resourceToolsChanged: suspend (tools: List<CodingTool>) -> Unit,
    val existingToolNames: () -> Set<String>,
)

class McpManager internal constructor(
    private val options: McpOptions,
    private val factory: McpTransportFactory,
    private val scope: CoroutineScope,
    initialServers: Map<String, McpServerConfig>,
    private val callbacks: McpManagerCallbacks,
    private val oauthHttp: dev.pi.ai.http.HttpTransport? = null,
    private val oauthTokenStore: dev.pi.mcp.oauth.McpTokenStore? = null,
    roots: List<dev.pi.mcp.protocol.McpRoot>? = null,
    private val samplingHandler: (suspend (dev.pi.mcp.protocol.McpSamplingRequest) -> dev.pi.mcp.protocol.McpSamplingResult)? = null,
    private val elicitationHandler: (suspend (dev.pi.mcp.protocol.McpElicitationRequest) -> dev.pi.mcp.protocol.McpElicitationResult)? = null,
) {
    private val mutex = Mutex()
    private val connections = LinkedHashMap<String, McpServerConnection>()
    private val statuses = LinkedHashMap<String, McpServerStatus>()
    private val adapterOptions = McpToolAdapterOptions(
        maxOutputBytes = options.maxOutputBytes,
        outputDirectory = options.outputDirectory,
    )
    private val prompts = McpPrompts()
    private var currentRoots: List<dev.pi.mcp.protocol.McpRoot> = roots.orEmpty()
    private val rootsEnabled: Boolean = roots != null
    private val clientHandlers = dev.pi.mcp.protocol.McpClientHandlers(
        listRoots = if (rootsEnabled) {
            { currentRoots }
        } else {
            null
        },
        sample = samplingHandler,
        elicit = elicitationHandler,
    )
    private var started = false
    private var closed = false

    init {
        for ((name, config) in initialServers) {
            connections[name] = newConnection(name, config)
            statuses[name] = initialStatus(name, config)
        }
    }

    val servers: List<McpServerStatus>
        get() = statuses.values.toList()

    fun status(name: String): McpServerStatus? = statuses[name]

    fun hasDeferredTools(): Boolean = connections.values.any { connection ->
        connection.config.enabled &&
            connection.config.exposure != McpExposure.Direct &&
            connection.config.exposure != McpExposure.Hidden
    }

    suspend fun start() {
        mutex.withLock {
            if (started) return
            started = true
        }
        val all = connections.values.filter { it.config.enabled }
        if (all.isNotEmpty()) {
            withTimeoutOrNull(options.connectTimeoutMs) {
                coroutineScope {
                    all.map { connection -> async { connection.connect() } }.awaitAll()
                }
            }
        }
        publishResourceTools()
    }

    private fun newConnection(name: String, config: McpServerConfig): McpServerConnection = McpServerConnection(
        name = name,
        config = config,
        factory = factory,
        options = options,
        adapterOptions = adapterOptions,
        callbacks = callbacks,
        onStatus = { status -> updateStatus(status) },
        onTools = { added, removed -> updateTools(name, added, removed) },
        authProvider = createAuthProvider(name, config),
        handlers = clientHandlers,
    )

    private fun createAuthProvider(name: String, config: McpServerConfig): dev.pi.mcp.oauth.McpAuthProvider? {
        val http = oauthHttp ?: return null
        val store = oauthTokenStore ?: return null
        if (config !is McpServerConfig.Http) return null
        val hasAuthorization = config.headers.keys.any { it.equals("authorization", ignoreCase = true) }
        if (config.oauth == null && hasAuthorization) return null
        val oauth = config.oauth
        return dev.pi.mcp.oauth.McpOAuthProvider(
            options = dev.pi.mcp.oauth.McpOAuthOptions(
                serverName = name,
                serverUrl = config.url,
                scope = oauth?.scope,
                clientId = oauth?.clientId,
                clientSecret = oauth?.clientSecret,
                callbackPort = oauth?.callbackPort,
                callbackUrl = oauth?.callbackUrl,
                clientName = oauth?.clientName ?: "pi2kotlin",
                authorizationServerMetadataUrl = oauth?.authServerMetadataUrl,
            ),
            transport = http,
            tokenStore = store,
            onChallenge = { challenge ->
                if (challenge.error == "insufficient_scope") Unit
            },
        )
    }

    suspend fun signIn(
        server: String,
        interaction: dev.pi.mcp.oauth.McpOAuthInteraction,
        redirectUri: String,
        signal: dev.pi.ai.RunSignal? = null,
    ): McpSignInResult {
        val connection = connections[server]
            ?: return McpSignInResult(server, false, "Unknown MCP server: $server")
        val config = connection.config as? McpServerConfig.Http
            ?: return McpSignInResult(server, false, "MCP server $server is not an HTTP server")
        val http = oauthHttp ?: return McpSignInResult(server, false, "OAuth is not configured")
        val store = oauthTokenStore ?: return McpSignInResult(server, false, "OAuth token store is not configured")
        val oauth = config.oauth
        val tokens = runCatching {
            dev.pi.mcp.oauth.signInMcpServer(
                options = dev.pi.mcp.oauth.McpOAuthOptions(
                    serverName = server,
                    serverUrl = config.url,
                    scope = oauth?.scope,
                    clientId = oauth?.clientId,
                    clientSecret = oauth?.clientSecret,
                    callbackPort = oauth?.callbackPort,
                    callbackUrl = oauth?.callbackUrl,
                    clientName = oauth?.clientName ?: "pi2kotlin",
                    authorizationServerMetadataUrl = oauth?.authServerMetadataUrl,
                ),
                redirectUri = redirectUri,
                interaction = interaction,
                transport = http,
                tokenStore = store,
                signal = signal,
            )
        }.getOrElse { error -> return McpSignInResult(server, false, error.message) }
        if (tokens.accessToken.isEmpty()) return McpSignInResult(server, false, "OAuth server returned an empty access token")
        reconnect(server)
        return McpSignInResult(server, true)
    }

    private fun initialStatus(name: String, config: McpServerConfig): McpServerStatus = McpServerStatus(
        name = name,
        state = if (config.enabled) McpServerState.Connecting else McpServerState.Disabled,
        description = config.description,
        exposure = config.exposure,
    )

    private suspend fun updateStatus(status: McpServerStatus) {
        statuses[status.name] = status
        callbacks.statusChanged(status)
    }

    private suspend fun updateTools(server: String, added: List<CodingTool>, removed: Set<String>) {
        if (added.isNotEmpty()) callbacks.toolsAdded(server, added)
        if (removed.isNotEmpty()) callbacks.toolsRemoved(server, removed)
    }

    private suspend fun publishResourceTools() {
        val clients = connections.values
            .filter { connection ->
                connection.config.enabled &&
                    connection.status.state == McpServerState.Connected &&
                    connection.hasResources
            }
            .mapNotNull { connection -> connection.clientOrNull()?.let { connection.name to it } }
            .toMap()
        callbacks.resourceToolsChanged(
            if (clients.isEmpty()) emptyList() else mcpResourceTools(clients, adapterOptions),
        )
    }

    fun deferredCatalog(): List<McpDeferredTool> = connections.values.flatMap { it.deferredCatalog() }

    suspend fun loadDeferredTools(names: List<String>): List<CodingTool> {
        if (names.isEmpty()) return emptyList()
        for (connection in connections.values) {
            if (connection.config.enabled && connection.status.state == McpServerState.Connecting) connection.connect()
        }
        val wanted = names.toSet()
        val result = mutableListOf<CodingTool>()
        for (connection in connections.values) {
            val tools = connection.adaptDeferred(wanted) ?: continue
            if (tools.tools.isNotEmpty()) {
                callbacks.toolsAdded(connection.name, tools.tools)
                result += tools.tools
            }
        }
        return result
    }

    suspend fun addServer(name: String, config: McpServerConfig): McpServerStatus = mutex.withLock {
        connections.remove(name)?.close()
        statuses.remove(name)
        val connection = newConnection(name, config)
        connections[name] = connection
        val initial = initialStatus(name, config)
        statuses[name] = initial
        if (config.enabled && started) {
            connection.connect()
        }
        publishResourceTools()
        statuses.getValue(name)
    }

    suspend fun removeServer(name: String): McpServerStatus? = mutex.withLock {
        val connection = connections.remove(name) ?: return@withLock null
        val previous = statuses.remove(name)
        connection.close()
        callbacks.toolsRemoved(name, connection.exposedToolNames() + connection.deferredNames())
        publishResourceTools()
        previous?.copy(state = McpServerState.Closed)
    }

    suspend fun setRoots(roots: List<dev.pi.mcp.protocol.McpRoot>) {
        if (!rootsEnabled || currentRoots == roots) return
        currentRoots = roots
        for (connection in connections.values) {
            connection.notifyRootsChanged()
        }
    }

    suspend fun reconnect(name: String): McpServerStatus? {
        val connection = connections[name] ?: return null
        connection.close()
        connection.connect()
        publishResourceTools()
        return statuses[name]
    }

    suspend fun reload(servers: Map<String, McpServerConfig>) {
        val existingNames = connections.keys.toList()
        for (name in existingNames) {
            if (name !in servers) removeServer(name)
        }
        for ((name, config) in servers) {
            val current = connections[name]
            if (current == null) {
                addServer(name, config)
            } else if (current.config != config) {
                removeServer(name)
                addServer(name, config)
            }
        }
        publishResourceTools()
    }

    suspend fun setEnabled(name: String, enabled: Boolean, persist: Boolean = false): McpServerStatus? {
        val connection = connections[name] ?: return null
        if (connection.config.enabled == enabled) return statuses[name]
        val updated = when (val config = connection.config) {
            is McpServerConfig.Http -> config.copy(enabled = enabled)
            is McpServerConfig.Stdio -> config.copy(enabled = enabled)
        }
        if (!enabled) {
            connection.close()
            statuses[name] = (statuses[name] ?: initialStatus(name, updated)).copy(
                state = McpServerState.Disabled,
                toolCount = 0,
            )
            callbacks.toolsRemoved(name, connection.exposedToolNames() + connection.deferredNames())
            publishResourceTools()
            statuses[name]?.let { callbacks.statusChanged(it) }
            return statuses[name]
        }
        return addServer(name, updated)
    }

    suspend fun tools(server: String? = null): List<McpToolInfo> {
        val selected = if (server == null) connections.values.toList() else listOfNotNull(connections[server])
        return selected.flatMap { connection -> connection.toolInfos() }
    }

    suspend fun listPrompts(server: String? = null): List<McpPromptInfo> = prompts.listOperations(
        connections.keys.toList().filter { server == null || it == server },
        operationsProvider = { name -> connections[name]?.clientOrNull()?.promptOperations() },
    )

    suspend fun expandPrompt(server: String, name: String, args: Map<String, String> = emptyMap()): String? {
        val client = connections[server]?.clientOrNull() ?: return null
        return runCatching { prompts.expand(client, name, args) }.getOrNull()
    }

    suspend fun promptResult(server: String, name: String, args: Map<String, String> = emptyMap()): McpPromptResult? {
        val client = connections[server]?.clientOrNull() ?: return null
        return runCatching { client.getPrompt(name, args) }.getOrNull()
    }

    suspend fun readResource(server: String, uri: String): McpReadResourceResult? {
        val client = connections[server]?.clientOrNull() ?: return null
        return runCatching { client.readResource(uri) }.getOrNull()
    }

    suspend fun expandPromptCommand(text: String): String? {
        if (!text.startsWith("/mcp__")) return null
        for ((name, connection) in connections) {
            val prefix = "/mcp__${name}__"
            if (!text.startsWith(prefix)) continue
            val remainder = text.removePrefix(prefix)
            val promptName = remainder.takeWhile { !it.isWhitespace() }
            if (promptName.isEmpty()) return null
            val argsString = remainder.drop(promptName.length).trim()
            val args = parseCommandArgs(argsString)
            val arguments = if (args.isEmpty()) emptyMap() else promptArguments(connection, promptName, args)
            return expandPrompt(name, promptName, arguments)
        }
        return null
    }

    private suspend fun promptArguments(
        connection: McpServerConnection,
        promptName: String,
        args: List<String>,
    ): Map<String, String> {
        val info = listPrompts(connection.name).firstOrNull { it.name == promptName }
        val names = info?.arguments?.map { it.name }.orEmpty()
        return names.mapIndexedNotNull { index, name -> args.getOrNull(index)?.let { name to it } }.toMap()
    }

    suspend fun healthCheck(): List<McpServerHealth> = connections.values.map { connection ->
        val client = connection.clientOrNull()
            ?: return@map McpServerHealth(connection.name, false, error = connection.status.lastError ?: "not connected")
        val startedAt = dev.pi.ai.platform.platformCurrentTimeMillis()
        val ok = runCatching { withTimeoutOrNull(5_000) { client.ping() } }.isSuccess
        val latency = dev.pi.ai.platform.platformCurrentTimeMillis() - startedAt
        McpServerHealth(connection.name, ok, latency.takeIf { ok }, if (ok) null else connection.status.lastError)
    }

    suspend fun close() {
        mutex.withLock {
            if (closed) return
            closed = true
        }
        connections.values.forEach { it.close() }
        connections.clear()
        statuses.clear()
    }
}

private class McpServerConnection(
    val name: String,
    val config: McpServerConfig,
    private val factory: McpTransportFactory,
    private val options: McpOptions,
    private val adapterOptions: McpToolAdapterOptions,
    private val callbacks: McpManagerCallbacks,
    private val onStatus: suspend (McpServerStatus) -> Unit,
    private val onTools: suspend (List<CodingTool>, Set<String>) -> Unit,
    private val authProvider: dev.pi.mcp.oauth.McpAuthProvider? = null,
    private val handlers: dev.pi.mcp.protocol.McpClientHandlers = dev.pi.mcp.protocol.McpClientHandlers(),
) {
    private val mutex = Mutex()
    private var transport: McpTransport? = null
    private var client: McpClient? = null
    private val adapted = LinkedHashMap<String, CodingTool>()
    private val deferred = LinkedHashMap<String, McpDeferredTool>()
    private var rawTools: List<McpTool> = emptyList()
    private var capabilities: dev.pi.mcp.protocol.McpServerCapabilities? = null
    var status: McpServerStatus = McpServerStatus(
        name = name,
        state = if (config.enabled) McpServerState.Connecting else McpServerState.Disabled,
        description = config.description,
        exposure = config.exposure,
    )
        private set
    var connectJob: Job? = null

    val hasResources: Boolean get() = capabilities?.hasResources == true

    fun clientOrNull(): McpClient? = client

    suspend fun notifyRootsChanged() {
        client?.notifyRootsChanged()
    }

    fun exposedToolNames(): Set<String> = adapted.keys

    fun deferredNames(): Set<String> = deferred.keys

    fun deferredCatalog(): List<McpDeferredTool> = deferred.values.toList()

    fun toolInfos(): List<McpToolInfo> = rawTools.map { tool ->
        val exposedName = adapted.entries.firstOrNull { it.value.label == "$name/${tool.name}" }?.key
            ?: deferred.entries.firstOrNull { it.value.name == mcpToolExposedName(name, tool.name) }?.key
            ?: mcpToolExposedName(name, tool.name)
        McpToolInfo(
            server = name,
            name = tool.name,
            exposedName = exposedName,
            description = tool.description,
            title = tool.title,
            annotations = tool.annotations,
            exposure = config.exposureFor(tool.name),
            parameters = tool.inputSchema,
        )
    }

    data class AdaptedDeferred(val tools: List<CodingTool>)

    fun adaptDeferred(names: Set<String>): AdaptedDeferred? {
        val matching = deferred.filterKeys { it in names }
        if (matching.isEmpty()) return null
        val client = client ?: return null
        val adapter = McpToolAdapter(name, client, adapterOptions)
        val existing = callbacks.existingToolNames().toMutableSet()
        val tools = matching.map { (exposedName, deferredTool) ->
            val raw = rawTools.firstOrNull { tool -> mcpToolExposedName(name, tool.name) == exposedName }
                ?: return AdaptedDeferred(emptyList())
            adapter.adapt(raw, existing).also { coded ->
                existing += coded.name
                adapted[coded.name] = coded
                deferred.remove(exposedName)
            }
        }
        return AdaptedDeferred(tools)
    }

    suspend fun connect() = mutex.withLock {
        if (status.state == McpServerState.Connected) return@withLock
        if (!config.enabled) {
            update(status.copy(state = McpServerState.Disabled))
            return@withLock
        }
        update(status.copy(state = McpServerState.Connecting, lastError = null))
        val newTransport = try {
            factory.create(name, config, authProvider)
        } catch (error: dev.pi.mcp.McpTransportUnsupportedException) {
            update(status.copy(state = McpServerState.Unsupported, lastError = error.message))
            return@withLock
        } catch (error: Exception) {
            update(status.copy(state = McpServerState.Failed, lastError = error.message))
            return@withLock
        }
        try {
            val newClient = McpClient(
                name,
                newTransport,
                McpClientOptions(
                    requestTimeoutMs = config.timeoutSeconds * 1_000,
                    handlers = handlers,
                ),
            )
            val initialized = newClient.initialize()
            (newTransport as? StreamableHttpTransport)?.let { http ->
                http.setProtocolVersion(initialized.protocolVersion)
            }
            newClient.setNotificationListener(
                McpNotificationListener(
                    onToolsChanged = { refresh(newClient) },
                    onLog = { message -> callbacks.log(name, message.level, renderLog(message)) },
                ),
            )
            transport = newTransport
            client = newClient
            capabilities = initialized.capabilities
            rawTools = newClient.listTools()
            publishTools()
            update(
                status.copy(
                    state = McpServerState.Connected,
                    toolCount = rawTools.size,
                    serverInfo = initialized.serverInfo,
                    description = config.description ?: initialized.instructions,
                    instructions = initialized.instructions,
                    lastError = null,
                ),
            )
        } catch (error: dev.pi.mcp.McpAuthRequiredException) {
            transport = newTransport
            update(status.copy(state = McpServerState.AuthRequired, lastError = error.message))
            runCatching { newTransport.close() }
            transport = null
        } catch (error: Exception) {
            runCatching { newTransport.close() }
            transport = null
            update(
                status.copy(
                    state = McpServerState.Failed,
                    lastError = buildString {
                        append(error::class.simpleName ?: "error")
                        error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    },
                ),
            )
        }
    }

    private suspend fun refresh(activeClient: McpClient) {
        if (client !== activeClient) return
        val fresh = runCatching { activeClient.listTools() }.getOrNull() ?: return
        rawTools = fresh
        publishTools()
        update(status.copy(toolCount = rawTools.size))
    }

    private suspend fun publishTools() {
        val previousExposed = adapted.keys.toSet() + deferred.keys
        adapted.clear()
        deferred.clear()
        val client = client ?: return
        val adapter = McpToolAdapter(name, client, adapterOptions)
        val existing = callbacks.existingToolNames().toMutableSet()
        for (tool in rawTools) {
            when (val exposure = config.exposureFor(tool.name)) {
                McpExposure.Direct -> {
                    val coded = adapter.adapt(tool, existing)
                    existing += coded.name
                    adapted[coded.name] = coded
                }

                McpExposure.Deferred, McpExposure.Codemode -> {
                    val exposedName = mcpToolExposedName(name, tool.name)
                    deferred[exposedName] = McpDeferredTool(
                        name = exposedName,
                        description = tool.description,
                        server = name,
                        parameters = tool.inputSchema,
                    )
                }

                McpExposure.Hidden -> Unit
            }
        }
        val current = adapted.keys.toSet() + deferred.keys
        val removed = previousExposed - current
        onTools(adapted.values.toList(), removed)
    }

    suspend fun close() {
        runCatching { client?.close() }
        runCatching { transport?.close() }
        client = null
        transport = null
        capabilities = null
        update(status.copy(state = McpServerState.Closed, toolCount = 0))
    }

    private suspend fun update(newStatus: McpServerStatus) {
        status = newStatus
        onStatus(newStatus)
    }
}

private fun McpServerConfig.exposureFor(toolName: String): McpExposure {
    val overrides = toolExposure
    overrides[toolName]?.let { return it }
    for ((pattern, exposure) in overrides) {
        if (pattern.contains("*") && globMatches(pattern, toolName)) return exposure
    }
    return exposure
}

private fun globMatches(pattern: String, value: String): Boolean {
    val regex = buildString {
        append('^')
        for (char in pattern) {
            when (char) {
                '*' -> append(".*")
                '?', '.', '+', '(', ')', '[', ']', '{', '}', '^', '$', '\\', '|' -> append('\\').append(char)
                else -> append(char)
            }
        }
        append('$')
    }
    return Regex(regex).matches(value)
}

private fun mcpToolExposedName(server: String, tool: String): String =
    "mcp__${server}__${tool}".replace(Regex("[^A-Za-z0-9_]"), "_")

private fun renderLog(message: McpLogMessage): String {
    val data = message.data?.let { value ->
        when (value) {
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            else -> value.toString()
        }
    }
    return data ?: message.logger ?: message.level
}
