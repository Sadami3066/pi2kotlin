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

class GrepToolTest {
    private val fs = FileSystem.SYSTEM

    @Test
    fun literalMatchesAcrossFiles() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "hello world\nfoo\n")
            fs.writeText(root, "b.txt", "hello there\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "hello", "literal" to true))
            assertEquals("a.txt:1: hello world\nb.txt:1: hello there", result.text())
            assertEquals(null, result.isError)
        }
    }

    @Test
    fun regexMatches() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "foo\nbar\nfoobar\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "fo+bar"))
            assertEquals("a.txt:3: foobar", result.text())
        }
    }

    @Test
    fun ignoreCaseMatches() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "Hello World\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "hello", "ignoreCase" to true))
            assertEquals("a.txt:1: Hello World", result.text())
        }
    }

    @Test
    fun globFiltersFiles() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "needle\n")
            fs.writeText(root, "b.md", "needle\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "needle", "glob" to "*.md"))
            assertEquals("b.md:1: needle", result.text())
        }
    }

    @Test
    fun contextLinesUseUpstreamFormat() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "one\ntwo\nthree\nfour\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "three", "context" to 1))
            assertEquals("a.txt-2- two\na.txt:3: three\na.txt-4- four", result.text())
        }
    }

    @Test
    fun streamingContextMergesOverlappingMatches() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "line1\nhit\nline3\nhit\nline5\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "hit", "context" to 1))
            assertEquals(
                "a.txt-1- line1\na.txt:2: hit\na.txt-3- line3\na.txt:4: hit\na.txt-5- line5",
                result.text(),
            )
        }
    }

    @Test
    fun limitReachedNotice() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "hit\nhit\nhit\nhit\nhit\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "hit", "limit" to 2))
            assertEquals(
                "a.txt:1: hit\na.txt:2: hit\n\n[2 matches limit reached. Use limit=4 for more, or refine pattern]",
                result.text(),
            )
        }
    }

    @Test
    fun noMatchesText() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "nothing here\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "needle"))
            assertEquals("No matches found", result.text())
        }
    }

    @Test
    fun longLinesAreTruncated() = runTest {
        withTempDir { root ->
            fs.writeText(root, "a.txt", "a".repeat(600) + "\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "a+"))
            val text = result.text()
            assertTrue(text.startsWith("a.txt:1: " + "a".repeat(500) + "... [truncated]"))
            assertTrue(text.endsWith("Some lines truncated to 500 chars. Use read tool to see full lines]"))
        }
    }

    @Test
    fun skipsGitDirectory() = runTest {
        withTempDir { root ->
            fs.writeText(root, ".git/config", "needle\n")
            fs.writeText(root, "src/a.txt", "needle\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "needle"))
            assertEquals("src/a.txt:1: needle", result.text())
        }
    }

    @Test
    fun singleFileSearchUsesBasename() = runTest {
        withTempDir { root ->
            fs.writeText(root, "nested/a.txt", "needle\n")
            val path = (root / "nested/a.txt").toString()
            val result = runTool(grepTool(root, fs), args("pattern" to "needle", "path" to path))
            assertEquals("a.txt:1: needle", result.text())
        }
    }

    @Test
    fun binaryFilesAreSkipped() = runTest {
        withTempDir { root ->
            fs.write(root / "bin.dat") { writeUtf8("needle\u0000binary\n") }
            fs.writeText(root, "a.txt", "needle\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "needle"))
            assertEquals("a.txt:1: needle", result.text())
        }
    }

    @Test
    fun relativeSearchPathFormatsAgainstSearchRoot() = runTest {
        withTempDir { root ->
            fs.writeText(root, "sub/a.txt", "needle\n")
            fs.writeText(root, "sub/deep/b.txt", "needle\n")
            val result = runTool(grepTool(root, fs), args("pattern" to "needle", "path" to "sub"))
            assertEquals("a.txt:1: needle\ndeep/b.txt:1: needle", result.text())
        }
    }

    @Test
    fun missingPathThrows() = runTest {
        withTempDir { root ->
            val failure = runCatching {
                runTool(grepTool(root, fs), args("pattern" to "needle", "path" to "missing"))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
}

private suspend fun withTempDir(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-grep-test-${Random.nextLong().toString().replace("-", "")}"
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
