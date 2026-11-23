# RapidRAW 1.7 Vulkan engine

Cinematic RAW Studio 0.2.0 uses RapidRAW 1.7.0 for preview and delivery. The active snapshot is pinned under `third_party/rapidraw-1.7-reference` and verified by `tools/verify_rapidraw_reference.py`. The [README](../README.md) lists the Android toolset.

## Implementation

The default single-take editor and project renderer now call the independent RapidRAW Rust engine. The old MLV Kotlin editor, CPU/GPU processing, native linkage, source tree, raw-tools stages and old RCD backend/shaders are removed. The CRAW TUI CPU developer is also removed from this repository/build.

MHC uses the unchanged green pass from `vf_mhc_green.comp` and unchanged coherent reconstruction functions from `vf_mhcyuv.comp`, copied from the actual local RawLens working tree. An isolated adapter changes the final output to camera-linear RGBA16F instead of running RawLens's recording grade. The source files, hashes, reference CPU twin and license are frozen under `third_party/rawlens-mhc-reference`. `tools/port_rawlens_mhc.py --check` verifies the exact extracts. The shader compiler omits its `-O` SPIR-V rewrite because that rewrite generated an import pattern rejected by Naga; grading/reconstruction arithmetic is unchanged.

MHC and RapidRAW share one wgpu device/queue. Bayer data is decoded into a CPU-accessible blob and uploaded once. Camera RGB remains on the GPU through dual-illuminant calibration and the grading graph. A recorded-RGB source adapter unpacks recorded camera-linear cells and uses the same new grade path.

The synthetic suite covers default/exposure, tonal, detail/noise, glow/halation/flare, grain, monochrome, RapidRAW AgX, retained Filament/ACES outputs, wheels, curves, HSL, radial/brush masks and LUTs. Masks are rasterized only when their definitions/scale change; their adjustments execute in the compute graph. Imported LUTs use app-owned storage and the exact upstream parsers/sampling code.

Preview now assembles tiles on the GPU and reads back once per frame. Main10 export assembles the unchanged high-precision RGB16F shader output on the GPU, then fits and converts it to limited-range BT.709 P010 on the GPU. Persistent output/readback buffers are reused. The remaining CPU operation is copying finished P010 words into the encoder's stride-aware Y/U/V views; RGB grading/conversion is not performed there. The plane helper handles U/V views whose final row limits do not cover the entire shared interleaved allocation.

Single-take and timeline playback use `SequencePlayer`, its generation-based stale-frame suppression and mixed-audio master clock. Preview uses an explicitly labeled proxy and 64 MiB graded-frame cache. Full-resolution export does not use proxy frames. `releaseForExport` relinquishes native preview resources while queued exports run. Export settings and projects contain immutable complete RapidRAW grade snapshots.

Projects are version 2. Earlier RapidRAW grade envelopes migrate with their saved values. Pre-RapidRAW project receipts are preserved and require a reset before grading. Per-source grades and copied settings use separate RapidRAW storage. Original CinemaDNG/WAV export remains source-preserving.

## Mobile grading

The renderer builds 1.7 tone-base, guided coefficients and scene statistics on demand and caches them for the source frame. Contrast can require scene statistics even when the local tone controls are neutral. The visible scopes run outside the grading graph on bounded samples of the displayed bitmap.

## Validation

The 0.2.0 work passed 7 Rust core tests, Android JVM tests, and 96 Vulkan offline compiler jobs on Adreno A750, Mali-G615 and Mali-G720. On a Mali-G615 device, the optimized graph matched the equivalent eager RapidRAW 1.7 graph byte for byte across 54 RGBA8, RGBA16F and P010 fixture files. The fixture also checks measured CFA samples and flat fields in all four Bayer phases.

The fixture covers synthetic grading and readback. Sustained real-footage playback, export throughput, and gesture validation on Android remain separate checks. Earlier renderer numbers are preserved in the [November benchmark](benchmarks/2026-11-23/rapidraw-mobile-vulkan.md).
