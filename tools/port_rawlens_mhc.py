#!/usr/bin/env python3
"""Copy exact coherent MHC arithmetic; adapt only final camera RGB store."""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
REF=ROOT/'third_party/rawlens-mhc-reference'
OUT=ROOT/'app/src/main/assets/mhc'
p=argparse.ArgumentParser();p.add_argument('--check',action='store_true');p.add_argument('--compile',action='store_true');args=p.parse_args()
manifest=json.loads((REF/'manifest.json').read_text())
for name,digest in manifest['files'].items(): assert hashlib.sha256((REF/Path(name).name).read_bytes()).hexdigest()==digest,name
src=(REF/'vf_mhcyuv.comp').read_text()
params=src[src.index('struct FusedParams {'):src.index('// Reserved for upcoming LUT support:')]
math=src[src.index('// Green edge-gate band,'):src.index('// ---- direct-encode record grade:')]
adapter='''// SPDX-License-Identifier: GPL-3.0-or-later
// Generated: exact RawLens coherent-green reconstruction; camera-linear output adapter.
#version 450
layout(local_size_x=16,local_size_y=16) in;
layout(binding=0) readonly buffer Bayer { uint codes[]; };
layout(binding=1,rgba16f) uniform writeonly image2D cameraRgb;
'''+params+math+'''
void main() {
    ivec2 s=ivec2(gl_GlobalInvocationID.xy);
    if(any(greaterThanEqual(s,u.cDims))) return;
    imageStore(cameraRgb,s,vec4(mhcRgb(s),1.0));
}
'''
files={'green.comp':(REF/'vf_mhc_green.comp').read_text(),'camera.comp':adapter}
for name,data in files.items():
    target=OUT/name
    if args.check: assert target.read_text()==data,name
    else:target.write_text(data)
    if args.compile:
        compiler=Path('/Users/monikamalinowska/Library/Android/sdk/ndk/27.0.12077973/shader-tools/darwin-x86_64/glslc')
        subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute',str(target),'-o',str(target.with_suffix('.spv'))],check=True)
print('Verified' if args.check else 'Generated','exact RawLens MHC green/chroma routines and camera RGB adapter')
