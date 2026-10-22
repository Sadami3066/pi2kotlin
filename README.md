# pi2kotlin

把 [pi](https://pi.dev)（pi coding agent）的核心包用 Kotlin 重写，产出一个用于构建**手机 agent 应用**的 Kotlin Multiplatform SDK。

上游源码：`/home/whywood/.codex/private-references/pi`（earendil-works/pi，v1.3.0）。

## 范围

| 模块 | 对应上游 | 状态 |
|---|---|---|
| `pi-ai` | `packages/ai`（unified LLM API，27.7k 行） | 类型/事件流/模型目录完成，协议实现进行中 |
| `pi-agent` | `packages/agent`（agent runtime，2.5k 行） | 规格已就绪，待实现 |
| `pi-coding-agent` | `packages/coding-agent`（core/SDK 层） | 待实现；不做 CLI/TUI |

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
