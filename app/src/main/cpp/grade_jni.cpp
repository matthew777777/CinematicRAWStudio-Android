// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge to MLV-App's image processing (third_party/mlv-app, vendored
// unchanged): one processing object per open clip, set up the way MLV-App
// links a clip (video_mlv.c setMlvProcessing), fed with demosaiced camera
// RGB (RawLens Packed RCD output, normalised 0..1 in half floats).
#include <jni.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include "raw_processing.h"
}

namespace {

struct Grade {
    processingObject_t* p = nullptr;
    int w = 0, h = 0;
    int dualIso = 0;
    float vigRadius = 1.0f, vigShape = 0.0f;
    std::vector<uint16_t> in, out;
    std::mutex mutex;
};

Grade* from(jlong h) { return reinterpret_cast<Grade*>(h); }

// Masks are sized per image, as MLV-App allocates them when linking a clip.
void ensureSize(Grade* g, int w, int h) {
    if (g->w == w && g->h == h) return;
    g->w = w; g->h = h;
    g->p->vignette_mask = static_cast<float*>(realloc(g->p->vignette_mask, size_t(w) * h * sizeof(float)));
    g->p->gradient_mask = static_cast<uint16_t*>(realloc(g->p->gradient_mask, size_t(w) * h * sizeof(uint16_t)));
    std::memset(g->p->gradient_mask, 0, size_t(w) * h * sizeof(uint16_t));
    processingSetVignetteMask(g->p, w, h, g->vigRadius, g->vigShape, 1.0f, 1.0f);
}

inline float half2float(uint16_t h) {
    _Float16 f;
    std::memcpy(&f, &h, 2);
    return static_cast<float>(f);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_crs_MlvGrade_nativeCreate(JNIEnv*, jclass) {
    auto* g = new Grade;
    g->p = initProcessingObject();
    // What MLV-App's setMlvProcessing() does when linking a clip.
    g->p->dual_iso = &g->dualIso;
    g->p->wbFindActive = 0;
    // Input is already black-subtracted and normalised (RCD output).
    processingSetBlackAndWhiteLevel(g->p, 0, 65535, 16);
    return reinterpret_cast<jlong>(g);
}

JNIEXPORT void JNICALL
Java_com_matthew_crs_MlvGrade_nativeFree(JNIEnv*, jclass, jlong h) {
    auto* g = from(h);
    if (!g) return;
    freeProcessingObject(g->p);
    delete g;
}

// Daylight (illuminant D65) and tungsten (Standard A) XYZ->camera matrices,
// as MLV-App passes camidGetColorMatrix2/1.
JNIEXPORT void JNICALL
Java_com_matthew_crs_MlvGrade_nativeSetCamMatrix(JNIEnv* env, jclass, jlong h, jdoubleArray d65, jdoubleArray a) {
    auto* g = from(h);
    double md[9], ma[9];
    env->GetDoubleArrayRegion(d65, 0, 9, md);
    env->GetDoubleArrayRegion(a, 0, 9, ma);
    std::lock_guard<std::mutex> l(g->mutex);
    processingSetCamMatrix(g->p, md, ma);
}

// One MLV-App setter by name; values in MLV-App's API units (see MlvGrade.kt).
JNIEXPORT jboolean JNICALL
Java_com_matthew_crs_MlvGrade_nativeSet(JNIEnv* env, jclass, jlong h, jstring jname, jdouble v) {
    auto* g = from(h);
    const char* c = env->GetStringUTFChars(jname, nullptr);
    const std::string n = c;
    env->ReleaseStringUTFChars(jname, c);
    std::lock_guard<std::mutex> l(g->mutex);
    processingObject_t* p = g->p;
    if (n == "exposure") processingSetExposureStops(p, v);
    else if (n == "contrast") processingSetSimpleContrast(p, v);
    else if (n == "pivot") processingSetPivot(p, v);
    else if (n == "clarity") processingSetClarity(p, v);
    else if (n == "temperature") processingSetWhiteBalanceKelvin(p, v);
    else if (n == "tint") processingSetWhiteBalanceTint(p, v);
    else if (n == "shadows") processingSetShadows(p, v);
    else if (n == "highlights") processingSetHighlights(p, v);
    else if (n == "saturation") processingSetSaturation(p, v);
    else if (n == "vibrance") processingSetVibrance(p, v);
    else if (n == "sharpen") processingSetSharpening(p, v);
    else if (n == "sharpenMasking") processingSetSharpenMasking(p, static_cast<int>(v));
    else if (n == "sharpenBias") processingSetSharpeningBias(p, v);
    else if (n == "rbfLuma") processingSetRbfDenoiserLuma(p, static_cast<int>(v));
    else if (n == "rbfChroma") processingSetRbfDenoiserChroma(p, static_cast<int>(v));
    else if (n == "rbfRange") processingSetRbfDenoiserRange(p, static_cast<int>(v));
    else if (n == "denoiseStrength") processingSetDenoiserStrength(p, static_cast<uint8_t>(v));
    else if (n == "denoiseWindow") processingSetDenoiserWindow(p, static_cast<uint8_t>(v));
    else if (n == "caDesaturate") processingSetCaDesaturate(p, static_cast<int>(v));
    else if (n == "vignetteStrength") processingSetVignetteStrength(p, static_cast<int8_t>(std::clamp(v, -127.0, 127.0)));
    else if (n == "vignetteRadius" || n == "vignetteShape") {
        (n == "vignetteRadius" ? g->vigRadius : g->vigShape) = static_cast<float>(v);
        if (g->w > 0) processingSetVignetteMask(p, g->w, g->h, g->vigRadius, g->vigShape, 1.0f, 1.0f);
    }
    else if (n == "profile") processingSetImageProfile(p, static_cast<int>(v));
    else if (n == "gamut") processingSetGamut(p, static_cast<int>(v));
    else if (n == "tonemapping") processingSetTonemappingFunction(p, static_cast<int>(v));
    else if (n == "gamma") processingSetGamma(p, v);
    else if (n == "dcFactor") processingSetDCFactor(p, v);
    else if (n == "dcRange") processingSetDCRange(p, v);
    else if (n == "lcFactor") processingSetLCFactor(p, v);
    else if (n == "lcRange") processingSetLCRange(p, v);
    else if (n == "lighten") processingSetLightening(p, v);
    else if (n == "chromaBlur") processingSetChromaBlurRadius(p, static_cast<int>(v));
    else if (n == "chromaSeparation") { if (v != 0) processingEnableChromaSeparation(p); else processingDisableChromaSeparation(p); }
    else if (n == "camMatrix") {
        const int m = static_cast<int>(v);
        if (m == 0) processingDontUseCamMatrix(p); else if (m == 2) processingUseCamMatrixDanne(p); else processingUseCamMatrix(p);
    }
    else if (n == "creative") { if (v != 0) processingAllowCreativeAdjustments(p); else processingDontAllowCreativeAdjustments(p); }
    else if (n == "exr") { if (v != 0) processingEnableExr(p); else processingDisableExr(p); }
    else if (n == "agx") { if (v != 0) processingEnableAgX(p); else processingDisableAgX(p); }
    else if (n == "highlightReconstruction") { if (v != 0) processingEnableHighlightReconstruction(p); else processingDisableHighlightReconstruction(p); }
    else return JNI_FALSE;
    return JNI_TRUE;
}

// Toning colour (0..255 each) and strength (0..100).
JNIEXPORT void JNICALL
Java_com_matthew_crs_MlvGrade_nativeSetToning(JNIEnv*, jclass, jlong h, jint r, jint gr, jint b, jint strength) {
    auto* g = from(h);
    std::lock_guard<std::mutex> l(g->mutex);
    processingSetToning(g->p, static_cast<uint8_t>(r), static_cast<uint8_t>(gr), static_cast<uint8_t>(b), static_cast<uint8_t>(strength));
}

// Gradation curve (kind 0, channel 0 L, 1 R, 2 G, 3 B) or Hue-vs curve (kind 1).
JNIEXPORT void JNICALL
Java_com_matthew_crs_MlvGrade_nativeSetCurve(JNIEnv* env, jclass, jlong h, jint kind, jint channel, jfloatArray xs, jfloatArray ys) {
    auto* g = from(h);
    const jsize n = env->GetArrayLength(xs);
    std::vector<float> x(n), y(n);
    env->GetFloatArrayRegion(xs, 0, n, x.data());
    env->GetFloatArrayRegion(ys, 0, n, y.data());
    std::lock_guard<std::mutex> l(g->mutex);
    if (kind == 0) processingSetGCurve(g->p, n, x.data(), y.data(), static_cast<uint8_t>(channel));
    else processingSetHueVsCurves(g->p, n, x.data(), y.data(), static_cast<uint8_t>(channel));
}

// Kelvin/tint at which MLV-App renders camera RGB `neutral` as grey: MLV-App
// itself is the reference (a flat patch of the neutral colour is processed
// and its chroma minimised), so the as-shot white balance means exactly
// what MLV-App's sliders mean. Returns [kelvin, tint].
JNIEXPORT jdoubleArray JNICALL
Java_com_matthew_crs_MlvGrade_nativeFindWhiteBalance(JNIEnv* env, jclass, jlong h, jdouble nr, jdouble ng, jdouble nb) {
    auto* g = from(h);
    std::lock_guard<std::mutex> l(g->mutex);
    processingObject_t* probe = initProcessingObject();
    int dual = 0;
    probe->dual_iso = &dual;
    probe->wbFindActive = 0;
    const int W = 8, H = 8;
    probe->vignette_mask = static_cast<float*>(calloc(W * H, sizeof(float)));
    probe->gradient_mask = static_cast<uint16_t*>(calloc(W * H, sizeof(uint16_t)));
    processingSetBlackAndWhiteLevel(probe, 0, 65535, 16);
    processingSetCamMatrix(probe, g->p->cam_matrix, g->p->cam_matrix_A);
    processingSetImageProfile(probe, PROFILE_STANDARD);
    const double m = std::max({nr, ng, nb});
    const uint16_t px[3] = {static_cast<uint16_t>(nr / m * 20000), static_cast<uint16_t>(ng / m * 20000), static_cast<uint16_t>(nb / m * 20000)};
    std::vector<uint16_t> in(W * H * 3), out(W * H * 3);
    for (int i = 0; i < W * H; ++i) std::memcpy(&in[i * 3], px, sizeof px);
    auto chroma = [&](double k, double t) {
        processingSetWhiteBalance(probe, k, t);
        std::vector<uint16_t> work(in);
        applyProcessingObject(probe, W, H, work.data(), out.data(), 1, 1, 0);
        const double r = out[0], gg = out[1], b = out[2], y = (r + gg + b) / 3 + 1;
        return ((r - gg) * (r - gg) + (b - gg) * (b - gg)) / (y * y);
    };
    double bestK = 5600, bestT = 0, best = chroma(bestK, bestT);
    for (double k = 2500; k <= 10000; k += 250)
        for (double t = -10; t <= 10; t += 1) {
            const double c = chroma(k, t);
            if (c < best) { best = c; bestK = k; bestT = t; }
        }
    for (double step : {50.0, 10.0, 2.0}) {
        const double k0 = bestK, t0 = bestT;
        for (double k = k0 - 5 * step; k <= k0 + 5 * step; k += step)
            for (double t = t0 - 1; t <= t0 + 1; t += step / 50)
                if (k >= 2500 && k <= 10000 && t >= -10 && t <= 10) {
                    const double c = chroma(k, t);
                    if (c < best) { best = c; bestK = k; bestT = t; }
                }
    }
    freeProcessingObject(probe);
    const jdouble v[2] = {bestK, bestT};
    jdoubleArray arr = env->NewDoubleArray(2);
    env->SetDoubleArrayRegion(arr, 0, 2, v);
    return arr;
}

// Processes an RGBA16F camera-RGB HardwareBuffer (RCD output) with MLV-App
// and writes the result into an ARGB_8888 bitmap of the same size.
JNIEXPORT jint JNICALL
Java_com_matthew_crs_MlvGrade_nativeRender(JNIEnv* env, jclass, jlong h, jobject rgbF16, jobject bitmap) {
    auto* g = from(h);
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, rgbF16);
    AndroidBitmapInfo bi;
    if (!g || !hb || AndroidBitmap_getInfo(env, bitmap, &bi) != ANDROID_BITMAP_RESULT_SUCCESS) return -1;
    AHardwareBuffer_Desc d{};
    AHardwareBuffer_describe(hb, &d);
    const int w = static_cast<int>(std::min(d.width, bi.width)), hh = static_cast<int>(std::min(d.height, bi.height));
    std::lock_guard<std::mutex> l(g->mutex);
    ensureSize(g, w, hh);
    g->in.resize(size_t(w) * hh * 3);
    g->out.resize(g->in.size());
    void* src = nullptr;
    if (AHardwareBuffer_lock(hb, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &src) != 0) return -2;
    for (int y = 0; y < hh; ++y) {
        const uint16_t* s = static_cast<const uint16_t*>(src) + size_t(y) * d.stride * 4;
        uint16_t* o = g->in.data() + size_t(y) * w * 3;
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c)
                o[x * 3 + c] = static_cast<uint16_t>(std::clamp(half2float(s[x * 4 + c]) * 65535.0f + 0.5f, 0.0f, 65535.0f));
    }
    AHardwareBuffer_unlock(hb, nullptr);
    static const int threads = static_cast<int>(std::max(1u, std::thread::hardware_concurrency()));
    applyProcessingObject(g->p, w, hh, g->in.data(), g->out.data(), threads, 1, 0);
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -3;
    for (int y = 0; y < hh; ++y) {
        const uint16_t* s = g->out.data() + size_t(y) * w * 3;
        uint8_t* o = static_cast<uint8_t*>(pixels) + size_t(y) * bi.stride;
        for (int x = 0; x < w; ++x) {
            o[x * 4 + 0] = static_cast<uint8_t>(s[x * 3 + 0] >> 8);
            o[x * 4 + 1] = static_cast<uint8_t>(s[x * 3 + 1] >> 8);
            o[x * 4 + 2] = static_cast<uint8_t>(s[x * 3 + 2] >> 8);
            o[x * 4 + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return 0;
}

}  // extern "C"
