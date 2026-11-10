// SPDX-License-Identifier: AGPL-3.0-or-later
// Executable bring-up checks for the independent upstream compute graph.
use crs_rapidraw_core::{prepare,render::RapidRenderer,baseline_exposure::{RawExposure,BaselineOptions}};
use std::{path::PathBuf,time::Instant};

fn main()->Result<(),String> {
    let directory=std::env::args().nth(1).ok_or("Usage: render-fixture output-directory")?;
    let directory=PathBuf::from(directory);std::fs::create_dir_all(&directory).map_err(|e|e.to_string())?;
    let width=64u32;let height=32u32;
    let mut pixels=Vec::new();
    for y in 0..height {for x in 0..width {
        let rgb=[x as f32/(width-1) as f32*2.0,y as f32/(height-1) as f32,(x+y) as f32/(width+height-2) as f32,1.0];
        pixels.extend(rgb.map(|v|half::f16::from_f32(v).to_bits()));
    }}
    let mut renderer=RapidRenderer::new(width,height)?;
    println!("Adapter: {}",renderer.adapter_name);
    renderer.upload(&pixels,1)?;
    let identity=[1.0f32,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0];
    let mut mhc_cases=Vec::new();
    for (phase,channels) in [[0,1,2,3],[1,0,3,2],[2,3,0,1],[3,2,1,0]].into_iter().enumerate() {
        let codes:Vec<u16>=(0..width*height).map(|i|64+((i*71+(i/width)*119)%960) as u16).collect();
        renderer.upload_bayer(&codes,channels,[64.0;4],1088.0,&identity,&identity,&[1.0;3])?;
        let words=renderer.camera_words()?;
        for y in 0..height {for x in 0..width {
            let q=((y&1)*2+(x&1)) as i32;
            let c=if q==channels[0] {0} else if q==channels[3] {2} else {1};
            let index=(y*width+x) as usize;
            let expected=half::f16::from_f32(f32::from(codes[index]-64)/1024.0).to_bits();
            if words[index*4+c]!=expected {return Err(format!("MHC phase {phase} changed a measured CFA sample"));}
        }}
        let flat:Vec<u16>=(0..width*height).map(|i|{let x=i%width;let y=i/width;let q=((y&1)*2+(x&1)) as i32;
            if q==channels[0] {832} else if q==channels[3] {320} else {576}}).collect();
        renderer.upload_bayer(&flat,channels,[64.0;4],1088.0,&identity,&identity,&[1.0;3])?;
        let flat_rgb=renderer.camera_words()?;
        let expected=[half::f16::from_f32(0.75).to_bits(),half::f16::from_f32(0.5).to_bits(),half::f16::from_f32(0.25).to_bits(),half::f16::ONE.to_bits()];
        if flat_rgb.chunks_exact(4).any(|pixel|pixel!=expected) {return Err(format!("MHC phase {phase} changed a flat field"));}
        let rgba:Vec<f32>=words.iter().map(|v|half::f16::from_bits(*v).to_f32()).collect();
        if rgba.iter().any(|v|!v.is_finite()||*v<0.0||*v>4.0) {return Err("Invalid MHC reconstruction".into());}
        mhc_cases.push(serde_json::json!({"phase":phase,"channels":channels,"codes":codes,"rgba":rgba}));
    }
    std::fs::write(directory.join("mhc-gpu.json"),serde_json::to_vec(&serde_json::json!({"width":width,"height":height,"adapter":renderer.adapter_name,"cases":mhc_cases})).unwrap()).map_err(|e|e.to_string())?;
    renderer.upload(&pixels,1)?;
    let defaults:serde_json::Value=serde_json::from_str(include_str!("../../../../assets/rapidraw/defaults.json")).map_err(|e|e.to_string())?;
    let mut cases=vec![("default",serde_json::json!({})),
        ("exposure",serde_json::json!({"exposure":1.0})),
        ("tonal",serde_json::json!({"contrast":25,"highlights":-45,"shadows":35})),
        ("detail",serde_json::json!({"sharpness":30,"clarity":25,"structure":20,"dehaze":15})),
        ("noise",serde_json::json!({"lumaNoiseReduction":35,"colorNoiseReduction":40})),
        ("creative",serde_json::json!({"glowAmount":25,"halationAmount":20,"flareAmount":15})),
        ("grain",serde_json::json!({"grainAmount":50})),
        ("monochrome",serde_json::json!({"monochromeEnabled":true,"monochromeFilm":"infrared"})),
        ("agx",serde_json::json!({"toneMapper":"agx"})),
        ("filament",serde_json::json!({})),
        ("aces2",serde_json::json!({}))];
    let mut wheels=defaults["colorGrading"].clone();wheels["shadows"]["hue"]=220.into();wheels["shadows"]["saturation"]=60.into();
    cases.push(("wheels",serde_json::json!({"colorGrading":wheels})));
    let mut curves=defaults["curves"].clone();curves["red"]=serde_json::json!([{"x":0,"y":0},{"x":100,"y":160},{"x":255,"y":255}]);
    cases.push(("curves",serde_json::json!({"curves":curves})));
    let mut hsl=defaults["hsl"].clone();hsl["reds"]["hue"]=75.into();hsl["reds"]["saturation"]=50.into();
    cases.push(("mixer",serde_json::json!({"hsl":hsl})));
    let mask=serde_json::json!({"id":"test","name":"Radial","visible":true,"invert":false,"opacity":100,
        "adjustments":{"exposure":0.8},"subMasks":[{"id":"radial","type":"radial","visible":true,"invert":false,"opacity":100,"mode":"additive",
        "parameters":{"centerX":32,"centerY":16,"radiusX":20,"radiusY":12,"rotation":0,"feather":0.5}}]});
    cases.push(("mask-radial",serde_json::json!({"masks":[mask]})));
    let brush=serde_json::json!({"id":"brush","name":"Brush","visible":true,"invert":false,"opacity":100,"adjustments":{"exposure":-0.8},
        "subMasks":[{"id":"brush1","type":"brush","visible":true,"invert":false,"opacity":100,"mode":"additive","parameters":{"lines":[{"tool":"brush","brushSize":16,"feather":0.5,"points":[{"x":12,"y":12},{"x":42,"y":20}]}]}}]});
    cases.push(("mask-brush",serde_json::json!({"masks":[brush]})));
    let mut cube=Vec::new();for b in 0..2 {for g in 0..2 {for r in 0..2 {cube.extend([1.0-r as f32,g as f32,b as f32]);}}}
    cases.push(("lut",serde_json::json!({"lutPath":"fixture://inverse-red","lutSize":2,"lutData":cube})));
    let mut reference:Option<Vec<u8>>=None;let mut results=Vec::new();
    for (name,patch) in cases {
        let mut parameters=defaults.clone();for (key,value) in patch.as_object().unwrap(){parameters[key]=value.clone();}
        let mut packed=prepare(&parameters.to_string(),true,6504.0,0.0,RawExposure::default(),BaselineOptions::default(),None)?;
        if name=="filament" {packed.set_crs_output(1)?;}
        if name=="aces2" {packed.set_crs_output(2)?;}
        let start=Instant::now();renderer.configure(&parameters.to_string(),1.0,true)?;
        let output=renderer.render_configured8(packed)?;
        let (output_width,output_height)=renderer.output_size();
        let precise=renderer.render16(packed)?;
        let assembled=renderer.render16_gpu_words(packed)?;
        if precise!=assembled {return Err(format!("{name}: GPU frame assembly changed high-precision output bits"));}
        let p010=renderer.render_p010(packed,width,height)?;
        if p010.len()!=width as usize*height as usize*3 {return Err("P010 output size mismatch".into());}
        if p010.chunks_exact(2).any(|p|u16::from_le_bytes([p[0],p[1]])&63!=0) {return Err("P010 words are not MSB-aligned".into());}
        let y_words=width as usize*height as usize;
        for (i,p) in p010.chunks_exact(2).enumerate() {let code=u16::from_le_bytes([p[0],p[1]])>>6;
            if !(64..=if i<y_words {940}else{960}).contains(&code) {return Err("P010 code outside limited range".into());}}

        if precise.len()!=output.len()||precise.iter().any(|v|!half::f16::from_bits(*v).to_f32().is_finite()) {return Err("Invalid high-precision output".into());}
        if name=="default" && !precise.iter().any(|v|{let x=half::f16::from_bits(*v).to_f32()*255.0;x.fract().abs()>0.01}) {return Err("High-precision export collapsed to 8-bit values".into());}
        if output.len()!=output_width as usize*output_height as usize*4{return Err("Output length mismatch".into());}
        let differing=reference.as_ref().map(|baseline|baseline.iter().zip(&output).filter(|(a,b)|a!=b).count()).unwrap_or(0);
        if name=="default"{reference=Some(output.clone());}else if differing==0{return Err(format!("{name} did not affect output"));}
        let ms=start.elapsed().as_secs_f64()*1000.0;
        println!("{name}: {ms:.2} ms; differing components {differing}");
        std::fs::write(directory.join(format!("{name}.rgba8")),&output).map_err(|e|e.to_string())?;
        results.push(serde_json::json!({"case":name,"milliseconds":ms,"differing_components":differing}));
    }
    renderer.configure(&defaults.to_string(),1.0,true)?;
    let restored=renderer.render_configured8(prepare(&defaults.to_string(),true,6504.0,0.0,RawExposure::default(),BaselineOptions::default(),None)?)?;
    if restored!=reference.unwrap() {return Err("Resetting all host effects did not restore the exact default frame".into());}
    std::fs::write(directory.join("report.json"),serde_json::to_vec_pretty(&serde_json::json!({"adapter":renderer.adapter_name,"width":width,"height":height,
        "scope":"Independent graph bring-up on synthetic linear RGBA16F; not reference image parity or throughput acceptance","cases":results})).unwrap()).map_err(|e|e.to_string())?;
    Ok(())
}
