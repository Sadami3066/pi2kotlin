package dev.pi.coding.tools

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import dev.pi.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GrepFindIgnoreTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun grepSkipsGitignoredDirectory() = runTest {
        withFixtureDir { root ->
            fs.writeFixture(root, ".gitignore", "custom-ignored/\n")
            fs.writeFixture(root, "custom-ignored/secret.txt", "needle\n")
            fs.writeFixture(root, "src/ok.txt", "needle\n")
            val result = runFixtureTool(grepTool(root, fs), fixtureArgs("pattern" to "needle"))
            assertEquals("src/ok.txt:1: needle", result.text())
        }
    }

    @Test
    fun grepSearchesDirectoryWithoutIgnoreEntry() = runTest {
        withFixtureDir { root ->
            fs.writeFixture(root, "build/out.txt", "needle\n")
            val result = runFixtureTool(grepTool(root, fs), fixtureArgs("pattern" to "needle"))
            assertEquals("build/out.txt:1: needle", result.text())
        }
    }

    @Test
    fun grepHonorsNegation() = runTest {
        withFixtureDir { root ->
            fs.writeFixture(root, ".gitignore", "*.log\n!keep.log\n")
            fs.writeFixture(root, "a.log", "needle\n")
            fs.writeFixture(root, "keep.log", "needle\n")
            val result = runFixtureTool(grepTool(root, fs), fixtureArgs("pattern" to "needle"))
            assertEquals("keep.log:1: needle", result.text())
        }
    }

    @Test
    fun findSkipsGitignoredDirectoryAndHonorsNegation() = runTest {
        withFixtureDir { root ->
            fs.writeFixture(root, ".gitignore", "*.log\n!keep.log\n")
            fs.writeFixture(root, "a.log", "a")
            fs.writeFixture(root, "keep.log", "k")
            fs.writeFixture(root, "src/main.kt", "m")
            val logs = runFixtureTool(findTool(root, fs), fixtureArgs("pattern" to "*.log"))
            assertEquals("keep.log", logs.text())
            val kotlin = runFixtureTool(findTool(root, fs), fixtureArgs("pattern" to "*.kt"))
            assertEquals("src/main.kt", kotlin.text())
        }
    }

    @Test
    fun findSearchesDirectoryWithoutIgnoreEntry() = runTest {
        withFixtureDir { root ->
            fs.writeFixture(root, "build/a.kt", "a")
            val result = runFixtureTool(findTool(root, fs), fixtureArgs("pattern" to "*.kt"))
            assertEquals("build/a.kt", result.text())
        }
    }

    @Test
    fun gitDirectoryIsAlwaysIgnored() = runTest {
        withFixtureDir { root ->
            fs.writeFixture(root, ".git/config.kt", "needle\n")
            val grep = runFixtureTool(grepTool(root, fs), fixtureArgs("pattern" to "needle"))
            assertEquals("No matches found", grep.text())
            val find = runFixtureTool(findTool(root, fs), fixtureArgs("pattern" to "*.kt"))
            assertEquals("No files found matching pattern", find.text())
        }
    }

    @Test
    fun abortStopsTraversalEarly() = runTest {
        withFixtureDir { root ->
            for (index in 1..60) {
                fs.writeFixture(root, "d$index/f.txt", "x\n")
            }
            val signal = RunSignal()
            val counted = CountingFileSystem(fs) { calls ->
                if (calls == 5) signal.abort()
            }
            val failure = runCatching {
                grepTool(root, counted).execute("test-call", fixtureArgs("pattern" to "zzz"), signal, null)
            }.exceptionOrNull()
            assertTrue(failure is RequestAbortedException)
            assertTrue(counted.listCalls <= 10, "list calls: ${counted.listCalls}")
        }
    }

    @Test
    fun findStopsEarlyAtLimit() = runTest {
        withFixtureDir { root ->
            for (index in 1..120) {
                fs.writeFixture(root, "d$index/f.kt", "x")
            }
            val counted = CountingFileSystem(fs)
            val result = runFixtureTool(findTool(root, counted), fixtureArgs("pattern" to "*.kt", "limit" to 3))
            assertTrue(result.text().contains("3 results limit reached"))
            assertTrue(counted.listCalls < 30, "list calls: ${counted.listCalls}")
        }
    }

    @Test
    fun grepStopsEarlyAtLimit() = runTest {
        withFixtureDir { root ->
            for (index in 1..120) {
                fs.writeFixture(root, "d$index/f.txt", "hit\n")
            }
            val counted = CountingFileSystem(fs)
            val result = runFixtureTool(grepTool(root, counted), fixtureArgs("pattern" to "hit", "limit" to 2))
            assertTrue(result.text().contains("2 matches limit reached"))
            assertTrue(counted.listCalls < 10, "list calls: ${counted.listCalls}")
        }
    }
}

private class CountingFileSystem(
    delegate: FileSystem,
    private val onList: (Int) -> Unit = {},
) : ForwardingFileSystem(delegate) {
    var listCalls: Int = 0
        private set

    override fun list(dir: Path): List<Path> {
        listCalls++
        onList(listCalls)
        return super.list(dir)
    }
}

private suspend fun withFixtureDir(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-ignore-test-${Random.nextLong().toString().replace("-", "")}"
    FileSystem.SYSTEM.createDirectories(root)
    try {
        block(root)
    } finally {
        FileSystem.SYSTEM.deleteRecursively(root)
    }
}

private fun FileSystem.writeFixture(root: Path, relative: String, text: String) {
    val path = root / relative
    path.parent?.let { createDirectories(it) }
    write(path) { writeUtf8(text) }
}

private fun fixtureArgs(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is Double -> put(key, value)
            else -> error("unsupported fixture argument type: ${value::class}")
        }
    }
}

private suspend fun runFixtureTool(tool: CodingTool, args: JsonObject): AgentToolResult =
    tool.execute("test-call", args, null, null)

private fun AgentToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }
