// SPDX-License-Identifier: Apache-2.0
#include "craw_compact.h"
#include <jni.h>
#include <stdexcept>
#include <thread>
#include <algorithm>
namespace {
void error(JNIEnv* env,const std::exception&e){env->ThrowNew(env->FindClass("java/io/IOException"),e.what());}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_matthew_crs_project_CompactNative_nativeStart(JNIEnv* env,jclass,jint source,jint output,jboolean checkOnly){
    try{const unsigned cores=std::thread::hardware_concurrency();const unsigned threads=std::max(1u,cores>1?cores-1:1);
        auto session=checkOnly?crs::compact::verify(source,output,threads):crs::compact::convert(source,output,threads);
        return reinterpret_cast<jlong>(session.release());
    }catch(const std::exception&e){error(env,e);return 0;}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_matthew_crs_project_CompactNative_nativeStep(JNIEnv* env,jclass,jlong handle){
    try{if(!handle)throw std::runtime_error("Compact session is closed");
        return env->NewStringUTF(crs::compact::json(reinterpret_cast<crs::compact::Session*>(handle)->step()).c_str());
    }catch(const std::exception&e){error(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_com_matthew_crs_project_CompactNative_nativeClose(JNIEnv*,jclass,jlong handle){delete reinterpret_cast<crs::compact::Session*>(handle);}
