// SPDX-License-Identifier: GPL-3.0-or-later
//
// JNI bridge for one .CRAW take: metadata, developed preview frames rendered
// straight into an ARGB_8888 Bitmap, and the take's audio as a WAV stream.
// Decoding, metadata and audio come from the CRAW decoder's Clip helper;
// colour (levels, lens shading, white balance, matrices, transfer) from the
// CRAW TUI developer, so phone and desktop render a take identically.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include "common/Clip.h"
#include "Develop.h"

#include <algorithm>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

struct Handle {
    explicit Handle(const std::string& path) : clip(path) {}
    craw::tools::Clip clip;
    std::mutex mutex;  // develop scratch is per handle; one render at a time
    std::vector<uint16_t> rgb;
};

Handle* from(jlong h) { return reinterpret_cast<Handle*>(h); }

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
