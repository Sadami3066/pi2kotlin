package dev.pi.mcp.transport

import dev.pi.ai.RunSignal
import dev.pi.mcp.McpConnectionClosedException
import dev.pi.mcp.McpException
import dev.pi.mcp.McpTransport
import dev.pi.mcp.McpTransportEvent
import dev.pi.mcp.protocol.JsonRpcCodec
import dev.pi.mcp.protocol.JsonRpcMessage
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StdioTransport(
    private val command: String,
    private val args: List<String> = emptyList(),
    private val env: Map<String, String> = emptyMap(),
    private val cwd: String? = null,
    private val stderrTailBytes: Int = 64 * 1024,
    private val closeGraceMs: Long = 500,
    private val closeTimeoutMs: Long = 2_000,
) : McpTransport {
    private val eventChannel = Channel<McpTransportEvent>(Channel.UNLIMITED)
    override val events: Flow<McpTransportEvent> = eventChannel.receiveAsFlow()

    private val sendMutex = Mutex()
    private val stateLock = Any()
    private val stderrBuffer = StringBuilder()
    private val closedEventEmitted = AtomicBoolean(false)
    private val malformedCount = AtomicInteger(0)

    private var process: Process? = null
    private var stdinWriter: OutputStreamWriter? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var started = false

    @Volatile
    private var closed = false

    val pid: Long?
        get() = synchronized(stateLock) { process?.pid() }

    fun stderrTail(): String = synchronized(stateLock) { stderrBuffer.toString() }

    fun malformedMessageCount(): Int = malformedCount.get()

    val isClosed: Boolean
        get() = closed

    override suspend fun start() {
        synchronized(stateLock) {
            if (started) throw IllegalStateException("MCP stdio transport already started")
            if (closed) throw McpConnectionClosedException()
            started = true
        }
        val builder = ProcessBuilder(listOf(command) + args)
        cwd?.let { builder.directory(File(it)) }
        builder.environment().putAll(env)
        val startedProcess = try {
            builder.start()
        } catch (error: Exception) {
            closed = true
            throw McpException("Failed to start MCP stdio server '$command': ${error.message}", error)
        }
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(stateLock) {
            process = startedProcess
            stdinWriter = OutputStreamWriter(startedProcess.outputStream, Charsets.UTF_8)
            scope = activeScope
        }
        activeScope.launch { readStdout(startedProcess) }
        activeScope.launch { readStderr(startedProcess) }
    }

    override suspend fun send(message: JsonRpcMessage, signal: RunSignal?) {
        if (!started) throw McpConnectionClosedException()
        signal?.throwIfAborted()
        sendMutex.withLock {
            signal?.throwIfAborted()
            if (closed) throw McpConnectionClosedException()
            val writer = synchronized(stateLock) {
                if (closed) throw McpConnectionClosedException()
                stdinWriter ?: throw McpConnectionClosedException()
            }
            val payload = JsonRpcCodec.encode(message) + "\n"
            try {
                writer.write(payload)
                writer.flush()
            } catch (error: Exception) {
                if (error is dev.pi.ai.RequestAbortedException) throw error
                closed = true
                finishClosed(McpConnectionClosedException("MCP stdio server is closed: ${error.message}", error))
                throw McpConnectionClosedException("MCP stdio server is closed", error)
            }
        }
    }

    override suspend fun close() {
        val activeProcess: Process?
        val writer: OutputStreamWriter?
        val activeScope: CoroutineScope?
        synchronized(stateLock) {
            if (closed) return
            closed = true
            activeProcess = process
            writer = stdinWriter
            activeScope = scope
        }
        val descendants = try {
            activeProcess?.toHandle()?.descendants()?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        if (activeProcess != null) {
            val exited = runCatching { activeProcess.waitFor(closeGraceMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!exited) {
                runCatching { activeProcess.destroy() }
                val terminated = runCatching {
                    activeProcess.waitFor(closeTimeoutMs, TimeUnit.MILLISECONDS)
                }.getOrDefault(false)
                if (!terminated) {
                    runCatching { activeProcess.destroyForcibly() }
                    runCatching { activeProcess.waitFor(closeTimeoutMs, TimeUnit.MILLISECONDS) }
                }
            }
        }
        terminateDescendants(descendants)
        activeScope?.cancel()
        synchronized(stateLock) {
            if (process === activeProcess) {
                process = null
                stdinWriter = null
                scope = null
            }
        }
        finishClosed(null)
    }

    private fun terminateDescendants(descendants: List<ProcessHandle>) {
        if (descendants.isEmpty()) return
        for (handle in descendants) {
            if (handle.isAlive) runCatching { handle.destroy() }
        }
        waitUntilGone(descendants, 500)
        for (handle in descendants) {
            if (handle.isAlive) runCatching { handle.destroyForcibly() }
        }
        waitUntilGone(descendants, 1_000)
    }

    private fun waitUntilGone(handles: List<ProcessHandle>, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (handles.none { it.isAlive }) return
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun readStdout(activeProcess: Process) {
        var error: Throwable? = null
        try {
            val reader = activeProcess.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine() ?: break
                val text = line.trim()
                if (text.isEmpty()) continue
                try {
                    val messages = JsonRpcCodec.decodeBatch(text)
                    for (message in messages) {
                        eventChannel.trySend(McpTransportEvent.Message(message))
                    }
                } catch (_: Exception) {
                    malformedCount.incrementAndGet()
                }
            }
        } catch (exception: Throwable) {
            if (!closed) error = exception
        }
        val exited = try {
            activeProcess.waitFor(2_000, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        val exitCode = try {
            if (exited) activeProcess.exitValue() else null
        } catch (_: Exception) {
            null
        }
        if (!closed) {
            closed = true
            if (!exited) {
                runCatching { activeProcess.destroy() }
            }
            if (error == null && exitCode != null && exitCode != 0) {
                error = McpException("MCP stdio server exited with code $exitCode")
            }
        }
        finishClosed(error)
    }

    private fun readStderr(activeProcess: Process) {
        try {
            val reader = activeProcess.errorStream.bufferedReader(Charsets.UTF_8)
            val chunk = CharArray(4_096)
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                if (count == 0) continue
                appendStderr(String(chunk, 0, count))
            }
        } catch (_: Exception) {
        }
    }

    private fun appendStderr(text: String) {
        synchronized(stateLock) {
            stderrBuffer.append(text)
            val limit = stderrTailBytes.coerceAtLeast(0)
            if (stderrBuffer.length > limit) {
                stderrBuffer.delete(0, stderrBuffer.length - limit)
            }
        }
    }

    private fun finishClosed(error: Throwable?) {
        if (!closedEventEmitted.compareAndSet(false, true)) return
        eventChannel.trySend(McpTransportEvent.Closed(error))
        eventChannel.close()
    }
}
