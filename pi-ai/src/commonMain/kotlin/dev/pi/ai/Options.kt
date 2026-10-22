package dev.pi.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModelInputModality {
    @SerialName("text") Text,
    @SerialName("image") Image,
}

@Serializable
enum class ThinkingLevel {
    @SerialName("minimal") Minimal,
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
    @SerialName("xhigh") Xhigh,
    @SerialName("max") Max,
}

@Serializable
enum class ModelThinkingLevel {
    @SerialName("off") Off,
    @SerialName("minimal") Minimal,
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
    @SerialName("xhigh") Xhigh,
    @SerialName("max") Max,
}

@Serializable
enum class CacheRetention {
    @SerialName("none") None,
    @SerialName("short") Short,
    @SerialName("long") Long,
}

@Serializable
enum class Transport {
    @SerialName("sse") Sse,

    @SerialName("websocket") WebSocket,

    @SerialName("websocket-cached") WebSocketCached,

    @SerialName("auto") Auto,
}

@Serializable
enum class ToolChoice {
    @SerialName("auto") Auto,
    @SerialName("none") None,
}

data class ClientIdentity(
    val userAgent: String? = null,
    val appName: String? = null,
    val appUrl: String? = null,
)

data class ProviderResponse(
    val status: Int,
    val headers: Map<String, String>,
)

typealias PayloadHook = (payload: JsonValue, model: Model) -> JsonValue?

typealias ResponseHook = (response: ProviderResponse, model: Model) -> Unit

typealias ProviderStreamEventHook = (data: JsonValue, model: Model) -> Unit

data class StreamOptions(
    val signal: RunSignal? = null,
    val apiKey: String? = null,
    val telemetry: JsonValue? = null,
    val env: Map<String, String>? = null,
    val onPayload: PayloadHook? = null,
    val onResponse: ResponseHook? = null,
    val onProviderStreamEvent: ProviderStreamEventHook? = null,
    val headers: Map<String, String?>? = null,
    val identity: ClientIdentity? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val maxRetryDelayMs: Long? = null,
    val temperature: Double? = null,
    val samplingParams: JsonObject? = null,
    val maxTokens: Long? = null,
    val transport: Transport? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val websocketConnectTimeoutMs: Long? = null,
    val metadata: JsonObject? = null,
) {
    fun withApiKey(key: String?): StreamOptions = copy(apiKey = key)
}

data class SimpleStreamOptions(
    val stream: StreamOptions = StreamOptions(),
    val toolChoice: ToolChoice? = null,
    val reasoning: ThinkingLevel? = null,
    val deferred: JsonValue? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
)

@Serializable
data class ThinkingBudgets(
    val minimal: Long? = null,
    val low: Long? = null,
    val medium: Long? = null,
    val high: Long? = null,
)

data class ImagesOptions(
    val apiKey: String? = null,
    val env: Map<String, String>? = null,
    val headers: Map<String, String?>? = null,
    val identity: ClientIdentity? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val maxRetryDelayMs: Long? = null,
    val metadata: JsonObject? = null,
)

data class ClassifierOptions(
    val apiKey: String? = null,
    val env: Map<String, String>? = null,
    val headers: Map<String, String?>? = null,
    val identity: ClientIdentity? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val maxRetryDelayMs: Long? = null,
    val temperature: Double? = null,
)
