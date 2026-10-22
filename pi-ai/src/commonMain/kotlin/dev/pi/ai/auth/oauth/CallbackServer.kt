package dev.pi.ai.auth.oauth

import dev.pi.ai.RunSignal
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select

data class OAuthCallbackRequest(
    val code: String,
    val state: String?,
    val params: Map<String, String>,
)

data class OAuthCallbackServerOptions<T>(
    val providerName: String,
    val host: String,
    val port: Int,
    val path: String,
    val redirectHost: String? = null,
    val state: String? = null,
    val signal: RunSignal? = null,
    val timeoutMs: Long? = null,
    val complete: suspend (OAuthCallbackRequest) -> T,
)

interface OAuthCallbackServer<T> {
    val redirectUri: String

    suspend fun wait(): T?

    fun cancel()

    fun close()
}

interface OAuthCallbackServerFactory {
    suspend fun <T> start(options: OAuthCallbackServerOptions<T>): OAuthCallbackServer<T>
}

sealed interface OAuthCallbackOutcome<out T> {
    data class Callback<T>(val value: T) : OAuthCallbackOutcome<T>

    data class Manual(val input: String) : OAuthCallbackOutcome<Nothing>
}

suspend fun <T> startOAuthCallbackServerOrNull(options: OAuthCallbackServerOptions<T>): OAuthCallbackServer<T>? {
    val factory = OAuthRuntime.callbackServerFactory ?: return null
    return runCatching { factory.start(options) }.getOrNull()
}

suspend fun <T> waitForCallbackOrManualInput(
    interaction: OAuthInteraction,
    callback: OAuthCallbackServer<T>?,
    message: String,
    placeholder: String? = null,
): OAuthCallbackOutcome<T> {
    if (callback == null) {
        return OAuthCallbackOutcome.Manual(interaction.prompt(message, placeholder))
    }
    return coroutineScope {
        val manual = async { interaction.prompt(message, placeholder) }
        val callbackResult = async { callback.wait() }
        val outcome = select<OAuthCallbackOutcome<T>> {
            manual.onAwait { input ->
                callback.cancel()
                OAuthCallbackOutcome.Manual(input)
            }
            callbackResult.onAwait { value ->
                if (value != null) {
                    manual.cancel()
                    OAuthCallbackOutcome.Callback(value)
                } else {
                    OAuthCallbackOutcome.Manual(manual.await())
                }
            }
        }
        manual.cancel()
        callbackResult.cancel()
        outcome
    }
}
