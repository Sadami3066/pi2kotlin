package dev.pi.coding.tools

import dev.pi.ai.TextContent
import dev.pi.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindToolTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun basenameGlobMatchesRecursively() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.kt", "a")
            fs.writeText(root, "b.txt", "b")
            fs.writeText(root, "sub/c.kt", "c")
            val result = runTool(findTool(root, fs), args("pattern" to "*.kt"))
            assertEquals("a.kt\nsub/c.kt", result.text())
        }
    }

    @Test
    fun fullPathGlobMatchesRootAndNested() = runTest {
        withTempDir { root ->
            fs.writeText(root, "readme.md", "a")
            fs.writeText(root, "docs/guide.md", "b")
            fs.writeText(root, "docs/guide.txt", "c")
            val result = runTool(findTool(root, fs), args("pattern" to "**/*.md"))
            assertEquals("docs/guide.md\nreadme.md", result.text())
        }
    }

    @Test
    fun pathPatternIsPrefixedWithDoubleStar() = runTest {
        withTempDir { root ->
            fs.writeText(root, "src/a.kt", "a")
            fs.writeText(root, "nested/src/b.kt", "b")
            fs.writeText(root, "src/c.txt", "c")
            val result = runTool(findTool(root, fs), args("pattern" to "src/*.kt"))
            assertEquals("nested/src/b.kt\nsrc/a.kt", result.text())
        }
    }

    @Test
    fun questionMarkAndCharacterClass() = runTest {
        withTempDir { root ->
            fs.writeText(root, "file1.txt", "a")
            fs.writeText(root, "file2.txt", "b")
            fs.writeText(root, "file3.log", "c")
            val question = runTool(findTool(root, fs), args("pattern" to "file?.txt"))
            assertEquals("file1.txt\nfile2.txt", question.text())
            val characterClass = runTool(findTool(root, fs), args("pattern" to "file[12].*"))
            assertEquals("file1.txt\nfile2.txt", characterClass.text())
        }
    }

    @Test
    fun limitReachedNotice() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.kt", "a")
            fs.writeText(root, "b.kt", "b")
            fs.writeText(root, "c.kt", "c")
            val result = runTool(findTool(root, fs), args("pattern" to "*.kt", "limit" to 2))
            assertEquals(
                "a.kt\nb.kt\n\n[2 results limit reached. Use limit=4 for more, or refine pattern]",
                result.text(),
            )
        }
    }

    @Test
    fun noResultsText() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "a")
            val result = runTool(findTool(root, fs), args("pattern" to "*.kt"))
            assertEquals("No files found matching pattern", result.text())
        }
    }

    @Test
    fun skipsGitAndNodeModules() = runTest {
        withTempDir { root ->
            fs.writeText(root, ".git/a.kt", "a")
            fs.writeText(root, "node_modules/b.kt", "b")
            fs.writeText(root, "src/c.kt", "c")
            val result = runTool(findTool(root, fs), args("pattern" to "*.kt"))
            assertEquals("src/c.kt", result.text())
        }
    }

    @Test
    fun resultsAreSortedAlphabetically() = runTest {
        withTempDir { root ->
            fs.writeText(root, "c.kt", "c")
            fs.writeText(root, "a.kt", "a")
            fs.writeText(root, "b.kt", "b")
            val result = runTool(findTool(root, fs), args("pattern" to "*.kt"))
            assertEquals("a.kt\nb.kt\nc.kt", result.text())
        }
    }

    @Test
    fun singleFileSearch() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "a")
            val path = (root / "a.txt").toString()
            val matching = runTool(findTool(root, fs), args("pattern" to "*.txt", "path" to path))
            assertEquals("a.txt", matching.text())
            val notMatching = runTool(findTool(root, fs), args("pattern" to "*.kt", "path" to path))
            assertEquals("No files found matching pattern", notMatching.text())
        }
    }

    @Test
    fun missingPathThrows() = runTest {
        withTempDir { root ->
            val failure = runCatching {
                runTool(findTool(root, fs), args("pattern" to "*.kt", "path" to "missing"))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
}

private suspend fun withTempDir(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-find-test-${Random.nextLong().toString().replace("-", "")}"
    FileSystem.SYSTEM.createDirectories(root)
    try {
        block(root)
    } finally {
        FileSystem.SYSTEM.deleteRecursively(root)
    }
}

private fun FileSystem.writeText(root: Path, relative: String, text: String) {
    val path = root / relative
    path.parent?.let { createDirectories(it) }
    write(path) { writeUtf8(text) }
}

private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is Double -> put(key, value)
            else -> error("unsupported test argument type: ${value::class}")
        }
    }
}

private suspend fun runTool(tool: CodingTool, args: JsonObject): AgentToolResult =
    tool.execute("test-call", args, null, null)

private fun AgentToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }
