# Cinematic RAW Studio — Android

Native Android app (Kotlin, Jetpack Compose, C++) to view, edit and export
**.CRAW** and **CinemaDNG** raw video: gallery, player with sound, editor,
multi-track timeline, hardware (MediaCodec) export with encoder probing and an
export queue.

Licence: GPL-3.0-or-later (see LICENSE). Vendored CRAW decoder, Clip helper and
developer (`third_party/craw`) are Apache-2.0, synced by `tools/sync_craw.sh`.

## Roadmap

| phase | scope | status |
|---|---|---|
| 1 | Gallery (folder or single file), player with audio as master clock, frame step, scrubbing, exposure | done |
| 2 | RawLens Vulkan pipeline (Packed RCD + grade, vendored unchanged); ½-res playback proxy with full-res pause; decode-ahead workers; precise audio clock; instant gallery (single-query listing, disk thumbnail cache); large takes open in < 3 s | done: 30 fps (CRAW-Live v2), 16–18 fps (CRAW-Fast) on MT6878 |
| 3 | Editor: white balance, exposure, curves, colour wheels, LUTs, gamut/log output; MLV-App raw tools (chroma smoothing, pattern noise, hot pixels, deflicker, dark frame, CA) | |
| 4 | Multi-track timeline: video/audio tracks, trim, split, snap, waveforms, projects | |
| 5 | Export: MediaCodec H.264 / HEVC 8- and 10-bit MP4 with encoder probing and software fallback; export queue; CinemaDNG | |
| 6 | CinemaDNG sequences as sources | |

## References (open source, human-written)

- MLV-App (GPL-3.0): raw video processing and export workflow — https://github.com/ilia3101/MLV-App
- LibRaw / dcraw: demosaic and colour reference used to validate output
- Android MediaCodec documentation and AOSP CTS encoder tests
