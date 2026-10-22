package dev.pi.ai.utils

import dev.pi.ai.AssistantMessage
import dev.pi.ai.Context
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolReference
import dev.pi.ai.TranscriptContext
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.PiJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptTest {
    private fun tool(name: String, description: String = "$name tool"): Tool =
        Tool(name = name, description = description, parameters = buildJsonObject {})

    private fun assistant(timestamp: Long): AssistantMessage = AssistantMessage(
        content = mutableListOf(TextContent("ok")),
        api = "openai-completions",
        provider = "test",
        model = "test-model",
        usage = Usage.empty(),
        stopReason = StopReason.Stop,
        timestamp = timestamp,
    )

    private fun systemMessages(): List<dev.pi.ai.Message> = listOf(
        SystemMessage(
            content = PlainTextContent("base"),
            sections = linkedMapOf("a" to "<a>1</a>", "b" to "<b>1</b>"),
            toolsAdded = listOf(tool("first")),
            timestamp = 10,
        ),
        UserMessage(content = PlainTextContent("hello"), timestamp = 11),
        SystemMessage(content = PlainTextContent("also do this"), timestamp = 12),
        assistant(13),
        SystemMessage(
            content = PlainTextContent(""),
            sections = linkedMapOf("a" to "<a>2</a>", "b" to null, "c" to "<c>1</c>"),
            toolsRemoved = listOf(ToolReference("first")),
            toolsAdded = listOf(tool("second")),
            timestamp = 14,
        ),
    )

    private val transcript: TranscriptContext = normalizeContext(Context(messages = systemMessages()))

    @Test
    fun replaysContentSectionsAndToolsIntoOneLeadingMessage() {
        val current = getCurrentSystemMessage(transcript.messages)
        assertEquals(
            SystemMessage(
                content = PlainTextContent("base\n\nalso do this"),
                sections = linkedMapOf("a" to "<a>2</a>", "c" to "<c>1</c>"),
                toolsAdded = listOf(tool("second")),
                timestamp = 10,
            ),
            current,
        )
        assertEquals("base\n\nalso do this\n\n<a>2</a>\n\n<c>1</c>", getCurrentSystemPrompt(transcript.messages))
    }

    @Test
    fun collapseKeepsOnlyNonSystemMessagesAfterReplayedHead() {
        val collapsed = collapseSystemMessages(transcript)
        assertEquals(listOf("system", "user", "assistant"), collapsed.messages.map { messageRole(it) })
        assertEquals(collapseSystemMessages(collapsed).messages, collapsed.messages)
    }

    @Test
    fun replayOfTranscriptWithoutSystemMessagesIsEmpty() {
        val context = normalizeContext(
            Context(messages = listOf(UserMessage(content = PlainTextContent("hi"), timestamp = 1))),
        )
        assertNull(getCurrentSystemMessage(context.messages))
        assertEquals("", getCurrentSystemPrompt(context.messages))
        assertEquals(context.messages, collapseSystemMessages(context).messages)
    }

    @Test
    fun lateFullPatchWithoutLeadingMessageReplaysAsPrompt() {
        val context = normalizeContext(
            Context(
                messages = listOf(
                    UserMessage(content = PlainTextContent("old session"), timestamp = 1),
                    SystemMessage(
                        content = PlainTextContent(""),
                        sections = linkedMapOf("preamble" to "You are pi."),
                        toolsAdded = listOf(tool("x")),
                        timestamp = 2,
                    ),
                ),
            ),
        )
        assertEquals("You are pi.", getCurrentSystemPrompt(context.messages))
        val head = collapseSystemMessages(context).messages.first()
        assertEquals(
            SystemMessage(
                content = PlainTextContent(""),
                sections = linkedMapOf("preamble" to "You are pi."),
                toolsAdded = listOf(tool("x")),
                timestamp = 2,
            ),
            head,
        )
    }

    @Test
    fun normalizeContextFoldsLegacyPromptAndToolFields() {
        val messages = listOf<UserMessage>(UserMessage(content = PlainTextContent("hi"), timestamp = 1))
        assertEquals(messages, normalizeContext(Context(messages = messages)).messages)
        assertEquals(messages, normalizeContext(Context(systemPrompt = "", tools = emptyList(), messages = messages)).messages)
        assertEquals(
            listOf(
                SystemMessage(
                    content = PlainTextContent("be brief"),
                    toolsAdded = listOf(tool("a")),
                    timestamp = 0,
                ),
            ) + messages,
            normalizeContext(Context(systemPrompt = "be brief", tools = listOf(tool("a")), messages = messages)).messages,
        )
    }

    @Test
    fun createInitialSystemMessageReturnsNullForEmptyPromptAndTools() {
        assertNull(createInitialSystemMessage(null, null))
        assertNull(createInitialSystemMessage("", emptyList()))
        assertEquals(
            SystemMessage(content = PlainTextContent("be brief"), timestamp = 0),
            createInitialSystemMessage("be brief", null),
        )
        assertEquals(
            SystemMessage(content = PlainTextContent(""), toolsAdded = listOf(tool("a")), timestamp = 0),
            createInitialSystemMessage(null, listOf(tool("a"))),
        )
        assertEquals(
            SystemMessage(content = PlainTextContent("be brief"), toolsAdded = listOf(tool("a")), timestamp = 0),
            createInitialSystemMessage("be brief", listOf(tool("a"))),
        )
    }

    @Test
    fun initialSystemMessageHelpersOnlyLookAtFirstEntry() {
        val messages = systemMessages()
        assertEquals<dev.pi.ai.Message?>(messages.first(), getInitialSystemMessage(messages))
        assertEquals(messages.drop(1), withoutInitialSystemMessage(messages))
        val withoutLeading = messages.drop(1)
        assertNull(getInitialSystemMessage(withoutLeading))
        assertEquals(withoutLeading, withoutInitialSystemMessage(withoutLeading))
    }

    @Test
    fun getCurrentToolsReplaysRemovalsAndAdditions() {
        val messages = listOf(
            SystemMessage(content = PlainTextContent(""), toolsAdded = listOf(tool("a"), tool("b")), timestamp = 1),
            SystemMessage(
                content = PlainTextContent(""),
                toolsRemoved = listOf(ToolReference("a")),
                toolsAdded = listOf(tool("c")),
                timestamp = 2,
            ),
            SystemMessage(content = PlainTextContent(""), toolsAdded = listOf(tool("b")), timestamp = 3),
            SystemMessage(
                content = PlainTextContent(""),
                toolsRemoved = listOf(ToolReference("c"), ToolReference("d")),
                toolsAdded = listOf(tool("a"), tool("e")),
                timestamp = 4,
            ),
        )
        assertEquals(listOf("b", "a", "e"), getCurrentTools(messages).map { it.name })
        assertEquals(emptyList(), getCurrentTools(listOf(UserMessage(content = PlainTextContent("hi"), timestamp = 1))))
    }

    @Test
    fun declarationsEqualIgnoresNothingButDeclarationContent() {
        val toolA = tool("a")
        assertTrue(declarationsEqual(toolA, tool("a")))
        assertEquals(false, declarationsEqual(toolA, tool("a", "changed")))
        assertEquals(false, declarationsEqual(toolA, toolA.copy(constrainedSampling = JsonPrimitive(false))))
        assertEquals(true, declarationsEqual(toolA.copy(constrainedSampling = JsonPrimitive(false)), toolA.copy(constrainedSampling = JsonPrimitive(false))))
        val orderedA = Tool(
            name = "a",
            description = "a tool",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("x", buildJsonObject { put("type", JsonPrimitive("number")) })
                    put("y", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
            },
        )
        val orderedB = Tool(
            name = "a",
            description = "a tool",
            parameters = buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", buildJsonObject {
                    put("y", buildJsonObject { put("type", JsonPrimitive("string")) })
                    put("x", buildJsonObject { put("type", JsonPrimitive("number")) })
                })
            },
        )
        assertEquals(false, declarationsEqual(orderedA, orderedB))
    }

    @Test
    fun declarationsEqualNormalizesNumbersLikeJsonStringify() {
        fun parameterized(minimum: String): Tool = Tool(
            name = "a",
            description = "a tool",
            parameters = PiJson.parseToJsonElement("""{"type":"object","properties":{},"minimum":$minimum}""").jsonObject,
        )
        assertEquals(true, declarationsEqual(parameterized("1.0"), parameterized("1")))
        assertEquals(true, declarationsEqual(parameterized("1e3"), parameterized("1000")))
        assertEquals(false, declarationsEqual(parameterized("1.5"), parameterized("1")))
    }

    @Test
    fun toToolDeclarationKeepsConstrainedSamplingAndDeepCopiesParameters() {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject { put("a", buildJsonObject { put("type", JsonPrimitive("string")) }) })
        }
        val declaration = toToolDeclaration(
            Tool("a", "a tool", parameters, constrainedSampling = JsonPrimitive(false)),
        )
        assertEquals(Tool("a", "a tool", parameters, constrainedSampling = JsonPrimitive(false)), declaration)
        assertEquals(parameters, declaration.parameters)
    }

    @Test
    fun toolStateChangesTreatChangedDefinitionsAsRemovalPlusAddition() {
        val changes = getToolStateChanges(
            previous = listOf(tool("a"), tool("b")),
            current = listOf(tool("b", "changed"), tool("c")),
        )
        assertEquals(listOf(tool("b", "changed"), tool("c")), changes.added)
        assertEquals(listOf(ToolReference("a"), ToolReference("b")), changes.removed)
        assertEquals(
            ToolStateChanges(added = emptyList(), removed = emptyList()),
            getToolStateChanges(previous = listOf(tool("a")), current = listOf(tool("a"))),
        )
    }

    @Test
    fun toolStateChangesCoverPureAddsRemovesAndReordering() {
        val added = getToolStateChanges(previous = emptyList(), current = listOf(tool("a")))
        assertEquals(listOf(tool("a")), added.added)
        assertEquals(emptyList(), added.removed)
        val removed = getToolStateChanges(previous = listOf(tool("a")), current = emptyList())
        assertEquals(emptyList(), removed.added)
        assertEquals(listOf(ToolReference("a")), removed.removed)
        val unchangedOrder = getToolStateChanges(previous = listOf(tool("a"), tool("b")), current = listOf(tool("b"), tool("a")))
        assertEquals(emptyList(), unchangedOrder.added)
        assertEquals(emptyList(), unchangedOrder.removed)
        val redefined = getToolStateChanges(previous = listOf(tool("a"), tool("b"), tool("c")), current = listOf(tool("c"), tool("a", "x")))
        assertEquals(listOf(tool("a", "x")), redefined.added)
        assertEquals(listOf(ToolReference("a"), ToolReference("b")), redefined.removed)
    }

    @Test
    fun replayOfSystemMessageWithOnlyToolsKeepsTimestampZero() {
        val message = SystemMessage(
            content = PlainTextContent(""),
            toolsAdded = listOf(tool("a")),
            timestamp = 0,
        )
        assertEquals(message.content, getCurrentSystemMessage(listOf(message))!!.content)
        assertEquals(listOf(tool("a")), getCurrentSystemMessage(listOf(message))!!.toolsAdded)
        assertEquals(0L, getCurrentSystemMessage(listOf(message))!!.timestamp)
    }

    private fun messageRole(message: dev.pi.ai.Message): String = when (message) {
        is SystemMessage -> "system"
        is UserMessage -> "user"
        is AssistantMessage -> "assistant"
        is dev.pi.ai.ToolResultMessage -> "toolResult"
    }
}
