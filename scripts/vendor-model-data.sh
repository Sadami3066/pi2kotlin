#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:-1.3.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/pi-ai/src/main/resources/models"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

npm pack "@earendil-works/pi-ai@$VERSION" --silent --pack-destination "$WORK" > /dev/null
tar xzf "$WORK"/*.tgz -C "$WORK"

rm -rf "$DEST"
mkdir -p "$DEST"
cp "$WORK/package/dist/providers/data/"*.json "$DEST/"
cp "$WORK/package/dist/providers/data/.manifest.json" "$DEST/manifest.json"
cd "$DEST"
ls *.json | grep -v '^manifest\.json$' | sort > index.txt

echo "vendored pi-ai $VERSION model catalog: $(wc -l < index.txt) providers, $(du -sh . | cut -f1)"
