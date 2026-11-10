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
REF = ROOT / "third_party/rapidraw-reference/src-tauri/src"
DEST = ROOT / "app/src/main/rust/rapidraw-core/src/generated"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    subprocess.run(["python3", str(ROOT / "tools/verify_rapidraw_reference.py")], check=True)
    files, mappings = {}, []
    def extract(name, selections, header=""):
        source = (REF / name).read_text()
        chunks = []
        for begin, end in selections:
            start = source.index(begin)
            finish = source.index(end, start) if end else len(source)
            chunks.append(source[start:finish])
            mappings.append(dict(upstream=name, first_line=source.count("\n", 0, start)+1,
                                 last_line=source.count("\n", 0, finish), output=name))
        files[name] = "// Generated from pinned RapidRAW; AGPL-3.0-or-later. Do not edit.\n" + header + "\n" + "\n".join(chunks)
    extract("image_processing.rs", [
        ("fn uses_baseline_exposure", "#[derive"),
        ("#[derive(Serialize, Deserialize, Debug, Clone, Copy, Pod, Zeroable, Default)]\n#[repr(C)]\npub struct Point", "pub fn resolve_tonemapper_override"),
        ("#[derive(Clone, Copy, Debug, Default, PartialEq)]\npub struct RenderOptions", "pub fn resolve_render_options"),
        ("fn is_section_visible", "#[derive(Clone)]\npub struct GpuContext"),
    ], """use crate::as_shot::AsShot;
use crate::baseline_exposure::BaselineOptions;
use crate::mask_generation::MaskDefinition;
use crate::white_balance::{self, WhiteBalance};
use bytemuck::{Pod, Zeroable};
use glam::{Mat3, Vec2, Vec3};
use serde::{Serialize, Deserialize};
use serde_json::{Value, json};
const BASELINE_EXPOSURE_KEY: &str = "baselineExposure";
""")
    extract("white_balance.rs", [("use crate::image_processing", None)])
    extract("baseline_exposure.rs", [
        ("#[derive(Clone, Copy, Debug, Default)]\npub struct RawExposure", "impl BaselineOptions"),
    ])
    extract("as_shot.rs", [("#[derive(Clone, Copy, Debug)]\npub struct AsShot", "fn as_shot_cache")],
            "use crate::baseline_exposure::RawExposure;\nuse crate::white_balance::WhiteBalance;")
    extract("negative_conversion.rs", [("pub fn is_enabled", "pub fn effective_is_raw")], "use serde_json::Value;")
    source = (REF / "mask_generation.rs").read_text()
    # Include the exact declarations and serde attributes; stop before behavior.
    start = source.index("#[derive", source.index("use "))
    finish = source.index("impl MaskDefinition")
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
    mappings.append(dict(upstream="mask_generation.rs", first_line=source.count("\n",0,start)+1,
                         last_line=source.count("\n",0,finish), output="mask_generation.rs"))
    for name, data in files.items():
        path = DEST / name
        if args.check:
            if not path.exists() or path.read_text() != data:
                raise SystemExit(f"Generated source differs: {path}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(data)
    manifest = dict(commit="f1d71a69eef813432db214f77969f567c41ff1d7", ranges=mappings,
                    adapters="Metadata is supplied by the caller. No decoder, file-service or UI code in core.")
    path = DEST.parent / "source-map.json"
    if args.check:
        assert json.loads(path.read_text()) == manifest
    else:
        path.write_text(json.dumps(manifest, indent=2)+"\n")
    print("Verified" if args.check else "Generated", len(files), "unchanged host source extracts")


if __name__ == "__main__":
    main()
