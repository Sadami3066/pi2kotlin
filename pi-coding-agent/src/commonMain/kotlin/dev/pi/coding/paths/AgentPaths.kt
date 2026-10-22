package dev.pi.coding.paths

import okio.Path

class AgentPaths(
    val cwd: Path,
    val agentDir: Path,
) {
    val sessionDir: Path = agentDir / "sessions"

    val promptsDir: Path = agentDir / "prompts"

    val skillsDir: Path = agentDir / "skills"

    val extensionsDir: Path = agentDir / "extensions"

    val themesDir: Path = agentDir / "themes"

    val settingsFile: Path = agentDir / "settings.json"

    val authFile: Path = agentDir / "auth.json"

    val modelsFile: Path = agentDir / "models.json"

    val trustFile: Path = agentDir / "trust.json"

    fun sessionsForCwd(): Path = sessionDir / encodeCwdName(cwd)
}

internal fun encodeCwdName(cwd: Path): String {
    val resolved = cwd.normalized().toString()
    val withoutLeadingSeparator = resolved.removePrefix("/").removePrefix("\\")
    val encoded = buildString(withoutLeadingSeparator.length) {
        for (char in withoutLeadingSeparator) {
            append(if (char == '/' || char == '\\' || char == ':') '-' else char)
        }
    }
    return "--$encoded--"
}
