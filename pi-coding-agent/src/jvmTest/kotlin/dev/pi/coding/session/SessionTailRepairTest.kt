package dev.pi.coding.session

import dev.pi.ai.PlainTextContent
import dev.pi.ai.UserMessage
import dev.pi.coding.paths.AgentPaths
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionTailRepairTest {
    private fun newFs(): FakeFileSystem {
        val fs = FakeFileSystem()
        fs.createDirectories("/work".toPath())
        fs.createDirectories("/agent".toPath())
        return fs
    }

    private fun paths(): AgentPaths = AgentPaths("/work".toPath(), "/agent".toPath())

    private fun text(fs: FileSystem, path: Path): String = fs.read(path) { readUtf8() }

    private fun overwrite(fs: FileSystem, path: Path, content: String) {
        fs.write(path) { writeUtf8(content) }
    }

    private fun backupOf(file: Path): Path = assertNotNull(file.parent) / (file.name + ".corrupt")

    private fun userTexts(manager: SessionManager): List<String> = manager.getEntries()
        .filterIsInstance<SessionMessageEntry>()
        .map { entry -> ((entry.message as UserMessage).content as PlainTextContent).text }

    private suspend fun appendUser(manager: SessionManager, text: String, timestamp: Long) {
        manager.appendMessage(UserMessage(PlainTextContent(text), timestamp))
    }

    private suspend fun newSessionWithBefore(fs: FakeFileSystem): SessionManager {
        val manager = SessionManager.create(paths(), fs)
        appendUser(manager, "before", 1L)
        return manager
    }

    @Test
    fun appendAfterCorruptTailIsVisibleOnReopen() = runTest {
        val fs = newFs()
        val manager = newSessionWithBefore(fs)
        val file = assertNotNull(manager.sessionFile)
        val halfRecord = "{\"type\":\"message\",\"id\":\"half"
        overwrite(fs, file, text(fs, file) + halfRecord)

        val reopened = SessionManager.open(file, fs)
        appendUser(reopened, "after", 2L)

        val finalManager = SessionManager.open(file, fs)
        assertEquals(listOf("before", "after"), userTexts(finalManager))
        assertFalse(text(fs, file).contains("half"))
    }

    @Test
    fun corruptTailIsBackedUpWithSeparatorAndTimestamp() = runTest {
        val fs = newFs()
        val manager = newSessionWithBefore(fs)
        val file = assertNotNull(manager.sessionFile)
        val halfRecord = "{\"type\":\"message\",\"id\":\"half"
        overwrite(fs, file, text(fs, file) + halfRecord)

        SessionManager.open(file, fs)

        val backup = backupOf(file)
        assertTrue(fs.exists(backup))
        val backupText = text(fs, backup)
        assertTrue(backupText.contains(halfRecord))
        assertTrue(backupText.contains("===== corrupt session tail from"))
        assertTrue(backupText.contains(" bytes) ====="))
        val repairedText = text(fs, file)
        assertTrue(repairedText.endsWith("\n"))
        assertFalse(repairedText.contains("half"))
    }

    @Test
    fun keepsCompleteLastLineWithoutNewline() = runTest {
        val fs = newFs()
        val manager = newSessionWithBefore(fs)
        val file = assertNotNull(manager.sessionFile)
        overwrite(fs, file, text(fs, file).trimEnd('\n'))

        val reopened = SessionManager.open(file, fs)
        assertEquals(listOf("before"), userTexts(reopened))
        appendUser(reopened, "after", 2L)

        val finalManager = SessionManager.open(file, fs)
        assertEquals(listOf("before", "after"), userTexts(finalManager))
        assertFalse(fs.exists(backupOf(file)))
    }

    @Test
    fun appendRepairsMissingNewlineForLoadedSession() = runTest {
        val fs = newFs()
        val manager = newSessionWithBefore(fs)
        val file = assertNotNull(manager.sessionFile)

        val reopened = SessionManager.open(file, fs)
        overwrite(fs, file, text(fs, file).trimEnd('\n'))
        appendUser(reopened, "after", 2L)

        val finalManager = SessionManager.open(file, fs)
        assertEquals(listOf("before", "after"), userTexts(finalManager))
    }

    @Test
    fun repeatedReopenAndAppendDoesNotDuplicateRecords() = runTest {
        val fs = newFs()
        val manager = newSessionWithBefore(fs)
        val file = assertNotNull(manager.sessionFile)
        var previousSize = assertNotNull(fs.metadataOrNull(file)?.size)

        repeat(3) { index ->
            val reopened = SessionManager.open(file, fs)
            assertEquals(previousSize, assertNotNull(fs.metadataOrNull(file)?.size))
            appendUser(reopened, "message-$index", 10L + index)
            val currentSize = assertNotNull(fs.metadataOrNull(file)?.size)
            assertTrue(currentSize > previousSize)
            previousSize = currentSize
        }

        val finalManager = SessionManager.open(file, fs)
        assertEquals(listOf("before", "message-0", "message-1", "message-2"), userTexts(finalManager))
        val lineCount = text(fs, file).split("\n").count { it.isNotBlank() }
        assertEquals(5, lineCount)
        assertFalse(fs.exists(backupOf(file)))
    }

    @Test
    fun repairIsIdempotentAcrossReopens() = runTest {
        val fs = newFs()
        val manager = newSessionWithBefore(fs)
        val file = assertNotNull(manager.sessionFile)
        overwrite(fs, file, text(fs, file) + "{\"broken")

        SessionManager.open(file, fs)
        val repairedText = text(fs, file)
        val repairedSize = assertNotNull(fs.metadataOrNull(file)?.size)
        val backupSize = assertNotNull(fs.metadataOrNull(backupOf(file))?.size)

        repeat(2) {
            SessionManager.open(file, fs)
            assertEquals(repairedText, text(fs, file))
            assertEquals(repairedSize, assertNotNull(fs.metadataOrNull(file)?.size))
            assertEquals(backupSize, assertNotNull(fs.metadataOrNull(backupOf(file))?.size))
        }
    }
}
