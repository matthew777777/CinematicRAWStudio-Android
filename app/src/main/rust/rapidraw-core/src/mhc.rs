// SPDX-License-Identifier: GPL-3.0-or-later
// Dispatch adapter only. Arithmetic is the exact pinned RawLens GLSL in assets/mhc.
use crate::render::GpuContext;

pub struct MhcPipeline {
    green: wgpu::ComputePipeline,
    chroma: wgpu::ComputePipeline,
    codes: wgpu::Buffer,
    params: wgpu::Buffer,
    greens: wgpu::Buffer,
    width: u32,
    height: u32,
}
impl MhcPipeline {
    pub fn new(context: &GpuContext,width: u32,height: u32) -> Result<Self,String> {
        if width<8 || height<8 || width%2!=0 || height%2!=0 { return Err("MHC requires even dimensions of at least 8 pixels".into()); }
        let device=&context.device;
        let make_pipeline=|name,bytes: &'static [u8]| {
            let module=device.create_shader_module(wgpu::ShaderModuleDescriptor { label:Some(name),source:wgpu::util::make_spirv(bytes) });
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor { label:Some(name),layout:None,module:&module,
                entry_point:Some("main"),compilation_options:Default::default(),cache:None })
        };
        let green=make_pipeline("Exact RawLens coherent green",include_bytes!("../../../assets/mhc/green.spv"));
        let chroma=make_pipeline("Exact RawLens coherent chroma",include_bytes!("../../../assets/mhc/camera.spv"));
        let buffer=|label,size,usage|device.create_buffer(&wgpu::BufferDescriptor { label:Some(label),size,usage,mapped_at_creation:false });
        let codes=buffer("MHC packed sensor samples",u64::from(width)*u64::from(height)*2,wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_DST);
        let params=buffer("RawLens exact FusedParams layout",192,wgpu::BufferUsages::STORAGE|wgpu::BufferUsages::COPY_DST);
        let greens=buffer("RawLens coherent binary16 green pairs",u64::from(width.div_ceil(2))*u64::from(height)*4,wgpu::BufferUsages::STORAGE);
        Ok(Self {green,chroma,codes,params,greens,width,height})
    }
    pub fn run(&self,context: &GpuContext,words: &[u16],channels: [i32;4],levels: [f32;4],white: f32,output: &wgpu::Texture) -> Result<(),String> {
        if words.len()!=self.width as usize*self.height as usize || channels.iter().copied().collect::<std::collections::BTreeSet<_>>()!=[0,1,2,3].into_iter().collect()
            || levels.iter().any(|v|!v.is_finite()) || !white.is_finite() { return Err("Invalid MHC source parameters".into()); }
        let mut params=[0u32;48];
        params[0]=self.width;params[1]=self.height;
        for i in 0..4 { params[4+i]=channels[i] as u32;
            // Exact VfMhc.packMhc ordering and f32 normalization arithmetic.
            let inv=1.0f32/(white-levels[i]).max(1.0);
            params[8+i]=(-levels[i]*inv).to_bits();params[12+i]=inv.to_bits();
        }
        params[16]=self.width;params[18]=self.width;params[19]=self.height;
        context.queue.write_buffer(&self.codes,0,bytemuck::cast_slice(words));
        context.queue.write_buffer(&self.params,0,bytemuck::cast_slice(&params));
        let device=&context.device;
        let group=|pipeline: &wgpu::ComputePipeline,entries: &[wgpu::BindGroupEntry<'_>]| device.create_bind_group(&wgpu::BindGroupDescriptor {
            label:Some("MHC pass resources"),layout:&pipeline.get_bind_group_layout(0),entries });
        let green=group(&self.green,&[
            wgpu::BindGroupEntry { binding:0,resource:self.codes.as_entire_binding() },
            wgpu::BindGroupEntry { binding:2,resource:self.params.as_entire_binding() },
            wgpu::BindGroupEntry { binding:5,resource:self.greens.as_entire_binding() }]);
        let view=output.create_view(&Default::default());
        let chroma=group(&self.chroma,&[
            wgpu::BindGroupEntry { binding:0,resource:self.codes.as_entire_binding() },
            wgpu::BindGroupEntry { binding:1,resource:wgpu::BindingResource::TextureView(&view) },
            wgpu::BindGroupEntry { binding:2,resource:self.params.as_entire_binding() },
            wgpu::BindGroupEntry { binding:5,resource:self.greens.as_entire_binding() }]);
        let mut encoder=device.create_command_encoder(&Default::default());
        { let mut pass=encoder.begin_compute_pass(&Default::default());pass.set_pipeline(&self.green);pass.set_bind_group(0,&green,&[]);
            pass.dispatch_workgroups(self.width.div_ceil(2).div_ceil(16),self.height.div_ceil(16),1); }
        // Separate passes make wgpu insert the storage-write/read dependency.
        { let mut pass=encoder.begin_compute_pass(&Default::default());pass.set_pipeline(&self.chroma);pass.set_bind_group(0,&chroma,&[]);
            pass.dispatch_workgroups(self.width.div_ceil(16),self.height.div_ceil(16),1); }
        context.queue.submit(Some(encoder.finish()));
        Ok(())
    }
}
