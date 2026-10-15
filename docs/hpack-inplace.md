# HPACK in-place growth: current follow-up

This is the current follow-up to the [historical three-stage report](hpack-64k-series.html), whose comparisons remain pinned to their original stages. [Open the self-contained HTML report](hpack-inplace.html). [Exhaustive comparisons](hpack-inplace-comparisons.md) include every browser, synthetic and initialization cell, allocation and paired confidence interval. [Portable raw evidence](hpack-inplace-evidence.tar.gz) includes commands, logs, source snapshots, provenance and hash manifests.

## Why keep the encoder object?

The motivating common case is initial peer SETTINGS, before headers have populated the encoder. An empty default-4K encoder configured to 64K needs larger lookup arrays, not a replacement encoder, sentinel, Huffman helper and copied state. The new void setter keeps the encoder identity stable and the wrapper reference final. This is a startup optimization, not a claim that late populated growth is common or faster.

The rarer populated-growth path retains existing nodes. Both new arrays are allocated before any links change; rehashing uses cached hashes (no user CharSequence callbacks). The two bucket-array fields and short mask are non-final. Only hash-chain next links change: FIFO after links, entry counters and name representatives are untouched. FIFO eviction and HPACK indexing are unchanged. High-water arrays are reused after shrink; shrinking can still evict entries as required by capacity.

Raw hash mapping, bounded admission, collision policy and sensitivity/security behavior are unchanged. This does not make sensitive headers safe to index: callers must still identify them. 64K capacity remains possible, but poor admission under hostile/skewed distributions can underfill it; capacity is not a promise of occupancy or compression. Existing runtime proofs measured exactly 64,887 bytes / 1120 entries, max chain 7 and max dynamic index 1181 in both tested distributions. Those observations are not a universal guarantee.

## Measured answer, not a parity claim

64K initialization: replacement → inplace **374.145 → 334.628 ns per encoder**, and **9192 → 8992 B per encoder**. Paired timing delta −39.516 ns, 95% CI [−46.057, −32.976]; allocation delta about −200 B. Initialization includes construction and empty configuration, not populated growth latency. Upstream was not measured at 64K because it does not support it.

4K initialization: upstream / replacement / inplace **36.088 / 36.419 / 38.172 ns per encoder**, approximately **768 B in all three**. All 54 timing CIs at 4K cross zero. This is inconclusive, NOT parity, equivalence or non-inferiority: there was no predeclared NI margin. Meaningful slowdowns remain possible. No outliers are hidden or removed. See especially new NORMAL/NEVER (633.190 / 927.691 / 1210.756 ns) and CHURN (1540.779 / 1512.810 / 1653.074 ns), plus the 64K persistent counterexamples in the exhaustive tables.

Units: browser ns and B are **per request/header block** (already normalized over 64 blocks; do not divide again); synthetic is **per four-field block**; initialization is **per encoder**. NEVER is primary, INDEX_COOKIES supporting. No pooled cell average is used. The shared-host campaign has 384 forks and 1920 measurement iterations, not 1920 independent samples. Paired-t CIs are pointwise, unadjusted and descriptive; multiplicity and host variability limit generalization.

## Evidence and verification scope

The six codec/test files exactly match the frozen source proof; benchmark sources are unchanged. Preserved current-source test logs report focused 148 tests, zero failures/errors/skips; full paranoid HTTP/2 1664 tests, zero failures/errors, 5 skips. These are existing runs, not fresh executions during documentation finalization. Earlier failures are preserved alongside corrected successful logs.

The archive excludes jars, classes, Maven repositories and caches, preserves original bytes and absolute-path provenance, and carries a deterministic content manifest plus omitted-binary hashes. The portable verifier validates the archive manifest, chained ledger/raw hashes and independently recalculates all 126 paired comparisons. The original full offline analysis additionally requires original binaries, external frozen paths and precommit git identity; it is not made self-contained by omitting those binaries. No production rebuild or new timing run was performed.

Decompressed archive security review found synthetic fixture credential markers (SYNTHETIC_NOT_A_CREDENTIAL_), literal test secrets and redacted probe values, not actual credentials. Local paths and command provenance are intentionally retained. No claim is made that a heuristic scan proves absence of every possible secret.

HTML checks are static: generated table fidelity, unique anchors, local links, no external resources or scripts. There is no browser visual-validation claim (macOS sandbox constraints).

## Reproduce without a benchmark run

From the worktree root, using Python 3 standard library only:

```sh
python3 -B docs/hpack-inplace-tools/verify.py docs/hpack-inplace-evidence.tar.gz
python3 -B docs/hpack-inplace-tools/security.py docs/hpack-inplace-evidence.tar.gz
python3 -B docs/hpack-inplace-tools/render.py
```

To recreate the deterministic archive from the original evidence directory (not required to read or verify it):

```sh
python3 -B docs/hpack-inplace-tools/package.py ../hpack-inplace-evidence
```

Two packaging passes must yield identical SHA256. Run `git diff --check` and `git diff --cached --check` without whitespace exemptions; immutable raw log/JSON bytes remain inside the archive rather than being normalized. Original commands for each fork are recorded in schedule.json and ledger.jsonl within the archive.

Archive: **3276094 bytes**, SHA256 `197d79caf8034d3446924e0ff432b4e7f15fcb79850c4eb954f07ddcea3049a0`.
