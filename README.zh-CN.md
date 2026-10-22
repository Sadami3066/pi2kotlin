# pi2kotlin

把 [pi](https://pi.dev)（`pi-ai`、`pi-agent`、`pi coding agent` 的 SDK 层）用 Kotlin 重写，产出一个用于构建 **Android / JVM AI agent 应用**的 Kotlin Multiplatform SDK。

[English](README.md)

状态：**0.1.0 alpha**。可编译、可发布 Android AAR 与 JVM JAR；JVM 测试 1,000+、Android host 测试数百；1.0 前 API 仍可能调整。

## 模块

| 模块 | 对应上游 | 内容 |
|---|---|---|
| `pi-ai` | `packages/ai` | 统一 LLM API：10 种 chat API、图片生成/分类器 API、42 个内置 provider、模型目录、认证（API key + 9 个 OAuth 流程）、流式事件、重试、usage/cost |
| `pi-agent` | `packages/agent` | Agent 运行时：事件生命周期、工具执行（串行/并行）、hooks、steering/follow-up 队列、proxy |
| `pi-coding-agent` | `packages/coding-agent` 的 core/SDK 层 | 会话（pi 兼容 JSONL）、设置、模型解析、内置工具、系统提示词与资源、压缩，以及 `AgentSession` / `createAgentSession` 门面 |

依赖方向：`pi-coding-agent` → `pi-agent` → `pi-ai`。

## 环境要求

- JDK 17+（发布产物为 Java 17 字节码）
- Android：`minSdk 26`、`compileSdk 36`
- 从源码构建：Gradle 9.8.2（自带 wrapper）+ Kotlin 2.4.21

## 安装

暂未发布到 Maven Central，先发布到本地：

```bash
./gradlew publishToMavenLocal
```

按需依赖：

```kotlin
// JVM
implementation("dev.pi:pi-coding-agent:0.1.0")

// Android
implementation("dev.pi:pi-coding-agent-android:0.1.0")
```

Android 工程还需要 `android.useAndroidX=true`。

## 快速开始

```kotlin
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.http.okHttpTransport
import dev.pi.agent.AgentEvent
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.SessionEvent
import okio.Path.Companion.toPath

val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = appPrivateDir.resolve("agent").absolutePath.toPath(),
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
    ),
)

val subscription = session.subscribe { event ->
    if (event is SessionEvent.AgentEventReceived) {
        val agentEvent = event.event
        if (agentEvent is AgentEvent.MessageUpdate) {
            val update = agentEvent.assistantMessageEvent
            if (update is TextDeltaEvent) print(update.delta)
        }
    }
}

session.prompt("总结一下这个目录里的文件")
println(session.getLastAssistantText())
subscription()
session.close()
```

`createAgentSession` 会从 `cwd` 与 `agentDir` 解析设置、凭据、模型目录与资源（上下文文件、skills、prompt templates）。

## 会话

- 会话持久化为 pi 兼容的 JSONL（与 `~/.pi/agent/sessions` 相同格式），包含系统提示词快照与压缩条目
- 传入 `sessionFile` 即可恢复；`session.prompt()` 在恢复的 transcript 上继续
- `AgentSessionRuntime` 提供 `newSession()` / `switchSession()` / `fork()`（fork 可为被放弃分支生成摘要）
- Android 上建议把 agent 目录放在应用私有目录

## 模型与 provider

- 内置 42 个 provider、1,714 条目录模型（chat/image/classifier）、10 种 chat API、9 个 OAuth 流程（Anthropic、OpenAI ChatGPT、GitHub Copilot、OpenAI Codex、xAI、OpenRouter、Kimi、Meta、Radius）
- API key 读取顺序：`<agentDir>/auth.json`（与 pi 兼容）→ 自定义 `CredentialStore` → 环境变量
- 自定义 provider：`<agentDir>/models.json`，或运行时通过 `AgentSessionOptions.providers` 注入

## 工具

内置：`read`、`write`、`edit`、`ls`、`grep`、`find`、`bash`。

- `bash` 需要 `ShellExecutor`；Android/JVM 可用 `UnixShellExecutor`（传 `null` 即禁用 shell）
- `grep` / `find` 为纯 Kotlin 实现，遵守 `.gitignore` / `.ignore` / `.fdignore`
- 自定义工具实现 `CodingTool`，通过 `AgentSessionOptions.tools` 传入

## Hooks

`CodingHooks` 支持输入拦截（`onInput`）、提示词改写、额外系统提示词 sections、会话启动/关闭回调。

## 示例

```bash
./gradlew :examples:jvm-demo:run          # 离线端到端演示（faux provider）
./scripts/build-android-demo.sh           # 发布到 ~/.m2 并构建 demo APK
```

Android demo 位于 `examples/android-demo`（独立 Gradle 工程），内置离线演示模式，无需 API key。

## 构建与测试

```bash
./gradlew build                           # 编译全部 target 并跑测试
./gradlew :pi-ai:jvmTest                  # 只跑 JVM 快速测试
./gradlew publishToMavenLocal             # 发布 dev.pi:* 到本地
node scripts/verify-release.mjs 0.1.0     # 校验发布产物（Java 17 字节码/AAR/POM）
./scripts/release-local.sh 0.1.0          # clean build + 测试 + 本地发布 + demo + 校验和
```

低内存机器用 `./scripts/gw.sh <tasks>` 替代 `./gradlew`（串行化构建）。

## 已知限制

- 不含 CLI 与终端 UI：SDK 只暴露事件与 `AgentSession`，界面由宿主应用实现
- 不兼容 TypeScript 扩展；扩展点使用 Kotlin 工具与 hooks
- OpenAI Codex 传输仅 SSE（WebSocket/zstd 未实现）
- Amazon Bedrock 凭据仅支持环境变量或 bearer token（无 profile/IMDS/STS 链）
- Radius 动态模型目录只有占位接口，支持静态 gateway URL
- 图片不做缩放/格式转换
- JSON Schema 校验覆盖常用约束关键字与本地 `$ref`/`$defs`；`format` 为子集
- 未实现：MCP client、codemode 沙箱、虚拟模型、deferred responses、telemetry

## 许可证

MIT。本项目是 [pi](https://github.com/earendil-works/pi)（MIT）的 Kotlin 重写，上游版权与许可保留在 `LICENSE` 中。
