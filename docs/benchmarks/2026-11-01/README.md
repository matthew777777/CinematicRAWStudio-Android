# Initial pipeline baseline — 2026-11-01

> Historical report: superseded by the current MHC/RapidRAW renderer. Removed-backend instructions, architecture and measurements below must not be used as current implementation or performance acceptance. See `docs/rcd-history-removal-2026-11-10.md`.


Device: Xiaomi `25080RABDG`. Bayer source: 4080x2040, 30 fps.
Release-optimized, unminified deviceTest build. This measures the old pipeline
before replacing it. RCD, display transforms, and editing algorithms were not
changed; diagnostic timings and instrumentation were added.

## Export and sequential development

30 frames per case. Both export codecs use a 1280x720 / 30 fps canvas at
12 Mbps; RAW development still runs at full source resolution. Export fps
includes initialization, as-shot fitting, encoder drain, and file delivery.

| Grade | Warm full-resolution render fps | H.264 export fps | Main10 export fps |
|---|---:|---:|---:|
| Default | 5.35 | 3.45 | 3.68 |
| Filament AgX | 5.78 | 3.28 | 4.20 |
| ACES 2.0 | 4.28 | 3.00 | 3.85 |
| Sharpening / CPU fallback | 2.26 | 1.75 | 1.77 |

Independent ffprobe verification of all eight files: exactly 30 video frames,
1280x720, 30/1 frame rate, H.264 yuv420p or HEVC Main10 yuv420p10le.
Details: `export-verification.json`. These are correctness/initial-cost samples,
not sustained native-resolution export or thermal-throughput acceptance.

## Playback delivery

Warm playback: 120 scheduled source frames (four seconds on the source clock).
Values below are delivered frames, not average display fps. A final render can
finish after the playback window; the instrumentation does not measure display
presentation. The first frame prewarms grading and as-shot fitting; playback
allocations and decoding may still incur startup costs.

| Mode | Native frames delivered / 120 | Proxy frames delivered / 120 |
|---|---:|---:|
| Ungraded base development | 71 | 52 |
| Default grade | 18 | 32 |
| Filament AgX | 25 | 34 |
| ACES 2.0 | 25 | 22 |
| Sharpening / CPU fallback | 8 | 17 |

Proxy uses the existing half-width/half-height path, including full source
decode and CPU binning/shading. Ungraded base development uses existing RCD
plus camera WB/matrix and BT.709 output, without the adjustment engine.
Runs were sequential, not controlled repeated trials; battery temperatures
rose from 33.5 C to 37.8 C and CPU/GPU scheduling can vary. Android reported
thermal status 0 at sampled checkpoints. Do not infer a universal proxy/native
speed ratio: proxies were slower for ungraded and ACES in these short samples.
Neither path meets the 30 fps target.

Cold playback in the first test delivered only one frame in each 30-frame
window, dominated by first-time as-shot fitting (approximately 1.7–2.2 seconds)
plus grade preparation. Warm runs separate this startup cost from delivery.

## Measured costs and next implementation priorities

Default warm full-resolution sequential rendering averages approximately:
25 ms native decode, 23 ms shading/copy, 46 ms RCD, 74 ms Vulkan grading with
readback, and 14 ms bitmap fitting. These measured synchronous calls include
waits and should not be treated as pure GPU kernel timings. CPU sharpening
raises full-resolution grading/readback to approximately 312–331 ms per frame.
H.264 upload/draw/submission costs about 3 ms per frame in these exports.

The results support replacing CPU fallbacks and the bitmap/readback grade
boundary, pooling GPU resources, keeping compatible intermediate buffers on
the GPU, overlapping decode and processing with bounded queues, and moving
reusable as-shot/parameter preparation out of frame delivery. Preserve RCD and
the requested output transforms while measuring their execution costs.
Use bounded frame/intermediate caches with precise invalidation; retain Vulkan
pipelines and use compatible pipeline caches to reduce startup compilation.
Pipeline caches alone will not remove repeated decode, shading, RCD, or grade
costs. Measure genuine GPU execution separately before choosing kernel changes.

Process PSS grew during sequential cases; these samples do not establish a
memory leak. The replacement needs an explicit cache budget and a longer
memory/thermal run before acceptance.

## Isolated exposure parity

The frozen RapidRAW `apply_linear_exposure` WGSL function and its GLSL/SPIR-V
translation produced zero unequal output bits on Apple M1 / Metal for dispatch
lengths 1, 63, 65, and 4233, including signed zero, subnormals, negative/HDR RGB,
fractional exposure, and seeded random inputs. Zero exposure preserved input
bits. This is not Android Vulkan or full-pipeline parity. Report:
`rapidraw-exposure-host.json`; executable harness: `tests/rapidraw-parity/run.py`.

The isolated function also passes on the target Android Vulkan GPU, Mali-G615
MC2, using Naga 29.0.4 matching upstream Cargo.lock. The same four dispatch
lengths produce zero unequal float bits and preserve zero-EV input bits.
Both SPIR-V modules pass `spirv-val --target-env vulkan1.1`.
Report: `rapidraw-exposure-android.json`; runner: `tests/rapidraw-parity/device.py`.
The report records driver, compiler versions, module hashes, and input hashes.
No production grade was switched, and this does not establish full-pipeline
parity, float16 boundary parity, throughput, or 30 fps playback.

Raw device logs: `pipeline-device.txt`, `playback-native-warm.txt`, and
`playback-proxy-warm.txt`. Reproduction instructions: `docs/pipeline-benchmark.md`.
The first raw log predates the dedicated benchmark PASS wording and ends with
the runner's old generic integration message; only benchmark paths ran.
