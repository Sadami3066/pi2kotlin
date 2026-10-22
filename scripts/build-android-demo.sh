#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

"$ROOT/gradlew" -p "$ROOT" publishToMavenLocal
"$ROOT/gradlew" -p "$ROOT/examples/android-demo" assembleDebug

echo "APK: $ROOT/examples/android-demo/build/outputs/apk/debug/pi2kotlin-android-demo-debug.apk"
