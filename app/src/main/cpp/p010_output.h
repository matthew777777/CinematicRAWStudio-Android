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

// Processed RGB16 -> limited-range BT.709 P010. Quantize only at the 10-bit boundary.
inline int rgb16ToP010(JNIEnv* env, jobject image, const uint16_t* rgb, int sw, int sh, int w, int h) {
    return withP010Planes(env, image, w, h, [&](P010Plane* p) {
        const float scale = rgb && sw > 0 && sh > 0 ? std::min(float(w)/sw,float(h)/sh) : 1.f;
        const float left = (w-sw*scale)*.5f, top = (h-sh*scale)*.5f;
        struct RGB { float r,g,b; };
        auto sample = [&](int x,int y) -> RGB {
            if (!rgb || sw <= 0 || sh <= 0 || x+.5f < left || y+.5f < top || x+.5f >= left+sw*scale || y+.5f >= top+sh*scale) return {0,0,0};
            const float sx=std::clamp((x+.5f-left)/scale-.5f,0.f,float(sw-1));
            const float sy=std::clamp((y+.5f-top)/scale-.5f,0.f,float(sh-1));
            const int x0=int(sx),y0=int(sy),x1=std::min(x0+1,sw-1),y1=std::min(y0+1,sh-1);
            const float fx=sx-x0,fy=sy-y0;
            float c[3]{};
            for(int k=0;k<3;++k) {
                const float a=rgb[(size_t(y0)*sw+x0)*3+k]*(1-fx)+rgb[(size_t(y0)*sw+x1)*3+k]*fx;
                const float b=rgb[(size_t(y1)*sw+x0)*3+k]*(1-fx)+rgb[(size_t(y1)*sw+x1)*3+k]*fx;
                c[k]=(a*(1-fy)+b*fy)/65535.f;
            }
            return {c[0],c[1],c[2]};
        };
        auto store = [&](int plane,int x,int y,float value) {
            const uint16_t code=uint16_t(std::clamp(std::lround(value),0L,1023L))<<6;
            std::memcpy(p[plane].p+size_t(y)*p[plane].row+size_t(x)*p[plane].pixel,&code,2);
        };
        for(int y=0;y<h;y+=2) for(int x=0;x<w;x+=2) {
            RGB average{};
            for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx) {
                const RGB c=sample(x+dx,y+dy);
                const float luma=.2126f*c.r+.7152f*c.g+.0722f*c.b;
                store(0,x+dx,y+dy,64.f+876.f*luma);
                average.r+=c.r*.25f;average.g+=c.g*.25f;average.b+=c.b*.25f;
            }
            const float luma=.2126f*average.r+.7152f*average.g+.0722f*average.b;
            store(1,x/2,y/2,512.f+896.f*(average.b-luma)/1.8556f);
            store(2,x/2,y/2,512.f+896.f*(average.r-luma)/1.5748f);
        }
        return 0;
    });
}
