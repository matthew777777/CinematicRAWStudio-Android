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
REF = ROOT / "third_party/rapidraw-1.7-reference"
RUNTIME_SHADERS = ROOT / "app/src/main/rust/rapidraw-core/src/generated/shaders"


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
    parser.add_argument("--output", type=Path)
    parser.add_argument("--renderer", choices=("upstream", "runtime", "reference-1.7"), default="upstream")
    parser.add_argument("--features", type=lambda value: int(value, 0),
                        help="Optional FEATURES override for the grading entry points")
    args = parser.parse_args()
    if args.features is not None and not 0 <= args.features <= 0xFFFFFFFF:
        parser.error("--features must fit in u32")
    if args.output is None:
        args.output = (ROOT / "app/src/main/assets/rapidraw/shaders"
                       if args.renderer == "upstream" and args.features is None
                       else ROOT / "build/shader-analysis" / args.renderer / "spv")
    reference = REF
    shader_dir = RUNTIME_SHADERS if args.renderer == "runtime" else reference / "src-tauri/src/shaders"
    call(["python3", ROOT / "tools/verify_rapidraw_reference.py", "--reference", reference])
    version = subprocess.check_output([args.naga, "--version"], text=True).strip()
    lock = (reference / "src-tauri/Cargo.lock").read_text()
    expected = re.search(r'name = "naga"\nversion = "([^"]+)"', lock)[1]
    if version != expected: raise SystemExit("Naga must match upstream Cargo.lock: " + expected)
    args.output.mkdir(parents=True, exist_ok=True)
    records = []
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        names = ["shader", "guided", "blur"]
        if (shader_dir / "flare.wgsl").is_file():
            names.append("flare")
        for name in names:
            source_path = shader_dir / f"{name}.wgsl"
            original = source_path.read_text()
            entries = re.findall(r"@compute[\s\S]*?\bfn (\w+)\(", original)
            variants = [(name, original, {})]
            if name == "shader":
                variants = [("grade8", original, {"HIGH_PRECISION_OUTPUT": 0}),
                            ("grade16", original.replace("rgba8unorm, write>", "rgba16float, write>"), {"HIGH_PRECISION_OUTPUT": 1})]
                if args.features is not None:
                    for _, _, overrides in variants:
                        overrides["FEATURES"] = args.features
            for variant, text, overrides in variants:
                source = temp / (variant + ".wgsl"); source.write_text(text)
                for entry in entries:
                    target = args.output / f"{variant}-{entry}.spv"
                    removed = compile_shader(args.naga, source, target, entry, overrides, temp)
                    records.append(dict(file=target.name, source=str(source_path.relative_to(ROOT)), entry=entry,
                        overrides=overrides, high_precision_texture_adapter=variant == "grade16",
                        removed_workgroup_stride_decorations=len(removed),
                        source_sha256=hashlib.sha256(text.encode()).hexdigest(),
                        spirv_sha256=hashlib.sha256(target.read_bytes()).hexdigest()))
                    if args.renderer == "upstream":
                        records[-1]["upstream"] = str(source_path.relative_to(REF))
    manifest = dict(commit=json.loads((reference / "manifest.json").read_text())["commit"],
                    renderer=args.renderer, naga=version,
                    target="Android Vulkan 1.1", status="Compiled and SPIR-V validated; runtime image parity gates pending", modules=records)
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2)+"\n")
    print("Compiled and validated",len(records),"native Vulkan modules")


if __name__ == "__main__": main()
