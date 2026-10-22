# 架构

## 定位

pi2kotlin 是一个用于构建**手机 agent 应用**的 Kotlin Multiplatform SDK：把 pi 的 `pi-ai`、`pi-agent`、`pi-coding-agent`（core/SDK 层）重写为 Kotlin 库，不做 CLI/TUI。

## 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 形态 | Kotlin Multiplatform 库 | Android 为主，JVM 用于测试/服务端；iOS 可后续加 target |
| targets | `androidTarget`（minSdk 26）+ `jvm`（JDK 17） | 手机 agent 应用 + JVM 测试/桌面复用；iOS 尚未接入（新增 target 需补 `dev.pi.ai.platform` actual 与平台 HTTP/文件实现） |
| 构建 | Gradle 9.8.2 + Kotlin 2.4.21 + AGP 9.x | 现有工程已就绪 |
| JSON | kotlinx.serialization | KMP 原生，sealed 判别键与上游 JSON 一致 |
| 并发/流式 | kotlinx.coroutines（Flow + Channel） | KMP 原生；对应上游 async iterator/Promise |
| 文件 | okio | KMP、Android 友好 |
| HTTP | 自定义 `HttpTransport` 接口 + OkHttp 实现 | 核心不绑定平台；实现可换 Ktor/Darwin |
| 测试 | kotlin.test + kotlinx-coroutines-test + MockWebServer | commonTest 可跑逻辑，平台实现各自测试 |

不引入上游 npm 依赖（openai/anthropic/google SDK、typebox、undici、jiti 等）；线上协议按上游源码手写。

## 模块

```
pi-ai/            LLM 统一 API：类型模型、事件流、模型目录、provider 注册、API 协议实现、认证
pi-agent/         Agent 运行时：Agent 类、agent loop、工具执行、事件、transport
pi-coding-agent/  Coding agent SDK：AgentSession、会话持久化、设置、模型解析、工具、系统提示词、压缩、扩展 hook
```

依赖方向：`pi-coding-agent` → `pi-agent` → `pi-ai`。

## KMP 源码布局（每个模块）

```
src/commonMain/kotlin/      纯 Kotlin 逻辑与类型（全部协议解析、agent 逻辑、校验、压缩…）
src/commonTest/kotlin/      共享测试
src/jvmMain/kotlin/         JVM 平台实现（OkHttp 传输、文件、凭据、dev.pi.ai.platform actual）
src/androidMain/kotlin/     Android 平台实现（同上）
```

平台边界收敛为 `dev.pi.ai.platform` 的 expect/actual（`platformCurrentTimeMillis`、`platformSecureRandomBytes`、`platformEnv`、`PlatformAtomicBoolean`，actual 已在 `pi-ai/src/jvmMain` 与 `pi-ai/src/androidMain`，pi-agent 经 `api(project(":pi-ai"))` 复用），以及接口注入（`HttpTransport`、`FileSystem`、`CredentialStore`、`ModelCatalogSource`）。构造 `PiClient`/`AgentSession` 时注入；默认工厂在平台源集里提供（Android 默认 OkHttp + 应用私有目录）。

当前 targets 仅 android + jvm。OS 相关差异只允许出现在 `dev.pi.ai.platform` 与各平台源集；`commonMain` 不使用 `java.*`/`javax.*`/`System.*`。耗时测量直接使用 `kotlin.time.TimeSource.Monotonic`，不进入平台抽象。新增 iOS target 时必须为上述 expect 补齐 actual（否则 commonMain 无法编译），并补平台 HTTP/文件实现；当前不声称已支持 iOS。

## 关键设计

### JSON 约定

- 字段名与上游内存/会话格式完全一致（camelCase）；`PiJson` 配置 `encodeDefaults=true`、`explicitNulls=false`、`ignoreUnknownKeys=true`：TS 的 `undefined` 不写、`null` 字段不落盘、未来新字段可读。
- 联合类型：`@Serializable` sealed interface + `@JsonClassDiscriminator`（`Message`→role、`Content`→type、`AssistantMessageEvent`→type）。
- 任意 JSON（工具参数、details、compat）用 `JsonObject`；wire DTO 与内存类型分离（snake_case 转换在 API 实现内部）。

### 事件流

`AssistantMessageEventStream` = `Channel(UNLIMITED)` 事件流 + `CompletableDeferred<AssistantMessage>` 结果 + `durationMs` 计时，对应上游 `EventStream`。`push` 非挂起；`done`/`error` 终止并完成结果。

对外 SDK 层不暴露 push 端，只暴露 `Flow<AgentEvent>` 与 `suspend fun prompt()`。

### 消息可变性

上游流式 partial 是共享的 live 对象（文本块随 delta 增长）。`AssistantMessage` 与内容块用可变字段，事件中的 `partial` 引用同一实例；coding-agent 依赖消息对象身份稳定（原地替换字段）。

### 取消

用显式 `RunSignal` 令牌（`abort()` + 回调 + `isAborted`）替代 `AbortSignal`，不用协程 cancel：上游 abort 是协作式的，取消协程会抛 `CancellationException` 并破坏"abort 后正常 resolve"的语义。

### 工具参数校验

pi-ai 提供 `validateToolArguments(tool, args)`：成功返回规范化参数，失败抛 `ToolArgumentValidationException`，错误文本与上游逐字一致（pi-agent 捕获后转成错误工具结果）。

## pi-ai 内部分层

```
types          数据模型 + 序列化
event-stream   AssistantMessageEventStream
models         模型目录 + Models 注册表 + provider 工厂 + cost
api/*          各线上协议实现（openai-completions、anthropic-messages、openai-responses、…）
auth           API key 解析、OAuth 流程、凭据存储（接口可注入）
http           HttpTransport 抽象 + SSE 解析 + retry
utils          partial JSON、transcript 规范化、JSON Schema 校验、usage/overflow
```

## 模型目录

1.3.0 目录（42 provider / 1,714 条）以构建期生成的资源内置，避免运行时网络；`ModelCatalogSource` 接口允许宿主提供自定义/远程目录（对应上游 models.store 与 pi.dev overlay，后置）。生成脚本 `scripts/vendor-model-data.sh` 从 npm tarball 提取并可复现。

## 移动端约束

- `commonMain` 禁止 `java.*`/`javax.*` import 与 `System.*`：墙钟用 `platformCurrentTimeMillis()`、耗时用 `kotlin.time.TimeSource.Monotonic`、随机数用 `platformSecureRandomBytes()`、环境变量用 `platformEnv()`、原子布尔用 `PlatformAtomicBoolean`。
- okio `FileSystem.SYSTEM` 属于 okio 跨平台 API（JVM/Android/Native 可用）；新增 JS/Wasm target 时需替换。
- 凭据存储：默认实现可注入；Android 上由宿主决定是否用 Keystore 加密。
- 存储根目录：构造时传入（Android 默认 app 私有目录），不写死 `~/.pi`。
- 依赖体积：只保留 coroutines/serialization/okio + 平台 HTTP 实现；不引入大 SDK。
- 工具：文件类工具在 app 沙箱内工作；`bash` 工具做成可注入/可禁用（移动端默认禁用）。

## 同构策略

每个 Kotlin 模块与上游文件/概念一一对应（同名概念、同事件序列、同 JSON 字段），上游 bugfix 可逐条对照移植，上游测试可逐条翻译。
