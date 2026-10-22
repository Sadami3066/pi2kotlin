# 并行实现的接口契约

本文件冻结并行开发所依赖的公共签名。实现者不得修改其他任务的签名；需要变更时先通知 lead。

## 已有公共类型（pi-ai，`dev.pi.ai`）

```kotlin
val PiJson: Json

sealed interface Message            // SystemMessage/UserMessage/AssistantMessage/ToolResultMessage
sealed interface Content            // TextContent/ThinkingContent/ImageContent/ToolCall
sealed interface AssistantMessageEvent
sealed interface MessageContent     // PlainTextContent/BlockContent

data class Model(...)               // catalog 里的 chat 模型
data class Tool(name, description, parameters: JsonObject, constrainedSampling: JsonValue?)
class TranscriptContext(val messages: List<Message>)
class AssistantMessageEventStream   // push/end/events: Flow/result()
class RunSignal                     // abort()/isAborted/onAbort()

data class StreamOptions(...)       // apiKey/env/headers/onPayload/onResponse/temperature/maxTokens/transport/cacheRetention/sessionId...
data class SimpleStreamOptions(val stream: StreamOptions, val toolChoice, val reasoning, val deferred, val thinkingBudgets)
```

## task-4（HTTP/流式底层）暴露

```kotlin
// dev.pi.ai.http
class SseEvent(val event: String?, val data: String, val id: String?, val retry: Long?)
class SseParser(source: BufferedSource) { suspend fun next(): SseEvent? ; fun close() }

// dev.pi.ai.http（okhttpMain source set）
fun okHttpTransport(client: OkHttpClient = defaultClient()): HttpTransport

// dev.pi.ai.utils
object PartialJson {
    fun parse(text: String): JsonValue?              // 不完整返回已解析部分/null
    fun repair(text: String): String
    fun parseToolCallArguments(json: String?): ToolArguments   // arguments: JsonObject + argumentsError: String?
}
data class ToolArguments(val arguments: JsonObject, val argumentsError: String? = null)

object Retry {
    fun isRetryableAssistantError(message: AssistantMessage): Boolean
    fun retryDelayMs(baseDelayMs: Long, maxAgentDelayMs: Long, attempt: Int): Long
    suspend fun sleep(ms: Long, signal: RunSignal?)
}

object ProviderErrors {
    fun normalize(status: Int, headers: Map<String, String>, body: String): NormalizedProviderError
    fun format(error: NormalizedProviderError, prefix: String? = null): String
    fun truncate(text: String, maxChars: Int): String
}
```

`OkHttpTransport` 语义：SSE 不缓冲整包；HTTP 非 2xx 也返回 `HttpResponse`（由上层读 body 生成错误）；取消通过协程 cancel 抛出即可（上层捕获转 aborted）；代理从 `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` 环境变量读取（JVM），Android 用系统默认 ProxySelector。

## task-5（transcript/校验/估算）暴露

```kotlin
// dev.pi.ai.utils.Transcript（顶层函数避免与类名冲突）
fun createInitialSystemMessage(systemPrompt: String?, tools: List<Tool>?): SystemMessage?
fun normalizeContext(context: Context): TranscriptContext
fun getInitialSystemMessage(messages: List<Message>): SystemMessage?
fun withoutInitialSystemMessage(messages: List<Message>): List<Message>
fun getCurrentTools(messages: List<Message>): List<Tool>
fun getCurrentSystemMessage(messages: List<Message>): SystemMessage?
fun getCurrentSystemPrompt(messages: List<Message>): String
fun collapseSystemMessages(context: TranscriptContext): TranscriptContext
fun toToolDeclaration(tool: Tool): Tool
fun declarationsEqual(a: Tool, b: Tool): Boolean
data class ToolStateChanges(val added: List<Tool>, val removed: List<ToolReference>)
fun getToolStateChanges(previous: List<Tool>, current: List<Tool>): ToolStateChanges

// dev.pi.ai.utils.Text
fun contentText(content: MessageContent, separator: String = "\n"): String
fun getSystemMessageText(message: SystemMessage): String
fun renderSystemMessageUpdate(message: SystemMessage): String

// dev.pi.ai.utils.Validation
class ToolArgumentValidationException(message: String) : Exception(message)
fun validateToolArguments(tool: Tool, toolCall: ToolCall): JsonObject
fun validateToolCall(tools: List<Tool>, toolCall: ToolCall): JsonObject

// dev.pi.ai.utils.Estimate
fun calculateContextTokens(usage: Usage): Long
fun estimateTextTokens(text: String): Long
fun estimateMessageTokens(message: Message): Long
data class ContextUsageEstimate(val tokens: Long, val usageTokens: Long, val trailingTokens: Long, val lastUsageIndex: Int)
fun estimateContextTokens(messages: List<Message>): ContextUsageEstimate

// dev.pi.ai.utils.Overflow
fun isContextOverflow(message: AssistantMessage, contextWindow: Long? = null): Boolean
fun isRecoverableLength(message: AssistantMessage, desiredMaxOutput: Long): Boolean
```

校验器要求：完整移植上游 `utils/validation.ts` 的 JSON Schema 子集（type/properties/required/additionalProperties/items/enum/const/anyOf/oneOf/allOf/nullable/整数与数字强转），错误消息逐字对齐：`Validation failed for tool "x":\n  - /path: message\n\nReceived arguments:\n{...}`；`argumentsError` 优先：`Invalid arguments for tool "x": <argumentsError>`。

## task-6（openai-completions）暴露

```kotlin
// dev.pi.ai.api
fun openAICompletionsApi(): ApiProvider
// ApiProvider: val api: String; suspend fun stream(model, context: TranscriptContext, options: StreamOptions): AssistantMessageEventStream
//              suspend fun streamSimple(model, context, options: SimpleStreamOptions): AssistantMessageEventStream
```

完整移植 `src/api/openai-completions.ts` + `src/api/transform-messages.ts` + `src/api/simple-options.ts` + `src/api/openai-prompt-cache.ts` 中该 API 用到的部分，compat 字段按 `src/providers/compat-schema.ts` 的 `OpenAICompletionsCompat` 全量支持。HTTP 通过 `HttpTransport`（构造参数注入），SSE 用 `SseParser`，参数增量用 `PartialJson`，错误用 `ProviderErrors`。

## task-7（Models/Provider/认证/faux）暴露

```kotlin
// dev.pi.ai
interface ApiFactory { fun create(): ApiProvider }

class Provider(
    val id: String,
    val name: String,
    val baseUrl: String? = null,
    val headers: Map<String, String?>? = null,
    val apiProviders: Map<String, () -> ApiProvider>,
    val models: List<Model>,
    val allModels: List<AnyModel> = models,
    val auth: AuthResolver,
)

class Models(private val providers: MutableMap<String, Provider> = linkedMapOf()) {
    fun setProvider(provider: Provider)
    fun getProviders(): List<Provider>
    fun getProvider(id: String): Provider?
    fun getModels(provider: String? = null): List<Model>
    fun getModel(provider: String, id: String): Model?
    fun getModelsOfType(type: ModelType, provider: String? = null): List<AnyModel>
    suspend fun stream(model: Model, context: Context, options: StreamOptions? = null, signal: RunSignal? = null): AssistantMessageEventStream
    suspend fun complete(model: Model, context: Context, options: StreamOptions? = null, signal: RunSignal? = null): AssistantMessage
    suspend fun streamSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessageEventStream
    suspend fun completeSimple(model: Model, context: Context, options: SimpleStreamOptions? = null, signal: RunSignal? = null): AssistantMessage
}

fun createModels(): Models
fun builtinProviders(): List<Provider>
fun calculateCost(model: AnyModel, usage: Usage): UsageCost
fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel>
fun clampThinkingLevel(model: Model, level: ModelThinkingLevel): ModelThinkingLevel

// dev.pi.ai.auth
sealed interface AuthResolver { suspend fun resolve(overrides: AuthOverrides? = null): ResolvedAuth? }
data class ResolvedAuth(val apiKey: String?, val headers: Map<String, String> = emptyMap(), val source: String)
class EnvApiKeyAuth(val label: String, val envVars: List<String>, val inlineKey: String? = null) : AuthResolver
class StaticApiKeyAuth(val apiKey: String) : AuthResolver
class CredentialStore            // JSON 持久化，可注入路径；兼容 pi 的 auth.json 结构
```

`builtinProviders()` 从内置模型目录数据构建全部 42 个 provider：每个 provider 的 `models` 取其 chat 模型，`apiProviders` 按模型 `api` 字段分组；尚未实现的 api（除 openai-completions 外）用占位工厂（返回抛 `NotImplementedError` 的 ApiProvider）保证可编译；`auth` 用 `EnvApiKeyAuth`，env 变量名与上游 `src/providers/*.ts` 一致。

faux provider（`providers/Faux.kt`）：移植 `src/providers/faux.ts` 的脚本化响应/流式事件/工具调用能力，供测试使用。

## task-8（pi-agent）暴露

按 `docs/analysis/pi-agent.md` 落地 `dev.pi.agent`：`AgentMessage`/`AgentTool`/`AgentEvent`/`AgentContext`/`AgentState`/`AgentOptions`/`AgentLoopConfig`/`StreamFn`/`Agent`/`runAgentLoop`/`runAgentLoopContinue`/proxy。`StreamFn` 定义为：

```kotlin
typealias StreamFn = suspend (model: Model, context: TranscriptContext, options: SimpleStreamOptions?) -> AssistantMessageEventStream
```

pi-agent 只依赖 pi-ai 的公共类型与 `utils/` 中已冻结的函数；不要修改 pi-ai 模块。

## 通用约束

- Kotlin 代码不写注释；用命名和结构表达。
- 先读 `docs/analysis/` 对应章节（有逐行证据），再读上游 TS 实现。
- 文件写入范围严格按任务声明；不修改 `build.gradle.kts`、`gradle/libs.versions.toml`、`docs/`（除非任务明确）。
- 测试：`./gradlew :pi-ai:jvmTest`（只跑 JVM 快速测试）；提交前确保 `:pi-ai:allTests` 绿。
- 命名：包 `dev.pi.ai` / `dev.pi.agent` / `dev.pi.coding`；不用 `I` 前缀接口。
- 完成后 complete task 并给 lead 发消息：文件清单、测试结果、与契约的偏差、遗留项。
