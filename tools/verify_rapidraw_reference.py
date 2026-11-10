#!/usr/bin/env python3
"""Verify the frozen upstream reference, independent of the installed app."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "third_party/rapidraw-reference"
manifest = json.loads((root / "manifest.json").read_text())
failures = []
for name, expected in manifest["sha256"].items():
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        failures.append(name)
if failures:
    raise SystemExit("Reference mismatch: " + ", ".join(failures))
print(f"Verified {len(manifest['sha256'])} upstream files at {manifest['commit']}")
