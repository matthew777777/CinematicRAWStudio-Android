# RapidRAW 1.7 mobile migration and player UX

Historical plan from 2026-11-16. The app now uses 1.7.0 as of 0.2.0. This is a staged migration, not a completed 1.7 renderer port or a measured 30 fps claim.

## Source and contract

RapidRAW v1.7.0 is pinned to `00379017cb14f3fc398e69fa14b33b60af07c588` in `third_party/rapidraw-1.7-reference`. The existing executable renderer remains pinned to `f1d71a69eef813432db214f77969f567c41ff1d7`. Both snapshots are unmodified and hash checked. This prevents a partially upgraded Rust/shader ABI from shipping as a finished upgrade.

Verify the new snapshot with:

```sh
python3 tools/verify_rapidraw_reference.py --reference third_party/rapidraw-1.7-reference
```

The reference is evidence, not an app dependency: its AI, library, network and desktop services are not linked into Android. The mobile app remains a native Kotlin player and editor, with CRAW decoding, exact improved RawLens MHC, RapidRAW grading, retained AgX Filament/ACES 2 output, linked timeline sound and source-preserving Compact conversion.

## Completed in this pass

- Grade-only updates keep the existing PCM mixer and audio clock running. The video loop consumes the newest grade; source, trim, rate, track/gain, relink and ordering changes still rebuild the audio schedule.
- Audio completion preserves the terminal clock after AudioTrack is released. It cannot jump back to the playback start and leave video running indefinitely.
- Immutable decoded grades, native resource configuration and packed GPU parameters are reused until their relevant grade/metadata inputs change. Source white balance and coordinate scale still invalidate the appropriate cached values. Shader arithmetic is unchanged.
- Preview mute changes AudioTrack volume without changing the audio clock or export mix. Backgrounding the single-take editor pauses playback.
- Editor UI: quiet title/action bar, dark image surround, warm accent, larger transport targets, elapsed/duration readouts and frame stepping; grading below the player in portrait, beside it in landscape; grading can be hidden for a larger viewer. Panel scroll resets when switching sections. Light starts with exposure and uses readable labels.
- Grading interactions: Color opens on color wheels; tool tabs select focused groups rather than disabling effects accidentally. Curves have an editable graph with long-press point dragging, tap-to-add and double-tap reset. Numeric controls open validated OK/Cancel entry dialogs. Horizontal intent is required before sliders change, so vertical scrolling does not adjust them. Double-tap a numeric label/slider to reset to the model default. Wheels use long-press dragging and double-tap reset. A bounded display-preview RGB histogram refreshes at 4 Hz; it is not a RAW clipping scope.
- UX: original/graded comparison does not replace saved/exported edits; undo/redo stay in the grading panel; reset-all asks for confirmation and remains undoable; copy-grade shows feedback; scrubbing resumes only if playback was active before the drag. A reset of an individual section remains immediate and undoable.

## Step 1 — executable 1.7 core and grade migration

Replace the old extraction paths, not just the version label. In 1.7, `image_processing.rs` and `gpu_processing.rs` have been split:

| Contract | 1.7 source |
|---|---|
| GPU uniform declarations, JSON packing and feature decisions | `src-tauri/src/render/uniforms.rs`, `render/adjustment_utils.rs`, `render/options.rs` |
| Working space, WB and metadata | `render/color_space.rs`, `decode/white_balance.rs`, `decode/as_shot.rs`, `decode/baseline_exposure.rs` |
| Pipelines, compute graph, readback and transport | `render/gpu/pipelines.rs`, `render/gpu/processor.rs`, `render/gpu/compute.rs`, `render/gpu/output.rs` |
| Geometric mask declarations/rasterization | `masks/generation.rs`, `masks/shapes.rs`, `masks/morphology.rs` |
| Grading kernels and guided/tonal-base kernels | `src-tauri/src/shaders/shader.wgsl`, `shaders/guided.wgsl` |

Update the source-range generators, regenerate native uniform and shader layouts together, update JNI validation, then run core/GPU fixtures before switching the executable source pin. The new renderer expects a `tone_base_view`; passing the old guided `structure` texture under a new name is not a valid migration. The light/motion/analytics service references must be explicitly excluded or correctly adapted; they cannot be left unresolved or silently stubbed while their controls remain enabled.

The new default model adds AI upscale and motion-blur fields; none are needed for the requested mobile basic toolset. Existing fields are not removed, but several advanced defaults change. `rapidraw-1.7-model-audit.json` records exact differences. Preserve every old saved grade/output and require an explicit versioned conversion; do not silently drop missing fields or claim identical images across different upstream shader versions. Rebuild the basic panel model from 1.7, expose supported controls only, and reject unsupported imported active effects clearly.

Acceptance: no Tauri/AI/desktop dependencies in the Android core; packed sizes/offsets agree with WGSL; old projects/Compact aliases/grades reopen; neutral, exposure, WB, wheels, curves, HSL, masks, LUT, AgX and ACES fixtures pass; full-resolution P010 export remains stride-correct. Retained MHC sample-preservation tests stay exact.

## Step 2 — mobile GPU playback path

Target 30 unique source frames per second for a 30 fps take, both neutral and representative basic grades, with sound. A project-rate label or a smooth audio clock alone is not proof of 30 fps video.

1. Measure decode, upload/MHC, guided preprocessing, grade dispatch, readback, composition and presentation separately. Include cold/warm runs, cache hit/miss counts, dropped/repeated frames, audio startup/underruns and source/output timestamps. Do not sum overlapping timers.
2. Give preview an explicit bounded resolution budget. The current Bayer preview is half-resolution, so a large RAW can still grade well above the 1280-pixel monitor size. Choose a mobile proxy before expensive grading, while retaining the agreed MHC/CFA contract. Full-resolution export must not reuse preview pixels.
3. Keep decoded/linear frames and graded intermediates on the GPU in a bounded cache keyed by source identity/frame, resolution and grade version. A slider edit should reuse decode/MHC. Revisit the unconditional source-dependent guided processing; only skip a stage when the 1.7 shader contract permits it. Use neutral/basic feature specialization and persistent pipelines, textures, uniform and staging buffers.
4. Replace the remaining synchronous RGBA CPU bitmap readback/Canvas presentation with a Vulkan surface or correctly synchronized HardwareBuffer display path. Use fences, bounded in-flight buffers and explicit ownership; never recycle a texture/bitmap still being displayed. Avoid full graph/pipeline recreation while dragging sliders.
5. Use a bounded latest-request scheduler for seeking, playback and grade changes. Preserve audio as the master clock; discard stale work, handle seek/restart/EOF and resume safely. Prefetch adjacent timeline clips without unbounded decoder or GPU allocations.

Acceptance on a representative mobile Vulkan device: a warm uncached 60-second 30 fps take sustains 30 unique presented frames/s within a declared drop tolerance; report actual drop counts and p50/p95/p99 timing with a 33.33 ms frame budget. Measure neutral and basic-grade cases, all CFA phases and recorded RGB, CRAW codecs 1–4, silent and sound takes, cuts/trims and scrubbing. Verify A/V offset/drift, memory bounds and a longer thermal run. Keep source rates such as 23.976/24/25/29.97 correct; do not force them to 30.

## Step 3 — export and UX acceptance

Benchmark full-resolution export independently of preview, with the same grade/output and audio. Record source dimensions/codec, device/GPU, encoder/bit depth, fps and output duration; compare to the previous baseline. Preserve AAC sync, frame count, AgX/ACES and Compact's independent lossless checks.

Finish visual/runtime checks at 360×800 and 800×360, a narrow split-screen window, tablet and larger font sizes. Verify every action fits cutouts/insets, controls remain at least 48 dp targets, scrolling and orientation retain edits, comparison never affects exports, frame steps seek exactly, backgrounding stops audio, mute is preview-only, and repeated export/return does not strand playback. The new single-take editor layout has been implemented; a full library/timeline/export screen redesign and on-device visual review remain separate work.

## Local verification and limits

JVM regression tests exercise grade/output updates versus audio/timeline changes. Kotlin compilation covers the new UI and transport. Source hashes are checked for both snapshots. Build results are recorded separately under `docs/benchmarks/2026-11-16`.

This pass does not claim a 1.7 executable migration, device screenshot review, measured native 30 fps, or an export speed gain. No user take has been converted/deleted and no phone deployment is part of these local checks.
