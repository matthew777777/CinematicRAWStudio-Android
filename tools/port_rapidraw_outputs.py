#!/usr/bin/env python3
"""Preserve CRS Filament AgX / OCIO ACES output math in the new WGSL engine.

Compile the retained GLSL functions, translate their SPIR-V with pinned Naga,
and remove only the fixture input/output wrapper. Callers select the output
at the same scene-linear boundary inside RapidRAW's main stage.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--naga',required=True);args=parser.parse_args()
    directory=ROOT/'app/src/main/rust/rapidraw-core/src/generated';directory.mkdir(parents=True,exist_ok=True)
    agx=ROOT/'app/src/main/shaders/display_transform.glsl';aces=ROOT/'app/src/main/shaders/aces2_output.glsl'
    source='#version 450\n'+agx.read_text()+'\n'+aces.read_text()+'''
layout(local_size_x=64) in;
layout(set=0,binding=0,std430) readonly buffer Inputs { vec4 samples[]; };
layout(set=0,binding=1,std430) buffer Outputs { vec4 results[]; };
void main(){uint i=gl_GlobalInvocationID.x;if(i>=samples.length())return;results[i]=vec4(samples[i].w==1.0?filamentAgx(samples[i].xyz):aces2Output(vec4(samples[i].xyz,1.0)).rgb,samples[i].w);}
'''
    with tempfile.TemporaryDirectory() as folder:
        folder=Path(folder);glsl=folder/'outputs.comp';spv=folder/'outputs.spv';wgsl=folder/'outputs.wgsl'
        glsl.write_text(source)
        subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1',str(glsl),'-o',str(spv)],check=True,stdout=subprocess.PIPE)
        subprocess.run(['spirv-val','--target-env','vulkan1.1',str(spv)],check=True)
        subprocess.run([args.naga,str(spv),str(wgsl)],check=True)
        text=wgsl.read_text();start=text.index('\nfn ')+1;end=text.index('\nfn main_1(')
        functions=text[start:end]
        agx_name=re.search(r'fn (filamentAgx\w*)\(',functions)[1];aces_name=re.search(r'fn (aces2Output\w*)\(',functions)[1]
        functions+='''
fn crs_filament_agx(color: vec3<f32>) -> vec3<f32> {
    var value = color;
    return AGX_NAME(&value);
}
fn crs_aces2(color: vec3<f32>) -> vec3<f32> {
    var value = vec4<f32>(color, 1.0);
    return ACES_NAME(&value).rgb;
}
'''.replace('AGX_NAME',agx_name).replace('ACES_NAME',aces_name)
        (directory/'output_transforms.wgsl').write_text('// Generated from retained CRS output transforms. AgX Apache-2.0; ACES OCIO BSD-3-Clause.\n'+functions)
        (directory/'output-transforms-source.json').write_text(json.dumps(dict(naga=subprocess.check_output([args.naga,'--version'],text=True).strip(),
            sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [agx,aces]},
            adapter='GLSL -> SPIR-V -> WGSL; fixture storage/entry wrapper removed; pointer-call wrappers added; math preserved'),indent=2)+'\n')
    print('Generated retained AgX / ACES WGSL functions')


if __name__=='__main__':main()
