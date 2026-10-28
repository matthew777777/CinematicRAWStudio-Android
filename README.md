# Cinematic RAW Studio — Android

A native Android editor for **.CRAW** raw video (from [RawLens](#related-projects)):
browse takes, grade them with MLV-App's own image processing, cut them on a
multi-track timeline with audio, and export H.264 / HEVC (including 10-bit
HEVC Main10) or the original frames as CinemaDNG.

Kotlin + Jetpack Compose UI, C++ and Vulkan for decoding, demosaicing and
grading. Licence: **GPL-3.0-or-later**.

## Features

**Library**
- Grid of takes from a folder (RawLens saves to `DCIM/RawLens`) with
  thumbnails and RAW/RGB, codec (Fast, Live, Live v2, Compact), audio and
  duration badges.
- Sort (date, name, size, duration) and filter (Bayer RAW, binned RGB, with
  audio); search.
- Long press: edit, paste settings, add to project, export video, export
  CinemaDNG, info (camera, ISO, shutter, as-shot white balance, codec, bit
  depth, audio), delete.

**Editor** — Lightroom-style layout
- Categories: Light, Colour, Curves, Detail, Effects, Optics, Profile. Every
  control is MLV-App's, with MLV-App's ranges and defaults.
- Sections can be collapsed, reset, or switched off in the preview to compare.
- Press and hold for the original, split before/after view, RGB histogram,
  full screen (tap the picture).
- Undo/redo, copy/paste settings between takes, named presets.
- MLV-App RAW corrections: vertical stripes, bad pixels, pattern noise,
  chroma smoothing.

**Projects** — multi-track timeline
- Video track with linked camera audio, extra audio tracks (WAV or any
  Android-decodable format), mute and solo, waveforms.
- Move and trim with live dragging and snapping to clip edges and the
  playhead; split, trim to playhead, duplicate, delete, grade per clip.
- Pinch or slider zoom; undo/redo; autosave. Rename, duplicate and delete
  projects.

**Export**
- Presets (UHD 10-bit HEVC, source 10-bit master, 1080p H.264, 1080p HEVC,
  720p preview) or custom codec, resolution and bitrate; mixed AAC audio.
- Sizes are fitted to the device's hardware encoder limits (e.g. 4080×2040 →
  3840×1920 on encoders capped at 3840 wide).
- Background queue in a foreground service with a progress notification; an
  Exports tab with cancel, retry, play and share.
- Original CinemaDNG sequence + WAV export.

## How the image is processed

1. **Decode** the .CRAW frame (vendored CRAW decoder, multi-threaded).
2. **Demosaic** on the GPU with RawLens's Vulkan Packed RCD pipeline.
3. **Grade** with MLV-App's processing:
   - paused frames and export: MLV-App's per-pixel loop as a Vulkan compute
     shader (`app/src/main/shaders/mlv_grade.comp`) reading MLV-App's own
     lookup tables;
   - settings that need MLV-App's spatial stages (clarity, shadows /
     highlights, sharpening, denoisers, LUTs, …) run MLV-App's own C code.
4. **Encode** with MediaCodec (P010 written directly by the GPU for export).

Grading code is not re-invented: it is MLV-App, vendored and checked against
the original by parity tests (see [Testing](#testing) and
[docs/mlv-app-parity.md](docs/mlv-app-parity.md)).

## Status

| phase | scope | status |
|---|---|---|
| 1 | Library, player with audio as master clock, frame step, scrubbing | done |
| 2 | RawLens Vulkan pipeline, half-res playback proxy, decode-ahead | done — 30 fps playback (CRAW-Live v2) on Dimensity 7300 |
| 3 | Editor on MLV-App processing and RAW corrections, per-take settings | done |
| 4 | Project timeline with audio | done; transitions and compositing not yet |
| 5 | H.264 / HEVC / HEVC Main10 export, queue, CinemaDNG export | done; HDR and intermediate codecs not yet |
| 6 | CinemaDNG sequences as sources | not started |

Known limitations:
- UHD 10-bit export runs at about 4–5 fps on a Dimensity 7300 (Mali-G615 MC2).
- Playback uses the RawLens GPU grade; MLV-App grading applies to paused
  frames and to export.
- Binned RGB takes play and export but cannot be graded.

## Requirements

- Android 11 (API 30) or newer, arm64, Vulkan with AHardwareBuffer import.
- Developed and tested on a Xiaomi 25080RABDG (MediaTek MT6878 / Dimensity
  7300, Mali-G615).

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

## Testing

**MLV-App parity (host vs phone, needs adb)**
- `tests/mlvapp-parity/run.sh` — every processing stage alone and all
  together: MLV-App built for the Mac vs for Android, 1 and 8 threads.
- `tests/mlvapp-parity/run_rawtools.sh take.craw` — the RAW corrections.

**Device tests** run on the `deviceTest` build type (release in every
respect but R8). Install it and its test APK, then:

```sh
./gradlew :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
adb install -r -t app/build/outputs/apk/deviceTest/app-deviceTest.apk
adb install -r -t app/build/outputs/apk/androidTest/deviceTest/app-deviceTest-androidTest.apk
adb shell cmd package compile -m speed -f com.matthew.crs   # full AOT, for benchmarks

R=com.matthew.crs.test/com.matthew.crs.ProjectInstrumentation
adb shell am instrument -w -e headless true $R                      # project, export, audio, CinemaDNG
adb shell am instrument -w -e headless true -e gpuparity true $R    # Vulkan grade vs MLV-App's C code
adb shell am instrument -w -e headless true -e main10hires true $R  # UHD HEVC Main10 export + speed
```

They read takes from the library folder chosen in the app: the full suite
needs a Bayer take and a binned RGB take; `main10hires` a Bayer take at least
3840 wide and 3 s long.

The minified release APK cannot host instrumentation (R8 removes Kotlin
runtime classes the test APK needs); smoke-test it by hand before a release:
open a take, grade, export, open About.

Unit tests: `./gradlew :app:testDeviceTestUnitTest`.

## Repository layout

```
app/src/main/java/com/matthew/crs/   UI, player, editor, library
app/src/main/java/com/matthew/crs/project/   timeline, audio mix, export, queue
app/src/main/java/com/matthew/rawlens/       RawLens Kotlin JNI classes (vendored)
app/src/main/cpp/      JNI: clip decoding, MLV-App bridge, Vulkan grade, P010
app/src/main/shaders/  Vulkan compute shaders (MLV-App grade, output)
third_party/           vendored sources, each with SOURCE.md and LICENSE
tests/                 MLV-App parity tests
tools/                 sync scripts for third_party, MLV-App patches
docs/                  parity results, editor and export notes
```

## Third-party code

Vendored unchanged except for documented patches; each directory names its
source commit in `SOURCE.md`. Update with the sync scripts in `tools/`.

| directory | source | licence |
|---|---|---|
| `third_party/mlv-app` | [MLV-App](https://github.com/ilia3101/MLV-App) processing, matrix and RAW-correction code; bug fixes in `tools/patches/mlv-app` | GPL-3.0 (bundled parts: Sobel Apache-2.0, RBFilter MIT, TinyExpr and GENANN zlib) |
| `third_party/rawlens-gpu` | RawLens Vulkan pipeline (Packed RCD, grade) | GPL-3.0 |
| `third_party/craw` | CinematicRAWStudio encoder, decoder (+ Clip helper) and TUI developer | Apache-2.0 |

The app shows all licence texts under **Library ⋮ → About & licences**.

## Related projects

- **RawLens** — Android camera app that records .CRAW.
- **CinematicRAWStudio-Spec / -Encoder / -Decoder / -TUI** — the .CRAW
  format, its reference encoder and decoder, and a desktop developer.

## Licence

GPL-3.0-or-later; see [LICENSE](LICENSE). Vendored components keep their own
licences (above).
