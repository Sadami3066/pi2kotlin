package dev.pi.ai.auth

import dev.pi.ai.PiJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

data class CredentialInfo(
    val providerId: String,
    val type: String,
)

class CredentialStore(
    val directory: Path = defaultAuthDirectory(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    val file: Path get() = directory / AUTH_FILE_NAME

    fun read(providerId: String): Credential? = readAll()[providerId]

    fun readApiKey(providerId: String): String? = (read(providerId) as? ApiKeyCredential)?.key

    fun readEnv(providerId: String): Map<String, String>? = (read(providerId) as? ApiKeyCredential)?.env

    fun list(): List<CredentialInfo> = readRaw().map { (providerId, element) ->
        CredentialInfo(providerId, credentialType(element))
    }

    fun keys(): List<String> = readRaw().keys.toList()

    fun modify(providerId: String, transform: (Credential?) -> Credential?): Credential? {
        val raw = readRaw().toMutableMap()
        val current = raw[providerId]?.let(::decodeCredential)
        val next = transform(current) ?: return null
        raw[providerId] = encodeCredential(next)
        writeRaw(raw)
        return next
    }

    fun writeApiKey(providerId: String, key: String, env: Map<String, String>? = null) {
        writeRaw(readRaw() + (providerId to encodeCredential(ApiKeyCredential(key = key, env = env))))
    }

    fun write(providerId: String, credential: Credential) {
        writeRaw(readRaw() + (providerId to encodeCredential(credential)))
    }

    fun delete(providerId: String) {
        val raw = readRaw()
        if (!raw.containsKey(providerId)) return
        writeRaw(raw - providerId)
    }

    fun readRaw(): Map<String, JsonElement> {
        if (!fileSystem.exists(file)) return emptyMap()
        val text = fileSystem.read(file) { readUtf8() }
        if (text.isBlank()) return emptyMap()
        return PiJson.parseToJsonElement(text).jsonObject
    }

    private fun readAll(): Map<String, Credential> = readRaw().mapNotNull { (providerId, element) ->
        decodeCredential(element)?.let { providerId to it }
    }.toMap()

    private fun writeRaw(raw: Map<String, JsonElement>) {
        fileSystem.createDirectories(directory)
        val text = JsonObject(raw).toString()
        val temp = directory / "$AUTH_FILE_NAME.tmp"
        fileSystem.write(temp) { writeUtf8(text) }
        fileSystem.atomicMove(temp, file)
    }

    private fun credentialType(element: JsonElement): String =
        (element as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull ?: "api_key"

    private fun decodeCredential(element: JsonElement): Credential? {
        val obj = element as? JsonObject ?: return null
        return when (credentialType(obj)) {
            "oauth" -> PiJson.decodeFromJsonElement(OAuthCredential.serializer(), obj)
            else -> PiJson.decodeFromJsonElement(ApiKeyCredential.serializer(), obj)
        }
    }

    private fun encodeCredential(credential: Credential): JsonObject = when (credential) {
        is OAuthCredential -> PiJson.encodeToJsonElement(OAuthCredential.serializer(), credential).jsonObject
        is ApiKeyCredential -> buildJsonObject {
            put("type", credential.type)
            credential.key?.let { put("key", it) }
            credential.env?.let { env ->
                put("env", buildJsonObject { env.forEach { (name, value) -> put(name, value) } })
            }
        }
    }

    companion object {
        const val AUTH_FILE_NAME = "auth.json"
    }
}

fun defaultAuthDirectory(): Path {
    val home = System.getProperty("user.home").orEmpty().ifBlank { "." }
    return home.toPath() / ".pi" / "agent"
}
