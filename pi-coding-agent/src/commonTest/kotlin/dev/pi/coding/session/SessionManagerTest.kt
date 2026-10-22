package dev.pi.coding.session

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.PlainTextContent
import dev.pi.ai.StopReason
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UsageCost
import dev.pi.ai.UserMessage
import dev.pi.coding.paths.AgentPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

class SessionManagerTest {
    @Test
    fun createDefersFileCreationUntilFirstConversationMessage() = runTest {
        val fs = FakeFileSystem()
        val paths = agentPaths()
        val session = SessionManager.create(paths, fs)
        val file = assertNotNull(session.sessionFile)
        assertEquals(paths.sessionsForCwd(), file.parent)
        assertTrue(!fs.exists(file), "session file must not exist before the first conversation message")
        session.appendModelChange("deepseek", "deepseek-v4-pro")
        session.appendThinkingLevelChange("max")
        session.appendCustomEntry("ext", buildJsonObject { put("state", 1) })
        session.appendSessionInfo("named session")
        assertTrue(!fs.exists(file), "state-only entries must stay in memory")
        session.appendMessage(userMessage("hello"))
        assertTrue(fs.exists(file), "first user message must flush the session file")
    }

    @Test
    fun firstFlushWritesHeaderAndAllBufferedEntries() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        session.appendModelChange("deepseek", "deepseek-v4-pro")
        session.appendThinkingLevelChange("high")
        val userEntry = session.appendMessage(userMessage("hello"))
        val file = assertNotNull(session.sessionFile)
        val lines = readLines(fs, file)
        assertEquals(4, lines.size)
        assertEquals("session", lineJson(lines[0]).stringValue("type"))
        assertEquals("model_change", lineJson(lines[1]).stringValue("type"))
        assertEquals("thinking_level_change", lineJson(lines[2]).stringValue("type"))
        assertEquals("message", lineJson(lines[3]).stringValue("type"))
        assertEquals(userEntry.id, lineJson(lines[3]).stringValue("id"))
    }

    @Test
    fun appendWritesSingleLinePerEntryAfterFirstFlush() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val file = assertNotNull(session.sessionFile)
        session.appendMessage(userMessage("first"))
        val initialLines = readLines(fs, file)
        val assistant = session.appendMessage(assistantMessage("reply"))
        val lines = readLines(fs, file)
        assertEquals(initialLines.size + 1, lines.size)
        assertEquals(assistant.id, lineJson(lines.last()).stringValue("id"))
    }

    @Test
    fun sessionFilePathUsesEncodedCwdAndSessionId() = runTest {
        val fs = FakeFileSystem()
        val paths = AgentPaths("/home/user/workspace".toPath(), "/home/user/.pi/agent".toPath())
        val session = SessionManager.create(paths, fs)
        assertEquals("--home-user-workspace--", paths.sessionsForCwd().name)
        val file = assertNotNull(session.sessionFile)
        assertEquals(paths.sessionsForCwd(), file.parent)
        assertTrue(file.name.endsWith(".jsonl"))
        assertTrue(file.name.contains(session.sessionId))
        assertTrue(!file.name.contains(":"))
    }

    @Test
    fun sessionAndEntryIdentifiersFollowUpstreamFormat() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
                .matches(session.sessionId),
            "session id should be UUIDv7: ${session.sessionId}",
        )
        assertTrue(
            Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z$")
                .matches(session.getHeader().timestamp),
            "header timestamp should be ISO-8601 UTC millis: ${session.getHeader().timestamp}",
        )
        val entry = session.appendMessage(userMessage("hello"))
        assertTrue(Regex("^[0-9a-f]{8}$").matches(entry.id), "entry id should be 8 hex chars: ${entry.id}")
    }

    @Test
    fun createRejectsInvalidCustomSessionId() = runTest {
        val fs = FakeFileSystem()
        assertFailsWith<IllegalArgumentException> {
            SessionManager.create(agentPaths(), fs, sessionId = "bad id!")
        }
        val session = SessionManager.create(agentPaths(), fs, sessionId = "custom-1")
        assertEquals("custom-1", session.sessionId)
    }

    @Test
    fun reopenRestoresEntriesLeafAndName() = runTest {
        val fs = FakeFileSystem()
        val paths = agentPaths()
        val session = SessionManager.create(paths, fs)
        session.appendSessionInfo("my session")
        session.appendModelChange("deepseek", "deepseek-v4-pro")
        val user = session.appendMessage(userMessage("hello"))
        val assistant = session.appendMessage(assistantMessage("hi"))
        val file = assertNotNull(session.sessionFile)
        val reopened = SessionManager.open(file, fs)
        assertEquals(session.sessionId, reopened.sessionId)
        assertEquals(paths.cwd, reopened.cwd)
        assertEquals(session.getEntries().map { it.id }, reopened.getEntries().map { it.id })
        assertEquals(assistant.id, reopened.getLeafId())
        assertEquals("my session", reopened.getSessionName())
        assertEquals(user.id, reopened.getEntries()[2].id)
        assertEquals(
            session.buildSessionContext().messages.map { it::class.simpleName },
            reopened.buildSessionContext().messages.map { it::class.simpleName },
        )
    }

    @Test
    fun openUsesHeaderCwd() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(cwd = "/work/other"), fs)
        session.appendMessage(userMessage("hello"))
        val file = assertNotNull(session.sessionFile)
        val reopened = SessionManager.open(file, fs)
        assertEquals("/work/other".toPath(), reopened.cwd)
    }

    @Test
    fun branchMovesLeafAndFormsTree() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("first"))
        val second = session.appendMessage(assistantMessage("second"))
        session.branch(first.id)
        val third = session.appendMessage(assistantMessage("third"))
        assertEquals(listOf(first.id, third.id), session.getBranch().map { it.id })
        val tree = session.getTree()
        assertEquals(1, tree.size)
        assertEquals(first.id, tree[0].entry.id)
        assertEquals(listOf(second.id, third.id), tree[0].children.map { it.entry.id })
        assertEquals(listOf(first.id, second.id), session.getBranch(second.id).map { it.id })
    }

    @Test
    fun resetLeafCreatesAdditionalRoot() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        session.appendMessage(userMessage("first"))
        session.resetLeaf()
        val secondRoot = session.appendMessage(userMessage("second"))
        assertEquals(2, session.getTree().size)
        assertEquals(secondRoot.id, session.getLeafId())
    }

    @Test
    fun buildSessionContextRecoversMessagesAndModelSettings() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        session.appendMessage(SystemMessage(content = PlainTextContent("system prompt"), timestamp = 0))
        session.appendModelChange("deepseek", "deepseek-v4-pro")
        session.appendThinkingLevelChange("max")
        session.appendMessage(userMessage("hello"))
        session.appendMessage(assistantMessage("hi", provider = "anthropic", model = "claude-sonnet-4-5"))
        val context = session.buildSessionContext()
        assertEquals(3, context.messages.size)
        assertEquals(StopReason.Stop, (context.messages[2] as AssistantMessage).stopReason)
        assertEquals("anthropic", context.provider)
        assertEquals("claude-sonnet-4-5", context.modelId)
        assertEquals("max", context.thinkingLevel)
    }

    @Test
    fun buildContextEntriesFollowsLatestCompaction() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("first"))
        val second = session.appendMessage(assistantMessage("second"))
        val compaction = session.appendCompaction("summary", firstKeptEntryId = second.id, tokensBefore = 1000)
        val third = session.appendMessage(userMessage("third"))
        val contextEntries = session.buildContextEntries()
        assertEquals(listOf(compaction.id, second.id, third.id), contextEntries.map { it.id })
        val context = session.buildSessionContext()
        assertEquals(3, context.messages.size)
        val summaryText = textOf(context.messages[0])
        assertEquals(COMPACTION_SUMMARY_PREFIX + "summary" + COMPACTION_SUMMARY_SUFFIX, summaryText)
        assertEquals(second.id, (contextEntries[1] as SessionMessageEntry).id)
        assertEquals(first.id, session.getEntries().first().id)
    }

    @Test
    fun olderCompactionOnPathContributesNoMessages() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("first"))
        session.appendCompaction("first summary", firstKeptEntryId = first.id, tokensBefore = 10)
        val second = session.appendMessage(assistantMessage("second"))
        session.appendCompaction("second summary", firstKeptEntryId = first.id, tokensBefore = 20)
        val third = session.appendMessage(userMessage("third"))
        val contextEntries = session.buildContextEntries()
        assertEquals(5, contextEntries.size)
        val context = session.buildSessionContext()
        assertEquals(4, context.messages.size)
        assertEquals(COMPACTION_SUMMARY_PREFIX + "second summary" + COMPACTION_SUMMARY_SUFFIX, textOf(context.messages[0]))
        assertEquals(third.id, session.getLeafId())
    }

    @Test
    fun compactionSystemMessageIsPrependedToContext() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("first"))
        val snapshot = SystemMessage(content = PlainTextContent("system prompt"), timestamp = 0)
        session.appendCompaction("summary", firstKeptEntryId = first.id, tokensBefore = 10, systemMessage = snapshot)
        session.appendMessage(userMessage("after"))
        val context = session.buildSessionContext()
        assertEquals(4, context.messages.size)
        val system = assertIs<SystemMessage>(context.messages[0])
        assertEquals("system prompt", (system.content as PlainTextContent).text)
        assertEquals(COMPACTION_SUMMARY_PREFIX + "summary" + COMPACTION_SUMMARY_SUFFIX, textOf(context.messages[1]))
    }

    @Test
    fun contextEditOmitsTargetAndReplacesContent() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("drop me"))
        val second = session.appendMessage(assistantMessage("original answer"))
        session.appendMessage(userMessage("keep me"))
        session.appendContextEdit(first.id, null)
        session.appendContextEdit(second.id, ContentReplacement(PlainTextContent("edited answer")))
        val projection = session.buildSessionProjection()
        assertTrue(projection.none { it.id == first.id })
        val projectedAssistant = projection.filterIsInstance<SessionMessageEntry>()
            .first { it.id == second.id }.message as AssistantMessage
        assertEquals(listOf("edited answer"), projectedAssistant.content.filterIsInstance<TextContent>().map { it.text })
        val context = session.buildSessionContext()
        assertEquals(2, context.messages.size)
        val assistant = assertIs<AssistantMessage>(context.messages[0])
        assertEquals("edited answer", (assistant.content.single() as TextContent).text)
        assertEquals("anthropic", assistant.provider)
        assertEquals("claude-sonnet-4-5", assistant.model)
        assertEquals("keep me", (context.messages[1] as UserMessage).let { textOf(it) })
    }

    @Test
    fun appendContextEditValidatesTarget() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        assertFailsWith<IllegalArgumentException> {
            session.appendContextEdit("missing", null)
        }
        val system = session.appendMessage(SystemMessage(content = PlainTextContent("system"), timestamp = 0))
        assertFailsWith<IllegalArgumentException> {
            session.appendContextEdit(system.id, null)
        }
        val first = session.appendMessage(userMessage("first"))
        val offBranch = session.appendMessage(userMessage("off branch"))
        session.branch(first.id)
        session.appendMessage(userMessage("on branch"))
        assertFailsWith<IllegalArgumentException> {
            session.appendContextEdit(offBranch.id, null)
        }
    }

    @Test
    fun sessionStatsAggregatesAllUsageSources() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        session.appendMessage(assistantMessage("reply", usage = usage(10, 5, 2, 1, 0.5)))
        session.appendMessage(toolResultMessage(usage = usage(1, 1, 0, 0, 0.1)))
        session.appendUsage(kind = null, provider = "deepseek", model = "m", usage = usage(3, 3, 0, 0, 0.2))
        val first = session.appendMessage(userMessage("compaction anchor"))
        session.appendCompaction(
            "summary",
            firstKeptEntryId = first.id,
            tokensBefore = 100,
            usage = usage(4, 4, 0, 0, 0.3),
        )
        val stats = session.getSessionStats()
        assertEquals(18, stats.inputTokens)
        assertEquals(13, stats.outputTokens)
        assertEquals(2, stats.cacheReadTokens)
        assertEquals(1, stats.cacheWriteTokens)
        assertEquals(1.1, stats.totalCost, 1e-9)
    }

    @Test
    fun sessionNameTracksLatestSessionInfo() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        assertNull(session.getSessionName())
        session.appendSessionInfo("first")
        assertEquals("first", session.getSessionName())
        session.appendSessionInfo("second")
        assertEquals("second", session.getSessionName())
        session.appendSessionInfo(null)
        assertNull(session.getSessionName())
        session.appendSessionInfo("   ")
        assertNull(session.getSessionName())
    }

    @Test
    fun listReportsSessionMetadata() = runTest {
        val fs = FakeFileSystem()
        val paths = agentPaths()
        val first = SessionManager.create(paths, fs)
        first.appendSessionInfo("alpha")
        first.appendMessage(userMessage("one"))
        first.appendMessage(assistantMessage("two"))
        val second = SessionManager.create(paths, fs)
        second.appendMessage(userMessage("other"))
        val infos = SessionManager.list(paths, fs)
        assertEquals(2, infos.size)
        val firstInfo = assertNotNull(infos.firstOrNull { it.sessionId == first.sessionId })
        assertEquals("alpha", firstInfo.name)
        assertEquals(2, firstInfo.messageCount)
        assertEquals("/work/project", firstInfo.cwd)
        assertEquals(first.getHeader().timestamp, firstInfo.timestamp)
        assertEquals(1, infos.first { it.sessionId == second.sessionId }.messageCount)
    }

    @Test
    fun findByIdAndContinueRecentLocateSessions() = runTest {
        val fs = FakeFileSystem()
        val paths = agentPaths()
        val session = SessionManager.create(paths, fs)
        session.appendMessage(userMessage("hello"))
        val found = assertNotNull(SessionManager.findById(session.sessionId, paths, fs))
        assertEquals(session.sessionId, found.sessionId)
        assertNull(SessionManager.findById("does-not-exist", paths, fs))
        val recent = assertNotNull(SessionManager.continueRecent(paths, fs))
        assertEquals(paths.cwd, recent.cwd)
        assertNull(SessionManager.continueRecent(agentPaths(cwd = "/work/empty"), fs))
    }

    @Test
    fun inMemorySessionNeverWritesFile() = runTest {
        val session = SessionManager.inMemory("/work/project".toPath())
        assertNull(session.sessionFile)
        session.appendMessage(userMessage("hello"))
        session.appendMessage(assistantMessage("hi"))
        assertNull(session.sessionFile)
        assertEquals(2, session.buildSessionContext().messages.size)
        assertEquals(2, session.getEntries().size)
    }

    @Test
    fun openEmptyFileInitializesHeader() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "empty.jsonl"
        fs.write(file) { }
        val session = SessionManager.open(file, fs)
        assertEquals(file, session.sessionFile)
        val lines = readLines(fs, file)
        assertEquals(1, lines.size)
        assertEquals("session", lineJson(lines[0]).stringValue("type"))
        assertEquals(session.sessionId, lineJson(lines[0]).stringValue("id"))
    }

    @Test
    fun openRejectsInvalidNonEmptyFile() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "invalid.jsonl"
        fs.write(file) { writeUtf8("not a session\n") }
        assertFailsWith<IllegalArgumentException> { SessionManager.open(file, fs) }
    }

    @Test
    fun createBranchedSessionKeepsOnlySelectedPath() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("first"))
        val kept = session.appendMessage(assistantMessage("kept"))
        session.appendLabelChange(kept.id, "checkpoint")
        session.branch(first.id)
        session.appendMessage(assistantMessage("abandoned"))
        val originalFile = assertNotNull(session.sessionFile)
        val originalLines = readLines(fs, originalFile).size
        session.branch(kept.id)
        val branched = session.createBranchedSession()
        val newFile = assertNotNull(branched.sessionFile)
        assertTrue(fs.exists(newFile))
        assertTrue(newFile != originalFile)
        assertEquals(originalFile.toString(), branched.getHeader().parentSession)
        assertEquals(session.getHeader().timestamp.length, branched.getHeader().timestamp.length)
        val branchedIds = branched.getEntries().map { it.id }
        assertTrue(first.id in branchedIds)
        assertTrue(kept.id in branchedIds)
        assertTrue(branched.getEntries().none { it is SessionMessageEntry && textOf(it.message) == "abandoned" })
        assertTrue(branched.getEntries().any { it is LabelEntry && it.targetId == kept.id && it.label == "checkpoint" })
        assertEquals(originalLines, readLines(fs, originalFile).size)
        assertEquals(branched.buildSessionContext().messages.size, 2)
    }

    @Test
    fun createBranchedSessionWritesFileEvenWithoutConversation() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val modelChange = session.appendModelChange("deepseek", "deepseek-v4-pro")
        val branched = session.createBranchedSession(modelChange.id)
        val newFile = assertNotNull(branched.sessionFile)
        assertTrue(fs.exists(newFile))
        val reopened = SessionManager.open(newFile, fs)
        assertEquals(branched.sessionId, reopened.sessionId)
        assertEquals(listOf(modelChange.id), reopened.getEntries().map { it.id })
        assertEquals(session.sessionFile.toString(), reopened.getHeader().parentSession)
    }

    @Test
    fun reopenedSessionAppendsToSameFile() = runTest {
        val fs = FakeFileSystem()
        val paths = agentPaths()
        val session = SessionManager.create(paths, fs)
        session.appendMessage(userMessage("hello"))
        val file = assertNotNull(session.sessionFile)
        val reopened = SessionManager.open(file, fs)
        val assistant = reopened.appendMessage(assistantMessage("resumed"))
        val lines = readLines(fs, file)
        assertEquals(assistant.id, lineJson(lines.last()).stringValue("id"))
        val again = SessionManager.open(file, fs)
        assertEquals(listOf("user", "assistant"), again.getEntries().map { (it as SessionMessageEntry).message.role() })
        assertEquals(assistant.id, again.getLeafId())
    }

    @Test
    fun labelChangesUpdateResolvedLabelsForBranchedSessions() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        val first = session.appendMessage(userMessage("first"))
        session.appendLabelChange(first.id, "checkpoint")
        session.appendLabelChange(first.id, null)
        val branched = session.createBranchedSession(first.id)
        assertTrue(branched.getEntries().none { it is LabelEntry })
        session.appendLabelChange(first.id, "kept")
        val branchedAgain = session.createBranchedSession(first.id)
        val label = assertIs<LabelEntry>(branchedAgain.getEntries().filterIsInstance<LabelEntry>().single())
        assertEquals(first.id, label.targetId)
        assertEquals("kept", label.label)
    }

    @Test
    fun persistedSessionKeepsUsagesAcrossReopen() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        session.appendMessage(assistantMessage("reply", usage = usage(10, 5, 2, 1, 0.5)))
        val expected = session.getSessionStats()
        val reopened = SessionManager.open(assertNotNull(session.sessionFile), fs)
        assertEquals(expected, reopened.getSessionStats())
    }

    @Test
    fun migrationFromV1AddsIdsAndFirstKeptEntryId() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "v1.jsonl"
        val v1 = listOf(
            """{"type":"session","id":"v1-session","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/work/project"}""",
            """{"type":"message","timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"user","content":"hello","timestamp":1000}}""",
            """{"type":"compaction","timestamp":"2026-01-01T00:00:02.000Z","summary":"summary","firstKeptEntryIndex":1,"tokensBefore":10}""",
        )
        fs.write(file) { writeUtf8(v1.joinToString("\n", postfix = "\n")) }
        val session = SessionManager.open(file, fs)
        assertEquals(3, session.getHeader().version)
        assertEquals(2, session.getEntries().size)
        val messageEntry = assertIs<SessionMessageEntry>(session.getEntries()[0])
        val compaction = assertIs<CompactionEntry>(session.getEntries()[1])
        assertEquals(messageEntry.id, compaction.firstKeptEntryId)
        assertEquals(messageEntry.id, compaction.parentId)
        assertNull(messageEntry.parentId)
        val rewritten = readLines(fs, file)
        assertEquals(3, rewritten.size)
        assertEquals(3, lineJson(rewritten[0]).intValue("version"))
        assertNotNull(lineJson(rewritten[1]).stringValue("id"))
    }

    @Test
    fun migrationFromV2RenamesHookMessageToCustom() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "v2.jsonl"
        val v2 = listOf(
            """{"type":"session","version":2,"id":"v2-session","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/work/project"}""",
            """{"type":"message","id":"aaaa1111","parentId":null,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"hookMessage","customType":"ext","content":"hidden","display":true,"timestamp":1000}}""",
        )
        fs.write(file) { writeUtf8(v2.joinToString("\n", postfix = "\n")) }
        val session = SessionManager.open(file, fs)
        assertEquals(3, session.getHeader().version)
        val message = assertIs<UserMessage>((session.getEntries().single() as SessionMessageEntry).message)
        assertEquals("hidden", textOf(message))
        val rewritten = fs.read(file) { readUtf8() }
        assertTrue(rewritten.contains("\"role\":\"custom\""))
        assertTrue(rewritten.contains("\"version\":3"))
    }

    @Test
    fun bashExecutionMessagesAreContextVisibleUnlessExcluded() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "bash.jsonl"
        val lines = listOf(
            """{"type":"session","version":3,"id":"bash-session","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/work/project"}""",
            """{"type":"message","id":"aaaa0001","parentId":null,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"bashExecution","command":"git status","output":"clean","exitCode":0,"cancelled":false,"truncated":false,"timestamp":1000}}""",
            """{"type":"message","id":"aaaa0002","parentId":"aaaa0001","timestamp":"2026-01-01T00:00:02.000Z","message":{"role":"bashExecution","command":"ls","output":"","cancelled":false,"truncated":false,"excludeFromContext":true,"timestamp":2000}}""",
            """{"type":"message","id":"aaaa0003","parentId":"aaaa0002","timestamp":"2026-01-01T00:00:03.000Z","message":{"role":"user","content":"next","timestamp":3000}}""",
        )
        fs.write(file) { writeUtf8(lines.joinToString("\n", postfix = "\n")) }
        val session = SessionManager.open(file, fs)
        val context = session.buildSessionContext()
        assertEquals(2, context.messages.size)
        assertEquals("Ran `git status`\n```\nclean\n```", textOf(context.messages[0]))
        assertEquals("next", textOf(context.messages[1]))
    }

    @Test
    fun customMessageAndBranchSummaryContributeUserMessages() = runTest {
        val fs = FakeFileSystem()
        val session = SessionManager.create(agentPaths(), fs)
        session.appendCustomMessageEntry(
            customType = "ext",
            content = PlainTextContent("injected context"),
            display = true,
        )
        session.appendBranchSummary(fromId = "abandoned-leaf", summary = "branch recap")
        val messages = session.buildSessionContext().messages
        assertEquals(2, messages.size)
        assertEquals("injected context", textOf(messages[0]))
        assertEquals(
            BRANCH_SUMMARY_PREFIX + "branch recap" + BRANCH_SUMMARY_SUFFIX,
            textOf(messages[1]),
        )
    }

    @Test
    fun readsRealPiSessionFileAndBuildsContext() = runTest {
        val realFs = FileSystem.SYSTEM
        val sampleDir = "/home/user/.pi/agent/sessions/--home-user-project-pi--".toPath()
        if (!realFs.exists(sampleDir)) return@runTest
        val files = realFs.listOrNull(sampleDir)?.filter { it.name.endsWith(".jsonl") }.orEmpty()
        val file = files.minByOrNull { it.name } ?: return@runTest
        val firstLine = realFs.read(file) { readUtf8Line() } ?: return@runTest
        if (!firstLine.contains("\"version\":3")) return@runTest
        val session = SessionManager.open(file, realFs)
        assertTrue(session.getEntries().isNotEmpty())
        assertEquals("/home/user/project/pi", session.cwd.toString())
        val context = session.buildSessionContext()
        assertTrue(context.messages.isNotEmpty())
        assertTrue(session.getHeader().id.isNotEmpty())
        assertEquals(session.getEntries().size, session.getEntries().map { it.id }.distinct().size)
    }

    private fun agentPaths(
        cwd: String = "/work/project",
        agentDir: String = "/home/user/.pi/agent",
    ): AgentPaths = AgentPaths(cwd.toPath(), agentDir.toPath())

    private fun userMessage(text: String, timestamp: Long = 1_000L): UserMessage =
        UserMessage(content = PlainTextContent(text), timestamp = timestamp)

    private fun assistantMessage(
        text: String,
        provider: String = "anthropic",
        model: String = "claude-sonnet-4-5",
        usage: Usage = Usage.empty(),
        timestamp: Long = 2_000L,
    ): AssistantMessage = AssistantMessage(
        content = mutableListOf(TextContent(text)),
        api = "anthropic-messages",
        provider = provider,
        model = model,
        usage = usage,
        stopReason = StopReason.Stop,
        timestamp = timestamp,
    )

    private fun toolResultMessage(usage: Usage): ToolResultMessage = ToolResultMessage(
        toolCallId = "call-1",
        toolName = "bash",
        content = listOf(TextContent("ok")),
        usage = usage,
        isError = false,
        timestamp = 3_000L,
    )

    private fun usage(input: Long, output: Long, cacheRead: Long, cacheWrite: Long, cost: Double): Usage = Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + output + cacheRead + cacheWrite,
        cost = UsageCost(input = cost, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0, total = cost),
    )


    private fun dev.pi.ai.Message.role(): String = when (this) {
        is SystemMessage -> "system"
        is UserMessage -> "user"
        is AssistantMessage -> "assistant"
        is ToolResultMessage -> "toolResult"
    }

    private fun readLines(fs: FileSystem, file: Path): List<String> =
        fs.read(file) { readUtf8() }.trim().split("\n")

    private fun lineJson(line: String): JsonObject = sessionJson.parseToJsonElement(line).jsonObject

    private fun textOf(message: dev.pi.ai.Message): String = when (message) {
        is UserMessage -> when (val content = message.content) {
            is PlainTextContent -> content.text
            is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
        }

        is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        else -> ""
    }
}
