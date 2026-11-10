#!/usr/bin/env python3
"""Record/check the exact vendored encoder/decoder snapshot; no network side effects."""
import argparse,hashlib,json,re
from pathlib import Path
root=Path(__file__).resolve().parents[1];vendor=root/'third_party/craw'
p=argparse.ArgumentParser();p.add_argument('--record',action='store_true');args=p.parse_args()
source=(vendor/'SOURCE.md').read_text()
commits=dict(re.findall(r'\| (encoder|decoder \+ tools/common) \| [^|]+ \| ([0-9a-f]{40}) \|',source))
assert len(commits)==2,'Missing CRAW source pins'
files={str(path.relative_to(vendor)):hashlib.sha256(path.read_bytes()).hexdigest() for group in ['encoder','decoder'] for path in sorted((vendor/group).rglob('*')) if path.is_file()}
manifest=dict(commits=commits,sha256=files)
path=vendor/'SOURCE-MANIFEST.json'
if args.record:path.write_text(json.dumps(manifest,indent=2)+'\n')
else:assert json.loads(path.read_text())==manifest,'Vendored CRAW differs from pinned snapshot'
print(('Recorded' if args.record else 'Verified')+f' {len(files)} CRAW source files; encoder {commits["encoder"][:12]}, decoder {commits["decoder + tools/common"][:12]}')
