// SPDX-License-Identifier: AGPL-3.0-or-later
// GPU encoder-delivery adapter; no image-grading arithmetic here.
use crate::render::GpuContext;
use wgpu::util::DeviceExt;
pub struct P010Delivery {
    pub width:u32,pub height:u32,
    pipeline:wgpu::ComputePipeline,output:wgpu::Buffer,readback:wgpu::Buffer,params:wgpu::Buffer,
}
impl P010Delivery {
    pub fn new(context:&GpuContext,source_width:u32,source_height:u32,width:u32,height:u32)->Result<Self,String> {
        if width<2||height<2||width%2!=0||height%2!=0{return Err("P010 requires even output dimensions".into());}
        let size=u64::from(width).checked_mul(u64::from(height)).and_then(|n|n.checked_mul(3)).ok_or("P010 size overflow")?;
        if size>context.limits.max_buffer_size||size>u64::from(context.limits.max_storage_buffer_binding_size){return Err("P010 output exceeds GPU limits".into());}
        let device=&context.device;
        let shader=device.create_shader_module(wgpu::ShaderModuleDescriptor {label:Some("BT.709 P010 delivery"),source:wgpu::ShaderSource::Wgsl(include_str!("p010.wgsl").into())});
        let pipeline=device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {label:Some("RapidRAW P010 delivery"),layout:None,module:&shader,entry_point:Some("main"),compilation_options:Default::default(),cache:None});
        let output=device.create_buffer(&wgpu::BufferDescriptor {label:Some("GPU P010 planes"),size,usage:wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_SRC,mapped_at_creation:false});
        let readback=device.create_buffer(&wgpu::BufferDescriptor {label:Some("Pooled P010 readback"),size,usage:wgpu::BufferUsages::COPY_DST|wgpu::BufferUsages::MAP_READ,mapped_at_creation:false});
        let params=device.create_buffer_init(&wgpu::util::BufferInitDescriptor {label:Some("P010 fit dimensions"),contents:bytemuck::cast_slice(&[source_width,source_height,width,height]),usage:wgpu::BufferUsages::UNIFORM});
        Ok(Self {width,height,pipeline,output,readback,params})
    }
    pub fn run(&self,context:&GpuContext,graded:&wgpu::TextureView)->Result<Vec<u8>,String> {
        let bindings=context.device.create_bind_group(&wgpu::BindGroupDescriptor {label:Some("P010 delivery bindings"),layout:&self.pipeline.get_bind_group_layout(0),entries:&[
            wgpu::BindGroupEntry {binding:0,resource:wgpu::BindingResource::TextureView(graded)},
            wgpu::BindGroupEntry {binding:1,resource:self.output.as_entire_binding()},
            wgpu::BindGroupEntry {binding:2,resource:self.params.as_entire_binding()}]});
        let mut encoder=context.device.create_command_encoder(&Default::default());
        {let mut pass=encoder.begin_compute_pass(&Default::default());pass.set_pipeline(&self.pipeline);pass.set_bind_group(0,&bindings,&[]);pass.dispatch_workgroups((self.width/2).div_ceil(8),(self.height/2).div_ceil(8),1);}
        encoder.copy_buffer_to_buffer(&self.output,0,&self.readback,0,self.output.size());context.queue.submit(Some(encoder.finish()));
        let (tx,rx)=std::sync::mpsc::channel();self.readback.slice(..).map_async(wgpu::MapMode::Read,move |r|{let _=tx.send(r);});
        context.device.poll(wgpu::PollType::Wait {submission_index:None,timeout:Some(std::time::Duration::from_secs(60))}).map_err(|e|e.to_string())?;
        rx.recv().map_err(|e|e.to_string())?.map_err(|e|e.to_string())?;
        let bytes=self.readback.slice(..).get_mapped_range().to_vec();self.readback.unmap();Ok(bytes)
    }
}
