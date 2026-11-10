# Cinematic RAW Studio — Android

Native Android editing for .CRAW video: library, RapidRAW Vulkan grading, multi-track timeline/audio, and H.264 / HEVC / Main10 or original CinemaDNG export.

## Current processing pipeline

1. CRAW decoding and recorded capture calibration.
2. The exact coherent-green/chroma MHC routines copied from the local RawLens working tree, pinned by file hashes in `third_party/rawlens-mhc-reference/manifest.json`.
3. Pinned RapidRAW host parameter preparation and Vulkan compute grading (`f1d71a69eef813432db214f77969f567c41ff1d7`).
4. Preview frame assembly on the GPU; Main10 fitting/BT.709 P010 conversion on the GPU, followed by a bounded readback/copy into MediaCodec's planes.

The old MLV-App processing, CPU grading fallback, RCD backend and old editor controls have been removed. There is one new renderer for single-take editing, project monitoring and exports.

## Toolset

This delivery follows the requested **basic, AI-free Vulkan grading** scope:

- Exposure, contrast, brightness, highlights/shadows, whites/blacks.
- As-shot white balance with temperature/tint adjustments, saturation, vibrance and hue.
- HSL mixer, four color wheels, color calibration and monochrome controls.
- Point and parametric curves, including the original curve scaling/math.
- Sharpening, clarity/dehaze/structure, GPU luma/chroma noise reduction and chromatic aberration adjustments.
- Glow/halation/flare, vignette and grain.
- Imported cube/3dl/Hald LUTs; geometric and brush local-adjustment masks.
- Original RapidRAW output plus retained Filament AgX and ACES 2.0 outputs.

AI inference/models, generated patches, depth/normal-map effects, negative-film conversion and advanced geometry are outside this basic delivery. Imported grades with active unsupported tools fail explicitly rather than render an incomplete grade.

## Playback, projects and export

Play/pause, frame stepping and continuous scrubbing use the sequence scheduler. Video follows mixed audio's output clock. Timeline cuts, trims, moves, linked camera audio, additional audio tracks, mute/solo and saved projects remain supported.

Preview uses an explicitly labeled half-size Bayer proxy and a 64 MiB cache of graded frames. Cache keys contain the source frame and complete-grade fingerprint. Export uses full source resolution before fitting to the hardware encoder's output limits. Preview native resources are released while the export queue is active.

Project version 2 stores complete RapidRAW grade envelopes. Version-1 grades are retained as opaque legacy JSON and require an explicit reset before rendering; their old slider values are never silently reinterpreted. Source takes are not changed. Original CinemaDNG/WAV conversion retains source samples and calibration.

## Validation status

See [docs/rapidraw-engine.md](docs/rapidraw-engine.md) and `docs/benchmarks/2026-11-10/` for the current local checks and their limits. Local tests cover timeline scheduling/persistence, parameter math, exact curve float bits, coherent MHC invariants, GPU frame-assembly identity and high-precision/P010 output.

**Native 30 fps playback and export throughput have not been established for this replacement on Android hardware.** Earlier RCD/MLV performance results do not validate the new pipeline. Host synthetic timings include multiple test renders and must not be presented as playback/export fps.

## Requirements

Android 11/API 30 or newer, ARM64 or ARMv7, and a working Vulkan implementation supporting the compute graph's texture/buffer formats. Unsupported devices report an error; there is no legacy CPU-grade fallback.

A build requires the Android SDK, NDK 27.0.12077973, Rust/cargo and the Rust targets `aarch64-linux-android` and `armv7-linux-androideabi`. Use `rustup target add aarch64-linux-android armv7-linux-androideabi` before the first native build.

## Building

Requires the Android SDK and NDK 27 (`local.properties` with `sdk.dir`).

```sh
./gradlew :app:assembleDebug          # debug build
./gradlew :app:assembleRelease        # release (R8, resource shrinking, -O3 native)
```

The native build compiles MLV-App, the CRAW libraries and the Vulkan
shaders. On a laptop it can saturate every core; to keep the machine
usable:

```sh
nice -n 19 ./gradlew --max-workers=2 :app:assembleRelease
```

### Release signing

Release builds read the key from the environment; nothing is stored in the
repository:

```sh
export CRS_KEYSTORE=/absolute/path/to/release.jks
export CRS_KEY_ALIAS=…
export CRS_KEYSTORE_PASSWORD=…
export CRS_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```

If `CRS_KEYSTORE` points at a missing file, or a password or the alias is
missing, the build stops. Without `CRS_KEYSTORE`, release builds are signed
with the standard debug key, so anyone can build and install them. Check
the signer with
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

## CRAW Compact

Library takes can be converted losslessly to Compact in their original directory. Conversion runs in the background queue, preserves source metadata/audio/motion data, and fully verifies the published file before offering to keep or delete the original. Saved project links continue through a durable source replacement mapping. No original is automatically removed. See [the Compact workflow and validation](docs/compact-conversion.md).

## Headless checks

```sh
python3 tools/verify_craw_sources.py
python3 tests/compact/run.py
python3 tools/verify_rapidraw_reference.py
python3 tools/generate_rapidraw_schema.py --check
python3 tools/port_rapidraw_core.py --check
python3 tools/port_rapidraw_renderer.py --check
python3 tools/port_rapidraw_masks.py --check
python3 tools/port_rawlens_mhc.py --check
cargo test --locked --manifest-path app/src/main/rust/rapidraw-core/Cargo.toml --lib
cargo run --locked --features gpu --manifest-path app/src/main/rust/rapidraw-core/Cargo.toml --bin render-fixture -- /tmp/crs-rapidraw-fixtures
./gradlew :app:testDeviceTestUnitTest :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest :app:assembleRelease
```

The Android instrumentation runner supports headless engine/source and project/video/audio checks. No current device deployment or visual QA is implied by a local APK build.

## Source and licensing

The Android shell is Kotlin/Compose, source decoding is C++, and demosaic/grading/delivery are Vulkan through the independent Rust host. Frozen upstream code and generated adapters are kept separate so changes are reviewable.

| Component | Use | License |
|---|---|---|
| RapidRAW reference/core/shaders | Parameter preparation and Vulkan grading | AGPL-3.0 |
| RawLens MHC reference | Exact coherent reconstruction routines | GPL-3.0-or-later |
| CRAW encoder/decoder and Clip helper | Source decoding, metadata, audio and original DNG | Apache-2.0 |
| OpenColorIO ACES 2.0 | Retained output-transform reference | BSD-3-Clause |
| Google Filament AgX | Retained output transform | Apache-2.0 |

App sources retain their notices. Bundled component licenses are available in About; distribution must comply with the included RapidRAW AGPL terms. No release has been published by this local migration.
