package dev.pi.coding.settings

import dev.pi.coding.json.Jsonc
import dev.pi.coding.paths.AgentPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path

data class SettingsError(
    val scope: String,
    val path: Path?,
    val message: String,
)

class SettingsManager(
    val globalFile: Path,
    val projectFile: Path?,
    val fs: FileSystem,
    projectTrusted: Boolean = true,
) {
    private val mutex = Mutex()
    private var globalSettings: JsonObject = JsonObject(emptyMap())
    private var projectSettings: JsonObject = JsonObject(emptyMap())
    private var mergedSettings: JsonObject = JsonObject(emptyMap())
    private var projectTrusted: Boolean = projectTrusted
    private var globalLoadError: String? = null
    private val errors: MutableList<SettingsError> = mutableListOf()

    companion object {
        fun forPaths(paths: AgentPaths, fs: FileSystem): SettingsManager = SettingsManager(
            globalFile = paths.settingsFile,
            projectFile = paths.cwd / ".pi" / "settings.json",
            fs = fs,
        )
    }

    suspend fun reload() {
        mutex.withLock {
            val global = loadScope(globalFile)
            globalSettings = global.settings
            globalLoadError = global.error
            global.error?.let { errors.add(SettingsError("global", globalFile, it)) }
            val project = projectFile
            if (projectTrusted && project != null) {
                val loaded = loadScope(project)
                projectSettings = loaded.settings
                loaded.error?.let { errors.add(SettingsError("project", project, it)) }
            } else {
                projectSettings = JsonObject(emptyMap())
            }
            mergedSettings = mergeSettings(globalSettings, projectSettings)
        }
    }

    fun isProjectTrusted(): Boolean = projectTrusted

    suspend fun setProjectTrusted(trusted: Boolean) {
        mutex.withLock {
            if (projectTrusted == trusted) return@withLock
            projectTrusted = trusted
            val project = projectFile
            if (trusted && project != null) {
                val loaded = loadScope(project)
                projectSettings = loaded.settings
                loaded.error?.let { errors.add(SettingsError("project", project, it)) }
            } else {
                projectSettings = JsonObject(emptyMap())
            }
            mergedSettings = mergeSettings(globalSettings, projectSettings)
        }
    }

    fun drainErrors(): List<SettingsError> {
        val drained = errors.toList()
        errors.clear()
        return drained
    }

    fun getSettings(): PiSettings = PiSettingsCodec.decode(mergedSettings)

    fun getDefaultProvider(): String? = getSettings().defaultProvider

    fun getDefaultModelId(): String? = getSettings().defaultModel

    fun getDefaultModel(): Pair<String, String>? {
        val settings = getSettings()
        val provider = settings.defaultProvider ?: return null
        val modelId = settings.defaultModel ?: return null
        return provider to modelId
    }

    fun getDefaultThinkingLevel(): String? = getSettings().defaultThinkingLevel

    fun getModelThinkingLevel(provider: String, modelId: String): String? =
        getSettings().modelThinkingLevels?.get("$provider/$modelId")

    fun getAllModelThinkingLevels(): Map<String, String> = getSettings().modelThinkingLevels.orEmpty()

    fun getEnabledModels(): List<String>? = getSettings().enabledModels

    fun getDefaultTools(): List<String> {
        if (!mergedSettings.containsKey("defaultTools")) return DEFAULT_TOOL_NAMES.toList()
        return resolveDefaultTools(getSettings().defaultTools.orEmpty())
    }

    fun getSteeringMode(): String =
        getSettings().steeringMode?.takeIf { it.isNotEmpty() } ?: SettingsDefaults.STEERING_MODE

    fun getFollowUpMode(): String =
        getSettings().followUpMode?.takeIf { it.isNotEmpty() } ?: SettingsDefaults.FOLLOW_UP_MODE

    fun getRetrySettings(): RetrySettings = getSettings().retry ?: RetrySettings()

    fun getCompactionSettings(): CompactionSettings = getSettings().compaction ?: CompactionSettings()

    fun getImageAutoResize(): Boolean = getSettings().images?.autoResize ?: SettingsDefaults.IMAGES_AUTO_RESIZE

    fun getShellPath(): String? = getSettings().shellPath

    fun getExtensionPaths(): List<String> = getSettings().extensions.orEmpty()

    fun getSkillPaths(): List<String> = getSettings().skills.orEmpty()

    fun getPromptTemplatePaths(): List<String> = getSettings().prompts.orEmpty()

    fun getThemePaths(): List<String> = getSettings().themes.orEmpty()

    fun getPackages(): List<JsonElement> = getSettings().packages.orEmpty()

    fun getSessionDir(): String? = getSettings().sessionDir

    fun getTheme(): String? = getSettings().theme?.takeUnless { it.contains("/") }

    fun getHideThinkingBlock(): Boolean = getSettings().hideThinkingBlock ?: SettingsDefaults.HIDE_THINKING_BLOCK

    fun getShowCacheMissNotices(): Boolean = getSettings().showCacheMissNotices ?: SettingsDefaults.SHOW_CACHE_MISS_NOTICES

    fun getCompactionEnabled(): Boolean = getCompactionSettings().enabled

    fun getRetryEnabled(): Boolean = getRetrySettings().enabled

    fun getBranchSummarySettings(): BranchSummarySettings =
        getSettings().branchSummary ?: BranchSummarySettings()

    fun getProviderRetrySettings(): ProviderRetrySettings = getRetrySettings().provider

    fun getTransport(): String = getSettings().transport ?: SettingsDefaults.TRANSPORT

    fun getDefaultProjectTrust(): String = getSettings().defaultProjectTrust ?: SettingsDefaults.DEFAULT_PROJECT_TRUST

    fun getCacheWarmingMode(): String = getSettings().cacheWarming ?: SettingsDefaults.CACHE_WARMING

    fun getShellCommandPrefix(): String? = getSettings().shellCommandPrefix

    suspend fun setDefaultModelAndProvider(provider: String, modelId: String) {
        writeGlobal(
            listOf(
                SettingsUpdate.Value("defaultProvider", JsonPrimitive(provider)),
                SettingsUpdate.Value("defaultModel", JsonPrimitive(modelId)),
            ),
        )
    }

    suspend fun setDefaultThinkingLevel(level: String?) {
        writeGlobal(listOf(SettingsUpdate.Value("defaultThinkingLevel", level?.let(::JsonPrimitive))))
    }

    suspend fun setEnabledModels(models: List<String>?) {
        writeGlobal(listOf(SettingsUpdate.Value("enabledModels", models?.let(::jsonStringArray))))
    }

    suspend fun setDefaultTools(tools: List<String>?) {
        writeGlobal(listOf(SettingsUpdate.Value("defaultTools", tools?.let(::jsonStringArray))))
    }

    suspend fun setSteeringMode(mode: String) {
        writeGlobal(listOf(SettingsUpdate.Value("steeringMode", JsonPrimitive(mode))))
    }

    suspend fun setFollowUpMode(mode: String) {
        writeGlobal(listOf(SettingsUpdate.Value("followUpMode", JsonPrimitive(mode))))
    }

    suspend fun setRetryEnabled(enabled: Boolean) {
        writeGlobal(listOf(SettingsUpdate.Nested("retry", mapOf("enabled" to JsonPrimitive(enabled)))))
    }

    suspend fun setCompactionEnabled(enabled: Boolean) {
        writeGlobal(listOf(SettingsUpdate.Nested("compaction", mapOf("enabled" to JsonPrimitive(enabled)))))
    }

    private suspend fun writeGlobal(updates: List<SettingsUpdate>) {
        mutex.withLock {
            globalSettings = mergeUpdates(globalSettings, updates)
            mergedSettings = mergeSettings(globalSettings, projectSettings)
            if (globalLoadError != null) return@withLock
            persist("global", globalFile, updates)
        }
    }

    private fun persist(scope: String, file: Path, updates: List<SettingsUpdate>) {
        val currentText = try {
            readTextIfExists(fs, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add(SettingsError(scope, file, e.message ?: "Failed to read settings file"))
            return
        }
        val current = if (currentText == null) {
            JsonObject(emptyMap())
        } else {
            try {
                Migrations.migrateSettings(SettingsJson.parseObject(currentText))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.add(SettingsError(scope, file, e.message ?: "Failed to parse settings file"))
                return
            }
        }
        val mergedFile = mergeUpdates(current, updates)
        try {
            writeTextAtomically(fs, file, SettingsJson.render(mergedFile))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors.add(SettingsError(scope, file, e.message ?: "Failed to write settings file"))
        }
    }

    private class LoadedSettings(val settings: JsonObject, val error: String?)

    private fun loadScope(file: Path): LoadedSettings {
        val text = try {
            readTextIfExists(fs, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return LoadedSettings(JsonObject(emptyMap()), e.message ?: "Failed to read settings file")
        }
        if (text == null) return LoadedSettings(JsonObject(emptyMap()), null)
        return try {
            LoadedSettings(Migrations.migrateSettings(SettingsJson.parseObject(text)), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadedSettings(JsonObject(emptyMap()), e.message ?: "Failed to parse settings file")
        }
    }
}

private sealed interface SettingsUpdate {
    data class Value(val field: String, val value: JsonElement?) : SettingsUpdate
    data class Nested(val field: String, val values: Map<String, JsonElement?>) : SettingsUpdate
}

private fun mergeUpdates(base: JsonObject, updates: List<SettingsUpdate>): JsonObject {
    val merged = LinkedHashMap(base)
    for (update in updates) {
        when (update) {
            is SettingsUpdate.Value -> if (update.value == null) merged.remove(update.field) else merged[update.field] = update.value
            is SettingsUpdate.Nested -> {
                val nested = LinkedHashMap((merged[update.field] as? JsonObject) ?: JsonObject(emptyMap()))
                for ((key, value) in update.values) {
                    if (value == null) nested.remove(key) else nested[key] = value
                }
                merged[update.field] = JsonObject(nested)
            }
        }
    }
    return JsonObject(merged)
}

internal fun jsonStringArray(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

internal fun deepMergeObjects(base: JsonObject, overrides: JsonObject): JsonObject {
    val result = LinkedHashMap(base)
    for ((key, overrideValue) in overrides) {
        val baseValue = base[key]
        result[key] = if (baseValue is JsonObject && overrideValue is JsonObject) {
            deepMergeObjects(baseValue, overrideValue)
        } else {
            overrideValue
        }
    }
    return JsonObject(result)
}

internal fun mergeDefaultTools(base: JsonElement?, overrides: JsonElement?): JsonElement? {
    if (overrides == null) return base
    val overridesArray = overrides as? JsonArray
    if (base !is JsonArray || overridesArray == null || !overridesArray.all(::isToolModifierElement)) return overrides
    return JsonArray(base + overridesArray)
}

private fun isToolModifierElement(element: JsonElement): Boolean {
    val primitive = element as? JsonPrimitive ?: return false
    return primitive.isString && isToolModifier(primitive.content)
}

internal fun mergeSettings(base: JsonObject, overrides: JsonObject): JsonObject {
    val merged = deepMergeObjects(base, overrides)
    val defaultTools = mergeDefaultTools(base["defaultTools"], overrides["defaultTools"]) ?: return merged
    val result = LinkedHashMap(merged)
    result["defaultTools"] = defaultTools
    return JsonObject(result)
}

internal object SettingsJson {
    private val documentJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun parse(text: String): JsonElement = Jsonc.parse(stripBom(text))

    fun parseObject(text: String): JsonObject {
        val element = parse(text)
        return element as? JsonObject ?: throw IllegalArgumentException("expected a JSON object")
    }

    fun render(element: JsonElement, trailingNewline: Boolean = false): String {
        val text = documentJson.encodeToString(JsonElement.serializer(), element)
        return if (trailingNewline) "$text\n" else text
    }

    private fun stripBom(text: String): String = text.removePrefix("\uFEFF")
}

internal fun readTextIfExists(fs: FileSystem, file: Path): String? =
    if (fs.exists(file)) fs.read(file) { readUtf8() } else null

internal fun writeTextAtomically(fs: FileSystem, file: Path, text: String) {
    val parent = file.parent
    if (parent == null) {
        fs.write(file) { writeUtf8(text) }
        return
    }
    if (!fs.exists(parent)) fs.createDirectories(parent)
    val temp = parent / (file.name + ".tmp")
    fs.write(temp) { writeUtf8(text) }
    try {
        fs.atomicMove(temp, file)
    } catch (e: Exception) {
        runCatching { fs.delete(temp) }
        throw e
    }
}
