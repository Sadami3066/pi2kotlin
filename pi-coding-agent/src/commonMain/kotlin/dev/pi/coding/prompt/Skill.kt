package dev.pi.coding.prompt

enum class SkillFileReadTool {
    Read,
    Bash,
    Indirect,
}

data class Skill(
    val name: String,
    val description: String,
    val filePath: String,
    val content: String,
    val baseDir: String = filePath.substringBeforeLast('/', ""),
    val disableModelInvocation: Boolean = false,
)

fun renderAvailableSkills(
    skills: List<Skill>,
    fileReadTool: SkillFileReadTool = SkillFileReadTool.Read,
): String {
    val visibleSkills = skills.filter { !it.disableModelInvocation }
    if (visibleSkills.isEmpty()) return ""

    val lines = mutableListOf<String>()
    lines.add("")
    lines.add("")
    lines.add("The following skills provide specialized instructions for specific tasks.")
    lines.add(
        when (fileReadTool) {
            SkillFileReadTool.Read -> "Use the read tool to load a skill's file when the task matches its description."
            SkillFileReadTool.Bash -> "Use bash to load a skill's file when the task matches its description."
            SkillFileReadTool.Indirect -> "Load a skill's file when the task matches its description."
        },
    )
    lines.add(
        "When a skill file references a relative path, resolve it against the skill directory " +
            "(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.",
    )
    lines.add("")
    lines.add("<available_skills>")
    for (skill in visibleSkills) {
        lines.add("  <skill>")
        lines.add("    <name>${escapeXml(skill.name)}</name>")
        lines.add("    <description>${escapeXml(skill.description)}</description>")
        lines.add("    <location>${escapeXml(skill.filePath)}</location>")
        lines.add("  </skill>")
    }
    lines.add("</available_skills>")
    return lines.joinToString("\n")
}

fun renderSkillPromptBlock(skill: Skill, arguments: String? = null): String {
    val body = stripFrontmatter(skill.content).trim()
    val baseDir = skill.baseDir.ifEmpty { skill.filePath.substringBeforeLast('/', "") }
    val block = "<skill name=\"${skill.name}\" location=\"${skill.filePath}\">\n" +
        "References are relative to $baseDir.\n\n" +
        "$body\n" +
        "</skill>"
    return if (!arguments.isNullOrEmpty()) "$block\n\n$arguments" else block
}

internal fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")
