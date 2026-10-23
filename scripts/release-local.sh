#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-0.1.3}"
MANIFEST="$ROOT/build/release-manifest.txt"

cd "$ROOT"

echo "==> clean build + tests"
./gradlew clean build --no-daemon

echo "==> publish to maven local"
./gradlew publishToMavenLocal --no-daemon

echo "==> verify published artifacts"
node scripts/verify-release.mjs "$VERSION"

echo "==> JVM demo"
./gradlew :examples:jvm-demo:run --no-daemon

echo "==> Android demo APK"
"$ROOT/gradlew" -p "$ROOT/examples/android-demo" assembleDebug --no-daemon

mkdir -p "$ROOT/build"
{
  echo "version=$VERSION"
  echo "generated=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  for module in pi-ai pi-agent pi-coding-agent; do
    for artifact in \
      "$HOME/.m2/repository/dev/pi/$module-jvm/$VERSION/$module-jvm-$VERSION.jar" \
      "$HOME/.m2/repository/dev/pi/$module-android/$VERSION/$module-android-$VERSION.aar"; do
      if [ -f "$artifact" ]; then
        echo "$(sha256sum "$artifact")"
      fi
    done
  done
  apk="$ROOT/examples/android-demo/build/outputs/apk/debug/pi2kotlin-android-demo-debug.apk"
  if [ -f "$apk" ]; then
    echo "$(sha256sum "$apk")"
  fi
} > "$MANIFEST"

echo
echo "release manifest: $MANIFEST"
cat "$MANIFEST"
