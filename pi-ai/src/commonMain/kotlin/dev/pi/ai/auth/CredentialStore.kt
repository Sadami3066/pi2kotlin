package dev.pi.ai.auth

import dev.pi.ai.PiJson
import dev.pi.ai.platform.platformEnv
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

interface CredentialStore {
    fun read(providerId: String): Credential?

    fun readAll(): Map<String, Credential>

    fun readApiKey(providerId: String): String?

    fun readEnv(providerId: String): Map<String, String>?

    fun list(): List<CredentialInfo>

    fun keys(): List<String>

    fun modify(providerId: String, transform: (Credential?) -> Credential?): Credential?

    fun writeApiKey(providerId: String, key: String, env: Map<String, String>? = null)

    fun write(providerId: String, credential: Credential)

    fun writeRawCredential(providerId: String, credential: JsonElement)

    fun delete(providerId: String)

    fun readRaw(): Map<String, JsonElement>
}

class FileCredentialStore(
    val directory: Path = defaultAuthDirectory(),
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : CredentialStore {
    val file: Path get() = directory / AUTH_FILE_NAME

    override fun read(providerId: String): Credential? = readAll()[providerId]

    override fun readAll(): Map<String, Credential> = readRaw().mapNotNull { (providerId, element) ->
        decodeCredential(element)?.let { providerId to it }
    }.toMap()

    override fun readApiKey(providerId: String): String? = (read(providerId) as? ApiKeyCredential)?.key

    override fun readEnv(providerId: String): Map<String, String>? = (read(providerId) as? ApiKeyCredential)?.env

    override fun list(): List<CredentialInfo> = readRaw().map { (providerId, element) ->
        CredentialInfo(providerId, credentialType(element))
    }

    override fun keys(): List<String> = readRaw().keys.toList()

    override fun modify(providerId: String, transform: (Credential?) -> Credential?): Credential? {
        val raw = readRaw().toMutableMap()
        val current = raw[providerId]?.let(::decodeCredential)
        val next = transform(current) ?: return null
        raw[providerId] = encodeCredential(next)
        writeRaw(raw)
        return next
    }

    override fun writeApiKey(providerId: String, key: String, env: Map<String, String>?) {
        writeRaw(readRaw() + (providerId to encodeCredential(ApiKeyCredential(key = key, env = env))))
    }

    override fun write(providerId: String, credential: Credential) {
        writeRaw(readRaw() + (providerId to encodeCredential(credential)))
    }

    override fun writeRawCredential(providerId: String, credential: JsonElement) {
        writeRaw(readRaw() + (providerId to credential))
    }

    override fun delete(providerId: String) {
        val raw = readRaw()
        if (!raw.containsKey(providerId)) return
        writeRaw(raw - providerId)
    }

    override fun readRaw(): Map<String, JsonElement> {
        if (!fileSystem.exists(file)) return emptyMap()
        val text = fileSystem.read(file) { readUtf8() }
        if (text.isBlank()) return emptyMap()
        return PiJson.parseToJsonElement(text).jsonObject
    }

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

fun fileCredentialStore(
    directory: Path = defaultAuthDirectory(),
    fileSystem: FileSystem = FileSystem.SYSTEM,
): CredentialStore = FileCredentialStore(directory, fileSystem)

fun defaultAuthDirectory(): Path {
    val home = platformEnv("HOME")?.ifBlank { null }
        ?: platformEnv("USERPROFILE")?.ifBlank { null }
        ?: "."
    return home.toPath() / ".pi" / "agent"
}
