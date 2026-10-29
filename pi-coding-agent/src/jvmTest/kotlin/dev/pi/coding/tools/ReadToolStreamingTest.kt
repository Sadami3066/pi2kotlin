package dev.pi.coding.tools

import dev.pi.agent.AgentToolResult
import dev.pi.ai.ImageContent
import dev.pi.ai.TextContent
import java.nio.file.Files
import kotlin.io.encoding.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReadToolStreamingTest {
    private val cwd: Path = "/work/project".toPath()

    private fun textArgs(path: String, offset: Int? = null, limit: Int? = null): JsonObject = buildJsonObject {
        put("path", path)
        offset?.let { put("offset", it) }
        limit?.let { put("limit", it) }
    }

    private fun AgentToolResult.text(): String = (content.single() as TextContent).text

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

    private class Fixture(val content: String, val offset: Int? = null, val limit: Int? = null)

    private fun legacyReadText(content: String, pathInput: String, offset: Int?, limit: Int?): String {
        val textContent = content
        val allLines = textContent.split("\n")
        val totalFileLines = allLines.size
        val startLine = if (offset != null) maxOf(0, offset - 1) else 0
        val startLineDisplay = startLine + 1
        if (startLine >= allLines.size) {
            throw IllegalArgumentException("Offset $offset is beyond end of file (${allLines.size} lines total)")
        }
        val selectedContent: String
        var userLimitedLines: Int? = null
        if (limit != null) {
            val endLine = minOf(startLine + limit, allLines.size)
            selectedContent = allLines.subList(startLine, endLine).joinToString("\n")
            userLimitedLines = endLine - startLine
        } else {
            selectedContent = allLines.subList(startLine, allLines.size).joinToString("\n")
        }
        val truncation = truncateHead(selectedContent)
        return when {
            truncation.truncated && truncation.text.isEmpty() -> {
                val firstLineSize = formatSize(allLines[startLine].encodeToByteArray().size)
                "[Line $startLineDisplay is $firstLineSize, exceeds ${formatSize(DEFAULT_MAX_BYTES)} limit. Use bash: sed -n '${startLineDisplay}p' $pathInput | head -c $DEFAULT_MAX_BYTES]"
            }

            truncation.truncated -> {
                val outputLines = if (truncation.text.isEmpty()) 0 else truncation.text.count { it == '\n' } + 1
                val endLineDisplay = startLineDisplay + outputLines - 1
                val nextOffset = endLineDisplay + 1
                if (truncation.truncatedBy == "lines") {
                    truncation.text +
                        "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines. Use offset=$nextOffset to continue.]"
                } else {
                    truncation.text +
                        "\n\n[Showing lines $startLineDisplay-$endLineDisplay of $totalFileLines (${formatSize(DEFAULT_MAX_BYTES)} limit). Use offset=$nextOffset to continue.]"
                }
            }

            userLimitedLines != null && startLine + userLimitedLines < allLines.size -> {
                val remaining = allLines.size - (startLine + userLimitedLines)
                val nextOffset = startLine + userLimitedLines + 1
                truncation.text + "\n\n[$remaining more lines in file. Use offset=$nextOffset to continue.]"
            }

            else -> truncation.text
        }
    }

    private fun line(text: String, index: Int): String = "line-${index.toString().padStart(6, '0')}-$text"

    private fun repeatedLines(count: Int, text: String): String {
        val builder = StringBuilder(count * 58)
        for (index in 1..count) {
            if (index > 1) builder.append('\n')
            builder.append(line(text, index))
        }
        return builder.toString()
    }

    @Test
    fun streamingOutputMatchesLegacyOutput() = runTest {
        val fs = FakeFileSystem()
        val fixtures = listOf(
            Fixture("line1\nline2\nline3\n"),
            Fixture("line1\nline2\nline3"),
            Fixture(""),
            Fixture("\n"),
            Fixture("\n\n"),
            Fixture("a\n\nb\n"),
            Fixture("a\r\nb\r\n"),
            Fixture("hello\nworld\n"),
            Fixture("中文\n测试\n"),
            Fixture("\n" + "b".repeat(60000) + "\n"),
            Fixture("\n\n" + "b".repeat(60000)),
            Fixture("a".repeat(51200)),
            Fixture("a".repeat(51201)),
            Fixture((1..2000).joinToString("\n") { "z" }),
            Fixture((1..2001).joinToString("\n") { "z" }),
            Fixture("a\n" + "b".repeat(60000), offset = 2),
            Fixture("a".repeat(60000)),
            Fixture("a".repeat(50000)),
            Fixture("a".repeat(50001)),
            Fixture("short\n" + "b".repeat(60000) + "\n"),
            Fixture((1..2100).joinToString("\n") { "x" }),
            Fixture((1..2100).joinToString("\n") { "x" } + "\n"),
            Fixture((1..2000).joinToString("\n") { "x".repeat(30) }),
            Fixture((1..4000).joinToString("\n") { "line-$it" }),
            Fixture("line1\nline2\nline3\n", offset = 1),
            Fixture("line1\nline2\nline3\n", offset = 2),
            Fixture("line1\nline2\nline3\n", offset = 2, limit = 1),
            Fixture("line1\nline2\nline3\n", offset = 4),
            Fixture("line1\nline2\nline3\n", offset = 4, limit = 2),
            Fixture("line1\nline2\nline3\n", offset = 5),
            Fixture("line1\nline2\nline3\n", offset = 1, limit = 10),
            Fixture("line1\nline2\nline3", offset = 5),
            Fixture("a\n\nb\n", offset = 2),
            Fixture("a\n\nb\n", offset = 2, limit = 1),
            Fixture("a\n\nb\n", offset = 3),
            Fixture("", offset = 1),
            Fixture("", offset = 2),
            Fixture("\n\n", offset = 2),
            Fixture("\n\n", offset = 3),
            Fixture("\n\n", offset = 1, limit = 1),
        )
        val tool = readTool(cwd, fs)
        for (fixture in fixtures) {
            fs.putText("/work/project/fixture.txt", fixture.content)
            val expected = runCatching { legacyReadText(fixture.content, "fixture.txt", fixture.offset, fixture.limit) }
            val actual = runCatching {
                tool.execute("call-1", textArgs("fixture.txt", fixture.offset, fixture.limit), null, null).text()
            }
            assertEquals(
                expected.exceptionOrNull()?.message,
                actual.exceptionOrNull()?.message,
                "exception for ${fixture.content.length} byte fixture offset=${fixture.offset} limit=${fixture.limit}",
            )
            assertEquals(
                expected.getOrNull(),
                actual.getOrNull(),
                "output for ${fixture.content.length} byte fixture offset=${fixture.offset} limit=${fixture.limit}",
            )
        }
    }

    @Test
    fun streamsLargeFileByOffsetAndLimit() = runTest {
        val dir = Files.createTempDirectory("read-tool-streaming").toString().toPath()
        try {
            val totalLines = 400_000
            val content = repeatedLines(totalLines, "x".repeat(45)) + "\n"
            val file = dir / "huge.log"
            FileSystem.SYSTEM.write(file) { writeUtf8(content) }
            val expectedTotal = totalLines + 1
            val tool = readTool(dir, FileSystem.SYSTEM)

            val defaultOutput = tool.execute("call-1", textArgs("huge.log"), null, null).text()
            assertTrue(defaultOutput.endsWith("of $expectedTotal (50.0KB limit). Use offset=883 to continue.]"))
            assertTrue(defaultOutput.startsWith(line("x".repeat(45), 1) + "\n"))
            assertTrue(defaultOutput.length <= DEFAULT_MAX_BYTES + 512)

            val window = tool.execute("call-1", textArgs("huge.log", offset = 200_000, limit = 3), null, null).text()
            val expectedWindow = line("x".repeat(45), 200_000) + "\n" +
                line("x".repeat(45), 200_001) + "\n" +
                line("x".repeat(45), 200_002) +
                "\n\n[199999 more lines in file. Use offset=200003 to continue.]"
            assertEquals(expectedWindow, window)
            assertTrue(window.length <= 4096)

            val nearEnd = tool.execute("call-1", textArgs("huge.log", offset = totalLines, limit = 5), null, null).text()
            assertEquals(line("x".repeat(45), totalLines) + "\n", nearEnd)

            val lastEmptyLine = tool.execute("call-1", textArgs("huge.log", offset = expectedTotal), null, null).text()
            assertEquals("", lastEmptyLine)

            val error = assertFailsWith<IllegalArgumentException> {
                tool.execute("call-1", textArgs("huge.log", offset = expectedTotal + 1), null, null)
            }
            assertEquals("Offset ${expectedTotal + 1} is beyond end of file ($expectedTotal lines total)", error.message)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false)
        }
    }

    private class CountingFileSystem(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        var bytesRead: Long = 0L
            private set

        override fun source(file: Path): Source = object : ForwardingSource(delegate.source(file)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0L) bytesRead += read
                return read
            }
        }
    }

    private fun pngBytes(): ByteArray {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        return signature + byteArrayOf(0, 0, 0, 13) + "IHDR".encodeToByteArray()
    }

    @Test
    fun oversizedImageIsRejectedAfterSniffOnly() = runTest {
        val fake = FakeFileSystem()
        val bytes = ByteArray(9 * 1024 * 1024)
        pngBytes().copyInto(bytes)
        fake.putBytes("/work/project/huge.png", bytes)
        val counting = CountingFileSystem(fake)
        val result = readTool(cwd, counting).execute("call-1", textArgs("huge.png"), null, null)
        val text = result.text()
        assertTrue(text.startsWith("Read image file [image/png]\n[Image omitted: file is 9.0MB"))
        assertTrue(text.contains("exceeds 8.0MB inline limit.]"))
        assertTrue(counting.bytesRead <= IMAGE_SNIFF_LIMIT)
    }

    @Test
    fun smallImageIsReadCompletely() = runTest {
        val fake = FakeFileSystem()
        val bytes = pngBytes() + ByteArray(300_000)
        fake.putBytes("/work/project/shot.png", bytes)
        val counting = CountingFileSystem(fake)
        val result = readTool(cwd, counting).execute("call-1", textArgs("shot.png"), null, null)
        assertEquals(2, result.content.size)
        assertEquals("Read image file [image/png]", (result.content[0] as TextContent).text)
        val image = assertIs<ImageContent>(result.content[1])
        assertEquals("image/png", image.mimeType)
        assertEquals(Base64.Default.encode(bytes), image.data)
    }

    @Test
    fun textReadDoesNotMaterializeWholeFile() = runTest {
        val fake = FakeFileSystem()
        val content = repeatedLines(20_000, "y".repeat(200)) + "\n"
        fake.putText("/work/project/big.txt", content)
        val tool = readTool(cwd, fake)
        val output = tool.execute("call-1", textArgs("big.txt", offset = 1, limit = 2), null, null).text()
        assertTrue(output.startsWith(line("y".repeat(200), 1) + "\n"))
        assertTrue(output.endsWith("[19999 more lines in file. Use offset=3 to continue.]"))
        assertTrue(output.length <= 1024)
    }

    private companion object {
        private const val IMAGE_SNIFF_LIMIT = 16_384L
    }
}
