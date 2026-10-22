package dev.pi.ai.auth.oauth

import dev.pi.ai.PiJson
import dev.pi.ai.RunSignal
import dev.pi.ai.auth.OAuthCredential as StoredOAuthCredential
import dev.pi.ai.auth.ResolvedAuth
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random

data class OAuthSelectOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

interface OAuthInteraction {
    val signal: RunSignal? get() = null

    suspend fun openUrl(url: String, instructions: String? = null)

    suspend fun showMessage(message: String)

    suspend fun prompt(message: String, placeholder: String? = null, secret: Boolean = false): String

    suspend fun select(message: String, options: List<OAuthSelectOption>): String
}

data class OAuthLoginOptions(
    val deviceId: String? = null,
    val agentName: String? = null,
)

data class OAuthCredential(
    val refresh: String,
    val access: String,
    val expires: Long,
    val clientId: String? = null,
    val scopes: List<String>? = null,
    val enterpriseUrl: String? = null,
    val availableModelIds: List<String>? = null,
    val extra: Map<String, JsonElement> = emptyMap(),
)

data class OAuthLoginResult(
    val credential: OAuthCredential,
    val label: String? = null,
)

interface OAuthAuth {
    val id: String
    val name: String
    val type: String get() = "oauth"
    val isSubscription: Boolean get() = false
    val loginLabel: String? get() = null

    suspend fun login(
        interaction: OAuthInteraction,
        transport: HttpTransport,
        options: OAuthLoginOptions? = null,
    ): OAuthCredential

    suspend fun refresh(
        credential: OAuthCredential,
        transport: HttpTransport,
        signal: RunSignal? = null,
    ): OAuthCredential

    suspend fun toAuth(credential: OAuthCredential): ResolvedAuth
}

object OAuthRuntime {
    @Volatile
    var callbackServerFactory: OAuthCallbackServerFactory? = null

    @Volatile
    var randomBytes: ((Int) -> ByteArray)? = null
}

fun oauthRandomBytes(count: Int): ByteArray {
    OAuthRuntime.randomBytes?.let { return it(count) }
    return Random.nextBytes(count)
}

private val knownCredentialKeys = setOf(
    "type",
    "refresh",
    "access",
    "expires",
    "clientId",
    "scopes",
    "enterpriseUrl",
    "availableModelIds",
)

fun OAuthCredential.toJsonObject(): JsonObject = buildJsonObject {
    put("type", "oauth")
    put("refresh", refresh)
    put("access", access)
    put("expires", expires)
    clientId?.let { put("clientId", it) }
    scopes?.let { values -> put("scopes", buildJsonArray { values.forEach { add(it) } }) }
    enterpriseUrl?.let { put("enterpriseUrl", it) }
    availableModelIds?.let { values -> put("availableModelIds", buildJsonArray { values.forEach { add(it) } }) }
    for ((key, value) in extra) {
        if (key !in knownCredentialKeys) put(key, value)
    }
}

fun oauthCredentialFromJson(element: JsonElement): OAuthCredential? {
    val obj = element as? JsonObject ?: return null
    val refresh = obj.stringField("refresh") ?: return null
    val access = obj.stringField("access") ?: return null
    val expires = obj.longField("expires") ?: return null
    val clientId = obj.stringField("clientId")
    val scopes = obj.stringListField("scopes")
    val enterpriseUrl = obj.stringField("enterpriseUrl")
    val availableModelIds = obj.stringListField("availableModelIds")
    val extra = obj.filterKeys { it !in knownCredentialKeys }
    return OAuthCredential(
        refresh = refresh,
        access = access,
        expires = expires,
        clientId = clientId,
        scopes = scopes,
        enterpriseUrl = enterpriseUrl,
        availableModelIds = availableModelIds,
        extra = extra,
    )
}

fun OAuthCredential.toStoredCredential(): StoredOAuthCredential = StoredOAuthCredential(
    refresh = refresh,
    access = access,
    expires = expires,
)

fun StoredOAuthCredential.toOAuthCredential(): OAuthCredential = OAuthCredential(
    refresh = refresh,
    access = access,
    expires = expires,
)

fun OAuthCredential.isExpired(nowMillis: Long, minimumValidityMs: Long = 0): Boolean =
    expires <= nowMillis + minimumValidityMs

internal fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.longField(name: String): Long? =
    (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.stringListField(name: String): List<String>? {
    val array = this[name] as? JsonArray ?: return null
    return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}

internal fun parseJsonOrNull(text: String): JsonElement? =
    runCatching { PiJson.parseToJsonElement(text) }.getOrNull()

internal fun oauthNowMillis(): Long = System.currentTimeMillis()
