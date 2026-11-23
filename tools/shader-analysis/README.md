# Android Vulkan shader analysis

This entry point uses the configured [RawLens shader analyzer](../../../RawLens/tools/shader-analysis/README.md). Set `RAWLENS_ROOT` if the RawLens checkout is elsewhere. The licensed Qualcomm compiler stays in RawLens. The wrapper streams CRS SPIR-V into the existing Colima container and retains both vendors' JSON and input hashes.

```sh
colima start --profile rawlens-shaders
python3 tools/shader-analysis/analyze.py --backend both \
  --adreno-core a750 --core Mali-G615 --core Mali-G720
```

The default paths cover the packaged MHC modules. Android builds the RapidRAW grader from WGSL through wgpu/Naga at runtime. Compile the corresponding WGSL for the offline comparison with the Naga CLI version in the RapidRAW lockfile.

```sh
python3 tools/compile_rapidraw_shaders.py --renderer runtime --naga /path/to/naga \
  --output build/shader-analysis/after/spv
python3 tools/compile_rapidraw_shaders.py --renderer runtime --naga /path/to/naga \
  --features 0 --output build/shader-analysis/after/basic-spv
python3 tools/shader-analysis/analyze.py build/shader-analysis/after/spv \
  build/shader-analysis/after/basic-spv/grade8-main.spv \
  build/shader-analysis/after/basic-spv/grade16-main.spv --backend both \
  --adreno-core a750 --core Mali-G615 --core Mali-G720 \
  --output build/shader-analysis/after/reports
```

Repeat in a baseline checkout with the same build recipe and feature overrides. Use `--renderer reference-1.7` for the pinned upstream 1.7 compute shaders. Treat that run as a version comparison because upstream bindings, tone response, and output transforms differ.

Run the Rust `render-fixture` binary built with `--features gpu --target aarch64-linux-android --release` on the device for preview and export image comparisons. Compare the RGBA8, RGBA16F and P010 files across builds. Run it again with `--benchmark-guided` for per-frame upload-to-readback wall times. The synthetic fixture measures grading paths independently of playback, which adds decode, presentation, audio and scheduling.

The RawLens README documents A740 assertions in `vf_rcd.spv`, `vf_rcd_f16.spv`, and `denoise_img_wiener_apply.spv`. Report compiler failures separately from device execution.
