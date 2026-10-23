package dev.pi.mcp.protocol

import dev.pi.ai.PiJson
import dev.pi.mcp.McpProtocolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface JsonRpcId {
    data class Text(val value: String) : JsonRpcId

    data class Number(val value: Long) : JsonRpcId
}

sealed interface JsonRpcMessage

data class JsonRpcRequest(
    val id: JsonRpcId,
    val method: String,
    val params: JsonObject? = null,
) : JsonRpcMessage

data class JsonRpcNotification(
    val method: String,
    val params: JsonObject? = null,
) : JsonRpcMessage

data class JsonRpcResponse(
    val id: JsonRpcId,
    val result: JsonObject? = null,
) : JsonRpcMessage

data class JsonRpcErrorResponse(
    val id: JsonRpcId,
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
) : JsonRpcMessage

object JsonRpcCodec {
    fun encode(message: JsonRpcMessage): String = when (message) {
        is JsonRpcRequest -> buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", idJson(message.id))
            put("method", message.method)
            message.params?.let { put("params", it) }
        }.toString()

        is JsonRpcNotification -> buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", message.method)
            message.params?.let { put("params", it) }
        }.toString()

        is JsonRpcResponse -> buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", idJson(message.id))
            put("result", message.result ?: JsonObject(emptyMap()))
        }.toString()

        is JsonRpcErrorResponse -> buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", idJson(message.id))
            put(
                "error",
                buildJsonObject {
                    put("code", message.code)
                    put("message", message.message)
                    message.data?.let { put("data", it) }
                },
            )
        }.toString()
    }

    fun decode(text: String): JsonRpcMessage {
        val element = try {
            PiJson.parseToJsonElement(text)
        } catch (error: Exception) {
            throw McpProtocolException("Invalid JSON-RPC payload: ${error.message}", error)
        }
        return decodeElement(element)
    }

    fun decodeBatch(text: String): List<JsonRpcMessage> {
        val element = try {
            PiJson.parseToJsonElement(text)
        } catch (error: Exception) {
            throw McpProtocolException("Invalid JSON-RPC payload: ${error.message}", error)
        }
        return when (element) {
            is JsonArray -> element.map { decodeElement(it) }
            else -> listOf(decodeElement(element))
        }
    }

    fun decodeElement(element: JsonElement): JsonRpcMessage {
        val obj = element as? JsonObject
            ?: throw McpProtocolException("JSON-RPC message must be an object")
        val jsonrpc = (obj["jsonrpc"] as? JsonPrimitive)?.contentOrNull
        if (jsonrpc != "2.0") throw McpProtocolException("Unsupported JSON-RPC version: $jsonrpc")
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        val idElement = obj["id"]
        if (method != null) {
            val id = if (idElement == null || idElement is JsonNull) null else parseId(idElement)
            val params = obj["params"] as? JsonObject
            return if (id == null) JsonRpcNotification(method, params) else JsonRpcRequest(id, method, params)
        }
        val id = idElement?.let(::parseId) ?: throw McpProtocolException("JSON-RPC response without id")
        val error = obj["error"] as? JsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.longOrNull?.toInt()
                ?: throw McpProtocolException("JSON-RPC error without numeric code")
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: ""
            return JsonRpcErrorResponse(id, code, message, error["data"])
        }
        val result = obj["result"] as? JsonObject
        return JsonRpcResponse(id, result)
    }

    private fun idJson(id: JsonRpcId): JsonElement = when (id) {
        is JsonRpcId.Text -> JsonPrimitive(id.value)
        is JsonRpcId.Number -> JsonPrimitive(id.value)
    }

    private fun parseId(element: JsonElement): JsonRpcId = when (element) {
        is JsonPrimitive -> {
            if (element.isString) {
                JsonRpcId.Text(element.content)
            } else {
                val number = element.longOrNull
                    ?: throw McpProtocolException("JSON-RPC id must be a string or integer")
                JsonRpcId.Number(number)
            }
        }

        else -> throw McpProtocolException("JSON-RPC id must be a string or integer")
    }
}
