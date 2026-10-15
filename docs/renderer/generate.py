#!/usr/bin/env python3
"""Offline, read-only evidence consumer. Never builds, times, freezes or reports."""
import argparse
import csv
import hashlib
import html
import importlib.util
import json
import math
import re
from pathlib import Path
import statistics
import subprocess
import sys
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent
SHAS = ['3eaff63caf06a582cc3df72422aa1124734b46c6',
        '76b8b0b72f156d5524ac9a5317f22f8563d107c6',
        '5a9c64318d3489f11042971014f2398614c32548',
        'ec00a0f50d989f9c53b30256d93e8f0dcaf1983d']
VERSIONS = ['baseline', 'stage1', 'stage2', 'stage3']
PROD = 'codec-http2/src/main/java/io/netty/handler/codec/http2/'
TITLES = ['Baseline', 'Return the encoder', 'Check admission before eviction', 'Grow on SETTINGS, not on lookup']
SCOPES = [
    'The starting encoder keeps fixed hash-bucket arrays. Default construction can use 4 KiB; a custom 128-bucket construction can use 8 KiB.',
    'Change the internal setter to return an encoder, and make the public wrapper retain it. This stage still returns the same object and keeps the old limits. It prepares callers for replacement without introducing growth. Tests and shared benchmark fixtures also change; baseline timing uses the identical fixture overlay.',
    'For tables larger than 4 KiB, reject a new insertion if its collision chains would grow beyond the admission bound. Check first, so a rejected field cannot evict useful retained entries. It is sent as a literal without indexing. This protects already-supported custom 8 KiB tables; it does not raise capacity.',
    'When configuration needs more buckets, construct a replacement with independently owned nodes and larger final arrays. Copy protocol state and entry labels, then let the wrapper retain the replacement. Raise the local ceiling to 64 KiB independently of the initial bucket hint. No growth occurs during ordinary lookup; smaller settings do not shrink the bucket arrays.'
]
def E(value):
    # Preserve verbatim decoded source/diff whitespace without trailing blanks
    # in the generated HTML file (including Git diff blank-context prefixes).
    escaped = html.escape(str(value), quote=True)
    return re.sub(r'[ \t]+(?=\n|$)',
                  lambda m: ''.join('&#32;' if c == ' ' else '&#9;' for c in m[0]), escaped)

def require(ok, message):
    if not ok:
        raise ValueError(message)

def load(path):
    return json.loads(path.read_text())

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def git(repo, *args):
    return subprocess.run(['git', '-C', str(repo), *args], check=True, capture_output=True, text=True).stdout

def read_csv(path):
    with path.open(newline='') as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        require(rows and len(reader.fieldnames) == len(set(reader.fieldnames)), 'Empty/invalid CSV: ' + str(path))
        require(all(None not in r and None not in r.values() for r in rows), 'Malformed CSV: ' + str(path))
        return rows

def same_rows(actual, expected, numeric=()):
    require(len(actual) == len(expected), 'CSV row count mismatch')
    # Report order is part of the runner schema, preventing duplicates/omissions.
    for a, b in zip(actual, expected):
        require(set(a) == set(b), 'CSV columns mismatch')
        for k in b:
            if k in numeric and str(b[k]) != 'undefined':
                x, y = float(a[k]), float(b[k])
                require(math.isfinite(x) and math.isclose(x, y, rel_tol=1e-12, abs_tol=1e-10), 'CSV numeric mismatch: ' + k)
            else:
                require(str(a[k]) == str(b[k]), 'CSV identity mismatch: ' + k)

def comparison_rows(jobs, values):
    rows = []
    comparisons = [('stage1', 'baseline'), ('stage2', 'baseline'), ('stage3', 'baseline'),
                   ('stage2', 'stage1'), ('stage3', 'stage2')]
    for group in ['primary', 'capacity']:
        for cell in sorted({j['cell'] for j in jobs if j['group'] == group}):
            subset = [j for j in jobs if j['group'] == group and j['cell'] == cell]
            index = {(j['round'], j['version'], j['size']): values[j['id']] for j in subset}
            rounds = sorted({j['round'] for j in subset})
            for candidate, reference in comparisons if group == 'primary' else [('stage3', 'stage3')]:
                cs, rs = (4096, 4096) if group == 'primary' else (65536, 4096)
                for metric in ['ns', 'bytes']:
                    a = [index[r, candidate, cs][metric] for r in rounds]
                    b = [index[r, reference, rs][metric] for r in rounds]
                    diff = [x-y for x, y in zip(a, b)]
                    delta = statistics.mean(diff)
                    half = {3: 4.30265273, 6: 2.57058184}[len(diff)] * statistics.stdev(diff) / math.sqrt(len(diff))
                    ref = statistics.mean(b)
                    rows.append(dict(group=group, cell=cell, comparison=f'{candidate}/{cs} - {reference}/{rs}',
                                     metric=metric, pairs=len(diff), candidate_mean=statistics.mean(a),
                                     reference_mean=ref, paired_delta=delta, ci95_low=delta-half,
                                     ci95_high=delta+half, percent_delta=100*delta/ref if ref else 'undefined'))
    return rows

def load_campaign(evidence):
    # Avoid even a bytecode-cache write outside presentation/.
    spec = importlib.util.spec_from_file_location('hpack_campaign_readonly', evidence / 'campaign.py')
    campaign = importlib.util.module_from_spec(spec)
    previous = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(campaign)
    finally:
        sys.dont_write_bytecode = previous
    return campaign


def validate(evidence, extracted=False):
    for name in ['freeze.json', 'schedule.json', 'readiness.json', 'ledger.jsonl',
                 'raw-forks.csv', 'comparisons.csv', 'wire.csv']:
        require((evidence / name).is_file(), 'Final rendering blocked: missing ' + name)
    # Import only the locally reviewed runner; its main guard prevents any action.
    campaign = load_campaign(evidence)
    m = campaign.config()
    prefix = 'io.netty.handler.codec.http2.'
    require(all(c['benchmark'] in [prefix+'HpackBrowserBenchmark.persistentSession',
                                  prefix+'HpackBrowserBenchmark.newConnectionSession']
                for c in m['browser_cases']), 'Unknown browser operation unit: review renderer')
    require(m['synthetic_benchmark'] == prefix+'HpackStateBenchmark.encode' and
            m['init_benchmark'] == prefix+'HpackInitializationBenchmark.encoder',
            'Unknown synthetic/init operation unit: review renderer')
    require([m['stages'][v]['commit'] for v in VERSIONS] == SHAS, 'Wrong series')
    if extracted:
        # Extraction integrity is deliberately NOT full frozen-artifact verification.
        manifest = load(evidence / 'EXTRACTION.json')
        original = Path(manifest['original_root'])
        for name, expected in manifest['files'].items():
            path = (evidence / name).resolve()
            require(path.is_relative_to(evidence.resolve()), 'Unsafe extraction path')
            require(digest(path) == expected, 'Changed extracted file: ' + name)
        frozen = load(evidence / 'freeze.json')
        for name, expected in frozen['files'].items():
            path = Path(name)
            if path.is_relative_to(original):
                relative = str(path.relative_to(original))
                if relative in manifest['files']:
                    require(digest(evidence / relative) == expected, 'Changed frozen text: ' + relative)
        jobs = load(evidence / 'schedule.json')
        require(hashlib.sha256(campaign.encoded(jobs)).hexdigest() == frozen['schedule_sha256'], 'Schedule mismatch')
        campaign.ROOT = original
        require(jobs == campaign.schedule(m), 'Frozen schedule is not the complete configured matrix')
        campaign.ROOT = evidence
    else:
        jobs = campaign.verify_freeze()
        require(jobs == campaign.schedule(m), 'Frozen schedule is not the complete configured matrix')
        inventory = campaign.inventory(m)  # hashes, wire proof, runtime/test attestations; no subprocess
        frozen = load(evidence / 'freeze.json')['files']
        require(all(frozen.get(p) == h for p, h in inventory.items()), 'Proof/inventory absent from freeze')
    events, _ = campaign.ledger()
    values = {}
    for job in jobs:
        ident = job['id']
        done = [e for e in events if e.get('id') == ident and e['event'] == 'process-finished']
        parsed = [e for e in events if e.get('id') == ident and e['event'] == 'parsed']
        require(len(done) == len(parsed) == 1 and done[0]['returncode'] == 0, 'Incomplete job: ' + ident)
        outputs = done[0]['outputs']
        for suffix in ['.json', '.log']:
            p = evidence / 'raw' / (ident + suffix)
            key = str(original / 'raw' / p.name) if extracted else str(p)
            require(outputs.get(key) == digest(p), 'Missing/changed raw proof: ' + str(p))
        require(len(outputs) == 2, 'Unexpected job outputs')
        if not extracted:
            require(all(digest(Path(p)) == h for p, h in outputs.items()), 'Changed job output')
        values[ident] = campaign.parse(job)
        require(values[ident] == parsed[0]['values'], 'Parsed ledger mismatch')
        require(all(x >= 0 for x in values[ident].values()), 'Negative raw measurement')
    raw = read_csv(evidence / 'raw-forks.csv')
    expected = [dict(**{k: j[k] for k in ['group', 'cell', 'round', 'version', 'size']},
                     **{'ns/op': values[j['id']]['ns'], 'B/op': values[j['id']]['bytes']}) for j in jobs]
    same_rows(raw, expected, ['ns/op', 'B/op'])
    comparisons = read_csv(evidence / 'comparisons.csv')
    same_rows(comparisons, comparison_rows(jobs, values),
              ['pairs', 'candidate_mean', 'reference_mean', 'paired_delta', 'ci95_low', 'ci95_high', 'percent_delta'])
    wire = read_csv(evidence / 'wire.csv')
    same_rows(wire, load(evidence / 'readiness.json')['wire_rows'])
    return raw, comparisons, wire

def table(headers, rows):
    return '<div class="scroll"><table><thead><tr>' + ''.join('<th scope="col">'+E(h)+'</th>' for h in headers) + '</tr></thead><tbody>' + ''.join('<tr>'+''.join('<td>'+E(c)+'</td>' for c in r)+'</tr>' for r in rows) + '</tbody></table></div>'

def diagram(title, labels):
    height = 75 * len(labels) + 12
    out = f'<svg role="img" aria-label="{E(title)}" viewBox="0 0 900 {height}"><title>{E(title)}</title>'
    for i, label in enumerate(labels):
        y = 12 + i*75
        if i:
            out += f'<path d="M450 {y-24}v20m-6-7 6 7 6-7" fill="none" stroke="#536987" stroke-width="2"/>'
        out += f'<rect x="12" y="{y}" width="876" height="48" rx="8" fill="#edf3fa" stroke="#b1c3d8"/><text x="450" y="{y+30}" text-anchor="middle">{E(label)}</text>'
    return out+'</svg>'

def chart(title, labels, values, intervals=None):
    """Means have no whiskers; only paired differences receive intervals."""
    bounds = values + ([x for pair in intervals for x in pair] if intervals else [])
    lo, hi = min([0]+bounds), max([0]+bounds)
    span = hi-lo or 1
    x = lambda n: 330 + 500*(n-lo)/span
    height = 65 + 38*len(labels)
    s = f'<svg role="img" aria-label="{E(title)}" viewBox="0 0 960 {height}"><title>{E(title)}</title>'
    s += f'<text x="12" y="22">{E(title)}</text><path d="M{x(0):.2f} 35V{height-22}" stroke="#8492a6"/>'
    for i, (label, value) in enumerate(zip(labels, values)):
        y = 53+i*38
        s += f'<text x="12" y="{y+5}">{E(label)}</text>'
        if intervals:
            a, b = map(x, intervals[i])
            s += f'<path d="M{a:.2f} {y}H{b:.2f}M{a:.2f} {y-5}v10M{b:.2f} {y-5}v10" stroke="#15645e" stroke-width="2"/>'
            s += f'<circle cx="{x(value):.2f}" cy="{y}" r="4" fill="#15645e"/>'
        else:
            s += f'<rect x="{min(x(0), x(value)):.2f}" y="{y-9}" width="{abs(x(value)-x(0)):.2f}" height="18" fill="#286aab"/>'
        s += f'<text x="850" y="{y+5}">{value:.3g}</text>'
    s += f'<text x="330" y="{height-3}">{lo:.3g}</text><text x="780" y="{height-3}">{hi:.3g}</text></svg>'
    return s

def commit_sections(repo, link_base):
    sections = []
    for i, sha in enumerate(SHAS):
        require(git(repo, 'rev-parse', sha).strip() == sha, 'Commit unavailable')
        if i:
            require(git(repo, 'rev-parse', sha+'^').strip() == SHAS[i-1], 'Unexpected parent')
        s = f'<section id="stage{i}"><h2>{i}. {TITLES[i]}</h2><p>{E(SCOPES[i])}</p>'
        s += f'<p><a href="{E(link_base+"/commit/"+sha)}">{sha[:10]}</a> · <code>{sha}</code></p>'
        if i:
            diff = git(repo, 'diff', '--no-ext-diff', '--no-color', '--unified=4', sha+'^', sha, '--', PROD)
            # One complete relevant hunk per file, never rewritten as pseudo-source.
            selected = []
            for file_diff in diff.split('diff --git ')[1:]:
                lines = file_diff.splitlines()
                hunks = [n for n, line in enumerate(lines) if line.startswith('@@')]
                if hunks:
                    # Favor behavioral callsite/configuration changes, not comments/constants.
                    target = next((n for n in hunks if any(token in '\n'.join(lines[n:(hunks[hunks.index(n)+1] if hunks.index(n)+1 < len(hunks) else len(lines))]) for token in ['hpackEncoder =', '!canIndex', 'HpackEncoder encoder ='])), hunks[0])
                    end = next((n for n in hunks if n > target), len(lines))
                    selected.append('diff --git '+'\n'.join(lines[:hunks[0]]+lines[target:end]))
            s += '<h3>Selected production hunks</h3><p class="annotation">Annotation: '+E(SCOPES[i])+'</p><pre><code>'+E('\n\n'.join(selected))+'</code></pre>'
            s += '<details><summary>Full production diff — every hunk, against parent</summary><pre><code>'+E(diff)+'</code></pre></details>'
            s += '<details><summary>Commit scope, including tests and fixtures</summary><pre>'+E(git(repo, 'show', '--format=fuller', '--stat', sha))+'</pre></details>'
            s += '<details><summary>Production source snapshots (not diff annotations)</summary>'
            for filename in ['HpackEncoder.java', 'DefaultHttp2HeadersEncoder.java']:
                s += '<details><summary>'+filename+'</summary><pre><code>'+E(git(repo, 'show', sha+':'+PROD+filename))+'</code></pre></details>'
            s += '</details>'
        sections.append(s+'</section>')
    return ''.join(sections)

def results(m, raw, comparisons, wire, preview):
    browser = [c['id'] for c in m['browser_cases']]
    synth = ['synthetic-'+s for s in ['REPEATED','WORKING_SET','MIXED','CHURN','NAME_HIT','COLLISION','STATIC','SENSITIVE','HUFFMAN']]
    capacity = browser + ['synthetic-'+s for s in ['WORKING_SET128','WORKING_SET384','WORKING_SET','WORKING_SET768','CHURN','COLLISION']]
    out = '<section id="results"><h2>Measurements: three different units of work</h2><p>No pooled averages. Every cell has its own linear scale starting at zero for means; difference plots include zero and may be negative. Scales differ between cells. Time and allocation are separate charts. No log scale hides signs.</p><p>Bars are arithmetic means of fork means, with no marginal confidence interval. Whiskers are 95% Student-t intervals on within-round candidate minus reference differences, not on either stage mean. Negative means less time/allocation. An interval crossing zero does not establish parity. These are unadjusted, per-comparison intervals on a shared host, not equivalence tests or isolated-host guarantees.</p>'
    groups = [('primary', browser, 'Browser', 'request'), ('primary', synth, 'Synthetic state', 'four-field block'), ('primary', ['init'], 'Initialization', 'encoder'), ('capacity', capacity, 'Stage 3 capacity only: 64 KiB versus 4 KiB', None)]
    for group, cells, title, unit in groups:
        out += '<h3>'+E(title)+'</h3>'
        for cell in cells:
            work = unit or ('request' if cell in browser else 'four-field block')
            out += f'<details class="cell"><summary>{E(cell)} · ns/{E(work)} and B/{E(work)}</summary>'
            if preview:
                out += '<p class="warning">UNMEASURED — no sample values, bars, intervals or performance conclusions.</p>'
                versions = VERSIONS if group == 'primary' else ['stage3/4096', 'stage3/65536']
                out += table(['Stage', 'ns/'+work, 'B/'+work, 'Wire corpus bytes'], [[v, 'unmeasured', 'unmeasured', 'N/A: no wire' if cell == 'init' else 'awaiting proof'] for v in versions])
                out += table(['Comparison', 'Time Δ + paired CI', 'Allocation Δ + paired CI'], [[c, 'unmeasured', 'unmeasured'] for c in (['stage1 − baseline (also parent)', 'stage2 − baseline', 'stage2 − stage1', 'stage3 − baseline', 'stage3 − stage2'] if group == 'primary' else ['stage3/65536 − stage3/4096'])])
            else:
                subset = [r for r in raw if r['group'] == group and r['cell'] == cell]
                keys = [(v, '4096') for v in VERSIONS] if group == 'primary' else [('stage3', '4096'), ('stage3', '65536')]
                means, rows = [], []
                for v, size in keys:
                    rr = [r for r in subset if r['version'] == v and r['size'] == size]
                    ns, alloc = [statistics.mean(float(r[k]) for r in rr) for k in ['ns/op','B/op']]
                    ww = [r for r in wire if r['version'] == v and r['cell'] == cell and str(r['size']) == size]
                    require(cell == 'init' or len(ww) == 1, 'Missing/duplicate wire corpus for '+cell)
                    rows.append([v, size, len(rr), ns, alloc, ww[0]['wire_bytes'] if ww else 'N/A (initialization)'])
                    means.append(ns)
                out += chart('Stage means · ns/'+work+' · no CI', [v+'/'+sz for v, sz in keys], means)
                out += chart('Stage means · B/'+work+' · no CI', [v+'/'+sz for v, sz in keys], [r[4] for r in rows])
                out += table(['Stage', 'Table bytes', 'Forks', 'ns/'+work, 'B/'+work, 'Wire corpus bytes (not B/op)'], rows)
                cc = [r for r in comparisons if r['group'] == group and r['cell'] == cell]
                for metric, label in [('ns', 'ns/'+work), ('bytes', 'B/'+work)]:
                    cr = [r for r in cc if r['metric'] == metric]
                    out += chart('Paired difference · '+label+' · 95% CI', [r['comparison'].replace('/4096', '').replace('baseline', 'base') for r in cr], [float(r['paired_delta']) for r in cr], [(float(r['ci95_low']),float(r['ci95_high'])) for r in cr])
                out += table(list(cc[0]), [list(r.values()) for r in cc])
                out += '<details><summary>Raw fork means (all rounds)</summary>'+table(list(subset[0]), [list(r.values()) for r in subset])+'</details>'
            out += '</details>'
    out += '<p>Stage 1 versus baseline is also stage 1 versus its parent; it is not counted twice. There is no baseline/stage 1/stage 2 64 KiB comparison: those encoders cannot support that configuration.</p></section>'
    return out

def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', type=Path, required=True)
    p.add_argument('--evidence', type=Path, required=True)
    p.add_argument('--extracted', action='store_true', help='Verify archived text/raw evidence, NOT omitted frozen binaries')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--preview', action='store_true')
    p.add_argument('--link-base', default='https://github.com/petedmarsh/netty')
    args = p.parse_args(argv)
    args.repo, args.evidence, args.output = [x.resolve() for x in [args.repo, args.evidence, args.output]]
    require(urlparse(args.link_base).scheme == 'https' and urlparse(args.link_base).netloc, 'HTTPS repository link base required')
    if args.preview:
        require(not args.output.is_relative_to(args.repo), 'Preview must remain outside the checkout')
        require(args.output.name != 'hpack-64k-series.html', 'Preview must not use the final filename')
    require(args.output.suffix == '.html', 'Output must be HTML')
    m = load(args.evidence/'input.json')
    raw, comparisons, wire = ([], [], []) if args.preview else validate(args.evidence, extracted=args.extracted)
    body = '<header><p class="eyebrow">HPACK · a three-commit walkthrough</p><h1>Make room without making lookup unbounded</h1>'
    body += '<p class="warning">UNMEASURED LAYOUT PREVIEW — NOT FINAL EVIDENCE</p>' if args.preview else '<p>Measured report — raw evidence validated offline. See verification scope below.</p>'
    body += '<p>HPACK saves wire bytes by remembering headers. This series first makes replacement possible, then protects admission, then allows a larger table. It does not redesign the decoder or promise a 4 KiB speedup.</p></header>'
    body += '<nav aria-label="Contents"><strong>Contents</strong> · '+ ' · '.join(f'<a href="#{k}">{v}</a>' for k,v in [('overview','Start here'),('stage1','1 API'),('stage2','2 Admission'),('stage3','3 Growth'),('mechanics','How it works'),('results','Measurements'),('caveats','Caveats'),('provenance','Evidence')])+'</nav>'
    body += '<section id="overview"><h2>The dependency chain</h2>'+diagram('Cumulative commit dependency chain', [f'{s[:10]} · {TITLES[i]}' for i,s in enumerate(SHAS)])
    body += '<h3>Encoder limits are not decoder limits</h3>'+table(['Component / configuration', 'Baseline + stages 1–2', 'Stage 3'], [['Encoder initial default', '4096 bytes / 64 buckets', '4096 bytes / 64 buckets (unchanged)'], ['Encoder after request for 65536, default construction', 'clamped to 4096', '65536 / 1024 buckets'], ['Encoder after request for 65536, custom 128 buckets', 'clamped to 8192', '65536 / 1024 buckets'], ['Decoder', 'default 4096; configurable separately', 'unchanged; no new encoder-local cap imposed']])
    body += diagram('Separate encoder and decoder configuration', ['Peer receive setting → local encoder chooses its supported table size', 'Stages 0–2: default ceiling 4 KiB; custom ceiling 8 KiB', 'Stage 3: default starts at 4 KiB; settings can grow it to 64 KiB', 'Local receive setting → decoder allowance (separate, unchanged code)'])+'</section>'
    body += commit_sections(args.repo, args.link_base.rstrip('/'))
    body += (HERE/'narrative.html').read_text()
    body += results(m, raw, comparisons, wire, args.preview)
    body += '<section id="provenance"><h2>Evidence and reproducibility</h2><p>Commit links target the fork but these three commits are local and unpushed; links may be unavailable until pushed. They are optional online navigation; no network access is needed to read this file. Production hunks and source snapshots above are escaped verbatim Git output. Explanations are separate from source. Baseline fixture overlay and test scope are recorded in HANDOFF.</p>'
    body += '<details><summary>Original HANDOFF (audit context, not timing results)</summary><pre>'+E((args.evidence/'HANDOFF.md').read_text())+'</pre></details>'
    body += '<p>Browser JMH operations are normalized per request by OperationsPerInvocation; state operations encode one four-field block; initialization constructs/configures one encoder. Wire bytes are exact corpus totals, not per-operation allocation.</p>'
    body += '<details><summary>Cell definitions and units audit</summary><pre>'+E(json.dumps({k:m[k] for k in ['browser_cases','synthetic_benchmark','init_benchmark','init_rounds']}, indent=2))+'</pre></details>'
    if not args.preview:
        names = ['freeze.json','schedule.json','readiness.json','ledger.jsonl','raw-forks.csv','comparisons.csv','wire.csv','campaign.py','HANDOFF.md']
        body += table(['Evidence file', 'SHA-256'], [[n,digest(args.evidence/n)] for n in names])
        body += '<details><summary>Exact wire corpus sizes and hashes</summary>'+table(list(wire[0]), [list(r.values()) for r in wire])+'</details>'
    if not args.preview:
        body += '<p>Campaign complete: 516 forks, 2,580 measured iterations. All 90 primary 4 KiB timing pointwise CIs cross zero: this is NOT proof of parity. Stage 3 capacity-only 64 KiB versus its own 4 KiB cohort has 10/14 timing improvements with CIs below zero and three new-connection allocation regressions with CIs above zero. Shared-host, unadjusted intervals; no universal speedup or equivalence claim.</p>'
        body += '<p>Verification scope: '+('extraction hashes, frozen included text, ledger, raw results and recomputed CSVs verified; omitted binaries NOT verified. Full frozen-artifact verification needs original jars, classes, JDK and paths.' if args.extracted else 'full frozen-artifact inventory plus ledger, raw results and recomputed CSVs verified on the original host. Published text-only archives cannot repeat binary verification without the original artifacts.')+'</p>'
    body += '</section>'
    css = (HERE/'style.css').read_text()
    document = '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; style-src \'unsafe-inline\'; img-src data:; base-uri \'none\'"><title>HPACK three-commit walkthrough'+(' — UNMEASURED PREVIEW' if args.preview else '')+'</title><style>'+css+'</style></head><body><main>'+body+'</main></body></html>'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_suffix('.html.tmp')
    temp.write_text(document)
    temp.replace(args.output)
    print(str(args.output))

if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as exc:
        sys.exit('Refused to render: '+str(exc))
