package io.github.whywood.deepdeep.sdk.ai.api

import io.github.whywood.deepdeep.sdk.ai.AssistantMessageEventStream
import io.github.whywood.deepdeep.sdk.ai.Model
import io.github.whywood.deepdeep.sdk.ai.SimpleStreamOptions
import io.github.whywood.deepdeep.sdk.ai.StreamOptions
import io.github.whywood.deepdeep.sdk.ai.TranscriptContext

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
