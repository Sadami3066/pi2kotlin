# pi2kotlin

把 [pi](https://pi.dev)（pi coding agent）的核心包用 Kotlin 重写，产出一个用于构建**手机 agent 应用**的 Kotlin Multiplatform SDK。

上游源码：`/home/whywood/.codex/private-references/pi`（earendil-works/pi，v1.3.0）。

## 范围

| 模块 | 对应上游 | 状态 |
|---|---|---|
| `pi-ai` | `packages/ai`（unified LLM API，27.7k 行） | 完成：10 个 chat API、图片/分类器 API、42 provider、9 个 OAuth 流程 |
| `pi-agent` | `packages/agent`（agent runtime，2.5k 行） | 完成：事件模型/agent loop/工具执行/Agent 类/proxy |
| `pi-coding-agent` | `packages/coding-agent`（core/SDK 层） | 主体完成：SessionManager/Settings/模型/工具/提示词/AgentSession + createAgentSession SDK |

## 形态

- Kotlin Multiplatform（`android` + `jvm`），核心逻辑在 `commonMain`
- 只做 SDK 库，不做 CLI/交互式界面
- 全量移植 pi-ai 的 42 个 provider

## 快速上手（SDK）

```kotlin
val session = createAgentSession(
    AgentSessionOptions(
        cwd = "/path/to/workspace".toPath(),
        agentDir = "/path/to/agent-dir".toPath(),
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
    ),
)
try {
    session.subscribe { event -> /* 渲染流式事件 */ }
    session.prompt("帮我看看这个目录里有什么")
    println(session.getLastAssistantText())
} finally {
    session.close()
}
```

## 构建

```bash
./gradlew build
./gradlew test
./gradlew publishToMavenLocal   # 发布 dev.pi:pi-ai / pi-agent / pi-coding-agent 到 ~/.m2（jvm + android 变体）
```

需要 JDK 21。Gradle 9.8.2（wrapper 自带）。

## 文档

- `docs/ARCHITECTURE.md`：技术选型与架构决策
- `docs/PLAN.md`：里程碑与当前进度
- `docs/analysis/`：上游源码分析（4,400+ 行，移植依据）
