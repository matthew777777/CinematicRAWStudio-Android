cmake_minimum_required(VERSION 3.22.1)
project(crs LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include(${CMAKE_CURRENT_SOURCE_DIR}/rapidraw_host.cmake)
set(CRAW ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/craw)
add_library(crs SHARED
    clip_jni.cpp
    craw_compact.cpp
    craw_compact_jni.cpp
    ${CRAW}/encoder/src/Encoder.cpp
    ${CRAW}/encoder/src/Writer.cpp
    ${CRAW}/decoder/src/Decoder.cpp
    ${CRAW}/decoder/src/Reader.cpp
    ${CRAW}/decoder/tools/common/Clip.cpp)
target_include_directories(crs PRIVATE ${CMAKE_CURRENT_BINARY_DIR} ${CRAW}/encoder/include ${CRAW}/decoder/include ${CRAW}/decoder/tools ${CRAW}/decoder/src )
# arm64: armv8-a+crc for hardware CRC32C (frame CRCs are verified on every
# decode). armeabi-v7a builds the portable code paths (as RawLens ships both).
target_compile_options(crs PRIVATE -O3 -fno-finite-math-only)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(crs PRIVATE -march=armv8-a+crc)
endif()
target_link_libraries(crs PRIVATE jnigraphics android log vulkan)
