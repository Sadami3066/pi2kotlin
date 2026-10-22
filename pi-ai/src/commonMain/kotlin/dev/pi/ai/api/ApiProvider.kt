package dev.pi.ai.api

import dev.pi.ai.AssistantMessageEventStream
import dev.pi.ai.Model
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StreamOptions
import dev.pi.ai.TranscriptContext

interface ApiProvider {
    val api: String

    suspend fun stream(
        model: Model,
        context: TranscriptContext,
        options: StreamOptions,
    ): AssistantMessageEventStream

    suspend fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions,
    ): AssistantMessageEventStream
}
