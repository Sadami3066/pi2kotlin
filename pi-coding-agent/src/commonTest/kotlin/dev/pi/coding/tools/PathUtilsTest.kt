package dev.pi.coding.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okio.Path
import okio.Path.Companion.toPath

class PathUtilsTest {
    private val cwd: Path = "/work/project".toPath()

    @Test
    fun resolvesRelativePathsAgainstCwd() {
        assertEquals("/work/project/notes.txt", resolveToolPath(cwd, "notes.txt").toString())
        assertEquals("/work/project/src/main.kt", resolveToolPath(cwd, "src/main.kt").toString())
    }

    @Test
    fun keepsAbsolutePaths() {
        assertEquals("/etc/hosts", resolveToolPath(cwd, "/etc/hosts").toString())
    }

    @Test
    fun normalizesDotAndDotDotSegments() {
        assertEquals("/work/project/src/main.kt", resolveToolPath(cwd, "src/./main.kt").toString())
        assertEquals("/work/project/src/main.kt", resolveToolPath(cwd, "src/legacy/../main.kt").toString())
        assertEquals("/work/other.txt", resolveToolPath(cwd, "../other.txt").toString())
    }

    @Test
    fun returnsCwdForEmptyInput() {
        assertEquals(cwd, resolveToolPath(cwd, ""))
    }

    @Test
    fun stripsAtPrefix() {
        assertEquals("/work/project/notes.md", resolveToolPath(cwd, "@notes.md").toString())
    }

    @Test
    fun normalizesUnicodeSpaces() {
        assertEquals("/work/project/a b.txt", resolveToolPath(cwd, "a\u00A0b.txt").toString())
        assertEquals("/work/project/a b.txt", resolveToolPath(cwd, "a\u202Fb.txt").toString())
    }

    @Test
    fun expandsTildeWithExplicitHome() {
        val home = "/home/user".toPath()
        assertEquals("/home/user", resolveToolPath(cwd, "~", home).toString())
        assertEquals("/home/user/docs/a.md", resolveToolPath(cwd, "~/docs/a.md", home).toString())
    }

    @Test
    fun expandsTildeWithConfiguredHome() {
        val previous = toolHomeDirectory
        toolHomeDirectory = "/home/configured".toPath()
        try {
            assertEquals("/home/configured/a.md", resolveToolPath(cwd, "~/a.md").toString())
            assertEquals("/home/configured", resolveToolPath(cwd, "~").toString())
        } finally {
            toolHomeDirectory = previous
        }
    }

    @Test
    fun failsWhenTildeHasNoHome() {
        val previous = toolHomeDirectory
        toolHomeDirectory = null
        try {
            assertFailsWith<IllegalStateException> { resolveToolPath(cwd, "~/a.md") }
        } finally {
            toolHomeDirectory = previous
        }
    }

    @Test
    fun doesNotExpandOtherTildeForms() {
        assertEquals("/work/project/~bob/a.md", resolveToolPath(cwd, "~bob/a.md").toString())
    }
}
