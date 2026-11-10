#!/usr/bin/env python3
"""Extract pinned geometric/brush rasterization, excluding all AI and image-dependent masks."""
from pathlib import Path
import argparse,hashlib,json
ROOT=Path(__file__).resolve().parents[1];REF=ROOT/'third_party/rapidraw-reference/src-tauri/src';OUT=ROOT/'app/src/main/rust/rapidraw-core/src/generated'
p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');args=p.parse_args()
s=(REF/'mask_generation.rs').read_text()
a=s.index('#[derive(Serialize, Deserialize, Debug, Clone, Default)]\n#[serde(rename_all = "camelCase")]\nstruct GrowFeatherParameters')
b=s.index('pub struct TransformParams')
c=s.index('fn for_each_mask_row');d=s.index('fn generate_sub_mask_bitmap',c)
e=s.index('pub fn generate_mask_bitmap');f=s.index('#[tauri::command(async)]\npub fn generate_mask_overlay',e)
text='''// Generated pinned RapidRAW rasterization; AGPL-3.0-or-later.
use crate::mask_generation::{MaskDefinition,SubMask,SubMaskMode};
use image::{DynamicImage,GrayImage,Luma};
use rayon::prelude::*;
use serde::{Deserialize,Serialize};
use serde_json::Value;
use std::f32::consts::PI;
'''+s[a:b]+s[c:d]+'''
// Dispatch adapter: the Android basic toolset exposes geometric/brush masks only.
fn generate_sub_mask_bitmap(sub_mask:&SubMask,width:u32,height:u32,scale:f32,crop_offset:(f32,f32),_warped_image:Option<&DynamicImage>)->Option<GrayImage> {
    if !sub_mask.visible {return None;}
    match sub_mask.mask_type.as_str() {
        "radial"=>Some(generate_radial_bitmap(&sub_mask.parameters,width,height,scale,crop_offset)),
        "linear"=>Some(generate_linear_bitmap(&sub_mask.parameters,width,height,scale,crop_offset)),
        "brush"=>Some(generate_brush_bitmap(&sub_mask.parameters,width,height,scale,crop_offset)),
        "flow"=>Some(generate_flow_bitmap(&sub_mask.parameters,width,height,scale,crop_offset)),
        "all"=>Some(generate_all_bitmap(width,height)),
        _=>None,
    }
}
'''+s[e:f]
path=OUT/'mask_raster.rs'
if args.check:assert path.read_text()==text
else:path.write_text(text)
manifest=dict(commit='f1d71a69eef813432db214f77969f567c41ff1d7',sha256=hashlib.sha256(s.encode()).hexdigest(),ranges=[[s.count('\n',0,x)+1,s.count('\n',0,y)] for x,y in [(a,b),(c,d),(e,f)]],adapter='Geometric/brush dispatch only; no AI methods')
path=OUT/'mask-raster-source.json'
if args.check:assert json.loads(path.read_text())==manifest
else:path.write_text(json.dumps(manifest,indent=2)+'\n')
print('Verified' if args.check else 'Generated','basic mask rasterization')
