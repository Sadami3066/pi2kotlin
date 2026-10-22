package dev.pi.ai.utils

const val MAX_PROVIDER_ERROR_BODY_CHARS: Int = 4000

data class NormalizedProviderError(
    val status: Int?,
    val body: String?,
    val message: String,
    val messageCarriesBody: Boolean,
)

object ProviderErrors {
    fun normalize(status: Int, headers: Map<String, String>, body: String): NormalizedProviderError {
        val trimmed = body.trim()
        val normalizedBody = if (trimmed.isEmpty()) null else truncate(trimmed, MAX_PROVIDER_ERROR_BODY_CHARS)
        return NormalizedProviderError(
            status = status,
            body = normalizedBody,
            message = normalizedBody ?: "$status status code (no body)",
            messageCarriesBody = normalizedBody == null,
        )
    }

    fun format(error: NormalizedProviderError, prefix: String? = null): String {
        if (error.messageCarriesBody || error.status == null || error.body == null) {
            return if (prefix != null && error.status != null) {
                "$prefix (${error.status}): ${error.message}"
            } else {
                error.message
            }
        }
        return if (prefix != null) {
            "$prefix (${error.status}): ${error.body}"
        } else {
            "${error.status}: ${error.body}"
        }
    }

    fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.substring(0, maxChars) + "... [truncated ${text.length - maxChars} chars]"
    }
}
