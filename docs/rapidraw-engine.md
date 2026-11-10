# RapidRAW basic Vulkan engine — 2026-11-10

## Approved scope

The latest user instruction is basic, fast Vulkan grading with **no AI**. This supersedes the earlier full feature-clone plan and the stopped-work checkpoint. Retain playback/scrubbing, timeline/audio scheduling, export, Filament AgX and ACES 2.0. Replace RCD completely with the user's improved coherent MHC from RawLens.

## Implementation

The default single-take editor and project renderer now call the independent RapidRAW Rust engine. The old MLV Kotlin editor, CPU/GPU processing, native linkage, source tree, raw-tools stages and old RCD backend/shaders are removed. The CRAW TUI CPU developer is also removed from this repository/build.

MHC uses the unchanged green pass from `vf_mhc_green.comp` and unchanged coherent reconstruction functions from `vf_mhcyuv.comp`, copied from the actual local RawLens working tree. An isolated adapter changes the final output to camera-linear RGBA16F instead of running RawLens's recording grade. The source files, hashes, reference CPU twin and license are frozen under `third_party/rawlens-mhc-reference`. `tools/port_rawlens_mhc.py --check` verifies the exact extracts. The shader compiler omits its `-O` SPIR-V rewrite because that rewrite generated an import pattern rejected by Naga; grading/reconstruction arithmetic is unchanged.

MHC and RapidRAW share one wgpu device/queue. Bayer data is decoded into a CPU-accessible blob and uploaded once. Camera RGB remains on the GPU through dual-illuminant calibration and the grading graph. A recorded-RGB source adapter unpacks recorded camera-linear cells and uses the same new grade path.

The 17-case synthetic suite covers default/exposure, tonal, detail/noise, glow/halation/flare, grain, monochrome, RapidRAW AgX, retained Filament/ACES outputs, wheels, curves, HSL, radial/brush masks and LUTs. Masks are rasterized only when their definitions/scale change; their adjustments execute in the compute graph. Imported LUTs use app-owned storage and the exact upstream parsers/sampling code.

Preview now assembles tiles on the GPU and reads back once per frame. Main10 export assembles the unchanged high-precision RGB16F shader output on the GPU, then fits and converts it to limited-range BT.709 P010 on the GPU. Persistent output/readback buffers are reused. The remaining CPU operation is copying finished P010 words into the encoder's stride-aware Y/U/V views; RGB grading/conversion is not performed there. The plane helper handles U/V views whose final row limits do not cover the entire shared interleaved allocation.

Single-take and timeline playback use `SequencePlayer`, its generation-based stale-frame suppression and mixed-audio master clock. Preview uses an explicitly labeled proxy and 64 MiB graded-frame cache. Full-resolution export does not use proxy frames. `releaseForExport` relinquishes native preview resources while queued exports run. Export settings and projects contain immutable complete RapidRAW grade snapshots.

Projects are version 2. Version-1 grade JSON is preserved without an executable legacy engine and requires explicit reset before processing. Per-source grades and copied settings use separate RapidRAW storage. Original CinemaDNG/WAV export remains source-preserving.

## Verified locally

- Frozen reference and generated parameter/schema/renderer/MHC/mask checks.
- Seven Rust core tests for scales, packed layout, WB, bypass, mask preparation and FFI validation.
- Fifteen JVM tests for sequence operations, linked audio, complete grade/output persistence, opaque legacy migration/reset, and 13 executed upstream curve fixtures at exact float32 bits.
- MHC measured-CFA sample preservation and flat-field preservation at exact binary16 bits in all four Bayer phases.
- All 17 preview outputs are byte-identical before/after GPU frame assembly.
- All 17 assembled RGB16F outputs match upstream CPU-readback output words exactly.
- GPU P010 checks cover output size, finite high-precision input, MSB-aligned words and legal limited-range code bounds. Full pipeline reset restores the exact default frame.

Host GPU checks ran headlessly on Apple M1/Metal. Android builds compile the native Vulkan backend for both ARM ABIs. These are not Android display/cadence or real-video export throughput measurements.

## Limits

No AI code/model/runtime or depth/normal-map effects are part of the active toolset. Negative-film conversion and advanced geometry are also excluded from this basic grading delivery. Unsupported imported adjustments report an explicit error and can be cleared with Reset all; no CPU/legacy fallback is used.

The Android release/instrumentation APKs are local artifacts. This turn does not establish phone runtime acceptance, visual UI correctness, recorded-RGB image parity, native 30 fps playback, export fps, or complete CRAW-versus-LibRaw source-image identity. Earlier phone/RCD checkpoints and historical docs must not be used as evidence for those claims.

The retained output-transform GLSL and frozen RapidRAW extracts remain separately auditable. Source math is preserved; no broad numeric tolerance was introduced to call a failing image-parity comparison a pass.

## Local build artifact

`app/build/outputs/apk/release/app-release.apk` (25,357,521 bytes). SHA-256: `51018c883ed65c0bb9203a21fbc06521bbabd406bb7a440cf1b937186d67f177`. Release, deviceTest and instrumentation APK builds succeeded together in one non-overlapping Gradle invocation. The APK contains ARM64/ARMv7 RapidRAW host and CRAW libraries, with no RCD/MLV shaders or old RawLens backend library. See `docs/benchmarks/2026-11-10/local-release-check.json`.
