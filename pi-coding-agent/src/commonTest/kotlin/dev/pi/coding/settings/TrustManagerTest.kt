package dev.pi.coding.settings

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

private const val TRUST = "/agent/trust.json"
private const val REAL_TRUST = "/home/user/.pi/agent/trust.json"

class TrustManagerTest {
    private fun FakeFileSystem.putText(path: String, text: String) {
        val file = path.toPath()
        val parent = file.parent
        if (parent != null && !exists(parent)) createDirectories(parent)
        write(file) { writeUtf8(text) }
    }

    private fun FakeFileSystem.textAt(path: String): String = read(path.toPath()) { readUtf8() }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun managerWith(fs: FileSystem): TrustManager = TrustManager(TRUST.toPath(), fs)

    private fun newFileSystem(): FakeFileSystem = FakeFileSystem()

    @Test
    fun missingTrustFileHasNoDecision() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/work".toPath())
        val manager = managerWith(fs)
        assertNull(manager.get("/work".toPath()))
        assertNull(manager.getEntry("/work".toPath()))
    }

    @Test
    fun storesDecisionsForCanonicalDirectory() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/work/sub".toPath())
        val manager = managerWith(fs)
        manager.set("/work".toPath(), true)
        assertEquals(true, manager.get("/work".toPath()))
        assertEquals(true, manager.get("/work/sub".toPath()))
        assertEquals(ProjectTrustEntry("/work", true), manager.getEntry("/work/sub".toPath()))
        assertEquals("{\n  \"/work\": true\n}\n", fs.textAt(TRUST))
    }

    @Test
    fun nearestDecisionWinsOverParent() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/work/private".toPath())
        val manager = managerWith(fs)
        manager.set("/work".toPath(), true)
        manager.set("/work/private".toPath(), false)
        assertEquals(false, manager.get("/work/private".toPath()))
        assertEquals(true, manager.get("/work".toPath()))
    }

    @Test
    fun nullDecisionDeletesEntry() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/work".toPath())
        val manager = managerWith(fs)
        manager.set("/work".toPath(), true)
        manager.set("/work".toPath(), null)
        assertNull(manager.get("/work".toPath()))
        assertEquals(json("{}"), json(fs.textAt(TRUST)))
    }

    @Test
    fun setManyWritesSortedEntriesAndPreservesNullValues() = runTest {
        val fs = newFileSystem()
        fs.putText(TRUST, """{ "keep": null }""")
        fs.createDirectories("/work/a".toPath())
        fs.createDirectories("/work/b".toPath())
        val manager = managerWith(fs)
        manager.setMany(
            listOf(
                ProjectTrustUpdate("/work/b".toPath(), false),
                ProjectTrustUpdate("/work/a".toPath(), true),
            ),
        )
        assertEquals("{\n  \"/work/a\": true,\n  \"/work/b\": false,\n  \"keep\": null\n}\n", fs.textAt(TRUST))
        assertNull(manager.get("/other".toPath()))
    }

    @Test
    fun rejectsInvalidTrustValue() = runTest {
        val fs = newFileSystem()
        fs.putText(TRUST, """{ "value": "yes" }""")
        fs.createDirectories("/work".toPath())
        val error = assertFailsWith<IllegalStateException> { managerWith(fs).get("/work".toPath()) }
        assertEquals("Invalid trust store /agent/trust.json: value for \"value\" must be true, false, or null", error.message)
    }

    @Test
    fun rejectsNonObjectTrustStore() = runTest {
        val fs = newFileSystem()
        fs.putText(TRUST, "[1, 2]")
        fs.createDirectories("/work".toPath())
        val error = assertFailsWith<IllegalStateException> { managerWith(fs).get("/work".toPath()) }
        assertEquals("Invalid trust store /agent/trust.json: expected an object", error.message)
    }

    @Test
    fun reportsUnreadableTrustStore() = runTest {
        val fs = newFileSystem()
        fs.putText(TRUST, "{ not json")
        fs.createDirectories("/work".toPath())
        val error = assertFailsWith<IllegalStateException> { managerWith(fs).get("/work".toPath()) }
        assertTrue(error.message!!.startsWith("Failed to read trust store /agent/trust.json:"))
    }

    @Test
    fun detectsTrustRequiringProjectResources() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/work".toPath())
        val manager = managerWith(fs)
        assertFalse(manager.hasTrustRequiringProjectResources("/work".toPath()))
        fs.putText("/work/.pi/settings.json", "{}")
        assertTrue(manager.hasTrustRequiringProjectResources("/work".toPath()))
    }

    @Test
    fun detectsAgentsSkillsDirectoriesBelowHome() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/home/u/project".toPath())
        fs.createDirectories("/home/u/.agents/skills".toPath())
        val manager = TrustManager("/home/u/.pi/agent/trust.json".toPath(), fs)
        assertFalse(manager.hasTrustRequiringProjectResources("/home/u/project".toPath()))
        fs.createDirectories("/home/u/project/.agents/skills".toPath())
        assertTrue(manager.hasTrustRequiringProjectResources("/home/u/project".toPath()))
    }

    @Test
    fun resolvesTrustedAccordingToDecisionAndDefault() = runTest {
        val fs = newFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/empty".toPath())
        fs.putText("/work/.pi/settings.json", "{}")
        val manager = managerWith(fs)
        assertFalse(manager.resolveTrusted("/work".toPath(), "ask"))
        assertFalse(manager.resolveTrusted("/work".toPath(), "never"))
        assertTrue(manager.resolveTrusted("/work".toPath(), "always"))
        manager.set("/work".toPath(), true)
        assertTrue(manager.resolveTrusted("/work".toPath(), "ask"))
        manager.set("/work".toPath(), false)
        assertFalse(manager.resolveTrusted("/work".toPath(), "always"))
        assertTrue(manager.resolveTrusted("/empty".toPath(), "never"))
    }

    @Test
    fun readsRealUserTrustFileIfPresent() = runTest {
        val fs = FileSystem.SYSTEM
        val realPath = REAL_TRUST.toPath()
        if (!fs.exists(realPath)) return@runTest
        val manager = TrustManager(realPath, fs)
        val decision = assertNotNull(manager.get("/home/user".toPath()))
        assertTrue(decision)
    }
}
