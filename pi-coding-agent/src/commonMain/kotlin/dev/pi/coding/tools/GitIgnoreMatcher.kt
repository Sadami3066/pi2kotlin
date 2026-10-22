package dev.pi.coding.tools

import dev.pi.ai.RunSignal
import okio.FileSystem
import okio.Path

private const val SIGNAL_CHECK_INTERVAL = 64
private val IGNORE_FILE_NAMES = listOf(".gitignore", ".ignore", ".fdignore")

internal class GitIgnoreRule(
    val basePath: String,
    val regex: Regex,
    val negated: Boolean,
    val directoryOnly: Boolean,
    val basenameOnly: Boolean,
)

internal class GitIgnoreMatcher internal constructor(val rules: List<GitIgnoreRule>) {
    fun isIgnored(relativePath: String, isDirectory: Boolean): Boolean = decision(relativePath, isDirectory) == true

    private fun decision(relativePath: String, isDirectory: Boolean): Boolean? {
        for (index in rules.indices.reversed()) {
            val rule = rules[index]
            if (rule.directoryOnly && !isDirectory) continue
            val target = if (rule.basenameOnly) {
                relativePath.substringAfterLast('/')
            } else {
                if (rule.basePath.isNotEmpty() && !relativePath.startsWith(rule.basePath + "/")) continue
                if (rule.basePath.isEmpty()) relativePath else relativePath.substring(rule.basePath.length + 1)
            }
            if (rule.regex.matches(target)) {
                return !rule.negated
            }
        }
        return null
    }
}

private val emptyGitIgnoreMatcher = GitIgnoreMatcher(emptyList())

internal fun parseGitIgnoreRules(text: String, basePath: String): List<GitIgnoreRule> {
    val rules = mutableListOf<GitIgnoreRule>()
    for (rawLine in text.split("\n")) {
        var line = rawLine.removeSuffix("\r").trimEnd(' ', '\t')
        if (line.isEmpty() || line.startsWith("#")) continue
        if (line.startsWith("\\#")) line = line.substring(1)
        var negated = false
        if (line.startsWith("!")) {
            negated = true
            line = line.substring(1)
        } else if (line.startsWith("\\!")) {
            line = line.substring(1)
        }
        if (line.isEmpty()) continue
        var directoryOnly = false
        if (line.endsWith("/")) {
            directoryOnly = true
            line = line.dropLast(1)
        }
        if (line.isEmpty()) continue
        val anchored = line.startsWith("/")
        val body = if (anchored) line.substring(1) else line
        if (body.isEmpty()) continue
        val basenameOnly = !anchored && !body.contains('/')
        rules += GitIgnoreRule(
            basePath = basePath,
            regex = Regex("^" + globToRegex(body) + "$"),
            negated = negated,
            directoryOnly = directoryOnly,
            basenameOnly = basenameOnly,
        )
    }
    return rules
}

internal class GitIgnoreHierarchy(
    private val fs: FileSystem,
    private val root: Path,
) {
    private val matchers = HashMap<Path, GitIgnoreMatcher>()

    fun isIgnored(path: Path, isDirectory: Boolean): Boolean {
        if (path.name == ".git") return true
        val directory = path.parent ?: return false
        val relative = toolRelativePath(root, path) ?: return false
        if (relative.isEmpty()) return false
        return matcherFor(directory).isIgnored(relative, isDirectory)
    }

    fun matcherFor(directory: Path): GitIgnoreMatcher {
        matchers[directory]?.let { return it }
        val parent = directory.parent
        val inherited = if (parent == null || directory == root) {
            emptyGitIgnoreMatcher
        } else {
            matcherFor(parent)
        }
        val basePath = toolRelativePath(root, directory).orEmpty()
        val localRules = readLocalRules(directory, basePath)
        val matcher = if (localRules.isEmpty()) inherited else GitIgnoreMatcher(inherited.rules + localRules)
        matchers[directory] = matcher
        return matcher
    }

    private fun readLocalRules(directory: Path, basePath: String): List<GitIgnoreRule> {
        val rules = mutableListOf<GitIgnoreRule>()
        for (name in IGNORE_FILE_NAMES) {
            val file = directory / name
            if (!fs.exists(file)) continue
            val text = runCatching { fs.read(file) { readUtf8() } }.getOrNull() ?: continue
            rules += parseGitIgnoreRules(text, basePath)
        }
        return rules
    }
}

internal suspend fun walkToolFilesStreaming(
    fs: FileSystem,
    root: Path,
    hierarchy: GitIgnoreHierarchy,
    signal: RunSignal?,
    filter: ((Path) -> Boolean)?,
    onFile: suspend (Path) -> Boolean,
) {
    walkDirectoryStreaming(fs, root, hierarchy, signal, filter, intArrayOf(0), onFile)
}

private suspend fun walkDirectoryStreaming(
    fs: FileSystem,
    directory: Path,
    hierarchy: GitIgnoreHierarchy,
    signal: RunSignal?,
    filter: ((Path) -> Boolean)?,
    visited: IntArray,
    onFile: suspend (Path) -> Boolean,
): Boolean {
    signal?.throwIfAborted()
    val children = runCatching { fs.list(directory) }.getOrNull() ?: return true
    for (child in children.sortedBy { it.name }) {
        visited[0]++
        if (visited[0] % SIGNAL_CHECK_INTERVAL == 0) signal?.throwIfAborted()
        val metadata = runCatching { fs.metadataOrNull(child) }.getOrNull() ?: continue
        val isDirectory = metadata.isDirectory
        if (hierarchy.isIgnored(child, isDirectory)) continue
        if (isDirectory) {
            if (!walkDirectoryStreaming(fs, child, hierarchy, signal, filter, visited, onFile)) return false
        } else if (metadata.isRegularFile) {
            if (filter != null && !filter(child)) continue
            if (!onFile(child)) return false
        }
    }
    return true
}
