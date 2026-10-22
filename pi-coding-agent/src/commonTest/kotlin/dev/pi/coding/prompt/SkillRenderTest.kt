package dev.pi.coding.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRenderTest {

    private val pdfSkill = Skill(
        name = "pdf-tools",
        description = "Extract text and tables from PDF files.",
        filePath = "/abs/path/pdf-tools/SKILL.md",
        content = "---\nname: pdf-tools\ndescription: Extract text and tables from PDF files.\n---\n\n# PDF tools\n",
    )

    @Test
    fun `renders the available skills block exactly`() {
        val expected = "\n\n" +
            "The following skills provide specialized instructions for specific tasks.\n" +
            "Use the read tool to load a skill's file when the task matches its description.\n" +
            "When a skill file references a relative path, resolve it against the skill directory " +
            "(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n" +
            "\n" +
            "<available_skills>\n" +
            "  <skill>\n" +
            "    <name>pdf-tools</name>\n" +
            "    <description>Extract text and tables from PDF files.</description>\n" +
            "    <location>/abs/path/pdf-tools/SKILL.md</location>\n" +
            "  </skill>\n" +
            "</available_skills>"
        assertEquals(expected, renderAvailableSkills(listOf(pdfSkill)))
    }

    @Test
    fun `renders multiple skills in order`() {
        val text = renderAvailableSkills(
            listOf(
                pdfSkill,
                Skill("second", "Second skill.", "/abs/second/SKILL.md", "body"),
            ),
        )
        val firstIndex = text.indexOf("<name>pdf-tools</name>")
        val secondIndex = text.indexOf("<name>second</name>")
        assertTrue(firstIndex in 0 until secondIndex)
        assertEquals(2, Regex("<skill>").findAll(text).count())
    }

    @Test
    fun `filters skills that disable model invocation`() {
        val disabled = pdfSkill.copy(disableModelInvocation = true)
        assertEquals("", renderAvailableSkills(listOf(disabled)))
        assertEquals(1, Regex("<name>pdf-tools</name>").findAll(renderAvailableSkills(listOf(pdfSkill, disabled))).count())
        val text = renderAvailableSkills(listOf(disabled, Skill("visible", "d", "/v/SKILL.md", "b")))
        assertTrue(text.contains("<name>visible</name>"))
        assertFalse(text.contains("<name>pdf-tools</name>"))
    }

    @Test
    fun `escapes xml characters`() {
        val skill = Skill(
            name = "a&b",
            description = "<tag> \"quoted\" 'single'",
            filePath = "/p&th/<x>/SKILL.md",
            content = "",
        )
        val text = renderAvailableSkills(listOf(skill))
        assertTrue(text.contains("<name>a&amp;b</name>"))
        assertTrue(text.contains("<description>&lt;tag&gt; &quot;quoted&quot; &apos;single&apos;</description>"))
        assertTrue(text.contains("<location>/p&amp;th/&lt;x&gt;/SKILL.md</location>"))
    }

    @Test
    fun `uses the tool specific hints`() {
        val read = renderAvailableSkills(listOf(pdfSkill), SkillFileReadTool.Read)
        assertTrue(read.contains("Use the read tool to load a skill's file"))
        val bash = renderAvailableSkills(listOf(pdfSkill), SkillFileReadTool.Bash)
        assertTrue(bash.contains("Use bash to load a skill's file"))
        val indirect = renderAvailableSkills(listOf(pdfSkill), SkillFileReadTool.Indirect)
        assertTrue(indirect.contains("Load a skill's file when the task matches its description."))
    }

    @Test
    fun `renders the skill prompt block with references line`() {
        val skill = pdfSkill.copy(
            content = "---\nname: pdf-tools\n---\n\n# PDF tools\n\nUse pdftotext.",
            baseDir = "/abs/path/pdf-tools",
        )
        assertEquals(
            "<skill name=\"pdf-tools\" location=\"/abs/path/pdf-tools/SKILL.md\">\n" +
                "References are relative to /abs/path/pdf-tools.\n\n" +
                "# PDF tools\n\nUse pdftotext.\n" +
                "</skill>",
            renderSkillPromptBlock(skill),
        )
    }

    @Test
    fun `appends arguments to the skill prompt block`() {
        val block = renderSkillPromptBlock(pdfSkill, "extract page 2")
        assertTrue(block.endsWith("</skill>\n\nextract page 2"))
    }

    @Test
    fun `derives the references directory from the skill path`() {
        val skill = Skill("s", "d", "/skills/s/SKILL.md", "body", baseDir = "")
        assertTrue(renderSkillPromptBlock(skill).contains("References are relative to /skills/s."))
    }

    @Test
    fun `parses frontmatter values`() {
        val parsed = Frontmatter.parse(
            "---\n" +
                "name: pdf-tools\n" +
                "description: 'Extract text & tables'\n" +
                "disable-model-invocation: true\n" +
                "quoted: \"say \\\"hi\\\"\"\n" +
                "metadata:\n" +
                "  origin: ecc\n" +
                "---\n" +
                "# Body\n",
        )
        assertEquals("pdf-tools", parsed.frontmatter["name"])
        assertEquals("Extract text & tables", parsed.frontmatter["description"])
        assertEquals("true", parsed.frontmatter["disable-model-invocation"])
        assertEquals("say \"hi\"", parsed.frontmatter["quoted"])
        assertEquals("# Body", parsed.body)
    }

    @Test
    fun `parses block scalars`() {
        val literal = Frontmatter.parse("---\ndescription: |\n  line one\n  line two\nname: x\n---\nbody")
        assertEquals("line one\nline two", literal.frontmatter["description"])
        assertEquals("x", literal.frontmatter["name"])

        val folded = Frontmatter.parse("---\ndescription: >\n  line one\n  line two\n---\nbody")
        assertEquals("line one line two", folded.frontmatter["description"])
    }

    @Test
    fun `handles missing frontmatter crlf and bom`() {
        val plain = Frontmatter.parse("# Just markdown\n")
        assertTrue(plain.frontmatter.isEmpty())
        assertEquals("# Just markdown\n", plain.body)

        val crlf = Frontmatter.parse("---\r\nname: x\r\n---\r\nbody\r\n")
        assertEquals("x", crlf.frontmatter["name"])
        assertEquals("body", crlf.body)

        val bom = Frontmatter.parse("\uFEFF---\nname: x\n---\nbody")
        assertEquals("x", bom.frontmatter["name"])

        val noClosing = Frontmatter.parse("---\nname: x\n")
        assertTrue(noClosing.frontmatter.isEmpty())
        assertNull(noClosing.frontmatter["name"])
    }

    @Test
    fun `stripFrontmatter removes the header`() {
        assertEquals("body", stripFrontmatter("---\nname: x\n---\nbody"))
        assertEquals("# plain", stripFrontmatter("# plain"))
    }

    @Test
    fun `supports null and comment only values`() {
        val parsed = Frontmatter.parse("---\nempty:\nnullish: ~\ncommented: value # trailing\n---\n")
        assertNull(parsed.frontmatter["empty"])
        assertTrue(parsed.frontmatter.containsKey("empty"))
        assertNull(parsed.frontmatter["nullish"])
        assertEquals("value", parsed.frontmatter["commented"])
    }

    @Test
    fun `keeps hashes that are not comments`() {
        val parsed = Frontmatter.parse("---\nname: issue-#42\n---\n")
        assertEquals("issue-#42", parsed.frontmatter["name"])
    }
}
