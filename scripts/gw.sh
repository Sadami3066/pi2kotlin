#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="${TMPDIR:-/tmp}/pi2kotlin-gradle.lock"

exec flock "$LOCK" "$ROOT/gradlew" --no-daemon --max-workers=2 "$@"
