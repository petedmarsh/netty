# HPACK three-commit series — offline evidence

Open [hpack-64k-series.html](hpack-64k-series.html) directly (`file:` works).
All 32 cells, per-step/baseline contrasts, raw fork means, allocation, exact wire
sizes/hashes, paired CIs, production diffs and source are inline. CSS and SVG are
embedded; no JavaScript, CDN or network resource is required.

## Local commits (not pushed)

Links target the fork and may be unavailable **until pushed**. No push or PR was
performed. The HTML embeds the source/diffs so these links are not dependencies.

| Step | Full commit | Change |
|---|---|---|
| 1 | `76b8b0b72f156d5524ac9a5317f22f8563d107c6` | Return/retain encoder configuration result; prepare replacement |
| 2 | `5a9c64318d3489f11042971014f2398614c32548` | Bound large-table collision admission before eviction |
| 3 | `ec00a0f50d989f9c53b30256d93e8f0dcaf1983d` | Grow immutable buckets on configuration, up to 64 KiB |

Baseline: `3eaff63caf06a582cc3df72422aa1124734b46c6`. Final production source
exactly matches `95c9eb3d06fa02f34c17cc356964f7b2cda53476` (`*/src/main/**`);
this is not an assertion of whole-tree or jar identity. This documentation commit
changes no production or Java test source and rewrites none of those commits.

## What was measured

516 forks, 2,580 measured iterations: 18 primary cells × four versions × six
paired rounds; 14 stage-3 capacity cells × two sizes × three paired rounds.
All **90 primary 4K timing pointwise CIs cross zero, NOT proof of parity**.
The capacity cohort has 10/14 timing CIs below zero (improvements), and three
new-connection allocation CIs above zero (regressions). Shared host; unadjusted
pointwise intervals, not equivalence tests or universal speedup claims.

Selected stage-3 capacity numbers (own 4K reference → 64K, not primary cohort):

| Case | ns/op | Allocated B/op | Paired timing Δ, 95% CI |
|---|---:|---:|---:|
| Persistent normal/index cookies | 504.30 → 373.39 | 122.69 → 0.000151 | −130.91 [−137.08, −124.74] |
| CHURN, four-field block | 1529.89 → 147.51 | 336 → 48 | −1382.39 [−1459.79, −1304.98] |
| Working set 512, four-field block | 819.07 → 131.26 | 208 → 48 | −687.81 [−784.13, −591.49] |
| New normal/index cookies | 473.03 → 427.61 | 138.13 → 242.25 | −45.43 [−85.87, −4.98] |

New-connection allocation regressions: authenticated/NEVER +91.875 B/request,
normal/index +104.125, normal/NEVER +121.875. Allocation is not wire size.
All 17 primary wire cases are identical across stages at 4K; 85 wire rows are
preserved. See extracted `results.md`, `results-details.md`, `comparisons.csv`,
`raw-forks.csv`, `wire.csv`, `final-audit.json` for full precision and caveats.
Historical audit text describes its own earlier no-commit scope.

## Archive, extraction and integrity

`evidence/hpack-series-text.tar.gz`: **6,247,647 bytes**, SHA-256:

```text
69640f1d6a9afc97bc6be6b88ed827337d96c0982bbdbf59149db22f87ceedf7
```

3,374 files, 75,818,157 extracted file bytes. Deterministic sorted USTAR + gzip
(mtime/uid/gid zero, fixed mode, empty gzip filename). The archive preserves all
raw JSON/logs, CSVs, wire proofs, step0–3 source/test snapshots and reports,
scripts, ledger, configuration, schedule and freeze proofs. `EXTRACTION.json`
records every included original file hash and exclusions. Frozen binaries' hashes
remain in the **unmodified** freeze/logs; jars/classes/JDK/Maven caches are not
included. The obsolete presentation preview is excluded; the final renderer,
its tests and source assets are tracked under `renderer/`, not duplicated in the
archive. Raw originals remain outside the checkout, untouched.

Run from this checkout root (substitute your own extraction directory):

```sh
shasum -a 256 docs/evidence/hpack-series-text.tar.gz
mkdir -p /tmp/hpack-review
# Preserves exact paths relative to hpack-series-evidence/.
tar -xzf docs/evidence/hpack-series-text.tar.gz -C /tmp/hpack-review
python3 -B docs/renderer/generate.py --repo "$PWD" \
  --evidence /tmp/hpack-review/hpack-series-evidence --extracted \
  --output /tmp/hpack-review/report.html
HPACK_EVIDENCE=/tmp/hpack-review/hpack-series-evidence \
  python3 -B -m unittest discover -s docs/renderer -p 'test_*.py' -v
```

Python 3.9+ stdlib and Git with all four series commits are required. Paths are
explicit: `--repo` supplies Git snapshots; `--evidence` supplies `input.json`,
runner and raw proof. Tests use `HPACK_EVIDENCE`. No command launches Java or
benchmarks. Extracted rendering checks included hashes/frozen text, schedule,
ledger chain, all raw output hashes, parser values, CSV recomputation and wire
rows. It is **extraction verification**, not full frozen-artifact verification.
The HTML states this distinction; thus its verification paragraph differs from
the checked-in full-verification rendering. The manifest is integrity metadata,
not an independent signature; trust comes from the reviewed commit/archive hash.

**Full frozen-artifact verification requires the original jars, classes, JDK
executables and original absolute paths** recorded in the freeze. Do not rewrite
frozen configuration or hashes to pretend omitted artifacts were verified.
Original-host command (machine-local paths, not portable):

```sh
python3 -B docs/renderer/generate.py --repo "$PWD" \
  --evidence /Users/pmarsh/src/netty/.worktrees/hpack-series-evidence \
  --output docs/hpack-64k-series.html
```

Rebuild the archive from that untouched original root:

```sh
python3 -B docs/renderer/package_evidence.py \
  --evidence /Users/pmarsh/src/netty/.worktrees/hpack-series-evidence \
  --output /tmp/hpack-series-text.tar.gz
cmp docs/evidence/hpack-series-text.tar.gz /tmp/hpack-series-text.tar.gz
```

## Publication and validation scope

Decompressed archive and final documentation were scanned for private keys,
common access-token formats, credential assignments and internal hostnames.
No real credential or internal service endpoint was identified. Explicitly
synthetic fixture credentials (`SYNTHETIC_NOT_A_CREDENTIAL_`, `synth-` cookies)
are preserved byte-for-byte, including wire logs. `internal-admin.local` is an
upstream HTTP conversion **test fixture**, not a real endpoint; Java package
names such as `io.netty.util.internal` and method calls `client.local()` are
not hosts. The agent's public noreply trailer domain is attribution, not a
service endpoint. Local paths in provenance/build commands are retained and
are not secrets. No personal Chrome profile/log was added.

Validation: full original-artifact gate, relocated extraction render, eight renderer
unit/integration tests (including raw tamper refusal) in both modes, static internal-anchor/offline HTML/SVG checks, CSV/chart consistency,
exact embedded Git source and stage-3 target-source equality. Browser headless
launch was previously blocked by macOS Mach-port sandbox restrictions: **no
screenshot or visual browser review is claimed**. No Java suites or benchmark
forks were rerun for this docs-only commit; their frozen historical results
remain evidence, not newly executed tests.

Repeat the full static audit on the original host:

```sh
python3 -B docs/renderer/verify_publication.py --repo "$PWD" \
  --evidence /Users/pmarsh/src/netty/.worktrees/hpack-series-evidence
```

The pattern scan has reviewed test/package/attribution exceptions; it is not a
guarantee against every possible secret encoding. All 3,374 decompressed files
and final documentation are scanned, not merely compressed archive bytes.
