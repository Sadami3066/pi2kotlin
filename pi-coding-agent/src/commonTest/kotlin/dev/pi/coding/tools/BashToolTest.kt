package dev.pi.coding.tools

import dev.pi.ai.RequestAbortedException
import dev.pi.ai.RunSignal
import dev.pi.ai.TextContent
import dev.pi.agent.AgentToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BashToolTest {
    private val cwd = "/tmp".toPath()

    @Test
    fun missingExecutorReturnsExplicitError() = runTest {
        val result = runTool(bashTool(cwd, null), args("command" to "ls"))
        assertEquals(true, result.isError)
        assertTrue(result.text().contains("no shell executor is configured"))
    }

    @Test
    fun successfulCommandReturnsOutput() = runTest {
        val executor = FakeShellExecutor(ShellResult("hello\n", 0))
        val result = runTool(bashTool(cwd, executor), args("command" to "echo hello"))
        assertEquals("hello\n", result.text())
        assertEquals("echo hello", executor.lastCommand)
        assertTrue(result.isError != true)
    }

    @Test
    fun nonZeroExitAppendsStatus() = runTest {
        val executor = FakeShellExecutor(ShellResult("boom", 2))
        val result = runTool(bashTool(cwd, executor), args("command" to "false"))
        assertEquals("boom\n\nCommand exited with code 2", result.text())
        assertEquals(true, result.isError)
    }

    @Test
    fun emptyOutputBecomesNoOutput() = runTest {
        val executor = FakeShellExecutor(ShellResult("", 0))
        val result = runTool(bashTool(cwd, executor), args("command" to "true"))
        assertEquals("(no output)", result.text())
        assertTrue(result.isError != true)
    }

    @Test
    fun missingExitCodeIsError() = runTest {
        val executor = FakeShellExecutor(ShellResult("", null))
        val result = runTool(bashTool(cwd, executor), args("command" to "kill"))
        assertEquals("(no output)\n\nCommand terminated without an exit code", result.text())
        assertEquals(true, result.isError)
    }

    @Test
    fun timeoutIsForwarded() = runTest {
        val executor = FakeShellExecutor(ShellResult("ok", 0))
        runTool(bashTool(cwd, executor), args("command" to "sleep 1", "timeout" to 5))
        assertEquals(5L, executor.lastTimeout)
    }

    @Test
    fun invalidTimeoutThrows() = runTest {
        val executor = FakeShellExecutor(ShellResult("ok", 0))
        val failure = runCatching {
            runTool(bashTool(cwd, executor), args("command" to "true", "timeout" to 0))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Invalid timeout: must be a finite number of seconds", failure.message)
    }

    @Test
    fun excessiveTimeoutThrows() = runTest {
        val executor = FakeShellExecutor(ShellResult("ok", 0))
        val failure = runCatching {
            runTool(bashTool(cwd, executor), args("command" to "true", "timeout" to 5000000))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Invalid timeout: maximum is 2147483.647 seconds", failure.message)
    }

    @Test
    fun longOutputIsTailTruncated() = runTest {
        val output = (1..2500).joinToString("\n") { "line$it" } + "\n"
        val executor = FakeShellExecutor(ShellResult(output, 0))
        val result = runTool(bashTool(cwd, executor), args("command" to "seq"))
        val text = result.text()
        assertTrue(text.startsWith("line501\n"))
        assertTrue(text.endsWith("[Showing lines 501-2500 of 2500]"))
        assertTrue(!text.contains("line500\n"))
    }

    @Test
    fun executorTruncationIsReported() = runTest {
        val executor = FakeShellExecutor(ShellResult("one", 0, truncated = true))
        val result = runTool(bashTool(cwd, executor), args("command" to "cat big"))
        assertEquals("one\n\n[Output truncated]", result.text())
    }

    @Test
    fun executorFailureBecomesErrorResult() = runTest {
        val executor = object : ShellExecutor {
            override suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult =
                throw IllegalStateException("shell crashed")
        }
        val result = runTool(bashTool(cwd, executor), args("command" to "true"))
        assertEquals("shell crashed", result.text())
        assertEquals(true, result.isError)
    }

    @Test
    fun abortedSignalStopsExecution() = runTest {
        val executor = FakeShellExecutor(ShellResult("ok", 0))
        val signal = RunSignal().apply { abort() }
        val failure = runCatching {
            bashTool(cwd, executor).execute("test-call", args("command" to "true"), signal, null)
        }.exceptionOrNull()
        assertTrue(failure is RequestAbortedException)
    }
}

private class FakeShellExecutor(private val result: ShellResult) : ShellExecutor {
    var lastCommand: String? = null
    var lastTimeout: Long? = null

    override suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult {
        lastCommand = command
        lastTimeout = timeoutSeconds
        return result
    }
}

private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) {
        when (value) {
            is String -> put(key, value)
            is Int -> put(key, value)
            is Boolean -> put(key, value)
            is Long -> put(key, value)
            is Double -> put(key, value)
            else -> error("unsupported test argument type: ${value::class}")
        }
    }
}

private suspend fun runTool(tool: CodingTool, args: JsonObject): AgentToolResult =
    tool.execute("test-call", args, null, null)

private fun AgentToolResult.text(): String = content.filterIsInstance<TextContent>().joinToString("") { it.text }
