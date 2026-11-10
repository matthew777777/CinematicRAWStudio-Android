> Historical checkpoint; superseded by the AI-free MHC/RapidRAW delivery in [rapidraw-engine.md](rapidraw-engine.md).

# RapidRAW migration — stopped at user request, 2026-11-10

## User instructions to retain

- Complete replacement of the app's editing/processing pipeline with pinned RapidRAW, including playback, scrubbing, timeline preview, grading, and export. Do not leave old MLV-App CPU/GPU editing code or a legacy fallback.
- All existing transport/timeline functions must work: play/pause, continuous scrubbing/seeking, frame stepping, cuts/trims/moves, linked audio/audio-clock sync, grade changes, save/reopen, and exports.
- Keep the app's Filament AgX and ACES 2.0 output transforms. Preserve CRAW source decoding, capture calibration, audio, and original CinemaDNG export.
- Target native 30 fps graded/ungraded playback and comparable/faster export. Proxies and bounded Vulkan/frame caches are authorized; do not report proxy throughput as native, or claim 30 fps without measurements.
- Work locally/headlessly. UI polish and general bug cleanup are deferred, but functional controls/transport are required.
- **LATEST CHANGE:** REMOVE RCD COMPLETELY. Replace it with an exact copy of the user's improved MHC from the local RawLens project. This supersedes earlier instructions to retain RCD. MHC replacement has been investigated but NOT implemented.
- Last user message: **“note everything and stop”**. Stop work after this checkpoint; resume only when asked.

## Reference pins

RapidRAW reference: `f1d71a69eef813432db214f77969f567c41ff1d7`, 232 frozen source files in `third_party/rapidraw-reference`, checked by `tools/verify_rapidraw_reference.py`.

Local RawLens: `/Users/monikamalinowska/AndroidStudioProjects/RawLens`; HEAD inspected at stop: `93a5fc3dd8f985f18f756257d5fb4acda9333678`. The working tree may contain the user's newer improvements; pin hashes of the actual files copied, not HEAD alone.

MHC sources discovered (read only so far):

- `app/src/main/java/com/matthew/rawlens/VfMhc.kt`: exact push-constant packing, FMA bias `-black * invRange`, 10 integer/8 float API, asynchronous submit/sync fd.
- `app/src/main/java/com/matthew/rawlens/MhcReference.kt`: CPU reference, improved edge/chroma behavior.
- `app/src/main/assets/shaders/vf/vf_mhc.comp` and `.spv`: standalone camera-linear RGBA16F MHC-family demosaic with the user's edge-directed green/chroma, phase-preserving mirrors, fp32 calculations, negative clamp and output ceiling 4.0.
- `vf_mhc_green.comp`/`.spv`: coherent green plane used by newer fused variants. Do not assume standalone and fused paths have identical improvements: inspect current RawLens recorder dispatch and validation docs before choosing the exact implementation the user intends.
- `app/src/main/cpp/vf_vulkan_vf_mhc_stab.inc`: standalone MHC JNI init/submit near the beginning; later fused implementations have additional dependencies.
- `tools/mhc-highlights/` and `docs/refactor/mhc-highlights-2026-11-01/`: parity/performance/acceptance harness and shader hashes.
- Repo currently vendors an older `third_party/rawlens-gpu` from `15574785be9949b6a67a753145cfd5d0ec50320e`. It has standalone MHC JNI entry points but has NOT been updated or verified against current RawLens. Do not substitute that older copy for the user's requested exact current implementation.

## Current code changes

### Old engine removal and new routes

- Deleted Kotlin `PlayerScreen`, `PlayerEngine`, `EditorPanel`, `CurveEditor`, `MlvGrade`, `ClipTimeline`, and the old `GpuRenderer` implementation.
- Removed MLV native build linkage and deleted native `grade_jni`, `mlv_gpu`, `raw_tools`, and their headers. Deleted `mlv_grade.comp`/`mlv_output.comp`, `third_party/mlv-app`, and `tools/sync_mlvapp.sh`.
- Retained display-transform GLSL sources for the generated RapidRAW output hooks. MLV patch files under `tools/patches/mlv-app` and legacy test/docs references still exist; clean these up when resuming. No claim of a finished zero-legacy audit.
- `MainActivity` opens `RapidEditorScreen` for normal single-take editing, with project screens retained.
- `ProjectFrameRenderer` now uses `RapidRcdSource` + `RapidComputeSession` exclusively, with full-resolution export default and explicit proxy mode for `SequencePlayer`. **Rename/replace this source and remove all RCD dependencies for latest MHC request.**
- AVC/8-bit export uses RapidRAW preview output; HEVC Main10 uses the new RGBA16F -> P010 path, preserving precision until final YUV quantization.
- Added a 64 MiB LRU of graded source bitmaps for proxy scrubbing. Cache keys include source URI, source frame and complete grade envelope. Preview monitor canvas is capped at 1280 longest dimension; export canvas is not capped.
- `SequencePlayer` keeps serial render ownership, timeline schedule and audio master clock. Grade/project update resumes playback if it was previously playing. Generation tokens coalesce pending seeks and suppress stale frames.
- Single-take editor has play/pause, live seeking, +/- frame stepping, undo/redo, copy, output selection and export. Uses the same sequence scheduling/audio/export code. UI has not been visually validated, per local/headless instruction.

### Projects and persistence

- Project version is now 2. `SequenceClip` stores `RapidGradeSnapshot` and optional `legacyGrade` JSON.
- Version-1 project reads preserve old receipt JSON as opaque data. Rendering/export rejects a legacy grade until explicit reset; old values are not silently reinterpreted or executed.
- Timeline grading panel and import/copy/export actions use new RapidRAW grades.
- `RapidGradeStore` is a separate AtomicFile namespace keyed by source URI SHA-256. CinemaDNG conversion sidecars now include a RapidRAW envelope where available.

### Native/Rust engine

- `app/src/main/rust/rapidraw-core`: exact generated upstream parameter scales/WB/math/shader/compute graph plus independent source/JNI adapters. Android backend is Vulkan; host fixture uses the native primary backend (Metal on this Mac).
- `render.rs` now adds `render16` and `crs_rapidraw_render16`, using upstream `RenderOutputPrecision::SixteenBit` (RGBA16F half words).
- `rapidraw_host_jni.cpp` adds `nativeP010`: bounds/stride-checked direct encoder planes, aspect-fit bilinear sampling, BT.709 limited 10-bit luma/chroma, 2x2 chroma averaging. No 8-bit preview intermediate in Main10 path. This is currently CPU delivery conversion/readback, NOT final zero-copy Vulkan-to-codec output.
- `CrawClip` no longer calls the old CRAW TUI developer or old raw editing tools. Thumbnails now use the new engine. Camera calibration matrices, raw decode/lens shading, audio and DNG remain.
- Added recorded-RGB cell unpack/normalization into camera RGBA16F AHardwareBuffer in `clip_jni.cpp`, then RapidRAW calibration/grade. This path is NEW and NOT verified on actual recorded-RGB footage.
- `RcdPipeline.kt` is the residual old RawLens device bootstrap, still initializes packed RCD and grade infrastructure. Latest requirement means replace/remove it completely; do not retain RCD as fallback.

### Missing files recovered this resumed session

At resume, the untracked Kotlin `rapidraw/` directory and generated `assets/rapidraw/` were missing from disk, though their callers and Rust sources remained. The old /tmp Rust toolchain was also gone. Restored:

- `RapidRawGrade.kt`: schema/envelope, immutable grades, curves/wheels, `RapidGradeSnapshot`.
- `RapidDefaults.kt`: generated pure-Kotlin embedded defaults/schema via updated `tools/generate_rapidraw_schema.py` (plus JSON assets).
- `RapidCurves.kt`: pinned parametric curve arithmetic (Double ops -> shader float32 bits).
- `RapidHost.kt`, `RapidComputeSession.kt`, `RapidGradeStore.kt`, `RapidRcdSource.kt`.
- `RapidEditorScreen.kt`: functional editor/transport shell and reusable `RapidPanel`.

The recovered UI uses generic recursive controls in several sections rather than the earlier richer custom wheel/curve widgets described in older notes. Do NOT claim full UI or all-tool parity. Resource-dependent/AI tools still fail explicitly. Curves have point and parametric controls. There are unfinished validation/range/gesture details to audit, including curve split constraints, coalesced undo during continuous edits, and actual color-wheel interaction.

## Immediate unfinished blocker introduced just before stop

New `tools/port_rapidraw_masks.py` extracts pinned mask rasterization and the AI mask *parameter* declarations without Tauri or inference. Outputs `generated/mask_raster.rs` and source manifest. Added `base64 = 0.22.1`, enabled image PNG, updated Cargo.lock; `lib.rs` exposes mask_raster behind `gpu`.

**Current GPU Cargo check FAILS:** `src/generated/mask_raster.rs:378` calls `imageproc::filter::gaussian_blur_f32`; `imageproc` dependency is not yet added. Add the exact upstream-compatible version/features and recheck before building. No substitute blur or silently disabled feathering.

Mask port is NOT yet connected to JNI/session resources or frame rendering. `RapidComputeSession` still rejects nonempty masks and non-null LUT resources. Negative conversion, AI/BM3D denoise, lens blur, relight/fog, cleanup/AI patches, geometry/lens correction and some resource workflows remain unconnected. Retain explicit failures; do not claim all tools are ported. Need audit all 124 parameters and inventory against actual execution, not just controls/model presence.

## Validation evidence and limits

This resumed session:

- `tools/verify_rapidraw_reference.py`: PASS, 232 pinned files.
- `tools/port_rapidraw_core.py --check`: PASS, six source extracts.
- `tools/port_rapidraw_renderer.py --check`: PASS.
- `tools/generate_rapidraw_schema.py --check`: PASS, 124 defaults / 46 static numeric ranges.
- Rust core `cargo test --locked --lib`: **7 passed** (`/tmp/crs-rapidraw-core-new.log`). This was before adding mask module dependency and is not GPU/all-feature acceptance.
- Android Kotlin compile reached success after restoring `timecode`, before subsequent cache/test/mask edits.
- `app/build/test-results/testDeviceTestUnitTest/TEST-com.matthew.crs.project.SequenceTest.xml`: **11 tests, 0 failures/errors**, timestamp 2026-11-10 07:22:28Z. Includes existing project operations and Rapid grade roundtrip adaptations.
- Added `RapidMigrationTest.kt` for outputs/full envelopes, timeline cut/move/trim + linked audio, opaque v1 migration/reset, and 13 upstream curve float-bit fixtures. **New tests have not been confirmed complete.**
- Full build log `/tmp/crs-rapidraw-full-build.log` reached both ABI native tasks, unit tests, and dex/strip stages, but no final success was recorded at stop. New mask dependency failure also invalidates claiming a final build.
- **No final APK acceptance, no new phone deployment, no manual UI acceptance, no native 30fps or export throughput claim.** Prior 2026-11-03 parity/device evidence remains under `docs/benchmarks`, but predates these routing/deletion/model/source changes and is not current acceptance.

Important small audit issue: recovered `Timecode.kt` uses `fps.toInt()` although its comment says rounded nominal rate; 29.97 currently becomes 29. Correct with rational/rounded nominal handling when resuming.

## Local toolchain / running tasks at stop

Rust reinstalled only in /tmp:

```
CARGO_HOME=/tmp/crs-parity-cargo
RUSTUP_HOME=/tmp/crs-parity-rustup
/tmp/crs-parity-cargo/bin/cargo
```

Android Rust targets `aarch64-linux-android`, `armv7-linux-androideabi` installed. NDK `27.0.12077973`. Use `CARGO_BUILD_JOBS=2` or 3 to bound memory. Gradle variant is `deviceTest`; use `:app:testDeviceTestUnitTest`, not `testDebugUnitTest`.

Running-check sessions at stop were sent Ctrl-C: full build `8331`, secondary unit run `24188`, mask check `58364` (already failed). A targeted process check found no matching Gradle wrapper/launcher process. No global Gradle daemon stop was issued because another project is active on this machine. Do not kill unrelated RawLens processes.

No commits, pushes or publishing were performed. The repository contains a large dirty working tree, including user/preexisting work: do not reset/revert indiscriminately. No edits were made to the local RawLens source tree.

## Resume order (only if user requests)

1. Honor latest MHC directive: inspect current active improved RawLens path, pin/copy exact source + binary + packing + necessary dispatch dependencies; build an exact hash/parity check. Replace `RapidRcdSource`/RCD bootstrap and remove RCD shaders/JNI/dependencies completely, with no fallback. Update thumbnails, editor, timeline, export, tests and About/docs labels consistently.
2. Fix mask imageproc dependency and wire actual mask resources, consistent full/proxy coordinate scaling and local-grade ordering. Then LUT/host effects/geometry and all missing inventory items with explicit same-source tests.
3. Finish headless Kotlin/Rust/native/Android builds and timeline/transport/export acceptance; verify latest changed code, not older evidence. Do not label scope finished while unsupported controls or broken build remain.
4. Optimize transfers, pooling/prefetch and GPU encoder interop; benchmark native versus proxy/cached playback separately and real export throughput with all supported outputs and grades.
