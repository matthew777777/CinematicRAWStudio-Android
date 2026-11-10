// SPDX-License-Identifier: AGPL-3.0-or-later
// Resource binding only: all frame grading stays in the RapidRAW compute graph.
use super::{RapidRenderer,Lut};
use crate::mask_generation::MaskDefinition;
use serde_json::Value;
use std::sync::Arc;

#[derive(Default)]
pub struct RenderResources {
    pub masks:Vec<image::GrayImage>,
    pub lut:Option<Arc<Lut>>,
    lut_key:String,
    mask_key:String,
}
impl RenderResources {
    // Geometric masks do not depend on frame contents: preserve their cache across source frames.
    pub fn reset_source(&mut self) {}
}
impl RapidRenderer {
    pub fn output_size(&self)->(u32,u32) { (self.width,self.height) }
    pub fn configure(&mut self,text:&str,scale:f32,_is_raw:bool)->Result<(),String> {
        if !scale.is_finite()||scale<=0.0 {return Err("Invalid source coordinate scale".into());}
        let parameters:Value=serde_json::from_str(text).map_err(|e|e.to_string())?;
        if !parameters.is_object(){return Err("Invalid grade resource envelope".into());}
        for (tool,key) in [("noiseReduction","aiDenoiseEnabled"),("negativeConversion","negativeConversionEnabled"),
            ("lensBlur","lensBlurEnabled"),("fog","fogEnabled"),("relight","relightEnabled")] {
            if crate::image_processing::is_effect_enabled(&parameters,tool,key) {return Err(format!("{tool} is outside the basic Vulkan grading toolset; reset this adjustment"));}
        }
        if parameters["aiPatches"].as_array().is_some_and(|p|p.iter().any(|p|p["visible"].as_bool().unwrap_or(true))) {
            return Err("AI patches are outside the basic Vulkan grading toolset".into());
        }
        // Do not silently reinterpret imported advanced geometry parameters as a neutral grade.
        for key in ["transformDistortion","transformVertical","transformHorizontal","transformRotate","transformAspect","transformXOffset","transformYOffset","rotation","orientationSteps"] {
            if parameters[key].as_f64().unwrap_or(0.0)!=0.0 {return Err("Advanced geometry is outside the basic Vulkan grading toolset".into());}
        }
        if parameters["crop"].is_object() || parameters["flipHorizontal"]==true || parameters["flipVertical"]==true || parameters["guidedPerspective"]["enabled"]==true || parameters["lensDistortionParams"].is_object() || parameters["transformScale"].as_f64().unwrap_or(100.0)!=100.0 {
            return Err("Advanced geometry is outside the basic Vulkan grading toolset".into());
        }
        let lut_path=if crate::image_processing::is_tool_active(&parameters,"lut") {parameters["lutPath"].as_str().unwrap_or("")} else {""};
        let lut_key=format!("{}:{}:{}",lut_path,parameters["lutSize"],parameters["lutData"]);
        if self.resources.lut_key!=lut_key {
            self.resources.lut=if lut_path.is_empty() {None} else {
                let data=if let Some(array)=parameters["lutData"].as_array() {
                    let size=parameters["lutSize"].as_u64().ok_or("Missing LUT size")? as u32;
                    let data=array.iter().map(|v|v.as_f64().filter(|v|v.is_finite()).map(|v|v as f32).ok_or("Invalid LUT value")).collect::<Result<Vec<_>,_>>()?;
                    Lut {size,data}
                } else {
                    let bytes=std::fs::read(lut_path).map_err(|e|format!("Cannot load LUT: {e}"))?;
                    let ext=std::path::Path::new(lut_path).extension().and_then(|e|e.to_str()).unwrap_or("cube").to_lowercase();
                    // Validate the allocation bound before passing through the exact upstream parser.
                    if ext=="cube" { for line in String::from_utf8_lossy(&bytes).lines() {
                        let words:Vec<_>=line.split_whitespace().collect();
                        if words.first()==Some(&"LUT_3D_SIZE") && words.get(1).and_then(|v|v.parse::<u32>().ok()).is_none_or(|v|!(2..=256).contains(&v)) {return Err("LUT size must be between 2 and 256".into());}
                    } }
                    crate::lut_parsers::from_bytes(&bytes,&ext)?
                };
                let expected=(data.size as usize).checked_pow(3).and_then(|n|n.checked_mul(3)).ok_or("LUT dimensions overflow")?;
                if data.size<2||data.size>256||data.data.len()!=expected||data.data.iter().any(|v|!v.is_finite()) {return Err("Invalid LUT resource".into());}
                Some(Arc::new(data))
            };self.resources.lut_key=lut_key;
        }
        let mask_key=format!("{}:{}:{}:{}",parameters["masks"],scale,self.width,self.height);
        if self.resources.mask_key!=mask_key {
            let definitions:Vec<MaskDefinition>=serde_json::from_value(parameters["masks"].clone()).map_err(|e|format!("Invalid mask definitions: {e}"))?;
            let visible:Vec<_>=definitions.iter().filter(|m|m.visible&&!m.sub_masks.is_empty()).collect();
            if visible.len()>crate::image_processing::MAX_MASKS {return Err("Too many visible masks".into());}
            for mask in &visible { for sub in mask.sub_masks.iter().filter(|s|s.visible) {
                if !matches!(sub.mask_type.as_str(),"radial"|"linear"|"brush"|"flow"|"all") {return Err("Only geometric/brush masks are supported in basic Vulkan grading".into());}
            } }
            self.resources.masks=visible.iter().map(|mask|crate::mask_raster::generate_mask_bitmap(mask,self.width,self.height,scale,(0.0,0.0),None).ok_or_else(||"Cannot rasterize selected mask".to_owned())).collect::<Result<Vec<_>,_>>()?;
            self.resources.mask_key=mask_key;
        }
        Ok(())
    }
}
