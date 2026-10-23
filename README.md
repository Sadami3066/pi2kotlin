# pi2kotlin

[![CI](https://github.com/Sadami3066/pi2kotlin/actions/workflows/ci.yml/badge.svg)](https://github.com/Sadami3066/pi2kotlin/actions/workflows/ci.yml)

A Kotlin Multiplatform SDK for building AI agent apps on **Android and the JVM**, rewritten from [pi](https://pi.dev) (`pi-ai`, `pi-agent`, and the SDK layer of `pi-coding-agent`).

[中文文档](README.zh-CN.md)

Status: **0.1.3 alpha**. The library builds, ships Android AARs and JVM JARs, and is covered by 1,000+ JVM tests plus Android host tests. API shape may still change before 1.0.

## Modules

| Module | Ports | What it does |
|---|---|---|
| `pi-ai` | `packages/ai` | Unified LLM API: 10 chat APIs, image generation and classifier APIs, 42 built-in providers, model catalog, auth (API key + 9 OAuth flows), streaming events, retries, usage/cost |
| `pi-agent` | `packages/agent` | Agent runtime: event lifecycle, tool execution (serial and parallel), hooks, steering/follow-up queues, proxy transport |
| `pi-coding-agent` | `packages/coding-agent` (core/SDK layer) | Sessions (pi-compatible JSONL), settings, model resolution, built-in tools, system prompt and resources, compaction, and the `AgentSession` / `createAgentSession` facade |

Dependency direction: `pi-coding-agent` → `pi-agent` → `pi-ai`.

## Requirements

- JDK 17 or newer (published JVM artifacts target Java 17)
- Android `minSdk 26` and `compileSdk 36` when used on Android
- Gradle 9.8.2 (wrapper included) and Kotlin 2.4.21 for building from source

## Installation

Artifacts are not on Maven Central yet. Two options:

**Prebuilt artifacts**: download `pi2kotlin-0.1.3-m2.zip` from [GitHub Releases](https://github.com/Sadami3066/pi2kotlin/releases), unzip it, and point the dependency repository at the unzipped directory:

```kotlin
repositories {
    maven { url = uri("path/to/unzipped") }
    mavenCentral()
}
```

**Build from source**:

```bash
./gradlew publishToMavenLocal
```

Then depend on the variant you need:

```kotlin
// JVM
implementation("dev.pi:pi-coding-agent:0.1.3")

// Android
implementation("dev.pi:pi-coding-agent-android:0.1.3")
```

Android apps also need `android.useAndroidX=true`.

## Quick start

```kotlin
import dev.pi.ai.TextDeltaEvent
import dev.pi.ai.http.okHttpTransport
import dev.pi.agent.AgentEvent
import dev.pi.coding.AgentSessionOptions
import dev.pi.coding.createAgentSession
import dev.pi.coding.session.SessionEvent
import okio.Path.Companion.toPath

val session = createAgentSession(
    AgentSessionOptions(
        cwd = workspaceDir,
        agentDir = appPrivateDir.resolve("agent").absolutePath.toPath(),
        transport = okHttpTransport(),
        model = "deepseek/deepseek-v4-flash",
    ),
)

val subscription = session.subscribe { event ->
    if (event is SessionEvent.AgentEventReceived) {
        val agentEvent = event.event
        if (agentEvent is AgentEvent.MessageUpdate) {
            val update = agentEvent.assistantMessageEvent
            if (update is TextDeltaEvent) print(update.delta)
        }
    }
}

session.prompt("Summarize the files in this directory")
println(session.getLastAssistantText())
subscription()
session.close()
```

`createAgentSession` resolves settings, credentials, the model catalog, and resources (context files, skills, prompt templates) from `cwd` and `agentDir`.

## Sessions

- Conversations are persisted as pi-compatible JSONL (the same format as `~/.pi/agent/sessions`), including system prompt snapshots and compaction entries.
- `sessionFile` can be passed to resume; `session.prompt()` resumes with the restored transcript.
- `AgentSessionRuntime` adds `newSession()`, `switchSession()`, and `fork()` (fork can summarize the abandoned branch).
- Store the agent directory in app-private storage on Android.

## Models and providers

- Built in: 42 providers and 1,714 catalog models (chat, image, classifier), 10 chat APIs, and 9 OAuth flows (Anthropic, OpenAI ChatGPT, GitHub Copilot, OpenAI Codex, xAI, OpenRouter, Kimi, Meta, Radius).
- API keys are read from `<agentDir>/auth.json` (pi-compatible), a custom `CredentialStore`, or environment variables.
- Custom providers can be configured in `<agentDir>/models.json` or injected at runtime via `AgentSessionOptions.providers`.

## Tools

Built-in tools: `read`, `write`, `edit`, `ls`, `grep`, `find`, `bash`.

- `bash` requires a `ShellExecutor`; on Android and JVM use `UnixShellExecutor` (or pass `null` to disable shell access).
- `grep` / `find` are pure Kotlin and honor `.gitignore` / `.ignore` / `.fdignore`.
- Custom tools implement `CodingTool` and are passed through `AgentSessionOptions.tools`.

## Hooks

`CodingHooks` supports input interception (`onInput`), prompt transformation, extra system prompt sections, and session start/shutdown callbacks.

## Examples

```bash
./gradlew :examples:jvm-demo:run          # offline end-to-end demo using the faux provider
./scripts/build-android-demo.sh           # publish to ~/.m2 and build the demo APK
```

The Android demo lives in `examples/android-demo` (standalone Gradle build) and includes an offline demo mode that needs no API key.

## Build and test

```bash
./gradlew build                           # compile all targets and run all tests
./gradlew :pi-ai:jvmTest                  # fast JVM-only tests
./gradlew publishToMavenLocal             # publish dev.pi:* artifacts locally
node scripts/verify-release.mjs 0.1.3     # verify published artifacts (Java 17 bytecode, AARs, POMs)
./scripts/release-local.sh 0.1.3          # clean build + tests + local publish + demos + checksums
```

On low-memory machines use `./scripts/gw.sh <tasks>` instead of `./gradlew` to serialize Gradle runs.

## Known limitations

- No CLI or terminal UI: the SDK exposes events and `AgentSession` for the host app to render.
- No TypeScript extension compatibility; use Kotlin tools and hooks instead.
- OpenAI Codex transport is SSE only (WebSocket/zstd not implemented).
- Amazon Bedrock credentials come from environment variables or a bearer token (no profile/IMDS/STS chain).
- Radius dynamic model catalog is stubbed; only the static gateway URL is supported.
- Images are passed through without resizing or format conversion.
- JSON Schema validation covers common constraint keywords plus local `$ref`/`$defs`; `format` supports a subset.
- Not implemented: MCP client, codemode sandbox, virtual models, deferred responses, telemetry.

## License

MIT. This project is a Kotlin rewrite of [pi](https://github.com/earendil-works/pi), which is MIT licensed; the original copyright and license are retained in `LICENSE`.
