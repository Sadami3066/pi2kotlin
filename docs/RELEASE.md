# 发布与产物校验

面向维护者。当前版本：`0.1.1`（`build.gradle.kts` 中 `subprojects.version`）。

## 本地发布流程

```bash
./scripts/release-local.sh 0.1.1
```

脚本依次执行：

1. `./gradlew clean build`（JVM + Android target 编译与测试）
2. `./gradlew publishToMavenLocal`（发布 `dev.pi:pi-ai-jvm`、`pi-ai-android`、`pi-agent-*`、`pi-coding-agent-*`，含 sources jar 与 POM）
3. `node scripts/verify-release.mjs 0.1.1`（校验产物完整性与字节码版本）
4. `./gradlew :examples:jvm-demo:run`（JVM 端到端演示）
5. 构建 Android demo APK（独立工程，消费本地发布产物）
6. 生成 `build/release-manifest.txt`（版本、生成时间、jar/aar/apk 的 sha256）

低内存机器上脚本内部应通过 `scripts/gw.sh` 串行执行构建，避免多个 Gradle 实例同时占用内存。

## 产物清单

| 产物 | 路径 |
|---|---|
| JVM jar | `~/.m2/repository/dev/pi/<module>-jvm/0.1.1/<module>-jvm-0.1.1.jar` |
| JVM sources | `~/.m2/repository/dev/pi/<module>-jvm/0.1.1/<module>-jvm-0.1.1-sources.jar` |
| Gradle metadata | `~/.m2/repository/dev/pi/<module>-jvm/0.1.1/<module>-jvm-0.1.1.module` |
| POM | `~/.m2/repository/dev/pi/<module>-jvm/0.1.1/<module>-jvm-0.1.1.pom` |
| Android AAR | `~/.m2/repository/dev/pi/<module>-android/0.1.1/<module>-android-0.1.1.aar` |
| Demo APK | `examples/android-demo/build/outputs/apk/debug/pi2kotlin-android-demo-debug.apk` |

## 校验项

`scripts/verify-release.mjs` 检查：

- 三个模块的 JVM jar / POM / Gradle module metadata / Android AAR / sources jar 均存在
- JVM jar 内 class 文件 major version 为 `61`（Java 17）；Android 依赖与 `minSdk 26` 兼容
- POM 版本与发布版本一致，模块间依赖坐标正确（`pi-agent-jvm` → `pi-ai-jvm`，`pi-coding-agent-jvm` → `pi-agent-jvm`）
- Android AAR 包含 `classes.jar` 与 `AndroidManifest.xml`

## 版本与 JVM 目标

- 版本号统一在根 `build.gradle.kts` 的 `subprojects { version = ... }` 设置
- JVM target 使用 `jvmToolchain(17)`，发布产物为 Java 17 字节码；Android target 编译选项同样为 17
- README / SDK 文档中的 JVM 要求必须与实际字节码保持一致；改动 toolchain 后需重跑 `verify-release.mjs`

## 已知发布限制

- 未配置 Maven Central / 私有仓库凭据；如需外部分发，需要在各模块补充 `publishing.repositories`
- Android demo 是独立 Gradle 工程（`examples/android-demo`），依赖 `~/.m2` 中的发布产物；发布新版本后需重新构建
- 发布前应在有真实设备的机器上跑一次 Android demo（安装、启动、离线对话、销毁重开）；真机证据按需本地留存，不随公开仓库发布
