// SPDX-License-Identifier: GPL-3.0-or-later
// MLV-App processing parity harness: runs the vendored, unchanged MLV-App
// processing (third_party/mlv-app) on a 16-bit RGB image with most stages
// enabled and writes the result, so builds (host vs Android) can be
// compared bit for bit.
// usage: parity in.rgb16 width height out.rgb16 [threads] [stage,stage,...|all]
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include "raw_processing.h"

int main(int argc, char** argv) {
    if (argc < 5) { fprintf(stderr, "usage: parity in w h out [threads]\n"); return 2; }
    const int w = atoi(argv[2]), h = atoi(argv[3]), threads = argc > 5 ? atoi(argv[5]) : 1;
    const char* stages = argc > 6 ? argv[6] : "all";
#define ON(name) (strcmp(stages, "all") == 0 || strstr(stages, name) != NULL)
    const size_t n = (size_t)w * h * 3;
    uint16_t* in = malloc(n * 2);
    uint16_t* out = malloc(n * 2);
    FILE* f = fopen(argv[1], "rb");
    if (!f || fread(in, 2, n, f) != n) { fprintf(stderr, "read failed\n"); return 1; }
    fclose(f);

    processingObject_t* p = initProcessingObject();
    /* What MLV-App's setMlvProcessing() does when linking a clip (video_mlv.c). */
    static int dualIso = 0;
    p->dual_iso = &dualIso;
    p->wbFindActive = 0;
    p->vignette_mask = realloc(p->vignette_mask, (size_t)w * h * sizeof(float));
    p->gradient_mask = realloc(p->gradient_mask, (size_t)w * h * sizeof(uint16_t));
    /* A camera matrix (XYZ -> camera, DNG ColorMatrix convention) as MLV-App sets it. */
    double cam[9] = {0.6667, -0.1589, -0.0857, -0.5739, 1.3898, 0.1430, -0.1379, 0.2652, 0.6036};
    double camA[9] = {1.5315, -0.4696, -0.2151, -0.4762, 1.4453, 0.0067, -0.0717, 0.2387, 0.2330};
    processingSetCamMatrix(p, cam, camA);
    processingSetBlackAndWhiteLevel(p, 0, 65535, 16);
    processingSetImageProfile(p, 1); /* PROFILE_TONEMAPPED */
    processingSetGamut(p, 0);
    if (ON("expo")) processingSetExposureStops(p, 0.7);
    if (ON("wb")) processingSetWhiteBalance(p, 5200, 1.5);
    if (ON("contrast")) { processingSetSimpleContrast(p, 0.3); processingSetPivot(p, 0.4); }
    if (ON("clarity")) processingSetClarity(p, 0.35);
    if (ON("shadhigh")) { processingSetShadows(p, 0.4); processingSetHighlights(p, -0.5); }
    if (ON("sat")) processingSetSaturation(p, 1.3);
    if (ON("vib")) processingSetVibrance(p, 1.4);
    if (ON("toning")) processingSetToning(p, 230, 160, 90, 20);
    float cx[4] = {0.0f, 0.3f, 0.7f, 1.0f}, cy[4] = {0.0f, 0.25f, 0.78f, 1.0f};
    if (ON("gcurve")) processingSetGCurve(p, 4, cx, cy, 0);
    float hx[3] = {0.0f, 0.5f, 1.0f}, hy[3] = {0.5f, 0.62f, 0.5f};
    if (ON("huevs")) processingSetHueVsCurves(p, 3, hx, hy, 0);
    if (ON("sharpen")) { processingSetSharpening(p, 0.4); processingSetSharpenMasking(p, 30); }
    if (ON("rbf")) { processingSetRbfDenoiserLuma(p, 20); processingSetRbfDenoiserChroma(p, 30); processingSetRbfDenoiserRange(p, 40); }
    if (ON("ca")) processingSetCaDesaturate(p, 40);
    processingSetVignetteMask(p, w, h, 0.8f, 0.5f, 1.0f, 1.0f);
    if (ON("vignette")) processingSetVignetteStrength(p, -40);

    applyProcessingObject(p, w, h, in, out, threads, 1, 0);
    f = fopen(argv[4], "wb");
    fwrite(out, 2, n, f);
    fclose(f);
    uint64_t hsh = 1469598103934665603ull;
    for (size_t i = 0; i < n; ++i) { hsh ^= out[i]; hsh *= 1099511628211ull; }
    printf("%dx%d threads=%d fnv=%016llx\n", w, h, threads, (unsigned long long)hsh);
    freeProcessingObject(p);
    return 0;
}
