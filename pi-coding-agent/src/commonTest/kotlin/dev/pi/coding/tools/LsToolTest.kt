package dev.pi.coding.tools

import dev.pi.ai.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LsToolTest {
    private val cwd: Path = "/work/project".toPath()

    private fun setupFs(): FakeFileSystem {
        val fs = FakeFileSystem()
        fs.createDirectories(cwd)
        fs.write("/work/project/b.txt".toPath()) { writeUtf8("b") }
        fs.write("/work/project/A.txt".toPath()) { writeUtf8("a") }
        fs.write("/work/project/.hidden".toPath()) { writeUtf8("hidden") }
        fs.createDirectories("/work/project/Zdir".toPath())
        return fs
    }

    private fun text(result: dev.pi.agent.AgentToolResult): String =
        (result.content.single() as TextContent).text

    @Test
    fun listsEntriesSortedCaseInsensitivelyWithDirectorySuffix() = runTest {
        val fs = setupFs()
        val text = text(lsTool(cwd, fs).execute("call-1", JsonObject(emptyMap()), null, null))
        assertEquals(".hidden\nA.txt\nb.txt\nZdir/", text)
    }

    @Test
    fun listsRequestedDirectory() = runTest {
        val fs = setupFs()
        val text = text(
            lsTool(cwd, fs).execute("call-1", buildJsonObject { put("path", "Zdir") }, null, null),
        )
        assertEquals("(empty directory)", text)
    }

    @Test
    fun reportsEmptyDirectory() = runTest {
        val fs = setupFs()
        fs.createDirectories("/work/project/empty".toPath())
        val text = text(
            lsTool(cwd, fs).execute("call-1", buildJsonObject { put("path", "empty") }, null, null),
        )
        assertEquals("(empty directory)", text)
    }

    @Test
    fun rejectsMissingPath() = runTest {
        val fs = setupFs()
        val error = assertFailsWith<IllegalArgumentException> {
            lsTool(cwd, fs).execute("call-1", buildJsonObject { put("path", "missing") }, null, null)
        }
        assertEquals("Path not found: /work/project/missing", error.message)
    }

    @Test
    fun rejectsFilePath() = runTest {
        val fs = setupFs()
        val error = assertFailsWith<IllegalArgumentException> {
            lsTool(cwd, fs).execute("call-1", buildJsonObject { put("path", "b.txt") }, null, null)
        }
        assertEquals("Not a directory: /work/project/b.txt", error.message)
    }

    @Test
    fun honorsLimitWithNotice() = runTest {
        val fs = setupFs()
        val text = text(
            lsTool(cwd, fs).execute("call-1", buildJsonObject { put("limit", 2) }, null, null),
        )
        assertEquals(".hidden\nA.txt\n\n[2 entries limit reached. Use limit=4 for more]", text)
    }

    @Test
    fun truncatesByByteLimit() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories(cwd)
        for (index in 0 until 500) {
            val name = "a".repeat(190) + index.toString().padStart(10, '0')
            fs.write((cwd / name)) { writeUtf8("x") }
        }
        val text = text(lsTool(cwd, fs).execute("call-1", JsonObject(emptyMap()), null, null))
        assertTrue(text.endsWith("[50.0KB limit reached]"), text.takeLast(200))
    }
}
