// SPDX-License-Identifier: AGPL-3.0-or-later
// CRS source-buffer adapter. The compute graph itself is generated unchanged.
use std::sync::{Arc, OnceLock};
#[path="resources.rs"]
mod resources;
use crate::renderer::{GpuPipelines, GpuProcessor, RenderRequest};
use crate::image_processing::AllAdjustments;

#[derive(Clone)]
pub struct GpuContext {
    pub device: Arc<wgpu::Device>,
    pub queue: Arc<wgpu::Queue>,
    pub limits: wgpu::Limits,
    pub pipelines: Arc<OnceLock<Arc<GpuPipelines>>>,
}
pub struct GpuImageEntry {
    pub id: u64,
    pub texture: wgpu::Texture,
    pub texture_view: wgpu::TextureView,
    pub gf_coeffs_view: wgpu::TextureView,
    pub gf_structure_view: wgpu::TextureView,
    pub haze_view: wgpu::TextureView,
    pub airlight_view: wgpu::TextureView,
    pub width: u32,
    pub height: u32,
}
#[derive(Debug, Clone)]
pub struct Lut { pub size: u32, pub data: Vec<f32> }

pub struct RapidRenderer {
    pub adapter_name: String,
    context: GpuContext,
    processor: GpuProcessor,
    input: Option<GpuImageEntry>,
    next_id: u64,
    width: u32,
    height: u32,
    camera_source: Option<wgpu::Texture>,
    camera_pipeline: OnceLock<wgpu::ComputePipeline>,
    mhc: Option<crate::mhc::MhcPipeline>,
    resources: resources::RenderResources,
    delivery: std::sync::Mutex<Option<crate::delivery::P010Delivery>>,
}

impl RapidRenderer {
    pub fn new(width: u32, height: u32) -> Result<Self, String> {
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor {
            backends: if cfg!(target_os="android") { wgpu::Backends::VULKAN } else { wgpu::Backends::PRIMARY },
            ..wgpu::InstanceDescriptor::new_without_display_handle()
        });
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            power_preference: wgpu::PowerPreference::HighPerformance, ..Default::default()
        })).map_err(|e| e.to_string())?;
        let limits=adapter.limits();
        if width==0 || height==0 || width>limits.max_texture_dimension_2d || height>limits.max_texture_dimension_2d {
            return Err("Source dimensions exceed GPU limits".into());
        }
        let adapter_name=format!("{} / {:?}",adapter.get_info().name,adapter.get_info().backend);
        let (device,queue)=pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label:Some("CRS RapidRAW compute"),required_limits:limits.clone(), ..Default::default()
        })).map_err(|e|e.to_string())?;
        let context=GpuContext {device:Arc::new(device),queue:Arc::new(queue),limits,pipelines:Arc::new(OnceLock::new())};
        let scope=context.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let processor=GpuProcessor::new(context.clone(),width,height)?;
        if let Some(error)=pollster::block_on(scope.pop()) {return Err(error.to_string());}
        Ok(Self { adapter_name,context,processor,input:None,next_id:1,width,height,camera_source:None,camera_pipeline:OnceLock::new(),mhc:None,resources:Default::default(),delivery:std::sync::Mutex::new(None) })
    }

    /// Accept scene-linear working-space RGBA16F, not uncalibrated camera RGB.
    /// Updating a source frame invalidates source-dependent guided/blur results.
    /// These upload/readback adapters are for bring-up; GPU texture entry avoids
    /// readback and is exposed separately by the generated processor.
    pub fn upload(&mut self,rgba16f:&[u16],is_raw:u32)->Result<(),String> {
        self.resources.reset_source();
        let expected=self.width as usize*self.height as usize*4;
        if rgba16f.len()!=expected {return Err("RGBA16F source length mismatch".into());}
        let texture=match self.input.take() {
            Some(previous) if previous.width==self.width&&previous.height==self.height=>previous.texture,
            Some(_)=>self.context.device.create_texture(&wgpu::TextureDescriptor {
                label:Some("RapidRAW source"),size:wgpu::Extent3d{width:self.width,height:self.height,depth_or_array_layers:1},
                mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,format:wgpu::TextureFormat::Rgba16Float,
                usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST|wgpu::TextureUsages::COPY_SRC|wgpu::TextureUsages::STORAGE_BINDING,view_formats:&[]}),
            None=>self.context.device.create_texture(&wgpu::TextureDescriptor {
                label:Some("RapidRAW linear source"),
                size:wgpu::Extent3d{width:self.width,height:self.height,depth_or_array_layers:1},
                mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,
                format:wgpu::TextureFormat::Rgba16Float,
                usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST|wgpu::TextureUsages::COPY_SRC|wgpu::TextureUsages::STORAGE_BINDING,view_formats:&[]
            })
        };
        self.context.queue.write_texture(texture.as_image_copy(),bytemuck::cast_slice(rgba16f),
            wgpu::TexelCopyBufferLayout{offset:0,bytes_per_row:Some(self.width*8),rows_per_image:Some(self.height)},
            wgpu::Extent3d{width:self.width,height:self.height,depth_or_array_layers:1});
        let view=texture.create_view(&Default::default());
        let guided=self.processor.build_guided_coeffs(&view,self.width,self.height,is_raw);
        self.input=Some(GpuImageEntry{id:self.next_id,texture,texture_view:view,
            gf_coeffs_view:guided.coeffs,gf_structure_view:guided.structure,haze_view:guided.haze,
            airlight_view:guided.airlight,width:self.width,height:self.height});
        self.next_id=self.next_id.checked_add(1).ok_or("Source identity overflow")?;
        Ok(())
    }

    /// MHC camera-linear -> RapidRAW's calibrated scene-linear working space.
    /// Matrix/WB derivation uses the unchanged upstream dual-illuminant routines.
    pub fn upload_camera(&mut self, words:&[u16],matrix_a:&[f32],matrix_d65:&[f32],neutral:&[f32]) -> Result<crate::white_balance::WhiteBalance,String> {
        self.calibrate_camera(Some(words),matrix_a,matrix_d65,neutral)
    }
    pub fn upload_bayer(&mut self, words:&[u16],channels:[i32;4],levels:[f32;4],white:f32,matrix_a:&[f32],matrix_d65:&[f32],neutral:&[f32]) -> Result<crate::white_balance::WhiteBalance,String> {
        let scope=self.context.device.push_error_scope(wgpu::ErrorFilter::Validation);
        if self.mhc.is_none() { self.mhc=Some(crate::mhc::MhcPipeline::new(&self.context,self.width,self.height)?); }
        if self.camera_source.is_none() {
            self.camera_source=Some(self.context.device.create_texture(&wgpu::TextureDescriptor {
                label:Some("MHC camera-linear source"),size:wgpu::Extent3d {width:self.width,height:self.height,depth_or_array_layers:1},
                mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,format:wgpu::TextureFormat::Rgba16Float,
                usage:wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::STORAGE_BINDING|wgpu::TextureUsages::COPY_DST|wgpu::TextureUsages::COPY_SRC,view_formats:&[] }));
        }
        self.mhc.as_ref().unwrap().run(&self.context,words,channels,levels,white,self.camera_source.as_ref().unwrap())?;
        let result=self.calibrate_camera(None,matrix_a,matrix_d65,neutral);
        if let Some(error)=pollster::block_on(scope.pop()) { return Err(error.to_string()); }
        result
    }
    fn calibrate_camera(&mut self, words:Option<&[u16]>,matrix_a:&[f32],matrix_d65:&[f32],neutral:&[f32]) -> Result<crate::white_balance::WhiteBalance,String> {
        use crate::white_balance::{WhiteBalance,camera_to_srgb};
        use wgpu::util::DeviceExt;
        if matrix_a.len()!=9||matrix_d65.len()!=9||neutral.len()!=3||
            matrix_a.iter().chain(matrix_d65).chain(neutral).any(|v|!v.is_finite())||neutral.iter().any(|v|*v<=0.0) {
            return Err("Invalid camera calibration".into());
        }
        let (wb,matrix)=WhiteBalance::from_dual_illuminant_camera_neutral(matrix_a,matrix_d65,neutral).ok_or("Cannot resolve camera white balance")?;
        let columns=camera_to_srgb(&matrix,neutral).ok_or("Cannot resolve camera-to-working-space matrix")?;
        if words.is_some_and(|v|v.len()!=self.width as usize*self.height as usize*4){return Err("Camera source length mismatch".into());}
        let device=&self.context.device;let queue=&self.context.queue;
        let make=|label:&'static str,usage|device.create_texture(&wgpu::TextureDescriptor{
            label:Some(label),size:wgpu::Extent3d{width:self.width,height:self.height,depth_or_array_layers:1},
            mip_level_count:1,sample_count:1,dimension:wgpu::TextureDimension::D2,format:wgpu::TextureFormat::Rgba16Float,usage,view_formats:&[]});
        let camera=self.camera_source.take().unwrap_or_else(||make("MHC camera source",wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::COPY_DST));
        self.resources.reset_source();
        if self.input.as_ref().is_some_and(|i|i.width!=self.width||i.height!=self.height) {self.processor=GpuProcessor::new(self.context.clone(),self.width,self.height)?;}
        let linear=self.input.take().filter(|entry|entry.width==self.width&&entry.height==self.height).map(|entry|entry.texture).unwrap_or_else(||make("RapidRAW calibrated source",wgpu::TextureUsages::TEXTURE_BINDING|wgpu::TextureUsages::STORAGE_BINDING|wgpu::TextureUsages::COPY_DST|wgpu::TextureUsages::COPY_SRC));
        if let Some(words)=words { queue.write_texture(camera.as_image_copy(),bytemuck::cast_slice(words),wgpu::TexelCopyBufferLayout{
            offset:0,bytes_per_row:Some(self.width*8),rows_per_image:Some(self.height)},
            wgpu::Extent3d{width:self.width,height:self.height,depth_or_array_layers:1}); }
        let mut packed=Vec::new();for column in columns {packed.extend(column);packed.push(0.0f32);}
        let params=device.create_buffer_init(&wgpu::util::BufferInitDescriptor{label:Some("Camera calibration"),contents:bytemuck::cast_slice(&packed),usage:wgpu::BufferUsages::UNIFORM});
        let pipeline=self.camera_pipeline.get_or_init(||{
            let shader=device.create_shader_module(wgpu::ShaderModuleDescriptor{label:Some("MHC working-space adapter"),source:wgpu::ShaderSource::Wgsl(r#"
struct Camera { matrix: mat3x3<f32> }
@group(0) @binding(0) var camera: texture_2d<f32>;
@group(0) @binding(1) var linear: texture_storage_2d<rgba16float,write>;
@group(0) @binding(2) var<uniform> calibration: Camera;
@compute @workgroup_size(8,8)
fn main(@builtin(global_invocation_id) id:vec3<u32>) {
    let dims=textureDimensions(linear);
    if(id.x>=dims.x||id.y>=dims.y){return;}
    let value=textureLoad(camera,id.xy,0);
    textureStore(linear,id.xy,vec4<f32>(calibration.matrix*value.rgb,value.a));
}
"#.into())});
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor{label:Some("MHC working-space adapter"),layout:None,module:&shader,entry_point:Some("main"),compilation_options:Default::default(),cache:None})
        });
        let camera_view=camera.create_view(&Default::default());let view=linear.create_view(&Default::default());
        let bindings=device.create_bind_group(&wgpu::BindGroupDescriptor{label:Some("MHC calibration bindings"),layout:&pipeline.get_bind_group_layout(0),entries:&[
            wgpu::BindGroupEntry{binding:0,resource:wgpu::BindingResource::TextureView(&camera_view)},
            wgpu::BindGroupEntry{binding:1,resource:wgpu::BindingResource::TextureView(&view)},
            wgpu::BindGroupEntry{binding:2,resource:params.as_entire_binding()}]});
        let mut encoder=device.create_command_encoder(&Default::default());
        {let mut pass=encoder.begin_compute_pass(&Default::default());pass.set_pipeline(pipeline);pass.set_bind_group(0,&bindings,&[]);pass.dispatch_workgroups(self.width.div_ceil(8),self.height.div_ceil(8),1);}
        queue.submit(Some(encoder.finish()));
        let guided=self.processor.build_guided_coeffs(&view,self.width,self.height,1);
        self.input=Some(GpuImageEntry{id:self.next_id,texture:linear,texture_view:view,gf_coeffs_view:guided.coeffs,
            gf_structure_view:guided.structure,haze_view:guided.haze,airlight_view:guided.airlight,width:self.width,height:self.height});
        self.camera_source=Some(camera);self.next_id=self.next_id.checked_add(1).ok_or("Source identity overflow")?;
        Ok(wb)
    }

    pub fn camera_words(&self)->Result<Vec<u16>,String> {
        let camera=self.camera_source.as_ref().ok_or("No camera frame")?;
        crate::renderer::read_rgba16(&self.context.device,&self.context.queue,camera,self.width,self.height)
    }
    pub fn render8(&self,parameters:AllAdjustments,masks:&[image::GrayImage],lut:Option<Arc<Lut>>)->Result<Vec<u8>,String> {
        if parameters.mask_count as usize!=masks.len() {return Err("Mask bitmaps must match packed parameters".into());}
        if parameters.global.has_lut!=0 && lut.is_none() {return Err("Selected LUT resource is missing".into());}
        if masks.iter().any(|m|m.width()!=self.output_size().0||m.height()!=self.output_size().1){return Err("Mask dimensions differ from source".into());}
        if let Some(lut)=&lut {
            let expected=(lut.size as usize).checked_pow(3).and_then(|v|v.checked_mul(3)).ok_or("LUT size overflow")?;
            if lut.size<2||lut.data.len()!=expected||lut.data.iter().any(|v|!v.is_finite()){return Err("Invalid LUT resource".into());}
        }
        let input=self.input.as_ref().ok_or("No source frame uploaded")?;
        let scope=self.context.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let result=self.processor.render_frame_gpu(input,RenderRequest{adjustments:parameters,mask_bitmaps:masks,
            mask_cache_key:None,lut,roi:None,hsl_probe:false}).and_then(|frame|
                crate::renderer::read_rgba8(&self.context.device,&self.context.queue,&frame.working_texture,self.width,self.height));
        if let Some(error)=pollster::block_on(scope.pop()) {return Err(error.to_string());}
        result
    }
    pub fn render_configured8(&self,parameters:AllAdjustments)->Result<Vec<u8>,String> { self.render8(parameters,&self.resources.masks,self.resources.lut.clone()) }
    pub fn render16_gpu_words(&self,parameters:AllAdjustments)->Result<Vec<u16>,String> {
        let input=self.input.as_ref().ok_or("No source frame")?;
        let frame=self.processor.render_frame16_gpu(input,RenderRequest {adjustments:parameters,mask_bitmaps:&self.resources.masks,
            mask_cache_key:None,lut:self.resources.lut.clone(),roi:None,hsl_probe:false})?;
        crate::renderer::read_rgba16(&self.context.device,&self.context.queue,&frame.working_texture,self.width,self.height)
    }
    pub fn render_p010(&self,parameters:AllAdjustments,width:u32,height:u32)->Result<Vec<u8>,String> {
        let scope=self.context.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let input=self.input.as_ref().ok_or("No source frame")?;
        let frame=self.processor.render_frame16_gpu(input,RenderRequest {adjustments:parameters,mask_bitmaps:&self.resources.masks,
            mask_cache_key:None,lut:self.resources.lut.clone(),roi:None,hsl_probe:false})?;
        let mut delivery=self.delivery.lock().map_err(|e|e.to_string())?;
        if delivery.as_ref().is_none_or(|d|d.width!=width||d.height!=height) { *delivery=Some(crate::delivery::P010Delivery::new(&self.context,self.width,self.height,width,height)?); }
        let view=frame.working_texture.create_view(&Default::default());
        let result=delivery.as_ref().unwrap().run(&self.context,&view);
        if let Some(error)=pollster::block_on(scope.pop()) {return Err(error.to_string());}
        result
    }
    pub fn render16(&self, parameters: AllAdjustments) -> Result<Vec<u16>, String> {
        if parameters.mask_count as usize!=self.resources.masks.len() || (parameters.global.has_lut!=0 && self.resources.lut.is_none()) {
            return Err("Export resource bindings are missing".into());
        }
        let input = self.input.as_ref().ok_or("No source frame uploaded")?;
        let scope = self.context.device.push_error_scope(wgpu::ErrorFilter::Validation);
        let result = self.processor.render_frame16(input, RenderRequest { adjustments: parameters,
            mask_bitmaps: &self.resources.masks, mask_cache_key: None, lut: self.resources.lut.clone(), roi: None, hsl_probe: false });
        if let Some(error) = pollster::block_on(scope.pop()) { return Err(error.to_string()); }
        result
    }

}

thread_local! {static LAST_ERROR:std::cell::RefCell<String>=const {std::cell::RefCell::new(String::new())};}
fn fail(error:String)->i32 {LAST_ERROR.with(|slot|*slot.borrow_mut()=error);-1}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_last_error(output:*mut u8,capacity:usize)->usize {
    LAST_ERROR.with(|slot|{let error=slot.borrow();let length=error.len().min(capacity);
        if !output.is_null(){unsafe{std::ptr::copy_nonoverlapping(error.as_ptr(),output,length);}}
        length})
}
#[unsafe(no_mangle)]
pub extern "C" fn crs_rapidraw_renderer_new(width:u32,height:u32)->*mut RapidRenderer {
    match RapidRenderer::new(width,height) {Ok(value)=>Box::into_raw(Box::new(value)),Err(error)=>{fail(error);std::ptr::null_mut()}}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_renderer_free(renderer:*mut RapidRenderer) {
    if !renderer.is_null(){drop(unsafe{Box::from_raw(renderer)});}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_upload(renderer:*mut RapidRenderer,words:*const u16,length:usize,is_raw:u32)->i32 {
    if renderer.is_null()||words.is_null()||(words as usize)%2!=0{return fail("Invalid source buffer".into());}
    match unsafe{&mut *renderer}.upload(unsafe{std::slice::from_raw_parts(words,length)},is_raw) {Ok(())=>0,Err(error)=>fail(error)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_upload_camera(renderer:*mut RapidRenderer,words:*const u16,length:usize,a:*const f32,d65:*const f32,neutral:*const f32,white_balance:*mut f64)->i32 {
    if renderer.is_null()||words.is_null()||a.is_null()||d65.is_null()||neutral.is_null()||white_balance.is_null() {return fail("Invalid calibration buffer".into());}
    let result=unsafe{&mut *renderer}.upload_camera(unsafe{std::slice::from_raw_parts(words,length)},
        unsafe{std::slice::from_raw_parts(a,9)},unsafe{std::slice::from_raw_parts(d65,9)},unsafe{std::slice::from_raw_parts(neutral,3)});
    match result {Ok(wb)=>{unsafe{*white_balance=wb.temperature;*white_balance.add(1)=wb.tint;}0},Err(error)=>fail(error)}
}
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_render8(renderer:*const RapidRenderer,parameters:*const u8,length:usize,output:*mut u8,capacity:usize)->i32 {
    if renderer.is_null()||parameters.is_null()||output.is_null()||length!=std::mem::size_of::<AllAdjustments>() {
        return fail("Invalid render buffers".into());
    }
    let renderer=unsafe{&*renderer};
    if capacity<renderer.output_size().0 as usize*renderer.output_size().1 as usize*4{return fail("Output buffer too small".into());}
    let packed=bytemuck::pod_read_unaligned(unsafe{std::slice::from_raw_parts(parameters,length)});
    match renderer.render8(packed,&renderer.resources.masks,renderer.resources.lut.clone()) {
        Ok(bytes)=>{unsafe{std::ptr::copy_nonoverlapping(bytes.as_ptr(),output,bytes.len());}0},
        Err(error)=>fail(error)
    }
}

/// RGBA16F output from the upstream high-precision pipeline, never RGBA8.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_render16(renderer: *const RapidRenderer, parameters: *const u8,
    length: usize, output: *mut u16, capacity: usize) -> i32 {
    if renderer.is_null() || parameters.is_null() || output.is_null() || (output as usize)%2 != 0
        || length != std::mem::size_of::<AllAdjustments>() { return fail("Invalid export buffers".into()); }
    let renderer = unsafe { &*renderer };
    if capacity < renderer.output_size().0 as usize * renderer.output_size().1 as usize * 4 { return fail("Export buffer too small".into()); }
    let packed = bytemuck::pod_read_unaligned(unsafe { std::slice::from_raw_parts(parameters,length) });
    match renderer.render16(packed) {
        Ok(words) => { unsafe { std::ptr::copy_nonoverlapping(words.as_ptr(),output,words.len()); } 0 },
        Err(error) => fail(error)
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_upload_bayer(renderer:*mut RapidRenderer,words:*const u16,length:usize,channels:*const i32,levels:*const f32,white:f32,a:*const f32,d65:*const f32,neutral:*const f32,white_balance:*mut f64)->i32 {
    if renderer.is_null()||words.is_null()||channels.is_null()||levels.is_null()||a.is_null()||d65.is_null()||neutral.is_null()||white_balance.is_null() {return fail("Invalid MHC input buffer".into());}
    let result=unsafe{&mut *renderer}.upload_bayer(unsafe{std::slice::from_raw_parts(words,length)},unsafe{std::ptr::read_unaligned(channels.cast::<[i32;4]>())},unsafe{std::ptr::read_unaligned(levels.cast::<[f32;4]>())},white,
        unsafe{std::slice::from_raw_parts(a,9)},unsafe{std::slice::from_raw_parts(d65,9)},unsafe{std::slice::from_raw_parts(neutral,3)});
    match result {Ok(wb)=>{unsafe{*white_balance=wb.temperature;*white_balance.add(1)=wb.tint;}0},Err(error)=>fail(error)}
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_configure(renderer:*mut RapidRenderer,json:*const u8,length:usize,scale:f32,is_raw:u32,dimensions:*mut u32)->i32 {
    if renderer.is_null()||json.is_null()||dimensions.is_null(){return fail("Invalid resource configuration buffers".into());}
    let Ok(text)=std::str::from_utf8(unsafe{std::slice::from_raw_parts(json,length)}) else{return fail("Invalid resource UTF-8".into());};
    let renderer=unsafe{&mut *renderer};
    match renderer.configure(text,scale,is_raw!=0) {Ok(())=>{let(w,h)=renderer.output_size();unsafe{*dimensions=w;*dimensions.add(1)=h;}0},Err(error)=>fail(error)}
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_render_p010(renderer:*const RapidRenderer,parameters:*const u8,length:usize,width:u32,height:u32,output:*mut u8,capacity:usize)->i32 {
    if renderer.is_null()||parameters.is_null()||output.is_null()||length!=std::mem::size_of::<AllAdjustments>() {return fail("Invalid P010 render buffers".into());}
    let Some(required)=(width as usize).checked_mul(height as usize).and_then(|n|n.checked_mul(3)) else {return fail("P010 output size overflow".into());};
    if capacity<required {return fail("P010 buffer too small".into());}
    let parameters=bytemuck::pod_read_unaligned(unsafe{std::slice::from_raw_parts(parameters,length)});
    match unsafe{&*renderer}.render_p010(parameters,width,height) {Ok(bytes)=>{unsafe{std::ptr::copy_nonoverlapping(bytes.as_ptr(),output,bytes.len());}0},Err(error)=>fail(error)}
}
