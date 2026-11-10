#!/usr/bin/env python3
"""Compile the complete pinned compute shader set for native Android Vulkan.

Naga's CLI emits ArrayStride on workgroup arrays. SPIR-V disallows that
decoration without explicit-workgroup-layout support. Remove only stride
decorations on workgroup-reachable types, then validate the final module.
No arithmetic or upstream WGSL is changed by this compatibility adapter.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "third_party/rapidraw-reference"


def call(command):
    subprocess.run([str(x) for x in command], check=True, stdout=subprocess.PIPE)


def compile_shader(naga, source, output, entry, overrides, temp):
    raw, assembly, fixed = temp / "raw.spv", temp / "raw.spvasm", temp / "fixed.spvasm"
    command = [naga, "--entry-point", entry, "--compact"]
    for key, value in overrides.items():
        command += ["--override", f"{key}={value}"]
    call(command + [source, raw])
    call(["spirv-dis", raw, "-o", assembly])
    text = assembly.read_text()
    types = {}
    roots = set()
    for line in text.splitlines():
        match = re.match(r"\s*(%\S+) = OpType(\w+) (.*)", line)
        if match:
            types[match[1]] = (match[2], match[3])
            if match[2] == "Pointer" and match[3].startswith("Workgroup "):
                roots.add(match[3].split()[1])
    reach = set()
    def visit(node):
        if node in reach:
            return
        reach.add(node)
        kind, args = types.get(node, ("", ""))
        if kind in {"Array", "RuntimeArray", "Vector", "Matrix"}:
            visit(args.split()[0])
        elif kind == "Struct":
            for member in args.split(): visit(member)
    for root in roots: visit(root)
    removed = []
    lines = []
    for line in text.splitlines():
        match = re.match(r"\s*OpDecorate (%\S+) ArrayStride \d+", line)
        if match and match[1] in reach:
            removed.append(match[1])
        else:
            lines.append(line)
    fixed.write_text("\n".join(lines) + "\n")
    call(["spirv-as", "--target-env", "spv1.0", fixed, "-o", output])
    call(["spirv-val", "--target-env", "vulkan1.1", output])
    return removed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--naga", required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/rapidraw/shaders")
    args = parser.parse_args()
    call(["python3", ROOT / "tools/verify_rapidraw_reference.py"])
    version = subprocess.check_output([args.naga, "--version"], text=True).strip()
    lock = (REF / "src-tauri/Cargo.lock").read_text()
    expected = re.search(r'name = "naga"\nversion = "([^"]+)"', lock)[1]
    if version != expected: raise SystemExit("Naga must match upstream Cargo.lock: " + expected)
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        for name in ["shader", "guided", "blur", "flare"]:
            source_path = REF / f"src-tauri/src/shaders/{name}.wgsl"
            original = source_path.read_text()
            entries = re.findall(r"@compute[\s\S]*?\bfn (\w+)\(", original)
            variants = [(name, original, {})]
            if name == "shader":
                variants = [("grade8", original, {"HIGH_PRECISION_OUTPUT": 0}),
                            ("grade16", original.replace("rgba8unorm, write>", "rgba16float, write>"), {"HIGH_PRECISION_OUTPUT": 1})]
            for variant, text, overrides in variants:
                source = temp / (variant + ".wgsl"); source.write_text(text)
                for entry in entries:
                    target = args.output / f"{variant}-{entry}.spv"
                    removed = compile_shader(args.naga, source, target, entry, overrides, temp)
                    records.append(dict(file=target.name, upstream=str(source_path.relative_to(REF)), entry=entry,
                        overrides=overrides, high_precision_texture_adapter=variant == "grade16",
                        removed_workgroup_stride_decorations=len(removed),
                        source_sha256=hashlib.sha256(text.encode()).hexdigest(),
                        spirv_sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
    manifest = dict(commit="f1d71a69eef813432db214f77969f567c41ff1d7", naga=version,
                    target="Android Vulkan 1.1", status="Compiled and SPIR-V validated; runtime image parity gates pending", modules=records)
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print("Compiled and validated",len(records),"native Vulkan modules")


if __name__ == "__main__": main()
