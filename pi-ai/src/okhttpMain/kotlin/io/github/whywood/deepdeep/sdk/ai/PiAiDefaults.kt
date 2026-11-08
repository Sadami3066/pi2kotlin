package io.github.whywood.deepdeep.sdk.ai

import io.github.whywood.deepdeep.sdk.ai.http.HttpTransport
import io.github.whywood.deepdeep.sdk.ai.http.okHttpTransport

fun defaultHttpTransport(): HttpTransport = okHttpTransport()

fun createDefaultModels(): Models = createModels(defaultHttpTransport())
