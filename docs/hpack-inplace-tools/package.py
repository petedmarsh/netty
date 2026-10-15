#!/usr/bin/env python3
"""Deterministic source/evidence archive; original evidence is never modified."""
import gzip
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile

repo = Path(__file__).resolve().parents[2]
evidence = Path(sys.argv[1]).resolve()
files = {}
omitted = {}
for p in sorted(evidence.rglob('*')):
    if not p.is_file():
        continue
    rel = p.relative_to(evidence)
    if p.suffix in {'.jar', '.class', '.pyc'} or any(x in rel.parts for x in ('__pycache__', '.m2', 'target', '.cache', 'caches')):
        omitted[str(rel)] = hashlib.sha256(p.read_bytes()).hexdigest()
    else:
        files['evidence/' + rel.as_posix()] = p.read_bytes()
audit = json.loads((evidence / 'source-proof/audit.json').read_text())
for name, digest in audit['sources'].items():
    data = (repo / name).read_bytes()
    assert hashlib.sha256(data).hexdigest() == digest, name
    files['sources/' + name] = data
files['OMITTED-BINARIES.json'] = (json.dumps(omitted, indent=2, sort_keys=True) + '\n').encode()
files['README.txt'] = b'''Portable HPACK evidence. Original evidence bytes and absolute-path provenance are preserved.
Use docs/hpack-inplace-tools/verify.py against this archive for the portable raw-data audit.
Full original analysis-audit.py verifies external frozen inventory, git identity and binary classes;
it requires the original binaries/paths (jars, classes, Maven dependencies), deliberately excluded here.
freeze.json and source-proof/audit.json retain their hashes. OMITTED-BINARIES.json records local omissions.
Commands are in schedule.json and ledger.jsonl; readiness/build/test logs retain provenance and failures.
No new performance measurements or test executions were performed for packaging.
Archive: sorted names, uid/gid 0, empty owner names, mtime 0, gzip mtime 0, no original gzip filename.
'''
files['MANIFEST.json'] = (json.dumps({n: hashlib.sha256(d).hexdigest() for n, d in sorted(files.items())}, indent=2) + '\n').encode()
out = repo / 'docs/hpack-inplace-evidence.tar.gz'
with out.open('wb') as f:
    with gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0, compresslevel=9) as gz:
        with tarfile.open(fileobj=gz, mode='w', format=tarfile.PAX_FORMAT) as tar:
            for name, data in sorted(files.items()):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                info.mode = 0o644
                tar.addfile(info, io.BytesIO(data))
print(out, out.stat().st_size, hashlib.sha256(out.read_bytes()).hexdigest())
