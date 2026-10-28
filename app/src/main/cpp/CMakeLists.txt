cmake_minimum_required(VERSION 3.22.1)
project(crs LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CRAW ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/craw)
add_library(crs SHARED
    clip_jni.cpp
    ${CRAW}/encoder/src/Encoder.cpp
    ${CRAW}/encoder/src/Writer.cpp
    ${CRAW}/decoder/src/Decoder.cpp
    ${CRAW}/decoder/src/Reader.cpp
    ${CRAW}/decoder/tools/common/Clip.cpp
    ${CRAW}/develop/Develop.cpp)
target_include_directories(crs PRIVATE ${CRAW}/encoder/include ${CRAW}/decoder/include ${CRAW}/decoder/tools ${CRAW}/develop)
# armv8-a+crc: hardware CRC32C (frame CRCs are verified on every decode).
target_compile_options(crs PRIVATE -O3 -fno-finite-math-only -march=armv8-a+crc)
target_link_libraries(crs PRIVATE jnigraphics android log)

# RawLens Vulkan Direct-Log pipeline, vendored unchanged (third_party/rawlens-gpu).
set(RLGPU ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rawlens-gpu/cpp)
add_library(rawLensVfEgl SHARED ${RLGPU}/vf_egl_jni.cpp ${RLGPU}/vf_vulkan_vf.cpp ${RLGPU}/vf_cpu_neon.cpp
    ${RLGPU}/vulkan_pipeline_cache.cpp ${RLGPU}/bgu_slice_cpu.cpp)
target_include_directories(rawLensVfEgl PRIVATE ${RLGPU})
target_compile_options(rawLensVfEgl PRIVATE -O3)
target_link_libraries(rawLensVfEgl PRIVATE EGL GLESv2 android log vulkan)
target_link_options(rawLensVfEgl PRIVATE "-Wl,-z,max-page-size=16384")
