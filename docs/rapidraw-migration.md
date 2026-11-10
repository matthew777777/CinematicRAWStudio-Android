> Historical checkpoint; superseded by the AI-free MHC/RapidRAW delivery in [rapidraw-engine.md](rapidraw-engine.md).

# RapidRAW processing migration

## Checkpoint 1: frozen reference

Reference: CyberTimon/RapidRAW commit
`f1d71a69eef813432db214f77969f567c41ff1d7`.
The selected Rust host files and all WGSL shaders are copied unchanged into
`third_party/rapidraw-reference`, with upstream AGPL-3.0 license and SHA-256
manifest. They are reference material only, not compiled or linked into the app.
Run `python3 tools/verify_rapidraw_reference.py` to check their integrity.
Licensing for distributed derived code must be resolved before enabling a port.

## Preserved pipeline

Performance target: native-resolution 30 fps playback with and without grading,
and 30 fps or faster full-quality export on the target phone/footage. Proxy
playback is permitted as an explicit mode and must be reported separately.
Export must not substitute playback proxies. This is a target, not a measured
capability or guarantee for every resolution, device, and effect combination.

The replacement may reuse Vulkan pipelines, persist device/driver-compatible
pipeline caches, pool images/buffers, and cache decoded/RCD/intermediate frames
within a memory budget. Cache keys must include source/frame identity, input
resolution, calibration/RAW corrections, engine version, and relevant upstream
parameters. Downstream edits should invalidate only affected stages. Pipeline
compilation caching improves startup; it does not eliminate per-frame processing.

Keep CRAW decoding, calibration, RCD, Filament AgX and analytical ACES
2.0 algorithms, timeline scheduling, audio, and codec validation. Replace the
entire current adjustment system, CPU and GPU, incrementally. Retaining these
explicitly requested algorithms does not authorize retaining the old editing
engine or its adapters; integrate them into the replacement engine.

The final repository and app must contain no MLV-App source, shaders, CPU
fallbacks, native bridges, processing tables, dependencies, old editing controls,
or old receipt implementation. This includes MLV-App-derived RAW corrections:
replace any required behavior independently or remove it. No hidden legacy
engine or compatibility execution path may remain. Remove obsolete MLV-App
tests, patches, synchronization tools, bundled notices, and documentation claims
as part of final retirement, subject to attribution obligations for any retained
material. Audit the source tree, build graph, packaged assets, and native symbols.

Existing saved edits must never be silently reinterpreted as RapidRAW parameters.
Use explicit data migration where a mapping is verified; otherwise report that
the legacy grade cannot be reproduced and require an explicit reset. Supporting
old project data must not retain executable legacy grading code. Temporary old
code during the staged implementation is not part of the final deliverable.

RapidRAW's native output transform and our AgX/ACES choices are distinct modes.
Upstream parity is measured with upstream's output transform. Our transforms
are checked against their existing references at the same scene-linear boundary.
Differences caused by choosing our transforms are deliberate, not upstream parity.

## Parity contract

* Preserve the upstream files byte for byte and map each translated function to
  its upstream path, symbol, and pinned commit. Document every adapter and change.
* Preserve operation order, constants, branches, input precision, texture formats,
  sampling, borders, and host parameter preparation. Shader text alone is not
  a complete algorithm reference.
* Compare the actual upstream WGSL execution with Vulkan GLSL on identical input
  buffers, parameters, dimensions, and device. Record mismatched sample count,
  maximum/mean absolute error, ULP differences, and final quantized pixel errors.
* Claim bit identity only when every compared bit matches. Float compiler and
  transcendental differences require investigation and an explicit recorded
  acceptance criterion; no tolerance is implicitly approved by this document.
* Include zero/default identity, negative and HDR RGB, saturated colors, ramps,
  odd image dimensions, borders, each stage alone, and combined stages.
* Preview and export use the same grade and transforms. Any reduced-resolution
  playback processing must be explicit and tested separately from full resolution.

## Ordered checkpoints

The implemented engine/UI checkpoint and outstanding integration/removal work
are documented in [`rapidraw-engine.md`](rapidraw-engine.md).

The complete pinned editing/UI source inventory is in
[`rapidraw-inventory/README.md`](rapidraw-inventory/README.md), with reviewed
feature matrix, 124 default keys, 220 nested default paths, 215 JSX control
instances, 39 host scale divisors, dependency references, and explicit port
status. The reference includes 232 hash-verified files. Inventory completion
does not mean UI implementation or runtime Android feature validation.

1. Frozen reference and contract (completed; numerical parity not yet measured).
2. Add stage timings for decode, raw corrections, RCD, grading, scaling/transfer,
   encoder submission/drain. Establish sustained device baselines for playback
   frame delivery, dropped frames, export fps, memory, and thermal degradation.
3. Build an executable upstream WGSL comparison harness and Vulkan stage harness.
   Start with `apply_linear_exposure`; preserve its zero branch and `pow(2, EV)`.
   This is an isolated validation stage, not a second exposure in production.
4. Introduce versioned RapidRAW parameters and the shared scene-linear boundary.
   Port host preparation and adjustment functions in upstream execution order,
   including guided/blur dependencies for spatial controls. Gate each stage on
   reference results before enabling its control.
5. Connect our AgX/ACES at the defined output boundary. Verify their existing
   references and preview/export consistency. Preserve upstream output as a
   reference mode for complete-pipeline comparisons.
6. Pipeline decode and GPU work with bounded buffers; reuse GPU allocations;
   implement codec delivery with minimal readback. Validate 8/10-bit exports,
   exact frame counts, cancellation, and audio synchronization.
7. Replace all existing editing controls and receipt code, and delete the complete
   old CPU/GPU adjustment engine and MLV-App integration after replacements pass
   the stage gates, saved-project migration checks, and sustained device
   playback/export measurements. Final acceptance requires zero legacy engine
   dependencies or executable fallback paths, including MLV-App RAW tools.

Smooth playback and faster export are required outcomes, not established facts.
Record source resolution/rate, output settings, device, active effects, and run
duration for every performance result. Short correctness exports do not establish
sustained throughput. No speed claim or production engine switch at checkpoint 1.

Initial measurement tooling is in `docs/pipeline-benchmark.md`. The isolated
exposure WGSL/SPIR-V comparison passes exact bits on Apple M1/Metal and on
Android Vulkan (25080RABDG, Mali-G615 MC2). Android reference compilation uses
Naga 29.0.4, matching the frozen upstream Cargo.lock. This establishes isolated
exposure parity, not complete-engine or RCD boundary parity. Device baseline
and parity results are stored under `docs/benchmarks/2026-11-01`.
