package dev.pi.ai

import kotlinx.serialization.Serializable

typealias ProviderEnv = Map<String, String>

typealias ProviderHeaders = Map<String, String?>

typealias FetchFunction = suspend (url: String, init: JsonValue) -> JsonValue

@Serializable
data class TextSignatureV1(
    val v: Int = 1,
    val id: String,
    val phase: String? = null,
)

enum class GrammarFormat {
    OpenAILark,
    OpenAIRegex,
}

typealias GrammarVariants = Map<GrammarFormat, String>

sealed interface ConstrainedSamplingConfig

data class JsonSchemaConstrainedSampling(val strict: String) : ConstrainedSamplingConfig

data class GrammarConstrainedSampling(val variants: GrammarVariants) : ConstrainedSamplingConfig

data class ImagesContext(val input: List<Content>)

sealed interface ImagesStopReason {
    data object Stop : ImagesStopReason

    data object Error : ImagesStopReason

    data object Aborted : ImagesStopReason
}

data class AssistantImages(
    val api: String,
    val provider: String,
    val model: String,
    val output: List<Content>,
    val responseId: String? = null,
    val usage: Usage? = null,
    val stopReason: ImagesStopReason,
    val errorMessage: String? = null,
    val timestamp: Long,
)

sealed interface ClassifierQuestion

data class ClassifierChoiceQuestion(
    val instructions: String,
    val criteria: Map<String, String>,
) : ClassifierQuestion

data class ClassifierScoreQuestion(
    val instructions: String,
    val criteria: List<String>,
) : ClassifierQuestion

data class ClassifierBoolQuestion(
    val instructions: String,
    val criteria: ClassifierBoolCriteria,
) : ClassifierQuestion

data class ClassifierBoolCriteria(val trueText: String, val falseText: String)

data class ClassifierContext(
    val state: JsonObject,
    val images: List<ImageContent>? = null,
    val questions: Map<String, ClassifierQuestion>,
)

sealed interface ClassifierAnswer

data class ClassifierChoiceAnswer(
    val choice: String,
    val probabilities: Map<String, Double>,
    val confidence: Double,
) : ClassifierAnswer

data class ClassifierScoreAnswer(val score: Double, val confidence: Double) : ClassifierAnswer

data class ClassifierBoolAnswer(val probability: Double) : ClassifierAnswer

sealed interface ClassifierStopReason {
    data object Stop : ClassifierStopReason

    data object Error : ClassifierStopReason

    data object Aborted : ClassifierStopReason
}

data class ClassifierResult(
    val api: String,
    val provider: String,
    val model: String,
    val answers: Map<String, ClassifierAnswer>,
    val usage: Usage? = null,
    val stopReason: ClassifierStopReason,
    val errorMessage: String? = null,
    val timestamp: Long,
)
