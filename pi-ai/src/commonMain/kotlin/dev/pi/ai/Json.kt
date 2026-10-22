package dev.pi.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

typealias JsonValue = JsonElement
typealias JsonObject = kotlinx.serialization.json.JsonObject

val PiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    allowSpecialFloatingPointValues = true
}

fun JsonElement?.orNull(): JsonElement? = this

internal fun JsonObject.withType(type: String, body: JsonObject): JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive(type))
    body.forEach { (key, value) -> put(key, value) }
}
