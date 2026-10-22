package dev.pi.ai.api

import dev.pi.ai.Model
import kotlinx.serialization.json.JsonObject

internal data class MistralConversationsCompat(
    val supportsMidConvoSystemMessages: Boolean? = null,
) {
    companion object {
        fun fromJson(json: JsonObject?): MistralConversationsCompat {
            if (json == null) return MistralConversationsCompat()
            return MistralConversationsCompat(
                supportsMidConvoSystemMessages = booleanField(json, "supportsMidConvoSystemMessages"),
            )
        }
    }
}

internal data class ResolvedMistralConversationsCompat(
    val supportsMidConvoSystemMessages: Boolean,
)

internal fun resolveMistralConversationsCompat(model: Model): ResolvedMistralConversationsCompat {
    val overrides = MistralConversationsCompat.fromJson(model.compat)
    return ResolvedMistralConversationsCompat(
        supportsMidConvoSystemMessages = overrides.supportsMidConvoSystemMessages ?: false,
    )
}
