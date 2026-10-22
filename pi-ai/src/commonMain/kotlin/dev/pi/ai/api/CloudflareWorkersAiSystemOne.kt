package dev.pi.ai.api

import dev.pi.ai.ClassifierContext
import dev.pi.ai.ClassifierModel
import dev.pi.ai.ClassifierOptions
import dev.pi.ai.ClassifierResult
import dev.pi.ai.http.HttpTransport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val CLOUDFLARE_WORKERS_AI_LABEL = "Cloudflare Workers AI"

class CloudflareWorkersAiSystemOneApi(internal val transport: HttpTransport) : ClassifierApiProvider {
    override suspend fun classify(
        model: ClassifierModel,
        context: ClassifierContext,
        options: ClassifierOptions,
    ): ClassifierResult = classifySystemOne(cloudflareSystemOneTransport, transport, model, context, options)
}

fun cloudflareWorkersAiSystemOneApi(transport: HttpTransport): ClassifierApiProvider =
    CloudflareWorkersAiSystemOneApi(transport)

private val cloudflareSystemOneTransport: SystemOneTransport = object : SystemOneTransport {
    override val api: String = "cloudflare-workers-ai-system-one"
    override val label: String = CLOUDFLARE_WORKERS_AI_LABEL

    override fun url(model: ClassifierModel): String = model.baseUrl.trimEnd('/') + "/run"

    override fun payload(model: ClassifierModel, request: JsonObject): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put("input", request)
    }

    override fun output(body: JsonElement): JsonObject {
        if (body !is JsonObject) throw IllegalStateException("$CLOUDFLARE_WORKERS_AI_LABEL returned an unexpected response")
        if ((body["success"] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "false") {
            throw IllegalStateException(cloudflareErrorMessage(body["errors"]))
        }
        val result = body["result"]
        if (result !is JsonObject) throw IllegalStateException("$CLOUDFLARE_WORKERS_AI_LABEL returned an unexpected response")
        if (result.containsKey("answers")) return result
        val state = (result["state"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (state != "Completed") {
            throw IllegalStateException("$CLOUDFLARE_WORKERS_AI_LABEL run did not complete (state: $state)")
        }
        val nested = result["result"]
        if (nested !is JsonObject) throw IllegalStateException("$CLOUDFLARE_WORKERS_AI_LABEL returned an unexpected response")
        return nested
    }
}

private fun cloudflareErrorMessage(errors: JsonElement?): String {
    if (errors is kotlinx.serialization.json.JsonArray) {
        val messages = errors.mapNotNull { error ->
            (error as? JsonObject)?.let { obj -> (obj["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        }
        if (messages.isNotEmpty()) return "$CLOUDFLARE_WORKERS_AI_LABEL error: ${messages.joinToString("; ")}"
    }
    return "$CLOUDFLARE_WORKERS_AI_LABEL request failed"
}
