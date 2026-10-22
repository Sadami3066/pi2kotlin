package dev.pi.coding.compat

import dev.pi.ai.AssistantMessage
import dev.pi.ai.BlockContent
import dev.pi.ai.Message
import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.TextContent
import dev.pi.ai.ToolResultMessage
import dev.pi.ai.UserMessage
import dev.pi.coding.session.BRANCH_SUMMARY_PREFIX
import dev.pi.coding.session.BRANCH_SUMMARY_SUFFIX
import dev.pi.coding.session.COMPACTION_SUMMARY_PREFIX
import dev.pi.coding.session.COMPACTION_SUMMARY_SUFFIX
import dev.pi.coding.session.CompactionEntry
import dev.pi.coding.session.ContextEditEntry
import dev.pi.coding.session.CustomEntry
import dev.pi.coding.session.CustomMessageEntry
import dev.pi.coding.session.CURRENT_SESSION_VERSION
import dev.pi.coding.session.LabelEntry
import dev.pi.coding.session.ModelChangeEntry
import dev.pi.coding.session.SessionEntry
import dev.pi.coding.session.SessionInfoEntry
import dev.pi.coding.session.SessionManager
import dev.pi.coding.session.SessionMessageEntry
import dev.pi.coding.session.SessionStats
import dev.pi.coding.session.ThinkingLevelChangeEntry
import dev.pi.coding.session.UsageEntry
import dev.pi.coding.session.decodeSessionEntry
import dev.pi.coding.session.encodeSessionEntry
import dev.pi.coding.session.sessionJson
import dev.pi.coding.session.stringValue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SESSION_DIR = "/agent/sessions/--work-project--"

private val ALL_ENTRY_TYPES = setOf(
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

class SyntheticSessionFixturesTest {
    private fun fixtureText(name: String): String {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream("fixtures/sessions/$name")) {
            "missing fixture fixtures/sessions/$name"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private suspend fun openFixture(
        fs: FakeFileSystem,
        name: String,
    ): Pair<SessionManager, Path> {
        val dir = SESSION_DIR.toPath()
        fs.createDirectories(dir)
        val file = dir / name
        fs.write(file) { writeUtf8(fixtureText(name)) }
        return SessionManager.open(file, fs) to file
    }

    private fun textOf(message: Message): String = when (message) {
        is UserMessage -> when (val content = message.content) {
            is PlainTextContent -> content.text
            is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
        }

        is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        is SystemMessage -> when (val content = message.content) {
            is PlainTextContent -> content.text
            is BlockContent -> content.blocks.filterIsInstance<TextContent>().joinToString("") { it.text }
        }

        else -> ""
    }

    private fun userTexts(messages: List<Message>): List<String> =
        messages.filterIsInstance<UserMessage>().map(::textOf)

    private fun assertStats(expected: SessionStats, actual: SessionStats) {
        assertEquals(expected.inputTokens, actual.inputTokens)
        assertEquals(expected.outputTokens, actual.outputTokens)
        assertEquals(expected.cacheReadTokens, actual.cacheReadTokens)
        assertEquals(expected.cacheWriteTokens, actual.cacheWriteTokens)
        assertEquals(expected.totalCost, actual.totalCost, 1e-9)
    }

    private fun assertRoundTripStable(entries: List<SessionEntry>) {
        for (entry in entries) {
            val encoded = encodeSessionEntry(entry)
            val decoded = assertNotNull(
                decodeSessionEntry(sessionJson.parseToJsonElement(encoded) as JsonObject),
                "could not decode re-encoded ${entry.type}",
            )
            assertEquals(encoded, encodeSessionEntry(decoded), "unstable round-trip for ${entry.type}")
        }
    }

    private suspend fun assertReencodedCopyMatches(
        original: SessionManager,
        originalFile: Path,
    ) {
        val copyFs = FakeFileSystem()
        val dir = SESSION_DIR.toPath()
        copyFs.createDirectories(dir)
        val copyFile = dir / "copy.jsonl"
        copyFs.write(copyFile) {
            writeUtf8(encodeEntriesFile(original))
        }
        val copy = SessionManager.open(copyFile, copyFs)
        try {
            assertEquals(original.getHeader().id, copy.getHeader().id)
            assertEquals(original.getHeader().timestamp, copy.getHeader().timestamp)
            assertEquals(original.getHeader().cwd, copy.getHeader().cwd)
            assertEquals(
                original.getEntries().map { it.type },
                copy.getEntries().map { it.type },
                "entry type sequence differs after re-encoding $originalFile",
            )
            assertEquals(
                original.getEntries().map { it.id },
                copy.getEntries().map { it.id },
                "entry id sequence differs after re-encoding $originalFile",
            )
            assertEquals(original.getSessionStats(), copy.getSessionStats())
            assertRoundTripStable(copy.getEntries())
        } finally {
            copy.close()
        }
    }

    private fun encodeEntriesFile(session: SessionManager): String = buildString {
        append(dev.pi.coding.session.encodeSessionHeader(session.getHeader()))
        append('\n')
        for (entry in session.getEntries()) {
            append(encodeSessionEntry(entry))
            append('\n')
        }
    }

    private fun rawLines(fs: FileSystem, file: Path): List<String> =
        fs.read(file) { readUtf8() }.lineSequence().filter { it.isNotBlank() }.toList()

    @Test
    fun everyFixtureIsWellFormedAndRoundTrips() = runTest {
        val fixtureNames = listOf(
            "v3-all-entries.jsonl",
            "v3-context-edits.jsonl",
            "v3-compaction-chain.jsonl",
            "v3-bash-custom-messages.jsonl",
            "v3-branched-tree.jsonl",
            "v3-usage-stats.jsonl",
            "v1-legacy.jsonl",
            "v2-legacy.jsonl",
        )
        var totalEntries = 0
        for (name in fixtureNames) {
            val fs = FakeFileSystem()
            val (session, file) = openFixture(fs, name)
            try {
                val entries = session.getEntries()
                assertTrue(entries.isNotEmpty(), "$name has no entries")
                totalEntries += entries.size
                val ids = entries.map { it.id }
                assertEquals(ids.size, ids.toSet().size, "$name has duplicate entry ids")
                val idSet = ids.toSet()
                for (entry in entries) {
                    assertTrue(ALL_ENTRY_TYPES.contains(entry.type), "$name has unknown entry type ${entry.type}")
                    val parentId = entry.parentId
                    assertTrue(parentId == null || idSet.contains(parentId), "$name has dangling parentId")
                    assertNotNull(session.getEntry(entry.id), "$name cannot resolve entry ${entry.id}")
                }
                val leafId = assertNotNull(session.getLeafId(), "$name has no leaf")
                assertEquals(leafId, session.getBranch().last().id)
                val header = session.getHeader()
                assertEquals(CURRENT_SESSION_VERSION, header.version, "$name was not migrated to the current version")
                assertTrue(header.id.isNotEmpty())
                assertTrue(header.cwd.isNotEmpty())
                assertTrue(header.timestamp.isNotEmpty())
                assertEquals(rawLines(fs, file).size - 1, entries.size, "$name decoded entry count != raw line count")
                assertRoundTripStable(entries)
                assertReencodedCopyMatches(session, file)
                assertNotNull(session.buildSessionContext())
                assertTrue(session.buildSessionProjection().isNotEmpty())
            } finally {
                session.close()
            }
        }
        assertEquals(54, totalEntries, "unexpected total fixture entry count")
    }

    @Test
    fun v3AllEntriesFixtureCoversEveryEntryType() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v3-all-entries.jsonl")
        try {
            val header = session.getHeader()
            assertEquals(3, header.version)
            assertEquals("fixture-all-entries", header.id)
            assertEquals("/work/project", header.cwd)
            assertEquals("/agent/sessions/--work-project--/parent.jsonl", header.parentSession)
            assertEquals("fixture-all-entries", session.sessionId)

            val entries = session.getEntries()
            assertEquals(16, entries.size)
            assertEquals(ALL_ENTRY_TYPES, entries.mapTo(mutableSetOf()) { it.type })
            assertEquals("fixture-all-entries", session.getSessionName())
            assertEquals("e0000010", session.getLeafId())
            assertEquals(16, session.getBranch().size)

            assertIs<ThinkingLevelChangeEntry>(entries[1]).also { assertEquals("high", it.thinkingLevel) }
            assertIs<ModelChangeEntry>(entries[2]).also {
                assertEquals("anthropic", it.provider)
                assertEquals("claude-sonnet-4-5", it.modelId)
            }
            val assistant = assertIs<AssistantMessage>(assertIs<SessionMessageEntry>(entries[3]).message)
            assertEquals(2, assistant.content.size)
            assertEquals(listOf("plan", "hi"), assistant.content.map { block ->
                when (block) {
                    is dev.pi.ai.ThinkingContent -> block.thinking
                    is TextContent -> block.text
                    else -> ""
                }
            })
            assertIs<CustomEntry>(entries[4]).also { assertEquals("ext", it.customType) }
            assertIs<CustomMessageEntry>(entries[5]).also {
                assertEquals("ext", it.customType)
                assertEquals(true, it.display)
            }
            val replacementEdit = assertIs<ContextEditEntry>(entries[6])
            assertEquals("e0000006", replacementEdit.targetId)
            assertNotNull(replacementEdit.replacement)
            val droppedCustomMessage = assertIs<CustomMessageEntry>(entries[7])
            assertEquals("drop me", (droppedCustomMessage.content as PlainTextContent).text)
            assertNull(assertIs<ContextEditEntry>(entries[8]).replacement)
            assertIs<dev.pi.coding.session.BranchSummaryEntry>(entries[9]).also {
                assertEquals("e0000004", it.fromId)
                assertEquals("branch recap", it.summary)
                assertNotNull(it.usage)
            }
            val compaction = assertIs<CompactionEntry>(entries[10])
            assertEquals("compacted", compaction.summary)
            assertEquals("e0000004", compaction.firstKeptEntryId)
            assertEquals(123, compaction.tokensBefore)
            assertNotNull(compaction.usage)
            assertEquals(false, compaction.fromHook)
            val snapshot = assertNotNull(compaction.systemMessage)
            assertEquals("sys", snapshot.sections?.get("preamble"))
            assertIs<UsageEntry>(entries[11]).also {
                assertEquals("cache_warm", it.kind)
                assertEquals("warm", it.note)
                assertEquals(8, it.usage.totalTokens)
            }
            assertIs<LabelEntry>(entries[12]).also {
                assertEquals("e0000001", it.targetId)
                assertEquals("checkpoint", it.label)
            }
            assertIs<SessionInfoEntry>(entries[13]).also { assertEquals("fixture-all-entries", it.name) }
            assertNull(assertIs<LabelEntry>(entries[14]).label)

            assertStats(SessionStats(12, 5, 5, 1, 0.17), session.getSessionStats())

            val contextEntries = session.buildContextEntries()
            assertEquals(
                listOf(
                    "e000000b",
                    "e0000004",
                    "e0000005",
                    "e0000006",
                    "e0000007",
                    "e0000008",
                    "e0000009",
                    "e000000a",
                    "e000000c",
                    "e000000d",
                    "e000000e",
                    "e000000f",
                    "e0000010",
                ),
                contextEntries.map { it.id },
            )
            val context = session.buildSessionContext()
            assertEquals("anthropic", context.provider)
            assertEquals("claude-sonnet-4-5", context.modelId)
            assertEquals("high", context.thinkingLevel)
            assertEquals(6, context.messages.size)
            val systemSnapshot = assertIs<SystemMessage>(context.messages[0])
            assertEquals("sys", systemSnapshot.sections?.get("preamble"))
            assertEquals(
                listOf(
                    COMPACTION_SUMMARY_PREFIX + "compacted" + COMPACTION_SUMMARY_SUFFIX,
                    "hi",
                    "masked",
                    BRANCH_SUMMARY_PREFIX + "branch recap" + BRANCH_SUMMARY_SUFFIX,
                    "after",
                ),
                context.messages.drop(1).map(::textOf),
            )

            val projection = session.buildSessionProjection()
            assertEquals(12, projection.size)
            assertTrue(projection.none { it.id == "e0000008" }, "context_edit with null replacement must omit the target")
            val projectedCustomMessage = projection.filterIsInstance<CustomMessageEntry>().single { it.id == "e0000006" }
            assertEquals("masked", (projectedCustomMessage.content as BlockContent).blocks.filterIsInstance<TextContent>().single().text)

            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)

            val branched = session.createBranchedSession("e0000010")
            val branchedFile = assertNotNull(branched.sessionFile)
            assertTrue(fs.exists(branchedFile), "branched session file was not written")
            assertEquals(file.toString(), branched.getHeader().parentSession)
            val branchedIds = branched.getEntries().map { it.id }
            assertEquals(entries.map { it.id }.filter { it != "e000000d" && it != "e000000f" }, branchedIds)
            val reopened = SessionManager.open(branchedFile, fs)
            try {
                assertEquals(branchedIds, reopened.getEntries().map { it.id })
                assertEquals(branched.sessionId, reopened.sessionId)
            } finally {
                reopened.close()
            }
        } finally {
            session.close()
        }
    }

    @Test
    fun v3ContextEditsFixtureAppliesReplacementsAndOmissions() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v3-context-edits.jsonl")
        try {
            val entries = session.getEntries()
            assertEquals(10, entries.size)
            assertEquals("f100000a", session.getLeafId())

            val projection = session.buildSessionProjection()
            assertEquals(9, projection.size)
            assertTrue(projection.none { it.id == "f1000004" }, "null replacement must omit the tool result")
            val projectedAssistant = assertIs<AssistantMessage>(
                assertIs<SessionMessageEntry>(projection.single { it.id == "f1000003" }).message,
            )
            assertEquals(listOf("assistant edited"), projectedAssistant.content.filterIsInstance<TextContent>().map { it.text })
            val projectedCustom = assertIs<CustomMessageEntry>(projection.single { it.id == "f1000005" })
            assertEquals("custom edited", (projectedCustom.content as PlainTextContent).text)
            val projectedSystem = assertIs<SystemMessage>(
                assertIs<SessionMessageEntry>(projection.single { it.id == "f1000001" }).message,
            )
            assertEquals("sys prompt", textOf(projectedSystem))

            val messages = session.buildSessionContext().messages
            assertEquals(
                listOf("sys prompt", "user one", "assistant edited", "custom edited", "user two"),
                messages.map(::textOf),
            )
            assertTrue(messages.none { it is ToolResultMessage }, "null replacement must drop the tool result message")
            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)
        } finally {
            session.close()
        }
    }

    @Test
    fun v3CompactionChainFixtureKeepsOnlyLatestSummary() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v3-compaction-chain.jsonl")
        try {
            val entries = session.getEntries()
            assertEquals(5, entries.size)
            assertEquals(
                listOf("f2000004", "f2000001", "f2000002", "f2000003", "f2000005"),
                session.buildContextEntries().map { it.id },
            )
            val context = session.buildSessionContext()
            assertEquals(
                listOf(
                    COMPACTION_SUMMARY_PREFIX + "second summary" + COMPACTION_SUMMARY_SUFFIX,
                    "first",
                    "second",
                    "third",
                ),
                context.messages.map(::textOf),
            )
            assertTrue(context.messages.none { textOf(it).contains("first summary") })
            assertStats(SessionStats(2, 2, 0, 0, 0.03), session.getSessionStats())
            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)
        } finally {
            session.close()
        }
    }

    @Test
    fun v3BashAndCustomMessagesFixtureConvertsContext() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v3-bash-custom-messages.jsonl")
        try {
            val entries = session.getEntries()
            assertEquals(7, entries.size)
            val bashEntries = entries.filterIsInstance<SessionMessageEntry>().filter {
                it.rawMessage?.stringValue("role") == "bashExecution"
            }
            assertEquals(4, bashEntries.size)
            assertTrue(bashEntries.all { it.message is UserMessage })

            val messages = session.buildSessionContext().messages
            assertEquals(6, messages.size)
            assertEquals(
                listOf(
                    "run",
                    "Ran `git status`\n```\nclean\n```",
                    "Ran `false`\n(no output)\n\nCommand exited with code 2",
                    "Ran `ls`\n```\npartial\n```\n\n(command cancelled)\n\n[Output truncated. Full output: /tmp/out.log]",
                    "injected custom",
                    "done",
                ),
                messages.map(::textOf),
            )
            assertTrue(messages.none { textOf(it).contains("secret") }, "excluded bashExecution must not reach the context")
            assertStats(SessionStats(0, 0, 0, 0, 0.0), session.getSessionStats())
            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)
        } finally {
            session.close()
        }
    }

    @Test
    fun v1LegacyFixtureIsMigratedAndRewritten() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v1-legacy.jsonl")
        try {
            val entries = session.getEntries()
            assertEquals(3, entries.size)
            assertEquals(3, session.getHeader().version)
            assertEquals("fixture-v1", session.sessionId)
            assertNull(entries[0].parentId)
            assertEquals(entries[0].id, entries[1].parentId)
            assertEquals(entries[1].id, entries[2].parentId)
            val compaction = assertIs<CompactionEntry>(entries[1])
            assertEquals(entries[0].id, compaction.firstKeptEntryId)
            assertTrue(entries.all { it.id.isNotEmpty() })

            val rewritten = rawLines(fs, file)
            assertEquals(4, rewritten.size)
            val header = sessionJson.parseToJsonElement(rewritten[0]) as JsonObject
            assertEquals(3, (header["version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
            for (line in rewritten.drop(1)) {
                val entry = sessionJson.parseToJsonElement(line) as JsonObject
                assertTrue(entry.stringValue("id").orEmpty().isNotEmpty(), "migrated entry has no id: $line")
            }
            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)
        } finally {
            session.close()
        }
    }

    @Test
    fun v2LegacyFixtureRenamesHookMessagesToCustom() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v2-legacy.jsonl")
        try {
            val entries = session.getEntries()
            assertEquals(2, entries.size)
            assertEquals(3, session.getHeader().version)
            val migratedMessage = assertIs<SessionMessageEntry>(entries[0])
            assertEquals("custom", migratedMessage.rawMessage?.stringValue("role"))
            assertEquals("hidden hook", textOf(migratedMessage.message))
            val raw = fs.read(file) { readUtf8() }
            assertTrue(raw.contains("\"role\":\"custom\""), "v2 hookMessage role was not rewritten to custom")
            assertTrue(raw.contains("\"version\":3"))
            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)
        } finally {
            session.close()
        }
    }

    @Test
    fun v3BranchedTreeFixtureResolvesTreesAndBranches() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v3-branched-tree.jsonl")
        try {
            val tree = session.getTree()
            assertEquals(1, tree.size)
            assertEquals("f5000001", tree[0].entry.id)
            assertEquals(listOf("f5000002"), tree[0].children.map { it.entry.id })
            assertEquals(listOf("f5000003", "f5000004"), tree[0].children.single().children.map { it.entry.id })
            assertEquals(
                listOf("f5000001", "f5000002", "f5000004", "f5000005", "f5000006"),
                session.getBranch().map { it.id },
            )
            assertEquals(listOf("f5000001", "f5000002", "f5000003"), session.getBranch("f5000003").map { it.id })

            val branched = session.createBranchedSession("f5000003")
            val branchedFile = assertNotNull(branched.sessionFile)
            assertTrue(fs.exists(branchedFile))
            assertEquals(file.toString(), branched.getHeader().parentSession)
            val branchedEntries = branched.getEntries()
            assertEquals(listOf("message", "message", "message", "label"), branchedEntries.map { it.type })
            val recreatedLabel = assertIs<LabelEntry>(branchedEntries.last())
            assertEquals("f5000001", recreatedLabel.targetId)
            assertEquals("root-label", recreatedLabel.label)
            assertEquals("f5000003", recreatedLabel.parentId)
            assertEquals(
                listOf("f5000001", "f5000002", "f5000003"),
                branched.getBranch().map { it.id }.take(3),
            )
            assertIs<LabelEntry>(branched.getBranch().last())
            val reopened = SessionManager.open(branchedFile, fs)
            try {
                assertEquals(branchedEntries.map { it.id }, reopened.getEntries().map { it.id })
            } finally {
                reopened.close()
            }

            val defaultLeafBranch = session.createBranchedSession()
            val defaultEntries = defaultLeafBranch.getEntries()
            assertEquals(
                listOf("message", "message", "message", "session_info", "label"),
                defaultEntries.map { it.type },
            )
            assertEquals("f5000006", defaultEntries[3].id)
            assertEquals(
                listOf("f5000001", "f5000002", "f5000004", "f5000006"),
                defaultLeafBranch.getBranch().map { it.id }.dropLast(1),
            )
            assertIs<LabelEntry>(defaultLeafBranch.getBranch().last())
        } finally {
            session.close()
        }
    }

    @Test
    fun v3UsageStatsFixtureAggregatesEveryUsageSource() = runTest {
        val fs = FakeFileSystem()
        val (session, file) = openFixture(fs, "v3-usage-stats.jsonl")
        try {
            val entries = session.getEntries()
            assertEquals(5, entries.size)
            assertNull(assertIs<UsageEntry>(entries[2]).kind)
            assertNull(assertIs<UsageEntry>(entries[2]).note)
            assertStats(SessionStats(20, 14, 2, 1, 1.27), session.getSessionStats())
            val reopened = SessionManager.open(file, fs)
            try {
                assertEquals(session.getSessionStats(), reopened.getSessionStats())
            } finally {
                reopened.close()
            }
            assertRoundTripStable(entries)
            assertReencodedCopyMatches(session, file)
        } finally {
            session.close()
        }
    }
}
