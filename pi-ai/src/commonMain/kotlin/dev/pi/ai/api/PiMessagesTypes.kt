package dev.pi.ai.api

import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.RunSignal
import dev.pi.ai.StopReason
import dev.pi.ai.ToolCall
import dev.pi.ai.Usage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class PiMessagesRewriteImpact(
    val policyId: String,
    val policyVersion: Long,
    val changed: Boolean,
    val tokenCountChange: Long,
    val messageCountChange: Long,
    val systemPromptChanged: Boolean,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "policyId" to JsonPrimitive(policyId),
            "policyVersion" to JsonPrimitive(policyVersion),
            "changed" to JsonPrimitive(changed),
            "tokenCountChange" to JsonPrimitive(tokenCountChange),
            "messageCountChange" to JsonPrimitive(messageCountChange),
            "systemPromptChanged" to JsonPrimitive(systemPromptChanged),
        ),
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface PiMessagesEvent {
    @Serializable
    @SerialName("start")
    data object Start : PiMessagesEvent

    @Serializable
    @SerialName("text_start")
    data class TextStart(val contentIndex: Int) : PiMessagesEvent

    @Serializable
    @SerialName("text_delta")
    data class TextDelta(val contentIndex: Int, val delta: String) : PiMessagesEvent

    @Serializable
    @SerialName("text_end")
    data class TextEnd(
        val contentIndex: Int,
        val content: String,
        val contentSignature: String? = null,
    ) : PiMessagesEvent

    @Serializable
    @SerialName("thinking_start")
    data class ThinkingStart(val contentIndex: Int) : PiMessagesEvent

    @Serializable
    @SerialName("thinking_delta")
    data class ThinkingDelta(val contentIndex: Int, val delta: String) : PiMessagesEvent

    @Serializable
    @SerialName("thinking_end")
    data class ThinkingEnd(
        val contentIndex: Int,
        val content: String,
        val contentSignature: String? = null,
        val redacted: Boolean? = null,
    ) : PiMessagesEvent

    @Serializable
    @SerialName("toolcall_start")
    data class ToolCallStart(
        val contentIndex: Int,
        val id: String,
        val toolName: String,
    ) : PiMessagesEvent

    @Serializable
    @SerialName("toolcall_delta")
    data class ToolCallDelta(val contentIndex: Int, val delta: String) : PiMessagesEvent

    @Serializable
    @SerialName("toolcall_end")
    data class ToolCallEnd(val contentIndex: Int, val toolCall: ToolCall) : PiMessagesEvent

    @Serializable
    @SerialName("done")
    data class Done(
        val reason: StopReason,
        val usage: Usage,
        val responseId: String? = null,
        val providerThinkingLevel: String? = null,
        val rewrite: PiMessagesRewriteImpact? = null,
    ) : PiMessagesEvent

    @Serializable
    @SerialName("error")
    data class Error(
        val reason: StopReason,
        val usage: Usage,
        val errorMessage: String? = null,
        val responseId: String? = null,
        val providerThinkingLevel: String? = null,
        val rewrite: PiMessagesRewriteImpact? = null,
    ) : PiMessagesEvent
}

internal fun JsonElement?.piStringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

data class RadiusGatewayModel(
    val id: String,
    val name: String,
    val reasoning: Boolean,
    val thinkingLevelMap: Map<String, String?>? = null,
    val input: List<ModelInputModality>,
    val cost: ModelCost,
    val contextWindow: Long,
    val maxTokens: Long,
)

data class RadiusGatewayConfig(
    val baseUrl: String,
    val models: List<RadiusGatewayModel>,
)

interface RadiusGatewayCatalog {
    suspend fun load(gateway: String, apiKey: String?, signal: RunSignal?): RadiusGatewayConfig?
}

fun radiusGatewayModels(providerId: String, config: RadiusGatewayConfig): List<Model> = config.models.map { model ->
    Model(
        id = model.id,
        name = model.name,
        api = PI_MESSAGES_API_NAME,
        provider = providerId,
        baseUrl = config.baseUrl,
        input = model.input,
        cost = model.cost,
        reasoning = model.reasoning,
        thinkingLevelMap = model.thinkingLevelMap,
        contextWindow = model.contextWindow,
        maxTokens = model.maxTokens,
    )
}

internal const val PI_MESSAGES_API_NAME = "pi-messages"

@Serializable
data class PiMessagesErrorBody(
    val error: JsonElement? = null,
)

@Serializable
data class PiMessagesErrorPayload(
    val message: JsonElement? = null,
    val code: JsonElement? = null,
    val details: JsonElement? = null,
)
