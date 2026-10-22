package dev.pi.coding.tools

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditToolTest {
    private val cwd: Path = "/work/project".toPath()

    private fun FileSystem.putText(path: String, content: String) {
        val target = path.toPath()
        target.parent?.let { createDirectories(it) }
        write(target) { writeUtf8(content) }
    }

    private fun editArgs(path: String, edits: List<Pair<String, String>>): JsonObject = buildJsonObject {
        put("path", path)
        putJsonArray("edits") {
            edits.forEach { (oldText, newText) ->
                addJsonObject {
                    put("oldText", oldText)
                    put("newText", newText)
                }
            }
        }
    }

    private fun text(result: dev.pi.agent.AgentToolResult): String =
        (result.content.single() as TextContent).text

    @Test
    fun replacesSingleEditAndReportsDiff() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "hello world\n")
        val result = editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("world" to "there")), null, null)

        assertEquals("hello there\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
        assertEquals("Successfully replaced 1 block(s) in a.txt.", text(result))
        val details = assertIs<JsonObject>(result.details)
        assertEquals("-1 hello world\n+1 hello there", details["diff"]?.jsonPrimitive?.content)
        assertEquals(1, details["firstChangedLine"]?.jsonPrimitive?.int)
        assertTrue(details["patch"]?.jsonPrimitive?.content?.contains("@@ -1 +1 @@") == true)
    }

    @Test
    fun appliesMultipleDisjointEdits() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "a\nb\nc\n")
        val result = editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("b" to "B", "c" to "C")), null, null)

        assertEquals("a\nB\nC\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
        assertEquals("Successfully replaced 2 block(s) in a.txt.", text(result))
        val details = assertIs<JsonObject>(result.details)
        assertEquals(2, details["firstChangedLine"]?.jsonPrimitive?.int)
    }

    @Test
    fun rejectsDuplicateMatches() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "x\nx\n")
        val error = assertFailsWith<IllegalArgumentException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("a.txt", listOf("x" to "y")), null, null)
        }
        assertEquals(
            "Found 2 occurrences of the text in a.txt. The text must be unique. Please provide more context to make it unique.",
            error.message,
        )
    }

    @Test
    fun rejectsMissingMatch() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "abc\n")
        val error = assertFailsWith<IllegalArgumentException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("a.txt", listOf("zzz" to "y")), null, null)
        }
        assertEquals(
            "Could not find the exact text in a.txt. The old text must match exactly including all whitespace and newlines.",
            error.message,
        )
    }

    @Test
    fun rejectsEmptyOldText() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "abc\n")
        val error = assertFailsWith<IllegalArgumentException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("a.txt", listOf("" to "y")), null, null)
        }
        assertEquals("oldText must not be empty in a.txt.", error.message)
    }

    @Test
    fun rejectsNoChange() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "abc\n")
        val error = assertFailsWith<IllegalArgumentException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("a.txt", listOf("abc" to "abc")), null, null)
        }
        assertEquals(
            "No changes made to a.txt. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected.",
            error.message,
        )
    }

    @Test
    fun rejectsOverlappingEdits() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "abcdef\n")
        val error = assertFailsWith<IllegalArgumentException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("a.txt", listOf("abc" to "x", "cde" to "y")), null, null)
        }
        assertEquals(
            "edits[0] and edits[1] overlap in a.txt. Merge them into one edit or target disjoint regions.",
            error.message,
        )
    }

    @Test
    fun preservesCrlfLineEndings() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "a\r\nb\r\n")
        editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("b" to "B")), null, null)
        assertEquals("a\r\nB\r\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }

    @Test
    fun preservesByteOrderMark() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "\uFEFFhello\n")
        editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("hello" to "hi")), null, null)
        assertEquals("\uFEFFhi\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }

    @Test
    fun fallsBackToFuzzyMatching() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "let x = \u201Chi\u201D\n")
        editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("let x = \"hi\"" to "let y = \"hi\"")), null, null)
        assertEquals("let y = \"hi\"\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }

    @Test
    fun reportsFirstChangedLine() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "l1\nl2\nl3\nl4\n")
        val result = editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("l3" to "L3")), null, null)
        val details = assertIs<JsonObject>(result.details)
        assertEquals(3, details["firstChangedLine"]?.jsonPrimitive?.int)
    }

    @Test
    fun failsForMissingFile() = runTest {
        val fs = FakeFileSystem()
        val error = assertFailsWith<IllegalArgumentException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("missing.txt", listOf("a" to "b")), null, null)
        }
        assertEquals("Could not edit file: missing.txt. Error code: ENOENT.", error.message)
    }

    @Test
    fun throwsWhenAborted() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "abc\n")
        val signal = RunSignal().apply { abort() }
        assertFailsWith<RequestAbortedException> {
            editTool(cwd, fs, FileMutationQueue())
                .execute("call-1", editArgs("a.txt", listOf("abc" to "x")), signal, null)
        }
        assertEquals("abc\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }

    @Test
    fun prepareArgumentsAcceptsJsonStringSingleObjectAndLegacyFields() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "abc\n")
        val tool = editTool(cwd, fs, FileMutationQueue())
        val prepare = tool.prepareArguments!!

        val fromString = prepare(
            buildJsonObject {
                put("path", "a.txt")
                put("edits", """[{"oldText":"abc","newText":"xyz"}]""")
            },
        )
        assertEquals(1, (fromString["edits"] as JsonArray).size)

        val fromSingleObject = prepare(
            buildJsonObject {
                put("path", "a.txt")
                put(
                    "edits",
                    buildJsonObject {
                        put("oldText", "abc")
                        put("newText", "xyz")
                    },
                )
            },
        )
        assertEquals(1, (fromSingleObject["edits"] as JsonArray).size)

        val fromLegacy = prepare(
            buildJsonObject {
                put("path", "a.txt")
                put("oldText", "abc")
                put("newText", "xyz")
            },
        )
        assertEquals(1, (fromLegacy["edits"] as JsonArray).size)
        assertNull(fromLegacy["oldText"])
        assertNull(fromLegacy["newText"])

        tool.execute("call-1", prepare(buildJsonObject {
            put("path", "a.txt")
            put("edits", """[{"oldText":"abc","newText":"xyz"}]""")
        }), null, null)
        assertEquals("xyz\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }

    @Test
    fun appliesEditsAgainstOriginalFileNotIncrementally() = runTest {
        val fs = FakeFileSystem()
        fs.putText("/work/project/a.txt", "one\ntwo\n")
        editTool(cwd, fs, FileMutationQueue())
            .execute("call-1", editArgs("a.txt", listOf("one" to "two", "two" to "three")), null, null)
        assertEquals("two\nthree\n", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }
}
