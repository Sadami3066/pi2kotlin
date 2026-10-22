package dev.pi.demo

import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.SessionEvent
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
            transport = dev.pi.ai.http.okHttpTransport(),
            model = "faux/faux-1",
            tools = emptyList(),
            providers = listOf(faux.provider),
        ),
    )

    session.subscribe { event ->
        if (event is SessionEvent.AgentEventReceived) {
            val agentEvent = event.event
            if (agentEvent is dev.pi.agent.AgentEvent.MessageUpdate) {
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
