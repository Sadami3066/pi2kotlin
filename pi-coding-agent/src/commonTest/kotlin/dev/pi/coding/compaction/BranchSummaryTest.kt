package dev.pi.coding.compaction

import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.TextContent
import dev.pi.ai.ToolCall
import dev.pi.ai.UserMessage
import dev.pi.coding.session.SessionManager
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BranchSummaryTest {

    private fun textOf(message: dev.pi.ai.Message): String = when (message) {
        is UserMessage -> contentText(message.content, "")
        is dev.pi.ai.AssistantMessage -> contentText(message.content)
        is dev.pi.ai.ToolResultMessage -> contentText(message.content, "")
        else -> ""
    }

    @Test
    fun `collects entries from the old leaf back to the common ancestor`() = runTest {
        val manager = SessionManager.inMemory("/work".toPath())
        val user1 = manager.appendMessage(UserMessage(PlainTextContent("one"), ts(1).let { millis(1) }))
        manager.appendMessage(plainAssistant("one reply"))
        manager.appendMessage(UserMessage(PlainTextContent("two"), millis(2)))
        manager.branch(user1.id)
        val user3 = manager.appendMessage(UserMessage(PlainTextContent("three"), millis(3)))
        val assistant3 = manager.appendMessage(plainAssistant("three reply"))

        val result = collectEntriesForBranchSummary(manager, assistant3.id, user1.id)
        assertEquals(user1.id, result.commonAncestorId)
        assertEquals(listOf(user3.id, assistant3.id), result.entries.map { it.id })
    }

    @Test
    fun `collects nothing without an old leaf`() = runTest {
        val manager = SessionManager.inMemory("/work".toPath())
        val user = manager.appendMessage(UserMessage(PlainTextContent("one"), millis(1)))
        val result = collectEntriesForBranchSummary(manager, null, user.id)
        assertEquals(emptyList(), result.entries)
        assertNull(result.commonAncestorId)
    }

    @Test
    fun `prepareBranchEntries walks newest to oldest within the budget`() {
        val entries = listOf(
            u("e1", null, "f1aa"),
            a("e2", "e1", "f2aa"),
            u("e3", "e2", "f3aa"),
            a("e4", "e3", "f4aa"),
            u("e5", "e4", "f5aa"),
        )
        val preparation = prepareBranchEntries(entries, tokenBudget = 2)
        assertEquals(listOf("f4aa", "f5aa"), preparation.messages.map(::textOf))
        assertEquals(2L, preparation.totalTokens)
    }

    @Test
    fun `prepareBranchEntries has no limit when the budget is zero`() {
        val entries = listOf(
            u("e1", null, "f1aa"),
            a("e2", "e1", "f2aa"),
            u("e3", "e2", "f3aa"),
        )
        val preparation = prepareBranchEntries(entries)
        assertEquals(3, preparation.messages.size)
        assertEquals(3L, preparation.totalTokens)
    }

    @Test
    fun `prepareBranchEntries skips tool results and converts summary entries`() {
        val entries = listOf(
            u("e1", null, "question"),
            a("e2", "e1", "answer"),
            tr("e3", "e2", "t1", "tool output"),
            branchSummaryEntry("e4", "e3", "e1", "branch summary"),
            compaction("e5", "e4", "compaction summary", "e1"),
        )
        val preparation = prepareBranchEntries(entries)
        assertEquals(4, preparation.messages.size)
        assertTrue(textOf(preparation.messages[0]).startsWith("question"))
        assertTrue(textOf(preparation.messages[2]).startsWith(SummaryPrompts.BRANCH_SUMMARY_PREFIX))
        assertTrue(textOf(preparation.messages[3]).startsWith(SummaryPrompts.COMPACTION_SUMMARY_PREFIX))
    }

    @Test
    fun `prepareBranchEntries keeps an oversized summary entry while under ninety percent`() {
        val entries = listOf(
            branchSummaryEntry("e1", null, "root", "s".repeat(40)),
            u("e2", "e1", "f2aa"),
        )
        val preparation = prepareBranchEntries(entries, tokenBudget = 10)
        assertEquals(2, preparation.messages.size)
        assertTrue(textOf(preparation.messages[0]).startsWith(SummaryPrompts.BRANCH_SUMMARY_PREFIX))
        assertTrue(preparation.totalTokens > 10)
    }

    @Test
    fun `prepareBranchEntries collects file operations from all entries`() {
        val details = BranchSummaryDetails(listOf("earlier-read.md"), listOf("earlier-write.md")).toJson()
        val entries = listOf(
            branchSummaryEntry("e1", null, "root", "summary", details = details),
            a(
                "e2",
                "e1",
                "aaaa",
                toolCalls = listOf(
                    ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("read.md")))),
                    ToolCall("t2", "write", JsonObject(mapOf("path" to JsonPrimitive("write.md")))),
                ),
            ),
            u("e3", "e2", "aaaa"),
        )
        val preparation = prepareBranchEntries(entries, tokenBudget = 1)
        assertEquals(setOf("earlier-read.md", "read.md"), preparation.fileOps.read)
        assertEquals(setOf("write.md"), preparation.fileOps.written)
        assertEquals(setOf("earlier-write.md"), preparation.fileOps.edited)
    }

    @Test
    fun `generateBranchSummary builds the prompt and result`() = runTest {
        val entries = listOf(
            u("e1", null, "question"),
            a(
                "e2",
                "e1",
                "answer",
                toolCalls = listOf(ToolCall("t1", "read", JsonObject(mapOf("path" to JsonPrimitive("src/a.kt"))))),
            ),
        )
        val summarize = FakeSummarize(listOf(summaryMessage("BRANCH", usageTotal(9))))
        val result = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model),
            summarize,
        )
        val summary = assertNotNull(result.summary)
        assertEquals(
            SummaryPrompts.BRANCH_SUMMARY_PREAMBLE + "BRANCH" +
                "\n\n<read-files>\nsrc/a.kt\n</read-files>",
            summary,
        )
        assertEquals(9L, result.usage!!.totalTokens)
        assertEquals(listOf("src/a.kt"), result.readFiles)
        assertEquals(emptyList(), result.modifiedFiles)
        assertNull(result.error)
        assertNull(result.aborted)

        val request = summarize.requests.single()
        assertEquals(model, request.model)
        assertEquals(SummaryPrompts.SUMMARIZATION_SYSTEM_PROMPT, request.systemPrompt)
        assertNull(request.maxTokens)
        val prompt = promptTextOf(request)
        assertTrue(prompt.startsWith("<conversation>\n[User]: question\n\n[Assistant]: answer\n"))
        assertTrue(prompt.contains("[Assistant tool calls]: read(path=\"src/a.kt\")"))
        assertTrue(prompt.endsWith(SummaryPrompts.BRANCH_SUMMARY_PROMPT))
    }

    @Test
    fun `generateBranchSummary appends custom instructions`() = runTest {
        val entries = listOf(u("e1", null, "question"))
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model, customInstructions = "focus"),
            summarize,
        )
        val prompt = promptTextOf(summarize.requests.single())
        assertTrue(prompt.contains(SummaryPrompts.BRANCH_SUMMARY_PROMPT))
        assertTrue(prompt.endsWith("${SummaryPrompts.ADDITIONAL_FOCUS_PREFIX}focus"))
    }

    @Test
    fun `generateBranchSummary can replace the instructions`() = runTest {
        val entries = listOf(u("e1", null, "question"))
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(
                model = model,
                customInstructions = "custom only",
                replaceInstructions = true,
            ),
            summarize,
        )
        val prompt = promptTextOf(summarize.requests.single())
        assertEquals(false, prompt.contains(SummaryPrompts.BRANCH_SUMMARY_PROMPT))
        assertTrue(prompt.endsWith("custom only"))
    }

    @Test
    fun `generateBranchSummary reports empty branches`() = runTest {
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        val result = generateBranchSummary(emptyList(), GenerateBranchSummaryOptions(model = model), summarize)
        assertEquals("No content to summarize", result.summary)
        assertEquals(0, summarize.requests.size)
    }

    @Test
    fun `generateBranchSummary honors the token budget`() = runTest {
        val entries = listOf(
            u("e1", null, "f1aa"),
            a("e2", "e1", "f2aa"),
            u("e3", "e2", "f3aa"),
            a("e4", "e3", "f4aa"),
            u("e5", "e4", "f5aa"),
        )
        val summarize = FakeSummarize(listOf(summaryMessage("S")))
        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model.copy(contextWindow = 1000), reserveTokens = 996),
            summarize,
        )
        val prompt = promptTextOf(summarize.requests.single())
        assertTrue(prompt.contains("f5aa"))
        assertTrue(prompt.contains("f4aa"))
        assertEquals(false, prompt.contains("f1aa"))
    }

    @Test
    fun `generateBranchSummary reports aborts errors and tool calls`() = runTest {
        val entries = listOf(u("e1", null, "question"))

        val aborted = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model),
            FakeSummarize(listOf(summaryMessage("partial", stopReason = StopReason.Aborted))),
        )
        assertEquals(true, aborted.aborted)
        assertNull(aborted.summary)

        val error = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model),
            FakeSummarize(
                listOf(summaryMessage("", stopReason = StopReason.Error, errorMessage = "boom")),
            ),
        )
        assertEquals("Branch summarization failed: boom", error.error)

        val length = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model),
            FakeSummarize(listOf(summaryMessage("", stopReason = StopReason.Length))),
        )
        assertEquals(
            "Branch summarization failed: generation hit the token cap and the summary is incomplete",
            length.error,
        )

        val tool = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(model = model),
            FakeSummarize(
                listOf(summaryMessage("x", toolCalls = listOf(ToolCall("t", "read", JsonObject(emptyMap()))))),
            ),
        )
        assertEquals("Branch summarization attempted to call a tool", tool.error)
    }

    @Test
    fun `branch summary details serialize file lists`() {
        val json = BranchSummaryDetails(listOf("a.md"), listOf("b.md")).toJson()
        assertEquals(
            listOf(JsonPrimitive("a.md")),
            (json["readFiles"] as kotlinx.serialization.json.JsonArray).toList(),
        )
        assertEquals(
            listOf(JsonPrimitive("b.md")),
            (json["modifiedFiles"] as kotlinx.serialization.json.JsonArray).toList(),
        )
    }

    @Test
    fun `text of converted custom messages is preserved`() {
        val entries = listOf(customMessage("e1", null, "note", "custom text"))
        val preparation = prepareBranchEntries(entries)
        assertEquals(listOf("custom text"), preparation.messages.map(::textOf))
    }

    @Test
    fun `text helper keeps only text blocks`() {
        val message = UserMessage(PlainTextContent("ignored"), 0)
        assertEquals("ignored", textOf(message).let { it })
        val assistantEntry = a("e1", null, "hello", toolCalls = listOf(ToolCall("t", "read", JsonObject(emptyMap()))))
        assertEquals("hello", textOf(assistantEntry.message))
        assertTrue(TextContent("x").text == "x")
    }
}
