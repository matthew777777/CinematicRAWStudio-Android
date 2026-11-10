#!/usr/bin/env python3
"""Execute pinned TypeScript curve math in macOS JavaScriptCore for fixtures.

Only the parameter/return type annotations are stripped from this one function.
Fixtures compare float32 values actually passed into the upstream shader ABI.
"""
import ctypes as c
import ctypes.util
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]


def main():
    source = (ROOT / "third_party/rapidraw-reference/src/components/adjustments/Curves.tsx").read_text()
    function = source[source.index("function buildParametricPoints"):source.index("function getCurvePath")]
    javascript = function.replace("settings: ParametricCurveSettings", "settings").replace(": Array<Coord>", "")
    javascript = javascript.replace("(v: number, x: number): number", "(v, x)").replace("(v: number)", "(v)")
    defaults = json.loads((ROOT / "app/src/main/assets/rapidraw/defaults.json").read_text())["parametricCurve"]["luma"]
    cases = [defaults]
    for key, values in {"shadows": [-100, 100], "darks": [-100, 100], "lights": [-100, 100],
                        "highlights": [-100, 100], "blackLevel": [100], "whiteLevel": [-100]}.items():
        for value in values: cases.append(dict(defaults, **{key:value}))
    cases += [dict(defaults, shadows=-45, darks=72, lights=-35, highlights=91, split1=10,split2=30,split3=90),
              dict(defaults, shadows=100,darks=-100,lights=100,highlights=-100,split1=30,split2=50,split3=60)]
    code = javascript + "\nJSON.stringify(" + json.dumps(cases) + ".map(buildParametricPoints))"
    js = c.CDLL(ctypes.util.find_library("JavaScriptCore"))
    js.JSGlobalContextCreate.argtypes=[c.c_void_p];js.JSGlobalContextCreate.restype=c.c_void_p
    js.JSStringCreateWithUTF8CString.argtypes=[c.c_char_p];js.JSStringCreateWithUTF8CString.restype=c.c_void_p
    js.JSEvaluateScript.argtypes=[c.c_void_p,c.c_void_p,c.c_void_p,c.c_void_p,c.c_int,c.POINTER(c.c_void_p)];js.JSEvaluateScript.restype=c.c_void_p
    js.JSValueToStringCopy.argtypes=[c.c_void_p,c.c_void_p,c.POINTER(c.c_void_p)];js.JSValueToStringCopy.restype=c.c_void_p
    js.JSStringGetMaximumUTF8CStringSize.argtypes=[c.c_void_p];js.JSStringGetMaximumUTF8CStringSize.restype=c.c_size_t
    js.JSStringGetUTF8CString.argtypes=[c.c_void_p,c.c_char_p,c.c_size_t];js.JSStringGetUTF8CString.restype=c.c_size_t
    js.JSStringRelease.argtypes=[c.c_void_p];js.JSGlobalContextRelease.argtypes=[c.c_void_p]
    context=js.JSGlobalContextCreate(None);script=js.JSStringCreateWithUTF8CString(code.encode());error=c.c_void_p()
    value=js.JSEvaluateScript(context,script,None,None,1,c.byref(error))
    if error.value: raise RuntimeError("JavaScript reference evaluation failed")
    string=js.JSValueToStringCopy(context,value,c.byref(error));size=js.JSStringGetMaximumUTF8CStringSize(string)
    buffer=c.create_string_buffer(size);js.JSStringGetUTF8CString(string,buffer,size)
    points=json.loads(buffer.value)
    js.JSStringRelease(string);js.JSStringRelease(script);js.JSGlobalContextRelease(context)
    def bits(value): return struct.unpack('<I',struct.pack('<f',value))[0]
    data=dict(commit="f1d71a69eef813432db214f77969f567c41ff1d7",symbol="buildParametricPoints",
        source_sha256=hashlib.sha256(function.encode()).hexdigest(),cases=[dict(settings=settings,
            points=output,float32_bits=[[bits(p['x']),bits(p['y'])] for p in output]) for settings,output in zip(cases,points)])
    for target in [ROOT/'app/src/test/resources/rapidraw-parametric-curves.json',ROOT/'app/src/androidTest/assets/rapidraw-parametric-curves.json']:
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(data,indent=2)+'\n')
    print('Generated',len(cases),'executed upstream curve fixtures')


if __name__ == '__main__': main()
