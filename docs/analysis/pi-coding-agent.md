# pi-coding-agent 源码分析与 Kotlin 重写范围建议

分析对象：`upstream pi checkout: packages/coding-agent`（只读），版本 1.3.0。
配套文档：`docs/analysis/pi-coding-agent-formats.md`（与现有 pi 数据/协议兼容所需的精确格式）。

本文所有结论均给出 `文件:行` 证据（路径相对 `packages/coding-agent/`）。源码规模按 `find src -name '*.ts' | xargs wc -l` 统计，共 **76,051 行 TypeScript**，另有 `docs/` **7,647 行**规格文档、`schemas/` 4 个 JSON Schema 文件。

---

## 1. 结论摘要

1. **coding-agent 不是"一个 agent"，而是 agent 之上的产品层**。真正的 agent 循环在 `pi-agent-core`（2,539 行），模型/Provider/流式协议在 `pi-ai`（27,709 行），终端框架在 `pi-tui`（19,623 行）。coding-agent 自己的 76k 行 = 会话编排 + 工具 + 会话树/压缩 + 资源体系（扩展/skills/prompts/themes/packages）+ 四种运行模式 + 设置/认证/模型解析。
2. **体量分布极不均匀**：`modes/interactive` 及其 components、theme 合计 **21,882 行（28.8%）**，全部是终端 UI；`core/extensions` + `extensions/*` 合计 **12,620 行（16.6%）**，是 TS 进程内扩展体系（jiti 直接加载 TS）。这两块是"不可移植成本"的主体。
3. **不可替代的内核约 20–22k 行**（含可裁剪的 renderers 1,007）：AgentSession（4,449）、SessionManager（2,013）、SettingsManager（1,443）、模型运行时/解析/Provider 组合（2,465）、工具（3,639 + renderers 1,007）、系统提示词/资源加载（1,516+1,287）、压缩（1,654）、RPC/print/json 三种 headless 模式（2,047）。
4. **四种运行模式里，三种（print/json/rpc）是机器可验证的协议契约，interactive 是人工体验**。RPC 模式（`src/modes/rpc/rpc-mode.ts`，819 行 + `rpc-types.ts` 303 行）是唯一自带完整协议定义、可被外部语言驱动、且已有 Python 最小客户端示例的接口（`docs/rpc.md:97-130`）。这是 Kotlin 重写最划算的兼容边界。
5. **最大的风险不在技术，而在生态断裂**：本机 `~/.pi/agent/settings.json` 目前声明 13 个 npm 扩展包（pi-subagents、pi-memory、pi-chrome、pi-mcp-adapter、pi-intercom、pi-rewind、pi-hud、pi-tool-display、pi-background-tasks、pi-web-access、rpiv-ask-user-question、rpiv-todo、rpiv-btw），`~/.pi/agent/skills` 有 6 个 skill 目录，`mcp.json` 配了 4 个 MCP server。任何"不兼容 TS 扩展"的 Kotlin 版本，在替代日常使用时都是功能倒退。**建议先把 Kotlin 版定位成 headless 内核（可共存），不要一上来替代交互式 pi。**
6. **推荐路径**：先做方案 A（headless 内核：CLI + print/json/rpc + 工具 + 会话 + 设置/认证/模型 + 压缩 + 系统提示词/上下文文件/skills/prompt templates），约 **15–20k 行 Kotlin**（不含 Provider 层；含最小 Provider 层约 18–25k）；验证通过后再做方案 B（+ 最小交互式 TUI，JLine3 方案 +4–6k、自研 +6–9k）。方案 C（扩展兼容）推迟，且必须先在"Kotlin SPI（生态不兼容）"和"Node 侧车（保留 Node 依赖）"之间做明确取舍。

### 1.1 与当前仓库状态的关系

截至本文写作时，仓库是 Gradle 多模块骨架（`settings.gradle.kts`：`pi-ai`、`pi-agent`、`pi-coding-agent`），`pi-ai` 已落地类型/消息/事件流/模型目录，`pi-agent` 尚未开始，`pi-coding-agent` 只有占位 `Main.kt`。`docs/ARCHITECTURE.md` 的"待定"两条（交互式 TUI 方案、扩展机制）正是本文 §11 与 §14 要回答的问题：

| ARCHITECTURE.md 待定项 | 本文结论 | 对应章节 |
|---|---|---|
| 交互式 TUI：JLine3 自建 / 先不做只做 print/json/rpc | 先不做（方案 A），A 验收通过后再用 JLine3 做最小集（方案 B）；不做全屏/鼠标/内联图片 | §11、§14.2 |
| 扩展机制：重新设计 SPI 或嵌入 JS 引擎 | 两者都推迟（方案 C）；先保证"读到扩展资源不崩、不误删配置"；若必须替代日常 pi 则选 Node 侧车而非 SPI | §7、§14.2 C1/C2 |
| 依赖方向 `pi-coding-agent → pi-agent → pi-ai` | 与本分析一致：coding-agent 对下层的依赖面很薄（§13.1、§13.2），主要工作量在产品层 | §13 |
| OkHttp / kotlinx.serialization / coroutines 选型 | 与本文推荐的 JVM 替代一致；`java.net.http` 仅在不想引入 OkHttp 时作为备选 | §12 |

因此 `pi-coding-agent` 模块的**第一个可交付物应当是方案 A**（headless 内核 + RPC 协议），而不是接口壳或 TUI。

---

## 2. 包结构与依赖

### 2.1 包外依赖

`package.json:40-60` 声明运行时依赖：

| 依赖 | 用途 | 在 src 中的直接引用点 |
|---|---|---|
| `@earendil-works/pi-agent-core` | Agent 循环、AgentEvent/AgentMessage/ThinkingLevel | `Agent`、`setDefaultStreamFn`（`src/core/sdk.ts:1-3`，`agent-session.ts:33`） |
| `@earendil-works/pi-ai` | Provider/模型/消息类型/流式/重试/OAuth/成本 | 58 处 import，见 §13 |
| `@earendil-works/pi-tui` | 终端框架、组件、主题、键位 | 90 处 import，几乎全在 `modes/interactive/` |
| `@earendil-works/pi-mcp` | MCP 客户端 | `extensions/mcp/` 4 处 |
| `@earendil-works/pi-codemode` | QuickJS 脚本沙箱 | `extensions/codemode/` 4 处 |
| `jiti` | 在进程内加载 TS 扩展 | `core/extensions/loader.ts:49-56`、`jiti-loader.ts` |
| `typebox` | JSON Schema 定义（工具参数、settings schema） | 19 处，`core/tools/*.ts`、`settings-schema.ts` |
| `proper-lockfile` | auth.json/settings.json/trust.json 跨进程锁 | `auth-storage.ts:9`、`settings-manager.ts`、`trust-manager.ts` |
| `undici` | HTTP 调度器（代理/超时/连接池） | `core/http-dispatcher.ts` |
| `minimatch` / `ignore` | 包资源 glob、.gitignore 语义 | `package-manager.ts`、`skills.ts:2` |
| `diff` | edit 工具 diff/unified patch | `core/tools/edit-diff.ts` |
| `yaml` | frontmatter 解析 | `utils/frontmatter.ts` |
| `semver` / `hosted-git-info` | 包版本/git 仓库解析 | `package-manager.ts`、`utils/git.ts` |
| `quickjs-wasi` | codemode WASM | `bun/runtime-setup.ts` |
| `@silvia-odwyer/photon-node` | 图片缩放/EXIF | `utils/photon.ts`、`image-resize-core.ts` |
| `chalk` | 终端着色（含 headless 诊断输出） | `main.ts:11`、`args.ts` 等 |
| `highlight.js` / `grok-mermaid` | 语法高亮 / mermaid 渲染（仅 TUI） | `utils/syntax-highlight.ts`、`components/mermaid.ts` |
| `cross-spawn` | 跨平台子进程 | `utils/child-process.ts` |

Node 内建模块使用频度（`grep -rhoP 'from "(node:)?[a-z_/]+"'`）：`node:path` 38、`node:fs` 34、`node:os` 15、`node:fs/promises` 13、`node:crypto` 9、`node:child_process` 9、`node:url` 5、`node:stream` 3、`node:readline` 3、`node:worker_threads` 2、`node:module` 2、`node:events` 2、`node:zlib` 1、`node:string_decoder` 1。

### 2.2 目录规模（src/）

| 目录 | 行数 | 说明 |
|---|---|---|
| `src/core/` | 23,390 | 业务核心：会话、工具、设置、模型、资源、压缩、扩展运行时、包管理 |
| `src/modes/interactive/components/` | 11,661 | 47 个 TUI 组件 |
| `src/modes/interactive/`（除 components/theme） | 7,960 | 交互模式主控（`interactive-mode.ts` 7,082）、renderer、share、bug-report |
| `src/core/extensions/` | 5,049 | 扩展类型 2,304 + runner 1,565 + loader 879 + index 225 + jiti 加载器 + virtual modules |
| `src/extensions/mcp/` | 4,258 | 内置 MCP 扩展（客户端、OAuth、资源、CLI、TUI） |
| `src/utils/` | 3,814 | shell/子进程/路径/图片/剪贴板/git/压缩 | 
| `src/core/tools/` | 3,639 | 8 个工具 + truncate + diff + 队列 + 累积器 |
| `src/` 根 | 3,629 | main.ts 1,000 + package-manager-cli.ts 1,142 + config.ts 656 + index.ts 497 + migrations.ts 315 |
| `src/modes/interactive/theme/` | 2,261 | theme.ts 1,086 + system-theme.ts 655 + controller/tokens/schema + dark/light.json |
| `src/modes/rpc/` | 1,797 | rpc-mode 819 + rpc-client 617 + rpc-types 303 + jsonl 58 |
| `src/core/compaction/` | 1,654 | compaction 1,104 + branch-summarization 380 + utils 163 |
| `src/extensions/llama/` | 1,577 | 内置 llama.cpp 扩展（TUI + 下载器） |
| `src/extensions/codemode/` | 1,474 | 内置 codemode 扩展（QuickJS worker） |
| `src/cli/` | 1,458 | 参数解析 479 + 启动 UI + auth 命令 + 会话选择器 |
| `src/core/tools/renderers/` | 1,007 | 8 个工具的 TUI/HTML 渲染器 |
| `src/core/export-html/` | 750 | HTML 导出（模板渲染 + ANSI→HTML） |
| `src/extensions/tool-search/` | 262 | 内置 tool_search 扩展 |
| `src/bun/` | 61 | Bun 编译产物专用入口 |

---

## 3. 启动链路

### 3.1 入口

```
dist/bundle/cli.js            <- package.json bin
  src/cli.ts:2-4              setupCli(); main(process.argv.slice(2))
     src/cli/setup.ts:5-13    process.title / PI_CODING_AGENT=true / AI_AGENT=pi / configureHttpDispatcher()
     src/main.ts:574          main()
dist/bundle/rpc-entry.js      <- package.json exports "./rpc-entry"
  src/rpc-entry.ts:1-13       main(["--mode","rpc", ...argv])
```

`main()` 的实际顺序（`src/main.ts:574-999`）值得逐段掌握，它决定了 Kotlin 版 CLI 的骨架：

| 顺序 | 动作 | 证据 |
|---|---|---|
| 1 | 设置 theme JSON 校验器；合并内置扩展工厂（`builtin:mcp/llama.cpp/codemode/tool-search`） | `main.ts:577-578`，`extensions/index.ts` |
| 2 | `--offline` / `PI_OFFLINE` 处理（置 `PI_SKIP_VERSION_CHECK`） | `main.ts:579-583` |
| 3 | `auth` 子命令（check / print-api-key / print-bearer-token） | `main.ts:585-587`，`cli/auth-command.ts` |
| 4 | Windows 自更新隔离清理、托管安装清理 | `main.ts:589-592` |
| 5 | 引导 `SettingsManager.create(cwd, agentDir, {projectTrusted:false})`；应用 httpProxy；配置 HTTP 调度器 | `main.ts:594-598` |
| 6 | `install|remove|uninstall|update|list` 包命令；`config` 命令；`mcp` 子命令 | `main.ts:600-621` |
| 7 | `parseArgs` 并输出 diagnostics（error 则 exit 1） | `main.ts:623-633`，`cli/args.ts:72-271` |
| 8 | `--version` / `--export` 早退 | `main.ts:635-652` |
| 9 | 决定 appMode；非 interactive 时 `takeOverStdout()` | `main.ts:654-658`，`core/output-guard.ts:51` |
| 10 | RPC 拒绝 `@file`；校验 `--fork` / `--session-id` 冲突 | `main.ts:660-666` |
| 11 | `runMigrations(cwd)`（auth/旧 sessions/commands→prompts/keybindings/工具二进制） | `main.ts:669`，`migrations.ts:305-315` |
| 12 | 建立真实 SettingsManager、收集 diagnostics、首次运行向导、`--use-theme` 覆盖 | `main.ts:672-684` |
| 13 | 计算 sessionDir 优先级（`--session-dir` > env > settings）并创建 SessionManager | `main.ts:691-696`，`config.ts:586` |
| 14 | 缺失 cwd 的会话：交互模式询问，否则报错 | `main.ts:697-709`，`core/session-cwd.ts` |
| 15 | `--name` 写入 `session_info` 条目 | `main.ts:710-717` |
| 16 | 项目信任解析（在 cwd 已知后才会加载项目级 settings/资源） | `main.ts:720-781`，见 §10.4 |
| 17 | `createAgentSessionRuntime(createRuntime, ...)`：按目标 cwd 重建 services（ModelRuntime/Settings/ResourceLoader），注册扩展 Provider/虚拟模型，解析模型 scope，创建 AgentSession | `main.ts:733-871`，`core/agent-session-runtime.ts:74` |
| 18 | `--help` / `--list-models` 早退（`--help` 会打印扩展注册的 flag） | `main.ts:878-892` |
| 19 | 读管道 stdin；interactive 且读到 stdin 时降级为 print | `main.ts:894-902` |
| 20 | 组合初始消息（stdin + @file + 第一条 message）、初始化主题 | `main.ts:904-907`，`cli/initial-message.ts` |
| 21 | 报错即退出；非交互模式无可用模型即退出 | `main.ts:917-931` |
| 22 | 分派 `runRpcMode` / `InteractiveMode.run()` / `runPrintMode()` | `main.ts:949-999` |

### 3.2 模式判定

`resolveAppMode`（`main.ts:112-123`）：

```
--mode rpc            -> rpc
--mode json           -> json
--print / stdin 非 TTY / stdout 非 TTY -> print
否则                  -> interactive
```

补充规则：
- `--mode text` 不强制一次性执行（`docs/cli.md:41`）。
- 读到管道 stdin 时 interactive 降级为 print（`main.ts:896-900`）。
- `--export` 是独立早退路径，不进入任何模式（`main.ts:640-652`）。

---

## 4. 四种运行模式

四种模式共享同一个 `AgentSession` 与事件流，差异只在"谁订阅事件、怎么输出、怎么接收输入"。

### 4.1 interactive（TUI）

- 入口：`src/modes/interactive/interactive-mode.ts`，`InteractiveMode` 类（7,082 行 + 11,661 行组件 + 2,261 行主题）。
- 构造参数：`InteractiveModeOptions`（`interactive-mode.ts:610` 构造函数），包含 startupDiagnostics、initialMessage(s)、verbose、tuiMode、theme。
- 职责：全量终端渲染、键位、选择器、扩展 UI、会话树/模型/设置/登录/导出/share/bug 等全部交互命令。
- 依赖 `pi-tui` 做终端抽象，两种 TUI 模式：`fullscreen`（TuiAltScreen）与 `regular`（TuiMainScreen）（`interactive-mode.ts:875-945`，`tui-mode` setting）。
- 与 headless 的关键耦合点：工具 renderers、`ExtensionUIContext` 实现（`createExtensionUIContext()`，`interactive-mode.ts:2633`）、会话条目渲染、`CustomEntry`/`CustomMessage` 渲染。

### 4.2 print（一次性文本）

- 入口：`src/modes/print-mode.ts:33 runPrintMode()`，173 行，是三种 headless 模式里最小的。
- 行为：`prompt(initialMessage)` → 逐条 `prompt(messages[i])` → 只把最后一条 assistant 消息的 text 块写到 stdout（`print-mode.ts:139-160`）。
- 退出码：正常 0；`stopReason` 为 `error`/`aborted` 或 `length` 时 1，并在 stderr 写原因（`print-mode.ts:145-157`）。
- 信号：SIGTERM/SIGHUP 先杀子进程再 dispose（`print-mode.ts:50-66`）。

### 4.3 json（事件流）

- 入口同上，`mode: "json"`。
- 输出：第一行是 session header（`print-mode.ts:122-127`），随后每个 `AgentSessionEvent` 经 `toJsonEvent()` 压缩后写一行（`print-mode.ts:108-112`，`src/modes/json-event.ts:46-61`）。
- 关键压缩规则：`message_update` 去掉 SDK 事件的累积 `message` 与每个 `assistantMessageEvent.partial`，只保留 delta；`toolcall_start` 补 `id`/`toolName`（`json-event.ts:20-38`，`docs/json.md:70-93`）。
- **stdout 只放协议记录，诊断一律 stderr**：通过 `takeOverStdout()` 把 `process.stdout.write` 重定向到 stderr，协议写走 `writeRawStdout()`（`core/output-guard.ts:51-97`）。
- 背压：每写一帧前 await `waitForRawStdoutBackpressure()`（`print-mode.ts:113-118`）。

### 4.4 rpc（双向 JSONL）

- 入口：`src/modes/rpc/rpc-mode.ts:54 runRpcMode()`，819 行；协议类型在 `rpc-types.ts`（303 行）。
- 记录族：stdin 命令、stdout `response`、stdout 会话事件、双向 extension UI 记录（`docs/rpc.md:24-33`）。
- 命令集（`rpc-types.ts:20-74`，共 33 个）：prompt / steer / follow_up / abort / clear_queue / new_session / get_state / set_model / cycle_model / get_available_models / set_thinking_level / cycle_thinking_level / get_available_thinking_levels / set_steering_mode / set_follow_up_mode / compact / set_auto_compaction / set_auto_retry / abort_retry / bash / abort_bash / get_session_stats / export_html / switch_session / fork / clone / get_fork_messages / get_entries / get_tree / get_last_assistant_text / set_session_name / get_messages / get_commands。
- 响应关联：可选 `id` 原样回传；未知命令回 `Unknown command: <type>`；JSON 解析失败回 `command:"parse"` 的失败响应（`rpc-mode.ts:786-810`）。
- 帧格式：严格 LF 分帧，**不能用 Node readline**（会额外切 U+2028/U+2029），自己实现 `attachJsonlLineReader`（`modes/rpc/jsonl.ts:16-58`），序列化 `JSON.stringify(v)+"\n"`（`jsonl.ts:8-10`）。
- 关闭语义：stdin EOF → `shutdown()` → dispose runtime → exit（`rpc-mode.ts:831-838`）；扩展也可请求 shutdown（`docs/rpc.md:91-95`）。
- 扩展 UI 子协议：`extension_ui_request`（select/confirm/input/editor/notify/setStatus/setWidget/setTitle/set_editor_text）与 `extension_ui_response`（`rpc-types.ts:252-297`，`docs/rpc-extension-ui.md`）。

### 4.5 事件总表（四种模式共享）

`AgentSessionEvent`（`core/agent-session.ts:196-240`）——Kotlin 版必须 1:1 实现的事件联合：

- 来自底层 agent：`agent_start`、`agent_end{messages,willRetry}`、`turn_start`、`turn_end{message,toolResults}`、`message_start`、`message_update`、`message_end`、`tool_execution_start/update/end`（后者可带 `parentToolCallId`）。
- 会话级：`agent_settled{aborted}`、`queue_update{steering,followUp}`、`entry_appended{entry}`、`session_info_changed{name}`、`thinking_level_changed{level}`。
- 压缩/重试：`compaction_start{reason}`、`compaction_end{reason,result,aborted,willRetry,errorMessage?}`、`auto_retry_start/end`、`summarization_retry_scheduled`、`summarization_retry_attempt_start{source,reason?}`、`summarization_retry_finished`。
- 仅 RPC/交互直连 bash：`bash_execution_update{id?,delta}`。

语义要点：`agent_end` 只结束一次底层 run，重试/溢出恢复/压缩重试/排队消息仍可能继续；`agent_settled` 才表示"没有自动工作"（`docs/json.md:48`，`agent-session.ts:1079`）。

---

## 5. 核心对象

### 5.1 AgentSession（4,449 行，`core/agent-session.ts`）

这是 coding-agent 的心脏：把 `Agent`（pi-agent-core 的循环）包成"产品会话"。

**配置面**（`AgentSessionConfig`，`agent-session.ts:252-301`）：`agent`、`sessionManager`、`settingsManager`、`cwd`、`scopedModels`、`resourceLoader`、`customTools`、`modelRuntime`、`cacheWarmer`、`initialActiveToolNames`、`usesDefaultTools`、`defaultToolModifiers`、`allowedToolNames`、`excludedToolNames`、`baseToolsOverride`、`extensionRunnerRef`、`sessionStartEvent`。

**主要职责分组**（按方法清单归纳，`agent-session.ts:486-4449`）：

| 组 | 代表方法 | 说明 |
|---|---|---|
| 初始化/绑定 | `constructor` 486、`_installAgentToolHooks` 653、`_installAgentRequestProjection` 788、`_installAgentBoundaryHooks` 887、`_installAgentNextTurnRefresh` 899 | 把扩展 hook 挂到 Agent 的 `beforeToolCall`/`afterToolCall`/`prepareRequest`/`finishTurn`/`prepareNextTurn` 上 |
| 事件 | `subscribe` 1373、`_emit` 1040、`_emitAgentSettled` 1079、`_emitExtensionEvent` 1288 | 会话事件 + 扩展事件分发 |
| 生命周期 | `dispose` 1397、`abort` 2485、`waitForIdle` 2497、`refreshContext` 1425 | |
| 运行 | `prompt` 2079、`_startRun` 1858、`_prepareRun` 1911、`_handlePostAgentRun` 1964、`_runBeforeSettleBoundary` 2004、`_runInputHandlers` 2028 | 一次 run = 多轮 turn；run 之间处理重试/压缩/队列/settle |
| 队列 | `steer` 2262、`followUp` 2278、`clearQueue` 2453、`sendUserMessage` 2416、`sendCustomMessage` 2344、`_flushPendingCustomMessages` 2398 | steering 在"当前 assistant turn 的工具调用结束、下一次 LLM 请求前"进入；followUp 在整次 run 结束后进入（`docs/how-pi-works.md:13`） |
| 工具注册/装载 | `setActiveToolsByName` 1523、`getActiveToolNames` 1487、`getCallableToolNames` 1492、`getAllTools` 1499、`_getToolExposure` 1559、`_applyToolLoadout` 1580、`_refreshToolRegistry` 3546 | 定义"声明给模型(active) / 可被其它工具调用(callable) / 隐藏"三态；`exposure` 语义见 §7 |
| 提示词 | `_rebuildSystemPrompt` 1705、`_preparePromptAndToolLoadout` 1743、`_installHiddenDeclarationsProjection` 1772、`_installAgentForcedPromptProjection` 1792、`_restoreToolsFromTranscript` 1815 | 结构化 sections + transcript 回放；工具集变化写成 system message patch |
| 模型/思考 | `setModel` 2528、`cycleModel` 2570、`setThinkingLevel` 2663、`cycleThinkingLevel` 2692、`getAvailableThinkingLevels` 2708、`_clampThinkingLevel` 2734 | |
| 压缩 | `compact` 2815、`_checkCompaction` 2998、`_runAutoCompaction` 3148、`abortCompaction` 2965、`_exceedsCompactionThreshold` 768、`_compactBeforeNextAssistantResponse` 777 | 阈值检查在工具结束后、下一轮前，也在新 prompt 前（`docs/compaction.md:37`） |
| 扩展绑定 | `bindExtensions` 3309、`_bindExtensionCore` 3411、`reload` 3711、`extendResourcesFromExtensions` 3335 | |
| 重试 | `_isRetryableError` 3760、`_prepareRetry` 3813、`abortRetry` 3858、`setAutoRetryEnabled` 3875 | |
| bash（`!` 命令/RPC bash） | `executeBash` 3892、`recordBashResult` 3930、`abortBash` 3956、`_flushPendingBashMessages` 3976 | 产出 `BashExecutionMessage`，下一次 prompt 时转成 user 文本（`core/messages.ts:82-98`） |
| 会话操作 | `navigateTree` 4015、`getUserMessagesForForking` 4212、`setSessionName` 3993 | |
| 统计 | `getSessionStats` 4234、`getContextUsage` 4288 | `SessionStats` 结构 `agent-session.ts:343-361` |
| 导出 | `exportToHtml` 4339、`exportToJsonl` 4365、`summarizeForBugReport` 4373 | |
| 其它 | `getLastAssistantText` 4398、`createReplacedSessionContext` 4426、`hasExtensionHandlers` 4439、`extensionRunner` 4446 | |

**prompt 的真实流程**（`agent-session.ts:2079-2166`），Kotlin 实现可直接照此建状态机：

1. 若正在 emit `agent_settled`，把 prompt 排到 `_deferredSettledActions`。
2. 以 `/` 开头先尝试扩展命令（流式期间也立即执行，扩展自己负责后续 LLM 交互）。
3. 压缩中 → 抛错。
4. 触发 `input` 扩展事件，可消费/改写/拦截（返回 null 表示 handled）。
5. 展开 `/skill:name` 与 prompt template。
6. 流式中：必须给 `streamingBehavior`（`steer`/`followUp`），否则报错；入队后返回 `queued`。
7. 校验模型与认证（无模型/无凭据给出可操作错误）。
8. `_startRun({messageType:"user",prompt,images})`：`_prepareRun` → 必要时先压缩 → `before_agent_start` → 组装起手消息（user + pending nextTurn + 扩展 message + system patch）→ `agent.prompt(messages)` → 循环 `_handlePostAgentRun()`（重试/溢出恢复/压缩后重试/队列）与 `_runBeforeSettleBoundary()`（`agent_before_settle`）→ `agent.continue()` → 最后 `agent_settled`。

### 5.2 AgentSessionRuntime / services（`core/agent-session-runtime.ts` 447 + `agent-session-services.ts` 233）

- `AgentSessionRuntime` 持有"当前 AgentSession + 该 cwd 的 services"，并提供 `newSession()`/`switchSession()`/`fork()`/`importFromJsonl()`；每次替换都会先 `session_shutdown`，再重建 services，再 `session_start`（`agent-session-runtime.ts:67-160`，`docs/sdk.md:58-60`）。
- `createAgentSessionServices()` 的职责顺序（`agent-session-services.ts:135-205`）：ModelRuntime（auth.json + models.json）→ SettingsManager → DefaultResourceLoader.reload（含项目信任回调）→ 注册扩展 Provider/虚拟模型 → `modelRuntime.refresh({allowNetwork:false})` → 应用扩展 CLI flag 值 → 返回 diagnostics。**这个"按 cwd 重建一切"的结构是 Kotlin 版必须保留的**，否则 `--session` 跨项目、`/resume` 切项目都会错。

### 5.3 SessionManager（2,013 行，`core/session-manager.ts`）

职责：JSONL 文件/内存会话的**追加写 + 树索引 + 上下文重建 + 列表/恢复/分叉**。核心事实：

- 当前版本 `CURRENT_SESSION_VERSION = 3`（`session-manager.ts:41`）；v1→v2 补 id/parentId，v2→v3 把 `hookMessage` 角色改名 `custom`（`session-manager.ts:287-335`）。
- 条目类型 11 种（`session-manager.ts:183-194`）：message、thinking_level_change、model_change、usage、compaction、branch_summary、custom、custom_message、context_edit、label、session_info。逐字段格式见 formats 文档 §2。
- **延迟建文件**：只有当会话里出现 user 或 assistant 消息才创建 JSONL；只有 model/thinking/system 条目时不留文件；`_persist` 第一次把内存里所有条目一次性写盘，之后逐条 append（`session-manager.ts:1166-1202`）。这一点直接影响"空会话不留垃圾文件"的兼容语义。
- 会话路径：`<agentDir>/sessions/--<cwd 转义>--/<ISO 时间戳，: . 替换为 ->_<uuidv7>.jsonl`（`session-manager.ts:589-598`，`1632-1700`）。转义规则：去掉开头 `/`，把 `/`、`\`、`:` 换成 `-`，两端加 `--`。
- id：会话 id 用 uuidv7；条目 id 用 8 位 hex（`randomUUID().slice(0,8)`，冲突重试 100 次后回退完整 UUID）（`session-manager.ts:264-284`）。
- 上下文重建：`buildContextEntries()`（compaction 感知）→ `buildSessionProjection()`（应用 `context_edit`）→ `buildSessionContext()`（转成消息 + 恢复 model/thinking）（`session-manager.ts:476-586`）。
- 树操作：`branch`/`resetLeaf`/`branchWithSummary`/`getTree`/`createBranchedSession`（fork/clone 的实现体，`session-manager.ts:1579-1754`）。
- 静态入口：`create`/`open`/`continueRecent`/`inMemory`/`forkFrom`/`findById`/`list`/`listAll`（`session-manager.ts:1755-1900`）。
- 列表并发：会话发现最多 64 并发、元信息加载最多 10 并发，列表按批次发布进度（`session-manager.ts:891-946`）；这是 `/resume` 在几百个会话下不卡的原因，Kotlin 版可以简单实现但要注意别串行读大文件。

### 5.4 SettingsManager（1,443 行）+ schema/defaults

- 两级：global（`<agentDir>/settings.json`）+ project（`<cwd>/.pi/settings.json`）；project 覆盖 global，资源数组合并（`core/settings-manager.ts:143-160`，`docs/settings.md:3`）。
- 默认值集中在 `core/settings-defaults.ts:4-67`；完整 schema 在 `core/settings-schema.ts`（`export const SettingsSchema`），字段全集见 formats 文档 §3。
- 写回：`FileSettingsStorage.withLock` 用 proper-lockfile 加锁后读-改-写（`settings-manager.ts:186-255`）；`enqueueWrite` 串行化写（576）；`persistScopedSettings`（598-628）在锁内重新读当前文件，只覆盖"本次修改过的字段/嵌套键"，**未修改字段与未知字段原样保留**（schema 声明 `additionalProperties:true`，`settings-schema.ts:453`）。Kotlin 版必须实现同类"只写脏字段"，否则会抹掉用户或其它扩展写入的键。读取侧对未知字段是宽容的（不校验、不报错），只在写回时按已知字段做事。
- `defaultTools` 的 `+name`/`-name` 语义在 `applyToolModifiers`/`resolveDefaultTools`/`mergeDefaultTools`（`settings-manager.ts:85-142`），是 CLI/settings/项目三层叠加的核心规则，容易实现错。

### 5.5 模型体系

四个对象分工：

| 对象 | 文件/行数 | 职责 |
|---|---|---|
| `ModelRuntime` | `core/model-runtime.ts` 1,049 | 唯一权威：组合 Provider、缓存模型快照、认证解析、可用性刷新、注册扩展 Provider/原生 Provider/虚拟模型、流式请求入口（`stream`/`complete`/`streamSimple`/`completeSimple`/`classify`/`generateImages`）、登录登出（`model-runtime.ts:174-1045`） |
| `ProviderComposer` | `core/provider-composer.ts` 732 | 把内置 Provider + `models.json` + 扩展 Provider 配置组合成可用的 Provider：模型覆盖合并、authHeader、OAuth 适配、headers/apiKey 解析（`provider-composer.ts:91-400`） |
| `ModelResolver` | `core/model-resolver.ts` 684 | `--model` 文本 → 模型：精确 ID、`provider/id`、模糊匹配、`:thinking` 后缀、`--models` scope（glob/fuzzy）、默认模型回退（`model-resolver.ts:120-531`） |
| `ModelRegistry` | `core/model-registry.ts` 244 | 给扩展的同步门面（`getAll`/`getAvailable`/`find`/`findOfType`） |
| `VirtualModels` | `core/virtual-models.ts` 238 | 虚拟模型：`api = "pi-virtual"`，按请求把 `user`/`continuation`/`retry`/`direct` 四类路由到物理模型，路由状态存 `custom` 条目 `pi.virtual-model-state` |
| `ModelsStore`/`RemoteCatalog` | `models-store.ts` 147 + `remote-catalog-provider.ts` 240 | `~/.pi/agent/models-store.json` 缓存/远端目录覆盖 |

认证：`core/auth-storage.ts` 507 行，`auth.json` 两种凭据 `{type:"api_key",key,env?}` / `{type:"oauth",access,refresh,expires,...}`，0600 权限、proper-lockfile 跨进程锁、异步锁 30s 上限 + EPERM 重试（`auth-storage.ts:36-156`）。凭据优先级：运行时 `--api-key` > `auth.json` > `models.json` 的 `apiKey` > Provider 环境变量（`docs/models.md:23`）。

### 5.6 系统提示词（`core/system-prompt.ts` 229 行）

**这是 pi 行为最难"看起来一样"的部分，但实现简单**：

- 提示词是一组**有名字、可独立替换的 section**（`SystemPromptSections`，`system-prompt.ts:51-56`）：`preamble`（无标签）、`tools`、`rules`、`docs`、`addendum`、`project_context`、`skills`、`cwd`，以及扩展自定义 section。除 `preamble` 外每个 section 包成 `<name>\n...\n</name>`（`system-prompt.ts:188-192`）。
- 默认 preamble 固定一句话（`system-prompt.ts:155-156`）；`tools` 段来自每个工具的 `promptSnippet`（157-160）；`rules` 段来自工具 `promptGuidelines` + 全局规则（`buildRules`，88-125）；`docs` 段把 readme/docs/examples 路径塞给模型（162-169）。
- 上下文文件以 `<project_instructions path="...">` 包裹（79-86）；skills 以 `<available_skills>` XML 列出 name/description/location（`core/skills.ts:358-388`）。
- **增量更新机制**：每次请求前 `diffSystemPromptSections(previous, current)` 得到 patch，作为一条 `role:"system", content:"", sections:patch` 的消息追加；`null` 表示删除某 section（`system-prompt.ts:217-228`，`agent-session.ts:1743-1771`）。首次请求写入完整 sections + `toolsAdded`（真实会话证据见 `~/.pi/agent/sessions/--home-user-workspace--/*.jsonl` 第 4 行）。工具集变化同样用 system message 的 `toolsAdded`/`toolsRemoved` 表达，回放即得当前状态。**Kotlin 版必须实现"回放 system messages 得到当前 prompt 与工具集"**，否则恢复会话会错。

---

## 6. 工具系统

8 个内置工具（`core/tools/index.ts:95-105`），全部通过 `ToolDefinition`（扩展类型 `core/extensions/types.ts:579`）注册，带 `parameters`(TypeBox schema)、`description`、`promptSnippet`、`promptGuidelines`、`execute`、可选 `outputSchema`/`constrainedSampling`/`render*`。

| 工具 | 文件 | 参数 schema | 行为要点 | 输出上限 |
|---|---|---|---|---|
| `read` | read.ts 224 | `path`, `offset?`(1-indexed), `limit?` | 文本按 UTF-8 读、行/字节双截断；图片走 `processImage`（自动缩放 + EXIF + 非视觉模型降级为文字提示）；offset 越界报错；首行超限时给 bash 兜底命令 | 2000 行 / 50KB（`truncate.ts:11-12`） |
| `bash` | bash.ts 442 | `command`, `timeout?`(秒) | 生成 shell 配置（`getShellConfig`），`detached` 进程组，流式 stdout+stderr，超时/abort 杀整棵进程树，输出累积到临时文件；`PI_*` 会话环境变量注入（194-208）；非 0 退出码 → `isError:true` 且文案追加 `Command exited with code N` | 尾部 2000 行 / 50KB；完整输出写 `pi-bash-*.log`；`structuredContent.output` 上限 1MB |
| `powershell` | powershell.ts 67 | 同 bash | Windows 专用，复用 `createShellToolDefinition` | 同 bash |
| `edit` | edit.ts 220 | `path`, `edits[{oldText,newText}]` | 原文件唯一非重叠匹配；`prepareArguments` 兼容"edits 为 JSON 字符串/单个对象/旧版 oldText+newText"（103-134）；BOM 与 CRLF 检测后归一化匹配再还原（190-197）；`withFileMutationQueue` 串行同文件写；返回 diff + unified patch + firstChangedLine | 无输出截断（返回摘要） |
| `write` | write.ts 97 | `path`, `content` | 自动递归建父目录，同文件串行 | 无 |
| `grep` | grep.ts 323 | `pattern`, `path?`, `glob?`, `ignoreCase?`, `literal?`, `context?`, `limit?`(默认100) | **依赖外部 `rg` 二进制**（`ensureTool("rg")`，缺失时从 GitHub 下载，离线模式降级报错）；`--json --line-number --color=never --hidden`；`--fixed-strings`/`--ignore-case`/`--glob`；匹配行截断到 500 字符；命中 limit 主动 kill | 100 命中 / 50KB |
| `find` | find.ts 318 | `pattern`, `path?`, `limit?`(默认1000) | **依赖外部 `fd` 二进制**；判断是否在 git repo 内决定是否加 `--no-require-git`；含 `/` 的 pattern 用 `--full-path` 并补 `**/` 前缀；结果相对化 | 1000 条 / 50KB |
| `ls` | ls.ts 175 | `path?`, `limit?`(默认500) | 字母序（case-insensitive），目录加 `/`，含隐藏文件，stat 失败跳过 | 500 条 / 50KB |

共享基础设施：

- `truncate.ts` 314：`truncateHead`/`truncateTail`/`truncateLine`/`truncateMiddle`，双限制（行/字节）取先到者，绝不返回半行（尾部截断有唯一例外）；`Buffer.byteLength` 按 UTF-8 字节算。
- `output-accumulator.ts` 257：bash 流式累积 + 超限落临时文件 + 节流回调（`BASH_UPDATE_THROTTLE_MS`）。
- `file-mutation-queue.ts` 61：按 realpath 串行化同文件写，不同文件并行。
- `path-utils.ts` 118：`~` 展开、`@` 前缀剥离、Unicode 空格归一、macOS NFD/弯引号/窄空格变体兜底。
- `bash-executor.ts` 146：`!` 命令与 RPC bash 用；ANSI/二进制清洗（`splitIncompleteAnsiSuffix` + `sanitizeBinaryOutput`），滚动缓冲 2×50KB，超限落盘。
- 外部工具管理：`utils/tools-manager.ts`（401 行）在 `~/.pi/agent/bin/` 下查找/下载 fd、rg（GitHub release，跳过 api.github.com 匿名配额）。**本机现状：`/usr/bin/rg` 存在；`fd` 只在 `~/.pi/agent/bin/fd`。**

TUI 耦合：每个工具的 `renderers/*.ts`（1,007 行）只被 interactive 模式与 HTML 导出使用；`ToolDefinition` 本身不含渲染逻辑，因此 **headless 化时 renderers 可以整体不实现**（`extensions/types.ts:459-690`）。

---

## 7. 扩展机制

### 7.1 加载

- `core/extensions/loader.ts`（879）：`jiti`（`jiti-loader.ts` / `jiti-static-loader.ts`）直接在当前进程内加载 TS/JS，注入虚拟模块别名（`virtual-modules.ts`），把 `@earendil-works/pi-*`、`typebox` 等映射到宿主提供的实例，避免重复类与重复注册表（`loader.ts:49-130`，`docs/packages.md:78-92`）。
- 发现规则：`extensions/` 下直接文件 + 子目录 `index.ts`/`index.js`/`package.json` main（`loader.ts:739-790`）；`~/.pi/agent/extensions/`、`.pi/extensions/`、settings `extensions`、包、`-e/--extension`、`builtin:<name>`（`resource-loader.ts:680-800`）。
- `builtin:` 扩展由 `extensions/index.ts` 提供工厂：`mcp`、`llama.cpp`、`codemode`、`tool-search`；可被同名扩展"替换"（`resource-loader.ts:108-152`，`docs/settings.md:163`）。
- 加载结果 `LoadExtensionsResult{extensions, errors, warnings, runtime}`；单个扩展失败只产生 diagnostic，不阻止其它扩展（`main.ts:803-811`）。

### 7.2 运行时 API

`ExtensionAPI`（`core/extensions/types.ts:1592`）——约 50 个事件订阅 + 注册方法：

- 事件订阅：`on(...)`，事件名见 §7.3。
- 注册：`registerTool`、`registerCommand`、`registerShortcut`、`registerFlag`、`registerMessageRenderer`、`registerMarkdownTransformer`、`registerEntryRenderer`、`registerToolRenderer`、`registerProvider`/`registerNativeProvider`、`registerVirtualModel`、`registerMcpServer`。
- 会话：`sendMessage`、`sendUserMessage`、`appendEntry`、`setSessionName`、`getSessionName`、`setLabel`、`exec`、`getActiveTools`/`setActiveTools`/`getAllTools`、`getSettings`、`getCommands`、`setModel`、`getThinkingLevel`/`setThinkingLevel`。
- 上下文 `ExtensionContext`（`types.ts:325`）：ui/mode/hasUI/cwd/sessionManager/modelRegistry/model/scopedModels/thinkingLevel/isIdle/isProjectTrusted/signal/abort/hasPendingMessages/shutdown/getContextUsage/compact/getSystemPrompt。
- 命令上下文 `ExtensionCommandContext`（`types.ts:401`）：`waitForIdle`、`newSession`、`fork`、`navigateTree`、`switchSession`、`reload`、`withSession`。
- 工具上下文 `ExtensionToolContext`（`types.ts:383`）：`executeTool(name,args,{signal,onUpdate})` 嵌套调用，事件带 `parentToolCallId`，`toolCallId` 为 `<parent>/<n>`，会话里只保留有界 `nestedCalls` 元数据（`agent-session.ts:731-767`，`docs/extensions.md:261`）。

### 7.3 事件与 hook 顺序

按 `docs/extensions.md:94-125` 与 `runner.ts` 的 emit 分组：

- 输入/启动：`input`、`before_agent_start`（`emitBeforeAgentStart`，`runner.ts:1427`）。
- run：`agent_start`、`agent_end`、`agent_before_settle`、`agent_settled`。
- turn：`turn_start`、`context`、`context_with_system`、`turn_end`。
- Provider：`before_provider_headers`、`before_provider_request`、`after_provider_response`、`provider_stream_event`；另有 `cache_warming_decision`。
- 消息：`message_start`、`message_update`、`message_end`。
- 工具：`tool_execution_start`、`tool_call`、`tool_execution_update`、`tool_result`、`tool_execution_end`。
- 会话：`project_trust`、`resources_discover`、`session_start`、`session_info_changed`、`session_before_switch`、`session_before_fork`、`session_before_compact`、`session_compact`、`session_compact_failed`、`session_shutdown`、`session_before_tree`、`session_tree`、`mcp_servers_change`。
- UI：`ui_prompt_start`、`ui_prompt_end`。
- 模型：`model_select`、`thinking_level_select`；bash：`user_bash`。

组合语义（`docs/extensions.md:203-241`）：通知型忽略返回值；原地修改型（headers、systemPromptOptions、tool_call.input）；链式变换型（input、context、context_with_system、message_end、tool_result、before_provider_request）；门控型（input handled / tool_call block / session cancel）；最后一个 `cache_warming_decision` 生效。`tool_call` 处理器抛错 = fail-closed 阻断工具（`docs/extensions.md:375`）。

### 7.4 工具暴露模型（exposure）

`ToolExposure = "direct" | "model-only" | "codemode" | "deferred" | "hidden"`（`types.ts:516`）：

- `direct`：声明给模型 + 可被 `executeTool` 调用（注册即激活）。
- `model-only`：只声明给模型，不能被其它工具调用。
- `codemode`：注册即"可调用"，但不声明给模型；由 codemode 脚本调用、codemode 描述里列 namespace。
- `deferred`：同 codemode 但 codemode 不列它，只能由 `tool_search` 找出来。
- `hidden`：注册但不可达（用于撤销）。

`prepareLoadout(loadout)` 可改写声明（描述/隐藏声明），`reachesTools(input)` 声明一次调用可能触达哪些工具（MCP 扩展据此延迟连接，`docs/extensions.md:293-295`）。

### 7.5 扩展 UI

`ExtensionUIContext`（`types.ts:149-306`）约 35 个方法：dialog（select/confirm/input/editor/custom）、notify、setStatus/setWidget/setFooter/setHeader/setTitle/setWorkingMessage/setWorkingVisible/setWorkingIndicator/setHiddenThinkingLabel、编辑器（pasteToEditor/setEditorText/getEditorText/setEditorComponent/addAutocompleteProvider）、主题（getAllThemes/getTheme/setTheme）、terminal input、tools expanded。

- interactive：全部实现（`interactive-mode.ts:2633-3005`）。
- RPC：dialog 走 `extension_ui_request/response`；custom/terminal input/footer 等降级为 no-op（`docs/rpc-extension-ui.md:12-25`）。
- json/print：`mode` 为 `"json"`/`"print"`，`hasUI=false`，dialog 直接返回默认值（`runner.ts:331` 的 `noOpUIContext`）。

### 7.6 资源体系：skills / prompt templates / themes / packages

| 资源 | 加载器 | 规模 | 关键规则 |
|---|---|---|---|
| skills | `core/skills.ts` 514 + `package-manager.ts:456-530` | | 目录含 `SKILL.md` 即技能根、不再下钻；否则递归找 `SKILL.md`；根目录下的 `.md` 也当 skill；`name` 缺省用父目录名；name 校验 64 字符/小写/连字符；description 必填且 ≤1024；冲突保留先发现的；支持 `disable-model-invocation`；额外发现 `~/.agents/skills` 与（受信任项目的）祖先 `.agents/skills`（`package-manager.ts:2464-2570`） |
| prompt templates | `core/prompt-templates.ts` 325 | | `<agentDir>/prompts/`、`.pi/prompts/`、settings `prompts`、包；只加载直接 `.md` 子文件（递归仅经 settings/包显式路径）；frontmatter `description`/`argument-hint`；参数替换 `$1`/`$@`/`$ARGUMENTS`/`${1:-d}`/`${@:N}`/`${@:N:L}`；shell 风格引号解析（`prompt-templates.ts:25-108`） |
| themes | `core/resource-loader.ts:1043-1135` + `modes/interactive/theme/theme.ts` 1,086 | | JSON：`name`(唯一、不能含 `/`、不能叫 `system`)/`appearance`/`vars`/`colors`/`export`；颜色六种写法（#hex、oklch、okhsl、256 索引、变量引用、`""` 终端默认）；5 个可选色有继承（`docs/themes.md:120-130`）；内置 `dark`/`light`/`system`；`system` 主题探测终端颜色 |
| 上下文文件 | `resource-loader.ts:184-275` | | 依次找 `AGENTS.override.md`、`AGENTS.md`、`AGENTS.MD`、`CLAUDE.md`、`CLAUDE.MD`；agentDir 一份 + cwd 向上到根各一份（由远到近）；worktree 影子文件去重；不需要项目信任 |
| SYSTEM.md/APPEND_SYSTEM.md | `resource-loader.ts:1213-1240` | | agentDir 或 `.pi/`（须受信任），同名不合并，项目覆盖用户 |
| packages | `core/package-manager.ts` 2,766 + `package-manager-cli.ts` 1,142 | | npm:/git:/本地路径；全局装 `~/.pi/agent/npm`，`--local` 写项目 settings；`pi` manifest 显式资源 + 约定目录；资源过滤语法 `!pattern` / `+path` / `-path`；包身份按 npm 名/git URL/绝对路径去重（`docs/packages.md:29-127`） |

---

## 8. 上下文管理

### 8.1 compaction（`core/compaction/compaction.ts` 1,104 + branch-summarization.ts 380）

- 触发：`contextTokens > contextWindow - reserveTokens`（默认 reserve 16,384 / keepRecent 20,000，`docs/compaction.md:29-35`）。检查点：工具结束后下一轮前、新 prompt 前、底层 run 结束后的溢出恢复（`docs/compaction.md:37`）。
- 算法：在投影后的条目上从新往旧累计 token，达到 keepRecentTokens 后在**合法切点**切；合法切点是 user/assistant/bashExecution/custom_message/branch_summary，**绝不切在 toolResult**（`compaction.ts:351-365`、`findCutPoint` 446-506）。若单个 user 跨度就超预算，会在 assistant 处切（split turn，`isSplitTurn`），并生成两份摘要合并（`findTurnStartIndex` 412）。
- 摘要：结构化固定格式（Goal/Constraints/Progress/Key Decisions/Next Steps/Critical Context），并把 `<read-files>`/`<modified-files>` 追加在末尾；第二次压缩用 UPDATE 指令保留旧摘要内容（`compaction.ts:507-583`）。
- 序列化：`serializeConversation` 把消息转成 `[User]:` / `[Assistant]:` / `[Assistant tool calls]:` / `[Tool result]:` 文本，thinking 丢弃，tool 结果截断 2000 字符（`docs/compaction.md:276-291`）。
- 落盘：`CompactionEntry{summary, firstKeptEntryId, tokensBefore, usage?, details?, fromHook?, systemMessage?}`；`systemMessage` 是压缩边界的完整 prompt/工具快照，成为压缩后上下文的领头 system 消息（`session-manager.ts:91-104`，`docs/session-format.md:123-137`）。
- 重复压缩的摘要起点是上一次的 `firstKeptEntryId`，不是 compaction 条目本身（`docs/compaction.md:83`）。retain-none 时 `firstKeptEntryId = 自身 id`。
- 溢出/长度恢复顺序：持久化最终 assistant 消息 → `turn_end` → `agent_end` → 追加 `context_edit` 省略 → 压缩成功则追加 compaction → 重新发起运行（`docs/compaction.md:89-98`）。
- 分支摘要：`/tree` 切分支时对离开的分支按共同祖先收集条目、按 token 预算从新到旧截取、生成摘要，写成 `BranchSummaryEntry{fromId, summary, usage?, details?, fromHook?}`（`branch-summarization.ts`，`docs/compaction.md:169-232`）。
- 扩展 hook：`session_before_compact` 可 cancel 或提供自定义摘要；`session_compact_failed`；`session_before_tree`（`docs/compaction.md:293-415`）。

### 8.2 context_edit

追加式修改"某个早期条目的上下文贡献"，不改变原始条目（`session-manager.ts:174-180`，`docs/session-format.md:139-147`）。`replacement` 是 `{content} | null`（不是直接给 content，注意文档里的省略）。同一 target 多条编辑取分支上最新者；非 null 替换只改内容、保留原角色与元数据。

### 8.3 token 估算

`estimateTokens`（`compaction.ts:298-349`）按角色分别估算；图片按固定 `ESTIMATED_IMAGE_CHARS = 4800` 字符折算（`compaction.ts:276`）。上下文 token 优先用最近一次 assistant 的 provider usage，再对后续消息做估算（`estimateContextTokens` 196）。Kotlin 版必须复刻"优先真实 usage + 估算增量"这一点，否则压缩触发点会明显偏离。

---

## 9. 周边能力（可裁剪度很高）

| 能力 | 文件 | 行数 | 说明 | 裁剪建议 |
|---|---|---|---|---|
| HTML 导出 | `core/export-html/index.ts` 318 + ansi-to-html 258 + tool-renderer 174 + template/vendor | 750 | 内嵌模板 + CSS/JS，把会话渲染成单文件 HTML；扩展工具可注册 HTML renderer | DEFER |
| JSONL 导出 | `core/session-export.ts` | 45 | 导出当前分支，parentId 重排 | 随 RPC `export_html`/`/export` 一起做，成本低 |
| /share | `modes/interactive/session-share.ts` + `core/radius.ts` | 224+11 | Radius artifact 或 GitHub gist | DROP |
| bug report | `core/bug-report.ts` 375 + upload 37 + crash-log 170 + `modes/interactive/bug-report.ts` 298 | 880 | 生成 zip、上传 radius.pi.dev | DROP |
| telemetry / version check | `core/telemetry.ts` 13、`utils/version-check.ts` 109 | 122 | 安装/更新匿名上报 | DROP |
| cache warmer / cache stats | `core/cache-warmer.ts` 453 + cache-stats 174 | 627 | 依据模型 `promptCache` 寿命与预估节省（≥$0.05）在空闲/流式结束后重放请求保温 | DROP（纯省钱优化，不影响正确性） |
| 虚拟模型 | `core/virtual-models.ts` 238 | 238 | 请求级路由（分类器/负载均衡） | DEFER |
| 扩展 MCP | `extensions/mcp/*` | 4,258 | MCP 客户端（stdio/streamable HTTP/OAuth/资源/暴露策略/TUI） | DEFER，风险高 |
| codemode | `extensions/codemode/*` + pi-codemode | 1,474+3,037 | QuickJS worker 里跑 JS 调工具 | DROP（JVM 无等价轻量方案） |
| tool_search | `extensions/tool-search/tool.ts` | 262 | 检索未声明工具并激活 | 仅在实现 MCP/codemode 后才有意义 |
| llama.cpp 扩展 | `extensions/llama/*` | 1,577 | 本地 GGUF 路由器管理、TUI、HuggingFace 下载 | DROP |
| 图片处理 | `utils/image-*.ts`、`photon.ts`、`exif-orientation.ts`、`mime.ts` | ~800 | 缩放（Lanczos3）、EXIF 方向、转 JPEG/PNG、worker 线程 | MUST-lite（read 工具送图需要；可用 ImageIO 替代） |
| 剪贴板/终端能力 | `utils/clipboard*.ts`、`terminal-setup` 相关 | ~500 | OSC52/命令行剪贴板、内联图片协议探测（在 pi-tui） | 随 TUI 决定 |
| 包管理 CLI | `package-manager-cli.ts` 1,142 + `package-manager.ts` 2,766 | 3,908 | install/remove/update/list/config、npm/git、锁、并发 | DEFER/DROP |

---

## 10. 与现有用户数据兼容性

本机实测（`~/.pi/agent`）：

| 文件/目录 | 现状 | 兼容要求 |
|---|---|---|
| `settings.json` | 1,075 字节，13 个 npm 包声明、`deviceId`、compaction、theme、`extensions:["+builtin:mcp"]` | **必须能读**；写回必须保留未修改字段与未知键（只写脏字段），与真 pi 行为一致 |
| `auth.json` | 5 个 provider：2 个 `api_key`，3 个 `oauth`（含 clientId/scopes/scope） | 必须能读两种凭据类型；写必须 0600 且加锁 |
| `models.json` | 2 个自定义 provider（`self-host`、`Milous-Plan`，均 `openai-completions`），9 个模型 | 必须能读 `providers.*.{baseUrl,api,apiKey,models[]}` |
| `models-store.json` | 146KB 目录缓存 + `.lock` 目录 | 可忽略（重新拉取），但要能容忍存在 |
| `sessions/` | 45 个项目目录；单会话 JSONL 以 v3 header 开头 | **双向兼容是硬要求**：Kotlin 写的会话要能被真 pi 打开，反之亦然 |
| `mcp.json` | 4 个 server（1 个 URL+headers、2 个 stdio+exposure:deferred、1 个 OAuth） | 若不实现 MCP，必须忽略此文件而不是报错 |
| `trust.json` | `{"/abs/path": true, ...}`（也允许 false/null） | 读：最近祖先命中即决定；写：与 pi 共存时不要互相覆盖 |
| `skills/` | 软链到 `/home/user/agent-config/skills`（6 个 skill） | skills 加载必须跟随符号链接 |
| `prompts/` | 空 | — |
| `bin/` | `fd`、`chrome-debug`、`qqmail` | fd 可直接复用；rg 用系统 `/usr/bin/rg` |
| `keybindings.json` | 不存在 | 按默认键位实现即可 |
| `themes/` | 无 `~/.pi/agent/themes`（主题选 `dark`） | 内置 dark/light 即可 |
| `extensions/` | 空目录（所有扩展来自 npm 包） | 决定扩展策略前可只报 diagnostic |

完整格式见 `docs/analysis/pi-coding-agent-formats.md`。

---

## 11. TUI 耦合分析

### 11.1 规模构成

| 部分 | 行数 | 内容 |
|---|---|---|
| `interactive-mode.ts` | 7,082 | 主控：TUI 挂载（875-945）、事件渲染（3363-3764）、扩展 UI（2633-3005）、键位/提交（3024-3357）、slash 命令（4878-7063）、登录/OAuth（5761-6403）、导出/share/bug（6403-6770）、会话树/选择器（5290-5760） |
| components/ | 11,661 | 47 个组件；最大：tree-selector 1,428、easter-egg-3d 1,292、session-selector 1,045、settings-selector 1,009、config-selector 951、model-selector 421、tool-execution 408、armin 384 |
| theme/ | 2,261 | theme.ts 1,086（颜色解析/OKHSL/gamut 映射）、system-theme 655（终端取色 + 对比度算法）、controller 253、tokens 121、schema 146 |
| `pi-tui`（外部） | 19,623 | 核心 13,776（alt-screen 1,797、tui 1,543、keys 1,455、utils 1,402、autocomplete 858、terminal-image 769、main-screen 656、terminal 630、stdin-buffer 480...）+ 组件 5,847（editor 2,473、markdown 1,047、input 501...） |

### 11.2 最小可用集合

若要在 Kotlin 里做一个"够用"的交互式界面，必须实现的最小集合（按依赖顺序）：

1. 终端控制：raw mode、光标/清行、窗口尺寸、SIGWINCH、宽字符宽度（CJK/emoji 双宽）、ANSI 样式与复位。
2. 行编辑器：插入/删除/移动、历史、多行（Shift+Enter）、粘贴大段文本、`@` 文件补全、`/` 命令补全、Tab 补全；CJK 输入法光标定位（pi 用 `CURSOR_MARKER` 机制，`docs/tui.md:48`）。
3. 流式 transcript：用户消息、assistant 文本（增量）、thinking（可折叠）、工具调用/结果块（可折叠）、diff 渲染、错误块、compaction/branch summary 块；行内 Markdown（标题/列表/代码块/链接）。
4. 状态区：footer（cwd/会话/模型/上下文占用/累计用量与成本）、编辑器边框颜色表达 thinking level、working 指示器。
5. 选择器：模型、thinking、会话（resume，含搜索/重命名/删除）、设置、trust、登录方式。
6. 队列与中断：Enter 发送（流式中=steering）、Alt+Enter=followUp、Esc=abort 并把队列内容放回编辑器、Alt+Up=取回队列、Ctrl+O 展开工具输出、Ctrl+T 切换 thinking。
7. 扩展 UI 的最小实现：select/confirm/input/editor/notify/setStatus/setWidget（约 8 个方法）。

**不在此集合内、可以整体丢掉的**：fullscreen/alt-screen 模式与鼠标事件、内联图片渲染（kitty/iterm2 协议）、OSC 8 超链接、语法高亮、mermaid、LaTeX、剪贴板图片粘贴、external editor、/share、/bug、/changelog、/hotkeys 帮助页、first-time-setup 向导、启动动画/3D/easter egg、theme `system` 自动取色（用固定 dark/light 即可）、自定义主题热重载、tmux/termux 适配、Windows 键位差异。

### 11.3 工作量估计（Kotlin）

| 做法 | 估计 | 风险 |
|---|---|---|
| 完全自研（对齐 pi-tui 行为） | 12–18k 行 | 高：宽字符/IME/终端差异是长期体力活；首批 bug 会集中在中文输入和窄终端 |
| 自研精简版（只做"最小可用集合"，regular 模式，无鼠标/图片） | 6–9k 行 | 中：功能明显少于 pi，但日常可用 |
| 基于 JLine3（LineReader + 补全 + 终端能力） + 自写 transcript/选择器 | 4–6k 行 | 中低：编辑器部分几乎免费；Markdown/表格/折叠仍需自写 |
| 基于 Lanterna / Mordant | 4–7k 行 | 中：Lanterna 全屏模型与 pi 的"滚动 transcript"心智不同，改造量大 |

**结论：交互式 TUI 不是一个"顺手做掉"的模块**。即使采用 JLine3，也还有 4–6k 行；并且它的正确性只能靠人肉体验与 pty 脚本，难以像 RPC 那样自动验收。

---

## 12. Node/Bun 能力清单与 JVM 替代方案

| Node/Bun 能力 | 使用点 | JVM 替代 | 风险 |
|---|---|---|---|
| `child_process.spawn` + detached + 进程树 kill | `core/tools/bash.ts:112-163`，`utils/shell.ts:killProcessTree`，`utils/child-process.ts`（cross-spawn） | `ProcessBuilder` + `ProcessHandle.descendants()` + `destroyForcibly()`；进程组 kill 需 `setsid` 或 JNA `killpg` | 中：`bash` 工具的"abort 杀掉 npx/子 shell 全部后代"是硬需求，JVM 需显式实现 |
| `node:fs` 同步/异步 | 遍布 | `java.nio.file`（`Files.*`），`FileChannel` 追加写 | 低 |
| 文件锁（proper-lockfile，跨进程） | auth/settings/trust/models-store/package-manager | `FileChannel.tryLock()`（同 JVM/跨进程可用，NFS 语义弱）或 mkdir 原子锁 | 中：需与真 pi 共存时互不破坏，锁协议不同也会"看起来能跑但可能撞车"；实际上两个进程同时写同一 JSON 概率低 |
| `node:readline`（JSONL 明确禁用，因为会切 U+2028/U+2029） | 会话列表读文件、grep 输出解析 | 自写按 `\n` 切分的 UTF-8 流读取器；`BufferedReader.readLine` 还会在孤立 `\r` 处断行，严格 JSONL 建议自写 | 低 |
| `undici`（HTTP 调度/代理/超时/连接池） | `core/http-dispatcher.ts` | `java.net.http.HttpClient`（HTTP/2、异步、BodyHandlers） | 低-中：SSE 流式需 `ofInputStream` + 自行解析 |
| `node:worker_threads` | 图片缩放 worker（`utils/image-resize.ts`） | 不需要（直接同步处理或 `Executors`） | 低 |
| `jiti`（进程内加载 TS/ESM，含虚拟模块别名） | 扩展加载 | **无等价物**。选择：Kotlin SPI、GraalJS、或 Node 侧车 | 高：这是扩展兼容的根本障碍 |
| `quickjs-wasi` | codemode | GraalJS（重）或无 | 高（建议放弃） |
| `@silvia-odwyer/photon-node`（WASM 图像处理） | 图片缩放/EXIF | `BufferedImage` + `ImageIO` + TwelveMonkeys；EXIF 用 metadata-extractor | 低 |
| `minimatch` / `brace-expansion` | 包资源 glob、模型 scope glob | 自写 glob 匹配（注意 `**`、`{a,b}`、`!` 语义）；`FileSystems.getDefault().getPathMatcher("glob:...")` 语义不同，不能直接用 | 中 |
| `ignore`（.gitignore 语义） | skills/packages 扫描 | 自写 gitignore 解析器（注意 `!`、`/` 前缀、目录后缀） | 中：扫描结果直接影响发现哪些 skill/包 |
| `diff`（jsdiff） | edit 工具 unified patch、TUI diff | `java-diff-utils` | 低 |
| `yaml` | frontmatter 解析 | SnakeYAML / kaml | 低 |
| `semver` / `hosted-git-info` | 包版本与 git URL | 自写 | 低 |
| `chalk` | 诊断着色 | 手写 ANSI 或 Mordant/Jansi | 低 |
| `typebox`（JSON Schema 定义 + 校验） | 工具参数 schema、settings schema | 自定义 schema DSL + kotlinx.serialization `JsonElement` 校验；工具 schema 必须输出为合法 JSON Schema 给 Provider | 中：schema 形状影响模型行为，不能随手改 |
| `grok-mermaid` / `highlight.js` | 仅 TUI | 丢弃 | 无 |
| `uuidv7`（pi-ai 提供） | 会话 id | 自写（时间戳 + 随机） | 低 |
| `process.stdout.write` 接管 + 背压 | `core/output-guard.ts` | `System.out`/`FileDescriptor.out` + 自写背压队列 | 低 |

JVM 侧建议栈：沿用仓库已定的 Kotlin 2.4.x + JDK 21 + kotlinx-coroutines（事件流）+ kotlinx-serialization-json（严格 JSONL）+ OkHttp 5（Provider/HTTP，见 `docs/ARCHITECTURE.md`）；新增 JLine3（TUI 行编辑）、Clikt 或 picocli（CLI）、GraalVM native-image 或 jpackage（分发）。`java.net.http` 可作为不引入 OkHttp 时的备选。

---

## 13. 对 pi-agent / pi-ai 的依赖点清单

coding-agent 对两个底层包的依赖是**清晰且薄**的（除 TUI 外），这对 Kotlin 版是好消息。

### 13.1 pi-agent-core（2,539 行）

import 面：`Agent`、`setDefaultStreamFn`、`AgentMessage`、`ThinkingLevel`（`sdk.ts:1-3`、`agent-session.ts:33`）。

依赖语义（`packages/agent/src/agent.ts`）：

- `Agent` 提供一次 run 的循环：`prompt()`/`continue()`/`abort()`/`waitForIdle()`、`state.messages/tools/model/thinkingLevel`、`subscribe()`、steering/followUp 队列（`set steeringMode`/`steer`/`followUp`/`clear*`）。
- 可替换钩子（coding-agent 全部用了）：`beforeToolCall`、`afterToolCall`、`prepareRequest`、`finishTurn`、`prepareNextTurn`、`transformContext`、`streamFunction`、`toolExecution`（`agent-session.ts:653-940`）。
- `AgentEvent` 是会话事件的基础联合（`turn_start/turn_end/message_*/tool_execution_*`）。

**结论：pi-agent-core 的 Kotlin 等价物 ≈ 2.5–4k 行**（若 pi-agent 工作流已实现，coding-agent 只需在其上挂 hook）。

### 13.2 pi-ai（27,709 行，重头）

coding-agent 真正使用的子集：

- 类型：`Model`/`Api`/`Message`/`AssistantMessage`/`ToolResultMessage`/`SystemMessage`/`ImageContent`/`TextContent`/`ThinkingContent`/`ToolCall`/`Usage`/`Provider`/`ProviderHeaders`/`AuthResult`/`Credential`/`CredentialStore`/`Transport`/`RetryPolicy`/`ClientIdentity`。
- 流式入口：`streamSimple`/`completeSimple`（`sdk.ts:5`、`compaction.ts:25`）、`StreamFn`、`lazyStream`、`AssistantMessageEventStream`、`AssistantMessageEvent`（JSON 模式直接转发）。
- 会话相关工具：`getCurrentSystemMessage`、`getSystemMessageText`、`getCurrentTools`、`normalizeContext`、`contentText`、`combineUsage`。
- 重试/错误：`retryAssistantCall`、`retryDelayMs`、`isRetryableAssistantError`、`DEFAULT_MAX_AGENT_RETRY_DELAY_MS`。
- 其他：`calculateCost`、`modelsAreEqual`、`uuidv7`、`PI_ATTRIBUTION`、`formatUserAgent`、`clampThinkingLevel`、`getSupportedThinkingLevels`、`ModelThinkingLevelSchema`、`isModelType`、`getProviderEnvValue`。
- Provider 目录/配置：`providers/all`、`providers/model-schema`、`providers/radius-config`、`providers/openrouter`、`providers/compat-schema`、`utils/model-operations`、`utils/oauth-page`、`utils/retry`、`utils/provider-env`、`bun-oauth`、`bedrock-provider`、`auth`（OAuth 类型）。
- 模型目录：内置 provider 列表 + 定价 + contextWindow/maxTokens/thinking 能力 + `promptCache` 寿命 + `inputLimits`。

**结论：Kotlin 版不可能 1:1 复刻 27.7k 行的全 Provider 矩阵。** 但按当前用户实际使用面，**只需 2 个 API 协议**（`openai-completions` + `anthropic-messages`）覆盖 `opencode-go`（本机默认 provider，`packages/ai/src/api/openai-completions.ts:614` 证明其走 OpenAI 兼容）、`deepseek`、`self-host`、`Milous-Plan`；再加 SSE 解析、usage/cost、thinking 参数映射即可。OAuth（openai/xai/radius）可以只做 API key。

### 13.3 pi-tui（19,623 行）

90 处 import，全部集中在 `modes/interactive/`（唯一例外是 `src/core/resource-loader.ts:3` 用 `detectCapabilities`/`getTerminalColorMode` 做颜色能力探测，headless 场景可忽略）。可整体替换，但行为差异会直接体现在交互体验上。

### 13.4 pi-mcp / pi-codemode

`extensions/mcp/*` 用 `@earendil-works/pi-mcp` 的 client/oauth/config；`extensions/codemode/*` 用 `@earendil-works/pi-codemode` 的 worker/source/declarations。两者自成一个子系统，**不实现时只需保证不加载、不报错**。

---

## 14. 范围建议

### 14.1 MUST / SHOULD / DEFER 三档

估计口径：Kotlin 行数包含等价逻辑、数据类、序列化与必要测试脚手架；不含 UI 视觉打磨。Prompt 文本按原样搬运。

| 档 | 子系统 | 依据 | Kotlin 估计 | 风险 |
|---|---|---|---|---|
| **MUST** | CLI 骨架（`cli.ts`/`main.ts`/`args.ts` 语义、exit code、stdout/stderr 契约、`@file`、stdin、`--offline`） | `main.ts:574-999` | 0.8–1.2k | 低 |
| MUST | SessionManager（JSONL v1–v3 读写、树、投影、context_edit、fork/clone/continue/list/findById、延迟建文件、cwd 转义） | `session-manager.ts` | 2.0–2.6k | 中：格式细节多，双向兼容必须测 |
| MUST | AgentSession 核心（run/turn 循环、prompt 流程、队列、abort、事件、模型/thinking、工具注册与装载、system prompt patch 回放、bash 记录、统计） | `agent-session.ts` | 3.5–4.5k | 高：状态机复杂，易漏边界（settle/重试/压缩交错） |
| MUST | 工具 read/bash/edit/write + truncate + file mutation queue + bash executor + 进程树 kill | `core/tools/*`、`bash-executor.ts` | 1.6–2.2k | 中 |
| MUST | 工具 grep/find/ls（调 rg/fd） | `grep.ts`、`find.ts`、`ls.ts` | 0.9–1.3k | 中：外部二进制 + 输出格式/截断提示必须一致 |
| MUST | 系统提示词 sections + 上下文文件 + skills 注入 + prompt templates 展开 | `system-prompt.ts`、`resource-loader.ts`、`skills.ts`、`prompt-templates.ts` | 1.2–1.6k | 中：文本细节影响模型行为 |
| MUST | SettingsManager（两级合并、默认值、`+/-` 工具修饰、写回） | `settings-manager.ts`、`settings-defaults.ts`、`settings-schema.ts` | 0.9–1.3k | 中：合并规则易错 |
| MUST | auth.json + models.json + 模型解析（`--model` 规则、scope、thinking 后缀、默认回退）+ Provider 组合 + （最小）Provider 层 | `auth-storage.ts`、`model-runtime.ts`、`model-resolver.ts`、`provider-composer.ts` + Provider | 2.0–2.6k（+Provider 3–5k） | 高：Provider 流式协议/工具调用/thinking/usage 是深水区 |
| MUST | print + json 模式（严格 JSONL、事件压缩、header、背压、stdout 接管） | `print-mode.ts`、`json-event.ts`、`output-guard.ts`、`jsonl.ts` | 0.6–0.9k | 低-中 |
| MUST | compaction（阈值、切点、摘要格式、entry、溢出恢复）+ branch summary | `compaction/*` | 1.3–1.8k | 中：切点与重复压缩规则细节多 |
| MUST | 基础 settings/诊断输出（warning/error 到 stderr，非交互模式无模型即失败） | `main.ts:878-931` | 0.2k | 低 |
| **SHOULD**（方案 A 中列入首批） | RPC 模式全命令 + 扩展 UI 子协议 + 关闭语义 | `rpc-mode.ts`、`rpc-types.ts` | 1.2–1.6k | 中：协议面宽，但可自动验收 |
| SHOULD | 最小交互式 TUI（见 §11.2） | `interactive-mode.ts` + components | 4–9k（取决于方案） | 高 |
| SHOULD | 资源体系其余部分：themes 加载、包声明解析（只读不安装）、trust.json、`SYSTEM.md`/`APPEND_SYSTEM.md` | `resource-loader.ts`、`trust-manager.ts` | 0.8–1.2k | 低-中 |
| SHOULD | HTML 导出 + `/export` jsonl | `export-html/*`、`session-export.ts` | 0.8–1.2k | 中：模板/ANSI→HTML 需要打磨 |
| **DEFER** | 扩展体系（TS 兼容或 Kotlin SPI） | `core/extensions/*` + `extensions/*` | SPI 2.5–4k / Node 侧车 4–6k | 极高：生态断裂 |
| DEFER | MCP | `extensions/mcp/*` | 3–4k | 高：协议 + OAuth + 暴露策略 |
| DEFER | package manager（npm/git 安装、update、锁） | `package-manager*.ts` | 2.5–4k | 高，且价值低于扩展运行时本身 |
| DEFER | 虚拟模型、classifier/image 模型调用、cache warmer、模型目录远端刷新 | `virtual-models.ts`、`model-runtime.ts` 部分 | 1–2k | 中 |
| **DROP** | codemode、tool_search、llama.cpp 扩展、share/bug/telemetry/version-check、easter egg/armin/3D、mermaid/LaTeX/语法高亮、全屏+鼠标、内联图片、self-update、Windows 自更新、first-time-setup、changelog 展示 | 见 §9、§11.2 | 0 | 无（但要显式文档化"没有"） |

### 14.2 "一部分"的三个边界方案

#### 方案 A：headless 内核（推荐先做）

范围：上述 MUST 全部；SHOULD 中的 RPC 模式 + 资源体系其余部分（themes 只读、trust、SYSTEM.md）；不做 TUI、不做扩展、不做 MCP/codemode/包管理/HTML 导出。

- 预计：**15–20k 行 Kotlin**（不含 Provider 层）；若把最小 Provider 层（OpenAI 兼容 + Anthropic 两种协议）也算进来，**18–25k**。单人全职 10–14 周，2 人 6–8 周；按"业余 + agent 辅助"节奏 3–5 个月。
- 风险：中。
  - 会话格式双向兼容（可用真 pi 生成样本做黄金测试）。
  - 事件流 JSON 形状（可对比 `pi --mode json` 输出逐字节 diff，忽略时间戳/id）。
  - Provider 流式的工具调用增量解析（Anthropic 的 input_json_delta、OpenAI 的 tool_calls 分片）是已知难点。
- 可验证完成标准（建议写成 CI 级验收）：
  1. `pi -p "列一下 src 目录"` 在真实仓库跑通，退出码 0，stdout 只有最终回答。
  2. `pi --mode json` 输出首行为合法 session header，随后事件能被现有 `jq` 示例解析；对固定 mock provider，事件序列与真 pi 的差异仅为时间戳/随机 id。
  3. 用 `examples/rpc-client.ts` 或自写 Python 客户端跑通 RPC：prompt→流式文本→`agent_settled`；`get_state`/`get_entries`/`get_tree`/`compact`/`set_model`/`bash` 全部返回结构化数据。
  4. 会话互操作：Kotlin 产生 `~/.pi/agent/sessions/...jsonl` 能被真 pi `--session` 打开并正确续聊；真 pi 产生的会话能被 Kotlin `--continue` 打开，模型/thinking 恢复正确。
  5. compaction：构造超阈值会话，触发自动压缩后 `buildSessionContext` 结果中旧消息被 summary 替代、保留区从 `firstKeptEntryId` 开始；再压缩一次不丢上次保留区。
  6. 工具行为：`read` 的截断提示、`edit` 的多编辑/CRLF/BOM、`bash` abort 杀进程树、`grep/find/ls` 的 limit 与提示语与真 pi 输出一致（字符串级断言）。
  7. 设置：读本机真实 `settings.json`/`models.json`/`auth.json` 不报错，默认模型解析为 `opencode-go/deepseek-v4.1-flash`。

#### 方案 B：A + 最小交互式 TUI

范围：A + §11.2 的 7 项最小集合（建议基于 JLine3），regular 模式（用终端自身回滚），无鼠标/图片/全屏。

- 预计：A + **4–9k 行**（JLine3 方案 4–6k；自研 6–9k）。追加 4–8 周。
- 风险：高。终端行为差异、宽字符、IME、窄终端、Mac/Linux/SSH 差异都会变成长期 bug 来源；这些 bug 难以自动回归。
- 可验证完成标准：
  1. pty 脚本（`expect`/`pexpect` 或 tmux 管道）完成：启动 → 输入 prompt → 看到流式文本与工具块 → 回答结束 → `/quit` 退出码 0。
  2. 中文输入（粘贴中文 + 单行编辑 + 光标移动）在 80/120/200 列下不串行、不错位。
  3. Esc 中断：运行中按 Esc 后 `agent_settled` 到达，排队消息回到编辑器。
  4. `/model`、`/thinking`、`/session`、`/compact`、`/new`、`/resume` 六个命令可用；`/resume` 能列出 45 个真实项目目录下的会话并成功打开。
  5. 大小窗口切换/resize 不崩、不残留半行。

#### 方案 C：A + B + 扩展兼容

两个子方案，必须先选：

- **C1 Kotlin SPI**：定义 JVM 插件接口（`registerTool`、`registerCommand`、`input`/`context`/`tool_call`/`tool_result`/`before_agent_start` 等 8–12 个 hook，`ctx.ui` 子集），用 ServiceLoader/JAR 加载。
  - 估计 +2.5–4k 行，2–4 周。
  - 风险：**与现有 13 个 npm 扩展、6 个 skill 包、builtin:mcp 完全不兼容**；用户的 pi-subagents/pi-memory/pi-chrome 等全部失效。
- **C2 Node 侧车**：保留一个小 Node/Bun 进程，用 pi 的扩展 loader 加载 TS 扩展，通过 JSONL 与 Kotlin 主进程通信（本质是把 RPC 反过来）。
  - 估计 +4–6k 行（协议、生命周期、错误恢复、UI 桥），4–8 周。
  - 风险：**保留了 Node 运行时依赖**，与"Kotlin 重写"目标冲突；双进程调试成本高；扩展的同步 API（`pi.getActiveTools()` 等）跨进程要重新设计。
- 可验证完成标准（共用）：用本机真实 `settings.json` 加载 13 个包中的至少 3 个（例如 pi-tool-display、pi-rewind、pi-web-access 各代表"渲染器/会话改写/工具"），注册的命令与工具在 Kotlin 版可用；扩展抛错不拖垮主进程。

### 14.3 推荐

**推荐：先做方案 A，再视情况做方案 B；方案 C 暂不做，但在 A 的设计里预留扩展点。**

理由（按权重）：

1. **A 是唯一"可机器验收"的部分**。RPC/JSON 协议有外部客户端示例与类型定义（`rpc-types.ts`），会话格式有 `session-format.md` 与真实样本，工具行为有确定字符串输出。这让"重写是否正确"不依赖人肉试用，特别适合当前这种多人/多 agent 协作的移植工程。
2. **A 与现有 pi 可共存**，不要求用户立刻切换日常工具。用户当前 pi 生态（13 个包 + MCP + skills）继续可用，Kotlin 版作为"内核/脚本化/未来 GUI 底座"逐步成熟。
3. **A 的单位工作量收益最高**：MUST 里约 15–20k 行覆盖了 pi 作为"编码 agent"的全部实质能力（模型、工具、会话、上下文、压缩）。剩下的 57k 行是终端体验与生态，边际收益递减而风险陡增。
4. **B 值得做，但必须晚于 A**。TUI 的正确性标准是主观的，且一旦先做 TUI，所有协议/格式问题会被 UI 掩盖，返工成本更高。
5. **C 的收益/成本比最差，且是战略选择而非工程选择**。两台路线都不"干净"：C1 直接丢弃用户现有生态，C2 则等于承认必须带着 Node。**在用户明确回答"Kotlin 版是否要替代日常 pi"之前，不要动 C。** 如果答案是要替代，那么现实建议是 C2（Node 侧车）而不是 C1，因为生态价值远大于进程纯净度。

**一句话结论**：把 coding-agent 切成"内核（MUST，值得全量移植）/ 交互（SHOULD，做最小集）/ 生态（DEFER，等战略决策）"三块；先交付 A 并用真 pi 做黄金对照，是风险最低、最快能看到可验证成果的路径。

### 14.4 需要用户拍板的问题

1. Kotlin 版定位：替代日常 pi（则必须解决扩展兼容），还是内核/实验/未来 GUI 底座（则可长期共存）？
2. Provider 范围：只支持 OpenAI 兼容 + Anthropic 两种 API（覆盖当前全部在用模型），还是要求 OAuth/Bedrock/Google 等？
3. 会话格式：要求"真 pi 能打开 Kotlin 会话"（双向兼容，成本 +10–15%）还是只要求"Kotlin 能读真 pi 会话"（单向，低成本）？
4. 交互界面：是否接受 regular 模式（终端自身回滚）+ 无鼠标/无内联图片的最小 TUI？
5. 扩展策略：接受"Kotlin 版没有扩展生态"，还是必须桥接 Node？
6. 分发形态：需要单文件可执行（GraalVM native / jpackage）还是 `java -jar` 可接受？启动时间要求（JVM 冷启动 100–300ms，native 20–50ms）？
