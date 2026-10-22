# pi-ai 类型模型与事件协议（Kotlin 建模基线）

版本基线：`@earendil-works/pi-ai` 1.3.0（源码 `/home/whywood/.codex/private-references/pi/packages/ai`，版本号见 `src/utils/version.ts:2`）。
行号均为相对 `packages/ai` 的 `文件:行`。

本文目标：实现者只看本文即可写出 Kotlin 数据模型、序列化器、事件流和验收测试，不需要再读 TypeScript。

---

## 0. 建模总原则

1. 两条命名域，必须分开建模：
   - 进程内/会话 JSON：全部 camelCase，字段名与文中 TypeScript 类型完全一致（`AssistantMessage`、`ToolCall`、`Usage` 等，见 `src/types.ts:414-671`）。pi 的会话持久化就是这个形状，Kotlin 不能改字段名，否则无法读写 pi 已有会话。
   - 线上 wire：各 provider 原生协议，多为 snake_case（OpenAI `prompt_tokens`、Anthropic `input_tokens`、Bedrock `inputTokens`、Mistral `prompt_tokens` 等）。wire DTO 与内存类型分离，转换代码按 API 实现分别写。
   - 例外：`pi-messages` 自定义协议的事件 JSON 是 camelCase（`src/api/pi-messages.ts:55-96`）。
2. 联合类型全部用 `sealed interface/class`：`Message`（4 变体）、`AssistantMessageEvent`（12 变体）、`Content`（4 变体）、`Credential`（2 变体）、`ClassifierAnswer`（3 变体）、`AuthEvent`（4 变体）。
3. 可空语义：TypeScript 的 `?:` 表示"可以缺失"，不是"值为 null"。Kotlin 用 nullable + `encodeDefaults = false`；不要用 `null` 表示缺省，否则写回 JSON 会多出 `"field": null` 污染 pi 会话。
4. 时间：所有 `timestamp` 是 Unix 毫秒 Long；`durationMs` 是毫秒 Long。
5. `arguments`/`details`/`state` 是任意 JSON：Kotlin 用 `kotlinx.serialization.json.JsonObject`，不要映射成固定数据类。
6. 模型目录 JSON 会随 pi 版本增加字段（例如 radius 的 `lab`/`enabled`/`providers`），Kotlin 反序列化必须 `ignoreUnknownKeys = true`；服务端目录甚至可能带 `providers` 子数组。
7. 枚举的线上落值必须是字符串常量，不用 ordinal；未知值要能降级（stop reason 等有 default 分支）。

---

## 1. 标量与枚举

### 1.1 API / Provider / 模型类型

| 类型 | 取值 | 证据 |
|---|---|---|
| `KnownApi` | `openai-completions`, `mistral-conversations`, `openai-responses`, `azure-openai-responses`, `openai-codex-responses`, `anthropic-messages`, `bedrock-converse-stream`, `google-generative-ai`, `google-vertex`, `pi-messages` | `src/types.ts:62-72` |
| `Api` | `KnownApi \| (string & {})`（允许自定义字符串） | `src/types.ts:74` |
| `KnownImageApi` | `openrouter-images` | `src/types.ts:76` |
| `KnownClassifierApi` | `typesafe-system-one`, `cloudflare-workers-ai-system-one`, `llama-cpp-classify`, `openai-decisions` | `src/types.ts:80-84` |
| `KnownProvider` | 42 个：`amazon-bedrock`, `ant-ling`, `anthropic`, `google`, `google-vertex`, `openai`, `azure`, `openai-codex`, `radius`, `typesafe`, `nvidia`, `deepseek`, `github-copilot`, `xai`, `groq`, `cerebras`, `openrouter`, `vercel-ai-gateway`, `zai`, `zai-coding-cn`, `mistral`, `minimax`, `minimax-cn`, `moonshotai`, `moonshotai-cn`, `huggingface`, `fireworks`, `together`, `baseten`, `opencode`, `opencode-go`, `kimi-coding`, `meta`, `cloudflare-workers-ai`, `cloudflare-ai-gateway`, `qwen-token-plan`, `qwen-token-plan-cn`, `qwen-token-plan-individual`, `xiaomi`, `xiaomi-token-plan-cn`, `xiaomi-token-plan-ams`, `xiaomi-token-plan-sgp` | `src/types.ts:88-130` |
| `ProviderId` | `KnownProvider \| string` | `src/types.ts:131` |
| `ModelType` | `chat` / `image` / `classifier` | `src/types.ts:894-901` |
| `AnyModel` | `Model \| ImageModel \| ClassifierModel` | `src/types.ts:904` |

模型类型判定：`type` 字段缺省视为 `chat`（`src/utils/model-operations.ts:18-25`），所以 `Model` 的 `type?: "chat"` 是可选的（`src/types.ts:845-851`），而 `ImageModel.type: "image"`、`ClassifierModel.type: "classifier"` 必填。

### 1.2 推理等级与缓存

| 类型 | 取值 | 证据 |
|---|---|---|
| `ThinkingLevel` | `minimal`, `low`, `medium`, `high`, `xhigh`, `max` | `src/providers/model-schema.ts:3` |
| `ModelThinkingLevel` | `off` + 上述 6 个 | `src/providers/model-schema.ts:4` |
| `ThinkingLevelMap` | `Partial<Record<ModelThinkingLevel, string \| null>>`；`null` = 该等级不支持；未出现的键 = 使用 provider 默认（高等级时 xhigh/max 视为不支持） | `src/providers/model-schema.ts:14-18`，语义注释 `src/types.ts:853-857` |
| `ModelInputModality` | `text` / `image` | `src/providers/model-schema.ts:5` |
| `CacheRetention` | `none` / `short` / `long`（默认 `short`） | `src/providers/model-schema.ts:6`，`src/types.ts:245-249` |
| `ModelPromptCache` | `{ short?: 秒数, long?: 秒数 }`，正数 | `src/providers/model-schema.ts:7,20-26` |
| `Transport` | `sse` / `websocket` / `websocket-cached` / `auto`（仅 codex 用） | `src/types.ts:145` |
| `GrammarFormat` | `openai_lark` / `openai_regex` | `src/types.ts:739` |
| `SessionAffinityFormat` | `openai` / `openai-nosession` / `openrouter` | `src/providers/compat-schema.ts:4-6` |
| `ThinkingTokenBudgetField` | `thinking_token_budget` / `thinking_budget` / `thinking_budget_tokens` | `src/providers/compat-schema.ts:12-17` |
| `ToolChoice`（simple） | `auto` / `none` | `src/types.ts:133` |
| `AuthType` | `api_key` / `oauth` | `src/auth/types.ts:117` |

### 1.3 stop reason

`StopReason = pending | stop | length | toolUse | error | aborted | deferred`（`src/types.ts:480`）。
图片：`ImagesStopReason = stop | error | aborted`（`src/types.ts:659`）。分类器：`ClassifierStopReason` 同图片（`src/types.ts:722`）。
`done` 事件的 reason 只允许 `stop|length|toolUse|deferred`，`error` 事件只允许 `aborted|error`（`src/types.ts:823-828`）。
`pending` 只在流进行中存在，终止前必须替换，否则各 API 抛 "stream ended without ..."（如 `src/api/anthropic-messages.ts:898-900`）。

### 1.4 JSON 基础类型

`JsonValue = null | boolean | number | string | JsonValue[] | JsonObject`，`JsonObject = map<string, JsonValue>`（`src/types.ts:482-483`）。Kotlin 用 `JsonElement`/`JsonObject`。

---

## 2. 内容块（Content）

联合：`TextContent | ThinkingContent | ImageContent | ToolCall`。Kotlin 用 `@Serializable @JsonClassDiscriminator("type")` 的多态，判别键是 `type`。

### 2.1 TextContent

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | `"text"` | 是 | 判别键 |
| `text` | String | 是 | 文本 |
| `textSignature` | String? | 否 | OpenAI Responses 的条目元数据：旧版 message id 字符串，或 `TextSignatureV1` 的 JSON 字符串（`src/types.ts:420-424`） |

`TextSignatureV1 = { v: 1; id: string; phase?: "commentary" | "final_answer" }`（`src/types.ts:414-418`），编码/解析见 `src/api/openai-responses-shared.ts:54-80`。Responses 回放时用它恢复 `message.id` 与 `phase`（`src/api/openai-responses-shared.ts:339-359`）。

### 2.2 ThinkingContent

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | `"thinking"` | 是 | |
| `thinking` | String | 是 | 思考文本；redacted 时固定占位 `[Reasoning redacted]` |
| `thinkingSignature` | String? | 否 | provider 私有回放数据：Anthropic signature、OpenAI reasoning item JSON、Google thoughtSignature、Bedrock base64 加密块（`src/types.ts:426-434`） |
| `redacted` | Boolean? | 否 | 安全过滤导致的脱敏块，密文在 `thinkingSignature`，必须原样回传 |

关键不变量：
- 跨模型/跨 provider 回放时 thinking 会被丢弃，只有同 provider 且同 model 才保留签名回放（`src/api/transform-messages.ts:127-138`）。
- Anthropic 的 signature 是流式 `signature_delta` 累加（`src/api/anthropic-messages.ts:808-815`）。
- Google 的 `thoughtSignature` 只在同 provider+同 model 且是合法 base64 时才回放（`src/api/google-shared.ts:148-162`）。
- OpenAI Responses 的 reasoning item 用 JSON 字符串整体存进 `thinkingSignature`（`src/api/openai-responses-shared.ts:766-771`）。

### 2.3 ImageContent

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | `"image"` | 是 | |
| `data` | String | 是 | base64（不带 data URL 前缀） |
| `mimeType` | String | 是 | `image/jpeg`、`image/png` 等 |

wire 上转换为 `data:<mimeType>;base64,<data>` 或 provider 原生 inline 结构；见第 10 节。

### 2.4 ToolCall

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | `"toolCall"` | 是 | |
| `id` | String | 是 | 可能被 provider 格式约束；Responses 用 `callId\|itemId` 复合 id |
| `name` | String | 是 | 工具名 |
| `arguments` | JsonObject | 是 | 解析后的参数；非法 JSON 时为 `{}` |
| `thoughtSignature` | String? | 否 | Google 专用 thought 上下文签名 |
| `namespace` | String? | 否 | OpenAI Responses 动态/命名空间工具 |
| `argumentsError` | String? | 否 | 完整参数文本不是合法 JSON 对象时，`arguments={}` 且此字段描述错误并带上原文（`src/types.ts:442-455`，实现 `src/utils/json-parse.ts:106-120`） |

注意：`arguments` 是"参数对象"，不是 JSON 字符串。线上协议多数是字符串增量，Kotlin 需按 API 维护 partial buffer 再解析。

---

## 3. 消息类型（Message）

`Message = SystemMessage | UserMessage | AssistantMessage | ToolResultMessage`（`src/types.ts:650`）。判别键 `role`，值分别为 `system|user|assistant|toolResult`。

### 3.1 SystemMessage

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `role` | `"system"` | 是 | |
| `content` | `String \| TextContent[]` | 是 | 首条 = 基础系统提示；后续 = 追加指令 |
| `sections` | `Map<String, String?>` | 否 | 命名提示段；`null` 表示删除该段；渲染在 `content` 之后（`src/types.ts:552-568`） |
| `toolsAdded` | `Tool[]` | 否 | 从此刻起可用的完整工具定义 |
| `toolsRemoved` | `{name}[]` | 否 | 从此刻起移除的工具 |
| `timestamp` | Long | 是 | 毫秒；合成首条消息固定为 0（`src/utils/transcript.ts:17-22`） |

回放模型：按顺序 replay 所有 system message 得到当前 prompt + 工具集合（`src/utils/transcript.ts:58-96`）。只支持单条系统消息的 API 会先 `collapseSystemMessages` 折叠（`src/utils/transcript.ts:104-120`）。Kotlin 必须实现这套 replay，否则 mid-conversation 系统消息/工具变更的会话无法正确发送。

### 3.2 UserMessage

| 字段 | 类型 | 必填 |
|---|---|---|
| `role` | `"user"` | 是 |
| `content` | `String \| (TextContent \| ImageContent)[]` | 是 |
| `timestamp` | Long | 是 |

### 3.3 AssistantMessage

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `role` | `"assistant"` | 是 | |
| `content` | `(TextContent \| ThinkingContent \| ToolCall)[]` | 是 | 可混合、有序 |
| `api` | `Api` | 是 | 产生该响应的 API |
| `provider` | `ProviderId` | 是 | |
| `model` | String | 是 | 请求的 model id |
| `responseModel` | String? | 否 | provider 实际返回的模型（与请求不同才设） |
| `responseId` | String? | 否 | provider 响应 id |
| `providerThinkingLevel` | String? | 否 | 实际发送给 provider 的 effort 原文 |
| `thinkingLevel` | `ModelThinkingLevel`? | 否 | agent loop 请求的等级 |
| `diagnostics` | `AssistantMessageDiagnostic[]` | 否 | 脱敏诊断 |
| `usage` | `Usage` | 是 | 见 3.5 |
| `stopReason` | `StopReason` | 是 | |
| `deferred` | `DeferredHandle` | 否 | 异步响应句柄 |
| `errorMessage` | String? | 否 | stopReason=error/aborted 时的错误 |
| `rawStopReason` | String? | 否 | provider 原始 stop reason |
| `endTurn` | Boolean? | 否 | provider 是否明确结束回合 |
| `timestamp` | Long | 是 | 请求开始时间（毫秒） |
| `durationMs` | Long? | 否 | 由事件流在终止消息上补齐（`src/utils/event-stream.ts:126-129`） |

`DeferredHandle`：`{ provider, modelId, api, id, expiresAt?, pollAfterMs?, data? }`（`src/types.ts:530-540`）。

### 3.4 ToolResultMessage

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `role` | `"toolResult"` | 是 | |
| `toolCallId` | String | 是 | 对应 ToolCall.id |
| `toolName` | String | 是 | |
| `content` | `(TextContent \| ImageContent)[]` | 是 | 支持图片 |
| `details` | JsonValue? | 否 | 工具自定义结构化结果 |
| `usage` | `Usage`? | 否 | 工具自身消耗，不参与主上下文 |
| `nestedCalls` | `NestedToolCalls`? | 否 | 工具内部再调工具的记录，不发给模型 |
| `isError` | Boolean | 是 | |
| `timestamp` | Long | 是 | |
| `durationMs` | Long? | 否 | |

`NestedToolCalls = { calls: NestedToolCallRecord[]; complete: boolean }`；record 字段 `id,name,arguments?,argumentsBytes?,status("ok"|"error"|"unfinished"),durationMs?,error?`（`src/types.ts:609-629`）。

### 3.5 Usage / Cost

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `input` | Long | 是 | 非缓存输入 token |
| `output` | Long | 是 | 输出 token（已含 reasoning） |
| `cacheRead` | Long | 是 | 缓存命中读 token |
| `cacheWrite` | Long | 是 | 缓存写 token |
| `cacheWrite1h` | Long? | 否 | cacheWrite 中用 1h 保留策略写的子集（仅 Anthropic/Bedrock 报） |
| `reasoning` | Long? | 否 | 推理 token，是 output 的子集；不报告的 provider 为 null |
| `totalTokens` | Long | 是 | 各 provider 语义见第 10 节 |
| `cost.input/output/cacheRead/cacheWrite/total` | Double | 是 | USD，四舍五入到 1e-6 级别 |

`calculateCost` 规则（`src/models.ts:1236-1256`）：
- `inputTokens = input + cacheRead + cacheWrite`。
- 定价档位：`cost.tiers[]` 中 `inputTokens > tier.inputTokensAbove` 且阈值最高的档位，整请求套用该档费率；没有则用基础费率。
- `cost.input = rate.input/1e6*input`，output/cacheRead 同理。
- `cost.cacheWrite = (rate.cacheWrite*shortWrite + rate.input*2*longWrite)/1e6`，其中 `longWrite=usage.cacheWrite1h`，`shortWrite=cacheWrite-longWrite`（Anthropic 1h 缓存写按输入价 2 倍计）。
- OpenAI Responses 的 `service_tier` 会在算完后乘系数：`flex=0.5`，`priority/fast=2`（gpt-5.5 为 2.5），见 `src/api/openai-responses.ts:431-459`。

---

## 4. 事件协议（AssistantMessageEvent）

定义 `src/types.ts:812-828`。一个响应的流式协议共 12 个变体：

| type | 字段 | 语义 |
|---|---|---|
| `start` | `partial: AssistantMessage` | 必须在任何 update/done 之前，每个响应恰好一次 |
| `text_start` | `contentIndex: Int`, `partial` | 文本块出现；此事件时 `partial` 里该块文本为空 |
| `text_delta` | `contentIndex`, `delta: String`, `partial` | 增量追加 |
| `text_end` | `contentIndex`, `content: String`, `partial` | 最终权威文本 |
| `thinking_start` | `contentIndex`, `partial` | 思考块出现 |
| `thinking_delta` | `contentIndex`, `delta`, `partial` | |
| `thinking_end` | `contentIndex`, `content`, `partial` | |
| `toolcall_start` | `contentIndex`, `partial` | 工具调用出现；此时参数尚未必可解析 |
| `toolcall_delta` | `contentIndex`, `delta: String`, `partial` | 参数 JSON 文本增量 |
| `toolcall_end` | `contentIndex`, `toolCall: ToolCall`, `partial` | 最终参数对象 |
| `done` | `reason: stop\|length\|toolUse\|deferred`, `message: AssistantMessage` | 成功终止 |
| `error` | `reason: aborted\|error`, `error: AssistantMessage` | 失败终止 |

不变量（`src/types.ts:796-811`）：
1. `start` 先行；update/done 不得早于 `start`。
2. `partial` 是"到目前为止的实时响应"的同一对象，不是快照；文本/思考块在 `*_start` 时为空，只通过对应的 `*_delta` 增长，`*_end` 给权威值。
3. redacted thinking 可以在 start 时就是完整内容，此后不发 delta。
4. `toolcall_start` 时 `arguments` 与 provider 有关；`toolcall_delta` 携带后续 JSON 增量。
5. 终止后不再接受事件（`src/utils/event-stream.ts:43-58`）。
6. 终止消息会被补 `durationMs`；转发流（deferred fetch）不覆盖已有值、不为早于流启动的 timestamp 计时（`src/utils/event-stream.ts:91-129`）。
7. `stream()` 可以同步抛错（缺 auth）；一旦返回 stream，请求/模型/运行时错误必须编码为 `error` 事件（`src/types.ts:385-400`）。Kotlin 不能既返回 Flow 又抛异常，需要显式建模为：`fun stream(...): StreamHandle`，内部捕获 setup 异常并转 error 事件；`streamSimple` 的同步抛错语义用返回 `Result`/`StreamHandle` 或直接抛（按调用方需求定）。
8. `result()`：`done.message` 或 `error.error` 作为最终 `AssistantMessage` Promise（`src/utils/event-stream.ts:97-130`）；`complete()` = `stream().result()`（`src/models.ts:921-927`）。

事件中所有时间戳是毫秒 Long，`contentIndex` 从 0 递增且在最终 message.content 中有效。

---

## 5. 工具与 JSON Schema

### 5.1 Tool

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | String | 是 | |
| `description` | String | 是 | |
| `parameters` | `TSchema`（TypeBox，本质是 JSON Schema 对象） | 是 | Kotlin 用 `JsonObject` 存原始 JSON Schema |
| `constrainedSampling` | `false \| ConstrainedSamplingConfig`? | 否 | 约束采样声明 |

`ConstrainedSamplingConfig`：
- `{ type: "json_schema"; strict: "prefer" | "require" }`
- `{ type: "grammar"; variants: { openai_lark?: string; openai_regex?: string } }`（`src/types.ts:739-758`）

### 5.2 strict JSON Schema 转换（实现时必须移植）

`src/api/constrained-sampling.ts:15-124`：
- 拒绝的键：`$ref,$defs,definitions,allOf,oneOf,patternProperties,dependentSchemas,dependencies,unevaluatedProperties,propertyNames,contains,prefixItems,not,if,then,else`。
- `anyOf` 只允许非 object/array 的结构，且每项递归 strict。
- 对象必须 `type:"object"`；`properties` 存在才允许 required；`required` 不得含未知属性。
- 非 required 且不允许 null 的属性自动包成 `anyOf:[schema,{type:"null"}]`。
- 最终 `required` = 全部属性名，`additionalProperties:false`。
- `strict:"prefer"` 失败则退回非 strict；`strict:"require"` 失败抛错（`src/api/constrained-sampling.ts:225-249`）。

### 5.3 grammar 约束

`resolveGrammarConstrainedSampling`（`src/api/constrained-sampling.ts:251-284`）要求参数 schema 是 object 且恰有一个 required 的 string 属性，取该属性名作为 grammar 输入的承载字段；OpenAI 自定义工具以 `custom` 形式发送，输入是字符串，流式增量需要 `appendGrammarToolInputJsonDelta` 的括号补全算法（`src/api/constrained-sampling.ts:146-200`）。仅当 compat 开启 `supportsOpenAIGrammarTools` 时生效。

### 5.4 工具参数解析/修复

`src/utils/json-parse.ts`：
- `repairJson`：转义字符串内裸控制字符；非法转义前补反斜杠；截断的 `\u` 丢弃（`:33-84`）。
- `parseStreamingJson`：先 `JSON.parse`，失败再 repair 后 parse，再失败用 `partial-json` 解析，再失败 repair 后 partial，最后 `{}`（`:129-149`）。Kotlin 侧需移植 partial-json 算法或等价物。
- `parseToolCallArguments`：完成态必须是 JSON object；否则 `arguments={}` 并生成 `argumentsError`（`:106-120`）。
- 校验：`validateToolArguments` / `validateToolCall` 基于 TypeBox 编译，失败返回结构化的 `{ ok: false, errors }`（`src/utils/validation.ts:302-353`）。Kotlin 建议用 JSON Schema 校验库（如 networknt/json-schema-validator）替代，且校验失败的文本必须能被工具回传给模型。

---

## 6. 模型目录数据模型（Model / ImageModel / ClassifierModel）

### 6.1 基类字段

`BaseModel`（`src/types.ts:831-842`）与 `Model`（`:845-878`）合并后，模型 JSON 的实际字段集合（以 1.3.0 pinned 目录全量统计为准）：

| JSON 字段 | 类型 | 出现范围 | 说明 |
|---|---|---|---|
| `id` | String | 全部 | 模型 id，provider 内唯一 |
| `name` | String | 全部 | 展示名 |
| `api` | String | 全部 | 必须是已注册 API 实现 |
| `provider` | String | 全部 | provider id |
| `baseUrl` | String | 全部 | 该模型实际 endpoint |
| `type` | `"chat"\|"image"\|"classifier"` | 全部 | chat 在源码中可缺省，但生成数据显式带 `"chat"`（`src/models.ts:1127-1128` 的 getModels 依据 `isModelType`） |
| `input` | `("text"\|"image")[]` | 全部 | 非空 |
| `inputLimits` | `ModelInputLimits`? | chat 常见 | 请求/图片预处理限制 |
| `cost` | `ModelCost` | 全部 | 4 个费率必填 |
| `headers` | `map<String,String>`? | 部分（如 github-copilot） | 模型级默认头 |
| `reasoning` | Boolean | chat 必填 | |
| `thinkingLevelMap` | `ThinkingLevelMap`? | chat 部分 | |
| `promptCache` | `{short?: 秒, long?: 秒}`? | chat 部分 | |
| `contextWindow` | Long | chat/classifier 必填 | |
| `maxTokens` | Long | chat 必填 | 输出上限 |
| `samplingParams` | `Map<String, JsonValue>`? | 可选 | 模型默认采样参数 |
| `samplingParamsByThinkingLevel` | `Map<ModelThinkingLevel, Map>`? | 可选 | 按等级覆盖 |
| `compat` | per-api object? | chat 部分 | 见 6.4 |
| `output` | `("text"\|"image")[]` | image 必填 | 含 `image` |
| `lab` / `enabled` / `providers` | 任意 | 仅 radius（网关透传） | 非 pi 模型类型字段，Kotlin 必须忽略 |
| `type` 之外的扩展字段 | 任意 | 目录演进 | ignoreUnknownKeys |

`ModelCost`：`{ input, output, cacheRead, cacheWrite, tiers? }`，单位 USD / 百万 token；`tiers[] = { inputTokensAbove, input, output, cacheRead, cacheWrite }`（`src/providers/model-schema.ts:28-49`）。
`ModelInputLimits`（`src/providers/model-schema.ts:51-86`）：`maxRequestBytes?`，`images?: { resize?: {maxWidth?,maxHeight?,maxBytes?,jpegQuality?}, maxPerMessage?, maxPerRequest? }`。

### 6.2 类型判别

- `getModelType(model) = model.type ?? "chat"`（`src/utils/model-operations.ts:18-20`）。
- `isModelType` / `assertChatModel` / `assertImageModel` / `assertClassifierModel`（`:22-43`）。
- `hasApi(model, api)` 只在 chat 模型且 api 相等时为 true（`src/models.ts:1220-1234`）。

### 6.3 目录数据的两层形态

1. 发布目录 `models.all.json`：`{ "<providerId>": [model, ...] }`，值是数组，同一 id 可在不同 type 出现（如同时有 chat 与 image 条目）；由 `scripts/hydrate-model-catalog.ts:37-61` 消费，也由 pi.dev `/api/models/revisions/<rev>?types=chat,image,classifier` 返回。
2. 包内分组数据 `src/providers/data/<provider>.json`：`{ "<api>": { "<type>:<id>": model } }`，键是 `type:id`，供 TypeScript 类型推导（`scripts/model-data.ts:308-335`，`scripts/model-data.ts:72-87`）。1.0.0 已安装构建产物就是这个形态，42 个文件、968 KB。
3. 生成的 TS 汇总：`src/models.generated.ts` 导出 `MODELS`（`:47`）、`IMAGE_MODELS`（`:135`）、`CLASSIFIER_MODELS`（`:223`）；每个 provider 一个 `providers/<id>.models.ts`，内部用 `flattenChatModelCatalog` 等按 `type` 过滤成 id→model 映射（`src/model-catalog.ts:53-80`）。

### 6.4 compat 字段（Kotlin 需要完整建模）

统一入口 `src/providers/compat-schema.ts:604-612`，按 API 对应 schema。

**OpenAICompletionsCompat**（`src/providers/compat-schema.ts:188-350`）：

| 字段 | 类型 | 默认/自动 |
|---|---|---|
| `supportsStore` | Bool | 从 URL 检测 |
| `supportsDeveloperRole` | Bool | 检测 |
| `supportsReasoningEffort` | Bool | 检测 |
| `supportsUsageInStreaming` | Bool | 默认 true |
| `supportsFinishReason` | Bool | 默认 true；false 时流结束自行推断 stop/toolUse |
| `maxTokensField` | `max_completion_tokens`\|`max_tokens` | 检测 |
| `requiresToolResultName` | Bool | 默认 false |
| `requiresAssistantAfterToolResult` | Bool | 默认 false |
| `requiresThinkingAsText` | Bool | 默认 false |
| `requiresReasoningContentOnAssistantMessages` | Bool | 默认 false（DeepSeek 检测为 true） |
| `thinkingFormat` | `openai`\|`openrouter`\|`deepseek`\|`together`\|`baseten`\|`zai`\|`qwen`\|`chat-template`\|`qwen-chat-template`\|`string-thinking`\|`ant-ling` | 检测 |
| `chatTemplateKwargs` / `chatTemplateArgs` | Map<String, String\|Number\|Bool\|Null\|{$var:...}> | 默认 {} |
| `openRouterRouting` | OpenRouterRouting | 默认 {} |
| `vercelGatewayRouting` | {only?, order?} | 默认 {} |
| `zaiToolStream` | Bool | 默认 false |
| `thinkingTokenBudgetField` | 三选一 | 无 |
| `supportsThinkingTokenBudget` | Bool | 默认 false（别名） |
| `supportsOpenAIGrammarTools` | Bool | 默认 false |
| `supportsMidConvoSystemMessages` | Bool | 默认 false |
| `supportsMidConvoToolAdditions` | Bool | 默认 false |
| `supportsStrictMode` | Bool | 默认 false |
| `cacheControlFormat` | `anthropic` | 无 |
| `sendSessionAffinityHeaders` | Bool | 检测 |
| `sessionAffinityFormat` | 三选一 | 检测 |
| `supportsLongCacheRetention` | Bool | 检测 |
| `vllmPriority` | Number | 无 |

**OpenAIResponsesCompat**（`src/providers/compat-schema.ts:352-414`）：`supportsDeveloperRole`(默认 true)、`supportsMidConvoSystemMessages`(false)、`sessionAffinityFormat`、`supportsLongCacheRetention`(true)、`supportsStrictMode`、`supportsOpenAIGrammarTools`(false)、`supportsAdditionalTools`(false)、`supportsToolSearch`(false)、`supportsExplicitPromptCacheMode`(false)、`supportsMidConvoEffort`(false)、`supportsMaxOutputTokens`(true)。

**AnthropicMessagesCompat**（`src/providers/compat-schema.ts:416-501`）：`supportsEagerToolInputStreaming`(true)、`supportsLongCacheRetention`(true)、`sendSessionAffinityHeaders`、`sessionAffinityFormat`、`supportsCacheControlOnTools`(true)、`supportsTemperature`(true)、`forceAdaptiveThinking`(false)、`allowEmptySignature`(false)、`supportsStrictTools`(false)、`supportsMidConvoEffort`(false)、`supportsMidConvoSystemMessages`(false)、`supportsMidConvoToolChanges`(false)、`allowedFallbackModels`（最多 3 个 `{provider,model,cost}`）。

**BedrockCompat**：仅 `supportsStrictMode`(false)（`:503-511`）。
**MistralConversationsCompat**：仅 `supportsMidConvoSystemMessages`(false)（`:513-522`）。

`ProviderCompatSchema` 是上述的合并超集，用于自定义 models.json（`src/providers/compat-schema.ts:614-622`）。

### 6.5 目录校验规则

`scripts/model-data.ts:144-206` 校验：
- key 必须等于 `<type>:<id>`；`value.id` 等于 id，`value.provider` 等于 provider，`value.api` 等于所在 API 分组。
- `name` 非空；`baseUrl` 是字符串；`input` 是非空 modality 数组。
- image 必须有含 image 的 `output`，且不允许其他类型带 output。
- chat 必须有 boolean `reasoning`、正数 `contextWindow`、正数 `maxTokens`；classifier 必须有正数 `contextWindow`。
- `cost` 4 个字段必须是有限数字。
这些规则可直接转成 Kotlin 的目录加载断言。

---

## 7. 请求选项类型

### 7.1 公共

`ProviderRequestOptions`（`src/types.ts:152-216`）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `signal` | AbortSignal? | 取消 |
| `telemetry` | TelemetrySpan? | 链路 span |
| `apiKey` | String? | 显式 key，覆盖 auth 解析 |
| `fetch` | FetchFunction? | 自定义 HTTP；Google/Codex 等适配器可能不支持 |
| `env` | Map<String,String>? | provider 作用域 env，优先 process.env |
| `onPayload` | (payload, model) => payload? | 请求体改写钩子 |
| `onResponse` | (status, headers, model) => void | 响应头钩子 |
| `headers` | Map<String, String\|null>? | 额外头；null = 删除同名默认头；Bedrock 走 SigV4 后注入 |
| `identity` | ClientIdentity? | UA/归因头 |
| `timeoutMs` | Long? | 请求超时 |
| `maxRetries` | Int? | 客户端重试次数；SDK 默认 2，但 pi 用 0 并外包重试 |
| `maxRetryDelayMs` | Long? | 默认 60000；0 = 不设限；超过则立即失败 |

`ClientIdentity = { userAgent?: "short"|"detailed"|{custom}, attribution?: {name,url,categories?}|null, version? }`（`src/utils/identity.ts:11-37`）。

`StreamOptions` 追加（`src/types.ts:218-268`）：`onProviderStreamEvent?`、`temperature?`、`samplingParams?`、`maxTokens?`、`transport?`、`cacheRetention?`、`sessionId?`、`websocketConnectTimeoutMs?`、`metadata?`。
`SimpleStreamOptions` 再追加（`:374-383`）：`toolChoice?: "auto"|"none"`、`reasoning?: ThinkingLevel`、`deferred?: boolean|{window}`、`thinkingBudgets?`。
`ThinkingBudgets = {minimal?,low?,medium?,high?}`（`:136-142`）。

### 7.2 每个 API 的专属选项

| API | 选项接口 | 专属字段 |
|---|---|---|
| `openai-completions` | `OpenAICompletionsOptions`（`src/api/openai-completions.ts:154-159`） | `toolChoice`（OpenAI 联合类型）、`reasoningEffort: minimal\|low\|medium\|high\|xhigh\|max`、`thinkingBudgets` |
| `openai-responses` | `OpenAIResponsesOptions`（`src/api/openai-responses.ts:144-150`） | `reasoningEffort`、`reasoningSummary: auto\|detailed\|concise\|null`、`serviceTier`、`toolChoice` |
| `azure-openai-responses` | `AzureOpenAIResponsesOptions`（`src/api/azure-openai-responses.ts:31-36`） | `azureApiVersion`、`azureResourceName`、`azureBaseUrl`、`azureDeploymentName`、`reasoningEffort`、`toolChoice`、`reasoningSummary` |
| `anthropic-messages` | `AnthropicOptions`（`src/api/anthropic-messages.ts:235-295`） | `thinkingEnabled`、`thinkingBudgetTokens`、`effort: low\|medium\|high\|xhigh\|max`、`thinkingDisplay: summarized\|omitted`、`interleavedThinking`、`toolChoice: auto\|any\|none\|{type:"tool",name}`、`client`（注入 SDK 客户端） |
| `google-generative-ai` | `GoogleOptions`（`src/api/google-generative-ai.ts:47-54`） | `toolChoice: auto\|none\|any`、`thinking: {enabled,budgetTokens?,level?}` |
| `google-vertex` | `GoogleVertexOptions`（`src/api/google-vertex.ts:51-61`） | 同上 + `project`、`location` |
| `mistral-conversations` | `MistralOptions`（`src/api/mistral-conversations.ts:37-42`） | `toolChoice`、`promptMode: "reasoning"`、`reasoningEffort` |
| `bedrock-converse-stream` | `BedrockOptions`（`src/api/bedrock-converse-stream.ts:80-112`） | `region`、`profile`、`toolChoice`、`reasoning`、`thinkingBudgets`、`interleavedThinking`、`thinkingDisplay`、`requestMetadata`、`bearerToken` |
| `openai-codex-responses` | `OpenAICodexResponsesOptions`（`src/api/openai-codex-responses.ts:83-90`） | `reasoningEffort: none\|minimal\|low\|medium\|high\|xhigh\|max`、`reasoningSummary`、`serviceTier`、`textVerbosity`、`toolChoice: auto\|none\|required` |
| `pi-messages` | `PiMessagesOptions`（`src/api/pi-messages.ts:34-40`） | `reasoning`、`toolChoice: auto\|none\|required\|{type:"function",...}`、`debug` |
| 图片 | `ImagesOptions`（`src/types.ts:364-370`） | `metadata` |
| 分类器 | `ClassifierOptions`（`src/types.ts:355-362`） | `temperature` |

### 7.3 默认值与上限

| 项 | 默认 | 证据 |
|---|---|---|
| `cacheRetention` | `short`（env `PI_CACHE_RETENTION=long` 可改） | `src/types.ts:245-249`，`src/api/openai-completions.ts:294-302` |
| simple 推理等级 | `options.reasoning ?? "off"` | `src/api/simple-options.ts:42` |
| 默认 thinking 预算 | minimal 1024 / low 2048 / medium 8192 / high 16384 | `src/api/simple-options.ts:71-76` |
| 答案保留 token | `MIN_ANSWER_TOKENS = 1024`；共享上限时思考预算被压到 `ceiling-1024` | `src/api/simple-options.ts:69,88-91` |
| maxTokens | `options.maxTokens ?? model.maxTokens`，再按 `contextWindow - estimateContextTokens - 4096` 收紧，最小 1 | `src/api/simple-options.ts:15-22,46` |
| Anthropic 思考预算 | `thinkingBudgetTokens \|\| 1024` | `src/api/anthropic-messages.ts:1303-1307` |
| Anthropic thinkingDisplay | `summarized` | `src/api/anthropic-messages.ts:1287,1294` |
| 重试 | HTTP 408/409/429/5xx 才重试；`x-should-retry` 优先 | `src/utils/provider-retry.ts:27-42` |
| 重试退避 | `min(0.5*2^n,8)s * (1-0.25*random)`；优先 `retry-after-ms`/`retry-after` | `src/utils/provider-retry.ts:60-84` |
| 服务器要求延迟上限 | 默认 60s，超过直接失败 | `src/utils/provider-retry.ts:44-58` |
| OAuth 提前刷新 | 到期前 5 分钟 | `src/auth/resolve.ts:102,161-191` |
| OAuth refresh 超时 | 15s | `src/auth/resolve.ts:103,140` |
| 流持续时间 | 用单调时钟测量并写 `durationMs` | `src/utils/event-stream.ts:97-129` |

推理等级钳制：`getSupportedThinkingLevels` 与 `clampThinkingLevel`（`src/models.ts:1258-1290`）：`reasoning=false` 只返回 `["off"]`；`thinkingLevelMap[level]===null` 不支持；`xhigh/max` 只有显式映射才支持；请求值不支持时先向上找、再向下找。

---

## 8. 认证类型

### 8.1 凭据

| 类型 | 形状 | 证据 |
|---|---|---|
| `ApiKeyCredential` | `{type:"api_key", key?: string, env?: Map<String,String>}` | `src/auth/types.ts:13-21` |
| `OAuthCredential` | `{type:"oauth", refresh: string, access: string, expires: Long(ms), ...任意扩展}` | `src/auth/types.ts:23-34` |
| `Credential` | 上述二选一 | `src/auth/types.ts:36-37` |
| 磁盘 auth.json | `{ "<providerId>": Credential }` | `src/cli.ts:20-24` 展示该形状 |

`expires` 是绝对毫秒时间戳；各 OAuth flow 还会在 credential 上放扩展字段，例如 GitHub Copilot 的 `availableModelIds`（`src/providers/github-copilot.ts:28-33`）、Radius 的 `gatewayConfig`（`src/providers/radius-config.ts:22-24`）、OpenAI ChatGPT 的 client id/scopes（`src/auth/oauth/openai-chatgpt.ts:155-175`）。

### 8.2 请求级 auth 与解析结果

`ModelAuth = { apiKey?, headers?: Map<String,String>, baseUrl? }`（`src/auth/types.ts:7-11`）。
`AuthResult = { auth: ModelAuth, env?: Map, source?: string }`（`:103-110`）。`source` 用于状态 UI，如 `"ANTHROPIC_API_KEY"`、`"OAuth"`、`"~/.aws/credentials"`。
`AuthCheck = { source?, type: "api_key"|"oauth" }`（`:112-115`）。

### 8.3 CredentialStore（应用侧实现）

接口（`src/auth/types.ts:65-94`）：`read(providerId)`、`list()`、`modify(providerId, fn)`、`delete(providerId)`。
- `modify` 是唯一写路径，必须按 provider 串行化（跨进程最好文件锁）；`fn` 能看到当前凭据，返回 undefined 表示不变。
- read 缺失返回 undefined；只有存储故障才 reject。
- OAuth 刷新必须在 `modify` 锁内执行，避免并发/多进程重复刷新轮换 refresh token（`src/auth/resolve.ts:105-155`）。
- Kotlin 建议：`Mutex` per provider + 原子文件写（tmp+rename），格式与 pi auth.json 完全兼容，便于迁移。

### 8.4 登录交互

`AuthPrompt`（`src/auth/types.ts:125-130`）：`text`/`secret`/`select`/`manual_code`，均带可选 `signal`。
`AuthEvent`（`:137-147`）：`info{message,links?}`、`auth_url{url,instructions?}`、`device_code{userCode,verificationUri,intervalSeconds?,expiresInSeconds?}`、`progress{message}`。
`AuthInteraction`（`:156-161`）：`prompt(prompt): Promise<String>`（select 返回 option id）、`notify(event)`、`signal`。
`LoginOptions`（`:202-214`）：`getDeviceId?()`、`agentName?`（OpenAI ChatGPT/Codex 登录用）。

### 8.5 ProviderAuth 结构

`ApiKeyAuth = { name, login?(interaction), check?({ctx,credential,signal}), resolve({ctx,credential,signal}) }`（`src/auth/types.ts:170-199`）。
`OAuthAuth = { name, isSubscription?, loginLabel?, login(interaction, options?), refresh(credential, signal), toAuth(credential) }`（`:221-245`）。
`ProviderAuth = { apiKey?: ApiKeyAuth, oauth?: OAuthAuth }`，至少一个（`:252-255`）。纯环境变量 provider 也必须提供 apiKey auth（其 resolve 报告是否已配置）。

### 8.6 9 个 OAuth flow 的关键常量

| flow | authorize URL | token URL | client_id | redirect / 模式 | scope | 凭据 |
|---|---|---|---|---|---|---|
| anthropic | `https://claude.ai/oauth/authorize` | `https://platform.claude.com/v1/oauth/token` | 解析自 base64：`9d1c250a-e61b-44d9-88ed-5944d1962f5e` | 本机回调 `http://localhost:53692/callback`，可回退 copy-code（`https://platform.claude.com/oauth/code/callback`） | `src/auth/oauth/anthropic.ts:26+`（含 `org:create_api_key user:profile user:inference` 等） | refresh/access/expires（expires 提前 5 分钟）`:123-141` |
| openai-chatgpt | `https://auth.openai.com/api/accounts/authorize` | `https://auth.openai.com/api/accounts/oauth/token` | `dynamic_agent_client`（动态注册用户 client） | `http://127.0.0.1:1455/auth/callback` | `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` | access/refresh/expires + 扩展 client_id/scope |
| openai-codex | `https://auth.openai.com/oauth/authorize`（可用 device flow 替代） | `https://auth.openai.com/oauth/token` | `app_EMoamEEZ73f0CkXaXp7hrann` | `http://localhost:1455/auth/callback`；device: `/api/accounts/deviceauth/usercode`、`/token`、`/codex/device` | `openid profile email offline_access` | access/refresh/expires；accountId 从 access JWT claim `https://api.openai.com/auth` 提取 |
| github-copilot | GitHub device flow | GitHub token 端点 | GitHub Copilot 客户端 id（base64 解码 `Iv1.b507a08c87ecfe98`） | device code | — | `refresh` 存 GitHub token，`access` 存 copilot 短期 token；`toAuth` 返回 proxy-ep 推导的 baseUrl 与 apiKey（`src/auth/oauth/github-copilot.ts:495-507`） |
| xai | `https://auth.x.ai/oauth2/device/code` | `https://auth.x.ai/oauth2/token` | `b1a00492-073a-47ea-816f-4c329264a828` | device code | `openid profile email offline_access grok-cli:access api:access` | access/refresh/expires（过期前 5 分钟刷新） |
| kimi-coding | `https://auth.kimi.com`（可覆盖）device flow | 同 host | `17e5f671-d194-4dfb-9706-5516cb48c098` | device code，15 分钟超时 | — | access/refresh/expires |
| radius | gateway 发现的授权端点 | gateway 发现/token | `pi-gateway` | 浏览器回调 `http://127.0.0.1:1456/oauth/callback` 或 device code | `gateway offline_access` | access/refresh/expires；`toAuth` 只返回 `apiKey=access`，gatewayConfig 以扩展字段存在 credential 上由 provider 读取（`src/auth/oauth/radius.ts:305-320`，`src/providers/radius-config.ts:22-24`） |
| openrouter | `https://openrouter.ai/auth`（PKCE） | `https://openrouter.ai/api/v1/auth/keys` | PKCE 动态 | 本机一次性 loopback 回调，可手动粘贴 redirect URL/裸 code | — | 兑换出的永久 API key 存为 access，refresh 空串，expires=MAX_SAFE_INTEGER |
| meta | `https://auth.meta.com/oidc/device/authorization/` | `https://auth.meta.com/oidc/device/token/`（device grant） | `1031625952748946` | device code；随后 `POST https://api.meta.ai/muse-code/key` 铸 key | — | refresh=identity token，access=铸出的 API key（约 24h） |

细节证据：`src/auth/oauth/*.ts` 各文件头部常量（anthropic:14-26、openai-chatgpt:16-29、openai-codex:22-35、github-copilot:11-19、xai:8-14、kimi-coding:14-19、radius:18-27、openrouter:19-22、meta:20-26）。
设备码轮询通用实现：`src/auth/oauth/device-code.ts:1-98`；PKCE：`src/auth/oauth/pkce.ts`；本机回调服务器：`src/auth/oauth/callback-server.ts`（忽略杂请求、只接受第一次、超时/取消语义在 `:52-183`）。

---

## 9. 图片与分类器类型

### 9.1 Images

`ImagesContext = { input: (TextContent|ImageContent)[] }`（`src/types.ts:652-657`）。
`AssistantImages = { api, provider, model, output: (TextContent|ImageContent)[], responseId?, usage?, stopReason: stop|error|aborted, errorMessage?, timestamp }`（`:661-671`）。
图片模型：`ImageModel = BaseModel + type:"image" + output: ("text"|"image")[]`（`:881-885`）。已注册图片 API 仅 `openrouter-images`（`:76`），协议见 `src/api/openrouter-images.ts:42-118`（OpenAI chat completions + `modalities:["image"]`，返回 `choices[0].message.images[].image_url` data URL）。

### 9.2 Classifier

问题联合（`src/types.ts:673-691`）：
- `{type:"choice", instructions, criteria: Map<String,String>}`
- `{type:"score", instructions, criteria: String[]}`
- `{type:"bool", instructions, criteria: {true:String,false:String}}`

`ClassifierContext = { state: JsonObject, images?: ImageContent[], questions: Map<String,ClassifierQuestion> }`（`:693-701`）。
答案联合（`:703-722`）：choice `{choice, probabilities: Map<String,Double>, confidence}`；score `{score, confidence}`；bool `{probability}`（wire 里 TypeSafe 用 `noul`）。
`ClassifierResult = { api, provider, model, answers: Map<String,ClassifierAnswer>, usage?, stopReason, errorMessage?, timestamp }`（`:724-734`）。

---

## 10. 线上 wire 映射表（Kotlin 实现直接照做）

### 10.1 端点

| API | 请求 | 证据 |
|---|---|---|
| openai-completions | 官方 `openai` SDK → `POST {baseUrl}/chat/completions`（`client.chat.completions.create`） | `src/api/openai-completions.ts:376-383,797-803` |
| openai-responses | `POST {baseUrl}/responses`（`client.responses.create`） | `src/api/openai-responses.ts:220-227,336-342` |
| azure-openai-responses | AzureOpenAI SDK，baseUrl 规范化为 `.../openai/v1`，deployment 名可覆盖 model，api-version 默认 `v1` | `src/api/azure-openai-config.ts:16-107`，`src/providers/azure.ts:40-55` |
| anthropic-messages | `client.beta.messages.create(..., stream:true)` → `POST {baseUrl}/v1/messages`，beta 通过请求参数 `betas` | `src/api/anthropic-messages.ts:655-662,1219-1225` |
| google-generative-ai | `@google/genai` `client.models.generateContentStream` → `{baseUrl}/models/{id}:streamGenerateContent`，baseUrl 含版本时 apiVersion="" | `src/api/google-generative-ai.ts:100,348-368` |
| google-vertex | `@google/genai` vertexai 模式，`v1`，project/location 必填，baseUrl 模板 `https://{location}-aiplatform.googleapis.com` | `src/api/google-vertex.ts:364-418,454-473` |
| mistral-conversations | 原生 `POST {baseUrl}/v1/chat/completions`，`Accept: text/event-stream` | `src/api/mistral-conversations.ts:298-341,355-372` |
| bedrock-converse-stream | AWS SDK `ConverseStreamCommand`（SigV4 或 bearer token，`AWS_BEDROCK_SKIP_AUTH=1` 可跳过签名） | `src/api/bedrock-converse-stream.ts:128-365` |
| openai-codex-responses | `POST {baseUrl}/codex/responses`，或 WebSocket（`ws(s)://.../codex/responses`） | `src/api/openai-codex-responses.ts:663-676` |
| pi-messages | `POST {baseUrl}/messages`，自定义 SSE（`data: {...}` 事件，`[DONE]` 可忽略），`?debug=1` | `src/api/pi-messages.ts:277-321,356-436` |
| openrouter-images | OpenAI chat completions 非流式，`modalities` | `src/api/openrouter-images.ts:73-83,156-166` |
| typesafe-system-one | `POST {baseUrl}/systemone`，`{model, state, questions}` | `src/api/typesafe-system-one.ts:15-22` |
| openai-decisions | `POST {baseUrl}/decisions`，`{model, input, questions}` | `src/api/openai-decisions.ts:19-27,173-185` |
| cloudflare-workers-ai-system-one | Workers AI REST：`{baseUrl}/run` | `src/api/cloudflare-workers-ai-system-one.ts`（48 行，REST classifier） |
| llama-cpp-classify | llama.cpp server 的 classification 端点 | `src/api/llama-cpp-classify.ts`（461 行） |

### 10.2 消息映射（内存 → wire）

| 内存 | openai-completions | anthropic-messages | openai-responses | google | mistral | bedrock |
|---|---|---|---|---|---|---|
| SystemMessage 首条 | `role: developer`（reasoning 且支持时）否则 `system`，内容为渲染文本 | 顶层 `system: [{type:"text",text,cache_control?}]` | `role: developer/system` + text input | `systemInstruction` | `role:"system"` | 顶层 `system: [{text}]` |
| 后续 SystemMessage | 支持 mid-convo 时原位发送，否则折叠 | 原位发送（受支持时）/折叠 | 原位 developer/system，或 tool 变更控制项 | 折叠 | 折叠 | 折叠 |
| UserMessage 文本 | `{role:"user",content:"..."}` | `{role:"user",content:[{type:"text",text}]}` | `{role:"user",content:[{type:"input_text",text}]}` | `{role:"user",parts:[{text}]}` | `{role:"user",content}` | `user` 消息 + text block |
| UserMessage 图片 | `image_url: {url:data URL}` | `{type:"image",source:{type:"base64",media_type,data}}` | `{type:"input_image",detail:"auto",image_url:data URL}` | `{inlineData:{mimeType,data}}` | `imageUrl` | image block |
| AssistantMessage 文本 | `content` 字符串（不要数组） | text block | `{type:"message",role:"assistant",content:[{type:"output_text",text}],id,phase?}` | `{role:"model",parts:[{text}]}` | assistant content | text block |
| thinking 回放 | 同模型：`reasoning_details` 数组或签名字段（`reasoning_content`/`reasoning`/`reasoning_text`）；异模型丢弃 | 同模型 `{type:"thinking",thinking,signature}`；redacted → `{type:"redacted_thinking",data}` | 同模型把 `thinkingSignature` 反序列化为 reasoning item；异模型丢弃 | 同模型 `{thought:true,text,thoughtSignature}`；异模型降级成普通 text | thinking chunk | reasoningContent block；加密块签名 base64 |
| ToolCall | assistant `tool_calls:[{id,type:"function",function:{name,arguments:JSON字符串}}]` | `{type:"tool_use",id,name,input}` | `{type:"function_call",id?,call_id,name,arguments,namespace?}` | `{functionCall:{name,args,id?},thoughtSignature?}` | `toolCalls:[{id,type,function:{name,arguments:字符串},index}]` | `{toolUse:{toolUseId,name,input}}` |
| ToolResultMessage | `{role:"tool",tool_call_id,content:text}`，连续结果合并；图片在支持时作为后续 user 图片消息 | `{role:"user",content:[{type:"tool_result",tool_use_id,content,is_error}]}`，连续合并 | `{type:"function_call_output",call_id,output}` | `{functionResponse:{name,response:{output\|error},parts?}}` | `{role:"tool",toolCallId,name,content}` | `{toolResult:{toolUseId,content:[...]}}` |

证据：openai `src/api/openai-completions.ts:1199-1490`；anthropic `:1339-1558`；responses `src/api/openai-responses-shared.ts:191-432`；google `src/api/google-shared.ts:193-345`；mistral `src/api/mistral-conversations.ts:808-923`；bedrock `src/api/bedrock-converse-stream.ts:988-1159`。

### 10.3 usage 映射

| API | 线上字段 → 内存 |
|---|---|
| openai-completions | `prompt_tokens/details.cached_tokens→cacheRead`，`prompt_tokens_details.cache_write_tokens→cacheWrite`，`completion_tokens→output`，`completion_tokens_details.reasoning_tokens→reasoning`；`input = max(0,prompt-cacheRead-cacheWrite)`；total=四项和（`src/api/openai-completions.ts:1529-1570`） |
| anthropic-messages | `input_tokens/output_tokens/cache_read_input_tokens/cache_creation_input_tokens/cache_creation.ephemeral_1h_input_tokens/output_tokens_details.thinking_tokens`；total 自算（`src/api/anthropic-messages.ts:711-721,861-890`） |
| openai-responses | `input_tokens - input_tokens_details.cached_tokens - cache_write_tokens` 为 input；`output_tokens`；`total_tokens`（`src/api/openai-responses-shared.ts:630-661`） |
| google | `promptTokenCount-cachedContentTokenCount→input`，`candidatesTokenCount+thoughtsTokenCount→output`，`cachedContentTokenCount→cacheRead`，`thoughtsTokenCount→reasoning`，`totalTokenCount`（`src/api/google-generative-ai.ts:232-251`） |
| mistral | `prompt_tokens-cached→input`，`completion_tokens→output`，`cached→cacheRead`，`total_tokens`（`src/api/mistral-conversations.ts:616-628`） |
| bedrock | `inputTokens/outputTokens/cacheReadInputTokens/cacheWriteInputTokens/cacheDetails[ttl=1h]→cacheWrite1h`，total 缺失时 input+output（`src/api/bedrock-converse-stream.ts:717-734`） |
| 图片 | OpenRouter chat usage 同 OpenAI 字段（`src/api/openrouter-images.ts:169-199`） |
| classifier | `{input_tokens,output_tokens}` → Usage（`src/api/classifier-shared.ts:117-131`） |

### 10.4 stop reason 映射

| API | 映射 |
|---|---|
| openai-completions | `stop/end→stop`，`length→length`，`function_call/tool_calls→toolUse`，`content_filter/network_error/未知→error`；null→stop（`src/api/openai-completions.ts:1572-1596`） |
| anthropic | `end_turn→stop`，`max_tokens→length`，`tool_use→toolUse`，`refusal→error`，`pause_turn/stop_sequence→stop`，`sensitive→error`，未知抛错（`src/api/anthropic-messages.ts:1664-1690`） |
| responses | status `completed→stop`（有 toolCall 则 toolUse）、`incomplete`+`max_output_tokens→length`、其他 `incomplete→error`、`failed/cancelled→error`，`in_progress/queued→stop`；由 `mapStopReason(status, incompleteReason)` 决定（`src/api/openai-responses-shared.ts:655-675,857-887`） |
| google | `STOP→stop`，`MAX_TOKENS→length`，安全/召回/工具错误等→error，未知抛错（`src/api/google-shared.ts:443-484`） |
| mistral | `stop→stop`，`length/model_length→length`，`tool_calls→toolUse`，`error` 及未知值→error（error 的 message 为 `Provider stopped with: error (server error)`，供重试判定）（`src/api/mistral-conversations.ts:936-952`） |
| bedrock | `end_turn/stop_sequence→stop`，`max_tokens/model_context_window_exceeded→length`，`tool_use→toolUse`，其余（含 guardrail 等）→error 并带 `Provider stopped with: <reason>`（`src/api/bedrock-converse-stream.ts:1196-1213`） |

### 10.5 工具调用 id 规范化

各 API 对 id 字符集/长度有约束，回放前必须处理（`src/api/transform-messages.ts` 是总入口）：
- openai-completions：带 `|` 的复合 id 切成 `callId_itemId`，非法字符替换 `_`，超 40 字符用短哈希；provider=`openai` 额外截断到 40（`src/api/openai-completions.ts:1208-1232`）。
- anthropic：只允许 `^[a-zA-Z0-9_-]+$` 且 ≤64（`src/api/anthropic-messages.ts:1334-1338`）。
- responses：`callId|itemId`，每段清洗后 ≤64，item id 必须以 `fc_` 开头，跨模型时用 `fc_<shortHash>`（`src/api/openai-responses-shared.ts:200-223`）。
- google：Gemini 3+/claude/gpt-oss 需要显式 id，替换非 `[a-zA-Z0-9_-]` 并截 64（`src/api/google-shared.ts:167-200`）。
- mistral：id 必须是 9 位字母数字，用短哈希派生并保证双向唯一（`src/api/mistral-conversations.ts:238-268`）。

### 10.6 孤儿工具调用/错误消息回放规则

`transformMessages`（`src/api/transform-messages.ts:91-256`）：
- `stopReason=error/aborted` 的 assistant 消息整条丢弃。
- 工具调用后没有 result 时插入合成 `toolResult`：`content=[{type:"text",text:"No result provided"}]`、`isError=true`。
- 工具调用之间存在 system message 时把 system 暂存到工具结果之后发出（避免 Anthropic 拒绝 tool_use→system→tool_result 顺序）。
- 不支持图片的模型：用户图片替换成文本 `(image omitted: model does not support images)`，工具结果图片替换成 `(tool image omitted: model does not support images)`。
- 错误工具结果带图片（Anthropic/Bedrock Claude 拒绝）时拆成文本结果 + 后续图片（`src/api/transform-messages.ts:16-60`）。

---

## 11. 错误与诊断类型

- `ModelsErrorCode = "model_source" | "model_validation" | "provider" | "stream" | "auth" | "oauth"`（`src/utils/models-error.ts:4`）。`ModelsError` 的 message 会带上 cause 文本（`:15-21`）。
- `AssistantMessageDiagnostic = { type: string, timestamp: Long, error?: {name?,message,stack?,code?}, details?: JsonObject }`（`src/utils/diagnostics.ts:2-7`）。
- 错误规范化：`normalizeProviderError` 从 SDK 错误对象提取 status/body/message；`formatProviderError` 生成 `"<status>: <body>"` 或 `"<prefix> (<status>): <body>"`（`src/utils/error-body.ts:38-135`）。Kotlin 侧错误文本要与 pi 保持一致，便于上层重试/溢出识别。
- 上下文溢出识别：`isContextOverflow(message, contextWindow?)`（`src/utils/overflow.ts:142-176`），包含 3 种情况：错误文本匹配 30+ 组正则、静默溢出（`input+cacheRead > contextWindow`）、length+zero-output 填满窗口。溢出正则表 `:38-67`，非溢出排除表 `:80-84`。
- 可恢复 length：`isRecoverableLength`（`:184-186`）。
- 重试判定：`isRetryableAssistantError`（`src/utils/retry.ts:266-270`），不可重试的配额/账单/订阅限制模式见 `:7-31`。

---

## 12. 该移植的测试清单（packages/ai/test，177 个文件，按优先级）

### 12.1 MUST（纯逻辑，无网络，直接作为 Kotlin 验收）

| 测试文件 | 用途 |
|---|---|
| `event-stream.test.ts` | 事件流队列顺序、结束语义、durationMs（`test/event-stream.test.ts:7-156`） |
| `json-parse.test.ts` | JSON 修复/partial 解析、非法参数处理（`:6-51`） |
| `message-types.test.ts` | 消息/内容类型约束与序列化（`:14-49`） |
| `model-types.test.ts` | 三类型模型、isModelType、无 getAllModels 的 provider（`:54-105`） |
| `models-entry.test.ts` / `models-runtime.test.ts` | Models 注册、查询、api 分派 |
| `providers.test.ts` | 42 provider 工厂/目录/认证一致性 |
| `model-data-validation.test.ts` | 目录 hydrate/校验/key 分组（`:87-199`） |
| `model-catalog-types.test.ts` | 目录类型推导与 copilot 路由 |
| `model-cost-tiers.test.ts` | 成本分档、缺失费率继承、时区覆盖跳过（`:15-97`） |
| `tokens.test.ts` / `total-tokens.test.ts` | usage/totalTokens 语义（后者需真实 key，参考用途） |
| `context-estimate.test.ts` | 3.5 chars/token、过期 usage 无效（`:46-79`） |
| `overflow.test.ts` | 各 provider 溢出正则（`:57-101`） |
| `retry.test.ts` / `provider-retry.test.ts` | 重试分类、退避、retry-after、中止（`:16-73`） |
| `abort.test.ts` | 流中/立即 abort 的终止消息 |
| `pre-generation-error.test.ts` | 缺 auth 时同步抛错（`:32`） |
| `transform-messages-cross-model-thinking.test.ts` | 跨模型 thinking 丢弃（`:40-71`） |
| `tool-call-id-normalization.test.ts` | 工具 id 规范化 |
| `sampling-options.test.ts` / `reasoning-options.test.ts` / `max-thinking.test.ts` / `supports-xhigh.test.ts` | 采样/推理映射 |
| `compat-env.test.ts` / `compat-schema.test.ts` | compat 默认值与环境 |
| `validation.test.ts` | 工具参数校验错误结构 |
| `faux-provider.test.ts` | 假 provider 脚本化响应（Kotlin 测试替身需求） |
| `context-overflow.test.ts` | 真实 provider 溢出 e2e（可选，需 key） |

### 12.2 SHOULD（按 API 移植，多数用 mock server 而非真实网络）

`openai-completions-retry.test.ts`、`openai-completions-prompt-cache.test.ts`、`openai-completions-tool-choice.test.ts`、`openai-completions-reasoning-details.test.ts`、`openai-completions-thinking-as-text.test.ts`、`openai-completions-response-model.test.ts`、`openai-responses-compat.test.ts`、`openai-responses-terminal-event.test.ts`、`openai-responses-partial-json-cleanup.test.ts`、`openai-responses-message-id.test.ts`、`anthropic-sse-parsing.test.ts`、`anthropic-thinking-disable.test.ts`、`anthropic-temperature-compat.test.ts`、`anthropic-strict-tool-schema.test.ts`、`anthropic-tool-name-normalization.test.ts`、`google-shared-convert-tools.test.ts`、`google-thinking-level-map.test.ts`、`google-thinking-signature.test.ts`、`google-shared-retry.test.ts`、`mistral-tool-schema.test.ts`、`mistral-raw-stop-reason.test.ts`、`mistral-http-transport.test.ts`、`bedrock-convert-messages.test.ts`、`bedrock-thinking-payload.test.ts`、`bedrock-error-metadata.test.ts`、`pi-messages.test.ts`、`openrouter-images.test.ts`、`images.test.ts`、`images-models.test.ts`、`classifier-models.test.ts`、`openai-decisions.test.ts`、`typesafe-system-one.test.ts`、`azure-openai-base-url.test.ts`、`azure-openai-completions.test.ts`。

### 12.3 OAuth / 登录（DEFER 或按需）

`oauth-auth.test.ts`、`oauth-device-code.test.ts`、`oauth-callback-server.test.ts`、`anthropic-oauth.test.ts`、`openai-codex-oauth.test.ts`、`openai-chatgpt-oauth.test.ts`、`github-copilot-oauth.test.ts`、`xai-oauth.test.ts`、`kimi-coding-oauth.test.ts`、`radius-oauth.test.ts`、`meta-oauth.test.ts`、`openrouter-oauth.test.ts`、`env-api-keys.test.ts`。
其中 `oauth-device-code`、`oauth-callback-server`、`env-api-keys` 适合优先移植（纯状态机/回调/环境变量）。

### 12.4 可略过

真实 key 的 smoke/e2e（`stream.test.ts`、各 `*-e2e.test.ts`、`anthropic-opus-4-8-smoke.test.ts`、`codex-websocket-cached-probe.ts`、`scratch.ts`、`test/utils` 辅助文件）以及 Anthropic/Bedrock 联邦、telemetry 等重环境测试。

---

## 13. Kotlin 建模检查清单

1. `Message` / `AssistantMessageEvent` / `Content` 用 sealed + 判别键 `role`/`type`；未知变体在 `done` 前不应发生，但反序列化旧会话时允许 skip。
2. `JsonObject` 用于 `arguments`、`details`、`state`、`Tool.parameters`；`Tool.parameters` 若需要 strict 转换，先深拷贝再改写。
3. `Usage.cost` 用 Double；`reasoning`/`cacheWrite1h` 可空；`totalTokens` 必须显式赋值，不要依赖派生。
4. 时间戳统一 Long ms；`durationMs` 只在终止消息上写。
5. `Model.compat` 用按 api 的 sealed 结构或"字段全可空"的合并数据类；未知 compat 字段忽略。
6. 模型目录加载：`ignoreUnknownKeys=true`；`type` 缺省视为 chat；`type:id` 唯一；同 id 不同 type 允许共存。
7. 序列化回写会话时保持 camelCase 与字段缺省（不写 null）；`textSignature`/`thinkingSignature` 原样字符串，不要解码。
8. 事件流的"同步抛错"语义与 Kotlin 协程 Flow 不兼容，必须显式设计（见 4.7）。
9. 工具参数校验失败时保留 `argumentsError` 文本，工具层需要把它作为错误结果回传模型。
10. 目录/测试先落地：用 `models.all.json` 做 JSON 解析往返测试；用 faux provider 等价物做事件协议测试。
