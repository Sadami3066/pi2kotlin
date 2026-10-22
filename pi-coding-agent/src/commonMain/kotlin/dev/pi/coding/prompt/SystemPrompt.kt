package dev.pi.coding.prompt

import dev.pi.ai.PlainTextContent
import dev.pi.ai.SystemMessage
import dev.pi.ai.utils.getSystemMessageText
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.Path

class SystemPromptSections(val sections: Map<String, String>) {
    operator fun get(name: String): String? = sections[name]

    val isEmpty: Boolean get() = sections.isEmpty()
}

class SystemPromptInput(
    val preamble: String? = null,
    val toolSnippets: List<String> = emptyList(),
    val toolGuidelines: List<String> = emptyList(),
    val docsPaths: List<String> = emptyList(),
    val addendum: String? = null,
    val projectInstructions: List<ContextFile> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val cwd: Path,
    val selectedTools: List<String> = emptyList(),
    val hiddenTools: List<String> = emptyList(),
    val customSections: Map<String, String> = emptyMap(),
)

const val DEFAULT_SYSTEM_PROMPT_PREAMBLE =
    "You are an expert coding assistant operating inside pi, a coding agent harness. " +
        "You help users by reading files, executing commands, editing code, and writing new files."

private const val TOOLS_TRAILER =
    "In addition to the tools above, you may have access to other custom tools depending on the project."

private val SECTION_NAME_PATTERN = Regex("^[a-z][a-z0-9_-]*$")

private const val RULE_CONCISE = "Be concise in your responses"
private const val RULE_FILE_PATHS = "Show file paths clearly when working with files"

fun buildSystemPrompt(input: SystemPromptInput): SystemPromptSections {
    for (name in input.customSections.keys) {
        if (!SECTION_NAME_PATTERN.matches(name) || name == "preamble") {
            throw IllegalArgumentException("Invalid system prompt section name: $name")
        }
    }

    val declaredTools = input.selectedTools.filter { it !in input.hiddenTools }
    val sections = LinkedHashMap<String, String>()
    val customPreamble = !input.preamble.isNullOrEmpty()
    if (customPreamble) {
        sections["preamble"] = input.preamble!!
    } else {
        sections["preamble"] = DEFAULT_SYSTEM_PROMPT_PREAMBLE
        val tools = if (input.toolSnippets.isEmpty()) {
            "(none)"
        } else {
            input.toolSnippets.joinToString("\n") { snippet -> "- $snippet" }
        }
        sections["tools"] = "$tools\n\n$TOOLS_TRAILER"
        sections["rules"] = buildRules(declaredTools, input.toolGuidelines)
        val docs = buildDocsSection(input.docsPaths)
        if (docs != null) sections["docs"] = docs
    }

    if (!input.addendum.isNullOrEmpty()) sections["addendum"] = input.addendum
    if (input.projectInstructions.isNotEmpty()) {
        sections["project_context"] = renderProjectContext(input.projectInstructions)
    }
    val skillFileReadTool = resolveSkillFileReadTool(declaredTools, input.selectedTools)
    if (skillFileReadTool != null && input.skills.isNotEmpty()) {
        val skillsPrompt = renderAvailableSkills(input.skills, skillFileReadTool).trim()
        if (skillsPrompt.isNotEmpty()) sections["skills"] = skillsPrompt
    }
    sections["cwd"] = input.cwd.toString().replace('\\', '/')
    for ((name, content) in input.customSections) {
        if (content.isNotEmpty()) sections[name] = content
    }

    val wrapped = LinkedHashMap<String, String>()
    wrapped["preamble"] = sections.getValue("preamble")
    for ((name, content) in sections) {
        if (name == "preamble") continue
        wrapped[name] = "<$name>\n$content\n</$name>"
    }
    return SystemPromptSections(wrapped)
}

fun buildSystemPromptText(input: SystemPromptInput): String {
    val sections = buildSystemPrompt(input).sections.mapValues { (_, value) -> value as String? }
    return getSystemMessageText(
        SystemMessage(
            content = PlainTextContent(""),
            sections = sections,
            timestamp = 0,
        ),
    )
}

fun diffSystemPromptSections(previous: SystemPromptSections?, current: SystemPromptSections): JsonObject {
    val previousSections = previous?.sections ?: emptyMap()
    return buildJsonObject {
        for ((name, text) in current.sections) {
            if (previousSections[name] != text) put(name, JsonPrimitive(text))
        }
        for (name in previousSections.keys) {
            if (!current.sections.containsKey(name)) put(name, JsonNull)
        }
    }
}

fun diffSystemPromptSectionMap(
    previous: SystemPromptSections?,
    current: SystemPromptSections,
): Map<String, String?> {
    val previousSections = previous?.sections ?: emptyMap()
    val patch = LinkedHashMap<String, String?>()
    for ((name, text) in current.sections) {
        if (previousSections[name] != text) patch[name] = text
    }
    for (name in previousSections.keys) {
        if (!current.sections.containsKey(name)) patch[name] = null
    }
    return patch
}

private fun resolveSkillFileReadTool(declaredTools: List<String>, selectedTools: List<String>): SkillFileReadTool? {
    if ("read" in declaredTools) return SkillFileReadTool.Read
    if ("bash" in declaredTools) return SkillFileReadTool.Bash
    if (selectedTools.any { it == "read" || it == "bash" }) return SkillFileReadTool.Indirect
    if (selectedTools.isEmpty()) return SkillFileReadTool.Read
    return null
}

private fun renderProjectContext(contextFiles: List<ContextFile>): String {
    val parts = mutableListOf("Project-specific instructions and guidelines:")
    for (file in contextFiles) {
        val path = file.path.toString().replace('\\', '/')
        parts.add("<project_instructions path=\"$path\">\n${file.content}\n</project_instructions>")
    }
    return parts.joinToString("\n\n")
}

private fun buildRules(selectedTools: List<String>, toolGuidelines: List<String>): String {
    val rules = mutableListOf<String>()
    val seen = LinkedHashSet<String>()
    fun addRule(rule: String) {
        val normalized = rule.trim()
        if (normalized.isEmpty() || !seen.add(normalized)) return
        rules.add(normalized)
    }

    val hasBash = "bash" in selectedTools
    val hasPowerShell = "powershell" in selectedTools
    val hasGrep = "grep" in selectedTools
    val hasFind = "find" in selectedTools
    val hasLs = "ls" in selectedTools
    if ((hasBash || hasPowerShell) && !hasGrep && !hasFind && !hasLs) {
        when {
            hasBash && hasPowerShell ->
                addRule("Use bash or PowerShell for file operations like listing, searching, and finding files")

            hasPowerShell ->
                addRule("Use PowerShell for file operations like listing, searching, and finding files")

            else -> addRule("Use bash for file operations like ls, rg, find")
        }
    }

    for (guideline in toolGuidelines) addRule(guideline)
    addRule(RULE_CONCISE)
    addRule(RULE_FILE_PATHS)
    return rules.joinToString("\n") { rule -> "- $rule" }
}

private fun buildDocsSection(docsPaths: List<String>): String? {
    if (docsPaths.isEmpty()) return null
    val lines = mutableListOf<String>()
    lines.add(
        "Pi documentation (read only when the user asks about pi itself, its SDK, extensions, themes, " +
            "skills, or TUI):",
    )
    docsPaths.getOrNull(0)?.let { lines.add("- Main documentation: $it") }
    docsPaths.getOrNull(1)?.let { lines.add("- Additional docs: $it") }
    docsPaths.getOrNull(2)?.let { lines.add("- Examples: $it (extensions, custom tools, SDK)") }
    lines.add(
        "- When reading pi docs or examples, resolve docs/... under Additional docs and examples/... " +
            "under Examples, not the current working directory",
    )
    lines.add(
        "- When asked about: extensions (docs/extensions.md, examples/extensions/), themes (docs/themes.md), " +
            "skills (docs/skills.md), prompt templates (docs/prompt-templates.md), TUI components (docs/tui.md), " +
            "keybindings (docs/keybindings.md), SDK integrations (docs/sdk.md), custom providers " +
            "(docs/custom-provider.md), adding models (docs/models.md), pi packages (docs/packages.md), " +
            "environment variables (docs/environment-variables.md), MCP servers (docs/mcp.md), codemode scripts " +
            "and non-LLM models such as classifiers and image models (docs/codemode.md)",
    )
    lines.add(
        "- When working on pi topics, read the docs and examples, and follow .md cross-references " +
            "before implementing",
    )
    lines.add(
        "- Always read pi .md files completely and follow links to related docs " +
            "(e.g., tui.md for TUI API details)",
    )
    return lines.joinToString("\n")
}
