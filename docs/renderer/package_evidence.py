#!/usr/bin/env python3
"""Deterministic text-only evidence archive; never modifies the source tree."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import tarfile


def package(root, output):
    files = {}
    for p in sorted(root.rglob('*')):
        rel = p.relative_to(root)
        if not p.is_file():
            continue
        if set(rel.parts) & {'m2', '__pycache__', 'presentation'} or p.suffix in {'.jar', '.class', '.pyc'} or p.name == 'runner.lock':
            # Frozen hashes of binaries remain in the unmodified freeze and logs.
            continue
        if p.is_symlink():
            raise ValueError('Unexpected symlink: ' + str(p))
        data = p.read_bytes()
        data.decode('utf-8')  # Refuse accidental binary inclusion.
        files[str(rel)] = data
    manifest = dict(original_root=str(root.resolve()),
                    scope='Text extraction only; full frozen-artifact verification requires original binaries and paths.',
                    exclusions=['m2/', '__pycache__/', 'presentation/ (superseded by docs/renderer)', '*.jar', '*.class', '*.pyc', 'runner.lock'],
                    files={n: hashlib.sha256(b).hexdigest() for n, b in files.items()})
    files['EXTRACTION.json'] = (json.dumps(manifest, indent=2, sort_keys=True)+'\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('wb') as f, gzip.GzipFile(filename='', mode='wb', fileobj=f, mtime=0, compresslevel=9) as gz:
        with tarfile.open(fileobj=gz, mode='w', format=tarfile.USTAR_FORMAT) as tar:
            for name, data in sorted(files.items()):
                entry = tarfile.TarInfo('hpack-series-evidence/' + name)
                entry.size = len(data)
                entry.mode = 0o644
                entry.mtime = entry.uid = entry.gid = 0
                tar.addfile(entry, io.BytesIO(data))
    print(f'{len(files)} files; {sum(map(len, files.values()))} extracted bytes; {output.stat().st_size} archive bytes')
    print(hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--evidence', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    package(a.evidence, a.output)
