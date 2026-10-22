package dev.pi.coding.tools

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitIgnoreMatcherTest {
    private fun matcher(vararg lines: String): GitIgnoreMatcher =
        GitIgnoreMatcher(parseGitIgnoreRules(lines.joinToString("\n"), ""))

    private fun nestedMatcher(basePath: String, vararg lines: String): GitIgnoreMatcher =
        GitIgnoreMatcher(parseGitIgnoreRules(lines.joinToString("\n"), basePath))

    @Test
    fun ignoresBasenameAtAnyDepth() {
        val subject = matcher("*.log")
        assertTrue(subject.isIgnored("a.log", false))
        assertTrue(subject.isIgnored("x/y/a.log", false))
        assertFalse(subject.isIgnored("a.txt", false))
    }

    @Test
    fun anchoredPatternMatchesOnlyRoot() {
        val subject = matcher("/build")
        assertTrue(subject.isIgnored("build", true))
        assertFalse(subject.isIgnored("x/build", true))
        assertTrue(subject.isIgnored("build", false))
    }

    @Test
    fun directoryOnlyPatternMatchesDirectoriesOnly() {
        val subject = matcher("cache/")
        assertTrue(subject.isIgnored("cache", true))
        assertTrue(subject.isIgnored("x/cache", true))
        assertFalse(subject.isIgnored("cache", false))
    }

    @Test
    fun negationReincludesLater() {
        val subject = matcher("*.log", "!keep.log")
        assertTrue(subject.isIgnored("a.log", false))
        assertFalse(subject.isIgnored("keep.log", false))
        assertFalse(subject.isIgnored("x/keep.log", false))
    }

    @Test
    fun laterRuleWins() {
        val subject = matcher("*.log", "!important.log", "important.log")
        assertTrue(subject.isIgnored("important.log", false))
    }

    @Test
    fun nestedBasePathResolvesAnchoredPattern() {
        val subject = nestedMatcher("sub", "/out")
        assertTrue(subject.isIgnored("sub/out", true))
        assertFalse(subject.isIgnored("out", true))
        assertFalse(subject.isIgnored("sub/deep/out", true))
    }

    @Test
    fun globstarMatchesZeroOrMoreDirectories() {
        val subject = matcher("**/gen/*.txt")
        assertTrue(subject.isIgnored("gen/a.txt", false))
        assertTrue(subject.isIgnored("x/gen/a.txt", false))
        assertFalse(subject.isIgnored("x/gen/deep/a.txt", false))
    }

    @Test
    fun questionMarkAndCharacterClass() {
        val subject = matcher("file?.bin", "[ab].tmp")
        assertTrue(subject.isIgnored("file1.bin", false))
        assertFalse(subject.isIgnored("file12.bin", false))
        assertTrue(subject.isIgnored("a.tmp", false))
        assertTrue(subject.isIgnored("b.tmp", false))
        assertFalse(subject.isIgnored("c.tmp", false))
    }

    @Test
    fun commentsAndBlankLinesIgnored() {
        val subject = matcher("# comment", "", "*.tmp")
        assertTrue(subject.isIgnored("x.tmp", false))
        assertFalse(subject.isIgnored("# comment", false))
    }

    @Test
    fun hierarchyStacksNestedIgnores() = runTest {
        withIgnoreTempDir { root ->
            val fs = FileSystem.SYSTEM
            fs.writeIgnoreText(root, ".gitignore", "*.log\n")
            fs.writeIgnoreText(root, "sub/.gitignore", "!keep.log\n")
            fs.createDirectories(root / "sub")
            fs.writeIgnoreText(root, "keep.log", "")
            fs.writeIgnoreText(root, "sub/keep.log", "")
            fs.writeIgnoreText(root, "sub/other.log", "")
            val hierarchy = GitIgnoreHierarchy(fs, root)
            assertTrue(hierarchy.isIgnored(root / "keep.log", false))
            assertTrue(hierarchy.isIgnored(root / "sub/other.log", false))
            assertFalse(hierarchy.isIgnored(root / "sub/keep.log", false))
        }
    }

    @Test
    fun hierarchyReadsIgnoreAndFdignoreFiles() = runTest {
        withIgnoreTempDir { root ->
            val fs = FileSystem.SYSTEM
            fs.writeIgnoreText(root, ".ignore", "ignored.txt\n")
            fs.writeIgnoreText(root, ".fdignore", "fd-only.txt\n")
            fs.writeIgnoreText(root, "ignored.txt", "")
            fs.writeIgnoreText(root, "fd-only.txt", "")
            fs.writeIgnoreText(root, "kept.txt", "")
            val hierarchy = GitIgnoreHierarchy(fs, root)
            assertTrue(hierarchy.isIgnored(root / "ignored.txt", false))
            assertTrue(hierarchy.isIgnored(root / "fd-only.txt", false))
            assertFalse(hierarchy.isIgnored(root / "kept.txt", false))
        }
    }

    @Test
    fun hierarchyAlwaysIgnoresGitDirectory() = runTest {
        withIgnoreTempDir { root ->
            val hierarchy = GitIgnoreHierarchy(FileSystem.SYSTEM, root)
            assertTrue(hierarchy.isIgnored(root / ".git", true))
            assertTrue(hierarchy.isIgnored(root / "sub/.git", true))
        }
    }
}

internal suspend fun withIgnoreTempDir(block: suspend (Path) -> Unit) {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "pi-gitignore-test-${Random.nextLong().toString().replace("-", "")}"
    FileSystem.SYSTEM.createDirectories(root)
    try {
        block(root)
    } finally {
        FileSystem.SYSTEM.deleteRecursively(root)
    }
}

internal fun FileSystem.writeIgnoreText(root: Path, relative: String, text: String) {
    val path = root / relative
    path.parent?.let { createDirectories(it) }
    write(path) { writeUtf8(text) }
}
