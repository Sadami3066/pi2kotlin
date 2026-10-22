# pi-coding-agent 兼容格式规范（Kotlin 实现照做版）

目标：给 Kotlin 实现者一份"照做即可与现有 pi 1.3.0 互操作"的精确格式清单。每条给出 `文件:行` 依据与真实样本。

兼容等级约定：

- **R（读兼容）**：能正确解析既有文件，不需要能写。
- **W（写兼容）**：写出的文件能被真 pi 正确解析。
- **P（协议兼容）**：线上/管道格式逐字段一致。

凡是标 **R+W** 的，必须做双向黄金测试（用真 pi 生成、Kotlin 读取与写回、再用真 pi 打开）。

---

## 1. 数据根与路径规则

### 1.1 配置目录

| 路径 | 内容 | 依据 |
|---|---|---|
| `$PI_CODING_AGENT_DIR` 或 `~/.pi/agent` | agent 目录（默认） | `src/config.ts:605-612`，`src/cli/args.ts:462` |
| `<agent-dir>/settings.json` | 用户级设置 | `docs/configuration.md:13` |
| `<agent-dir>/auth.json` | API key 与 OAuth 凭据（0600） | `src/core/auth-storage.ts:64-79` |
| `<agent-dir>/models.json` | 自定义 Provider/模型/覆盖 | `docs/configuration.md:16` |
| `<agent-dir>/models-store.json`（+`.lock/`） | 动态模型目录缓存 | `src/core/models-store.ts:54-60` |
| `<agent-dir>/mcp.json` | MCP server | `docs/mcp.md:32` |
| `<agent-dir>/mcp-auth.json` | MCP OAuth 凭据 | `docs/mcp.md:129` |
| `<agent-dir>/mcp-cache.json` / `mcp.log` | MCP 缓存与日志 | 本机实测；`docs/mcp.md:98` |
| `<agent-dir>/trust.json` | 项目信任决策 | `src/core/trust-manager.ts:17` |
| `<agent-dir>/keybindings.json` | 键位覆盖 | `docs/keybindings.md:9` |
| `<agent-dir>/AGENTS.md` / `AGENTS.override.md` / `CLAUDE.md` | 全局上下文 | `src/core/resource-loader.ts:184-199` |
| `<agent-dir>/SYSTEM.md` / `APPEND_SYSTEM.md` | 替换/追加 system prompt | `src/core/resource-loader.ts:1213-1240` |
| `<agent-dir>/extensions/` | 用户扩展 | `docs/configuration.md:21` |
| `<agent-dir>/skills/` | 用户技能 | `docs/configuration.md:22` |
| `<agent-dir>/prompts/` | 用户 prompt 模板（旧名 `commands/` 会被迁移） | `src/migrations.ts:137-155` |
| `<agent-dir>/themes/` | 用户主题 | `docs/configuration.md:24` |
| `<agent-dir>/sessions/--<encoded-cwd>--/` | 会话 JSONL | `src/core/session-manager.ts:589-598` |
| `<agent-dir>/bin/` | 托管二进制（fd、rg） | `src/config.ts:639-643`，`src/utils/tools-manager.ts:11` |
| `<agent-dir>/npm/` | 包安装根（`node_modules`、`package.json`） | 本机实测，`docs/packages.md:33` |
| `<cwd>/.pi/...` | 项目级同名资源（需项目信任） | `docs/configuration.md:26-38` |
| `~/.agents/skills/`、`<cwd 祖先>/.agents/skills/` | Agent Skills 兼容目录（后者需信任） | `src/core/package-manager.ts:2464-2570`，`src/core/trust-manager.ts:181-197` |

### 1.2 环境变量

进程配置（`docs/environment-variables.md:77-100`，`src/config.ts:584-586`）：

| 变量 | 作用 |
|---|---|
| `PI_CODING_AGENT_DIR` | 覆盖 agent 目录 |
| `PI_CODING_AGENT_SESSION_DIR` | 覆盖会话目录（`--session-dir` 优先） |
| `PI_PACKAGE_DIR` | 覆盖包目录（Nix/Guix） |
| `PI_OFFLINE` | `1`/`true`/`yes` 关闭联网（并置 `PI_SKIP_VERSION_CHECK=1`，`src/main.ts:579-583`） |
| `PI_SKIP_VERSION_CHECK` | 关闭 pi.dev 版本检查 |
| `PI_TELEMETRY` | 覆盖安装遥测 |
| `PI_CODEMODE_MAX_WORKERS` | codemode worker 上限（默认 2） |
| `PI_CACHE_RETENTION` | `long` 启用长缓存 |
| `PI_SHARE_VIEWER_URL` / `PI_RADIUS_GATEWAY` | share/bug 上传地址 |
| `PI_HARDWARE_CURSOR` / `PI_CLIPBOARD_OSC52` / `PI_HYPERLINKS` / `PI_PROGRAM_STATUS` / `PI_IMAGE_PROTOCOL` / `PI_TRUE_COLOR` / `PI_TUI_ESC_TIMEOUT` | 终端能力覆盖 |
| `VISUAL` / `EDITOR` | external editor 回退 |
| `HTTP_PROXY` / `HTTPS_PROXY` | 代理 |

进程标记（`src/cli/setup.ts:7-9`，`src/rpc-entry.ts:7-9`）：`PI_CODING_AGENT=true`、`AI_AGENT=pi`。

工具子进程环境（仅 LLM 调用的 `bash`/`powershell`，不是用户 `!` 命令，`docs/environment-variables.md:20-49`，`src/core/tools/bash.ts:193-209`）：

| 变量 | 值 |
|---|---|
| `PI_SESSION_ID` | 当前会话 id |
| `PI_SESSION_FILE` | 会话绝对路径（内存会话不设） |
| `PI_PROVIDER` | 当前模型 provider |
| `PI_MODEL` | 当前模型 id |
| `PI_REASONING_LEVEL` | `off`/`minimal`/`low`/`medium`/`high`/`xhigh`/`max` |

注意：暴露前会先删除继承来的这 5 个变量，避免嵌套 pi 泄漏父会话信息（`bash.ts:194-198`）。

---

## 2. 会话 JSONL（R+W，最高优先级）

### 2.1 文件位置与命名

```
<agent-dir>/sessions/--<encoded-cwd>--/<ISO-8601 时间戳，[:.] 替换为 ->_<session-id>.jsonl
```

- 编码：`--` + (`cwd` 去掉开头 `/` 或 `\`，把 `/`、`\`、`:` 全部换成 `-`) + `--`（`src/core/session-manager.ts:589-593`）。
  例：`/home/whywood/文档` → `--home-whywood-文档--`。
- 文件名时间戳：`new Date().toISOString().replace(/[:.]/g,"-")`，例 `2026-10-22T01-40-58-902Z`（`session-manager.ts:1683-1685`）。
- 会话 id：`uuidv7()`（`session-manager.ts:264-266`）。自定义 id 必须匹配 `^[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?$`（`session-manager.ts:268-274`）。
- `--session-dir` / `PI_CODING_AGENT_SESSION_DIR` / `sessionDir` 可覆盖根目录；优先级 `--session-dir` > env > setting（`src/main.ts:691-696`）。

真实样本（本机）：

```
~/.pi/agent/sessions/--home-whywood-文档--/2026-10-22T01-40-58-902Z_01a16144-c396-7069-b1e0-072e1af0764b.jsonl
```

### 2.2 文件结构

严格 JSONL：一行一个 JSON 对象，`\n` 结尾。第一行必须是 header。条目**只追加不改写**（`createBranchedSession`/`_rewriteFile` 除外，见 2.7）。

```
line 1: SessionHeader
line 2..n: SessionEntry（append 顺序，即写盘顺序，不保证与树路径顺序一致）
```

**延迟建文件规则**（`session-manager.ts:1160-1202`）：内存里可以先有 header/model_change/thinking_level_change/system message 等，但只要还没出现 `role` 为 `user` 或 `assistant` 的 message 条目，就不创建文件；首次出现时才把内存中全部条目一次性写盘（`openSync(path,"wx")` + 逐行写），之后每条 append。**Kotlin 版必须复刻**，否则"打开 pi 不聊天也留下空会话文件"会让 `--continue`/`/resume` 行为变化。

### 2.3 SessionHeader

```json
{"type":"session","version":3,"id":"01a16144-c396-7069-b1e0-072e1af0764b","timestamp":"2026-10-22T01:40:58.902Z","cwd":"/home/whywood/文档"}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `type` | `"session"` | 固定 |
| `version` | number? | v1 无此字段；当前写 3（`session-manager.ts:41-50`） |
| `id` | string | uuidv7 或自定义 |
| `timestamp` | string | ISO 8601（`new Date().toISOString()`） |
| `cwd` | string | 会话工作目录（决定资源发现与分组） |
| `parentSession` | string? | fork/clone 时指向源会话文件路径（`session-manager.ts:1688-1695`） |

### 2.4 条目基类

```json
{"type":"<kind>","id":"<8 hex 或完整 uuid>","parentId":"<id 或 null>","timestamp":"<ISO8601>", ...}
```

- `id`：`randomUUID().slice(0,8)`，100 次冲突重试，仍冲突则用完整 UUID（`session-manager.ts:276-284`）。
- `parentId`：根条目为 `null`；`resetLeaf()`/`branchWithSummary(null,...)` 可产生多个根（`docs/session-format.md:209-222`）。
- `timestamp`：会话条目用 ISO 字符串；**消息体内的 `timestamp` 是 Unix 毫秒**（`docs/session-format.md:47`）。

### 2.5 条目类型全集（11 种）

`SessionEntry` 联合定义在 `session-manager.ts:183-194`。

#### (1) `message`

```json
{"type":"message","id":"a1b2c3d4","parentId":"prev1234","timestamp":"2026-10-22T01:40:59.547Z","message":{ ... AgentMessage ... }}
```

`message` 的 `role` 可以是：

通用（pi-ai）：`system`、`user`、`assistant`、`toolResult`。
coding-agent 扩展（`src/core/messages.ts:29-77`）：`bashExecution`、`custom`、`branchSummary`、`compactionSummary`。

**SystemMessage**（`core/messages.ts` 用 pi-ai 类型；真实样本第 4 行）：

```json
{"role":"system","content":"","sections":{"preamble":"You are an expert coding assistant operating inside pi, ...","tools":"<tools>\n- bash: ...\n</tools>","rules":"<rules>...","docs":"<docs>...","project_context":"<project_context>...","skills":"<skills>...","cwd":"<cwd>/home/whywood/文档</cwd>","pi_background_shell_policy":"<pi_background_shell_policy>...</pi_background_shell_policy>"},"toolsAdded":[{"name":"bash","description":"...","parameters":{}}],"timestamp":1792633264614}
```

本机真实会话的 section 键顺序为：`preamble, tools, rules, docs, project_context, skills, cwd, pi_background_shell_policy`（最后一个是扩展通过自定义 section 注入的，说明 sections 是开放的 `Record<string,string|null>`）。同一会话 `toolsAdded` 有 37 个工具。

| 字段 | 说明 |
|---|---|
| `content` | 结构化 prompt 时为空字符串；`before_agent_start` 强制整段 prompt 时为完整文本 |
| `sections` | `Record<string, string \| null>`；`null` 表示删除该 section（`src/core/system-prompt.ts:217-228`） |
| `toolsAdded` | `Tool[]`（name/description/parameters）——首次声明或新增工具 |
| `toolsRemoved` | `{name}[]`——移除工具 |
| `timestamp` | 毫秒 |

**UserMessage**：

```json
{"role":"user","content":[{"type":"text","text":"合并一下两个作业的pdf"}],"timestamp":1792633264614}
```

`content` 可以是 string 或 `(TextContent|ImageContent)[]`。

**AssistantMessage**（真实样本第 5、8、10 行）：

```json
{"role":"assistant","content":[{"type":"thinking","thinking":"...","thinkingSignature":"reasoning_content"},{"type":"toolCall","id":"call_00_...","name":"bash","arguments":{"command":"..."}}],"api":"anthropic-messages","provider":"anthropic","model":"claude-sonnet-4-5","usage":{...},"stopReason":"toolUse","timestamp":1792633265000}
```

可选字段：`responseModel`、`responseId`、`providerThinkingLevel`、`thinkingLevel`、`diagnostics`、`rawStopReason`、`errorMessage`、`endTurn`、`deferred`（`docs/message-types.md:119-160`）。`stopReason` ∈ `pending|stop|length|toolUse|error|aborted|deferred`；`pending` 不落盘（`docs/message-types.md:145`）。

**ToolResultMessage**：

```json
{"role":"toolResult","toolCallId":"call_00_e3v0...","toolName":"bash","content":[{"type":"text","text":"total 8864\n..."}],"isError":false,"timestamp":1792633267694}
```

可选：`details`（工具自定义，渲染/压缩文件统计用）、`usage`（嵌套模型调用）、`nestedCalls`（有界元数据）。`nestedCalls` 结构见 `docs/message-types.md:177-194`。

**BashExecutionMessage**（`!` 命令与 RPC bash，`core/messages.ts:29-40`）：

```json
{"role":"bashExecution","command":"git status","output":"...","exitCode":0,"cancelled":false,"truncated":false,"fullOutputPath":"/tmp/pi-bash-xxx.log","excludeFromContext":false,"timestamp":1792633267000}
```

`exitCode` 可为 `undefined`（被杀/取消）。`excludeFromContext:true` = `!!` 前缀，不发模型。

**CustomMessage**（扩展注入，参与上下文，`core/messages.ts:46-53`）：

```json
{"role":"custom","customType":"my-ext","content":"注入文本","display":true,"details":{},"timestamp":1792633267000}
```

**BranchSummaryMessage / CompactionSummaryMessage**（不是直接落盘的条目类型，而是由 `branch_summary`/`compaction` 条目在构建上下文时生成，`core/messages.ts:100-120`）：

```json
{"role":"branchSummary","summary":"...","fromId":"abc123","timestamp":1792633267000}
{"role":"compactionSummary","summary":"...","tokensBefore":50000,"timestamp":1792633267000}
```

**内容块**（`docs/message-types.md:13-66`）：

```json
{"type":"text","text":"...","textSignature":"<opaque>"}
{"type":"image","data":"<base64>","mimeType":"image/png"}
{"type":"thinking","thinking":"...","thinkingSignature":"<opaque>","redacted":true}
{"type":"toolCall","id":"call_x","name":"bash","arguments":{...},"thoughtSignature":"<opaque>","namespace":"<openai responses namespace>"}
```

**Usage**（`docs/message-types.md:71-90`）：

```json
{"input":100,"output":20,"cacheRead":0,"cacheWrite":0,"cacheWrite1h":0,"reasoning":0,"totalTokens":120,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}}
```

`reasoning` 已包含在 `output` 内，勿重复相加；`cacheWrite1h` 是 `cacheWrite` 中 1 小时保留的子集。

#### (2) `thinking_level_change`

```json
{"type":"thinking_level_change","id":"9fa7ddd7","parentId":"66035c39","timestamp":"...","thinkingLevel":"max"}
```

#### (3) `model_change`

```json
{"type":"model_change","id":"66035c39","parentId":null,"timestamp":"...","provider":"opencode-go","modelId":"deepseek-v4.1-flash"}
```

#### (4) `usage`

```json
{"type":"usage","id":"f6g7h8i9","parentId":"...","timestamp":"...","kind":"cache_warm","provider":"anthropic","model":"claude-sonnet-4-5","usage":{...},"note":"可选"}
```

不参与 LLM 上下文，但计入会话 token/成本；未知 `kind` 按普通 usage 处理（`docs/session-format.md:113-121`）。

#### (5) `compaction`

```json
{"type":"compaction","id":"f6g7h8i9","parentId":"e5f6g7h8","timestamp":"...","summary":"...","firstKeptEntryId":"c3d4e5f6","tokensBefore":50000,"usage":{...},"details":{"readFiles":[],"modifiedFiles":[]},"fromHook":false,"systemMessage":{"role":"system","content":"","sections":{...},"toolsAdded":[...],"timestamp":1792635000000}}
```

- `firstKeptEntryId` 必填。retain-none 时等于自身 id（`docs/compaction.md:131`）。
- `systemMessage` 是压缩边界的完整 prompt/工具快照；旧会话可能没有。
- `details`：默认实现为 `{readFiles:string[], modifiedFiles:string[]}`；扩展可自定义。

#### (6) `branch_summary`

```json
{"type":"branch_summary","id":"g7h8i9j0","parentId":"a1b2c3d4","timestamp":"...","fromId":"f6g7h8i9","summary":"...","details":{"readFiles":[],"modifiedFiles":[]},"usage":{...},"fromHook":false}
```

`parentId` 是新分支继续的条目，`fromId` 是被放弃的叶子（`docs/session-format.md:149-162`）。

#### (7) `custom`

```json
{"type":"custom","id":"h8i9j0k1","parentId":"...","timestamp":"...","customType":"my-extension","data":{"count":42}}
```

不参与上下文。虚拟模型状态用 `customType:"pi.virtual-model-state"`、`data:{provider,modelId,state}`（`src/core/virtual-models.ts:29-37`）。

#### (8) `custom_message`

```json
{"type":"custom_message","id":"i9j0k1l2","parentId":"...","timestamp":"...","customType":"my-ext","content":"注入上下文","display":true,"details":{}}
```

参与上下文（转成 user 消息）。`display` 控制 TUI 是否展示。

#### (9) `context_edit`

```json
{"type":"context_edit","id":"g6h7i8j9","parentId":"...","timestamp":"...","targetId":"c3d4e5f6","replacement":null}
```

**注意**：非 null 的形式是 `{"content": <UserMessage.content | AssistantMessage.content | ToolResultMessage.content | CustomMessage.content>}`，不是直接写 content（`session-manager.ts:174-180`）。语义：

- `replacement:null` → 目标条目从模型上下文中省略。
- 非 null → 只替换内容，保留原角色与元数据。
- assistant/toolResult 的字符串替换会被归一化成一个 text 块（`session-manager.ts:519-541`）。
- 同一 target 多条编辑，取当前分支上最后一条；分支相对（`docs/session-format.md:147`）。

#### (10) `label`

```json
{"type":"label","id":"j0k1l2m3","parentId":"...","timestamp":"...","targetId":"a1b2c3d4","label":"checkpoint-1"}
```

`label: undefined` 表示清除（写文件时该键省略或值为 null，解析端两种都要容忍）。

#### (11) `session_info`

```json
{"type":"session_info","id":"k1l2m3n4","parentId":"...","timestamp":"...","name":"Refactor auth module"}
```

`name` 缺省表示清除。`--name` / `/name` 写此条目（`src/main.ts:710-717`，`session-manager.ts:1304-1316`）。

### 2.6 版本迁移（R）

`migrateToCurrentVersion`（`session-manager.ts:337-352`）：

- v1（无 version）→ v2：按行顺序补 `id`（8 hex）与 `parentId`（前一条），compaction 的 `firstKeptEntryIndex` 转成 `firstKeptEntryId`。
- v2 → v3：`message.role === "hookMessage"` 改名为 `"custom"`。

Kotlin 读入时必须能处理 v1/v2/v3；写出时统一 v3。**不要**把迁移结果立刻回写（真 pi 也是只在内存迁移，写回时按需；`loadEntriesFromFile` 会在文件末尾追加前确保有换行，`session-manager.ts:662-676`）。

### 2.7 树与分支操作（W）

- `branch(branchFromId)`：把 leaf 移到某条目（`session-manager.ts:1579-1590`）。
- `resetLeaf()`：leaf 置 null，下一个条目成为新根（1591-1599）。
- `branchWithSummary(branchFromId, summary, details?, fromHook?)`：追加 `branch_summary` 条目（1600-1631）。
- `createBranchedSession(leafId)`（fork/clone 实现，1632-1754）：
  1. 取 leaf→root 路径；
  2. 去掉 `label` 条目并重新串接 `parentId`（label 上的子树要接回）；
  3. compaction 的 `firstKeptEntryId` 若指向被去掉的 label，替换为其后继；
  4. 新 header（新 id、新时间戳、`parentSession`=旧文件路径）；
  5. 把路径上目标的 label 以新 `label` 条目追加到末尾；
  6. 若分支里已有 user/assistant 消息则立即重写文件，否则延迟到首次消息。

### 2.8 上下文重建（R，必须逐字对齐）

三步（`session-manager.ts:439-586`）：

**第 1 步 `buildContextEntries(entries, leafId)`**
1. 从 leaf 沿 `parentId` 回溯到根得到路径（`buildSessionPath`，390-417）。
2. 找路径上**最新**的 compaction 条目。
3. 无 compaction → 返回整条路径。
4. 有 compaction → `[compaction] + 旧路径中从 firstKeptEntryId（含）到 compaction 之前的条目（跳过 system message） + compaction 之后的全部条目`。

**第 2 步 `buildSessionProjection`**
1. 从**完整路径**（不是裁剪后的）取最后的 `model_change`/`thinking_level_change` 作为当前 model/thinking（`getSessionContextSettings`，418-437）。
2. 对裁剪后的条目集合，收集 `context_edit`（按 targetId 取后出现者）。
3. 每条目转消息（`sessionEntryToContextMessages`，439-474）：
   - `message` → 原消息（`content` 为 null 时补 `""`/`[]`，容忍损坏文件）；
   - `custom_message` → CustomMessage；
   - `branch_summary`（有 summary）→ BranchSummaryMessage；
   - `compaction` → `[systemMessage?, compactionSummary]`；
   - 其它 → 空。
4. 应用 context_edit（`projectContextEntry`，519-541）。
5. 注意：若裁剪后 index>0 处还有更老的 compaction 条目（因为它的原始 id 落在保留区间），它的消息贡献为空（只保留条目本身供 UI 渲染，`session-manager.ts:552-575`）。

**第 3 步 `buildSessionContext`**：`{messages, thinkingLevel, model}`。

### 2.9 会话列表与信息（R）

`SessionInfo`（`session-manager.ts:229-243`）：`path,id,cwd,name?,parentSessionPath?,created,modified,messageCount,firstMessage,allMessagesText`。

- `list(cwd)`：只看 `<agent-dir>/sessions/--encoded-cwd--/*.jsonl`；`listAll()` 扫全部项目目录。
- 排序：`modified` 降序（`sortSessionInfos`，920-922）。
- 读 header 有界扫描：最多 1MB、4KB 缓冲（604-613）；损坏文件不阻断其它会话。
- `findById(cwd,id)`：先读 header 精确匹配（1874+）。
- `continueRecent`：取该 cwd 目录下 mtime 最新且 header.cwd 匹配的会话（`findMostRecentSession`，749-769）。
- 删除：真 pi 用系统 `trash` 命令（`docs/session-format.md:16-20`），Kotlin 版可以只做文件删除但要在帮助里说明。

### 2.10 会话不变量（易错点）

1. `timestamp` 两种：条目 ISO 字符串，消息体内毫秒数字。混用会导致 UI/排序错误。
2. `parentId` 必须指向已存在的条目 id，否则 `getTree` 会把该条目当根（`docs/session-format.md:211-216`）。
3. 同一次压缩重复写入会产生两个 compaction 条目；`buildContextEntries` 只认路径上最新的，但 `tokensBefore` 必须按压缩前投影重新计算（`docs/compaction.md:83`）。
4. 系统消息可以出现在会话中间（工具集/prompt 变化），不可假设只有第一条。
5. `usage`/`custom`/`label`/`session_info`/`context_edit` 不产生上下文消息，但 UI/统计需要它们。

---

## 3. settings.json（R+W）

### 3.1 位置与优先级

- 用户级：`<agent-dir>/settings.json`；项目级：`<cwd>/.pi/settings.json`（需项目信任，`sessionDir` 例外）。
- 合并：标量/对象 **项目覆盖用户**（对象深合并）；资源数组 **合并**（`src/core/settings-manager.ts:143-158`）。
- 写回：加文件锁后读-改-写，**只覆盖被修改的字段/嵌套键**，未修改字段与未知字段保留（`persistScopedSettings`，`settings-manager.ts:601-628`）。
- 目录不存在则 `mode 0o700` 创建（`settings-manager.ts:186-200` 区域）。
- 读取失败会记录 error 并在启动时输出 diagnostic，但不会崩（`settings-diagnostics.ts`）。

### 3.2 顶层字段全集

依据 `src/core/settings-schema.ts:206-470`（`export const SettingsSchema`，`additionalProperties:true`）与 `src/core/settings-defaults.ts:4-67`。默认值写成 `——` 表示无默认。

| 字段 | 类型 | 默认 | 含义 |
|---|---|---|---|
| `$schema` | string | — | 编辑器校验用，写 `https://pi.dev/schemas/settings.schema.json` |
| `lastChangelogVersion` | string | — | 用户看过的 changelog 版本 |
| `defaultProvider` | string | — | 启动 provider |
| `defaultModel` | string | — | 启动模型 id |
| `defaultThinkingLevel` | enum(off,minimal,low,medium,high,xhigh,max) | `medium`（代码默认，schema 未写默认） | 启动思考级别 |
| `modelThinkingLevels` | `Record<"provider/modelId", level>` | — | 每模型启动思考级别 |
| `transport` | `auto\|sse\|websocket\|websocket-cached` | `auto` | AI Provider 传输偏好 |
| `sessionShareDestination` | `auto\|gist\|radius` | `auto` | `/share` 目标 |
| `steeringMode` | `all\|one-at-a-time` | `one-at-a-time` | steering 投递策略 |
| `followUpMode` | `all\|one-at-a-time` | `one-at-a-time` | follow-up 投递策略 |
| `theme` | string | — | 主题名；`light/dark` 表示跟随 |
| `compaction` | object | 见下 | |
| `branchSummary` | object | 见下 | |
| `retry` | object | 见下 | |
| `hideThinkingBlock` | boolean | `false` | 隐藏 thinking |
| `showCacheMissNotices` | boolean | `false` | 缓存命中提示 |
| `externalEditor` | string | — | Ctrl+G 外部编辑器，优先于 `$VISUAL`/`$EDITOR` |
| `shellPath` | string | — | 自定义 shell（支持 `~`） |
| `quietStartup` | boolean\|`"header"` | `false` | `true` 全隐藏；`"header"` 只留头部 |
| `defaultProjectTrust` | `ask\|always\|never` | `ask` | **仅全局可设** |
| `shellCommandPrefix` | string | — | 每条 shell 命令前缀 |
| `npmCommand` | string[] | `["npm"]`（未写默认） | npm 调用 argv |
| `collapseChangelog` | boolean | `false` | 精简 changelog |
| `enableInstallTelemetry` | boolean | `true` | 安装/更新遥测 |
| `enableAnalytics` | boolean | `false` | 分析数据 |
| `trackingId` | string | — | 分析 id |
| `packages` | `PackageSource[]` | `[]` | 包声明 |
| `extensions` | string[] | `[]` | 扩展路径 |
| `deviceId` | string | — | 稳定安装 UUID（仅全局） |
| `skills` | string[] | `[]` | 技能路径（旧对象格式会迁移） |
| `prompts` | string[] | `[]` | prompt 模板路径 |
| `themes` | string[] | `[]` | 主题路径 |
| `enableSkillCommands` | boolean | `true` | 注册 `/skill:name` |
| `terminal` | object | 见下 | |
| `images` | object | 见下 | |
| `enabledModels` | string[] | — | 模型循环 scope（同 `--models`） |
| `defaultTools` | string[] | `["read","bash","edit","write"]`（`DEFAULT_TOOL_NAMES`，`settings-manager.ts:85`） | 初始工具选择，支持 `+name`/`-name` |
| `doubleEscapeAction` | `tree\|fork\|none` | `tree` | 空编辑器双击 Esc |
| `treeFilterMode` | `default\|no-tools\|user-only\|labeled-only\|all` | `default` | `/tree` 初始过滤 |
| `thinkingBudgets` | object | — | 各 thinking level 的 token 预算 |
| `editorPaddingX` | number(0-3) | `0` | 编辑器左右 padding |
| `outputPad` | `0\|1` | `1` | 正文左右 padding |
| `autocompleteMaxVisible` | number(3-20) | `5` | 补全可见条数 |
| `showHardwareCursor` | boolean | `false` | 用终端硬件光标 |
| `markdown` | object | 见下 | |
| `warnings` | object | 见下 | |
| `codemode` | object | 见下 | |
| `sessionDir` | string | — | 会话目录 |
| `httpProxy` | string | — | 代理（仅全局） |
| `httpIdleTimeoutMs` | number\|"disabled" | `300000` | HTTP 空闲超时 |
| `cacheWarming` | `off\|streaming\|idle` | `streaming` | 缓存保温（仅全局） |
| `websocketConnectTimeoutMs` | number\|"disabled" | `15000` | WS 连接超时 |
| `tuiMode` | `regular\|fullscreen` | `fullscreen` | TUI 模式 |
| `fullscreenExitOutput` | `transcript\|resume-hint` | `transcript` | 全屏退出输出 |
| `fullscreenScrollbar` | `auto\|always\|hidden` | `auto` | 全屏滚动条 |
| `fullscreenCopyOnSelect` | boolean | `true` | 全屏选中即复制 |
| `fullscreenWheelScrollLines` | number\|`auto` | `auto` | 滚轮行数（1-100 截断） |
| `queueMode` | 旧字段 | — | 迁移到 `steeringMode` |
| `websockets` | 旧字段 | — | 迁移到 `transport` |

嵌套对象：

```jsonc
"compaction": { "enabled": true, "reserveTokens": 16384, "keepRecentTokens": 20000,
                "modelOverrides": { "provider/modelId": { "reserveTokens": 100000, "keepRecentTokens": 30000 } } }
"branchSummary": { "reserveTokens": 16384, "skipPrompt": false }
"retry": { "enabled": true, "maxRetries": 3, "baseDelayMs": 2000, "maxAgentDelayMs": 60000,
           "provider": { "timeoutMs": 300000, "maxRetries": 0, "maxRetryDelayMs": 60000 } }
"terminal": { "showImages": true, "imageWidthCells": 60, "clearOnShrink": false,
              "showTerminalProgress": false, "hyperlinks": "auto", "images": "auto", "trueColor": "auto" }
"images": { "autoResize": true, "blockImages": false }
"markdown": { "codeBlockIndent": "  ", "mermaid": "streaming" }
"warnings": { "anthropicExtraUsage": true }
"codemode": { "mode": "on", "inlineBudget": 3000 }
```

设置迁移（`migrateSettings`，`settings-manager.ts:397-457`，读写时会做，写回为迁移后形态）：

- `queueMode` → `steeringMode`（仅当无 `steeringMode`）。
- `websockets:true/false` → `transport:"websocket"/"sse"`（仅当无 `transport`）。
- `skills` 旧对象 `{enableSkillCommands,customDirectories}` → `enableSkillCommands` + `skills:string[]`。
- `retry.maxDelayMs` → `retry.provider.maxRetryDelayMs`。

### 3.3 `defaultTools` 合并算法（易错，必须照做）

依据 `settings-manager.ts:85-142`，优先级：内置默认 → 用户 settings → 项目 settings → `--tools` / `--no-tools` / `--no-builtin-tools`。

- 列表全是普通名字 → **替换**继承的选择。
- 列表只含 `+name`/`-name` → 在继承选择上增删；两种不能混（混用报错 `tool names cannot be mixed with +name or -name entries`）。
- `+name`/`-name` 只接受精确工具名，不接受 `*`（`settings-manager.ts:97-108`）。
- 空数组 = 关闭全部内置工具（但不清扩展/SDK 工具）。

### 3.4 `packages` 条目

`PackageSourceSchema`（`settings-schema.ts:171-190`）：

```jsonc
"packages": [
  "npm:pi-web-access@0.42.0",
  "npm:@juicesharp/rpiv-todo",
  { "source": "npm:pi-hud", "skills": ["-skills/release/SKILL.md"], "extensions": ["-extensions/hud.ts"] },
  { "source": "npm:pi-rewind", "autoload": false, "extensions": ["+src/index.ts"] }
]
```

- `source` 前缀：`npm:`、`git:`、`github:`、`http(s)://`（当 git 处理）、`ssh:`、`builtin:`；裸路径/相对路径/`file:` 为本地。
- 过滤语法（对象形式的 `extensions`/`skills`/`prompts`/`themes`）：`[]` 表示该类型全不加载；省略表示全加载；`!pattern` 排除 glob；`+path` 精确包含；`-path` 精确排除（`docs/packages.md:111-119`）。
- `autoload:false`：从空开始，仅显式 pattern 生效（项目条目可当"用户包过滤增量"）。

### 3.5 真实样本（本机，必须能读）

```json
{
  "defaultModel": "deepseek-v4.1-flash",
  "defaultProvider": "opencode-go",
  "defaultThinkingLevel": "max",
  "hideThinkingBlock": true,
  "lastChangelogVersion": "1.0.0",
  "packages": ["npm:pi-web-access@0.42.0", "npm:@juicesharp/rpiv-ask-user-question", "...13 条..."],
  "theme": "dark",
  "compaction": { "enabled": true },
  "extensions": ["+builtin:mcp"],
  "deviceId": "08ddb45b-1f4f-468f-86b9-61b67b088e25"
}
```

---

## 4. auth.json / models-store.json（R+W）

### 4.1 auth.json

路径 `<agent-dir>/auth.json`，`JSON.stringify(data, null, 2)`，权限 **0600**（仅创建时设置，已有权限不强改，`auth-storage.ts:36-37`），跨进程 `proper-lockfile` 加锁（同步 10 次重试、异步 30s 上限 + EPERM 重试，`auth-storage.ts:81-156`）。

结构：`Record<providerId, Credential>`（`packages/ai/src/auth/types.ts:17-37`）：

```jsonc
{
  "deepseek":  { "type": "api_key", "key": "sk-...", "env": { "OPTIONAL": "provider-scoped env" } },
  "opencode-go": { "type": "api_key", "key": "sk-..." },
  "xai":       { "type": "oauth", "access": "...", "refresh": "...", "expires": 1792654542271 },
  "openai":    { "type": "oauth", "access": "...", "refresh": "...", "expires": 1792026121915,
                 "clientId": "oaiapp_...", "scopes": ["chatgpt.tokens.use.direct","email","offline_access","openid","profile"] },
  "radius":    { "type": "oauth", "access": "...", "refresh": "...", "expires": 1792719580653, "scope": "gateway offline_access" }
}
```

- `api_key`：`key` 可省略（纯环境变量场景）；`env` 是 provider 级环境值（Cloudflare account/gateway 等）。
- `oauth`：`access`/`refresh`/`expires`(毫秒)；其余键（`clientId`/`scopes`/`scope`/`tokenType`…）原样保留，`OAuthCredentials` 允许 `[key:string]:unknown`。
- 凭据文件不存在时创建 `{}`（`auth-storage.ts:75-79`）。
- 旧 `~/.pi/agent/oauth.json` 与 `settings.json.apiKeys` 会迁移进 auth.json，并把 oauth.json 改名为 `.migrated`（`src/migrations.ts:21-73`）。

**凭据解析优先级**（`docs/models.md:23`）：运行时 `--api-key` > auth.json > `models.json` 的 `apiKey` > Provider 环境变量/云凭据。

### 4.2 models-store.json

`FileModelsStore`：`Record<providerId, ModelsStoreEntry>`（pi-ai 类型），与 auth 共用同一套锁实现（`models-store.ts:44-60`）。运行时缓存动态模型目录；**读不到不影响启动**，可整体忽略并重新拉取。

---

## 5. models.json（R）

路径 `<agent-dir>/models.json`。字段全集见 `docs/models-configuration.md`（自动生成自源码）。最高频结构：

```jsonc
{
  "$schema": "https://pi.dev/schemas/models.schema.json",
  "providers": {
    "self-host": {
      "baseUrl": "http://47.113.218.61:28080",
      "api": "openai-completions",
      "apiKey": "sk-...",                       // 也支持 "$ENV" / "${ENV}" / "!command"
      "headers": { "X-Foo": "$FOO" },
      "authHeader": false,                      // true = 以 Authorization: Bearer 发送 key
      "models": [
        { "id": "Airis-AI", "name": "Airis-AI", "reasoning": true, "input": ["text"],
          "contextWindow": 262144, "maxTokens": 32768 }
      ]
    },
    "anthropic-proxy": { "baseUrl": "https://proxy.example.com" }   // 只改 baseUrl，模型/认证沿用内置
  }
}
```

关键字段（`docs/models-configuration.md:13-104`）：

- provider：`name`、`baseUrl`、`apiKey`、`api`、`oauth:"radius"`、`headers`、`compat`、`authHeader`、`models[]`、`modelOverrides`。
- model：`id`(必填)、`name`、`api`、`baseUrl`、`reasoning`、`thinkingLevelMap`、`input:("text"|"image")[]`（默认 `["text"]`）、`inputLimits`、`cost{input,output,cacheRead,cacheWrite,tiers[]}`、`promptCache{short,long}`、`contextWindow`（默认 128000）、`maxTokens`（默认 16384）、`samplingParams`、`samplingParamsByThinkingLevel`、`headers`、`compat`。
- `modelOverrides`：按模型 id 覆盖内置/扩展模型；对象字段（如 `cost`）逐键合并，其它字段整体替换；未知 id 忽略。
- 值解析：`apiKey`/`headers` 支持 `$NAME`、`${NAME}`、字面量、`!command`；`$$`/`$!` 转义为字面 `$`/`!`；命令在请求时执行且不缓存（`docs/models.md:65`，`src/core/resolve-config-value.ts`）。
- `compat` 是"已验证差异"清单，按 API 分组（openai-completions / openai-responses / anthropic-messages / bedrock / mistral），字段见 `docs/models-configuration.md:105-187`。Kotlin 版若只支持两种 API，只需实现这两组字段。

真实样本见 §3.5 同目录 `models.json`（2 个 `openai-completions` provider、9 个模型，`contextWindow`/`maxTokens` 都显式给出）。

---

## 6. mcp.json / mcp-auth.json / mcp-cache.json（R，可忽略）

`<agent-dir>/mcp.json` 与 `<cwd>/.pi/mcp.json`（项目需信任；项目条目可只写 `enabled`/`exposure`/`toolExposure` 覆盖用户条目）格式（`docs/mcp.md:44-83`）：

```jsonc
{
  "mcpServers": {
    "context7": { "url": "https://mcp.context7.com/mcp", "headers": { "CONTEXT7_API_KEY": "..." }, "description": "..." },
    "chrome-devtools": { "command": "chrome-devtools-mcp", "args": ["--browserUrl","http://127.0.0.1:9222"], "exposure": "deferred" },
    "radius": { "url": "https://radius.pi.dev/mcp", "auth": { "provider": "radius" } }
  }
}
```

- stdio：`command`、`args`、`env`、`cwd`；HTTP：`url`、`headers`、`oauth`；`type` 可选（`stdio|http|streamable-http`，`sse` 拒绝）。
- 通用：`timeout`(秒，默认 60)、`enabled`(默认 true)、`exposure`(默认 `codemode`)、`toolExposure`、`description`。
- 工具名 `mcp__<server>__<tool>`，非字母数字下划线替换为 `_`，冲突加 hash 后缀（`docs/mcp.md:77`）。
- `env`/`headers` 值可用 `${NAME}` 或整值 `!command`。
- `mcp-auth.json`：MCP OAuth 令牌，按 server 名 + URL 归属；`mcp-cache.json`：连接/工具缓存。两者都可整体忽略。

Kotlin 版若不实现 MCP：**只解析不连接**，且保证 `mcp.json` 存在时不报错、不影响其它启动路径。

---

## 7. trust.json（R+W）

路径 `<agent-dir>/trust.json`，结构 `Record<canonicalAbsPath, boolean|null>`（`src/core/trust-manager.ts:33-43`）：

```json
{ "/home/whywood": true, "/home/whywood/dev/skills": true, "/home/whywood/project": true }
```

- 查找：从 `cwd` 规范化（realpath）后向上找最近一个 `true`/`false` 条目；`null` 表示"在该目录显式清空继承的信任"（`trust-manager.ts:63-80`）。
- 需要信任的项目资源：`settings.json`、`mcp.json`、`extensions/`、`skills/`、`prompts/`、`themes/`、`SYSTEM.md`、`APPEND_SYSTEM.md`（`trust-manager.ts:43-53`）。
- 写入用文件锁（`proper-lockfile`）。与真 pi 共存时**不要重写整个文件**，只更新自己的键。
- 非交互模式遇到未信任但有上述资源的项目：默认不加载，并给 diagnostic（`src/core/project-trust.ts`）。

---

## 8. keybindings.json（R，写为可选项）

路径 `<agent-dir>/keybindings.json`，结构：`Record<actionId, string | string[]>`（空数组 = 禁用该动作）。依据 `docs/keybindings.md:9-32`。

```jsonc
{
  "$schema": "https://pi.dev/schemas/keybindings.schema.json",
  "app.session.new": "ctrl+shift+n",
  "app.session.tree": ["ctrl+shift+t", "alt+shift+t"],
  "tui.altScreen.pageUp": []
}
```

- 键名语法：`modifier+key`，modifier ∈ `ctrl|shift|alt|super`（可组合）；key ∈ `a-z`、`0-9`、`escape|esc|enter|return|tab|space|backspace|delete|insert|clear|home|end|pageUp|pageDown|up|down|left|right`、`f1-f12`、符号 `` ` - = [ ] \ ; ' , . / ! @ # $ % ^ & * ( ) _ + | ~ { } : < > ? ``。
- 动作 id 两组：`app.*` 43 个（`src/core/keybindings.ts:26-170`，如 `app.interrupt`=escape、`app.clear`=ctrl+c、`app.exit`=ctrl+d、`app.thinking.cycle`=shift+tab、`app.model.cycleForward`=ctrl+p、`app.model.select`=ctrl+l、`app.tools.expand`=ctrl+o、`app.thinking.toggle`=ctrl+t、`app.session.toggleNamedFilter`=ctrl+n、`app.editor.external`=ctrl+g、`app.message.copy`=ctrl+x、`app.message.followUp`=alt+enter、`app.message.dequeue`=alt+up、`app.clipboard.pasteImage`=ctrl+v…）与 `tui.*` 94 个（来自 pi-tui，`packages/tui/src/keybindings.ts`）。
- 旧格式迁移：`migrateKeybindingsConfig`（`src/migrations.ts:157-172` 调用）。Kotlin 版若不实现 TUI，可只解析并忽略；但**必须容忍文件存在**。

---

## 9. themes/*.json（R）

位置：`<agent-dir>/themes/<name>.json`、`.pi/themes/`、settings `themes`、包。内置 `dark`/`light` 在 `src/modes/interactive/theme/*.json`，默认 `system`（`docs/themes.md:22,38`）。

```jsonc
{
  "$schema": "https://pi.dev/schemas/theme.schema.json",
  "name": "dark",
  "appearance": "dark",
  "vars": { "text": "okhsl(234 3% 89%)", "blueBg": "okhsl(233 41% 24%)" },
  "colors": { "accent": "violet", "border": "okhsl(231 57% 65%)", "text": "text", "userMessageBg": "blueBg",
              "toolPendingBg": "okhsl(229 5% 24%)", "mdHeading": "yellow", "syntaxKeyword": "blue",
              "thinkingOff": "okhsl(229 8% 49%)", "thinkingMax": "okhsl(20 99% 63%)", "bashMode": "okhsl(159 64% 65%)" },
  "export": { "pageBg": "okhsl(262 14% 16%)", "cardBg": "okhsl(264 13% 19%)", "infoBg": "okhsl(53 51% 24%)" }
}
```

- 必填：`name`（唯一、不能含 `/`、不能是 `system`）、`colors`（schema 规定必填角色）。
- 颜色写法 6 种：`#rgb`/`#rrggbb`、`oklch(L% C H)`、`okhsl(H S% L%)`、256 色索引（数字）、变量引用（`vars` 的键）、`""`（终端默认前景/背景）。变量可链式引用；缺失或循环引用 = 主题非法（`docs/themes.md:83-96`）。
- 5 个可选色有回退：`scrollbarTrack→muted`、`scrollbarThumb→text`、`searchMatchBg→selectedBg`、`searchMatchText→text`、`thinkingMax→thinkingXhigh`（`docs/themes.md:120-130`）。
- `export` 省略时由 `userMessageBg` 派生。
- 主题对象是严格的：未知顶层字段/未知颜色 token 会被拒绝（`docs/themes.md:81`）。
- 若 Kotlin 版只做 headless：**不需要解析主题**；但 HTML 导出需要 `colors`/`export` 的十六进制转换。

---

## 10. skills（R）

### 10.1 目录与文件

```
<skills-dir>/<skill-name>/SKILL.md     ← 目录形式（可移植形态）
<skills-dir>/<name>.md                ← 根目录下直接 .md 也当 skill（Pi 特有）
```

发现规则（`src/core/skills.ts:168-275`）：

1. 进入某目录，若含 `SKILL.md` → 该目录是技能根，**不再向下递归**（返回一个 skill）。
2. 否则：递归子目录找 `SKILL.md`；同时若 `includeRootFiles`（初始调用为 true）则把该目录直接的 `.md` 文件当 skill。
3. 跳过以 `.` 开头的条目与 `node_modules`；跟随符号链接（断链跳过）。
4. 忽略文件：扫描过程中逐层叠加 `.gitignore`/`.ignore`/`.fdignore`（`skills.ts:16,47-65`）。
5. 默认扫描 `<agent-dir>/skills`（source=user）与 `<cwd>/.pi/skills`（project），外加 `~/.agents/skills`、受信任项目的祖先 `.agents/skills`；再合并 settings `skills`、`--skill`、包资源（`package-manager.ts:2464-2570`）。
6. 同名冲突：保留先发现的，输出 collision diagnostic（`skills.ts:426-455`）。同一文件经符号链接重复出现则静默去重。

### 10.2 SKILL.md 格式

```markdown
---
name: pdf-tools
description: Extract text and tables from PDF files. Use when reading, converting, or inspecting PDFs.
disable-model-invocation: true
---

# 正文
...
```

frontmatter 字段（`src/core/skills.ts:67-72`）：

| 字段 | 规则 |
|---|---|
| `name` | 缺省 = 父目录名；必须 `^[a-z0-9-]+$`、不以 `-` 开头结尾、无连续 `--`、≤64 字符（校验失败只警告，仍加载，`skills.ts:92-112`） |
| `description` | **必填**且非空，≤1024 字符（为空则该 skill 不加载，`skills.ts:304-332`） |
| `disable-model-invocation` | `true` 时不进系统提示词，只能 `/skill:<name>` 调用 |
| 其它 | `license`/`compatibility`/`metadata`/`allowed-tools` 等原样保留、不解释 |

frontmatter 解析器：`utils/frontmatter.ts`（YAML，支持 `---` 包裹）。

### 10.3 进系统提示词的格式（W，必须逐字一致）

`formatSkillsForPrompt`（`skills.ts:358-388`）：

```text
The following skills provide specialized instructions for specific tasks.
Use the read tool to load a skill's file when the task matches its description.
When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.

<available_skills>
  <skill>
    <name>pdf-tools</name>
    <description>...</description>
    <location>/abs/path/pdf-tools/SKILL.md</location>
  </skill>
</available_skills>
```

- 该段作为 `skills` section 包在 `<skills>...</skills>` 内（`system-prompt.ts:174-182`）。
- `disableModelInvocation:true` 的 skill 被过滤掉。
- 若 `read` 工具隐藏（prepareLoadout），提示语改为 `Load a skill's file when...`，不提工具名（`system-prompt.ts:174-178`）。
- 用户输入 `/skill:name args` 展开后的消息文本格式（`src/core/agent-session.ts:166-188` 的 `parseSkillBlock`）：

```text
<skill name="pdf-tools" location="/abs/path/SKILL.md">
<完整 SKILL.md 正文>
</skill>

<args 作为用户请求>
```

---

## 11. prompt templates（R）

位置：`<agent-dir>/prompts/*.md`、`<cwd>/.pi/prompts/*.md`（**只加载直接子文件，不递归**）、settings `prompts`、包。文件名（去 `.md`）即命令名，例 `review.md` → `/review`（`src/core/prompt-templates.ts:134`）。

```markdown
---
description: Review staged git changes
argument-hint: "[focus]"
---
Review the staged changes. Focus on ${1:-correctness, security, and error handling}.
```

- `description` 缺省时取正文第一条非空行并截断到 60 字符 + `...`（`prompt-templates.ts:136-145`）。
- `argument-hint` 约定：`<必填>`、`[可选]`。

参数展开（`prompt-templates.ts:76-108`，先 shell 风格分词 `parseCommandArgs` 25-61）：

| 语法 | 结果 |
|---|---|
| `$1` `$2` … | 第 N 个位置参数（缺失 → 空串） |
| `$@` / `$ARGUMENTS` | 全部参数用空格连接 |
| `${N:-default}` | 第 N 个参数，空/缺失时用 default |
| `${@:-default}` / `${ARGUMENTS:-default}` | 全部参数，空时用 default |
| `${@:N}` | 从第 N 个开始（1-indexed，0 视为 1） |
| `${@:N:L}` | 从第 N 个开始取 L 个 |

替换只作用于模板文本，不对参数/默认值递归替换（`prompt-templates.ts:72-74`）。引号：`"..."` 与 `'...'` 保留空格；空引号 `""` 是有效空参数。

---

## 12. 上下文文件 / 系统提示词文件（R）

### 12.1 上下文文件（AGENTS/CLAUDE）

候选文件名顺序（`resource-loader.ts:184-199`）：

```
AGENTS.override.md, AGENTS.md, AGENTS.MD, CLAUDE.md, CLAUDE.MD
```

同目录命中第一个即止。加载顺序（`loadProjectContextFiles`，232-275）：

1. `<agent-dir>` 里的一份（全局）。
2. 从 `cwd` 向上直到根，每个目录一份；**由远到近**排列。
3. 去重（按绝对路径）；git 嵌套 worktree 的影子文件不重复加载（`findShadowedContextFile`，214-231）。
4. 不需要项目信任（`docs/configuration.md:47`）。
5. `--no-context-files` / `-nc` 关闭。

注入 system prompt 的格式（`system-prompt.ts:79-86`）：

```text
Project-specific instructions and guidelines:

<project_instructions path="/abs/AGENTS.md">
<文件内容>
</project_instructions>
```

多个文件之间用空行分隔；整段包进 `<project_context>...</project_context>` section。

### 12.2 SYSTEM.md / APPEND_SYSTEM.md

- 位置：`<agent-dir>/{SYSTEM,APPEND_SYSTEM}.md` 或 `<cwd>/.pi/{SYSTEM,APPEND_SYSTEM}.md`（项目需信任）。
- 同名不合并，项目文件**优先**（`docs/configuration.md:39`）。
- `SYSTEM.md` → 替换 `preamble`（`buildSystemPromptOptions.customPrompt`）。
- `APPEND_SYSTEM.md` → 追加为 `addendum` section（`system-prompt.ts:172`）。
- `--system-prompt` / `--append-system-prompt` 也可给文本或文件路径（`cli/args.ts:121-125`，`resource-loader.ts:167-182` 负责判断"是文本还是已存在文件"）。

### 12.3 系统提示词 section 生成顺序（W）

`buildSystemPromptSections`（`system-prompt.ts:128-193`）：

```
preamble            (无标签；customPrompt 或内置一句话)
tools               <tools>...</tools>           仅在非 customPrompt 时
rules               <rules>...</rules>           仅在非 customPrompt 时
docs                <docs>...</docs>             仅在非 customPrompt 时
addendum            <addendum>...</addendum>     APPEND_SYSTEM
project_context     <project_context>...</project_context>
skills              <skills>...</skills>
cwd                 <cwd>...</cwd>               （反斜杠转 /）
<自定义 section>     <name>...</name>             扩展传入
```

规则段默认结尾固定两条：`Be concise in your responses`、`Show file paths clearly when working with files`（`system-prompt.ts:122-123`）；工具 guideline 去重、按工具选择顺序拼接。

---

## 13. 扩展 manifest 与 API（R，兼容性取决于策略）

### 13.1 包 manifest（`package.json` 的 `pi` 键）

```jsonc
{
  "name": "my-pi-package",
  "keywords": ["pi-package"],
  "pi": {
    "extensions": ["./src/extension.ts"],
    "skills": ["./resources/skills"],
    "prompts": ["./resources/prompts/*.md"],
    "themes": ["./resources/themes/*.json"],
    "image": "https://.../preview.png",
    "video": "https://.../demo.mp4"
  },
  "peerDependencies": {
    "@earendil-works/pi-ai": "*", "@earendil-works/pi-agent-core": "*",
    "@earendil-works/pi-coding-agent": "*", "@earendil-works/pi-tui": "*", "typebox": "*"
  }
}
```

约定：无 `pi` manifest 时按 `extensions/`、`skills/`、`prompts/`、`themes/` 约定目录发现（`docs/packages.md:44-74`）。宿主提供的包必须放 `peerDependencies` 且 `"*"`，不能放 `dependencies`（否则告警，`resource-loader.ts:53-98`）。

### 13.2 扩展文件形态

- 单文件 `.ts`/`.js` 或目录（`index.ts`/`index.js`/`package.json` main）。
- 导出默认工厂：`export default function (pi: ExtensionAPI) {...}`，可 async（`docs/extensions.md:15-38`）。
- 生命周期：`session_start` 里建资源、`session_shutdown` 幂等释放；不要在工厂里起进程/定时器（`docs/extensions.md:56-60`）。
- 加载：jiti 在进程内转译，宿主包通过虚拟模块映射（`core/extensions/virtual-modules.ts`）。

### 13.3 ExtensionAPI（摘要，完整签名在 `core/extensions/types.ts:1592-1930`）

事件（`on`）：`project_trust`、`resources_discover`、`session_start`、`session_info_changed`、`session_before_switch`、`session_before_fork`、`session_before_compact`、`session_compact`、`session_compact_failed`、`session_shutdown`、`mcp_servers_change`、`session_before_tree`、`session_tree`、`context`、`context_with_system`、`cache_warming_decision`、`before_provider_request`、`before_provider_headers`、`after_provider_response`、`provider_stream_event`、`before_agent_start`、`agent_start`、`agent_end`、`agent_before_settle`、`agent_settled`、`ui_prompt_start`、`ui_prompt_end`、`turn_start`、`turn_end`、`message_start`、`message_update`、`message_end`、`tool_execution_start`、`tool_execution_update`、`tool_execution_end`、`model_select`、`thinking_level_select`、`tool_call`、`tool_result`、`user_bash`、`input`。

注册/操作：`registerTool`、`registerCommand`、`registerShortcut`、`registerFlag`、`getFlag`、`registerMessageRenderer`、`registerMarkdownTransformer`、`registerEntryRenderer`、`registerToolRenderer`、`sendMessage`、`sendUserMessage`、`appendEntry`、`setSessionName`/`getSessionName`、`setLabel`、`exec`、`getActiveTools`/`setActiveTools`/`getAllTools`、`getSettings`、`getCommands`、`setModel`、`getThinkingLevel`/`setThinkingLevel`、`registerProvider`、`unregisterProvider`、`registerMcpServer`/`unregisterMcpServer`/`getMcpServers`、`registerVirtualModel`/`unregisterVirtualModel`。

工具定义（`types.ts:579-670`）：`{name,label,description,parameters(JSON Schema),outputSchema?,promptSnippet?,promptGuidelines?,exposure?,namespace?,annotations?,execute(id,args,signal,onUpdate,ctx),renderCall?,renderResult?,prepareArguments?,prepareLoadout?,reachesTools?,constrainedSampling?,renderShell?}`。

扩展 UI 协议（RPC 下）：见 §14.5。

### 13.4 若不做 TS 兼容

只需保证：读到 13 个 npm 包声明时**不崩、不误删设置、能列出为"未支持"**；扩展目录里的 `.ts` 文件不被当作配置解析。

---

## 14. RPC 协议（P，逐字段）

### 14.1 帧

- 严格 JSONL：一条记录 = 一个 JSON 对象 + `\n`。
- 切分：**只按 LF**；CRLF 输入容忍去掉尾部 `\r`；**不得**把 U+2028/U+2029 当分隔符（`docs/rpc.md:50-56`，`src/modes/rpc/jsonl.ts:1-58`）。
- 读端要处理"一次 read 停在多字节字符中间"（UTF-8 解码器带状态）。
- stdout 只放协议；所有诊断/日志走 stderr（`takeOverStdout`，`src/core/output-guard.ts:51-70`）。
- 必须处理背压：客户端不读会被阻塞（Pi 侧 `waitForRawStdoutBackpressure`）。

### 14.2 命令（stdin）

全部命令都接受可选 `id`（string），响应原样回传（`rpc-types.ts:20-74`）：

```jsonc
{"id":"req-1","type":"prompt","message":"hi","images":[{"type":"image","data":"<b64>","mimeType":"image/png"}],"streamingBehavior":"steer"|"followUp"}
{"type":"steer","message":"...","images":[...]}
{"type":"follow_up","message":"...","images":[...]}
{"type":"abort"}
{"type":"clear_queue"}
{"type":"new_session","parentSession":"/path/to/parent.jsonl"}
{"type":"get_state"}
{"type":"set_model","provider":"anthropic","modelId":"..."}
{"type":"cycle_model"}
{"type":"get_available_models"}
{"type":"set_thinking_level","level":"high"}
{"type":"cycle_thinking_level"}
{"type":"get_available_thinking_levels"}
{"type":"set_steering_mode","mode":"all"|"one-at-a-time"}
{"type":"set_follow_up_mode","mode":"all"|"one-at-a-time"}
{"type":"compact","customInstructions":"focus on ..."}
{"type":"set_auto_compaction","enabled":true}
{"type":"set_auto_retry","enabled":true}
{"type":"abort_retry"}
{"type":"bash","command":"ls -la","excludeFromContext":false}
{"type":"abort_bash"}
{"type":"get_session_stats"}
{"type":"export_html","outputPath":"/tmp/x.html"}
{"type":"switch_session","sessionPath":"/path/s.jsonl"}
{"type":"fork","entryId":"abc123"}
{"type":"clone"}
{"type":"get_fork_messages"}
{"type":"get_entries","since":"abc123"}
{"type":"get_tree"}
{"type":"get_last_assistant_text"}
{"type":"set_session_name","name":"my-feature"}
{"type":"get_messages"}
{"type":"get_commands"}
```

`prompt` 的约束：
- 流式中必须给 `streamingBehavior`，否则失败响应。
- `@file` 不支持，必须走 `prompt` 的 `images`/正文。
- 输入展开：`/skill:name`、`/template` 在入队/发送前展开；扩展命令（`/cmd`）立即执行并返回 `disposition:"handled"`（`docs/rpc-commands.md:31-34`）。
- `data.disposition` ∈ `"handled" | "queued" | "started"`，含义分别是"扩展/输入处理器消费"、"运行中排队"、"接受并启动一次 run"（`docs/rpc-commands.md:40`）。

### 14.3 响应（stdout）

成功：

```jsonc
{"id":"req-1","type":"response","command":"prompt","success":true,"data":{"disposition":"started"}}
{"id":"req-2","type":"response","command":"abort","success":true}
```

失败：

```jsonc
{"id":"req-3","type":"response","command":"set_model","success":false,"error":"Model not found: invalid/model"}
{"type":"response","command":"parse","success":false,"error":"Failed to parse command: ..."}   // 无 id
```

各命令 data 形状见 `rpc-types.ts:116-245`；重点几个：

- `get_state`：`{model?, thinkingLevel, isStreaming, isCompacting, steeringMode, followUpMode, sessionFile?, sessionId, sessionName?, autoCompactionEnabled, messageCount, pendingMessageCount}`（`rpc-types.ts:96-109`）。
- `get_entries`：`{entries: SessionEntry[], leafId: string|null}`；`since` 不存在时失败。
- `get_tree`：`{tree: [{entry, children, label?, labelTimestamp?}], leafId}`。
- `get_session_stats`：`{sessionFile, sessionId, userMessages, assistantMessages, toolCalls, toolResults, totalMessages, tokens:{input,output,cacheRead,cacheWrite,total}, cost, contextUsage?:{tokens,contextWindow,percent}}`（`docs/rpc-commands.md:534-567`）。
- `compact`：`{summary, firstKeptEntryId, tokensBefore, estimatedTokensAfter, usage?, details}`。
- `bash`：`{output, exitCode, cancelled, truncated, fullOutputPath?}`；流式 `bash_execution_update{id?,delta}`。
- `get_commands`：`{commands:[{name, description?, source:"extension"|"prompt"|"skill", sourceInfo:{path,source,scope,origin,baseDir?}}]}`。

### 14.4 事件（stdout）

与 json 模式同一套事件（§15.2），区别：RPC **不**发 session header（用 `get_state` 拿）。RPC 独有：`bash_execution_update`、`extension_error`（`{extensionPath,event,error}`，`docs/json.md:190-194`）。

### 14.5 扩展 UI 子协议

Pi → 客户端（`rpc-types.ts:252-287`）：

```jsonc
{"type":"extension_ui_request","id":"uuid","method":"select","title":"...","options":["Allow","Block"],"timeout":10000}
{"type":"extension_ui_request","id":"uuid","method":"confirm","title":"...","message":"...","timeout":5000}
{"type":"extension_ui_request","id":"uuid","method":"input","title":"...","placeholder":"...","timeout":...}
{"type":"extension_ui_request","id":"uuid","method":"editor","title":"...","prefill":"..."}
{"type":"extension_ui_request","id":"uuid","method":"notify","message":"...","notifyType":"info"|"warning"|"error"}
{"type":"extension_ui_request","id":"uuid","method":"setStatus","statusKey":"...","statusText":"..."|undefined}
{"type":"extension_ui_request","id":"uuid","method":"setWidget","widgetKey":"...","widgetLines":[...]|undefined,"widgetPlacement":"aboveEditor"|"belowEditor"}
{"type":"extension_ui_request","id":"uuid","method":"setTitle","title":"..."}
{"type":"extension_ui_request","id":"uuid","method":"set_editor_text","text":"..."}
```

客户端 → Pi（仅 dialog 需要回，`rpc-types.ts:294-297`）：

```jsonc
{"type":"extension_ui_response","id":"uuid","value":"Allow"}
{"type":"extension_ui_response","id":"uuid","confirmed":true}
{"type":"extension_ui_response","id":"uuid","cancelled":true}
```

- dialog 超时由 Pi 侧自动按默认值 resolve，客户端无需计时（`docs/rpc-extension-ui.md:10`）。
- fire-and-forget 方法（notify/setStatus/setWidget/setTitle/set_editor_text）不期待响应。
- 降级清单（`custom()` 返回 undefined、`onTerminalInput` no-op、主题相关空实现等）见 `docs/rpc-extension-ui.md:12-25`。
- `ctx.mode === "rpc"` 且 `hasUI === true`；只有 `ctx.mode === "tui"` 才能用 `custom()`。

### 14.6 生命周期与关闭

- `prompt` 的 `success` 只表示"接受"，不代表完成；必须等 `agent_settled`（或 `agent_end` + 自己判断）才能认为空闲。
- 客户端应在发 prompt 前先订阅事件，避免错过快速完成。
- 关闭：客户端关 stdin → Pi dispose runtime 后退出；扩展也可 `ctx.shutdown()`（在命令后或 `agent_settled` 后执行）。
- 客户端还必须处理：子进程启动失败、意外退出、stderr 诊断、取消、自身超时；**不要把 stderr 当协议解析**。

---

## 15. json 模式输出（P）

### 15.1 结构

```
line 1: SessionHeader（与 §2.3 完全一致，但只用于本次 run 的会话快照）
line 2..n: 事件，一行一个
```

RPC 不发 header。写入方式与 RPC 相同（原始 stdout + 背压），诊断走 stderr（`src/modes/print-mode.ts:108-127`）。

### 15.2 事件全集与字段

基础事件（`docs/json.md:52-68`）：

| 事件 | 字段 |
|---|---|
| `agent_start` | 无 |
| `agent_end` | `messages`, `willRetry` |
| `agent_settled` | `aborted` |
| `turn_start` | 无 |
| `turn_end` | `message`, `toolResults` |
| `message_start` | `message` |
| `message_update` | `usage`, `assistantMessageEvent`（见 §15.3） |
| `message_end` | `message`（权威最终值） |
| `tool_execution_start` | `toolCallId`, `toolName`, `args` |
| `tool_execution_update` | `toolCallId`, `toolName`, `args`, `partialResult` |
| `tool_execution_end` | `toolCallId`, `toolName`, `result`, `isError`, `durationMs?` |

队列/状态：`queue_update{steering,followUp}`（全量当前队列）、`entry_appended{entry}`、`session_info_changed{name?}`、`thinking_level_changed{level}`。

压缩：`compaction_start{reason:"manual"|"threshold"|"overflow"}`；`compaction_end{reason,result?,aborted,willRetry,errorMessage?}`，`result={summary,firstKeptEntryId,tokensBefore,estimatedTokensAfter,usage?,details}`。

重试：`auto_retry_start{attempt,maxAttempts,delayMs,errorMessage}`、`auto_retry_end{success,attempt,finalError?}`；`summarization_retry_scheduled{attempt,maxAttempts,delayMs,errorMessage}`、`summarization_retry_attempt_start{source:"compaction",reason}` 或 `{source:"branchSummary"}`、`summarization_retry_finished`。

### 15.3 `message_update` 的线缆压缩（必须照做）

SDK 内部事件带累积快照；JSON/RPC 输出时（`src/modes/json-event.ts:20-61`）：

1. 删除顶层 `message` 字段；顶层只留 `type:"message_update"`、`usage`（该 assistant 响应最新的累积 usage）、`assistantMessageEvent`。
2. 每个 `assistantMessageEvent` 删除 `partial` 字段。
3. `toolcall_start` 额外补 `id` 与 `toolName`（从 `partial.content[contentIndex]` 取）。
4. `start`/`done`/`error` 通常被 agent loop 转成 `message_start`/`message_end`，但类型上仍允许出现在 `message_update` 里。

内层事件类型（`docs/json.md:74-90`）：`start`、`text_start{contentIndex}`、`text_delta{contentIndex,delta}`、`text_end{contentIndex,content}`、`thinking_start`、`thinking_delta`、`thinking_end`、`toolcall_start{contentIndex,id,toolName}`、`toolcall_delta{contentIndex,delta}`、`toolcall_end{contentIndex,toolCall}`、`done{reason,message}`、`error{reason,error}`。

重建规则：delta 只用于实时显示；`text_end`/`thinking_end`/`toolcall_end` 的 content 覆盖；最终以 `message_end.message` 为准。`usage` 在流式期间可能一直是 0（provider 不报）。

### 15.4 示例序列

```json
{"type":"agent_start"}
{"type":"turn_start"}
{"type":"message_start","message":{"role":"user","content":"Review this repository","timestamp":1733234401000}}
{"type":"message_end","message":{"role":"user","content":"Review this repository","timestamp":1733234401000}}
{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","...":"..."}}
{"type":"message_update","usage":{"input":100,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":101,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello "}}
{"type":"message_end","message":{"role":"assistant","...":"..."}}
{"type":"turn_end","message":{"role":"assistant","...":"..."},"toolResults":[]}
{"type":"agent_end","messages":[...],"willRetry":false}
{"type":"agent_settled","aborted":false}
```

---

## 16. print 模式输出（P）

- stdout：只输出最后一条 assistant 消息的 text 块，**每个 text 块后加一个 `\n`**（`src/modes/print-mode.ts:149-153`）；无其他内容。
- stderr：`stopReason==="error"` → `errorMessage` 或 `Request error`；`aborted` → 同理；`length` → `Response was truncated: the model reached its output token limit.`（print-mode.ts:145-157）。
- 退出码：`length`/`error`/`aborted` → 1，否则 0；捕获到异常 → 1 且 stderr 打消息（print-mode.ts:163-165）。
- 初始消息构成（`src/cli/initial-message.ts:22-43`）：`stdin 内容` + `@file 文本` + `messages[0]` 直接拼接（无分隔符），剩余 messages 依次作为后续 prompt。`@` 图片进 `initialImages`。
- stdout 接管：非 interactive 模式所有 `console.log` 会被重定向到 stderr（`output-guard.ts:51-70`），Kotlin 版等价实现 = 把普通日志固定写 stderr。

---

## 17. 内部文本协议（W，会进入会话文件并被真 pi 读取/渲染）

### 17.1 compaction summary 消息

`convertToLlm` 把 `compactionSummary` 消息转成 user 文本（`core/messages.ts:11-24,176-183`）：

```text
The conversation history before this point was compacted into the following summary:

<summary>
<summary 文本>
</summary>
```

### 17.2 branch summary 消息

`core/messages.ts:19-24,170-175`：

```text
The following is a summary of a branch that this conversation came back from:

<summary>
<summary 文本>
</summary>
```

### 17.3 bash 结果转 user 文本

`core/messages.ts:82-98`：

````text
Ran `<command>`
```
<output>
```
````

无输出 → `(no output)`；取消 → 追加 `\n\n(command cancelled)`；非 0 → `\n\nCommand exited with code N`；截断 → `\n\n[Output truncated. Full output: <path>]`。

### 17.4 压缩摘要的生成提示词（W，若要跨实现共享摘要）

- 首次：`SUMMARIZATION_PROMPT`（`core/compaction/compaction.ts:507-538`），固定格式 `## Goal` / `## Constraints & Preferences` / `## Progress(Done/In Progress/Blocked)` / `## Key Decisions` / `## Next Steps` / `## Critical Context`。
- 再次压缩：`UPDATE_SUMMARIZATION_PROMPT`（540-583），把旧摘要放在 `<previous-summary>` 里，要求保留已有信息。
- 文件列表由 `formatFileOperations` 追加为 `<read-files>` / `<modified-files>` 块（`core/compaction/utils.ts`）。
- 序列化：`[User]:`、`[Assistant]:`、`[Assistant tool calls]:`、`[Tool result]:`；thinking 丢弃；tool 结果截断 2000 字符（`docs/compaction.md:276-291`）。

### 17.5 系统提示词里的 docs 段（W）

内容是一段固定文本 + 四个绝对路径（`getReadmePath`/`getDocsPath`/`getExamplesPath`/`getPackageDir`），并列出"问 pi 自身时该读哪些文档"（`src/core/system-prompt.ts:162-169`）。Kotlin 版如果不再分发 docs/examples，**必须改写或删除这段**，否则模型会被指向不存在的路径。

---

## 18. 校验清单（Kotlin 兼容性验收）

按顺序执行，全部通过才算"格式兼容"：

1. **读**：用本机真实 `~/.pi/agent/{settings,auth,models,mcp,trust}.json` 启动，无异常、diagnostics 合理。
2. **读会话**：随机挑 50 个 `~/.pi/agent/sessions/**/*.jsonl`（含 v3 与可能的历史版本），`buildSessionContext` 不抛异常，模型/thinking 恢复正确，`messageCount` 与 `grep -c '"type":"message"'` 一致（注意 compaction 会裁剪上下文但不改计数）。
3. **写会话**：新会话跑完一轮后，用真 pi `pi --session <file>` 打开并继续提问成功；`--mode json` 输出的 header 与 Kotlin 写出的首行逐字段相等（除 id/时间戳）。
4. **写设置**：修改 `defaultModel` 后，文件里未修改字段（如 `packages`、`deviceId`、未知扩展字段）仍在；真 pi 能读。
5. **auth 兼容**：`api_key` 与 `oauth` 两类都能解析；写入后权限 `600`；真 pi 的 `/login` 结果 Kotlin 能读，反之亦然。
6. **模型解析**：`--model deepseek`, `--model opencode-go/deepseek-v4.1-flash:max`, `--models "opencode-go/*"` 均解析到预期模型；无凭据模型被排除。
7. **RPC 协议**：用 `rpc-types.ts` 生成/手写的 33 条命令逐条打，成功/失败响应字段与 `command` 值正确；`get_entries?since=` 游标可用；`agent_settled` 前客户端能收到完整 delta 流。
8. **事件压缩**：同一 mock provider 下，Kotlin 的 `--mode json` 与真 pi 的事件序列在去掉 `timestamp`/`id`/`durationMs` 后一致。
9. **工具输出文本**：`read`（截断提示、offset 越界、首行超限）、`edit`（多编辑/CRLF/BOM/重叠报错）、`bash`（非 0 退出/超时/abort）、`grep`（limit/context/长行截断）、`find`（`**/` 语义）、`ls`（目录斜杠/排序）与真 pi 字符串级一致。
10. **压缩**：人为构造超阈值会话，触发一次自动压缩和一次手动 `/compact`，`firstKeptEntryId`/`tokensBefore` 合理；再打开真 pi 继续对话不乱序。
11. **skills/prompts**：本机 6 个 skill 全部被发现（含符号链接），`/skill:name` 展开文本与 §10.3 一致；`prompts/` 为空时不报错。
12. **不支持的资源**：`~/.pi/agent/npm/` 下 13 个包、`mcp.json` 4 个 server、`models-store.json` 146KB 都只解析不执行，且启动时间不受明显影响。

---

## 19. 附：必须与真 pi 对齐的"字符串常量"

实现时建议建一个常量文件，逐字复制（它们会拼进提示词或用作文本输出，模型行为依赖这些措辞）：

| 常量 | 位置 |
|---|---|
| 默认 preamble | `src/core/system-prompt.ts:155-156` |
| docs section 全文 | `system-prompt.ts:162-169` |
| 默认 rules 结尾两条 | `system-prompt.ts:122-123` |
| skills 段引导语与 XML | `src/core/skills.ts:365-387` |
| 各工具 description / promptSnippet / promptGuidelines | `core/tools/*.ts`（read.ts:20-23,96；bash.ts:45-48,255；edit.ts:43-51,151-152；write.ts:16-19,52-53；grep.ts:35-38,78；find.ts:34-37,78；ls.ts:16-19,62） |
| 截断提示文案 | `read.ts:182-199`，`bash.ts:338-356`，`grep.ts:286-303`，`find.ts:281-293`，`ls.ts:145-155` |
| compaction 摘要前缀/后缀 | `core/messages.ts:11-24` |
| bash 转 user 文本 | `core/messages.ts:82-98` |
| 压缩/更新摘要提示词 | `core/compaction/compaction.ts:507-583` |
| 不支持模型/凭据的错误文案 | `core/auth-guidance.ts`、`agent-session.ts:2138-2155` |
| RPC 未知命令/解析失败文案 | `modes/rpc/rpc-mode.ts:786-810` |
