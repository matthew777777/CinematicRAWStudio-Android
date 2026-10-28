cmake_minimum_required(VERSION 3.22.1)
project(crs LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CRAW ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/craw)
# MLV-App image processing, vendored unchanged (third_party/mlv-app), built
# with MLV-App's release flags; OpenMP as in MLV-App's own builds.
set(MLV ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/mlv-app/src)
add_library(mlvprocessing STATIC
    ${MLV}/processing/raw_processing.c ${MLV}/processing/blur_threaded.c ${MLV}/processing/cube_lut.c
    ${MLV}/processing/denoiser/denoiser_2d_median.c ${MLV}/processing/sobel/sobel.c
    ${MLV}/processing/cafilter/ColorAberrationCorrection.c ${MLV}/processing/interpolation/cosine_interpolation.c
    ${MLV}/processing/tinyexpr/tinyexpr.c ${MLV}/processing/filter/filter.c ${MLV}/processing/filter/genann/genann.c
    ${MLV}/matrix/matrix.c ${MLV}/processing/rbfilter/rbf_wrapper.cpp ${MLV}/processing/rbfilter/RBFilterPlain.cpp
    ${MLV}/processing/interpolation/spline_helper.cpp
    ${MLV}/mlv/llrawproc/stripes.c ${MLV}/mlv/llrawproc/patternnoise.c ${MLV}/mlv/llrawproc/pixelproc.c)
target_include_directories(mlvprocessing PUBLIC ${MLV}/processing ${MLV}/mlv)
target_compile_options(mlvprocessing PRIVATE -O3 -ftree-vectorize -fno-math-errno -w -fopenmp)
target_link_options(mlvprocessing PUBLIC -fopenmp -static-openmp)

# MLV-App grade on Vulkan (src/main/shaders), embedded as SPIR-V words.
find_program(GLSLC glslc HINTS ${ANDROID_NDK}/shader-tools/${ANDROID_HOST_TAG} ${ANDROID_NDK}/shader-tools/darwin-x86_64
    ${ANDROID_NDK}/shader-tools/linux-x86_64 ${ANDROID_NDK}/shader-tools/windows-x86_64 REQUIRED)
foreach(SHADER mlv_grade mlv_output)
    add_custom_command(OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/${SHADER}.spv.inc
        COMMAND ${GLSLC} --target-env=vulkan1.1 -O -fshader-stage=compute -mfmt=num
            -o ${CMAKE_CURRENT_BINARY_DIR}/${SHADER}.spv.inc ${CMAKE_CURRENT_SOURCE_DIR}/../shaders/${SHADER}.comp
        DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../shaders/${SHADER}.comp VERBATIM)
endforeach()

add_library(crs SHARED
    clip_jni.cpp
    grade_jni.cpp
    mlv_gpu.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/mlv_grade.spv.inc
    ${CMAKE_CURRENT_BINARY_DIR}/mlv_output.spv.inc
    raw_tools.cpp
    ${CRAW}/encoder/src/Encoder.cpp
    ${CRAW}/encoder/src/Writer.cpp
    ${CRAW}/decoder/src/Decoder.cpp
    ${CRAW}/decoder/src/Reader.cpp
    ${CRAW}/decoder/tools/common/Clip.cpp
    ${CRAW}/develop/Develop.cpp)
target_include_directories(crs PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CRAW}/encoder/include ${CRAW}/decoder/include ${CRAW}/decoder/tools ${CRAW}/develop)
# arm64: armv8-a+crc for hardware CRC32C (frame CRCs are verified on every
# decode). armeabi-v7a builds the portable code paths (as RawLens ships both).
target_compile_options(crs PRIVATE -O3 -fno-finite-math-only)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(crs PRIVATE -march=armv8-a+crc)
endif()
target_link_libraries(crs PRIVATE mlvprocessing jnigraphics android log vulkan)

# RawLens Vulkan Direct-Log pipeline, vendored unchanged (third_party/rawlens-gpu).
set(RLGPU ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rawlens-gpu/cpp)
add_library(rawLensVfEgl SHARED ${RLGPU}/vf_egl_jni.cpp ${RLGPU}/vf_vulkan_vf.cpp ${RLGPU}/vf_cpu_neon.cpp
    ${RLGPU}/vulkan_pipeline_cache.cpp ${RLGPU}/bgu_slice_cpu.cpp)
target_include_directories(rawLensVfEgl PRIVATE ${RLGPU})
target_compile_options(rawLensVfEgl PRIVATE -O3)
target_link_libraries(rawLensVfEgl PRIVATE EGL GLESv2 android log vulkan)
target_link_options(rawLensVfEgl PRIVATE "-Wl,-z,max-page-size=16384")
