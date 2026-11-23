#!/usr/bin/env python3
"""Verify the frozen upstream reference, independent of the installed app."""
import argparse
import hashlib
import json
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument("--reference",default="third_party/rapidraw-1.7-reference")
args=parser.parse_args()
root = Path(__file__).resolve().parents[1] / args.reference
manifest = json.loads((root / "manifest.json").read_text())
failures = []
for name, expected in manifest["sha256"].items():
    path = root / name
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        failures.append(name)
if failures:
    raise SystemExit("Reference mismatch: " + ", ".join(failures))
print(f"Verified {len(manifest['sha256'])} upstream files at {manifest['commit']}")
