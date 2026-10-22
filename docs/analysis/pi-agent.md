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

## 3. 事件流完整规范

### 3.1 通用规则

1. sink 是异步的：`AgentEventSink = (event) => Promise<void> | void`（`agent-loop.ts:35`），所有 `emit(...)` 都被 await。Agent 的 sink 是 `processEvents`，它会先归约内部状态，再按注册顺序串行 await 每个订阅者（`agent.ts:575-622`）。
2. 一次 run 以一个 `agent_start` 开始、以一个 `agent_end` 结束；`agent_end` 之后不再有循环事件，但 `Agent` 的 `isStreaming` 要等 `agent_end` 的订阅者全部 settle 才转 false（`agent.ts:559-566`；测试 `test/agent.test.ts:362-433`）。
3. 消息生命周期事件覆盖 system→user→assistant→toolResult。`message_update` 只用于 assistant 流式更新；system/user/toolResult 只有 start/end。
4. 轮次由 `turn_start`/`turn_end` 包裹。第一个 `turn_start` 由入口函数在 agent_start 之后立刻发出（`agent-loop.ts:127,156`）；后续每轮在 `prepareNextTurn` 之后、pending 消息事件之前发出（`agent-loop.ts:216`）。
5. `agent_end.messages` 是“本次 loop 调用新增的全部消息”：prompt 运行包含初始 prompt 与循环中合成的 system 消息（`agent-loop.ts:119-124`），continue 运行只包含本次新增（`agent-loop.ts:152`）。Agent 失败路径的 `agent_end.messages` 只有合成的失败 assistant 消息（`agent.ts:557`）。
6. 事件 payload 中的 message 对象与内部上下文共享引用（顶层浅拷贝的情况见 3.3），订阅者应视为只读。

### 3.2 精确序列

(a) `prompt("Hello")`，无工具，正常 stop（README 与 `test/agent-loop.test.ts:114-158`）：

```text
agent_start
turn_start
message_start{user}
message_end{user}
message_start{assistant}          # 流有 start 事件时在流开始时；没有 start 时在 done 处补发
message_update{assistant} * N     # 只有流式 delta 才有
message_end{assistant}
turn_end{message: assistant, toolResults: []}
agent_end{messages: [user, assistant]}
```

(b) 带工具调用再给最终回答（`README.md` Event Flow 段；`test/agent-loop.test.ts:305-399`）：

```text
agent_start
turn_start
message_start/end{user}
message_start{assistant}
message_update* 
message_end{assistant(toolUse)}
tool_execution_start{id,name,args}
tool_execution_update*            # 工具调用 onUpdate 时
tool_execution_end{id,name,result,isError,durationMs?}
message_start{toolResult}
message_end{toolResult}
turn_end{message: assistant, toolResults: [toolResult]}
turn_start                        # 下一轮
message_start{assistant}
message_update*
message_end{assistant}
turn_end{message: assistant, toolResults: []}
agent_end{messages: [user, assistant, toolResult, assistant]}
```

(c) `finishTurn` 返回 `{action:"end"}` 的精确序列（测试断言原文，`test/agent-loop.test.ts:1713-1729`）：

```text
agent_start, turn_start,
message_start/end{system(工具声明)},   # context.tools 与转录声明不一致时合成
message_start/end{user},
message_start/end{assistant},
tool_execution_start, tool_execution_end,
message_start/end{toolResult},
turn_end, agent_end
```

该用例同时断言 `messages` 角色为 `["system","user","assistant","toolResult"]`，说明工具声明 system 消息进入了返回消息与新上下文（`test/agent-loop.test.ts:1710-1713`）。

(d) `continue()` 从 user/toolResult 尾巴续跑（`test/agent-loop.test.ts:2031-2070`）：

```text
agent_start
turn_start
# 没有 user 消息事件
message_start/end{assistant}
turn_end
agent_end
```

测试断言 message_end 只有 1 条且为 assistant（`test/agent-loop.test.ts:2062-2069`）。

(e) assistant `stopReason` 为 `error`/`aborted`（测试 `test/agent-loop.test.ts:1162-1215`、`test/agent.test.ts:331-360`、`test/e2e.test.ts:208-219`）：

```text
agent_start, turn_start,
message_start/end{user},
message_start/end{assistant(error|aborted)},
# finishTurn 在此被调用（无事件）
turn_end{message: assistant, toolResults: []},
agent_end{messages: [...]}
```

该路径不轮询 steering/follow-up，也忽略 `finishTurn` 的决策（`agent-loop.ts:254-265`；测试断言的调用计数为 providerCalls=1、followUpPolls=0）。

(f) streamFn 违约 throw（Agent 层，`test/agent.test.ts:331-360`）：

```text
agent_start, turn_start,
message_start/end{user},
message_start/end{assistant(stopReason:"error", errorMessage:"provider exploded")},
turn_end, agent_end
```

失败消息由 `handleRunFailure` 合成（`agent.ts:542-558`），`agent.state.errorMessage` 同步写入。

### 3.3 流式 partial 的构造方式（逐行）

`streamAssistantResponse`（`agent-loop.ts:386-479`）是唯一把 `AssistantMessageEvent` 映射为 `AgentEvent` 的地方：

1. 请求前：`transformContext`（可选，AgentMessage→AgentMessage，仅影响本次请求，不回写上下文）→ `convertToLlm`（必填，AgentMessage→Message）→ `normalizeContext({messages})` → `getApiKey`（每次请求重新取，用于过期 OAuth token）→ `streamFn(model, llmContext, {...config, apiKey, identity: mergeClientIdentity(DEFAULT_AGENT_IDENTITY, config.identity), signal})`（`agent-loop.ts:397-417`）。
2. `start` 事件：`partialMessage = event.partial`，把它 push 进 `context.messages`（同一引用），`addedPartial = true`，发 `message_start{message: {...partialMessage}}`（顶层浅拷贝）（`agent-loop.ts:426-431`）。
3. 文本/thinking/toolcall 的 start/delta/end 事件：若已有 partial，则 `partialMessage = event.partial`，替换 `context.messages` 最后一个元素，发 `message_update{message: {...partialMessage}, assistantMessageEvent: event}`。`message` 是浅拷贝；`assistantMessageEvent.partial` 是 pi-ai 定义的“共享实时对象”，不是事件时刻快照（`ai/types.ts:796-811`）。没见过 `start` 时这些事件被忽略（`agent-loop.ts:442-451`）。
4. `done`/`error`：`finalMessage = await response.result()`，然后 `Object.assign(finalMessage, {thinkingLevel: config.reasoning ?? "off"})`——即每条最终 assistant 消息都会记录本次请求的 thinking level，值为 `"off"` 或请求级别（`agent-loop.ts:418-419,453-466`）。若之前加过 partial，用 finalMessage 替换数组最后一个元素（不会再发 message_start）；否则 push 并补发 `message_start{...finalMessage}`。最后发 `message_end{message: finalMessage}`（`message_end` 直接传 finalMessage，不再浅拷贝）。
5. 流迭代自然结束但没有 done/error：走与 4 相同的收尾逻辑（`agent-loop.ts:470-478`）。若流既不结束也不产出终态，`response.result()` 会永久挂起——proxy 之所以在 EOF 时合成 error 事件就是为了避免这种挂起（`proxy.ts:219-230`）。
6. `durationMs` 不由 loop 设置，而由 `AssistantMessageEventStream` 在 push done/error 或 `end(result)` 时补上（单调时钟，且不覆盖已有值/早于流创建时间的消息；`ai/utils/event-stream.ts:91-130`）。
7. 消息结束事件的 payload 与上下文共享同一个对象；`message_update.message` 的 `content` 数组与实时 partial 共享（浅拷贝）。Kotlin 若把消息建模为不可变 data class，需明确并在测试中固定“共享/复制”策略；推荐与 TS 一致：对消息做顶层 copy（`copy()` 共享 content 列表），实时对象由 pi-ai 流负责。

### 3.4 订阅者观察到的顺序与结算

- `Agent.subscribe(listener)` 把 listener 放进 `Set`，返回 `() => this.listeners.delete(listener)`（类型标 `() => void`，实际返回 boolean；`agent.ts:275-278`）。
- 注册顺序即调用顺序；一个事件的 listener 依次 await 完成后才处理下一个事件（`agent.ts:619-621`）。没有初识事件，状态 mutator 不发事件（测试 `test/agent.test.ts:309-329`）。
- listener 收到 `(event, signal)`，`signal` 是当前 run 的 AbortController signal；没有 activeRun 时 `processEvents` 抛 `"Agent listener invoked outside active run"`（`agent.ts:615-618`）。
- listener 抛错会沿 sink 向上传播，成为 run 失败：Agent 走 `handleRunFailure` 再发一轮 `message_start/message_end/turn_end/agent_end`（可能再次调用抛错 listener）；若失败处理再次抛错，`prompt()` 以该错误 reject，`finishRun` 仍会执行（`agent.ts:517-540,542-558`）。
- 低层 `agentLoop` 的 `.then` 没有 `.catch`（`agent-loop.ts:56-67`）：sink/loop 抛错时返回的 EventStream 不会 `end`，消费者会永久等待。Kotlin 移植建议在流上以异常完成（见 8.10）。
- JS `Set` 的 `for..of` 会访问迭代期间新增的元素；Agent 没有快照 listeners。Kotlin 若用 `LinkedHashSet` 直接迭代会 CME，建议快照（允许偏差，无测试覆盖）。

## 4. agent-loop.ts 状态机

### 4.1 入口与数据流

- `agentLoop`（`agent-loop.ts:47-70`）：`createAgentStream()` 创建 `EventStream<AgentEvent, AgentMessage[]>`（完成条件 `agent_end`，结果取 `agent_end.messages`，`162-167`）；立即启动 `runAgentLoop`，sink 只做 `stream.push`；promise settle 后 `stream.end(messages)`；返回流。
- `agentLoopContinue`（`80-109`）：先同步校验 `context.messages.length > 0` 且最后一条 role 不是 `assistant`，否则同步 throw（`86-92`）；之后与 `agentLoop` 相同。
- `runAgentLoop`（`111-135`）：`initialMessages = declareToolChanges(context, prompts)`；`newMessages = [...initialMessages]`；`currentContext = {...context, messages: [...context.messages, ...initialMessages]}`（新数组）；发 `agent_start`、`turn_start`，再为每条 initialMessages 发 start/end；进入 `runLoop`；返回 `newMessages`。
- `runAgentLoopContinue`（`137-160`）：同样校验；`newMessages = []`；`currentContext = {...context}`——注意这里没有复制 `messages` 数组，循环会直接 push 调用方传入的数组；发 `agent_start`、`turn_start`（不发消息事件）；进入 `runLoop`。
- `streamFn ?? getDefaultStreamFn()` 在调用 `runLoop` 前解析（`133`、`158`），因此全局默认函数是在 run 开始时取值的。

### 4.2 `runLoop` 的完整控制流（`agent-loop.ts:169-330`）

```text
currentContext = initialContext; config = initialConfig
lastCompletedTurn = undefined; explicitContinuation = false
pendingMessages = await getSteeringMessages?.() || []        # 轮询点 1：运行起点

while (true) {                                               # 外层：follow-up 复活
  hasMoreToolCalls = true
  while (hasMoreToolCalls || pendingMessages.length > 0) {   # 内层：工具/steering 驱动
    preparedMessages = []
    if (lastCompletedTurn) {                                  # 非第一轮
      snapshot = await prepareNextTurn?.(lastCompletedTurn)
      if (snapshot) { 合并 context/messages/model/reasoning }
      if (pendingMessages.length === 0)                       # 轮询点 2
        pendingMessages = await getSteeringMessages?.() || []
      await emit(turn_start)
    }
    for (m of declareToolChanges(currentContext, [...preparedMessages, ...pendingMessages]))
      { await emit(message_start); await emit(message_end); context.messages.push(m); newMessages.push(m) }
    pendingMessages = []
    requestUpdate = await prepareRequest?.({context, model, thinkingLevel: config.reasoning ?? "off"}, signal)
    if (requestUpdate) { 合并 context/model/reasoning }
    assistant = await streamAssistantResponse(currentContext, config, signal, emit, streamFunction)
    newMessages.push(assistant)
    if (assistant.stopReason === "error" || assistant.stopReason === "aborted") {
      lastCompletedTurn = {message: assistant, toolResults: [], context: currentContext, newMessages}
      await finishTurn?.(lastCompletedTurn, signal)
      await emit(turn_end, toolResults: [])
      await emit(agent_end, messages: newMessages)
      return
    }
    toolCalls = assistant.content.filter(type === "toolCall")
    toolResults = []; hasMoreToolCalls = false
    if (toolCalls.length > 0) {
      batch = stopReason === "length" ? failToolCallsFromTruncatedMessage(...)
                                      : executeToolCalls(...)
      toolResults.push(...batch.messages)
      hasMoreToolCalls = !batch.terminate
      for (r of toolResults) { context.messages.push(r); newMessages.push(r) }
    }
    lastCompletedTurn = {message: assistant, toolResults, context: currentContext, newMessages}
    decision = await finishTurn?.(lastCompletedTurn, signal)  # 轮询点：finishTurn
    await emit(turn_end, message: assistant, toolResults)
    if (decision?.action === "end") { await emit(agent_end); return }
    explicitContinuation = decision?.action === "continue"
    pendingMessages = await getSteeringMessages?.() || []      # 轮询点 3
    if (hasMoreToolCalls || pendingMessages.length > 0) explicitContinuation = false
  }
  followUpMessages = await getFollowUpMessages?.() || []       # 轮询点 4：自然停止处
  if (followUpMessages.length > 0) { explicitContinuation = false; pendingMessages = followUpMessages; continue }
  if (explicitContinuation) { explicitContinuation = false; continue }  # 额外一次 context-only 请求
  break
}
await emit(agent_end, messages: newMessages)
```

要点：

- 第一轮不调用 `prepareNextTurn`（`lastCompletedTurn` 为空）；后续每轮在 `turn_start` 前调用（`agent-loop.ts:194-217`）。
- `prepareNextTurn` 返回的 `messages` 与 steering 的 pending 消息拼在一起，先 `declareToolChanges` 再逐条发 start/end 并同时写入 `currentContext.messages` 与 `newMessages`（`220-225`）。顺序：prepared 在前，pending 在后。
- 模型/推理级别的合并规则：`model: new ?? config.model`；`thinkingLevel === undefined` 保留 `config.reasoning`，`"off"` 变为 `undefined`，否则取新值（`199-208`、`236-248`）。
- `prepareRequest` 在 pending 消息事件之后、请求之前调用；返回的 context/model/thinkingLevel 会持久替换 `currentContext`/`config`，影响本次 run 后续请求（`228-248`）。
- `error`/`aborted` 是硬退出：先 `finishTurn`，再 `turn_end`，再 `agent_end`；不轮询队列，忽略 finishTurn 决策（`254-265`）。
- `stopReason === "length"`：所有 tool call 一律失败不执行（`276-279`、`481-513`）。
- 工具批 `terminate=true` 只影响 `hasMoreToolCalls`；steering/follow-up/explicit continuation 仍可让循环继续（`281`、`304-307`、`310-323`）。
- `explicitContinuation` 是 finishTurn 的 continue 决策：若工具结果、steering、follow-up 已自然提供了下一次请求，则置 false；否则外层再来一轮，内层以 `hasMoreToolCalls=true` 进入，做一次 context-only 请求（测试 `test/agent-loop.test.ts:1273-1305`）。
- 该 context-only 轮次会再次调用 `prepareNextTurn`（`lastCompletedTurn` 没清空），这是可观察的调用计数细节，移植需保留（见 8.9 B17）。
- `agent_end` 只在三处发出：硬退出（`263`）、finishTurn end（`299`）、正常自然停止（`329`）。

### 4.3 `declareToolChanges`（`agent-loop.ts:332-384`）

目的：`context.tools` 是“可执行集合”，转录里的 system 消息声明“模型可见集合”；二者不一致时，在本次请求前用一条 system 消息向模型宣告增删。算法：

1. 在 `pendingMessages` 中从后往前找最后一条 `role === "system"` 的消息（`342-350`）。
2. 若有 pending system 消息，构造 `baseline`：把它复制并把 `toolsAdded`/`toolsRemoved` 清空（`withToolChanges(pending, NO_CHANGES)`），其余消息原样；否则 `baseline = pendingMessages`（`351-355`）。
3. `changes = getToolStateChanges(getCurrentTools([...context.messages, ...baseline]), (context.tools ?? []).map(toToolDeclaration))`（`356-359`）。即“已提交转录 + 去掉工具字段的 pending 消息”作为旧状态，与可执行集合比较。
4. `unchanged = 两个列表都空`（`360`）。
5. 若存在 pending system 消息（`362-366`）：
   - 若 unchanged 且该消息本来也没有工具字段 → 原样返回 `pendingMessages`（保留调用方对象）。
   - 否则返回 `baseline` 的映射，把该位置替换为 `withToolChanges(pending, changes)`——内容/sections 保留，工具字段用重算的差量覆盖。
6. 若无 pending system 消息（`367-371`）：
   - unchanged → 原样返回；
   - 否则新建 `{role:"system", content:"", timestamp: Date.now(), toolsAdded?, toolsRemoved?}`，插入到第一条非 system 消息之前（全 system 则追加到末尾）。
7. `withToolChanges`（`376-384`）：复制消息、删旧工具字段、只添加非空列表。
8. `NO_CHANGES = {toolsAdded: [], toolsRemoved: []}`（`374`）。

推论（测试固定）：

- 可执行集合优先于调用方 pending 消息里声明的意图（`test/agent.test.ts:247-280`）。
- pending system 消息里已有的 content/sections 保留，delta 合并进去（`test/agent.test.ts:212-245`）。
- 合成消息的 `toolsAdded` 是剥离后的声明（无 `execute`/`label` 等，`toToolDeclaration` 只留 name/description/parameters/constrainedSampling；`test/agent.test.ts:199-210`）。
- 该函数在入口（prompts）与每轮 pending 消息处理中各调用一次；发出的 system 消息会进入转录，因此后续重放自然包含它。

### 4.4 工具执行（`agent-loop.ts:515-959`）

策略选择（`518-533`）：

```text
toolCalls = assistantMessage.content 中 type==="toolCall" 的块
hasSequentialToolCall = 任一 toolCall 对应 currentContext.tools 中 executionMode === "sequential"
if (config.toolExecution === "sequential" || hasSequentialToolCall) → 串行
else → 并行
```

串行（`540-594`），逐 call：

1. `emit(tool_execution_start{toolCallId, toolName, args: toolCall.arguments})` —— args 是原始参数，未经 prepare/校验。
2. `prepareToolCall(...)`（见下）→ immediate 或 prepared。
3. immediate：直接作为 finalized。
4. prepared：`executePreparedToolCall`（执行，收集 onUpdate）→ `finalizeExecutedToolCall`（afterToolCall 合并）。
5. `emit(tool_execution_end)`；`createToolResultMessage`；`emit(message_start)`；`emit(message_end)`；收集 finalized/message。
6. 每次 call 之后检查 `signal?.aborted`，aborted 则 break；**剩余 call 完全没有任何事件与结果**。

并行（`596-670`），两阶段：

1. 预检阶段（串行）：逐 call `emit(tool_execution_start)` → `prepareToolCall`。
   - immediate：立即 `emit(tool_execution_end)`，finalized 入列；若 aborted 则 break。
   - prepared：把一个惰性 thunk 入列（不执行）；若 aborted 则 break。
2. 执行阶段：`Promise.all(entries.map(entry => typeof entry === "function" ? entry() : Promise.resolve(entry)))`——所有 thunk 同时在微任务中启动（并发）。每个 thunk 开头若 `signal.aborted` 则产出 `Operation aborted` 错误结果并 `tool_execution_end` 后返回；否则执行+finalize+`tool_execution_end`。
3. 收尾阶段：按 `Promise.all` 的结果顺序（= assistant 源顺序）逐条 `createToolResultMessage` → `emit(message_start)` → `emit(message_end)`，并收集为 `messages`。
4. `terminate = shouldTerminateToolBatch(orderedFinalizedCalls)`，即 finalized 数量 >0 且每个 `result.terminate === true`（`700-702`）。

因此并行模式的可观察顺序：`tool_execution_start` 按源顺序（preflight 期间），`tool_execution_end` 按完成顺序，toolResult 消息按源顺序，`turn_end.toolResults` 也按源顺序（测试 `test/agent-loop.test.ts:668-760` 断言 end 顺序 `["tool-2","tool-1"]`，消息顺序 `["tool-1","tool-2"]`）。

`prepareToolCall`（`718-787`）精确步骤：

1. 按 name 在 `currentContext.tools` 找工具；找不到 → immediate error `Tool ${name} not found`，`isError: true`（`726-733`）——仍然已经发过 start，随后发 end，不进入 hook。
2. try：
   a. `prepareArguments`（若存在）生成 `preparedToolCall`；若返回同一引用则沿用原 toolCall，否则用新 arguments 覆盖（`704-716`）。
   b. `validateToolArguments(tool, preparedToolCall)` → `validatedArgs`；抛错走 catch。
   c. 若配置 `beforeToolCall`：await 调用，context 为 `{assistantMessage, toolCall: 原 toolCall, args: validatedArgs, context}`（注意不是 prepared 后的 toolCall），signal 透传。
   d. hook 返回后若 `signal.aborted` → immediate `Operation aborted`（abort 优先于 block，`748-754`）。
   e. 若 `beforeResult.block` → 错误结果文本 `reason || "Tool execution was blocked"`；若 `terminate === true` 则把 terminate 写到结果上（`755-765`）。
3. hook 之后再次检查 aborted → `Operation aborted`（`767-773`）。
4. 返回 prepared `{toolCall: preparedToolCall, tool, args: validatedArgs}`（`774-779`）。
5. catch（prepareArguments 抛错、校验失败、beforeToolCall 抛错）→ immediate error，文本 `error instanceof Error ? error.message : String(error)`，`isError: true`（`780-786`）。因此 hook/校验异常表现为错误工具结果而非 run 失败。
6. 被 `block` 的调用不会执行 `afterToolCall`，也不测量 duration（测试 `test/agent-loop.test.ts:2167-2212` 的 hookCalls 断言）。
7. `beforeToolCall` 可以原地改 `args`，之后**不会重新校验**，execute 直接拿到改后的对象（测试 `test/agent-loop.test.ts:528-587`）。

`executePreparedToolCall`（`831-867`）：

- `startedAt = performance.now()`；`acceptingUpdates = true`；onUpdate 回调把 `Promise.resolve(onUpdate(partialResult))` 收进 `updateEvents`；settle 后 `acceptingUpdates = false` 并 `await Promise.all(updateEvents)`。
- 成功：`{result, isError: result.isError === true, durationMs: round(now-startedAt)}`。
- 异常：错误结果（异常 message），`isError: true`，仍带 `durationMs`；仍会等待已排队的 update。
- `finally` 再置 `acceptingUpdates = false`。工具 settle 后的 `onUpdate` 调用被静默忽略（测试 `test/agent.test.ts:473-612`）。
- 若某个 update promise reject，会被同一个 catch 捕获，成功结果被替换成错误结果（TS 语义；Kotlin 需保留）。

`finalizeExecutedToolCall`（`869-920`）：

- 有 `afterToolCall` 时 await；合并规则（`893-907`）：
  - `structuredContent = afterResult.structuredContent ?? (afterResult.content ? undefined : result.structuredContent)`
  - `content/details/usage/terminate` 分别 `afterResult.x ?? result.x`
  - `structuredContent === undefined` 时删除该字段，否则写入
  - `isError = afterResult.isError ?? isError`
  - 无深合并
- afterToolCall 抛错：结果替换为错误结果，`isError = true`，`durationMs` 保留。
- 输出 `{toolCall: prepared.toolCall, result, isError, durationMs}`。

`runToolCall`（`812-829`，公共 API，供工具嵌套调用）：

- 与模型发起的调用完全相同的管线（prepareArguments→validate→beforeToolCall→execute→afterToolCall），但**不发任何事件、不产生消息**。
- 参数：`{tools, assistantMessage, context, signal?, onUpdate?, beforeToolCall?, afterToolCall?}`（`800-810`）。
- immediate 结果返回 `{toolCall, result, isError}`（无 durationMs）；执行过的带 durationMs。
- 不为工具失败 reject（unknown/校验失败/blocked/thrown 都是 `isError:true`）。
- 测试见 `test/agent-loop.test.ts:2133-2234`。

消息与事件构造：

- `tool_execution_end`（`929-938`）：`{toolCallId, toolName, result, isError, durationMs?}`，durationMs 未定义时字段省略。
- `toolResult` 消息（`940-954`）：`{role:"toolResult", toolCallId, toolName, content: result.content ?? [], details: result.details, usage: result.usage, isError, durationMs?, timestamp: Date.now()}`。
- `message_start`/`message_end`（`956-959`）。
- 错误结果 helper（`922-927`）：`{content:[{type:"text",text:message}], details:{}}`（**details 为 `{}`，不是 undefined**）。
- 截断消息路径（`481-513`）：对每个 toolCall 发 start → 错误结果（文本含 `output token limit` 与 `Re-issue the tool call with complete arguments.`）→ end（无 durationMs）→ toolResult 消息事件；返回 `terminate:false`，循环会继续让模型重发（测试 `test/agent-loop.test.ts:456-526` 断言 execute 未执行、callIndex=2）。

### 4.5 停止条件（没有 maxTurns）

按优先级：

1. assistant `stopReason` 为 `"error"` 或 `"aborted"`：硬退出，`finishTurn` 后直接 `turn_end`+`agent_end`（`agent-loop.ts:254-265`）。
2. `finishTurn` 返回 `{action:"end"}`：`turn_end` 后在轮询队列/`prepareNextTurn` 之前结束（`agent-loop.ts:298-301`；测试 `test/agent-loop.test.ts:1217-1271`）。
3. 本轮无工具调用（或整批 `terminate=true`）且无 steering、无 follow-up、无 finishTurn continue：`agent_end`（`agent-loop.ts:305-329`）。
4. 有 follow-up：先注入再继续（follow-up 可无限续命）。
5. `finishTurn` 返回 `{action:"continue"}` 但没有自然请求：额外做一次 context-only 请求（可无限续命，README 警告无条件 continue 会死循环）。
6. 工具结果、steering、follow-up 任一存在即可自然满足 continue 决策，不额外请求（测试 `test/agent-loop.test.ts:1307-1402`）。

没有轮数上限、没有 token 预算、没有超时；调用方若不满足停止条件会一直请求下去。

## 5. agent.ts：`Agent` 类

### 5.1 状态构造（`agent.ts:83-112`）

1. `tools = initialState?.tools?.slice() ?? []`，`messages = initialState?.messages?.slice() ?? []`。
2. `initialMessage = createInitialSystemMessage(initialState?.systemPrompt, tools.map(toToolDeclaration))`。
3. 仅当 `messages[0]?.role !== "system"` 且有 initialMessage 时，把 initialMessage `unshift` 到 messages 头部。因此：若调用方自带首条 system 消息，`initialState.systemPrompt` 不再用于播种，`state.systemPrompt` 取转录重放结果。
4. 默认值：`model = initialState?.model ?? DEFAULT_MODEL`（`{id/name/api/provider:"unknown", baseUrl:"", reasoning:false, input:[], cost:全 0, contextWindow:0, maxTokens:0}`，`agent.ts:58-69`）；`thinkingLevel = initialState?.thinkingLevel ?? "off"`；`isStreaming=false`；`streamingMessage=undefined`；`pendingToolCalls=new Set()`；`errorMessage=undefined`。
5. `systemPrompt` 是 getter：`getCurrentSystemPrompt(messages)`（重放 content + sections + tools，`agent.ts:90-92`）。
6. `tools`/`messages` setter 复制顶层数组；getter 返回内部数组（可被 push 修改）。

### 5.2 `PendingMessageQueue`（`agent.ts:149-180`）

| 方法 | `"all"` 模式 | `"one-at-a-time"` 模式 |
|---|---|---|
| `peek()` | 返回全部消息的浅拷贝 | 只返回最旧一条（无则 []） |
| `drain()` | 取出全部并从队列删除 | 取出最旧一条并从队列删除 |
| `enqueue`/`hasItems`/`clear` | 普通 append / 非空判断 / 清空 | 同左 |

### 5.3 运行生命周期（`agent.ts:182-186,517-566`）

`ActiveRun = {promise, resolve, abortController}`。

`runWithLifecycle(executor)`：

1. 若已有 activeRun → 抛 `"Agent is already processing."`。
2. 新建 AbortController，手工构造一个可 resolve 的 promise，写入 `activeRun`。
3. `state.isStreaming = true`；`streamingMessage = undefined`；`errorMessage = undefined`。
4. `await executor(signal)`；异常 → `handleRunFailure(error, signal.aborted)`。
5. `finally` → `finishRun()`：isStreaming=false、streamingMessage=undefined、pendingToolCalls 换新 Set、resolve activeRun.promise、activeRun=undefined。

`handleRunFailure`（`542-558`）：合成失败 assistant：

```ts
{ role:"assistant", content:[{type:"text",text:""}],
  api: state.model.api, provider: state.model.provider, model: state.model.id,
  usage: EMPTY_USAGE, stopReason: aborted ? "aborted" : "error",
  errorMessage: error.message, timestamp: Date.now() }
```

然后依次 `processEvents`：message_start、message_end、turn_end{toolResults: []}、agent_end{messages:[failureMessage]}。

### 5.4 公共 API 与错误条件

| 方法/属性 | 行为 | 错误/边界 |
|---|---|---|
| `subscribe(listener)` | 加入 Set，返回取消函数 | 无初始事件 |
| `state` | 返回内部可变状态对象（同一引用） | — |
| `steeringMode`/`followUpMode` get/set | 控制队列 drain 模式 | — |
| `steer(m)`/`followUp(m)` | 入队，不立即改变 state.messages | — |
| `clearSteeringQueue`/`clearFollowUpQueue`/`clearAllQueues` | 清空 | — |
| `hasQueuedMessages()` | 任一队列非空 | — |
| `peekQueuedMessages()` | steering.peek() 非空则返回；否则 followUp.peek() | 不消费 |
| `signal` | 当前 run 的 AbortSignal 或 undefined | — |
| `abort()` | 有 run 则 abort signal；无 run 不抛错 | `test/agent.test.ts:669-674` |
| `waitForIdle()` | 返回当前 run 的 promise 或 resolved | run 结束后才 resolve |
| `reset()` | 有 run 抛错；否则 messages = 重放后的 baseline（有则 `[baseline]` 否则 `[]`）；清运行态字段与两条队列 | 抛 `"Agent is already processing. Wait for completion before resetting."` |
| `prompt(string \| AgentMessage \| AgentMessage[], images?)` | 有 run 抛错；normalize 后启动 run | 抛 `"Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion."` |
| `continue()` | 有 run 抛错；按尾巴分支（见 5.5） | `"Agent is already processing. Wait for completion before continuing."` / `"No messages to continue from"` / `"Cannot continue from message role: assistant"` |

`normalizePromptInput`（`422-439`）：数组原样返回（不复制）；非 string 的单条包成 `[input]`；string 生成 `{role:"user", content:[{type:"text",text}, ...images], timestamp: Date.now()}`。

### 5.5 `continue()` 分支（`agent.ts:392-420`）

1. 若 `messages` 为空或全为 system → 抛 `"No messages to continue from"`，**不消费队列**（测试 `test/agent.test.ts:945-959`）。
2. 若最后一条 role 为 `assistant`（无法直接续跑）：
   - `steeringQueue.drain()` 非空 → `runPromptMessages(queuedSteering, {skipInitialSteeringPoll:true})`，即把 steering 当成本次 prompt 消息，且跳过 loop 起点的 steering 轮询（避免同批投递两次；`agent.ts:404-408,476-515`）。
   - 否则 `followUpQueue.drain()` 非空 → `runPromptMessages(queuedFollowUps)`。
   - 否则抛 `"Cannot continue from message role: assistant"`。
3. 否则（user/toolResult/自定义角色）→ `runContinuation()`，走 `runAgentLoopContinue`：loop 起点会轮询 steering；follow-up 要等自然停止（测试 `test/agent.test.ts:981-1069`）。

### 5.6 循环配置装配（`agent.ts:476-515`）

- `model = state.model`；`reasoning = thinkingLevel === "off" ? undefined : thinkingLevel`（`480`）。
- 转发：`sessionId`、`onPayload`、`onResponse`、`onProviderStreamEvent`、`transport`、`thinkingBudgets`、`maxRetryDelayMs`、`identity`、`toolExecution`、`beforeToolCall`、`afterToolCall`、`finishTurn`、`prepareRequest`、`convertToLlm`、`transformContext`、`getApiKey`。
- `prepareNextTurn` 包装：优先 `prepareNextTurnWithContext(context, this.signal)`，否则 `prepareNextTurn(this.signal)`；传入的是**当前 activeRun 的 signal**，不是 loop 参数里的 signal（`494-502`）。
- `getSteeringMessages`：首次调用若 `skipInitialSteeringPoll` 为 true，则返回 [] 并清标记；之后 drain steering 队列（`506-512`）。
- `getFollowUpMessages`：drain follow-up 队列（`513`）。
- 每次 run 都重新装配配置，因此调用方在两次 prompt 之间修改 Agent 字段会生效（测试 `test/agent.test.ts:1177-1201` 的 sessionId）。

### 5.7 `processEvents` 的状态归约（`agent.ts:575-622`）

状态归约先于 listener：

| 事件 | 状态变化 |
|---|---|
| `message_start` | `streamingMessage = event.message`（任意角色，包括 user/toolResult） |
| `message_update` | `streamingMessage = event.message` |
| `message_end` | `streamingMessage = undefined`；`messages.push(event.message)` |
| `tool_execution_start` | 复制 Set 并加入 toolCallId |
| `tool_execution_end` | 复制 Set 并删除 toolCallId |
| `turn_end` | 若 message 为 assistant 且有 errorMessage，写入 `state.errorMessage` |
| `agent_end` | `streamingMessage = undefined` |

之后取 activeRun signal；无 signal 抛 `"Agent listener invoked outside active run"`；按 `listeners` Set 顺序逐个 `await listener(event, signal)`。

<!-- PART3 -->

