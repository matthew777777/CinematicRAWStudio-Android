# Pipeline baseline and parity harness

> Historical report: superseded by the current MHC/RapidRAW renderer. Removed-backend instructions, architecture and measurements below must not be used as current implementation or performance acceptance. See `docs/rcd-history-removal-2026-11-10.md`.


`PipelineTiming` is disabled by default. Headless instrumentation enables it
only during a benchmark and disables it in `finally`. Normal rendering uses the
same algorithms. Native blob decode appends three diagnostic durations after
the original metadata; Kotlin strips this tail before constructing FrameParams.

Build the release-optimized, unminified device test APK and runner:

```sh
./gradlew :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest
adb install -r app/build/outputs/apk/deviceTest/app-deviceTest.apk
adb install -r app/build/outputs/apk/androidTest/deviceTest/app-deviceTest-androidTest.apk
adb shell am instrument -w -e headless true -e benchmark true -e frames 30 \
  com.matthew.crs.test/com.matthew.crs.ProjectInstrumentation
```

Use a previously selected library folder containing a Bayer take, or supply
`-e source content://...` with a URI the app can read. No activity is launched.
The benchmark does not save projects or alter receipts or original footage.
It writes MP4 outputs and `pipeline-benchmark.txt` under the app's external
`project-review` folder. Repeating the benchmark replaces those named outputs.

The full pass tests default, Filament AgX, ACES 2.0, and sharpening (CPU fallback).
It warms two full-resolution renders, measures sequential rendering, measures
cold proxy playback against the source clock, and exports 30 frames of H.264
and Main10 to a 1280x720 canvas at 30 fps / 12 Mbps with no audio sources.
Encoder capability fitting can reduce unsupported output dimensions. The
production Main10 SPS check still runs. Source frame count limits the test.

Startup as-shot fitting can exceed this short playback window. Measure warm
delivery separately, pre-rendering frame zero before starting the clock:

```sh
adb shell am instrument -w -e headless true -e benchmark true \
  -e playbackonly true -e nativeplayback true -e frames 120 \
  com.matthew.crs.test/com.matthew.crs.ProjectInstrumentation
```

This writes `pipeline-playback-warm.txt`. It includes an ungraded base-development
case and skips sequential render/export measurements. Native allocations can
still occur on the first delivered frame. Omit `nativeplayback` for the separate
proxy comparison; copy each report before the next run replaces it.
Use `-e nativeexport true` on the full benchmark to request the source-sized
export canvas; actual dimensions after codec fitting are reported.

The performance target is native 30 fps ungraded/graded playback and at least
30 fps full-quality export on target footage/device. Explicit proxy playback
and bounded caches are permitted, but do not establish native playback speed
or justify using proxies for export.

Reported times are CPU wall time around synchronous calls, including waits;
they are not Vulkan timestamp queries. Native decode, RAW corrections, and
shading/copy are separated. Grade GPU attempts include readback/output; a null
GPU attempt is followed by the measured CPU fallback. RCD, grade preparation,
bitmap fitting, codec input/output waits, submission, and mux writes have
separate counters. Nested and concurrent stages must not be added together.
End-to-end export fps includes startup, final drain, mux, and destination write.
Headless playback measures delivered frames and gaps, not screen presentation.
Thermal status, battery temperature, and process PSS accompany results.

These short samples establish initial costs, not sustained thermal performance
or smoothness acceptance. Repeat longer runs when assessing the replacement.
The independent RapidRAW exposure comparison is documented in
`tests/rapidraw-parity/README.md`; host parity is distinct from Android parity.
