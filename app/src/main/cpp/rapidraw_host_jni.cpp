// SPDX-License-Identifier: AGPL-3.0-or-later
// Independent bridge to pinned RapidRAW parameter preparation; no MLV headers.
#include <jni.h>
#include <cstddef>
#include <cstdint>
#include <vector>
#include <string>
#include <cstring>
#include <android/bitmap.h>
#include "p010_output.h"
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

extern "C" size_t crs_rapidraw_parameter_size();
extern "C" int32_t crs_rapidraw_set_output(uint8_t*, size_t, uint32_t);
extern "C" intptr_t crs_rapidraw_prepare(const uint8_t*, size_t, double, double,
    uint32_t, uint32_t, float, float, uint32_t, float, uint32_t, int32_t, uint8_t*, size_t);
extern "C" void* crs_rapidraw_renderer_new(uint32_t,uint32_t);
extern "C" void crs_rapidraw_renderer_free(void*);
extern "C" int32_t crs_rapidraw_upload(void*, const uint16_t*, size_t, uint32_t);
extern "C" int32_t crs_rapidraw_render8(const void*,const uint8_t*,size_t,uint8_t*,size_t);
extern "C" size_t crs_rapidraw_last_error(uint8_t*,size_t);
extern "C" int32_t crs_rapidraw_upload_camera(void*,const uint16_t*,size_t,const float*,const float*,const float*,double*);

static void renderError(JNIEnv* env) {
    std::vector<uint8_t> bytes(4096);
    bytes.resize(crs_rapidraw_last_error(bytes.data(),bytes.size()));
    std::string message(bytes.begin(),bytes.end());
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),message.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeCreate(JNIEnv* env,jclass,jint width,jint height) {
    auto* renderer=crs_rapidraw_renderer_new(width,height);
    if(!renderer) renderError(env);
    return reinterpret_cast<jlong>(renderer);
}
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeClose(JNIEnv*,jclass,jlong handle) {
    crs_rapidraw_renderer_free(reinterpret_cast<void*>(handle));
}
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeUpload(JNIEnv* env,jclass,jlong handle,jobject source,jboolean raw) {
    auto* words=static_cast<uint16_t*>(env->GetDirectBufferAddress(source));
    const auto capacity=env->GetDirectBufferCapacity(source);
    if(capacity<0 || crs_rapidraw_upload(reinterpret_cast<void*>(handle),words,static_cast<size_t>(capacity)/2,raw)!=0) renderError(env);
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeUploadCamera(JNIEnv* env,jclass,jlong handle,jobject hardware,jfloatArray matrixA,jfloatArray matrixD65,jfloatArray cameraNeutral) {
    if(env->GetArrayLength(matrixA)!=9||env->GetArrayLength(matrixD65)!=9||env->GetArrayLength(cameraNeutral)!=3){
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Invalid camera calibration lengths");return nullptr;
    }
    float a[9],d65[9],neutral[3];
    env->GetFloatArrayRegion(matrixA,0,9,a);env->GetFloatArrayRegion(matrixD65,0,9,d65);env->GetFloatArrayRegion(cameraNeutral,0,3,neutral);
    auto* buffer=AHardwareBuffer_fromHardwareBuffer(env,hardware);
    AHardwareBuffer_Desc desc{};if(buffer)AHardwareBuffer_describe(buffer,&desc);
    if(!buffer||desc.format!=AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT){
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Expected MHC RGBA16F source");return nullptr;
    }
    void* data=nullptr;
    if(AHardwareBuffer_lock(buffer,AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,-1,nullptr,&data)!=0){
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Cannot read MHC source");return nullptr;
    }
    std::vector<uint16_t> words(size_t(desc.width)*desc.height*4);
    for(uint32_t y=0;y<desc.height;++y)std::memcpy(words.data()+size_t(y)*desc.width*4,
        static_cast<uint16_t*>(data)+size_t(y)*desc.stride*4,size_t(desc.width)*8);
    AHardwareBuffer_unlock(buffer,nullptr);
    double wb[2]{};
    if(crs_rapidraw_upload_camera(reinterpret_cast<void*>(handle),words.data(),words.size(),a,d65,neutral,wb)!=0){renderError(env);return nullptr;}
    auto result=env->NewDoubleArray(2);if(result)env->SetDoubleArrayRegion(result,0,2,wb);return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeRender(JNIEnv* env,jclass,jlong handle,jobject parameters,jobject bitmap) {
    auto* bytes=static_cast<uint8_t*>(env->GetDirectBufferAddress(parameters));
    const auto capacity=env->GetDirectBufferCapacity(parameters);
    AndroidBitmapInfo info{};
    if(capacity<0 || AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Expected RGBA8888 bitmap and direct parameter buffer");return;
    }
    std::vector<uint8_t> output(size_t(info.width)*info.height*4);
    if(crs_rapidraw_render8(reinterpret_cast<void*>(handle),bytes,static_cast<size_t>(capacity),output.data(),output.size())!=0){renderError(env);return;}
    void* pixels=nullptr;
    if(AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS){
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Cannot access output bitmap");return;
    }
    for(uint32_t y=0;y<info.height;++y) std::memcpy(static_cast<uint8_t*>(pixels)+size_t(y)*info.stride,output.data()+size_t(y)*info.width*4,size_t(info.width)*4);
    AndroidBitmap_unlockPixels(env,bitmap);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_matthew_crs_rapidraw_RapidHost_nativePrepare(JNIEnv* env, jclass, jbyteArray json,
    jdouble temperature, jdouble tint, jboolean raw, jboolean scene,
    jfloat dngBaseline, jfloat preservation, jboolean baselineEnabled, jfloat baseline,
    jboolean compensate, jint tonemapper, jint outputMode) {
    if (!json) return nullptr;
    const auto length = env->GetArrayLength(json);
    std::vector<uint8_t> text(static_cast<size_t>(length));
    env->GetByteArrayRegion(json, 0, length, reinterpret_cast<jbyte*>(text.data()));
    if (env->ExceptionCheck()) return nullptr;
    std::vector<uint8_t> buffer(crs_rapidraw_parameter_size());
    const auto result = crs_rapidraw_prepare(text.data(), length,
        temperature, tint, raw, scene, dngBaseline, preservation,
        baselineEnabled, baseline, compensate, tonemapper, buffer.data(), buffer.size());
    if (result < 0 || crs_rapidraw_set_output(buffer.data(), buffer.size(), static_cast<uint32_t>(outputMode)) != 0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "RapidRAW parameter preparation failed");
        return nullptr;
    }
    auto bytes = env->NewByteArray(static_cast<jsize>(buffer.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(buffer.size()), reinterpret_cast<const jbyte*>(buffer.data()));
    return bytes;
}

// The graded RGB16F frame is fitted and converted to P010 entirely on Vulkan.
extern "C" int32_t crs_rapidraw_render_p010(const void*,const uint8_t*,size_t,uint32_t,uint32_t,uint8_t*,size_t);
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeP010(JNIEnv* env,jclass,jlong handle,jobject parameters,jobject image,jint width,jint height) {
    auto* params=static_cast<uint8_t*>(env->GetDirectBufferAddress(parameters));
    const auto size=env->GetDirectBufferCapacity(parameters);
    if(!params||size<0||width<2||height<2||width%2||height%2) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Invalid P010 parameters");return;
    }
    std::vector<uint16_t> pixels(size_t(width)*height*3/2);
    if(crs_rapidraw_render_p010(reinterpret_cast<void*>(handle),params,size_t(size),uint32_t(width),uint32_t(height),reinterpret_cast<uint8_t*>(pixels.data()),pixels.size()*2)!=0){renderError(env);return;}
    const auto* chroma=pixels.data()+size_t(width)*height;
    if(p010PlanesToImage(env,image,pixels.data(),chroma,width,height)!=0 && !env->ExceptionCheck()) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Encoder did not expose writable P010 planes");
    }
}

extern "C" int32_t crs_rapidraw_upload_bayer(void*,const uint16_t*,size_t,const int32_t*,const float*,float,const float*,const float*,const float*,double*);
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeUploadBayer(JNIEnv* env,jclass,jlong handle,jobject hardware,
    jint width,jint height,jintArray channelArray,jfloatArray levelArray,jfloat white,jfloatArray matrixA,jfloatArray matrixD65,jfloatArray cameraNeutral) {
    if(width<8||height<8||width%2||height%2||env->GetArrayLength(channelArray)!=4||env->GetArrayLength(levelArray)!=4||
        env->GetArrayLength(matrixA)!=9||env->GetArrayLength(matrixD65)!=9||env->GetArrayLength(cameraNeutral)!=3) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"Invalid MHC frame layout/calibration");return nullptr;
    }
    auto* buffer=AHardwareBuffer_fromHardwareBuffer(env,hardware);
    AHardwareBuffer_Desc desc{};if(buffer)AHardwareBuffer_describe(buffer,&desc);
    if(!buffer||desc.format!=AHARDWAREBUFFER_FORMAT_BLOB||uint64_t(desc.width)*desc.height<uint64_t(width)*height*2) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),"MHC requires a packed Bayer blob");return nullptr;
    }
    int32_t channels[4];float levels[4],a[9],d65[9],neutral[3];
    env->GetIntArrayRegion(channelArray,0,4,channels);env->GetFloatArrayRegion(levelArray,0,4,levels);
    env->GetFloatArrayRegion(matrixA,0,9,a);env->GetFloatArrayRegion(matrixD65,0,9,d65);env->GetFloatArrayRegion(cameraNeutral,0,3,neutral);
    void* data=nullptr;
    if(AHardwareBuffer_lock(buffer,AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,-1,nullptr,&data)!=0) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"Cannot read packed Bayer frame");return nullptr;
    }
    double wb[2]{};
    const int code=crs_rapidraw_upload_bayer(reinterpret_cast<void*>(handle),static_cast<uint16_t*>(data),size_t(width)*height,channels,levels,white,a,d65,neutral,wb);
    AHardwareBuffer_unlock(buffer,nullptr);
    if(code!=0){renderError(env);return nullptr;}
    auto result=env->NewDoubleArray(2);if(result)env->SetDoubleArrayRegion(result,0,2,wb);return result;
}

extern "C" int32_t crs_rapidraw_configure(void*,const uint8_t*,size_t,float,uint32_t,uint32_t*);
extern "C" JNIEXPORT jintArray JNICALL
Java_com_matthew_crs_rapidraw_RapidComputeSession_nativeConfigure(JNIEnv* env,jclass,jlong handle,jbyteArray json,jfloat scale,jboolean raw) {
    const auto length=env->GetArrayLength(json);std::vector<uint8_t> text(size_t(length),0);
    env->GetByteArrayRegion(json,0,length,reinterpret_cast<jbyte*>(text.data()));
    uint32_t dims[2]{};
    if(crs_rapidraw_configure(reinterpret_cast<void*>(handle),text.data(),text.size(),scale,raw,dims)!=0){renderError(env);return nullptr;}
    auto result=env->NewIntArray(2);if(result) {const jint values[2]={jint(dims[0]),jint(dims[1])};env->SetIntArrayRegion(result,0,2,values);}return result;
}
