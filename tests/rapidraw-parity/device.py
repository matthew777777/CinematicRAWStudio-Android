#!/usr/bin/env python3
"""Run WGSL-derived and GLSL-derived SPIR-V on the same Android Vulkan device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

import numpy as np
from run import ordered_bits

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--naga", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--stage", choices=["exposure", "display-outputs"], default="exposure")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    out = args.output.resolve()
    def call(command):
        result = subprocess.run([str(x) for x in command], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{result.stdout}")
        return result.stdout
    version = call([args.naga, "--version"]).strip()
    lock = (ROOT / "third_party/rapidraw-reference/src-tauri/Cargo.lock").read_text()
    import re
    expected = re.search(r'name = "naga"\nversion = "([^"]+)"', lock).group(1)
    if version != expected:
        raise RuntimeError(f"Naga version {version} does not match upstream Cargo.lock {expected}")
    if args.stage == "exposure":
        print(call([sys.executable, Path(__file__).with_name("run.py"), "--output", out, "--prepare-only"]))
    else:
        from display_outputs import prepare_outputs
        prepare_outputs(out)
    compiler = args.ndk / "toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android30-clang++"
    print(call([compiler, "-std=c++17", "-O2", "-static-libstdc++", Path(__file__).with_name("vulkan_runner.cpp"), "-lvulkan", "-o", out / "runner"]))
    print(call([args.naga, out / "reference.wgsl", out / "reference.spv"]))
    for name in ["reference.spv", "translated.spv"]:
        print(call(["spirv-val", "--target-env", "vulkan1.1", out / name]))
    remote = "/data/local/tmp/crs-rapidraw-exposure-parity"
    def adb(*words):
        return call([args.adb, *words])
    adb("shell", "mkdir", "-p", remote)
    for name in ["runner", "reference.spv", "translated.spv"]:
        adb("push", out / name, remote + "/" + name)
    adb("shell", "chmod", "755", remote + "/runner")
    results, device_logs = [], []
    for count in [1, 63, 65, 4233]:
        name = f"{count}-input.f32"
        adb("push", out / name, remote + "/" + name)
        arrays = []
        for variant in ["reference", "translated"]:
            target = f"{count}-{variant}.f32"
            device_logs.append(adb("shell", remote + "/runner", remote + f"/{variant}.spv", remote + "/" + name, remote + "/" + target))
            adb("pull", remote + "/" + target, out / target)
            value = np.fromfile(out / target, dtype=np.float32)
            if value.size != count * 4:
                raise RuntimeError("Unexpected output length")
            arrays.append(value.reshape(count, 4))
        reference, port = arrays
        inputs = np.fromfile(out / name, dtype=np.float32).reshape(count, 4)
        absolute = np.abs(reference[:, :3].astype(np.float64) - port[:, :3].astype(np.float64))
        unequal = reference.view(np.uint32) != port.view(np.uint32)
        zero = inputs[:, 3] == 0
        identity = all(np.array_equal(a[zero, :3].copy().view(np.uint32), inputs[zero, :3].copy().view(np.uint32)) for a in arrays) if args.stage == "exposure" else None
        def quantize(a):
            return np.rint(np.clip(a[:, :3].astype(np.float64), 0, 1) * 65535).astype(np.int64)
        qdiff = np.abs(quantize(reference) - quantize(port))
        locations = np.argwhere(unequal)
        examples = [dict(row=int(i), component=int(j), reference_bits=int(reference.view(np.uint32)[i,j]),
                         translated_bits=int(port.view(np.uint32)[i,j]), input=inputs[i].tolist()) for i,j in locations[:16]]
        results.append(dict(samples=count, unequal_components=int(unequal.sum()), max_absolute=float(absolute.max()),
                            mean_absolute=float(absolute.mean()), max_ulp=int(np.abs(ordered_bits(reference[:, :3]) - ordered_bits(port[:, :3])).max()),
                            zero_ev_bit_identity=identity, quantized_rgb16_max=int(qdiff.max()),
                            quantized_rgb16_unequal=int(np.count_nonzero(qdiff)), examples=examples))
    manifest = json.loads((ROOT / "third_party/rapidraw-reference/manifest.json").read_text())
    report = dict(commit=manifest["commit"], stage="apply_linear_exposure" if args.stage == "exposure" else "retained Filament AgX / OCIO ACES2", scope="Same-device Android Vulkan; isolated function only",
                  device=adb("shell", "getprop", "ro.product.model").strip(), driver=device_logs[0].strip(),
                  naga_version=version,
                  compiler=call([compiler, "--version"]).strip(), glslang_version=call(["glslangValidator", "--version"]).strip(),
                  hashes={name: hashlib.sha256((out/name).read_bytes()).hexdigest() for name in ["reference.wgsl", "reference.spv", "translated.spv", "runner"]},
                  inputs={str(count): hashlib.sha256((out/f"{count}-input.f32").read_bytes()).hexdigest() for count in [1, 63, 65, 4233]},
                  cases=results)
    (out / "device-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if any(r["unequal_components"] or r["zero_ev_bit_identity"] is False for r in results):
        raise SystemExit("FAIL: exact-bit parity gate; no tolerance accepted")


if __name__ == "__main__":
    main()
