package dev.pi.coding.settings

import dev.pi.coding.paths.AgentPaths
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GLOBAL = "/agent/settings.json"
private const val PROJECT = "/work/.pi/settings.json"
private const val REAL_SETTINGS = "/home/whywood/.pi/agent/settings.json"

class SettingsManagerTest {
    private fun FakeFileSystem.putText(path: String, text: String) {
        val file = path.toPath()
        val parent = file.parent
        if (parent != null && !exists(parent)) createDirectories(parent)
        write(file) { writeUtf8(text) }
    }

    private fun FakeFileSystem.textAt(path: String): String = read(path.toPath()) { readUtf8() }

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private suspend fun reloaded(fs: FileSystem, projectTrusted: Boolean = true): SettingsManager {
        val manager = SettingsManager(GLOBAL.toPath(), PROJECT.toPath(), fs, projectTrusted)
        manager.reload()
        return manager
    }

    private fun newFileSystem(): FakeFileSystem = FakeFileSystem()

    @Test
    fun returnsUpstreamDefaultsWhenNoSettingsFilesExist() = runTest {
        val manager = reloaded(newFileSystem())
        assertNull(manager.getDefaultModel())
        assertNull(manager.getDefaultProvider())
        assertNull(manager.getDefaultThinkingLevel())
        assertNull(manager.getEnabledModels())
        assertNull(manager.getModelThinkingLevel("p", "m"))
        assertEquals(DEFAULT_TOOL_NAMES, manager.getDefaultTools())
        assertEquals("one-at-a-time", manager.getSteeringMode())
        assertEquals("one-at-a-time", manager.getFollowUpMode())
        assertEquals(RetrySettings(), manager.getRetrySettings())
        assertEquals(CompactionSettings(), manager.getCompactionSettings())
        assertEquals(BranchSummarySettings(), manager.getBranchSummarySettings())
        assertEquals(ProviderRetrySettings(), manager.getProviderRetrySettings())
        assertEquals(true, manager.getImageAutoResize())
        assertNull(manager.getShellPath())
        assertEquals("auto", manager.getTransport())
        assertEquals("ask", manager.getDefaultProjectTrust())
        assertEquals("streaming", manager.getCacheWarmingMode())
        assertTrue(manager.getExtensionPaths().isEmpty())
        assertTrue(manager.drainErrors().isEmpty())
    }

    @Test
    fun readsGlobalSettings() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """
            {
              "defaultProvider": "opencode-go",
              "defaultModel": "deepseek-v4.1-flash",
              "defaultThinkingLevel": "max",
              "modelThinkingLevels": { "opencode-go/deepseek-v4.1-flash": "high" },
              "hideThinkingBlock": true,
              "steeringMode": "all",
              "shellPath": "/bin/zsh",
              "enabledModels": ["a", "b"],
              "images": { "autoResize": false }
            }
            """.trimIndent(),
        )
        val manager = reloaded(fs)
        assertEquals("opencode-go" to "deepseek-v4.1-flash", manager.getDefaultModel())
        assertEquals("max", manager.getDefaultThinkingLevel())
        assertEquals("high", manager.getModelThinkingLevel("opencode-go", "deepseek-v4.1-flash"))
        assertEquals(true, manager.getHideThinkingBlock())
        assertEquals("all", manager.getSteeringMode())
        assertEquals("/bin/zsh", manager.getShellPath())
        assertEquals(listOf("a", "b"), manager.getEnabledModels())
        assertEquals(false, manager.getImageAutoResize())
    }

    @Test
    fun mergesProjectSettingsOverGlobalWithDeepObjectMerge() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """
            {
              "defaultProvider": "g",
              "theme": "dark",
              "compaction": { "enabled": false, "reserveTokens": 111 },
              "retry": { "maxRetries": 9 }
            }
            """.trimIndent(),
        )
        fs.putText(
            PROJECT,
            """
            {
              "defaultProvider": "p",
              "compaction": { "enabled": true },
              "retry": { "enabled": false }
            }
            """.trimIndent(),
        )
        val settings = reloaded(fs).getSettings()
        assertEquals("p", settings.defaultProvider)
        assertEquals("dark", settings.theme)
        assertEquals(CompactionSettings(enabled = true, reserveTokens = 111, keepRecentTokens = 20000), settings.compaction)
        assertEquals(RetrySettings(enabled = false, maxRetries = 9), settings.retry)
    }

    @Test
    fun replacesArraysExceptDefaultTools() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "enabledModels": ["a", "b"], "extensions": ["global"] }""")
        fs.putText(PROJECT, """{ "enabledModels": ["c"], "extensions": ["project"] }""")
        val manager = reloaded(fs)
        assertEquals(listOf("c"), manager.getEnabledModels())
        assertEquals(listOf("project"), manager.getExtensionPaths())
    }

    @Test
    fun defaultToolsPlainListReplacesInheritedSelection() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "defaultTools": ["read", "bash"] }""")
        fs.putText(PROJECT, """{ "defaultTools": ["write"] }""")
        assertEquals(listOf("write"), reloaded(fs).getDefaultTools())
    }

    @Test
    fun defaultToolsModifiersApplyToInheritedSelection() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "defaultTools": ["read", "bash"] }""")
        fs.putText(PROJECT, """{ "defaultTools": ["+write", "-bash"] }""")
        assertEquals(listOf("read", "write"), reloaded(fs).getDefaultTools())
    }

    @Test
    fun defaultToolsModifierOnlyListWithoutBaseModifiesBuiltinDefaults() = runTest {
        val fs = newFileSystem()
        fs.putText(PROJECT, """{ "defaultTools": ["-read"] }""")
        assertEquals(listOf("bash", "edit", "write"), reloaded(fs).getDefaultTools())
    }

    @Test
    fun emptyDefaultToolsDisablesAllBuiltinTools() = runTest {
        val fs = newFileSystem()
        fs.putText(PROJECT, """{ "defaultTools": [] }""")
        assertEquals(emptyList(), reloaded(fs).getDefaultTools())
    }

    @Test
    fun defaultToolsMixedNamesAndModifiersResolveLikeUpstream() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "defaultTools": ["read"] }""")
        fs.putText(PROJECT, """{ "defaultTools": ["write", "+bash"] }""")
        assertEquals(listOf("write", "bash"), reloaded(fs).getDefaultTools())
        assertEquals("tool names cannot be mixed with +name or -name entries", getToolListError(listOf("read", "+bash")))
        assertEquals(
            "+name and -name entries take exact tool names, not patterns: +r*",
            getToolListError(listOf("+r*")),
        )
        assertNull(getToolListError(listOf("read", "write")))
    }

    @Test
    fun toolModifierHelpersMatchUpstreamOrdering() {
        assertEquals(listOf("b", "c"), applyToolModifiers(listOf("a", "b"), listOf("+c", "-a")))
        assertEquals(emptyList(), applyToolModifiers(listOf("a"), listOf("+a", "-a")))
        assertEquals(listOf("a", "c"), applyToolModifiers(listOf("a"), listOf("+c", "+c")))
        assertEquals(listOf("read", "write"), resolveDefaultTools(listOf("read", "bash", "-bash", "+write")))
        assertEquals(emptyList(), resolveDefaultTools(emptyList()))
    }

    @Test
    fun writesOnlyDirtyFieldsAndPreservesUnknownFields() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """
            {
              "defaultProvider": "g",
              "keepMe": true,
              "unknownTop": { "a": 1 }
            }
            """.trimIndent(),
        )
        val manager = reloaded(fs)
        manager.setSteeringMode("all")
        assertEquals(
            json(
                """
                {
                  "defaultProvider": "g",
                  "keepMe": true,
                  "unknownTop": { "a": 1 },
                  "steeringMode": "all"
                }
                """.trimIndent(),
            ),
            json(fs.textAt(GLOBAL)),
        )
        assertEquals("all", manager.getSteeringMode())
    }

    @Test
    fun nestedWritesMergeIntoExistingObjectAndKeepSiblingFields() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """
            {
              "retry": { "maxRetries": 7, "baseDelayMs": 1234, "custom": true },
              "other": "x"
            }
            """.trimIndent(),
        )
        val manager = reloaded(fs)
        manager.setRetryEnabled(false)
        assertEquals(
            json(
                """
                {
                  "retry": { "maxRetries": 7, "baseDelayMs": 1234, "custom": true, "enabled": false },
                  "other": "x"
                }
                """.trimIndent(),
            ),
            json(fs.textAt(GLOBAL)),
        )
        assertEquals(false, manager.getRetryEnabled())
        assertEquals(7L, manager.getRetrySettings().maxRetries)
        assertEquals(1234L, manager.getRetrySettings().baseDelayMs)
    }

    @Test
    fun settersPersistScalarsAndArrays() = runTest {
        val fs = newFileSystem()
        val manager = reloaded(fs)
        manager.setDefaultModelAndProvider("provider", "model")
        manager.setDefaultThinkingLevel("high")
        manager.setEnabledModels(listOf("a"))
        manager.setDefaultTools(listOf("read"))
        manager.setFollowUpMode("all")
        manager.setCompactionEnabled(false)
        val settings = manager.getSettings()
        assertEquals("provider", settings.defaultProvider)
        assertEquals("model", settings.defaultModel)
        assertEquals("high", settings.defaultThinkingLevel)
        assertEquals(listOf("a"), settings.enabledModels)
        assertEquals(listOf("read"), settings.defaultTools)
        assertEquals("all", settings.followUpMode)
        assertEquals(false, settings.compaction?.enabled)
        assertEquals(16384L, settings.compaction?.reserveTokens)
    }

    @Test
    fun nullValuesRemoveFieldsFromMemoryAndFile() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """{ "defaultThinkingLevel": "max", "enabledModels": ["a"], "defaultTools": ["read"] }""",
        )
        val manager = reloaded(fs)
        manager.setDefaultThinkingLevel(null)
        manager.setEnabledModels(null)
        manager.setDefaultTools(null)
        assertNull(manager.getDefaultThinkingLevel())
        assertNull(manager.getEnabledModels())
        assertEquals(DEFAULT_TOOL_NAMES, manager.getDefaultTools())
        assertEquals(json("{}"), json(fs.textAt(GLOBAL)))
    }

    @Test
    fun projectSettingsRequireTrust() = runTest {
        val fs = newFileSystem()
        fs.putText(PROJECT, """{ "defaultProvider": "project-provider" }""")
        val untrusted = reloaded(fs, projectTrusted = false)
        assertNull(untrusted.getDefaultProvider())
        assertFalse(untrusted.isProjectTrusted())
        untrusted.setProjectTrusted(true)
        assertEquals("project-provider", untrusted.getDefaultProvider())
        assertTrue(untrusted.isProjectTrusted())
    }

    @Test
    fun malformedSettingsFileIsToleratedButBlocksWrites() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, "{ not json")
        val manager = reloaded(fs)
        val errors = manager.drainErrors()
        assertEquals(1, errors.size)
        assertEquals("global", errors.first().scope)
        assertNull(manager.getDefaultProvider())
        manager.setSteeringMode("all")
        assertEquals("all", manager.getSteeringMode())
        assertEquals("{ not json", fs.textAt(GLOBAL))
    }

    @Test
    fun writeCreatesParentDirectoryAndLeavesNoTemporaryFile() = runTest {
        val fs = newFileSystem()
        val manager = reloaded(fs)
        manager.setSteeringMode("all")
        assertTrue(fs.exists(GLOBAL.toPath()))
        assertFalse(fs.exists("/agent/settings.json.tmp".toPath()))
        assertEquals("{\n  \"steeringMode\": \"all\"\n}", fs.textAt(GLOBAL))
    }

    @Test
    fun reloadPicksUpExternalChanges() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "defaultProvider": "first" }""")
        val manager = reloaded(fs)
        assertEquals("first", manager.getDefaultProvider())
        fs.putText(GLOBAL, """{ "defaultProvider": "second" }""")
        manager.reload()
        assertEquals("second", manager.getDefaultProvider())
    }

    @Test
    fun appliesSettingsMigrationsWhenReading() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """
            {
              "queueMode": "all",
              "websockets": true,
              "skills": { "enableSkillCommands": false, "customDirectories": ["/skills"] },
              "retry": { "maxDelayMs": 1234 }
            }
            """.trimIndent(),
        )
        val manager = reloaded(fs)
        val settings = manager.getSettings()
        assertEquals("all", settings.steeringMode)
        assertNull(settings.queueMode)
        assertEquals("websocket", settings.transport)
        assertNull(settings.websockets)
        assertEquals(listOf("/skills"), settings.skills)
        assertEquals(false, settings.enableSkillCommands)
        assertEquals(1234L, settings.retry?.provider?.maxRetryDelayMs)
        assertEquals(1234L, manager.getProviderRetrySettings().maxRetryDelayMs)
    }

    @Test
    fun migratesLegacySkillsObjectWithoutCustomDirectories() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "skills": { "enableSkillCommands": false } }""")
        val settings = reloaded(fs).getSettings()
        assertNull(settings.skills)
        assertEquals(false, settings.enableSkillCommands)
    }

    @Test
    fun migratesRetryMaxDelayOnlyWhenProviderValueIsMissing() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "retry": { "maxDelayMs": 1234, "provider": { "maxRetryDelayMs": 99 } } }""")
        assertEquals(99L, reloaded(fs).getProviderRetrySettings().maxRetryDelayMs)
    }

    @Test
    fun migratesCommandsDirectoryToPrompts() = runTest {
        val fs = newFileSystem()
        fs.putText("/agent/commands/one.md", "global")
        fs.putText("/work/.pi/commands/two.md", "project")
        Migrations.run(fs, "/agent".toPath(), "/work".toPath())
        assertFalse(fs.exists("/agent/commands".toPath()))
        assertEquals("global", fs.textAt("/agent/prompts/one.md"))
        assertFalse(fs.exists("/work/.pi/commands".toPath()))
        assertEquals("project", fs.textAt("/work/.pi/prompts/two.md"))
    }

    @Test
    fun keepsExistingPromptsDirectoryDuringMigration() = runTest {
        val fs = newFileSystem()
        fs.putText("/agent/commands/one.md", "old")
        fs.putText("/agent/prompts/keep.md", "new")
        assertFalse(Migrations.migrateCommandsToPrompts(fs, "/agent".toPath()))
        assertTrue(fs.exists("/agent/commands/one.md".toPath()))
        assertEquals("new", fs.textAt("/agent/prompts/keep.md"))
    }

    @Test
    fun forPathsUsesAgentDirectoryAndProjectConfigFile() {
        val fs = newFileSystem()
        val manager = SettingsManager.forPaths(AgentPaths("/work".toPath(), "/agent".toPath()), fs)
        assertEquals("/agent/settings.json".toPath(), manager.globalFile)
        assertEquals("/work/.pi/settings.json".toPath(), manager.projectFile)
        assertEquals(fs, manager.fs)
    }

    @Test
    fun preservesPackagesAndComplexValuesOnUnrelatedWrites() = runTest {
        val fs = newFileSystem()
        fs.putText(
            GLOBAL,
            """
            {
              "packages": ["npm:pi-web-access@0.42.0", { "source": "npm:pi-hud", "skills": ["-skills/release/SKILL.md"] }]
            }
            """.trimIndent(),
        )
        val manager = reloaded(fs)
        assertEquals(2, manager.getPackages().size)
        manager.setDefaultThinkingLevel("low")
        val file = json(fs.textAt(GLOBAL)).jsonObject
        assertEquals(
            json("""["npm:pi-web-access@0.42.0", { "source": "npm:pi-hud", "skills": ["-skills/release/SKILL.md"] }]"""),
            file["packages"],
        )
    }

    @Test
    fun exposesUnknownTopLevelFieldsThroughExtra() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "defaultProvider": "g", "future": { "x": 1 } }""")
        val settings = reloaded(fs).getSettings()
        assertEquals<JsonElement?>(json("""{ "future": { "x": 1 } }"""), settings.extra)
        assertNull(reloaded(newFileSystem()).getSettings().extra)
    }

    @Test
    fun malformedDefaultToolsValueDisablesBuiltinToolsLikeUpstream() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "defaultTools": "read" }""")
        assertEquals(emptyList(), reloaded(fs).getDefaultTools())
    }

    @Test
    fun projectSettingsErrorsAreReportedOnReload() = runTest {
        val fs = newFileSystem()
        fs.putText(PROJECT, "[1, 2]")
        val manager = reloaded(fs)
        val errors = manager.drainErrors()
        assertEquals(1, errors.size)
        assertEquals("project", errors.first().scope)
        assertEquals(PROJECT.toPath(), errors.first().path)
    }

    @Test
    fun readsRealUserSettingsFileIfPresent() = runTest {
        val fs = FileSystem.SYSTEM
        val realPath = REAL_SETTINGS.toPath()
        if (!fs.exists(realPath)) return@runTest
        val manager = SettingsManager(realPath, null, fs)
        manager.reload()
        assertTrue(manager.drainErrors().isEmpty())
        val settings = manager.getSettings()
        assertTrue(settings.defaultProvider != null)
        assertTrue(settings.defaultModel != null)
        assertTrue(settings.packages?.isNotEmpty() == true)
        assertEquals(DEFAULT_TOOL_NAMES, manager.getDefaultTools())
        assertTrue(manager.getSteeringMode() == "all" || manager.getSteeringMode() == "one-at-a-time")
    }

    @Test
    fun getSettingsExpandsNestedObjectsWithDefaults() = runTest {
        val fs = newFileSystem()
        fs.putText(GLOBAL, """{ "compaction": { "enabled": false }, "terminal": { "imageWidthCells": 80 } }""")
        val settings = reloaded(fs).getSettings()
        assertEquals(CompactionSettings(enabled = false), settings.compaction)
        assertEquals(80L, settings.terminal?.imageWidthCells)
        assertEquals(true, settings.terminal?.showImages)
    }
}
