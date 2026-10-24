# pi2kotlin SDK 使用指南

面向 Android / JVM 应用的编码 agent SDK：把 pi（TypeScript）的 `pi-ai`、`pi-agent`、`pi-coding-agent` 重写为 Kotlin Multiplatform 库。

## 依赖

```kotlin
dependencies {
    implementation("dev.pi:pi-coding-agent:0.2.1")
}
```

Android 使用 `dev.pi:pi-coding-agent-android:0.2.1`。产物暂未发布到 Maven Central，先执行 `./gradlew publishToMavenLocal`。

模块：`pi-ai`（LLM 统一层）→ `pi-agent`（agent 循环）→ `pi-coding-agent`（会话/工具/提示词/SDK 门面）。targets：Android（minSdk 26）+ JVM（Java 17 字节码）。

## 快速开始

```kotlin
val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = appPrivateDir.resolve("agent"),
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
    ),
)

val subscription = session.subscribe { event ->
    when (event) {
        is SessionEvent.AgentEventReceived -> when (val agentEvent = event.event) {
            is AgentEvent.MessageUpdate -> {
                val delta = agentEvent.assistantMessageEvent
                if (delta is TextDeltaEvent) ui.append(delta.delta)
            }
            else -> Unit
        }
        else -> Unit
    }
}

session.prompt("帮我看看这个目录里有什么")
println(session.getLastAssistantText())
subscription()
session.close()
```

## 会话

```kotlin
val session = createAgentSession(options)
session.prompt("...")                                   // 一次完整 run（含重试/工具调用）
session.steer("顺便看一下 README")                        // 流式期间插入，当前轮工具调用后生效
session.followUp("跑完再看一下测试")                       // 当前 run 结束后继续
session.abort()                                         // 协作式中止
session.waitForIdle()
session.setModel(model, thinkingLevel = "high")
session.setThinkingLevel("medium")
session.messages                                        // 当前 transcript（AgentMessage）
session.systemPrompt                                    // 当前生效的系统提示词
session.sessionFile                                     // 会话 JSONL 路径（可能尚未落盘）
session.close()
```

会话持久化为与 pi 兼容的 JSONL（`~/.pi` 结构），可直接被 pi 读取；函数支持恢复：

```kotlin
// 新建
val session = createAgentSession(options.copy(sessionFile = null))
// 恢复
val resumed = createAgentSession(options.copy(sessionFile = file))
// 会话运行时（自动重建会话/切换/分叉）
val runtime = AgentSessionRuntime.create(options)
runtime.newSession()
runtime.switchSession(file)
runtime.fork(entryId = null)
```

存储根目录完全由宿主决定（Android 用应用私有目录，JVM 用任意目录）。

## 模型与 Provider

- 内置 42 个 provider（OpenAI 兼容 28 个已可用；anthropic/responses/google 等逐步补齐），模型目录随包内置。
- 自定义 provider：`<agentDir>/models.json`（与 pi 格式兼容），或运行时注入：

```kotlin
AgentSessionOptions(
    ...,
    providers = listOf(myProvider),
    model = "my-provider/my-model",
)
```

- 认证：`<agentDir>/auth.json` 中读取 api key；也可注入 `CredentialStore`；OAuth 登录流程见后续版本。

## 工具

默认工具集：`read`、`write`、`edit`、`ls`、`grep`、`find`、`bash`（bash 需要注入 `ShellExecutor`，移动端默认不提供）。

自定义工具：

```kotlin
val myTool = CodingTool(
    name = "weather",
    description = "查询天气",
    parameters = buildJsonObject { put("type", "object") },
    label = "Weather",
    execute = { _, args, _, _ -> textResult("晴") },
)

AgentSessionOptions(..., tools = defaultCodingTools(...) + myTool)
```

## Hooks

```kotlin
CodingHooks(
    transformPrompt = { raw -> raw.trim() },
    extraSystemPromptSections = { mapOf("app_rules" to "<app_rules>...</app_rules>") },
    onSessionStart = { session -> /* ... */ },
    onSessionShutdown = { session -> /* ... */ },
)
```

## 平台接入

- HTTP：实现 `HttpTransport`（默认 `okHttpTransport()`）。
- 文件：okio `FileSystem`，可注入（默认 `FileSystem.SYSTEM`）。
- 命令执行：实现 `ShellExecutor` 并传给 `AgentSessionOptions.shellExecutor`。
- 凭据：实现自己的 `CredentialStore` 或使用默认文件实现。

## MCP

见 [MCP 接入指南](MCP.md)。

## 限制

- 不包含 CLI/交互式 TUI；UI 由宿主应用渲染事件流。
- 不支持加载 pi 的 TypeScript 扩展；扩展点以 Kotlin `CodingHooks` + 自定义 `CodingTool` 提供。
- 图片工具不做自动缩放/格式转换（移动端可用宿主图像库预处理）。
- `grep`/`find` 为纯 Kotlin 实现，不依赖 rg/fd。
