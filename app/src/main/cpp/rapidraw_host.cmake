# Independent pinned Rust host core. Install Rust targets aarch64-linux-android
# and armv7-linux-androideabi. CARGO_HOME/RUSTUP_HOME may point to an isolated setup.
find_program(RAPIDRAW_CARGO cargo HINTS "$ENV{CARGO_HOME}/bin" "$ENV{HOME}/.cargo/bin" REQUIRED)
set(RAPIDRAW_RUST ${CMAKE_CURRENT_SOURCE_DIR}/../rust/rapidraw-core)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(RAPIDRAW_RUST_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(RAPIDRAW_RUST_TARGET armv7-linux-androideabi)
else()
    message(FATAL_ERROR "RapidRAW Rust ABI not configured: ${ANDROID_ABI}")
endif()
set(RAPIDRAW_RUST_LIB ${CMAKE_CURRENT_BINARY_DIR}/rapidraw-rust/${RAPIDRAW_RUST_TARGET}/release/libcrs_rapidraw_core.a)
file(GLOB_RECURSE RAPIDRAW_RUST_SOURCES CONFIGURE_DEPENDS ${RAPIDRAW_RUST}/src/*.rs ${RAPIDRAW_RUST}/src/*.wgsl ${CMAKE_CURRENT_SOURCE_DIR}/../assets/mhc/*.spv)
add_custom_command(OUTPUT ${RAPIDRAW_RUST_LIB}
    COMMAND ${RAPIDRAW_CARGO} build --locked --release --features gpu --lib --target ${RAPIDRAW_RUST_TARGET}
        --manifest-path ${RAPIDRAW_RUST}/Cargo.toml --target-dir ${CMAKE_CURRENT_BINARY_DIR}/rapidraw-rust
    DEPENDS ${RAPIDRAW_RUST_SOURCES} ${RAPIDRAW_RUST}/Cargo.toml ${RAPIDRAW_RUST}/Cargo.lock
    VERBATIM)
add_custom_target(rapidraw_rust_build DEPENDS ${RAPIDRAW_RUST_LIB})
add_library(rapidraw_rust STATIC IMPORTED)
set_target_properties(rapidraw_rust PROPERTIES IMPORTED_LOCATION ${RAPIDRAW_RUST_LIB})
add_dependencies(rapidraw_rust rapidraw_rust_build)
add_library(rapidrawhost SHARED rapidraw_host_jni.cpp)
target_link_libraries(rapidrawhost PRIVATE rapidraw_rust log android dl m jnigraphics)
target_link_options(rapidrawhost PRIVATE "-Wl,-z,max-page-size=16384")
