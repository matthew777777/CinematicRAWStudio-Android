#!/usr/bin/env python3
"""Execute unchanged upstream WGSL function vs compiled GLSL/SPIR-V.

Host backends are explicitly reported. A host pass is not an Android Vulkan pass.
Exit nonzero on any unequal output bit; no tolerance-based pass.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

import numpy as np
import wgpu
from wgpu.utils import get_default_device
from wgpu.utils.compute import compute_with_buffers

ROOT = Path(__file__).resolve().parents[2]


def extract_function(source, name):
    start = source.index("fn " + name + "(")
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def ordered_bits(values):
    bits = values.view(np.uint32).astype(np.int64)
    return np.where(bits & 0x80000000, 0xffffffff - bits, bits + 0x80000000)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prepare-only", action="store_true", help="Write inputs/shaders without accessing a host GPU")
    args = parser.parse_args()
    subprocess.run(["python3", str(ROOT / "tools/verify_rapidraw_reference.py")], check=True)
    reference = ROOT / "third_party/rapidraw-reference"
    manifest = json.loads((reference / "manifest.json").read_text())
    source = (reference / "src-tauri/src/shaders/shader.wgsl").read_text()
    function = extract_function(source, "apply_linear_exposure")
    shader = """
@group(0) @binding(0) var<storage, read> inputs: array<vec4<f32>>;
@group(0) @binding(1) var<storage, read_write> outputs: array<vec4<f32>>;
""" + function + """
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let i = id.x;
    if (i >= arrayLength(&inputs)) { return; }
    outputs[i] = vec4<f32>(apply_linear_exposure(inputs[i].xyz, inputs[i].w), inputs[i].w);
}
"""
    # Negative colors, signed zero, subnormals, HDR, boundaries, and seeded randoms.
    levels = np.array([-65504, -1, -0.18, -0.0, 0, np.nextafter(np.float32(0), np.float32(1)),
                       1e-6, 0.0031308, 0.18, 0.5, 1, 2, 16, 65504], dtype=np.float32)
    evs = [-10, -2.5, -1, -0.01, 0, 0.01, 0.5, 1, 3.25, 10]
    rows = [[v, levels[(i + 3) % len(levels)], levels[(i + 7) % len(levels)], ev]
            for ev in evs for i, v in enumerate(levels)]
    rng = np.random.default_rng(20261101)
    random = np.column_stack((rng.uniform(-2, 32, (4093, 3)), rng.uniform(-10, 10, 4093))).astype(np.float32)
    inputs = np.concatenate((np.array(rows, dtype=np.float32), random))
    with tempfile.TemporaryDirectory() as temp:
        spv = Path(temp) / "exposure.spv"
        subprocess.run(["glslangValidator", "-V", "--target-env", "vulkan1.1", "-o", str(spv),
                        str(Path(__file__).with_name("exposure.comp"))], check=True)
        spirv = spv.read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "reference.wgsl").write_text(shader)
    (args.output / "translated.spv").write_bytes(spirv)
    for count in [1, 63, 65, len(inputs)]:
        inputs[:count].tofile(args.output / f"{count}-input.f32")
    if args.prepare_only:
        print("Prepared upstream WGSL, translated SPIR-V, and deterministic float buffers")
        return
    device = get_default_device()
    results = []
    # Includes non-workgroup-aligned lengths to verify bounds handling.
    for count in [1, 63, 65, len(inputs)]:
        data = inputs[:count].copy()
        def execute(code):
            result = compute_with_buffers({0: data}, {1: (count, 4, "f")}, code, n=((count + 63) // 64, 1, 1))
            return np.frombuffer(result[1], dtype=np.float32).copy().reshape(count, 4)
        upstream, port = execute(shader), execute(spirv)
        # RGB only for numerical statistics; alpha contains EV and is checked too.
        unequal = upstream.view(np.uint32) != port.view(np.uint32)
        absolute = np.abs(upstream[:, :3].astype(np.float64) - port[:, :3].astype(np.float64))
        ulp = np.abs(ordered_bits(upstream[:, :3]) - ordered_bits(port[:, :3]))
        zero = data[:, 3] == 0
        identity = bool(np.array_equal(upstream[zero, :3].view(np.uint32), data[zero, :3].copy().view(np.uint32))
                        and np.array_equal(port[zero, :3].view(np.uint32), data[zero, :3].copy().view(np.uint32)))
        def quantize(a):
            return np.rint(np.clip(a[:, :3].astype(np.float64), 0, 1) * 65535).astype(np.int64)
        qdiff = np.abs(quantize(upstream) - quantize(port))
        results.append(dict(samples=count, unequal_components=int(unequal.sum()),
                            max_absolute=float(absolute.max()), mean_absolute=float(absolute.mean()),
                            max_ulp=int(ulp.max()), quantized_rgb16_max=int(qdiff.max()),
                            quantized_rgb16_unequal=int(np.count_nonzero(qdiff)), zero_ev_bit_identity=identity))
        args.output.mkdir(parents=True, exist_ok=True)
        for name, value in [("input", data), ("wgsl", upstream), ("glsl", port)]:
            value.tofile(args.output / f"{count}-{name}.f32")
    report = dict(commit=manifest["commit"], stage="apply_linear_exposure", adapter=dict(device.adapter.info),
                  wgpu_version=wgpu.__version__, spirv_sha256=hashlib.sha256(spirv).hexdigest(),
                  scope="Host GPU execution; not an Android Vulkan parity result", cases=results)
    (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if any(r["unequal_components"] or not r["zero_ev_bit_identity"] for r in results):
        raise SystemExit("FAIL: exact-bit parity gate")


if __name__ == "__main__":
    main()
