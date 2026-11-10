// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>

struct P010Plane { uint8_t* p; int row, pixel; jlong bytes; jobject object, buffer; };

// Locks the three planes of a MediaCodec P010 input Image and calls
// fill(planes); returns its result, or a negative code if the planes are unusable.
template <class Fill>
inline int withP010Planes(JNIEnv* env, jobject image, int w, int h, Fill fill) {
    if (!image || w <= 0 || h <= 0 || (w & 1) || (h & 1)) return -1;
    jclass ic = env->GetObjectClass(image);
    jmethodID getPlanes = env->GetMethodID(ic, "getPlanes", "()[Landroid/media/Image$Plane;");
    auto planes = static_cast<jobjectArray>(env->CallObjectMethod(image, getPlanes));
    if (env->ExceptionCheck() || !planes || env->GetArrayLength(planes) != 3) return -2;
    P010Plane p[3]{};
    int result = 0;
    for (int i = 0; i < 3; ++i) {
        p[i].object = env->GetObjectArrayElement(planes, i);
        jclass pc = env->GetObjectClass(p[i].object);
        p[i].buffer = env->CallObjectMethod(p[i].object, env->GetMethodID(pc,"getBuffer","()Ljava/nio/ByteBuffer;"));
        p[i].row = env->CallIntMethod(p[i].object,env->GetMethodID(pc,"getRowStride","()I"));
        p[i].pixel = env->CallIntMethod(p[i].object,env->GetMethodID(pc,"getPixelStride","()I"));
        jclass bc = env->GetObjectClass(p[i].buffer);
        const int position = env->CallIntMethod(p[i].buffer,env->GetMethodID(bc,"position","()I"));
        const int limit = env->CallIntMethod(p[i].buffer,env->GetMethodID(bc,"limit","()I"));
        p[i].p = static_cast<uint8_t*>(env->GetDirectBufferAddress(p[i].buffer));
        p[i].bytes = limit - position;
        if (p[i].p) p[i].p += position;
        env->DeleteLocalRef(bc);env->DeleteLocalRef(pc);
        const int pw = i ? w/2 : w, ph = i ? h/2 : h;
        if (!p[i].p || p[i].pixel < 2 || p[i].row <= 0 ||
            int64_t(ph-1)*p[i].row + int64_t(pw-1)*p[i].pixel+2 > p[i].bytes) result = -3;
    }
    if (result == 0) result = fill(p);
    for(auto& plane:p) {if(plane.buffer)env->DeleteLocalRef(plane.buffer);if(plane.object)env->DeleteLocalRef(plane.object);}
    env->DeleteLocalRef(planes);env->DeleteLocalRef(ic);
    return result;
}

// Packed P010 planes (Y: w x h words; CbCr interleaved: w/2 x h/2 pairs) -> Image.
inline int p010PlanesToImage(JNIEnv* env, jobject image, const uint16_t* y, const uint16_t* cbcr, int w, int h) {
    return withP010Planes(env, image, w, h, [&](P010Plane* p) {
        for (int r = 0; r < h; ++r) {
            const uint16_t* s = y + size_t(r) * w;
            uint8_t* o = p[0].p + size_t(r) * p[0].row;
            if (p[0].pixel == 2) std::memcpy(o, s, size_t(w) * 2);
            else for (int x = 0; x < w; ++x) std::memcpy(o + size_t(x) * p[0].pixel, s + x, 2);
        }
        // Semi-planar P010 (U and V views of one interleaved plane, V = U + 2):
        // one copy per row; otherwise per sample.
        const bool interleaved = p[1].pixel == 4 && p[2].pixel == 4 && p[2].row == p[1].row && p[2].p == p[1].p + 2 &&
            int64_t(h / 2 - 1) * p[2].row + int64_t(w / 2 - 1) * 4 + 2 <= p[2].bytes;
        for (int r = 0; r < h / 2; ++r) {
            const uint16_t* s = cbcr + size_t(r) * w;
            if (interleaved) { std::memcpy(p[1].p + size_t(r) * p[1].row, s, size_t(w) * 2); continue; }
            for (int x = 0; x < w / 2; ++x) {
                std::memcpy(p[1].p + size_t(r) * p[1].row + size_t(x) * p[1].pixel, s + x * 2, 2);
                std::memcpy(p[2].p + size_t(r) * p[2].row + size_t(x) * p[2].pixel, s + x * 2 + 1, 2);
            }
        }
        return 0;
    });
}

// Empty timeline canvas: write legal black without invoking any grading engine.
inline int blackP010Image(JNIEnv* env,jobject image,int w,int h) {
    return withP010Planes(env,image,w,h,[&](P010Plane* p) {
        const uint16_t black=64u<<6,neutral=512u<<6;
        for(int row=0;row<h;++row)for(int x=0;x<w;++x)std::memcpy(p[0].p+size_t(row)*p[0].row+size_t(x)*p[0].pixel,&black,2);
        for(int plane=1;plane<3;++plane)for(int row=0;row<h/2;++row)for(int x=0;x<w/2;++x)std::memcpy(p[plane].p+size_t(row)*p[plane].row+size_t(x)*p[plane].pixel,&neutral,2);
        return 0;
    });
}
