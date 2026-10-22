package dev.pi.coding.prompt

import dev.pi.coding.paths.AgentPaths
import dev.pi.coding.settings.SettingsManager
import okio.FileSystem
import okio.IOException
import okio.Path

const val CONFIG_DIR_NAME = ".pi"

data class ContextFile(
    val path: Path,
    val content: String,
)

data class LoadedResources(
    val systemPromptOverride: String? = null,
    val appendSystemPrompt: String? = null,
    val contextFiles: List<ContextFile> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val promptTemplates: List<PromptTemplate> = emptyList(),
)

class ResourceLoader(
    val paths: AgentPaths,
    val fs: FileSystem,
    val settings: SettingsManager,
) {
    suspend fun reload(): LoadedResources = loadPromptResources(
        cwd = paths.cwd,
        agentDir = paths.agentDir,
        fs = fs,
        projectTrusted = settings.isProjectTrusted(),
    )
}

suspend fun loadPromptResources(
    cwd: Path,
    agentDir: Path,
    fs: FileSystem,
    projectTrusted: Boolean,
): LoadedResources = LoadedResources(
    systemPromptOverride = loadSystemPromptOverride(cwd, agentDir, fs, projectTrusted),
    appendSystemPrompt = loadAppendSystemPrompt(cwd, agentDir, fs, projectTrusted),
    contextFiles = loadContextFiles(agentDir, cwd, fs),
    skills = loadSkills(cwd, agentDir, fs, projectTrusted),
    promptTemplates = loadPromptTemplates(cwd, agentDir, fs, projectTrusted),
)

private val CONTEXT_FILE_NAMES = listOf("AGENTS.override.md", "AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD")

suspend fun loadContextFiles(agentDir: Path, cwd: Path, fs: FileSystem): List<ContextFile> {
    val files = mutableListOf<ContextFile>()
    val seen = mutableSetOf<String>()
    loadContextFileFromDir(agentDir, fs)?.let { global ->
        files.add(global)
        seen.add(global.path.toString())
    }

    val ancestorFiles = mutableListOf<ContextFile>()
    var current: Path? = cwd.normalized()
    while (current != null) {
        val contextFile = loadContextFileFromDir(current, fs)
        if (contextFile != null && seen.add(contextFile.path.toString())) {
            ancestorFiles.add(0, contextFile)
        }
        val parent = current.parent
        current = if (parent == current) null else parent
    }
    files.addAll(ancestorFiles)
    return files
}

private suspend fun loadContextFileFromDir(dir: Path, fs: FileSystem): ContextFile? {
    for (name in CONTEXT_FILE_NAMES) {
        val path = dir / name
        if (!fs.exists(path)) continue
        val metadata = fs.metadataOrNull(path) ?: continue
        if (!metadata.isRegularFile) continue
        val content = readTextOrNull(fs, path) ?: continue
        return ContextFile(path, content.removePrefix("\uFEFF"))
    }
    return null
}

suspend fun loadSystemPromptOverride(
    cwd: Path,
    agentDir: Path,
    fs: FileSystem,
    projectTrusted: Boolean,
): String? {
    val path = discoverPromptFile(cwd, agentDir, fs, projectTrusted, "SYSTEM.md") ?: return null
    return readTextOrNull(fs, path)?.removePrefix("\uFEFF")
}

suspend fun loadAppendSystemPrompt(
    cwd: Path,
    agentDir: Path,
    fs: FileSystem,
    projectTrusted: Boolean,
): String? {
    val path = discoverPromptFile(cwd, agentDir, fs, projectTrusted, "APPEND_SYSTEM.md") ?: return null
    return readTextOrNull(fs, path)?.removePrefix("\uFEFF")
}

private fun discoverPromptFile(
    cwd: Path,
    agentDir: Path,
    fs: FileSystem,
    projectTrusted: Boolean,
    fileName: String,
): Path? {
    if (projectTrusted) {
        val projectPath = cwd / CONFIG_DIR_NAME / fileName
        if (fs.exists(projectPath)) return projectPath
    }
    val globalPath = agentDir / fileName
    return if (fs.exists(globalPath)) globalPath else null
}

suspend fun loadSkills(
    cwd: Path,
    agentDir: Path,
    fs: FileSystem,
    projectTrusted: Boolean,
): List<Skill> {
    val skills = mutableListOf<Skill>()
    val names = mutableSetOf<String>()
    fun addAll(loaded: List<Skill>) {
        for (skill in loaded) {
            if (!names.add(skill.name)) continue
            skills.add(skill)
        }
    }
    if (projectTrusted) addAll(loadSkillsFromDir(fs, cwd / CONFIG_DIR_NAME / "skills", includeRootFiles = true))
    addAll(loadSkillsFromDir(fs, agentDir / "skills", includeRootFiles = true))
    return skills
}

private suspend fun loadSkillsFromDir(
    fs: FileSystem,
    dir: Path,
    includeRootFiles: Boolean,
): List<Skill> {
    if (!fs.exists(dir)) return emptyList()
    val entries = (fs.listOrNull(dir) ?: return emptyList()).sortedBy { it.name }
    for (entry in entries) {
        if (entry.name != "SKILL.md") continue
        if (!isRegularFile(fs, entry)) continue
        val skill = loadSkillFromFile(fs, entry)
        return if (skill != null) listOf(skill) else emptyList()
    }

    val skills = mutableListOf<Skill>()
    for (entry in entries) {
        if (entry.name.startsWith(".")) continue
        if (entry.name == "node_modules") continue
        val metadata = fs.metadataOrNull(entry) ?: continue
        if (metadata.isDirectory) {
            skills.addAll(loadSkillsFromDir(fs, entry, includeRootFiles = false))
            continue
        }
        if (!metadata.isRegularFile) continue
        if (!includeRootFiles || !entry.name.endsWith(".md")) continue
        loadSkillFromFile(fs, entry)?.let { skills.add(it) }
    }
    return skills
}

private suspend fun loadSkillFromFile(fs: FileSystem, path: Path): Skill? {
    val rawContent = readTextOrNull(fs, path) ?: return null
    val parsed = try {
        Frontmatter.parse(rawContent)
    } catch (error: Exception) {
        return null
    }
    val description = parsed.frontmatter["description"]
    if (description.isNullOrBlank()) return null
    val name = parsed.frontmatter["name"]?.takeIf { it.isNotEmpty() } ?: path.parent?.name ?: path.name
    return Skill(
        name = name,
        description = description!!,
        filePath = path.toString(),
        content = rawContent,
        baseDir = (path.parent ?: path).toString(),
        disableModelInvocation = parsed.frontmatter["disable-model-invocation"] == "true",
    )
}

suspend fun loadPromptTemplates(
    cwd: Path,
    agentDir: Path,
    fs: FileSystem,
    projectTrusted: Boolean,
): List<PromptTemplate> {
    val templates = mutableListOf<PromptTemplate>()
    val names = mutableSetOf<String>()
    fun addAll(loaded: List<PromptTemplate>) {
        for (template in loaded) {
            if (!names.add(template.name)) continue
            templates.add(template)
        }
    }
    if (projectTrusted) addAll(loadPromptTemplatesFromDir(fs, cwd / CONFIG_DIR_NAME / "prompts"))
    addAll(loadPromptTemplatesFromDir(fs, agentDir / "prompts"))
    return templates
}

private suspend fun loadPromptTemplatesFromDir(fs: FileSystem, dir: Path): List<PromptTemplate> {
    if (!fs.exists(dir)) return emptyList()
    val entries = (fs.listOrNull(dir) ?: return emptyList()).sortedBy { it.name }
    val templates = mutableListOf<PromptTemplate>()
    for (entry in entries) {
        if (!entry.name.endsWith(".md")) continue
        if (!isRegularFile(fs, entry)) continue
        val rawContent = readTextOrNull(fs, entry) ?: continue
        templates.add(
            try {
                parsePromptTemplateFile(rawContent, entry.name, entry.toString())
            } catch (error: Exception) {
                continue
            },
        )
    }
    return templates
}

private suspend fun isRegularFile(fs: FileSystem, path: Path): Boolean {
    val metadata = fs.metadataOrNull(path) ?: return false
    return metadata.isRegularFile
}

private suspend fun readTextOrNull(fs: FileSystem, path: Path): String? = try {
    fs.read(path) { readUtf8() }
} catch (error: IOException) {
    null
}
