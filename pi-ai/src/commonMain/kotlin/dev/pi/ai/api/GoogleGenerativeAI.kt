package dev.pi.ai.api

import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.Model
import dev.pi.ai.ModelThinkingLevel
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.ToolChoice
import dev.pi.ai.TranscriptContext
import dev.pi.ai.clampThinkingLevel
import dev.pi.ai.http.HttpTransport

class GoogleGenerativeAIApi(internal val transport: HttpTransport) : ApiProvider {
    override val api: String = GOOGLE_GENERATIVE_AI_API

    override suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream = startStream(model, context, options, null, null)

    override suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream {
        if (options.stream.apiKey.isNullOrEmpty()) {
            throw IllegalStateException("No API key for provider: ${model.provider}")
        }
        val base = buildBaseOptions(model, context, options)
        val toolChoice = options.toolChoice?.let { if (it == ToolChoice.None) "none" else "auto" }
        return startStream(model, context, base, toolChoice, resolveGoogleSimpleThinking(model, options))
    }

    private suspend fun startStream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
        toolChoice: String?,
        thinking: GoogleThinkingOptions?,
    ): AssistantMessageEventStream = streamGoogle(
        transport = transport,
        model = model,
        context = context,
        options = options,
        toolChoice = toolChoice,
        thinking = thinking,
        resolveUrlAndHeaders = {
            val apiKey = options.apiKey
            if (apiKey.isNullOrEmpty()) throw IllegalStateException("No API key for provider: ${model.provider}")
            val headers = buildGoogleBaseHeaders(model, options)
            if (!hasGoogleHeaderName(headers, "x-goog-api-key")) headers["x-goog-api-key"] = apiKey
            googleGenerativeAIUrl(model) to finishGoogleHeaders(headers)
        },
        endLabel = "Google",
    )
}

internal fun hasGoogleHeaderName(headers: Map<String, String>, name: String): Boolean =
    headers.keys.any { it.equals(name, ignoreCase = true) }

internal fun resolveGoogleSimpleThinking(model: Model, options: SimpleStreamOptions): GoogleThinkingOptions {
    val reasoning = options.reasoning ?: return GoogleThinkingOptions(enabled = false)
    val clamped = clampThinkingLevel(model, reasoning.toModelThinkingLevel())
    if (clamped == ModelThinkingLevel.Off) return GoogleThinkingOptions(enabled = false)
    val resolvedLevel = resolveGoogleThinkingLevel(model, clamped.jsonName())
    return if (usesGoogleThinkingLevel(model)) {
        GoogleThinkingOptions(enabled = true, level = toGoogleThinkingLevel(resolvedLevel))
    } else {
        GoogleThinkingOptions(
            enabled = true,
            budgetTokens = getGoogleBudget(model, resolvedLevel, options.thinkingBudgets),
        )
    }
}

fun googleGenerativeAIApi(transport: HttpTransport): ApiProvider = GoogleGenerativeAIApi(transport)
