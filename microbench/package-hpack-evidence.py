#!/usr/bin/env python3
"""Package local HPACK evidence deterministically and verify every extracted byte."""
import gzip
import hashlib
import io
from pathlib import Path
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent
EVIDENCE = ROOT / 'microbench/results/hpack'
OUTPUT = ROOT / 'microbench/hpack-benchmark-evidence.tar.gz'
MANIFEST = 'microbench/results/hpack/ARCHIVE-SHA256SUMS'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    files = sorted(p for p in EVIDENCE.rglob('*')
                   if '__pycache__' not in p.parts and p.suffix != '.pyc'
                   and p.relative_to(ROOT).as_posix() != MANIFEST)
    if any(p.is_symlink() or not (p.is_file() or p.is_dir()) for p in files):
        raise ValueError('Evidence must contain only regular files and directories')
    files = [p for p in files if p.is_file()]
    if not files:
        raise ValueError('Extract the evidence archive before rebuilding it')
    hashes = {p.relative_to(ROOT).as_posix(): sha256(p) for p in files}
    manifest = ''.join(f'{digest}  {name}\n' for name, digest in hashes.items()).encode()

    def build(destination):
        with destination.open('wb') as raw:
            with gzip.GzipFile(filename='', mode='wb', fileobj=raw,
                               compresslevel=9, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode='w', format=tarfile.PAX_FORMAT) as archive:
                    for name in sorted([*hashes, MANIFEST]):
                        info = tarfile.TarInfo(name)
                        # Normalize ownership, timestamps and modes, retaining executable scripts.
                        info.mode = 0o644
                        if name == MANIFEST:
                            info.size = len(manifest)
                            archive.addfile(info, io.BytesIO(manifest))
                        else:
                            path = ROOT / name
                            info.size = path.stat().st_size
                            if path.stat().st_mode & 0o111:
                                info.mode = 0o755
                            with path.open('rb') as source:
                                archive.addfile(info, source)

    with tempfile.TemporaryDirectory(prefix='hpack-evidence-') as temporary:
        temp = Path(temporary)
        first, second = temp / 'first.tar.gz', temp / 'second.tar.gz'
        build(first)
        build(second)
        if sha256(first) != sha256(second):
            raise ValueError('Archive builds are not deterministic')
        if first.stat().st_size >= 100_000_000:
            raise ValueError('Archive must be smaller than 100 MB')
        extracted = temp / 'extracted'
        with tarfile.open(first, 'r:gz') as archive:
            # Only the root-relative regular-file names generated above are allowed.
            if archive.getnames() != sorted([*hashes, MANIFEST]):
                raise ValueError('Unexpected archive paths')
            archive.extractall(extracted)
        if (extracted / MANIFEST).read_bytes() != manifest:
            raise ValueError('Extracted manifest mismatch')
        for name, expected in hashes.items():
            if sha256(extracted / name) != expected or sha256(ROOT / name) != expected:
                raise ValueError(f'Evidence changed or extraction mismatch: {name}')
        # Copy only after all integrity and reproducibility checks pass.
        with first.open('rb') as source, OUTPUT.open('wb') as destination:
            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                destination.write(chunk)
    print(f'{sha256(OUTPUT)}  {OUTPUT.relative_to(ROOT)}')
    print(f'{len(hashes)} original files verified; {OUTPUT.stat().st_size} bytes; '
          'two deterministic builds and extraction verified')


if __name__ == '__main__':
    main()
