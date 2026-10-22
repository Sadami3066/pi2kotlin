package dev.pi.coding.prompt

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SystemPromptTest {

    private fun sectionsOf(input: SystemPromptInput): Map<String, String> = buildSystemPrompt(input).sections

    private fun baseInput(
        toolSnippets: List<String> = emptyList(),
        toolGuidelines: List<String> = emptyList(),
        docsPaths: List<String> = emptyList(),
        addendum: String? = null,
        projectInstructions: List<ContextFile> = emptyList(),
        skills: List<Skill> = emptyList(),
        selectedTools: List<String> = emptyList(),
    ) = SystemPromptInput(
        toolSnippets = toolSnippets,
        toolGuidelines = toolGuidelines,
        docsPaths = docsPaths,
        addendum = addendum,
        projectInstructions = projectInstructions,
        skills = skills,
        cwd = "/project".toPath(),
        selectedTools = selectedTools,
    )

    @Test
    fun `builds default sections in the documented order`() {
        val sections = sectionsOf(
            SystemPromptInput(
                toolSnippets = listOf("read: Read file contents", "bash: Run a shell command"),
                toolGuidelines = listOf("Use read to examine files instead of cat or sed."),
                docsPaths = listOf("/pkg/README.md", "/pkg/docs", "/pkg/examples"),
                addendum = "Extra guidance",
                projectInstructions = listOf(ContextFile("/project/AGENTS.md".toPath(), "# Rules")),
                skills = listOf(Skill("pdf-tools", "Work with PDFs.", "/skills/pdf-tools/SKILL.md", "body")),
                cwd = "/project".toPath(),
                selectedTools = listOf("read", "bash", "edit", "write"),
            ),
        )
        assertEquals(
            listOf("preamble", "tools", "rules", "docs", "addendum", "project_context", "skills", "cwd"),
            sections.keys.toList(),
        )
        assertEquals(DEFAULT_SYSTEM_PROMPT_PREAMBLE, sections.getValue("preamble"))
        assertEquals(
            "<tools>\n- read: Read file contents\n- bash: Run a shell command\n\n" +
                "In addition to the tools above, you may have access to other custom tools depending on the project.\n</tools>",
            sections.getValue("tools"),
        )
        assertEquals(
            "<rules>\n- Use bash for file operations like ls, rg, find\n" +
                "- Use read to examine files instead of cat or sed.\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files\n</rules>",
            sections.getValue("rules"),
        )
        assertEquals(
            "<docs>\nPi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, skills, or TUI):\n" +
                "- Main documentation: /pkg/README.md\n" +
                "- Additional docs: /pkg/docs\n" +
                "- Examples: /pkg/examples (extensions, custom tools, SDK)\n" +
                "- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... under " +
                "Examples, not the current working directory\n" +
                "- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), " +
                "skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), " +
                "keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers " +
                "(docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), " +
                "environment variables (docs/environment-variables.md), MCP servers (docs/mcp.md), codemode scripts " +
                "and non-LLM models such as classifiers and image models (docs/codemode.md)\n" +
                "- When working on pi topics, read the docs and examples, and follow .md cross-references before implementing\n" +
                "- Always read pi .md files completely and follow links to related docs (e.g., tui.md for TUI API details)\n" +
                "</docs>",
            sections.getValue("docs"),
        )
        assertEquals("<addendum>\nExtra guidance\n</addendum>", sections.getValue("addendum"))
        assertEquals(
            "<project_context>\nProject-specific instructions and guidelines:\n\n" +
                "<project_instructions path=\"/project/AGENTS.md\">\n# Rules\n</project_instructions>\n</project_context>",
            sections.getValue("project_context"),
        )
        assertTrue(sections.getValue("skills").startsWith("<skills>\nThe following skills provide specialized instructions"))
        assertTrue(sections.getValue("skills").contains("    <name>pdf-tools</name>"))
        assertEquals("<cwd>\n/project\n</cwd>", sections.getValue("cwd"))
    }

    @Test
    fun `custom preamble replaces the default prompt and suppresses tools rules and docs`() {
        val sections = sectionsOf(
            SystemPromptInput(
                preamble = "Custom system prompt",
                toolSnippets = listOf("read: Read file contents"),
                toolGuidelines = listOf("Guideline"),
                docsPaths = listOf("/pkg/README.md"),
                cwd = "/project".toPath(),
                selectedTools = listOf("read"),
            ),
        )
        assertEquals(listOf("preamble", "cwd"), sections.keys.toList())
        assertEquals("Custom system prompt", sections.getValue("preamble"))
        assertFalse(sections.containsKey("tools"))
        assertFalse(sections.containsKey("rules"))
        assertFalse(sections.containsKey("docs"))
    }

    @Test
    fun `empty preamble falls back to the default prompt`() {
        val sections = sectionsOf(
            SystemPromptInput(
                preamble = "",
                cwd = "/project".toPath(),
                selectedTools = listOf("read"),
            ),
        )
        assertEquals(DEFAULT_SYSTEM_PROMPT_PREAMBLE, sections.getValue("preamble"))
    }

    @Test
    fun `tools section falls back to none`() {
        val sections = sectionsOf(baseInput(selectedTools = listOf("read")))
        assertEquals(
            "<tools>\n(none)\n\nIn addition to the tools above, you may have access to other custom tools " +
                "depending on the project.\n</tools>",
            sections.getValue("tools"),
        )
    }

    @Test
    fun `rules add the bash fallback when no file listing tools are selected`() {
        val bashOnly = sectionsOf(baseInput(selectedTools = listOf("bash")))
        assertEquals(
            "<rules>\n- Use bash for file operations like ls, rg, find\n" +
                "- Be concise in your responses\n- Show file paths clearly when working with files\n</rules>",
            bashOnly.getValue("rules"),
        )

        val both = sectionsOf(baseInput(selectedTools = listOf("bash", "powershell")))
        assertTrue(
            both.getValue("rules")
                .contains("- Use bash or PowerShell for file operations like listing, searching, and finding files"),
        )

        val withGrep = sectionsOf(baseInput(selectedTools = listOf("bash", "grep")))
        assertFalse(withGrep.getValue("rules").contains("Use bash for file operations"))

        val powerShellOnly = sectionsOf(baseInput(selectedTools = listOf("powershell")))
        assertTrue(
            powerShellOnly.getValue("rules")
                .contains("- Use PowerShell for file operations like listing, searching, and finding files"),
        )
    }

    @Test
    fun `rules deduplicate and trim guidelines`() {
        val sections = sectionsOf(
            baseInput(
                toolGuidelines = listOf("  Use read  ", "Use read", "", "Be concise in your responses"),
                selectedTools = listOf("read"),
            ),
        )
        val rules = sections.getValue("rules")
        assertEquals(1, Regex("Use read").findAll(rules).count { it.value == "Use read" })
        assertTrue(rules.contains("- Use read\n"))
        assertEquals(1, Regex("Be concise in your responses").findAll(rules).count())
    }

    @Test
    fun `hidden tools do not contribute rules and downgrade the skill hint`() {
        val hiddenInput = buildSystemPrompt(
            SystemPromptInput(
                toolGuidelines = listOf("Hidden tool guideline"),
                skills = listOf(Skill("s", "d", "/skills/s/SKILL.md", "body")),
                cwd = "/project".toPath(),
                selectedTools = listOf("read", "bash"),
                hiddenTools = listOf("read", "bash"),
            ),
        ).sections
        assertTrue(hiddenInput.getValue("rules").contains("- Hidden tool guideline"))
        assertFalse(
            hiddenInput.getValue("skills")
                .contains("Use the read tool to load a skill's file when the task matches its description."),
        )
        assertTrue(
            hiddenInput.getValue("skills")
                .contains("Load a skill's file when the task matches its description."),
        )
    }

    @Test
    fun `skills section uses the bash hint when read is not declared`() {
        val sections = sectionsOf(
            SystemPromptInput(
                skills = listOf(Skill("s", "d", "/skills/s/SKILL.md", "body")),
                cwd = "/project".toPath(),
                selectedTools = listOf("bash"),
            ),
        )
        assertTrue(
            sections.getValue("skills")
                .contains("Use bash to load a skill's file when the task matches its description."),
        )
    }

    @Test
    fun `skills section is omitted when no reader tool is selected`() {
        val sections = sectionsOf(
            SystemPromptInput(
                skills = listOf(Skill("s", "d", "/skills/s/SKILL.md", "body")),
                cwd = "/project".toPath(),
                selectedTools = listOf("grep"),
            ),
        )
        assertFalse(sections.containsKey("skills"))
    }

    @Test
    fun `skills section defaults to the read hint when no tools are declared`() {
        val sections = sectionsOf(
            SystemPromptInput(
                skills = listOf(Skill("s", "d", "/skills/s/SKILL.md", "body")),
                cwd = "/project".toPath(),
            ),
        )
        assertTrue(
            sections.getValue("skills")
                .contains("Use the read tool to load a skill's file when the task matches its description."),
        )
    }

    @Test
    fun `custom sections are validated and tagged`() {
        val sections = sectionsOf(
            SystemPromptInput(
                cwd = "/project".toPath(),
                customSections = mapOf("environment" to "Node 22", "empty" to ""),
            ),
        )
        assertEquals("<environment>\nNode 22\n</environment>", sections.getValue("environment"))
        assertFalse(sections.containsKey("empty"))
        val error = assertFailsWith<IllegalArgumentException> {
            buildSystemPrompt(
                SystemPromptInput(cwd = "/project".toPath(), customSections = mapOf("Bad Name" to "x")),
            )
        }
        assertEquals("Invalid system prompt section name: Bad Name", error.message)
        assertFailsWith<IllegalArgumentException> {
            buildSystemPrompt(
                SystemPromptInput(cwd = "/project".toPath(), customSections = mapOf("preamble" to "x")),
            )
        }
    }

    @Test
    fun `cwd normalizes backslashes`() {
        val sections = sectionsOf(
            SystemPromptInput(cwd = "C:\\work\\project".toPath(), selectedTools = listOf("read")),
        )
        assertEquals("<cwd>\nC:/work/project\n</cwd>", sections.getValue("cwd"))
    }

    @Test
    fun `buildSystemPromptText joins the sections like a replayed system message`() {
        val input = SystemPromptInput(
            toolSnippets = listOf("read: Read file contents"),
            addendum = "Extra",
            cwd = "/project".toPath(),
            selectedTools = listOf("read"),
        )
        val expected = buildSystemPrompt(input).sections.values.joinToString("\n\n")
        assertEquals(expected, buildSystemPromptText(input))
    }

    @Test
    fun `diff reports changed added and removed sections`() {
        val previous = SystemPromptSections(
            linkedMapOf(
                "preamble" to "old",
                "tools" to "<tools>\nold\n</tools>",
                "rules" to "<rules>\nold\n</rules>",
            ),
        )
        val current = SystemPromptSections(
            linkedMapOf(
                "preamble" to "old",
                "tools" to "<tools>\nnew\n</tools>",
                "cwd" to "<cwd>\n/project\n</cwd>",
            ),
        )
        val diff = diffSystemPromptSections(previous, current).jsonObject
        assertEquals(JsonPrimitive("<tools>\nnew\n</tools>"), diff["tools"])
        assertEquals(JsonPrimitive("<cwd>\n/project\n</cwd>"), diff["cwd"])
        assertEquals(JsonNull, diff["rules"])
        assertFalse(diff.containsKey("preamble"))

        val nullPrevious = diffSystemPromptSections(null, current).jsonObject
        assertEquals(3, nullPrevious.size)
        assertFalse(nullPrevious.containsKey("rules"))
    }

    @Test
    fun `diff is empty when nothing changed`() {
        val sections = buildSystemPrompt(SystemPromptInput(cwd = "/project".toPath(), selectedTools = listOf("read")))
        assertTrue(diffSystemPromptSections(sections, sections).jsonObject.isEmpty())
        assertTrue(diffSystemPromptSectionMap(sections, sections).isEmpty())
    }

    @Test
    fun `section map diff uses null for removals`() {
        val previous = SystemPromptSections(linkedMapOf("preamble" to "p", "skills" to "s"))
        val current = SystemPromptSections(linkedMapOf("preamble" to "p2", "cwd" to "c"))
        val diff = diffSystemPromptSectionMap(previous, current)
        assertEquals(mapOf("preamble" to "p2", "cwd" to "c", "skills" to null), diff)
        assertNull(diff["skills"])
    }
}
