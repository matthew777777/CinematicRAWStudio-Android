#!/usr/bin/env python3
"""Generate source evidence for the pinned RapidRAW editing inventory.

Lexical extraction, not a TypeScript evaluator: retain dynamic expressions and
source locations rather than guessing runtime values or platform availability.
"""
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REF = ROOT / "third_party/rapidraw-reference"
OUT = ROOT / "docs/rapidraw-inventory"


def balanced(text, start, opening="{", closing="}"):
    depth, quote, escaped = 0, None, False
    for end in range(start, len(text)):
        char = text[end]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in "'\"`":
            quote = char
        elif char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return text[start:end + 1]
    raise ValueError("Unbalanced source")


def flatten(value, prefix=""):
    if isinstance(value, dict) and value:
        for key, item in value.items():
            yield from flatten(item, f"{prefix}.{key}" if prefix else key)
    else:
        yield prefix, value


def main():
    subprocess.run(["python3", str(ROOT / "tools/verify_rapidraw_reference.py")], check=True)
    manifest = json.loads((REF / "manifest.json").read_text())
    sources = {name: (REF / name).read_text() for name in manifest["sha256"]
               if Path(name).suffix in {".ts", ".tsx", ".rs", ".wgsl", ".css"}}
    def location(name, position):
        return dict(path=name, line=sources[name].count("\n", 0, position) + 1)
    definitions = sources["src/utils/adjustments.ts"]
    enums = {}
    for name, source in sources.items():
        if not name.endswith((".ts", ".tsx")):
            continue
        for match in re.finditer(r"export enum (\w+)\s*\{", source):
            block = balanced(source, match.end() - 1)
            values = dict(re.findall(r"(\w+)\s*=\s*['\"]([^'\"]+)['\"]", block))
            enums[match[1]] = dict(values=values, source=location(name, match.start()))
    marker = definitions.index("export const ADJUSTMENT_SECTIONS:")
    section_text = balanced(definitions, definitions.index("{", marker))
    sections = {}
    for match in re.finditer(r"\b(\w+):\s*\[([^\]]*)\]", section_text):
        keys = []
        for token in match[2].split(","):
            token = token.strip()
            if not token:
                continue
            if token.startswith("..."):
                array = re.search(r"export const " + re.escape(token[3:]) + r"\s*=\s*\[([\s\S]*?)\]", definitions)
                if not array:
                    raise ValueError("Unresolved spread " + token)
                keys.extend(re.findall(r"['\"]([^'\"]+)['\"]", array[1]))
            elif token.startswith(("'", '"')):
                keys.append(token.strip("'\""))
            else:
                enum, field = token.split(".")
                keys.append(enums[enum]["values"][field])
        sections[match[1]] = keys
    defaults = json.loads((REF / "src/utils/defaultAdjustments.json").read_text())
    controls = []
    for name, source in sources.items():
        if not name.endswith(".tsx"):
            continue
        for match in re.finditer(r"<(Slider|ColorWheel|Switch|Toggle|Select|Dropdown)\b", source):
            cursor = match.end()
            while cursor < len(source) and not source.startswith("/>", cursor) and source[cursor] != ">":
                if source[cursor] == "{":
                    cursor += len(balanced(source, cursor))
                elif source[cursor] in "'\"":
                    quote = source[cursor]
                    cursor += 1
                    while cursor < len(source) and source[cursor] != quote:
                        cursor += 2 if source[cursor] == "\\" else 1
                    cursor += 1
                else:
                    cursor += 1
            block = source[match.start():cursor + 2]
            properties = {}
            for prop in ["label", "min", "max", "step", "value", "defaultValue", "speedEditId", "options", "disabled"]:
                found = re.search(r"\b" + prop + r"=\{", block)
                if found:
                    properties[prop] = balanced(block, found.end() - 1)[1:-1]
            controls.append(dict(kind=match[1], source=location(name, match.start()), properties=properties))
    parameters = []
    for key, default in defaults.items():
        references = []
        pattern = re.compile(r"(?<![\w])" + re.escape(key) + r"(?![\w])")
        for name, source in sources.items():
            for match in pattern.finditer(source):
                loc = location(name, match.start())
                # At most one reference per line, without omitting files.
                if not references or references[-1] != loc:
                    references.append(loc)
        parameters.append(dict(key=key, default=default,
            sections=[section for section, keys in sections.items() if key in keys],
            references=references,
            processing_status="isolated shader parity only" if key == "exposure" else "not ported",
            ui_status="not ported"))
    functions = []
    for name, source in sources.items():
        if name.endswith((".rs", ".wgsl")):
            for match in re.finditer(r"\bfn\s+(\w+)\s*(?:<[^\n]*?>)?\s*\(", source):
                functions.append(dict(symbol=match[1], source=location(name, match.start())))
    interface = definitions.index("export interface Adjustments {")
    block = balanced(definitions, definitions.index("{", interface))
    declared = re.findall(r"^  ([\wé]+)\??:", block, re.M)
    host = sources["src-tauri/src/image_processing.rs"]
    scale_start = host.index("const SCALES:")
    scales = dict((key, float(value)) for key, value in re.findall(r"([\wé]+):\s*([\d.]+)", balanced(host, host.index("{", scale_start))))
    initialization = {}
    for match in re.finditer(r"export const (INITIAL_\w+|DEFAULT_\w+|createRelightLight)\s*(?::[^=\n]+)?=", definitions):
        ending = definitions.find(";", match.end())
        initialization[match[1]] = dict(source=location("src/utils/adjustments.ts", match.start()),
                                       expression=definitions[match.end():ending].strip())
    catalog = dict(commit=manifest["commit"], repository=manifest["repository"],
        extraction="Lexical source evidence; dynamic JSX expressions are retained, not evaluated. References are not proof of a working feature.",
        counts=dict(reference_files=len(manifest["sha256"]), top_level_defaults=len(defaults),
                    default_leaf_paths=len(list(flatten(defaults))), jsx_control_instances=len(controls)),
        sections=sections, enums=enums, parameters=parameters, controls=controls,
        host_scale_divisors=scales, initialization_expressions=initialization,
        declared_fields_without_json_default=sorted(set(declared) - set(defaults)), functions=functions)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
    base = f"{manifest['repository']}/blob/{manifest['commit']}/"
    lines = ["# Pinned parameter defaults", "", "Generated by `python3 tools/inventory_rapidraw.py`. Arrays are retained as complete values. Empty maps and lists are intentional defaults. UI ranges live in `catalog.json` as source expressions; do not infer shader units from them.", "", "| Parameter path | Default |", "|---|---|"]
    for key, value in flatten(defaults):
        lines.append(f"| `{key}` | `{json.dumps(value, ensure_ascii=False, separators=(',', ':'))}` |")
    lines.extend(["", "Declared fields without a JSON default (conditional/transient or loader-generated; inspect the source):", "", ", ".join(f"`{key}`" for key in catalog["declared_fields_without_json_default"]), ""])
    (OUT / "parameters.md").write_text("\n".join(lines))
    lines = ["# Source control instances", "", "Generated lexical inventory of slider, wheel, switch/toggle, select/dropdown JSX instances. Conditional branches and repeated templates are separate instances. Buttons, canvas gestures, context menus, and runtime availability are covered by the reviewed feature matrix and frozen source, not inferred from this list.", "", "| Source | Widget | Label/value | Min | Max | Step |", "|---|---|---|---|---|---|"]
    def cell(value):
        return str(value).replace("|", "\\|").replace("\n", " ")
    for control in controls:
        loc, props = control["source"], control["properties"]
        link = f"[{loc['path']}:{loc['line']}]({base}{loc['path']}#L{loc['line']})"
        lines.append("| " + " | ".join([link, control["kind"], cell(props.get("label", props.get("value", "—"))), *[cell(props.get(k, "—")) for k in ["min", "max", "step"]]]) + " |")
    (OUT / "controls.md").write_text("\n".join(lines) + "\n")
    print(json.dumps(catalog["counts"]))


if __name__ == "__main__":
    main()
