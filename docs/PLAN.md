# 计划

## 定位（2026-10-22 与用户确认）

Kotlin 版 pi 是一个**现代化、轻量的 SDK**，用于开发者构建**手机 agent 应用**。

由此确定的三条边界：

1. **只做库，不做 CLI**：不移植 `pi-coding-agent` 的 CLI/参数解析、interactive TUI、print/json/rpc 运行模式；只移植其 core/SDK 层（`AgentSession`/`SessionManager`/工具/设置/认证/模型解析/压缩/扩展 hook）。
2. **全量 42 个 provider**：pi-ai 的 provider 矩阵完整移植，不做裁剪。
3. **移动端优先**：Kotlin Multiplatform（`android` + `jvm` 两个 target；iOS 目标后续在 macOS 上可加），核心逻辑放 `commonMain`，不依赖 JVM 专有 API；HTTP、文件、凭据存储等平台能力通过可替换实现注入。

## 目标

| 模块 | 对应上游 | 内容 |
|---|---|---|
| `pi-ai` | `packages/ai`（27,709 行 TS） | 类型/事件协议、10 个 chat API 实现、42 provider、认证/OAuth、模型目录、图片/分类器、usage/cost |
| `pi-agent` | `packages/agent`（2,539 行 TS） | Agent 类、agent loop、工具执行、事件、transport/proxy |
| `pi-coding-agent` | `packages/coding-agent`（76,051 行 TS） | 仅 core/SDK：会话编排、会话持久化、设置、认证、模型解析、工具、系统提示词/资源、压缩、扩展 hook |

源码分析见 `docs/analysis/`（4,400+ 行，含逐字段类型规格、事件协议、50 条行为保真清单、文件/协议格式）。

## SDK 设计原则

- **轻量**：不引入 Node/Bun、无 CLI、无 TUI、无进程内 TS 扩展；核心依赖只有 coroutines/serialization/okio，HTTP 实现可替换。
- **现代 API**：`suspend` 函数 + `Flow` 事件流 + `close()`/结构化并发；不可变配置 + 显式生命周期；无全局单例。
- **可嵌入**：存储根目录、HTTP 传输、凭据存储、工具集、系统提示词、扩展 hook 全部可注入；Android 上默认使用应用私有目录。
- **平台无关核心**：`commonMain` 只用 multiplatform API；`android`/`jvm` 各自提供 OkHttp 传输与文件实现。
- **行为对齐上游**：事件序列、JSON 字段、错误文本、会话格式与 pi 保持一致（可对照、可互操作）。

## 里程碑

### M0 基础设施与调研（已完成）

- Gradle + Kotlin/JVM 多模块工程、`./gradlew build` 全绿
- pi-ai 基础类型（Message/Content/Usage/Model/Tool/事件）+ 序列化 + 事件流
- 1.3.0 模型目录数据（42 provider / 1,714 条）+ 加载器 + 测试
- 三包源码分析文档（pi-ai / pi-agent / pi-coding-agent + 格式规格）

### M1 工程改造为 KMP SDK（已完成）

- Kotlin Multiplatform：`commonMain` + `jvm` + `android`（AGP 9.4.1、Kotlin 2.4.21、minSdk 26），`okhttpMain` 共享源集
- 模型目录改为一处 gzip 资源内嵌（约 68KB 源码、50KB 二进制），`scripts/vendor-model-data.sh` + `generate-catalog.py` 可复现
- 验收：`./gradlew build` 全绿；jvmTest 与 testAndroidHostTest 都跑通

### M2 pi-ai 核心协议（已完成）

1. SSE 解析、partial JSON 增量解析
2. transcript 规范化：normalizeContext、system/工具声明重放、孤儿工具调用、图片降级
3. JSON Schema 工具参数校验（错误文本与上游一致）
4. retry/abort/错误规范化/context overflow 判定
5. `Models`/`Provider` 注册表 + api key 认证 + `calculateCost`/thinking level + 42 个内置 provider
6. `openai-completions`（28 个 provider 可用；compat 28 字段全支持）
7. faux provider + MockWebServer fixture 测试

验收：`:pi-ai:jvmTest` 250 tests + `:pi-ai:testAndroidHostTest` 全绿；`./gradlew build --rerun-tasks` 三模块双 target 全绿。

### M3 pi-ai 其余 provider

`openai-completions` 已覆盖 28 个 provider；其余 13 个 API 实现仍为占位。

`anthropic-messages`、`openai-responses`、`azure`、`google-generative-ai`、`google-vertex`、`mistral-conversations`、`bedrock-converse-stream`、`openai-codex-responses`、`pi-messages`、image/classifier API；9 个 OAuth flow（按移动端可用的方式实现：外部浏览器 + 回调/设备码）。

### M4 pi-agent（已完成）

AgentMessage/AgentEvent、双层 agent loop、并行/串行工具执行、hooks、队列/中断、transport/proxy。

验收：`:pi-agent:jvmTest` 112 tests 全绿（B01–B13、B15–B50 均有直接断言，B14 已实现）。

### M5 pi-coding-agent SDK 层（下一步）

- `AgentSession`：prompt/steer/followUp/abort/订阅/状态
- `SessionManager`：JSONL 持久化、树/分支/fork、in-memory 模式、可配置存储根
- `SettingsManager`、`AuthStorage`（Android 可接 Keystore）、`ModelResolver`/`ModelRuntime`
- 工具：read/write/edit/grep/find/ls（文件类，Android 沙箱可用）+ bash（可注入/可禁用）
- 系统提示词/skills/prompt templates/context 文件、compaction
- 扩展 hook：Kotlin 接口（工具注册、命令、输入/上下文/工具调用前后 hook），ServiceLoader 或显式注册

验收：以 `examples/sdk` 的等价 Kotlin 用例驱动，完成多轮工具调用 + 会话持久化/恢复 + 模型切换。

### M6 打磨与发布

- API 稳定性检查、KDoc、示例工程（Android app + JVM demo）
- Maven 发布配置（本地/私有仓库）
- R8/体积检查、启动耗时

## 技术决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 形态 | SDK 库，无 CLI/TUI | 用户定位：手机 agent 应用底座 |
| 平台 | KMP：`android` + `jvm`，iOS 预留 | 移动端优先，同时保证可在 JVM 上跑测试/服务端 |
| JSON | kotlinx.serialization；`encodeDefaults=true` + `explicitNulls=false` + `ignoreUnknownKeys=true` | 与 TS "undefined 不落盘、camelCase" 一致 |
| 联合类型 | sealed interface + `@JsonClassDiscriminator` | 判别键 type/role 与上游一致 |
| 事件流 | `Flow` + `Channel` + `CompletableDeferred` | 对应上游 async iterator + result |
| 消息可变性 | AssistantMessage/内容块可变，流式共享实例 | 上游 partial 是共享 live 对象 |
| 取消 | 显式 RunSignal 令牌，不用协程 cancel | 上游 abort 是协作式，cancel 会抛异常 |
| HTTP | 传输接口 + OkHttp 实现（android/jvm） | KMP 下可替换；后续可加 Darwin/URLSession 实现 |
| 文件 | okio | multiplatform、稳定、Android 友好 |
| 模型数据 | 内置 1.3.0 目录快照，构建期生成，可替换 | 离线优先、无网络依赖 |
| 扩展 | Kotlin hook SPI | 移动端不引入 JS/TS 运行时 |
| 分发 | Maven 依赖（`java -jar` 不适用） | SDK 形态 |

## 验收原则

1. 上游测试逐条移植（行为断言，不追覆盖率）。
2. 事件序列固定 fixture 黄金对照；条件允许时与真 pi 同请求 diff。
3. 会话/设置/认证文件格式与 pi 一致（复用现有数据；移动端单独根目录）。
4. 错误文本、工具输出提示语与上游字符串级一致（上层逻辑依赖）。
5. 每个里程碑必须在 `jvm` 与 `android` target 上编译通过、测试通过。
