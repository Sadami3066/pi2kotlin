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
| `createInitialSystemMessage`、`getCurrentSystemMessage`、`getCurrentSystemPrompt`、`toToolDeclaration` | `agent.ts:3-4,86,369-370` | 初始 system 消息、reset 基线、`state.systemPrompt`、工具声明剥离 |
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
10. `parseStreamingJson`（`ai/utils/json-parse.ts:129-150` 区域）：先 `JSON.parse`；失败后 `repairJson` 再 parse；再失败退到 `partial-json`；再失败对修复文本做 partial；最后返回 `{}`。永远返回对象。
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

注意：若 `context.tools` 与转录里声明的工具不一致，`newMessages` 会在 user 之前多一条合成的工具声明 system 消息；此时 (b) 的角色序列为 `[system,user,assistant,toolResult,assistant]`。测试 `test/agent-loop.test.ts:1732-1781` 用 terminate 停在第一轮之后，断言序列为 `[system,user,assistant,toolResult]`。

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
- 注册顺序即调用顺序；一个事件的 listener 依次 await 完成后才处理下一个事件（`agent.ts:619-621`）。没有初始事件，状态 mutator 不发事件（测试 `test/agent.test.ts:309-329`）。
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
6. 每次 call 之后检查 `signal?.aborted`，aborted 则 break；**剩余 call 完全没有任何事件与结果**。随后仍会进 finishTurn/turn_end；`hasMoreToolCalls` 仍为 `!terminate`（通常 true），循环会带着已 abort 的 signal 再发起一次请求，而 assistant 消息里未被执行的 toolCall 在转录中没有配对 toolResult（TS 原样行为，provider 可能拒绝；移植不要“修复”）.

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

## 6. proxy.ts 与 stream-fn.ts

### 6.1 proxy 的用途与协议

用途：应用把 LLM 调用路由到自有服务端，由服务端持有认证并代理到 provider；客户端把 `streamProxy` 当作 `streamFn` 传给 Agent（`proxy.ts:1-5,72-105`）。

请求：

- `POST ${proxyUrl}/api/stream`
- Header：`Authorization: Bearer ${authToken}`、`Content-Type: application/json`
- Body：`{ model, context, options: ProxySerializableStreamOptions }`（`proxy.ts:147-159`；序列化字段白名单 `48-61,91-105`）

下游事件协议：SSE 风格，每行 `data: <JSON>`；JSON 为 `ProxyAssistantMessageEvent`（服务端去掉了 partial 以减少带宽，`proxy.ts:20-46,179-189`）。客户端负责重建 partial 并映射成 pi-ai 的 `AssistantMessageEvent`。

注意被丢弃的 options：`apiKey`、`signal`、`identity`、`onPayload`、`onResponse`、`onProviderStreamEvent`、`fetch`、`env`、`timeoutMs`、`maxRetries`、`toolChoice`、`deferred`、`websocketConnectTimeoutMs`。只有白名单里的 11 个字段发给服务端；`signal` 仅用于本地取消（`proxy.ts:91-105`）。

### 6.2 客户端 partial 重建（`proxy.ts:107-252,254-393`）

初始 partial：`{role:"assistant", stopReason:"pending", content:[], api: model.api, provider: model.provider, model: model.id, usage: 全 0, timestamp: Date.now()}`（`116-132`）。

映射表（服务端事件 → 修改 partial → 产出 pi-ai 事件）：

| proxy 事件 | partial 修改 | 产出的 AssistantMessageEvent | 异常 |
|---|---|---|---|
| `start` | 无 | `{type:"start", partial}` | — |
| `text_start` | `content[idx]={type:"text",text:""}` | `{type:"text_start",contentIndex,partial}` | — |
| `text_delta` | 要求 content[idx] 是 text，追加 delta | `{type:"text_delta",contentIndex,delta,partial}` | 非 text → throw `Received text_delta for non-text content` |
| `text_end` | 写 `textSignature` | `{type:"text_end",contentIndex,content:text,partial}` | 同上，非 text 抛错 |
| `thinking_start` | `content[idx]={type:"thinking",thinking:""}` | `{type:"thinking_start",contentIndex,partial}` | — |
| `thinking_delta` | 要求 thinking，追加 | `{type:"thinking_delta",contentIndex,delta,partial}` | 非 thinking → throw |
| `thinking_end` | 写 `thinkingSignature` | `{type:"thinking_end",contentIndex,content:thinking,partial}` | 非 thinking → throw |
| `toolcall_start` | `content[idx]={type:"toolCall",id,name,arguments:{},partialJson:""}` | `{type:"toolcall_start",contentIndex,partial}` | — |
| `toolcall_delta` | 追加 `partialJson`；`arguments = parseStreamingJson(partialJson) || {}`；用 `{...content}` 替换以触发响应式 | `{type:"toolcall_delta",contentIndex,delta,partial}` | 非 toolCall → throw |
| `toolcall_end` | `Object.assign(content, toolCall)`；删除 `partialJson` | `{type:"toolcall_end",contentIndex,toolCall:content,partial}`；若 content 不是 toolCall 返回 undefined（不发事件） | — |
| `done` | 写 `stopReason`、`usage`、可选 `providerThinkingLevel` | `{type:"done",reason,message:partial}` | — |
| `error` | 写 `stopReason`、`errorMessage`、`usage`、可选 `providerThinkingLevel` | `{type:"error",reason,error:partial}` | — |
| 未知 | 无 | `console.warn` 后 undefined（exhaustive 检查） | — |

关键：`toolcall_start` 只带 id/toolName；`arguments` 靠 `toolcall_delta` 累积；`toolcall_end` 的完整 `toolCall` 会整体合并（因此只在 end 出现的字段如 `namespace` 得以保留，测试 `test/proxy.test.ts:33-75`）。

### 6.3 流读取、错误与中止（`proxy.ts:134-249`）

- 创建 `AssistantMessageEventStream`；异步 IIFE 执行请求；`options.signal` 的 abort 监听器会 `reader.cancel("Request aborted by user")`；结束时移除监听器（`136-144,244-248`）。
- `fetch` 非 2xx：先尝试解析 JSON `{error}`；消息为 `Proxy error: ${error}` 或 `Proxy error: ${status} ${statusText}`；throw 进入 catch（`161-172,233-243`）。
- 读循环：每次 `reader.read()` 后检查 `signal.aborted`，是则 throw；解码、按 `\n` 切行，行缓存留在 buffer；逐行 `processLine`。`processLine` 只处理 `data: ` 前缀，JSON.parse 失败会抛到外层 catch（`174-206`）。
- EOF：先检查 aborted；再 flush decoder（`decoder.decode()`）并处理最后一行（不要求换行结尾；测试 `test/proxy.test.ts:78-97`）；若从未见到 done/error，则合成 `partial.stopReason="error"`、`partial.errorMessage="Connection closed by proxy server before the response completed"`、push `{type:"error",reason:"error",error:partial}`（`208-232`；测试 `test/proxy.test.ts:99-118`）。
- catch：`reason = signal.aborted ? "aborted" : "error"`；partial 写 stopReason/errorMessage；push error 事件；`stream.end()`（`233-243`）。
- 所有路径最终 `stream.end()`，保证 `result()` 可解析（相比低层 loop 的悬挂问题，proxy 主动兜底）。

### 6.4 stream-fn.ts（`stream-fn.ts:1-20`）

- 模块级 `let defaultStreamFn: StreamFn | undefined`。
- `setDefaultStreamFn(fn | undefined)`：设置/清除全局默认。
- `getDefaultStreamFn()`：未设置时 throw `"No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn()."`。
- 使用点：`Agent` 构造时 `runtimeOptions.streamFn ?? getDefaultStreamFn()`（`agent.ts:244`）；`runAgentLoop`/`runAgentLoopContinue` 在进入 runLoop 前 `streamFn ?? getDefaultStreamFn()`（`agent-loop.ts:133,158`）。
- 行为特征：全局可变、进程级；测试每个用例后 `setDefaultStreamFn(undefined)` 清理（`test/agent.test.ts:93-112`、`test/agent-loop.test.ts:79-111`）。coding-agent 在 sdk 模块加载时 `setDefaultStreamFn(streamSimple)`（`ca/core/sdk.ts:46`）。

## 7. coding-agent 对 pi-agent 的实际依赖面

### 7.1 依赖符号清单

从 `@earendil-works/pi-agent-core` 导入（`ca/core/agent-session.ts:19-33`）：

- 类型：`Agent`、`AgentContext`、`AgentEvent`、`AgentMessage`、`AgentState`、`AgentTool`、`AgentToolCallOutcome`、`BeforeToolCallContext`、`BeforeToolCallResult`、`AfterToolCallContext`、`AfterToolCallResult`、`PrepareNextTurnContext`、`ThinkingLevel`。
- 值：`runToolCall`。
- 其它文件：`setDefaultStreamFn`（`ca/core/sdk.ts:2,46`）、`Agent`（`ca/core/sdk.ts:403`）、`AgentMessage`/`ThinkingLevel`（同文件）。

### 7.2 依赖的字段/方法/语义（按重要性）

1. `new Agent({initialState, convertToLlm, streamFn, onPayload, onResponse, onProviderStreamEvent, sessionId, transformContext, steeringMode, followUpMode, transport, thinkingBudgets, maxRetryDelayMs})`（`ca/core/sdk.ts:403-445`）。`initialState.messages` 直接灌入既有会话消息；`systemPrompt: ""`、`tools: []`。
2. 直接给 Agent 公共字段赋值安装 hook：
   - `agent.beforeToolCall` / `agent.afterToolCall`（`ca/core/agent-session.ts:653-656`）；
   - `agent.prepareRequest` 包装（`788-886`）：每次请求前用 session 投影替换 `context.messages`，`context.tools` 保持可执行实现；可选虚拟模型路由；可触发压缩；
   - `agent.finishTurn` 包装（`887-897`）：先跑 extension 的 turn_end 边界，再沿用前一个 finishTurn；`end` 优先，`continue` 可被 extension 请求；
   - `agent.prepareNextTurnWithContext` 包装（`899-937`）：压缩、重新计算系统提示与工具装载，返回 context/messages/model/thinkingLevel；
   - `agent.transformContext` 包装（`ca/core/agent-session.ts:1773-1801` 附近）：接 extension 的 context 事件。
   说明这些字段必须是**可替换的公共可变属性**，且 hook 必须接收当前 Agent 状态。
3. `agent.prompt(messages)` 与 `agent.continue()` 组成的运行循环（`ca/core/agent-session.ts:1884-1893`）：prompt 之后根据 post-run 处理决定是否 continue，可连续多次；说明 `prompt()` 必须等到 run 完全结算（含 agent_end 监听器）才 resolve，否则会与后续 continue 竞争。
4. `agent.steer(...)` / `agent.followUp(...)`（`2296,2313,2361-2363`）；`agent.peekQueuedMessages()`（`997`）；`agent.hasQueuedMessages()`（`1020-1021,1973,2001,3262`）；`agent.abort()`（`1403`）。
5. `agent.state` 读写：`state.model`、`state.thinkingLevel`、`state.messages`、`state.tools`（`752-753,799,805-806,924-930,944,1273` 等）；`state.messages` 会被整体替换（`944`），且元素对象身份被 SessionManager 用作 map key（`_entryIdsByMessage`，`1218-1240`）。
6. `agent.subscribe(handler)`（`512-514`）；事件驱动持久化/扩展/重试；`_handleAgentEvent` 消费所有事件类型。
7. `agent.toolExecution`（`739`）、`agent.streamFunction === streamSimple`（`591`）读取；`agent.state.tools` 的名称集合即模型可见工具集。
8. `runToolCall(toolCall, {tools, assistantMessage, context, beforeToolCall, afterToolCall, signal, onUpdate})` 用于工具嵌套调用（`ca/core/agent-session.ts:736-760`）：要求它复用同一 before/after hook 管线、不产生事件/消息、失败不 reject。
9. 消息对象是**可变且身份稳定**的：
   - `_handleAgentEvent` 在 `message_start`（toolResult）时直接改 `message.nestedCalls`/`message.usage`（`ca/core/agent-session.ts:1107-1122`）；
   - `_replaceMessageInPlace` 删除目标对象全部自有 key 再把 replacement 的字段 assign 回去，保持对象身份（`1271-1287`），注释明确依赖“Agent 在 message_end 前已把最终消息对象放进 state”；
   - `_findPersistedMessageEntryId` 用 `state.messages.indexOf(message)` 和对象相等性（`1218-1240`）。
   因此 Kotlin 消息类型不能全部做成不可变 data class 并在事件里传副本，否则 coding-agent 的持久化映射与就地改写语义会坏。
10. `AgentEvent` 语义依赖：`message_start`（user）用于把队列消息从 UI 队列移除（`1127-1145`）；`message_end` 用于持久化；`turn_end` 用于边界；`agent_end` 用于重试判定（`1202-1216`）；`agent_start` 时若已请求 abort 则立即 abort（`1104-1108`，依赖 agent_start 在首个请求前被 await）。
11. `convertToLlm` 由调用方提供，pi-agent 只在请求边界调用一次/轮（`ca/core/messages.ts:150-190`）；Agent 的 `defaultConvertToLlm` 对 coding-agent 不适用。

### 7.3 由此反推的 Kotlin 兼容性硬要求

- `Agent` 的 `beforeToolCall`/`afterToolCall`/`prepareRequest`/`prepareNextTurnWithContext`/`finishTurn`/`transformContext`/`convertToLlm`/`streamFunction` 必须是可运行时替换的公开可变属性（或等价的安装 API）。
- 消息对象必须支持身份比较与受控的就地替换（Kotlin 中 `===` 引用比较在 data class 上依然成立，但如果事件与 state 传不同实例就会破）。
- `runToolCall` 必须是公开 API。
- 事件 payload 里的 message 必须与 `state.messages` 中最终存储的是同一个实例（`message_end` 时 push 的就是事件对象，`agent.ts:587`）。
- `prepareRequest` 的 context 替换必须持久影响后续请求（coding-agent 每次都用 session 投影覆盖），且 pending 消息已先写入事件/状态。

## 8. Kotlin 移植设计

### 8.1 模块与包布局

沿用现有 Gradle 多模块：`:pi-ai` → `:pi-agent` → `:pi-coding-agent`；`pi-agent` 依赖 `pi-ai`，新增代码全部放在 `pi-agent/src/main/kotlin/dev/pi/agent/`。`pi-agent` 不应依赖 okhttp（proxy 只依赖 `java.net.http` 或复用 pi-ai 的 HTTP 抽象；现有 `pi-ai/build.gradle.kts` 已含 okhttp，可把 proxy 的 HTTP 客户端抽到 pi-ai 或直接在 pi-agent 用 JDK HttpClient 以避免新依赖）。

建议包划分：

| 文件 | 内容 |
|---|---|
| `Types.kt` | 枚举、StreamFn typealias、AgentMessage/CustomAgentMessage、hook 接口与结果、AgentLoopConfig、turn/prepare 类型、AgentTool/结果、AgentContext、AgentState、AgentInitialState、AgentOptions、AgentEvent sealed、proxy 事件类型 |
| `AgentLoop.kt` | `agentLoop*`、`runAgentLoop*`、`runLoop`、`declareToolChanges`、`streamAssistantResponse`、工具执行管线、`runToolCall`、内部 helper |
| `Agent.kt` | `Agent`、`PendingMessageQueue`、默认状态/model/usage、生命周期、事件归约、队列 API |
| `Proxy.kt` | `streamProxy`、SSE 解析、partial 重建、proxy 类型 |
| `StreamFn.kt` | 全局默认 StreamFn 注册表 |

### 8.2 类型映射规则

| TS | Kotlin | 备注 |
|---|---|---|
| `interface` 可选字段 | `val` + 可空 + 默认值；构造用命名参数 | 语义等价 |
| 联合类型 `A \| B` | `sealed interface` + data class 子类 | 用于 `Message`、`AgentMessage` 核心层、`AssistantMessageEvent`、`AgentEvent` |
| `any`/`unknown` | `JsonElement`（details/structuredContent/args）；需要逐步类型化时给泛型 | 注意 `args` 的**可变性**（8.3） |
| `JsonValue`/`JsonObject` | `JsonElement`/`JsonObject` | 需要 mutable 版本时用 `LinkedHashMap<String, JsonElement>` |
| `AbortSignal` | 显式 `RunSignal`（`isAborted` + `abort()` + 回调/await），不直接用 `Job`（8.4） | pi-ai 的 streamFn 也用它 |
| `Promise<T>` | `suspend fun` / `Deferred<T>` | StreamFn/事件 sink 全部 suspend |
| `AsyncIterable<T>` + `result()` | `Flow<T>` + `CompletableDeferred<R>`（或自定义 `EventStream` 类） | 需缓冲、单消费者、可先 result 后消费 |
| `Date.now()` / `performance.now()` | `currentTimeMillis()` / `System.nanoTime()` | durationMs 用单调时钟并 round |
| `Set<string>` copy-on-write | `Set<String>` 不可变集合，事件处理时整体替换 | 保留“订阅者拿到旧 Set 不变” |
| `Record<string, string\|null>`（headers） | `Map<String, String?>` | — |

`AgentLoopConfig` 在 TS 里 `extends SimpleStreamOptions`，Kotlin 无继承 data class，建议：

- pi-ai 定义 `class SimpleStreamOptions(...)` 或 `data class` 含全部可选字段；
- pi-agent 定义 `class AgentLoopConfig(val model, val convertToLlm, ..., val streamOptions: SimpleStreamOptions?)`，并暴露便捷属性（`reasoning`、`sessionId`、`transport`、`thinkingBudgets`、`maxRetryDelayMs`、`onPayload`、`onResponse`、`onProviderStreamEvent`、`identity`、`temperature` 等）以便测试与调用方按 TS 习惯构造；
- `streamAssistantResponse` 处显式构造 `SimpleStreamOptions`（只含 pi-ai 字段）再传给 `StreamFn`。TS 实际把带额外字段的 config 整个展开传给 streamFn，多出来的键在 JS 里无害；Kotlin 不应把 Agent 专用字段塞进 options（允许偏差，见 8.10）。

### 8.3 AgentMessage 的 Kotlin 表达（决定模块方向）

问题：TS 的 `AgentMessage = Message | CustomAgentMessages[keyof ...]` 依赖声明合并，Kotlin 没有等价物；同时 `UserMessage` 等核心类型在 `:pi-ai`，而 `AgentMessage` 概念属于 `:pi-agent`，模块依赖方向只允许 pi-agent → pi-ai。

推荐方案（A）：把“可被 agent 承载的消息”接口下沉到 pi-ai：

```kotlin
// pi-ai
interface AgentMessage { val role: String }
sealed interface Message : AgentMessage
class SystemMessage(...) : Message
class UserMessage(...) : Message
class AssistantMessage(...) : Message
class ToolResultMessage(...) : Message

// pi-agent
interface CustomAgentMessage : AgentMessage
// 应用侧示例
class BashExecutionMessage(...) : CustomAgentMessage { override val role = "bashExecution" }
```

理由：

- `AgentContext.messages`、`AgentEvent`、`AgentState.messages` 统一用 `List<AgentMessage>`；
- `convertToLlm` 内部 `filterIsInstance<Message>()` 即可得到核心消息，`sealed interface Message` 保留 `when` 穷尽；
- 应用自定义消息实现 `CustomAgentMessage`，与 TS 的声明合并等价（编译期无法把自定义类型并入一个“封闭联合”，但可以在需要时 `when (m) { is Message -> ...; is CustomAgentMessage -> ... }`；loop 本身只检查 role，透明性一致）；
- pi-ai 只增加一个空接口/一个父接口，不反向依赖 pi-agent。

代价与注意事项：

- 失去 TS 那种“自定义消息类型自动进入 AgentMessage 联合”的编译期穷尽提示；用 `else -> error(...)` 或显式注册表弥补。
- 序列化：核心消息按 `role` 判别；自定义消息需要应用侧 codec（`CustomAgentMessage` 可要求 `val role` + 应用自己的 `JsonElement` 载荷）。pi-agent 不做持久化，故不强制。
- 若 pi-ai 已定稿为 `sealed interface Message` 而不能加父接口，则退化为方案 C（pi-agent 定义 `AgentMessage` 包装层 + 核心消息子类），但会引入转换层与实例身份问题，不推荐。

另一个必须决策的点：`AgentTool.execute` 的 `args` 与 hook 的 `args` 是**可变对象**。TS 中 `beforeToolCall` 可以原地修改 `args` 且 execute 直接使用修改后的对象、不重新校验（`agent-loop.ts:738-779`；测试 `test/agent-loop.test.ts:528-587`）。kotlinx.serialization 的 `JsonObject` 不可变，因此：

- 工具的原始参数与校验后参数用 `MutableJsonObject`（例如 `LinkedHashMap<String, JsonElement>` 或自定义可变包装）；
- `AgentToolExecute` 泛型参数可提供 `Args` 类型 + 反序列化，但 hook 层仍要能修改；
- 建议保留一个内部可变 JSON 对象贯穿 prepare→hook→execute，只在产生 `ToolCall.arguments`/消息 payload 时冻结。

类似地，`AgentToolResult.details` 在 coding-agent 侧是任意 JSON 可序列化值，Kotlin 用 `JsonElement`（`JsonNull` 表示 undefined 还是 null 需统一：TS 中 `details: undefined` 与缺省不同，`test/agent-loop.test.ts:1225` 用 `details: undefined`；建议 `JsonElement?` + 显式 null 区分）。

### 8.4 并发与取消模型

Kotlin 协程与 JS 事件循环的三个根本差异，必须在实现里显式处理：

1. **取消语义**：TS 的 abort 不抛异常，只是把 AbortSignal 置为 aborted，各层自行检查；`Agent.abort()` 之后 `prompt()` 仍会正常 resolve（最终 assistant 消息 stopReason=aborted）。因此 Kotlin 的 run 不能简单用“cancel 协程”实现 abort（会抛 `CancellationException`，与 TS 行为不符）。推荐：
   - 定义 `class RunSignal { val isAborted; fun abort(cause); fun onAbort(handler); suspend fun awaitAbort() }`，等价 AbortSignal；
   - `Agent.abort()` 调用 `activeRun.signal.abort()`，不 cancel 协程；
   - run 协程用 `coroutineScope`/`supervisorScope` 管理子协程；调用方协程被取消时，在 `finally` 里 abort signal 并传播 `CancellationException`（这是 Kotlin 额外语义，TS 没有；建议作为允许偏差记录）；
   - streamFn/tool/hook 的签名都带 `RunSignal`，与 TS 一一对应；
   - 循环内部只在 TS 的相同位置检查 `signal.isAborted`（工具 preflight、execute 前后、sequential 每次 call 后），不要额外在每轮开头 check，否则会改变“abort 后仍完成当前工具/仍发一次请求”的可观察行为。
2. **并发工具执行**：TS 的 `Promise.all` 是单线程微任务并发；Kotlin 用 `coroutineScope { toolCalls.map { async { ... } }.awaitAll() }`：
   - preflight 仍串行；
   - 每个 thunk 一个 `async`，`awaitAll` 保序返回（源顺序）；
   - `tool_execution_end` 在各自 async 内发出，顺序=完成顺序；
   - 事件 sink 需要串行化（见下）；
   - 注意 `CoroutineStart.LAZY` 不必需（`async` 立即调度即可，因为 TS 的 thunk 也是全部同时启动）。
3. **事件/状态串行化**：TS 单线程保证 `emit` 与 `state` 修改不交错。Kotlin 并行工具会并发调用 sink，因此：
   - Agent 的 `processEvents` 与 loop 的 `emit` 需要一个 `Mutex` 串行化（或在 `Dispatchers.Default.limitedParallelism(1)` 上跑整个 run）；推荐“整个 run 在单线程 dispatcher/`limitedParallelism(1)` 上运行”，最接近事件循环语义，同时保留并发工具的挂起点真实并行 IO；
   - 若用 Mutex，则 listener 的 await 在锁内会阻塞其它 tool 的 end 事件，顺序语义接近 TS（TS 中一个 emit 的 await 也会阻塞该 sink 的后续调用，但其它 thunk 仍可在 await 点插入——顺序不严格确定；测试只用确定性 gating，不依赖交错）。
   - `steer()`/`followUp()` 可能从其它协程调用：队列必须线程安全（`synchronized`/`ConcurrentLinkedQueue` 或 `Mutex` 包装；方法非 suspend 的话用锁临界区最短）。
   - `state.pendingToolCalls` 在事件处理中整体替换，订阅者不应持锁读；建议 volatile/atomic reference 或单线程限定的“只保证在 sink 线程可见”。
4. **事件流（低层 API）**：`agentLoop` 用 `Channel<AgentEvent>(UNLIMITED)` + `CompletableDeferred<List<AgentMessage>>` 实现：立即启动 run 协程（需传入 `CoroutineScope`，或返回带 `start(scope)` 的冷包装；推荐显式 scope 参数），`Flow = channel.receiveAsFlow()`，`result = deferred.await()`；run 异常时 `channel.close(cause)`/deferred 异常完成。`send` 在 `Channel.UNLIMITED` 下不会挂起，等价 `stream.push`；`agent_end` 先完成 deferred 再 close。
5. **订阅 API**：为与 TS 一致，`Agent.subscribe` 接收 `suspend (event: AgentEvent, signal: RunSignal) -> Unit`，内部用 `LinkedHashSet` 保存并按插入顺序 await；不要用 `SharedFlow` 的 `emit` 代替（会失去“逐个 await、prompt 结算包含监听器”的语义）。可以额外提供 `val events: Flow<AgentEvent>`（内部把事件 send 到 Channel）给 UI 用，但结算仍以 listener 为准。

### 8.5 状态并发建议

- `AgentState` 保持“可变对象 + 稳定身份”：`var model`、`var thinkingLevel`、自定义 getter/setter 的 `tools`/`messages`（setter 复制顶层列表）、`val isStreaming` 等。不要把 `Agent.state` 换成 `StateFlow<Snapshot>`；coding-agent 依赖 `agent.state.messages = ...`、`agent.state.messages.indexOf(msg)` 和对象身份。
- `systemPrompt` 为计算属性：重放当前 messages 的所有 system 消息（`getCurrentSystemPrompt`）；Kotlin 里缓存与否都可，但必须每次读到最新。
- `errorMessage`/`streamingMessage`/`pendingToolCalls` 的清理时机完全照 5.3/5.7；`pendingToolCalls` 每次事件替换新不可变 Set。
- 建议在文档 KDoc 中声明：`Agent` 的调用（prompt/continue/reset/subscribe/队列 API）不是线程安全的，除 `steer`/`followUp`/`abort` 外的调用应来自同一协程；`steer`/`followUp`/`abort` 可从任意线程调用。

### 8.6 StreamFn / transport / proxy 的 Kotlin 形态

- `typealias StreamFn = suspend (Model<Api>, TranscriptContext, SimpleStreamOptions?) -> AssistantMessageEventStream`。
- pi-ai 侧 `AssistantMessageEventStream` 建议做成“写端 + 读端”：
  - 写端：`push(event)`、`end(result?)`；
  - 读端：`Flow<AssistantMessageEvent>`、`suspend fun result(): AssistantMessage`；
  - 内部用 `Channel(UNLIMITED)` + `CompletableDeferred<AssistantMessage>`，完成事件（done/error）先解析 result；push 在 done 后忽略；`durationMs` 在 done/error/end 时补。
  这与 `ai/utils/event-stream.ts:26-135` 等价，且能复用到 pi-ai 的 provider 实现。
- `transport`：`enum class Transport(val wire: String) { SSE("sse"), WEBSOCKET("websocket"), WEBSOCKET_CACHED("websocket-cached"), AUTO("auto") }`；`Agent.transport` 默认 `AUTO`；序列化/透传用 `wire`。
- `proxy`：用 JDK `HttpClient`（`java.net.http`）发起 POST、按行读取 SSE；不需要新依赖。关键点与 6.2/6.3 一一对应：
  - 维护可变 partial（Kotlin `class` 而非 data class，字段可变：`stopReason`、`errorMessage`、`usage`、`content` 列表）；
  - 逐行解析 `data: `，`Json.decodeFromString<ProxyAssistantMessageEvent>`（用 `JsonElement` + 手动判别 type，避免多态序列化配置）；
  - abort 时取消响应体读取（`HttpResponse.BodyHandlers.ofLines()` 流可关闭）；
  - EOF 无终态必须合成 error（错误文本一致）。
- `setDefaultStreamFn`：Kotlin 用 `@Volatile var defaultStreamFn: StreamFn?` + `synchronized` 设置；未设置抛同文案异常。注意全局可变状态在并行测试中的隔离（测试 teardown 清空）。

### 8.7 队列 / interrupt / steer 语义映射

| TS 行为 | Kotlin 实现 |
|---|---|
| `steer` = 入队 steering，不立即可见 | `ArrayDeque<AgentMessage>` + 锁；`steer` 非 suspend |
| `followUp` = 入队，agent 本会停时才消费 | 同上，独立队列 |
| `steeringMode`/`followUpMode` 可随时改 | 队列对象持有 `var mode`（或 Agent 持有并传入 drain） |
| `peek` 返回 all 的浅拷贝 / one 的第一条 | `toList()` / `listOf(first)` |
| `drain` 消费 | `removeFirst(n)` 或清空 |
| loop 拉取点固定（3 处 steering、1 处 follow-up） | 由 `AgentLoopConfig` 的 `getSteeringMessages`/`getFollowUpMessages` 回调对应，Agent 内部 drain |
| `continue()` assistant 尾：steering 优先，其次 follow-up，最后抛错 | 按 5.5 实现；`skipInitialSteeringPoll` 用局部 boolean 闭包传给 loop 配置（TS 如此，`agent.ts:477,507-510`） |
| `abort` 不清队列；`reset` 清队列 | 一一对应 |
| `waitForIdle` | `CompletableDeferred<Unit>`，`finishRun` 时 complete；空闲时返回已完成 Deferred |

所谓 “interrupt” 在本包里没有独立的 interrupt 方法：中断= `abort()`；steer 是“运行中插话”，follow-up 是“跑完再说的下一轮输入”。coding-agent 的 `/steer` 与 `/followUp` 最终就是这两个队列（`ca/core/agent-session.ts:2296-2313`）。

### 8.8 错误/异常映射

| TS 场景 | TS 表现 | Kotlin 建议 |
|---|---|---|
| streamFn throw | Agent 合成失败消息（`AbortController` 状态决定 error/aborted），prompt 正常 resolve | `catch (e: Throwable)`（排除 `CancellationException`）→ 合成失败消息；外部协程取消则 abort signal 并重新抛出 `CancellationException` |
| stream 事件 error/aborted | 最终 assistant 消息；硬退出 | 相同 |
| 工具 execute throw | 错误工具结果，run 继续 | `catch (e: Throwable)` → `AgentToolResult(isError=true, content=[text(e.message)])` |
| beforeToolCall throw | 错误工具结果（不执行工具），run 继续 | 相同（注意不能吞 `CancellationException`；若调用方取消，应传播） |
| afterToolCall throw | 错误工具结果，保留 durationMs | 相同 |
| validateToolArguments 失败 | 错误工具结果，文本为校验错误 | 由 pi-ai 校验器抛出带同格式文案的异常，loop 转错误结果 |
| 订阅者 throw | 视为 run 失败，合成失败事件；若再次 throw 则 prompt reject | 若需要与 TS 完全一致则照做；否则建议记录并继续（需明确偏差） |
| agentLoop 内部 throw（低层） | 返回的流永不结束（bug 式行为） | 建议流以异常完成（允许偏差） |
| 并发调用 prompt/continue/reset | 精确错误文案 | 相同文案，抛 `IllegalStateException` |
| listener 在无 run 时被调用 | `"Agent listener invoked outside active run"` | 相同（或断言失败） |

### 8.9 必须逐字保真的行为清单

实现时逐项勾验；编号在 Kotlin 测试里直接引用。

- B01 `agent_start` 是第一个事件，`agent_end` 是最后一个；首个 `turn_start` 紧随 `agent_start`；后续 `turn_start` 在 `prepareNextTurn` 之后、pending 消息事件之前（`agent-loop.ts:126-131,216`）。
- B02 prompt 运行的初始消息（prompt + 可能合成的工具声明 system 消息）在 `agent_start`/`turn_start` 之后逐条发 `message_start`+`message_end`，并进入返回消息（`agent-loop.ts:119-131`）。
- B03 continue 运行不重发既有消息事件，`newMessages`/`agent_end.messages` 只含本次新增（`agent-loop.ts:152-158`；测试 `test/agent-loop.test.ts:2031-2070`）。
- B04 每条最终 assistant 消息写 `thinkingLevel = reasoning ?? "off"`（`agent-loop.ts:418-419`）。
- B05 `message_update` 只在见过流的 `start` 后发出；若流直接 done/error，补发 `message_start`（`agent-loop.ts:442-451,456-463,470-476`）。
- B06 流式 `message_start`/`message_update` 的 message 是顶层浅拷贝；`message_end` 传最终对象本身（`agent-loop.ts:430,448,464,477`）。
- B07 `tool_execution_start.args` 是 assistant toolCall 的原始 `arguments`，不经 `prepareArguments`/校验（`agent-loop.ts:552-557`；测试 `test/agent-loop.test.ts:589-666` 的 prepareArguments 只影响校验/执行）。
- B08 并行批：`tool_execution_end` 按完成顺序、toolResult 消息与 `turn_end.toolResults` 按源顺序（`agent-loop.ts:656-669`；测试 `test/agent-loop.test.ts:668-760`）。
- B09 串行判定：全局 `"sequential"` 或批内任一工具 `executionMode: "sequential"` → 整批串行；否则并行（`agent-loop.ts:518-533`；测试 `test/agent-loop.test.ts:867-1106`）。
- B10 并行批的 preflight（start、查找、prepareArguments、校验、beforeToolCall）串行执行，执行阶段才并发（`agent-loop.ts:606-658`）。
- B11 abort 检查点与效果：beforeToolCall 之后（abort 优先于 block）、hook 之后、preflight 每次 call 后、并行 thunk 开头、串行每次 call 后（`agent-loop.ts:748-773,585-587,623-653`）。
- B12 工具未找到/参数校验失败/`prepareArguments` 抛错/beforeToolCall 抛错 → 错误工具结果（`isError=true`），run 不失败（`agent-loop.ts:726-733,780-786`；测试 `test/agent-loop.test.ts:2167-2212` 的 hookCalls）。
- B13 `beforeToolCall` 原地修改 `args` 后，execute 直接使用修改值且不再校验（`agent-loop.ts:738-779`；测试 `test/agent-loop.test.ts:528-587`）。
- B14 `prepareArguments` 返回同一引用时 `toolCall` 不替换；否则替换 arguments（`agent-loop.ts:704-716`）。
- B15 `block:true` 的错误文本为 `reason || "Tool execution was blocked"`；`terminate:true` 写到结果上（`agent-loop.ts:755-765`；测试 `test/agent-loop.test.ts:1783-1839`）。
- B16 `afterToolCall` 只做字段级覆盖，无深合并；给 content 不给 structuredContent 时丢弃 structuredContent；hook 抛错→错误结果且保留 durationMs（`agent-loop.ts:880-919`；测试 `test/agent-loop.test.ts:2214-2233`）。
- B17 `finishTurn` 在 assistant+全部工具结果之后、`turn_end` 之前调用，normal/error/aborted 都调用；error/aborted 的决策被忽略；`{action:"end"}` 在 turn_end 后立即 agent_end，跳过队列轮询与 prepareNextTurn；`{action:"continue"}` 在没有工具结果/steering/follow-up 时额外做一次 context-only 请求，且该轮会再次调用 `prepareNextTurn`（`agent-loop.ts:194-217,254-265,289-307,319-323`；测试 `test/agent-loop.test.ts:1108-1402`、`test/agent.test.ts:1071-1134`）。
- B18 `prepareRequest` 每个请求前调用（含首个），pending 消息已追加并发事件；不轮询队列；返回的 context/model/thinkingLevel 持久作用于本次 run 后续请求（`agent-loop.ts:228-248`；测试 `test/agent-loop.test.ts:1404-1501`）。
- B19 `prepareNextTurn` 仅在已有一轮完成后、下一轮开始前调用；返回 messages 走正常消息生命周期并进入 newMessages（`agent-loop.ts:194-225`；测试 `test/agent-loop.test.ts:1503-1578`）。
- B20 steering 轮询点：运行起点、每轮结束后、prepareNextTurn 之后且 pending 为空时；follow-up 只在“本会停止”时轮询（`agent-loop.ts:185,213-215,304-317`；测试 `test/agent-loop.test.ts:1458-1501`、`test/agent.test.ts:981-1069`）。
- B21 批终止：`finalizedCalls.length > 0 && every(result.terminate === true)`；blocked 与 afterToolCall 都能设置 terminate（`agent-loop.ts:700-702`；测试 `test/agent-loop.test.ts:1732-2009`）。
- B22 `stopReason === "length"` 时该 assistant 的全部 toolCall 失败不执行，错误文本包含 “output token limit”，循环继续（`agent-loop.ts:276-279,481-513`；测试 `test/agent-loop.test.ts:456-526`）。
- B23 工具 update：execute settle 前的 `onUpdate` 被接受并 await；settle 后的调用静默忽略；update promise 异常可把成功结果变成错误结果（`agent-loop.ts:831-867`；测试 `test/agent.test.ts:473-612`）。
- B24 `durationMs` 只测 `execute()`（单调时钟、四舍五入、不含 hook）；未真正执行则结果/消息/事件都没有 durationMs（`agent-loop.ts:838-866,936,951`；测试 `test/agent-loop.test.ts:402-454`）。
- B25 toolResult 消息字段：`content: result.content ?? []`、`details`、`usage`、`isError`、可选 `durationMs`、`timestamp: Date.now()`；错误结果 `details={}`（`agent-loop.ts:922-927,940-954`）。
- B26 `tool_execution_end` 先于 toolResult 的 `message_start`；toolResult 的 start/end 成对出现（`agent-loop.ts:579-583,929-959`）。
- B27 `tool_execution_end.result` 是最终 `AgentToolResult`（含被 hook 覆盖后的 content/details/structuredContent/usage/terminate）（`agent-loop.ts:914-937`）。
- B28 队列默认 `"one-at-a-time"`；`peek` 不消费；`peekQueuedMessages` steering 优先，空则 follow-up（`agent.ts:255-256,339-342`；测试 `test/agent.test.ts:1136-1153`）。
- B29 `abort()` 空闲时不抛错、不清队列、不改变 state；abort 后 `prompt()` 仍以 assistant stopReason=aborted 正常 resolve（`agent.ts:350-352`；`test/agent.test.ts:669-674`；`test/e2e.test.ts:208-219`）。
- B30 `prompt`/`continue`/`reset` 的运行中保护与精确错误文案（5.4 表；`agent.ts:365-366,383-386,394-396,518-520`）。
- B31 `continue()` 三类尾巴与队列消费：空/system-only 抛 `"No messages to continue from"` 且不消费；assistant 尾先 steering 后 follow-up，仍空则抛 `"Cannot continue from message role: assistant"`；其它尾走 continue，steering 起点轮询、follow-up 延后（`agent.ts:398-419`；测试 `test/agent.test.ts:824-1069`）。
- B32 `reset()` 用 `getCurrentSystemMessage` 重放基线（含当前 prompt/sections/工具声明），清空两条队列与运行态字段（`agent.ts:363-377`；测试 `test/agent.test.ts:282-307`）。
- B33 状态归约先于 listener；`message_start` 设置 `streamingMessage`（任何角色）；`message_end` push 并清空；`tool_execution_*` 复制替换 pendingToolCalls 集合（`agent.ts:576-613`）。
- B34 `isStreaming` 到 `agent_end` 监听器全部结算后才 false；`waitForIdle` 同样等待（`agent.ts:517-566`；测试 `test/agent.test.ts:362-433`）。
- B35 listener 按插入顺序串行 await；listener 异常导致 run 失败路径（3.4；`agent.ts:619-621,517-540`）。
- B36 streamFn 违约 throw 时合成失败消息：空文本、当前 model 的 api/provider/id、`EMPTY_USAGE`、stopReason=aborted|error、errorMessage、timestamp；事件序列见 3.2(f)（`agent.ts:542-558`）。
- B37 `agent_end.messages` 三种语义（prompt 含初始消息、continue 只含新增、失败路径只含失败消息）（`agent-loop.ts:134,159,263,299,329`；`agent.ts:557`）。
- B38 默认 `convertToLlm` 只保留 role 为 system/user/assistant/toolResult 的消息（`agent.ts:39-47`）。
- B39 `Agent` 构造：tools/messages 顶层复制；`createInitialSystemMessage` 在有 prompt 或 tools 时生成首条 system 消息，仅在 messages 首条不是 system 时 unshift（`agent.ts:83-112`；测试 `test/agent.test.ts:114-162`）。
- B40 工具装载差分：可执行集合优先；pending system 消息的工具字段被视为意图并重算；有 pending system 消息则合并，否则在第一条非 system 消息前插入 `{content:"", timestamp: now}` 的声明消息（4.3；测试 `test/agent.test.ts:164-280`）。
- B41 全局默认 StreamFn：未配置时错误文案精确；prompt/低层循环都支持省略 streamFn（`agent-loop.ts:133,158`；`agent.ts:244`；`stream-fn.ts:15-19`）。
- B42 请求身份：默认 `{attribution: PI_ATTRIBUTION}`；配置的 identity 字段级覆盖；`{attribution:null}` 关闭；传给 streamFn 的 options.identity 是合并结果（`agent-loop.ts:41,415`；测试 `test/agent.test.ts:1203-1228`）。
- B43 `sessionId`、`transport`、`thinkingBudgets`、`maxRetryDelayMs`、`onPayload`、`onResponse`、`onProviderStreamEvent` 原样进入 stream options（`agent.ts:481-488`；测试 `test/agent.test.ts:1155-1201`）。
- B44 `getApiKey` 每次请求调用，取返回值或 `config.apiKey`；provider 用 `config.model.provider`（`agent-loop.ts:409-410`）。
- B45 proxy 协议与兜底：请求路径/头部/字段白名单、SSE `data: ` 解析、toolcall 增量重建、`toolcall_end` 完整元数据合并、非换行结尾的终态、EOF 无终态合成 error、abort 时 reason=aborted（第 6 节；`test/proxy.test.ts:32-118`）。
- B46 自定义消息对 loop 透明：只被 `role === "system"`（工具声明）和 `role === "assistant"`（continue 校验）结构识别，其余只经 `convertToLlm`（`agent-loop.ts:90,148,344-349`；`types.ts:351-374`）。
- B47 合成的工具声明 system 消息内容为空、timestamp=now；`pending` 里已有 system 消息时保留其 content/sections（`agent-loop.ts:362-371`）。
- B48 error/aborted 的 `turn_end.toolResults` 为空数组，`turn_end.message` 是 assistant 消息（`agent-loop.ts:262`）。
- B49 并行 preflight 中 immediate 失败的 `tool_execution_end` 在 preflight 阶段就发出，早于其它工具的 end（`agent-loop.ts:615-627`）。
- B50 事件 sink 是 await 的背压：慢监听器会拖慢循环（`agent-loop.ts:35,126`；`agent.ts:575-622`）。

### 8.10 允许的偏差（必须写进实现说明）

1. 低层 `agentLoop`/`agentLoopContinue`：TS 在内部异常时流悬挂；Kotlin 以异常完成 `Channel`/`Deferred` 是更安全且不与现有测试冲突的选择（现有低层测试不覆盖该路径）。
2. `AgentLoopConfig` 传给 StreamFn 的 options 只含 pi-ai `SimpleStreamOptions` 字段；TS 会把 agent 专用字段一起展开。coding-agent 的 streamFn 只读取 pi-ai 字段，故无影响。
3. `message_update.message` 的浅拷贝/共享策略：Kotlin 若采用不可变消息类型，需在测试里明确订阅者拿到的是快照还是共享对象；建议与 TS 一致（顶层 copy，content 列表共享）。
4. JS `Set` 迭代期间新增 listener 的可见性、以及 listener 抛出后再次调用的行为，Kotlin 可用快照/记录并继续，不要求逐位复刻；相关行为无测试固定。
5. 时钟：`Date.now()` / `performance.now()` 分别映射为 wall clock / `nanoTime`；`durationMs` 只保证相对值范围，不保证与 TS 数值一致（测试也只用区间断言）。
6. `typebox` 运行时校验：Kotlin 用 pi-ai 的 JSON Schema 校验器替代；错误文案格式（`Validation failed for tool ...`）建议保持，但并非所有隐式类型强转都能 1:1（需在 pi-ai 侧明确定义）。注意 `docs/analysis/pi-ai-types.md` 提议校验器返回 `{ok:false, errors}` 结构；pi-agent 的 catch/错误结果语义需要抛异常或错误文本，集成时须约定一个 throwing 包装（或让 agent 侧直接消费结构化错误）。
7. `args` 可变性：Kotlin 必须用可变 JSON 对象承接 hook 的原地修改；若 pi-ai 校验器返回不可变对象，需显式复制成可变结构后再交给 hook/execute。
8. `partialJson` 是 proxy 的内部临时字段，Kotlin 不要污染公开 `ToolCall` 类型。
9. 测试工具 `new Function` 求值（`test/utils/calculate.ts`）在 Kotlin 里改为安全的表达式求值器；这是测试基础设施差异，不涉及运行时行为。

### 8.11 文件级拆分与代码量估计

主代码（`pi-agent/src/main/kotlin/dev/pi/agent/`）：

| 文件 | 内容 | 估计行数 |
|---|---|---|
| `Types.kt` | 枚举/typealias/AgentMessage 与 Custom/StreamFn/hooks/loop 配置/turn 与 prepare 类型/AgentTool 族/AgentContext/AgentState/初始状态/AgentOptions/AgentEvent sealed/proxy 事件类型 | 380-460 |
| `AgentLoop.kt` | 入口与事件流包装、runLoop、declareToolChanges、streamAssistantResponse、串行/并行工具执行、prepare/execute/finalize、截断处理、runToolCall、错误结果与消息构造 | 780-950 |
| `Agent.kt` | 默认 usage/model、Agent 可变状态工厂、PendingMessageQueue、Agent 类（订阅/生命周期/入口/配置装配/事件归约/队列 API） | 520-650 |
| `Proxy.kt` | ProxyStreamOptions/事件类型、HTTP+SSE、partial 重建、错误兜底 | 280-360 |
| `StreamFn.kt` | 全局默认 StreamFn | 25-35 |
| **主代码合计** | | **约 2000-2450** |

测试代码（`pi-agent/src/test/kotlin/dev/pi/agent/`）：

| 文件 | 内容 | 估计行数 |
|---|---|---|
| `AgentLoopTest.kt` | 移植 `agent-loop.test.ts` 全部用例 + 本地 scripted StreamFn 工具 | 900-1100 |
| `AgentTest.kt` | 移植 `agent.test.ts` | 700-850 |
| `AgentE2eTest.kt` | 移植 `e2e.test.ts`，依赖 pi-ai 的 faux provider 测试替身 | 350-450 |
| `ProxyTest.kt` | 移植 `proxy.test.ts`，用 MockWebServer 或自建 `HttpServer` 模拟 SSE | 200-300 |
| `TestSupport.kt` | scripted stream、gating、断言事件序列工具 | 250-350 |
| **测试合计** | | **约 2400-3050** |

pi-ai 前置工作量（不属本文交付，但阻塞 pi-agent）：消息/Model/Usage 类型、EventStream/AssistantMessageEventStream、转录重放助手、工具校验器、身份合并、partial JSON 解析、系统提示渲染，以及 faux provider 测试替身（约 1200-1800 行）。

### 8.12 实施顺序

1. pi-ai 前置（1.3 清单 1-11）先落地，尤其 `EventStream`/`TranscriptContext`/`validateToolArguments`/转录重放。
2. `Types.kt` + 事件/工具类型（可先无实现编译）。
3. `AgentLoop.kt`：先 `streamAssistantResponse` 与 `declareToolChanges`，再 `runLoop` 主体，最后工具执行两策略与 hooks；配 `AgentLoopTest` 前 30 个用例（scripted stream 即可，不需要 faux provider）。
4. `Agent.kt`：状态工厂 → PendingMessageQueue → 生命周期 → prompt/continue → processEvents；配 `AgentTest`。
5. `StreamFn.kt` + `Proxy.kt`；配 proxy 测试。
6. 端到端：pi-ai 的 faux provider 替身 + `AgentE2eTest`；最后对照第 9 节验收清单逐项签字。

## 9. 测试清单与验收标准

### 9.1 总览

| 文件 | 行数 | 用例声明数 | 执行数（含 it.each 展开） | 覆盖 |
|---|---|---|---|---|
| `test/agent-loop.test.ts` | 2234 | 36 | 38 | 低层循环、事件顺序、工具执行、finishTurn/prepareRequest/prepareNextTurn、终止规则、runToolCall |
| `test/agent.test.ts` | 1229 | 36 | 41 | Agent 状态/订阅/生命周期/队列/continue 分支/hook 转发/身份 |
| `test/e2e.test.ts` | 416 | 10 | 10 | 用 faux provider 跑 Agent 端到端（文本、工具、abort、多轮、thinking、continue） |
| `test/proxy.test.ts` | 118 | 3 | 3 | streamProxy 的增量重建与 EOF 兜底 |
| `test/utils/*` | 89 | — | — | calculate/get_current_time 工具、waitForTick |

共 92 次测试执行。下表按文件列出每个用例与它固定的行为；Kotlin 测试类建议一一对应。

### 9.2 `test/agent-loop.test.ts`

| 行 | 用例 | 用途/关键断言 |
|---|---|---|
| 80 | uses the configured default when a legacy caller omits streamFn | 省略 streamFn 时用 `setDefaultStreamFn` 注册的函数；调用 1 次 |
| 114 | should emit events with AgentMessage types | prompt 后消息为 [user, assistant]；事件包含 start/turn_start/message_start/message_end/turn_end/agent_end |
| 160 | should build provider context exclusively from transcript messages | streamFn 收到的 context 只有 `messages` 键；首条 system 消息对象与 prompt 相同（引用） |
| 199 | should handle custom message types via convertToLlm | context 里的 notification 自定义消息被 convertToLlm 过滤，converted 只剩 user |
| 253 | should apply transformContext before convertToLlm | transformContext 先裁剪为 2 条，convertToLlm 收到裁剪后的 2 条 |
| 305 | should handle tool calls and results | tool 执行、toolStart/toolEnd 事件、afterToolCall 可改 usage 并写入 toolResult |
| 402 | records how long execute() took on the tool result, excluding hooks | 执行 30ms、beforeToolCall 100ms 时 durationMs 在 [25,100)；blocked 结果为错误且无 durationMs |
| 456 | should not execute tool calls from a length-truncated assistant message | stopReason=length 时工具不执行，end 事件错误文本含 "output token limit"，循环继续（callIndex=2） |
| 528 | should execute mutated beforeToolCall args without revalidation | hook 把 args.value 改为 123 后 execute 收到 123 |
| 589 | should prepare tool arguments for validation | prepareArguments 把 oldText/newText 转为 edits 数组，校验并执行 |
| 668 | should emit tool_execution_end in completion order but persist tool results in source order | end 顺序 ["tool-2","tool-1"]；toolResult 消息/turn_end.toolResults 顺序 ["tool-1","tool-2"]；并发被观测到 |
| 762 | should inject queued messages after all tool calls complete | 两个工具全部执行后 steering 才注入；事件 order 中两条 tool result 先于 interrupt；第二次请求包含 interrupt |
| 867 | should force sequential execution when a tool has executionMode=sequential even with default parallel config | 并行未被观测到；toolResult 顺序固定 |
| 949 | should force sequential execution when one of multiple tools has executionMode=sequential | executionOrder[0]=="slow:a"，fast 在 slow settle 后才执行 |
| 1035 | should allow parallel execution when all tools have executionMode=parallel | second 在 first settle 前开始 |
| 1108 | runs finishTurn after tool-result messages and before turn_end | 事件尾部顺序 [message_end:toolResult, finishTurn, turn_end]；finishTurn 里 context 末条为 toolResult |
| 1162 | runs finishTurn for a %s assistant before turn_end without changing the hard exit（error/aborted 各 1） | 顺序 ["finishTurn","turn_end"]；providerCalls=1；steeringPolls=1；followUpPolls=0；决策 continue 被忽略 |
| 1217 | action:end skips queue polling and next-turn preparation | providerCalls=1；steeringPolls=1；followUpPolls=0；prepareNextTurnCalls=0 |
| 1273 | makes exactly one context-only request when no natural request satisfies continuation | finishTurn 第一次 continue、第二次 undefined；providerCalls=2、finishCalls=2 |
| 1307 | lets a natural tool-result request satisfy continuation | continue 决策由工具结果自然满足，providerCalls=2、finishCalls=2 |
| 1347 | lets a natural steering/follow-up request satisfy continuation（各 1） | 第二次请求包含对应队列消息；providerCalls=2、finishCalls=2 |
| 1404 | prepares the initial request after pending messages and can replace request state | prepareRequest 在 steering 事件之后调用；返回的 context/model/thinkingLevel 生效（模型替换、reasoning="high"） |
| 1458 | does not poll steering after prepareRequest | 请求包含 late steering 为 [false,true]；prepareRequest 2 次；steeringPolls=3 |
| 1503 | should use prepareNextTurn snapshot before continuing | prepareNextTurn 1 次；第二次请求包含其返回的 "updated guidance" system 消息 |
| 1580 | picks up steering queued during prepareNextTurn before the next request | 第二次请求包含 late steering |
| 1633 | action:end receives finalized turn context and stops before queue polling | 精确事件序列见 3.2(c)；finishTurn 收到 toolResults 与 context 角色 ["system","user","assistant","toolResult"]；返回消息角色同 |
| 1732 | should stop after a tool batch when every tool result sets terminate=true | llmCalls=1；消息 [system,user,assistant,toolResult]；turn_end 只 1 次 |
| 1783 | should stop after a blocked tool call when beforeToolCall sets terminate=true | 工具未执行；错误结果文本 "Blocked by policy"；llmCalls=1 |
| 1841 | should continue after a mixed batch with one terminating blocked call | 只执行 second；llmCalls=2（非全部 terminate 不停止） |
| 1899 | should continue after parallel tool calls when not all tool results terminate | 第二次请求发生；消息 [system,user,assistant,toolResult,toolResult,assistant] |
| 1964 | should allow afterToolCall to mark a tool batch as terminating | afterToolCall 返回 terminate=true 后 llmCalls=1 |
| 2013 | should throw when context has no messages | `agentLoopContinue` 同步抛 "Cannot continue: no messages in context" |
| 2031 | should continue from existing context without emitting user message events | 返回消息只有 1 条 assistant；message_end 只有 assistant |
| 2072 | should allow custom message types as last message (caller responsibility) | 自定义角色由 convertToLlm 转 user，不抛错；返回 1 条 assistant |
| 2167 | runToolCall validates, runs the hooks, and reports failures as error outcomes | 成功/校验失败/blocked/unknown/工具内 isError 五类；hookCalls 顺序 ["before a","after a","before c","before e","after e"]；update 只 1 次 |
| 2214 | runToolCall afterToolCall replace structured content / drop when content replaced | structuredContent 结果序列 [undefined, {value:"replaced"}, {value:"both"}, {value:"original"}] |

### 9.3 `test/agent.test.ts`

| 行 | 用例 | 用途/关键断言 |
|---|---|---|
| 93 | uses the configured default when a legacy caller omits streamFn | 构造 `Agent({})` 时用全局默认；prompt 后 calls=1 |
| 114 | should create an agent instance with default state | model 已定义、thinkingLevel="off"、tools=[]、messages=[]、isStreaming=false、streamingMessage=undefined、pendingToolCalls=空集、errorMessage=undefined |
| 128 | should create an agent instance with custom initial state | messages=首条 system（content=systemPrompt, timestamp=0）；model/thinkingLevel 生效 |
| 144 | converts initial prompt and tools into transcript state | 首条 system 含 content 与 toolsAdded 名称 |
| 164 | declares tool loadout changes to the model before the next request | 三次 prompt 的请求 system 工具声明序列为 ["+first","-"]、["+first","-","+second","-first"]、重复；合成消息字段与 timestamp；声明无 execute |
| 212 | merges tool changes into a pending system message | pending system（sections）被合并 toolsAdded；请求里 system 消息共 2 条 |
| 247 | rewrites pending tool declarations to match the executable set | pending 声明 second/-first 被重算为空；最终重放工具为 ["first"] |
| 282 | restores the transcript baseline when reset | reset 后 messages 只剩一条重放的 system（content + toolsAdded） |
| 309 | should subscribe to events | subscribe 不发初始事件；状态 mutator 不发事件；unsubscribe 生效 |
| 331 | emits full lifecycle events for thrown run failures | 事件序列见 3.2(f)；最后 assistant stopReason=error、errorMessage="provider exploded"；state.errorMessage 同步 |
| 362 | should await async subscribers before prompt resolves | agent_end 监听器挂起时 prompt 不 resolve、isStreaming=true；释放后 resolve、isStreaming=false |
| 400 | waitForIdle should wait for async subscribers | 同上，waitForIdle 也等待 |
| 435 | should pass the active abort signal to subscribers | agent_start 监听器拿到的 signal 未 abort；agent.abort() 后变 abort |
| 473 | should ignore tool updates after the tool execution settles | 工具 settle 后调用 onUpdate 不产生事件、无 unhandledRejection |
| 538 | should ignore a settled parallel tool update while another tool is still running | 已 settle 工具的更新被忽略；全程 tool_execution_update 数量为 0 |
| 614 | should update state with mutators | model/thinkingLevel 可改；tools/messages 赋值复制顶层数组；messages.push 生效；messages=[] 清空 |
| 649 | should support steering message queue | steer 后消息不在 state.messages |
| 659 | should support follow-up message queue | followUp 后消息不在 state.messages |
| 669 | should handle abort controller | 空闲时 abort 不抛错 |
| 676 | should reject reset while processing without corrupting the transcript | reset 抛精确文案；state 不变；run 完成后 messages=[user,assistant] |
| 710 | should throw when prompt() called while streaming | 第二次 prompt 抛精确文案；abort 后原 prompt 结束 |
| 750 | should throw when continue() called while streaming | 抛精确文案 |
| 786 | continue() should process queued follow-up messages after an assistant turn | assistant 尾 + followUp 队列 → continue 注入并产生新 assistant |
| 824 | continue() keeps $mode steering semantics for assistant-tail fallback（2 模式） | one-at-a-time 需 2 次请求，all 只需 1 次；第一批含 Steering 1，etc. |
| 860 | keeps legacy prepareNextTurn signal callback behavior | 旧签名收到 AbortSignal 实例；requestCount=2 |
| 901 | forwards finishTurn through AgentOptions with the active abort signal | finishTurn 收到 signal；context 角色 ["system","user","assistant","toolResult"]；requestCount=1（end） |
| 945 | rejects a queued continuation from $name context without draining queues（empty/system-only 各 1） | 抛 "No messages to continue from"；peek 仍依次返回 steering、followUp |
| 961 | defers follow-up input on the first continuation request from $name tail（user/toolResult 各 1） | 第一次请求不含 follow-up，第二次包含 |
| 1007 | polls $mode steering at continuation startup（2 模式） | 第一批含 first；one-at-a-time 第二批含 second；all 第一批即含 second |
| 1043 | keeps steering ahead of follow-up from a non-assistant continuation tail | 第一次请求含 steering、不含 follow-up；第二次含 follow-up |
| 1071 | keeps queues on a %s response even when finishTurn requests continuation（error/aborted 各 1） | 队列不被消费；peek 顺序 steering→followUp |
| 1109 | keeps queues when finishTurn ends the run | 同上，end 不消费队列 |
| 1136 | previews the next selected queued messages without consuming them | peek 幂等；steering 清空后 peek 返回 followUp |
| 1155 | forwards provider stream event observers through AgentOptions | onProviderStreamEvent 收到的数据 [{request_cost:0.01}] |
| 1177 | forwards sessionId to streamFunction options | 默认值传入、setter 修改后生效 |
| 1203 | attributes requests to pi by default and merges a configured identity over it | 三次请求的 identity 分别为 `{attribution:PI_ATTRIBUTION}`、`{attribution:acme,userAgent:"short"}`、`{attribution:PI_ATTRIBUTION,userAgent:"short"}` |

### 9.4 `test/e2e.test.ts`

| 行 | 用例 | 用途/关键断言 |
|---|---|---|
| 187 | handles a basic text prompt | faux 返回 "4"；消息 [system,user,assistant]；isStreaming=false |
| 193 | executes tools and tracks pending tool calls | 工具消息内容含 123*456=56088；pendingToolCalls 在事件中为 {start:["calc-1"]}、{end:[]}，最终为空 |
| 208 | handles abort during streaming | tokensPerSecond=20、30ms 后 abort；最终 assistant stopReason=aborted、errorMessage 存在；state.errorMessage 同步 |
| 221 | emits lifecycle updates while streaming | 事件包含 update，顺序 start<message_start<message_end<agent_end |
| 227 | maintains context across multiple turns | 两轮 prompt 后消息 5 条；第二轮回答含 Alice |
| 243 | preserves thinking content blocks | 最终 assistant content 精确等于 [{thinking:"step by step"},{text:"4"}] |
| 270 | continue throws when no messages in context | 抛 "No messages to continue from" |
| 283 | continue throws when last message is assistant | 抛 "Cannot continue from message role: assistant" |
| 318 | continue from user message | 只发 1 次请求；消息 [user,assistant]；回答含 HELLO WORLD |
| 352 | continue from tool result | 消息 ≥4；最后为 assistant 且含 8 |

### 9.5 `test/proxy.test.ts`

| 行 | 用例 | 用途/关键断言 |
|---|---|---|
| 33 | preserves tool-call metadata received only on toolcall_end | namespace 只在 toolcall_end 出现，事件与最终消息都保留 |
| 78 | processes terminal metadata when the event is not newline-terminated | 最后一行无 `\n` 仍被处理：事件 ["start","done"]、providerThinkingLevel="high" |
| 99 | emits an error instead of hanging when the stream ends without a terminal event | 事件 ["start","error"]；stopReason=error；errorMessage 含 "Connection closed by proxy server" |

### 9.6 测试基础设施移植要求

1. **Scripted StreamFn**：低层测试不依赖 faux provider，直接构造流并按脚本 push 事件。Kotlin 需要一个 `ScriptedStream` 测试工具：
   - 返回 `AssistantMessageEventStream` 写端；
   - 可在 `runTest` 中用 `launch`/`CompletableDeferred` 控制事件时序；
   - 捕获 `(model, context, options)` 供断言；
   - 支持“只发 done（不发 start）”“发 error”“先 start 后延迟 delta”等形态（现有测试大量使用只发 done 的模式，如 `test/agent-loop.test.ts:127-134`）。
2. **门控**：并行顺序/并发用例依赖 `Deferred` 门控（TS 用 Promise+setTimeout）；Kotlin 用 `CompletableDeferred`，不要用 sleep 以免 flaky。只有 e2e 的 abort 用真实时间（30ms），Kotlin 可用 `withTimeout`/真实 `delay`。
3. **最终结果读取**：测试同时消费事件流与 `result()`；Kotlin 的 `Flow` 单次收集 + `Deferred.await()`，注意先 `collect` 再 `await` 或并发启动收集，避免死锁（TS 的 EventStream 支持先 iterator 后 result）。
4. **断言工具**：`assertEventTypes(events, listOf("agent_start", ...))`、`assertMessageRoles(...)`、`assertToolResultOrder(...)`；事件 payload 类型用 sealed `when` 展开。
5. **Faux provider（e2e）**：pi-ai 测试模块需要等价实现：
   - `registerFauxProvider({models?, tokensPerSecond?, tokenSize?})` → 返回注册对象，含 `setResponses([...])`、`getModel()`、`state.callCount`、`unregister()`（`ai/providers/faux.ts:120-180` 区域）；
   - `fauxAssistantMessage(text|blocks, {stopReason?, ...})`、`fauxText`、`fauxThinking`、`fauxToolCall(name,args,{id?})`（`ai/providers/faux.ts:70-120`）；
   - 响应可以是函数 `(context, options, state, model) => AssistantMessage`，用于多轮上下文断言（`test/e2e.test.ts:231-238`）；
   - 流式切片按 `tokensPerSecond`/`tokenSize` 控制；`abort` 时产出 aborted 消息（`test/e2e.test.ts:208-219` 依赖）；
   - 每个用例后 unregister，避免全局注册表污染（`test/e2e.test.ts:18-37`）。
6. **测试工具移植**：`calculate.ts`（用表达式求值器替代 `new Function`）、`get-current-time.ts`、`wait-for-tick.ts`。

### 9.7 验收标准

A. 功能验收（必须全部满足）：

1. 移植后的 92 次测试执行全部通过；测试名、分组与 TS 一一对应（可在 Kotlin 测试里用 DisplayName 保留英文原名，便于对照）。
2. 五条精确事件序列用硬断言（逐元素比较 type 列表），不允许只断言 contains：3.2(a)(b)(c)(d)(e/f)。
3. 所有固定字符串逐字一致（见 10.1）；并发/状态错误路径抛 `IllegalStateException`，文案与 TS 逐字一致。
4. 并行执行的顺序断言用门控而非时间：end 完成顺序（[tool-2,tool-1]）、消息源顺序（[tool-1,tool-2]）、turn_end.toolResults 源顺序。
5. 工具参数可变性：beforeToolCall 原地改 args → execute 收到改后值（B13）；afterToolCall 的 structuredContent 丢弃规则（B16）。
6. abort：Agent.abort() 后 prompt 正常返回、最终消息 stopReason=aborted、state.errorMessage 非空；空闲 abort 不抛错；工具/订阅者仍收到同一 signal。
7. 生命周期：isStreaming 在 agent_end 监听器结算后才转 false；waitForIdle 等价；prompt/continue/reset 的并发保护文案精确。
8. 队列：三种 continue 尾巴分支、两种 QueueMode、peek 不消费、end/error/aborted 不消费队列。
9. 组装语义：prepareRequest/prepareNextTurn/finishTurn 的调用时机、调用计数与返回值生效范围（B17-B20）；工具声明差分（B40）。
10. 事件 payload 身份：`message_end.message` 与 `state.messages.last()` 是同一实例；`turn_end.toolResults` 与 state 中 toolResult 同一实例（coding-agent 依赖，`ca/core/agent-session.ts:1218-1287`）。
11. 消息可变性：toolResult 的 `nestedCalls`/`usage` 能在 `message_start` 监听器里被修改并保留到 state（coding-agent 用法，`ca/core/agent-session.ts:1107-1122`）；assistant 消息可被整体就地替换字段（`1271-1287`）。
12. proxy 三个用例通过；SSE 行缓冲、无换行终态、EOF 兜底行为一致。

B. 工程验收（Kotlin 特有）：

1. `:pi-agent:test` 无协程泄漏（`runTest` 结束检查子协程）、无未捕获异常（配置 `CoroutineExceptionHandler` 或测试断言）。
2. 并行工具测试在不同调度器（单线程 `limitedParallelism(1)` 与默认测试调度器）下都稳定；不依赖真实 sleep 的顺序断言。
3. `Agent` 的公开 API 与 coding-agent 依赖面（第 7 节）对齐，编译 `:pi-coding-agent` 的移植分支时无需改动 pi-agent 的导出。
4. 所有 `B01-B50` 在测试代码中有对应引用（测试注释/名称标注编号），便于回归审计。

## 10. 附录

### 10.1 固定字符串与常量

错误/提示文案（逐字）：

| 文案 | 来源 |
|---|---|
| `No default stream function configured. Pass streamFn explicitly or call setDefaultStreamFn().` | `stream-fn.ts:17` |
| `Cannot continue: no messages in context` | `agent-loop.ts:87,145`；`test/agent-loop.test.ts:2028` 断言 |
| `Cannot continue from message role: assistant` | `agent-loop.ts:91,149` |
| `Tool ${toolCall.name} not found` | `agent-loop.ts:730` |
| `Operation aborted` | `agent-loop.ts:633,751,770` |
| `Tool execution was blocked` | `agent-loop.ts:756` |
| `Tool call "${toolCall.name}" was not executed: the response hit the output token limit, so its arguments may be truncated. Re-issue the tool call with complete arguments.` | `agent-loop.ts:503` |
| `Agent is already processing. Wait for completion before resetting.` | `agent.ts:366` |
| `Agent is already processing a prompt. Use steer() or followUp() to queue messages, or wait for completion.` | `agent.ts:384-386` |
| `Agent is already processing. Wait for completion before continuing.` | `agent.ts:395` |
| `No messages to continue from` | `agent.ts:400` |
| `Cannot continue from message role: assistant` | `agent.ts:416` |
| `Agent is already processing.` | `agent.ts:519` |
| `Agent listener invoked outside active run` | `agent.ts:617` |
| `Request aborted by user` | `proxy.ts:138,196,209` |
| `Proxy error: ${status} ${statusText}` / `Proxy error: ${error}` | `proxy.ts:162-171` |
| `Connection closed by proxy server before the response completed` | `proxy.ts:224` |
| `Received text_delta for non-text content` 等 5 条 proxy 类型错误 | `proxy.ts:280,294,312,326,352` |

常量：

| 常量 | 值 | 来源 |
|---|---|---|
| `DEFAULT_AGENT_IDENTITY` | `{attribution: PI_ATTRIBUTION}`；`PI_ATTRIBUTION = {name:"pi", url:"https://pi.dev", categories:["cli-agent"]}` | `agent-loop.ts:41`；`ai/utils/identity.ts:24` |
| `EMPTY_USAGE` | `{input:0,output:0,cacheRead:0,cacheWrite:0,totalTokens:0,cost:{全 0}}` | `agent.ts:49-56` |
| `DEFAULT_MODEL` | `{id:"unknown",name:"unknown",api:"unknown",provider:"unknown",baseUrl:"",reasoning:false,input:[],cost:{0},contextWindow:0,maxTokens:0}` | `agent.ts:58-69` |
| 默认队列模式 | `"one-at-a-time"`（steering 与 follow-up） | `agent.ts:255-256` |
| 默认工具执行 | `"parallel"` | `agent.ts:261` |
| 默认 transport | `"auto"` | `agent.ts:259` |
| 默认 thinkingLevel | `"off"` | `agent.ts:94` |
| 工具声明消息 | `{role:"system", content:"", timestamp: Date.now()}` | `agent-loop.ts:368` |
| 错误工具结果 | `{content:[{type:"text",text:message}], details:{}}` | `agent-loop.ts:922-927` |

### 10.2 证据索引（按主题）

| 主题 | 主要证据 |
|---|---|
| 事件定义 | `types.ts:509-539` |
| 事件序列与 loop 控制流 | `agent-loop.ts:111-330` |
| 工具声明差分 | `agent-loop.ts:332-384`；`ai/utils/transcript.ts:58-167` |
| assistant 流式映射 | `agent-loop.ts:386-479`；`ai/types.ts:796-828`；`ai/utils/event-stream.ts:91-130` |
| 工具执行 | `agent-loop.ts:481-959` |
| finishTurn/prepareRequest/prepareNextTurn | `types.ts:146-191,256-306`；`agent-loop.ts:194-248,289-323` |
| Agent 生命周期 | `agent.ts:83-112,149-186,238-623` |
| 队列与 continue | `agent.ts:149-180,289-342,392-420,476-515` |
| proxy 协议 | `proxy.ts:20-70,107-393` |
| 默认 StreamFn | `stream-fn.ts:1-20`；`agent.ts:244`；`agent-loop.ts:133,158` |
| pi-ai 消息/模型类型 | `ai/types.ts:420-480,542-650,760-828,830-904` |
| Transcript/身份/校验 | `ai/utils/transcript.ts`、`ai/utils/identity.ts`、`ai/utils/validation.ts:295-353` |
| coding-agent 依赖 | `ca/core/sdk.ts:403-445`；`ca/core/agent-session.ts:19-33,512-520,645-760,788-944,1104-1287,1884-1893,2280-2380` |
| 测试 | `test/agent-loop.test.ts`、`test/agent.test.ts`、`test/e2e.test.ts`、`test/proxy.test.ts` |




