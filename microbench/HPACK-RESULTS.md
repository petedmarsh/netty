# HPACK final results and archived evidence

The retained candidate is iteration2 plus linkedMixed64 (always-mixed hashing),
not the rejected ring or iteration3 experiments. The table below is copied from
the final `linkedMixed-retained/UPSTREAM.md` report. This is **not a blanket
upstream non-regression claim**: collision cases have positive point estimates
and intervals spanning zero; repeated-header estimates are also uncertain.
Three-fork controls provide less evidence than six-fork matched cases. These
microbenchmarks do not establish production latency or throughput gains.

## Immutable evidence

`microbench/hpack-benchmark-evidence.tar.gz` contains all 4,395 original files
under `microbench/results/hpack`, excluding `__pycache__` directories and `.pyc`
files. Original bytes and historical hash manifests are unchanged. Included are
commands, raw benchmark JSON, analysis/runner scripts, test logs, source
snapshots, rejected experiments, and final validation evidence. The additional
`ARCHIVE-SHA256SUMS` manifest hashes every original archived file using
repository-root-relative paths.

Archive size: **6,262,281 bytes** (below 100 MB).

Archive SHA256:

```text
a98289a168df8e223f31b5c47d7e7075cb15daf125f3ba67666cab6b9d7f7f12  microbench/hpack-benchmark-evidence.tar.gz
```

From the repository root, verify the archive, then extract it to restore all
historical relative links in `microbench/HPACK.md`:

```sh
printf '%s  %s\n' a98289a168df8e223f31b5c47d7e7075cb15daf125f3ba67666cab6b9d7f7f12 microbench/hpack-benchmark-evidence.tar.gz | shasum -a 256 -c -
tar -xzf microbench/hpack-benchmark-evidence.tar.gz
shasum -a 256 -c microbench/results/hpack/ARCHIVE-SHA256SUMS
```

`python3 microbench/package-hpack-evidence.py` rebuilds the archive from local
extracted evidence. It normalizes tar metadata and gzip timestamps, builds twice
and compares SHA256, extracts into a temporary directory, verifies every file
against its original hash, and enforces the size limit before publishing.
Both deterministic builds and all 4,395 extraction hashes passed at packaging.
Local originals are retained untracked, with `/microbench/results/` excluded
only in `.git/info/exclude`; they are not replaced or deleted by packaging.
On another checkout, optionally add that same local exclusion after extraction.

Final code tests already passed before this documentation/packaging change;
they were not rerun. See the archived final-commit validation evidence for test
commands and logs. Packaging integrity and staged diff checks were run separately.

## LinkedMixed versus upstream, ring campaign only

Arithmetic fork means, ns / allocated B per four-field block (constructor per encoder).
Change is the paired geometric timing ratio, not the ratio of displayed arithmetic means.

| Scenario / table bytes | Forks each | Upstream ns / B | linkedMixed ns / B | Change [95% interval] |
|---|---:|---:|---:|---:|
| CHURN / 4096 | 6 | 1463.93 / 336.00 | 174.31 / 336.00 | -88.12% [-88.92, -87.26] |
| COLLISION / 4096 | 6 | 962.61 / 168.00 | 1011.33 / 168.00 | +4.97% [-0.41, +10.64] |
| NAME_HIT / 4096 | 6 | 843.86 / 208.00 | 139.60 / 208.00 | -83.47% [-84.71, -82.13] |
| REPEATED / 4096 | 6 | 42.88 / 24.00 | 40.40 / 16.00 | -5.87% [-23.84, +16.34] |
| COLLISION / 256 | 6 | 123.45 / 200.00 | 131.24 / 208.00 | +6.41% [-0.46, +13.74] |
| NAME_HIT / 256 | 6 | 167.64 / 208.00 | 102.73 / 208.00 | -37.82% [-49.56, -23.34] |
| REPEATED / 256 | 6 | 42.39 / 24.00 | 36.72 / 0.00 | -12.42% [-25.80, +3.37] |
| HUFFMAN / 4096 | 3 | 3458.49 / 208.00 | 3252.39 / 208.00 | -5.97% [-10.93, -0.74] |
| MIXED / 4096 | 3 | 536.48 / 208.00 | 134.23 / 208.00 | -74.97% [-82.23, -64.75] |
| SENSITIVE / 4096 | 3 | 51.28 / 48.00 | 51.73 / 48.00 | +0.88% [-2.56, +4.43] |
| STATIC / 4096 | 3 | 69.26 / 48.00 | 70.75 / 48.00 | +2.15% [-2.39, +6.90] |
| WORKING_SET / 4096 | 3 | 756.92 / 208.00 | 106.14 / 208.00 | -85.97% [-87.47, -84.30] |
| encoder / 4096 | 3 | 33.13 / 744.00 | 28.14 / 656.00 | -15.04% [-18.10, -11.85] |
| firstBlock / 4096 | 3 | 178.94 / 1080.00 | 161.92 / 992.00 | -9.51% [-10.19, -8.83] |
