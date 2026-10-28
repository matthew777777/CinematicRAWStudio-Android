# Cinematic RAW Studio — Android

Native Android app (Kotlin, Jetpack Compose, C++) to view, edit and export
**.CRAW** and **CinemaDNG** raw video: gallery, player with sound, editor,
a project timeline with linked camera audio and additional audio tracks,
hardware H.264/HEVC SDR export (including HEVC Main10), and a persistent export queue. Original
CinemaDNG conversion is available separately from the library.

Licence: GPL-3.0-or-later (see LICENSE). Vendored CRAW decoder, Clip helper and
developer (`third_party/craw`) are Apache-2.0, synced by `tools/sync_craw.sh`.

## Roadmap

| phase | scope | status |
|---|---|---|
| 1 | Gallery (folder or single file), player with audio as master clock, frame step, scrubbing, exposure | done |
| 2 | RawLens Vulkan pipeline (Packed RCD + grade, vendored unchanged); ½-res playback proxy with full-res pause; decode-ahead workers; precise audio clock; instant gallery (single-query listing, disk thumbnail cache); large takes open in < 3 s | done: 30 fps (CRAW-Live v2), 16–18 fps (CRAW-Fast) on MT6878 |
| 3 | Editor on MLV-App's own processing (vendored, parity-tested: docs/mlv-app-parity.md): light, colour, curves, detail, profile, toning, lens; MLV-App raw tools (vertical stripes, bad pixels, pattern noise, chroma smoothing); per-take receipts | done (paused frames graded by MLV-App; playback uses the GPU grade) |
| 4 | Project timeline: multiple takes, linked camera audio, extra audio tracks, trim, split, frame-quantized movement, save/reopen | first milestone implemented; transitions/compositing/waveforms remain |
| 5 | Export: edited H.264 / HEVC 8-bit and HEVC Main10 10-bit SDR MP4, mixed AAC audio, persistent foreground queue; original CinemaDNG + WAV | first milestone implemented; HDR/intermediate codecs remain |
| 6 | CinemaDNG sequences as sources | |

## References (open source, human-written)

- MLV-App (GPL-3.0): raw video processing and export workflow — https://github.com/ilia3101/MLV-App
- LibRaw / dcraw: demosaic and colour reference used to validate output
- Android MediaCodec documentation and AOSP CTS encoder tests

See [Projects and export](docs/projects-and-export.md) for behavior, limits, and validation.
