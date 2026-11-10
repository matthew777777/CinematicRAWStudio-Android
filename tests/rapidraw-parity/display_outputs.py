"""Fixture adapter for retained GLSL output transforms versus generated WGSL."""
from pathlib import Path
import subprocess
import numpy as np

ROOT=Path(__file__).resolve().parents[2]


def prepare_outputs(output):
    functions=(ROOT/'app/src/main/rust/rapidraw-core/src/generated/output_transforms.wgsl').read_text()
    wgsl=functions+'''
@group(0) @binding(0) var<storage,read> inputs: array<vec4<f32>>;
@group(0) @binding(1) var<storage,read_write> outputs: array<vec4<f32>>;
@compute @workgroup_size(64)
fn main(@builtin(global_invocation_id) id: vec3<u32>) {
    let i=id.x;
    if(i>=arrayLength(&inputs)){return;}
    var rgb:vec3<f32>;
    if(inputs[i].w==1.0){rgb=crs_filament_agx(inputs[i].rgb);}
    else{rgb=crs_aces2(inputs[i].rgb);}
    outputs[i]=vec4<f32>(rgb,inputs[i].w);
}
'''
    # Reference is the retained GLSL itself; the generated WGSL is the port under test.
    (output/'reference.wgsl').write_text(wgsl)
    glsl='#version 450\n'+(ROOT/'app/src/main/shaders/display_transform.glsl').read_text()+'\n'+(ROOT/'app/src/main/shaders/aces2_output.glsl').read_text()+'''
layout(local_size_x=64) in;
layout(set=0,binding=0,std430) readonly buffer Input {vec4 samples[];};
layout(set=0,binding=1,std430) buffer Output {vec4 results[];};
void main(){uint i=gl_GlobalInvocationID.x;if(i>=samples.length())return;results[i]=vec4(samples[i].w==1.0?filamentAgx(samples[i].xyz):aces2Output(vec4(samples[i].xyz,1.0)).rgb,samples[i].w);}
'''
    path=output/'display-outputs.comp';path.write_text(glsl)
    subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1',str(path),'-o',str(output/'translated.spv')],check=True)
    rng=np.random.default_rng(20261103)
    data=np.column_stack((rng.uniform(-0.1,32,(4233,3)),np.where(np.arange(4233)%2,1,2))).astype(np.float32)
    levels=[0,1e-6,0.0031308,0.18,1,2,16,256,1024]
    for i,level in enumerate(levels):
        for mode in [1,2]:data[i*2+mode-1]=[level,level,level,mode]
    for count in [1,63,65,4233]:data[:count].tofile(output/f'{count}-input.f32')
