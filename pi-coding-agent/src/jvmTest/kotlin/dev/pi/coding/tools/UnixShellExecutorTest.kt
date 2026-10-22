package dev.pi.coding.tools

import dev.pi.ai.RunSignal
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnixShellExecutorTest {
    @Test
    fun echoesOutputAndExitCode() = runBlocking {
        withTempDir { dir ->
            val result = UnixShellExecutor(dir).run("echo hello", null, null)
            assertEquals(0, result.exitCode)
            assertEquals("hello", result.output.trim())
            assertFalse(result.truncated)
        }
    }

    @Test
    fun mergesStandardErrorIntoOutput() = runBlocking {
        withTempDir { dir ->
            val result = UnixShellExecutor(dir).run("echo out; echo err 1>&2", null, null)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("out"))
            assertTrue(result.output.contains("err"))
        }
    }

    @Test
    fun reportsNonZeroExitCode() = runBlocking {
        withTempDir { dir ->
            val result = UnixShellExecutor(dir).run("exit 3", null, null)
            assertEquals(3, result.exitCode)
        }
    }

    @Test
    fun runsInConfiguredWorkingDirectory() = runBlocking {
        withTempDir { dir ->
            val result = UnixShellExecutor(dir).run("pwd", null, null)
            val expected = File(dir.toString()).canonicalPath
            assertEquals(expected, result.output.trim())
        }
    }

    @Test
    fun injectsAdditionalEnvironment() = runBlocking {
        withTempDir { dir ->
            val executor = UnixShellExecutor(dir, environment = mapOf("PI_MODEL" to "test-model"))
            val result = executor.run("printf '%s' \"\$PI_MODEL\"", null, null)
            assertEquals("test-model", result.output)
        }
    }

    @Test
    fun closesStandardInput() = runBlocking {
        withTempDir { dir ->
            val started = System.currentTimeMillis()
            val result = UnixShellExecutor(dir).run("cat", 5, null)
            assertEquals(0, result.exitCode)
            assertTrue(System.currentTimeMillis() - started < 4000)
        }
    }

    @Test
    fun timesOutAndKillsProcess() = runBlocking {
        withTempDir { dir ->
            val started = System.currentTimeMillis()
            val failure = runCatching {
                UnixShellExecutor(dir).run("sleep 10", 1, null)
            }.exceptionOrNull()
            assertTrue(failure is ShellExecutionException)
            assertTrue(failure.message.orEmpty().contains("Command timed out after 1 seconds"))
            assertTrue(System.currentTimeMillis() - started < 4000)
        }
    }

    @Test
    fun abortsRunningCommand() = runBlocking {
        withTempDir { dir ->
            val executor = UnixShellExecutor(dir)
            val signal = RunSignal()
            val started = System.currentTimeMillis()
            val deferred = async(Dispatchers.IO) {
                runCatching { executor.run("sleep 10", null, signal) }.exceptionOrNull()
            }
            delay(300)
            signal.abort()
            val failure = deferred.await()
            assertTrue(failure is ShellExecutionException)
            assertTrue(failure.message.orEmpty().contains("Command aborted"))
            assertTrue(System.currentTimeMillis() - started < 5000)
        }
    }

    @Test
    fun truncatesLargeOutputTail() = runBlocking {
        withTempDir { dir ->
            val result = UnixShellExecutor(dir).run("seq 1 5000", null, null)
            assertTrue(result.truncated)
            val lines = result.output.split("\n")
            assertEquals(2000, lines.size)
            assertEquals("3001", lines.first())
            assertEquals("5000", lines.last())
        }
    }

    @Test
    fun keepsSmallOutputUntruncated() = runBlocking {
        withTempDir { dir ->
            val result = UnixShellExecutor(dir).run("printf 'ok'", null, null)
            assertEquals("ok", result.output)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun rejectsMissingCustomShell() = runBlocking {
        withTempDir { dir ->
            val executor = UnixShellExecutor(dir, shellPath = "/definitely/not/a/shell")
            val failure = runCatching { executor.run("echo hi", null, null) }.exceptionOrNull()
            assertTrue(failure is ShellExecutionException)
            assertTrue(failure.message.orEmpty().contains("Custom shell path not found: /definitely/not/a/shell"))
        }
    }

    @Test
    fun usesExplicitShellPath() = runBlocking {
        withTempDir { dir ->
            val executor = UnixShellExecutor(dir, shellPath = "/bin/sh")
            val result = executor.run("echo explicit", null, null)
            assertEquals(0, result.exitCode)
            assertEquals("explicit", result.output.trim())
        }
    }

    @Test
    fun defaultShellExecutorRunsCommands() = runBlocking {
        withTempDir { dir ->
            val result = defaultShellExecutor(dir).run("echo default", null, null)
            assertEquals(0, result.exitCode)
            assertEquals("default", result.output.trim())
        }
    }
}

private suspend fun withTempDir(block: suspend (Path) -> Unit) {
    val dir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-unix-shell-${Random.nextLong().toString().replace("-", "")}"
    FileSystem.SYSTEM.createDirectories(dir)
    try {
        block(dir)
    } finally {
        FileSystem.SYSTEM.deleteRecursively(dir)
    }
}
