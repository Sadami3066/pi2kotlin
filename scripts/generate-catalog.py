#!/usr/bin/env python3
import base64
import gzip
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
DATA = ROOT / "data" / "model-catalog"
OUT = ROOT / "pi-ai" / "src" / "commonMain" / "kotlin" / "dev" / "pi" / "ai" / "CatalogData.kt"

index = [line.strip() for line in (DATA / "index.txt").read_text().splitlines() if line.strip()]
files = {}
for name in index:
    files[name] = json.loads((DATA / name).read_text())
manifest = json.loads((DATA / "manifest.json").read_text())

bundle = json.dumps({"files": files}, separators=(",", ":"), ensure_ascii=False).encode()
compressed = gzip.compress(bundle, compresslevel=9, mtime=0)
encoded = base64.b64encode(compressed).decode()

chunk_size = 30000
chunks = [encoded[i:i + chunk_size] for i in range(0, len(encoded), chunk_size)]
body = "\n        ".join(f'"{chunk}" +' for chunk in chunks)
if body.endswith(" +"):
    body = body[:-2]

OUT.write_text(f'''package dev.pi.ai

internal object CatalogData {{
    const val SCHEMA_VERSION: Int = {manifest["schemaVersion"]}

    val GZIP_BASE64: String =
        {body}
}}
''')
print(f"generated {OUT.relative_to(ROOT)}: {len(files)} providers, raw {len(bundle)} bytes, gzip {len(compressed)} bytes, base64 {len(encoded)} chars")
