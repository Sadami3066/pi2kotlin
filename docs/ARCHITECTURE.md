# 架构

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/运行时 | Kotlin/JVM，JDK 21 | 上游是 Node/TS，Java 生态有成熟 HTTP/文件/进程能力；后续可评估 GraalVM native-image |
| 构建 | Gradle 9.8.2 + Kotlin 2.4.21 | 多模块、依赖管理、测试一体化 |
| JSON | kotlinx.serialization | 与 Kotlin 类型系统集成最好，支持 sealed class 多态 |
| 并发/流式 | kotlinx.coroutines（Flow + Channel） | 对应上游 async iterator + Promise 事件流模型 |
| HTTP | OkHttp 5 | JVM 上最成熟的 HTTP 客户端：SSE 流式读取、连接池、代理（HTTP_PROXY/HTTPS_PROXY）、超时与取消 |
| 测试 | JUnit 5 + kotlinx-coroutines-test | 对齐上游 vitest 用例 |

不引入上游的 npm 依赖（openai/anthropic/google SDK、typebox、undici 等），线上协议按上游源码手写，保证行为可逐条对照。

## 模块

```
pi-ai/            LLM 统一 API：类型模型、事件流、模型目录、provider 注册、各 API 协议实现、认证
pi-agent/         Agent 运行时：Agent 类、agent loop、工具执行、事件、transport
pi-coding-agent/  Coding agent CLI：参数解析、会话、设置、工具、提示词、模式（print/json/rpc/interactive）
```

依赖方向：`pi-coding-agent` → `pi-agent` → `pi-ai`。

## pi-ai 内部分层

```
types/          纯数据模型（Message/Content/Event/Model/Usage/Tool…）+ JSON 序列化
event-stream    AssistantMessageEventStream（Channel + Flow + result Deferred）
models          模型目录数据 + Models 注册表 + provider 工厂 + 解析
api/*           各线上协议实现（openai-completions、anthropic-messages、google-generative-ai、…）
auth            API key 解析、OAuth 流程、凭据存储、撤销/刷新
utils           retry、abort、SSE 解析、partial JSON、用量估算、transcript 规范化等
```

关键契约（来自上游 `types.ts`）：

- 所有 provider 实现统一输出 `AssistantMessageEvent` 流：`start` → 增量（text/thinking/toolcall）→ `done` | `error`；失败编码在流里，不直接抛异常（认证缺失除外，允许同步抛）。
- `AssistantMessageEventStream` 同时提供事件迭代与最终结果（`result()`），并对最终消息补 `durationMs`。
- 线上 API 的 camelCase/snake_case、tool call 参数增量拼接、usage/cost 折算必须逐字段对齐。

## 同构策略

每个 Kotlin 模块的公开 API 与上游一一对应（同名概念、同事件序列、同 JSON 字段），文件级对应关系记录在各模块 README 与分析文档里。这样上游的 bugfix/行为变更可以逐条对照移植，上游测试可以逐条翻译为 Kotlin 测试。

## 待定

- `pi-coding-agent` 的交互式 TUI：上游依赖 `pi-tui`（不在本次三包范围内）。可选方案：JLine3 自建最小 TUI / 先不做 interactive 只做 print/json/rpc。见 `docs/PLAN.md`。
- 扩展机制（上游用 jiti 直接加载 TypeScript 扩展）：JVM 上无法直接执行 TS，需要重新设计扩展 SPI 或嵌入 JS 引擎。
