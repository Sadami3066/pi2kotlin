package dev.pi.mcp

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.mcp.protocol.JsonRpcErrorResponse
import dev.pi.mcp.protocol.JsonRpcId
import dev.pi.mcp.protocol.JsonRpcMessage
import dev.pi.mcp.protocol.JsonRpcNotification
import dev.pi.mcp.protocol.JsonRpcRequest
import dev.pi.mcp.protocol.JsonRpcResponse
import dev.pi.mcp.protocol.LATEST_PROTOCOL_VERSION
import dev.pi.mcp.protocol.McpCallResult
import dev.pi.mcp.protocol.McpClientHandlers
import dev.pi.mcp.protocol.McpElicitationRequest
import dev.pi.mcp.protocol.McpElicitationResult
import dev.pi.mcp.protocol.McpSamplingRequest
import dev.pi.mcp.protocol.McpSamplingResult
import dev.pi.mcp.protocol.McpImplementation
import dev.pi.mcp.protocol.McpInitializeResult
import dev.pi.mcp.protocol.McpLogMessage
import dev.pi.mcp.protocol.McpPrompt
import dev.pi.mcp.protocol.McpPromptResult
import dev.pi.mcp.protocol.McpPromptsPage
import dev.pi.mcp.protocol.McpReadResourceResult
import dev.pi.mcp.protocol.McpResource
import dev.pi.mcp.protocol.McpResourceTemplate
import dev.pi.mcp.protocol.McpResourceTemplatesPage
import dev.pi.mcp.protocol.McpResourcesPage
import dev.pi.mcp.protocol.McpServerCapabilities
import dev.pi.mcp.protocol.McpTool
import dev.pi.mcp.protocol.McpToolsPage
import dev.pi.mcp.protocol.SUPPORTED_PROTOCOL_VERSIONS
import dev.pi.mcp.protocol.stringOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject as SerialJsonObject

data class McpImplementationInfo(
    val name: String = "pi2kotlin",
    val version: String = "0.2.0",
    val title: String? = null,
)

data class McpClientOptions(
    val clientInfo: McpImplementationInfo = McpImplementationInfo(),
    val protocolVersion: String? = null,
    val requestTimeoutMs: Long = 60_000,
    val capabilities: SerialJsonObject = SerialJsonObject(emptyMap()),
    val handlers: McpClientHandlers = McpClientHandlers(),
)

data class McpRequestOptions(
    val signal: RunSignal? = null,
    val timeoutMs: Long? = null,
    val onProgress: ((progressToken: kotlinx.serialization.json.JsonElement?, progress: Double, total: Double?, message: String?) -> Unit)? = null,
)

data class McpNotificationListener(
    val onToolsChanged: (suspend () -> Unit)? = null,
    val onResourcesChanged: (suspend () -> Unit)? = null,
    val onLog: (suspend (McpLogMessage) -> Unit)? = null,
)

private const val JSON_RPC_METHOD_NOT_FOUND = -32601
private const val JSON_RPC_INVALID_PARAMS = -32602
private const val JSON_RPC_INTERNAL_ERROR = -32603
private const val MAX_LIST_PAGES = 1_000

class McpClient(
    val serverName: String,
    private val transport: McpTransport,
    private val options: McpClientOptions = McpClientOptions(),
) {
    private enum class State { Idle, Connecting, Connected, Closed }

    private class PendingRequest(
        val deferred: CompletableDeferred<JsonElement>,
        val timeoutMs: Long,
        val onProgress: ((JsonElement?, Double, Double?, String?) -> Unit)?,
        val progressToken: JsonRpcId?,
        val signal: RunSignal?,
        var timeoutJob: Job? = null,
        var abortUnsubscribe: (() -> Unit)? = null,
    )

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = mutableMapOf<JsonRpcId, PendingRequest>()
    private val progressRequests = mutableMapOf<JsonRpcId, JsonRpcId>()
    private var nextRequestId: Long = 1

    @kotlin.concurrent.Volatile
    private var state: State = State.Idle
    private var collectorJob: Job? = null

    @kotlin.concurrent.Volatile
    private var notificationListener: McpNotificationListener? = null

    var serverInfo: McpImplementation? = null
        private set

    var serverCapabilities: McpServerCapabilities? = null
        private set

    var instructions: String? = null
        private set

    var protocolVersion: String? = null
        private set

    val isInitialized: Boolean get() = state == State.Connected

    suspend fun initialize(): McpInitializeResult {
        mutex.withLock {
            if (state != State.Idle) throw McpException("MCP client is already initialized")
            state = State.Connecting
        }
        try {
            transport.start()
            startEventCollector()
            val requestedVersion = options.protocolVersion ?: LATEST_PROTOCOL_VERSION
            if (requestedVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
                throw McpProtocolException("Unsupported MCP protocol version: $requestedVersion")
            }
            val params = buildJsonObject {
                put("protocolVersion", requestedVersion)
                put("capabilities", mergedCapabilities())
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", options.clientInfo.name)
                        put("version", options.clientInfo.version)
                        options.clientInfo.title?.let { put("title", it) }
                    },
                )
            }
            val resultElement = request("initialize", params, McpRequestOptions(), allowConnecting = true)
            val result = try {
                McpInitializeResult.fromJson(resultElement)
            } catch (error: IllegalArgumentException) {
                throw McpProtocolException("Invalid MCP initialize result: ${error.message}", error)
            }
            if (result.protocolVersion !in SUPPORTED_PROTOCOL_VERSIONS) {
                throw McpProtocolException("MCP server selected unsupported protocol version: ${result.protocolVersion}")
            }
            serverInfo = result.serverInfo
            serverCapabilities = result.capabilities
            instructions = result.instructions
            protocolVersion = result.protocolVersion
            transport.send(JsonRpcNotification("notifications/initialized"))
            mutex.withLock { state = State.Connected }
            return result
        } catch (error: Throwable) {
            runCatching { close() }
            throw error
        }
    }

    suspend fun listTools(options: McpRequestOptions = McpRequestOptions()): List<McpTool> {
        requireConnected()
        val tools = mutableListOf<McpTool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        while (true) {
            val page = listToolsPage(cursor, options)
            tools.addAll(page.tools)
            pageCount += 1
            val nextCursor = page.nextCursor ?: return tools
            if (!seenCursors.add(nextCursor)) {
                throw McpProtocolException("MCP tools/list returned duplicate cursor: $nextCursor")
            }
            if (pageCount >= MAX_LIST_PAGES) {
                throw McpProtocolException("MCP tools/list exceeded $MAX_LIST_PAGES pages")
            }
            cursor = nextCursor
        }
    }

    suspend fun listToolsPage(cursor: String?, options: McpRequestOptions = McpRequestOptions()): McpToolsPage {
        requireConnected()
        val result = request("tools/list", cursorParams(cursor), options)
        val page = parsePage(result, "tools/list", "tools") { McpTool.fromJson(it) }
        return McpToolsPage(page.first, page.second)
    }

    suspend fun callTool(
        name: String,
        arguments: SerialJsonObject,
        options: McpRequestOptions = McpRequestOptions(),
    ): McpCallResult {
        requireConnected()
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val result = request("tools/call", params, options)
        val objectResult = result as? JsonObject ?: JsonObject(emptyMap())
        val content = objectResult["content"]
        if (content != null && content !is JsonNull && content !is JsonArray) {
            throw McpProtocolException("Invalid MCP tools/call result: content must be an array")
        }
        if (content is JsonArray) {
            for (element in content) {
                if (element !is JsonObject) {
                    throw McpProtocolException("Invalid MCP tools/call result: content entries must be objects")
                }
            }
        }
        val isError = objectResult["isError"]
        if (isError != null && isError !is JsonNull) {
            (isError as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull
                ?.toBooleanStrictOrNull()
                ?: throw McpProtocolException("Invalid MCP tools/call result: isError must be a boolean")
        }
        return McpCallResult.fromJson(objectResult)
    }

    suspend fun listResources(
        cursor: String? = null,
        options: McpRequestOptions = McpRequestOptions(),
    ): McpResourcesPage {
        requireConnected()
        val result = request("resources/list", cursorParams(cursor), options)
        val page = parsePage(result, "resources/list", "resources") { McpResource.fromJson(it) }
        return McpResourcesPage(page.first, page.second)
    }

    suspend fun listResourceTemplates(
        cursor: String? = null,
        options: McpRequestOptions = McpRequestOptions(),
    ): McpResourceTemplatesPage {
        requireConnected()
        val result = request("resources/templates/list", cursorParams(cursor), options)
        val page = parsePage(result, "resources/templates/list", "resourceTemplates") {
            McpResourceTemplate.fromJson(it)
        }
        return McpResourceTemplatesPage(page.first, page.second)
    }

    suspend fun readResource(uri: String, options: McpRequestOptions = McpRequestOptions()): McpReadResourceResult {
        requireConnected()
        val result = request("resources/read", buildJsonObject { put("uri", uri) }, options)
        val objectResult = result as? JsonObject
            ?: throw McpProtocolException("Invalid MCP resources/read result")
        if (objectResult["contents"] !is JsonArray) {
            throw McpProtocolException("Invalid MCP resources/read result")
        }
        return McpReadResourceResult.fromJson(objectResult)
    }

    suspend fun listPrompts(cursor: String? = null, options: McpRequestOptions = McpRequestOptions()): McpPromptsPage {
        requireConnected()
        val result = request("prompts/list", cursorParams(cursor), options)
        val page = parsePage(result, "prompts/list", "prompts") { McpPrompt.fromJson(it) }
        return McpPromptsPage(page.first, page.second)
    }

    suspend fun getPrompt(
        name: String,
        arguments: Map<String, String> = emptyMap(),
        options: McpRequestOptions = McpRequestOptions(),
    ): McpPromptResult {
        requireConnected()
        val params = buildJsonObject {
            put("name", name)
            if (arguments.isNotEmpty()) {
                put(
                    "arguments",
                    buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } },
                )
            }
        }
        val result = request("prompts/get", params, options)
        return McpPromptResult.fromJson(result)
    }

    suspend fun ping(options: McpRequestOptions = McpRequestOptions()) {
        requireConnected()
        request("ping", null, options)
    }

    fun setNotificationListener(listener: McpNotificationListener?) {
        notificationListener = listener
    }

    suspend fun notifyRootsChanged() {
        if (options.handlers.listRoots == null) return
        requireConnected()
        transport.send(JsonRpcNotification("notifications/roots/list_changed"))
    }

    suspend fun close() {
        val alreadyClosed = mutex.withLock {
            if (state == State.Closed) {
                true
            } else {
                state = State.Closed
                false
            }
        }
        if (alreadyClosed) return
        rejectAllPending(McpConnectionClosedException("MCP client closed"))
        collectorJob?.cancel()
        collectorJob = null
        runCatching { transport.close() }
        scope.cancel()
    }

    private suspend fun request(
        method: String,
        params: JsonObject?,
        options: McpRequestOptions,
        allowConnecting: Boolean = false,
    ): JsonElement {
        val signal = options.signal
        signal?.throwIfAborted()
        requireTransport(allowConnecting)
        val id = mutex.withLock {
            val current: JsonRpcId = JsonRpcId.Number(nextRequestId)
            nextRequestId += 1
            current
        }
        val progressToken: JsonRpcId? = if (options.onProgress != null) id else null
        val requestParams = withProgressToken(params, progressToken)
        val timeoutMs = options.timeoutMs ?: this.options.requestTimeoutMs
        val deferred = CompletableDeferred<JsonElement>()
        val entry = PendingRequest(deferred, timeoutMs, options.onProgress, progressToken, signal)
        mutex.withLock {
            pending[id] = entry
            progressToken?.let { progressRequests[it] = id }
        }
        entry.abortUnsubscribe = signal?.onAbort {
            scope.launch {
                cancelPending(
                    id,
                    RequestAbortedException(signal.reason),
                    notifyServer = method != "initialize",
                    reason = signal.reason ?: "Aborted",
                )
            }
        }
        armTimeout(id, entry)
        if (signal?.isAborted == true) {
            cancelPending(id, RequestAbortedException(signal.reason), method != "initialize", "Aborted")
            throw RequestAbortedException(signal.reason)
        }
        try {
            transport.send(JsonRpcRequest(id, method, requestParams), signal)
        } catch (error: Throwable) {
            cancelPending(id, error, notifyServer = false, reason = null)
        }
        return deferred.await()
    }

    private fun withProgressToken(params: JsonObject?, progressToken: JsonRpcId?): JsonObject? {
        if (progressToken == null) return params
        val meta = LinkedHashMap<String, JsonElement>()
        val existingMeta = params?.get("_meta") as? JsonObject
        existingMeta?.forEach { (key, value) -> meta[key] = value }
        meta["progressToken"] = idElement(progressToken)
        val merged = LinkedHashMap<String, JsonElement>()
        params?.forEach { (key, value) -> if (key != "_meta") merged[key] = value }
        merged["_meta"] = JsonObject(meta)
        return JsonObject(merged)
    }

    private fun armTimeout(id: JsonRpcId, entry: PendingRequest) {
        entry.timeoutJob?.cancel()
        val timeoutMs = entry.timeoutMs
        if (timeoutMs <= 0) return
        entry.timeoutJob = scope.launch {
            delay(timeoutMs)
            cancelPending(
                id,
                McpTimeoutException("MCP request timed out after ${timeoutMs}ms"),
                notifyServer = true,
                reason = "Request timed out",
            )
        }
    }

    private suspend fun cancelPending(
        id: JsonRpcId,
        error: Throwable,
        notifyServer: Boolean,
        reason: String?,
    ) {
        val entry = mutex.withLock {
            val pendingEntry = pending[id]
            if (pendingEntry != null) removePendingLocked(id, pendingEntry)
            pendingEntry
        } ?: return
        entry.deferred.completeExceptionally(error)
        if (notifyServer) sendCancelledNotification(id, reason)
    }

    private fun sendCancelledNotification(id: JsonRpcId, reason: String?) {
        scope.launch {
            runCatching {
                transport.send(
                    JsonRpcNotification(
                        "notifications/cancelled",
                        buildJsonObject {
                            put("requestId", idElement(id))
                            reason?.let { put("reason", it) }
                        },
                    ),
                )
            }
        }
    }

    private fun removePendingLocked(id: JsonRpcId, entry: PendingRequest) {
        pending.remove(id)
        entry.timeoutJob?.cancel()
        entry.timeoutJob = null
        entry.progressToken?.let { progressRequests.remove(it) }
        entry.abortUnsubscribe?.invoke()
        entry.abortUnsubscribe = null
    }

    private suspend fun rejectAllPending(error: Throwable) {
        val entries = mutex.withLock {
            val snapshot = pending.values.toList()
            for ((id, entry) in pending.toList()) removePendingLocked(id, entry)
            snapshot
        }
        for (entry in entries) entry.deferred.completeExceptionally(error)
    }

    private fun startEventCollector() {
        collectorJob = scope.launch {
            try {
                transport.events.collect { event ->
                    when (event) {
                        is McpTransportEvent.Message -> handleMessage(event.message)
                        is McpTransportEvent.Closed -> handleTransportClosed(event.error)
                    }
                }
                handleTransportClosed(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                handleTransportClosed(error)
            }
        }
    }

    private suspend fun handleMessage(message: JsonRpcMessage) {
        when (message) {
            is JsonRpcResponse -> {
                val entry = mutex.withLock {
                    val current = pending[message.id]
                    if (current != null) removePendingLocked(message.id, current)
                    current
                } ?: return
                entry.deferred.complete(message.result ?: JsonObject(emptyMap()))
            }

            is JsonRpcErrorResponse -> {
                val entry = mutex.withLock {
                    val current = pending[message.id]
                    if (current != null) removePendingLocked(message.id, current)
                    current
                } ?: return
                entry.deferred.completeExceptionally(McpRpcException(message.code, message.message, message.data))
            }

            is JsonRpcNotification -> handleNotification(message.method, message.params)
            is JsonRpcRequest -> handleServerRequest(message)
        }
    }

    private suspend fun handleServerRequest(message: JsonRpcRequest) {
        val response = try {
            when (message.method) {
                "ping" -> JsonRpcResponse(message.id, JsonObject(emptyMap()))
                "roots/list" -> handleListRoots(message)
                "sampling/createMessage" -> handleSamplingCreateMessage(message)
                "elicitation/create" -> handleElicitationCreate(message)
                else -> serverMethodNotFound(message)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            JsonRpcErrorResponse(
                message.id,
                JSON_RPC_INTERNAL_ERROR,
                "Internal error: ${error.message ?: error.toString()}",
            )
        }
        runCatching { transport.send(response) }
    }

    private fun serverMethodNotFound(message: JsonRpcRequest): JsonRpcMessage =
        JsonRpcErrorResponse(message.id, JSON_RPC_METHOD_NOT_FOUND, "Method not found: ${message.method}")

    private suspend fun handleListRoots(message: JsonRpcRequest): JsonRpcMessage {
        val handler = options.handlers.listRoots ?: return serverMethodNotFound(message)
        val roots = try {
            handler()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return JsonRpcErrorResponse(
                message.id,
                JSON_RPC_INTERNAL_ERROR,
                "Internal error: ${error.message ?: error.toString()}",
            )
        }
        val encoded = roots.map { root ->
            buildJsonObject {
                put("uri", root.uri)
                root.name?.let { put("name", it) }
            }
        }
        return JsonRpcResponse(message.id, buildJsonObject { put("roots", JsonArray(encoded)) })
    }

    private suspend fun handleSamplingCreateMessage(message: JsonRpcRequest): JsonRpcMessage {
        val handler = options.handlers.sample ?: return serverMethodNotFound(message)
        val request = try {
            parseSamplingRequest(message.params)
        } catch (error: McpProtocolException) {
            return JsonRpcErrorResponse(message.id, JSON_RPC_INVALID_PARAMS, error.message ?: "Invalid params")
        }
        val result = try {
            handler(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return JsonRpcErrorResponse(
                message.id,
                JSON_RPC_INTERNAL_ERROR,
                "Internal error: ${error.message ?: error.toString()}",
            )
        }
        return JsonRpcResponse(message.id, encodeSamplingResult(result))
    }

    private suspend fun handleElicitationCreate(message: JsonRpcRequest): JsonRpcMessage {
        val handler = options.handlers.elicit ?: return serverMethodNotFound(message)
        val request = try {
            parseElicitationRequest(message.params)
        } catch (error: McpProtocolException) {
            return JsonRpcErrorResponse(message.id, JSON_RPC_INVALID_PARAMS, error.message ?: "Invalid params")
        }
        val result = try {
            handler(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return JsonRpcErrorResponse(
                message.id,
                JSON_RPC_INTERNAL_ERROR,
                "Internal error: ${error.message ?: error.toString()}",
            )
        }
        return JsonRpcResponse(message.id, encodeElicitationResult(result))
    }

    private fun parseSamplingRequest(params: JsonObject?): McpSamplingRequest {
        val raw = params ?: throw McpProtocolException("Invalid MCP sampling/createMessage params: missing params")
        val messagesElement = raw["messages"]
        if (messagesElement !is JsonArray || messagesElement.isEmpty()) {
            throw McpProtocolException(
                "Invalid MCP sampling/createMessage params: messages must be a non-empty array",
            )
        }
        val messages = messagesElement.map { element ->
            element as? JsonObject
                ?: throw McpProtocolException("Invalid MCP sampling/createMessage params: messages entries must be objects")
        }
        val maxTokensElement = raw["maxTokens"]
        val maxTokens = when {
            maxTokensElement == null || maxTokensElement is JsonNull -> 4096L
            else -> (maxTokensElement as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
                ?: throw McpProtocolException("Invalid MCP sampling/createMessage params: maxTokens must be an integer")
        }
        val temperatureElement = raw["temperature"]
        val temperature = when {
            temperatureElement == null || temperatureElement is JsonNull -> null
            else -> (temperatureElement as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: throw McpProtocolException(
                    "Invalid MCP sampling/createMessage params: temperature must be a number",
                )
        }
        val stopSequencesElement = raw["stopSequences"]
        val stopSequences = when {
            stopSequencesElement == null || stopSequencesElement is JsonNull -> emptyList()
            stopSequencesElement is JsonArray -> stopSequencesElement.map { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw McpProtocolException(
                        "Invalid MCP sampling/createMessage params: stopSequences entries must be strings",
                    )
            }

            else -> throw McpProtocolException(
                "Invalid MCP sampling/createMessage params: stopSequences must be an array",
            )
        }
        val modelPreferencesElement = raw["modelPreferences"]
        val modelPreferences = when {
            modelPreferencesElement == null || modelPreferencesElement is JsonNull -> null
            modelPreferencesElement is JsonObject -> modelPreferencesElement
            else -> throw McpProtocolException(
                "Invalid MCP sampling/createMessage params: modelPreferences must be an object",
            )
        }
        return McpSamplingRequest(
            messages = messages,
            systemPrompt = raw.stringOrNull("systemPrompt"),
            maxTokens = maxTokens,
            temperature = temperature,
            stopSequences = stopSequences,
            modelPreferences = modelPreferences,
            raw = raw,
        )
    }

    private fun encodeSamplingResult(result: McpSamplingResult): JsonObject = buildJsonObject {
        put("role", result.role)
        put("content", JsonArray(result.content))
        put("model", result.model)
        result.stopReason?.let { put("stopReason", it) }
    }

    private fun parseElicitationRequest(params: JsonObject?): McpElicitationRequest {
        val raw = params ?: throw McpProtocolException("Invalid MCP elicitation/create params: missing params")
        val message = raw.stringOrNull("message")
            ?: throw McpProtocolException("Invalid MCP elicitation/create params: message must be a string")
        val schema = raw["requestedSchema"] as? JsonObject
            ?: throw McpProtocolException(
                "Invalid MCP elicitation/create params: requestedSchema must be an object",
            )
        return McpElicitationRequest(message = message, requestedSchema = schema, raw = raw)
    }

    private fun encodeElicitationResult(result: McpElicitationResult): JsonObject = buildJsonObject {
        put("action", result.action)
        result.content?.let { put("content", it) }
    }

    private fun mergedCapabilities(): JsonObject {
        val merged = LinkedHashMap<String, JsonElement>()
        options.capabilities.forEach { (key, value) -> merged[key] = value }
        fun ensure(name: String, defaults: JsonObject) {
            val existing = merged[name]
            merged[name] = if (existing is JsonObject) JsonObject(existing + defaults) else defaults
        }
        if (options.handlers.listRoots != null) {
            ensure("roots", buildJsonObject { put("listChanged", true) })
        }
        if (options.handlers.sample != null) ensure("sampling", JsonObject(emptyMap()))
        if (options.handlers.elicit != null) ensure("elicitation", JsonObject(emptyMap()))
        return JsonObject(merged)
    }

    private suspend fun handleNotification(method: String, params: JsonObject?) {
        when (method) {
            "notifications/tools/list_changed" -> {
                val callback = notificationListener?.onToolsChanged ?: return
                scope.launch { runCatching { callback() } }
            }

            "notifications/resources/list_changed" -> {
                val callback = notificationListener?.onResourcesChanged ?: return
                scope.launch { runCatching { callback() } }
            }

            "notifications/message" -> {
                val callback = notificationListener?.onLog ?: return
                val logMessage = parseLogMessage(params) ?: return
                scope.launch { runCatching { callback(logMessage) } }
            }

            "notifications/progress" -> handleProgress(params)
        }
    }

    private fun parseLogMessage(params: JsonObject?): McpLogMessage? {
        val level = params?.stringOrNull("level") ?: return null
        return McpLogMessage(
            level = level,
            logger = params.stringOrNull("logger"),
            data = params["data"],
        )
    }

    private suspend fun handleProgress(params: JsonObject?) {
        if (params == null) return
        val progressTokenElement = params["progressToken"] ?: return
        val progressToken = runCatching { parseId(progressTokenElement) }.getOrNull() ?: return
        val progress = (params["progress"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return
        val total = (params["total"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val message = params.stringOrNull("message")
        val matched = mutex.withLock {
            val requestId = progressRequests[progressToken] ?: return@withLock null
            val entry = pending[requestId] ?: return@withLock null
            requestId to entry
        } ?: return
        val requestId = matched.first
        val entry = matched.second
        armTimeout(requestId, entry)
        val callback = entry.onProgress ?: return
        runCatching { callback(progressTokenElement, progress, total, message) }
    }

    private suspend fun handleTransportClosed(error: Throwable?) {
        val wasClosed = mutex.withLock {
            if (state == State.Closed) {
                true
            } else {
                state = State.Closed
                false
            }
        }
        if (wasClosed) return
        rejectAllPending(
            McpConnectionClosedException(
                error?.message ?: "MCP transport closed",
                error,
            ),
        )
    }

    private fun requireConnected() {
        if (state != State.Connected) throw McpConnectionClosedException("MCP client is not initialized")
    }

    private fun requireTransport(allowConnecting: Boolean) {
        when (state) {
            State.Connected -> return
            State.Connecting -> if (allowConnecting) return
            State.Idle -> throw McpConnectionClosedException("MCP client is not initialized")
            State.Closed -> throw McpConnectionClosedException("MCP client is closed")
        }
    }

    private fun cursorParams(cursor: String?): JsonObject? =
        cursor?.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("cursor", it) } }

    private fun <T> parsePage(
        result: JsonElement,
        method: String,
        key: String,
        parseItem: (JsonElement) -> T,
    ): Pair<List<T>, String?> {
        val obj = result as? JsonObject ?: throw McpProtocolException("Invalid MCP $method result")
        val items = obj[key] as? JsonArray
            ?: throw McpProtocolException("Invalid MCP $method result: $key must be an array")
        val parsed = try {
            items.map(parseItem)
        } catch (error: IllegalArgumentException) {
            throw McpProtocolException("Invalid MCP $method result: ${error.message}", error)
        }
        val cursorElement = obj["nextCursor"]
        val nextCursor = when {
            cursorElement == null || cursorElement is JsonNull -> null
            else -> {
                val value = (cursorElement as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw McpProtocolException("Invalid MCP $method result: nextCursor must be a string")
                value.takeIf { it.isNotEmpty() }
            }
        }
        return parsed to nextCursor
    }

    private fun parseId(element: JsonElement): JsonRpcId {
        val primitive = element as? JsonPrimitive
            ?: throw McpProtocolException("JSON-RPC id must be a string or integer")
        return if (primitive.isString) {
            JsonRpcId.Text(primitive.content)
        } else {
            val number = primitive.contentOrNull?.toLongOrNull()
                ?: throw McpProtocolException("JSON-RPC id must be a string or integer")
            JsonRpcId.Number(number)
        }
    }

    private fun idElement(id: JsonRpcId): JsonElement = when (id) {
        is JsonRpcId.Text -> JsonPrimitive(id.value)
        is JsonRpcId.Number -> JsonPrimitive(id.value)
    }
}
