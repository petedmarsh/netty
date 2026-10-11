# HPACK microbenchmarks

> **Evidence is archived.** Read [HPACK-RESULTS.md](HPACK-RESULTS.md) for the
> final retained-candidate comparison and uncertainty. Historical links below
> require extracting the archive **from the repository root**:
>
> ```sh
> tar -xzf microbench/hpack-benchmark-evidence.tar.gz
> shasum -a 256 -c microbench/results/hpack/ARCHIVE-SHA256SUMS
> ```
>
> The archive preserves original commands, raw JSON, scripts, test logs, source
> snapshots, and historical hash manifests byte-for-byte. Packaging details and
> the archive SHA256 are in [HPACK-RESULTS.md](HPACK-RESULTS.md).

## Current source: linkedMixed64 retained; ring rejected on performance

Root now contains original iteration2 plus the exact measured linkedMixed64
one-line hashing change, not the ring. A final two-line bucket-comment correction
preserves source line count but changes the source hash; historical snapshot hashes
are preserved in the [retention report](results/hpack/linkedMixed-retained/README.md).
All 276 compiled codec classes match both
measured ring/distribution linkedMixed jars and the fresh root package.
Full HTTP/2: 1643 tests, five existing skips; paranoid HPACK: 125 tests, no skips;
zero failures/errors. Benchmark sources and previous evidence are preserved.
[Retention report and upstream comparison](results/hpack/linkedMixed-retained/README.md).
No new timings or commit.
**Do not promote the ring:** matched64-bucket/always-mixed comparisons show
CHURN4096 −10.10% but REPEATED4096 **+23.10% [6.38,42.45]**, NAME_HIT256
+17.88% and COLLISION256 +57.14%. Most upstream-relative gains come from mixing.
LinkedMixed (iteration2 + always-mixed64) is now the retained production
candidate. This is not a blanket upstream non-regression claim.

Completed **237 forks /1185 measured iterations**, six balanced forks on important
matched cases, three on remaining controls; all9 scenarios4096, three256 cases,
constructor/first-block and64KiB working-set/wrap coverage. Ring cuts churn
allocation336→48B/block, but first-block allocation rises992→1376B and exact-hit
JIT allocation modes worsen. Populated retention is workload-dependent: ring
saves distinct-name CHURN nodes, but retains more for repeated names/small tables.
Upstream cannot run65536 (clamps4096); ring/linked wire checks pass there.

Full decision, uncertainty, explicit memory assumptions and durable evidence:
[ring/README.md](results/hpack/ring/README.md),
[ring/ANALYSIS.md](results/hpack/ring/ANALYSIS.md).

## Historical: iteration2 restored before ring; iteration3 rejected

Only the iteration3 `encodeHeaderMiss` extraction has been reverted. The complete
codec diff matches `/tmp/hpack-candidate-iteration2.patch` byte-for-byte; all
earlier improvements, codec tests, and new benchmarks are retained unchanged.
Iteration3 measurements below are **historical evidence for a rejected experiment,
not measurements of the current source**. Existing iteration3 jars/results remain
unchanged. Restoring iteration2 does not establish blanket non-regression.

Post-revert verification: full HTTP/2 **1642 tests, 0 failures/errors, 5 skips**;
paranoid HPACK **124 tests, 0 failures/errors/skips**; `git diff --check` clean.
Logs: `/tmp/hpack-reverted-iteration2-{http2,paranoid}-tests.log`.

**Excluded unsupported periodic corpus:** `MIXED` at 32768 fails the fixture's
periodicity requirement (`Non-periodic HPACK corpus at 0`). It was not timed;
no supported periodic replay or mixed-traffic capacity conclusion is claimed.
This is a fixture limitation, not evidence that the codec cannot use 32768.
The benchmark and validation guard are deliberately unchanged; no fixture fix
or new performance measurements were attempted during this revert.

## Historical: iteration3 completed — experimental exact-hit fix rejected

Completed2026-10-10, **255 forks /1275 measured iterations in44m39s**, within
55-minute sequential workflow budget. Identical updated benchmark sources built
against upstream in `/tmp/netty-hpack-baseline` and iteration3. Isolated jars
`/tmp/hpack-iteration3-baseline.jar` and `/tmp/hpack-iteration3.jar`; previous jars
preserved. No production or benchmark-source edits. Six AB/BA pairs for15
matched cases, three rotated capacity rounds, three fresh/history pairs, six
matched traversal pairs; every fork5×1s warmup/measurement with GC profiling.

**Iteration3 is rejected as a performance/non-regression fix.** Exact-hit
REPEATED4096 is **42.65→48.84ns, +14.53% [3.99,27.26]**, allocation **24→44B**
(delta95% [4,36]). The slowdown is supported, though its lower bound does not
establish >5% timing regression. REPEATED256 remains unresolved. Ten of15 matched
cases support the +5% timing analysis margin; five do not. INIT4096 improves
15.86%, saving88B; NAME_HIT256 improves36.32%; COLLISION4096 supports practical NI.
These gains do not establish exact-hit improvement or blanket non-regression.
Historical iteration2 is not a direct randomized extraction comparator; causality
versus iteration2 is not established. The extraction has since been reverted
as documented above; these numbers still describe iteration3 only.

**Lower capacity saves initialization allocation but can sharply worsen wire,
allocation and time.** At16/32/64KiB, init is2736/4784/8880B and176/269/319ns.
128 entries fit all three.384/512 fit32/64KiB but lose compression at16KiB;
768 needs64KiB in this fixture. For768, wire is47.409/45.087/10.740B per block,
time130/125/51ns, allocation208/198/16B. Keep64KiB capability absent a deliberate
workload-specific memory tradeoff; lowering the ceiling does not fix256/4096.
This measures negotiated capacities on one implementation, not hard-cap variants
or retained heap. Three-fork intervals are exploratory, not equivalence proofs.

All requested timing fixtures validate. **MIXED32768 fails periodicity** (not a
requested timing case); failure preserved, not timed, guard unchanged. History
control finds CHURN4096 fresh→grown/shrunk1472→176ns at identical wire/336B,
showing backing history matters; no resize/retained-heap claim. Traversal is
6.15→6.20ns, zero allocation, supporting its +5% control margin. Allocation-mode
variation remains important for repeated exact hits and some capacity cells.

Build tests:113 baseline/124 candidate HPACK. Postmeasurement full HTTP/2:
1642 tests,0 failures/errors,5 skips; paranoid HPACK124 pass. See
[latest conclusions and verification](results/hpack/RESULTS.md),
[fork-level analysis](results/hpack/iteration3/ANALYSIS.md), and
[plan/raw/provenance/scripts](results/hpack/iteration3/PLAN.md).

## Capacity-knee fixture design (now measured above)

New scenarios in `HpackStateBenchmark` retain the historical `WORKING_SET`
name and its 512-entry data. All use four fields/block, the same four names
`x-key-0` through `x-key-3`, and `value-<id>` with fixed decimal IDs. These are
**reachable unique name/value working sizes**, using HPACK's 32-byte entry
charge, not heap size, bucket capacity, or a claim that every entry is admitted:

| Scenario | Unique entries | Exact HPACK bytes | Capacity band |
|---|---:|---:|---|
| `WORKING_SET128` | 128 | 6034 | below 16 KiB (above 4 KiB) |
| `WORKING_SET384` | 384 | 18322 | between 16 and 32 KiB |
| `WORKING_SET` (unchanged) | 512 | 24466 | between 16 and 32 KiB |
| `WORKING_SET768` | 768 | 36754 | between 32 and 64 KiB |

For these cardinalities N, the exact sum is `48*N - 110`: each entry costs
`32 + 7 + 6 + decimalDigits(id)` bytes, with IDs 0 through N−1. Because all N
are multiples of four, an ID always has the same name. The corpus remains
2048 blocks / 8192 fields; the original power-of-two cursor and timed primary
methods are unchanged. For 384, IDs 0–127 occur 22 times and the rest 21;
for 768, IDs 0–511 occur 11 times and the rest 10. Thus these two scenarios
restart a partial working-set cycle at the corpus boundary, rather than
claiming uniform frequency. Every ID is reachable. Setup still requires
periodic second/third-cycle bytes and validates two captured replay cycles,
including this boundary. No randomness or invocation fixture allocation is added.

`growThenShrink` defaults to `false`. With `true`, setup requires final
`tableSize=4096`, requests and verifies 65536 on the encoder, then shrinks it
to 4096 **before any headers are inserted**. This isolates historical backing
structure sizing, not high-capacity traffic, resize latency, retained heap
measurement, or a timed SETTINGS exchange. The decoder is configured for the
final size, and normal warmup/wire validation follows. Unsupported 65536
requests fail even though the final size is supported. Compare false/true at
4096 on the same implementation; use a heap diagnostic separately for retention.

`traverseHeaders` blackholes both names and values through the same header
container iterator, with no HPACK work. It is a diagnostic control for iterator
allocation/JIT behavior: it does not replace containers or change `encode`.
Its allocations are **not** a number to mechanically subtract from encoder
allocation; escape analysis may differ. Use an explicit `.encode`/`.decode`
benchmark selector when reproducing historical matrices: broad selectors now
also include this control and the three new scenarios.

### Capacity versus implementation cap: required comparison

Compare **actual effective capacities** 16384, 32768, and 65536 using the same
codec implementation and new fixture source. `tableSize` is a requested
negotiated HPACK table maximum, not an implementation hard-cap parameter.
`newEncoder` verifies the effective getter and rejects clamping; do not remove
that guard or relabel a clamped request as a larger-capacity measurement.
Even an accepted maximum does not guarantee admission of every entry (collision
policy can reduce useful occupancy), so capture wire bytes alongside timing.

A codec with a 16 KiB implementation ceiling cannot supply a 32/64 KiB actual
capacity comparison by passing larger `tableSize` values. First obtain a
version that genuinely supports all three capacities, or explicitly prepare
and identify otherwise-equivalent cap variants with main/codec-owner approval.
Do not pass off measurements of different algorithms as a pure hard-cap sweep.
The upstream 4096-clamped baseline can use exactly this benchmark source at
supported sizes; unsupported cases must fail, not be silently skipped/relabelled.
Historical 4096→65536 results below alone do **not** locate a 16/32/64 KiB knee.

No build or timing was performed while codec changes were in flight. The
iteration3 campaign above subsequently compiled and validated the additions.
For reproduction, run deterministic validation (no JMH timing) on each newly
built isolated jar, preserving historical jars. Note that the combined candidate
command stops at the documented MIXED32768 periodicity failure; run65536
separately and retain the failure, rather than disabling validation:

```sh
# Supported capacities only; an intentional unsupported request must fail.
java -cp /tmp/hpack-capacity.jar io.netty.handler.codec.http2.HpackStateBenchmark \
  4096 16384 32768 65536 > /tmp/hpack-capacity-wire.csv
java -cp /tmp/hpack-capacity.jar io.netty.handler.codec.http2.HpackStateBenchmark \
  --growThenShrink 4096 > /tmp/hpack-history-wire.csv
# Same source built against upstream, where only these sizes are supported:
java -cp /tmp/hpack-capacity-baseline.jar io.netty.handler.codec.http2.HpackStateBenchmark \
  0 256 4096 > /tmp/hpack-capacity-baseline-wire.csv
```

The wire reporter appends a `growThenShrink` column to distinguish histories.
Future timing (only when requested) should select `.encode` with
`scenario=WORKING_SET128,WORKING_SET384,WORKING_SET,WORKING_SET768` and
`tableSize=16384,32768,65536`, `growThenShrink=false`; separately compare
`growThenShrink=false,true` at 4096 and run `.traverseHeaders` with GC profiling.
Retain wire reports, effective-size rejection evidence, source/jar hashes,
explicit selectors and all parameters. No cap recommendation is implied yet.

## Historical: final iteration2 versus upstream baseline

Completed 2026-10-10 **17:31:30–18:11:25 UTC (39m55s measurement)**: **228 fresh JVM forks / 1140 measured iterations**, all 17 matched cases and four candidate-only 65536 cases. Six alternating AB/BA pairs, 5×1s warmup and measurement, GC profiler, Homebrew JDK21, one thread, 512MiB heap. No production or benchmark Java edits; preserved all previous jars and evidence. New isolated jar: `/tmp/hpack-iteration2.jar`.

**Initialization savings remain; HUFFMAN and NAME_HIT support practical noninferiority, but blanket non-regression is NOT established.** Twelve of 17 matched cases support the predeclared +5% timing margin; five remain unresolved (REPEATED at 0/256/4096, COLLISION4096, decode HUFFMAN control). None establishes a >5% regression, but REPEATED256/4096 and COLLISION4096 have positive timing intervals. This is not universal proof or user acceptance.

Two-sided 95% intervals bootstrap six paired fork means, 100000 resamples stratified by AB/BA; iterations are not independent replicates. Allocation tolerance is max(1 B/op, 1% baseline). These are analysis conventions; intervals are per-case, not simultaneous family-wide guarantees. No forks discarded or historical data pooled. This session launched no concurrent measurement/build/test workloads; external host idleness is not certified.

| Matched case | Baseline → iteration2 ns/op | Change [fork95%] | B/op baseline → iteration2 |
|---|---:|---:|---:|
| encode/HUFFMAN/4096 | 3348.21 → 3268.68 | -2.38% [-4.86, +0.25] | 208.0 → 208.0 |
| encode/REPEATED/4096 | 44.55 → 47.48 | +6.58% [+0.47, +17.34] | 32.0 → 40.0 |
| encode/REPEATED/256 | 42.62 → 47.50 | +11.44% [+0.98, +24.17] | 24.0 → 40.0 |
| encode/REPEATED/0 | 37.30 → 38.35 | +2.81% [-5.96, +14.16] | 4.0 → 8.0 |
| encode/COLLISION/4096 | 970.18 → 1013.41 | +4.46% [+1.77, +8.15] | 168.0 → 168.0 |
| encode/COLLISION/256 | 128.07 → 128.61 | +0.42% [-3.58, +4.54] | 208.0 → 208.0 |
| encode/NAME_HIT/4096 | 835.87 → 793.73 | -5.04% [-6.66, -3.38] | 208.0 → 208.0 |
| encode/NAME_HIT/256 | 173.80 → 110.92 | -36.18% [-37.32, -34.99] | 208.0 → 208.0 |
| encoder/INIT/4096 | 33.11 → 27.98 | -15.50% [-16.01, -15.13] | 744.0 → 656.0 |

INIT saves 88 B/context (11.83%), allocation delta interval [−88,−88]. REPEATED allocation delta intervals: 4096 [0,24], 256 [0,32], zero-table [−8,20] B/op; COLLISION4096 [−24,24]. Thus allocation neutrality is unresolved for these four cases despite equal COLLISION means. Other matched cases are within allocation tolerance. All matched wire sizes agree.

### 65536 capacity results (candidate only)

These compare iteration2 at 65536 with iteration2 at 4096, **not upstream**; no NAME_HIT65536 was in the requested original four-case matrix.

| Case | 4096 → 65536 ns/block | Ratio [fork95%] | B/block 4096 → 65536 |
|---|---:|---:|---:|
| WORKING_SET | 721.18 → 52.78 | 0.0732 [0.0652, 0.0814] | 208.0 → 24.0 |
| MIXED | 506.03 → 100.72 | 0.1990 [0.1910, 0.2061] | 208.0 → 103.0 |
| CHURN | 1450.19 → 199.67 | 0.1377 [0.1340, 0.1421] | 336.0 → 336.0 |
| COLLISION | 1013.41 → 222.58 | 0.2196 [0.2045, 0.2347] | 168.0 → 32.0 |

Capacity ratios are descriptive round-index bootstrap comparisons, not adjacent matched upstream AB/BA experiments. COLLISION wire size still increases 72 → 75.859 B/block; large-table capacity does not improve every compression outcome.

### Historical iteration1 and verification

Iteration1 (`optimized/`) measured HUFFMAN +5.73%, INIT −15.32%, COLLISION4096 −0.87%, NAME_HIT256 +3.26% against its own baseline. Current results are a separate campaign against upstream. They do not measure iteration2 versus iteration1, establish significance between iterations, or isolate the causal effects of Huffman reintegration/fused admission. Particularly large NAME_HIT256 changes warrant this caution. Fork/JIT/allocation modes and the variable unchanged decode control limit attribution.

Fresh reactor benchmark-jar build succeeded (124 HPACK tests). Full HTTP/2: **1642 tests, zero failures/errors, five existing skips**. Paranoid HPACK: **124 tests, zero failures/errors/skips**. Logs archived alongside raw results. Initial/final verification checks source patch, encoder and benchmark hashes, baseline/current packaged classes against compiled outputs, and preservation of earlier jars. `git diff --check` passed.

Iteration2 jar SHA256: `d2598337ee0876c8e44ad178e90825d9fb58c9784ad64bd0b4ed0a7c8149652c`.

Codec patch SHA256: `c9199c0869a31d5835261550d78d2cd91f2debf5ae01e9a706eebebd26cb3105`.

[Complete fork analysis](results/hpack/iteration2/ANALYSIS.md), [plan](results/hpack/iteration2/PLAN.md), and raw/provenance artifacts in `results/hpack/iteration2/`.

## Historical iteration1: optimized candidate, six-round comparison

Completed **228 fresh JVM forks / 1140 measured iterations in 40m14s** on
2026-10-10, with Homebrew21, GC profiling, six independent forks per version/case,
5×1s warmup and 5×1s measurement, sequential AB/BA rounds. All six pairs of
COLLISION4096, NAME_HIT256 and INIT4096 ran first, followed by the full requested
matrix. The new isolated `/tmp/hpack-optimized.jar` was built successfully before
measurement; baseline and old candidate jars and prior evidence remain unchanged.
No production or benchmark Java edits were made by this campaign.

**Initialization is fixed; elimination of the timing regressions is not established.**
INIT4096 improves **15.32% [14.78%,15.85%]**, allocating **656 versus 744 B/context**
(upstream), rather than the prior candidate's 752. COLLISION4096 is **−0.87%
[−7.90%,+7.26%]**; NAME_HIT256 **+3.26% [−10.53%,+18.64%]**. Both remain unresolved
under the predeclared **+5% analysis noninferiority margin, not user acceptance**.
Nine of 17 matched cases support that margin; eight are unresolved; none establishes
>5% regression. REPEATED4096 and HUFFMAN encode4096 show positive timing intervals,
so this is not a blanket no-regression result. Strong fork/order variation also
appears in controls. No forks were discarded.

All matched wire sizes agree. Candidate65536 retains large capacity benefits for
WORKING_SET/MIXED; COLLISION still worsens wire size from 72 to 75.859 B/block.
See [latest results](results/hpack/RESULTS.md), [all cases, fork-level intervals and
allocation](results/hpack/optimized/ANALYSIS.md), and
[premeasurement plan](results/hpack/optimized/PLAN.md). Exact commands, 228 raw JSON
files and compressed logs, patch, and initial/round/final provenance are in
`results/hpack/optimized/`. External host idleness could not be certified (`ps`
was sandbox-denied); this session launched no overlapping jobs.

## Historical: previous candidate order-balanced comparison

Completed **228 fresh JVM forks / 1140 measured iterations in 40m06s**:
six independent forks per case/version, **5 × 1s warmup / 5 × 1s measurement**,
GC profiling, sequential adjacent **AB/BA pairs alternating each round**.
Coverage: all nine encode4096 scenarios; REPEATED/CHURN/NAME_HIT/COLLISION
encode256; REPEATED/CHURN encode0; encoder init4096; unchanged HUFFMAN
decode4096 control; candidate65536 WORKING_SET/MIXED/CHURN/COLLISION.
No production edits. Existing jar hashes, current codec patch/source, benchmark
sources and available compiled outputs were verified; no concurrent builds.

See [latest results, provenance and interpretation](results/hpack/RESULTS.md)
and [complete fork-level tables](results/hpack/balanced/ANALYSIS.md).
Raw JSON, compressed logs, exact command manifest, new wire CSVs, source/hash
provenance, predeclared plan and reproduction/analysis scripts are preserved in
`results/hpack/balanced/`.

* Predeclared **+5% timing noninferiority** and allocation tolerance
  **max(1 B/op, 1% of baseline)** are analysis conventions, **not user acceptance
  criteria**. Paired, order-stratified fork bootstrap gives per-case 95% ratio
  intervals; six pairs and host/JIT variability limit certainty. Nonsignificance
  is not equivalence. No pooling with earlier measurements.
* **Slowdown evidence remains:** COLLISION encode4096 **+10.21% [3.10%, 17.37%]**;
  NAME_HIT encode256 **+4.87% [3.55%, 6.21%]**. Both intervals exclude no change,
  but straddle the practical 5% threshold: neither NI nor a >5% regression is
  established. Do not call them neutral based on the earlier confirmation.
* **Initialization allocation increases 744 → 752 B/context** (+8 B, +1.075%),
  exceeding the declared allocation tolerance. Timing is +3.71% [2.71%, 4.46%].
  REPEATED and COLLISION allocations vary by fork; their allocation neutrality
  remains unresolved. No implementation fix was made.
* **9/17 matched cases support timing NI; only 8/17 support both gates.** Eight
  timing cases remain unresolved; none establishes >5% timing regression under
  the interval rule. **Blanket non-regression is not established.** The unchanged
  decoder remains noisy (-8.73% [-21.45%, +6.77%]); it is not used to adjust results.
* Capacity benefits repeat: candidate WORKING_SET4096→65536 **779.49→53.61 ns**,
  **208→24 B allocation**, **47.141→9.984 B wire**; MIXED **548.91→98.25 ns**,
  **208→103 B allocation**, **48.216→24.223 B wire**. These are capacity comparisons,
  not matched-capacity implementation speedups. CHURN wire is unchanged;
  COLLISION wire worsens **72→75.859 B/block**. All matched wire sizes are identical.

## Historical measured comparison

The following initial results and focused confirmation are retained for context;
the newer balanced evidence above supersedes their provisional interpretation.

Completed **100 cases / 300 forks / 900 measured iterations**, including all nine
scenarios, both encode/decode and initialization. See
[full results and uncertainty](results/hpack/RESULTS.md), raw JMH JSON and wire
CSV in `results/hpack/`, and `environment.json` for jar hashes/JDK. Compressed
logs and the exact candidate codec patch are included. The measured candidate
includes the reviewer `canIndex` fix; a subsequent rebuild confirmed **all 8099
packaged classes byte-identical** to the measured jar.

* Capacity benefit is demonstrated: `WORKING_SET` wire size falls from **47.141
  to 9.984 B/block (78.8%)** at 64 KiB; `MIXED` falls from **48.216 to 24.223
  B/block (49.8%)**. Candidate encode time at 4096 → 65536 is **775.3 → 55.0
  ns/block** and **530.7 → 96.8 ns/block**, respectively. These are capacity
  comparisons, not matched-capacity implementation speedups.
* Candidate 64 KiB initialization costs **327.0 ns / 8976 B** per encoder versus
  **34.3 ns / 752 B** at 4096. Larger capacity is not free.
* Matched capacities have identical wire sizes and essentially unchanged
  allocation. Timing is **not uniformly neutral**: candidate NAME_HIT encode
  at 256 is **18.1% slower**, COLLISION encode at 4096 **18.4% slower**, and
  HUFFMAN decode is **7–8% slower**. Several other changes are small or have
  wide intervals. Do not claim blanket regression-free performance.
* At 65536 adversarial COLLISION wire expands from **72.0 to 75.859 B/block**
  as bounded admission declines indexing; faster processing is not better
  compression. Streaming CHURN/NAME_HIT gain no wire-size benefit.
* Runs were sequential, baseline then candidate per capacity, on a shared
  macOS ARM64 host. Three short warmup/measurement iterations bound runtime;
  thermal/JIT/run-order noise remains. Unchanged decoder controls also shift,
  so observed deltas are not all attributable to encoder changes.
* A subsequent focused **candidate-first confirmation** used 3 forks and
  5 × 1s warmup / 5 × 1s measurement with GC profiling on the same isolated
  jars. NAME_HIT encode256 was **+1.8%** (overlapping intervals), COLLISION
  encode4096 **-1.6%**, and the noisy unchanged HUFFMAN decode4096 control
  **-2.1%**. The original large encoder regressions were **not reproduced**;
  this is not proof of equivalence or a regression-free full matrix.
  **The blanket non-regression goal is not established and must not be claimed
  met.** See the results document's focused confirmation for raw evidence,
  fork variability, allocation changes, and the acceptance verdict.


`HpackStateBenchmark` measures a reused, thread-confined encoder or decoder. One
operation is **one block of four fields**, not one field and not an HTTP/2 frame.
`HpackInitializationBenchmark` separately measures construction plus table-size
configuration (no first block, frame codec, channel, or header container).

## Workloads

* `REPEATED`: four exact dynamic-table hits once warm (all fit in 256 bytes).
* `WORKING_SET`: 512 reusable name/value entries (24466 HPACK bytes), cycled every
  128 blocks; historical workload unchanged. `WORKING_SET128`, `WORKING_SET384`,
  and `WORKING_SET768` add capacity-knee coverage (exact sizes above).
* `MIXED`: three hot fields per block drawn from 384 reusable entries, plus one
  cold field advancing across 2048 values. Measures hot reuse under eviction.
* `CHURN`: unique names and values; the cyclic working set exceeds 64 KiB.
* `NAME_HIT`: four reused names with changing values; dynamic name hits and eviction.
* `COLLISION`: one name, distinct 16-byte mixed-case values with identical
  `AsciiString` hashes. Setup asserts the collision property. Deliberately
  adversarial full-hash collisions, not a representative traffic distribution.
* `STATIC`: four static-table fields; control with no dynamic insertions.
* `SENSITIVE`: changing values, never indexed.
* `HUFFMAN`: changing 600-byte highly compressible values, above the encoder's
  default 512-byte Huffman threshold. Other scenarios use short raw literals.

Default table sizes are `0,256,4096`. The baseline at `014ef8d703` caps the default
encoder at 4096 bytes (`64` buckets times `64`). Setup checks the **effective**
size, failing rather than silently benchmarking a clamped table. Use
`-p tableSize=65536` only on a candidate which actually supports it. Decoder-only
runs also check encoder support because the encoder produces their input.

## Fixture correctness and allocations

All header objects, strings, encoded input buffers and a fixed-capacity output
buffer are built at trial setup, never at invocation/iteration boundaries.
There are no timed `duplicate()`, buffer allocations, fresh contexts, random
strings, or fixture resets. Codec-created iterators, entries, decoded strings,
and per-block codec sinks remain part of measured codec cost.

Setup encodes and fully validates three sequential corpus cycles. It captures
the second cycle and requires byte-identical output in the third. The decoder
then validates two actual captured replay cycles, including wrap boundaries.
Both measured peers start at a cycle boundary in steady state; each advances its
own cursor across JMH iteration boundaries without resetting the table. This is
important: replaying bytes generated by a fresh encoder is not generally valid
for a persistent decoder. Setup exceptions invalidate a result.

The timed decoder uses a reusable `Http2Headers` sink that blackholes **both**
name and value. Header collection allocations are deliberately excluded; string
and dynamic-table allocations inside HPACK are not. Header semantic validation
is disabled in both setup and timing, to isolate HPACK rather than request
validation. These are codec microbenchmarks, not end-to-end frame processing.
Decoder comparisons use each encoder's own produced wire, not a shared wire
fixture: differences can reflect compression/admission policy, not isolated
decoder performance (particularly at 65536).
Use `-prof gc` and compare `gc.alloc.rate.norm` (B/block), not MB/sec. Trial setup
is outside the steady-state measurement, but use adequate warmup to eliminate
its GC/cache effects. The initialization results report B/context.

The prior local `perf/hpack-general-purpose` branch supplied useful persistent
context and lifecycle ideas. Its cold-corpus replay requires workload-specific
proof, its discard sink does not observe decoded values, and its lifecycle/frame
benchmarks include container/channel allocations. These benchmarks instead
prove steady-cycle replay and separate initialization from codec hot paths.
Multiple cache-cold connections and frame integration are outside this suite.

## Build and run

On this macOS host (the default `java` launcher is not usable):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
# From the checkout root. Build sequentially after other codec tests finish.
# This reactor build uses current sources, not previously installed SNAPSHOTs.
mvn -pl microbench -am '-Pbenchmark-jar,!mac' \
  '-Dtest=Hpack*Test' -Dsurefire.failIfNoSpecifiedTests=false package
java -jar microbench/target/microbenchmarks.jar -l '.*Hpack(State|Initialization)Benchmark.*'

# Full validation/smoke matrix, NOT performance evidence.
java -jar microbench/target/microbenchmarks.jar \
  '.*Hpack(State|Initialization)Benchmark.*' \
  -f 1 -wi 0 -i 1 -r 100ms -foe true

# Deterministic wire report (not timed):
java -cp /tmp/hpack-baseline.jar io.netty.handler.codec.http2.HpackStateBenchmark \
  256 4096 > /tmp/wire-baseline.csv
java -cp /tmp/hpack-candidate.jar io.netty.handler.codec.http2.HpackStateBenchmark \
  256 4096 65536 > /tmp/wire-candidate.csv

# Actual measured matrix: run sequentially for baseline256, candidate256,
# baseline4096, candidate4096, candidate65536. Omitting scenario now runs all twelve.
# Set VERSION and SIZE to the corresponding values for each invocation.
java -jar /tmp/hpack-${VERSION}.jar '.*Hpack(State|Initialization)Benchmark.*' \
  -p tableSize=${SIZE} -f 3 -wi 3 -w 1s -i 3 -r 1s -t 1 -prof gc \
  -foe true -jvmArgs '-Xms512m -Xmx512m' -rf json \
  -rff microbench/results/hpack/${VERSION}-${SIZE}.json

# Smaller matched bounded comparison, run unchanged on each isolated jar.
java -jar /tmp/hpack-baseline.jar '.*HpackStateBenchmark.*' \
  -p scenario=REPEATED,CHURN,NAME_HIT,COLLISION -p tableSize=256,4096 \
  -f 3 -wi 3 -w 1s -i 3 -r 1s -t 1 -prof gc -foe true \
  -jvmArgs '-Xms512m -Xmx512m' -rf json -rff /tmp/hpack-baseline.json
# Repeat with /tmp/hpack-candidate.jar and /tmp/hpack-candidate.json.
# Then candidate only: same command with -p tableSize=65536 and distinct output.

# Initialization (run on both jars):
java -jar /tmp/hpack-candidate.jar '.*HpackInitializationBenchmark.*' \
  -p tableSize=256,4096 -f 3 -wi 3 -w 1s -i 3 -r 1s -prof gc \
  -jvmArgs '-Xms512m -Xmx512m' -foe true -rf json -rff /tmp/hpack-init-candidate.json
```

For an isolated baseline, copy only these two Java benchmark files to the
baseline worktree outside the checkout, then build its reactor with the same command and JDK.
The measured baseline was moved to `/tmp/netty-hpack-baseline`: nested worktrees
are scanned by the root nohttp check. On this host `!mac` disables irrelevant
native kqueue packaging (whose native-src artifact is unavailable); HPACK is Java.
Keep build logs outside the checkout as Maven output may contain plain HTTP URLs. Copy
each shaded jar to its versioned `/tmp` path immediately after building. Use
`package`, not `install`, and never build the two trees simultaneously: installing
identical SNAPSHOT coordinates can contaminate another build. Do not reuse an
old `microbenchmarks.jar`; confirm the reactor succeeded and inspect the jar's
benchmark listing. Record source revisions/diffs, JDK, machine, flags and jar
SHA-256 sums alongside JSON. Do not run codec tests or other benchmarks in
parallel with measurements. Longer runs and reversed run order are recommended
before accepting a small change; JMH's default 99.9% confidence intervals from
three forks are uncertainty estimates, not guarantees. A 65536-byte result is a
capacity experiment, not a same-capacity speedup versus a 4096-byte baseline.
