# RapidRAW exposure parity

> Historical report: superseded by the current MHC/RapidRAW renderer. Removed-backend instructions, architecture and measurements below must not be used as current implementation or performance acceptance. See `docs/rcd-history-removal-2026-11-10.md`.


This is an isolated executable reference comparison, not a production grade.
`run.py` extracts `apply_linear_exposure` unchanged from the hash-verified pinned
WGSL, executes it, compiles `exposure.comp` to Vulkan 1.1 SPIR-V, and executes
that module with the same buffers and adapter through wgpu-native. The GLSL
translation changes function/type syntax only. Input RGB and EV are packed into
vec4 storage buffers; output alpha preserves EV. Neither wrapper applies a
display transform or half-float conversion.

Requirements: Python, `wgpu==0.32.0`, `numpy==2.5.4`, `glslangValidator`, and GPU
access. An environment in `/tmp` keeps dependencies outside the app build.

```sh
python3 tools/verify_rapidraw_reference.py
python tests/rapidraw-parity/run.py --output /tmp/crs-rapidraw-exposure-parity
```

The JSON report records backend/device, reference commit, SPIR-V hash, unequal
bits, absolute error, ULP error, and normalized RGB16 quantization error. Every
unequal output bit fails. Full input and output float32 buffers are retained.
Cases include signed zero, subnormals, negative values, HDR, fractional EV,
seeded random values, and dispatch boundary lengths 1/63/65/4233.

**Scope:** on macOS both shaders execute through Metal after wgpu translation.
This validates the comparison harness and isolated function on that backend;
it does not establish Android Vulkan parity, full RapidRAW pipeline parity,
RCD half-float boundary behavior, or performance. No production switch is made.
The device Vulkan execution comparison is still required before enabling this
stage in the app. Preserve the exact gate even if another device fails it.

## Same-device Android Vulkan gate

`device.py` compiles the unchanged extracted WGSL with Naga matching the frozen
upstream Cargo.lock (29.0.4), validates both SPIR-V modules, cross-compiles the
independent runner with Android NDK 27, and executes both modules on the phone.
It needs the host Python packages above, `spirv-val`, the NDK, Naga CLI, and adb.
The runner links its C++ runtime statically, uses coherent mapped storage buffers,
and waits on a fence plus a compute-to-host memory barrier before writing output.
Each module executes with its own clean buffers/device instance.

```sh
python tests/rapidraw-parity/device.py \
  --adb /path/to/adb --ndk /path/to/android-ndk \
  --naga /path/to/naga --output /tmp/crs-rapidraw-android-parity
```

No app installation or production code change is needed. The runner, shaders,
and fixtures are placed in `/data/local/tmp/crs-rapidraw-exposure-parity`.
The report and raw float buffers are retrieved into the output directory.
Any unequal bit fails; up to 16 mismatch examples per dispatch are recorded.
The gate is isolated function parity, not full texture/half-float pipeline parity,
full RapidRAW host preparation parity, or a playback/export performance claim.

`exposure.comp` is derived from RapidRAW under AGPL-3.0; the upstream license is
in `third_party/rapidraw-reference/LICENSE`. The app does not link this harness.
