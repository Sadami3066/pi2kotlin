package dev.pi.coding.models

import dev.pi.ai.ModelCost
import dev.pi.ai.ModelCostTier
import dev.pi.ai.ModelImageInputLimits
import dev.pi.ai.ModelImageResizeOptions
import dev.pi.ai.ModelInputLimits
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PiJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class CustomModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val tiers: List<ModelCostTier>? = null,
) {
    fun toModelCost(): ModelCost = ModelCost(input, output, cacheRead, cacheWrite, tiers)
}

data class CustomModelOverrideCost(
    val input: Double? = null,
    val output: Double? = null,
    val cacheRead: Double? = null,
    val cacheWrite: Double? = null,
    val tiers: List<ModelCostTier>? = null,
)

data class CustomModelConfig(
    val id: String,
    val name: String? = null,
    val api: String? = null,
    val baseUrl: String? = null,
    val reasoning: Boolean? = null,
    val thinkingLevelMap: Map<String, String?>? = null,
    val input: List<ModelInputModality>? = null,
    val inputLimits: ModelInputLimits? = null,
    val cost: CustomModelCost? = null,
    val promptCache: Map<String, Double>? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val samplingParams: JsonObject? = null,
    val samplingParamsByThinkingLevel: Map<String, JsonObject>? = null,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)

data class CustomModelOverride(
    val name: String? = null,
    val reasoning: Boolean? = null,
    val thinkingLevelMap: Map<String, String?>? = null,
    val input: List<ModelInputModality>? = null,
    val inputLimits: ModelInputLimits? = null,
    val cost: CustomModelOverrideCost? = null,
    val promptCache: Map<String, Double>? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val samplingParams: JsonObject? = null,
    val samplingParamsByThinkingLevel: Map<String, JsonObject>? = null,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
)

data class CustomProviderConfig(
    val id: String,
    val name: String? = null,
    val baseUrl: String? = null,
    val api: String? = null,
    val apiKey: String? = null,
    val headers: Map<String, String>? = null,
    val compat: JsonObject? = null,
    val authHeader: Boolean = false,
    val oauth: String? = null,
    val models: List<CustomModelConfig> = emptyList(),
    val modelOverrides: Map<String, CustomModelOverride> = emptyMap(),
)

class CustomModelsLoader {
    fun parse(text: String): List<CustomProviderConfig> {
        val stripped = stripJsonComments(stripBom(text)).trim()
        if (stripped.isEmpty()) return emptyList()
        val root = try {
            PiJson.parseToJsonElement(stripped)
        } catch (error: Throwable) {
            throw IllegalArgumentException("Failed to parse models.json: ${error.message}")
        }
        val rootObject = root as? JsonObject
            ?: throw IllegalArgumentException("Invalid models.json: root must be a JSON object")
        val providersElement = rootObject["providers"] ?: return emptyList()
        val providersObject = providersElement as? JsonObject
            ?: throw IllegalArgumentException("Invalid models.json: providers must be an object")
        return providersObject.map { (providerId, element) ->
            val providerObject = element as? JsonObject
                ?: throw IllegalArgumentException("Invalid models.json: providers.$providerId must be an object")
            parseProvider(providerId, providerObject)
        }
    }

    companion object {
        fun parse(text: String): List<CustomProviderConfig> = CustomModelsLoader().parse(text)
    }
}

private fun stripBom(text: String): String = if (text.startsWith("\uFEFF")) text.substring(1) else text

private fun stripJsonComments(input: String): String {
    val withoutComments = StringBuilder(input.length)
    var index = 0
    var inString = false
    while (index < input.length) {
        val char = input[index]
        if (inString) {
            withoutComments.append(char)
            when {
                char == '\\' && index + 1 < input.length -> {
                    withoutComments.append(input[index + 1])
                    index += 2
                }

                char == '"' -> {
                    inString = false
                    index += 1
                }

                else -> index += 1
            }
            continue
        }
        if (char == '"') {
            inString = true
            withoutComments.append(char)
            index += 1
            continue
        }
        if (char == '/' && index + 1 < input.length && input[index + 1] == '/') {
            while (index < input.length && input[index] != '\n') index += 1
            continue
        }
        withoutComments.append(char)
        index += 1
    }

    val text = withoutComments.toString()
    val withoutTrailingCommas = StringBuilder(text.length)
    index = 0
    inString = false
    while (index < text.length) {
        val char = text[index]
        if (inString) {
            withoutTrailingCommas.append(char)
            when {
                char == '\\' && index + 1 < text.length -> {
                    withoutTrailingCommas.append(text[index + 1])
                    index += 2
                }

                char == '"' -> {
                    inString = false
                    index += 1
                }

                else -> index += 1
            }
            continue
        }
        if (char == '"') {
            inString = true
            withoutTrailingCommas.append(char)
            index += 1
            continue
        }
        if (char == ',') {
            var next = index + 1
            while (next < text.length && text[next].isWhitespace()) next += 1
            if (next < text.length && (text[next] == '}' || text[next] == ']')) {
                index += 1
                continue
            }
        }
        withoutTrailingCommas.append(char)
        index += 1
    }
    return withoutTrailingCommas.toString()
}

private fun invalid(message: String): Nothing = throw IllegalArgumentException("Invalid models.json: $message")

private fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: invalid("$key must be a string")
    if (!primitive.isString) invalid("$key must be a string")
    return primitive.content
}

private fun JsonObject.booleanOrNull(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: invalid("$key must be a boolean")
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> invalid("$key must be a boolean")
    }
}

private fun JsonObject.longOrNull(key: String): Long? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: invalid("$key must be a number")
    if (primitive.isString) invalid("$key must be a number")
    return primitive.content.toLongOrNull() ?: primitive.content.toDoubleOrNull()?.toLong()
        ?: invalid("$key must be a number")
}

private fun JsonObject.doubleOrNull(key: String): Double? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive ?: invalid("$key must be a number")
    if (primitive.isString) invalid("$key must be a number")
    return primitive.content.toDoubleOrNull() ?: invalid("$key must be a number")
}

private fun JsonObject.objectOrNull(key: String): JsonObject? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element as? JsonObject ?: invalid("$key must be an object")
}

private fun JsonObject.arrayOrNull(key: String): JsonArray? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return element as? JsonArray ?: invalid("$key must be an array")
}

private fun parseProvider(providerId: String, json: JsonObject): CustomProviderConfig {
    val models = json.arrayOrNull("models")?.mapIndexed { index, element ->
        val modelObject = element as? JsonObject
            ?: invalid("providers.$providerId.models[$index] must be an object")
        parseModel(providerId, index, modelObject)
    }.orEmpty()
    val modelOverrides = json.objectOrNull("modelOverrides")?.map { (modelId, element) ->
        val overrideObject = element as? JsonObject
            ?: invalid("providers.$providerId.modelOverrides.$modelId must be an object")
        modelId to parseModelOverride(providerId, modelId, overrideObject)
    }?.toMap().orEmpty()
    return CustomProviderConfig(
        id = providerId,
        name = json.stringOrNull("name"),
        baseUrl = json.stringOrNull("baseUrl"),
        api = json.stringOrNull("api"),
        apiKey = json.stringOrNull("apiKey"),
        headers = parseStringMap(json.objectOrNull("headers"), "providers.$providerId.headers"),
        compat = json.objectOrNull("compat"),
        authHeader = json.booleanOrNull("authHeader") ?: false,
        oauth = json.stringOrNull("oauth"),
        models = models,
        modelOverrides = modelOverrides,
    )
}

private fun parseModel(providerId: String, index: Int, json: JsonObject): CustomModelConfig {
    val path = "providers.$providerId.models[$index]"
    val id = json.stringOrNull("id")
    if (id.isNullOrEmpty()) invalid("$path.id must be a non-empty string")
    val contextWindow = json.longOrNull("contextWindow")?.also {
        if (it <= 0) invalid("$path.contextWindow must be greater than 0")
    }
    val maxTokens = json.longOrNull("maxTokens")?.also {
        if (it <= 0) invalid("$path.maxTokens must be greater than 0")
    }
    return CustomModelConfig(
        id = id,
        name = json.stringOrNull("name"),
        api = json.stringOrNull("api"),
        baseUrl = json.stringOrNull("baseUrl"),
        reasoning = json.booleanOrNull("reasoning"),
        thinkingLevelMap = parseThinkingLevelMap(json.objectOrNull("thinkingLevelMap"), "$path.thinkingLevelMap"),
        input = parseInput(json.arrayOrNull("input"), "$path.input"),
        inputLimits = parseInputLimits(json.objectOrNull("inputLimits"), "$path.inputLimits"),
        cost = parseModelCost(json.objectOrNull("cost"), "$path.cost"),
        promptCache = parsePromptCache(json.objectOrNull("promptCache"), "$path.promptCache"),
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        samplingParams = json.objectOrNull("samplingParams"),
        samplingParamsByThinkingLevel = parseSamplingParamsByThinkingLevel(
            json.objectOrNull("samplingParamsByThinkingLevel"),
            "$path.samplingParamsByThinkingLevel",
        ),
        headers = parseStringMap(json.objectOrNull("headers"), "$path.headers"),
        compat = json.objectOrNull("compat"),
    )
}

private fun parseModelOverride(providerId: String, modelId: String, json: JsonObject): CustomModelOverride {
    val path = "providers.$providerId.modelOverrides.$modelId"
    val contextWindow = json.longOrNull("contextWindow")?.also {
        if (it <= 0) invalid("$path.contextWindow must be greater than 0")
    }
    val maxTokens = json.longOrNull("maxTokens")?.also {
        if (it <= 0) invalid("$path.maxTokens must be greater than 0")
    }
    val costObject = json.objectOrNull("cost")
    val cost = costObject?.let { costJson ->
        CustomModelOverrideCost(
            input = costJson.doubleOrNull("input"),
            output = costJson.doubleOrNull("output"),
            cacheRead = costJson.doubleOrNull("cacheRead"),
            cacheWrite = costJson.doubleOrNull("cacheWrite"),
            tiers = parseCostTiers(costJson.arrayOrNull("tiers"), "$path.cost.tiers"),
        )
    }
    return CustomModelOverride(
        name = json.stringOrNull("name"),
        reasoning = json.booleanOrNull("reasoning"),
        thinkingLevelMap = parseThinkingLevelMap(json.objectOrNull("thinkingLevelMap"), "$path.thinkingLevelMap"),
        input = parseInput(json.arrayOrNull("input"), "$path.input"),
        inputLimits = parseInputLimits(json.objectOrNull("inputLimits"), "$path.inputLimits"),
        cost = cost,
        promptCache = parsePromptCache(json.objectOrNull("promptCache"), "$path.promptCache"),
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        samplingParams = json.objectOrNull("samplingParams"),
        samplingParamsByThinkingLevel = parseSamplingParamsByThinkingLevel(
            json.objectOrNull("samplingParamsByThinkingLevel"),
            "$path.samplingParamsByThinkingLevel",
        ),
        headers = parseStringMap(json.objectOrNull("headers"), "$path.headers"),
        compat = json.objectOrNull("compat"),
    )
}

private fun parseStringMap(json: JsonObject?, path: String): Map<String, String>? {
    if (json == null) return null
    val result = LinkedHashMap<String, String>()
    for ((key, element) in json) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) invalid("$path.$key must be a string")
        result[key] = primitive.content
    }
    return result
}

private fun parseThinkingLevelMap(json: JsonObject?, path: String): Map<String, String?>? {
    if (json == null) return null
    val result = LinkedHashMap<String, String?>()
    for ((key, element) in json) {
        if (element is JsonNull) {
            result[key] = null
            continue
        }
        val primitive = element as? JsonPrimitive
        if (primitive == null || !primitive.isString) invalid("$path.$key must be a string or null")
        result[key] = primitive.content
    }
    return result
}

private fun parseInput(json: JsonArray?, path: String): List<ModelInputModality>? {
    if (json == null) return null
    return json.mapIndexed { index, element ->
        when ((element as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text" -> ModelInputModality.Text
            "image" -> ModelInputModality.Image
            else -> invalid("$path[$index] must be \"text\" or \"image\"")
        }
    }
}

private fun parseModelCost(json: JsonObject?, path: String): CustomModelCost? {
    if (json == null) return null
    val input = json.doubleOrNull("input") ?: invalid("$path.input is required")
    val output = json.doubleOrNull("output") ?: invalid("$path.output is required")
    val cacheRead = json.doubleOrNull("cacheRead") ?: invalid("$path.cacheRead is required")
    val cacheWrite = json.doubleOrNull("cacheWrite") ?: invalid("$path.cacheWrite is required")
    return CustomModelCost(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        tiers = parseCostTiers(json.arrayOrNull("tiers"), "$path.tiers"),
    )
}

private fun parseCostTiers(json: JsonArray?, path: String): List<ModelCostTier>? {
    if (json == null) return null
    return json.mapIndexed { index, element ->
        val tierObject = element as? JsonObject ?: invalid("$path[$index] must be an object")
        val tierPath = "$path[$index]"
        ModelCostTier(
            inputTokensAbove = tierObject.longOrNull("inputTokensAbove")
                ?: invalid("$tierPath.inputTokensAbove is required"),
            input = tierObject.doubleOrNull("input") ?: invalid("$tierPath.input is required"),
            output = tierObject.doubleOrNull("output") ?: invalid("$tierPath.output is required"),
            cacheRead = tierObject.doubleOrNull("cacheRead") ?: invalid("$tierPath.cacheRead is required"),
            cacheWrite = tierObject.doubleOrNull("cacheWrite") ?: invalid("$tierPath.cacheWrite is required"),
        )
    }
}

private fun parsePromptCache(json: JsonObject?, path: String): Map<String, Double>? {
    if (json == null) return null
    val result = LinkedHashMap<String, Double>()
    for ((key, element) in json) {
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) invalid("$path.$key must be a number")
        result[key] = primitive.content.toDoubleOrNull() ?: invalid("$path.$key must be a number")
    }
    return result
}

private fun parseSamplingParamsByThinkingLevel(
    json: JsonObject?,
    path: String,
): Map<String, JsonObject>? {
    if (json == null) return null
    val result = LinkedHashMap<String, JsonObject>()
    for ((level, element) in json) {
        val value = element as? JsonObject ?: invalid("$path.$level must be an object")
        result[level] = value
    }
    return result
}

private fun parseInputLimits(json: JsonObject?, path: String): ModelInputLimits? {
    if (json == null) return null
    val images = json.objectOrNull("images")?.let { imagesJson -> parseImageInputLimits(imagesJson, "$path.images") }
    return ModelInputLimits(
        maxRequestBytes = json.longOrNull("maxRequestBytes"),
        images = images,
    )
}

private fun parseImageInputLimits(json: JsonObject, path: String): ModelImageInputLimits {
    val resize = json.objectOrNull("resize")?.let { resizeJson -> parseImageResize(resizeJson, "$path.resize") }
    return ModelImageInputLimits(
        resize = resize,
        maxPerMessage = json.longOrNull("maxPerMessage"),
        maxPerRequest = json.longOrNull("maxPerRequest"),
    )
}

private fun parseImageResize(json: JsonObject, path: String): ModelImageResizeOptions = ModelImageResizeOptions(
    maxWidth = json.longOrNull("maxWidth"),
    maxHeight = json.longOrNull("maxHeight"),
    maxBytes = json.longOrNull("maxBytes"),
    jpegQuality = json.longOrNull("jpegQuality"),
)

internal fun JsonElement.stringOrNullValue(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
