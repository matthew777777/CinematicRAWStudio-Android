#!/usr/bin/env python3
"""Extract the RapidRAW 1.7 Vulkan grading graph for the Android host."""
import argparse
import json
from pathlib import Path
import re
import subprocess
from textwrap import indent

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "third_party/rapidraw-1.7-reference"
REF = REFERENCE / "src-tauri/src"
OUT = ROOT / "app/src/main/rust/rapidraw-core/src/generated"


def section(text, first, last):
    start = text.index(first)
    return text[start:text.index(last, start)]


def mobile_shader(text):
    text = text.replace("const F_MONOCHROME: u32 = 2048u;",
                        "const F_MONOCHROME: u32 = 2048u;\nconst F_GUIDED: u32 = 4096u;")
    start = text.index("    let gf_source")
    end = text.index("    var processed_rgb", start)
    guided = text[start:end]
    for var in ("detail_fine", "detail_broad", "lc_log_gain"):
        guided = guided.replace(f"let {var} =", f"{var} =")
    guided += """    clipped = sampled_base.clipped;
    tone_broad = sampled_base.tone_detail * max(detail_core(sampled_base.tone_detail, LC_BROAD_CORE), textured);
"""
    text = text[:start] + """    var detail_fine = 0.0;
    var detail_broad = 0.0;
    var lc_log_gain = 0.0;
    var clipped = 0.0;
    var tone_broad = 0.0;
    if (has_feature(F_GUIDED) && (t_clarity != 0.0 || t_structure != 0.0
        || adjustments.global.centre != 0.0 || t_shadows != 0.0 || t_blacks != 0.0
        || t_highlights != 0.0 || t_whites != 0.0 || t_dehaze != 0.0)) {
""" + indent(guided, "    ") + "    }\n\n" + text[end:]
    text = text.replace("    let tone_stats = textureLoad(airlight_texture, vec2<i32>(2, 0), 0);", """    var tone_stats = vec4<f32>(0.0);
    if (has_feature(F_GUIDED) && (t_contrast != 0.0 || t_highlights != 0.0)) {
        tone_stats = textureLoad(airlight_texture, vec2<i32>(2, 0), 0);
    }""")
    text = text.replace("    let tone_broad = sampled_base.tone_detail * max(detail_core(sampled_base.tone_detail, LC_BROAD_CORE), textured);\n", "")
    text = text.replace("tone_broad, sampled_base.clipped, t_highlights", "tone_broad, clipped, t_highlights")
    return text.replace("fn display_transform(c: vec3<f32>) -> vec3<f32> {", """fn display_transform(c: vec3<f32>) -> vec3<f32> {
    if (adjustments.global._pad_agx1 == 1.0) { return crs_filament_agx(c); }
    if (adjustments.global._pad_agx1 == 2.0) { return crs_aces2(c); }""")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    subprocess.run(["python3", ROOT / "tools/verify_rapidraw_reference.py", "--reference", REFERENCE], check=True)
    gpu = REF / "render/gpu"
    processor = (gpu / "processor.rs").read_text()
    pipelines = (gpu / "pipelines.rs").read_text()
    pipelines = pipelines[pipelines.index("use std::collections"):].replace("use std::sync::Arc;\n", "")
    pipelines = re.sub(r"^.*motion_blur:.*\n", "", pipelines, flags=re.M)
    pipelines = pipelines.replace("../../shaders/", "shaders/")
    pipelines = pipelines.replace("pub fn shader_features", "const F_GUIDED: u32 = 4096;\n\npub fn shader_features")
    pipelines = pipelines.replace("    features\n}", """    set(F_GUIDED, g.centré != 0.0
        || any(g.contrast, |m| m.contrast)
        || any(g.clarity, |m| m.clarity)
        || any(g.structure, |m| m.structure)
        || any(g.dehaze, |m| m.dehaze)
        || any(g.shadows, |m| m.shadows)
        || any(g.blacks, |m| m.blacks)
        || any(g.highlights, |m| m.highlights)
        || any(g.whites, |m| m.whites));
    features
}""")
    header = """// Generated from RapidRAW 1.7 gpu/processor.rs and pipelines.rs, AGPL-3.0-or-later.
use std::sync::Arc;
use half::f16;
use image::{ImageBuffer, Luma};
use wgpu::util::{DeviceExt, TextureDataOrder};
use crate::image_processing::{AllAdjustments, HslColor, MAX_MASKS, MaskAdjustments};
use crate::render::{GpuContext, GpuImageEntry, Lut};
"""
    body = section(processor, "#[derive(Clone, Copy, Debug)]", "fn recycle_readback_buffer")
    body = body[:body.index("type ReadbackPool")]
    body += section(processor, "struct HighPrecisionTile", "pub struct MotionBlurred")
    body += section(processor, "pub enum RenderedPixels", "pub const GPU_IMAGE_CACHE_ENTRIES")
    body += section(processor, "impl GpuProcessor", "    pub fn motion_blurred")
    body += section(processor, "    fn lut_view", "    pub fn readback_buffer")
    body += processor[processor.index("    pub fn display_textures"):]
    body = re.sub(r"^.*(?:motion_blur:|motion_cache:|motion_grain:|readback_pool:|NEXT_GPU_IMAGE_ID).+\n", "", body, flags=re.M)
    body = body.replace("crate::effects::light", "crate::rapid_light").replace("crate::render::gpu::compute", "crate::rapid_compute")
    body = body.replace("    display_textures: std::sync::OnceLock<DisplayTextures>,", "    display_textures: std::sync::OnceLock<DisplayTextures>,\n    export_textures: std::sync::OnceLock<DisplayTextures>,")
    body = body.replace("            display_textures: std::sync::OnceLock::new(),", "            display_textures: std::sync::OnceLock::new(),\n            export_textures: std::sync::OnceLock::new(),")
    begin = body.index("        let gf_coeffs_view = &input.gf_coeffs_view;")
    end = body.index("        let device = &self.context.device;", begin)
    body = body[:begin] + """        let (width, height) = (input.width, input.height);
        let features = shader_features(&request.adjustments);
        let guided = (features & F_GUIDED != 0).then(|| {
            input.guided.get_or_init(|| {
                self.build_guided_coeffs(input_texture_view, width, height, input.is_raw)
            })
        });
        let dummy = &self.pipelines.dummy_blur_view;
        let gf_coeffs_view = guided.map_or(dummy, |textures| &textures.coeffs);
        let tone_base_view = guided.map_or(dummy, |textures| &textures.tone_base);
        let haze_view = guided.map_or(dummy, |textures| &textures.haze);
        let airlight_view = guided.map_or(dummy, |textures| &textures.airlight);
""" + body[end:]
    body = body.replace("                shader_features(&request.adjustments),", "                features,")
    body = body.replace(section(body, "        if output_precision == RenderOutputPrecision::SixteenBit && output_to_display", "        let high_precision_tile"), "")
    body = body.replace("texture: &self.tile_output_texture,", "texture: output_tile_texture,")
    body = body.replace("texture: &self.display_textures().working_texture,", "texture: &self.display_textures_for_precision(output_precision).working_texture,")
    compute = (gpu / "compute.rs").read_text().replace("crate::render::gpu::context::GpuContext", "crate::render::GpuContext").replace("../../shaders/", "shaders/")
    # Retain scratch allocations for the light pyramid within a mobile-sized budget.
    compute = compute.replace("768 << 20", "32 << 20")
    light = (REF / "effects/light.rs").read_text()
    light = light.replace("crate::render::gpu::context::GpuContext", "crate::render::GpuContext")
    light = light.replace("crate::render::gpu::compute", "crate::rapid_compute").replace("crate::render::uniforms", "crate::image_processing")
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
    fn run_configured(&self,input:&GpuImageEntry,request:RenderRequest,display:bool,precision:RenderOutputPrecision)->Result<(RenderedPixels,u32,u32,u32,u32),String> {
        let mask = self.mask_view(request.mask_bitmaps, input.width, input.height, request.mask_cache_key);
        self.run(input, &mask, request, display, precision)
    }

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
        self.run_configured(input,request,true,RenderOutputPrecision::SixteenBit)?;
        Ok(self.display_textures_for_precision(RenderOutputPrecision::SixteenBit))
    }
    pub fn render_frame16(&self, input: &GpuImageEntry, request: RenderRequest) -> Result<Vec<u16>, String> {
        match self.run_configured(input, request, false, RenderOutputPrecision::SixteenBit)?.0 {
            RenderedPixels::U16(values) => Ok(values),
            _ => Err("Unexpected pixel precision".into()),
        }
    }
    pub fn render_frame8(&self, input: &GpuImageEntry, request: RenderRequest) -> Result<Vec<u8>, String> {
        match self.run_configured(input, request, false, RenderOutputPrecision::EightBit)?.0 {
            RenderedPixels::U8(values) => Ok(values),
            _ => Err("Unexpected pixel precision".into()),
        }
    }
    pub fn render_frame_gpu(&self, input: &GpuImageEntry, request: RenderRequest) -> Result<&DisplayTextures, String> {
        self.run_configured(input, request, true, RenderOutputPrecision::EightBit)?;
        Ok(self.display_textures())
    }
}
'''

    files = {"renderer.rs": header + pipelines + body + adapter,
             "rapid_compute.rs": compute, "rapid_light.rs": light}
    for name in ("shader", "guided", "blur", "compute_common", "transform", "relight", "fog", "lens_blur", "light_effects"):
        text = (REF / f"shaders/{name}.wgsl").read_text()
        if name == "shader":
            text = (OUT / "output_transforms.wgsl").read_text() + "\n" + mobile_shader(text)
        files[f"shaders/{name}.wgsl"] = text
    commit = json.loads((REFERENCE / "manifest.json").read_text())["commit"]
    files["../renderer-source-map.json"] = json.dumps(dict(commit=commit,
        upstream=["render/gpu/processor.rs", "render/gpu/pipelines.rs", "render/gpu/compute.rs", "effects/light.rs", "shaders"]), indent=2) + "\n"
    for name, text in files.items():
        path = OUT / name
        if args.check:
            if path.read_text() != text:
                raise SystemExit(f"Generated renderer differs: {path}")
        else:
            path.write_text(text)


if __name__ == "__main__":
    main()
