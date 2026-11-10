# Exposure translation map

Pinned upstream: `f1d71a69eef813432db214f77969f567c41ff1d7`.
Path: `src-tauri/src/shaders/shader.wgsl`, lines 680–685,
symbol `apply_linear_exposure`.

| Upstream operation | GLSL translation |
|---|---|
| `fn` function returning `vec3<f32>` | Function returning `vec3` |
| `color_in: vec3<f32>, exposure_adj: f32` | `vec3 color_in, float exposure_adj` |
| Test `exposure_adj == 0.0` | Unchanged |
| Return `color_in` at zero | Unchanged |
| Multiply by `pow(2.0, exposure_adj)` | Unchanged; do not replace with exp2 or a LUT |

The reference function is extracted at runtime from the byte-verified WGSL;
it is not handwritten as a second reference implementation. The surrounding
buffer/dispatch wrapper and GLSL bindings are harness adapters. They pass
unmodified float32 RGB and exposure values in vec4 storage records, guard the
array length, and preserve EV in output alpha. Alpha is included in the bit gate.
No clamp, tone map, camera matrix, white balance, fitting, encoding, or rounding
is inserted into this isolated stage. Quantized RGB16 is an additional diagnostic
only; exact float output bits determine pass/fail.

The sample EV is already upstream's native exposure parameter. This test does
not yet verify UI/receipt-to-EV mapping, scene-linear working-space adapters,
RCD float16 import/export, or complete pipeline operation order. Such adapters
must receive independent reference gates before production integration.
