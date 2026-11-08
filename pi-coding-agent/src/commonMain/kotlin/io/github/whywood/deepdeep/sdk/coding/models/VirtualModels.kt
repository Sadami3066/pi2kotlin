package io.github.whywood.deepdeep.sdk.coding.models

import io.github.whywood.deepdeep.sdk.ai.AssistantMessage
import io.github.whywood.deepdeep.sdk.ai.Message
import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.ModelCost
import io.github.whywood.deepdeep.sdk.ai.ModelInputModality
import io.github.whywood.deepdeep.sdk.ai.ModelThinkingLevel
import io.github.whywood.deepdeep.sdk.ai.RunSignal

const val VIRTUAL_MODEL_API: String = "pi-virtual"
const val VIRTUAL_MODEL_STATE_ENTRY: String = "pi.virtual-model-state"

enum class ModelRouteReason {
    User,
    Continuation,
    Retry,
    Direct,
}

data class ModelRoutePrevious(
    val model: Model,
    val thinkingLevel: ModelThinkingLevel? = null,
)

data class ModelRouteFailed(
    val model: Model,
    val thinkingLevel: ModelThinkingLevel? = null,
    val message: AssistantMessage,
)

data class ModelRouteRequest<TState>(
    val model: Model,
    val thinkingLevel: ModelThinkingLevel,
    val reason: ModelRouteReason,
    val previous: ModelRoutePrevious? = null,
    val failed: ModelRouteFailed? = null,
    val state: TState? = null,
    val messages: List<Message>,
    val signal: RunSignal? = null,
)

data class ModelRoute<TState>(
    val model: Model,
    val thinkingLevel: ModelThinkingLevel,
    val state: TState? = null,
)

data class VirtualModelDefinition<TState>(
    val provider: String,
    val id: String,
    val name: String,
    val thinkingLevels: List<ModelThinkingLevel> = listOf(ModelThinkingLevel.Off),
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val input: List<ModelInputModality>? = null,
    val route: suspend (ModelRouteRequest<TState>) -> ModelRoute<TState>,
)

fun isVirtualModel(model: Model): Boolean = model.api == VIRTUAL_MODEL_API

fun createVirtualModel(definition: VirtualModelDefinition<*>): Model {
    val levels = definition.thinkingLevels
    val thinkingLevelMap = LinkedHashMap<String, String?>()
    for (level in THINKING_LEVELS) {
        thinkingLevelMap[level] = level.takeIf { mapped -> levels.any { it.name.equals(mapped, ignoreCase = true) } }
    }
    return Model(
        id = definition.id,
        name = definition.name,
        api = VIRTUAL_MODEL_API,
        provider = definition.provider,
        baseUrl = "",
        input = definition.input ?: listOf(ModelInputModality.Text, ModelInputModality.Image),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        reasoning = levels.any { it != ModelThinkingLevel.Off },
        thinkingLevelMap = thinkingLevelMap,
        contextWindow = definition.contextWindow ?: 0,
        maxTokens = definition.maxTokens ?: 0,
    )
}
