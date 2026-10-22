package dev.pi.coding.tools

import dev.pi.ai.JsonValue
import dev.pi.ai.RunSignal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Path

interface ShellExecutor {
    suspend fun run(command: String, timeoutSeconds: Long?, signal: RunSignal?): ShellResult
}

data class ShellResult(
    val output: String,
    val exitCode: Int?,
    val truncated: Boolean = false,
)

private const val BASH_MAX_LINES = 2000
private const val BASH_MAX_BYTES = 51200
private const val MAX_TIMEOUT_MS = 2147483647L
private const val MAX_TIMEOUT_SECONDS_TEXT = "2147483.647"

private val bashParameters: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("command") {
            put("type", "string")
            put("description", "Shell command to execute")
        }
        putJsonObject("timeout") {
            put("type", "number")
            put("description", "Timeout in seconds (optional, no default timeout)")
        }
    }
    put("required", buildJsonArray { add(JsonPrimitive("command")) })
}

fun bashTool(cwd: Path, shellExecutor: ShellExecutor?): CodingTool = CodingTool(
    name = "bash",
    description = "Execute a bash command in the current working directory. Returns stdout and stderr. Output is truncated to last $BASH_MAX_LINES lines or ${BASH_MAX_BYTES / 1024}KB (whichever is hit first). If truncated, full output is saved to a temp file. Optionally provide a timeout in seconds.",
    parameters = bashParameters,
    label = "bash",
    promptSnippet = "Execute bash commands (ls, grep, find, etc.)",
    promptGuidelines = listOf("You can inspect PI_* environment variables for current model and session details."),
    execute = { _toolCallId, args, signal, _onUpdate ->
        val command = args.toolRequiredString("command")
        val timeoutSeconds = args.toolTimeoutSeconds()
        if (shellExecutor == null) {
            textResult(
                "bash is unavailable in this environment: no shell executor is configured (mobile builds do not provide shell execution).",
                null,
                true,
            )
        } else {
            signal?.throwIfAborted()
            var failure: String? = null
            val result = try {
                shellExecutor.run(command, timeoutSeconds, signal)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failure = error.message ?: error.toString()
                null
            }
            if (result == null) {
                textResult(failure ?: "Command failed", null, true)
            } else {
                formatBashResult(result)
            }
        }
    },
)

private fun formatBashResult(result: ShellResult): dev.pi.agent.AgentToolResult {
    val truncation = truncateTail(result.output, maxLines = BASH_MAX_LINES, maxBytes = BASH_MAX_BYTES)
    var text = truncation.text.ifEmpty { "(no output)" }
    val notices = mutableListOf<String>()
    if (truncation.truncated) {
        val totalLines = countOutputLines(result.output)
        val shownLines = countOutputLines(truncation.text)
        val startLine = (totalLines - shownLines + 1).coerceAtLeast(1)
        notices += if (truncation.truncatedBy == "bytes") {
            "Showing lines $startLine-$totalLines of $totalLines (${formatToolSize(BASH_MAX_BYTES)} limit)"
        } else {
            "Showing lines $startLine-$totalLines of $totalLines"
        }
    } else if (result.truncated) {
        notices += "Output truncated"
    }
    if (notices.isNotEmpty()) {
        text += "\n\n[" + notices.joinToString(". ") + "]"
    }
    val exitCode = result.exitCode
    val isError = exitCode == null || exitCode != 0
    when {
        exitCode == null -> text += "\n\nCommand terminated without an exit code"
        exitCode != 0 -> text += "\n\nCommand exited with code $exitCode"
    }
    return textResult(text, bashDetails(truncation, result), isError)
}

private fun bashDetails(truncation: Truncated, result: ShellResult): JsonValue? {
    if (!truncation.truncated && !result.truncated) {
        return null
    }
    return buildJsonObject {
        if (truncation.truncated) {
            put("truncation", truncationJson(truncation))
        }
        if (result.truncated) {
            put("executorTruncated", true)
        }
    }
}

private fun JsonObject.toolTimeoutSeconds(): Long? {
    val primitive = this["timeout"] as? JsonPrimitive ?: return null
    val seconds = primitive.doubleOrNull ?: return null
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) {
        throw IllegalArgumentException("Invalid timeout: must be a finite number of seconds")
    }
    val timeoutMs = seconds * 1000.0
    if (timeoutMs > MAX_TIMEOUT_MS) {
        throw IllegalArgumentException("Invalid timeout: maximum is $MAX_TIMEOUT_SECONDS_TEXT seconds")
    }
    return kotlin.math.ceil(seconds).toLong()
}

private fun countOutputLines(text: String): Int {
    if (text.isEmpty()) {
        return 0
    }
    val lines = text.split("\n")
    return if (text.endsWith("\n")) lines.size - 1 else lines.size
}
