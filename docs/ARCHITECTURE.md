# 架构

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 语言/运行时 | Kotlin/JVM，JDK 21 | 上游是 Node/TS；JVM 有成熟的 HTTP/文件/进程/终端能力；后续可评估 GraalVM native |
| 构建 | Gradle 9.8.2 + Kotlin 2.4.21 | 多模块、依赖管理、测试一体化 |
| JSON | kotlinx.serialization | sealed class 判别键（type/role）与上游 JSON 形状一致 |
| 并发/流式 | kotlinx.coroutines（Flow + Channel） | 对应上游 async iterator + Promise 模型 |
| HTTP | OkHttp 5 | SSE 流式读取、连接池、代理、超时、取消 |
| 测试 | JUnit 5 + kotlinx-coroutines-test | 对齐上游 vitest 用例 |

不引入上游的 npm 依赖（openai/anthropic/google SDK、typebox、undici 等）；线上协议按上游源码手写，保证行为可逐条对照。

## 模块

```
pi-ai/            LLM 统一 API：类型模型、事件流、模型目录、provider 注册、各 API 协议实现、认证
pi-agent/         Agent 运行时：Agent 类、agent loop、工具执行、事件、transport
pi-coding-agent/  Coding agent CLI：参数解析、会话、设置、工具、提示词、模式（print/json/rpc/interactive）
```

依赖方向：`pi-coding-agent` → `pi-agent` → `pi-ai`。

## 关键设计

### JSON 约定

- 字段名与上游内存/会话格式完全一致（camelCase），`PiJson` 配置为 `encodeDefaults=true`、`explicitNulls=false`、`ignoreUnknownKeys=true`：TS 的 `undefined` 不写，`null` 字段不落盘，未来新字段可读。
- 联合类型用 `@Serializable` sealed interface + `@JsonClassDiscriminator`：`Message`（role）、`Content`（type）、`AssistantMessageEvent`（type）。
- `MessageContent`（string | 内容块数组）、`arguments`/`details` 等任意 JSON 用自定义序列化器或 `JsonObject`。

### 事件流

`AssistantMessageEventStream` = `Channel(UNLIMITED)` 事件流 + `CompletableDeferred<AssistantMessage>` 结果 + `durationMs` 计时，对应上游 `EventStream`/`AssistantMessageEventStream`。`push` 非挂起；`done`/`error` 终止流并完成结果。

### 消息可变性

上游流式 partial 是共享的 live 对象（文本块随 delta 增长）。`AssistantMessage` 与内容块用可变字段，事件中的 `partial` 引用同一实例；`message_end` 交付最终对象。pi-agent 依赖消息对象身份稳定（coding-agent 会原地替换字段）。

### 取消

pi-ai/pi-agent 用显式 `RunSignal` 令牌（`isAborted` + 回调）替代 `AbortSignal`，不用协程 cancel：上游 cancel 是协作式的，协程取消会抛 `CancellationException`，破坏"abort 后继续跑完当前请求并正常 resolve"的语义。

### 工具参数校验

pi-ai 提供 `validateToolArguments(tool, args)`：成功返回规范化参数，失败抛 `ToolArgumentValidationException`，错误文本与上游逐字一致（pi-agent 捕获后转成错误工具结果）。

## pi-ai 内部分层

```
types          纯数据模型（Message/Content/Event/Model/Usage/Tool…）+ 序列化
event-stream   AssistantMessageEventStream
models         模型目录数据 + Models 注册表 + provider 工厂 + 解析 + cost
api/*          各线上协议实现（openai-completions、anthropic-messages、openai-responses、google…）
auth           API key 解析、OAuth 流程、凭据存储
utils          SSE、partial JSON、retry、abort、transcript 规范化、JSON Schema 校验
```

## 模型数据

1.3.0 目录（42 provider / 1,714 条 chat/image/classifier）以 JSON 资源内置（`pi-ai/src/main/resources/models/`），由 `scripts/vendor-model-data.sh` 从 npm tarball 提取并可复现；加载器校验 manifest schemaVersion。运行期拉取 pi.dev 目录叠层后置。

## coding-agent 范围

见 `docs/PLAN.md`：先做内核（无 TUI、无 TS 扩展），交互与生态待定。

## 同构策略

每个 Kotlin 模块与上游文件/概念一一对应（同名概念、同事件序列、同 JSON 字段），上游 bugfix 可以逐条对照移植，上游测试可以逐条翻译为 Kotlin 测试。
