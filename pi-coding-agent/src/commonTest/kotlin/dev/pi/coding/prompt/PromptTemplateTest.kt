package dev.pi.coding.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PromptTemplateTest {

    @Test
    fun `parses command arguments with quotes`() {
        assertEquals(listOf("a", "b", "c"), parseCommandArgs("a b c"))
        assertEquals(listOf("hello world", "second"), parseCommandArgs("\"hello world\" second"))
        assertEquals(listOf("hello world"), parseCommandArgs("'hello world'"))
        assertEquals(listOf(""), parseCommandArgs("\"\""))
        assertEquals(listOf(""), parseCommandArgs("''"))
        assertEquals(listOf("a", "", "b"), parseCommandArgs("a \"\" b"))
        assertEquals(listOf("a b c"), parseCommandArgs("\"a b c\""))
        assertEquals(listOf("unterminated"), parseCommandArgs("\"unterminated"))
        assertEquals(emptyList(), parseCommandArgs(""))
        assertEquals(emptyList(), parseCommandArgs("   "))
        assertEquals(listOf("a", "b"), parseCommandArgs("  a   b  "))
    }

    @Test
    fun `substitutes positional and all arguments`() {
        val args = listOf("one", "two", "three")
        assertEquals("one", substituteArgs("\$1", args))
        assertEquals("two", substituteArgs("\$2", args))
        assertEquals("", substituteArgs("\$9", args))
        assertEquals("one two three", substituteArgs("\$@", args))
        assertEquals("one two three", substituteArgs("\$ARGUMENTS", args))
        assertEquals("pre one post", substituteArgs("pre \$1 post", args))
    }

    @Test
    fun `substitutes defaults and slicing`() {
        val args = listOf("a", "", "c")
        assertEquals("a", substituteArgs("\${1:-fallback}", args))
        assertEquals("fallback", substituteArgs("\${2:-fallback}", args))
        assertEquals("fallback", substituteArgs("\${9:-fallback}", args))
        assertEquals("a c", substituteArgs("\${@:-fallback}", listOf("a", "c")))
        assertEquals("fallback", substituteArgs("\${@:-fallback}", emptyList()))
        assertEquals("fallback", substituteArgs("\${ARGUMENTS:-fallback}", emptyList()))
        assertEquals("b c", substituteArgs("\${@:2}", listOf("a", "b", "c")))
        assertEquals("a b c", substituteArgs("\${@:0}", listOf("a", "b", "c")))
        assertEquals("b", substituteArgs("\${@:2:1}", listOf("a", "b", "c")))
        assertEquals("a b c", substituteArgs("\${@:1:9}", listOf("a", "b", "c")))
        assertEquals("", substituteArgs("\${@:9}", listOf("a", "b", "c")))
    }

    @Test
    fun `does not substitute patterns inside argument values`() {
        assertEquals("\$2", substituteArgs("\$1", listOf("\$2")))
        assertEquals("value \$@", substituteArgs("\$1", listOf("value \$@")))
    }

    @Test
    fun `parses a template file with frontmatter`() {
        val raw = "---\ndescription: Review staged git changes\nargument-hint: \"[focus]\"\n---\n" +
            "Review the staged changes. Focus on \${1:-correctness}.\n"
        val template = parsePromptTemplateFile(raw, "review.md", "/prompts/review.md")
        assertEquals("review", template.name)
        assertEquals("Review staged git changes", template.description)
        assertEquals("[focus]", template.argumentHint)
        assertEquals("Review the staged changes. Focus on \${1:-correctness}.", template.content)
        assertEquals("/prompts/review.md", template.filePath)
    }

    @Test
    fun `derives the description from the first non-empty body line`() {
        val raw = "---\nargument-hint: \"<file>\"\n---\n\n\nFix the given file.\nMore details."
        val template = parsePromptTemplateFile(raw, "fix.md", null)
        assertEquals("Fix the given file.", template.description)
        assertEquals("Fix the given file.\nMore details.", template.content)
        assertNull(template.filePath)
    }

    @Test
    fun `truncates long derived descriptions`() {
        val longLine = "x".repeat(80)
        val template = parsePromptTemplateFile(longLine, "long.md", null)
        assertEquals("x".repeat(60) + "...", template.description)
    }

    @Test
    fun `description is null when the body is empty`() {
        val template = parsePromptTemplateFile("---\n---\n", "empty.md", null)
        assertNull(template.description)
        assertEquals("", template.content)
    }

    @Test
    fun `expands a matching template`() {
        val templates = listOf(
            PromptTemplate(
                name = "review",
                description = "Review",
                content = "Review with focus on \${1:-correctness, security}.",
            ),
        )
        assertEquals(
            "Review with focus on performance.",
            expandPromptTemplate("/review performance", templates),
        )
        assertEquals(
            "Review with focus on correctness, security.",
            expandPromptTemplate("/review", templates),
        )
    }

    @Test
    fun `leaves unknown or non-command text unchanged`() {
        val templates = listOf(PromptTemplate("review", "Review", "body"))
        assertEquals("hello", expandPromptTemplate("hello", templates))
        assertEquals("/unknown arg", expandPromptTemplate("/unknown arg", templates))
        assertEquals("/", expandPromptTemplate("/", templates))
        assertEquals("body", expandPromptTemplate("/review", templates))
    }

    @Test
    fun `expander uses the injected templates`() {
        val expander = PromptTemplateExpander(
            listOf(PromptTemplate("greet", "Greet", "Hello \$1!")),
        )
        assertEquals("Hello World!", expander.expand("/greet World"))
        assertEquals("plain", expander.expand("plain"))
        assertEquals("/greet World", PromptTemplateExpander().expand("/greet World"))
    }
}
