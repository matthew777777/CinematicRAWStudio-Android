#!/usr/bin/env bash
# Vendors the Cinematic RAW Studio (.CRAW) decoder, its Clip/DNG/WAV helper
# and the TUI's developer (all Apache-2.0) into third_party/craw.
#   tools/sync_craw.sh [decoder-repo] [tui-repo]
set -euo pipefail
cd "$(dirname "$0")/.."
DEC="${1:-$HOME/CinematicRAWStudio-Decoder}"
TUI="${2:-$HOME/CinematicRAWStudio-TUI}"
DST=third_party/craw
rm -rf "$DST"
mkdir -p "$DST/decoder/tools/common" "$DST/develop"
cp -R "$DEC/include" "$DEC/src" "$DEC/LICENSE" "$DEC/NOTICE" "$DST/decoder/"
cp "$DEC"/tools/common/Clip.cpp "$DEC"/tools/common/Clip.h "$DEC"/tools/common/Json.h "$DST/decoder/tools/common/"
cp "$TUI/src/Develop.cpp" "$TUI/src/Develop.h" "$TUI/LICENSE" "$DST/develop/"
{
    echo "# Cinematic RAW Studio (.CRAW) — vendored sources"
    echo
    echo "Apache-2.0. Synced by tools/sync_craw.sh; do not edit here."
    echo
    echo "| part | repo | commit |"
    echo "|------|------|--------|"
    echo "| decoder + tools/common | CinematicRAWStudio-Decoder | $(git -C "$DEC" rev-parse HEAD) |"
    echo "| develop | CinematicRAWStudio-TUI | $(git -C "$TUI" rev-parse HEAD) |"
} > "$DST/SOURCE.md"
echo "synced into $DST"
