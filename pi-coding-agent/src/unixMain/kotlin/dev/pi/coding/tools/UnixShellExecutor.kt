package dev.pi.coding.tools

import dev.pi.ai.RunSignal
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okio.Path

class ShellExecutionException(message: String) : Exception(message)

class UnixShellExecutor(
    private val cwd: Path,
    private val shellPath: String? = null,
    private val environment: Map<String, String> = emptyMap(),
    private val maxOutputLines: Int = UNIX_MAX_OUTPUT_LINES,
    private val maxOutputBytes: Int = UNIX_MAX_OUTPUT_BYTES,
) : ShellExecutor {
    override suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult {
        signal?.throwIfAborted()
        val shell = resolveShell(shellPath)
        val builder = ProcessBuilder(listOf(shell, "-c", command))
        builder.directory(File(cwd.toString()))
        builder.redirectErrorStream(true)
        val processEnvironment = builder.environment()
        for (key in SESSION_ENVIRONMENT_KEYS) {
            processEnvironment.remove(key)
        }
        processEnvironment.putAll(environment)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            throw ShellExecutionException(error.message ?: "Failed to start shell")
        }
        runCatching { process.outputStream.close() }
        val collector = OutputCollector(maxOutputLines, maxOutputBytes)
        val reader = thread(isDaemon = true, name = "pi-shell-output") {
            readMergedOutput(process.inputStream, collector)
        }
        val unsubscribe = signal?.onAbort { killProcessTree(process) }
        try {
            val finished = try {
                if (timeoutSeconds != null) {
                    process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                } else {
                    process.waitFor()
                    true
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                killProcessTree(process)
                runCatching { process.waitFor() }
                finishReading(process, reader)
                val interrupted = collector.snapshot()
                throw ShellExecutionException(statusText(interrupted.text, "Command aborted"))
            }
            if (!finished) {
                killProcessTree(process)
                runCatching { process.waitFor() }
                finishReading(process, reader)
                val output = collector.snapshot()
                throw ShellExecutionException(statusText(output.text, "Command timed out after ${timeoutSeconds ?: 0} seconds"))
            }
            finishReading(process, reader)
            val output = collector.snapshot()
            if (signal?.isAborted == true) {
                throw ShellExecutionException(statusText(output.text, "Command aborted"))
            }
            val exitCode = runCatching { process.exitValue() }.getOrNull()
            return ShellResult(output.text, exitCode, output.truncated)
        } finally {
            unsubscribe?.invoke()
            if (process.isAlive) {
                killProcessTree(process)
            }
        }
    }
}

fun defaultShellExecutor(cwd: Path): ShellExecutor = UnixShellExecutor(cwd)

private const val UNIX_MAX_OUTPUT_LINES = 2000
private const val UNIX_MAX_OUTPUT_BYTES = 51200
private const val READER_JOIN_MILLIS = 5000L
private const val READER_CLOSE_JOIN_MILLIS = 1000L
private const val OUTPUT_BUFFER_FACTOR = 4

private val SESSION_ENVIRONMENT_KEYS = listOf(
    "PI_SESSION_ID",
    "PI_SESSION_FILE",
    "PI_PROVIDER",
    "PI_MODEL",
    "PI_REASONING_LEVEL",
)

private fun readMergedOutput(stream: InputStream, collector: OutputCollector) {
    val reader = stream.reader(Charsets.UTF_8)
    val buffer = CharArray(8192)
    try {
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) {
                break
            }
            collector.append(buffer, read)
        }
    } catch (_: IOException) {
    } finally {
        runCatching { stream.close() }
    }
}

private fun finishReading(process: Process, reader: Thread) {
    reader.join(READER_JOIN_MILLIS)
    if (reader.isAlive) {
        runCatching { process.inputStream.close() }
        reader.join(READER_CLOSE_JOIN_MILLIS)
    }
}

private fun resolveShell(customShellPath: String?): String {
    if (customShellPath != null) {
        val configured = File(customShellPath)
        if (configured.exists()) {
            return customShellPath
        }
        throw ShellExecutionException("Custom shell path not found: $customShellPath")
    }
    val bash = File("/bin/bash")
    if (bash.exists() && bash.canExecute()) {
        return "/bin/bash"
    }
    val bashOnPath = executableOnPath("bash")
    if (bashOnPath != null) {
        return bashOnPath
    }
    return "sh"
}

private fun executableOnPath(executable: String): String? {
    val pathVariable = System.getenv("PATH") ?: return null
    for (directory in pathVariable.split(File.pathSeparatorChar)) {
        if (directory.isEmpty()) {
            continue
        }
        val candidate = File(directory, executable)
        if (candidate.exists() && candidate.canExecute()) {
            return candidate.absolutePath
        }
    }
    return null
}

private fun killProcessTree(process: Process) {
    for (handle in processHandles(process)) {
        destroyHandle(handle)
    }
    runCatching { process.destroyForcibly() }
}

private fun processHandles(process: Process): List<Any> {
    return try {
        val processClass = Process::class.java
        val handleClass = Class.forName("java.lang.ProcessHandle")
        val handle = processClass.getMethod("toHandle").invoke(process) ?: return emptyList()
        val descendants = handleClass.getMethod("descendants").invoke(handle) ?: return emptyList()
        val streamClass = Class.forName("java.util.stream.Stream")
        val elements = streamClass.getMethod("toArray").invoke(descendants) ?: return emptyList()
        val array = elements as? Array<*> ?: return emptyList()
        array.filterNotNull()
    } catch (_: Throwable) {
        emptyList()
    }
}

private fun destroyHandle(handle: Any) {
    try {
        val method: Method = handle.javaClass.getMethod("destroyForcibly")
        method.invoke(handle)
    } catch (_: Throwable) {
        try {
            Class.forName("java.lang.ProcessHandle").getMethod("destroyForcibly").invoke(handle)
        } catch (_: Throwable) {
        }
    }
}

private fun statusText(output: String, status: String): String {
    val text = if (output.isEmpty()) "(no output)" else output
    return "$text\n\n$status"
}

private class OutputCollector(
    private val maxLines: Int,
    private val maxBytes: Int,
) {
    private val lock = Any()
    private val builder = java.lang.StringBuilder()
    private var dropped = false

    fun append(chars: CharArray, length: Int) {
        synchronized(lock) {
            builder.append(chars, 0, length)
            trimInterim()
        }
    }

    fun snapshot(): CollectedOutput {
        synchronized(lock) {
            val truncation = truncateTail(builder.toString(), maxLines = maxLines, maxBytes = maxBytes)
            return CollectedOutput(truncation.text, truncation.truncated || dropped)
        }
    }

    private fun trimInterim() {
        val limit = maxBytes * OUTPUT_BUFFER_FACTOR
        if (builder.length <= limit) {
            return
        }
        var cut = builder.length - maxBytes * 2
        if (cut <= 0) {
            return
        }
        val newline = builder.indexOf("\n", cut)
        if (newline >= 0 && newline + 1 < builder.length) {
            cut = newline + 1
        } else if (cut < builder.length && builder[cut].isLowSurrogate()) {
            cut++
        }
        if (cut <= 0) {
            return
        }
        builder.delete(0, cut)
        dropped = true
    }
}

private data class CollectedOutput(val text: String, val truncated: Boolean)
