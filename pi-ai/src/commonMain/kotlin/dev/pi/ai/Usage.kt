package dev.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsageCost(
    var input: Double,
    var output: Double,
    var cacheRead: Double,
    var cacheWrite: Double,
    var total: Double,
)

@Serializable
data class Usage(
    var input: Long,
    var output: Long,
    var cacheRead: Long,
    var cacheWrite: Long,
    var cacheWrite1h: Long? = null,
    var reasoning: Long? = null,
    var totalTokens: Long,
    var cost: UsageCost,
) {
    companion object {
        fun empty(): Usage = Usage(
            input = 0,
            output = 0,
            cacheRead = 0,
            cacheWrite = 0,
            totalTokens = 0,
            cost = UsageCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = 0.0),
        )
    }
}

@Serializable
enum class StopReason {
    @SerialName("pending") Pending,
    @SerialName("stop") Stop,
    @SerialName("length") Length,
    @SerialName("toolUse") ToolUse,
    @SerialName("error") Error,
    @SerialName("aborted") Aborted,
    @SerialName("deferred") Deferred,
}

@Serializable
data class DiagnosticErrorInfo(
    val name: String? = null,
    val message: String,
    val stack: String? = null,
    val code: String? = null,
)

@Serializable
data class AssistantMessageDiagnostic(
    val type: String,
    val timestamp: Long,
    val error: DiagnosticErrorInfo? = null,
    val details: JsonObject? = null,
)

@Serializable
data class ModelCostRates(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

@Serializable
data class ModelCostTier(
    val inputTokensAbove: Long,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

@Serializable
data class ModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val tiers: List<ModelCostTier>? = null,
)
