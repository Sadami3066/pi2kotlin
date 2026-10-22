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
import dev.pi.ai.utils.jsJsonStringify
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

private const val DECISIONS_LABEL = "OpenAI Decisions"
private const val MAX_DECISION_IMAGES = 128
private val DECISIONS_NO_RETRY_STATUSES = listOf(504)

class OpenAiDecisionsApi(internal val transport: HttpTransport) : ClassifierApiProvider {
    override suspend fun classify(
        model: ClassifierModel,
        context: ClassifierContext,
        options: ClassifierOptions,
    ): ClassifierResult {
        val timestamp = System.currentTimeMillis()
        var usage: Usage? = null
        try {
            if (model.api != "openai-decisions") throw IllegalStateException("Unsupported classifier API: ${model.api}")
            val body = buildJsonObject {
                put("model", JsonPrimitive(model.id))
                put("input", decisionsWireInput(context))
                put("questions", JsonArray(context.questions.map { (id, question) -> decisionsWireQuestion(id, question) }))
            }
            val url = model.baseUrl.trimEnd('/') + "/decisions"
            val response = postClassifierRequest(
                transport = transport,
                label = DECISIONS_LABEL,
                url = url,
                model = model,
                body = body,
                options = options,
                noRetryStatuses = DECISIONS_NO_RETRY_STATUSES,
            )
            if (response !is JsonObject) throw IllegalStateException("$DECISIONS_LABEL returned an unexpected response")
            usage = parseClassifierUsage(response["usage"], model)
            val answers = decisionsParseAnswers(response["answers"], context)
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
            val message = if (error is ClassifierApiHttpException && error.status == 504) {
                "$DECISIONS_LABEL error (504): the request timed out at the gateway. " +
                    "Very large inputs (above roughly 600K tokens) currently exceed its time limit."
            } else {
                classifierHttpErrorMessage(error, "$DECISIONS_LABEL error")
            }
            return ClassifierResult(
                api = model.api,
                provider = model.provider,
                model = model.id,
                answers = emptyMap(),
                usage = usage,
                stopReason = ClassifierStopReason.Error,
                errorMessage = message,
                timestamp = timestamp,
            )
        }
    }
}

fun openAiDecisionsApi(transport: HttpTransport): ClassifierApiProvider = OpenAiDecisionsApi(transport)

private fun predicateInstructions(question: ClassifierBoolQuestion): String {
    val meanings = buildList {
        if (question.criteria.trueText.isNotEmpty()) add("True means: ${question.criteria.trueText}")
        if (question.criteria.falseText.isNotEmpty()) add("False means: ${question.criteria.falseText}")
    }
    return if (meanings.isEmpty()) question.instructions else question.instructions + "\n\n" + meanings.joinToString("\n")
}

private fun decisionsWireQuestion(id: String, question: ClassifierQuestion): JsonObject = when (question) {
    is ClassifierChoiceQuestion -> buildJsonObject {
        put("type", JsonPrimitive("choice"))
        put("name", JsonPrimitive(id))
        put("instructions", JsonPrimitive(question.instructions))
        put(
            "choices",
            JsonArray(
                question.criteria.map { (value, description) ->
                    if (description.isNotEmpty()) {
                        buildJsonObject {
                            put("value", JsonPrimitive(value))
                            put("description", JsonPrimitive(description))
                        }
                    } else {
                        buildJsonObject { put("value", JsonPrimitive(value)) }
                    }
                },
            ),
        )
    }

    is ClassifierScoreQuestion -> buildJsonObject {
        put("type", JsonPrimitive("score"))
        put("name", JsonPrimitive(id))
        put("instructions", JsonPrimitive(question.instructions))
        put(
            "levels",
            JsonArray(
                question.criteria.map { label ->
                    buildJsonObject { put("label", JsonPrimitive(label)) }
                },
            ),
        )
    }

    is ClassifierBoolQuestion -> buildJsonObject {
        put("type", JsonPrimitive("predicate"))
        put("name", JsonPrimitive(id))
        put("instructions", JsonPrimitive(predicateInstructions(question)))
    }
}

private fun decisionsWireInput(context: ClassifierContext): JsonElement {
    val state = jsJsonStringify(context.state)
    val images = context.images.orEmpty()
    if (images.isEmpty()) return JsonPrimitive(state)
    if (images.size > MAX_DECISION_IMAGES) {
        throw IllegalStateException("$DECISIONS_LABEL accepts at most $MAX_DECISION_IMAGES images, got ${images.size}")
    }
    val content = buildJsonArray {
        add(
            buildJsonObject {
                put("type", JsonPrimitive("input_text"))
                put("text", JsonPrimitive(state))
            },
        )
        for (image in images) {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    put("image_url", JsonPrimitive("data:${image.mimeType};base64,${image.data}"))
                },
            )
        }
    }
    return JsonArray(
        listOf(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", content)
            },
        ),
    )
}

private fun decisionsParseAnswers(value: JsonElement?, context: ClassifierContext): Map<String, ClassifierAnswer> {
    if (value !is JsonArray) throw IllegalStateException("$DECISIONS_LABEL returned an unexpected response")
    val byName = LinkedHashMap<String, JsonObject>()
    for (element in value) {
        val answer = element as? JsonObject ?: continue
        val name = (answer["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
        byName[name] = answer
    }
    val answers = LinkedHashMap<String, ClassifierAnswer>()
    for ((id, question) in context.questions) {
        val answer = byName[id] ?: throw IllegalStateException("$DECISIONS_LABEL did not return an answer for $id")
        answers[id] = decisionsParseAnswer(id, question, answer)
    }
    return answers
}

private fun decisionsParseAnswer(id: String, question: ClassifierQuestion, answer: JsonObject): ClassifierAnswer {
    val type = (answer["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (type == "refusal") throw IllegalStateException("$DECISIONS_LABEL refused to answer $id")
    return when (question) {
        is ClassifierChoiceQuestion -> {
            val choice = (answer["choice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type != "choice" || choice == null) {
                throw IllegalStateException("$DECISIONS_LABEL did not return a choice answer for $id")
            }
            ClassifierChoiceAnswer(
                choice = choice,
                probabilities = decisionsProbabilities(answer["probabilities"], id),
                confidence = requiredClassifierNumber(DECISIONS_LABEL, answer["confidence"], "confidence for $id"),
            )
        }

        is ClassifierScoreQuestion -> {
            if (type != "score") throw IllegalStateException("$DECISIONS_LABEL did not return a score answer for $id")
            ClassifierScoreAnswer(
                score = requiredClassifierNumber(DECISIONS_LABEL, answer["score"], "score for $id"),
                confidence = requiredClassifierNumber(DECISIONS_LABEL, answer["confidence"], "confidence for $id"),
            )
        }

        is ClassifierBoolQuestion -> {
            if (type != "predicate") {
                throw IllegalStateException("$DECISIONS_LABEL did not return a predicate answer for $id")
            }
            ClassifierBoolAnswer(
                probability = requiredClassifierNumber(DECISIONS_LABEL, answer["probability"], "probability for $id"),
            )
        }
    }
}

private fun decisionsProbabilities(value: JsonElement?, id: String): Map<String, Double> {
    if (value !is JsonArray) throw IllegalStateException("$DECISIONS_LABEL returned invalid probabilities for $id")
    val probabilities = LinkedHashMap<String, Double>()
    for (element in value) {
        val entry = element as? JsonObject
            ?: throw IllegalStateException("$DECISIONS_LABEL returned invalid probabilities for $id")
        val choiceValue = (entry["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalStateException("$DECISIONS_LABEL returned invalid probabilities for $id")
        probabilities[choiceValue] = requiredClassifierNumber(
            DECISIONS_LABEL,
            entry["probability"],
            "probability for $id.$choiceValue",
        )
    }
    return probabilities
}
