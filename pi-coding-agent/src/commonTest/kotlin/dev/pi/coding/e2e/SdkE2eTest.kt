package dev.pi.coding.e2e

import dev.pi.agent.AgentEvent
import dev.pi.agent.AgentMessage
import dev.pi.agent.asAssistantMessage
import dev.pi.agent.asLlmMessage
import dev.pi.agent.asToolResultMessage
import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Model
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SimpleStreamOptions
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.TranscriptContext
import dev.pi.ai.UserMessage
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.FauxProviderHandle
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.FauxProviderState
import dev.pi.ai.providers.FauxResponseFactory
import dev.pi.ai.providers.FauxResponseStep
import dev.pi.ai.providers.FauxTokenSize
import dev.pi.ai.providers.fauxAssistantMessage
import dev.pi.ai.providers.fauxProvider
import dev.pi.ai.providers.fauxText
import dev.pi.ai.providers.fauxToolCall
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.AgentSession
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionManager
import dev.pi.coding.session.SessionMessageEntry
import dev.pi.coding.session.decodeSessionEntry
import dev.pi.coding.session.sessionJson
import dev.pi.coding.tools.CodingTool
import dev.pi.coding.tools.textResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private object NoHttpTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        throw IllegalStateException("network is disabled in this test")
}

private fun objectSchema(): JsonObject = buildJsonObject { put("type", "object") }

private fun echoTool(): CodingTool = CodingTool(
    name = "echo",
    description = "Echo the given text",
    parameters = objectSchema(),
    label = "Echo",
    execute = { _, args, _, _ ->
        val text = (args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        textResult("echo: $text")
    },
)

private fun throwingTool(): CodingTool = CodingTool(
    name = "explode",
    description = "Always throws",
    parameters = objectSchema(),
    label = "Explode",
    execute = { _, _, _, _ -> error("tool exploded") },
)

private fun rejectingTool(): CodingTool = CodingTool(
    name = "reject",
    description = "Reports a failure without throwing",
    parameters = objectSchema(),
    label = "Reject",
    execute = { _, _, _, _ -> textResult("rejected by policy", isError = true) },
)

private fun toolCallMessage(name: String, arguments: JsonObject, id: String): AssistantMessage =
    fauxAssistantMessage(
        content = listOf(fauxText("calling $name"), fauxToolCall(name, arguments, id)),
        stopReason = StopReason.ToolUse,
    )

private fun textOf(content: dev.pi.ai.MessageContent): String = when (content) {
    is PlainTextContent -> content.text
    is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
}

private fun contentText(content: dev.pi.ai.Content): String = when (content) {
    is TextContent -> content.text
    else -> ""
}

private fun AgentMessage.assistantText(): String? {
    val message = asAssistantMessage() ?: return null
    return message.content.filterIsInstance<TextContent>().joinToString("") { it.text }.ifBlank { null }
}

private fun AgentMessage.userText(): String? {
    val message = asLlmMessage() as? UserMessage ?: return null
    return textOf(message.content)
}

private fun AgentMessage.roleName(): String = role

private fun eventName(event: AgentEvent): String = when (event) {
    is AgentEvent.AgentStart -> "agent_start"
    is AgentEvent.AgentEnd -> "agent_end"
    is AgentEvent.TurnStart -> "turn_start"
    is AgentEvent.TurnEnd -> "turn_end"
    is AgentEvent.MessageStart -> "message_start:${event.message.roleName()}"
    is AgentEvent.MessageUpdate -> "message_update"
    is AgentEvent.MessageEnd -> "message_end:${event.message.roleName()}"
    is AgentEvent.ToolExecutionStart -> "tool_start:${event.toolName}"
    is AgentEvent.ToolExecutionUpdate -> "tool_update:${event.toolName}"
    is AgentEvent.ToolExecutionEnd -> "tool_end:${event.isError}"
}

private class E2eContext(
    val fs: FakeFileSystem,
    val faux: FauxProviderHandle,
    val session: AgentSession,
) {
    val events: MutableList<AgentEvent> = mutableListOf()

    fun persistedEntries(): List<SessionEntry> {
        val file = session.sessionFile ?: return emptyList()
        val text = fs.read(file) { readUtf8() }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .drop(1)
            .mapNotNull { line ->
                val element = sessionJson.parseToJsonElement(line)
                decodeSessionEntry(element as JsonObject)
            }
            .toList()
    }

    fun persistedMessageRoles(): List<String> = persistedEntries()
        .filterIsInstance<SessionMessageEntry>()
        .mapNotNull { entry -> (entry.rawMessage?.get("role") as? JsonPrimitive)?.contentOrNull }

    fun persistedMessageTexts(): List<String> = persistedEntries()
        .filterIsInstance<SessionMessageEntry>()
        .mapNotNull { entry -> entry.rawMessage?.let { rawText(it) } }

    private fun rawText(raw: JsonObject): String? {
        val content = raw["content"] ?: return null
        return when (content) {
            is kotlinx.serialization.json.JsonPrimitive -> content.contentOrNull
            is kotlinx.serialization.json.JsonArray -> content.mapNotNull { block ->
                val obj = block as? JsonObject ?: return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("")

            else -> null
        }
    }
}

private suspend fun newSession(
    fs: FakeFileSystem,
    faux: FauxProviderHandle,
    tools: List<CodingTool> = emptyList(),
    sessionFile: Path? = null,
): AgentSession {
    fs.createDirectories("/work".toPath())
    fs.createDirectories("/agent".toPath())
    return createAgentSession(
        AgentSessionOptions(
            cwd = "/work".toPath(),
            agentDir = "/agent".toPath(),
            transport = NoHttpTransport,
            fs = fs,
            model = "faux/faux-1",
            thinkingLevel = "off",
            tools = tools,
            sessionFile = sessionFile,
            providers = listOf(faux.provider),
        ),
    )
}

private fun FauxProviderHandle.setFixed(vararg texts: String) {
    setResponses(texts.map { FauxResponseStep.Fixed(fauxAssistantMessage(it)) })
}

class SdkE2eTest {
    @Test
    fun toolCallRoundTripEmitsLifecycleAndPersistsMessages() = runTest {
        val fs = FakeFileSystem()
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(
                    toolCallMessage("echo", buildJsonObject { put("text", "hello") }, "echo-1"),
                ),
                FauxResponseStep.Fixed(fauxAssistantMessage("all done")),
            ),
        )
        val session = newSession(fs, faux, tools = listOf(echoTool()))
        val context = E2eContext(fs, faux, session)
        session.agent.subscribe { event, _ -> context.events.add(event) }
        try {
            session.prompt("use the echo tool")

            assertEquals("all done", session.getLastAssistantText())
            assertEquals(2, faux.state.callCount)
            assertTrue(!session.isStreaming)

            val sequence = context.events.map(::eventName).filter { it != "message_update" }
            assertEquals(
                listOf(
                    "agent_start",
                    "turn_start",
                    "message_start:user",
                    "message_end:user",
                    "message_start:assistant",
                    "message_end:assistant",
                    "tool_start:echo",
                    "tool_end:false",
                    "message_start:toolResult",
                    "message_end:toolResult",
                    "turn_end",
                    "turn_start",
                    "message_start:assistant",
                    "message_end:assistant",
                    "turn_end",
                    "agent_end",
                ),
                sequence,
            )
            assertTrue(context.events.count { it is AgentEvent.MessageUpdate } > 0)
            assertEquals(
                listOf(
                    "message_start:user",
                    "message_end:user",
                    "message_start:assistant",
                    "message_end:assistant",
                    "message_start:toolResult",
                    "message_end:toolResult",
                    "message_start:assistant",
                    "message_end:assistant",
                ),
                context.events.map(::eventName).filter { it.startsWith("message_") && it != "message_update" },
            )

            val roles = context.persistedMessageRoles()
            assertEquals(listOf("system", "user", "assistant", "toolResult", "assistant"), roles)
            val entries = context.persistedEntries()
            assertEquals(
                listOf(
                    "model_change",
                    "thinking_level_change",
                    "message",
                    "message",
                    "message",
                    "message",
                    "message",
                ),
                entries.map { it.type },
            )
            val assistantEntry = entries.filterIsInstance<SessionMessageEntry>()[2]
            val assistant = assertIs<AssistantMessage>(assistantEntry.message)
            val toolCall = assertIs<dev.pi.ai.ToolCall>(assistant.content.filterIsInstance<dev.pi.ai.ToolCall>().single())
            assertEquals("echo-1", toolCall.id)
            assertEquals("hello", toolCall.arguments["text"]?.let { (it as? JsonPrimitive)?.contentOrNull })
            val toolResultEntry = entries.filterIsInstance<SessionMessageEntry>()[3]
            val toolResult = assertIs<ToolResultMessage>(toolResultEntry.message)
            assertEquals("echo-1", toolResult.toolCallId)
            assertEquals(false, toolResult.isError)
            assertEquals("echo: hello", contentText(toolResult.content.single()))

            val reopened = SessionManager.open(assertNotNull(session.sessionFile), fs)
            assertEquals(4, reopened.buildSessionContext().messages.count { it !is SystemMessage })

            faux.appendResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage("second answer"))))
            session.prompt("second question")
            assertEquals("second answer", session.getLastAssistantText())
            assertEquals(3, faux.state.callCount)
            val multiTurnRoles = E2eContext(fs, faux, session).persistedMessageRoles()
            assertEquals(
                listOf("system", "user", "assistant", "toolResult", "assistant", "user", "assistant"),
                multiTurnRoles,
            )
            assertEquals(
                listOf("use the echo tool", "second question"),
                session.messages.filter { it.roleName() == "user" }.mapNotNull { it.userText() },
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun steerAndFollowUpQueuedWhileStreamingAreInjectedInOrder() = runTest {
        val fs = FakeFileSystem()
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        faux.setResponses(
            listOf(
                FauxResponseStep.Scripted(
                    object : FauxResponseFactory {
                        override suspend fun produce(
                            context: TranscriptContext,
                            options: SimpleStreamOptions?,
                            state: FauxProviderState,
                            model: Model,
                        ): AssistantMessage {
                            started.complete(Unit)
                            release.await()
                            return fauxAssistantMessage("first reply")
                        }
                    },
                ),
                FauxResponseStep.Fixed(fauxAssistantMessage("steer reply")),
                FauxResponseStep.Fixed(fauxAssistantMessage("follow reply")),
            ),
        )
        val session = newSession(fs, faux)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val promptJob = scope.launch { session.prompt("first question") }
            started.await()
            assertTrue(session.isStreaming)
            session.steer("steer message")
            session.followUp("follow-up message")
            release.complete(Unit)
            promptJob.join()

            assertTrue(!session.isStreaming)
            assertEquals(3, faux.state.callCount)
            val messages = session.messages.filter { it.roleName() != "system" }
            assertEquals(
                listOf("user", "assistant", "user", "assistant", "user", "assistant"),
                messages.map { it.roleName() },
            )
            assertEquals(
                listOf("first question", "steer message", "follow-up message"),
                messages.mapNotNull { it.userText() },
            )
            assertEquals(
                listOf("first reply", "steer reply", "follow reply"),
                messages.mapNotNull { it.assistantText() },
            )

            val context = E2eContext(fs, faux, session)
            assertEquals(
                listOf("system", "user", "assistant", "user", "assistant", "user", "assistant"),
                context.persistedMessageRoles(),
            )
            val persistedTexts = context.persistedMessageTexts()
            assertTrue(persistedTexts.contains("steer message"))
            assertTrue(persistedTexts.contains("follow-up message"))
            val reopened = SessionManager.open(assertNotNull(session.sessionFile), fs)
            assertEquals(
                listOf("system", "user", "assistant", "user", "assistant", "user", "assistant"),
                reopened.buildSessionContext().messages.map {
                    when (it) {
                        is UserMessage -> "user"
                        is AssistantMessage -> "assistant"
                        is ToolResultMessage -> "toolResult"
                        is SystemMessage -> "system"
                    }
                },
            )
        } finally {
            scope.cancel()
            session.close()
        }
    }

    @Test
    fun abortDuringStreamingConvergesToAbortedAssistantMessage() = runTest {
        val fs = FakeFileSystem()
        val reply = "one two three four five six seven eight nine ten eleven twelve"
        val faux = fauxProvider(
            FauxProviderOptions(
                tokenSize = FauxTokenSize(1, 1),
                tokensPerSecond = 100.0,
            ),
        )
        faux.setResponses(
            listOf(FauxResponseStep.Fixed(fauxAssistantMessage(reply))),
        )
        val session = newSession(fs, faux)
        val firstUpdate = CompletableDeferred<Unit>()
        var sawStreaming = false
        session.agent.subscribe { event, _ ->
            if (event is AgentEvent.MessageUpdate) {
                if (!firstUpdate.isCompleted) firstUpdate.complete(Unit)
            }
            if (event is AgentEvent.AgentStart) sawStreaming = session.isStreaming
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val promptJob = scope.launch { session.prompt("give me a long answer") }
            firstUpdate.await()
            assertTrue(session.isStreaming)
            session.abort()
            assertEquals(true, session.agent.signal?.isAborted)
            promptJob.join()

            assertTrue(!session.isStreaming)
            assertTrue(sawStreaming)
            val last = session.messages.last()
            val assistant = assertNotNull(last.asAssistantMessage())
            assertEquals(StopReason.Aborted, assistant.stopReason)
            assertEquals("Request was aborted", assistant.errorMessage)
            assertTrue(session.getLastAssistantText() == null || session.getLastAssistantText()!!.length < reply.length)
            assertEquals("Request was aborted", session.agent.state.errorMessage)
            session.waitForIdle()

            val persisted = SessionManager.open(assertNotNull(session.sessionFile), fs)
            val lastPersisted = persisted.buildSessionContext().messages.last()
            assertIs<AssistantMessage>(lastPersisted)
            assertEquals(StopReason.Aborted, lastPersisted.stopReason)
        } finally {
            scope.cancel()
            session.close()
        }
    }

    @Test
    fun manualCompactionSurvivesReopenAndConversationContinues() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/agent".toPath())
        fs.write("/agent/settings.json".toPath()) {
            writeUtf8("""{"compaction":{"enabled":true,"reserveTokens":0,"keepRecentTokens":2}}""")
        }
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(fauxAssistantMessage("first reply")),
                FauxResponseStep.Fixed(fauxAssistantMessage("ok")),
            ),
        )
        val summaryFactory = object : FauxResponseFactory {
            override suspend fun produce(
                context: TranscriptContext,
                options: SimpleStreamOptions?,
                state: FauxProviderState,
                model: Model,
            ): AssistantMessage = fauxAssistantMessage("summary of earlier turns")
        }
        val session = newSession(fs, faux)
        val file = session.sessionFile
        try {
            session.prompt("first question with enough words")
            session.prompt("second question with enough words")
            assertEquals("ok", session.getLastAssistantText())

            faux.setResponses(
                listOf(
                    FauxResponseStep.Scripted(summaryFactory),
                    FauxResponseStep.Scripted(summaryFactory),
                ),
            )
            val result = assertNotNull(session.compact())
            assertTrue(result.summary.contains("summary of earlier turns"))
            val entries = session.sessionManager.getEntries()
            val compaction = assertIs<CompactionEntry>(entries.filterIsInstance<CompactionEntry>().single())
            assertEquals(result.firstKeptEntryId, compaction.firstKeptEntryId)
            val llmMessages = session.messages.mapNotNull { it.asLlmMessage() }
            assertIs<SystemMessage>(llmMessages.first())
            val summaryMessage = assertIs<UserMessage>(llmMessages[1])
            assertTrue(textOf(summaryMessage.content).startsWith("The conversation history before this point was compacted"))
            assertNotNull(file)
        } finally {
            session.close()
        }

        faux.setFixed("reply after resume")
        val reopened = newSession(fs, faux, sessionFile = file)
        try {
            val entries = reopened.sessionManager.getEntries()
            assertEquals(1, entries.filterIsInstance<CompactionEntry>().size)
            val summaryMessages = reopened.messages.mapNotNull { it.asLlmMessage() }
                .filterIsInstance<UserMessage>()
                .filter { textOf(it.content).startsWith("The conversation history before this point was compacted") }
            assertEquals(1, summaryMessages.size)

            reopened.prompt("third question after resume")
            assertEquals("reply after resume", reopened.getLastAssistantText())
            val afterEntries = reopened.sessionManager.getEntries()
            assertEquals(1, afterEntries.filterIsInstance<CompactionEntry>().size)
            val contextMessages = SessionManager.open(assertNotNull(file), fs).buildSessionContext().messages
            assertTrue(
                contextMessages.filterIsInstance<UserMessage>().any { textOf(it.content) == "third question after resume" },
            )
            assertTrue(
                contextMessages.filterIsInstance<AssistantMessage>().any {
                    it.content.filterIsInstance<TextContent>().joinToString("") { block -> block.text } == "reply after resume"
                },
            )
        } finally {
            reopened.close()
        }
    }

    @Test
    fun abortInterruptsFauxStreamingBeforeCompletion() = runTest {
        val fs = FakeFileSystem()
        val reply = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen"
        val faux = fauxProvider(
            FauxProviderOptions(
                tokenSize = FauxTokenSize(1, 1),
                tokensPerSecond = 20.0,
            ),
        )
        faux.setResponses(listOf(FauxResponseStep.Fixed(fauxAssistantMessage(reply))))
        val session = newSession(fs, faux)
        val firstUpdate = CompletableDeferred<Unit>()
        session.agent.subscribe { event, _ ->
            if (event is AgentEvent.MessageUpdate && !firstUpdate.isCompleted) firstUpdate.complete(Unit)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val promptJob = scope.launch { session.prompt("count slowly") }
            firstUpdate.await()
            assertTrue(session.isStreaming)
            session.abort()
            assertEquals(true, session.agent.signal?.isAborted)
            promptJob.join()

            val assistant = assertNotNull(session.messages.last().asAssistantMessage())
            assertEquals(StopReason.Aborted, assistant.stopReason)
            val text = session.getLastAssistantText()
            assertTrue(text == null || text.length < reply.length)
        } finally {
            scope.cancel()
            session.close()
        }
    }

    @Test
    fun toolFailuresBecomeErrorToolResultsAndPersist() = runTest {
        val fs = FakeFileSystem()
        val faux = fauxProvider(FauxProviderOptions(tokenSize = FauxTokenSize(1, 1)))
        faux.setResponses(
            listOf(
                FauxResponseStep.Fixed(
                    fauxAssistantMessage(
                        content = listOf(
                            fauxText("calling failing tools"),
                            fauxToolCall("explode", buildJsonObject { }, "call-1"),
                            fauxToolCall("reject", buildJsonObject { }, "call-2"),
                        ),
                        stopReason = StopReason.ToolUse,
                    ),
                ),
                FauxResponseStep.Fixed(fauxAssistantMessage("recovered")),
            ),
        )
        val session = newSession(fs, faux, tools = listOf(throwingTool(), rejectingTool()))
        val toolEnds = mutableListOf<AgentEvent.ToolExecutionEnd>()
        session.agent.subscribe { event, _ ->
            if (event is AgentEvent.ToolExecutionEnd) toolEnds.add(event)
        }
        try {
            session.prompt("run failing tools")

            assertEquals("recovered", session.getLastAssistantText())
            assertEquals(2, toolEnds.size)
            assertTrue(toolEnds.all { it.isError })
            assertTrue(toolEnds.any { it.toolName == "explode" })
            assertTrue(toolEnds.any { it.toolName == "reject" })

            val toolResults = session.messages.mapNotNull { it.asToolResultMessage() }
            assertEquals(2, toolResults.size)
            assertTrue(toolResults.all { it.isError })
            assertTrue(contentText(toolResults[0].content.single()).contains("tool exploded"))
            assertEquals("rejected by policy", contentText(toolResults[1].content.single()))

            val entries = E2eContext(fs, faux, session).persistedEntries()
            val persistedResults = entries.filterIsInstance<SessionMessageEntry>()
                .mapNotNull { it.message as? ToolResultMessage }
            assertEquals(2, persistedResults.size)
            assertTrue(persistedResults.all { it.isError })
            assertTrue(contentText(persistedResults[0].content.single()).contains("tool exploded"))
            assertEquals("rejected by policy", contentText(persistedResults[1].content.single()))

            val reopened = SessionManager.open(assertNotNull(session.sessionFile), fs)
            val contextToolResults = reopened.buildSessionContext().messages.filterIsInstance<ToolResultMessage>()
            assertEquals(2, contextToolResults.size)
            assertTrue(contextToolResults.all { it.isError })
        } finally {
            session.close()
        }
    }
}
