#!/usr/bin/env bash
# Vendors MLV-App's image processing (GPL-3.0) unchanged from a pinned
# commit: grading, white balance, curves, profiles, LUTs, filters, denoise,
# CA correction and sharpening (src/processing + src/matrix).
#   tools/sync_mlvapp.sh [mlv-app-repo] [commit]
set -euo pipefail
cd "$(dirname "$0")/.."
SRC="${1:-$HOME/src/MLV-App}"
REV="$(git -C "$SRC" rev-parse "${2:-HEAD}")"
DST=third_party/mlv-app
rm -rf "$DST"
mkdir -p "$DST"
git -C "$SRC" archive "$REV" src/processing src/matrix LICENSE | tar -x -C "$DST"
# processing includes MLV container type headers (src/mlv): headers only.
git -C "$SRC" archive "$REV" src/mlv src/debayer | tar -x -C "$DST"
find "$DST/src/mlv" "$DST/src/debayer" -type f ! -name '*.h' -delete
# Fixes for bugs found by the parity tests (each patch documents the bug).
for p in tools/patches/mlv-app/*.patch; do
    [ -e "$p" ] || continue
    patch -s -p1 -d "$DST" < "$p"
    echo "applied $(basename "$p")"
done
{
    echo "# MLV-App image processing — vendored"
    echo
    echo "GPL-3.0 (see LICENSE). Unchanged copy of src/processing and src/matrix"
    echo "(plus the header files of src/mlv and src/debayer they include)"
    echo "from https://github.com/ilia3101/MLV-App commit $REV,"
    echo "synced by tools/sync_mlvapp.sh; do not edit here. Patches applied"
    echo "on top (bug fixes found by the parity tests): tools/patches/mlv-app/."
} > "$DST/SOURCE.md"
echo "synced MLV-App $REV"
