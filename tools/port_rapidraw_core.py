#!/usr/bin/env python3
"""Extract exact pinned host parameter routines into a standalone Rust core.

Only adapters import metadata already resolved by CRS. No image algorithms are
reimplemented or substituted by this generator.
"""
import argparse
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "third_party/rapidraw-1.7-reference/src-tauri/src"
DEST = ROOT / "app/src/main/rust/rapidraw-core/src/generated"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    subprocess.run(["python3", str(ROOT / "tools/verify_rapidraw_reference.py"), "--reference", str(REF.parents[1])], check=True)
    files, mappings = {}, []
    def take(path, begin, end=None):
        source = (REF / path).read_text()
        start = source.index(begin)
        stop = source.index(end, start) if end else len(source)
        mappings.append(dict(upstream=path, first_line=source.count("\n", 0, start)+1,
                             last_line=source.count("\n", 0, stop)))
        return source[start:stop]

    uniforms = take("render/uniforms.rs", "use crate::decode")
    uniforms = uniforms.replace("crate::decode::", "crate::").replace(
        "crate::masks::generation", "crate::mask_generation").replace(
        "crate::render::color_space", "crate::color_space").replace(
        "crate::adjustments::negative_conversion", "crate::negative_conversion")
    uniforms = "\n".join(line for line in uniforms.splitlines()
                           if not line.startswith(("use crate::render::adjustment_utils",
                                                   "use crate::render::options"))) + "\n"
    options = take("render/options.rs", "#[derive(Clone, Copy, Debug, Default, PartialEq)]",
                   "pub fn resolve_render_options")
    adjustments = take("render/adjustment_utils.rs", "fn uses_baseline_exposure")
    files["image_processing.rs"] = ("// Generated from RapidRAW 1.7, AGPL-3.0-or-later.\n"
        "use crate::baseline_exposure::BaselineOptions;\nuse serde_json::{Value, json};\n"
        'const BASELINE_EXPOSURE_KEY: &str = "baselineExposure";\n'
        + uniforms + options + adjustments)
    files["color_space.rs"] = take("render/color_space.rs", "use glam")
    files["white_balance.rs"] = take("decode/white_balance.rs", "use crate").replace(
        "crate::render::color_space", "crate::color_space")
    files["baseline_exposure.rs"] = take("decode/baseline_exposure.rs",
        "#[derive(Clone, Copy, Debug, Default)]\npub struct RawExposure", "impl BaselineOptions")
    files["as_shot.rs"] = ("use crate::baseline_exposure::RawExposure;\nuse crate::white_balance::WhiteBalance;\n"
        + take("decode/as_shot.rs", "#[derive(Clone, Copy, Debug)]\npub struct AsShot", "fn as_shot_cache"))
    files["negative_conversion.rs"] = ("use serde_json::Value;\nuse crate::image_processing::is_effect_enabled;\n"
        + take("adjustments/negative_conversion.rs", "pub fn is_enabled", "pub fn effective_is_raw"))
    source = (REF / "masks/generation.rs").read_text()
    # Include the exact declarations and serde attributes; stop before behavior.
    start = source.index("#[derive", source.index("use "))
    finish = source.index("#[derive", source.index("pub struct MaskDefinition"))
    files["mask_generation.rs"] = "// Generated from pinned RapidRAW; AGPL-3.0-or-later.\nuse serde_json::Value;\n" + source[start:finish]
    files['image_processing.rs']+='''
// CRS output adapter: a previously unused padding word selects our retained
// transforms at RapidRAW's scene-linear output boundary. Zero is upstream.
impl AllAdjustments {
    pub fn set_crs_output(&mut self, mode: u32) -> Result<(), &'static str> {
        if mode > 2 { return Err("Unknown CRS output transform"); }
        self.global._pad_agx1 = mode as f32;
        Ok(())
    }
}
'''
    mappings.append(dict(upstream="masks/generation.rs", first_line=source.count("\n",0,start)+1,
                         last_line=source.count("\n",0,finish), output="mask_generation.rs"))
    for name, data in files.items():
        path = DEST / name
        if args.check:
            if not path.exists() or path.read_text() != data:
                raise SystemExit(f"Generated source differs: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(data)
    manifest = dict(commit="00379017cb14f3fc398e69fa14b33b60af07c588", ranges=mappings,
                    adapters="Metadata is supplied by the caller. No decoder, file-service or UI code in core.")
    path = DEST.parent / "source-map.json"
    if args.check:
        assert json.loads(path.read_text()) == manifest
    else:
        path.write_text(json.dumps(manifest, indent=2)+"\n")
    print("Verified" if args.check else "Generated", len(files), "unchanged host source extracts")


if __name__ == "__main__":
    main()
