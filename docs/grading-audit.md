# Grading audit — 2026-10-29

> Historical report: superseded by the current MHC/RapidRAW renderer. Removed-backend instructions, architecture and measurements below must not be used as current implementation or performance acceptance. See `docs/rcd-history-removal-2026-11-10.md`.


Compared CRS receipts, editor controls, JNI setters, Vulkan shader, and CPU
fallback with MLV-App's `ReceiptSettings.h`, `MainWindow.cpp`, processing
API, and image profiles. Our processing pin is c4357a3ab8a64f4feca4b05dbaba8981cdf9920a.
Current upstream checked: 5c87aeb49339381985e330e15533da010be7524d (2026-10-28).
The receipt definitions and raw_processing.h match the pin; upstream has
newer implementations of cube_lut.c, RBFilterPlain.cpp and the median
denoiser. Those optimizations have not been imported by this change.

## MLV-App coverage

Bundling a processing source file does not make its feature available in the
Android editor. The port is **not feature complete**.

| Feature | Android status |
|---|---|
| Exposure, contrast, pivot, clarity, shadows/highlights | Receipt, UI, native processing present. Spatial tools use CPU fallback. |
| Temperature/tint, vibrance, saturation | Receipt, UI, native processing present. As-shot WB is fitted to CRAW metadata. |
| Dark/light contrast strength/range, lighten | Receipt, UI, native processing present. |
| Luma/R/G/B gradation; hue-vs-hue/sat/luma and luma-vs-sat | Receipt, UI, native processing present. |
| Sharpening and edge masking | Present; CPU. Directional sharpening bias has a JNI setter but no receipt/UI. |
| Median and RBF luma/chroma/range denoise | Present; CPU. Median window is stored/applied but lacks a UI choice. |
| Chroma separation and blur | Present; CPU; blur requires separation as upstream does. |
| Vignette strength/radius/shape; color toning | Present. Vignette uses fixed unit X/Y stretch. |
| CA desaturation | Present; CPU. CA radius and red/blue geometric correction have no controls. |
| Camera matrix choices, creative switch, reconstruction, legacy AgX compression | Present. EXR switch is stored/applied but has no UI. No EXR export workflow. |
| Profiles | First six presets exposed. Linear, sRGB, Rec.709, DaVinci Wide Gamut/Intermediate, Fuji F-Log, Canon Log, Panasonic V-Log exist in vendored profiles but are not selectable. |
| Independent gamut and tonemap selection; custom transfer expression | Native setters/code exist, but no receipt/UI for these independent controls or expression text. |
| Gradient exposure/contrast and mask geometry | Processing code included, no receipt/UI/JNI mask bridge. |
| Grain strength/luma weighting | Processing code included, no receipt/UI/JNI bridge. |
| User .cube LUTs and LUT strength | Processing code included, no picker, receipt, or JNI loading bridge. |
| Film/emulation filters and strength | Processing code included, no receipt/UI/native selection bridge. |
| RAW vertical stripes, bad pixels, search method, chroma smooth, pattern noise | Present. Bad-pixel interpolation is stored/applied but not selectable. Chroma smooth enum is corrected to its actual labeled size. |
| User RAW black/white override | Metadata normalization present; no manual override controls. |
| Focus/map pixels, dual ISO, dark frames, deflicker | Not offered. Several are Canon/MLV-specific; not equivalent to grading controls. |
| Binned RGB takes | Shared MLV-App receipt grading remains limited to Bayer/GPU takes; RGB takes use the basic CPU developer. |
| Debayer method menu | Replaced by the RawLens Packed RCD path for Bayer takes. |
| Stretch/framing/rotation/stabilization controls | Not ported from MLV-App. Timeline cuts/scaling are separate app features. |
| Three-way correction | Upstream header itself marks this API as not doing anything; not counted as a working missing tool. |

## AgX and ACES

MLV-App's AgX switch uses an inset chroma matrix, the selected legacy profile
transfer, creative operations, and an inverse of that same inset matrix.
It does not implement Filament's Rec.2020 inset, EV normalization, contrast
polynomial, separate outset, and 2.2 linearization. Its output is not the
same as RawLens's Filament Base implementation.

Upstream's `processingSetGamma` only rebuilds the active transfer
expression; it does not replace the exponent hardcoded into that expression.
CRS's former gamma slider therefore did not change the image. It is replaced
by a working zero-centered **Gamma adjustment**, applied through the same
RGB16 lookup table on CPU and Vulkan after creative curves. Zero is an
exact identity. It is available for all display transforms.

The Profile panel now offers:

- **MLV-App**: existing settings and saved receipts keep their profile and
  optional MLV-App AgX compression.
- **AgX · Filament**: Google Filament Base pinned to
  2a8018f54d5154ceb1bf7005c6c01b13aa70e7ad, the same base used by RawLens.
  Explicit Rec.709/Rec.2020 conversion and final sRGB encoding surround it.
  RawLens's optional shoulder, purity, saturation, hue-preservation and
  gamut controls are not included, so those extra adjustments can differ.
- **ACES 2.0**: full analytical ACES 2.0 SDR 100-nit Rec.709 output transform
  from OpenColorIO 2.5.0's built-in ACES 2.0 CG configuration. Includes
  D65/D60 adaptation, AP1 input guard, JMh tone scale, chroma compression,
  gamut compression, and sRGB display encoding. CPU uses unchanged OCIO
  ACES2 core sources with small matrix host adapters. Vulkan uses OCIO's
  generated shader with its exact 362-entry hue tables embedded as constants.
  It is not the BakingLab fit, a baked 3D LUT, or an ACES camera IDT/certification.
  HDR display targets, ACES EXR interchange, and ACEScct editing are not added.

The modern transforms replace the legacy profile/gamma/AgX stages, rather
than applying a second display transform on already encoded pixels. They
require the camera matrix and retain floating-point scene highlights until
the output transform. Existing creative tools remain available; the
post-transfer MLV-App tools still operate on display-encoded RGB.

## Highlight correction and consistency

Before this change, temporary per-channel WB gains were clamped to RGB16
before the camera matrix undid them. A bright neutral could become colored
even with highlight reconstruction disabled. Both CPU and Vulkan now use
the unclipped float values through the matrix when reconstruction is off.
For sensor-saturated input, reconstruction-off also clips the remaining
channel tails toward the current WB neutral, with a smooth transition in
the final 2% of the normalized sensor range, after MLV-App levels and
before temporary WB. CPU and Vulkan use the same Q16 transition. This prevents a clipped green channel and
still-rising red/blue channels from producing pink sky pixels. It does not
recover missing texture. Reconstruction remains a separate opt-in operation.
A neutral-pixel 0/0 in upstream's gamut desaturation is also guarded.

Playback previously bypassed the MLV-App receipt entirely (basic fused
BT.709 only). It now applies the shared receipt and output transform to
proxy/native RCD frames, matching paused preview and export. Spatial
settings still need CPU processing and can reduce playback speed.

Channels already saturated in recorded sensor data have missing color
information. Reconstruction-off uses neutral clipping; the reconstruction
control remains useful when highlight recovery is wanted.

## Validation

- `python3 tests/color/run.py`: 206 colors against OpenColorIO 2.5.0;
  maximum encoded-channel error 0.0000076294. Neutral highlight ramp with
  reconstruction OFF: maximum channel spread 1/65535. A separate clipped-green
  sensor regression also has channel spread 1/65535.
- `tools/color/generate_aces.py`: reproducible shader and reference vectors;
  requires `opencolorio==2.5.0` and NumPy.
- `ProjectInstrumentation -e colorcheck true`: real CRAW CPU/Vulkan parity,
  spatial fallback, proxy playback and HEVC Main10 export checks.
- `ProjectInstrumentation -e gpuparity true`: full stage-interaction suite,
  extended with Filament/ACES exposure, white-balance and creative cases.

For a separately signed device test installation, use
`./gradlew -PcrsApplicationId=com.matthew.crs.colorcheck :app:assembleDeviceTest :app:assembleDeviceTestAndroidTest`.
The focused and full GPU parity runners accept `-e source file:///.../take.craw`
for a source copied into that test application's storage. The production
application ID remains `com.matthew.crs`.

## True-zero grading and complete bypass

The app now uses `exposure / 100` EV, with no hidden +1.2 EV. Fresh receipts
also use dark contrast strength zero. Zero-centered gamma maps to exponent
`2^(-adjustment/100)`, so zero means an identity lookup. Saturation and
vibrance zero map to their neutral multiplier of one. Pivot/range controls
only position an effect; they do nothing when its strength is zero. As-shot
white balance and the selected display transform remain the viewing baseline.

The normalized RCD bridge now supplies identity black/white levels instead
of inheriting MLV-App's 0.7% white reduction. Identity gradation curves use
exact identity tables instead of the spline path's nonzero black floor.
Zero-strength contrast curves bypass table quantisation exactly.

The switch is now labeled **Grading adjustments**. Off bypasses exposure,
manual WB/tint, contrast, gamma adjustment, color/curves/toning, spatial
filters, vignette/CA, reconstruction and optional MLV RAW correction tools.
It retains metadata normalization, as-shot WB, camera color conversion and
the selected display transform. Saved slider values remain intact and return
when switched on. This broadens the previous upstream creative-only bypass.

Receipts include `gradingVersion: 2`. Older saved receipts add 120 to the
exposure slider value on load (old 0 becomes explicit +1.20 EV), retain their
stored dark-contrast strength, and map their ineffective old gamma value
to zero adjustment. Subsequent saves carry the version, preventing repeated
migration. The highlight and exact-neutral fixes intentionally still apply.

Final zero-grading validation on the street CRAW take: all three display
options produce byte-identical preview images for neutral controls versus
full bypass, and for nonzero saved controls with bypass enabled. Gamma
adjustment changes the image in every display option. Spatial CPU fallback,
proxy playback and three-frame HEVC Main10 exports also passed. Fifteen
receipt/timeline unit tests passed, including one-time legacy migration.
CPU/Vulkan ACES comparisons use numeric tolerances: the neutral ACES frame
had mean error 0.0108/65535, with 2 of 9,351,360 pixels differing by over
256/65535 (maximum 486/65535); the transform is not claimed bit-identical
between GPU math and CPU math.

The final full CPU/Vulkan stage-interaction suite passed all 42 cases,
including positive/negative gamma, complete bypass, both modern transforms,
exposure, white balance, saturation, curves and leave-one-out combinations.
The production release APK also built successfully.
