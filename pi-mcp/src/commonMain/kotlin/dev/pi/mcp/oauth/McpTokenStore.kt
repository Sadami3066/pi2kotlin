package dev.pi.mcp.oauth

import dev.pi.ai.PiJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path

interface McpTokenStore {
    suspend fun read(serverName: String, serverUrl: String): McpOAuthState?

    suspend fun write(serverName: String, serverUrl: String, state: McpOAuthState)

    suspend fun clear(serverName: String, serverUrl: String)
}

class MemoryMcpTokenStore : McpTokenStore {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, McpOAuthState>()

    override suspend fun read(serverName: String, serverUrl: String): McpOAuthState? = mutex.withLock {
        states[storeKey(serverName, serverUrl)]
    }

    override suspend fun write(serverName: String, serverUrl: String, state: McpOAuthState) = mutex.withLock {
        states[storeKey(serverName, serverUrl)] = state
    }

    override suspend fun clear(serverName: String, serverUrl: String) {
        mutex.withLock {
            states.remove(storeKey(serverName, serverUrl))
        }
    }
}

class FileMcpTokenStore(
    val file: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : McpTokenStore {
    private val mutex = Mutex()

    override suspend fun read(serverName: String, serverUrl: String): McpOAuthState? = mutex.withLock {
        load()[storeKey(serverName, serverUrl)]
    }

    override suspend fun write(serverName: String, serverUrl: String, state: McpOAuthState) = mutex.withLock {
        val states = load().toMutableMap()
        states[storeKey(serverName, serverUrl)] = state
        save(states)
    }

    override suspend fun clear(serverName: String, serverUrl: String) {
        mutex.withLock {
            val states = load().toMutableMap()
            if (states.remove(storeKey(serverName, serverUrl)) != null) save(states)
        }
    }

    private fun load(): Map<String, McpOAuthState> {
        if (!fileSystem.exists(file)) return emptyMap()
        val text = fileSystem.read(file) { readUtf8() }
        if (text.isBlank()) return emptyMap()
        val root = PiJson.parseToJsonElement(text) as? JsonObject ?: return emptyMap()
        val result = LinkedHashMap<String, McpOAuthState>()
        for ((key, value) in root) {
            val state = runCatching {
                PiJson.decodeFromJsonElement(McpOAuthState.serializer(), value.jsonObject)
            }.getOrNull() ?: continue
            result[key] = state
        }
        return result
    }

    private fun save(states: Map<String, McpOAuthState>) {
        file.parent?.let { fileSystem.createDirectories(it) }
        val root = JsonObject(
            states.mapValues { (_, state) -> PiJson.encodeToJsonElement(McpOAuthState.serializer(), state) },
        )
        val parent = file.parent
            ?: throw IllegalStateException("MCP token store file has no parent directory: $file")
        val temp = parent / "${file.name}.tmp"
        fileSystem.write(temp) { writeUtf8(root.toString()) }
        fileSystem.atomicMove(temp, file)
    }
}

internal fun storeKey(serverName: String, serverUrl: String): String = "$serverName|$serverUrl"

fun defaultMcpAuthFile(agentDir: Path): Path = agentDir / "mcp-auth.json"
