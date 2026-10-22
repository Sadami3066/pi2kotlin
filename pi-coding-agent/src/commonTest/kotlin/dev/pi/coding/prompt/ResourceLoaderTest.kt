package dev.pi.coding.prompt

import dev.pi.coding.paths.AgentPaths
import dev.pi.coding.settings.SettingsManager
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ResourceLoaderTest {

    private fun fakeFs(vararg files: Pair<String, String>): FileSystem {
        val fs = FakeFileSystem()
        for ((path, content) in files) {
            val target = path.toPath()
            target.parent?.let { fs.createDirectories(it) }
            fs.write(target) { writeUtf8(content) }
        }
        return fs
    }

    private val agentDir = "/agent".toPath()
    private val cwd = "/work/project".toPath()

    @Test
    fun `loads context files from the agent dir and cwd ancestors far to near`() = runTest {
        val fs = fakeFs(
            "/agent/AGENTS.md" to "global",
            "/work/AGENTS.md" to "work",
            "/work/project/AGENTS.md" to "project",
        )
        val files = loadContextFiles(agentDir, cwd, fs)
        assertEquals(listOf("global", "work", "project"), files.map { it.content })
        assertEquals(
            listOf("/agent/AGENTS.md", "/work/AGENTS.md", "/work/project/AGENTS.md"),
            files.map { it.path.toString() },
        )
    }

    @Test
    fun `prefers the first matching context file name`() = runTest {
        val fs = fakeFs(
            "/work/project/AGENTS.override.md" to "override",
            "/work/project/AGENTS.md" to "agents",
            "/work/project/CLAUDE.md" to "claude",
        )
        assertEquals(listOf("override"), loadContextFiles(agentDir, cwd, fs).map { it.content })
    }

    @Test
    fun `falls back to CLAUDE md and strips bom`() = runTest {
        val fs = fakeFs("/work/project/CLAUDE.md" to "\uFEFFclaude content")
        val files = loadContextFiles(agentDir, cwd, fs)
        assertEquals(listOf("claude content"), files.map { it.content })
    }

    @Test
    fun `project context files load without project trust`() = runTest {
        val fs = fakeFs("/work/project/AGENTS.md" to "project")
        val resources = loadPromptResources(cwd, agentDir, fs, projectTrusted = false)
        assertEquals(listOf("project"), resources.contextFiles.map { it.content })
    }

    @Test
    fun `discovers project system prompt only when trusted`() = runTest {
        val fs = fakeFs(
            "/agent/SYSTEM.md" to "global system",
            "/work/project/.pi/SYSTEM.md" to "project system",
            "/agent/APPEND_SYSTEM.md" to "global append",
            "/work/project/.pi/APPEND_SYSTEM.md" to "project append",
        )
        val trusted = loadPromptResources(cwd, agentDir, fs, projectTrusted = true)
        assertEquals("project system", trusted.systemPromptOverride)
        assertEquals("project append", trusted.appendSystemPrompt)

        val untrusted = loadPromptResources(cwd, agentDir, fs, projectTrusted = false)
        assertEquals("global system", untrusted.systemPromptOverride)
        assertEquals("global append", untrusted.appendSystemPrompt)
    }

    @Test
    fun `falls back to the global system prompt when the project file is missing`() = runTest {
        val fs = fakeFs("/agent/SYSTEM.md" to "\uFEFFglobal system")
        val resources = loadPromptResources(cwd, agentDir, fs, projectTrusted = true)
        assertEquals("global system", resources.systemPromptOverride)
    }

    @Test
    fun `deduplicates context files that repeat the agent directory`() = runTest {
        val fs = fakeFs("/work/AGENTS.md" to "work")
        val resources = loadPromptResources(cwd, "/work".toPath(), fs, projectTrusted = false)
        assertEquals(listOf("/work/AGENTS.md"), resources.contextFiles.map { it.path.toString() })
    }

    @Test
    fun `system prompt override is null when no file exists`() = runTest {
        val resources = loadPromptResources(cwd, agentDir, fakeFs(), projectTrusted = true)
        assertNull(resources.systemPromptOverride)
        assertNull(resources.appendSystemPrompt)
    }

    @Test
    fun `loads skills from skill directories and root markdown files`() = runTest {
        val fs = fakeFs(
            "/agent/skills/pdf-tools/SKILL.md" to
                "---\nname: pdf-tools\ndescription: Work with PDF files.\n---\n\n# PDF tools\n",
            "/agent/skills/root-skill.md" to "---\nname: root-skill\ndescription: Root level skill.\n---\nbody",
            "/agent/skills/nested/deep/SKILL.md" to "---\ndescription: Deep skill.\n---\nbody",
        )
        val skills = loadSkills(cwd, agentDir, fs, projectTrusted = false)
        assertEquals(listOf("deep", "pdf-tools", "root-skill"), skills.map { it.name }.sorted())
        val pdf = skills.first { it.name == "pdf-tools" }
        assertEquals("Work with PDF files.", pdf.description)
        assertTrue(pdf.filePath.endsWith("/agent/skills/pdf-tools/SKILL.md"))
        assertEquals("/agent/skills/pdf-tools", pdf.baseDir)
        assertTrue(pdf.content.contains("# PDF tools"))
        val deep = skills.first { it.name == "deep" }
        assertEquals("Deep skill.", deep.description)
    }

    @Test
    fun `skips skills without a description`() = runTest {
        val fs = fakeFs(
            "/agent/skills/no-desc/SKILL.md" to "---\nname: no-desc\n---\nbody",
            "/agent/skills/root.md" to "plain markdown without frontmatter",
            "/agent/skills/ok/SKILL.md" to "---\ndescription: Fine.\n---\nbody",
        )
        assertEquals(listOf("ok"), loadSkills(cwd, agentDir, fs, projectTrusted = false).map { it.name })
    }

    @Test
    fun `does not recurse into a skill root`() = runTest {
        val fs = fakeFs(
            "/agent/skills/multi/SKILL.md" to "---\nname: multi\ndescription: Multi.\n---\nbody",
            "/agent/skills/multi/inner/SKILL.md" to "---\nname: inner\ndescription: Inner.\n---\nbody",
        )
        assertEquals(listOf("multi"), loadSkills(cwd, agentDir, fs, projectTrusted = false).map { it.name })
    }

    @Test
    fun `skips hidden directories and node modules`() = runTest {
        val fs = fakeFs(
            "/agent/skills/.hidden/SKILL.md" to "---\ndescription: Hidden.\n---\nbody",
            "/agent/skills/node_modules/pkg/SKILL.md" to "---\ndescription: Module.\n---\nbody",
            "/agent/skills/visible/SKILL.md" to "---\ndescription: Visible.\n---\nbody",
        )
        assertEquals(listOf("visible"), loadSkills(cwd, agentDir, fs, projectTrusted = false).map { it.name })
    }

    @Test
    fun `parses disable model invocation and frontmatter names`() = runTest {
        val fs = fakeFs(
            "/agent/skills/tool/SKILL.md" to
                "---\nname: custom-name\ndescription: Hidden tool.\ndisable-model-invocation: true\n---\nbody",
        )
        val skill = loadSkills(cwd, agentDir, fs, projectTrusted = false).single()
        assertEquals("custom-name", skill.name)
        assertTrue(skill.disableModelInvocation)
    }

    @Test
    fun `project skills load only when trusted and win name collisions`() = runTest {
        val fs = fakeFs(
            "/work/project/.pi/skills/project-skill/SKILL.md" to "---\ndescription: Project skill.\n---\nbody",
            "/agent/skills/project-skill/SKILL.md" to "---\ndescription: Agent skill.\n---\nbody",
            "/agent/skills/agent-only/SKILL.md" to "---\ndescription: Agent only.\n---\nbody",
        )
        val untrusted = loadSkills(cwd, agentDir, fs, projectTrusted = false)
        assertEquals(listOf("agent-only", "project-skill"), untrusted.map { it.name }.sorted())
        assertEquals("Agent skill.", untrusted.first { it.name == "project-skill" }.description)

        val trusted = loadSkills(cwd, agentDir, fs, projectTrusted = true)
        assertEquals("Project skill.", trusted.first { it.name == "project-skill" }.description)
    }

    @Test
    fun `loads prompt templates from the prompts directory`() = runTest {
        val fs = fakeFs(
            "/agent/prompts/review.md" to "---\ndescription: Review changes\nargument-hint: \"[focus]\"\n---\n" +
                "Review the changes. \${1:-all}\n",
            "/agent/prompts/fix.md" to "Fix the given file.\n",
        )
        val templates = loadPromptTemplates(cwd, agentDir, fs, projectTrusted = false)
        assertEquals(listOf("fix", "review"), templates.map { it.name }.sorted())
        val review = templates.first { it.name == "review" }
        assertEquals("Review changes", review.description)
        assertEquals("[focus]", review.argumentHint)
        assertEquals("Review the changes. \${1:-all}", review.content)
        assertEquals("Fix the given file.", templates.first { it.name == "fix" }.description)
    }

    @Test
    fun `prompt templates are not recursive`() = runTest {
        val fs = fakeFs(
            "/agent/prompts/nested/ignored.md" to "---\ndescription: Nested\n---\nbody",
            "/agent/prompts/top.md" to "---\ndescription: Top\n---\nbody",
        )
        assertEquals(listOf("top"), loadPromptTemplates(cwd, agentDir, fs, projectTrusted = false).map { it.name })
    }

    @Test
    fun `project prompt templates load when trusted and win collisions`() = runTest {
        val fs = fakeFs(
            "/work/project/.pi/prompts/deploy.md" to "---\ndescription: Project deploy\n---\nproject body",
            "/agent/prompts/deploy.md" to "---\ndescription: Agent deploy\n---\nagent body",
        )
        assertEquals(
            "Agent deploy",
            loadPromptTemplates(cwd, agentDir, fs, projectTrusted = false).single().description,
        )
        assertEquals(
            "Project deploy",
            loadPromptTemplates(cwd, agentDir, fs, projectTrusted = true).single().description,
        )
    }

    @Test
    fun `aggregates all resources`() = runTest {
        val fs = fakeFs(
            "/agent/AGENTS.md" to "global",
            "/work/project/AGENTS.md" to "project",
            "/agent/SYSTEM.md" to "system",
            "/agent/APPEND_SYSTEM.md" to "append",
            "/agent/skills/s/SKILL.md" to "---\ndescription: Skill.\n---\nbody",
            "/agent/prompts/p.md" to "---\ndescription: Template.\n---\nbody",
        )
        val resources = loadPromptResources(cwd, agentDir, fs, projectTrusted = false)
        assertEquals("system", resources.systemPromptOverride)
        assertEquals("append", resources.appendSystemPrompt)
        assertEquals(listOf("global", "project"), resources.contextFiles.map { it.content })
        assertEquals(listOf("s"), resources.skills.map { it.name })
        assertEquals(listOf("p"), resources.promptTemplates.map { it.name })
    }

    @Test
    fun `returns empty resources for an empty file system`() = runTest {
        val resources = loadPromptResources(cwd, agentDir, fakeFs(), projectTrusted = true)
        assertEquals(LoadedResources(), resources)
    }

    @Test
    fun `resource loader applies the settings manager trust state`() = runTest {
        val fs = fakeFs(
            "/work/project/.pi/skills/project-skill/SKILL.md" to "---\ndescription: Project skill.\n---\nbody",
            "/agent/skills/user-skill/SKILL.md" to "---\ndescription: User skill.\n---\nbody",
            "/work/project/.pi/SYSTEM.md" to "project system",
            "/agent/SYSTEM.md" to "global system",
        )
        val paths = AgentPaths(cwd, agentDir)
        val settings = SettingsManager.forPaths(paths, fs)
        val loader = ResourceLoader(paths, fs, settings)

        val trusted = loader.reload()
        assertEquals("project system", trusted.systemPromptOverride)
        assertEquals(listOf("project-skill", "user-skill"), trusted.skills.map { it.name }.sorted())

        settings.setProjectTrusted(false)
        val untrusted = loader.reload()
        assertEquals("global system", untrusted.systemPromptOverride)
        assertEquals(listOf("user-skill"), untrusted.skills.map { it.name })
    }

    @Test
    fun `missing resource directories are ignored`() = runTest {
        val fs = FakeFileSystem()
        assertTrue(loadPromptTemplates(cwd, agentDir, fs, projectTrusted = false).isEmpty())
        assertTrue(loadSkills(cwd, agentDir, fs, projectTrusted = true).isEmpty())
        assertTrue(loadContextFiles(agentDir, cwd, fs).isEmpty())
    }
}
