cmake_minimum_required(VERSION 3.22.1)
project(crs LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CRAW ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/craw)
add_library(crs SHARED
    clip_jni.cpp
    ${CRAW}/decoder/src/Decoder.cpp
    ${CRAW}/decoder/src/Reader.cpp
    ${CRAW}/decoder/tools/common/Clip.cpp
    ${CRAW}/develop/Develop.cpp)
target_include_directories(crs PRIVATE ${CRAW}/decoder/include ${CRAW}/decoder/tools ${CRAW}/develop)
target_compile_options(crs PRIVATE -O3 -fno-finite-math-only)
target_link_libraries(crs PRIVATE jnigraphics log)
