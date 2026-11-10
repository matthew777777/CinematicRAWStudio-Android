// SPDX-License-Identifier: AGPL-3.0-or-later
// The generated modules are unchanged extracts of pinned RapidRAW host routines.
#[path = "generated/image_processing.rs"]
pub mod image_processing;
#[path = "generated/white_balance.rs"]
pub mod white_balance;
#[path = "generated/baseline_exposure.rs"]
pub mod baseline_exposure;
#[path = "generated/as_shot.rs"]
pub mod as_shot;
#[path = "generated/negative_conversion.rs"]
pub mod negative_conversion;
#[path = "generated/mask_generation.rs"]
pub mod mask_generation;
#[cfg(feature = "gpu")]
#[path = "generated/renderer.rs"]
pub mod renderer;
#[cfg(feature = "gpu")]
pub mod render;
#[cfg(feature = "gpu")]
pub mod mhc;
#[cfg(feature = "gpu")]
#[path = "generated/mask_raster.rs"]
pub mod mask_raster;

use image_processing::{AllAdjustments, RenderOptions, get_all_adjustments_from_json};
use as_shot::AsShot;
use baseline_exposure::{RawExposure, BaselineOptions};
use white_balance::WhiteBalance;

/// The caller resolves camera metadata and RAW development. No file I/O or legacy grade.
pub fn prepare(parameters: &str, is_raw: bool, temperature: f64, tint: f64,
               exposure: RawExposure, baseline: BaselineOptions,
               tonemapper: Option<u32>) -> Result<AllAdjustments, String> {
    let value: serde_json::Value = serde_json::from_str(parameters).map_err(|e| e.to_string())?;
    if !value.is_object() || !temperature.is_finite() || !tint.is_finite() {
        return Err("Invalid parameters or as-shot white balance".into());
    }
    Ok(get_all_adjustments_from_json(&value, is_raw,
        AsShot { white_balance: WhiteBalance { temperature, tint }, exposure },
        RenderOptions { baseline, tonemapper_override: tonemapper, non_raw_tonemapper_override: None }))
}

/// C ABI: write one packed AllAdjustments block; return negative error or byte length.
/// Parameter JSON must already pass the versioned schema gate. Camera metadata
/// is supplied explicitly so the CRAW/MHC adapter cannot silently use a guessed IDT.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_prepare(
    json: *const u8, length: usize, temperature: f64, tint: f64,
    is_raw: u32, scene_referred: u32, dng_baseline: f32, highlight_preservation: f32,
    baseline_enabled: u32, baseline_amount: f32, compensate_highlights: u32,
    tonemapper: i32, output: *mut u8, capacity: usize,
) -> isize {
    let size = std::mem::size_of::<AllAdjustments>();
    if json.is_null() || output.is_null() || capacity < size { return -1; }
    let Ok(text) = std::str::from_utf8(unsafe { std::slice::from_raw_parts(json, length) }) else { return -2; };
    let result = prepare(text, is_raw != 0, temperature, tint,
        RawExposure { scene_referred: scene_referred != 0, dng_baseline, highlight_preservation },
        BaselineOptions { baseline_exposure: (baseline_enabled != 0).then_some(baseline_amount),
                          compensate_highlight_preservation: compensate_highlights != 0 },
        (tonemapper >= 0).then_some(tonemapper as u32));
    let Ok(block) = result else { return -3; };
    let bytes = bytemuck::bytes_of(&block);
    unsafe { std::ptr::copy_nonoverlapping(bytes.as_ptr(), output, bytes.len()); }
    size as isize
}

#[unsafe(no_mangle)]
pub extern "C" fn crs_rapidraw_parameter_size() -> usize { std::mem::size_of::<AllAdjustments>() }

#[unsafe(no_mangle)]
pub unsafe extern "C" fn crs_rapidraw_set_output(output: *mut u8, length: usize, mode: u32) -> i32 {
    if output.is_null() || length != std::mem::size_of::<AllAdjustments>() { return -1; }
    let bytes=unsafe { std::slice::from_raw_parts_mut(output,length) };
    let mut block=bytemuck::pod_read_unaligned::<AllAdjustments>(bytes);
    if block.set_crs_output(mode).is_err() {return -2;}
    bytes.copy_from_slice(bytemuck::bytes_of(&block));
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    fn defaults() -> serde_json::Value {
        serde_json::from_str(include_str!("../../../assets/rapidraw/defaults.json")).unwrap()
    }
    fn run(value: &serde_json::Value) -> AllAdjustments {
        prepare(&value.to_string(), true, 6504.0, 0.0, RawExposure::default(), BaselineOptions::default(), None).unwrap()
    }
    #[test] fn upstream_exposure_units_and_bypass_are_preserved() {
        let mut v = defaults(); v["exposure"] = 2.0.into();
        assert_eq!(run(&v).global.exposure.to_bits(), 2.5f32.to_bits());
        v["activeTools"]["basic"] = false.into();
        assert_eq!(run(&v).global.exposure, 0.0);
    }
    #[test] fn baseline_metadata_is_separate_from_user_exposure() {
        let v = defaults();
        let result = prepare(&v.to_string(), true, 6504.0, 0.0,
            RawExposure { scene_referred: true, dng_baseline: 0.5, highlight_preservation: 0.25 },
            BaselineOptions { baseline_exposure: Some(1.0), compensate_highlight_preservation: true }, None).unwrap();
        assert_eq!(result.global.exposure, 1.75);
    }
    #[test] fn wheels_and_calibration_use_upstream_divisors() {
        let mut v = defaults(); v["colorGrading"]["shadows"]["saturation"] = 50.into();
        v["colorGrading"]["balance"] = (-80).into();
        v["colorCalibration"]["redHue"] = 100.into();
        let result = run(&v);
        assert_eq!(result.global.color_grading_shadows.saturation, 0.1);
        assert_eq!(result.global.color_grading_balance, -0.4);
        assert_eq!(result.global.color_calibration.red_hue, 0.25);
    }
    #[test] fn white_balance_is_identity_at_as_shot_and_changes_with_shift() {
        let mut v = defaults(); let neutral = run(&v);
        assert_eq!(neutral.global.wb_log_gain_l, 0.0);
        assert_eq!(neutral.global.wb_log_gain_m, 0.0);
        v["temperature"] = 25.into(); let shifted = run(&v);
        assert_ne!(shifted.global.wb_log_gain_l, neutral.global.wb_log_gain_l);
        assert!(shifted.global.wb_log_gain_l.is_finite());
    }
    #[test] fn masks_filter_visibility_and_keep_local_exposure_units() {
        let mut v = defaults();
        v["masks"] = serde_json::json!([
            {"id":"a","name":"hidden","visible":false,"invert":false,"adjustments":{"exposure":4},"subMasks":[{"id":"s","type":"all","visible":true,"mode":"additive","parameters":{}}]},
            {"id":"b","name":"visible","visible":true,"invert":false,"adjustments":{"exposure":1.2},"subMasks":[{"id":"s","type":"all","visible":true,"mode":"additive","parameters":{}}]}
        ]);
        let result=run(&v);
        assert_eq!(result.mask_count,1);
        assert_eq!(result.mask_adjustments[0].exposure,1.5);
    }
    #[test] fn packed_layout_matches_shader_storage_offsets() {
        assert_eq!(std::mem::size_of::<image_processing::Point>(),16);
        assert_eq!(std::mem::size_of::<image_processing::GpuMat3>(),48);
        assert_eq!(std::mem::size_of::<image_processing::MaskAdjustments>(),1360);
        let block=run(&defaults());
        assert_eq!(bytemuck::bytes_of(&block).len(),crs_rapidraw_parameter_size());
    }
    #[test] fn ffi_rejects_invalid_input_and_small_output() {
        let mut out=[0u8;16];
        let rc=unsafe { crs_rapidraw_prepare(b"{}".as_ptr(),2,6504.0,0.0,1,0,0.0,0.0,0,0.0,0,-1,out.as_mut_ptr(),out.len()) };
        assert_eq!(rc,-1);
        assert!(prepare("not json",true,6504.0,0.0,RawExposure::default(),BaselineOptions::default(),None).is_err());
    }
}

#[cfg(feature="gpu")]
#[path="generated/lut_parsers.rs"]
pub mod lut_parsers;

#[cfg(feature="gpu")]
pub mod delivery;
