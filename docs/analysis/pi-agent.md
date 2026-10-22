# pi-agent-core（packages/agent）源码分析与 Kotlin 移植规格

- 源码：`/home/whywood/.codex/private-references/pi/packages/agent`（只读快照），包名/版本 `@earendil-works/pi-agent-core` 1.3.0（`package.json`）。
- 规模：`src` 6 文件共 2539 行（types 539、agent-loop 959、agent 623、proxy 393、stream-fn 20、index 5）；`test` 4 文件共 3997 行 + utils 89 行。
- 依赖：`@earendil-works/pi-ai` 1.3.0（类型 + EventStream/转录助手/参数校验/请求身份）、`typebox` 1.3.27（仅类型，`src` 不 import 任何运行时值）。
- 证据约定：`src/x.ts:行`、`test/x.test.ts:行` 相对 `packages/agent`；`ai/...` 相对 `packages/ai/src`；`ca/...` 相对 `packages/coding-agent/src`。行号基于当前快照。
- 阅读目标：不看 TS 也能逐字写出行为一致的 Kotlin；第 1-7 节是事实规格，第 8-10 节是移植决策、测试与拆分。

## 0. 结论摘要

1. 包内只有两条执行路径：低层无状态循环 `runAgentLoop`/`runAgentLoopContinue`（事件经 sink 回调，`agent-loop.ts:111-160`），以及有状态封装 `Agent`（事件订阅、消息队列、中断、运行生命周期，`agent.ts:194-623`）。其余文件是类型、事件定义、proxy streamFn。
2. 所有对外可观察行为都由 `AgentEvent` 序列承载；事件 sink 是异步的且被逐条 await（`agent-loop.ts:35`、`126`；`agent.ts:575-622`），订阅者按注册顺序串行 await。
3. 工具执行有两种策略：默认 `parallel`（先串行 preflight，再并发执行；`tool_execution_end` 按完成顺序、toolResult 消息按源顺序），任一工具声明 `executionMode: "sequential"` 会把整批降级为串行（`agent-loop.ts:518-533`、`596-670`）。
4. abort 是协作式的：AbortSignal 透传给 streamFn、工具、hook；循环本身不主动抛异常。assistant 消息 `stopReason` 为 `error`/`aborted` 是硬退出；工具批内 abort 只产生 `Operation aborted` 工具结果并让循环带着已 abort 的 signal 继续到下一次请求（`agent-loop.ts:254-265`、`585-587`、`623-653`、`748-773`）。
5. 本版本没有 `maxTurns`/重试/持久化/独立 attachment API；停止条件只有第 4.5 节列出的六类。重试由调用方用 `continue()` 驱动（`agent-loop.ts:72-79`），`finishTurn` 可强制多一次请求（`agent-loop.ts:295-323`）。

## 1. 包职责与边界

### 1.1 文件职责

| 文件 | 行数 | 职责 | 关键导出 |
|---|---|---|---|
| `src/index.ts` | 5 | 汇总导出 | 全部公有符号 |
| `src/types.ts` | 539 | 纯类型：StreamFn、工具、hook、loop 配置、状态、事件、上下文 | 全部类型 |
| `src/agent-loop.ts` | 959 | 无状态循环：转录工具声明、流式 assistant、工具执行/校验/hook、事件发出 | `agentLoop`、`agentLoopContinue`、`runAgentLoop`、`runAgentLoopContinue`、`runToolCall`、`DEFAULT_AGENT_IDENTITY`、`AgentEventSink`、`ToolCallHooks`、`RunToolCallOptions` |
| `src/agent.ts` | 623 | 有状态 `Agent`：状态对象、订阅、steering/follow-up 队列、AbortController、生命周期 | `Agent`、`AgentOptions`、`AgentInitialState`、`QueueMode`（再导出） |
| `src/proxy.ts` | 393 | 走服务端代理的 StreamFn：HTTP POST + SSE 解析 + partial 重建 | `streamProxy`、`ProxyStreamOptions`、`ProxyAssistantMessageEvent` |
| `src/stream-fn.ts` | 20 | 全局默认 StreamFn 注册表 | `setDefaultStreamFn` |

包内没有 provider 实现、认证、HTTP（proxy 的 `fetch` 除外）、会话存储、压缩、重试策略。`CHANGELOG.md` 1.0.0 明确把 harness/session/durable 相关能力移出本包（`CHANGELOG.md` 1.0.0 Breaking Changes）。

### 1.2 `index.ts` 导出面（`src/index.ts:1-5`）

- 来自 `agent.ts`：`Agent`、`AgentOptions`、`AgentInitialState`、再导出 `QueueMode`（`agent.ts:37`）。
- 来自 `agent-loop.ts`：`AgentEventSink`（35）、`DEFAULT_AGENT_IDENTITY`（41）、`agentLoop`（47）、`agentLoopContinue`（80）、`runAgentLoop`（111）、`runAgentLoopContinue`（137）、`ToolCallHooks`（694）、`RunToolCallOptions`（801）、`runToolCall`（821）。
- 来自 `proxy.ts`：`streamProxy`、`ProxyStreamOptions`、`ProxyAssistantMessageEvent`。
- 来自 `stream-fn.ts`：只有 `setDefaultStreamFn`；`getDefaultStreamFn` 不导出，只被 `agent-loop.ts:21` 与 `agent.ts:16` 内部使用。
- 来自 `types.ts`：全部类型（经 `export *`）。

注意：`agentLoop`/`agentLoopContinue` 返回 `EventStream<AgentEvent, AgentMessage[]>`（`ai/utils/event-stream.ts:26-135`），其 `result()` 就是 `agent_end.messages`（`agent-loop.ts:162-167`）；`runAgentLoop*` 直接返回 `Promise<AgentMessage[]>`。

### 1.3 依赖面（pi-ai）

| 符号 | 使用处 | 用途 |
|---|---|---|
| `StreamFn` 相关类型、`Model`、`TranscriptContext`、`SimpleStreamOptions`、`AssistantMessageEventStream`、`AssistantMessageEvent`、`Message`/`AssistantMessage`/`ToolResultMessage`/`SystemMessage`/`TextContent`/`ImageContent`/`ToolCall`/`Usage`/`StopReason`、`Tool`、`ThinkingBudgets`、`ClientIdentity` | types.ts、agent.ts | 类型基础 |
| `EventStream` | `agent-loop.ts:9,54-69,162-167` | 低层事件流实现 |
| `createAssistantMessageEventStream`、`parseStreamingJson` | `proxy.ts:11,112,343` | proxy 产出流 / 增量 JSON |
| `normalizeContext` | `agent-loop.ts:406` | `Message[]` → `TranscriptContext`（只带 messages） |
| `createInitialSystemMessage`、`getCurrentSystemMessage`、`getCurrentSystemPrompt`、`toToolDeclaration` | `agent.ts:3-4,86,369,86,370` | 初始 system 消息、reset 基线、`state.systemPrompt`、工具声明剥离 |
| `getCurrentTools`、`getToolStateChanges` | `agent-loop.ts:10-11,357-359` | 转录中的工具状态重放与差分 |
| `validateToolArguments` | `agent-loop.ts:19,737` | 工具参数 schema 校验（typebox） |
| `mergeClientIdentity`、`PI_ATTRIBUTION` | `agent-loop.ts:12,14,41,415` | 请求身份归属 |
| 工具状态/转录助手 | `ai/utils/transcript.ts` | 具体语义见下 |

Kotlin 移植的前置依赖（pi-ai 模块必须先具备，且行为逐字一致）：

1. 消息/内容/`Model`/`Usage`/`StopReason`/`AssistantMessageEvent`/`TranscriptContext` 类型与 JSON 序列化。
2. `EventStream<T,R>` 与 `AssistantMessageEventStream`（`ai/utils/event-stream.ts:26-135`）：FIFO 队列 + 等待者队列；`push` 在 `done` 后忽略；完成事件先解析 final result；`end(result?)` 解析 result 并唤醒消费者；`result()` 可先于消费等待；`AssistantMessageEventStream` 额外给最终消息补 `durationMs`（单调时钟，且不覆盖已有值/早于流创建的 timestamp）。
3. `normalizeContext`（`ai/utils/transcript.ts:30-34`）：唯一能产出 `TranscriptContext` 的入口；agent 调用时只传 `{messages}`，不传 systemPrompt/tools（`agent-loop.ts:406`）。
4. `createInitialSystemMessage`（`ai/utils/transcript.ts:10-23`）：systemPrompt 非空或 tools 非空时生成 `{role:"system", content: systemPrompt ?? "", toolsAdded?, timestamp: 0}`。
5. `getCurrentSystemMessage`/`getCurrentSystemPrompt`（`ai/utils/transcript.ts:68-102` + `ai/utils/text.ts:15-21`）：按顺序重放所有 system 消息；content 非空追加、sections 按名替换/删除；工具用 `getCurrentTools` 重放；最终文本 = content + 各 section 值，以 `\n\n` 连接。
6. `getCurrentTools`（`ai/utils/transcript.ts:58-66`）：按顺序 `toolsRemoved` 删、`toolsAdded` 设，Map 保序。
7. `getToolStateChanges`/`declarationsEqual`/`toToolDeclaration`（`ai/utils/transcript.ts:123-167`）：声明只保留 name/description/parameters（JSON 往返）/constrainedSampling；声明 JSON 不等即视为移除+新增。
8. `validateToolArguments`（`ai/utils/validation.ts:317-352`）：`argumentsError` 优先报错；`structuredClone` 后做可选 null 归一、`Value.Convert` 强转、typebox 校验；失败抛 `Validation failed for tool "X":\n  - path: message\n\nReceived arguments:\n{json}`；容忍非 typebox schema 的 JSON Schema 强转。
9. `mergeClientIdentity`/`PI_ATTRIBUTION`（`ai/utils/identity.ts:24,49-59`）：字段级合并，后者覆盖，undefined 跳过。
10. `parseStreamingJson`（`ai/utils/json-parse.ts:129-150` 区域）：先修复坏 JSON，再 `partial-json` 解析，永远返回对象。
11. `contentText`/`getSystemMessageText`（`ai/utils/text.ts:6-21`）。

### 1.4 常见误判

- 没有 `maxTurns`：全包无该符号。停止条件见 4.5。
- 没有重试：`agentLoopContinue` 的注释说明它用于 retry（`agent-loop.ts:72-79`），真正的重试/退避在调用方（coding-agent 的 session 层）。
- 没有独立 attachment 类型：图片即 `ImageContent` 放进 `UserMessage.content`，`prompt(string, images)` 会拼成 `[{type:"text"},{type:"image"}]`（`agent.ts:422-439`）。
- `transport` 只是字符串枚举 `"sse" | "websocket" | "websocket-cached" | "auto"`（`ai/types.ts:145`），Agent 默认 `"auto"` 且原样放进 stream options（`agent.ts:230,259,485`）；具体传输由 pi-ai provider 实现。
- “subscription 顺序”是 Set 插入顺序（`agent.ts:196,619-621`），不是事件总线。

## 2. 类型模型（精确到字段）

### 2.1 标量/枚举

| 类型 | 定义 | 默认/约束 | 证据 |
|---|---|---|---|
| `ThinkingLevel` | `"off" \| "minimal" \| "low" \| "medium" \| "high" \| "xhigh" \| "max"` | Agent 状态默认 `"off"`；loop 内 `"off"` 映射成 `reasoning: undefined` | `types.ts:344-349`；`agent.ts:94,480` |
| `ToolExecutionMode` | `"sequential" \| "parallel"` | Agent 默认 `"parallel"`；loop 缺省也按 parallel 走 | `types.ts:40-47`；`agent.ts:261`；`agent-loop.ts:529` |
| `QueueMode` | `"all" \| "one-at-a-time"` | 两条队列默认都 `"one-at-a-time"` | `types.ts:49-55`；`agent.ts:255-256` |
| `Transport` | `"sse" \| "websocket" \| "websocket-cached" \| "auto"`（pi-ai） | Agent 默认 `"auto"` | `ai/types.ts:145`；`agent.ts:259` |
| `StopReason` | `"pending" \| "stop" \| "length" \| "toolUse" \| "error" \| "aborted" \| "deferred"` | 只有 `error`/`aborted` 触发硬退出；`length` 触发工具全失败 | `ai/types.ts:480`；`agent-loop.ts:254,277-279` |
| `Usage` | input/output/cacheRead/cacheWrite（+可选 cacheWrite1h、reasoning）/totalTokens/cost{input,output,cacheRead,cacheWrite,total} | 全部 number，必填（除可选字段） | `ai/types.ts:457-478` |
| `JsonValue`/`JsonObject` | `null \| boolean \| number \| string \| readonly JsonValue[] \| JsonObject`；`{[key:string]: JsonValue}` | Kotlin 用 `JsonElement`/`JsonObject` | `ai/types.ts:482-483` |
| `Model<TApi>` | id,name,api,provider,baseUrl,input,cost,reasoning,contextWindow,maxTokens（+可选 type/inputLimits/headers/thinkingLevelMap/promptCache/samplingParams…） | Agent 默认模型见 2.9 | `ai/types.ts:830-878` |
| `Tool`（pi-ai） | name,description,parameters(TSchema),constrainedSampling? | `AgentTool` 继承它 | `ai/types.ts:760-765` |
| `ToolCall` | type:"toolCall", id,name,arguments:JsonObject,thoughtSignature?,namespace?,argumentsError? | `partialJson` 是 proxy 内部临时字段（TS 用 cast 挂上，`proxy.ts:330-336,359`） | `ai/types.ts:442-455` |

### 2.2 LLM 消息（pi-ai，`ai/types.ts:542-650`）

| 类型 | 字段（必填） | 可选字段 | 语义要点 |
|---|---|---|---|
| `SystemMessage` | role:"system"、content: string \| TextContent[]、timestamp: number | sections?: Record<string,string\|null>、toolsAdded?: Tool[]、toolsRemoved?: ToolReference[] | 转录里的 system 消息声明 prompt 与工具；重放语义见 1.3/4.6 |
| `UserMessage` | role:"user"、content: string \| (TextContent\|ImageContent)[]、timestamp | 无 | 图片走 content 数组 |
| `AssistantMessage` | role:"assistant"、content:(TextContent\|ThinkingContent\|ToolCall)[]、api、provider、model、usage、stopReason、timestamp | responseModel、responseId、providerThinkingLevel、thinkingLevel、diagnostics、deferred、errorMessage、rawStopReason、endTurn、durationMs | loop 会在最终消息上写 `thinkingLevel`（`agent-loop.ts:419`） |
| `ToolResultMessage<TDetails>` | role:"toolResult"、toolCallId、toolName、content:(TextContent\|ImageContent)[]、isError、timestamp | details、usage、nestedCalls、durationMs | loop 生成时 `content ?? []`（`agent-loop.ts:947`） |
| `Message` | 上述四者联合 | — | `convertToLlm` 的输出类型 |
| `TranscriptContext` | `{messages: Message[]}` + 私有 brand | — | 只能由 `normalizeContext` 产出（`ai/types.ts:777-794`） |

### 2.3 `StreamFn`（`types.ts:19-37`）

```ts
type StreamFn = (model: Model<Api>, context: TranscriptContext,
                 options?: SimpleStreamOptions) => AssistantMessageEventStream | Promise<AssistantMessageEventStream>
```

契约（必须逐字保真的调用方假设）：

- 不得因请求/模型/运行时失败而 throw 或返回 rejected promise；失败必须编码为流内的 `error` 事件，最终 `AssistantMessage.stopReason` 为 `"error"`/`"aborted"` 且带 `errorMessage`（`types.ts:27-32`）。
- 收到的转录已规范化：system prompt 与工具声明都在 system 消息里，`context.systemPrompt`/`context.tools` 不使用。
- 返回流的事件协议见 `ai/types.ts:796-828`：`start` 必须先于 update/done；`partial` 是共享的实时对象而非快照；文本/thinking 在 `*_start` 时为空、`*_delta` 增长、`*_end` 权威；`done` 的 reason 是 `stop|length|toolUse|deferred`，`error` 的 reason 是 `aborted|error`。
- 即使 streamFn 违约 throw，Agent 也会合成失败消息（`agent.ts:542-558`），但低层 `agentLoop` 的流永远不会 end（`agent-loop.ts:56-67` 的 `.then` 无 `.catch`），这是 TS 的已知行为，Kotlin 侧建议改为流异常完成（见 8.10）。

### 2.4 `AgentMessage` 与声明合并扩展机制

TS 定义（`types.ts:351-374`）：

```ts
export interface CustomAgentMessages {}                       // 空接口，供应用扩展
export type AgentMessage = Message | CustomAgentMessages[keyof CustomAgentMessages];
declare module "@earendil-works/pi-agent-core" {
  interface CustomAgentMessages { artifact: ArtifactMessage; notification: NotificationMessage }
}
```

机制与后果：

- 纯类型层合并：`CustomAgentMessages` 在应用侧被声明合并后，`AgentMessage` 联合自动包含新消息类型；运行时零开销。
- 循环只对角色做两处结构判断：`role === "system"`（工具声明合并/插入，`agent-loop.ts:344-349,369`）与 `role === "assistant"`（continue 校验，`agent-loop.ts:90-92,148-150`）。其它自定义消息对 loop 完全透明，只经 `convertToLlm` 过滤/转换。
- coding-agent 实际合并了 4 个角色：`bashExecution`、`custom`、`branchSummary`、`compactionSummary`（`ca/core/messages.ts:29-76`），`convertToLlm` 在应用层把它们转成 user/toolResult 或丢弃（`ca/core/messages.ts:150-190` 区域）。

Kotlin 表达方案（推荐 A，见 8.3 的完整理由）：

- A（推荐）：`interface AgentMessage { val role: String; val timestamp: Long }`；pi-ai 的四个核心消息类实现它；pi-agent 另给 `interface CustomAgentMessage : AgentMessage` 作为应用扩展点。`convertToLlm` 接收 `List<AgentMessage>`，内部按 `is Message` 过滤/转换，核心四类用 `sealed interface Message : AgentMessage` 保留 `when` 穷尽性。
- B：`Agent<M : AgentMessage>`/`AgentSession<M>` 泛型化。类型最安全，但事件、状态、循环、工具结果全部被泛型污染，且 coding-agent 依赖的 `runToolCall`/`AgentEvent` 也要泛型，代价过高。
- C：`data class AppMessage(role, payload: JsonElement)` 单类型承载所有自定义消息。保持 sealed，但失去每类自定义消息的静态类型，应用侧要手动解析 payload。
- 结论：A 最接近 TS 语义；`role: String` 保留线上/持久化兼容；把 `AgentMessage` 放在 pi-ai 是必要的模块方向妥协（Kotlin 无结构化联合类型，且 pi-ai 不能依赖 pi-agent），见 8.2。

### 2.5 工具类型族（`types.ts:423-499`）

`AgentToolResult<T = JsonValue | undefined>`：

| 字段 | 类型 | 必填 | 语义 |
|---|---|---|---|
| `content` | `(TextContent \| ImageContent)[]` | 是 | 回送给模型的文本/图片 |
| `details` | `T` | 是 | 日志/UI 用的结构化细节；`T` 默认 `JsonValue \| undefined` |
| `structuredContent` | `JsonValue` | 否 | 匹配 `outputSchema` 的机器可读结果；不发给模型 |
| `usage` | `Usage` | 否 | 工具自身执行消耗，不参与主 LLM 上下文统计 |
| `isError` | `boolean` | 否 | 不抛异常地报告失败；模型看到的是错误结果 |
| `terminate` | `boolean` | 否 | 提示结束本批；仅当整批每个最终结果都为 true 才生效 |

`AgentTool<TParameters extends TSchema = TSchema, TDetails = any> extends Tool<TParameters>`（`types.ts:465-499`）：

| 字段 | 类型 | 必填 | 语义/证据 |
|---|---|---|---|
| `name`/`description`/`parameters` | 来自 pi-ai `Tool` | 是 | schema 决定校验 |
| `label` | `string` | 是 | UI 展示 |
| `prepareArguments` | `(args: unknown) => Static<TParameters>` | 否 | 校验前的兼容 shim；返回同一引用时 loop 不替换 toolCall（`agent-loop.ts:704-716`） |
| `outputSchema` | `TSchema` | 否 | `structuredContent` 的 schema |
| `execute` | `(toolCallId, params, signal?, onUpdate?) => Promise<AgentToolResult<TDetails>>` | 是 | 失败抛异常或返回 `isError: true`，不能只在 content 里描述失败（`types.ts:479-488`） |
| `replay` | `"never" \| "safe"` | 否 | 持久化意图存在但结果未知时的恢复策略；本包不使用 |
| `executionMode` | `"sequential" \| "parallel"` | 否 | 单工具覆盖；批内任一 sequential 全批串行（`agent-loop.ts:526-530`） |

其它：

- `AgentToolCall` = assistant content 里 `{type:"toolCall"}` 的抽取（`types.ts:57-58`）。
- `AgentToolCallOutcome` = `{toolCall, result, isError, durationMs?}`；`durationMs` 是 `execute()` 单调时钟毫秒、不含 hook，未执行则缺失（`types.ts:448-455`）。
- `AgentToolUpdateCallback<T> = (partialResult) => void`；作用域是当前 `execute()` 调用，工具 promise settle 后的调用被忽略（`types.ts:457-463`；实现 `agent-loop.ts:831-867`）。

### 2.6 Hook 类型（`types.ts:60-158`）

| 类型 | 字段 | 说明 |
|---|---|---|
| `BeforeToolCallResult` | `block?`、`reason?`、`terminate?` | `block:true` 阻止执行并产出错误 tool result，reason 作为文本；terminate 参与批级终止（`agent-loop.ts:755-765`） |
| `AfterToolCallResult` | `content?`、`details?`、`structuredContent?`、`isError?`、`usage?`、`terminate?` | 字段级替换，无深合并；**若给了 content 没给 structuredContent，则 structuredContent 被丢弃**（`agent-loop.ts:893-906`） |
| `BeforeToolCallContext` | `assistantMessage`、`toolCall`、`args`（已校验）、`context: AgentContext` | `types.ts:106-116` |
| `AfterToolCallContext` | 上述 + `result: AgentToolResult<any>`、`isError: boolean` | `types.ts:118-132` |
| `AgentTurnContext` | `message: AssistantMessage`、`toolResults: ToolResultMessage[]`、`context: AgentContext`、`newMessages: AgentMessage[]` | `newMessages` 是“本次 loop 调用若此刻退出会返回的消息”；prompt 运行包含初始 prompt 消息，continue 运行不含既有上下文（`types.ts:134-144`） |
| `AgentTurnDecision` | `{action:"continue"} \| {action:"end"}` | `finishTurn` 返回 undefined 表示保持正常调度（`types.ts:146-147`） |
| `FinishTurn` | `(turn, signal?) => AgentTurnDecision \| void \| Promise<...>` | 在 assistant+全部工具结果之后、`turn_end` 之前调用；error/aborted 也会调用但决策被忽略（`types.ts:149-158`） |
| `AgentLoopTurnUpdate` | `context?`、`messages?`、`model?`、`thinkingLevel?` | 下一轮请求的替换运行时状态；messages 会走正常生命周期事件（`types.ts:160-170`） |
| `PrepareRequestContext` | `context`、`model`、`thinkingLevel` | 请求前快照（`types.ts:172-177`） |
| `AgentRequestUpdate` | `Omit<AgentLoopTurnUpdate,"messages">` | 请求替换（`types.ts:179-180`） |
| `PrepareRequest` | `(request, signal?) => AgentRequestUpdate \| void \| Promise<...>` | 每个请求前调用，含首个；pending 消息已追加并发事件；不轮询队列（`types.ts:182-189`） |
| `PrepareNextTurnContext` | `extends AgentTurnContext` | `types.ts:191` |

### 2.7 `AgentLoopConfig`（`types.ts:193-342`）

`extends SimpleStreamOptions`（所以 loop 配置对象本身就是 stream options，`streamAssistantResponse` 直接 `{...config, apiKey, identity, signal}` 透传，`agent-loop.ts:412-417`）。

| 字段 | 类型 | 必填/默认 | 调用时机与契约 |
|---|---|---|---|
| `model` | `Model<any>` | 必填 | 每次请求 |
| `convertToLlm` | `(AgentMessage[]) => Message[] \| Promise<Message[]>` | 必填 | 每次请求前；必须不 throw/reject，否则打断低层 loop 且无正常事件序列（`types.ts:196-222`） |
| `transformContext` | `(AgentMessage[], signal?) => Promise<AgentMessage[]>` | 可选 | `convertToLlm` 之前；只影响本次请求，不回写 loop 上下文；必须不 throw（`types.ts:224-244`；`agent-loop.ts:397-401`） |
| `getApiKey` | `(provider) => Promise<string\|undefined> \| string \| undefined` | 可选 | 每次请求，取返回值或 `config.apiKey`；必须不 throw（`types.ts:246-254`；`agent-loop.ts:408-410`） |
| `finishTurn` | `FinishTurn` | 可选 | 见 2.6/4.5 |
| `prepareRequest` | `PrepareRequest` | 可选 | 每个请求前（含第一个），pending 消息之后；返回替换 context/model/thinkingLevel，持久到本次 run 后续请求；不轮询队列（`types.ts:266-271`；`agent-loop.ts:228-248`） |
| `prepareNextTurn` | `(context) => AgentLoopTurnUpdate \| undefined \| Promise<...>` | 可选 | `turn_end` 之后、下一轮开始时；返回的 messages 会以正常消息事件追加（`types.ts:273-280`；`agent-loop.ts:194-217`） |
| `getSteeringMessages` | `() => Promise<AgentMessage[]>` | 可选 | 运行起点、每轮结束后、`prepareNextTurn` 之后（仅当 pending 为空）；必须不 throw，无消息返回 `[]`（`types.ts:282-293`） |
| `getFollowUpMessages` | `() => Promise<AgentMessage[]>` | 可选 | 仅当 agent 本会停下（无工具、无 steering）时；必须不 throw（`types.ts:295-306`） |
| `toolExecution` | `ToolExecutionMode` | 默认 `"parallel"` | 批级策略（`types.ts:308-317`） |
| `beforeToolCall` | `(BeforeToolCallContext, signal?) => Promise<BeforeToolCallResult\|undefined>` | 可选 | 参数校验后、执行前；返回 `block:true` 阻止执行；hook 负责响应 abort（`types.ts:319-326`） |
| `afterToolCall` | `(AfterToolCallContext, signal?) => Promise<AfterToolCallResult\|undefined>` | 可选 | 执行后、`tool_execution_end` 与 toolResult 消息事件之前；只做字段级替换（`types.ts:328-341`） |
| `SimpleStreamOptions` 其余字段 | 见 pi-ai | 可选 | 透传给 streamFn；Agent 只设置其中一部分（见 2.9） |

### 2.8 `AgentContext`/`AgentState`/`AgentInitialState`/`AgentOptions`

`AgentContext`（`types.ts:501-507`）：`messages: AgentMessage[]`、`tools?: AgentTool<any>[]`（可选）。这是 loop 的输入快照，`context.tools` 是可执行集合，转录里的 system 消息声明模型可见集合。

`AgentState`（`types.ts:376-421`）：

| 成员 | 类型 | 读写 | 语义/证据 |
|---|---|---|---|
| `systemPrompt` | `string` | readonly getter | 从转录 system 消息重放（`agent.ts:90-92`）；要改 prompt 就追加 system 消息（content 或 sections） |
| `model` | `Model<any>` | 读写 | 后续 turn 使用 |
| `thinkingLevel` | `ThinkingLevel` | 读写 | 请求的推理级别 |
| `tools` | `AgentTool<any>[]` | getter+setter | setter 复制顶层数组；getter 返回内部数组（`agent.ts:95-100`） |
| `messages` | `AgentMessage[]` | getter+setter | setter 复制顶层数组；getter 返回内部数组（`agent.ts:101-106`） |
| `isStreaming` | `boolean` | readonly | 从 run 开始 true，直到 run 完全结算（含 agent_end 监听器）；`agent.ts:529,560-566` |
| `streamingMessage` | `AgentMessage?` | readonly | 最近一条已 `message_start` 未 `message_end` 的消息（实现上任意角色都会被设置，`agent.ts:577-586`） |
| `pendingToolCalls` | `ReadonlySet<string>` | readonly | 执行中的 toolCallId；每次事件用新 Set 替换（`agent.ts:590-602`） |
| `errorMessage` | `string?` | readonly | 最近失败/中止 turn 的错误；run 开始时清空，`turn_end` 时从 assistant.errorMessage 写入（`agent.ts:531,604-608`） |

`AgentInitialState = Partial<Omit<AgentState,"pendingToolCalls"|"isStreaming"|"streamingMessage"|"errorMessage">>`（`agent.ts:78-81`）：`systemPrompt?`、`model?`、`thinkingLevel?`、`tools?`、`messages?`。

`AgentOptions`（`agent.ts:114-147`）：

| 字段 | 类型/默认 | 说明 |
|---|---|---|
| `initialState` | `AgentInitialState?` | 见 5.1 构造规则 |
| `convertToLlm` | 默认 `defaultConvertToLlm`（过滤 role 为 system/user/assistant/toolResult 的消息，`agent.ts:39-47`） | — |
| `transformContext` | 可选 | — |
| `streamFn` | 类型必填；运行时缺省回落到 `getDefaultStreamFn()`（`agent.ts:244`） | — |
| `getApiKey`、`onPayload`、`onResponse`、`onProviderStreamEvent` | 可选 | 转发给 stream options |
| `beforeToolCall`、`afterToolCall`、`finishTurn`、`prepareRequest` | 可选 | 转发到 loop 配置 |
| `prepareNextTurn` | 可选，旧签名 `(signal?) => update` | 与 `prepareNextTurnWithContext` 二选一，后者优先（`agent.ts:494-502`） |
| `prepareNextTurnWithContext` | 可选，新签名 `(context, signal?) => update` | — |
| `steeringMode`/`followUpMode` | 默认 `"one-at-a-time"` | — |
| `sessionId`、`thinkingBudgets`、`transport`（默认 `"auto"`）、`maxRetryDelayMs`、`toolExecution`（默认 `"parallel"`）、`identity` | 可选 | 原样转发 |

`Agent` 自身的可写公共字段（`agent.ts:200-236`）：`convertToLlm`、`transformContext`、`streamFunction`、`getApiKey`、`onPayload`、`onResponse`、`onProviderStreamEvent`、`beforeToolCall`、`afterToolCall`、`finishTurn`、`prepareRequest`、`prepareNextTurn`、`prepareNextTurnWithContext`、`sessionId`、`thinkingBudgets`、`transport`、`maxRetryDelayMs`、`toolExecution`、`identity`。coding-agent 正是通过直接赋值这些字段安装 hook（见 7.2）。

### 2.9 `AgentEvent`（`types.ts:509-539`）

| variant | payload | 何时发出 |
|---|---|---|
| `agent_start` | 无 | run 第一个事件；在初始消息事件之前（`agent-loop.ts:126`） |
| `agent_end` | `messages: AgentMessage[]` | run 最后一个事件；payload 是本次 loop 调用的 `newMessages`（prompt 含初始 prompt；continue 只含新增；Agent 失败路径只含合成失败消息） |
| `turn_start` | 无 | 每个 turn 开始；首个 turn 由入口函数发（`agent-loop.ts:127,156`），后续在 prepareNextTurn 之后、pending 消息事件之前发（`agent-loop.ts:216`） |
| `turn_end` | `message: AgentMessage`（实际为 AssistantMessage）、`toolResults: ToolResultMessage[]` | 每轮 assistant + 全部工具结果之后；finishTurn 之后（`agent-loop.ts:262,296`） |
| `message_start` | `message: AgentMessage` | system（工具声明）、user、assistant（流的 start 或合成）、toolResult 开始 |
| `message_update` | `message: AgentMessage`、`assistantMessageEvent: AssistantMessageEvent` | 仅 assistant 流式期间，且只在见过 `start` 之后（`agent-loop.ts:442-451`） |
| `message_end` | `message: AgentMessage` | 每条消息结束；assistant 的最终消息；toolResult 在 tool_execution_end 之后 |
| `tool_execution_start` | `toolCallId`、`toolName`、`args: any` | 工具调用开始；`args` 是 assistant toolCall 的原始 arguments（未经 prepare/校验） |
| `tool_execution_update` | `toolCallId`、`toolName`、`args`、`partialResult: any` | 工具调用 `onUpdate` 时；sink 被 await，settle 后的更新被忽略 |
| `tool_execution_end` | `toolCallId`、`toolName`、`result: AgentToolResult`、`isError: boolean`、`durationMs?: number` | 工具最终化后；`durationMs` 仅在真正执行过时存在 |

事件发出顺序的严格规则见第 3 节。

### 2.10 proxy 类型（`proxy.ts:20-70`）

`ProxyAssistantMessageEvent`：服务端下发、去掉 partial 字段以省带宽的事件：

| variant | 字段 |
|---|---|
| `start` | 无 |
| `text_start` / `thinking_start` | `contentIndex` |
| `text_delta` / `thinking_delta` | `contentIndex`、`delta` |
| `text_end` / `thinking_end` | `contentIndex`、`contentSignature?` |
| `toolcall_start` | `contentIndex`、`id`、`toolName` |
| `toolcall_delta` | `contentIndex`、`delta` |
| `toolcall_end` | `contentIndex`、`toolCall: ToolCall` |
| `done` | `reason: "stop"\|"length"\|"toolUse"`、`usage`、`providerThinkingLevel?` |
| `error` | `reason: "aborted"\|"error"`、`errorMessage?`、`usage`、`providerThinkingLevel?` |

`ProxySerializableStreamOptions = Pick<SimpleStreamOptions, temperature|samplingParams|maxTokens|reasoning|cacheRetention|sessionId|headers|metadata|transport|thinkingBudgets|maxRetryDelayMs>`（`proxy.ts:48-61`）。

`ProxyStreamOptions` = 上述 + `signal?`、`authToken: string`、`proxyUrl: string`（`proxy.ts:63-70`）。

<!-- PART2 -->
