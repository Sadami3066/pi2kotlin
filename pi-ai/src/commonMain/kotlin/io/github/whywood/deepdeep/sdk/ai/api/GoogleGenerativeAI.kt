package io.github.whywood.deepdeep.sdk.ai.api

import io.github.whywood.deepdeep.sdk.ai.AssistantMessageEventStream
import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.ModelThinkingLevel
import io.github.whywood.deepdeep.sdk.ai.SimpleStreamOptions
import io.github.whywood.deepdeep.sdk.ai.StreamOptions
import io.github.whywood.deepdeep.sdk.ai.ToolChoice
import io.github.whywood.deepdeep.sdk.ai.TranscriptContext
import io.github.whywood.deepdeep.sdk.ai.clampThinkingLevel
import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport

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
