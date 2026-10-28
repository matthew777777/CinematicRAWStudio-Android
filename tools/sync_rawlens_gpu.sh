#!/usr/bin/env bash
# Vendors RawLens's Vulkan Direct-Log pipeline (Packed RCD demosaic, fused
# grade to P010, P010 -> RGBA8) byte-for-byte from a pinned RawLens commit:
# native module, compiled shaders and the Kotlin JNI/parameter classes
# (kept in package com.matthew.rawlens so the JNI symbol names match).
#   tools/sync_rawlens_gpu.sh [rawlens-repo] [commit]
set -euo pipefail
cd "$(dirname "$0")/.."
RL="${1:-$HOME/AndroidStudioProjects/RawLens}"
REV="$(git -C "$RL" rev-parse "${2:-HEAD}")"
DST=third_party/rawlens-gpu
rm -rf "$DST" app/src/main/assets/shaders app/src/main/java/com/matthew/rawlens
mkdir -p "$DST/cpp" app/src/main/assets/shaders/vf app/src/main/assets/shaders/rcd_packed app/src/main/java/com/matthew/rawlens
show() { git -C "$RL" show "$REV:$1" > "$2"; }
CPP=app/src/main/cpp
for f in vf_egl_jni.cpp vf_vulkan_vf.cpp vf_cpu_neon.cpp vulkan_pipeline_cache.cpp vulkan_pipeline_cache.h bgu_slice_cpu.cpp bgu_jni_shape.h; do
    show "$CPP/$f" "$DST/cpp/$f"
done
for f in $(git -C "$RL" ls-tree --name-only "$REV" "$CPP/" | grep '/vf_vulkan_vf_.*\.inc$'); do show "$f" "$DST/cpp/$(basename "$f")"; done
for f in $(git -C "$RL" ls-tree --name-only "$REV" app/src/main/assets/shaders/vf/ | grep '\.spv$'); do
    show "$f" "app/src/main/assets/shaders/vf/$(basename "$f")"; done
for f in $(git -C "$RL" ls-tree --name-only "$REV" app/src/main/assets/shaders/rcd_packed/ | grep '\.spv$'); do
    show "$f" "app/src/main/assets/shaders/rcd_packed/$(basename "$f")"; done
KT=app/src/main/java/com/matthew/rawlens
for f in VfVulkan VfLevels VfPackedRcd VfRgb8Convert VfEglImport VfLogGrade DirectLogProfile VfRcd LogVideoProbe EglPusher; do
    show "$KT/$f.kt" "$KT/$f.kt"
done
show LICENSE "$DST/LICENSE"
{
    echo "# RawLens GPU pipeline — vendored"
    echo
    echo "GPL-3.0-or-later (RawLens). Synced by tools/sync_rawlens_gpu.sh from"
    echo "RawLens commit $REV; do not edit here. Packed RCD shaders: see RawLens"
    echo "third_party/rcd-packed-upstream (GPL-3.0)."
} > "$DST/SOURCE.md"
echo "synced RawLens $REV"
