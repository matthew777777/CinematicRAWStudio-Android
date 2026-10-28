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

add_library(crs SHARED
    clip_jni.cpp
    grade_jni.cpp
    raw_tools.cpp
    ${CRAW}/encoder/src/Encoder.cpp
    ${CRAW}/encoder/src/Writer.cpp
    ${CRAW}/decoder/src/Decoder.cpp
    ${CRAW}/decoder/src/Reader.cpp
    ${CRAW}/decoder/tools/common/Clip.cpp
    ${CRAW}/develop/Develop.cpp)
target_include_directories(crs PRIVATE ${CRAW}/encoder/include ${CRAW}/decoder/include ${CRAW}/decoder/tools ${CRAW}/develop)
# armv8-a+crc: hardware CRC32C (frame CRCs are verified on every decode).
target_compile_options(crs PRIVATE -O3 -fno-finite-math-only -march=armv8-a+crc)
target_link_libraries(crs PRIVATE mlvprocessing jnigraphics android log)

# RawLens Vulkan Direct-Log pipeline, vendored unchanged (third_party/rawlens-gpu).
set(RLGPU ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rawlens-gpu/cpp)
add_library(rawLensVfEgl SHARED ${RLGPU}/vf_egl_jni.cpp ${RLGPU}/vf_vulkan_vf.cpp ${RLGPU}/vf_cpu_neon.cpp
    ${RLGPU}/vulkan_pipeline_cache.cpp ${RLGPU}/bgu_slice_cpu.cpp)
target_include_directories(rawLensVfEgl PRIVATE ${RLGPU})
target_compile_options(rawLensVfEgl PRIVATE -O3)
target_link_libraries(rawLensVfEgl PRIVATE EGL GLESv2 android log vulkan)
target_link_options(rawLensVfEgl PRIVATE "-Wl,-z,max-page-size=16384")
