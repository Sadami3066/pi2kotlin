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

验收：pi-ai 250 JVM tests + 193 Android host tests、pi-agent 113 JVM tests + 113 Android host tests 全绿；`./gradlew clean build` 三模块双 target 全绿。

### M3 pi-ai 其余 provider

`openai-completions` 已覆盖 28 个 provider；其余 13 个 API 实现仍为占位。

`anthropic-messages`、`openai-responses`、`azure`、`google-generative-ai`、`google-vertex`、`mistral-conversations`、`bedrock-converse-stream`、`openai-codex-responses`、`pi-messages`、image/classifier API；9 个 OAuth flow（按移动端可用的方式实现：外部浏览器 + 回调/设备码）。

### M4 pi-agent（已完成）

AgentMessage/AgentEvent、双层 agent loop、并行/串行工具执行、hooks、队列/中断、transport/proxy。

验收：113 tests 全绿（B01–B13、B15–B50 均有直接断言，B14 已实现）；另有跨模块 e2e：Agent → pi-ai Models → faux provider 全链路测试。

### M5 pi-coding-agent SDK 层（主体已完成，收尾中）

已完成：
- `SessionManager`：会话 JSONL v1-v3 读写、延迟建文件、树/分支/fork、context_edit 投影、compaction 感知重建、统计、列表/恢复（55 tests）
- `SettingsManager` + `TrustManager` + `Migrations`：两级合并、脏字段写回、defaultTools 修饰、trust.json、commands→prompts 迁移（42 tests）
- `ModelRuntime` / `ProviderComposer` / `ModelResolver`：models.json 自定义 provider、apiKey 解析、模型解析与 thinking 后缀（47 tests）
- 工具：read/write/edit/ls + grep/find（纯 Kotlin 无 rg/fd 依赖）+ bash（注入 ShellExecutor）+ truncate/path/mutation queue（95 tests）
- 提示词：SystemPrompt sections/defaults/diff、ResourceLoader（AGENTS.md/SYSTEM.md/skills/prompts）、skills XML、prompt templates（62 tests）
- `AgentSession` + `createAgentSession` + `AgentSessionRuntime` + `CodingHooks`（lead 实现，SDK 公开 API）
- 端到端验证：Agent → StreamFn → ModelRuntime → faux provider；会话落盘/重开恢复（SdkIntegrationTest）

验收：三模块 `./gradlew clean build` 全绿；pi-coding-agent 304 JVM tests + 304 Android host tests。

已完成收尾项：
- compaction + branch summary 移植（`coding/compaction`，56 tests）；AgentSession 已接入：prompt 前自动压缩、上下文溢出的自动压缩恢复、`session.compact()` 公开 API
- session 级自动重试（指数退避、context_edit 省略失败轮、retry 事件）
- `UnixShellExecutor`（JVM/Android 共享源集，ProcessBuilder、超时/abort 杀进程树、尾部截断，13 tests）
- SDK 开发者文档 `docs/SDK.md`

已完成：
- `AgentSessionRuntime.fork(entryId, summarizeAbandonedBranch = true)` 接入 branch summary
- Maven 发布配置（`maven-publish`，`publishToMavenLocal` 产出 jvm jar/android aar/sources/pom，依赖正确）

已完成：
- 示例工程：`examples/jvm-demo`（Gradle application，`./gradlew :examples:jvm-demo:run` 端到端打印流式回复并落会话文件）、`examples/android-demo`（独立 Gradle 工程，消费 mavenLocal 发布的 android 变体，产出 debug APK）
- 根构建不再声明插件版本，各模块/示例自力声明版本（解决 AGP 9 application 与 KMP 的类路径冲突）；Android demo 使用经典 DSL + kotlin-android 2.4.21

剩余：
- 扩展 hook 细化（custom message 持久化、input 流程）

### M3 provider 矩阵（已完成）

**10 个 chat API 全部实现**：openai-completions、openai-responses、openai-codex-responses、azure-openai-responses、anthropic-messages、google-generative-ai、google-vertex、mistral-conversations、bedrock-converse-stream（AWS SigV4 + 二进制 event stream）、pi-messages（radius 网关）。

**图片/分类器**：openrouter-images；typesafe-system-one、openai-decisions、cloudflare-workers-ai-system-one；Models 层 `generateImages`/`classify` 分发。

**OAuth 9 个流程全部实现**：anthropic、openai-chatgpt、github-copilot、openai-codex、xai、openrouter、kimi-coding、meta、radius。基础设施（纯 Kotlin SHA-256/PKCE、设备码轮询、本地回调服务器、凭据刷新写回）已完成并接入 builtinProviders 认证链（OAuth → auth.json api key → env）。

模型目录 42 个 provider 全部可路由（typesafe 仅 classifier）。

已知偏差：codex 的 WebSocket 传输/zstd 压缩未实现（回退 SSE）；bedrock 凭据链仅 env/bearer（无 profile/IMDS/STS）；radius 动态目录仅留接口。

### M6 打磨与发布（已完成主体）

- Maven 发布配置（jvm jar / android aar / sources / pom），`publishToMavenLocal` 可用
- 示例工程：`examples/jvm-demo`（`./gradlew :examples:jvm-demo:run` 端到端跑通）、`examples/android-demo`（独立 Gradle 工程，消费发布产物，`scripts/build-android-demo.sh` 产出 debug APK）
- SDK 使用文档 `docs/SDK.md`
- 剩余可选：KDoc 全量、R8/体积与启动耗时测量、私有仓库发布

## 最终验收（review 修复轮，0.1.0）

针对外部 review 的 14 项 P1/P2 与完成度边界，本轮修复并验证：

| review 项 | 修复 | 验证 |
|---|---|---|
| P1.1 OAuth 刷新被绕过 | pi-ai 统一认证链 `ProviderAuthResolver`/`resolveAuthForProvider`：stored OAuth → 过期 refresh → `toAuth()`；coding `ModelRuntime` 不再预填 credential | pi-ai `ProviderAuthChainTest` 9 tests（refresh 请求/写回/headers）；coding `AuthScenarioTest` 2 tests（经 SDK 端到端） |
| P1.2 abort/waitForIdle/close 未覆盖整次操作 | 会话级 `RunSignal` 覆盖重试等待/压缩/摘要；`waitForIdle` 等待整个 prompt 操作；`close()` suspend 且等操作结束、先 shutdown hook 再退订/关会话；Runtime 提供 suspend `close()` | `SessionLifecycleScenarioTest` 3 tests（重试等待 abort 不发第二次请求、waitForIdle 覆盖重试与整次操作） |
| P1.3 溢出恢复缺 omitFailedTurn/次数边界 | 溢出分支先 `omitFailedTurn`（context_edit）+ 重建上下文，再自动压缩一次（split-turn 允许 history + turn-prefix 两次摘要），随后 continueRun | `OverflowRecoveryScenarioTest` 2 tests；不再出现 "Cannot continue from message role: assistant" |
| P1.4 Android 示例线程错误 | 示例：session/prompt 全部在 IO，UI 经 Channel→Main；onDestroy suspend close；新增离线 faux / 本地 mock 模式 | 真机（Xiaomi rubypro/Android 13）离线 + `adb reverse` mock + 销毁重开；logcat 0 崩溃/ANR/NetworkOnMain，StrictMode 无应用帧违规；证据 `docs/device-test/` |
| P1.5 自定义 provider 仅 openai-completions | pi-ai `chatApiFactory` 覆盖全部 10 种 chat API；`ProviderComposer` 接入 | `ProtocolFactoriesTest` 10 API；coding 自定义 provider 真实请求到 anthropic/openai-responses 端点 |
| P1.6 Cloudflare 未适配 | 新增 `CloudflareStream`/`CloudflareAuth`：URL 占位符替换、workers-ai Bearer、ai-gateway `cf-aig-authorization`（gateway token 优先） | `CloudflareProvidersTest` 7 tests（MockWebServer URL/headers） |
| P1.7 defaultTools 未生效 | `createAgentSession` 用 `settings.getDefaultTools()` 过滤默认工具集 | `SessionConfigScenarioTest`（defaultTools/加减修饰） |
| P1.8 静态 extra sections 首次 prompt 丢失 | `AgentSession` 保存 `staticSystemPromptSections` 并参与每次 sections 计算 | `SessionConfigScenarioTest` 首请求可见 |
| P2.9 系统消息未持久化 | 初始 system 快照与 patch 均落 JSONL；恢复会话时从历史 system 消息重放 sections 基线 | `SessionContextScenarioTest`（JSONL system 条目/重开一致） |
| P2.10 模型/thinking 语义丢失 | 显式 `options.model` 优先；`:thinking` 后缀与 `modelThinking`/默认 thinking 参与解析 | `SessionConfigScenarioTest` |
| P2.11 事件订阅可能丢事件 | `MutableSharedFlow(SUSPEND, buffer=256)` + suspend `emit`；`subscribe` 用 `UNDISPATCHED` 立即收集 | `SessionContextScenarioTest` 仅经 `session.events` 校验完整序列 |
| P2.12 grep/find 忽略语义 | 新增 `GitIgnoreMatcher`（嵌套/取反/目录/锚定/通配）；流式 limit 早停 + RunSignal 取消 | 44 tests（工具 + matcher + 早停/取消） |
| P2.13 校验子集 | 补齐数字/字符串/数组约束、本地 `$ref`/`$defs`、21 个 format；错误文本 TypeBox 1.3.27 golden 逐字对照 | ValidationTest 35 tests（60 golden 断言） |
| P2.14 PKCE 随机源 | `oauthRandomBytes` 默认 `platformSecureRandomBytes`（java.security.SecureRandom） | PkceTest + PlatformAbstractionTest |
| 平台泄漏 | 平台差异收敛 `dev.pi.ai.platform`（time/random/env/atomic）；commonMain 无 `java.*` import | grep 校验 + `PlatformAbstractionTest` |
| 发布不一致 | 版本统一 `0.1.0`；`jvmToolchain(17)` 产物 class major=61；双语 README、LICENSE、RELEASE 文档 | `scripts/verify-release.mjs` 32/32 PASS；`scripts/release-local.sh` 全流程通过 |

测试不足项同步补齐：固定 JSONL/协议 fixture（TS 格式手写，默认可运行）、真实数据扫描改 `PI2K_REAL_SESSIONS_DIR` opt-in、SDK 组合场景 18 tests。
