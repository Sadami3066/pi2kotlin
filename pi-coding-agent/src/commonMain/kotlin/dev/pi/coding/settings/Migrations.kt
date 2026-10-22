package dev.pi.coding.settings

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path

object Migrations {
    suspend fun run(fs: FileSystem, agentDir: Path, cwd: Path? = null) {
        migrateCommandsToPrompts(fs, agentDir)
        if (cwd != null) migrateCommandsToPrompts(fs, cwd / ".pi")
    }

    suspend fun migrateCommandsToPrompts(fs: FileSystem, baseDir: Path): Boolean {
        val commandsDir = baseDir / "commands"
        val promptsDir = baseDir / "prompts"
        if (!fs.exists(commandsDir) || fs.exists(promptsDir)) return false
        return try {
            fs.atomicMove(commandsDir, promptsDir)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    fun migrateSettings(settings: JsonObject): JsonObject {
        var current = settings
        current = migrateQueueMode(current)
        current = migrateWebsockets(current)
        current = migrateSkills(current)
        current = migrateRetryMaxDelay(current)
        return current
    }

    private fun migrateQueueMode(settings: JsonObject): JsonObject {
        if (!settings.containsKey("queueMode") || settings.containsKey("steeringMode")) return settings
        val queueMode = settings["queueMode"] ?: return settings
        val result = LinkedHashMap<String, JsonElement>()
        settings.forEach { (key, value) ->
            if (key != "queueMode") result[key] = value
        }
        result["steeringMode"] = queueMode
        return JsonObject(result)
    }

    private fun migrateWebsockets(settings: JsonObject): JsonObject {
        if (settings.containsKey("transport")) return settings
        val websockets = settings["websockets"] as? JsonPrimitive ?: return settings
        if (websockets.isString) return settings
        val boolean = when (websockets.content) {
            "true" -> true
            "false" -> false
            else -> return settings
        }
        val result = LinkedHashMap(settings)
        result["transport"] = JsonPrimitive(if (boolean) "websocket" else "sse")
        result.remove("websockets")
        return JsonObject(result)
    }

    private fun migrateSkills(settings: JsonObject): JsonObject {
        val skills = settings["skills"] as? JsonObject ?: return settings
        val result = LinkedHashMap(settings)
        val enableSkillCommands = skills["enableSkillCommands"]
        if (enableSkillCommands != null && !settings.containsKey("enableSkillCommands")) {
            result["enableSkillCommands"] = enableSkillCommands
        }
        val customDirectories = skills["customDirectories"]
        if (customDirectories is JsonArray && customDirectories.isNotEmpty()) {
            result["skills"] = customDirectories
        } else {
            result.remove("skills")
        }
        return JsonObject(result)
    }

    private fun migrateRetryMaxDelay(settings: JsonObject): JsonObject {
        val retry = settings["retry"] as? JsonObject ?: return settings
        val provider = retry["provider"] as? JsonObject
        val maxDelayMs = retry["maxDelayMs"]
        var changed = retry.containsKey("maxDelayMs")
        val retryResult = LinkedHashMap(retry)
        val numericMaxDelay = maxDelayMs is JsonPrimitive && !maxDelayMs.isString && maxDelayMs.content.toDoubleOrNull() != null
        if (numericMaxDelay) {
            val providerMaxDelay = provider?.get("maxRetryDelayMs")
            if (providerMaxDelay == null || providerMaxDelay is JsonNull) {
                val providerResult = LinkedHashMap(provider ?: JsonObject(emptyMap()))
                providerResult["maxRetryDelayMs"] = maxDelayMs
                retryResult["provider"] = JsonObject(providerResult)
                changed = true
            }
        }
        retryResult.remove("maxDelayMs")
        if (!changed) return settings
        val result = LinkedHashMap(settings)
        result["retry"] = JsonObject(retryResult)
        return JsonObject(result)
    }
}
