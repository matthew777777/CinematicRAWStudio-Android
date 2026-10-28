// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for one .CRAW take: metadata, developed preview frames rendered
// straight into an ARGB_8888 Bitmap, and the take's audio as a WAV stream.
// Decoding, metadata and audio come from the CRAW decoder's Clip helper;
// colour (levels, lens shading, white balance, matrices, transfer) from the
// CRAW TUI developer, so phone and desktop render a take identically.
#include <jni.h>
#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>

#include "common/Clip.h"
#include "Develop.h"

#include <algorithm>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
#include <chrono>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

struct Handle {
    explicit Handle(const std::string& path) : clip(path) {}
    Handle(int fd, const std::string& name) : clip(fd, name) {}
    craw::tools::Clip clip;
    std::mutex mutex;  // develop scratch is per handle; one render at a time
    std::vector<uint16_t> rgb;
};

Handle* from(jlong h) { return reinterpret_cast<Handle*>(h); }

// Recorded lens shading applied to the mosaic: (v - black) * gain + black,
// with the CRAW TUI developer's interpolation (grid points at cell centres,
// bilinear, edges clamped; gain plane R, Gr, Gb, B by CFA position).
// Playback proxy: 2x2 same-colour bin to a half-size mosaic of the same CFA
// phase (output (x, y) averages the four samples of its colour in the 4x4
// source block), lens shading applied at the block. Paused frames use the
// full-resolution path.
void binHalf(const craw::tools::RawFrame& raw, bool shaded, uint16_t* dst, unsigned threads);

void applyShading(const craw::tools::RawFrame& raw, uint16_t* dst, unsigned threads) {
    const uint32_t W = raw.width, H = raw.height;
    const int cols = raw.shadingColumns, rows = raw.shadingRows;
    static const int kColour[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    const int* colour = kColour[raw.cfa & 3];
    int plane[4];
    for (int pos = 0; pos < 4; ++pos) {
        const int c = colour[pos];
        if (c != 1) { plane[pos] = c == 0 ? 0 : 3; continue; }
        const int rowHasRed = colour[(pos & 2)] == 0 || colour[(pos & 2) | 1] == 0;
        plane[pos] = rowHasRed ? 1 : 2;
    }
    struct Tap { int i0, i1; float w; };
    auto taps = [](uint32_t n, int points) {
        std::vector<Tap> out(n);
        for (uint32_t k = 0; k < n; ++k) {
            const double g = std::clamp((k + 0.5) / n * points - 0.5, 0.0, points - 1.0);
            const int i0 = static_cast<int>(g);
            out[k] = {i0, std::min(i0 + 1, points - 1), static_cast<float>(g - i0)};
        }
        return out;
    };
    const auto colTap = taps(W, cols), rowTap = taps(H, rows);
    const float* G = raw.shadingGains.data();
    auto work = [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            const Tap& ry = rowTap[y];
            const uint16_t* s = raw.pixels.data() + size_t{y} * W;
            uint16_t* d = dst + size_t{y} * W;
            for (uint32_t x = 0; x < W; ++x) {
                const int pos = static_cast<int>((y & 1) * 2 + (x & 1));
                const Tap& cx = colTap[x];
                auto at = [&](int xx, int yy) { return G[(size_t(yy) * cols + xx) * 4 + plane[pos]]; };
                const float top = at(cx.i0, ry.i0) + (at(cx.i1, ry.i0) - at(cx.i0, ry.i0)) * cx.w;
                const float bot = at(cx.i0, ry.i1) + (at(cx.i1, ry.i1) - at(cx.i0, ry.i1)) * cx.w;
                const float gain = top + (bot - top) * ry.w;
                const float b = static_cast<float>(raw.black[pos]);
                const float v = (s[x] - b) * gain + b;
                d[x] = static_cast<uint16_t>(std::clamp(v + 0.5f, 0.f, 65535.f));
            }
        }
    };
    std::vector<std::thread> pool;
    const unsigned n = std::max(1u, std::min(threads, H));
    for (unsigned t = 0; t < n; ++t) pool.emplace_back(work, H * t / n, H * (t + 1) / n);
    for (auto& th : pool) th.join();
}

void binHalf(const craw::tools::RawFrame& raw, bool shaded, uint16_t* dst, unsigned threads) {
    const uint32_t W = raw.width, H = raw.height, OW = W / 4 * 2, OH = H / 4 * 2;
    const int cols = raw.shadingColumns, rows = raw.shadingRows;
    static const int kColour[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
    const int* colour = kColour[raw.cfa & 3];
    int plane[4];
    for (int pos = 0; pos < 4; ++pos) {
        const int c = colour[pos];
        if (c != 1) { plane[pos] = c == 0 ? 0 : 3; continue; }
        const int rowHasRed = colour[(pos & 2)] == 0 || colour[(pos & 2) | 1] == 0;
        plane[pos] = rowHasRed ? 1 : 2;
    }
    // Gain grid interpolated vertically once per output row (per plane), then
    // horizontally per block: the same bilinear (grid points at cell centres).
    auto axis = [](double f, int points, int& i0, int& i1, float& w) {
        const double g = std::clamp(f * points - 0.5, 0.0, points - 1.0);
        i0 = static_cast<int>(g); i1 = std::min(i0 + 1, points - 1); w = static_cast<float>(g - i0);
    };
    std::vector<int> cx0(OW / 2), cx1(OW / 2);
    std::vector<float> cw(OW / 2);
    for (uint32_t q = 0; q < OW / 2; ++q) axis((q * 4 + 2.0) / W, std::max(cols, 1), cx0[q], cx1[q], cw[q]);
    auto work = [&](uint32_t y0, uint32_t y1) {
        std::vector<float> rowGain(shaded ? size_t(cols) * 4 : 0);
        for (uint32_t oy = y0; oy < y1; ++oy) {
            const uint32_t py = oy & 1, by = (oy >> 1) * 4 + py;
            if (shaded) {
                int r0i, r1i; float wy;
                axis((by + 1.5) / H, rows, r0i, r1i, wy);
                const float* G = raw.shadingGains.data();
                for (size_t k = 0; k < rowGain.size(); ++k)
                    rowGain[k] = G[size_t(r0i) * cols * 4 + k] + (G[size_t(r1i) * cols * 4 + k] - G[size_t(r0i) * cols * 4 + k]) * wy;
            }
            const uint16_t* r0 = raw.pixels.data() + size_t{by} * W;
            const uint16_t* r2 = r0 + 2 * size_t{W};
            uint16_t* d = dst + size_t{oy} * OW;
            for (uint32_t ox = 0; ox < OW; ++ox) {
                const uint32_t px = ox & 1, bx = (ox >> 1) * 4 + px;
                const int pos = static_cast<int>(py * 2 + px);
                const float b = static_cast<float>(raw.black[pos]);
                const float sum = static_cast<float>(r0[bx] + r0[bx + 2] + r2[bx] + r2[bx + 2]);
                float g = 1.f;
                if (shaded) {
                    const uint32_t q = ox >> 1;
                    const float a0 = rowGain[size_t(cx0[q]) * 4 + plane[pos]], a1 = rowGain[size_t(cx1[q]) * 4 + plane[pos]];
                    g = a0 + (a1 - a0) * cw[q];
                }
                d[ox] = static_cast<uint16_t>(std::clamp((sum * 0.25f - b) * g + b + 0.5f, 0.f, 65535.f));
            }
        }
    };
    std::vector<std::thread> pool;
    const unsigned n = std::max(1u, std::min(threads, OH));
    for (unsigned t = 0; t < n; ++t) pool.emplace_back(work, OH * t / n, OH * (t + 1) / n);
    for (auto& th : pool) th.join();
}

std::string jstr(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string escape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') o += '\\';
        if (static_cast<unsigned char>(c) >= 0x20) o += c;
    }
    return o;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_matthew_crs_CrawClip_nativeOpen(JNIEnv* env, jclass, jstring path) {
    try {
        return reinterpret_cast<jlong>(new Handle(jstr(env, path)));
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, "CrawClip", "open failed: %s", e.what());
        return 0;
    }
}

// Opens from a content-provider descriptor (memory-mapped; the caller may
// close `fd` afterwards). SAF files cannot be reopened by path.
JNIEXPORT jlong JNICALL
Java_com_matthew_crs_CrawClip_nativeOpenFd(JNIEnv* env, jclass, jint fd, jstring name) {
    try {
        const auto t0 = std::chrono::steady_clock::now();
        auto* h = new Handle(fd, jstr(env, name));
        __android_log_print(ANDROID_LOG_INFO, "CrawClip", "open %s: %.0f ms mapped=%d", jstr(env, name).c_str(),
            std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(),
            h->clip.reader().mapped());
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, "CrawClip", "open failed: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_matthew_crs_CrawClip_nativeClose(JNIEnv*, jclass, jlong h) {
    delete from(h);
}

// {"width","height","frames","fps","rgb","camera","iso","exposure","audioRate","audioChannels","audioBits","wavSize"}
JNIEXPORT jstring JNICALL
Java_com_matthew_crs_CrawClip_nativeInfo(JNIEnv* env, jclass, jlong h) {
    auto* p = from(h);
    if (!p) return nullptr;
    const auto& c = p->clip;
    const auto [w, hh] = c.imageSize();
    const auto& a = c.audio();
    double iso = 0, exposure = 0;
    if (c.frameCount() > 0) {
        try {
            const auto raw = c.frameRaw(0, 1);
            iso = raw.iso;
            exposure = raw.exposureSeconds;
        } catch (...) {
        }
    }
    char buf[640];
    std::snprintf(buf, sizeof buf,
                  "{\"width\":%u,\"height\":%u,\"frames\":%zu,\"fps\":%.6f,\"rgb\":%s,\"camera\":\"%s\","
                  "\"iso\":%.0f,\"exposure\":%.9f,\"audioRate\":%u,\"audioChannels\":%d,\"audioBits\":%d,"
                  "\"wavSize\":%llu,\"recovered\":%s}",
                  w, hh, c.frameCount(), c.info().fpsNum / c.info().fpsDen, c.isRgb() ? "true" : "false",
                  escape(c.info().uniqueModel).c_str(), iso, exposure, a.sampleRate, a.channels,
                  a.format == 1 ? 16 : a.format ? 24 : 0, static_cast<unsigned long long>(a.format ? c.wavSize() : 0),
                  c.reader().recovered() ? "true" : "false");
    return env->NewStringUTF(buf);
}

// Develops frame `index` at half resolution (Rec.709) into `bitmap`
// (ARGB_8888, width/height = half the picture size, as reported by
// nativePreviewSize). Returns 0 on success.
JNIEXPORT jint JNICALL
Java_com_matthew_crs_CrawClip_nativeRender(JNIEnv* env, jclass, jlong h, jint index, jobject bitmap,
                                           jfloat exposureEv) {
    auto* p = from(h);
    if (!p || index < 0 || static_cast<size_t>(index) >= p->clip.frameCount()) return -1;
    AndroidBitmapInfo bi;
    if (AndroidBitmap_getInfo(env, bitmap, &bi) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bi.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return -2;
    std::lock_guard<std::mutex> lock(p->mutex);
    try {
        static const unsigned threads = std::max(1u, std::thread::hardware_concurrency());
        const auto raw = p->clip.frameRaw(static_cast<size_t>(index), threads);
        craw::tui::DevelopSettings s;
        s.halfResolution = true;
        s.exposureEv = exposureEv;
        uint32_t w = 0, hh = 0;
        craw::tui::develop(raw, p->clip.info(), s, p->rgb, w, hh, threads);
        void* pixels = nullptr;
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return -3;
        const uint32_t cw = std::min(w, bi.width), ch = std::min(hh, bi.height);
        for (uint32_t y = 0; y < ch; ++y) {
            const uint16_t* s16 = p->rgb.data() + static_cast<size_t>(y) * w * 3;
            auto* d = reinterpret_cast<uint8_t*>(static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * bi.stride);
            for (uint32_t x = 0; x < cw; ++x, s16 += 3, d += 4) {
                d[0] = static_cast<uint8_t>(s16[0] >> 8);
                d[1] = static_cast<uint8_t>(s16[1] >> 8);
                d[2] = static_cast<uint8_t>(s16[2] >> 8);
                d[3] = 255;
            }
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        return 0;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, "CrawClip", "render %d failed: %s", index, e.what());
        return -4;
    }
}

// Preview size: [width, height] of nativeRender's output.
JNIEXPORT jintArray JNICALL
Java_com_matthew_crs_CrawClip_nativePreviewSize(JNIEnv* env, jclass, jlong h) {
    auto* p = from(h);
    if (!p) return nullptr;
    const auto [w, hh] = p->clip.frameSize();  // coded size; develop halves it (RGB frames too)
    const jint v[2] = {static_cast<jint>(w / 2), static_cast<jint>(hh / 2)};
    jintArray out = env->NewIntArray(2);
    env->SetIntArrayRegion(out, 0, 2, v);
    return out;
}

// Decodes Bayer frame `index` straight into `blob` (a BLOB AHardwareBuffer
// of width * height uint16 samples, the RawLens GPU input) and returns the
// frame parameters the GPU grade needs:
//   [width, height, cfa, black0..3 (2x2 row-major), white, neutral R, G, B,
//    shadeCols, shadeRows, gains (R, Gr, Gb, B per point)...]
// Null for RGB (binned) frames or on error.
JNIEXPORT jfloatArray JNICALL
Java_com_matthew_crs_CrawClip_nativeDecodeToBlob(JNIEnv* env, jclass, jlong h, jint index, jobject blob, jboolean half,
                                                 jint threadCount) {
    auto* p = from(h);
    if (!p || p->clip.isRgb() || index < 0 || static_cast<size_t>(index) >= p->clip.frameCount()) return nullptr;
    AHardwareBuffer* hb = AHardwareBuffer_fromHardwareBuffer(env, blob);
    if (!hb) return nullptr;
    try {
        const unsigned threads = threadCount > 0 ? static_cast<unsigned>(threadCount) : std::max(1u, std::thread::hardware_concurrency());
        const auto c0 = std::chrono::steady_clock::now();
        // Read the compressed frame ahead in one request instead of a page
        // fault per 4 KB (content-provider files are served through FUSE).
        {
            const auto rec = p->clip.reader().frame(static_cast<size_t>(index));
            static const uintptr_t page = static_cast<uintptr_t>(sysconf(_SC_PAGESIZE));
            const uintptr_t a = reinterpret_cast<uintptr_t>(rec.payload) & ~(page - 1);
            madvise(reinterpret_cast<void*>(a), reinterpret_cast<uintptr_t>(rec.payload) + rec.payloadSize - a, MADV_WILLNEED);
        }
        const auto raw = p->clip.frameRaw(static_cast<size_t>(index), threads);
        const auto c1 = std::chrono::steady_clock::now();
        const size_t bytes = raw.pixels.size() * 2;
        AHardwareBuffer_Desc d{};
        AHardwareBuffer_describe(hb, &d);
        if (static_cast<size_t>(d.width) * d.height < bytes) return nullptr;
        void* dst = nullptr;
        if (AHardwareBuffer_lock(hb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &dst) != 0) return nullptr;
        const bool shaded = raw.shadingColumns > 0 && raw.shadingRows > 0 &&
            raw.shadingGains.size() == size_t{raw.shadingColumns} * raw.shadingRows * 4;
        if (half) {
            binHalf(raw, shaded, static_cast<uint16_t*>(dst), threads);
        } else if (shaded) {
            applyShading(raw, static_cast<uint16_t*>(dst), threads);
        } else {
            std::memcpy(dst, raw.pixels.data(), bytes);
        }
        AHardwareBuffer_unlock(hb, nullptr);
        static int logged = 0;
        if (logged++ % 15 == 0)
            __android_log_print(ANDROID_LOG_INFO, "CrawClip", "decode %.1f ms, shade+copy %.1f ms",
                std::chrono::duration<double, std::milli>(c1 - c0).count(),
                std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c1).count());
        const uint32_t ow = half ? raw.width / 4 * 2 : raw.width, oh = half ? raw.height / 4 * 2 : raw.height;
        std::vector<float> out = {static_cast<float>(ow), static_cast<float>(oh), static_cast<float>(raw.cfa)};
        for (double b : raw.black) out.push_back(static_cast<float>(b));
        out.push_back(static_cast<float>(raw.white));
        for (double n : raw.neutral) out.push_back(static_cast<float>(n));
        out.push_back(0);  // shading already applied above
        out.push_back(0);
        jfloatArray arr = env->NewFloatArray(static_cast<jsize>(out.size()));
        env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
        return arr;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_WARN, "CrawClip", "decode %d failed: %s", index, e.what());
        return nullptr;
    }
}

// White-balanced camera RGB -> linear Rec.709, row-major (CRAW TUI
// cameraToTarget: ForwardMatrix1, else ColorMatrix1), for the GPU grade.
JNIEXPORT jfloatArray JNICALL
Java_com_matthew_crs_CrawClip_nativeCcm(JNIEnv* env, jclass, jlong h, jfloat nr, jfloat ng, jfloat nb) {
    auto* p = from(h);
    if (!p) return nullptr;
    const auto m = craw::tui::cameraToTarget(p->clip.info(), {nr, ng, nb}, craw::tui::Gamut::Rec709);
    jfloat v[9];
    for (int i = 0; i < 9; ++i) v[i] = static_cast<jfloat>(m[i]);
    jfloatArray arr = env->NewFloatArray(9);
    env->SetFloatArrayRegion(arr, 0, 9, v);
    return arr;
}

// XYZ->camera matrices for MLV-App: [daylight (illuminant 21, D65) x9,
// tungsten (illuminant 17, Standard A) x9], chosen by illuminant, not by
// position (Canon DNGs put D65 second, RawLens first). One matrix -> both.
JNIEXPORT jdoubleArray JNICALL
Java_com_matthew_crs_CrawClip_nativeCamMatrices(JNIEnv* env, jclass, jlong h) {
    auto* p = from(h);
    if (!p) return nullptr;
    const auto& i = p->clip.info();
    const std::vector<double>* d65 = nullptr;
    const std::vector<double>* a = nullptr;
    auto pick = [&](int ill, const std::vector<double>& m) {
        if (m.size() != 9) return;
        if (ill == 21 || ill == 20 || ill == 23) d65 = &m;  // D65 / D55 / D50 class
        else if (ill == 17 || ill == 3) a = &m;            // Standard A / tungsten
    };
    pick(i.illuminant1, i.colorMatrix1);
    pick(i.illuminant2, i.colorMatrix2);
    if (!d65) d65 = i.colorMatrix1.size() == 9 ? &i.colorMatrix1 : a;
    if (!a) a = d65;
    if (!d65) return nullptr;
    double out[18];
    std::copy(d65->begin(), d65->end(), out);
    std::copy(a->begin(), a->end(), out + 9);
    jdoubleArray arr = env->NewDoubleArray(18);
    env->SetDoubleArrayRegion(arr, 0, 18, out);
    return arr;
}

// Reads WAV bytes [offset, offset + len) into `dst`; returns the count.
JNIEXPORT jint JNICALL
Java_com_matthew_crs_CrawClip_nativeReadWav(JNIEnv* env, jclass, jlong h, jlong offset, jbyteArray dst, jint len) {
    auto* p = from(h);
    if (!p || p->clip.audio().format == 0 || offset < 0) return -1;
    std::vector<uint8_t> tmp(static_cast<size_t>(std::max(len, 0)));
    const size_t n = p->clip.readWav(static_cast<uint64_t>(offset), tmp.data(), tmp.size());
    env->SetByteArrayRegion(dst, 0, static_cast<jsize>(n), reinterpret_cast<const jbyte*>(tmp.data()));
    return static_cast<jint>(n);
}

}  // extern "C"
