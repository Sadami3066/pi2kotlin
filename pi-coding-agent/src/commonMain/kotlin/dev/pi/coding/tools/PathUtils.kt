package dev.pi.coding.tools

import okio.Path
import okio.Path.Companion.toPath

var toolHomeDirectory: Path? = null

private val unicodeSpaces = Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]")

fun resolveToolPath(cwd: Path, input: String): Path = resolveToolPath(cwd, input, toolHomeDirectory)

fun resolveToolPath(cwd: Path, input: String, homeDirectory: Path?): Path {
    val normalized = normalizePathInput(input)
    if (normalized.isEmpty()) return cwd
    val expanded = expandTilde(normalized, homeDirectory)
    return cwd.resolve(expanded, normalize = true)
}

private fun normalizePathInput(input: String): String {
    var normalized = unicodeSpaces.replace(input, " ")
    if (normalized.startsWith("@")) {
        normalized = normalized.substring(1)
    }
    return normalized
}

private fun expandTilde(input: String, homeDirectory: Path?): Path {
    if (input == "~") {
        return homeDirectory ?: throw IllegalStateException("Cannot expand \"~\": home directory is not configured")
    }
    if (input.startsWith("~/")) {
        val home = homeDirectory
            ?: throw IllegalStateException("Cannot expand \"~\": home directory is not configured")
        return home / input.substring(2)
    }
    return input.toPath(normalize = true)
}
