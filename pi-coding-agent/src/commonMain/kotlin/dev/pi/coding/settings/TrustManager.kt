package dev.pi.coding.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path

private const val CONFIG_DIR_NAME = ".pi"

private val TRUST_REQUIRING_PROJECT_RESOURCES = listOf(
    "settings.json",
    "mcp.json",
    "extensions",
    "skills",
    "prompts",
    "themes",
    "SYSTEM.md",
    "APPEND_SYSTEM.md",
)

data class ProjectTrustEntry(val path: String, val decision: Boolean)

data class ProjectTrustUpdate(val path: Path, val decision: Boolean?)

class TrustManager(
    val trustFile: Path,
    val fs: FileSystem,
) {
    private val mutex = Mutex()

    fun normalizeCwd(cwd: Path): String = canonicalize(cwd).toString()

    suspend fun get(cwd: Path): Boolean? = getEntry(cwd)?.decision

    suspend fun getEntry(cwd: Path): ProjectTrustEntry? = mutex.withLock {
        findNearestTrustEntry(readTrustFile(), cwd)
    }

    suspend fun set(cwd: Path, decision: Boolean?) = setMany(listOf(ProjectTrustUpdate(cwd, decision)))

    suspend fun setMany(updates: List<ProjectTrustUpdate>) {
        mutex.withLock {
            val data = LinkedHashMap(readTrustFile())
            for (update in updates) {
                val key = normalizeCwd(update.path)
                if (update.decision == null) data.remove(key) else data[key] = update.decision
            }
            writeTrustFile(data)
        }
    }

    suspend fun hasTrustRequiringProjectResources(cwd: Path): Boolean {
        val configDir = cwd / CONFIG_DIR_NAME
        if (TRUST_REQUIRING_PROJECT_RESOURCES.any { entry -> existsOrFalse(configDir / entry) }) return true
        val userAgentsSkillsDir = trustFile.parent?.parent?.parent?.let { homeDir -> homeDir / ".agents" / "skills" }
        var current = canonicalize(cwd)
        while (true) {
            val agentsSkillsDir = current / ".agents" / "skills"
            if (agentsSkillsDir != userAgentsSkillsDir && existsOrFalse(agentsSkillsDir)) return true
            val parent = current.parent ?: return false
            if (parent == current) return false
            current = parent
        }
    }

    private fun existsOrFalse(path: Path): Boolean = try {
        fs.exists(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    suspend fun resolveTrusted(
        cwd: Path,
        defaultProjectTrust: String = SettingsDefaults.DEFAULT_PROJECT_TRUST,
    ): Boolean {
        if (!hasTrustRequiringProjectResources(cwd)) return true
        get(cwd)?.let { return it }
        return defaultProjectTrust == "always"
    }

    private fun canonicalize(path: Path): Path = try {
        fs.canonicalize(path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        path.normalized()
    }

    private fun findNearestTrustEntry(data: Map<String, Boolean?>, cwd: Path): ProjectTrustEntry? {
        var current = canonicalize(cwd)
        while (true) {
            val decision = data[current.toString()]
            if (decision != null) return ProjectTrustEntry(current.toString(), decision)
            val parent = current.parent ?: return null
            if (parent == current) return null
            current = parent
        }
    }

    private fun readTrustFile(): Map<String, Boolean?> {
        if (!fs.exists(trustFile)) return emptyMap()
        val text = try {
            readTextIfExists(fs, trustFile) ?: return emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read trust store $trustFile: ${e.message}")
        }
        val parsedElement = try {
            SettingsJson.parse(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read trust store $trustFile: ${e.message}")
        }
        if (parsedElement !is JsonObject) {
            throw IllegalStateException("Invalid trust store $trustFile: expected an object")
        }
        val parsed = parsedElement
        val data = LinkedHashMap<String, Boolean?>()
        for ((key, value) in parsed) {
            val decision = when {
                value is JsonNull -> null
                value is JsonPrimitive && !value.isString && value.content == "true" -> true
                value is JsonPrimitive && !value.isString && value.content == "false" -> false
                else -> throw IllegalStateException(
                    "Invalid trust store $trustFile: value for ${SettingsJson.render(JsonPrimitive(key))} must be true, false, or null",
                )
            }
            data[key] = decision
        }
        return data
    }

    private fun writeTrustFile(data: Map<String, Boolean?>) {
        val sorted = LinkedHashMap<String, JsonElement>()
        for (key in data.keys.sorted()) {
            val decision = data[key]
            sorted[key] = if (decision == null) JsonNull else JsonPrimitive(decision)
        }
        writeTextAtomically(fs, trustFile, SettingsJson.render(JsonObject(sorted), trailingNewline = true))
    }
}
