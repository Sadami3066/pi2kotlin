package dev.pi.coding.mcp

import dev.pi.coding.paths.AgentPaths
import dev.pi.coding.settings.SettingsManager
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CWD = "/work/project"
private const val AGENT = "/agent"
private const val GLOBAL_MCP = "$AGENT/mcp.json"
private const val PROJECT_MCP = "$CWD/.pi/mcp.json"

class McpConfigLoaderTest {
    private fun newFileSystem(): FakeFileSystem = FakeFileSystem()

    private fun FakeFileSystem.putText(path: String, text: String) {
        val file: Path = path.toPath()
        val parent = file.parent
        if (parent != null && !exists(parent)) createDirectories(parent)
        write(file) { writeUtf8(text) }
    }

    private fun loader(fs: FileSystem, trusted: Boolean = true): McpConfigLoader {
        val paths = AgentPaths(CWD.toPath(), AGENT.toPath())
        val settings = SettingsManager(
            globalFile = paths.settingsFile,
            projectFile = paths.cwd / ".pi" / "settings.json",
            fs = fs,
            projectTrusted = trusted,
        )
        return McpConfigLoader(paths, fs, settings)
    }

    private fun writeGlobal(fs: FakeFileSystem, json: String) = fs.putText(GLOBAL_MCP, json)

    private fun writeProject(fs: FakeFileSystem, json: String) = fs.putText(PROJECT_MCP, json)

    @Test
    fun loadsGlobalStdioAndHttpServers() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """
            {"mcpServers":{
              "fs":{"command":"npx","args":["-y","server-fs"]},
              "docs":{"url":"https://example.com/mcp","headers":{"X-Token":"abc"}}
            }}
            """.trimIndent(),
        )
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals(listOf("fs", "docs"), result.servers.map { it.first })
        val stdio = result.servers[0].second as McpServerConfig.Stdio
        assertEquals("npx", stdio.command)
        assertEquals(listOf("-y", "server-fs"), stdio.args)
        val http = result.servers[1].second as McpServerConfig.Http
        assertEquals("https://example.com/mcp", http.url)
        assertEquals(mapOf("X-Token" to "abc"), http.headers)
    }

    @Test
    fun missingFilesYieldEmptyResult() = runTest {
        val result = loader(newFileSystem()).load(env = emptyMap())
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun ignoresProjectFileWhenProjectIsUntrusted() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":{"docs":{"url":"https://example.com/mcp"}}}""")
        writeProject(fs, """{"mcpServers":{"docs":{"enabled":false}}}""")
        val result = loader(fs, trusted = false).load(env = emptyMap())
        assertEquals(1, result.servers.size)
        assertEquals(true, result.servers.single().second.enabled)
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test
    fun doesNotReadInvalidProjectFileWhenUntrusted() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":{"docs":{"url":"https://example.com/mcp"}}}""")
        writeProject(fs, "{not json")
        val result = loader(fs, trusted = false).load(env = emptyMap())
        assertEquals(1, result.servers.size)
        assertTrue(result.errors.isEmpty(), result.errors.toString())
    }

    @Test
    fun trustedProjectOverridesEnabledExposureAndToolExposure() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """
            {"mcpServers":{"docs":{
              "url":"https://example.com/mcp",
              "headers":{"X-Token":"abc"},
              "timeout":120,
              "description":"docs server",
              "enabled":true,
              "exposure":"deferred"
            }}}
            """.trimIndent(),
        )
        writeProject(
            fs,
            """
            {"mcpServers":{"docs":{
              "enabled":false,
              "exposure":"hidden",
              "toolExposure":{"read":"direct"}
            }}}
            """.trimIndent(),
        )
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val config = result.servers.single().second as McpServerConfig.Http
        assertEquals(false, config.enabled)
        assertEquals(McpExposure.Hidden, config.exposure)
        assertEquals(mapOf("read" to McpExposure.Direct), config.toolExposure)
        assertEquals("https://example.com/mcp", config.url)
        assertEquals(mapOf("X-Token" to "abc"), config.headers)
        assertEquals(120L, config.timeoutSeconds)
        assertEquals("docs server", config.description)
    }

    @Test
    fun projectOverrideRejectsDefinitionFields() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":{"docs":{"command":"npx","args":["server"]}}}""")
        writeProject(fs, """{"mcpServers":{"docs":{"command":"other","enabled":false}}}""")
        val result = loader(fs).load(env = emptyMap())
        assertTrue(
            result.errors.any { it.contains("may only override") },
            result.errors.toString(),
        )
        val config = result.servers.single().second as McpServerConfig.Stdio
        assertEquals("npx", config.command)
        assertEquals(true, config.enabled)
    }

    @Test
    fun projectOverrideRequiresGlobalServer() = runTest {
        val fs = newFileSystem()
        writeProject(fs, """{"mcpServers":{"new-server":{"enabled":false}}}""")
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.servers.isEmpty())
        assertTrue(
            result.errors.any { it.contains("server needs a global server to override") },
            result.errors.toString(),
        )
    }

    @Test
    fun interpolatesEnvironmentValuesHeadersAndSecrets() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """
            {"mcpServers":{
              "fs":{"command":"npx","env":{"TOKEN":"${'$'}{FS_TOKEN}","BASE":"${'$'}{BASE_DIR}/data"}},
              "docs":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer ${'$'}{DOCS_TOKEN}"}}
            }}
            """.trimIndent(),
        )
        val result = loader(fs).load(
            env = mapOf("FS_TOKEN" to "fs-secret", "BASE_DIR" to "/var", "DOCS_TOKEN" to "docs-secret"),
        )
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        val stdio = result.servers[0].second as McpServerConfig.Stdio
        assertEquals(mapOf("TOKEN" to "fs-secret", "BASE" to "/var/data"), stdio.env)
        val http = result.servers[1].second as McpServerConfig.Http
        assertEquals("Bearer docs-secret", http.headers["Authorization"])
    }

    @Test
    fun missingEnvironmentVariableSkipsOnlyThatServer() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """
            {"mcpServers":{
              "docs":{"url":"https://example.com/mcp","headers":{"Authorization":"Bearer ${'$'}{DOCS_TOKEN}"}},
              "ok":{"command":"npx"}
            }}
            """.trimIndent(),
        )
        val result = loader(fs).load(env = emptyMap())
        assertEquals(listOf("ok"), result.servers.map { it.first })
        assertTrue(
            result.errors.any { it.contains("DOCS_TOKEN") && it.contains("is not set") },
            result.errors.toString(),
        )
    }

    @Test
    fun environmentMapDoesNotFallBackToProcessEnvironment() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """{"mcpServers":{"s":{"command":"npx","env":{"HOME_COPY":"${'$'}{HOME}"}}}}""",
        )
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.any { it.contains("HOME") }, result.errors.toString())
    }

    @Test
    fun rejectsCommandValues() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """
            {"mcpServers":{
              "evil":{"command":"npx","env":{"TOKEN":"!cat /etc/passwd"}},
              "ok":{"command":"npx"}
            }}
            """.trimIndent(),
        )
        val result = loader(fs).load(env = emptyMap())
        assertEquals(listOf("ok"), result.servers.map { it.first })
        assertTrue(
            result.errors.any { it.contains("command values") && it.contains("are not supported") },
            result.errors.toString(),
        )
    }

    @Test
    fun rejectsCommandValuesAfterInterpolation() = runTest {
        val fs = newFileSystem()
        writeGlobal(
            fs,
            """{"mcpServers":{"s":{"command":"npx","env":{"TOKEN":"${'$'}{PREFIX}cmd"}}}}""",
        )
        val result = loader(fs).load(env = mapOf("PREFIX" to "!"))
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.any { it.contains("command values") }, result.errors.toString())
    }

    @Test
    fun invalidGlobalJsonReportsPath() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, "{bad json")
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.servers.isEmpty())
        assertTrue(result.errors.any { it.contains(GLOBAL_MCP) }, result.errors.toString())
    }

    @Test
    fun invalidProjectJsonReportsPathWhenTrusted() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":{"docs":{"command":"npx"}}}""")
        writeProject(fs, "{bad json")
        val result = loader(fs).load(env = emptyMap())
        assertEquals(1, result.servers.size)
        assertTrue(
            result.errors.any { it.contains(PROJECT_MCP) },
            result.errors.toString(),
        )
    }

    @Test
    fun invalidMcpServersShapeReportsError() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":[]}""")
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.servers.isEmpty())
        assertTrue(
            result.errors.any { it.contains("expected an object with an \"mcpServers\" object") },
            result.errors.toString(),
        )
    }

    @Test
    fun stripsByteOrderMark() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, "\uFEFF{\"mcpServers\":{\"docs\":{\"command\":\"npx\"}}}")
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.errors.isEmpty(), result.errors.toString())
        assertEquals("npx", (result.servers.single().second as McpServerConfig.Stdio).command)
    }

    @Test
    fun invalidProjectOverrideValueReportsError() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":{"docs":{"command":"npx"}}}""")
        writeProject(fs, """{"mcpServers":{"docs":{"exposure":"sometimes"}}}""")
        val result = loader(fs).load(env = emptyMap())
        assertEquals(1, result.servers.size)
        assertTrue(
            result.errors.any { it.contains("exposure must be one of") },
            result.errors.toString(),
        )
        assertEquals(McpExposure.Deferred, result.servers.single().second.exposure)
    }

    @Test
    fun reportsGlobalValidationErrorsWithFilePath() = runTest {
        val fs = newFileSystem()
        writeGlobal(fs, """{"mcpServers":{"bad name":{"command":"npx"}}}""")
        val result = loader(fs).load(env = emptyMap())
        assertTrue(result.servers.isEmpty())
        assertTrue(
            result.errors.any { it.startsWith(GLOBAL_MCP) && it.contains("invalid server name") },
            result.errors.toString(),
        )
    }

    @Test
    fun usesProjectMcpFileFromAgentPaths() = runTest {
        val fs = newFileSystem()
        val paths = AgentPaths(CWD.toPath(), AGENT.toPath())
        assertEquals(AGENT.toPath() / "mcp.json", paths.mcpFile)
        assertEquals(CWD.toPath() / ".pi" / "mcp.json", paths.projectMcpFile)
        writeGlobal(fs, """{"mcpServers":{"docs":{"command":"npx"}}}""")
        writeProject(fs, """{"mcpServers":{"docs":{"enabled":false}}}""")
        val result = loader(fs).load(env = emptyMap())
        assertFalse(result.servers.single().second.enabled)
        assertNull(result.servers.singleOrNull { it.first == "unknown" })
    }
}
