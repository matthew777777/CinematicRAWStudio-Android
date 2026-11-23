#!/usr/bin/env python3
"""Use the configured RawLens compilers to analyze CRS Vulkan modules."""
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
RAWLENS = Path(os.environ.get("RAWLENS_ROOT", ROOT.parent / "RawLens")).expanduser().resolve()


def aoc(arguments):
    compiler = RAWLENS / ".local/adreno-offline-compiler/7.0.26/aoc"
    if not compiler.is_file():
        raise SystemExit(f"Licensed Adreno compiler missing at {compiler}")
    *options, shader = arguments
    data = Path(shader).read_bytes()
    index = 20  # SPIR-V header is five words.
    entries = []
    while index + 4 <= len(data):
        opcode, words = struct.unpack_from("<HH", data, index)
        next_index = index + 4 * words
        if words == 0 or next_index > len(data):
            raise SystemExit(f"Malformed SPIR-V instruction in {shader}")
        if opcode == 15:  # OpEntryPoint: execution model, function ID, name.
            entries.append(data[index + 12:next_index].split(b"\0", 1)[0].decode("utf-8"))
        index = next_index
    if len(entries) != 1:
        raise SystemExit(f"Expected one SPIR-V entry point in {shader}")
    command = [
        "docker", "--context", os.environ.get("RAWLENS_AOC_DOCKER_CONTEXT", "colima-rawlens-shaders"),
        "run", "--rm", "-i", "--platform=linux/amd64", "--network=none", "--read-only",
        "--tmpfs", "/tmp:exec,size=512m",
        "--mount", f"type=bind,source={compiler.parent},target=/aoc,readonly",
        "--env", "HOME=/tmp", "--workdir", "/tmp", "--entrypoint", "/bin/sh",
        os.environ.get("RAWLENS_AOC_IMAGE", "rawlens-aoc:7.0.26"),
        "-c", 'cat > shader.spv && exec /aoc/aoc "$@" shader.spv', "sh",
        "-entry_point", entries[0], *options,
    ]
    return subprocess.run(command, input=data).returncode


def main(arguments):
    if arguments and arguments[0] == "--run-aoc":
        return aoc(arguments[1:])
    analyzer_path = RAWLENS / "tools/shader-analysis/analyze.py"
    if not analyzer_path.is_file():
        raise SystemExit(f"RawLens shader analyzer missing at {analyzer_path}; set RAWLENS_ROOT")
    spec = importlib.util.spec_from_file_location("rawlens_shader_analysis", analyzer_path)
    analyzer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(analyzer)
    analyzer.ROOT = ROOT
    with tempfile.TemporaryDirectory() as directory:
        template = Path(directory) / "aoc-command.json"
        template.write_text(json.dumps([
            sys.executable, str(Path(__file__).resolve()), "--run-aoc", "-api=Vulkan",
            "-arch={core}", "-dump=stats_perf", "-output_format=json", "{stage}", "{input}",
        ]))
        sys.argv[1:] = ["--adreno-command", str(template), *arguments]
        return analyzer.main()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
