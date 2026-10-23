package dev.pi.coding.mcp

import dev.pi.ai.platform.platformEnv
import dev.pi.coding.paths.AgentPaths
import dev.pi.coding.settings.SettingsManager
import dev.pi.mcp.config.McpServerConfig
import dev.pi.mcp.config.applyMcpServerOverrides
import dev.pi.mcp.config.parseMcpConfig
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path

data class McpConfigLoadResult(
    val servers: List<Pair<String, McpServerConfig>>,
    val errors: List<String>,
)

class McpConfigLoader(
    val paths: AgentPaths,
    val fs: FileSystem,
    val settings: SettingsManager,
) {
    suspend fun load(env: Map<String, String>? = null): McpConfigLoadResult {
        val errors = mutableListOf<String>()
        val globalEntries = readServerEntries(paths.mcpFile, errors, env, interpolate = true)
        val globalRoot = JsonObject(
            linkedMapOf("mcpServers" to JsonObject(LinkedHashMap(globalEntries.toMap()))),
        )
        val parsed = parseMcpConfig(globalRoot)
        for (error in parsed.errors) errors.add("${paths.mcpFile}: $error")
        val servers = LinkedHashMap<String, McpServerConfig>()
        for ((name, config) in parsed.servers) servers[name] = config
        if (settings.isProjectTrusted()) {
            val projectEntries = readServerEntries(paths.projectMcpFile, errors, env, interpolate = false)
            for ((name, override) in projectEntries) {
                val base = servers[name]
                if (base == null) {
                    errors.add("${paths.projectMcpFile}: mcpServers.$name: server needs a global server to override")
                    continue
                }
                val merged = applyMcpServerOverrides(base, override)
                if (merged.errors.isNotEmpty()) {
                    for (error in merged.errors) {
                        errors.add("${paths.projectMcpFile}: mcpServers.$name.$error")
                    }
                    continue
                }
                merged.config?.let { servers[name] = it }
            }
        }
        return McpConfigLoadResult(servers.entries.map { it.key to it.value }, errors)
    }

    private fun readServerEntries(
        path: Path,
        errors: MutableList<String>,
        env: Map<String, String>?,
        interpolate: Boolean,
    ): List<Pair<String, JsonObject>> {
        if (!fs.exists(path)) return emptyList()
        val text = try {
            fs.read(path) { readUtf8() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.add("$path: ${error.message ?: "failed to read file"}")
            return emptyList()
        }
        val root = try {
            Json.parseToJsonElement(text.removePrefix("\uFEFF"))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            errors.add("$path: ${error.message ?: "invalid JSON"}")
            return emptyList()
        }
        if (root !is JsonObject) {
            errors.add("$path: expected a JSON object")
            return emptyList()
        }
        val serversElement = root["mcpServers"] ?: return emptyList()
        if (serversElement !is JsonObject) {
            errors.add("$path: expected an object with an \"mcpServers\" object")
            return emptyList()
        }
        val lookup: (String) -> String? = if (env != null) {
            { name -> env[name] }
        } else {
            { name -> platformEnv(name) }
        }
        val entries = mutableListOf<Pair<String, JsonObject>>()
        for ((name, value) in serversElement) {
            if (value !is JsonObject) {
                errors.add("$path: mcpServers.$name: server must be an object")
                continue
            }
            if (!interpolate) {
                entries.add(name to value)
                continue
            }
            try {
                val interpolated = interpolateElement(value, "", lookup) as JsonObject
                entries.add(name to interpolated)
            } catch (error: McpInterpolationException) {
                errors.add("$path: mcpServers.$name.${error.path}: ${error.message}")
            }
        }
        return entries
    }
}

private class McpInterpolationException(
    val path: String,
    message: String,
) : Exception(message)

private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

private fun interpolateElement(
    element: JsonElement,
    path: String,
    lookup: (String) -> String?,
): JsonElement = when (element) {
    is JsonObject -> {
        val result = LinkedHashMap<String, JsonElement>()
        for ((key, value) in element) {
            val childPath = if (path.isEmpty()) key else "$path.$key"
            result[key] = interpolateElement(value, childPath, lookup)
        }
        JsonObject(result)
    }

    is JsonArray -> JsonArray(
        element.mapIndexed { index, item -> interpolateElement(item, "$path[$index]", lookup) },
    )

    is JsonNull -> element

    is JsonPrimitive -> if (element.isString) {
        JsonPrimitive(interpolateString(element.content, path, lookup))
    } else {
        element
    }
}

private fun interpolateString(text: String, path: String, lookup: (String) -> String?): String {
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '$' && index + 1 < text.length && text[index + 1] == '{') {
            val close = text.indexOf('}', index + 2)
            if (close < 0) {
                builder.append(text.substring(index))
                break
            }
            val name = text.substring(index + 2, close)
            if (!ENV_NAME.matches(name)) {
                builder.append(text.substring(index, close + 1))
                index = close + 1
                continue
            }
            val value = lookup(name)
                ?: throw McpInterpolationException(path, "environment variable $name is not set")
            builder.append(value)
            index = close + 1
            continue
        }
        builder.append(char)
        index += 1
    }
    val resolved = builder.toString()
    if (resolved.startsWith("!")) {
        throw McpInterpolationException(path, "command values (\"!...\") are not supported")
    }
    return resolved
}
