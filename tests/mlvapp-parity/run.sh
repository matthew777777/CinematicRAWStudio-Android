#!/usr/bin/env bash
# MLV-App processing parity: builds the vendored MLV-App processing for the
# host (reference, MLV-App's release flags) and for Android arm64, runs each
# stage alone and all together on a connected phone (1 and 8 threads) and
# compares outputs sample by sample.
#   tests/mlvapp-parity/run.sh   (needs adb and frame.rgb16, 2040x1020 RGB16)
set -euo pipefail
cd "$(dirname "$0")/../.."
M=third_party/mlv-app/src
B=tests/mlvapp-parity/build
NDK=${NDK:-$HOME/Library/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
SRC=($M/processing/raw_processing.c $M/processing/blur_threaded.c $M/processing/cube_lut.c
     $M/processing/denoiser/denoiser_2d_median.c $M/processing/sobel/sobel.c
     $M/processing/cafilter/ColorAberrationCorrection.c $M/processing/interpolation/cosine_interpolation.c
     $M/processing/tinyexpr/tinyexpr.c $M/processing/filter/filter.c $M/processing/filter/genann/genann.c
     $M/matrix/matrix.c tests/mlvapp-parity/parity.c)
CXX=($M/processing/rbfilter/rbf_wrapper.cpp $M/processing/rbfilter/RBFilterPlain.cpp $M/processing/interpolation/spline_helper.cpp)
FLAGS=(-O3 -ftree-vectorize -fno-math-errno -w -I$M/processing)
rm -rf "$B"; mkdir -p "$B/host" "$B/android"
for f in "${SRC[@]}"; do
    clang -c "${FLAGS[@]}" "$f" -o "$B/host/$(basename "$f").o"
    "$NDK/aarch64-linux-android30-clang" -c "${FLAGS[@]}" -fopenmp "$f" -o "$B/android/$(basename "$f").o"
done
for f in "${CXX[@]}"; do
    clang++ -c "${FLAGS[@]}" "$f" -o "$B/host/$(basename "$f").o"
    "$NDK/aarch64-linux-android30-clang++" -c "${FLAGS[@]}" "$f" -o "$B/android/$(basename "$f").o"
done
clang++ "$B"/host/*.o -o "$B/parity_host"
"$NDK/aarch64-linux-android30-clang++" "$B"/android/*.o -fopenmp -static-openmp -static-libstdc++ -o "$B/parity_android"
"$ADB" push "$B/parity_android" tests/mlvapp-parity/frame.rgb16 /data/local/tmp/ >/dev/null 2>&1
"$ADB" shell chmod +x /data/local/tmp/parity_android
for st in none expo wb contrast clarity shadhigh sat vib toning gcurve huevs sharpen rbf ca vignette all; do
    "$B/parity_host" tests/mlvapp-parity/frame.rgb16 2040 1020 "$B/h_$st.rgb16" 1 "$st" >/dev/null
    for t in 1 8; do
        "$ADB" shell "cd /data/local/tmp && ./parity_android frame.rgb16 2040 1020 a.rgb16 $t $st" >/dev/null
        "$ADB" pull /data/local/tmp/a.rgb16 "$B/a_${st}_$t.rgb16" >/dev/null 2>&1
    done
done
/usr/bin/python3 - "$B" <<'PY'
import sys, numpy as np
B = sys.argv[1]
stages = "none expo wb contrast clarity shadhigh sat vib toning gcurve huevs sharpen rbf ca vignette all".split()
# Allowed difference (of 65535): stages built on MLV-App's recursive bilateral
# filter differ in the last bits between libm implementations (Apple vs
# bionic); everything else must match exactly. Threads must never matter.
LIMIT = {"clarity": 16, "shadhigh": 16, "rbf": 16, "all": 64}
failed = False
print(f"{'stage':9s} {'exact':6s} {'max diff':>8s} {'limit':>5s} {'differing':>9s}  threads 1 == 8")
for st in stages:
    h = np.fromfile(f"{B}/h_{st}.rgb16", "<u2").astype(np.int32)
    a1 = np.fromfile(f"{B}/a_{st}_1.rgb16", "<u2").astype(np.int32)
    a8 = np.fromfile(f"{B}/a_{st}_8.rgb16", "<u2").astype(np.int32)
    d = np.abs(a1 - h); same_threads = np.array_equal(a1, a8)
    ok = d.max() <= LIMIT.get(st, 0) and same_threads
    failed |= not ok
    print(f"{st:9s} {'yes' if d.max() == 0 else 'no':6s} {d.max():8d} {LIMIT.get(st, 0):5d} {100*(d>0).mean():8.3f}%  {'yes' if same_threads else 'NO'}{'' if ok else '  FAIL'}")
print("PARITY FAILED" if failed else "parity ok")
sys.exit(1 if failed else 0)
PY
