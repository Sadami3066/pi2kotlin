package io.github.whywood.deepdeep.sdk.demo

import io.github.whywood.deepdeep.sdk.ai.TextDeltaEvent
import io.github.whywood.deepdeep.sdk.ai.providers.FauxProviderOptions
import io.github.whywood.deepdeep.sdk.ai.providers.FauxResponseStep
import io.github.whywood.deepdeep.sdk.ai.providers.FauxTokenSize
import io.github.whywood.deepdeep.sdk.ai.providers.fauxAssistantMessage
import io.github.whywood.deepdeep.sdk.ai.providers.fauxProvider
import io.github.whywood.deepdeep.sdk.coding.AgentSessionOptions
import io.github.whywood.deepdeep.sdk.coding.createAgentSession
import io.github.whywood.deepdeep.sdk.coding.session.SessionEvent
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

fun main() = runBlocking {
    val workspace = "build/demo-workspace".toPath()
    val agentDir = "build/demo-agent".toPath()
    val fs = FileSystem.SYSTEM
    fs.createDirectories(workspace)
    fs.createDirectories(agentDir)

    val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 2)))
    faux.setResponses(
        listOf(
            FauxResponseStep.Fixed(
                fauxAssistantMessage("你好，我是 pi2kotlin SDK 的演示会话。"),
            ),
        ),
    )

    val session = createAgentSession(
        AgentSessionOptions(
            cwd = workspace,
            agentDir = agentDir,
            transport = io.github.whywood.deepdeep.sdk.ai.http.okHttpTransport(),
            model = "faux/faux-1",
            tools = emptyList(),
            providers = listOf(faux.provider),
        ),
    )

    session.subscribe { event ->
        if (event is SessionEvent.AgentEventReceived) {
            val agentEvent = event.event
            if (agentEvent is io.github.whywood.deepdeep.sdk.agent.AgentEvent.MessageUpdate) {
                val update = agentEvent.assistantMessageEvent
                if (update is TextDeltaEvent) print(update.delta)
            }
        }
    }

    session.prompt("介绍一下你自己")
    println()
    println("session file: ${session.sessionFile ?: "(not persisted)"}")
    println("last assistant text: ${session.getLastAssistantText()}")
    session.close()
}
