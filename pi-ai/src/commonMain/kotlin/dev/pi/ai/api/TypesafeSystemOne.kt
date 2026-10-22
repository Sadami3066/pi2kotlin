package dev.pi.ai.api

import dev.pi.ai.ClassifierAnswer
import dev.pi.ai.ClassifierBoolAnswer
import dev.pi.ai.ClassifierBoolQuestion
import dev.pi.ai.ClassifierChoiceAnswer
import dev.pi.ai.ClassifierChoiceQuestion
import dev.pi.ai.ClassifierContext
import dev.pi.ai.ClassifierModel
import dev.pi.ai.ClassifierOptions
import dev.pi.ai.ClassifierQuestion
import dev.pi.ai.ClassifierResult
import dev.pi.ai.ClassifierScoreAnswer
import dev.pi.ai.ClassifierScoreQuestion
import dev.pi.ai.ClassifierStopReason
import dev.pi.ai.Usage
import dev.pi.ai.http.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

internal interface SystemOneTransport {
    val api: String
    val label: String
    fun url(model: ClassifierModel): String
    fun payload(model: ClassifierModel, request: JsonObject): JsonObject
    fun output(body: JsonElement): JsonObject
}

class TypesafeSystemOneApi(internal val transport: HttpTransport) : ClassifierApiProvider {
    override suspend fun classify(
        model: ClassifierModel,
        context: ClassifierContext,
        options: ClassifierOptions,
    ): ClassifierResult = classifySystemOne(typesafeSystemOneTransport, transport, model, context, options)
}

fun typesafeSystemOneApi(transport: HttpTransport): ClassifierApiProvider = TypesafeSystemOneApi(transport)

private val typesafeSystemOneTransport: SystemOneTransport = object : SystemOneTransport {
    override val api: String = "typesafe-system-one"
    override val label: String = "System One API"

    override fun url(model: ClassifierModel): String = model.baseUrl.trimEnd('/') + "/systemone"

    override fun payload(model: ClassifierModel, request: JsonObject): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(model.id))
        for ((key, value) in request) put(key, value)
    }

    override fun output(body: JsonElement): JsonObject {
        if (body !is JsonObject) throw IllegalStateException("System One API returned an unexpected response")
        return body
    }
}

internal suspend fun classifySystemOne(
    systemOneTransport: SystemOneTransport,
    transport: HttpTransport,
    model: ClassifierModel,
    context: ClassifierContext,
    options: ClassifierOptions,
): ClassifierResult {
    val timestamp = System.currentTimeMillis()
    var usage: Usage? = null
    try {
        if (model.api != systemOneTransport.api) {
            throw IllegalStateException("Unsupported classifier API: ${model.api}")
        }
        if (!context.images.isNullOrEmpty()) {
            throw IllegalStateException("${systemOneTransport.label} does not support image input")
        }
        val request = systemOneWireRequest(context)
        val payload = systemOneTransport.payload(model, request)
        val response = postClassifierRequest(
            transport = transport,
            label = systemOneTransport.label,
            url = systemOneTransport.url(model),
            model = model,
            body = payload,
            options = options,
        )
        val result = systemOneTransport.output(response)
        usage = parseClassifierUsage(result["usage"], model)
        val answers = systemOneParseAnswers(systemOneTransport.label, result["answers"], context)
        return ClassifierResult(
            api = model.api,
            provider = model.provider,
            model = model.id,
            answers = answers,
            usage = usage,
            stopReason = ClassifierStopReason.Stop,
            timestamp = timestamp,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        return ClassifierResult(
            api = model.api,
            provider = model.provider,
            model = model.id,
            answers = emptyMap(),
            usage = usage,
            stopReason = ClassifierStopReason.Error,
            errorMessage = classifierHttpErrorMessage(error, "${systemOneTransport.label} error"),
            timestamp = timestamp,
        )
    }
}

private fun systemOneWireRequest(context: ClassifierContext): JsonObject = buildJsonObject {
    put("state", context.state)
    put(
        "questions",
        JsonObject(
            context.questions.mapValues { (_, question) -> systemOneWireQuestion(question) },
        ),
    )
}

private fun systemOneWireQuestion(question: ClassifierQuestion): JsonObject = buildJsonObject {
    when (question) {
        is ClassifierChoiceQuestion -> {
            put("type", JsonPrimitive("choice"))
            put("instructions", JsonPrimitive(question.instructions))
            put(
                "criteria",
                JsonObject(question.criteria.mapValues { (_, description) -> JsonPrimitive(description) }),
            )
        }

        is ClassifierScoreQuestion -> {
            put("type", JsonPrimitive("score"))
            put("instructions", JsonPrimitive(question.instructions))
            put("criteria", JsonArray(question.criteria.map(::JsonPrimitive)))
        }

        is ClassifierBoolQuestion -> {
            put("type", JsonPrimitive("noul"))
            put("instructions", JsonPrimitive(question.instructions))
            put(
                "criteria",
                JsonObject(
                    mapOf(
                        "true" to JsonPrimitive(question.criteria.trueText),
                        "false" to JsonPrimitive(question.criteria.falseText),
                    ),
                ),
            )
        }
    }
}

private fun systemOneParseAnswers(
    label: String,
    value: JsonElement?,
    context: ClassifierContext,
): Map<String, ClassifierAnswer> {
    if (value !is JsonObject) throw IllegalStateException("$label returned an unexpected response")
    val answers = LinkedHashMap<String, ClassifierAnswer>()
    for ((id, question) in context.questions) {
        val answer = value[id] as? JsonObject
            ?: throw IllegalStateException("$label did not return an answer for $id")
        answers[id] = when (question) {
            is ClassifierChoiceQuestion -> {
                val type = (answer["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val choice = (answer["choice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type != "choice" || choice == null) {
                    throw IllegalStateException("$label did not return a choice answer for $id")
                }
                ClassifierChoiceAnswer(
                    choice = choice,
                    probabilities = systemOneProbabilities(label, answer["probabilities"], id),
                    confidence = requiredClassifierNumber(label, answer["confidence"], "confidence for $id"),
                )
            }

            is ClassifierScoreQuestion -> {
                val type = (answer["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type != "score") throw IllegalStateException("$label did not return a score answer for $id")
                ClassifierScoreAnswer(
                    score = requiredClassifierNumber(label, answer["score"], "score for $id"),
                    confidence = requiredClassifierNumber(label, answer["confidence"], "confidence for $id"),
                )
            }

            is ClassifierBoolQuestion -> {
                val type = (answer["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type != "noul") throw IllegalStateException("$label did not return a bool answer for $id")
                ClassifierBoolAnswer(
                    probability = requiredClassifierNumber(label, answer["noul"], "probability for $id"),
                )
            }
        }
    }
    return answers
}

private fun systemOneProbabilities(label: String, value: JsonElement?, id: String): Map<String, Double> {
    if (value !is JsonObject) throw IllegalStateException("$label returned invalid probabilities for $id")
    val probabilities = LinkedHashMap<String, Double>()
    for ((key, entry) in value) {
        probabilities[key] = requiredClassifierNumber(label, entry, "probability for $id.$key")
    }
    return probabilities
}
