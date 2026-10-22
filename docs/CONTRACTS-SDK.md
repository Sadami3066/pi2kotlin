# pi-coding-agent SDK 契约（M5）

所有新代码在 `pi-coding-agent/src/commonMain/kotlin/dev/pi/coding/`，测试在 `commonTest`。平台无关：只用 okio（`okio.FileSystem`/`Path`）、kotlinx.serialization、coroutines；禁止 `java.*`、`File`、`ProcessBuilder`、`System.getenv`。

## 通用

```kotlin
// paths/AgentPaths.kt
class AgentPaths(val cwd: Path, val agentDir: Path) {
    val sessionDir: Path            // agentDir / "sessions"
    fun sessionsForCwd(): Path      // sessionDir / "--<encoded-cwd>--"
    val promptsDir: Path            // agentDir / "prompts"
    val skillsDir: Path             // agentDir / "skills"
    val extensionsDir: Path
    val themesDir: Path
}

// json/Jsonc.kt
object Jsonc {
    fun parse(text: String): JsonElement   // 允许 // 与 /* */ 注释、尾逗号；失败抛 IllegalArgumentException
}
```

## 1. session（task-9）

`session/SessionEntry.kt`：`SessionHeader` + 11 种条目（格式见 `docs/analysis/pi-coding-agent-formats.md` §2.3-2.5，字段名逐字一致）。

```kotlin
sealed interface SessionEntry {
    val type: String
    val id: String
    val parentId: String?
    val timestamp: String        // ISO-8601 字符串
}

class SessionMessageEntry(... val message: Message) : SessionEntry
class ThinkingLevelChangeEntry(... val thinkingLevel: String) : SessionEntry
class ModelChangeEntry(... val provider: String, val modelId: String) : SessionEntry
class UsageEntry(... val kind: String? = null, val provider: String, val model: String, val usage: Usage, val note: String? = null) : SessionEntry
class CompactionEntry(... val summary: String, val firstKeptEntryId: String, val tokensBefore: Long, val usage: Usage? = null, val details: JsonValue? = null, val fromHook: Boolean? = null, val systemMessage: SystemMessage? = null) : SessionEntry
class BranchSummaryEntry(... val fromId: String, val summary: String, val details: JsonValue? = null, val usage: Usage? = null, val fromHook: Boolean? = null) : SessionEntry
class CustomEntry(... val customType: String, val data: JsonValue? = null) : SessionEntry
class CustomMessageEntry(... val customType: String, val content: MessageContent, val display: Boolean, val details: JsonValue? = null) : SessionEntry
class ContextEditEntry(... val targetId: String, val replacement: ContentReplacement?) : SessionEntry
class LabelEntry(... val targetId: String, val label: String?) : SessionEntry
class SessionInfoEntry(... val name: String?) : SessionEntry
```

`session/SessionManager.kt`：

```kotlin
class SessionManager {
    companion object {
        suspend fun create(paths: AgentPaths, fs: FileSystem, sessionId: String? = null): SessionManager
        suspend fun open(file: Path, fs: FileSystem): SessionManager
        suspend fun continueRecent(paths: AgentPaths, fs: FileSystem): SessionManager?
        fun inMemory(cwd: Path): SessionManager
        suspend fun list(paths: AgentPaths, fs: FileSystem): List<SessionInfo>       // 元信息：id/file/cwd/timestamp/name/messageCount
        suspend fun findById(sessionId: String, paths: AgentPaths, fs: FileSystem): SessionManager?
    }

    val sessionId: String
    val cwd: Path
    val sessionFile: Path?

    fun getHeader(): SessionHeader
    fun getEntries(): List<SessionEntry>
    fun getEntry(id: String): SessionEntry?
    fun getLeafId(): String?
    fun getBranch(fromId: String? = null): List<SessionEntry>            // 默认从 leaf 回溯
    fun getSessionName(): String?
    fun getTree(): List<TreeNode>                                        // TreeNode(entry, children)
    fun buildSessionProjection(): List<SessionEntry>                     // 应用 context_edit
    fun buildContextEntries(): List<SessionEntry>                        // compaction 感知
    fun buildSessionContext(): SessionContext                            // messages + provider/model + thinkingLevel
    fun getSessionStats(): SessionStats                                  // tokens/cost 累计（usage 条目 + assistant usage）

    suspend fun appendMessage(message: Message): SessionMessageEntry
    suspend fun appendThinkingLevelChange(level: String): ThinkingLevelChangeEntry
    suspend fun appendModelChange(provider: String, modelId: String): ModelChangeEntry
    suspend fun appendUsage(kind: String?, provider: String, model: String, usage: Usage, note: String? = null): UsageEntry
    suspend fun appendCompaction(summary: String, firstKeptEntryId: String, tokensBefore: Long, usage: Usage? = null, details: JsonValue? = null, systemMessage: SystemMessage? = null): CompactionEntry
    suspend fun appendBranchSummary(fromId: String, summary: String, details: JsonValue? = null, usage: Usage? = null): BranchSummaryEntry
    suspend fun appendCustomEntry(customType: String, data: JsonValue? = null): CustomEntry
    suspend fun appendCustomMessageEntry(customType: String, content: MessageContent, display: Boolean, details: JsonValue? = null): CustomMessageEntry
    suspend fun appendContextEdit(targetId: String, replacement: ContentReplacement?): ContextEditEntry
    suspend fun appendLabelChange(targetId: String, label: String?): LabelEntry
    suspend fun appendSessionInfo(name: String?): SessionInfoEntry

    fun branch(entryId: String)
    fun resetLeaf()
    suspend fun branchWithSummary(entryId: String, summary: String, details: JsonValue? = null, usage: Usage? = null): BranchSummaryEntry
    suspend fun createBranchedSession(leafId: String? = null): SessionManager
    suspend fun close()
}

data class SessionContext(val messages: List<Message>, val provider: String?, val modelId: String?, val thinkingLevel: String?)
data class SessionInfo(val sessionId: String, val sessionFile: Path, val cwd: String, val timestamp: String, val name: String?, val messageCount: Int)
data class SessionStats(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long, val cacheWriteTokens: Long, val totalCost: Double)
class TreeNode(val entry: SessionEntry, val children: List<TreeNode>)
```

必须复刻：延迟建文件（第一条 user/assistant message 才落盘，之前条目在内存）、追加写、v1-v3 迁移读取、cwd 编码、uuidv7/8 位 hex id、context_edit 投影、compaction 感知重建。

## 2. settings（task-10）

`settings/PiSettings.kt`：完整 settings 模型（字段全集见 formats 文档 §3.2），默认值见上游 `settings-defaults.ts`。

```kotlin
@Serializable data class PiSettings(
    val defaultProvider: String? = null,
    val defaultModel: String? = null,
    val defaultThinkingLevel: String? = null,
    val defaultTools: List<String>? = null,
    val enabledModels: List<String>? = null,
    val steeringMode: String? = null,
    val followUpMode: String? = null,
    val retry: RetrySettings? = null,
    val compaction: CompactionSettings? = null,
    val images: ImageSettings? = null,
    val shell: ShellSettings? = null,
    val modelThinking: Map<String, String>? = null,
    val theme: String? = null,
    val packages: List<JsonElement>? = null,
    ...
)
class SettingsManager {
    suspend fun reload()
    fun getSettings(): PiSettings
    suspend fun setDefaultModelAndProvider(provider: String, modelId: String)
    suspend fun setDefaultThinkingLevel(level: String?)
    suspend fun setEnabledModels(models: List<String>?)
    suspend fun setDefaultTools(tools: List<String>?)
    suspend fun setSteeringMode(mode: String)
    suspend fun setFollowUpMode(mode: String)
    suspend fun setRetryEnabled(enabled: Boolean)
    suspend fun setCompactionEnabled(enabled: Boolean)
    fun getDefaultModel(): Pair<String, String>?     // provider to modelId
    fun getDefaultThinkingLevel(): String?
    fun getEnabledModels(): List<String>?
    fun getDefaultTools(): List<String>              // 含 +/- 修饰规则（formats §3.3）
    fun getSteeringMode(): String
    fun getFollowUpMode(): String
    fun getRetrySettings(): RetrySettings
    fun getCompactionSettings(): CompactionSettings
    fun getImageAutoResize(): Boolean
    fun getShellPath(): String?
    fun getModelThinkingLevel(provider: String, modelId: String): String?
}
```
两级 global+project 合并（数组并集/覆盖规则按上游），写回只写脏字段且保留未知字段，原子写（tmp+move）。

## 3. models（task-11）

```kotlin
// models/CustomModels.kt
data class CustomModelConfig(val id, val name?, val reasoning?, val input?, val contextWindow?, val maxTokens?, val cost?...)
data class CustomProviderConfig(val id: String?, val baseUrl, val api, val apiKey?, val headers?, val models: List<CustomModelConfig>, val authHeader: String? = null)
object CustomModelsLoader { fun parse(text: String): List<CustomProviderConfig> }   // models.json 的 providers 段

// models/ModelRuntime.kt
class ModelRuntime(
    val models: Models,                     // pi-ai，由 createDefaultModels() + 自定义 provider 构建
    val credentialStore: CredentialStore,
    val env: Map<String, String> = emptyMap(),
) {
    fun getModels(): List<Model>
    fun getModel(provider: String, id: String): Model?
    fun getProvider(id: String): Provider?
    suspend fun getAvailableSnapshot(): List<Model>
    suspend fun resolveModel(spec: String): Model?
    suspend fun hasConfiguredAuth(providerId: String): Boolean
    suspend fun streamSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessageEventStream
    suspend fun completeSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessage
    fun registerProvider(provider: Provider)
    suspend fun refresh()
}

// models/ModelResolver.kt
class ModelResolver(private val runtime: ModelRuntime, private val defaultProvider: String?, private val defaultModel: String?, private val enabledModels: List<String>?) {
    suspend fun resolve(spec: String): ResolvedModel          // "provider/id"、"id"、模糊匹配、":thinking" 后缀
    suspend fun resolveDefault(): ResolvedModel?
}
data class ResolvedModel(val model: Model, val thinkingLevel: String?)
```
models.json 自定义 provider：`apiKey` 写进 provider auth；`authHeader` 等按上游 provider-composer。

## 4. tools（task-12 / task-13）

```kotlin
// tools/ToolSupport.kt
class CodingTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val label: String,
    val promptSnippet: String? = null,
    val promptGuidelines: List<String>? = null,
    val prepareArguments: ((JsonObject) -> JsonObject)? = null,
    val execute: suspend (toolCallId: String, args: JsonObject, signal: RunSignal?, onUpdate: AgentToolUpdateCallback?) -> AgentToolResult,
) {
    fun asAgentTool(): AgentTool
}
class ToolOutput(val content: String, val details: JsonValue? = null)
fun textResult(text: String, details: JsonValue? = null, isError: Boolean = false): AgentToolResult

// tools/PathUtils.kt
fun resolveToolPath(cwd: Path, input: String): Path     // 相对 cwd、展开 ~、规范化
// tools/Truncate.kt
data class Truncated(val text: String, val truncated: Boolean, val truncatedBy: String? = null)
fun truncateHead(text: String, maxLines: Int = 2000, maxBytes: Int = 50_000): Truncated
fun truncateTail(...); fun truncateLine(line: String, maxChars: Int): String
// tools/FileMutationQueue.kt
class FileMutationQueue { suspend fun <T> withFile(path: Path, block: suspend () -> T): T }
```

工具（行为与提示语按 `docs/analysis/pi-coding-agent.md` §6 与上游源码）：

- task-12：`read`、`write`、`edit`、`ls`（纯 Kotlin/okio；edit 返回 diff 摘要 + 首变更行；BOM/CRLF 归一化；write 建父目录；read 行/字节双截断、offset/limit、二进制与大文件处理）
- task-13：`grep`、`find`（**不依赖 rg/fd，纯 Kotlin 实现**：okio 递归遍历 + 可配置忽略目录、glob→Regex、正则/字面量/忽略大小写/上下文行/limit；输出格式、截断与提示语对齐上游文本）、`bash`（`ShellExecutor` 接口注入，默认实现不提供；没有 executor 时工具返回明确错误）
- `tools/CodingTools.kt`：`fun defaultCodingTools(cwd, fs, mutationQueue, shellExecutor?): List<CodingTool>`

## 5. prompt（task-14）

```kotlin
// prompt/SystemPrompt.kt
class SystemPromptSections(val sections: Map<String, String>)          // 顺序敏感：preamble,tools,rules,docs,addendum,project_context,skills,cwd
fun buildSystemPrompt(input: SystemPromptInput): SystemPromptSections
fun diffSystemPromptSections(previous: SystemPromptSections?, current: SystemPromptSections): JsonObject   // null 值=删除
class SystemPromptInput(val preamble: String? = null, val toolSnippets: List<String>, val toolGuidelines: List<String>, val docsPaths: List<String>, val addendum: String?, val projectInstructions: List<ContextFile>, val skills: List<Skill>, val cwd: Path)

// prompt/ResourceLoader.kt
class ResourceLoader(val paths: AgentPaths, val fs: FileSystem, val settings: SettingsManager) {
    suspend fun reload(): LoadedResources
}
data class LoadedResources(
    val systemPromptOverride: String?,
    val appendSystemPrompt: String?,
    val contextFiles: List<ContextFile>,
    val skills: List<Skill>,
    val promptTemplates: List<PromptTemplate>,
)
data class ContextFile(val path: Path, val content: String)
data class Skill(val name: String, val description: String, val filePath: String, val content: String)
data class PromptTemplate(val name: String, val description: String?, val content: String)
fun renderAvailableSkills(skills: List<Skill>): String     // <available_skills> XML，与上游逐字一致
class PromptTemplateExpander { fun expand(text: String): String }   // /template 展开（参数替换规则按上游）
```

默认提示词文本逐字移植上游 `core/system-prompt.ts` 与 `core/skills.ts` 的模板；上下文文件规则：`AGENTS.md`/`AGENTS.override.md`/`CLAUDE.md`、`SYSTEM.md`/`APPEND_SYSTEM.md`（formats §12）。

## AgentSession（lead 负责，其他人不要写）

`session/AgentSession.kt`、`AgentSdk.kt`（createAgentSession）由 lead 实现，依赖以上契约。

## 通用约束

- Kotlin 代码无注释；JSON 字段名 camelCase 与上游一致；错误文案与上游逐字一致（模型依赖）。
- 文件只写自己任务声明的范围；不改 build.gradle.kts、不改 pi-ai/pi-agent。
- 验证：`./gradlew :pi-coding-agent:jvmTest --no-daemon`（首次编译约 1 分钟）。不要并发跑全仓 build。
- 完成后 complete task + send_message 给 lead：文件清单、测试数、偏差、遗留。
