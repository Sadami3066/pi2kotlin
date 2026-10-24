# MCP 接入指南

pi2kotlin 0.2.0 起内置 MCP（Model Context Protocol）客户端，0.2.1 补齐 sampling / elicitation / roots，支持把第三方 MCP server 的工具、资源、提示词接入 agent 会话。

## 支持范围

| 能力 | 状态 |
|---|---|
| Streamable HTTP 传输（POST/JSON+SSE、`Mcp-Session-Id`、协议版本协商、GET 通知流、Last-Event-ID 续传、断线重连、DELETE 关闭） | 支持 |
| stdio 传输 | 仅 JVM（Android 无子进程，配置会标记 `Unsupported`） |
| 工具：direct / deferred / codemode / hidden 暴露、`toolExposure` 单工具覆盖 | 支持（codemode 映射为 deferred + tool_search，不做 JS 脚本） |
| `tool_search`（BM25 + 中文 2-gram） | 支持 |
| resources：`list_mcp_resources` / `list_mcp_resource_templates` / `read_mcp_resource` | 支持 |
| prompts：list/get、`session.promptMcp(...)`、`/mcp__<server>__<prompt>` 斜杠命令 | 支持 |
| OAuth 2.1：受保护资源发现、RFC 8414/OIDC、DCR、PKCE、refresh、scope step-up、token 持久化 | 支持（CIMD 不支持） |
| `tools/list_changed`、日志/进度通知 | 支持 |
| roots（工作区根目录声明、`roots/list_changed`） | 支持（默认返回 `cwd`，可配置/更新） |
| sampling（server 请求宿主用当前模型生成） | 支持（默认启用，可用 `samplingEnabled = false` 关闭） |
| elicitation（server 请求宿主向用户收集输入） | 支持（需宿主提供 `elicitationHandler`；未提供时不声明该能力） |
| resource subscriptions / completion / CIMD | 未实现 |
| legacy HTTP+SSE 传输 | 不支持（配置 `type:"sse"` 会报错） |

## 快速开始

```kotlin
import dev.pi.coding.mcp.McpOptions
import dev.pi.mcp.config.McpExposure
import dev.pi.mcp.config.McpServerConfig

val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = agentDir,
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
        mcp = McpOptions(
            servers = mapOf(
                "filesystem" to McpServerConfig.Stdio(
                    command = "npx",
                    args = listOf("-y", "@modelcontextprotocol/server-filesystem", workspaceDir.toString()),
                    exposure = McpExposure.Direct,
                ),
                "docs" to McpServerConfig.Http(
                    url = "https://mcp.example.com/mcp",
                    headers = mapOf("Authorization" to "Bearer ${System.getenv("DOCS_TOKEN")}"),
                ),
            ),
        ),
    ),
)
```

不传 `McpOptions.servers` 时，会从以下文件读取配置：

1. `<agentDir>/mcp.json`（全局）
2. `<cwd>/.pi/mcp.json`（仅当项目被信任；`settings.json` 或 `AgentSessionOptions.projectTrusted` 控制）

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
      "env": { "FOO": "${FOO}" },
      "cwd": ".",
      "enabled": true,
      "timeout": 60,
      "exposure": "deferred"
    },
    "internal-docs": {
      "url": "https://mcp.example.com/mcp",
      "headers": { "Authorization": "Bearer ${DOCS_TOKEN}" },
      "oauth": { "scope": "mcp:read", "clientId": "..." }
    }
  }
}
```

字段说明：

- `type`：`stdio` 或 `http`（不写时按 `command`/`url` 推断；`streamable-http` 是别名；`sse` 不支持）
- `command` / `args` / `env` / `cwd`：stdio
- `url` / `headers` / `oauth` / `auth.provider`：HTTP
- `enabled`：默认 `true`
- `timeout`：单请求超时秒数，默认 `60`
- `description`：给模型看的一句话说明
- `exposure`：`direct` / `deferred`（默认）/ `codemode`（等价 deferred）/ `hidden`
- `toolExposure`：按工具名或 `*` 通配覆盖单个工具的 exposure

`${VAR}` 会从环境变量插值；缺失变量会报错并跳过该 server。值为 `!command` 会被拒绝执行（安全限制）。

项目 `mcp.json` 只能覆盖全局同名 server 的 `enabled` / `exposure` / `toolExposure`，不能定义新 server、命令或凭据。

## 工具暴露与 tool_search

- `direct`：连接后立即声明给模型，像内置工具一样调用。
- `deferred`（默认）：工具不预先声明，模型先用 `tool_search` 搜索，命中的工具会在**下一个模型请求**被声明并可调用；命中结果同时写入会话自定义条目 `mcp_loaded_tools`，恢复会话后仍保持已加载状态。
- `codemode`：上游 pi 用它把工具暴露给 JS 脚本；本 SDK 不做 JS 沙箱，按 `deferred` 处理。
- `hidden`：注册但不暴露。

`tool_search` 的排序是 BM25，中英文混合查询可用（中文按 2-gram 分词）。存在 deferred server 时会自动加入会话。

## 资源

连接成功且 server 声明 resources 能力时，会自动注册：

- `list_mcp_resources`（`server`、`cursor`）
- `list_mcp_resource_templates`
- `read_mcp_resource`（`uri` 必填，`server` 可选）

多 server 时列表会合并；`read` 需要指定 `server`（只有一个 server 时可省略）。文本与图片直接返回，二进制按配置目录落盘。

宿主也可以直接调用：

```kotlin
session.mcp.readResource("internal-docs", "file:///handbook.md")
```

## 提示词

```kotlin
val prompts = session.mcp.listPrompts()          // 全部 server
session.promptMcp("demo", "greet", mapOf("who" to "world"))
```

也可以在 `prompt` 中直接写斜杠命令：`/mcp__demo__greet world`。

## OAuth

HTTP server 未配置 `Authorization` 或配置了 `oauth` 时，401 会触发 OAuth 流程：

```kotlin
val result = session.mcp.signIn(
    server = "internal-docs",
    redirectUri = "http://127.0.0.1:33418/callback", // JVM 可用 pi-mcp 的 LoopbackCallbackServer
    interaction = object : McpOAuthInteraction {
        override suspend fun authorize(url: String, redirectUri: String): String {
            // Android: 打开 Custom Tabs / 浏览器，等待 deep link 回调并返回完整 callback URL
            return awaitBrowserCallback(url, redirectUri)
        }
    },
)
```

- token 持久化到 `<agentDir>/mcp-auth.json`（原子写，0600）。
- 自动 refresh（提前 30 秒）、401 重试一次、403 `insufficient_scope` step-up。
- JVM 可用 `dev.pi.mcp.oauth.LoopbackCallbackServer` 起本地回调；Android 需要宿主提供浏览器回调（示例见 `examples/android-demo`）。
- `auth.provider`（复用 SDK 已登录的 provider token）目前**只解析配置、未接线**，请不要依赖；0.3.0 再补。

## roots / sampling / elicitation

这三个是 MCP 的客户端能力，由 `McpOptions` 控制：

```kotlin
McpOptions(
    // roots：默认返回 file://<cwd>；可显式配置，运行时可更新
    roots = listOf(McpRoot(uri = "file:///data/user/0/app/files/workspace", name = "workspace")),
    rootsEnabled = true,

    // sampling：server 调用宿主模型；默认启用，可关闭
    samplingEnabled = true,
    samplingMaxTokens = 4096,
    samplingTemperature = null,

    // elicitation：server 请求用户输入，由宿主 UI 实现
    elicitationHandler = { request ->
        // request.message 是提示文案，request.requestedSchema 是 JSON Schema 表单
        val accepted = showForm(request.message, request.requestedSchema)
        McpElicitationResult(
            action = if (accepted != null) "accept" else "decline",
            content = accepted,
        )
    },
)
```

行为与注意事项：

- **roots**：`roots/list` 返回配置的根目录；`rootsEnabled=false` 时不声明能力。运行时更新：
  ```kotlin
  session.mcp.setRoots(listOf(McpRoot("file:///new-workspace")))
  ```
  会自动发送 `notifications/roots/list_changed`。
- **sampling**：server 发 `sampling/createMessage` 时，SDK 用**当前会话模型**发一次非流式请求（`maxTokens` 取 min(server 请求值, `samplingMaxTokens`, 模型上限)），支持 text/image 输入，返回 text/image。这是真实模型调用，**会消耗 token**；不需要时设置 `samplingEnabled = false`（不声明能力，server 不会请求）。sampling 请求发生在工具执行期间，不会推进 agent 会话轮次。
- **elicitation**：只有提供 `elicitationHandler` 才声明能力。handler 收到 `message` + `requestedSchema`，返回 `action` 为 `"accept"`（可带符合 schema 的 `content`）、`"decline"` 或 `"cancel"`。没有 handler 时能力不声明；若 server 仍强行请求会收到 `-32601`。Android/JVM 的 UI 由宿主实现，SDK 不弹窗。
- 三者都不影响普通 MCP 工具/资源/prompt。

## 运行时管理

```kotlin
session.mcp.servers                              // List<McpServerStatus>
session.mcp.reconnect("docs")
session.mcp.reload()                             // 重读 mcp.json 并 diff
session.mcp.addServer("extra", config)
session.mcp.removeServer("extra")
session.mcp.setEnabled("docs", enabled = false)
session.mcp.healthCheck()
session.mcp.listPrompts(); session.mcp.tools(); session.mcp.readResource(...)
```

事件：

```kotlin
session.events.collect { event ->
    when (event) {
        is SessionEvent.McpServerStatusChanged -> { /* Connecting/Connected/AuthRequired/Failed/Unsupported/Closed */ }
        is SessionEvent.McpToolsChanged -> { }
        is SessionEvent.McpLog -> { }
        is SessionEvent.McpSignInRequired -> { }
        else -> Unit
    }
}
```

`session.mcp.servers` 含 `state`、`toolCount`、`serverInfo`、`instructions`、`lastError`。

## 平台差异

- Android：只能使用 HTTP server；stdio 配置会得到 `Unsupported` 状态和明确错误。访问本机 HTTP server 需要宿主配置 cleartext（仅调试）或使用 https。
- JVM：HTTP + stdio 均可用。stdio server 关闭时会清理子进程（SIGTERM → SIGKILL）。
- 首次 `createAgentSession` 会并行连接所有已启用的 server，最多等待 `McpOptions.connectTimeoutMs`（默认 10 秒）；超时/失败的 server 不阻塞会话，状态与错误通过事件暴露。

## 安全与限制

- 项目配置仅在项目被信任时读取，且只能覆盖 `enabled`/`exposure`/`toolExposure`。
- 不执行 `!command` 插值；HTTP 要求 https（loopback 例外）。
- 输出默认按 20KB 中间截断（全文可落盘），单 SSE 事件上限 16MB，每请求默认 60 秒超时。
- 不支持：codemode JS 沙箱、legacy SSE、CIMD、resource subscriptions、completion、MCP prompts 之外的 UI 资源（`ui://`）。
- Android OAuth 仍需宿主提供浏览器回调与 redirect URI（JVM 内置 loopback）。
- 项目 `.pi/mcp.json` 只能覆盖全局 server 的 enabled/exposure/toolExposure，不能新增 server。
- `auth.provider` 配置解析但未接线。

## 示例

```bash
./gradlew :examples:mcp-demo:run
```

该示例启动一个本地 mock MCP HTTP server（echo 工具 + 资源），演示 deferred 工具经 `tool_search` 加载并调用的完整流程。
