# RapidRAW mobile Vulkan grading

The Android renderer now builds and caches guided textures on the first render that uses them. It checks the packed global and mask adjustments for clarity, structure, Centré, dehaze, shadows, blacks, highlights and whites. A fresh source frame has an empty cache. The generic shader checks the per-pixel adjustments, including when the specialized pipeline is still compiling.

On frames whose grades bypass the guided graph, this removes 30 dispatches, 30 bind groups, five parameter buffers and one queue submission. At 1920×1080 the 14 avoided RGBA32F texture allocations have 190.3 MiB of logical texel storage, including 41.5 MiB for the four filter outputs. These figures are derived from the texture descriptors. Device memory residency was outside this measurement.

## Offline compiler results

Naga 29.0.4 generated the RGBA8 and RGBA16F grading modules using the existing build recipe. `spirv-val --target-env vulkan1.1` passed. Qualcomm AOC 7.0.26 and Mali Offline Compiler 2026.5.0 passed all 69 jobs per build across A750, G615 and G720. They also passed all 90 jobs for the pinned upstream RapidRAW 1.7 grade, guided and blur reference shaders, and six jobs per packaged MHC scan. A740 was outside the test matrix. The configured AOC needed the explicit entry-point name for the blur, guided and flare modules.

| RGBA8 grader | A750 instructions | A750 texture-read instructions | A750 full-precision registers | Mali work registers / estimated occupancy | Mali stack spill bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| Basic before | 8,216 | 17 | 13 | 64 / 50% | 0 |
| Basic after | 7,723 | 2 | 13 | 32 / 100% | 0 |
| Tonal after | 8,288 | 18 | 16 | 64 / 50% | 0 |
| Generic before | 18,044 | 69 | 34 | 64 / 50% | 264 |
| Generic after | 18,109 | 69 | 34 | 64 / 50% | 268 |
| Upstream 1.7 generic | 20,940 | 74 | 35 | 64 / 50% | 280 |

Both Mali targets report load/store as the aggregate bottleneck in the grader and guided blur. On A750, AOC projects exposed long-latency waits in about 69% of generic grade cycles and estimates 25% ALU fiber occupancy. On the after basic specialization those values are about 34% and 43%. The full generic shader grows slightly, and the A750 basic specialization's register footprint is steady. Offline cycle projections describe the compiler model. They omit frame scheduling, cache behavior and thermal limits.

## Device parity

On Xiaomi 25080RABDG with Mali-G615 MC2, the before and after runs match all 350,208 components across 54 files, including 18 grades each for RGBA8, RGBA16F and P010. Fixtures cover neutral, tonal, detail, mask-only guided controls, curves, LUT, AgX and ACES 2. Each executable also verified measured CFA sample preservation in all four MHC phases. The standalone parity run hit a mutex teardown error after emitting its output files. The comparison used the completed files.

## Device timings

The same phone ran the 1920×1080 synthetic RGBA16F upload-to-RGBA8-readback benchmark in before, after, after, before order. Each run used eight warm-up frames and 30 measured frames per grade. All four timing processes exited successfully.

| Grade | First before / after p50 (ms) | Reverse-order before / after p50 (ms) | Pooled before / after p95 (ms) |
| --- | ---: | ---: | ---: |
| Neutral | 297.91 / 122.41 | 870.57 / 346.77 | 1,149.68 / 403.47 |
| Exposure + saturation | 271.28 / 134.48 | 273.38 / 396.79 | 359.45 / 509.65 |
| Highlights + shadows | 272.52 / 263.03 | 247.34 / 861.94 | 285.99 / 1,139.36 |
| Clarity + dehaze | 407.58 / 269.12 | 271.95 / 810.57 | 475.72 / 1,124.50 |

Neutral median latency was lower in both pairs. The other grades varied substantially between pairs, so this run does not establish a speedup for them. The lowest neutral median was 122 ms, above a 33.33 ms budget even before decode and presentation. The benchmark excludes those stages, audio and frame scheduling. The isolated app's playback benchmark required a configured Bayer source folder. Runtime timing on Adreno and G720 remains open.

## RapidRAW 1.7 migration

The mobile change applies to the in-app renderer pinned at `f1d71a69`. Upstream 1.7 at `00379017` uses a separate edge-aware tone-base texture and scene statistics stored in the third airlight texel. Contrast can need those statistics even with local tone controls neutral. The 1.7 port can use the same demand-driven approach, with scene statistics, tone-base and dehaze gated according to their respective consumers. Port its shader bindings and Rust uniform packing together and compare against 1.7 fixtures before switching the runtime pin. The generic results make the usual basic-grade specializations and the bandwidth cost of the guided passes the priorities for that port.

Raw evidence is saved under `build/shader-analysis` in `mobile-grading-before`, `mobile-grading-after`, `mobile-grading-reference-1.7`, `mobile-device-before`, `mobile-device-after`, `mobile-device-parity.json` and `mobile-device-timing-summary.json`. The rebuild and analysis entry points are documented in [the shader-analysis README](../../../tools/shader-analysis/README.md).
