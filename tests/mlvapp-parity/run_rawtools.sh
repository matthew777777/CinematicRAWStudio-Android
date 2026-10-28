#!/usr/bin/env bash
# MLV-App raw-stage tools parity: the app's RawTools adapter (vendored
# llrawproc) on a real CRAW frame, host vs Android (1 and 8 threads).
#   tests/mlvapp-parity/run_rawtools.sh take.craw   (frame 0, adb phone)
set -euo pipefail
cd "$(dirname "$0")/../.."
TAKE="$1"
M=third_party/mlv-app/src
B=tests/mlvapp-parity/build/rt
NDK=${NDK:-$HOME/Library/Android/sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin}
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
DEC=${DEC:-$HOME/CinematicRAWStudio-Decoder/build/craw_dec}
rm -rf "$B"; mkdir -p "$B/host" "$B/android"
for f in $M/mlv/llrawproc/stripes.c $M/mlv/llrawproc/patternnoise.c $M/mlv/llrawproc/pixelproc.c; do
    # Host reference: MLV-App's portable path (DISO_SINGLE_THREAD), not its
    # Apple-only GCD chroma path, which reads uninitialised scratch memory.
    clang -c -O3 -ftree-vectorize -fno-math-errno -w -DDISO_SINGLE_THREAD -I$M/mlv "$f" -o "$B/host/$(basename "$f").o"
    "$NDK/aarch64-linux-android30-clang" -c -O3 -ftree-vectorize -fno-math-errno -w -fopenmp -I$M/mlv "$f" -o "$B/android/$(basename "$f").o"
done
for f in app/src/main/cpp/raw_tools.cpp tests/mlvapp-parity/rawtools.cpp; do
    clang++ -c -std=c++20 -O2 -I$M/mlv -Iapp/src/main/cpp "$f" -o "$B/host/$(basename "$f").o"
    "$NDK/aarch64-linux-android30-clang++" -c -std=c++20 -O2 -I$M/mlv -Iapp/src/main/cpp "$f" -o "$B/android/$(basename "$f").o"
done
clang++ "$B"/host/*.o -o "$B/rt_host"
"$NDK/aarch64-linux-android30-clang++" "$B"/android/*.o -fopenmp -static-openmp -static-libstdc++ -o "$B/rt_android"
"$DEC" frames "$TAKE" "$B" 0-0 >/dev/null
read -r W H CFA < <(/usr/bin/python3 -c "
import json,subprocess,sys,re
out=subprocess.run(['$DEC','info','$TAKE'],capture_output=True,text=True).stdout
m=json.loads(out.split('meta: ',1)[1].split('\n',1)[0]); arr=m.get('sensorArrangment','rggb').lower()
import os; n=os.path.getsize('$B/frame_000000.raw')//2; w=m.get('width'); print(w, n//w, ['rggb','grbg','gbrg','bggr'].index(arr))")
mkdir -p "$B/out_host"
"$B/rt_host" "$B/frame_000000.raw" "$W" "$H" "$CFA" "$B/out_host" | grep -E "fnv=|unchanged=" > "$B/h.txt"
"$ADB" push "$B/rt_android" "$B/frame_000000.raw" /data/local/tmp/ >/dev/null 2>&1
for t in 1 8; do
    "$ADB" shell "rm -rf /data/local/tmp/rt_out && mkdir -p /data/local/tmp/rt_out && cd /data/local/tmp && chmod +x rt_android && OMP_NUM_THREADS=$t ./rt_android frame_000000.raw $W $H $CFA rt_out" | grep -E "fnv=|unchanged=" > "$B/a$t.txt"
    rm -rf "$B/out_a$t"; "$ADB" pull /data/local/tmp/rt_out "$B/out_a$t" >/dev/null 2>&1
done
# Gating: each tool alone matches the host exactly; Android is
# deterministic for every case (1 vs 8 threads, and a second run).
# Reported only: host vs Android for combinations after vertical stripes -
# the host reference itself varies between runs there (open issue in
# MLV-App's combined tools; see docs/mlv-app-parity.md).
"$ADB" shell "rm -rf /data/local/tmp/rt_out2 && mkdir -p /data/local/tmp/rt_out2 && cd /data/local/tmp && OMP_NUM_THREADS=8 ./rt_android frame_000000.raw $W $H $CFA rt_out2" >/dev/null
rm -rf "$B/out_a8b"; "$ADB" pull /data/local/tmp/rt_out2 "$B/out_a8b" >/dev/null 2>&1
/usr/bin/python3 - "$B" <<'PY'
import sys, numpy as np
B = sys.argv[1]
bad = False
rd = lambda d, n: np.fromfile(f"{B}/{d}/{n}.raw", "<u2").astype(np.int32)
print(f"{'case':14s} {'host-android':>12s} {'android 1v8':>11s} {'android rerun':>13s}")
for line in open(f"{B}/h.txt"):
    name = line.split()[0]
    if name.startswith("mlvapp"):
        ok = all("unchanged=yes" in open(f"{B}/{f}").read() for f in ("h.txt", "a1.txt", "a8.txt"))
        print(f"MLV-App '2x2' (chroma_smooth method 1) is a no-op on host and Android: {'yes' if ok else 'NO'}")
        bad |= not ok
        continue
    h, a1, a8, b8 = rd("out_host", name), rd("out_a1", name), rd("out_a8", name), rd("out_a8b", name)
    hd, td, rr = int(np.abs(h - a1).max()), int(np.abs(a1 - a8).max()), int(np.abs(a8 - b8).max())
    combo_after_stripes = name.startswith("stripes+") or name == "all"
    ok = td == 0 and rr == 0 and (combo_after_stripes or hd == 0)
    bad |= not ok
    note = " (reported)" if combo_after_stripes else ""
    print(f"{name:14s} {hd:12d}{note:11s} {td:11d} {rr:13d}  {'ok' if ok else 'FAIL'}")
print("RAW TOOLS PARITY FAILED" if bad else "raw tools parity ok")
sys.exit(1 if bad else 0)
PY
