package dev.pi.ai

import kotlinx.serialization.Serializable

@Serializable
data class Model(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val input: List<ModelInputModality>,
    val inputLimits: ModelInputLimits? = null,
    val cost: ModelCost,
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
) {
    fun supportsImageInput(): Boolean = input.contains(ModelInputModality.Image)
}

@Serializable
data class ImageModel(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val input: List<ModelInputModality>,
    val inputLimits: ModelInputLimits? = null,
    val cost: ModelCost,
    val headers: Map<String, String>? = null,
    val type: String = "image",
    val output: List<String>,
)

@Serializable
data class ClassifierModel(
    val id: String,
    val name: String,
    val api: String,
    val provider: String,
    val baseUrl: String,
    val input: List<ModelInputModality>,
    val inputLimits: ModelInputLimits? = null,
    val cost: ModelCost,
    val headers: Map<String, String>? = null,
    val type: String = "classifier",
    val contextWindow: Long,
)

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
