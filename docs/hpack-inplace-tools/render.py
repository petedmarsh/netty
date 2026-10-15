#!/usr/bin/env python3
"""Render the offline current report directly from the archived exhaustive report."""
import hashlib
import html
from html.parser import HTMLParser
from pathlib import Path
import re
import tarfile

root = Path(__file__).resolve().parents[1]
archive = root / 'hpack-inplace-evidence.tar.gz'
with tarfile.open(archive) as tar:
    results = tar.extractfile('evidence/results.md').read().decode()
(root / 'hpack-inplace-comparisons.md').write_text(results)
intro = '''# HPACK in-place growth: current follow-up

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
'''
intro += '\nArchive: **' + str(archive.stat().st_size) + ' bytes**, SHA256 `' + hashlib.sha256(archive.read_bytes()).hexdigest() + '`.\n'
(root / 'hpack-inplace.md').write_text(intro)

def inline(s):
    s = html.escape(s)
    s = re.sub(r'\[([^]]+)\]\(([^)]+)\)', r'<a href="\2">\1</a>', s)
    s = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', s)
    return re.sub(r'`([^`]+)`', r'<code>\1</code>', s)


def render(md, prefix):
    out = []
    table = False
    code = False
    for line in md.splitlines():
        if line.startswith('```'):
            out.append('</code></pre>' if code else '<pre><code>')
            code = not code
            continue
        if code:
            out.append(html.escape(line) + '\n')
            continue
        if line.startswith('|'):
            if re.match(r'^\|[-:| ]+\|$', line):
                continue
            cells = line.strip('|').split('|')
            tag = 'td' if table else 'th'
            if not table:
                out.append('<div class="scroll"><table>')
            table = True
            out.append('<tr>' + ''.join('<'+tag+'>'+inline(x.strip())+'</'+tag+'>' for x in cells) + '</tr>')
            continue
        if table:
            out.append('</table></div>')
            table = False
        if line.startswith('#'):
            n = len(line) - len(line.lstrip('#'))
            title = line[n:].strip()
            anchor = prefix + re.sub('[^a-z0-9]+', '-', title.lower()).strip('-')
            out.append(f'<h{n} id="{anchor}">{inline(title)}</h{n}>')
        elif line:
            out.append('<p>'+inline(line)+'</p>')
    if table:
        out.append('</table></div>')
    return '\n'.join(out)

svg = '''<section id="architecture"><h2>One stable object, new lookup arrays</h2><p>Source-only architecture diagram, not a heap measurement. In both cases FIFO means oldest entries leave first.</p>
<svg viewBox="0 0 900 360" role="img" aria-labelledby="diagram-title diagram-desc" xmlns="http://www.w3.org/2000/svg">
<title id="diagram-title">Old replacement versus new in-place growth</title><desc id="diagram-desc">Old wrapper switches to a new encoder with new arrays and copied nodes. New final wrapper reference keeps the same encoder, allocates both arrays, and relinks hash chains on existing nodes. FIFO after links remain unchanged.</desc>
<g fill="#e8eff8" stroke="#38628c" stroke-width="2"><rect x="10" y="45" width="420" height="120" rx="12"/><rect x="460" y="45" width="430" height="120" rx="12"/><rect x="10" y="215" width="420" height="120" rx="12"/><rect x="460" y="215" width="430" height="120" rx="12"/></g>
<g fill="#173450" font-family="system-ui,sans-serif" font-size="18"><text x="20" y="26">OLD: replacement-return API</text><text x="30" y="77">wrapper → encoder A + old arrays</text><text x="30" y="110">growth: allocate encoder B + arrays</text><text x="30" y="143">copy nodes/state; switch wrapper → B</text><text x="480" y="77">FIFO copied oldest → newest</text><text x="480" y="110">old object is not the new object</text><text x="480" y="143">extra startup objects / copying</text><text x="20" y="196">NEW: void setter; stable identity</text><text x="30" y="247">final wrapper → same encoder A</text><text x="30" y="280">allocate BOTH arrays before relinking</text><text x="30" y="313">publish arrays + mutable short mask</text><text x="480" y="247">existing nodes: cached hash → bucket</text><text x="480" y="280">hash next links change; no callbacks</text><text x="480" y="313">FIFO after links / counters untouched</text></g></svg></section>'''
page = '''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'"><title>HPACK in-place growth — current measured report</title><style>body{font:16px/1.6 system-ui,sans-serif;color:#173450;background:#f7f9fc;margin:0}main{max-width:1200px;margin:auto;padding:32px}h1,h2{line-height:1.2}h2{margin-top:2.5rem}a{color:#145b99}p{max-width:100ch}table{border-collapse:collapse;font-size:14px;width:100%}td,th{border:1px solid #ccd5df;padding:8px;text-align:left}th{background:#e8eff8}.scroll{overflow-x:auto}pre{overflow:auto;background:#e8eff8;padding:16px}svg{width:100%;height:auto}nav{padding:16px;background:#e8eff8}</style></head><body><main><nav><a href="#architecture">How growth works</a> · <a href="#exhaustive">All measured comparisons</a> · <a href="hpack-inplace-evidence.tar.gz">Portable evidence</a> · <a href="hpack-64k-series.html">Historical stages</a></nav>'''
page += render(intro, 'current-') + svg + '<section id="exhaustive">' + render(results, 'results-') + '</section></main></body></html>\n'
(root / 'hpack-inplace.html').write_text(page)

class Check(HTMLParser):
    ids = []
    links = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        assert tag not in {'script', 'iframe', 'object', 'embed', 'link'}, tag
        assert 'src' not in attrs and not any(k.startswith('on') for k in attrs)
        if 'id' in attrs:
            self.ids.append(attrs['id'])
        if 'href' in attrs:
            self.links.append(attrs['href'])

check = Check()
check.feed(page)
assert len(check.ids) == len(set(check.ids))
for link in check.links:
    if link.startswith('#'):
        assert link[1:] in check.ids, link
    else:
        assert ':' not in link and (root / link).is_file(), link
assert page.count('<table>') == results.count('| Cell |') + 1 == 11
for line in results.splitlines():
    if line.startswith('|') and not re.match(r'^\|[-:| ]+\|$', line):
        assert all(inline(c.strip()) in page for c in line.strip('|').split('|'))
assert 'url(' not in page
print('PASS: reproducible HTML, all 11 exhaustive tables, exact cells, unique anchors, local links, no external resources/scripts; no visual browser check')
