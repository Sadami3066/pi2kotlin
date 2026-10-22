# pi2kotlin

把 [pi](https://pi.dev)（pi coding agent）的核心包用 Kotlin 重写。

上游源码：`/home/whywood/.codex/private-references/pi`（earendil-works/pi，v1.3.0）。

## 范围

| 模块 | 对应上游 | 状态 |
|---|---|---|
| `pi-ai` | `packages/ai`（unified LLM API，27.7k 行） | 基础设施完成，协议实现进行中 |
| `pi-agent` | `packages/agent`（agent runtime，2.5k 行） | 规格已就绪，待实现 |
| `pi-coding-agent` | `packages/coding-agent`（coding agent CLI，76k 行） | 范围待确认，推荐先做内核 |

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
