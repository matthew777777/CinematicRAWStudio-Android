# Reviewed feature and UI matrix

> Historical report: superseded by the current MHC/RapidRAW renderer. Removed-backend instructions, architecture and measurements below must not be used as current implementation or performance acceptance. See `docs/rcd-history-removal-2026-11-10.md`.


Pin and generated parameter/control evidence: [inventory](README.md).
All source paths below resolve under `third_party/rapidraw-reference` at the
same frozen revision. Host file is `src-tauri/src/image_processing.rs` unless
another module is named; main shader is `src-tauri/src/shaders/shader.wgsl`.
Widget bounds/defaults are in [controls](controls.md) and
[parameters](parameters.md); dynamic expressions remain source expressions.

**Status for every row: not ported**, except the isolated exposure shader
function (exact-bit host/Android gate passed; UI/host mapping still not ported).
No row is considered complete because an old CRS feature has the same name.

## Adjustment panels

| ID / section | UI and stored controls | Processing and dependencies | Required parity/behavior checks |
|---|---|---|---|
| B01 Basic | Basic/AgX selector; exposure −5…5, step .01 | `Basic.tsx`; host tone-mapper resolution, baseline exposure and exposure scale; `apply_linear_exposure`, `agx_full_transform`, Basic RAW branch | Shader gate already passes; test UI mapping, baseline, RAW/non-RAW defaults; retained CRS AgX/ACES adapters separately |
| B02 Basic | Brightness, contrast, highlights, shadows, whites, blacks; signed sliders | `Basic.tsx`; `apply_filmic_exposure`, `apply_tonal_adjustments`, `apply_highlights_adjustment`; guided detail coefficients | Signed extremes, neutral identity, HDR/negative samples, stage order; brightness is after output transform |
| C01 Curves | Point/parametric mode; luma/R/G/B channels; add/move/remove points; per-channel reset/copy/paste | `Curves.tsx`; curve generation helpers in `Curves.tsx` and `utils/adjustments.ts`; effective points to host aligned buffers; WGSL cubic Hermite interpolation | Endpoints, equal/near points, channel switching, monotonic constraints, curve mode persistence, global/local equality |
| C02 Curves | Parametric white level, highlights, lights, darks, shadows, black level; three region splits | `Curves.tsx`; `parametricCurve` → effective point generation; preserve `pointCurves` separately | Split ordering, clamping, mode conversion/paste, saved state and sampled curve results |
| K01 Color / WB | As-shot/relative/kelvin state, temperature/tint, picker; nonlinear kelvin slider | `Color.tsx`, `utils/whiteBalance.ts`, Rust `white_balance.rs`, `as_shot.rs`; `apply_white_balance` in LMS | Camera metadata, picker samples, matrix precision, grey/colored HDR, local WB relative to global |
| K02 Color / Presence | Saturation and vibrance | `Color.tsx`; `apply_creative_color`; host scaling | Negative/positive values, neutrals, skin/saturated hues, monochrome interaction |
| K03 Color / Hue | Global hue shift | `Color.tsx`; `apply_hue_shift`, HSV conversions | Hue wrap, achromatic input, negative RGB and HDR behavior |
| K04 Color / Wheels | Shadows, midtones, highlights, global; hue/saturation/luminance; blending and balance; combined/single views | `Color.tsx`, `ui/ColorWheel.tsx`; `apply_color_grading`, host scale factors | Wheel drag coordinates, reset/numeric values, tonal weights, blending/balance limits, global/local wheel composition |
| K05 Color / Mixer | Eight bands: red/orange/yellow/green/aqua/blue/purple/magenta; H/S/L modes, swatches, targeted picker | `Color.tsx`, `utils/hslMixer.ts`, `hooks/useHslMixerPicker.ts`; `apply_hsl_panel`, `hsl_probe` | Band overlap/wrap and order, picker influence, mixed bands, negative/HDR colors, monochrome mix interaction |
| K06 Color / Calibration | Shadow tint; red/green/blue primary hue and saturation; hidden by default | `Color.tsx`; host `ColorCalibrationSettings`, `apply_color_calibration` | Primaries, grey ramp, signed extremes, tool bypass independent of hidden/collapsed UI |
| K07 Color / Monochrome | Enabled, film presets, filter hue/strength, eight-band mix, shadow/highlight toning hue/saturation and balance | `Color.tsx`, film enums; `apply_monochrome_mix`, `apply_monochrome_toning`, OKLab conversion | Every film preset/mixer, filter limits, toning stage order, color-mode switch without data loss |
| D01 Details / Sharpening | Sharpness −100…100; threshold 0…80, default 15 | `Details.tsx`; `apply_sharpen`, neighborhood sampling and scale | Halo/border behavior, signed amount, threshold transitions, resolution-dependent radius, local masks |
| D02 Details / Presence | Clarity, dehaze, structure; centré and draggable center | `Details.tsx`, center overlay; guided/blur WGSL, `shape_detail_gf`, `apply_dehaze`, `apply_centre_tonal_and_color` | Guided coefficients/blur first, edges/borders, center placement through crop, proxy/full scale and parameter combinations |
| D03 Details / Noise | Luma/chroma noise reduction; global 0…100, local −100…100 | `Details.tsx`; `apply_noise_reduction`, workgroup tile/guide helpers | Coarse/fine guides, workgroup halos, exposure normalization, local/global blending, odd sizes and all boundaries |
| D04 Details / AI denoise | Enable, method AI/BM3D, amount 0…100 default 100, detail 0…100 default 0 | `Details.tsx`; `ai_denoise.rs`, `bm3d.rs`, model/provider paths | Model/version and preparation, blend amount/detail, cached results/cancellation, per-frame identity and temporal stability; not a slider-only shader port |
| D05 Details / CA | Red–cyan and blue–yellow correction; hidden by default | `Details.tsx`; `apply_ca_correction`, host 10000 scale divisor, sampling coordinates | Signed offsets, edges, alpha, resolution scaling; distinguish from profile lens TCA |
| D06 Details / Negative | Enable, RGB weights .5…2, exposure −2…2, contrast .5…2.5 | `Details.tsx`; `negative_conversion.rs`; conversion changes effective RAW handling | Baseline/WB/tool ordering, working space, cache identity, negative versus positive input, masks/picker consistency |
| E01 Effects / Creative | Glow, halation, flare amounts | `Effects.tsx`; `apply_glow_bloom`, `apply_halation`, `flare.wgsl`, blur chain and flare texture | Blur pyramids and scale, highlights and exposure, edges, flare texture generation, combined effects |
| E02 Effects / LUT | Import/select/preview LUT, name/path/data/size, intensity, scene-referred flag | `Effects.tsx`, LUT UI/hooks; `lut_processing.rs`; tetrahedral sampling; scene V-Log or display-referred branch | Cube/image LUT parsing, domain/channel order, interpolation boundaries, resource persistence/export, correct transform position |
| E03 Effects / Vignette | Amount, midpoint, roundness, feather, draggable center | `Effects.tsx`, center overlay; main WGSL vignette branch | Signed strength, off-center/shape limits, aspect and crop coordinate consistency |
| E04 Effects / Grain | Amount, size default 25, roughness default 50 | `Effects.tsx`; `gradient_noise`, coordinate/scale-based grain branch | Exact noise/hash operations and coordinates, brightness mask, proxy/full scaling; specify video temporal behavior explicitly |
| E05 Effects / Lens blur | Enable, amount default 40, diffusion, aperture shape, depth selection min/max and fades, depth map | `Effects.tsx`; `lens_blur.rs`, `effect_maps.rs`, AI depth generation | Map identity/geometry and resolution, bokeh edges, fades, cache/resource lifecycle, multi-frame stability |
| E06 Effects / Relight | Enable, normal map, lights list and canvas shapes; ambient, softness, shine, shadows and shadow softness | `Effects.tsx`, `RelightLightShape.tsx`; `relight.rs`, `effect_maps.rs` | Light construction/defaults, position/direction/color, shadow maps, normal warping, order and temporal stability |
| E07 Effects / Fog | Enable, depth map, amount, start, density, height, variation, glow, temperature/tint | `Effects.tsx`; `fog.rs`, depth/effect map dependencies | Depth normalization, noise coordinates, depth fades, geometry/cache mapping, HDR and temporal behavior |

## Masks, cleanup, and local editing

Source UI: `src/components/panel/right/Masks.tsx`, `MasksPanel.tsx`,
`src/hooks/useAiMasking.ts`, and editor canvas tools. Processing:
`src-tauri/src/mask_generation.rs`, feature modules, and mask parameter buffers
in `image_processing.rs`/`gpu_processing.rs`.

| ID | UI / scope | Dependencies and acceptance |
|---|---|---|
| M01 Containers | Create/name/select/reorder/duplicate/delete masks; visibility, invert, opacity; copy/paste and mask presets | Container and submask identity, compositing order, undo/redo, serialized state and cached mask invalidation |
| M02 Combination | Additive, subtractive, intersect submasks; individual visibility/invert/opacity | Upstream raster order and blend math; exact alpha/mask planes before grade parity |
| M03 Manual selection | Brush/eraser, flow, linear, radial, whole image | Brush parameters from UI defaults/types; stroke interpolation, pressure/flow, falloff, feathering, transforms, edge clipping and coordinate precision |
| M04 Range selection | Color and luminance masks with source sampling/ranges | Same sampled source stage, range falloffs, picker behavior, display overlay versus actual mask |
| M05 AI selection | Subject, sky, foreground, depth, text/prompt, face/face-part selectors | Provider/model files, generated bitmaps, face segmentation, source/crop linkage, progress/retry/cancel and CPU/GPU support per Android provider |
| M06 Cleanup tools | Quick eraser, clone, heal, liquify, retouch identifiers and related canvas tools | Trace each tool to its actual upstream execution/provider path; preserve strokes, offsets/warp, patch order, preview/export parity. An enum identifier alone is not proof of runtime support |
| M07 Local grade | Basic tools, curves, WB shifts, hue/HSL, saturation/vibrance, wheels, sharpness, clarity/dehaze/structure, luma/chroma noise, glow/halation/flare | Use the actual `MaskAdjustments` subset; do not expose unrelated global effects locally. Per-tool influence enters multiple pipeline stages |
| M08 Overlay and gestures | Mask tint/visibility, selected handles, brush cursor, transformed canvas hit-testing | Overlay is preview UI only; export must use the actual mask without drawing handles/overlay. Touch pan/zoom must not unintentionally paint |

Mask-type enum coverage: `ai-depth`, `ai-foreground`, `ai-sky`, `ai-subject`,
`ai-text`, `ai-face`, `all`, `brush`, `flow`, `color`, `linear`, `luminance`,
`quick-eraser`, `radial`, `clone`, `heal`, `liquify`, `retouch`.
Mode enum coverage: `additive`, `subtractive`, `intersect`.

## Geometry and lens correction

| ID | UI / stored controls | Processing and acceptance |
|---|---|---|
| G01 Crop | Rectangle, aspect ratio/free crop, orientation steps, fine rotation, flips; composition overlays | `CropPanel.tsx`, `ImageCanvas.tsx`, `CompositionOverlays.tsx`; host geometry; verify forward/inverse mapping, crop bounds, dimensions, alpha, masks/maps and export framing |
| G02 Transform | Distortion, vertical/horizontal perspective, rotate, aspect, scale, X/Y offsets | `CropPanel.tsx`; image-processing geometry/warp; test signed extremes, resampling/borders, combined geometry and coordinates |
| G03 Guided/auto | Guided perspective enabled/lines/auto-crop; automatic straighten entry points | `guided_perspective.rs`, `auto_straighten.rs`; analysis input and output transforms, reproducibility, manual override, cancellation |
| G04 Lens | Manual/profile mode, maker/model matching, distortion/TCA/vignette enable and strengths, coefficients | `lens_correction.rs`, Lensfun database/resources; test profiles and interpolation, missing metadata/profile, distortion versus creative vignette, cache invalidation |

## Supporting editor workflows and dependencies

| ID | UI / workflow | Scope and acceptance |
|---|---|---|
| U01 Panel shell | Compact bottom tool sheet/mobile switcher; wide/full arrangements; collapsible sections and tools | `EditorView.tsx`, `PanelSwitcher.tsx`, `SidePanelArea.tsx`, `ControlsPanel.tsx`; preserve touch hierarchy, scroll positions, expanded/collapsed/bypass state, theme and orientation behavior |
| U02 Controls | Slider/numeric editing, defaults/reset, wheel interactions, curve gestures, drag state/speed editing, tool visibility/order | `ui/Slider.tsx`, `ui/ColorWheel.tsx`, `Curves.tsx`, toolbar/speed overlay; dynamic ranges and labels are in generated evidence; accessibility/numeric precision/reset all need tests |
| U03 Preview | Zoom/pan/pinch, fit/fullscreen, before/after or comparison interactions, clipping warnings, histogram/waveform/channel selection | `Editor.tsx`, `ImageCanvas.tsx`, toolbar, `Waveform.tsx`, `ConnectedWaveform.tsx`; determine source/display stages, keep warnings out of export, preserve responsive gestures |
| U04 History | Undo/redo and edit transactions, reset section/all, copy/paste chosen groups and masks | `utils/editHistory.ts`, editor store/actions, adjustment group definitions; drag coalescing, no intermediate history explosion, serialization and engine-version migration |
| U05 Presets | Local preset lists/groups, create/configure/import/export/apply, strength/preview paths; community entry points | `PresetsPanel.tsx`, `usePresets.ts`, `ConfigurePresetModal.tsx`, `preset_converter.rs`; partial groups and masks, resource references, old-format conversion, service assets tracked independently |
| U06 Automatic edits | Auto-adjust strength modes, analysis results, one-click actions | `useEditorActions.ts`, `perform_auto_analysis`; same analysis pixels, baseline and activation settings, deterministic outputs and nonblocking cancellation |
| U07 AI workflow | Provider configuration/connection, generative replacement and patch management; model-backed helper tools | `AIPanel.tsx`, cloud store/hooks, `ai_*` Rust modules; inventory provider-specific conditions, credentials/models/downloads, progress/cancel/retry, patch caching and privacy. Do not present a dead control as a ported feature |
| U08 Multi-image operations | HDR/deghosting, panorama/stitching, multi-exposure operations where exposed | `multi_exposure.rs`, `hdr_deghosting.rs`, `panorama_stitching.rs`, frontend dialogs/actions; source has still-photo operations, so define explicit CRS still-frame/sequence semantics rather than claim video parity |
| U09 Library/editor context | Filmstrip, source navigation, ratings/flags, metadata, folders, viewer window | Existing CRS library/projects remain; adapt editor entry/selection/context rather than blindly replace source management. Metadata must describe original source correctly |
| U10 Output | Upstream still export settings, presets, metadata and color/precision options | `ExportPanel.tsx`, `useExportSettings.ts`, `export_processing.rs`; inventory as still exporter. CRS preserves hardware video export, timeline/audio/queue; enforce no proxy substitution and exact source frame schedule |
| U11 External integrations | Tethering, edit-in apps, Immich/cloud/community account paths | Present in source and tracked dependencies, not Vulkan adjustments; retain an explicit integration backlog and Android availability evidence. Do not silently make third-party accounts a prerequisite for core grading |
| U12 Settings/cache | Processing/model settings, display/RAW defaults, custom tool layout, storage limits | `app_settings.rs`, settings UI/stores, GPU/effect/mask caches; separate persistent preference, per-clip grade, transient UI and generated resource state |

## Execution order and migration batches

1. New versioned parameter model, tool activation and resource references;
   section/panel shell, numeric controls, reset and serialization. No legacy
   receipt semantics carried into new parameters. Add host-scaling gates first.
2. GPU-resident RCD boundary, exposure/white balance and output adapters;
   preserve upstream output for parity and CRS AgX/ACES as explicit choices.
3. Tonal tools, presence/hue/calibration/HSL, wheels, curves, monochrome. Port
   guided/blur dependencies before claiming tonal/local-detail parity.
4. Spatial/noise/sharpen/detail, creative effects, LUTs/vignette/grain; masks
   and geometry must use compatible coordinate/working-space contracts.
5. Full mask authoring/local edits and cleanup; lens/profile resources;
   depth/normal/AI-dependent effects and provider workflows. Earlier masks
   required by stage tests can be generated fixtures before authoring UI exists.
6. Presets/history/analysis and all remaining workflows; integration and
   temporal contracts; sustained playback/export/memory verification;
   remove every old editing dependency and audit final build/package/source.

These are dependency batches, not permission to drop later features. Exact
function parity alone is insufficient: host preparation, UI semantics,
coordinate maps, resource behavior, stage combinations, and video temporal
behavior must also pass before a row changes to complete.
