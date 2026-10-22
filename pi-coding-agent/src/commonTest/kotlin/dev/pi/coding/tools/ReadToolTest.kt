package dev.pi.coding.tools

import dev.pi.agent.AgentToolResult
import dev.pi.ai.ImageContent
import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import kotlin.io.encoding.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadToolTest {
    private val cwd: Path = "/work/project".toPath()

    private fun newFs(): FakeFileSystem = FakeFileSystem()

    private fun FileSystem.putText(path: String, content: String) {
        val target = path.toPath()
        target.parent?.let { createDirectories(it) }
        write(target) { writeUtf8(content) }
    }

    private fun FileSystem.putBytes(path: String, content: ByteArray) {
        val target = path.toPath()
        target.parent?.let { createDirectories(it) }
        write(target) { write(content) }
    }

    private fun textArgs(path: String, offset: Int? = null, limit: Int? = null): JsonObject = buildJsonObject {
        put("path", path)
        offset?.let { put("offset", it) }
        limit?.let { put("limit", it) }
    }

    private fun AgentToolResult.text(): String = (content.single() as TextContent).text

    @Test
    fun readsWholeTextFile() = runTest {
        val fs = newFs()
        fs.putText("/work/project/notes.txt", "line1\nline2\nline3\n")
        val result = readTool(cwd, fs).execute("call-1", textArgs("notes.txt"), null, null)
        assertEquals("line1\nline2\nline3\n", result.text())
        assertNull(result.isError)
    }

    @Test
    fun appliesOffsetAndLimit() = runTest {
        val fs = newFs()
        fs.putText("/work/project/notes.txt", "line1\nline2\nline3")
        val tool = readTool(cwd, fs)

        assertEquals("line2\nline3", tool.execute("call-1", textArgs("notes.txt", offset = 2), null, null).text())
        assertEquals(
            "line2\n\n[1 more lines in file. Use offset=3 to continue.]",
            tool.execute("call-1", textArgs("notes.txt", offset = 2, limit = 1), null, null).text(),
        )
        assertEquals(
            "line1\nline2\nline3",
            tool.execute("call-1", textArgs("notes.txt", offset = 1, limit = 10), null, null).text(),
        )
    }

    @Test
    fun rejectsOffsetBeyondEndOfFile() = runTest {
        val fs = newFs()
        fs.putText("/work/project/notes.txt", "line1\nline2\nline3")
        val error = assertFailsWith<IllegalArgumentException> {
            readTool(cwd, fs).execute("call-1", textArgs("notes.txt", offset = 5), null, null)
        }
        assertEquals("Offset 5 is beyond end of file (3 lines total)", error.message)
    }

    @Test
    fun truncatesByLineLimit() = runTest {
        val fs = newFs()
        fs.putText("/work/project/lines.txt", (1..2100).joinToString("\n") { "x" })
        val result = readTool(cwd, fs).execute("call-1", textArgs("lines.txt"), null, null)
        val text = result.text()
        assertTrue(text.endsWith("\n\n[Showing lines 1-2000 of 2100. Use offset=2001 to continue.]"))
        assertEquals(2000, text.substringBefore("\n\n[").lines().size)
    }

    @Test
    fun truncatesByByteLimit() = runTest {
        val fs = newFs()
        val line = "abcdefghijklmnopqrstuvwxyz1234"
        fs.putText("/work/project/lines.txt", (1..2000).joinToString("\n") { line })
        val text = readTool(cwd, fs).execute("call-1", textArgs("lines.txt"), null, null).text()
        assertTrue(text.endsWith("[Showing lines 1-1651 of 2000 (50.0KB limit). Use offset=1652 to continue.]"))
    }

    @Test
    fun reportsFirstLineExceedingByteLimit() = runTest {
        val fs = newFs()
        fs.putText("/work/project/big.txt", "a".repeat(60000))
        val text = readTool(cwd, fs).execute("call-1", textArgs("big.txt"), null, null).text()
        assertEquals(
            "[Line 1 is 58.6KB, exceeds 50.0KB limit. Use bash: sed -n '1p' big.txt | head -c 51200]",
            text,
        )
    }

    @Test
    fun failsForMissingFile() = runTest {
        val fs = newFs()
        val error = assertFailsWith<IllegalArgumentException> {
            readTool(cwd, fs).execute("call-1", textArgs("missing.txt"), null, null)
        }
        assertEquals("ENOENT: no such file or directory, access '/work/project/missing.txt'", error.message)
    }

    @Test
    fun failsForDirectory() = runTest {
        val fs = newFs()
        fs.createDirectories("/work/project/folder".toPath())
        val error = assertFailsWith<IllegalArgumentException> {
            readTool(cwd, fs).execute("call-1", textArgs("folder"), null, null)
        }
        assertEquals("EISDIR: illegal operation on a directory, read", error.message)
    }

    @Test
    fun returnsImageContentForPng() = runTest {
        val fs = newFs()
        val png = pngBytes()
        fs.putBytes("/work/project/shot.png", png)
        val result = readTool(cwd, fs).execute("call-1", textArgs("shot.png"), null, null)
        assertEquals(2, result.content.size)
        assertEquals("Read image file [image/png]", (result.content[0] as TextContent).text)
        val image = assertIs<ImageContent>(result.content[1])
        assertEquals("image/png", image.mimeType)
        assertEquals(Base64.Default.encode(png), image.data)
    }

    @Test
    fun reportsUnsupportedImageWithoutInlineContent() = runTest {
        val fs = newFs()
        fs.putBytes("/work/project/shot.bmp", bmpBytes())
        val text = readTool(cwd, fs).execute("call-1", textArgs("shot.bmp"), null, null).text()
        assertEquals(
            "Read image file [image/bmp]\n[Image omitted: could not be converted to a supported inline image format.]",
            text,
        )
    }

    @Test
    fun throwsWhenAborted() = runTest {
        val fs = newFs()
        fs.putText("/work/project/notes.txt", "hello")
        val signal = RunSignal().apply { abort() }
        assertFailsWith<RequestAbortedException> {
            readTool(cwd, fs).execute("call-1", textArgs("notes.txt"), signal, null)
        }
    }

    private fun pngBytes(): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        return signature + byteArrayOf(0, 0, 0, 13) + "IHDR".encodeToByteArray()
    }

    private fun bmpBytes(): ByteArray {
        val bytes = ByteArray(70)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        writeUint32Le(bytes, 2, 70)
        writeUint32Le(bytes, 10, 54)
        writeUint32Le(bytes, 14, 40)
        writeUint16Le(bytes, 26, 1)
        writeUint16Le(bytes, 28, 24)
        return bytes
    }

    private fun writeUint32Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeUint16Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
