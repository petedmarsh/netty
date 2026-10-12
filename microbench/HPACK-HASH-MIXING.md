# HPACK hash-mixing-only: fresh HEAD comparison

## Outcome

The isolated mixer substantially reduces time in four synthetic dynamic-table workloads at 4096 bytes: WORKING_SET, MIXED, CHURN and NAME_HIT. It does **not** establish a general HPACK speedup. Full-hash COLLISION is not fixed by bucket mixing. Other cases, including repeated hits, small-table workloads, initialization and unchanged decoder, have weaker or inconclusive evidence. No 64 KiB support is added: the upstream capacity cap and backing structures are unchanged.

## Isolation and verification

- Baseline: `014ef8d7031f9f1015020095421a659ced36cc8a`, the upstream `4.2` HEAD returned by a fresh fetch for this experiment.
- Candidate code commit: `fce861766e` on `perf/hpack-hash-mixing`, based directly on that baseline. This is the sole commit above the baseline on the code branch. The measured patch was subsequently committed without source changes; the earlier local commit `acb74b9c7f` was replaced only to improve its commit message. The production patch changes only `HpackEncoder.bucket(int)`: xor-shift, multiply by `0x85ebca6b`, xor-shift and existing mask. The existing focused encoder tests are retained. No other codec changes imported or production edits made during this campaign.
- Identical `HpackStateBenchmark.java` / `HpackInitializationBenchmark.java` copied from root commit `9e11b3c42c`; defaults remain 0/256/4096. Root checkout left untouched. The focused codec/test change is isolated on `perf/hpack-hash-mixing`. This report, benchmark sources, and raw evidence are on the separate `perf/hpack-hash-mixing-benchmarks` branch, which adds one supporting commit on top of the same code commit. Both branches target the `petedmarsh/netty` fork; no PR is requested.
- Fresh sequential `clean package` reactor builds, Homebrew OpenJDK 21.0.12, macOS arm64. Explicit Maven 3.9.11 and writable `/tmp/hpack-maven-repository`; `benchmark-jar,!mac` excludes irrelevant native kqueue packaging. HPACK tests run during both builds; neither build installs shared SNAPSHOT artifacts.
- 1461 codec-http2/microbench compiled classes per jar byte-compared with fresh target outputs (module descriptors deliberately excluded). Sources, compiled classes, patches, build commands/logs and SHA-256 provenance retained in raw evidence.
- Byte-identity assertion passed: length-delimited SHA-256 of all 2048 captured blocks for every one of 12 scenarios at each of 0/256/4096 (36 comparisons). Setup validates decoded fields, third-cycle periodicity and two captured-wire replay cycles. No grow/shrink or unsupported 65536 runs.

## Method and results

Exactly 17 cases × 6 matched fork pairs = **204 new JVM forks**, 1020 measured iterations. Per-case rounds alternate AB/BA, giving three ABBA blocks; A is baseline. One thread, each fork five 1-second warmups and five 1-second measurements, `-prof gc`, `-Xms512m -Xmx512m`, fail-on-error. Prior benchmark data were not reused.

One encode operation is a four-field header block. Initialization is one encoder construction/configuration. Decode is the unchanged HUFFMAN control. Each fork's five measurements are averaged first. Brackets below are **95% Student-t confidence intervals across six independent fork means** (df=5); percent change uses six paired per-round percentages, negative means faster. Percentages are not ratios of pooled means. No correction for 17 comparisons; intervals do not capture systematic machine bias. Allocation column is mean normalized B/op; per-fork values and allocation confidence intervals are in `fork-confidence.json`.

| Case / table bytes | HEAD ns/op [95% CI] | Mixer ns/op [95% CI] | Paired change % [95% CI] | HEAD → mixer B/op |
|---|---:|---:|---:|---:|
| encode/REPEATED/4096 | 45.21 [38.20, 52.23] | 40.16 [35.14, 45.18] | -8.97 [-30.91, 12.96] | 32.00 → 16.00 |
| encode/WORKING_SET/4096 | 771.47 [761.61, 781.34] | 161.38 [158.84, 163.92] | -79.08 [-79.38, -78.79] | 208.00 → 208.00 |
| encode/MIXED/4096 | 560.76 [546.97, 574.54] | 191.55 [189.46, 193.63] | -65.83 [-66.38, -65.28] | 208.00 → 208.00 |
| encode/CHURN/4096 | 1414.91 [1402.38, 1427.43] | 164.92 [153.57, 176.26] | -88.35 [-89.09, -87.60] | 336.00 → 336.00 |
| encode/NAME_HIT/4096 | 841.22 [830.31, 852.14] | 171.17 [139.79, 202.56] | -79.67 [-83.30, -76.04] | 208.00 → 208.00 |
| encode/COLLISION/4096 | 981.34 [960.07, 1002.61] | 1018.22 [938.34, 1098.10] | 3.77 [-4.38, 11.93] | 160.00 → 168.00 |
| encode/STATIC/4096 | 70.10 [69.19, 71.00] | 70.97 [69.69, 72.26] | 1.26 [-0.71, 3.22] | 48.00 → 48.00 |
| encode/SENSITIVE/4096 | 52.09 [51.25, 52.93] | 52.10 [50.86, 53.34] | 0.04 [-2.27, 2.34] | 48.00 → 48.00 |
| encode/HUFFMAN/4096 | 3315.18 [3152.00, 3478.36] | 3269.81 [3195.61, 3344.01] | -1.26 [-4.46, 1.94] | 208.00 → 208.00 |
| encode/REPEATED/256 | 38.82 [33.59, 44.05] | 37.89 [36.22, 39.55] | -1.65 [-9.17, 5.88] | 8.00 → 4.00 |
| encode/CHURN/256 | 161.32 [135.68, 186.96] | 132.62 [116.52, 148.72] | -15.96 [-34.16, 2.23] | 336.00 → 336.00 |
| encode/NAME_HIT/256 | 166.41 [137.78, 195.03] | 161.61 [160.41, 162.81] | 0.07 [-22.64, 22.78] | 208.00 → 208.00 |
| encode/COLLISION/256 | 127.54 [121.70, 133.38] | 129.94 [123.88, 136.00] | 2.04 [-4.53, 8.61] | 204.00 → 208.00 |
| encode/REPEATED/0 | 37.45 [34.62, 40.28] | 36.27 [36.03, 36.51] | -2.80 [-9.08, 3.48] | 4.00 → 0.00 |
| encode/CHURN/0 | 59.76 [58.97, 60.54] | 59.24 [58.68, 59.79] | -0.85 [-2.62, 0.92] | 48.00 → 48.00 |
| encoder/INIT/4096 | 33.27 [32.85, 33.68] | 33.25 [32.83, 33.67] | -0.05 [-1.51, 1.41] | 744.00 → 744.00 |
| decode/HUFFMAN/4096 | 3370.24 [2635.30, 4105.18] | 3103.10 [3016.83, 3189.37] | -4.93 [-23.47, 13.61] | 2768.00 → 2768.00 |

## Interpretation and caveats

The four strongest 4096-byte improvements are much larger and more consistent than the controls' variability. These are deterministic synthetic name/value distributions, not sampled production traffic. WORKING_SET uses 512 identifiers; MIXED retains that fixture. NAME_HIT reuses names while values change. Hash mixing redistributes different full hashes; it cannot separate equal full hashes. The selected collision fixture explicitly checks equal full ASCII hashes.

Small effects are not reliable on this unpinned, non-dedicated macOS host. The unchanged decoder has large between-fork variability, and some encoder cases show distinct fast/slow fork clusters; do not infer a decoder benefit from this source change or hide these samples as outliers. CPU frequency, scheduling, thermals and JIT shape were not controlled. Five-second warmup is bounded, not proof of convergence. No real-traffic, multi-connection, cold-cache or HTTP/2 frame integration claims. Allocation varies discretely between forks in some cases: REPEATED/4096 averages 32 → 16 B/op, COLLISION/4096 160 → 168, and the unchanged table-zero REPEATED control 4 → approximately 0. These are genuine measured fork differences, not merely rounding noise, but the mixer changes no allocation sites. Different JIT escape-analysis outcomes are a plausible, unproven explanation; no compiler diagnostic campaign was run. Do not claim an allocation optimization from these averages. The four strong 4096-byte dynamic workloads retain equal allocations, and initialization remains 744 B/op.

## Evidence and reproduction

Raw evidence: `/tmp/hpack-hash-only-results`. `prepare.py`, `run.py`, `analyze.py`, `verify.py`, `PLAN.md`, exact command manifests, all 204 JSON/log pairs, both fresh jar hashes, source snapshots/patches, preserved compiled classes, wire probe and fork CIs are retained. Re-run `python3 run.py N` for N=1..6 only in a fresh results directory; runner refuses overwrites. Builds and forks must remain sequential.

The deterministic companion `hpack-hash-mixing-evidence.tar.gz` excludes large shaded jars (available in raw evidence), caches and credentials; includes measured codec/benchmark classes and all benchmark evidence. `SHA256SUMS` covers archived files. Source safety scan and final source/patch preservation assertions are recorded in `verification.json`. Supporting benchmark/doc/evidence files are committed separately from the focused hash-mixing/test change, so the production optimization can be reviewed or cherry-picked independently.

### Fresh jar SHA-256

- baseline: `ff03b114872859240abb5556b33a6e461b2a2dc32ad724d1c79d683639206f63`
- candidate: `03506a0df8236f3da6413636cdb2fb5a3a3de1e5d9167231d9d2ef2f4a40f9b5`

Build-start to final measurement: 38.4 minutes.
