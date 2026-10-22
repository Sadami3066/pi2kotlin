# pi-ai 源码分析与 Kotlin 移植方案

源码基线：`upstream pi checkout`，`packages/ai` 版本 **1.3.0**（`packages/ai/package.json`，`src/utils/version.ts:2`）。只读分析，未修改源码，未执行 npm install/构建。
行号：`文件:行` 相对 `packages/ai`（如 `src/types.ts:62`），或相对仓库根（如 `nix/package.nix:26`）。
配套文档：`docs/analysis/pi-ai-types.md`（逐类型/逐字段/事件协议/测试清单），本文聚焦包结构、provider/API 协议、认证、模型数据与 Kotlin 落地计划。

---

## 0. 结论摘要

1. 规模与结构：`src` 共 **27,709 行**（201 个 `.ts` 文件）；其中 `src/api` 43 文件 13,486 行、`src/providers` 95 文件 3,827 行、`src/auth` 18 文件 3,832 行（OAuth 13 文件 3,189 行）、`src/utils` 26 文件 2,699 行、`src` 根 18 文件 3,820 行；测试 177 文件 44,744 行。真正的协议实现集中在 10 个 chat API、1 个 image API、4 个 classifier API。
2. Provider 面：42 个内置 provider（`src/models.generated.ts:47,135,223` 的 `MODELS`/`IMAGE_MODELS`/`CLASSIFIER_MODELS`，与 `src/types.ts:88-130` 的 `KnownProvider` 一致），全部模型条目 **1,705 个 = 1,608 chat + 59 image + 38 classifier**（1.3.0 pinned catalog 实测，见第 8 节）。很多 provider 只是同一 API 实现 + 不同 baseUrl/env key，Kotlin 侧应按 API 实现数（10）而非 provider 数（42）估算协议成本。
3. API 协议成本极不均匀：openai-completions（1,744 行）、openai-codex-responses（1,724）、anthropic-messages（1,690）、bedrock-converse-stream（1,428）、mistral-conversations（952）是重头；openai-responses 由 459 + shared 887 行组成；google 两个 API 共享 517 行转换层；pi-messages 448 行；azure 261 + 配置 107 行。
4. 认证与模型数据是隐藏的主工程：9 个 OAuth flow（3,189 行 OAuth 目录）、凭据锁/刷新语义（`src/auth/resolve.ts`）、模型目录生成链（`scripts/generate-models.ts` 3,598 行 + `scripts/model-data.ts` 336 行）都不小。模型数据在源码 checkout 里被 gitignore（`packages/ai/src/providers/data/`），必须从 pi.dev 的 pinned catalog 或已安装 npm 构建产物获取，方案与实测见第 8 节。
5. Kotlin MUST 范围建议：类型模型 + 事件流 + 模型注册/认证解析 + 3 个主力 API（openai-completions、anthropic-messages、openai-responses）+ SSE/partial-JSON/retry/abort/usage-cost；预计 8,000-11,000 行 Kotlin。全量 parity（42 provider、9 OAuth、image/classifier、bedrock/codex/mistral）约 15,000-19,000 行。

---

## 1. 包职责与边界

pi-ai 是"一次 LLM 请求"的统一层（README 首段，`packages/ai/README.md:3`）：provider 目录、认证解析、请求发送与流式事件归一化、token/cost 统计、三种模型操作（chat/image/classifier）。它不包含 agent loop、工具执行、会话持久化、UI —— 这些在 `packages/agent`、`packages/coding-agent`。

上游用法三层：
- 新 API：`createModels()`/`builtinModels()` → `Models.getModel()` → `Models.stream()/complete()`（`src/models.ts:258-367,1028-1030`，`src/providers/all.ts:184-190`）。
- 旧全局 API：`@earendil-works/pi-ai/compat` 的 `stream/complete/streamSimple/completeSimple`，内部用 `builtinModels()` 和 env key 兜底，保持旧调用方兼容（`src/compat.ts:217-302`）。
- 直接用 API 模块：`@earendil-works/pi-ai/api/<name>` 的 `stream/streamSimple`（`package.json` exports 的 `./api/*`）。

下界：API 实现只接受已归一化的 `TranscriptContext`（`src/types.ts:791-794`），系统提示和工具都在首条 system message 里，`Models` 负责 `normalizeContext()`（`src/utils/transcript.ts:30-34`）。上界：`src/coding-agent` 的 `remote-catalog-provider.ts` 在 Models 之上做 pi.dev 目录叠加（`packages/coding-agent/src/core/remote-catalog-provider.ts:14,81`），说明 pi-ai 本身不负责远程模型目录刷新。

---

## 2. 规模、入口与依赖

### 2.1 目录规模（`wc -l` 实测）

| 目录 | 文件数 | 行数 | 主要内容 |
|---|---|---|---|
| `src` 根 | 18 | 3,820 | `types.ts` 904、`models.ts` 1,299、`compat.ts` 302、`models.generated.ts` 309、`model-catalog.ts` 81、`models-store.ts` 46、`cli.ts`、`index.ts`、`oauth.ts` 等 |
| `src/api` | 43 | 13,486 | 10 个 chat API + image/classifier + 15 个 `.lazy.ts` 包装 |
| `src/providers` | 95 | 3,827 | 42 个 provider 工厂 + 42 个 `.models.ts` 生成 shard + `all.ts`、`faux.ts`、cloudflare/radius/openrouter 辅助 |
| `src/auth` | 18 | 3,832 | 凭据/解析/登录 + 13 个 OAuth 文件（3,189 行） |
| `src/utils` | 26 | 2,699 | 事件流、transcript、retry、JSON、overflow、identity、proxy 等 |
| `src/compat` | 1 | 45 | 扩展 OAuth 类型 |
| `test` | 177 | 44,744 | 单元 + 真实 provider e2e |

### 2.2 入口与导出（`packages/ai/package.json` exports）

主入口 `dist/index.js` 只导出"core、无副作用"的部分：类型、`Models`、auth 基础类型、event-stream、retry/overflow/json-parse/transcript/validation、`PI_AI_VERSION`，以及 `providers/faux`（`src/index.ts:4-49`）。按需加载的子入口：
`./models`、`./compat`、`./providers/*`（provider 工厂与 `all.ts`）、`./api/*`（API 实现）、`./utils/*`、`./oauth`（仅类型）、`./bedrock-provider`、`./bun-oauth`。
`index.ts` **不**导出生成的目录/API 注册表/OAuth 实现，这是为浏览器/打包体积考虑；Kotlin 不需要照搬这种裁剪，但可以借鉴"core 类型独立、provider 与 API 实现分开"的边界。

### 2.3 依赖（`packages/ai/package.json` dependencies）

| 依赖 | 版本 | 用途 | Kotlin 替代 |
|---|---|---|---|
| `openai` | 7.19.0 | OpenAI Chat Completions / Responses / Azure 客户端 | OkHttp + 手写 JSON（或 openai-kotlin，但建议手写以控制 SSE/重试） |
| `@anthropic-ai/sdk` | 0.129.0 | Anthropic Messages + beta headers | OkHttp/CIO + 手写 SSE |
| `@google/genai` | 2.21.0 | Gemini / Vertex 客户端 | REST + 手写 SSE（`streamGenerateContent`） |
| `@aws-sdk/client-bedrock-runtime` | 3.1127.0 | Bedrock ConverseStream + SigV4 | AWS SDK for Kotlin（bedrockruntime）或自写 SigV4 |
| `partial-json` | 0.1.7 | 流式工具参数解析 | 移植算法（见 types 文档 §5.4） |
| `typebox` | 1.3.27 | 工具参数 Schema + 校验 + 生成 | kotlinx.serialization JsonObject + JSON Schema 校验库 |
| `http-proxy-agent` / `https-proxy-agent` | 9.1.0 | 代理 | OkHttp `Proxy`/`ProxySelector` |
| `@earendil-works/pi-telemetry` | 1.3.0 | telemetry schema | 可 DEFER |

Node 要求 `>=22.19.0`（`package.json` engines）；源码中大量使用 `fetch`、`AbortSignal`、`ReadableStream`、`node:http`（OAuth 回调）、`node:crypto`（PKCE/UUID/hash）、`process.env`。

---

## 3. 公开 API 全貌

### 3.1 Models（运行时集合）

接口定义 `src/models.ts:258-367`，实现 `ModelsImpl`（`:400-1026`），`createModels()` 工厂（`:1028-1030`），`MutableModels` 增加 `setProvider/deleteProvider/clearProviders`（`:369-374`）。

| 类别 | 方法（摘要） | 语义要点 |
|---|---|---|
| Provider 读 | `getProviders/getProvider` | 按注册顺序 |
| 模型读（同步） | `getModels(provider?)`、`getModel`、`getModelsOfType(type, provider?)`、`getModelOfType`、`getAllModels` | 只读"最后一次已知"列表；provider `getModels()` 抛错视为空（`:442-484`） |
| 动态刷新 | `refresh({allowNetwork?,providers?,force?,signal?})` → `{aborted,errors}` | 并发刷新有 `refreshModels` 的 provider；代际号 + AbortController 防旧结果覆盖（`:498-625`） |
| 认证 | `checkAuth`、`getAvailable/getAvailableOfType/getAllAvailable`、`getAuth(providerId\|model)`、`login/logout` | getAuth 会刷新过期 OAuth 并返回 `{auth,env,source}`；错误码 `ModelsError`（`:664-841`） |
| chat | `stream`、`complete`、`streamSimple`、`completeSimple` | 先 `normalizeContext`，再 `applyAuth`，再交给 provider（`:901-948`） |
| deferred | `streamDeferred`、`fetchDeferred`、`cancelDeferred` | provider 可选能力（`:950-988`） |
| image/classifier | `generateImages`、`classify` | 永不 reject，错误也返回带 `stopReason:"error"` 的结果（`:990-1025`） |

`applyAuth` 的合并顺序（`src/models.ts:856-899`）：`getAuth` 结果 → 显式 options.apiKey 覆盖 → `auth.baseUrl` 覆盖 model.baseUrl → headers 合并（opencode session 头、auth 头、request 头）→ `transformHeaders` 最后 → env 合并 → identity 合并 → 其余 options 原样透传。Kotlin 必须按同序合并，否则多 provider header 行为会偏。

### 3.2 Provider（运行时单元）

`Provider` 接口 `src/models.ts:158-247`：`id/name/baseUrl?/headers?/auth/getModels()/getAllModels?()/refreshModels?(ctx)/filterModels?/filterAllModels?/stream/streamSimple/fetchDeferred?/cancelDeferred?/generateImages?/classify?`。
`createProvider()`（`:1077-1218`）：
- `api` 可以是单个 `ProviderStreams` 或按 `model.api` 分派的 map；map 中没有对应实现时返回错误流（`:1108-1119`）。
- `models` 是静态基线，`fetchModels` 提供动态 overlay；同名同类型覆盖（`:1092-1105`）。
- `refreshModels` 内部实现"先恢复 persisted、再网络刷新、通过 `context.publish()` 事务化持久化并更新内存"（`:1129-1156`）。
- `images`/`classifiers` 也是按 `model.api` 分派的 map。
- 空实现会被拒绝（`:1088-1090`）。

`ModelsStore`：按 providerId 持久化 `{models, lastModified?, checkedAt?, etag?}`（`src/models-store.ts:3-26`），默认 `InMemoryModelsStore`（`:28-46`）。`Models.refresh` 用代际 + publication 链保证事务顺序（`src/models.ts:516-563`）。

### 3.3 流式入口与事件

API 模块统一导出 `stream(model, context, options?)` 与 `streamSimple(model, context, options?)`，返回 `AssistantMessageEventStream`（`src/types.ts:317-330`）。`streamSimple` 用 `buildBaseOptions` 把 simple 选项转成请求选项：clamp `maxTokens`、解析 sampling、映射 reasoning 到各 API 专属字段（`src/api/simple-options.ts:36-66`）。
`AssistantMessageEventStream`（`src/utils/event-stream.ts:97-130`）是 AsyncIterable + `result()` Promise，终止事件时给最终消息补 `durationMs`。`lazyStream` 让 `stream()` 同步返回、异步做 auth/模块加载，setup 失败转成 `error` 事件（`src/api/lazy.ts:60-86`）。
事件协议细节见 `docs/analysis/pi-ai-types.md` 第 4 节。

### 3.4 其他公开能力

- 图片：`generateImages(model, context, options)` 经过全局 images API 注册表（`src/images.ts:19-26`，`src/images-api-registry.ts:38-53`）；`Model.generateImages()` 走 provider 分派。仅实现 `openrouter-images`。
- 分类器：`classify()`，4 个 API（TypeSafe System One、Cloudflare Workers AI System One、llama.cpp classify、OpenAI Decisions）。
- OAuth 工程：`Models.login(providerId, type, interaction, options)`（`src/models.ts:775-830`），凭据持久化和 OAuth refresh 由 Models/`CredentialStore` 负责；`src/oauth.ts` 仅类型兼容入口。
- CLI：`pi-ai login [provider]` / `pi-ai list`（`src/cli.ts:85-99`），把 OAuth 凭据写入当前目录 `auth.json`（`:20-38`）。
- Faux provider：测试用脚本化 provider（`src/providers/faux.ts`，727 行），支持队列响应、deferred、token 速率模拟；Kotlin 测试替身的重要参考。
- Telemetry：`AI_TELEMETRY_SCHEMA`（`src/telemetry.ts:63-120`），span 名 `pi.ai.request`/`pi.ai.poll`，可选。
- Session 资源清理：`registerSessionResourceCleanup`/`cleanupSessionResources`（`src/session-resources.ts`），codex WebSocket 用它做会话级清理（`src/api/openai-codex-responses.ts:956-975`）。

---

## 4. 架构分层与一次请求的数据流

分层（自下而上）：
1. `types.ts` / `providers/model-schema.ts` / `providers/compat-schema.ts`：纯类型与 schema。
2. `api/*.ts`：每个线上协议实现，只依赖类型、utils、SDK；不依赖 `models.ts` 的运行时（除 `calculateCost`/`clampThinkingLevel`）。
3. `providers/*.ts`：provider 工厂，组合 baseUrl/auth/models/api 实现。
4. `models.ts`：注册表、auth 解析、动态刷新、请求转发。
5. `compat.ts` / `index.ts` / `providers/all.ts`：兼容层与聚合入口。

一次 `Models.stream(model, context, options)`（`src/models.ts:901-919`）：
1. `normalizeContext(context)`：`systemPrompt`+`tools` 折叠成首条 `SystemMessage`（`src/utils/transcript.ts:10-34`）。
2. `requireChatProvider(model)`：查注册表，非 chat 模型直接抛 `ModelsError("provider")`。
3. `applyAuth()`：`getAuth` → 解析 api key/OAuth（OAuth 过期则加锁刷新）→ 覆盖 baseUrl/headers/env/identity（`src/models.ts:856-899`）。
4. `provider.stream(requestModel, transcript, requestOptions)`（`Model` 可能是带 auth.baseUrl 的副本）。
5. API 实现：`resolveTranscript`（按是否支持 mid-convo system 折叠）→ 转换消息/工具 → 构造请求体 → `onPayload` → HTTP（SDK 或 fetch）→ `onResponse` → `start` 事件 → 逐 SSE 事件归一化 → `done`/`error`。
6. `Models.complete()` = `stream().result()`；`lazyStream` 负责把 setup 异常编码成错误事件。

横切职责：
- 重试：每个 API 的**首个 HTTP 请求**由 `retryProviderRequest` 包裹（`src/utils/provider-retry.ts:107-128`）；SDK 自身 retries 固定为 0（如 `src/api/openai-completions.ts:371-383`），保证退避可被 AbortSignal 打断。
- 错误：SDK 错误统一经 `normalizeProviderError`/`formatProviderError` 转文本（`src/utils/error-body.ts:38-135`），上层用文本模式识别重试/溢出（`src/utils/retry.ts:7-121`、`src/utils/overflow.ts:38-84`）。Kotlin 必须**生成同样的错误文本**，否则上层的重试/溢出/压缩逻辑失效。
- cost：流结束或 usage 更新时 `calculateCost(model, usage)`（`src/models.ts:1236-1256`）。
- duration：事件流终止消息补 `durationMs`（`src/utils/event-stream.ts:126-129`）。

---

## 5. 全部 Provider 清单

统计列来自 1.3.0 pinned catalog（第 8 节方案 B，`/tmp/models.all.json`）的实测计数；`chat/image/classifier` 为该 provider 目录条目数。baseUrl 为目录中出现的实际 endpoint（多值时列首值/说明）。

| provider | chat | image | classifier | API（目录中实际出现） | 认证 | baseUrl / 端点 |
|---|---|---|---|---|---|---|
| openrouter | 394 | 59 | 23 | anthropic-messages, openai-completions, openrouter-images, typesafe-system-one | api key `OPENROUTER_API_KEY` + OAuth | `https://openrouter.ai/api`（anthropic）/`/api/v1` |
| vercel-ai-gateway | 297 | 0 | 8 | anthropic-messages, typesafe-system-one | `AI_GATEWAY_API_KEY` | `https://ai-gateway.vercel.sh`、`/typesafe/v1` |
| amazon-bedrock | 193 | 0 | 0 | bedrock-converse-stream | 环境 AWS 凭据链（profile/access key/bearer/ECS/IRSA），无 OAuth | 每模型区域 endpoint，如 `https://bedrock-runtime.us-east-1.amazonaws.com` |
| opencode | 83 | 0 | 2 | anthropic-messages, google-generative-ai, openai-completions, openai-responses, typesafe-system-one | `OPENCODE_API_KEY` | `https://opencode.ai/inference`(+`/v1`) |
| huggingface | 78 | 0 | 0 | openai-completions | `HF_TOKEN` | `https://router.huggingface.co/v1` |
| cloudflare-ai-gateway | 57 | 0 | 0 | anthropic-messages, openai-responses, openai-completions | `CLOUDFLARE_API_KEY`+`CLOUDFLARE_ACCOUNT_ID`+`CLOUDFLARE_GATEWAY_ID` | `https://gateway.ai.cloudflare.com/v1/{account}/{gateway}/{anthropic\|openai\|compat}` |
| azure | 45 | 0 | 0 | openai-completions, azure-openai-responses | `AZURE_OPENAI_API_KEY` + endpoint env/参数 | 无目录 baseUrl：`AZURE_OPENAI_BASE_URL` / `..._RESOURCE_NAME` 解析，路径 `/openai/v1` |
| openai | 44 | 0 | 1 | openai-responses, openai-decisions | `OPENAI_API_KEY` + ChatGPT OAuth（subscription） | `https://api.openai.com/v1` |
| mistral | 40 | 0 | 0 | mistral-conversations | `MISTRAL_API_KEY` | `https://api.mistral.ai` |
| github-copilot | 36 | 0 | 0 | anthropic-messages, openai-completions, openai-responses | `COPILOT_GITHUB_TOKEN` 或 OAuth（subscription） | `https://api.individual.githubcopilot.com`；OAuth 时按 token 的 proxy-ep 动态推导 |
| opencode-go | 31 | 0 | 0 | anthropic-messages, google-generative-ai, openai-completions, openai-responses | `OPENCODE_API_KEY` | `https://opencode.ai/inference/go`(+`/v1`) |
| radius | 29 | 0 | 0 | pi-messages | `RADIUS_API_KEY` + OAuth；目录动态刷新 | `https://radius.pi.dev/v1`（网关 `/v1/config` 下发） |
| google | 23 | 0 | 0 | google-generative-ai | `GEMINI_API_KEY` | `https://generativelanguage.googleapis.com/v1beta` |
| baseten | 22 | 0 | 0 | openai-completions | `BASETEN_API_KEY` | `https://inference.baseten.co/v1` |
| fireworks | 22 | 0 | 0 | anthropic-messages, openai-completions | `FIREWORKS_API_KEY` | `https://api.fireworks.ai/inference`(+`/v1`) |
| cloudflare-workers-ai | 18 | 0 | 3 | openai-completions, cloudflare-workers-ai-system-one | `CLOUDFLARE_API_KEY`+account | `https://api.cloudflare.com/client/v4/accounts/{id}/ai`(+`/v1`) |
| qwen-token-plan | 20 | 0 | 0 | openai-completions | `QWEN_TOKEN_PLAN_API_KEY` | `https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1` |
| qwen-token-plan-cn | 20 | 0 | 0 | openai-completions | `QWEN_TOKEN_PLAN_CN_API_KEY` | `https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` |
| nvidia | 19 | 0 | 0 | openai-completions | `NVIDIA_API_KEY` | `https://integrate.api.nvidia.com/v1` |
| together | 17 | 0 | 0 | openai-completions | `TOGETHER_API_KEY` | `https://api.together.ai/v1` |
| anthropic | 17 | 0 | 0 | anthropic-messages | `ANTHROPIC_API_KEY`/`ANTHROPIC_OAUTH_TOKEN`/`ANTHROPIC_AUTH_TOKEN`（Bearer）+ OAuth + workload identity federation | `https://api.anthropic.com` |
| google-vertex | 14 | 0 | 0 | google-vertex | `GOOGLE_CLOUD_API_KEY` 或 ADC（`GOOGLE_APPLICATION_CREDENTIALS`）+ project/location | `https://{location}-aiplatform.googleapis.com` |
| qwen-token-plan-individual | 9 | 0 | 0 | openai-completions | `QWEN_TOKEN_PLAN_API_KEY` | 同 qwen-token-plan |
| openai-codex | 9 | 0 | 0 | openai-codex-responses | 仅 OAuth（ChatGPT Plus/Pro） | `https://chatgpt.com/backend-api`（`/codex/responses`） |
| zai | 7 | 0 | 0 | openai-completions | `ZAI_API_KEY` | `https://api.z.ai/api/coding/paas/v4` |
| groq | 7 | 0 | 0 | openai-completions | `GROQ_API_KEY` | `https://api.groq.com/openai/v1` |
| meta | 6 | 0 | 0 | openai-responses | `META_API_KEY` + OAuth（Muse subscription，device flow + key mint） | `https://api.meta.ai/v1` |
| xiaomi | 6 | 0 | 0 | openai-completions | `XIAOMI_API_KEY` | `https://api.xiaomimimo.com/v1` |
| kimi-coding | 4 | 0 | 0 | openai-completions | `KIMI_API_KEY` + OAuth（subscription） | `https://api.kimi.com/coding/v1` |
| moonshotai | 4 | 0 | 0 | openai-completions | `MOONSHOT_API_KEY` | `https://api.moonshot.ai/v1` |
| moonshotai-cn | 4 | 0 | 0 | openai-completions | `MOONSHOT_API_KEY` | `https://api.moonshot.cn/v1` |
| xai | 4 | 0 | 0 | openai-responses | `XAI_API_KEY` + OAuth（subscription） | `https://api.x.ai/v1` |
| xiaomi-token-plan-cn | 4 | 0 | 0 | openai-completions | `XIAOMI_TOKEN_PLAN_CN_API_KEY` | `https://token-plan-cn.xiaomimimo.com/v1` |
| xiaomi-token-plan-ams | 4 | 0 | 0 | openai-completions | `XIAOMI_TOKEN_PLAN_AMS_API_KEY` | `https://token-plan-ams.xiaomimimo.com/v1` |
| xiaomi-token-plan-sgp | 4 | 0 | 0 | openai-completions | `XIAOMI_TOKEN_PLAN_SGP_API_KEY` | `https://token-plan-sgp.xiaomimimo.com/v1` |
| zai-coding-cn | 4 | 0 | 0 | openai-completions | `ZAI_CODING_CN_API_KEY` | `https://open.bigmodel.cn/api/coding/paas/v4` |
| ant-ling | 3 | 0 | 0 | openai-completions | `ANT_LING_API_KEY` | `https://api.ant-ling.com/v1` |
| minimax | 3 | 0 | 0 | anthropic-messages | `MINIMAX_API_KEY` | `https://api.minimax.io/anthropic` |
| minimax-cn | 3 | 0 | 0 | anthropic-messages | `MINIMAX_CN_API_KEY` | `https://api.minimaxi.com/anthropic` |
| cerebras | 2 | 0 | 0 | openai-completions | `CEREBRAS_API_KEY` | `https://api.cerebras.ai/v1` |
| deepseek | 2 | 0 | 0 | openai-completions | `DEEPSEEK_API_KEY` | `https://api.deepseek.com` |
| typesafe | 0 | 0 | 1 | typesafe-system-one | `TYPESAFE_API_KEY` | `https://api.typesafe.ai/v1/` |

关键观察：
- 28 个 provider 至少有一个 chat 模型走 `openai-completions`（其中 21 个 provider 只走它），共用同一实现 + per-model `compat` 覆盖；这是 Kotlin 优先做 openai-completions 的最大理由。
- 7 个 provider 的 chat 模型跨多个 API：openrouter、opencode、opencode-go、github-copilot、cloudflare-ai-gateway、fireworks、azure；`createProvider` 的 api map 是必需能力。
- 3 个 provider 有动态目录/个性化：radius（`refreshModels` 拉 gateway `/v1/config`，`src/providers/radius.ts:35-84`）、openrouter（`personalizeOpenRouterCatalog` 调 `GET /models/user` 过滤可用模型并按账号限额改价，`src/providers/openrouter.ts:28-70`）、github-copilot（`filterModels` 用 credential 的 `availableModelIds`，`src/providers/github-copilot.ts:19-27`）。
- 3 个 provider 的 baseUrl 含占位符，发送前替换：cloudflare（`{CLOUDFLARE_ACCOUNT_ID}`/`{CLOUDFLARE_GATEWAY_ID}`，`src/providers/cloudflare-stream.ts:11-22`）、google-vertex（`{location}`，`src/api/google-vertex.ts:420-426`）、azure（无目录 baseUrl，按请求解析）。
- 认证方式：33 个 provider 只有 api key/env 或环境凭据；9 个带 OAuth（anthropic、openai、openai-codex、github-copilot、xai、kimi-coding、meta、radius、openrouter），对应 `src/auth/oauth/` 的 9 个 flow；bedrock 是 AWS 环境凭据链；vertex 是 API key 或 ADC。

Provider 工厂证据：`src/providers/<id>.ts` 每个 20-100 行；api key 映射表 `src/env-api-keys.ts:73-127`；OAuth 封装 `src/auth/helpers.ts:38-81`。

---

## 6. 各 API 实现协议详解

通用约定：所有 API 返回 `AssistantMessageEventStream`；首事件 `start`；错误统一转成 `{type:"error", reason:"error"|"aborted", error}`；usage 每次更新后 `calculateCost`；流结束前若 `stopReason==="pending"` 抛 "stream ended without ..."。

### 6.1 openai-completions（`src/api/openai-completions.ts`，1,744 行）

- 客户端：官方 `openai` SDK，`new OpenAI({apiKey, baseURL: model.baseUrl, defaultHeaders, fetch})`（`:760-804`）。头：identity UA + model.headers + copilot 动态头 + session affinity（`x-session-id` 或 `session_id`/`x-client-request-id`/`x-session-affinity`，`:770-795`）。
- 请求：`client.chat.completions.create(params, {maxRetries:0,...})`（`:376-383`）→ `POST {baseUrl}/chat/completions`。`params`（`:828-1015`）：`model, messages, stream:true, stream_options:{include_usage:true}, store:false（compat 支持时）, prompt_cache_key（api.openai.com 或 long retention）, prompt_cache_retention:"24h"（long + 支持时）, max_tokens 或 max_completion_tokens, temperature, tools, tool_choice, samplingParams 最后覆盖`。tools 为空但历史有工具调用时发送空数组（Anthropic-via-proxy 需要，`:866-869`）。
- thinking 参数按 `compat.thinkingFormat` 分支（`:886-983`）：`openai`→`reasoning_effort`；`openrouter`→`reasoning:{effort}`；`deepseek`→`thinking:{type:enabled/disabled}`+`reasoning_effort`；`zai`→`thinking:{type,clear_thinking:false}`；`qwen`→`enable_thinking`；`qwen-chat-template`→`chat_template_kwargs`；`chat-template`→可配置 kwargs；`baseten`→`chat_template_args`；`together`→`reasoning:{enabled}`；`string-thinking`→`thinking:string`；`ant-ling`→`reasoning:{effort}`。另有独立 `thinking_token_budget`/`thinking_budget`/`thinking_budget_tokens` 顶层预算（`:985-991`）。
- 流解析：遍历 SDK chunk（`:559-686`）。顺序：`responseId ||= chunk.id`；`responseModel`；usage；`finish_reason`→stop；再处理 delta：先 reasoning 字段（`reasoning_content`/`reasoning`/`reasoning_text`，只取第一个非空避免重复）→ `reasoning_details`（结构化回放，存进 thinkingSignature）→ `content` → `tool_calls`。文本/思考块惰性创建并推 `*_start`；工具调用按 `index` 或 `id` 映射到块，`function.arguments` 增量累积后 `parseStreamingJson` 更新 `arguments`，`toolcall_end` 时 `parseToolCallArguments` 定稿（`:500-557,688-709`）。
- tool 增量：`partialArgs` 累积字符串，`arguments=parseStreamingJson(partialArgs)` 实时可用；结束事件给最终 `ToolCall`。grammar 工具（OpenAI custom tools）走 `custom.input` + 括号补全算法（`:411-432`）。
- usage：`parseChunkUsage`（`:1529-1570`）见 types 文档 §10.3；usage 可能出现在 `chunk.usage` 或非标准的 `choice.usage`（Moonshot，`:576-580`）。
- 错误与收尾：`formatProviderError(normalizeProviderError(error))`，OpenRouter 的 `error.metadata.raw` 追加到消息（`:710-733`）。
- compat：`detectCompat` 按 provider/baseUrl 探测 28 个字段的默认值（`:1603-1700`）；`model.compat` 逐字段覆盖（`:1706-1744`）。这是 openai-compatible 生态的全部适配开关，Kotlin 必须原样支持，否则小众 provider 行为不一致。
- prompt cache：`resolveCacheRetention`（默认 short）、`getCompatCacheControl`/`applyAnthropicCacheControl`（OpenRouter 的 `anthropic/*` 模型使用 Anthropic 风格 `cache_control`，`:1083-1197`）。

### 6.2 anthropic-messages（`src/api/anthropic-messages.ts`，1,690 行）

- 客户端：`@anthropic-ai/sdk`，子类 `PiAnthropic` 禁用 SDK 默认凭据链（`:335-339`）。三种模式（`:1015-1114`）：GitHub Copilot（Bearer）、OAuth token（`sk-ant-oat` 前缀，Bearer + Claude Code UA/`x-app:cli`）、API key/header-owned/federation。federation 会缓存 client 共享 token（`:372-377`）。
- 请求：`client.beta.messages.create(params, {maxRetries:0})`（`:654-662`）→ `POST {baseUrl}/v1/messages`。`params`（`:1160-1332`）：`model, messages, max_tokens（默认 model.maxTokens）, stream:true, system, temperature（thinking 开启时禁用）, tools, thinking, output_config, metadata, tool_choice, fallbacks`。
- beta 头：以 SDK `betas` 数组传（`:1116-1158`）：OAuth 必带 `claude-code-20250219`,`oauth-2025-04-20`；fine-grained tool streaming；interleaved thinking；server-side fallback；mid-convo output config；inline tools。用户可通过 `anthropic-beta` 头完全覆盖，`null` 清空。
- thinking：`thinkingDisabled`→`{type:"disabled"}`；managed effort→`{type:"adaptive",display,block_binding:{prefix_mismatch_behavior:"drop_block"}}`+`output_config.effort`；adaptive→`{type:"adaptive",display}`；旧模型→`{type:"enabled",budget_tokens: options||1024, display}`（`:1278-1309`）。`mapThinkingLevelToEffort` 把 minimal/low→low，medium→medium，high→high，xhigh/max 用 `thinkingLevelMap`（`:941-959`）。
- SSE 解码：手写解析器（`:379-569`），支持 CRLF/LF、event+多行 data、注释忽略；要求 `message_start` 与 `message_stop` 成对，否则抛 "ended before message_stop"。
- 事件序列（`:696-892`）：`message_start`（id/model/usage 初始值/input_transformations）→ `content_block_start`（text/thinking/redacted_thinking/tool_use/fallback）→ `content_block_delta`（`text_delta`/`thinking_delta`/`input_json_delta`/`signature_delta`）→ `content_block_stop`（发 `*_end`）→ `message_delta`（stop_reason + usage 增量）→ `message_stop`。工具参数：`input_json_delta.partial_json` 累积 + `parseStreamingJson`，`content_block_stop` 时 `parseToolCallArguments` 定稿。
- redacted thinking：`redacted_thinking` 块的 `data` 存 `thinkingSignature`，`thinking="[Reasoning redacted]"`、`redacted=true`（`:746-755`）。
- usage：`message_start` 与 `message_delta` 双重更新，`cacheWrite1h` 来自 `cache_creation.ephemeral_1h_input_tokens`（Vercel 网关也会在 delta 里给，`:874-880`）；reasoning 来自 `output_tokens_details.thinking_tokens`。`responseModel` 与请求不同时会按 `compat.allowedFallbackModels` 换 cost（`:704-710`）。
- strict tools 降级：请求被 400 拒绝（grammar 过大/ strict 工具过多 / union schema 过多）时关闭 prefer-strict 工具重试一次并写 diagnostic（`:664-689`）。
- 消息转换：`transformMessages` 后按角色转（`:1353-1558`）；连续 toolResult 合并进一个 user 消息（z.ai Anthropic 端点要求）；错误 toolResult 的图片拆到后面；`cache_control` 加到 system 最后一个块、最后一个工具、最后一条 user/system 消息（`:1529-1555`）。
- tool schema：Anthropic strict 模式有不支持关键字/格式黑名单（`:1589-1620`），`input_schema` 对 strict 工具会同时输出完整 schema + legacy `{type,properties,required}`（`:1640-1651`）。OAuth 模式下工具名转 Claude Code 规范大小写别名（`:95-133,1654`）。
- stop reason 映射见 types 文档 §10.4。

### 6.3 openai-responses（`src/api/openai-responses.ts` 459 行 + `openai-responses-shared.ts` 887 行）

- 请求：`client.responses.create(params, {maxRetries:0})`（`:220-227`）→ `POST {baseUrl}/responses`。`params`（`:345-429`）：`model, input, stream:true, store:false, prompt_cache_key, prompt_cache_retention, prompt_cache_options, max_output_tokens(>=16), temperature, service_tier, tools, tool_choice, reasoning:{effort,summary}, include:["reasoning.encrypted_content"], samplingParams`。
- 输入转换（shared `:191-432`）：system→`developer`/`system` 文本；assistant 文本→`{type:"message",role:"assistant",content:[{type:"output_text",text}],status:"completed",id,phase?}`；thinking→反序列化 `thinkingSignature` 成 reasoning item；toolCall→`function_call`（id 可缺省，call_id 必填，namespace 仅同模型回放）或 `custom_tool_call`（grammar）；toolResult→`function_call_output`/`custom_tool_call_output`。工具变更（`additions`/`changes`）支持 `tool_search_call/output`、`additional_tools`、`configuration_update`（`:231-273`）。
- 事件处理（shared `:511-855`）：`response.created`→responseId；`response.output_item.added` 建块（reasoning/message/function_call/custom_tool_call）；`response.reasoning_summary_text.delta`、`response.reasoning_text.delta`、`response.output_text.delta`、`response.refusal.delta`；`response.function_call_arguments.delta/done`；`response.custom_tool_call_input.delta/done`；`response.output_item.done` 定稿块（thinking 存整个 item JSON 到 `thinkingSignature`；文本存 `textSignature`=TextSignatureV1；工具定稿参数）；`response.completed/incomplete` 终态；`error`/`response.failed` 抛错。流结束没有终态事件抛 "ended before a terminal response event"；`toolUse` 但仍有未完成工具调用（`partialJson`/`customInput` 未删）也抛错（`:838-854`）。
- usage/cost：`input_tokens - cached - cache_write`，`output_tokens`，`total_tokens`，reasoning_tokens（`:630-655`）；service tier 系数乘到 cost（`:431-459`）。
- ChatGPT 登录 token 会禁用部分字段（prompt_cache_retention/options、max_output_tokens、temperature 等，`:367-385`），并对订阅限额错误追加提示（`:263-271`）。
- HTTP 头除 identity 外还有 session affinity（`session_id`/`x-client-request-id`/`x-session-id`，`:320-329`）。

### 6.4 azure-openai-responses（`src/api/azure-openai-responses.ts` 261 行 + `azure-openai-config.ts` 107 行）

- 客户端：`new AzureOpenAI({apiKey, apiVersion, baseURL})`（`:160-179`）。
- endpoint 解析（`azure-openai-config.ts:16-107`）：优先 options `azureBaseUrl`，其次 env `AZURE_OPENAI_BASE_URL`；否则由 `AZURE_OPENAI_RESOURCE_NAME` 或 options 构造 `https://<resource>.openai.azure.com/openai/v1`；model.baseUrl 作为后备。Azure 主机路径规范化为 `/openai/v1`（`:44-64`）。
- deployment：`azureDeploymentName` > env `AZURE_OPENAI_DEPLOYMENT_NAME_MAP`（`model=deployment,...`）> model.id；发送前通过 `onPayload` 改写 `model` 字段（`src/providers/azure.ts:27-37`）。api-version 默认 `v1`。
- 请求/流处理复用 `openai-responses-shared`（`:179+`）。azure provider 的 `openai-completions` 通道也做同样的 baseUrl/deployment 解析（`src/providers/azure.ts:40-55`）。
- 目录里 azure 同时有 `azure-openai-responses` 与 `openai-completions` 模型；Kotlin 需要按 model.api 分派。

### 6.5 openai-codex-responses（`src/api/openai-codex-responses.ts`，1,724 行）

- 仅 OAuth（ChatGPT Plus/Pro）。请求 URL：`resolveCodexUrl` 把 baseUrl 规范成 `{base}/codex/responses`（`:663-669`）；WebSocket 版本 `wss://.../codex/responses`（`:671-676`）。
- 传输：默认 `auto`，尝试 WebSocket，失败/Session 标记回退 SSE；`transport` 可强制 `sse`/`websocket`/`websocket-cached`（`:290-330`）。SSE 请求体可 zstd 压缩（level 3，仅 Node，`:62-64,204-237`）。
- 请求体（`:542-622`）：`model, store:false, stream:true, instructions（首条 system 文本，缺省 "You are a helpful assistant."), input, text:{verbosity}, include:["reasoning.encrypted_content"], prompt_cache_key, tool_choice:"auto", parallel_tool_calls:true, tools, reasoning:{effort,summary}`。system prompt 不放进 input（`includeSystemPrompt:false`）。
- 头（`:1649-1724`）：`Authorization: Bearer <access>`、`chatgpt-account-id`（从 access JWT 的 `https://api.openai.com/auth` claim 取 `chatgpt_account_id`）、`OpenAI-Beta: responses=experimental`（SSE）/`responses=websockets`（WS）、`originator: pi`、session id 头；可删除 `OpenAI-Beta` 做兼容。
- 事件：SSE 事件先经 `mapCodexEvents` 映射为 Responses 风格事件，再交给共享 `processResponsesStream`（`:682-702`）；WS 走 `processWebSocketStream`（`:1515+`），有会话缓存 `previous_response_id`，连接上限错误码 `websocket_connection_limit_reached` 等（`:56-78`）。
- 重试：自有 `DEFAULT_MAX_RETRIES=0`、`getRetryAfterDelayMs`、连接错误与限流分类（`:123-203`）。
- 这是 Kotlin DEFER 级：协议依赖 OpenAI Codex 私有行为、WebSocket、zstd。

### 6.6 google-generative-ai（`src/api/google-generative-ai.ts` 472 + `google-shared.ts` 517）

- 客户端：`new GoogleGenAI({apiKey, httpOptions:{baseUrl,apiVersion:"",headers}})`（`:348-368`）；不支持自定义 fetch（`:87-89`）。
- 请求：`client.models.generateContentStream(params)`（`:100`）→ `POST {baseUrl}/models/{id}:streamGenerateContent?alt=sse`。`params`（`:370-430`）：`model, contents, config:{temperature,maxOutputTokens,systemInstruction,tools:[{functionDeclarations}],toolConfig:{functionCallingConfig:{mode}},thinkingConfig:{includeThoughts,thinkingLevel|thinkingBudget}}`。严格工具采样（Gemini 3+）时 mode 用 `VALIDATED`（`google-shared.ts:406-438`）。
- 消息转换（`google-shared.ts:193-345`）：user→`{role:"user",parts:[{text}|{inlineData}]}`；assistant→`{role:"model",parts}`（text/thinking/toolCall）；toolResult→`{role:"user",parts:[{functionResponse:{name,response:{output|error},parts?,id?}}]}`，同轮多个结果合并到一条 user；Gemini<3 或 Claude 走独立图片消息。thinking 只在同 provider+model 时以 `{thought:true,text,thoughtSignature}` 回放，跨模型降级为 text；thoughtSignature 必须是 base64 且仅同 provider/model 保留（`:148-162`）。
- 流解析（`:106-252`）：按 candidate.parts 顺序处理；`part.thought===true` 为 thinking（`google-shared.ts:130-132`），否则 text；同一时刻只有一个 currentBlock，切换类型时先发 `*_end` 再 `*_start`；`thoughtSignature` 用 `retainThoughtSignature` 保留最后一次非空值；`functionCall` 直接给完整参数（无增量），生成/复用一个唯一 toolCall id，并立即发 `toolcall_start/delta/end` 三连（`:175-220`）；`finishReason`→stop；`usageMetadata`→usage。
- usage（`:232-251`）：`input=promptTokenCount-cachedContentTokenCount`，`output=candidatesTokenCount+thoughtsTokenCount`，`cacheRead=cachedContentTokenCount`，total 用 `totalTokenCount`。
- 重试：`retryGoogleRequest` 给没有 headers 的 SDK ApiError 补 `headers=undefined` 后复用统一重试（`google-shared.ts:487-517`）。
- thinking 等级：Gemini 3/4、Gemma 4 使用离散 `thinkingLevel`（MINIMAL/LOW/MEDIUM/HIGH），其余用 `thinkingBudget`；2.5 系列有硬编码预算表（`src/api/google-generative-ai.ts:432-471`）；关闭思考用 `thinkingBudget:0` 或 clamp 后的 level（`google-shared.ts:104-113`）。

### 6.7 google-vertex（`src/api/google-vertex.ts`，567 行）

- 复用 `google-shared` 的消息/工具/stop 转换，差异在客户端与认证：`new GoogleGenAI({vertexai:true, project, location, apiVersion:"v1", googleAuthOptions?})`（`:364-381`）。
- API key 模式与 ADC 模式：`google-vertex` provider 的 auth resolver 支持 api-key / ADC / service-account 三选一（`src/providers/google-vertex.ts:14-92`）；`GOOGLE_APPLICATION_CREDENTIALS` 作为 keyFilename（`:437-440`）。
- project/location 必填，来源 options 或 `GOOGLE_CLOUD_PROJECT`/`GCLOUD_PROJECT`、`GOOGLE_CLOUD_LOCATION`（`:454-473`）。
- 自定义 baseUrl 中若含 `{location}` 则不作为覆盖；含版本号的 baseUrl 会清空 apiVersion（`:397-435`）。

### 6.8 mistral-conversations（`src/api/mistral-conversations.ts`，952 行）

- 手工 HTTP：`POST {baseUrl}/v1/chat/completions`，`Accept: text/event-stream`，`Authorization: Bearer`，`x-affinity`（sessionId，命中 prompt cache 时（`:298-372`）。
- 请求体构造后统一 camelCase→snake_case 重映射：`topP→top_p, maxTokens→max_tokens, randomSeed→random_seed, responseFormat→response_format` 等（`:386-420`）。图片只支持 `imageUrl`（data URL 或 http），不支持时降级为文本（`:808-923`）。
- SSE：手写事件边界识别（支持 `\r\n\r\n`/`\n\n` 等），`data:` 行拼接，`[DONE]` 结束（`:505-525`）。
- 流解析（`:576-775`）：`delta.content` 可为字符串或数组（文本/thinking chunk）；thinking 只在类型切换时开块；tool_calls 按 `index` 映射块，`function.arguments` 字符串增量累积 + partial 解析，结束时定稿。工具 id 必须是 9 位字母数字，`createMistralToolCallIdNormalizer` 用短哈希派生并做双向唯一映射（`:238-268`）。
- usage：`prompt_tokens/completion_tokens/total_tokens` + 6 种 cached 字段变体（`:555-574,616-628`）。
- stop reason 见 types 文档 §10.4。
- `streamSimple`：有 thinkingLevelMap 的模型用 `reasoning_effort`，否则用 `promptMode:"reasoning"`（`:187-216`）。

### 6.9 bedrock-converse-stream（`src/api/bedrock-converse-stream.ts`，1,428 行）

- 客户端：`@aws-sdk/client-bedrock-runtime` 的 `ConverseStreamCommand`（`:288-290`）。认证：SigV4（AWS profile/access key/ECS/IRSA）或 bearer token（`AWS_BEARER_TOKEN_BEDROCK`，`:184-245`）；`AWS_BEDROCK_SKIP_AUTH=1` 使用 dummy 凭据（代理场景，`:209-215`）；region 解析顺序 ARN 内嵌 > 显式 options > env > 默认链（`:193-240`）；代理通过 NodeHttpHandler + Http(s)ProxyAgent（`:222-234`）。
- 请求（`:268-288`）：`{modelId, messages, system, inferenceConfig:{maxTokens,temperature}, toolConfig, additionalModelRequestFields, requestMetadata}`。
- 事件（`:300-333`）：`messageStart`→`start`；`contentBlockStart`（只有 toolUse 会显式发，text 不预先发，`:594-615`）；`contentBlockDelta`（text/toolUse.input 字符串增量/reasoningContent.text/signature/redactedContent）；`contentBlockStop`→`*_end`+定稿；`messageStop`→stop；`metadata`→usage；流中异常项（internalServer/modelStreamError/validation/throttling/serviceUnavailable）直接 throw。
- redacted reasoning：`redactedContent`（Uint8Array）先缓冲，块结束时 base64 编码进 `thinkingSignature`，`thinking` 放占位符（`:667-704`）。
- 思考：Anthropic Claude 模型支持 budget/adaptive；`buildAdditionalModelRequestFields` 处理 thinking payload；`mapThinkingLevelToEffort` 支持原生 xhigh 的模型（`:778-848`）。prompt cache：`cachePoint`/`cache_control` 由 `buildSystemPrompt`/`convertMessages` 插入（`:849-925,926-945`）。
- usage：`inputTokens/outputTokens/cacheReadInputTokens/cacheWriteInputTokens/cacheDetails[ttl=1h]`（`:717-734`）。
- 自定义头：Smithy build middleware 在 SigV4 前注入，保留头（authorization/host/x-amz-*）跳过（`:468-506`）；响应头在 deserialize 阶段捕获（`:519-541`）。
- 错误：`formatBedrockError` 保留可重试/溢出识别所需的 "Throttling error:" 等前缀（`:373-412`）。

### 6.10 pi-messages（`src/api/pi-messages.ts`，448 行）

- 这是 pi 自己的网关协议（radius 和测试用）。`POST {baseUrl}/messages`，Bearer key，body `{model, context, options:{temperature,maxTokens,reasoning,cacheRetention,sessionId,toolChoice}}`（`:356-436`）。
- 响应是 SSE，但数据本身已经是 pi 事件协议：`start/text_*/thinking_*/toolcall_*/done/error`，camelCase 字段，`toolcall_end` 直接给完整 `ToolCall`（`PiMessagesEvent`，`:55-96`）。`createEventConverter` 把这些事件累积成 `AssistantMessage` 并转发（`:181-275`）。
- `done`/`error` 携带 usage/responseId/providerThinkingLevel/rewrite 诊断（`:196-218`）；非 2xx 时解析 `{error:{message,code,details}}` 并生成 `PiMessagesResponseError`（`:99-158`）。
- 这是 Kotlin SHOULD/DEFER 级：若目标不接 radius 网关，可以先不做。

### 6.11 图片 API

仅 `openrouter-images`（`src/api/openrouter-images.ts`，200 行）：OpenAI chat completions 非流式，body `{model, messages:[{role:"user",content:[text|image_url]}], stream:false, modalities:["image"] 或 ["image","text"]}`（`:140-167`）；响应取 `choices[0].message.content` 文本与 `message.images[].image_url` data URL，解析 base64 进 `ImageContent`（`:92-110`）；usage 同 OpenAI chat 字段并定价（`:169-199`）。注册表 + 懒加载在 `src/images-api-registry.ts`、`src/providers/images/register-builtins.ts`。

### 6.12 分类器 API

- `typesafe-system-one`：`POST {baseUrl}/systemone`，body `{model, state, questions}`，`bool` 问题在 wire 上叫 `noul`，输出 `{answers, usage}`（`src/api/system-one-shared.ts:100-135`，`src/api/typesafe-system-one.ts:15-22`）。OpenRouter 与 Vercel 网关也用这个 API，只是 base URL 不同。
- `openai-decisions`：`POST {baseUrl}/decisions`，body `{model, input, questions}`，question type 映射 choice/score/predicate，图片以 `input_image` 附带，504 不重试（`src/api/openai-decisions.ts:19-27,161-197`）。
- `cloudflare-workers-ai-system-one`：Workers AI REST `run` 端点（48 行）。
- `llama-cpp-classify`：本地 llama.cpp 分类端点（461 行），DEFER。

### 6.13 横切模块

- `src/api/transform-messages.ts`（256 行）：所有 API 的公共消息预处理/回放规则（跨模型 thinking 丢弃、孤儿工具调用补结果、图片降级、错误图片拆分）。Kotlin 必须先移植这个，再写各 API 转换。
- `src/api/constrained-sampling.ts`（298 行）：strict/grammar 工具 schema 处理。
- `src/utils/json-parse.ts`（149 行）：JSON 修复 + partial 解析。
- `src/utils/provider-retry.ts`（128 行）：首请求重试策略；`src/utils/retry.ts`（271 行）：上层 agent 级重试与错误分类。
- `src/utils/overflow.ts`（193 行）：上下文溢出识别；`src/utils/estimate.ts`（117 行）：token 估算（3.5 chars/token，图片 4800 chars）。
- `src/utils/transcript.ts`（263 行）：system/tools replay、mid-convo 折叠、工具变更表达能力（`TranscriptToolSupport = none|additions|changes`）。

---

## 7. 认证体系

### 7.1 数据模型与解析顺序

凭据：`ApiKeyCredential {type:"api_key", key?, env?}` 或 `OAuthCredential {type:"oauth", refresh, access, expires(ms), ...扩展}`（`src/auth/types.ts:7-37`）。存储接口 `CredentialStore` 要求 `read/list/modify/delete`，`modify` 是唯一写路径且必须串行化（`:65-94`）；默认实现是内存（`src/auth/credential-store.ts:9-67`），持久化由宿主 app 注入（pi 的 coding-agent 用 auth.json）。

解析顺序（`src/auth/resolve.ts:46-93`）：
1. 显式 `overrides.apiKey`（若 provider 有 apiKey auth）。
2. 读存储凭据：OAuth → 若 5 分钟内到期则在 `modify` 锁内刷新（两层检查，`refreshStoredOAuthCredential :119-155`）；api_key → 走 apiKey.resolve。
3. 无存储凭据时走 ambient（env 变量、AWS profile、ADC 文件）。
4. 存储了但类型与 provider 支持的不匹配 → undefined（不静默回退 env）。

`AuthResult.source` 用于 UI 展示来源（如 `"stored credential"`、`"ANTHROPIC_API_KEY"`、`"OAuth"`）。
`Models.getAuth(model)` 还会把 model.headers 合并进 auth.headers（`src/models.ts:754-773`）。

### 7.2 API key 体系

- 标准实现 `envApiKeyAuth(name, envVars)`：存储 key 优先，否则按顺序取第一个非空 env（`src/auth/helpers.ts:9-36`）。
- 非标准实现：anthropic（auth token/bearer/OAuth token/federation，`src/providers/anthropic.ts:19-72`）、bedrock（AWS 凭据链）、vertex（API key/ADC/service-account，`src/providers/google-vertex.ts:14-92`）、cloudflare（key+account+gateway 三段式，`src/providers/cloudflare-auth.ts:13-103`）、azure（key + endpoint 参数）、github-copilot（token + 动态目录）。
- env 变量总表：`src/env-api-keys.ts:73-127`（含 anthropic 的三个 token、azure、vertex、bedrock 特判 `:160-192`）。Kotlin 的 env 解析应可注入（`AuthContext.env/fileExists`），方便桌面/Android/测试。

### 7.3 OAuth 架构

`OAuthAuth = { name, isSubscription?, loginLabel?, login(interaction, options?), refresh(credential, signal), toAuth(credential) }`（`src/auth/types.ts:221-245`）。
- `login` 完成交互并返回 `OAuthCredential`；`Models.login` 再把凭据写入 `CredentialStore`（`src/models.ts:775-830`）。
- `refresh` 在网络层交换新 token，由 `Models` 在锁内调用；`toAuth` 是纯函数，把有效凭据转成 `ModelAuth`（可带 per-credential baseUrl，如 GitHub Copilot）。
- 9 个 flow 的 URL/client id/回调/scope 见 `docs/analysis/pi-ai-types.md` §8.6，文件在 `src/auth/oauth/`：`anthropic.ts` 307 行、`openai-chatgpt.ts` 309、`openai-codex.ts` 438、`github-copilot.ts` 507、`kimi-coding.ts` 296、`radius.ts` 321、`xai.ts` 239、`meta.ts` 208、`openrouter.ts` 169；通用件 `device-code.ts` 98、`callback-server.ts` 183、`pkce.ts` 34、`load.ts` 80。
- 设备码流程支持 `authorization_pending`、`slow_down`（间隔 +5s 或服务端值）、`access_denied`、`expired_token`（`src/auth/oauth/device-code.ts:1-98`）。
- 本机回调服务器支持固定端口、端口被占时换端口（Anthropic 特别逻辑）、忽略杂请求、只接受第一个回调、错误页展示、取消/超时（`src/auth/oauth/callback-server.ts:52-183`）。
- OAuth 登录交互面：`AuthPrompt`/`AuthEvent`/`AuthInteraction`（`src/auth/types.ts:119-164`），Kotlin 可用 suspend + callback/Flow 实现。

### 7.4 Client identity、User-Agent 与代理

- `ClientIdentity`：`userAgent: short|detailed|{custom}`、`attribution`（OpenRouter `HTTP-Referer`/`X-OpenRouter-Title`/`X-OpenRouter-Categories`；NVIDIA `X-BILLING-INVOKE-ORIGIN`；OpenCode `x-opencode-client`）、`version`（`src/utils/identity.ts:11-143`）。
- detailed UA 形如 `pi/1.3.0 (linux; node/v24.x; x64)`；browser 下 `pi/<ver> (browser)`（`:74-82`）。
- OpenCode 会话头 `x-opencode-session` 由 `getOpenCodeSessionHeaders` 在 `Models.applyAuth` 注入（`src/utils/identity.ts:126-135`，`src/models.ts:880-885`），provider 包装层也会注入（`src/providers/opencode-headers.ts`）。
- 代理：`src/utils/node-http-proxy.ts`（161 行）解析 `HTTP_PROXY/HTTPS_PROXY/NO_PROXY` 并给 Bedrock 的 Smithy handler 用；其他 SDK 由宿主 fetch/agent 处理。Kotlin 用 OkHttp `ProxySelector` 即可。

---

## 8. 模型目录数据来源与 Kotlin 获取方案（已实测）

### 8.1 生产链

1. 数据抓取（`packages/ai/scripts/generate-models.ts`，3,598 行）：
   - `GET https://models.dev/api.json`（`:1814-1818`）：主 catalog，覆盖 anthropic/openai/google/openai-codex/amazon-bedrock/groq/cerebras/mistral/zai/deepseek/...；每个模型取 `tool_call===true`、`limit.context/output`、`cost`、`modalities`、`reasoning_options`。
   - `GET https://models.dev/models.json?type=decision`（`:2618-2619`）：classifier 模型。
   - `GET https://openrouter.ai/api/v1/models` 三次（默认、`?output_modalities=image`、`?output_modalities=decisions`）（`:1335-1363`）：构建 openrouter 的 chat/image/classifier 条目，含价格换算/推理等级映射（`scripts/openrouter-catalog.ts:30-105`）。
   - `GET https://ai-gateway.vercel.sh/models`（`AI_GATEWAY_MODELS_URL`，`:1380-1456`）：Vercel 目录；evaluation 类型转 classifier。
   - `GET https://integrate.api.nvidia.com/v1/models`（NVIDIA 模型 id 归一化，`:1300-1333`）。
   - Radius 网关 `GET {gateway}/v1/config`（`:1365-1378`，`src/providers/radius-config.ts:80-96`）。
   - 之后是大量硬编码修正（价格、模型别名、thinking 映射、compat 覆盖、模型下线、区域价格等），全文 3,598 行大部分在此。
2. 归一化与校验：每个模型必须形如 `{id,name,api,provider,baseUrl,reasoning,input,cost,contextWindow,maxTokens,...}`（`Model`）；校验/分组逻辑在 `scripts/model-data.ts:144-336`。
3. 数据落盘：`groupProviderModelData` 按 `type:id` 分组到 `<api>` 下，写 `src/providers/data/<provider>.json`（`scripts/model-data.ts:308-335`；写入流程 `scripts/generate-models.ts:3436-3550`）；同时生成 42 个 `providers/<provider>.models.ts` 与 `models.generated.ts`（`:3459-3540`）。
4. 发布：`npm run generate-model-catalog` 生成 `.artifacts/model-catalog/models.all.json` + `models.json` + `providers*.json`（`package.json:60-62`），由 `scripts/publish-model-catalog.mjs` 发布到 pi.dev 的 `models/v1/revisions/<sha256>/...` 存储（`scripts/model-catalog-protocol.ts:15-31`）。`models.all.json` 形如 `{"<provider>": [model,...]}`。
5. 离线/可复现构建：`nix/model-catalog.json` 只 pin 一个 revision（`nix/model-catalog.json:1-3`），`nix/package.nix:26-30` 从 `https://pi.dev/api/models/revisions/<rev>?types=chat,image,classifier` 下载并校验 sha256，然后 `node packages/ai/scripts/hydrate-model-catalog.ts <file>` 生成 `src/providers/data/`，再 `npm run build:offline`（`nix/package.nix:45-50`）。`hydrate-model-catalog.ts:37-86` 会把数组按 provider 重新分组、生成 `.manifest.json`（schemaVersion 6，固定 generatedAt=1970 保证可复现）。
6. 运行时覆盖：coding-agent 的 `remote-catalog-provider.ts` 对每个 provider 拉 `GET https://pi.dev/api/models/providers/<provider>?types=chat,image,classifier`（`:14,81-90`），用 `Last-Modified`/`ETag` 与本地 `generatedAt` 比较后持久化 overlay；每 4 小时检查（`:16`）。

### 8.2 已安装 npm 1.0.0 构建产物的形态（实测）

pi-coding-agent 安装目录：`npm package @earendil-works/pi-ai (installed via @earendil-works/pi-coding-agent)`，`package.json` 版本 **1.0.0**。
- `dist/providers/data/` 有 42 个 provider JSON + `.manifest.json`，总计 968 KB；`manifest` 为 `{"schemaVersion":6,"generatedAt":"2026-10-01T18:57:11.882Z","structureHash":"...","files":{"<provider>.json":"<sha256>",...}}`。
- 单个文件形态：`{"<api>":{"<type>:<id>":{...模型字段...}}}`，例如：
  `{"anthropic-messages":{"chat:claude-fable-5":{"id":"claude-fable-5","name":"Claude Fable 5","api":"anthropic-messages","provider":"anthropic","baseUrl":"https://api.anthropic.com","reasoning":true,"input":["text","image"],"cost":{"input":10,"output":50,"cacheRead":1,"cacheWrite":12.5},"contextWindow":1000000,"maxTokens":128000,"compat":{...},"type":"chat"}}}`
- 统计：1.0.0 共 1,604 条（1,532 chat + 57 image + 15 classifier），最大 provider 是 openrouter 399/57/9、vercel 252/0/2、bedrock 180。注意 1.0.0 比源码 1.3.0 旧：1.0.0 有 `azure-openai-responses.json`（无 `azure.json`）、`kimi-coding` 走 anthropic-messages，而 1.3.0 已改为 `azure` provider、kimi-coding 走 openai-completions；因此它是"形态参考 + 可用兜底快照"，不是 1.3.0 的准确数据。

### 8.3 1.3.0 权威数据：pinned catalog（推荐，已实测）

源码 checkout 的 `src/providers/data/` 被 `.gitignore` 忽略（仓库根 `.gitignore` 中 `packages/ai/src/providers/data/`），**本地源码树没有数据文件**，无法直接从源码目录读出模型数据。可行且最准确的方式是拉 pi.dev 的 pinned revision：

```bash
REV=sha256-ffa3e98626230f378f80200debb736c4a2238b5f2ba246a92cfc7d8e19604678   # nix/model-catalog.json
curl -sS -o /tmp/models.all.json \
  "https://pi.dev/api/models/revisions/$REV?types=chat,image,classifier"
sha256sum /tmp/models.all.json
# 实测：HTTP 200，931,299 bytes；
# ffa3e986... 与 pin 完全一致（2026-10-22 本机验证）
```

实测内容：
- 42 个 provider，与 `src/models.generated.ts` 的 provider 集合完全一致（无缺失/多余）。
- 1,705 条：1,608 chat + 59 image + 38 classifier。字段并集：`id,name,api,provider,baseUrl,reasoning,input,cost,contextWindow,maxTokens,inputLimits,type,thinkingLevelMap,compat,promptCache,headers,output,lab,enabled,providers`；其中 `lab/enabled/providers` 只出现在 radius 的 29 条（网关透传的额外字段，Kotlin 忽略即可）。
- 逐 provider 计数见第 5 节表格。
- 单 provider 运行时接口同样可用并返回数组：
  `curl "https://pi.dev/api/models/providers/anthropic?types=chat,image,classifier"` → HTTP 200，17 条数组。

### 8.4 从源码重新生成（已实测，适合需要最新/自定义 provider 时）

`generate-models.ts` 支持 `--json-only --json-output <dir>`，该模式下**不写** `src/providers/data`（`scripts/generate-models.ts:3436` 的 `if (!generatorOptions.jsonOnly)`），因此可以在只读源码树上运行：

```bash
cd /tmp
node packages/ai/scripts/generate-models.ts \
  --strict --json-only --json-output /tmp/model-catalog-gen
# 实测（2026-10-22，Node v24.14 原生 TS type stripping，无需 npm install）：
# EXIT 0；生成 models.all.json 932,434 bytes + models.json + providers/；
# 42 providers，1,714 条 = 1,600 chat + 63 image + 51 classifier
```

与 pinned 版本差异：pinned-only 62 条、live-only 71 条（模型目录随上游漂移，属正常）。需要联网访问 models.dev / openrouter.ai / ai-gateway.vercel.sh / NVIDIA NIM / radius.pi.dev；`--strict` 表示任一抓取失败即报错（生产构建使用 `--strict`）。
注意：`scripts/generate-models.ts` 里含大量人工修正，重新生成等价于接受当天的上游数据；如果 Kotlin 要保持与 pi 1.3.0 行为一致，应使用 8.3 的 pin，而不是最新数据。

### 8.5 Kotlin 侧建议方案

推荐组合（务实且可复现）：
1. **构建期**：用 8.3 的 pinned revision 拉 `models.all.json`，用一次性脚本转换为 Kotlin 资源 `src/main/resources/pi-models/models.json`（保持 provider→array 原结构，字段名不变）。记录 revision + sha256 到资源旁的 `catalog-meta.json`（或代码常量）。Gradle 任务可放在 `buildSrc`/单独脚本，默认走本地已下载文件，网络可选。
2. **运行期动态刷新（SHOULD）**：对每个 provider 调 `GET {PI_CATALOG_BASE}/api/models/providers/<id>?types=chat,image,classifier`，用 ETag/Last-Modified 做条件请求，落盘到 app cache；失败保留内置快照。这与 pi 的 coding-agent 行为一致（`packages/coding-agent/src/core/remote-catalog-provider.ts:81-90,120-190`），但注意 pi-ai 本包不做这层。
3. **provider 元数据不要从目录取**：`provider id/name/auth 方式/特殊包装（cloudflare 占位符、azure 部署、copilot 过滤、radius 动态、opencode session 头）` 必须写在 Kotlin 代码里；目录只提供 model 条目。
4. **数据校验**：加载时断言 `type` 合法、`id` 唯一（type 内）、`provider/api/baseUrl/cost` 必填、chat 有 `reasoning/contextWindow/maxTokens`、image 有 `output` 含 image；未知字段忽略。
5. **兜底**：允许用户通过配置文件追加/覆盖模型（对应 pi 的 models.json 自定义 provider 能力），Kotlin 可设计 `CustomProviderConfig`，复用同一 `Model` 校验。
6. **可选**：从已安装 1.0.0 dist 提取作为过渡快照（`dist/providers/data/*.json`，42 文件 968 KB），但存在版本偏差，不建议作为 1.3.0 基线。

---

## 9. 外部依赖与 Node 专有能力替代表

| pi-ai 依赖/能力 | 用法位置 | Kotlin 替代 | 备注 |
|---|---|---|---|
| `openai` SDK（含 AzureOpenAI） | openai-completions/responses/azure/images/codex | OkHttp + kotlinx.serialization 手写；或 openai-kotlin | 手写更可控，SDK 会隐藏 SSE/重试细节 |
| `@anthropic-ai/sdk` | anthropic-messages | OkHttp + 手写 SSE | pi 本身就是手写 SSE 解码（`anthropic-messages.ts:379-569`），可 1:1 移植 |
| `@google/genai` | google-generative-ai/vertex | REST + 手写 SSE | 需要实现 ADC token 刷新（vertex ADC 模式）或走 API key |
| `@aws-sdk/client-bedrock-runtime` | bedrock | AWS SDK for Kotlin（bedrockruntime 有 ConverseStream）或自写 SigV4 | SigV4 与事件流反序列化较重，建议用官方 SDK |
| `partial-json` | 流式工具参数 | 移植算法 | 纯逻辑，配 `repairJson` 一起移植 |
| `typebox` | 工具 schema/校验 | JsonObject + JSON Schema 校验库 | Kotlin 不需要 schema builder；测试可用 networknt 校验器 |
| `http(s)-proxy-agent` | Bedrock/Node 代理 | OkHttp Proxy/ProxySelector | 需要解析 PROXY/NO_PROXY |
| `AbortSignal` | 全链路取消 | 协程 Job + `ensureActive()`/`withTimeout` | 保存语义：abort 时最终消息 stopReason=aborted |
| `AsyncIterable`/`ReadableStream` | SSE | `Flow<AssistantMessageEvent>` + `Channel` | 注意背压 |
| `node:http` OAuth 回调 | 本机 OAuth | JDK `com.sun.net.httpserver.HttpServer` 或 Ktor server | 需要固定端口/端口回退/一次性回调 |
| `node:crypto`（PKCE、UUIDv7、短哈希、base64） | OAuth/utils | JVM `MessageDigest`/`SecureRandom`/`Base64` | UUIDv7 需自行实现（源码算法见 `src/utils/uuid.ts:8-45`） |
| `performance.now()` | durationMs | `System.nanoTime()` | |
| `process.env` | provider env | 可注入 `Environment` 接口 | Android/TV 等平台无 env，需配置文件/设置 |
| zstd（codex 请求压缩） | openai-codex | `zstd-jni` 或不做（发未压缩） | DEFER |
| Node `fetch` | mistral/pi-messages/classifier/OAuth | OkHttp/CIO | 需要可注入 fetch 的等价物（`options.fetch`） |

---

## 10. Kotlin 设计建议

### 10.1 模块/包结构

```
:pi-ai-core        // types、事件流、模型目录数据类、SSE、retry、partial-json、usage/cost
:pi-ai-auth        // CredentialStore、AuthContext、api-key 解析、OAuth flows、回调服务器
:pi-ai-api-openai     // openai-completions + compat
:pi-ai-api-anthropic
:pi-ai-api-openai-responses // 含 azure
:pi-ai-api-google
:pi-ai-api-mistral
:pi-ai-api-bedrock   // 可选
:pi-ai-api-gateway   // pi-messages/codex（可选）
:pi-ai-images / :pi-ai-classifiers // 可选
:pi-ai-testkit     // faux provider、SSE fixture、golden JSON
```
不建议按 provider 建模块；按 API 实现建模块，provider 是数据/工厂。

### 10.2 类型建模

- `Message`/`Content`/`AssistantMessageEvent` 用 sealed + `@JsonClassDiscriminator`；反序列化会话时 `ignoreUnknownKeys=true`、`classDiscriminator` 用 `role`/`type`。
- 内存类型保持 camelCase 字段名，`@SerialName` 仅在 wire DTO 使用；这样与 pi 会话文件互通。
- `Tool.parameters`/`arguments`/`details` 用 `JsonObject`；不要烘焙成数据类。
- `Model.compat` 建议两种方案二选一：a) 按 API 的 sealed；b) 一个全可空 `Compat` 数据类 + 分发。考虑到兼容字段 30+ 且会增长，b 更省事，但需要 `encodeDefaults=false`。
- 枚举用 `@Serializable` enum + `@JsonNames`/custom serializer 处理未知值（stop reason 未知时按 provider 规则转 error，而不是崩）。
- 时间统一 Long ms；cost 用 Double，序列化保留足够精度（与 pi 的 JSON 数值一致即可）。

### 10.3 流式 API 形态

建议：
- `fun stream(model, context, options): AssistantStream`，`AssistantStream` 暴露 `events: Flow<AssistantMessageEvent>` 与 `suspend fun result(): AssistantMessage`。
- 内部：`Channel<AssistantMessageEvent>` + `CompletableDeferred<AssistantMessage>`；终止事件写入后关闭 channel 并 complete deferred；`result()` 等待终止（`error` 时返回错误消息而非抛异常，与 pi 一致）。
- setup 异常（缺 key/模块加载失败）在 `stream()` 返回前无法抛（Kotlin 要返回对象），因此统一转成 `error` 事件；如果宿主需要"同步抛错"语义，再提供 `streamOrThrow()` 之类的变体，或让 `streamSimple` 在缺 auth 时直接抛。
- 取消：`AssistantStream.cancel()` 或传入 `CoroutineScope`/`Job`；取消时生成 `stopReason=aborted` 的最终消息（注意 pi 的语义：已产生内容也返回，`errorMessage` 一般为空）。
- `complete()` = `stream().result()`。

### 10.4 HTTP/SSE 层

- 统一 `HttpTransport` 接口：`execute(request): Response`（支持自定义 baseUrl、headers、timeout、代理、取消）；默认 OkHttp。这样 `options.fetch` 等价物可注入，便于测试。
- SSE 解析器单独实现（支持 CRLF/LF、多行 data、event 字段、注释、`[DONE]`、尾部无空行），供 anthropic/mistral/pi-messages/codex/google 复用。OpenAI 官方 SDK 的 SSE 行为可直接按 chunk 事件处理，不必复用。
- 超时拆分：连接/首字节超时 vs 流空闲超时（pi 的 `timeoutMs` 语义在各 API 不同：Mistral 只覆盖响应头，`src/api/mistral-conversations.ts:308-312`；SDK 的 timeout 覆盖整体）。Kotlin 侧建议提供 `connectTimeoutMs`/`requestTimeoutMs`/`idleTimeoutMs` 三个参数，默认值对齐 pi（SDK 默认 10 分钟，`src/types.ts:198-202`）。

### 10.5 认证层

- `CredentialStore`：per-provider `Mutex` + 原子写；`modify` 在锁内执行 refresh；auth.json 结构保持 pi 格式（第 8.1 节）。
- OAuth：先做 device-code 通用机 + callback server 通用机，再按 flow 填常量与 token 交换；把 client id/redirect/scope 集中为数据表，便于更新。
- `AuthInteraction` 用 Kotlin 接口 + suspend（prompt/notify）；桌面端可实现为控制台/GUI，Android 可实现为浏览器 + 自定义 scheme。
- env 解析：`AuthContext.env(name): String?`、`fileExists(path)` 可注入；桌面用 `System.getenv` + `~` 展开，Android 用配置文件。

### 10.6 错误、重试、usage

- 错误文本必须与 pi 一致（`normalizeProviderError` 逻辑），否则上层 `isRetryableAssistantError`/`isContextOverflow` 的文本识别会失效。Kotlin 应把"错误分类"做成结构化枚举 + 保留原文，供上层选择；不要只留 message。
- 重试策略：首请求 408/409/429/5xx，`x-should-retry` 优先，`retry-after(-ms)`，指数退避 + 抖动，60s 上限可配置；重试必须可取消。
- usage/cost：统一在收到 usage 时重新计算（provider 可能多次给 usage）；`cacheWrite1h` 的 2 倍输入价不要漏；OpenAI Responses 的 service tier 系数不要漏。
- `durationMs` 用单调时钟在终止消息上补；不要覆盖已有值。

### 10.7 模型目录

- 资源里只放 `models.all.json` 转换后的精简版（去掉 radius 的 `lab/enabled/providers` 也可以，但保留 compat/thinkingLevelMap/promptCache/inputLimits 等行为字段）。
- 启动时构建 `providerId -> Map<type, Map<id, Model>>` 索引；`getModels` 等查询 O(1)。
- 目录版本不匹配（新增 api 未实现）时：该模型跳过并记录 warning，不要整体失败（pi 的 `withKnownModelTypes` 也丢弃未知类型，`src/models.ts:132-142`）。
- 自定义 provider：允许配置文件声明 `{id, baseUrl, auth, api, models:[...]}`，走与内置相同的校验/注册路径（pi 的 `createProvider` 语义）。

### 10.8 测试策略

- **纯逻辑单测**：事件流顺序/终止、JSON 修复/partial、cost 分档、overflow 正则、transcript replay、tool id 规范化、thinking 跨模型丢弃、strict schema 转换。直接照 types 文档 §12 的 MUST 清单移植。
- **协议 fixture 测试**：用 MockWebServer（OkHttp）返回预录 SSE 文本，断言事件序列与最终 message。每个 API 至少覆盖：正常文本、thinking、工具调用增量、usage、错误（4xx/5xx）、中止。
- **HTTP 层测试**：代理、超时、重试、retry-after。
- **模型目录测试**：加载 pinned JSON 全量，校验 42 provider/1,705 条通过校验；JSON round-trip。
- **OAuth 测试**：device code 状态机、callback server 一次性/超时、PKCE、token refresh（用本地 mock server）。
- **端到端**（可选，需 key）：每个主力 API 一条 smoke。

---

## 11. 范围分级与工作量

### 11.1 MUST（首个可用版本）

| 项 | 说明 | 估算 Kotlin 行数 |
|---|---|---|
| 核心类型 + 序列化 | 第 3-5、7 节所有类型、事件、compat、options | 2,000-2,500 |
| 事件流 + lazy/setup 语义 | Channel/Flow/result/cancel/durationMs | 250-350 |
| transcript/transform | normalizeContext、system replay、tool replay、图片降级、孤儿工具调用 | 500-700 |
| SSE + partial JSON + repair | 通用解析器 + partial-json 移植 | 500-700 |
| retry/abort/错误规范化/overflow | provider-retry + retry + error-body + overflow | 600-800 |
| Models/Provider 注册表 | createModels/createProvider、getModels、auth apply、refresh（可选网络）、getAvailable | 800-1,000 |
| auth（api key + env + 凭据存储） | auth.json 兼容、CredentialStore、AuthContext | 500-700 |
| openai-completions | 含 compat 全字段、cache、thinking 分支 | 1,500-2,000 |
| anthropic-messages | 含 SSE、thinking、strict tools、cache | 1,500-1,800 |
| openai-responses（含 shared） | 含 tool/search/additional_tools 可后置 | 1,200-1,600 |
| 模型目录数据 + 加载校验 | 资源 + 索引 + 校验 + 转换脚本 | 300-500 + JSON 资源约 1 MB |
| usage/cost | calculateCost、各 API usage 映射 | 300-500 |
| 测试与测试替身 | faux provider、MockWebServer fixtures、单测 | 2,000-3,000 |
| **合计（不含测试）** | | **约 8,000-11,000** |

### 11.2 SHOULD（第二批）

`google-generative-ai`（900-1,200）、`google-vertex`（复用 google 转换，300-500）、`mistral-conversations`（600-800）、`azure`（200-300）、`bedrock-converse-stream`（1,200-1,600，含 SigV4/事件流）、`openrouter-images`（250-350）、classifiers（typesafe/openai-decisions 400-600）、OAuth flows（每个 150-400；先做 anthropic/openai-chatgpt/github-copilot）、动态模型刷新 + 个性化（radius/openrouter/copilot，300-500）、client identity/UA/归因（150-250）。

### 11.3 DEFER

- `openai-codex-responses` 的 WebSocket transport、zstd 压缩、session 缓存（SSE 版本可先做，约 700 行；WS 再加 600-900）。
- `pi-messages`/radius（若产品不接 radius 网关）。
- `llama-cpp-classify`、`cloudflare-workers-ai-system-one`、cloudflare AI binding。
- deferred-response（`fetchDeferred/cancelDeferred`）。
- telemetry、browser/Vite 打包适配、CLI。
- Nix hydration、pi.dev 发布链（Kotlin 只需消费 pinned catalog）。
- 图片 `inputLimits.resize` 的自动缩放（Kotlin 侧可用 ImageIO 实现，但非核心）。
- mid-conversation system messages / tool changes 的完整支持（先折叠，与 pi 对不支持模型的行为一致）。

### 11.4 里程碑

| 里程碑 | 内容 | 验收 |
|---|---|---|
| M0（1 周） | 模型目录数据落地 + 类型/序列化 + 事件流骨架 | pinned JSON 全量加载通过；事件流单测绿 |
| M1（1-2 周） | HTTP/SSE/retry/partial JSON + openai-completions + Models/Provider + api key 认证 | 对 mock provider 完成 text/thinking/tool/usage/error/abort 全事件序列；用真实 key 跑通至少一个 openai-compatible provider |
| M2（1 周） | anthropic-messages（含 SSE 手写解析、thinking、cache、strict tools 降级） | fixture 事件序列；真实 Anthropic key smoke |
| M3（1 周） | openai-responses + azure；usage/cost 对齐 | 与 pi 同请求对比 usage/cost；service tier 系数单测 |
| M4（1-2 周） | google/mistral；OAuth 基础设施 + 2-3 个 flow；模型动态刷新 | 登录/刷新测试；协议 fixture |
| M5（2 周） | bedrock、images/classifiers、codex SSE、剩余 OAuth | 按需 |

---

## 12. 风险与需要 lead 决策的问题

风险：
1. **协议漂移**：42 个 provider、10 个 API 会随上游变化；pinned catalog 也会漂移。必须把 catalog revision + 生成日期写进构建产物，测试用固定 fixture。
2. **认证/合规**：OAuth client id 是内置在开源代码里的（anthropic/openai/github 等），Kotlin 若复用这些 flow 需要确认使用条款与客户端身份（User-Agent/attribution 也可能影响封禁）。
3. **错误文本兼容**：上层的重试/溢出/压缩逻辑依赖错误文本正则；Kotlin 若改写文本会静默破坏行为。必须在 Kotlin 中保留 `isRetryableAssistantError`/`isContextOverflow` 等价逻辑并测试。
4. **Bedrock/Codex**：SigV4、事件流反序列化、WebSocket、zstd 是重活；建议用官方 AWS SDK for Kotlin，Codex 放最后。
5. **thinking 签名回放**：跨模型/跨 provider 的 thinking/签名必须按规则丢弃或降级，否则会出现"重放非法签名"400；这是最容易踩的坑（`transform-messages.ts`、各 API 的同样 provider+model 判断）。
6. **工具 id 规范化**：Responses 的 `callId|itemId`、Anthropic 64 字符限制、Mistral 9 位限制，如果不做，跨 provider handoff 会失败。
7. **模型数据规模**：全量 JSON 约 0.9-1 MB；Android 资源可接受，但要注意解析耗时（建议懒加载 + 索引）。
8. **测试成本**：真实 provider smoke 需要 key/额度；建议以 MockWebServer + 录制的 SSE fixture 为主。

需要 lead/产品决策：
- 目标平台：纯 JVM（桌面/服务端）还是 Android/KMP？Android 无 `System.getenv`、需要 WebView/Custom Tabs OAuth，且 Android 上 OkHttp 可用。
- 是否需要 OAuth（订阅制 provider）？若只做 API key，可省 3,000+ 行。
- 是否需要 image/classifier？若不做，可省 1,000+ 行。
- 是否要求与 pi 的 auth.json/models.json 双向兼容？若要求，凭据/模型字段命名必须完全一致。
- 模型数据的更新策略：内置快照（离线优先）还是运行时拉 pi.dev（网络依赖）？建议两者都有，默认快照。

---

## 13. 附录

### 13.1 关键文件规模（前 20）

| 文件 | 行数 | 角色 |
|---|---|---|
| `src/api/openai-completions.ts` | 1,744 | OpenAI 兼容 chat |
| `src/api/openai-codex-responses.ts` | 1,724 | Codex Responses（SSE/WS） |
| `src/api/anthropic-messages.ts` | 1,690 | Anthropic Messages |
| `src/api/bedrock-converse-stream.ts` | 1,428 | Bedrock ConverseStream |
| `src/models.ts` | 1,299 | Models 运行时 |
| `src/api/mistral-conversations.ts` | 952 | Mistral |
| `src/types.ts` | 904 | 核心类型 |
| `src/api/openai-responses-shared.ts` | 887 | Responses 共享转换/流 |
| `src/providers/faux.ts` | 727 | 测试 provider |
| `src/providers/compat-schema.ts` | 644 | compat schema |
| `src/api/google-vertex.ts` | 567 | Vertex |
| `src/api/google-shared.ts` | 517 | Google 共享转换 |
| `src/api/google-generative-ai.ts` | 472 | Gemini |
| `src/api/llama-cpp-classify.ts` | 461 | llama.cpp 分类 |
| `src/api/openai-responses.ts` | 459 | Responses |
| `src/api/pi-messages.ts` | 448 | pi 网关协议 |
| `src/auth/oauth/github-copilot.ts` | 507 | Copilot OAuth |
| `src/auth/oauth/openai-codex.ts` | 438 | Codex OAuth |
| `src/utils/validation.ts` | 353 | 工具参数校验 |
| `src/providers/all.ts` | 190 | 42 provider 聚合 |

### 13.2 数据统计（本机实测）

| 数据集 | provider 数 | 总数 | chat | image | classifier |
|---|---|---|---|---|---|
| 1.3.0 pinned catalog（`nix/model-catalog.json` revision） | 42 | 1,705 | 1,608 | 59 | 38 |
| 1.0.0 已安装 npm dist（`dist/providers/data`） | 42 | 1,604 | 1,532 | 57 | 15 |
| 2026-10-22 从源码重新生成 | 42 | 1,714 | 1,600 | 63 | 51 |

### 13.3 术语

- **Models**：provider 集合与请求门面（`src/models.ts`）。
- **Provider**：一个服务商的运行时单元（id/baseUrl/auth/models/api 实现）。
- **API**：线上协议实现（如 `anthropic-messages`），一个 API 可被多个 provider 复用。
- **compat**：per-model 的兼容开关，覆盖 URL 探测默认值。
- **TranscriptContext**：已归一化上下文（system/tools 在首条 system message）。
- **pinned catalog**：`nix/model-catalog.json` 指向的 pi.dev 模型目录 revision。
- **faux provider**：测试用脚本化 provider。
