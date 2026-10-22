package dev.pi.coding.compat

import dev.pi.ai.AssistantMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ThinkingContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.Usage
import dev.pi.ai.UserMessage
import dev.pi.ai.http.HttpRequest
import dev.pi.ai.http.HttpResponse
import dev.pi.ai.http.HttpTransport
import dev.pi.ai.providers.FauxProviderOptions
import dev.pi.ai.providers.fauxProvider
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.BRANCH_SUMMARY_PREFIX
import dev.pi.coding.session.BranchSummaryEntry
import dev.pi.coding.session.COMPACTION_SUMMARY_PREFIX
import dev.pi.coding.session.CURRENT_SESSION_VERSION
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.CustomEntry
import dev.pi.coding.session.CustomMessageEntry
import dev.pi.coding.session.LabelEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionHeader
import dev.pi.coding.session.SessionInfoEntry
import dev.pi.coding.session.SessionManager
import dev.pi.coding.session.SessionMessageEntry
import dev.pi.coding.session.SessionStats
import dev.pi.coding.session.ThinkingLevelChangeEntry
import dev.pi.coding.session.UsageEntry
import dev.pi.coding.session.decodeSessionEntry
import dev.pi.coding.session.decodeSessionHeader
import dev.pi.coding.session.encodeSessionEntry
import dev.pi.coding.session.encodeSessionHeader
import dev.pi.coding.session.sessionJson
import dev.pi.coding.session.stringValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private object NoHttpTransport : HttpTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse =
        throw IllegalStateException("network is disabled in this test")
}

private val KNOWN_ENTRY_TYPES = setOf(
    "message",
    "thinking_level_change",
    "model_change",
    "usage",
    "compaction",
    "branch_summary",
    "custom",
    "custom_message",
    "context_edit",
    "label",
    "session_info",
)

private val KNOWN_MESSAGE_ROLES = setOf("system", "user", "assistant", "toolResult", "bashExecution", "custom", "hookMessage")

private data class SessionCandidate(val file: Path, val header: SessionHeader)

private data class SessionAudit(
    val file: Path,
    val entries: Int,
    val types: Set<String>,
    val roles: Map<String, Int>,
    val bashExecutions: Int,
    val contextMessages: Int,
    val contextProvider: String?,
    val contextModelId: String?,
    val stats: SessionStats,
)

class RealSessionCompatibilityTest {
    private val fs: FileSystem = FileSystem.SYSTEM

    private fun sessionsRoot(): Path? {
        val override = System.getenv("PI2K_REAL_SESSIONS_DIR")?.takeIf { it.isNotBlank() } ?: return null
        val path = override.toPath()
        return if (fs.exists(path)) path else null
    }

    private fun skipRealSessionScan(testName: String) {
        println(
            "[skip] $testName: PI2K_REAL_SESSIONS_DIR is not set or does not exist; " +
                "the always-on synthetic fixture suite (SyntheticSessionFixturesTest) covers the session format.",
        )
    }

    private fun jsonlFiles(root: Path): List<Path> = fs.listRecursively(root)
        .filter { it.name.endsWith(".jsonl") }
        .toList()

    private fun firstLine(file: Path): String? = try {
        fs.read(file) { readUtf8Line() }
    } catch (_: Exception) {
        null
    }

    private fun validCandidate(file: Path): SessionCandidate? {
        val line = firstLine(file)?.trim().orEmpty()
        if (line.isEmpty()) return null
        val objectValue = runCatching { sessionJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        if (objectValue.stringValue("type") != "session") return null
        if (objectValue.stringValue("id").isNullOrEmpty()) return null
        val header = runCatching { decodeSessionHeader(objectValue) }.getOrNull() ?: return null
        if (header.version != CURRENT_SESSION_VERSION) return null
        if (header.id.isBlank() || header.cwd.isBlank() || header.timestamp.isBlank()) return null
        return SessionCandidate(file, header)
    }

    @Test
    fun scansAllRealSessionFilesWithoutMutatingThem() = runTest {
        val root = sessionsRoot() ?: run {
            skipRealSessionScan("scansAllRealSessionFilesWithoutMutatingThem")
            return@runTest
        }
        val files = jsonlFiles(root)
        assertTrue(files.isNotEmpty(), "no .jsonl files under $root")
        var valid = 0
        var invalid = 0
        val ids = mutableSetOf<String>()
        for (file in files) {
            val candidate = validCandidate(file)
            if (candidate == null) {
                invalid++
                continue
            }
            valid++
            assertTrue(ids.add(candidate.header.id), "duplicate session id ${candidate.header.id} in $file")
            assertEquals(CURRENT_SESSION_VERSION, candidate.header.version)
        }
        assertTrue(valid >= minOf(300, files.size), "valid sessions $valid, files ${files.size}")
        assertTrue(invalid <= maxOf(1, files.size / 20), "unexpected invalid session files: $invalid of ${files.size}")
    }

    @Test
    fun opensRealSessionsReadOnlyAndRebuildsCanonicalContext() = runTest(timeout = 180.seconds) {
        val root = sessionsRoot() ?: run {
            skipRealSessionScan("opensRealSessionsReadOnlyAndRebuildsCanonicalContext")
            return@runTest
        }
        val candidates = jsonlFiles(root).mapNotNull(::validCandidate)
        assertTrue(candidates.isNotEmpty(), "no valid pi session files under $root")
        val selected = candidates
            .sortedBy { fs.metadataOrNull(it.file)?.size ?: Long.MAX_VALUE }
            .take(minOf(300, candidates.size))
        val audits = withContext(Dispatchers.Default) {
            coroutineScope {
                selected.chunked(16).map { chunk ->
                    async { chunk.map { candidate -> audit(candidate) } }
                }.awaitAll().flatten()
            }
        }
        assertEquals(selected.size, audits.size)
        assertEquals(minOf(300, candidates.size), audits.size)
        assertTrue(audits.sumOf { it.entries } > 0)
        assertTrue(audits.sumOf { it.contextMessages } > 0)

        val observedTypes = audits.flatMap { it.types }.toSet()
        val expectedInRealData = setOf(
            "message",
            "custom",
            "custom_message",
            "model_change",
            "thinking_level_change",
            "session_info",
            "compaction",
            "context_edit",
        )
        assertTrue(
            observedTypes.containsAll(expectedInRealData),
            "missing entry types in real data: ${expectedInRealData - observedTypes}",
        )
        assertTrue(KNOWN_ENTRY_TYPES.containsAll(observedTypes), "unknown entry types: ${observedTypes - KNOWN_ENTRY_TYPES}")

        val roleCounts = mutableMapOf<String, Int>()
        for (audit in audits) {
            for ((role, count) in audit.roles) roleCounts[role] = (roleCounts[role] ?: 0) + count
        }
        for (role in listOf("user", "assistant", "toolResult", "system", "bashExecution")) {
            assertTrue((roleCounts[role] ?: 0) > 0, "no $role messages found in real sessions")
        }
        assertTrue(audits.sumOf { it.bashExecutions } > 0, "no bashExecution messages parsed")

        val totalInput = audits.sumOf { it.stats.inputTokens }
        val totalOutput = audits.sumOf { it.stats.outputTokens }
        assertTrue(totalInput > 0)
        assertTrue(totalOutput > 0)
        assertTrue(audits.sumOf { it.stats.totalCost } >= 0.0)
        assertTrue(audits.count { it.contextProvider != null } > 0)
        for (audit in audits) {
            if (audit.contextProvider != null) assertNotNull(audit.contextModelId, "provider without model in ${audit.file}")
        }
    }

    private suspend fun audit(candidate: SessionCandidate): SessionAudit {
        try {
            val before = fs.metadataOrNull(candidate.file)
            val session = SessionManager.open(candidate.file, fs)
            try {
                val entries = session.getEntries()
                assertTrue(entries.isNotEmpty(), "no entries in ${candidate.file}")
                val header = session.getHeader()
                assertEquals(candidate.header.id, header.id, "header id in ${candidate.file}")
                assertEquals(candidate.header.id, session.sessionId)
                assertEquals(candidate.header.timestamp, header.timestamp)
                assertEquals(candidate.header.cwd, header.cwd)
                assertEquals(candidate.file, session.sessionFile)
                assertEquals(candidate.header.cwd.toPath(), session.cwd)
                assertEquals(
                    entries.map { it.id }.toSet().size,
                    entries.size,
                    "duplicate entry ids in ${candidate.file}",
                )
                val ids = entries.mapTo(mutableSetOf()) { it.id }
                for (entry in entries) {
                    assertTrue(KNOWN_ENTRY_TYPES.contains(entry.type), "unknown entry type ${entry.type} in ${candidate.file}")
                    val parent = entry.parentId
                    assertTrue(parent == null || ids.contains(parent), "dangling parentId in ${candidate.file}")
                }
                val leafId = assertNotNull(session.getLeafId(), "no leaf in ${candidate.file}")
                assertNotNull(session.getEntry(leafId))
                val branch = session.getBranch()
                assertTrue(branch.isNotEmpty())
                assertEquals(leafId, branch.last().id)

                val types = mutableSetOf<String>()
                val roles = mutableMapOf<String, Int>()
                var bashExecutions = 0
                for (entry in entries) {
                    types.add(entry.type)
                    if (entry !is SessionMessageEntry) continue
                    val raw = assertNotNull(entry.rawMessage, "message entry without rawMessage in ${candidate.file}")
                    val role = assertNotNull(raw.stringValue("role"), "message without role in ${candidate.file}")
                    assertTrue(KNOWN_MESSAGE_ROLES.contains(role), "unknown message role $role in ${candidate.file}")
                    roles[role] = (roles[role] ?: 0) + 1
                    when (role) {
                        "system" -> assertIs<dev.pi.ai.SystemMessage>(entry.message)
                        "user" -> assertIs<UserMessage>(entry.message)
                        "assistant" -> assertIs<AssistantMessage>(entry.message)
                        "toolResult" -> assertIs<ToolResultMessage>(entry.message)
                        "bashExecution" -> {
                            bashExecutions++
                            val message = assertIs<UserMessage>(entry.message)
                            val text = messageText(message)
                            assertTrue(text.startsWith("Ran `"), "unexpected bashExecution rendering in ${candidate.file}")
                        }

                        else -> assertIs<UserMessage>(entry.message)
                    }
                }

                val context = session.buildSessionContext()
                if (context.provider != null) assertNotNull(context.modelId, "provider without model in ${candidate.file}")
                val stats = session.getSessionStats()
                val expectedStats = expectedStats(entries)
                assertEquals(expectedStats.inputTokens, stats.inputTokens, "input tokens in ${candidate.file}")
                assertEquals(expectedStats.outputTokens, stats.outputTokens, "output tokens in ${candidate.file}")
                assertEquals(expectedStats.cacheReadTokens, stats.cacheReadTokens, "cache read in ${candidate.file}")
                assertEquals(expectedStats.cacheWriteTokens, stats.cacheWriteTokens, "cache write in ${candidate.file}")
                assertEquals(expectedStats.totalCost, stats.totalCost, 1e-9, "cost in ${candidate.file}")

                val branchIds = branch.mapTo(mutableSetOf()) { it.id }
                if (branch.any { it is CompactionEntry }) {
                    assertCompactionContext(candidate.file, context.messages)
                }
                val projectEntries = session.buildSessionProjection()
                assertTrue(projectEntries.isNotEmpty() || entries.isEmpty())

                val rawLines = try {
                    fs.read(candidate.file) { readUtf8() }.lineSequence().count { it.isNotBlank() }
                } catch (_: Exception) {
                    -1
                }
                if (rawLines >= 1) {
                    assertEquals(rawLines - 1, entries.size, "decoded entry count != raw line count in ${candidate.file}")
                }

                val after = fs.metadataOrNull(candidate.file)
                if (before != null && after != null) {
                    val recent = System.currentTimeMillis() - (before.lastModifiedAtMillis ?: 0L) < 600_000L
                    if (!recent) {
                        assertEquals(before.size, after.size, "session file changed while reading ${candidate.file}")
                        assertEquals(
                            before.lastModifiedAtMillis,
                            after.lastModifiedAtMillis,
                            "session file mtime changed while reading ${candidate.file}",
                        )
                    }
                }

                return SessionAudit(
                    file = candidate.file,
                    entries = entries.size,
                    types = types,
                    roles = roles,
                    bashExecutions = bashExecutions,
                    contextMessages = context.messages.size,
                    contextProvider = context.provider,
                    contextModelId = context.modelId,
                    stats = stats,
                )
            } finally {
                session.close()
            }
        } catch (error: AssertionError) {
            throw AssertionError("${candidate.file}: ${error.message}", error)
        }
    }

    private fun assertCompactionContext(file: Path, messages: List<dev.pi.ai.Message>) {
        val firstUser = messages.filterIsInstance<UserMessage>().firstOrNull()
        assertNotNull(firstUser, "compaction branch without user message in $file")
        assertTrue(
            messageText(firstUser).startsWith(COMPACTION_SUMMARY_PREFIX),
            "compaction summary not first in context of $file",
        )
    }

    private fun expectedStats(entries: List<SessionEntry>): SessionStats {
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var cost = 0.0
        fun add(usage: Usage?) {
            if (usage == null) return
            input += usage.input
            output += usage.output
            cacheRead += usage.cacheRead
            cacheWrite += usage.cacheWrite
            cost += usage.cost.total
        }
        for (entry in entries) {
            when (entry) {
                is UsageEntry -> add(entry.usage)
                is CompactionEntry -> add(entry.usage)
                is BranchSummaryEntry -> add(entry.usage)
                is SessionMessageEntry -> when (val message = entry.message) {
                    is AssistantMessage -> add(message.usage)
                    is ToolResultMessage -> add(message.usage)
                    else -> Unit
                }

                else -> Unit
            }
        }
        return SessionStats(input, output, cacheRead, cacheWrite, cost)
    }

    private fun messageText(message: UserMessage): String = when (val content = message.content) {
        is dev.pi.ai.PlainTextContent -> content.text
        is dev.pi.ai.BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
    }

    @Test
    fun listsRealSessionMetadataAndLocatesSessionsById() = runTest {
        val root = sessionsRoot() ?: run {
            skipRealSessionScan("listsRealSessionMetadataAndLocatesSessionsById")
            return@runTest
        }
        val directories = fs.listOrNull(root).orEmpty().filter { dir ->
            fs.metadataOrNull(dir)?.isDirectory == true
        }
        val target = directories.mapNotNull { dir ->
            val files = fs.listOrNull(dir).orEmpty().filter { it.name.endsWith(".jsonl") }
            if (files.size < 2) null else dir to files
        }.minByOrNull { (_, files) -> files.sumOf { fs.metadataOrNull(it)?.size ?: 0L } } ?: return@runTest
        val (dir, files) = target
        val header = files.firstNotNullOfOrNull { validCandidate(it)?.header } ?: return@runTest
        val agentDir = assertNotNull(root.parent)
        val paths = dev.pi.coding.paths.AgentPaths(header.cwd.toPath(), agentDir)
        assertEquals(dir, paths.sessionsForCwd())

        val infos = SessionManager.list(paths, fs)
        assertTrue(infos.isNotEmpty(), "no session metadata under $dir")
        assertTrue(infos.all { it.sessionFile.parent == dir })
        assertTrue(infos.any { it.messageCount > 0 }, "no message-bearing session under $dir")
        for (info in infos) {
            val candidate = validCandidate(info.sessionFile)
            if (candidate == null) continue
            assertEquals(candidate.header.id, info.sessionId)
            assertEquals(candidate.header.timestamp, info.timestamp)
            assertEquals(candidate.header.cwd, info.cwd)
        }
        assertTrue(infos.any { validCandidate(it.sessionFile) != null }, "no decodable session metadata under $dir")
        val known = infos.first { validCandidate(it.sessionFile) != null }
        val found = assertNotNull(SessionManager.findById(known.sessionId, paths, fs))
        assertEquals(known.sessionId, found.sessionId)
        assertEquals(known.sessionFile, found.sessionFile)
        val recent = SessionManager.continueRecent(paths, fs)
        if (recent != null) assertTrue(infos.any { it.sessionId == recent.sessionId })
    }

    @Test
    fun parsesGoldenSessionWithEveryEntryTypeAndRoundTrips() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "golden.jsonl"
        val lines = listOf(
            """{"type":"session","version":3,"id":"golden-session","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/work/project","parentSession":"/agent/sessions/parent.jsonl"}""",
            """{"type":"message","id":"aaaa0001","parentId":null,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"hello"}],"timestamp":1000}}""",
            """{"type":"thinking_level_change","id":"aaaa0002","parentId":"aaaa0001","timestamp":"2026-01-01T00:00:02.000Z","thinkingLevel":"high"}""",
            """{"type":"model_change","id":"aaaa0003","parentId":"aaaa0002","timestamp":"2026-01-01T00:00:03.000Z","provider":"anthropic","modelId":"claude-sonnet-4-5"}""",
            """{"type":"usage","id":"aaaa0004","parentId":"aaaa0003","timestamp":"2026-01-01T00:00:04.000Z","kind":"summary","provider":"anthropic","model":"claude-sonnet-4-5","usage":{"input":10,"output":5,"cacheRead":2,"cacheWrite":1,"totalTokens":18,"cost":{"input":0.1,"output":0.2,"cacheRead":0.03,"cacheWrite":0.04,"total":0.37}},"note":"note-1"}""",
            """{"type":"message","id":"aaaa0005","parentId":"aaaa0004","timestamp":"2026-01-01T00:00:05.000Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"hi"}],"api":"anthropic-messages","provider":"anthropic","model":"claude-sonnet-4-5","usage":{"input":4,"output":3,"cacheRead":0,"cacheWrite":0,"totalTokens":7,"cost":{"input":0.01,"output":0.02,"cacheRead":0.0,"cacheWrite":0.0,"total":0.03}},"stopReason":"stop","timestamp":2000}}""",
            """{"type":"custom","id":"aaaa0006","parentId":"aaaa0005","timestamp":"2026-01-01T00:00:06.000Z","customType":"ext","data":{"k":1}}""",
            """{"type":"custom_message","id":"aaaa0007","parentId":"aaaa0006","timestamp":"2026-01-01T00:00:07.000Z","customType":"ext","content":[{"type":"text","text":"injected"}],"display":true,"details":{"d":true}}""",
            """{"type":"label","id":"aaaa0008","parentId":"aaaa0007","timestamp":"2026-01-01T00:00:08.000Z","targetId":"aaaa0001","label":"checkpoint"}""",
            """{"type":"context_edit","id":"aaaa0009","parentId":"aaaa0008","timestamp":"2026-01-01T00:00:09.000Z","targetId":"aaaa0007","replacement":{"content":[{"type":"text","text":"masked"}]}}""",
            """{"type":"branch_summary","id":"aaaa000a","parentId":"aaaa0009","timestamp":"2026-01-01T00:00:10.000Z","fromId":"aaaa0005","summary":"branch recap","details":{"readFiles":[]},"usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":2,"cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}""",
            """{"type":"compaction","id":"aaaa000b","parentId":"aaaa000a","timestamp":"2026-01-01T00:00:11.000Z","summary":"compacted","firstKeptEntryId":"aaaa0005","tokensBefore":123,"details":{"readFiles":["/work/a"],"modifiedFiles":[]},"systemMessage":{"role":"system","content":"","sections":{"preamble":"sys"},"timestamp":0}}""",
            """{"type":"session_info","id":"aaaa000c","parentId":"aaaa000b","timestamp":"2026-01-01T00:00:12.000Z","name":"golden"}""",
        )
        fs.write(file) { writeUtf8(lines.joinToString("\n", postfix = "\n")) }

        val session = SessionManager.open(file, fs)
        val header = session.getHeader()
        assertEquals(3, header.version)
        assertEquals("golden-session", header.id)
        assertEquals("/work/project", header.cwd)
        assertEquals("/agent/sessions/parent.jsonl", header.parentSession)
        assertEquals("golden-session", session.sessionId)

        val entries = session.getEntries()
        assertEquals(12, entries.size)
        assertEquals(KNOWN_ENTRY_TYPES, entries.mapTo(mutableSetOf()) { it.type })
        assertEquals("golden", session.getSessionName())
        assertEquals("aaaa000c", session.getLeafId())
        assertEquals(12, session.getBranch().size)

        assertIs<ThinkingLevelChangeEntry>(entries[1]).also { assertEquals("high", it.thinkingLevel) }
        assertIs<dev.pi.coding.session.ModelChangeEntry>(entries[2]).also {
            assertEquals("anthropic", it.provider)
            assertEquals("claude-sonnet-4-5", it.modelId)
        }
        assertIs<UsageEntry>(entries[3]).also {
            assertEquals("summary", it.kind)
            assertEquals("note-1", it.note)
            assertEquals(18, it.usage.totalTokens)
            assertEquals(0.37, it.usage.cost.total, 1e-9)
        }
        val assistant = assertIs<AssistantMessage>(assertIs<SessionMessageEntry>(entries[4]).message)
        assertEquals(2, assistant.content.size)
        assertIs<ThinkingContent>(assistant.content[0])
        assertIs<TextContent>(assistant.content[1])
        assertEquals("claude-sonnet-4-5", assistant.model)
        assertEquals(7, assistant.usage.totalTokens)
        assertIs<CustomEntry>(entries[5]).also { assertEquals("ext", it.customType) }
        assertIs<CustomMessageEntry>(entries[6]).also {
            assertEquals("ext", it.customType)
            assertEquals(true, it.display)
        }
        val label = assertIs<LabelEntry>(entries[7])
        assertEquals("aaaa0001", label.targetId)
        assertEquals("checkpoint", label.label)
        val contextEdit = assertIs<dev.pi.coding.session.ContextEditEntry>(entries[8])
        assertEquals("aaaa0007", contextEdit.targetId)
        assertNotNull(contextEdit.replacement)
        assertIs<BranchSummaryEntry>(entries[9]).also {
            assertEquals("aaaa0005", it.fromId)
            assertEquals("branch recap", it.summary)
            assertNotNull(it.usage)
        }
        val compaction = assertIs<CompactionEntry>(entries[10])
        assertEquals("compacted", compaction.summary)
        assertEquals("aaaa0005", compaction.firstKeptEntryId)
        assertEquals(123, compaction.tokensBefore)
        val snapshot = assertNotNull(compaction.systemMessage)
        assertEquals("sys", snapshot.sections?.get("preamble"))
        assertIs<SessionInfoEntry>(entries.last()).also { assertEquals("golden", it.name) }

        val stats = session.getSessionStats()
        assertEquals(15, stats.inputTokens)
        assertEquals(9, stats.outputTokens)
        assertEquals(2, stats.cacheReadTokens)
        assertEquals(1, stats.cacheWriteTokens)
        assertEquals(0.4, stats.totalCost, 1e-9)

        val context = session.buildSessionContext()
        assertEquals("anthropic", context.provider)
        assertEquals("claude-sonnet-4-5", context.modelId)
        assertEquals("high", context.thinkingLevel)
        val firstUser = assertNotNull(context.messages.filterIsInstance<UserMessage>().firstOrNull())
        assertTrue(messageText(firstUser).startsWith(COMPACTION_SUMMARY_PREFIX))
        val branchMessage = context.messages.filterIsInstance<UserMessage>().firstOrNull {
            messageText(it).startsWith(BRANCH_SUMMARY_PREFIX)
        }
        assertNotNull(branchMessage)
        assertNotNull(session.buildSessionProjection())

        for (entry in entries) {
            val encoded = encodeSessionEntry(entry)
            val decoded = assertNotNull(
                decodeSessionEntry(sessionJson.parseToJsonElement(encoded) as JsonObject),
                "failed to decode re-encoded ${entry.type}",
            )
            assertEquals(encoded, encodeSessionEntry(decoded), "unstable round-trip for ${entry.type}")
        }
    }

    @Test
    fun roundTripsRealSessionsThroughSerializeAndReopen() = runTest {
        val root = sessionsRoot() ?: run {
            skipRealSessionScan("roundTripsRealSessionsThroughSerializeAndReopen")
            return@runTest
        }
        val candidates = jsonlFiles(root).mapNotNull(::validCandidate)
            .sortedBy { fs.metadataOrNull(it.file)?.size ?: Long.MAX_VALUE }
            .take(20)
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val original = SessionManager.open(candidate.file, fs)
            try {
                val header = original.getHeader()
                val entries = original.getEntries()
                val copyFs = FakeFileSystem()
                val dir = "/agent/sessions/copy".toPath()
                copyFs.createDirectories(dir)
                val copyFile = dir / "session.jsonl"
                val text = buildString {
                    append(encodeSessionHeader(header))
                    append('\n')
                    for (entry in entries) {
                        append(encodeSessionEntry(entry))
                        append('\n')
                    }
                }
                copyFs.write(copyFile) { writeUtf8(text) }
                val copy = SessionManager.open(copyFile, copyFs)
                try {
                    assertEquals(entries.size, copy.getEntries().size, "entry count after round-trip of ${candidate.file}")
                    assertEquals(
                        entries.map { it.type },
                        copy.getEntries().map { it.type },
                        "entry types after round-trip of ${candidate.file}",
                    )
                    assertEquals(
                        original.buildSessionContext().messages,
                        copy.buildSessionContext().messages,
                        "context after round-trip of ${candidate.file}",
                    )
                    assertEquals(
                        original.buildSessionContext().provider,
                        copy.buildSessionContext().provider,
                    )
                    assertEquals(
                        original.buildSessionContext().modelId,
                        copy.buildSessionContext().modelId,
                    )
                    assertEquals(
                        original.buildSessionContext().thinkingLevel,
                        copy.buildSessionContext().thinkingLevel,
                    )
                    assertEquals(original.getSessionStats(), copy.getSessionStats())
                } finally {
                    copy.close()
                }
            } finally {
                original.close()
            }
        }
    }

    @Test
    fun sdkRestoresRealPiSessionBytesWithoutWriting() = runTest {
        val root = sessionsRoot() ?: run {
            skipRealSessionScan("sdkRestoresRealPiSessionBytesWithoutWriting")
            return@runTest
        }
        val candidates = jsonlFiles(root).mapNotNull(::validCandidate)
            .sortedBy { fs.metadataOrNull(it.file)?.size ?: Long.MAX_VALUE }
            .take(3)
        assertTrue(candidates.isNotEmpty())
        for (candidate in candidates) {
            val originalText = fs.read(candidate.file) { readUtf8() }
            val fakeFs = FakeFileSystem()
            fakeFs.createDirectories("/agent/sessions/real".toPath())
            fakeFs.createDirectories("/work".toPath())
            val copyFile = "/agent/sessions/real/session.jsonl".toPath()
            fakeFs.write(copyFile) { writeUtf8(originalText) }
            val faux = fauxProvider(FauxProviderOptions())
            val session = createAgentSession(
                AgentSessionOptions(
                    cwd = "/work".toPath(),
                    agentDir = "/agent".toPath(),
                    transport = NoHttpTransport,
                    fs = fakeFs,
                    model = "faux/faux-1",
                    tools = emptyList(),
                    sessionFile = copyFile,
                    providers = listOf(faux.provider),
                ),
            )
            try {
                assertEquals(candidate.header.id, session.sessionId)
                assertEquals(copyFile, session.sessionFile)
                assertTrue(session.messages.isNotEmpty(), "restored no messages from ${candidate.file}")
                val manager = SessionManager.open(copyFile, fakeFs)
                val contextSize = manager.buildSessionContext().messages.size
                val prependedSystem = if (manager.buildSessionContext().messages.firstOrNull() is dev.pi.ai.SystemMessage) 0 else 1
                assertEquals(contextSize + prependedSystem, session.messages.size)
                assertEquals(originalText, fakeFs.read(copyFile) { readUtf8() }, "SDK modified restored session ${candidate.file}")
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun contextEditReplacementAppliesToModelContext() = runTest {
        val fs = FakeFileSystem()
        val dir = "/agent/sessions/--work-project--".toPath()
        fs.createDirectories(dir)
        val file = dir / "context-edit.jsonl"
        val lines = listOf(
            """{"type":"session","version":3,"id":"edit-session","timestamp":"2026-01-01T00:00:00.000Z","cwd":"/work/project"}""",
            """{"type":"message","id":"bbbb0001","parentId":null,"timestamp":"2026-01-01T00:00:01.000Z","message":{"role":"user","content":[{"type":"text","text":"original"}],"timestamp":1000}}""",
            """{"type":"message","id":"bbbb0002","parentId":"bbbb0001","timestamp":"2026-01-01T00:00:02.000Z","message":{"role":"assistant","content":[{"type":"text","text":"answer"}],"api":"anthropic-messages","provider":"anthropic","model":"claude-sonnet-4-5","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}},"stopReason":"stop","timestamp":2000}}""",
            """{"type":"context_edit","id":"bbbb0003","parentId":"bbbb0002","timestamp":"2026-01-01T00:00:03.000Z","targetId":"bbbb0001","replacement":{"content":[{"type":"text","text":"edited"}]}}""",
            """{"type":"label","id":"bbbb0004","parentId":"bbbb0003","timestamp":"2026-01-01T00:00:04.000Z","targetId":"bbbb0002","label":"answer-checkpoint"}""",
        )
        fs.write(file) { writeUtf8(lines.joinToString("\n", postfix = "\n")) }
        val session = SessionManager.open(file, fs)
        val messages = session.buildSessionContext().messages
        val user = assertIs<UserMessage>(messages[0])
        assertEquals("edited", messageText(user))
        val projection = session.buildSessionProjection()
        val projectedUser = assertIs<UserMessage>(
            assertIs<SessionMessageEntry>(projection.first { it.id == "bbbb0001" }).message,
        )
        assertEquals("edited", messageText(projectedUser))
        val label = assertIs<LabelEntry>(session.getEntries().filterIsInstance<LabelEntry>().single())
        assertEquals("answer-checkpoint", label.label)
    }
}
