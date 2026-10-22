package dev.pi.ai

import kotlinx.serialization.Serializable

enum class ModelType { Chat, Image, Classifier }

sealed interface AnyModel {
    val id: String
    val name: String
    val api: String
    val provider: String
    val baseUrl: String
    val input: List<ModelInputModality>
    val cost: ModelCost
    val modelType: ModelType
}

@Serializable
data class Model(
    override val id: String,
    override val name: String,
    override val api: String,
    override val provider: String,
    override val baseUrl: String,
    override val input: List<ModelInputModality>,
    val inputLimits: ModelInputLimits? = null,
    override val cost: ModelCost,
    val headers: Map<String, String>? = null,
    val type: String? = null,
    val reasoning: Boolean,
    val thinkingLevelMap: Map<String, String?>? = null,
    val promptCache: Map<String, Double>? = null,
    val contextWindow: Long,
    val maxTokens: Long,
    val samplingParams: JsonObject? = null,
    val samplingParamsByThinkingLevel: Map<String, JsonObject>? = null,
    val compat: JsonObject? = null,
) : AnyModel {
    override val modelType: ModelType get() = ModelType.Chat

    fun supportsImageInput(): Boolean = input.contains(ModelInputModality.Image)
}

@Serializable
data class ImageModel(
    override val id: String,
    override val name: String,
    override val api: String,
    override val provider: String,
    override val baseUrl: String,
    override val input: List<ModelInputModality>,
    val inputLimits: ModelInputLimits? = null,
    override val cost: ModelCost,
    val headers: Map<String, String>? = null,
    val type: String = "image",
    val output: List<String>,
) : AnyModel {
    override val modelType: ModelType get() = ModelType.Image
}

@Serializable
data class ClassifierModel(
    override val id: String,
    override val name: String,
    override val api: String,
    override val provider: String,
    override val baseUrl: String,
    override val input: List<ModelInputModality>,
    val inputLimits: ModelInputLimits? = null,
    override val cost: ModelCost,
    val headers: Map<String, String>? = null,
    val type: String = "classifier",
    val contextWindow: Long,
) : AnyModel {
    override val modelType: ModelType get() = ModelType.Classifier
}

@Serializable
data class ModelImageResizeOptions(
    val maxWidth: Long? = null,
    val maxHeight: Long? = null,
    val maxBytes: Long? = null,
    val jpegQuality: Long? = null,
)

@Serializable
data class ModelImageInputLimits(
    val resize: ModelImageResizeOptions? = null,
    val maxPerMessage: Long? = null,
    val maxPerRequest: Long? = null,
)

@Serializable
data class ModelInputLimits(
    val maxRequestBytes: Long? = null,
    val images: ModelImageInputLimits? = null,
)

fun getModelType(model: AnyModel): ModelType = model.modelType
