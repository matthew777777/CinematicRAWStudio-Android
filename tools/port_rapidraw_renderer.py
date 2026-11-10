#!/usr/bin/env python3
"""Extract the upstream compute renderer without its Tauri/window/service layer."""
import argparse
import json
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]
REF=ROOT/'third_party/rapidraw-reference/src-tauri/src'
OUT=ROOT/'app/src/main/rust/rapidraw-core/src/generated'


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--check',action='store_true');args=parser.parse_args()
    subprocess.run(['python3',str(ROOT/'tools/verify_rapidraw_reference.py')],check=True)
    source=(REF/'gpu_processing.rs').read_text();chunks=[];mapping=[]
    for first,last in [
        ('#[derive(Clone, Copy, Debug)]\npub struct Roi','#[repr(C)]\n#[derive(Debug, Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]\npub struct DisplayTransform'),
        ('fn read_texture_data_roi','fn to_rgba_f16'),
        ('#[repr(C)]\n#[derive(Debug, Copy, Clone, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]\nstruct BlurParams','struct AnalyticsReadback'),
        ('pub struct DisplayTextures','pub fn process_and_get_dynamic_image('),
    ]:
        start=source.index(first);end=source.index(last,start)
        # AnalyticsReadback has a derive immediately before it: exclude that attribute.
        part=source[start:end]
        if last=='struct AnalyticsReadback':part=part.rstrip().removesuffix('#[derive(Debug)]').rstrip()+'\n'
        # Output transport adapters only; do not alter any grading arithmetic.
        part=part.replace('    display_textures: std::sync::OnceLock<DisplayTextures>,','    display_textures: std::sync::OnceLock<DisplayTextures>,\n    export_textures: std::sync::OnceLock<DisplayTextures>,')
        part=part.replace('            display_textures: std::sync::OnceLock::new(),','            display_textures: std::sync::OnceLock::new(),\n            export_textures: std::sync::OnceLock::new(),')
        reject='        if output_precision == RenderOutputPrecision::SixteenBit && output_to_display {\n            return Err(\n                "High-precision GPU output is only supported for CPU-readback renders.".to_string(),\n            );\n        }\n'
        part=part.replace(reject,'')
        part=part.replace('texture: &self.tile_output_texture,','texture: output_tile_texture,')
        part=part.replace('texture: &self.display_textures().working_texture,','texture: &self.display_textures_for_precision(output_precision).working_texture,')
        chunks.append(part);mapping.append(dict(first_line=source.count('\n',0,start)+1,last_line=source.count('\n',0,end)))
    header='''// Generated from pinned RapidRAW gpu_processing.rs; AGPL-3.0-or-later.
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use half::f16;
use image::{ImageBuffer, Luma};
use wgpu::util::{DeviceExt, TextureDataOrder};
use crate::image_processing::{AllAdjustments, MAX_MASKS, MaskAdjustments};
use crate::render::{GpuContext, GpuImageEntry};
use crate::render::Lut;
'''
    adapter='''
// CRS adapter: expose the upstream compute run without its Tauri/window layer.
pub fn read_rgba8(device:&wgpu::Device,queue:&wgpu::Queue,texture:&wgpu::Texture,width:u32,height:u32)->Result<Vec<u8>,String> {
    read_texture_data_roi(device,queue,texture,wgpu::Origin3d::ZERO,wgpu::Extent3d {width,height,depth_or_array_layers:1},4)
}
pub fn read_rgba16(device:&wgpu::Device,queue:&wgpu::Queue,texture:&wgpu::Texture,width:u32,height:u32)->Result<Vec<u16>,String> {
    let bytes=read_texture_data_roi(device,queue,texture,wgpu::Origin3d::ZERO,wgpu::Extent3d {width,height,depth_or_array_layers:1},8)?;
    Ok(bytes.chunks_exact(2).map(|b|u16::from_ne_bytes([b[0],b[1]])).collect())
}
impl GpuProcessor {
    fn display_textures_for_precision(&self,precision:RenderOutputPrecision)->&DisplayTextures {
        if precision==RenderOutputPrecision::EightBit {return self.display_textures();}
        self.export_textures.get_or_init(|| {
            let create=|label|self.context.device.create_texture(&wgpu::TextureDescriptor {
                label:Some(label),size:self.full_image_size,mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,
                format:wgpu::TextureFormat::Rgba16Float,usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST|wgpu::TextureUsages::COPY_SRC,view_formats:&[] });
            let working_texture=create("RapidRAW high-precision assembled frame");
            let output_texture=create("RapidRAW high-precision output");
            let output_texture_view=output_texture.create_view(&Default::default());
            DisplayTextures {working_texture,output_texture,output_texture_view}
        })
    }
    pub fn render_frame16_gpu(&self,input:&GpuImageEntry,request:RenderRequest)->Result<&DisplayTextures,String> {
        self.run(input,request,true,RenderOutputPrecision::SixteenBit)?;
        Ok(self.display_textures_for_precision(RenderOutputPrecision::SixteenBit))
    }
    pub fn render_frame16(&self, input: &GpuImageEntry, request: RenderRequest) -> Result<Vec<u16>, String> {
        match self.run(input, request, false, RenderOutputPrecision::SixteenBit)?.0 {
            RenderedPixels::U16(values) => Ok(values),
            _ => Err("Unexpected pixel precision".into()),
        }
    }
    pub fn render_frame8(&self, input: &GpuImageEntry, request: RenderRequest) -> Result<Vec<u8>, String> {
        match self.run(input, request, false, RenderOutputPrecision::EightBit)?.0 {
            RenderedPixels::U8(values) => Ok(values),
            _ => Err("Unexpected pixel precision".into()),
        }
    }
    pub fn render_frame_gpu(&self, input: &GpuImageEntry, request: RenderRequest) -> Result<&DisplayTextures, String> {
        self.run(input, request, true, RenderOutputPrecision::EightBit)?;
        Ok(self.display_textures())
    }
}
'''
    files={'renderer.rs':header+'\n'.join(chunks)+adapter}
    transforms=(OUT/'output_transforms.wgsl').read_text()
    for shader in (REF/'shaders').glob('*.wgsl'):
        text=shader.read_text()
        if shader.name=='shader.wgsl':
            files['shaders/upstream-shader.wgsl']=text
            original='if (has_feature(F_AGX) && adjustments.global.tonemapper_mode == 1u) {'
            replacement='''if (adjustments.global._pad_agx1 == 1.0) {
        default_tonemapped = crs_filament_agx(composite_rgb_linear);
    } else if (adjustments.global._pad_agx1 == 2.0) {
        default_tonemapped = crs_aces2(composite_rgb_linear);
    } else '''+original
            assert text.count(original)==1
            text=transforms+'\n'+text.replace(original,replacement)
        files['shaders/'+shader.name]=text
    for name,text in files.items():
        path=OUT/name
        if args.check:
            if not path.exists() or path.read_text()!=text:raise SystemExit('Generated renderer differs: '+str(path))
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text)
    path=OUT.parent/'renderer-source-map.json';data=dict(commit='f1d71a69eef813432db214f77969f567c41ff1d7',upstream='src-tauri/src/gpu_processing.rs',ranges=mapping)
    if args.check:assert json.loads(path.read_text())==data
    else:path.write_text(json.dumps(data,indent=2)+'\n')
    print('Verified' if args.check else 'Generated','independent upstream compute renderer')


if __name__=='__main__':main()
