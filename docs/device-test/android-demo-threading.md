# Android 示例真机验证（P1.4 线程修复）

## 设备与环境

| 项 | 值 |
|---|---|
| 设备 | Xiaomi `22101316UCP`（codename `rubypro`），USB 直连 |
| 系统 | Android 13（API 33），`ro.build.display.id=TP1A.220624.014` |
| 屏幕 | 1080x2400，density 440 |
| root | `adb shell su -c id` 返回 uid=0（Magisk），adb root 不可用，故 root 命令统一走 `su -c` |
| 构建 | `./scripts/gw.sh publishToMavenLocal`（flock 串行、`--max-workers=2`），SDK 版本 `0.1.0` |
| APK | `examples/android-demo/build/outputs/apk/debug/pi2kotlin-android-demo-debug.apk` |

## 示例改动

文件：`examples/android-demo/src/main/kotlin/dev/pi/demo/android/MainActivity.kt`、`examples/android-demo/src/main/AndroidManifest.xml`、`examples/android-demo/build.gradle.kts`（依赖改为 `dev.pi:pi-coding-agent-android:0.1.0`）、新增 `scripts/mock-sse-server.py`。

- `createAgentSession` 与 `prompt` 全部在 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 中执行；`onCreate` 时的 key 读取也在 IO。
- 所有 UI 更新经 `Channel<UiEvent>` 汇到 `Dispatchers.Main.immediate` 的消费协程执行（append/status/按钮状态），SDK `subscribe` 回调只做 `trySend`，不直接操作 View。
- 三种模式：
  - 在线：DeepSeek（`deepseek/deepseek-v4-flash`），key 从私有目录读取但**不回显**（只显示“已保存 key”提示），输入框可覆盖。
  - 离线：`fauxProvider`（`tokensPerSecond=10`、`tokenSize=1`），无 key、无网络，逐块出字，用于线程验证。
  - 本地 mock：`openAICompletionsApi(okHttpTransport())` 指向 `http://127.0.0.1:8765/v1`，配合 `scripts/mock-sse-server.py` + `adb reverse tcp:8765 tcp:8765`，用于真机网络流验证。
- 生命周期：`onDestroy` 先 `uiChannel.close()`、取消 UI/Work scope，再 `runBlocking(Dispatchers.IO) { withContext(NonCancellable) { session.close() } }`；`close()` 为 suspend，示例在 IO 线程完成关闭。
- StrictMode：主线程 `setThreadPolicy(detectAll().penaltyLog())`，用于捕获主线程 disk/network 违规。
- 自动化入口：`am start ... --ez auto_offline true` / `--ez auto_mock true` / `--ez auto_finish true`（demo 完成后自动 `finish()`，用于验证 onDestroy）。

## 构建与安装

```bash
cd /home/whywood/project/pi2kotlin
./scripts/gw.sh publishToMavenLocal
./scripts/gw.sh -p examples/android-demo assembleDebug
adb install -r examples/android-demo/build/outputs/apk/debug/pi2kotlin-android-demo-debug.apk
```

## 离线（faux）验证

```bash
adb shell am force-stop dev.pi.demo.android
adb logcat -c
adb shell am start -n dev.pi.demo.android/.MainActivity --ez auto_offline true
adb exec-out screencap -p > /tmp/pi2kotlin-offline-stream.png
adb exec-out screencap -p > /tmp/pi2kotlin-offline-done.png
```

截图：`pi2kotlin-android-offline-stream.png`（流式中途，文本逐块出现）、`pi2kotlin-android-offline-done.png`（完成 + `[done]` + 状态栏“离线演示完成 · 消息数=3 · 主线程未阻塞”）。

关键 logcat（完整见 `logcat-offline.txt`）：

```text
I pi2kotlin-demo: onCreate thread=main isMain=true device=22101316UCP android=13
I pi2kotlin-demo: offline start thread=main
I pi2kotlin-demo: offline createAgentSession start thread=DefaultDispatcher-worker-2
I pi2kotlin-demo: offline createAgentSession done thread=DefaultDispatcher-worker-2
I pi2kotlin-demo: session attached mode=offline thread=DefaultDispatcher-worker-2
I pi2kotlin-demo: offline prompt start thread=DefaultDispatcher-worker-2
I pi2kotlin-demo: ui update thread=main isMain=true
I pi2kotlin-demo: ui update thread=main isMain=true
I pi2kotlin-demo: offline prompt done thread=DefaultDispatcher-worker-1 chars=111
```

结论：session 创建/agent 运行在 `DefaultDispatcher-worker-*`（Dispatchers.IO），UI 更新在 `main`；主线程无网络/磁盘/慢调用违规记录来自示例代码。

## 本地 mock（真机网络流）验证

```bash
python3 scripts/mock-sse-server.py &          # 宿主机 127.0.0.1:8765
adb reverse tcp:8765 tcp:8765
adb shell am start -n dev.pi.demo.android/.MainActivity --ez auto_mock true
```

服务端记录：

```text
[mock-sse] listening on http://127.0.0.1:8765
[mock-sse] "POST /v1/chat/completions HTTP/1.1" 200 -
[mock-sse] served POST /v1/chat/completions
```

设备 logcat（完整见 `logcat-mock.txt`）：

```text
I pi2kotlin-demo: mock start thread=main
I pi2kotlin-demo: mock createAgentSession start thread=DefaultDispatcher-worker-1
I pi2kotlin-demo: mock prompt start thread=DefaultDispatcher-worker-1
I pi2kotlin-demo: ui update thread=main isMain=true
I pi2kotlin-demo: mock prompt done thread=DefaultDispatcher-worker-3 chars=135
```

截图：`pi2kotlin-android-mock-stream.png`、`pi2kotlin-android-mock-done.png`（状态“本地 mock 完成 · 消息数=3 · 网络读取在 IO 线程”）。

首次 mock 运行失败原因：Android 9+ 默认禁止 cleartext HTTP，设备日志为
`stopReason=error, errorMessage="CLEARTEXT communication to 127.0.0.1 not permitted by network security policy"`；
在 demo manifest 增加 `android:usesCleartextTraffic="true"` 后通过（仅示例工程，不影响 SDK）。

## StrictMode / 主线程违规分析

- `StrictMode.setThreadPolicy(detectAll().penaltyLog())` 在主线程调用，只对主线程生效。
- 离线与 mock 运行各只有 1 条 `DiskReadViolation`，解析全部 40 行堆栈后 **无任何 `dev.pi.*` 帧**；调用来自系统/vendor 框架：
  - `android.os.TurboSchedMonitorImpl.checkFileAvailable`（MTK 调度）
  - `com.miui.base.MiuiStubRegistry.collectStubProviders`（MIUI stub 初始化）
  - 早前版本还出现过 `com.mediatek.boostfwk.identify.scroll.ScrollIdentify`（由 `ScrollView.fullScroll` 触发；示例已改为 `scrollTo` 直接跳转）。
- 无 `NetworkViolation`、无 `NetworkOnMainThreadException`、无 `ANR in`、无 `FATAL EXCEPTION`（grep 均为 0）。
- 结论：主线程违规仅为设备厂商框架噪声，示例/SDK 路径没有主线程网络或磁盘访问。

## 销毁与重开

用 `adb shell su -c 'settings put global always_finish_activities 1'` 让返回键真正销毁 Activity，并带 `--ez auto_finish true` 运行，`logcat-destroy.txt`：

```text
I pi2kotlin-demo: onDestroy thread=main hasSession=true
I pi2kotlin-demo: onDestroy closed session on thread=DefaultDispatcher-worker-2
I pi2kotlin-demo: onDestroy done thread=main
```

`dumpsys activity` 中无该 Activity 残留；`adb shell pidof dev.pi.demo.android` 在进程退出后为空。
随后 `adb shell am force-stop dev.pi.demo.android` 后重启两轮，两轮离线演示均完成（`offline prompt done ... chars=111`），`FATAL EXCEPTION` 计数为 0。

## 发现的问题

1. 示例首次跑 mock 模式时被 Android cleartext 策略拦截（非 SDK 问题），已在 demo manifest 修复。
2. 示例原先会把用户已保存的 API key 回显到输入框（截图会泄露），已改为不回显、仅提示已保存。
3. StrictMode 在小米/MTK 设备上会被系统框架自身触发 `DiskReadViolation`（无应用帧），不能作为“零违规”硬断言；证据文档按“是否含应用帧”判定。
4. 未使用真实 DeepSeek key 做在线调用（避免消耗/网络与隐私暴露）；在线模式的线程路径与 mock 模式一致（都由 `Dispatchers.IO` 执行 session/prompt），离线与 mock 已覆盖线程/UI 关键路径。
5. 本次验证期间 SDK 由 lead 并行重写（`AgentSession.close()` suspend、内部 `platformWorkDispatcher`），示例按最新 0.1.0 产物构建验证；未发现需要修改 SDK 的线程问题。

## 设备侧清理

已执行：`adb reverse --remove tcp:8765`、停止 mock server、`svc power stayon false`、删除 `screen_off_timeout` 与 `always_finish_activities` 临时设置。APK 保留安装，便于复验。
