#!/usr/bin/env bash
# Vendors the Cinematic RAW Studio (.CRAW) encoder, decoder, the decoder's
# Clip/DNG/WAV helper (Apache-2.0) into
# third_party/craw.
#   tools/sync_craw.sh [decoder-repo] [encoder-repo] (legacy three-argument form also accepted)
set -euo pipefail
cd "$(dirname "$0")/.."
DEC="${1:-$HOME/CinematicRAWStudio-Decoder}"
ENC="${3:-${2:-$HOME/CinematicRAWStudio-Encoder}}"
DST=third_party/craw
rm -rf "$DST"
mkdir -p "$DST/decoder/tools/common" "$DST/encoder"
cp -R "$ENC/include" "$ENC/src" "$ENC/LICENSE" "$ENC/NOTICE" "$DST/encoder/"
cp -R "$DEC/include" "$DEC/src" "$DEC/LICENSE" "$DEC/NOTICE" "$DST/decoder/"
cp "$DEC/tools/craw_compact.cpp" "$DST/decoder/tools/"
cp "$DEC"/tools/common/Clip.cpp "$DEC"/tools/common/Clip.h "$DEC"/tools/common/Json.h "$DST/decoder/tools/common/"
{
    echo "# Cinematic RAW Studio (.CRAW) — vendored sources"
    echo
    echo "Apache-2.0. Synced by tools/sync_craw.sh; do not edit here."
    echo
    echo "| part | repo | commit |"
    echo "|------|------|--------|"
    echo "| encoder | CinematicRAWStudio-Encoder | $(git -C "$ENC" rev-parse HEAD) |"
    echo "| decoder + tools/common | CinematicRAWStudio-Decoder | $(git -C "$DEC" rev-parse HEAD) |"
} > "$DST/SOURCE.md"
python3 tools/verify_craw_sources.py --record
echo "synced into $DST"
