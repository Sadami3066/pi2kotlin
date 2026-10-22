package dev.pi.ai.api

import dev.pi.ai.AssistantImages
import dev.pi.ai.ImageContent
import dev.pi.ai.ImageModel
import dev.pi.ai.ImagesContext
import dev.pi.ai.ImagesOptions
import dev.pi.ai.ImagesStopReason
import dev.pi.ai.PiJson
import dev.pi.ai.TextContent
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val OPENROUTER_IMAGES_LABEL = "OpenRouter images"
private val DATA_URL_PATTERN = Regex("^data:([^;]+);base64,(.+)$")

class OpenRouterImagesApi(internal val transport: HttpTransport) : ImagesApiProvider {
    override suspend fun generateImages(
        model: ImageModel,
        context: ImagesContext,
        options: ImagesOptions,
    ): AssistantImages {
        val timestamp = System.currentTimeMillis()
        try {
            val apiKey = options.apiKey ?: throw IllegalStateException("No API key for provider: ${model.provider}")
            val headers = buildHeaders(model, options, apiKey)
            val params = buildParams(model, context)
            val body = PiJson.encodeToString(JsonObject.serializer(), params).encodeToByteArray()
            val url = model.baseUrl.trimEnd('/') + "/chat/completions"
            val response = executeImagesRequest(transport, url, headers, body, options)
            val text = try {
                response.body.readUtf8()
            } finally {
                response.close()
            }
            val json = PiJson.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("$OPENROUTER_IMAGES_LABEL returned an unexpected response")
            return parseResponse(model, json, timestamp)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return AssistantImages(
                api = model.api,
                provider = model.provider,
                model = model.id,
                output = emptyList(),
                stopReason = ImagesStopReason.Error,
                errorMessage = imagesErrorMessage(error),
                timestamp = timestamp,
            )
        }
    }
}

fun openRouterImagesApi(transport: HttpTransport): ImagesApiProvider = OpenRouterImagesApi(transport)

private fun buildHeaders(model: ImageModel, options: ImagesOptions, apiKey: String): Map<String, String> {
    val headers = providerIdentityHeaders(model, options.identity)
    model.headers?.let { modelHeaders ->
        for ((name, value) in modelHeaders) {
            headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach { headers.remove(it) }
            headers[name] = value
        }
    }
    applyHeaderOverrides(headers, options.headers)
    if (!hasHeaderName(headers, "authorization")) headers["Authorization"] = "Bearer $apiKey"
    if (!hasHeaderName(headers, "content-type")) headers["Content-Type"] = "application/json"
    if (!hasHeaderName(headers, "accept")) headers["Accept"] = "application/json"
    return headers
}

private fun buildParams(model: ImageModel, context: ImagesContext): JsonObject {
    val content = JsonArray(
        context.input.mapNotNull { item ->
            when (item) {
                is TextContent -> buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive(sanitizeSurrogates(item.text)))
                }

                is ImageContent -> buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put(
                        "image_url",
                        buildJsonObject {
                            put("url", JsonPrimitive("data:${item.mimeType};base64,${item.data}"))
                        },
                    )
                }

                else -> null
            }
        },
    )
    val modalities = if (model.output.contains("text")) listOf("image", "text") else listOf("image")
    return buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put(
            "messages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", content)
                    },
                ),
            ),
        )
        put("stream", JsonPrimitive(false))
        put("modalities", JsonArray(modalities.map(::JsonPrimitive)))
    }
}

private fun parseResponse(model: ImageModel, json: JsonObject, timestamp: Long): AssistantImages {
    val output = mutableListOf<dev.pi.ai.Content>()
    val responseId = (json["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val usage = imagesUsage(json["usage"], model)
    val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    val message = choice?.get("message") as? JsonObject
    if (message != null) {
        val content = (message["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (content != null && content.isNotEmpty()) {
            output.add(TextContent(text = content))
        }
        val images = message["images"] as? JsonArray
        images?.forEach { element ->
            val image = element as? JsonObject ?: return@forEach
            val imageUrl = imageUrl(image) ?: return@forEach
            if (!imageUrl.startsWith("data:")) return@forEach
            val match = DATA_URL_PATTERN.find(imageUrl) ?: return@forEach
            output.add(ImageContent(mimeType = match.groupValues[1], data = match.groupValues[2]))
        }
    }
    return AssistantImages(
        api = model.api,
        provider = model.provider,
        model = model.id,
        output = output,
        responseId = responseId,
        usage = usage,
        stopReason = ImagesStopReason.Stop,
        timestamp = timestamp,
    )
}

private fun imageUrl(image: JsonObject): String? = when (val value = image["image_url"]) {
    is JsonPrimitive -> if (value.isString) value.content else null
    is JsonObject -> (value["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    else -> null
}
