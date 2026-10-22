package dev.pi.coding.compaction

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Content
import dev.pi.ai.Model
import dev.pi.ai.ModelCost
import dev.pi.ai.ModelInputModality
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.Tool
import dev.pi.ai.ToolCall
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.ContentReplacement
import dev.pi.coding.session.ContextEditEntry
import dev.pi.coding.session.CustomMessageEntry
import dev.pi.coding.session.ModelChangeEntry
import dev.pi.coding.session.SessionManager
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionMessageEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionTest {

    @Test
    fun `default compaction settings match upstream`() {
        assertTrue(DEFAULT_COMPACTION_SETTINGS.enabled)
        assertEquals(16384L, DEFAULT_COMPACTION_SETTINGS.reserveTokens)
        assertEquals(20000L, DEFAULT_COMPACTION_SETTINGS.keepRecentTokens)
    }

    @Test
    fun `calculateContextTokens prefers the native total`() {
        assertEquals(10L, calculateContextTokens(usageTotal(10)))
        assertEquals(4L, calculateContextTokens(usageComponents(1, 1, 1, 1)))
    }

    @Test
    fun `shouldCompact respects enabled reserve and the strict boundary`() {
        val settings = CompactionSettings(enabled = true, reserveTokens = 100, keepRecentTokens = 20)
        assertTrue(shouldCompact(901, 1000, settings))
        assertEquals(false, shouldCompact(900, 1000, settings))
        assertEquals(false, shouldCompact(5000, 1000, settings.copy(enabled = false)))
    }

    @Test
    fun `getLastAssistantUsage skips aborted error and empty usages`() {
        val entries = listOf(
            u("e1", null, "a"),
            a("e2", "e1", "b", usage = usageTotal(50), stopReason = StopReason.Aborted),
            a("e3", "e2", "c", usage = usageTotal(60), stopReason = StopReason.Error),
            a("e4", "e3", "d", usage = emptyUsage()),
            a("e5", "e4", "e", usage = usageTotal(70)),
            u("e6", "e5", "f"),
        )
        assertEquals(70L, getLastAssistantUsage(entries)!!.totalTokens)
        assertEquals(null, getLastAssistantUsage(listOf(u("e1", null, "a"))))
    }

    @Test
    fun `estimateMessagesTokens sums every message`() {
        val messages = listOf(
            UserMessage(PlainTextContent("abcd"), 0),
            UserMessage(PlainTextContent("abcdefgh"), 1),
            plainAssistant("abcdefgh"),
        )
        assertEquals(5L, estimateMessagesTokens(messages))
        assertEquals(0L, estimateMessagesTokens(emptyList()))
    }

    @Test
    fun `estimateContextTokens uses the last usage plus trailing estimates`() {
        val messages = listOf(
            UserMessage(PlainTextContent("abcd"), 0),
            AssistantMessage(
                content = mutableListOf(TextContent("abcd")),
                api = "api",
                provider = "p",
                model = "m",
                usage = usageTotal(100),
                stopReason = StopReason.Stop,
                timestamp = 1,
            ),
            UserMessage(PlainTextContent("abcdefgh"), 2),
        )
        val estimate = estimateContextTokens(messages)
        assertEquals(102L, estimate.tokens)
        assertEquals(100L, estimate.usageTokens)
        assertEquals(2L, estimate.trailingTokens)
        assertEquals(1, estimate.lastUsageIndex)

        val noUsage = estimateContextTokens(listOf(UserMessage(PlainTextContent("abcd"), 0)))
        assertEquals(1L, noUsage.tokens)
        assertEquals(0L, noUsage.usageTokens)
        assertEquals(null, noUsage.lastUsageIndex)
    }

    @Test
    fun `estimateTokens covers every message kind`() {
        assertEquals(1L, estimateTokens(UserMessage(PlainTextContent("abcd"), 0)))
        assertEquals(
            1L,
            estimateTokens(UserMessage(BlockContent(listOf(TextContent("abcd"))), 0)),
        )
        assertEquals(
            5L,
            estimateTokens(
                AssistantMessage(
                    content = mutableListOf(TextContent("abcdefgh"), dev.pi.ai.ThinkingContent("abcdefghijkl")),
                    api = "api",
                    provider = "p",
                    model = "m",
                    usage = emptyUsage(),
                    stopReason = StopReason.Stop,
                    timestamp = 0,
                ),
            ),
        )
        assertEquals(2L, estimateTokens(ToolResultMessage("id", "read", listOf(TextContent("abcdefgh")), isError = false, timestamp = 0)))

        val tool = Tool(name = "read", description = "Read", parameters = JsonObject(emptyMap()))
        val system = dev.pi.ai.SystemMessage(
            content = PlainTextContent("abcd"),
            sections = mapOf("one" to "efgh"),
            toolsAdded = listOf(tool),
            timestamp = 0,
        )
        val expectedChars = 4 + 4 + encodeToolsJson(listOf(tool)).toString().length
        assertEquals(((expectedChars / 4.0)).toLong() + if (expectedChars % 4 != 0) 1L else 0L, estimateTokens(system))

        val userWithImage = UserMessage(
            BlockContent(listOf(TextContent("abcd"), dev.pi.ai.ImageContent("data", "image/png"))),
            0,
        )
        assertEquals(((4800 + 4) / 4.0).toLong(), estimateTokens(userWithImage))
    }

    @Test
    fun `findCutPoint keeps approximately the recent tokens`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa"),
            u("e5", "e4", "aaaa"),
            a("e6", "e5", "aaaa"),
        )
        val cut = findCutPoint(entries, 0, entries.size, keepRecentTokens = 2)
        assertEquals(4, cut.firstKeptEntryIndex)
        assertEquals(-1, cut.turnStartIndex)
        assertEquals(false, cut.isSplitTurn)

        val all = findCutPoint(entries, 0, entries.size, keepRecentTokens = 1000)
        assertEquals(0, all.firstKeptEntryIndex)
    }

    @Test
    fun `findCutPoint never cuts at a tool result`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa", toolCalls = listOf(ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("a")))))),
            tr("e3", "e2", "t1", "abcdefgh"),
            u("e4", "e3", "aaaa"),
        )
        val cut = findCutPoint(entries, 0, entries.size, keepRecentTokens = 2)
        val firstKept = entries[cut.firstKeptEntryIndex] as SessionMessageEntry
        assertTrue(firstKept.message !is ToolResultMessage)
        assertEquals(3, cut.firstKeptEntryIndex)
        assertEquals(false, cut.isSplitTurn)

        val toolHeavy = listOf(
            u("e1", null, "aaaa"),
            u("e2", "e1", "aaaa"),
            a("e3", "e2", "aaaa", toolCalls = listOf(ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("a")))))),
            tr("e4", "e3", "t1", "abcdefgh"),
            tr("e5", "e4", "t1", "abcdefgh"),
        )
        val tailCut = findCutPoint(toolHeavy, 0, toolHeavy.size, keepRecentTokens = 1)
        assertEquals(2, tailCut.firstKeptEntryIndex)
        assertTrue((toolHeavy[tailCut.firstKeptEntryIndex] as SessionMessageEntry).message is AssistantMessage)
        assertEquals(true, tailCut.isSplitTurn)
    }

    @Test
    fun `findCutPoint splits a turn when the cut lands on an assistant`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa"),
            tr("e5", "e4", "t1", "aaaa"),
        )
        val cut = findCutPoint(entries, 0, entries.size, keepRecentTokens = 2)
        assertEquals(3, cut.firstKeptEntryIndex)
        assertEquals(2, cut.turnStartIndex)
        assertEquals(true, cut.isSplitTurn)
    }

    @Test
    fun `findTurnStartIndex walks back to the turn start`() {
        val entries = listOf(
            u("e1", null, "a"),
            a("e2", "e1", "b"),
            u("e3", "e2", "c"),
            a("e4", "e3", "d"),
        )
        assertEquals(2, findTurnStartIndex(entries, 3, 0))
        assertEquals(0, findTurnStartIndex(entries, 1, 0))
    }

    @Test
    fun `findCutPoint keeps adjacent metadata entries`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            modelChange("e3", "e2"),
            u("e4", "e3", "aaaa"),
            a("e5", "e4", "aaaa"),
        )
        val cut = findCutPoint(entries, 0, entries.size, keepRecentTokens = 2)
        assertEquals(2, cut.firstKeptEntryIndex)
        assertEquals(true, cut.isSplitTurn)
        assertEquals(0, cut.turnStartIndex)
    }

    @Test
    fun `prepareCompaction produces a plan`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa"),
            u("e5", "e4", "aaaa"),
            a("e6", "e5", "aaaa", usage = usageTotal(123), second = 6),
        )
        val preparation = prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model)
        val prep = assertNotNull(preparation)
        assertEquals("e5", prep.firstKeptEntryId)
        assertEquals(listOf("user", "assistant", "user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals(emptyList(), prep.turnPrefixMessages)
        assertEquals(false, prep.isSplitTurn)
        assertEquals(123L, prep.tokensBefore)
        assertNull(prep.previousSummary)
        assertEquals(model, prep.model)
    }

    @Test
    fun `prepareCompaction returns null when there is nothing to summarize`() {
        val entries = listOf(u("e1", null, "aaaa"), a("e2", "e1", "aaaa"))
        assertNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 20000), model))
    }

    @Test
    fun `prepareCompaction returns null when the session was just compacted`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            compaction("e2", "e1", "summary", "e1"),
        )
        assertNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS, model))
    }

    @Test
    fun `prepareCompaction excludes system messages from summarized context`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            sys("e2", "e1", "prompt"),
            a("e3", "e2", "aaaa"),
            u("e4", "e3", "aaaa"),
            a("e5", "e4", "aaaa"),
        )
        val prep = assertNotNull(
            prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model),
        )
        assertEquals(listOf("user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals("e4", prep.firstKeptEntryId)
    }

    @Test
    fun `prepareCompaction uses the previous summary and retained range`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa"),
            compaction("e5", "e4", "PREV", "e3"),
            u("e6", "e5", "aaaa"),
            a("e7", "e6", "aaaa", usage = usageTotal(321)),
        )
        val prep = assertNotNull(
            prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model),
        )
        assertEquals("PREV", prep.previousSummary)
        assertEquals("e6", prep.firstKeptEntryId)
        assertEquals(listOf("user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals(321L, prep.tokensBefore)
    }

    @Test
    fun `prepareCompaction inherits file operations from the previous details`() {
        val details = CompactionDetails(listOf("inherited-read.md"), listOf("inherited-write.md")).toJson()
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa", toolCalls = listOf(ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("read.md")))))),
            a(
                "e3",
                "e2",
                "aaaa",
                toolCalls = listOf(
                    ToolCall("t2", "write", JsonObject(mapOf("path" to JsonPrimitive("write.md")))),
                    ToolCall("t3", "edit", JsonObject(mapOf("path" to JsonPrimitive("edit.md")))),
                ),
            ),
            compaction("e4", "e3", "PREV", "e1", details = details),
            u("e5", "e4", "aaaa"),
            a("e6", "e5", "aaaa"),
        )
        val prep = assertNotNull(
            prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model),
        )
        assertEquals(setOf("inherited-read.md", "read.md"), prep.fileOps.read)
        assertEquals(setOf("write.md"), prep.fileOps.written)
        assertEquals(setOf("inherited-write.md", "edit.md"), prep.fileOps.edited)
    }

    @Test
    fun `prepareCompaction plans split turns`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa"),
            tr("e5", "e4", "t1", "aaaa"),
        )
        val prep = assertNotNull(
            prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model),
        )
        assertEquals("e4", prep.firstKeptEntryId)
        assertTrue(prep.isSplitTurn)
        assertEquals(listOf("user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals(listOf("user"), prep.turnPrefixMessages.map(::roleOf))
    }

    @Test
    fun `compact appends file operations to the summary`() = runTest {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a(
                "e2",
                "e1",
                "aaaa",
                toolCalls = listOf(
                    ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("src/a.kt")))),
                    ToolCall("t2", "write", JsonObject(mapOf("path" to JsonPrimitive("src/b.kt")))),
                ),
            ),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa", usage = usageTotal(55)),
        )
        val prep = assertNotNull(
            prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model),
        )
        val summarize = FakeSummarize(listOf(summaryMessage("SUMMARY", usageTotal(7))))
        val result = assertNotNull(compact(prep, summarize))
        assertEquals(
            "SUMMARY\n\n<read-files>\nsrc/a.kt\n</read-files>\n\n<modified-files>\nsrc/b.kt\n</modified-files>",
            result.summary,
        )
        assertEquals("e3", result.firstKeptEntryId)
        assertEquals(55L, result.tokensBefore)
        assertEquals(7L, result.usage!!.totalTokens)
        val details = assertNotNull(result.details)
        assertEquals(JsonArray(listOf(JsonPrimitive("src/a.kt"))), details["readFiles"])
        assertEquals(JsonArray(listOf(JsonPrimitive("src/b.kt"))), details["modifiedFiles"])
    }

    @Test
    fun `compact builds the initial summarization prompt`() = runTest {
        val entries = listOf(
            u("e1", null, "u1aa"),
            a("e2", "e1", "a1aa"),
            u("e3", "e2", "u2aa"),
            a("e4", "e3", "a2aa"),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model))
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        compact(prep, summarize)
        val request = summarize.requests.single()
        assertEquals(model, request.model)
        assertEquals(SummaryPrompts.SUMMARIZATION_SYSTEM_PROMPT, request.systemPrompt)
        assertEquals(null, request.maxTokens)
        val prompt = promptTextOf(request)
        assertTrue(prompt.startsWith("<conversation>\n[User]: u1aa\n\n[Assistant]: a1aa\n"))
        assertTrue(prompt.endsWith(SummaryPrompts.SUMMARIZATION_PROMPT))
        assertEquals(false, prompt.contains("<previous-summary>"))
    }

    @Test
    fun `compact uses the update prompt with the previous summary`() = runTest {
        val entries = listOf(
            u("e1", null, "u1aa"),
            a("e2", "e1", "a1aa"),
            compaction("e3", "e2", "PREV", "e1"),
            u("e4", "e3", "u2aa"),
            a("e5", "e4", "a2aa", usage = usageTotal(10)),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model))
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        compact(prep, summarize)
        val prompt = promptTextOf(summarize.requests.single())
        assertTrue(prompt.contains("<previous-summary>\nPREV\n</previous-summary>"))
        assertTrue(prompt.endsWith(SummaryPrompts.UPDATE_SUMMARIZATION_PROMPT))
    }

    @Test
    fun `compact applies custom instructions`() = runTest {
        val entries = listOf(
            u("e1", null, "abcd"),
            a("e2", "e1", "abcd"),
            u("e3", "e2", "abcd"),
            a("e4", "e3", "abcd"),
        )
        val prep = assertNotNull(
            prepareCompaction(
                entries,
                DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2),
                model,
                customInstructions = "focus on tests",
            ),
        )
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        compact(prep, summarize)
        assertTrue(
            promptTextOf(summarize.requests.single())
                .endsWith("${SummaryPrompts.ADDITIONAL_FOCUS_PREFIX}focus on tests"),
        )
    }

    @Test
    fun `compact merges split turn summaries and combines usage`() = runTest {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "aaaa"),
            tr("e5", "e4", "t1", "aaaa"),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model))
        assertTrue(prep.isSplitTurn)
        val summarize = FakeSummarize(
            listOf(
                summaryMessage("HISTORY", usageComponents(1, 2, 3, 4)),
                summaryMessage("TURN", usageComponents(5, 6, 7, 8)),
            ),
        )
        val result = assertNotNull(compact(prep, summarize))
        assertEquals("HISTORY\n\n---\n\n**Turn Context (split turn):**\n\nTURN", result.summary)
        assertEquals(2, summarize.requests.size)
        val historyPrompt = promptTextOf(summarize.requests[0])
        val turnPrompt = promptTextOf(summarize.requests[1])
        assertTrue(historyPrompt.endsWith(SummaryPrompts.SUMMARIZATION_PROMPT))
        assertTrue(turnPrompt.startsWith("# Conversation\n"))
        assertTrue(turnPrompt.contains("# Instructions\n${SummaryPrompts.TURN_PREFIX_SUMMARIZATION_PROMPT}"))
        val usage = assertNotNull(result.usage)
        assertEquals(6L, usage.input)
        assertEquals(8L, usage.output)
        assertEquals(10L, usage.cacheRead)
        assertEquals(12L, usage.cacheWrite)
    }

    @Test
    fun `compact returns null when summarization is aborted`() = runTest {
        val entries = listOf(
            u("e1", null, "abcd"),
            a("e2", "e1", "abcd"),
            u("e3", "e2", "abcd"),
            a("e4", "e3", "abcd"),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model))
        val summarize = FakeSummarize(listOf(summaryMessage("partial", stopReason = StopReason.Aborted)))
        assertNull(compact(prep, summarize))
    }

    @Test
    fun `compact throws on summarization failures`() = runTest {
        val entries = listOf(
            u("e1", null, "abcd"),
            a("e2", "e1", "abcd"),
            u("e3", "e2", "abcd"),
            a("e4", "e3", "abcd"),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model))

        val error = assertFailsWith<IllegalStateException> {
            compact(prep, FakeSummarize(listOf(summaryMessage("", stopReason = StopReason.Error, errorMessage = "boom"))))
        }
        assertEquals("Summarization failed: boom", error.message)

        val length = assertFailsWith<IllegalStateException> {
            compact(prep, FakeSummarize(listOf(summaryMessage("", stopReason = StopReason.Length))))
        }
        assertEquals(
            "Summarization failed: generation hit the token cap and the summary is incomplete",
            length.message,
        )

        val tool = assertFailsWith<IllegalStateException> {
            compact(
                prep,
                FakeSummarize(
                    listOf(summaryMessage("x", toolCalls = listOf(ToolCall("t", "read", JsonObject(emptyMap()))))),
                ),
            )
        }
        assertEquals("Summarization attempted to call a tool", tool.message)
    }

    @Test
    fun `getSummarizationFailure reports error and length only`() {
        assertEquals(
            "Summarization failed: Unknown error",
            getSummarizationFailure(summaryMessage("", stopReason = StopReason.Error), "Summarization"),
        )
        assertEquals(
            "Summarization failed: nope",
            getSummarizationFailure(
                summaryMessage("", stopReason = StopReason.Error, errorMessage = "nope"),
                "Summarization",
            ),
        )
        assertEquals(
            "Turn prefix summarization failed: generation hit the token cap and the summary is incomplete",
            getSummarizationFailure(summaryMessage("", stopReason = StopReason.Length), "Turn prefix summarization"),
        )
        assertNull(getSummarizationFailure(summaryMessage("ok"), "Summarization"))
        assertNull(getSummarizationFailure(summaryMessage("", stopReason = StopReason.Aborted), "Summarization"))
    }

    @Test
    fun `completeSummarization builds a single user prompt`() {
        lateinit var captured: SummarizeRequest
        val summarize = Summarize { request ->
            captured = request
            summaryMessage("ok")
        }
        lateinit var response: AssistantMessage
        runTest {
            response = completeSummarization(model, "system", "PROMPT", maxTokens = 42, summarize = summarize)
        }
        assertEquals("ok", (response.content.single() as TextContent).text)
        assertEquals(model, captured.model)
        assertEquals("system", captured.systemPrompt)
        assertEquals(42L, captured.maxTokens)
        val message = captured.messages.single() as UserMessage
        assertEquals("PROMPT", (message.content as BlockContent).blocks.filterIsInstance<TextContent>().single().text)
    }

    @Test
    fun `serializeConversation formats every message kind`() {
        val messages = listOf(
            UserMessage(PlainTextContent("hello"), 0),
            AssistantMessage(
                content = mutableListOf(
                    TextContent("hi"),
                    ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("a")))),
                ),
                api = "api",
                provider = "p",
                model = "m",
                usage = emptyUsage(),
                stopReason = StopReason.ToolUse,
                timestamp = 1,
            ),
            AssistantMessage(
                content = mutableListOf(ToolCall("t2", "edit", JsonObject(emptyMap()))),
                api = "api",
                provider = "p",
                model = "m",
                usage = emptyUsage(),
                stopReason = StopReason.ToolUse,
                timestamp = 2,
            ),
            ToolResultMessage("t1", "read", listOf(TextContent("RESULT")), isError = false, timestamp = 3),
            AssistantMessage(
                content = mutableListOf(dev.pi.ai.ThinkingContent("secret")),
                api = "api",
                provider = "p",
                model = "m",
                usage = emptyUsage(),
                stopReason = StopReason.Stop,
                timestamp = 4,
            ),
        )
        assertEquals(
            "[User]: hello\n\n" +
                "[Assistant]: hi\n\n" +
                "[Assistant tool calls]: read(path=\"a\")\n\n" +
                "[Assistant tool calls]: edit()\n\n" +
                "[Tool result]: RESULT",
            serializeConversation(messages),
        )
    }

    @Test
    fun `serializeConversation truncates long tool results`() {
        val text = "x".repeat(2500)
        val serialized = serializeConversation(
            listOf(ToolResultMessage("t1", "read", listOf(TextContent(text)), isError = false, timestamp = 0)),
        )
        assertTrue(serialized.startsWith("[Tool result]: ${"x".repeat(2000)}\n\n[... 500 more characters truncated]"))
    }

    @Test
    fun `file operations are extracted and formatted`() {
        val fileOps = createFileOps()
        extractFileOpsFromMessage(
            AssistantMessage(
                content = mutableListOf(
                    ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("read-a.md")))),
                    ToolCall("t2", "write", JsonObject(mapOf("path" to JsonPrimitive("write-b.md")))),
                    ToolCall("t3", "edit", JsonObject(mapOf("path" to JsonPrimitive("edit-c.md")))),
                    ToolCall("t4", "read", JsonObject(emptyMap())),
                ),
                api = "api",
                provider = "p",
                model = "m",
                usage = emptyUsage(),
                stopReason = StopReason.ToolUse,
                timestamp = 0,
            ),
            fileOps,
        )
        extractFileOpsFromMessage(
            ToolResultMessage(
                toolCallId = "t5",
                toolName = "codemode",
                content = emptyList(),
                nestedCalls = dev.pi.ai.NestedToolCalls(
                    calls = listOf(
                        dev.pi.ai.NestedToolCallRecord(
                            id = "n1",
                            name = "read",
                            arguments = JsonObject(mapOf("path" to JsonPrimitive("read-d.md"))),
                            status = "done",
                        ),
                    ),
                    complete = true,
                ),
                isError = false,
                timestamp = 0,
            ),
            fileOps,
        )
        val lists = computeFileLists(fileOps)
        assertEquals(listOf("read-a.md", "read-d.md"), lists.readFiles)
        assertEquals(listOf("edit-c.md", "write-b.md"), lists.modifiedFiles)
        assertEquals(
            "\n\n<read-files>\nread-a.md\nread-d.md\n</read-files>\n\n" +
                "<modified-files>\nedit-c.md\nwrite-b.md\n</modified-files>",
            formatFileOperations(lists.readFiles, lists.modifiedFiles),
        )
        assertEquals("", formatFileOperations(emptyList(), emptyList()))
    }

    @Test
    fun `prepareCompaction works with session manager branches`() = runTest {
        val manager = SessionManager.inMemory("/work".toPath())
        manager.appendMessage(UserMessage(PlainTextContent("u1aa"), millis(1)))
        manager.appendMessage(plainAssistant("a1aa"))
        val kept = manager.appendMessage(UserMessage(PlainTextContent("u2aa"), millis(2)))
        manager.appendMessage(plainAssistant("a2aa", usage = usageTotal(200)))
        manager.appendCompaction("PREV", kept.id, 100)
        manager.appendMessage(UserMessage(PlainTextContent("u3aa"), millis(3)))
        manager.appendMessage(plainAssistant("a3aa"))

        val preparation = prepareCompaction(
            manager.getBranch(),
            DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2),
            model,
        )
        val prep = assertNotNull(preparation)
        assertEquals("PREV", prep.previousSummary)
        assertEquals(listOf("user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals(listOf("u2aa", "a2aa"), prep.messagesToSummarize.map(::plainTextOf))
    }

    @Test
    fun `compaction results can be appended to a session manager`() = runTest {
        val manager = SessionManager.inMemory("/work".toPath())
        manager.appendMessage(UserMessage(PlainTextContent("u1aa"), millis(1)))
        manager.appendMessage(plainAssistant("a1aa"))
        manager.appendMessage(UserMessage(PlainTextContent("u2aa"), millis(2)))
        manager.appendMessage(plainAssistant("a2aa", usage = usageTotal(90)))
        val prep = assertNotNull(
            prepareCompaction(manager.getBranch(), DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 2), model),
        )
        val result = assertNotNull(compact(prep, FakeSummarize(listOf(summaryMessage("NEW SUMMARY")))))
        manager.appendCompaction(
            result.summary,
            result.firstKeptEntryId,
            result.tokensBefore,
            result.usage,
            result.details,
        )
        val context = manager.buildSessionContext()
        val summaries = context.messages.filterIsInstance<UserMessage>().filter { message ->
            contentText(message.content, "").startsWith(SummaryPrompts.COMPACTION_SUMMARY_PREFIX)
        }
        assertEquals(1, summaries.size)
        assertTrue(contentText(summaries.single().content, "").contains("NEW SUMMARY"))
        assertNull(prepareCompaction(manager.getBranch(), DEFAULT_COMPACTION_SETTINGS, model))
    }

    @Test
    fun `projection converts custom and branch summary entries`() {
        val entries = listOf(
            u("e1", null, "original"),
            customMessage("e2", "e1", "note", "custom text"),
            dev.pi.coding.session.BranchSummaryEntry(
                id = "e3",
                parentId = "e2",
                timestamp = ts(3),
                fromId = "e1",
                summary = "branch summary text",
            ),
        )
        val messages = buildSessionProjection(entries).messages
        assertEquals(3, messages.size)
        assertEquals("original", contentText((messages[0] as UserMessage).content, ""))
        assertEquals("custom text", contentText((messages[1] as UserMessage).content, ""))
        val branch = contentText((messages[2] as UserMessage).content, "")
        assertTrue(branch.startsWith(SummaryPrompts.BRANCH_SUMMARY_PREFIX))
        assertTrue(branch.endsWith(SummaryPrompts.BRANCH_SUMMARY_SUFFIX))
    }

    @Test
    fun `projection converts the compaction entry into a summary message`() {
        val entries = listOf(
            compaction("e1", null, "compaction summary text", "e1"),
        )
        val messages = buildSessionProjection(entries).messages
        assertEquals(1, messages.size)
        val text = contentText((messages.single() as UserMessage).content, "")
        assertEquals(
            SummaryPrompts.COMPACTION_SUMMARY_PREFIX + "compaction summary text" +
                SummaryPrompts.COMPACTION_SUMMARY_SUFFIX,
            text,
        )
    }

    @Test
    fun `projection applies context edit replacements`() {
        val entries = listOf(
            u("e1", null, "original"),
            customMessage("e2", "e1", "note", "custom text"),
            contextEdit("e3", "e2", "e2", ContentReplacement(PlainTextContent("redacted"))),
        )
        val messages = buildSessionProjection(entries).messages
        assertEquals(2, messages.size)
        assertEquals("original", contentText((messages[0] as UserMessage).content, ""))
        assertEquals("redacted", contentText((messages[1] as UserMessage).content, ""))
    }

    @Test
    fun `projection omits entries deleted by a context edit`() {
        val entries = listOf(
            u("e1", null, "keep"),
            u("e2", "e1", "drop"),
            contextEdit("e3", "e2", "e2", null),
        )
        val projection = buildSessionProjection(entries)
        assertEquals(1, projection.messages.size)
        assertEquals("keep", contentText((projection.messages.single() as UserMessage).content, ""))
        assertEquals(listOf(1, 0, 0), projection.entries.map { it.messages.size })
    }

    @Test
    fun `estimateProjectedContextTokens invalidates usage after a later context edit`() {
        val withoutEdit = listOf(
            u("e1", null, "abcd"),
            a("e2", "e1", "abcd", usage = usageTotal(50)),
        )
        val cleanProjection = buildSessionProjection(withoutEdit)
        assertEquals(50L, estimateProjectedContextTokens(cleanProjection, withoutEdit).usageTokens)

        val withEdit = withoutEdit + contextEdit("e3", "e2", "e1", ContentReplacement(PlainTextContent("x")))
        val editedProjection = buildSessionProjection(withEdit)
        val estimate = estimateProjectedContextTokens(editedProjection, withEdit)
        assertEquals(0L, estimate.usageTokens)
        assertEquals(null, estimate.lastUsageIndex)
        assertEquals(2L, estimate.tokens)
    }

    @Test
    fun `repeated compaction keeps the retained region`() {
        val entries = listOf(
            u("e1", null, "old-question"),
            a("e2", "e1", "old-answer"),
            u("e3", "e2", "kept-question"),
            a("e4", "e3", "kept-answer"),
            compaction("e5", "e4", "PREV", "e3"),
            u("e6", "e5", "new-question"),
            a("e7", "e6", "new-answer", usage = usageTotal(42)),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 1), model))
        assertTrue(prep.isSplitTurn)
        assertEquals("e7", prep.firstKeptEntryId)
        assertEquals("PREV", prep.previousSummary)
        assertEquals(listOf("user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals(listOf("kept-question", "kept-answer"), prep.messagesToSummarize.map(::plainTextOf))
        assertEquals(listOf("new-question"), prep.turnPrefixMessages.map(::plainTextOf))
    }

    private fun plainTextOf(message: dev.pi.ai.Message): String = when (message) {
        is UserMessage -> contentText(message.content, "")
        is AssistantMessage -> contentText(message.content)
        is ToolResultMessage -> contentText(message.content, "")
        else -> ""
    }

    @Test
    fun `projected cut advances past a recovery omission suffix`() {
        val entries = listOf(
            u("e1", null, "aaaa"),
            a("e2", "e1", "aaaa"),
            u("e3", "e2", "aaaa"),
            a("e4", "e3", "failure", stopReason = StopReason.Error, usage = emptyUsage()),
            contextEdit("e5", "e4", "e4", null),
        )
        val prep = assertNotNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 1), model))
        assertEquals("e4", prep.firstKeptEntryId)
        assertTrue(prep.isSplitTurn)
        assertEquals(listOf("user", "assistant"), prep.messagesToSummarize.map(::roleOf))
        assertEquals(listOf("user"), prep.turnPrefixMessages.map(::roleOf))
    }
}
