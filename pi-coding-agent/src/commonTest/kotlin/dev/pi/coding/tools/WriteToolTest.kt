package dev.pi.coding.tools

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteToolTest {
    private val cwd: Path = "/work/project".toPath()

    private fun writeArgs(path: String, content: String): JsonObject = buildJsonObject {
        put("path", path)
        put("content", content)
    }

    @Test
    fun writesFileAndCreatesParentDirectories() = runTest {
        val fs = FakeFileSystem()
        val tool = writeTool(cwd, fs, FileMutationQueue())
        val result = tool.execute("call-1", writeArgs("src/main.kt", "fun main() {}\n"), null, null)

        assertEquals("Successfully wrote to src/main.kt", (result.content.single() as TextContent).text)
        assertEquals("fun main() {}\n", fs.read("/work/project/src/main.kt".toPath()) { readUtf8() })
    }

    @Test
    fun overwritesExistingFile() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/work/project".toPath())
        fs.write("/work/project/a.txt".toPath()) { writeUtf8("old") }
        writeTool(cwd, fs, FileMutationQueue())
            .execute("call-1", writeArgs("a.txt", "new"), null, null)
        assertEquals("new", fs.read("/work/project/a.txt".toPath()) { readUtf8() })
    }

    @Test
    fun throwsWhenAborted() = runTest {
        val fs = FakeFileSystem()
        val signal = RunSignal().apply { abort() }
        assertFailsWith<RequestAbortedException> {
            writeTool(cwd, fs, FileMutationQueue())
                .execute("call-1", writeArgs("a.txt", "value"), signal, null)
        }
        assertFalse(fs.exists("/work/project/a.txt".toPath()))
    }

    @Test
    fun serializesMutationsForTheSameFile() = runTest {
        val queue = FileMutationQueue()
        val path = "/work/project/shared.txt".toPath()
        val order = mutableListOf<String>()
        coroutineScope {
            launch {
                queue.withFile(path) {
                    order += "first-start"
                    delay(10)
                    order += "first-end"
                }
            }
            launch {
                queue.withFile(path) {
                    order += "second-start"
                    order += "second-end"
                }
            }
        }
        assertEquals(listOf("first-start", "first-end", "second-start", "second-end"), order)
    }

    @Test
    fun allowsParallelMutationsForDifferentFiles() = runTest {
        val queue = FileMutationQueue()
        val first = "/work/project/first.txt".toPath()
        val second = "/work/project/second.txt".toPath()
        val order = mutableListOf<String>()
        coroutineScope {
            launch {
                queue.withFile(first) {
                    order += "first-start"
                    delay(10)
                    order += "first-end"
                }
            }
            launch {
                queue.withFile(second) {
                    order += "second-start"
                    order += "second-end"
                }
            }
        }
        assertEquals(listOf("first-start", "second-start", "second-end", "first-end"), order)
        assertTrue(order.indexOf("second-start") < order.indexOf("first-end"))
    }
}
