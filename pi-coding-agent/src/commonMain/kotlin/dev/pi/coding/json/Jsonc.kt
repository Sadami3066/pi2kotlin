package dev.pi.coding.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object Jsonc {
    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json = Json {
        allowComments = true
        allowTrailingComma = true
    }

    fun parse(text: String): JsonElement = try {
        json.parseToJsonElement(text)
    } catch (error: Exception) {
        throw IllegalArgumentException(error.message ?: "Invalid JSONC", error)
    }
}
