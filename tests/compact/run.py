#!/usr/bin/env python3
"""Host checks of the same Compact adapter compiled into the Android app."""
import pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[2]
subprocess.run(['python3','tools/verify_craw_sources.py'],cwd=root,check=True)
with tempfile.TemporaryDirectory(prefix='crs-compact-build-') as folder:
    binary=pathlib.Path(folder)/'compact-tests'
    subprocess.run(['c++','-std=c++20','-O2','-pthread','-Iapp/src/main/cpp','-Ithird_party/craw/encoder/include','-Ithird_party/craw/decoder/include','-Ithird_party/craw/decoder/src',
        'tests/compact/roundtrip.cpp','app/src/main/cpp/craw_compact.cpp','third_party/craw/encoder/src/Encoder.cpp','third_party/craw/encoder/src/Writer.cpp','third_party/craw/decoder/src/Decoder.cpp','third_party/craw/decoder/src/Reader.cpp','-o',str(binary)],cwd=root,check=True)
    subprocess.run([str(binary)],cwd=root,check=True)
