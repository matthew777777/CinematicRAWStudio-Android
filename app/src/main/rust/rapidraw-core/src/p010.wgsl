// SPDX-License-Identifier: AGPL-3.0-or-later
// Delivery only: aspect-fit the already graded RGB16F frame to limited-range BT.709 P010.
struct Delivery { source:vec2<u32>, output:vec2<u32> }
@group(0) @binding(0) var graded:texture_2d<f32>;
@group(0) @binding(1) var<storage,read_write> words:array<u32>;
@group(0) @binding(2) var<uniform> dimensions:Delivery;
fn sample_rgb(pixel:vec2<u32>)->vec3<f32> {
    let source=vec2<f32>(dimensions.source);
    let output=vec2<f32>(dimensions.output);
    let scale=min(output.x/source.x,output.y/source.y);
    let offset=(output-source*scale)*0.5;
    let center=vec2<f32>(pixel)+0.5;
    if(any(center<offset)||any(center>=offset+source*scale)){return vec3<f32>(0.0);}
    let p=clamp((center-offset)/scale-0.5,vec2<f32>(0.0),source-1.0);
    let lo=vec2<i32>(floor(p));let hi=min(lo+1,vec2<i32>(dimensions.source)-1);
    let weight=fract(p);
    let a=clamp(textureLoad(graded,lo,0).rgb,vec3<f32>(0.0),vec3<f32>(1.0));
    let b=clamp(textureLoad(graded,vec2<i32>(hi.x,lo.y),0).rgb,vec3<f32>(0.0),vec3<f32>(1.0));
    let c=clamp(textureLoad(graded,vec2<i32>(lo.x,hi.y),0).rgb,vec3<f32>(0.0),vec3<f32>(1.0));
    let d=clamp(textureLoad(graded,hi,0).rgb,vec3<f32>(0.0),vec3<f32>(1.0));
    return mix(mix(a,b,weight.x),mix(c,d,weight.x),weight.y);
}
fn yuv(rgb:vec3<f32>)->vec3<f32> {
    let y=dot(rgb,vec3<f32>(0.2126,0.7152,0.0722));
    return vec3<f32>(y,(rgb.b-y)/1.8556,(rgb.r-y)/1.5748);
}
fn code(value:f32,low:f32,high:f32)->u32 {return u32(clamp(floor(value+0.5),low,high))<<6u;}
@compute @workgroup_size(8,8)
fn main(@builtin(global_invocation_id) id:vec3<u32>) {
    let pixel=id.xy*2u;
    if(any(pixel>=dimensions.output)){return;}
    let a=yuv(sample_rgb(pixel));let b=yuv(sample_rgb(pixel+vec2<u32>(1,0)));
    let c=yuv(sample_rgb(pixel+vec2<u32>(0,1)));let d=yuv(sample_rgb(pixel+vec2<u32>(1,1)));
    let stride=dimensions.output.x/2u;
    let index=pixel.y*stride+id.x;
    words[index]=code(64.0+876.0*a.x,64.0,940.0)|(code(64.0+876.0*b.x,64.0,940.0)<<16u);
    words[index+stride]=code(64.0+876.0*c.x,64.0,940.0)|(code(64.0+876.0*d.x,64.0,940.0)<<16u);
    let uv=(a.yz+b.yz+c.yz+d.yz)*224.0+512.0;
    let chroma=dimensions.output.x*dimensions.output.y/2u+id.y*stride+id.x;
    words[chroma]=code(uv.x,64.0,960.0)|(code(uv.y,64.0,960.0)<<16u);
}
