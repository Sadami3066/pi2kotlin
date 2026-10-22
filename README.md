# pi2kotlin

把 [pi](https://pi.dev)（pi coding agent）的核心包用 Kotlin 重写，产出一个用于构建**手机 agent 应用**的 Kotlin Multiplatform SDK。

上游源码：`/home/whywood/.codex/private-references/pi`（earendil-works/pi，v1.3.0）。

## 范围

| 模块 | 对应上游 | 状态 |
|---|---|---|
| `pi-ai` | `packages/ai`（unified LLM API，27.7k 行） | 核心完成：类型/事件流/HTTP/SSE/transcript/校验/Models/openai-completions/42 provider/认证 |
| `pi-agent` | `packages/agent`（agent runtime，2.5k 行） | 完成：事件模型/agent loop/工具执行/Agent 类/proxy |
| `pi-coding-agent` | `packages/coding-agent`（core/SDK 层） | 下一步：会话/设置/工具/系统提示词/压缩/扩展 hook |

## 形态

- Kotlin Multiplatform（`android` + `jvm`），核心逻辑在 `commonMain`
- 只做 SDK 库，不做 CLI/交互式界面
- 全量移植 pi-ai 的 42 个 provider

## 构建

```bash
./gradlew build
./gradlew test
```

需要 JDK 21。Gradle 9.8.2（wrapper 自带）。

## 文档

- `docs/ARCHITECTURE.md`：技术选型与架构决策
- `docs/PLAN.md`：里程碑与当前进度
- `docs/analysis/`：上游源码分析（4,400+ 行，移植依据）
