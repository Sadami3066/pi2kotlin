# 计划

## 目标

用 Kotlin 重写 pi 的三个包：

1. `pi-ai`（上游 `packages/ai`，27,709 行 TS）
2. `pi-agent`（上游 `packages/agent`，2,539 行 TS）
3. `pi-coding-agent`（上游 `packages/coding-agent`，76,051 行 TS）——**范围待用户确认**

详细源码分析见 `docs/analysis/`（4,479 行，含逐字段类型规格、事件协议、50 条行为保真清单、文件/协议格式、范围与工作量评估）。

## coding-agent 的范围切分

上游 coding-agent 不是一个 agent，而是 agent 之上的产品层。按可移植性切成三块：

| 块 | 内容 | 上游规模 | Kotlin 估算 | 建议 |
|---|---|---|---|---|
| 内核 | CLI、会话（SessionManager/AgentSession）、设置、认证、模型解析、工具（read/bash/edit/write/find/grep/ls）、系统提示词/skills/prompt templates、压缩、print/json 模式 | 约 15–20k 行 | 15–19k 行 | MUST，先做 |
| 交互 | 交互式 TUI（依赖 pi-tui，不在三包范围内） | 21,882 行 | +4–9k 行（JLine3 最小集） | SHOULD，第二期 |
| 生态 | 扩展体系（jiti 加载 TS）、MCP、package manager | 12,620 行 | +3–10k 行 | DEFER，需要战略决策 |

推荐先交付"内核"，它以 print/json/rpc 三种模式运行，协议可机器验收，且能与现有 pi 共存；交互与生态在拿到用户定位后再定。

## 里程碑

### M0 基础设施（已完成）

- Gradle 9.8.2 + Kotlin 2.4.21 + JDK 21 多模块工程，`./gradlew build` 全绿
- pi-ai 基础类型：Message/Content/Usage/Model/Tool/事件联合类型 + JSON 序列化（字段名与 pi 会话格式一致）
- `AssistantMessageEventStream`（Channel + Flow + result Deferred + durationMs 计时）
- 1.3.0 模型目录数据落地（42 provider / 1,714 条，`scripts/vendor-model-data.sh` 可复现），加载器 + 测试
- 源码分析文档：`docs/analysis/pi-ai.md`、`pi-ai-types.md`、`pi-agent.md`、`pi-coding-agent.md`、`pi-coding-agent-formats.md`

### M1 pi-ai 核心（进行中）

1. HTTP/SSE 层（OkHttp）：流式读取、取消、代理、超时
2. partial JSON 增量解析（tool call 参数）
3. transcript 规范化：normalizeContext、system 消息重放、工具声明重放、孤儿工具调用、图片降级
4. 工具参数校验（JSON Schema）：`validateToolArguments` 抛出与上游一致的错误文本，供 pi-agent 捕获
5. retry/abort/错误规范化/context overflow 判定（错误文本与上游逐字兼容）
6. `Models`/`Provider`/`createProvider` 注册表 + api key 认证（env、auth.json 兼容）+ `calculateCost`/thinking level 解析
7. `openai-completions` API 实现（覆盖 DeepSeek/OpenAI/xAI/Groq/OpenRouter 等 28 个 provider）
8. faux provider + MockWebServer fixture 测试；真实 DeepSeek key smoke

验收：mock server 上 text/thinking/tool call/usage/error/abort 全事件序列与上游 fixture 一致；真实 DeepSeek 请求跑通。

### M2 pi-ai 第二批

- `anthropic-messages`（SSE 手写解析、thinking/signature、prompt cache、strict tool 降级）
- `openai-responses`（含 shared 转换层）
- google-generative-ai；azure；mistral；其余 provider 按需

### M3 pi-agent

- `AgentMessage`（pi-ai 下沉基础接口 + 自定义消息扩展）、`AgentTool`、`AgentEvent`、hooks
- `agent-loop`：双层循环、串行/并行工具执行、preflight、终止/截断/abort、prepareRequest/prepareNextTurn/finishTurn、steering/follow-up 队列
- `Agent`：状态、运行生命周期、interrupt/steer、订阅结算
- transport/proxy
- 验收：移植上游 92 个测试用例（docs/analysis/pi-agent.md 第 9 节）+ B01–B50 行为清单逐条断言

### M4 pi-coding-agent 内核

- CLI/args/exit code/stdout 契约、`@file`、stdin
- 会话：JSONL v1–v3 读写、树、fork/clone/continue、`--session` 互操作
- 设置/auth/models.json/trust、模型解析规则
- 工具：read/bash/edit/write/find/grep/ls + truncate + diff + 进程树 kill
- 系统提示词 sections、AGENTS.md/PROJECT.md、skills、prompt templates
- compaction + branch summary
- print/json/rpc 三种模式
- 验收：与真 pi 黄金对照（事件序列 diff、会话互操作、工具输出字符串级一致）

### M5+ 待定

- 最小交互式 TUI（JLine3）
- 扩展机制策略（Kotlin SPI 或 Node 侧车）
- MCP、HTML 导出、images/classifier 等

## 技术决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 平台 | JVM 21 起步，后续评估 GraalVM native | 先保正确性与开发效率；单文件分发后置 |
| JSON | kotlinx.serialization，`encodeDefaults=true` + `explicitNulls=false` + `ignoreUnknownKeys=true` | 与 TS 的 "undefined 不写、字段名 camelCase" 一致；兼容读取未来字段 |
| 联合类型 | sealed interface + `@JsonClassDiscriminator` | 判别键与上游一致（type/role） |
| 事件流 | Channel(UNLIMITED) + Flow + CompletableDeferred | 对应上游 async iterator + result promise；push 非挂起 |
| 消息可变性 | AssistantMessage/Content 块可变，流式共享同一实例 | 上游 partial 是"共享的 live 对象"，订阅者读到持续增长的内容 |
| 取消 | 显式 RunSignal 令牌（不依赖协程 cancel） | 上游 AbortSignal 是协作式，cancel 会抛 CancellationException 破坏行为 |
| HTTP | OkHttp 5 | SSE、代理、超时、取消齐全 |
| 工具校验契约 | pi-ai 提供抛异常的 `validateToolArguments`，错误文本与上游一致 | pi-agent 的 catch 语义需要异常/错误文本（见 pi-agent.md 8.10） |
| 模型数据 | 内置 pi-ai 1.3.0 目录快照（构建期从 npm tarball 提取） | 离线优先；运行期可选拉 pi.dev overlay（后置） |
| 数据兼容 | 目标双向兼容 `~/.pi/agent` 现有 sessions/settings/auth | 用户已有数据与工作流不丢 |

## 验收原则

1. 每个包的上游测试逐条移植（不追求覆盖率，只移植行为断言）。
2. 事件序列用固定 fixture 做黄金对照；条件允许时与真 pi 同请求 diff。
3. 会话/设置/认证文件双向互操作测试（真 pi 写、Kotlin 读，反之亦然）。
4. 错误文本、工具输出提示语与上游字符串级一致（上层正则依赖它们）。

## 待确认（用户）

1. Kotlin 版定位：替代日常 pi（必须解决扩展/生态）还是内核/实验/未来底座（可长期共存）？
2. Provider 范围：先 OpenAI 兼容 + Anthropic（覆盖当前在用 DeepSeek）即可，还是要求 Google/Vertex/Bedrock/OAuth 全套？
3. 交互界面：接受"先无 TUI（print/json/rpc）"，还是必须第一期就有可交互终端？
4. 扩展策略：接受 Kotlin 版没有 npm 扩展生态，还是必须桥接 Node？
5. 分发：`java -jar` 可接受，还是要求单文件可执行？
