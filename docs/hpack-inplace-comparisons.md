# Complete in-place HPACK campaign — audited results

## Answer
**64K initialization improved; universal 4K preservation/improvement is not established.** The stable void setter reduces 64K initialization from 374.145 to 334.628 ns/op (−39.516 ns, 95% paired CI [−46.057, −32.976], −10.562%) and allocation from 9192.000152 to 8992.000136 B/op (−200.000016 B, CI [−200.000018, −200.000013], −2.176%). These are pointwise significant versus replacement; upstream does not support 64K and was not measured there.
At 4K all 54 timing contrasts (18 cells × three comparisons) include zero. This means no detected timing improvement/regression, NOT equivalence or non-inferiority. The frozen plan has no non-inferiority margin or decision rule; no NI pass count is justified. Broad uncertainty leaves meaningful slowdowns possible. 4K init is 36.088 / 36.419 / 38.172 ns/op (upstream / replacement / inplace), with about 768 B/op in all three.

## Audit and provenance
- COMPLETE: 384 unique scheduled forks, 324 at 4K + 48 browser at 64K + 12 initialization at 64K; all completed once, exit 0. 1920 measurement iterations, averaged within forks (not 1920 independent samples).
- 1536 hash-chained events: exactly preflight/start/process-finished/parsed per scheduled fork, in schedule order; 768 raw hashes = 384 JSON + 384 logs. No orphan or missing raw file. All preflights report no competing JMH JVM, but the host was shared, not isolated.
- All 811 readiness inventory inputs unchanged; freeze.json contains **812 entries**, because freeze adds schedule.json. Candidate HEAD and exact protected git patch rechecked; all 12 protected source hashes, three jar identities, six fixture sources/48 nested/generated classes, and five candidate production class hashes verified offline. Broader historical hash-verification reports 77 HPACK benchmark classes.
- 126 comparison rows independently recalculated from raw JSON and matched against both comparisons.csv and summary.json; all 384 raw-forks.csv records matched. All 67 exact-wire rows match readiness and wire.csv: 24 browser + 27 synthetic at 4K, 16 browser at 64K. Matched wire lengths/hashes and requested/actual capacity checked by the read-only readiness gate.
- Existing reviewed focused tests: 148, 0 failures/errors/skips. Full paranoid HTTP/2: 1664, 0 failures/errors, 5 skips. Package history: success, focused tests enabled. These are preserved historical results, NOT new test executions. Original probe/test failures remain preserved, followed by corrected successful evidence.
- Candidate jar SHA256: `c5f0f55d8471f8ba2f0656846a57d61ad74a2066037102bea4c1f1bd6cc80640`. Candidate base `7369225ffe91d9afd75a5b168c1687ef3a96f71f` plus frozen protected patch (not a clean committed identity).
- No timing, tests, builds, Java probes, source edits, commit or push in this audit. Existing artifacts are hashed before/after and preserved. Only analysis-audit.py/json, analysis-comparisons.csv and this report are added.

## Source change and functional scope
Two production files replace the replacement-return API/copy constructor with void setMaxHeaderTableSize and a final wrapper encoder reference. Mutable bucket arrays/short mask grow at their high-water mark; both arrays are allocated before relinking existing nodes via cached hashes. FIFO links, counters, representatives, raw hash/admission/collision policy and pending minimum behavior are retained. Four test/support files adapt callers and cover empty/populated growth, identity, cached hash/counters, validation, eviction/indexing and high-water reuse. No benchmark source change.
Existing runtime proof: root identity stable for inplace, replaced for old implementation; empty and populated arrays grow 64→1024. Populated proof retains 50 pair and 10 name nodes, 2240 bytes. Both implementations retain 64,887 bytes/1120 entries with max chain 7 and max dynamic index 1181 in both tested distributions. Initial encoder root is 72 bytes, reachable graph 2160 bytes/13 objects for all three; graph footprint is not allocation per operation.

## Method and notation
U = unchanged upstream; O = old replacement implementation; N = new inplace setter. Means are raw ns/op or normalized B/op. Browser operations are already normalized per header block (64 blocks/invocation); do not divide again. Synthetic and initialization retain their fixture operation definitions. NEVER is primary policy; INDEX_COOKIES is supporting, not a substitute.
For each cell and metric, average five measurements within each fork, pair versions by round, then compute arithmetic differences dᵣ = candidate − reference. CI = mean(d) ± t(.975,n−1)·sd(d)/√n, with n=6/df=5 for all 4K and 64K init, n=3/df=2 for 64K browser; t=2.57058184 and 4.30265273. Negative favors candidate. Percent delta is delta/reference mean, not mean ratios. No pooling across cells, no outlier deletion, no cross-capacity paired claim.
4K uses each of six three-version permutations once per cell; 64K alternates AB/BA, browser starting sides balance globally (three pairs cannot balance within one cell), initialization has three pairs each direction. Intervals are descriptive, unadjusted pointwise 95% paired-t intervals on a shared host. Distribution/host effects and multiplicity limit generalization. Tiny allocation differences can reflect normalized profiler overhead; near-zero allocation denominators make percent changes misleading.
Each contrast below is **delta [95% CI low, high]; percent**. Full precision is in analysis-comparisons.csv; very small allocation values use scientific notation rather than rounding away uncertainty.

## 4K: browser (all eight cells) — ns/op

| Cell | U mean | O mean | N mean | N−U [CI]; % | N−O [CI]; % | O−U [CI]; % |
|---|---:|---:|---:|---|---|---|
| new-AUTH-INDEX | 619.759 | 1220.901 | 653.105 | 33.346 [-92.162, 158.854]; +5.380% | -567.796 [-2043.626, 908.033]; -46.506% | 601.142 [-807.601, 2009.885]; +96.996% |
| new-AUTH-NEVER | 726.620 | 679.580 | 705.730 | -20.890 [-74.615, 32.834]; -2.875% | 26.149 [-40.409, 92.707]; +3.848% | -47.040 [-167.014, 72.935]; -6.474% |
| new-NORMAL-INDEX | 558.819 | 759.439 | 562.354 | 3.536 [-14.587, 21.658]; +0.633% | -197.085 [-696.733, 302.563]; -25.951% | 200.620 [-316.310, 717.551]; +35.901% |
| new-NORMAL-NEVER | 633.190 | 927.691 | 1210.756 | 577.565 [-936.948, 2092.078]; +91.215% | 283.065 [-537.177, 1103.307]; +30.513% | 294.500 [-413.850, 1002.850]; +46.511% |
| persistent-AUTH-INDEX | 650.656 | 700.330 | 667.894 | 17.238 [-177.142, 211.619]; +2.649% | -32.436 [-68.422, 3.551]; -4.631% | 49.674 [-130.845, 230.192]; +7.634% |
| persistent-AUTH-NEVER | 1056.415 | 673.667 | 654.111 | -402.305 [-1428.180, 623.571]; -38.082% | -19.557 [-52.228, 13.115]; -2.903% | -382.748 [-1410.513, 645.017]; -36.231% |
| persistent-NORMAL-INDEX | 501.706 | 504.406 | 497.309 | -4.397 [-33.653, 24.859]; -0.876% | -7.097 [-19.148, 4.954]; -1.407% | 2.700 [-20.628, 26.028]; +0.538% |
| persistent-NORMAL-NEVER | 529.204 | 578.303 | 526.052 | -3.152 [-85.647, 79.342]; -0.596% | -52.251 [-262.754, 158.252]; -9.035% | 49.099 [-87.694, 185.892]; +9.278% |

## 4K: browser (all eight cells) — B/op

| Cell | U mean | O mean | N mean | N−U [CI]; % | N−O [CI]; % | O−U [CI]; % |
|---|---:|---:|---:|---|---|---|
| new-AUTH-INDEX | 151.750 | 151.750 | 143.750 | -8.000 [-45.919, 29.919]; -5.272% | -8.000 [-45.920, 29.919]; -5.272% | 0.000244129 [-31.858, 31.859]; +0.000% |
| new-AUTH-NEVER | 133.875 | 133.875 | 133.875 | -8.81599e-06 [-3.18735e-05, 1.42416e-05]; -0.000% | 1.15649e-05 [-1.81375e-05, 4.12674e-05]; +0.000% | -2.03809e-05 [-7.30337e-05, 3.22719e-05]; -0.000% |
| new-NORMAL-INDEX | 138.125 | 130.125 | 138.125 | 2.15986e-06 [-7.37818e-06, 1.16979e-05]; +0.000% | 8.000 [-12.565, 28.564]; +6.148% | -8.000 [-28.564, 12.565]; -5.792% |
| new-NORMAL-NEVER | 134.375 | 134.375 | 134.375 | 0.00020755 [-0.000337999, 0.0007531]; +0.000% | 9.22513e-05 [-0.00018293, 0.000367433]; +0.000% | 0.000115299 [-0.00016136, 0.000391958]; +0.000% |
| persistent-AUTH-INDEX | 139.017 | 123.017 | 99.017 | -40.000 [-60.565, -19.435]; -28.773% | -24.000 [-51.590, 3.590]; -19.510% | -16.000 [-42.012, 10.012]; -11.509% |
| persistent-AUTH-NEVER | 111.625 | 119.625 | 119.625 | 8.000 [-12.564, 28.564]; +7.167% | 1.89768e-05 [-2.84108e-05, 6.63645e-05]; +0.000% | 8.000 [-12.564, 28.564]; +7.167% |
| persistent-NORMAL-INDEX | 114.691 | 122.691 | 122.691 | 8.000 [-12.565, 28.565]; +6.975% | 1.06296e-05 [-8.29597e-06, 2.95551e-05]; +0.000% | 8.000 [-12.565, 28.565]; +6.975% |
| persistent-NORMAL-NEVER | 118.000 | 118.000 | 118.000 | 1.40605e-06 [-2.59844e-05, 2.87965e-05]; +0.000% | -1.86884e-05 [-0.00011263, 7.52534e-05]; -0.000% | 2.00945e-05 [-5.13414e-05, 9.15303e-05]; +0.000% |

## 4K: synthetic (all nine cells) — ns/op

| Cell | U mean | O mean | N mean | N−U [CI]; % | N−O [CI]; % | O−U [CI]; % |
|---|---:|---:|---:|---|---|---|
| CHURN | 1540.779 | 1512.810 | 1653.074 | 112.295 [-362.078, 586.668]; +7.288% | 140.264 [-264.763, 545.290]; +9.272% | -27.969 [-114.681, 58.743]; -1.815% |
| COLLISION | 1284.591 | 998.401 | 1030.744 | -253.846 [-631.336, 123.643]; -19.761% | 32.343 [-43.984, 108.670]; +3.239% | -286.189 [-620.888, 48.510]; -22.279% |
| HUFFMAN | 3680.107 | 3424.041 | 3526.288 | -153.819 [-754.216, 446.577]; -4.180% | 102.246 [-169.398, 373.890]; +2.986% | -256.066 [-1005.172, 493.041]; -6.958% |
| MIXED | 556.210 | 573.030 | 562.842 | 6.632 [-30.388, 43.652]; +1.192% | -10.188 [-41.800, 21.424]; -1.778% | 16.820 [-23.506, 57.146]; +3.024% |
| NAME_HIT | 874.600 | 947.222 | 905.395 | 30.796 [-24.360, 85.951]; +3.521% | -41.827 [-189.608, 105.954]; -4.416% | 72.623 [-126.188, 271.433]; +8.304% |
| REPEATED | 49.176 | 48.722 | 49.877 | 0.701 [-11.565, 12.966]; +1.425% | 1.154 [-17.699, 20.008]; +2.369% | -0.454 [-19.602, 18.694]; -0.922% |
| SENSITIVE | 54.757 | 52.678 | 60.951 | 6.194 [-7.863, 20.252]; +11.313% | 8.274 [-4.295, 20.842]; +15.706% | -2.079 [-4.922, 0.763]; -3.797% |
| STATIC | 104.898 | 85.533 | 94.274 | -10.624 [-76.147, 54.898]; -10.128% | 8.741 [-10.438, 27.920]; +10.220% | -19.365 [-101.216, 62.485]; -18.461% |
| WORKING_SET | 818.760 | 807.081 | 792.527 | -26.233 [-62.729, 10.264]; -3.204% | -14.554 [-79.028, 49.920]; -1.803% | -11.679 [-56.059, 32.702]; -1.426% |

## 4K: synthetic (all nine cells) — B/op

| Cell | U mean | O mean | N mean | N−U [CI]; % | N−O [CI]; % | O−U [CI]; % |
|---|---:|---:|---:|---|---|---|
| CHURN | 336.001 | 336.001 | 336.001 | 2.33223e-05 [-0.000261706, 0.00030835]; +0.000% | 3.60378e-05 [-0.000211659, 0.000283735]; +0.000% | -1.27156e-05 [-0.000108972, 8.35412e-05]; -0.000% |
| COLLISION | 184.001 | 160.000 | 168.000 | -16.000 [-57.130, 25.129]; -8.696% | 8.000 [-12.565, 28.565]; +5.000% | -24.000 [-51.591, 3.590]; -13.044% |
| HUFFMAN | 208.001 | 208.001 | 208.001 | -5.60189e-05 [-0.00028499, 0.000172952]; -0.000% | 4.17783e-05 [-6.76007e-05, 0.000151157]; +0.000% | -9.77972e-05 [-0.000386353, 0.000190758]; -0.000% |
| MIXED | 208.000 | 208.000 | 208.000 | 2.8845e-06 [-1.22591e-05, 1.80281e-05]; +0.000% | -4.09618e-06 [-1.70855e-05, 8.8931e-06]; -0.000% | 6.98069e-06 [-9.44447e-06, 2.34058e-05]; +0.000% |
| NAME_HIT | 208.000 | 208.000 | 208.000 | 1.24045e-05 [-9.37214e-06, 3.41812e-05]; +0.000% | -1.71527e-05 [-7.82965e-05, 4.39912e-05]; -0.000% | 2.95572e-05 [-5.16495e-05, 0.000110764]; +0.000% |
| REPEATED | 32.000 | 32.000 | 24.000 | -8.000 [-57.526, 41.526]; -25.000% | -8.000 [-34.012, 18.012]; -25.000% | -5.00476e-07 [-35.619, 35.619]; -0.000% |
| SENSITIVE | 48.000 | 40.000 | 48.000 | 2.51837e-06 [-3.19305e-06, 8.22979e-06]; +0.000% | 8.000 [-12.565, 28.565]; +20.000% | -8.000 [-28.565, 12.565]; -16.667% |
| STATIC | 48.000 | 48.000 | 48.000 | -6.16722e-06 [-3.09926e-05, 1.86582e-05]; -0.000% | -5.49409e-07 [-8.70439e-06, 7.60557e-06]; -0.000% | -5.61781e-06 [-3.58942e-05, 2.46586e-05]; -0.000% |
| WORKING_SET | 208.000 | 208.000 | 208.000 | -1.07523e-05 [-2.54526e-05, 3.94802e-06]; -0.000% | -5.96538e-06 [-3.24715e-05, 2.05407e-05]; -0.000% | -4.78693e-06 [-2.30612e-05, 1.34873e-05]; -0.000% |

## 4K: initialization — ns/op

| Cell | U mean | O mean | N mean | N−U [CI]; % | N−O [CI]; % | O−U [CI]; % |
|---|---:|---:|---:|---|---|---|
| init | 36.088 | 36.419 | 38.172 | 2.084 [-1.237, 5.406]; +5.776% | 1.753 [-2.172, 5.678]; +4.813% | 0.332 [-1.218, 1.881]; +0.919% |

## 4K: initialization — B/op

| Cell | U mean | O mean | N mean | N−U [CI]; % | N−O [CI]; % | O−U [CI]; % |
|---|---:|---:|---:|---|---|---|
| init | 768.000 | 768.000 | 768.000 | 8.5002e-07 [-4.98035e-07, 2.19808e-06]; +0.000% | 7.17984e-07 [-8.90463e-07, 2.32643e-06]; +0.000% | 1.32037e-07 [-5.14055e-07, 7.78129e-07]; +0.000% |

## 64K — inplace versus replacement only: browser (all eight cells) — ns/op

| Cell | O mean | N mean | N−O [CI]; % |
|---|---:|---:|---|
| new-AUTH-INDEX | 516.557 | 518.076 | 1.519 [-20.962, 23.999]; +0.294% |
| new-AUTH-NEVER | 565.994 | 559.227 | -6.767 [-16.842, 3.309]; -1.196% |
| new-NORMAL-INDEX | 424.547 | 414.878 | -9.669 [-53.979, 34.641]; -2.278% |
| new-NORMAL-NEVER | 452.844 | 451.937 | -0.907 [-15.621, 13.806]; -0.200% |
| persistent-AUTH-INDEX | 526.100 | 550.488 | 24.388 [-53.356, 102.133]; +4.636% |
| persistent-AUTH-NEVER | 565.629 | 598.386 | 32.757 [-111.968, 177.482]; +5.791% |
| persistent-NORMAL-INDEX | 395.158 | 415.872 | 20.714 [-89.356, 130.783]; +5.242% |
| persistent-NORMAL-NEVER | 431.368 | 424.374 | -6.994 [-28.042, 14.055]; -1.621% |

## 64K — inplace versus replacement only: browser (all eight cells) — B/op

| Cell | O mean | N mean | N−O [CI]; % |
|---|---:|---:|---|
| new-AUTH-INDEX | 214.250 | 211.125 | -3.125 [-3.125, -3.125]; -1.459% |
| new-AUTH-NEVER | 209.875 | 206.750 | -3.125 [-3.125, -3.125]; -1.489% |
| new-NORMAL-INDEX | 242.250 | 239.375 | -2.875 [-62.029, 56.279]; -1.187% |
| new-NORMAL-NEVER | 256.250 | 253.500 | -2.750 [-2.750, -2.750]; -1.073% |
| persistent-AUTH-INDEX | 0.000213891 | 16.000 | 16.000 [-52.842, 84.842]; +7480448.761% |
| persistent-AUTH-NEVER | 0.000229916 | 16.000 | 16.000 [-52.842, 84.843]; +6959076.823% |
| persistent-NORMAL-INDEX | 16.000 | 48.000 | 32.000 [-36.842, 100.842]; +199.998% |
| persistent-NORMAL-NEVER | 32.000 | 48.000 | 16.000 [-52.842, 84.842]; +50.000% |

## 64K — inplace versus replacement only: initialization — ns/op

| Cell | O mean | N mean | N−O [CI]; % |
|---|---:|---:|---|
| init | 374.145 | 334.628 | -39.516 [-46.057, -32.976]; -10.562% |

## 64K — inplace versus replacement only: initialization — B/op

| Cell | O mean | N mean | N−O [CI]; % |
|---|---:|---:|---|
| init | 9192.000 | 8992.000 | -200.000 [-200.000, -200.000]; -2.176% |

## Pointwise CI counts (lower / higher / includes zero)

| Scope / contrast / metric | Lower | Higher | Includes zero |
|---|---:|---:|---:|
| primary: inplace/4096 - replacement/4096: ns | 0 | 0 | 18 |
| primary: inplace/4096 - replacement/4096: bytes | 0 | 0 | 18 |
| primary: inplace/4096 - upstream/4096: ns | 0 | 0 | 18 |
| primary: inplace/4096 - upstream/4096: bytes | 1 | 0 | 17 |
| primary: replacement/4096 - upstream/4096: ns | 0 | 0 | 18 |
| primary: replacement/4096 - upstream/4096: bytes | 0 | 0 | 18 |
| capacity: inplace/65536 - replacement/65536: ns | 1 | 0 | 8 |
| capacity: inplace/65536 - replacement/65536: bytes | 4 | 0 | 5 |

Counts are descriptions, not a familywise significance test, an NI decision, or proof over all workloads. At 4K the only allocation CI excluding zero is persistent AUTH/INDEX N−U (−40.000 B/op, CI [−60.565, −19.435]); none excludes zero against O. At 64K timing only initialization excludes zero; allocation excludes zero for initialization and three new-connection cells.

## Counterexamples and limits
- 4K CHURN is slower in point estimate: U/O/N 1540.779/1512.810/1653.074 ns. N−O +140.264 ns (+9.272%), CI [−264.763, +545.290]; N−U +112.295 ns (+7.288%), CI [−362.078, +586.668]. Allocation approximately 336 B/op throughout. Neither improvement nor regression established.
- 4K new NORMAL/NEVER: U/O/N 633.190/927.691/1210.756 ns. N−U +577.565 [−936.948, +2092.078]; N−O +283.065 [−537.177, +1103.307]. Large positive point estimate cannot be dismissed, nor called significant. All forks are retained.
- 64K steady persistent AUTH/NEVER: old/new 565.629/598.386 ns, +32.757 (+5.791%), CI [−111.968, +177.482]; allocation old/new 0.000230/16.000243 B/op, delta +16.000013 [−52.842489, +84.842515]. Persistent NORMAL/INDEX: 395.158→415.872 ns, +20.714 [−89.356, +130.783]; allocation 16.000161→48.000169 B/op, +32.000008 [−36.842480, +100.842497]. These disprove a blanket point-estimate improvement narrative, not establish regressions.
- The 64K init allocation saving is not a universal steady-state allocation saving. Near-zero baseline ratios (millions of percent) are mathematically computable but not useful engineering effect sizes. Use absolute B/op and intervals.
- Initialization measures construction plus empty default-4K→64K configuration, not populated late SETTINGS growth or steady-state resize latency. Functional tests cover populated semantics, not its performance. No 64K synthetic/churn timings exist in this matrix; 64K steady browser results are the available counterexamples.

## Reproduce this audit (offline only)
`python3 -B analysis-audit.py` from this evidence directory. Reads existing raw evidence and read-only git identity; writes only the four derived analysis/report artifacts. Never run preparation, freeze, run, build, or test commands for this interpretation.
