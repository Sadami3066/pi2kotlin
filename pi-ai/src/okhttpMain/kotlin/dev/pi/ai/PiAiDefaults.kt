package dev.pi.ai

import dev.pi.ai.http.HttpTransport
import dev.pi.ai.http.okHttpTransport

fun defaultHttpTransport(): HttpTransport = okHttpTransport()

fun createDefaultModels(): Models = createModels(defaultHttpTransport())
