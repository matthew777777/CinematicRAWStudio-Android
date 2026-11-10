# RapidRAW Android editor migration inventory

Source pin: **`f1d71a69eef813432db214f77969f567c41ff1d7`**,
[CyberTimon/RapidRAW](https://github.com/CyberTimon/RapidRAW/tree/f1d71a69eef813432db214f77969f567c41ff1d7).
This inventory describes that revision, not a moving upstream release.

The frozen reference contains 232 hash-verified source/configuration files,
including Android-responsive UI, English labels, parameter definitions, Rust
host processing, WGSL shaders, and dependency lockfile. It is reference material
and is not compiled into CRS. Upstream AGPL-3.0 terms are preserved. Resources,
models, lens databases, film LUT collections, other translations, and external
submodules are **not** claimed to be vendored by this source snapshot.

## Deliverables and coverage

* [Feature matrix](features.md): editing features, supporting interactions,
  processing dependencies, migration order, and acceptance checks.
* [Parameter defaults](parameters.md): every one of the 124 top-level defaults,
  expanded into 220 leaf/container paths. Arrays retain their complete values.
* [Control instances](controls.md): 215 source occurrences of sliders, wheels,
  switches/toggles, and selects/dropdowns, including conditional branches and
  reusable templates; this is **not** a claim of 215 unique visible controls.
* [Machine-readable catalog](catalog.json): defaults, section membership,
  parameter reference locations, source enums (including masks and panel IDs),
  widget properties/range expressions, and Rust/WGSL function locations.
  It also records 39 host scale divisors and initialization expressions for
  mask containers, local adjustments, wheels, curves, and relight lights.

Generate the evidence with `python3 tools/inventory_rapidraw.py`. The script
first verifies the reference hashes. It preserves dynamic range expressions
rather than assigning guessed constants. This is lexical source extraction,
not execution of React or proof of Android runtime availability. Buttons,
canvas tools, menus, and interactions are reviewed in the matrix and frozen
source. No newly ported UI or processing is claimed by creating this inventory.

## Android UI structure to reproduce

Upstream [App.tsx:281](../../third_party/rapidraw-reference/src/App.tsx)
selects three Android layouts using viewport dimensions in CSS pixels:

| Layout | Source condition | Editor organization |
|---|---|---|
| Compact | Portrait, width below 600 | Canvas above an expandable/resizable bottom tool panel with mobile panel switcher |
| Wide | Other Android viewports below width 1000 | Wide panel layout; retain accessible canvas and panel navigation |
| Full | Width at least 1000 | Full panel arrangement, with configurable panel regions |

The Compose port must reproduce the interaction hierarchy rather than equate
CSS pixels with Android physical pixels. Validate portrait, landscape, tablet,
font scaling, touch targets, and panel state across rotation. This is a source
layout audit; the upstream APK was not rendered or exercised in this step.

Panel IDs are `adjustments`, `ai`, `crop`, `export`, `masks`, `metadata`,
`presets`, `folderTree`, and `tethering`. Definition in
[AppProperties.tsx](../../third_party/rapidraw-reference/src/components/ui/AppProperties.tsx).
Listing a panel ID does not prove it is available on every Android build.
Tethering and service integrations are tracked as dependencies, not silently
included in the Vulkan shader work or used to replace CRS project/export UI.

Adjustment section order is **Basic → Curves → Color → Details → Effects**.
Color, Details, and Effects have independently collapsible/bypassable tools.
The default tool layout hides `chromaticAberration` and `colorCalibration`;
hidden does not mean absent from the feature inventory. Preserve custom section
order, tool order/visibility, collapse state, reset, section copy/paste, and
tool activation independently of parameter values. References:
[adjustments.ts](../../third_party/rapidraw-reference/src/utils/adjustments.ts),
[ControlsPanel.tsx](../../third_party/rapidraw-reference/src/components/panel/right/ControlsPanel.tsx),
[EditorView.tsx](../../third_party/rapidraw-reference/src/components/views/EditorView.tsx).

## Parameter and parity traps

1. **UI values are not shader values.** Rust `SCALES` and `get_val` in
   [image_processing.rs](../../third_party/rapidraw-reference/src-tauri/src/image_processing.rs)
   convert them and apply tool activation. Exposure uses scale **0.8**, and
   global exposure also adds baseline exposure. The completed isolated exposure
   test supplies the shader EV directly; it does not yet validate the UI mapping.
2. JSON `toneMapper` defaults to `basic`; RAW default tone mapping can be
   overridden by application settings (`default_raw_tonemapper` defaults to
   AgX). Preserve resolution of defaults rather than assuming either is universal.
3. White balance supports relative shifts, kelvin/tint, as-shot state, a picker,
   nonlinear slider mapping, and Rust LMS adaptation matrices. The absolute
   temperature bounds are 2000–50000 K; tint bound 150; relative range 100.
   Mask white balance is relative to the resolved global white balance.
4. Point curves, parametric settings, and compiled/effective curve points are
   distinct saved data. Preserve channels, split points, curve generation,
   interpolation, and copy/reset semantics. Do not save only a sampled LUT.
5. Local adjustments are a subset of global tools. `MaskAdjustments`,
   `INITIAL_MASK_ADJUSTMENTS`, and `get_mask_adjustments_from_json` define the
   actual subset and scaling. Noise-reduction sliders permit negative local
   values where their global ranges start at zero.
6. Spatial tools depend on guided coefficients, blur levels, coordinates,
   sampling/border rules, and resolution scaling. A per-pixel function alone
   cannot establish their parity. Grain uses coordinate/scale-dependent noise;
   do not introduce a video time seed without declaring a deliberate difference.
7. Depth/normal maps, AI patches, masks, LUTs, and lens data are source-linked
   resources. File identity, geometry, cache invalidation, export availability,
   and temporal behavior must be specified and tested.

## Processing boundaries

Upstream has CPU/host preparation as well as GPU stages. Trace those dependencies
through `image_processing.rs`, `gpu_processing.rs`, `image_loader.rs`, and the
feature modules; the main WGSL file is not the whole processing engine.
The inventory records dependencies without assuming every upstream operation
already executes on Vulkan.

The main shader's broad order is input/CA/noise reduction → sharpening and
guided local detail → dehaze → exposure and glow/halation/flare → white balance
and centré → tonal/highlight tools → monochrome or calibration/HSL/hue/presence
→ grading wheels and local grading → vignette → output transform → scene LUT
selection → brightness → global/local curves → monochrome toning → display LUT
→ grain → clipping overlay → output dither/clamp. Mask parameters influence
several earlier stages, not just a final compositing pass. Confirm the exact
host-generated feature flags and auxiliary passes before porting each group.

CRS preserves CRAW decoding/calibration and RCD instead of RapidRAW's RAW
decoder/debayer. Our Filament AgX and ACES 2.0 remain explicit output choices.
Therefore full-source RAW output identity is not claimed across the two apps.
Compare identical linear buffers at a documented working-space boundary;
compare upstream-output mode for RapidRAW end-to-end grading parity and test
our retained transforms against their own references. No double tone mapping.

## Migration acceptance and current status

The inventory catalog records the original starting statuses and is not a live
implementation tracker. The independent host core, compute graph, expanded
new controls, and real-source paused preview are now implemented as described
in [engine checkpoint](../rapidraw-engine.md). The complete replacement is not
finished; resource/AI workflows, playback/export integration and old-code
retirement remain. Isolated function gates are distinguished from image parity
and performance acceptance in that checkpoint.

Per matrix row, completion requires UI/model serialization, host parameter
parity, Vulkan stage and combination parity, preview/export consistency,
reset/bypass/undo tests, and measured performance. AI/service/resource workflows
also require their dependency and temporal contracts; they are retained in the
backlog rather than silently dropped. No tolerance is implicitly approved.

Native 30 fps ungraded/graded playback and at least 30 fps full-quality export
remain targets on the target phone/footage. Explicit proxy playback is allowed;
report it separately. Use bounded caches, persistent compatible pipelines, and
GPU-resident intermediates. Model generation and one-time analysis must be
measured separately from steady processing. Complex effect combinations are
not assumed to meet the budget without measurement.

At completion remove all MLV-App code, old editing controls/receipts, CPU/GPU
adjustment engines, RAW-tool dependencies, shaders, bridges, and fallback
paths. Preserve the explicitly retained CRAW/RCD/AgX/ACES algorithms and
projects/audio/export systems through new integration, as defined in
[migration contract](../rapidraw-migration.md).
