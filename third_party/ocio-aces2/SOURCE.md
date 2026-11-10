# OpenColorIO ACES 2.0

`Transform.cpp`, `Transform.h`, `Common.h`, `ColorLib.h`, `MatrixLib.h` are
unchanged from OpenColorIO v2.5.0, `src/OpenColorIO/ops/fixedfunction/ACES2/`:
https://github.com/AcademySoftwareFoundation/OpenColorIO/tree/v2.5.0/src/OpenColorIO/ops/fixedfunction/ACES2
BSD-3-Clause, see LICENSE. Copyright Contributors to the OpenColorIO Project.
SHA-256 hashes are in SHA256SUMS.

The two nested headers under `ops/` and `transforms/` are CRS host adapters
(GPL-3.0-or-later). They supply the matrix and chromaticity types used by the
unchanged ACES algorithm, avoiding a dependency on the entire OCIO runtime.
The adapters perform double-precision matrix construction/inversion; they do
not approximate the ACES algorithm. OCIO_NAMESPACE maps to crs_ocio.

The Vulkan code in app/src/main/shaders/aces2_output.glsl is generated from
OpenColorIO 2.5.0's official built-in ACES 2.0 CG config using
`tools/color/generate_aces.py`. Nearest-sampled 362-entry hue tables are
embedded unchanged as GLSL constant arrays. There is no 3D LUT bake.

CRS wraps the transform with the same Rec.709 D65 -> AP0 D60 conversion,
AP1 [0,1024] input guard, and sRGB display encoding as that config. This is
an ACES 2.0 SDR 100-nit Rec.709 output option, not an ACES-certified camera
IDT or an ACES interchange/EXR workflow. Camera input uses the CRAW matrix
and the existing MLV-App white balance.
