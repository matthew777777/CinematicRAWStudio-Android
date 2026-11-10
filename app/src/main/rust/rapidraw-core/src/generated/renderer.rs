// Generated from pinned RapidRAW gpu_processing.rs; AGPL-3.0-or-later.
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use half::f16;
use image::{ImageBuffer, Luma};
use wgpu::util::{DeviceExt, TextureDataOrder};
use crate::image_processing::{AllAdjustments, MAX_MASKS, MaskAdjustments};
use crate::render::{GpuContext, GpuImageEntry};
use crate::render::Lut;
#[derive(Clone, Copy, Debug)]
pub struct Roi {
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum RenderOutputPrecision {
    #[default]
    EightBit,
    SixteenBit,
}

pub struct RenderRequest<'a> {
    pub adjustments: AllAdjustments,
    pub mask_bitmaps: &'a [ImageBuffer<Luma<u8>, Vec<u8>>],
    pub mask_cache_key: Option<u64>,
    pub lut: Option<Arc<Lut>>,
    pub roi: Option<Roi>,
    pub hsl_probe: bool,
}


fn read_texture_data_roi(
    device: &wgpu::Device,
    queue: &wgpu::Queue,
    texture: &wgpu::Texture,
    origin: wgpu::Origin3d,
    size: wgpu::Extent3d,
    bytes_per_pixel: u32,
) -> Result<Vec<u8>, String> {
    let unpadded_bytes_per_row = bytes_per_pixel * size.width;
    let align = wgpu::COPY_BYTES_PER_ROW_ALIGNMENT;
    let padded_bytes_per_row = (unpadded_bytes_per_row + align - 1) & !(align - 1);
    let output_buffer_size = (padded_bytes_per_row * size.height) as u64;

    let output_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("Readback Buffer"),
        size: output_buffer_size,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });

    let mut encoder =
        device.create_command_encoder(&wgpu::CommandEncoderDescriptor { label: None });
    encoder.copy_texture_to_buffer(
        wgpu::TexelCopyTextureInfo {
            texture,
            mip_level: 0,
            origin,
            aspect: wgpu::TextureAspect::All,
        },
        wgpu::TexelCopyBufferInfo {
            buffer: &output_buffer,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(padded_bytes_per_row),
                rows_per_image: Some(size.height),
            },
        },
        size,
    );

    queue.submit(Some(encoder.finish()));
    let buffer_slice = output_buffer.slice(..);
    let (tx, rx) = std::sync::mpsc::channel();
    buffer_slice.map_async(wgpu::MapMode::Read, move |result| {
        let _ = tx.send(result);
    });
    device
        .poll(wgpu::PollType::Wait {
            submission_index: None,
            timeout: Some(std::time::Duration::from_secs(60)),
        })
        .map_err(|e| format!("Failed while polling mapped GPU buffer: {}", e))?;
    let map_result = rx
        .recv()
        .map_err(|e| format!("Failed receiving GPU map result: {}", e))?;
    map_result.map_err(|e| e.to_string())?;

    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mapped = buffer_slice.get_mapped_range();
        let data = if padded_bytes_per_row == unpadded_bytes_per_row {
            mapped.to_vec()
        } else {
            let mut unpadded_data =
                Vec::with_capacity((unpadded_bytes_per_row * size.height) as usize);
            for chunk in mapped.chunks(padded_bytes_per_row as usize) {
                unpadded_data.extend_from_slice(&chunk[..unpadded_bytes_per_row as usize]);
            }
            unpadded_data
        };
        drop(mapped);
        output_buffer.unmap();
        data
    }))
    .map_err(|_| "GPU device was lost while reading back the image".to_string())
}


#[repr(C)]
#[derive(Debug, Copy, Clone, PartialEq, bytemuck::Pod, bytemuck::Zeroable)]
struct BlurParams {
    radius: u32,
    tile_offset_x: u32,
    tile_offset_y: u32,
    input_width: u32,
    input_height: u32,
    _pad1: u32,
    _pad2: u32,
    _pad3: u32,
}

static NEXT_GPU_IMAGE_ID: AtomicU64 = AtomicU64::new(1);

const BLUR_H_GROUP_WIDTH: u32 = 256;
const BLUR_V_GROUP_SIZE: (u32, u32) = (8, 64);
const DIRECT_BLUR_MAX_RADIUS: u32 = 8;

const GF_SHORT_EDGE: u32 = 1080;
const GF_CLARITY_RADIUS: f32 = 8.0;
const GF_STRUCTURE_RADIUS: f32 = 40.0;
const GF_CLARITY_EPS: f32 = 0.25;
const GF_STRUCTURE_EPS: f32 = 0.5;
const GF_COARSE_DIV: u32 = 4;
const GF_TONAL_RADIUS: f32 = 10.0;
const GF_TONAL_EPS: f32 = 0.25;
const HZ_DIV: u32 = 2;
const HZ_PATCH_RADIUS: f32 = 12.0;
const HZ_GF_RADIUS: f32 = 48.0;
const HZ_GF_EPS: f32 = 1.0e-3;

pub struct GuidedTextures {
    pub coeffs: wgpu::TextureView,
    pub structure: wgpu::TextureView,
    pub haze: wgpu::TextureView,
    pub airlight: wgpu::TextureView,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
struct GfParams {
    radius: u32,
    is_raw: u32,
    eps: f32,
    _pad: f32,
}

#[repr(C)]
#[derive(Debug, Copy, Clone, bytemuck::Pod, bytemuck::Zeroable)]
struct FlareParams {
    amount: f32,
    is_raw: u32,
    exposure: f32,
    brightness: f32,
    contrast: f32,
    whites: f32,
    aspect_ratio: f32,
    _pad: f32,
}

pub struct GpuProcessor {
    context: GpuContext,
    pipelines: Arc<GpuPipelines>,
    blur_params_buffer: wgpu::Buffer,

    flare_params_buffer: wgpu::Buffer,
    flare_threshold_view: wgpu::TextureView,
    flare_ghosts_view: wgpu::TextureView,
    flare_final_view: wgpu::TextureView,

    high_precision_tile: std::sync::OnceLock<HighPrecisionTile>,
    tile_output_size: wgpu::Extent3d,
    adjustments_buffer: wgpu::Buffer,
    mask_cache: std::sync::Mutex<Option<MaskCache>>,
    lut_cache: std::sync::Mutex<Option<LutCache>>,
    ping_pong_view: wgpu::TextureView,
    sharpness_blur_view: wgpu::TextureView,
    tonal_blur_view: wgpu::TextureView,
    clarity_blur_view: wgpu::TextureView,
    structure_blur_view: wgpu::TextureView,
    blur_sources: std::sync::Mutex<[Option<(u64, BlurParams)>; 4]>,

    pub tile_output_texture: wgpu::Texture,
    pub tile_output_texture_view: wgpu::TextureView,
    full_image_size: wgpu::Extent3d,
    display_textures: std::sync::OnceLock<DisplayTextures>,
    export_textures: std::sync::OnceLock<DisplayTextures>,
    readback_pool: ReadbackPool,
}

type ReadbackPool = Arc<std::sync::Mutex<Vec<wgpu::Buffer>>>;

const READBACK_POOL_SIZE: usize = 3;

fn recycle_readback_buffer(pool: &ReadbackPool, buffer: wgpu::Buffer) {
    let mut pool = pool.lock().unwrap_or_else(|e| e.into_inner());
    if pool.len() >= READBACK_POOL_SIZE {
        pool.remove(0);
    }
    pool.push(buffer);
}

pub struct PooledReadback {
    view: Option<wgpu::BufferView>,
    buffer: wgpu::Buffer,
    pool: ReadbackPool,
}

impl std::ops::Deref for PooledReadback {
    type Target = [u8];

    fn deref(&self) -> &[u8] {
        self.view.as_deref().unwrap_or(&[])
    }
}

impl Drop for PooledReadback {
    fn drop(&mut self) {
        drop(self.view.take());
        self.buffer.unmap();
        recycle_readback_buffer(&self.pool, self.buffer.clone());
    }
}

pub struct DisplayTextures {
    pub working_texture: wgpu::Texture,
    pub output_texture: wgpu::Texture,
    pub output_texture_view: wgpu::TextureView,
}

struct HighPrecisionPipeline {
    bgl: wgpu::BindGroupLayout,
    pipeline: wgpu::ComputePipeline,
    shader: wgpu::ShaderModule,
    layout: wgpu::PipelineLayout,
}

type SpecializedPipelines =
    Arc<std::sync::Mutex<HashMap<(bool, u32), Option<wgpu::ComputePipeline>>>>;

const ALL_SHADER_FEATURES: u32 = u32::MAX;
const F_NOISE_REDUCTION: u32 = 1;
const F_HSL: u32 = 2;
const F_MASKS: u32 = 4;
const F_SHARPEN: u32 = 8;
const F_DEHAZE: u32 = 16;
const F_GLOW_HALATION_FLARE: u32 = 32;
const F_GRAIN: u32 = 64;
const F_VIGNETTE: u32 = 128;
const F_LUT: u32 = 256;
const F_CA: u32 = 512;
const F_AGX: u32 = 1024;
const F_MONOCHROME: u32 = 2048;

fn shader_features(adjustments: &AllAdjustments) -> u32 {
    let g = &adjustments.global;
    let masks = &adjustments.mask_adjustments[..(adjustments.mask_count as usize).min(MAX_MASKS)];
    let any = |global: f32, pick: fn(&MaskAdjustments) -> f32| {
        global != 0.0 || masks.iter().any(|m| pick(m) != 0.0)
    };
    let hsl_nonzero = |hsl: &[crate::image_processing::HslColor; 8]| {
        bytemuck::cast_slice::<_, f32>(hsl)
            .iter()
            .any(|&v| v != 0.0)
    };

    let mut features = 0;
    let mut set = |flag: u32, on: bool| {
        if on {
            features |= flag;
        }
    };
    set(
        F_NOISE_REDUCTION,
        any(g.luma_noise_reduction, |m| m.luma_noise_reduction)
            || any(g.color_noise_reduction, |m| m.color_noise_reduction),
    );
    set(
        F_HSL,
        hsl_nonzero(&g.hsl) || masks.iter().any(|m| hsl_nonzero(&m.hsl)),
    );
    set(F_MASKS, adjustments.mask_count > 0);
    set(F_SHARPEN, any(g.sharpness, |m| m.sharpness));
    set(F_DEHAZE, any(g.dehaze, |m| m.dehaze));
    set(
        F_GLOW_HALATION_FLARE,
        any(g.glow_amount, |m| m.glow_amount)
            || any(g.halation_amount, |m| m.halation_amount)
            || any(g.flare_amount, |m| m.flare_amount),
    );
    set(F_GRAIN, g.grain_amount > 0.0);
    set(F_VIGNETTE, g.vignette_amount != 0.0);
    set(F_LUT, g.has_lut == 1);
    set(
        F_CA,
        g.chromatic_aberration_red_cyan.abs() > 0.000001
            || g.chromatic_aberration_blue_yellow.abs() > 0.000001,
    );
    set(F_AGX, g.tonemapper_mode == 1);
    set(F_MONOCHROME, g.monochrome.enabled == 1);
    features
}

struct HighPrecisionTile {
    texture: wgpu::Texture,
    view: wgpu::TextureView,
}

struct MaskCache {
    width: u32,
    height: u32,
    layers: u32,
    key: u64,
    texture: wgpu::Texture,
    view: wgpu::TextureView,
}

struct LutCache {
    lut: Arc<Lut>,
    view: wgpu::TextureView,
    sampler: wgpu::Sampler,
}

enum RenderedPixels {
    U8(Vec<u8>),
    U16(Vec<u16>),
}

fn high_precision_shader_source() -> String {
    include_str!("shaders/shader.wgsl").replace("rgba8unorm, write>", "rgba16float, write>")
}

const FLARE_MAP_SIZE: u32 = 512;
const GPU_IMAGE_CACHE_ENTRIES: usize = 3;
const GPU_IMAGE_CACHE_PIXELS: u64 = 50_000_000;

fn lock_or_reset<'a, T>(
    mutex: &'a std::sync::Mutex<Option<T>>,
    name: &str,
) -> std::sync::MutexGuard<'a, Option<T>> {
    mutex.lock().unwrap_or_else(|poisoned| {
        log::warn!("{} lock was poisoned. Resetting to self-heal.", name);
        let mut guard = poisoned.into_inner();
        *guard = None;
        guard
    })
}

pub struct GpuPipelines {
    blur_bgl: wgpu::BindGroupLayout,
    h_blur_pipeline: wgpu::ComputePipeline,
    v_blur_pipeline: wgpu::ComputePipeline,
    h_blur_direct_pipeline: wgpu::ComputePipeline,
    v_blur_direct_pipeline: wgpu::ComputePipeline,

    flare_bgl_0: wgpu::BindGroupLayout,
    flare_bgl_1: wgpu::BindGroupLayout,
    flare_threshold_pipeline: wgpu::ComputePipeline,
    flare_ghosts_pipeline: wgpu::ComputePipeline,
    flare_sampler: wgpu::Sampler,

    gf_bgl: wgpu::BindGroupLayout,
    gf_downsample_pipeline: wgpu::ComputePipeline,
    gf_box_down_pipeline: wgpu::ComputePipeline,
    gf_blur_h_pipeline: wgpu::ComputePipeline,
    gf_blur_v_pipeline: wgpu::ComputePipeline,
    gf_coeffs_pipeline: wgpu::ComputePipeline,
    gf_pack_pipeline: wgpu::ComputePipeline,
    hz_downsample_pipeline: wgpu::ComputePipeline,
    hz_airlight_pipeline: wgpu::ComputePipeline,
    hz_min_h_pipeline: wgpu::ComputePipeline,
    hz_min_v_pipeline: wgpu::ComputePipeline,
    hz_max_h_pipeline: wgpu::ComputePipeline,
    hz_max_v_pipeline: wgpu::ComputePipeline,
    hz_coeffs_pipeline: wgpu::ComputePipeline,

    main_bgl: wgpu::BindGroupLayout,
    main_pipeline: wgpu::ComputePipeline,
    main_shader_module: wgpu::ShaderModule,
    main_pipeline_layout: wgpu::PipelineLayout,
    specialized_pipelines: SpecializedPipelines,
    high_precision_entries: Vec<wgpu::BindGroupLayoutEntry>,
    high_precision_pipeline: std::sync::OnceLock<HighPrecisionPipeline>,
    hsl_probe_pipeline: std::sync::OnceLock<wgpu::ComputePipeline>,
    dummy_blur_view: wgpu::TextureView,
    dummy_lut_view: wgpu::TextureView,
    dummy_lut_sampler: wgpu::Sampler,
    dummy_mask_view: wgpu::TextureView,
}

impl GpuPipelines {
    fn new(device: &wgpu::Device) -> Self {
        const MAX_MASK_BINDINGS: u32 = 1;

        let blur_shader_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Blur Shader"),
            source: wgpu::ShaderSource::Wgsl(include_str!("shaders/blur.wgsl").into()),
        });

        let blur_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Blur BGL"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: false },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::StorageTexture {
                        access: wgpu::StorageTextureAccess::WriteOnly,
                        format: wgpu::TextureFormat::Rgba16Float,
                        view_dimension: wgpu::TextureViewDimension::D2,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });

        let blur_pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Blur Pipeline Layout"),
            bind_group_layouts: &[Some(&blur_bgl)],
            immediate_size: 0,
        });

        let h_blur_pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Horizontal Blur Pipeline"),
            layout: Some(&blur_pipeline_layout),
            module: &blur_shader_module,
            entry_point: Some("horizontal_blur"),
            compilation_options: Default::default(),
            cache: None,
        });

        let v_blur_pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Vertical Blur Pipeline"),
            layout: Some(&blur_pipeline_layout),
            module: &blur_shader_module,
            entry_point: Some("vertical_blur"),
            compilation_options: Default::default(),
            cache: None,
        });

        let h_blur_direct_pipeline =
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("Horizontal Direct Blur Pipeline"),
                layout: Some(&blur_pipeline_layout),
                module: &blur_shader_module,
                entry_point: Some("horizontal_blur_direct"),
                compilation_options: Default::default(),
                cache: None,
            });

        let v_blur_direct_pipeline =
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("Vertical Direct Blur Pipeline"),
                layout: Some(&blur_pipeline_layout),
                module: &blur_shader_module,
                entry_point: Some("vertical_blur_direct"),
                compilation_options: Default::default(),
                cache: None,
            });

        let flare_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Flare Shader"),
            source: wgpu::ShaderSource::Wgsl(include_str!("shaders/flare.wgsl").into()),
        });

        let flare_bgl_0 = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Flare BGL 0"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::StorageTexture {
                        access: wgpu::StorageTextureAccess::WriteOnly,
                        format: wgpu::TextureFormat::Rgba16Float,
                        view_dimension: wgpu::TextureViewDimension::D2,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 3,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                    count: None,
                },
            ],
        });

        let flare_bgl_1 = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Flare BGL 1"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: false },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 1,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::StorageTexture {
                        access: wgpu::StorageTextureAccess::WriteOnly,
                        format: wgpu::TextureFormat::Rgba16Float,
                        view_dimension: wgpu::TextureViewDimension::D2,
                    },
                    count: None,
                },
            ],
        });

        let flare_threshold_layout =
            device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("Flare Threshold Layout"),
                bind_group_layouts: &[Some(&flare_bgl_0)],
                immediate_size: 0,
            });

        let flare_ghosts_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Flare Ghosts Layout"),
            bind_group_layouts: &[Some(&flare_bgl_0), Some(&flare_bgl_1)],
            immediate_size: 0,
        });

        let flare_threshold_pipeline =
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("Flare Threshold Pipeline"),
                layout: Some(&flare_threshold_layout),
                module: &flare_shader,
                entry_point: Some("threshold_main"),
                compilation_options: Default::default(),
                cache: None,
            });

        let flare_ghosts_pipeline =
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("Flare Ghosts Pipeline"),
                layout: Some(&flare_ghosts_layout),
                module: &flare_shader,
                entry_point: Some("ghosts_main"),
                compilation_options: Default::default(),
                cache: None,
            });

        let flare_sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            label: Some("Flare Sampler"),
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });

        let gf_shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Guided Filter Shader"),
            source: wgpu::ShaderSource::Wgsl(include_str!("shaders/guided.wgsl").into()),
        });

        let gf_texture_entry = |binding: u32| wgpu::BindGroupLayoutEntry {
            binding,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        };

        let gf_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Guided Filter BGL"),
            entries: &[
                gf_texture_entry(0),
                gf_texture_entry(1),
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::StorageTexture {
                        access: wgpu::StorageTextureAccess::WriteOnly,
                        format: wgpu::TextureFormat::Rgba32Float,
                        view_dimension: wgpu::TextureViewDimension::D2,
                    },
                    count: None,
                },
                wgpu::BindGroupLayoutEntry {
                    binding: 3,
                    visibility: wgpu::ShaderStages::COMPUTE,
                    ty: wgpu::BindingType::Buffer {
                        ty: wgpu::BufferBindingType::Uniform,
                        has_dynamic_offset: false,
                        min_binding_size: None,
                    },
                    count: None,
                },
            ],
        });

        let gf_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Guided Filter Pipeline Layout"),
            bind_group_layouts: &[Some(&gf_bgl)],
            immediate_size: 0,
        });

        let gf_pipeline = |entry: &'static str| {
            device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some(entry),
                layout: Some(&gf_layout),
                module: &gf_shader,
                entry_point: Some(entry),
                compilation_options: Default::default(),
                cache: None,
            })
        };

        let gf_downsample_pipeline = gf_pipeline("gf_downsample");
        let gf_box_down_pipeline = gf_pipeline("gf_box_down");
        let gf_blur_h_pipeline = gf_pipeline("gf_blur_h");
        let gf_blur_v_pipeline = gf_pipeline("gf_blur_v");
        let gf_coeffs_pipeline = gf_pipeline("gf_coeffs");
        let gf_pack_pipeline = gf_pipeline("gf_pack");
        let hz_downsample_pipeline = gf_pipeline("hz_downsample");
        let hz_airlight_pipeline = gf_pipeline("hz_airlight");
        let hz_min_h_pipeline = gf_pipeline("hz_min_h");
        let hz_min_v_pipeline = gf_pipeline("hz_min_v");
        let hz_max_h_pipeline = gf_pipeline("hz_max_h");
        let hz_max_v_pipeline = gf_pipeline("hz_max_v");
        let hz_coeffs_pipeline = gf_pipeline("hz_coeffs");

        let shader_module = device.create_shader_module(wgpu::ShaderModuleDescriptor {
            label: Some("Image Processing Shader"),
            source: wgpu::ShaderSource::Wgsl(include_str!("shaders/shader.wgsl").into()),
        });

        let mut bind_group_layout_entries = vec![
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Texture {
                    sample_type: wgpu::TextureSampleType::Float { filterable: false },
                    view_dimension: wgpu::TextureViewDimension::D2,
                    multisampled: false,
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 1,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::StorageTexture {
                    access: wgpu::StorageTextureAccess::WriteOnly,
                    format: wgpu::TextureFormat::Rgba8Unorm,
                    view_dimension: wgpu::TextureViewDimension::D2,
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 2,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Buffer {
                    ty: wgpu::BufferBindingType::Storage { read_only: true },
                    has_dynamic_offset: false,
                    min_binding_size: None,
                },
                count: None,
            },
        ];

        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 3,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2Array,
                multisampled: false,
            },
            count: None,
        });

        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 3 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D3,
                multisampled: false,
            },
            count: None,
        });
        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 4 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::NonFiltering),
            count: None,
        });

        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 5 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        });
        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 6 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        });
        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 7 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        });
        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 8 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        });

        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 9 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: true },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        });
        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 10 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
            count: None,
        });

        bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
            binding: 11 + MAX_MASK_BINDINGS,
            visibility: wgpu::ShaderStages::COMPUTE,
            ty: wgpu::BindingType::Texture {
                sample_type: wgpu::TextureSampleType::Float { filterable: false },
                view_dimension: wgpu::TextureViewDimension::D2,
                multisampled: false,
            },
            count: None,
        });

        for binding in 12..15 {
            bind_group_layout_entries.push(wgpu::BindGroupLayoutEntry {
                binding: binding + MAX_MASK_BINDINGS,
                visibility: wgpu::ShaderStages::COMPUTE,
                ty: wgpu::BindingType::Texture {
                    sample_type: wgpu::TextureSampleType::Float { filterable: false },
                    view_dimension: wgpu::TextureViewDimension::D2,
                    multisampled: false,
                },
                count: None,
            });
        }

        let main_bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("Main BGL"),
            entries: &bind_group_layout_entries,
        });

        let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
            label: Some("Pipeline Layout"),
            bind_group_layouts: &[Some(&main_bgl)],
            immediate_size: 0,
        });

        let main_shader_module = shader_module.clone();
        let main_pipeline_layout = pipeline_layout.clone();
        let main_pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("Compute Pipeline"),
            layout: Some(&pipeline_layout),
            module: &shader_module,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });

        bind_group_layout_entries[1].ty = wgpu::BindingType::StorageTexture {
            access: wgpu::StorageTextureAccess::WriteOnly,
            format: wgpu::TextureFormat::Rgba16Float,
            view_dimension: wgpu::TextureViewDimension::D2,
        };
        let dummy_texture_desc = wgpu::TextureDescriptor {
            label: Some("Dummy Texture"),
            size: wgpu::Extent3d {
                width: 1,
                height: 1,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba16Float,
            usage: wgpu::TextureUsages::TEXTURE_BINDING,
            view_formats: &[],
        };
        let dummy_blur_texture = device.create_texture(&dummy_texture_desc);
        let dummy_blur_view = dummy_blur_texture.create_view(&Default::default());

        let dummy_lut_texture = device.create_texture(&wgpu::TextureDescriptor {
            dimension: wgpu::TextureDimension::D3,
            ..dummy_texture_desc
        });
        let dummy_lut_view = dummy_lut_texture.create_view(&Default::default());
        let dummy_lut_sampler = device.create_sampler(&wgpu::SamplerDescriptor::default());
        let dummy_mask_view = device
            .create_texture(&wgpu::TextureDescriptor {
                label: Some("Dummy Mask Texture Array"),
                size: wgpu::Extent3d {
                    width: 1,
                    height: 1,
                    depth_or_array_layers: 2,
                },
                format: wgpu::TextureFormat::R8Unorm,
                ..dummy_texture_desc
            })
            .create_view(&wgpu::TextureViewDescriptor {
                dimension: Some(wgpu::TextureViewDimension::D2Array),
                ..Default::default()
            });

        Self {
            blur_bgl,
            h_blur_pipeline,
            v_blur_pipeline,
            h_blur_direct_pipeline,
            v_blur_direct_pipeline,
            flare_bgl_0,
            flare_bgl_1,
            flare_threshold_pipeline,
            flare_ghosts_pipeline,
            flare_sampler,
            gf_bgl,
            gf_downsample_pipeline,
            gf_box_down_pipeline,
            gf_blur_h_pipeline,
            gf_blur_v_pipeline,
            gf_coeffs_pipeline,
            gf_pack_pipeline,
            hz_downsample_pipeline,
            hz_airlight_pipeline,
            hz_min_h_pipeline,
            hz_min_v_pipeline,
            hz_max_h_pipeline,
            hz_max_v_pipeline,
            hz_coeffs_pipeline,
            main_bgl,
            main_pipeline,
            main_shader_module,
            main_pipeline_layout,
            specialized_pipelines: Arc::new(std::sync::Mutex::new(HashMap::new())),
            high_precision_entries: bind_group_layout_entries,
            high_precision_pipeline: std::sync::OnceLock::new(),
            hsl_probe_pipeline: std::sync::OnceLock::new(),
            dummy_blur_view,
            dummy_lut_view,
            dummy_lut_sampler,
            dummy_mask_view,
        }
    }
}

impl GpuProcessor {
    pub fn new(context: GpuContext, max_width: u32, max_height: u32) -> Result<Self, String> {
        let device = &context.device;
        let pipelines = Arc::clone(
            context
                .pipelines
                .get_or_init(|| Arc::new(GpuPipelines::new(device))),
        );

        let blur_params_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Blur Params Buffer"),
            size: std::mem::size_of::<BlurParams>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let flare_params_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Flare Params Buffer"),
            size: std::mem::size_of::<FlareParams>() as u64,
            usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        let flare_tex_desc = wgpu::TextureDescriptor {
            label: Some("Flare Tex"),
            size: wgpu::Extent3d {
                width: FLARE_MAP_SIZE,
                height: FLARE_MAP_SIZE,
                depth_or_array_layers: 1,
            },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba16Float,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::STORAGE_BINDING,
            view_formats: &[],
        };

        let flare_threshold_texture = device.create_texture(&flare_tex_desc);
        let flare_threshold_view = flare_threshold_texture.create_view(&Default::default());
        let flare_ghosts_texture = device.create_texture(&flare_tex_desc);
        let flare_ghosts_view = flare_ghosts_texture.create_view(&Default::default());
        let flare_final_texture = device.create_texture(&flare_tex_desc);
        let flare_final_view = flare_final_texture.create_view(&Default::default());

        let adjustments_buffer = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("Adjustments Buffer"),
            size: std::mem::size_of::<AllAdjustments>() as u64,
            usage: wgpu::BufferUsages::STORAGE | wgpu::BufferUsages::COPY_DST,
            mapped_at_creation: false,
        });

        const TILE_SIZE: u32 = 2048;
        const TILE_OVERLAP: u32 = 128;

        let clamped_tile_width = max_width.min(TILE_SIZE + TILE_OVERLAP * 2);
        let clamped_tile_height = max_height.min(TILE_SIZE + TILE_OVERLAP * 2);

        let clamped_tile_size = wgpu::Extent3d {
            width: clamped_tile_width,
            height: clamped_tile_height,
            depth_or_array_layers: 1,
        };

        let full_image_size = wgpu::Extent3d {
            width: max_width,
            height: max_height,
            depth_or_array_layers: 1,
        };

        let reusable_texture_desc = wgpu::TextureDescriptor {
            label: None,
            size: clamped_tile_size,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba16Float,
            usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::STORAGE_BINDING,
            view_formats: &[],
        };

        let ping_pong_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Ping Pong Texture"),
            ..reusable_texture_desc
        });
        let ping_pong_view = ping_pong_texture.create_view(&Default::default());

        let sharpness_blur_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Sharpness Blur Texture"),
            ..reusable_texture_desc
        });
        let sharpness_blur_view = sharpness_blur_texture.create_view(&Default::default());

        let tonal_blur_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Tonal Blur Texture"),
            ..reusable_texture_desc
        });
        let tonal_blur_view = tonal_blur_texture.create_view(&Default::default());

        let clarity_blur_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Clarity Blur Texture"),
            ..reusable_texture_desc
        });
        let clarity_blur_view = clarity_blur_texture.create_view(&Default::default());

        let structure_blur_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Structure Blur Texture"),
            ..reusable_texture_desc
        });
        let structure_blur_view = structure_blur_texture.create_view(&Default::default());

        let tile_output_texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("Tile Output Texture"),
            size: clamped_tile_size,
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::TEXTURE_BINDING
                | wgpu::TextureUsages::STORAGE_BINDING
                | wgpu::TextureUsages::COPY_SRC,
            view_formats: &[],
        });
        let tile_output_texture_view = tile_output_texture.create_view(&Default::default());

        Ok(Self {
            context,
            pipelines,
            blur_params_buffer,
            flare_params_buffer,
            flare_threshold_view,
            flare_ghosts_view,
            flare_final_view,
            high_precision_tile: std::sync::OnceLock::new(),
            tile_output_size: clamped_tile_size,
            adjustments_buffer,
            mask_cache: std::sync::Mutex::new(None),
            lut_cache: std::sync::Mutex::new(None),
            ping_pong_view,
            sharpness_blur_view,
            tonal_blur_view,
            clarity_blur_view,
            structure_blur_view,
            blur_sources: std::sync::Mutex::new([None; 4]),
            tile_output_texture,
            tile_output_texture_view,
            full_image_size,
            display_textures: std::sync::OnceLock::new(),
            export_textures: std::sync::OnceLock::new(),
            readback_pool: Arc::new(std::sync::Mutex::new(Vec::new())),
        })
    }

    pub fn build_guided_coeffs(
        &self,
        input_view: &wgpu::TextureView,
        width: u32,
        height: u32,
        is_raw: u32,
    ) -> GuidedTextures {
        let device = &self.context.device;
        let queue = &self.context.queue;

        let short = width.min(height).max(1);
        let k = (GF_SHORT_EDGE as f32 / short as f32).min(1.0);
        let lw = ((width as f32 * k).round() as u32).max(1);
        let lh = ((height as f32 * k).round() as u32).max(1);
        let low_short = lw.min(lh) as f32;
        let cw = lw.div_ceil(GF_COARSE_DIV).max(1);
        let ch = lh.div_ceil(GF_COARSE_DIV).max(1);
        let hw = lw.div_ceil(HZ_DIV).max(1);
        let hh = lh.div_ceil(HZ_DIV).max(1);
        let hcw = hw.div_ceil(GF_COARSE_DIV).max(1);
        let hch = hh.div_ceil(GF_COARSE_DIV).max(1);

        let make_view = |label: &'static str, w: u32, h: u32| {
            device
                .create_texture(&wgpu::TextureDescriptor {
                    label: Some(label),
                    size: wgpu::Extent3d {
                        width: w,
                        height: h,
                        depth_or_array_layers: 1,
                    },
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: wgpu::TextureFormat::Rgba32Float,
                    usage: wgpu::TextureUsages::TEXTURE_BINDING
                        | wgpu::TextureUsages::STORAGE_BINDING,
                    view_formats: &[],
                })
                .create_view(&Default::default())
        };

        let make_params = |base_radius: f32, eps: f32, div: f32| {
            let radius = (base_radius * low_short / 1080.0 / div).ceil().max(1.0) as u32;
            device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
                label: Some("Guided Filter Params"),
                contents: bytemuck::bytes_of(&GfParams {
                    radius,
                    is_raw,
                    eps,
                    _pad: 0.0,
                }),
                usage: wgpu::BufferUsages::UNIFORM,
            })
        };

        let moments = make_view("GF Moments", lw, lh);
        let tmp = make_view("GF Ping Pong", lw, lh);
        let blurred = make_view("GF Blurred", lw, lh);
        let ab = make_view("GF Coeffs", lw, lh);
        let ab_clarity = make_view("GF Coeffs Clarity", lw, lh);
        let c_moments = make_view("GF Coarse Moments", cw, ch);
        let c_tmp = make_view("GF Coarse Ping Pong", cw, ch);
        let c_blurred = make_view("GF Coarse Blurred", cw, ch);
        let c_ab = make_view("GF Coarse Coeffs", cw, ch);
        let hz_a = make_view("Haze Ping", hw, hh);
        let hz_b = make_view("Haze Pong", hw, hh);
        let hz_coarse = make_view("Haze Coarse", hcw, hch);
        let haze = make_view("Haze Coeffs", hw, hh);
        let airlight = make_view("Haze Airlight", 2, 1);

        let params_clarity = make_params(GF_CLARITY_RADIUS, GF_CLARITY_EPS, 1.0);
        let params_tonal = make_params(GF_TONAL_RADIUS, GF_TONAL_EPS, 1.0);
        let params_coarse =
            make_params(GF_STRUCTURE_RADIUS, GF_STRUCTURE_EPS, GF_COARSE_DIV as f32);
        let params_hz_patch = make_params(HZ_PATCH_RADIUS, HZ_GF_EPS, HZ_DIV as f32);
        let params_hz_gf = make_params(HZ_GF_RADIUS, HZ_GF_EPS, HZ_DIV as f32);

        let mut encoder = device.create_command_encoder(&Default::default());

        let mut pass = |pipeline: &wgpu::ComputePipeline,
                        src_a: &wgpu::TextureView,
                        src_b: &wgpu::TextureView,
                        dst: &wgpu::TextureView,
                        params: &wgpu::Buffer,
                        (w, h): (u32, u32)| {
            let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Guided Filter BG"),
                layout: &self.pipelines.gf_bgl,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(src_a),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(src_b),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: wgpu::BindingResource::TextureView(dst),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: params.as_entire_binding(),
                    },
                ],
            });
            let mut cpass = encoder.begin_compute_pass(&Default::default());
            cpass.set_pipeline(pipeline);
            cpass.set_bind_group(0, &bind_group, &[]);
            cpass.dispatch_workgroups(w.div_ceil(8), h.div_ceil(8), 1);
        };
        let fine = (lw, lh);
        let coarse = (cw, ch);
        let half = (hw, hh);

        pass(
            &self.pipelines.gf_downsample_pipeline,
            input_view,
            input_view,
            &moments,
            &params_clarity,
            fine,
        );

        pass(
            &self.pipelines.gf_box_down_pipeline,
            &moments,
            &moments,
            &c_moments,
            &params_coarse,
            coarse,
        );
        pass(
            &self.pipelines.gf_blur_h_pipeline,
            &c_moments,
            &c_moments,
            &c_tmp,
            &params_coarse,
            coarse,
        );
        pass(
            &self.pipelines.gf_blur_v_pipeline,
            &c_tmp,
            &c_tmp,
            &c_blurred,
            &params_coarse,
            coarse,
        );
        pass(
            &self.pipelines.gf_coeffs_pipeline,
            &c_blurred,
            &c_blurred,
            &c_ab,
            &params_coarse,
            coarse,
        );
        pass(
            &self.pipelines.gf_blur_h_pipeline,
            &c_ab,
            &c_ab,
            &c_tmp,
            &params_coarse,
            coarse,
        );
        pass(
            &self.pipelines.gf_blur_v_pipeline,
            &c_tmp,
            &c_tmp,
            &c_moments,
            &params_coarse,
            coarse,
        );

        for (params, out) in [(&params_clarity, &ab_clarity), (&params_tonal, &blurred)] {
            pass(
                &self.pipelines.gf_blur_h_pipeline,
                &moments,
                &moments,
                &tmp,
                params,
                fine,
            );
            pass(
                &self.pipelines.gf_blur_v_pipeline,
                &tmp,
                &tmp,
                &blurred,
                params,
                fine,
            );
            pass(
                &self.pipelines.gf_coeffs_pipeline,
                &blurred,
                &blurred,
                &ab,
                params,
                fine,
            );
            pass(
                &self.pipelines.gf_blur_h_pipeline,
                &ab,
                &ab,
                &tmp,
                params,
                fine,
            );
            pass(
                &self.pipelines.gf_blur_v_pipeline,
                &tmp,
                &tmp,
                out,
                params,
                fine,
            );
        }

        pass(
            &self.pipelines.gf_pack_pipeline,
            &ab_clarity,
            &blurred,
            &ab,
            &params_clarity,
            fine,
        );

        pass(
            &self.pipelines.hz_downsample_pipeline,
            input_view,
            input_view,
            &hz_a,
            &params_hz_patch,
            half,
        );
        pass(
            &self.pipelines.gf_box_down_pipeline,
            &hz_a,
            &hz_a,
            &hz_coarse,
            &params_hz_patch,
            (hcw, hch),
        );
        pass(
            &self.pipelines.hz_airlight_pipeline,
            &hz_coarse,
            &hz_coarse,
            &airlight,
            &params_hz_patch,
            (1, 1),
        );
        pass(
            &self.pipelines.hz_min_h_pipeline,
            &hz_a,
            &airlight,
            &hz_b,
            &params_hz_patch,
            half,
        );
        pass(
            &self.pipelines.hz_min_v_pipeline,
            &hz_b,
            &hz_b,
            &hz_a,
            &params_hz_patch,
            half,
        );
        pass(
            &self.pipelines.hz_max_h_pipeline,
            &hz_a,
            &hz_a,
            &hz_b,
            &params_hz_patch,
            half,
        );
        pass(
            &self.pipelines.hz_max_v_pipeline,
            &hz_b,
            &hz_b,
            &hz_a,
            &params_hz_patch,
            half,
        );
        pass(
            &self.pipelines.gf_blur_h_pipeline,
            &hz_a,
            &hz_a,
            &hz_b,
            &params_hz_gf,
            half,
        );
        pass(
            &self.pipelines.gf_blur_v_pipeline,
            &hz_b,
            &hz_b,
            &hz_a,
            &params_hz_gf,
            half,
        );
        pass(
            &self.pipelines.hz_coeffs_pipeline,
            &hz_a,
            &hz_a,
            &hz_b,
            &params_hz_gf,
            half,
        );
        pass(
            &self.pipelines.gf_blur_h_pipeline,
            &hz_b,
            &hz_b,
            &hz_a,
            &params_hz_gf,
            half,
        );
        pass(
            &self.pipelines.gf_blur_v_pipeline,
            &hz_a,
            &hz_a,
            &haze,
            &params_hz_gf,
            half,
        );

        queue.submit(Some(encoder.finish()));
        GuidedTextures {
            coeffs: ab,
            structure: c_moments,
            haze,
            airlight,
        }
    }

    fn mask_view(
        &self,
        mask_bitmaps: &[ImageBuffer<Luma<u8>, Vec<u8>>],
        width: u32,
        height: u32,
        cache_key: Option<u64>,
    ) -> wgpu::TextureView {
        if mask_bitmaps.is_empty() {
            return self.pipelines.dummy_mask_view.clone();
        }

        let device = &self.context.device;
        let queue = &self.context.queue;
        let layers = mask_bitmaps.len().clamp(2, MAX_MASKS) as u32;
        let buffer_size = (width as usize) * (height as usize) * (layers as usize);

        let mut cache = self.mask_cache.lock().unwrap_or_else(|e| e.into_inner());
        if let (Some(c), Some(key)) = (cache.as_ref(), cache_key)
            && c.width == width
            && c.height == height
            && c.layers == layers
            && c.key == key
        {
            return c.view.clone();
        }

        let mut data = Vec::with_capacity(buffer_size);
        for mask_bitmap in mask_bitmaps.iter().take(MAX_MASKS) {
            data.extend_from_slice(mask_bitmap.as_raw());
        }
        data.resize(buffer_size, 0);

        let size = wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: layers,
        };
        let reusable = cache_key.and_then(|_| {
            cache
                .take()
                .filter(|c| c.width == width && c.height == height && c.layers == layers)
        });
        let texture = match reusable {
            Some(c) => {
                queue.write_texture(
                    c.texture.as_image_copy(),
                    &data,
                    wgpu::TexelCopyBufferLayout {
                        offset: 0,
                        bytes_per_row: Some(width),
                        rows_per_image: Some(height),
                    },
                    size,
                );
                c.texture
            }
            None => device.create_texture_with_data(
                queue,
                &wgpu::TextureDescriptor {
                    label: Some("Full Mask Texture Array"),
                    size,
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: wgpu::TextureFormat::R8Unorm,
                    usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                    view_formats: &[],
                },
                TextureDataOrder::MipMajor,
                &data,
            ),
        };
        let view = texture.create_view(&wgpu::TextureViewDescriptor {
            dimension: Some(wgpu::TextureViewDimension::D2Array),
            ..Default::default()
        });
        if let Some(key) = cache_key {
            *cache = Some(MaskCache {
                width,
                height,
                layers,
                key,
                texture,
                view: view.clone(),
            });
        }
        view
    }

    fn lut_view(&self, lut: &Arc<Lut>) -> (wgpu::TextureView, wgpu::Sampler) {
        let mut cache = self.lut_cache.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(c) = cache.as_ref()
            && Arc::ptr_eq(&c.lut, lut)
        {
            return (c.view.clone(), c.sampler.clone());
        }

        let device = &self.context.device;
        let queue = &self.context.queue;
        let size = lut.size;
        let mut rgba_lut_data_f16 = Vec::with_capacity(lut.data.len() / 3 * 4);
        for chunk in lut.data.as_chunks::<3>().0 {
            rgba_lut_data_f16.push(f16::from_f32(chunk[0]));
            rgba_lut_data_f16.push(f16::from_f32(chunk[1]));
            rgba_lut_data_f16.push(f16::from_f32(chunk[2]));
            rgba_lut_data_f16.push(f16::ONE);
        }
        let lut_texture = device.create_texture_with_data(
            queue,
            &wgpu::TextureDescriptor {
                label: Some("LUT 3D Texture"),
                size: wgpu::Extent3d {
                    width: size,
                    height: size,
                    depth_or_array_layers: size,
                },
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D3,
                format: wgpu::TextureFormat::Rgba16Float,
                usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
                view_formats: &[],
            },
            TextureDataOrder::MipMajor,
            bytemuck::cast_slice(&rgba_lut_data_f16),
        );
        let view = lut_texture.create_view(&Default::default());
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            address_mode_u: wgpu::AddressMode::ClampToEdge,
            address_mode_v: wgpu::AddressMode::ClampToEdge,
            address_mode_w: wgpu::AddressMode::ClampToEdge,
            mag_filter: wgpu::FilterMode::Nearest,
            min_filter: wgpu::FilterMode::Nearest,
            ..Default::default()
        });
        *cache = Some(LutCache {
            lut: Arc::clone(lut),
            view: view.clone(),
            sampler: sampler.clone(),
        });
        (view, sampler)
    }

    fn main_pipeline_for(&self, high_precision: bool, features: u32) -> wgpu::ComputePipeline {
        let (generic, shader, layout) = if high_precision {
            let hp = self.high_precision_pipeline();
            (&hp.pipeline, &hp.shader, &hp.layout)
        } else {
            (
                &self.pipelines.main_pipeline,
                &self.pipelines.main_shader_module,
                &self.pipelines.main_pipeline_layout,
            )
        };
        if features == ALL_SHADER_FEATURES {
            return generic.clone();
        }

        let key = (high_precision, features);
        let mut pipelines = self
            .pipelines
            .specialized_pipelines
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        match pipelines.get(&key) {
            Some(Some(pipeline)) => return pipeline.clone(),
            Some(None) => return generic.clone(),
            None => {}
        }
        pipelines.insert(key, None);
        drop(pipelines);

        let device = self.context.device.clone();
        let shader = shader.clone();
        let layout = layout.clone();
        let specialized_pipelines = Arc::clone(&self.pipelines.specialized_pipelines);
        std::thread::spawn(move || {
            let mut constants = vec![("FEATURES", features as f64)];
            if high_precision {
                constants.push(("HIGH_PRECISION_OUTPUT", 1.0));
            }
            let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("Specialized Compute Pipeline"),
                layout: Some(&layout),
                module: &shader,
                entry_point: Some("main"),
                compilation_options: wgpu::PipelineCompilationOptions {
                    constants: &constants,
                    ..Default::default()
                },
                cache: None,
            });
            specialized_pipelines
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(key, Some(pipeline));
        });
        generic.clone()
    }

    fn readback_buffer(&self, size: u64) -> wgpu::Buffer {
        let pooled = {
            let mut pool = self.readback_pool.lock().unwrap_or_else(|e| e.into_inner());
            pool.iter()
                .position(|buffer| buffer.size() == size)
                .map(|index| pool.swap_remove(index))
        };
        pooled.unwrap_or_else(|| {
            self.context.device.create_buffer(&wgpu::BufferDescriptor {
                label: Some("Async Analytics Readback Buffer"),
                size,
                usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
                mapped_at_creation: false,
            })
        })
    }

    pub fn display_textures(&self) -> &DisplayTextures {
        self.display_textures.get_or_init(|| {
            let descriptor = |label| wgpu::TextureDescriptor {
                label: Some(label),
                size: self.full_image_size,
                mip_level_count: 1,
                sample_count: 1,
                dimension: wgpu::TextureDimension::D2,
                format: wgpu::TextureFormat::Rgba8Unorm,
                usage: wgpu::TextureUsages::TEXTURE_BINDING
                    | wgpu::TextureUsages::COPY_DST
                    | wgpu::TextureUsages::COPY_SRC,
                view_formats: &[],
            };
            let device = &self.context.device;
            let working_texture = device.create_texture(&descriptor("Working Output Texture"));
            let output_texture = device.create_texture(&descriptor("Full Output Texture"));
            let output_texture_view = output_texture.create_view(&Default::default());
            DisplayTextures {
                working_texture,
                output_texture,
                output_texture_view,
            }
        })
    }

    fn high_precision_pipeline(&self) -> &HighPrecisionPipeline {
        self.pipelines.high_precision_pipeline.get_or_init(|| {
            let device = &self.context.device;
            let bgl = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("High Precision Main BGL"),
                entries: &self.pipelines.high_precision_entries,
            });
            let layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
                label: Some("High Precision Pipeline Layout"),
                bind_group_layouts: &[Some(&bgl)],
                immediate_size: 0,
            });
            let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
                label: Some("High Precision Image Processing Shader"),
                source: wgpu::ShaderSource::Wgsl(high_precision_shader_source().into()),
            });
            let pipeline = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                label: Some("High Precision Compute Pipeline"),
                layout: Some(&layout),
                module: &shader,
                entry_point: Some("main"),
                compilation_options: wgpu::PipelineCompilationOptions {
                    constants: &[("HIGH_PRECISION_OUTPUT", 1.0)],
                    ..Default::default()
                },
                cache: None,
            });
            HighPrecisionPipeline {
                bgl,
                pipeline,
                shader,
                layout,
            }
        })
    }

    fn hsl_probe_pipeline(&self) -> &wgpu::ComputePipeline {
        self.pipelines.hsl_probe_pipeline.get_or_init(|| {
            self.context
                .device
                .create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
                    label: Some("HSL Probe Pipeline"),
                    layout: Some(&self.pipelines.main_pipeline_layout),
                    module: &self.pipelines.main_shader_module,
                    entry_point: Some("main"),
                    compilation_options: wgpu::PipelineCompilationOptions {
                        constants: &[("HSL_PROBE", 1.0)],
                        ..Default::default()
                    },
                    cache: None,
                })
        })
    }

    fn high_precision_tile(&self) -> &HighPrecisionTile {
        self.high_precision_tile.get_or_init(|| {
            let texture = self
                .context
                .device
                .create_texture(&wgpu::TextureDescriptor {
                    label: Some("High Precision Tile Output Texture"),
                    size: self.tile_output_size,
                    mip_level_count: 1,
                    sample_count: 1,
                    dimension: wgpu::TextureDimension::D2,
                    format: wgpu::TextureFormat::Rgba16Float,
                    usage: wgpu::TextureUsages::TEXTURE_BINDING
                        | wgpu::TextureUsages::STORAGE_BINDING
                        | wgpu::TextureUsages::COPY_SRC,
                    view_formats: &[],
                });
            let view = texture.create_view(&Default::default());
            HighPrecisionTile { texture, view }
        })
    }

    fn run(
        &self,
        input: &GpuImageEntry,
        request: RenderRequest,
        output_to_display: bool,
        output_precision: RenderOutputPrecision,
    ) -> Result<(RenderedPixels, u32, u32, u32, u32), String> {
        let input_texture_view = &input.texture_view;
        let gf_coeffs_view = &input.gf_coeffs_view;
        let gf_structure_view = &input.gf_structure_view;
        let haze_view = &input.haze_view;
        let airlight_view = &input.airlight_view;
        let (width, height) = (input.width, input.height);
        let device = &self.context.device;
        let queue = &self.context.queue;
        let scale = (width.min(height) as f32) / 1080.0;
        const MAX_MASK_BINDINGS: u32 = 1;


        let high_precision_tile = if output_precision == RenderOutputPrecision::SixteenBit {
            Some(self.high_precision_tile())
        } else {
            None
        };
        let output_pipeline = if request.hsl_probe {
            self.hsl_probe_pipeline().clone()
        } else {
            self.main_pipeline_for(
                high_precision_tile.is_some(),
                shader_features(&request.adjustments),
            )
        };
        let (output_bgl, output_tile_texture, output_tile_view, bytes_per_pixel) =
            if let Some(high_precision_tile) = high_precision_tile {
                let hp = self.high_precision_pipeline();
                (
                    &hp.bgl,
                    &high_precision_tile.texture,
                    &high_precision_tile.view,
                    8,
                )
            } else {
                (
                    &self.pipelines.main_bgl,
                    &self.tile_output_texture,
                    &self.tile_output_texture_view,
                    4,
                )
            };

        let bounds = request.roi.unwrap_or(Roi {
            x: 0,
            y: 0,
            width,
            height,
        });
        let out_width = bounds.width;
        let out_height = bounds.height;
        let mask_texture_view =
            self.mask_view(request.mask_bitmaps, width, height, request.mask_cache_key);
        let (lut_texture_view, lut_sampler) = match &request.lut {
            Some(lut) => self.lut_view(lut),
            None => (
                self.pipelines.dummy_lut_view.clone(),
                self.pipelines.dummy_lut_sampler.clone(),
            ),
        };

        let adjustments = request.adjustments;
        if adjustments.global.flare_amount > 0.0 {
            let mut encoder = device.create_command_encoder(&Default::default());

            let aspect_ratio = if height > 0 {
                width as f32 / height as f32
            } else {
                1.0
            };
            let f_params = FlareParams {
                amount: adjustments.global.flare_amount,
                is_raw: adjustments.global.is_raw_image,
                exposure: adjustments.global.exposure,
                brightness: adjustments.global.brightness,
                contrast: adjustments.global.contrast,
                whites: adjustments.global.whites,
                aspect_ratio,
                _pad: 0.0,
            };
            queue.write_buffer(&self.flare_params_buffer, 0, bytemuck::bytes_of(&f_params));

            let bg0 = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Flare BG0"),
                layout: &self.pipelines.flare_bgl_0,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(input_texture_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(&self.flare_threshold_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: self.flare_params_buffer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: wgpu::BindingResource::Sampler(&self.pipelines.flare_sampler),
                    },
                ],
            });

            {
                let mut cpass = encoder.begin_compute_pass(&Default::default());
                cpass.set_pipeline(&self.pipelines.flare_threshold_pipeline);
                cpass.set_bind_group(0, &bg0, &[]);
                cpass.dispatch_workgroups(FLARE_MAP_SIZE / 16, FLARE_MAP_SIZE / 16, 1);
            }

            let bg0_ghosts = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Flare BG0 Ghosts"),
                layout: &self.pipelines.flare_bgl_0,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(input_texture_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(&self.flare_final_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: self.flare_params_buffer.as_entire_binding(),
                    },
                    wgpu::BindGroupEntry {
                        binding: 3,
                        resource: wgpu::BindingResource::Sampler(&self.pipelines.flare_sampler),
                    },
                ],
            });

            let bg1 = device.create_bind_group(&wgpu::BindGroupDescriptor {
                label: Some("Flare BG1"),
                layout: &self.pipelines.flare_bgl_1,
                entries: &[
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(&self.flare_threshold_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(&self.flare_ghosts_view),
                    },
                ],
            });

            {
                let mut cpass = encoder.begin_compute_pass(&Default::default());
                cpass.set_pipeline(&self.pipelines.flare_ghosts_pipeline);
                cpass.set_bind_group(0, &bg0_ghosts, &[]);
                cpass.set_bind_group(1, &bg1, &[]);
                cpass.dispatch_workgroups(FLARE_MAP_SIZE / 16, FLARE_MAP_SIZE / 16, 1);
            }

            queue.submit(Some(encoder.finish()));
        }

        const TILE_SIZE: u32 = 2048;
        const TILE_OVERLAP: u32 = 128;

        let output_len = if output_to_display {
            0
        } else {
            (out_width * out_height * 4) as usize
        };
        let mut final_pixels = match output_precision {
            RenderOutputPrecision::EightBit => RenderedPixels::U8(vec![0u8; output_len]),
            RenderOutputPrecision::SixteenBit => RenderedPixels::U16(vec![0u16; output_len]),
        };

        let start_tile_x = bounds.x / TILE_SIZE;
        let start_tile_y = bounds.y / TILE_SIZE;
        let end_tile_x = (bounds.x + bounds.width).div_ceil(TILE_SIZE);
        let end_tile_y = (bounds.y + bounds.height).div_ceil(TILE_SIZE);

        for tile_y in start_tile_y..end_tile_y {
            for tile_x in start_tile_x..end_tile_x {
                let x_start_unclamped = tile_x * TILE_SIZE;
                let y_start_unclamped = tile_y * TILE_SIZE;

                let x_start = x_start_unclamped.max(bounds.x);
                let y_start = y_start_unclamped.max(bounds.y);
                let x_end = (x_start_unclamped + TILE_SIZE)
                    .min(bounds.x + bounds.width)
                    .min(width);
                let y_end = (y_start_unclamped + TILE_SIZE)
                    .min(bounds.y + bounds.height)
                    .min(height);

                let tile_width = x_end - x_start;
                let tile_height = y_end - y_start;

                let input_x_start = (x_start as i32 - TILE_OVERLAP as i32).max(0) as u32;
                let input_y_start = (y_start as i32 - TILE_OVERLAP as i32).max(0) as u32;
                let input_x_end = (x_end + TILE_OVERLAP).min(width);
                let input_y_end = (y_end + TILE_OVERLAP).min(height);
                let input_width = input_x_end - input_x_start;
                let input_height = input_y_end - input_y_start;

                let run_blur = |slot: usize,
                                base_radius: f32,
                                output_view: &wgpu::TextureView|
                 -> bool {
                    let radius = (base_radius * scale).ceil().max(1.0) as u32;
                    if radius == 0 {
                        return false;
                    }

                    let params = BlurParams {
                        radius,
                        tile_offset_x: input_x_start,
                        tile_offset_y: input_y_start,
                        input_width,
                        input_height,
                        _pad1: 0,
                        _pad2: 0,
                        _pad3: 0,
                    };

                    let source = Some((input.id, params));
                    if self.blur_sources.lock().unwrap_or_else(|e| e.into_inner())[slot] == source {
                        return true;
                    }

                    queue.write_buffer(&self.blur_params_buffer, 0, bytemuck::bytes_of(&params));

                    let mut blur_encoder = device.create_command_encoder(&Default::default());

                    let h_blur_bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
                        label: Some("H-Blur BG"),
                        layout: &self.pipelines.blur_bgl,
                        entries: &[
                            wgpu::BindGroupEntry {
                                binding: 0,
                                resource: wgpu::BindingResource::TextureView(input_texture_view),
                            },
                            wgpu::BindGroupEntry {
                                binding: 1,
                                resource: wgpu::BindingResource::TextureView(&self.ping_pong_view),
                            },
                            wgpu::BindGroupEntry {
                                binding: 2,
                                resource: self.blur_params_buffer.as_entire_binding(),
                            },
                        ],
                    });

                    let direct = radius <= DIRECT_BLUR_MAX_RADIUS;

                    {
                        let mut cpass = blur_encoder.begin_compute_pass(&Default::default());
                        cpass.set_pipeline(if direct {
                            &self.pipelines.h_blur_direct_pipeline
                        } else {
                            &self.pipelines.h_blur_pipeline
                        });
                        cpass.set_bind_group(0, &h_blur_bg, &[]);
                        cpass.dispatch_workgroups(
                            input_width.div_ceil(BLUR_H_GROUP_WIDTH),
                            input_height,
                            1,
                        );
                    }

                    let v_blur_bg = device.create_bind_group(&wgpu::BindGroupDescriptor {
                        label: Some("V-Blur BG"),
                        layout: &self.pipelines.blur_bgl,
                        entries: &[
                            wgpu::BindGroupEntry {
                                binding: 0,
                                resource: wgpu::BindingResource::TextureView(&self.ping_pong_view),
                            },
                            wgpu::BindGroupEntry {
                                binding: 1,
                                resource: wgpu::BindingResource::TextureView(output_view),
                            },
                            wgpu::BindGroupEntry {
                                binding: 2,
                                resource: self.blur_params_buffer.as_entire_binding(),
                            },
                        ],
                    });

                    {
                        let mut cpass = blur_encoder.begin_compute_pass(&Default::default());
                        cpass.set_bind_group(0, &v_blur_bg, &[]);
                        if direct {
                            cpass.set_pipeline(&self.pipelines.v_blur_direct_pipeline);
                            cpass.dispatch_workgroups(input_width, input_height.div_ceil(256), 1);
                        } else {
                            cpass.set_pipeline(&self.pipelines.v_blur_pipeline);
                            cpass.dispatch_workgroups(
                                input_width.div_ceil(BLUR_V_GROUP_SIZE.0),
                                input_height.div_ceil(BLUR_V_GROUP_SIZE.1),
                                1,
                            );
                        }
                    }

                    queue.submit(Some(blur_encoder.finish()));
                    self.blur_sources.lock().unwrap_or_else(|e| e.into_inner())[slot] = source;
                    true
                };

                let active_masks = adjustments
                    .mask_adjustments
                    .iter()
                    .take(adjustments.mask_count as usize);
                let any_term =
                    |global: f32, pick: &dyn Fn(&MaskAdjustments) -> f32, positive: bool| {
                        let hit = |v: f32| if positive { v > 0.0 } else { v != 0.0 };
                        hit(global) || active_masks.clone().any(|m| hit(pick(m)))
                    };
                let need_sharpen = any_term(adjustments.global.sharpness, &|m| m.sharpness, false);
                let need_halation = any_term(
                    adjustments.global.halation_amount,
                    &|m| m.halation_amount,
                    true,
                );
                let need_structure =
                    any_term(adjustments.global.glow_amount, &|m| m.glow_amount, true);

                let did_create_sharpness_blur =
                    need_sharpen && run_blur(0, 1.0, &self.sharpness_blur_view);
                let did_create_tonal_blur = need_sharpen && run_blur(1, 3.5, &self.tonal_blur_view);
                let did_create_clarity_blur =
                    need_halation && run_blur(2, 8.0, &self.clarity_blur_view);
                let did_create_structure_blur =
                    need_structure && run_blur(3, 40.0, &self.structure_blur_view);

                let mut main_encoder = device.create_command_encoder(&Default::default());

                let mut tile_adjustments = adjustments;
                tile_adjustments.tile_offset_x = input_x_start;
                tile_adjustments.tile_offset_y = input_y_start;
                queue.write_buffer(
                    &self.adjustments_buffer,
                    0,
                    bytemuck::bytes_of(&tile_adjustments),
                );

                let mut bind_group_entries = vec![
                    wgpu::BindGroupEntry {
                        binding: 0,
                        resource: wgpu::BindingResource::TextureView(input_texture_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 1,
                        resource: wgpu::BindingResource::TextureView(output_tile_view),
                    },
                    wgpu::BindGroupEntry {
                        binding: 2,
                        resource: self.adjustments_buffer.as_entire_binding(),
                    },
                ];
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 3,
                    resource: wgpu::BindingResource::TextureView(&mask_texture_view),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 3 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(&lut_texture_view),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 4 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::Sampler(&lut_sampler),
                });

                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 5 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(if did_create_sharpness_blur {
                        &self.sharpness_blur_view
                    } else {
                        &self.pipelines.dummy_blur_view
                    }),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 6 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(if did_create_tonal_blur {
                        &self.tonal_blur_view
                    } else {
                        &self.pipelines.dummy_blur_view
                    }),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 7 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(if did_create_clarity_blur {
                        &self.clarity_blur_view
                    } else {
                        &self.pipelines.dummy_blur_view
                    }),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 8 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(if did_create_structure_blur {
                        &self.structure_blur_view
                    } else {
                        &self.pipelines.dummy_blur_view
                    }),
                });

                let use_flare = adjustments.global.flare_amount > 0.0;
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 9 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(if use_flare {
                        &self.flare_ghosts_view
                    } else {
                        &self.pipelines.dummy_blur_view
                    }),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 10 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::Sampler(&self.pipelines.flare_sampler),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 11 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(gf_coeffs_view),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 12 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(gf_structure_view),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 13 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(haze_view),
                });
                bind_group_entries.push(wgpu::BindGroupEntry {
                    binding: 14 + MAX_MASK_BINDINGS,
                    resource: wgpu::BindingResource::TextureView(airlight_view),
                });

                let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
                    label: Some("Tile Bind Group"),
                    layout: output_bgl,
                    entries: &bind_group_entries,
                });

                {
                    let mut compute_pass = main_encoder.begin_compute_pass(&Default::default());
                    compute_pass.set_pipeline(&output_pipeline);
                    compute_pass.set_bind_group(0, &bind_group, &[]);
                    compute_pass.dispatch_workgroups(
                        input_width.div_ceil(8),
                        input_height.div_ceil(8),
                        1,
                    );
                }

                let crop_x_start = x_start - input_x_start;
                let crop_y_start = y_start - input_y_start;

                if output_to_display {
                    main_encoder.copy_texture_to_texture(
                        wgpu::TexelCopyTextureInfo {
                            texture: output_tile_texture,
                            mip_level: 0,
                            origin: wgpu::Origin3d {
                                x: crop_x_start,
                                y: crop_y_start,
                                z: 0,
                            },
                            aspect: wgpu::TextureAspect::All,
                        },
                        wgpu::TexelCopyTextureInfo {
                            texture: &self.display_textures_for_precision(output_precision).working_texture,
                            mip_level: 0,
                            origin: wgpu::Origin3d {
                                x: x_start,
                                y: y_start,
                                z: 0,
                            },
                            aspect: wgpu::TextureAspect::All,
                        },
                        wgpu::Extent3d {
                            width: tile_width,
                            height: tile_height,
                            depth_or_array_layers: 1,
                        },
                    );
                }

                queue.submit(Some(main_encoder.finish()));

                if !output_to_display {
                    let processed_tile_data = read_texture_data_roi(
                        device,
                        queue,
                        output_tile_texture,
                        wgpu::Origin3d {
                            x: crop_x_start,
                            y: crop_y_start,
                            z: 0,
                        },
                        wgpu::Extent3d {
                            width: tile_width,
                            height: tile_height,
                            depth_or_array_layers: 1,
                        },
                        bytes_per_pixel,
                    )?;

                    match &mut final_pixels {
                        RenderedPixels::U8(final_pixels) => {
                            for row in 0..tile_height {
                                let final_y = y_start + row - bounds.y;
                                let final_x = x_start - bounds.x;
                                let final_row_offset = (final_y * out_width + final_x) as usize * 4;
                                let source_row_offset = (row * tile_width) as usize * 4;
                                let copy_bytes = (tile_width * 4) as usize;

                                final_pixels[final_row_offset..final_row_offset + copy_bytes]
                                    .copy_from_slice(
                                        &processed_tile_data
                                            [source_row_offset..source_row_offset + copy_bytes],
                                    );
                            }
                        }
                        RenderedPixels::U16(final_pixels) => {
                            for row in 0..tile_height {
                                let final_y = y_start + row - bounds.y;
                                let final_x = x_start - bounds.x;
                                let final_row_offset = (final_y * out_width + final_x) as usize * 4;
                                let source_pixel_offset = (row * tile_width) as usize;

                                for sample in 0..(tile_width as usize * 4) {
                                    let source_byte_offset = (source_pixel_offset * 4 + sample) * 2;
                                    final_pixels[final_row_offset + sample] = u16::from_ne_bytes([
                                        processed_tile_data[source_byte_offset],
                                        processed_tile_data[source_byte_offset + 1],
                                    ]);
                                }
                            }
                        }
                    }
                }
            }
        }

        Ok((final_pixels, out_width, out_height, bounds.x, bounds.y))
    }
}


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
