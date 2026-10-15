#!/usr/bin/env python3
"""Portable, standard-library-only evidence audit. No Java, git or network."""
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys
import tarfile


def verify(path):
    with tarfile.open(path, 'r:gz') as tar:
        files = {m.name: tar.extractfile(m).read() for m in tar if m.isfile()}
    manifest = json.loads(files['MANIFEST.json'])
    assert set(files) == set(manifest) | {'MANIFEST.json'}
    for name, digest in manifest.items():
        assert hashlib.sha256(files[name]).hexdigest() == digest, name
    def load(name):
        return json.loads(files['evidence/' + name])
    def canonical(obj):
        return json.dumps(obj, sort_keys=True, separators=(',', ':')).encode()
    audit = load('source-proof/audit.json')
    repo = Path(__file__).resolve().parents[2]
    for name, digest in audit['sources'].items():
        assert hashlib.sha256(files['sources/' + name]).hexdigest() == digest
        assert hashlib.sha256((repo / name).read_bytes()).hexdigest() == digest, name
    for log, expected in [('review-focused.log', 'Tests run: 148, Failures: 0, Errors: 0, Skipped: 0'),
                          ('review-full-paranoid.log', 'Tests run: 1664, Failures: 0, Errors: 0, Skipped: 5')]:
        assert expected in files['evidence/' + log].decode()
    assert len([n for n in files if n.startswith('evidence/raw/')]) == 768
    jobs = load('schedule.json')
    assert len(jobs) == len({j['id'] for j in jobs}) == 384
    assert hashlib.sha256(canonical(jobs)).hexdigest() == load('freeze.json')['schedule_sha256']
    events = [json.loads(s) for s in files['evidence/ledger.jsonl'].splitlines()]
    assert len(events) == 1536
    previous = hashlib.sha256(files['evidence/freeze.json']).hexdigest()
    for e in events:
        assert e['previous'] == previous
        assert hashlib.sha256(canonical({k: v for k, v in e.items() if k != 'hash'})).hexdigest() == e['hash']
        previous = e['hash']
    values = {}
    for i, j in enumerate(jobs):
        es = events[4*i:4*i+4]
        assert [e['event'] for e in es] == ['preflight', 'started', 'process-finished', 'parsed']
        assert all(e.get('id', e.get('job_id')) == j['id'] for e in es)
        assert es[0]['returncode'] == es[2]['returncode'] == 0
        assert es[0]['competitors'] == [] and es[1]['argv'] == j['argv']
        for original, digest in es[2]['outputs'].items():
            assert hashlib.sha256(files['evidence/raw/' + Path(original).name]).hexdigest() == digest
        d, = load('raw/' + j['id'] + '.json')
        assert d['benchmark'] == j['benchmark'] and d['params'] == j['params']
        alloc, = [v for k, v in d['secondaryMetrics'].items() if k.replace('·', '').split(':')[-1].strip() == 'gc.alloc.rate.norm']
        for metric, unit, data in [('ns', 'ns/op', d['primaryMetric']), ('bytes', 'B/op', alloc)]:
            assert data['scoreUnit'] == unit and len(data['rawData']) == 1 and len(data['rawData'][0]) == 5
            values[j['group'], j['cell'], j['version'], j['round'], metric] = statistics.mean(data['rawData'][0])
    rows = list(csv.DictReader(files['evidence/analysis-comparisons.csv'].decode().splitlines()))
    assert len(rows) == 126
    for row in rows:
        a, b = [s.split('/')[0] for s in row['comparison'].split(' - ')]
        rounds = sorted({j['round'] for j in jobs if (j['group'], j['cell']) == (row['group'], row['cell'])})
        av, bv = [[values[row['group'], row['cell'], v, r, row['metric']] for r in rounds] for v in (a, b)]
        ds = [x-y for x, y in zip(av, bv)]
        n = len(ds)
        delta = statistics.mean(ds)
        half = {6: 2.57058184, 3: 4.30265273}[n] * statistics.stdev(ds) / math.sqrt(n)
        expected = [n, statistics.mean(av), statistics.mean(bv), delta, delta-half, delta+half, 100*delta/statistics.mean(bv)]
        for key, value in zip(['pairs', 'candidate_mean', 'reference_mean', 'paired_delta', 'ci95_low', 'ci95_high', 'percent_delta'], expected):
            assert math.isclose(float(row[key]), value, rel_tol=1e-10, abs_tol=1e-8), (row, key)
    assert all(float(r['ci95_low']) <= 0 <= float(r['ci95_high']) for r in rows if r['group'] == 'primary' and r['metric'] == 'ns')
    print('PASS: manifest, 12 frozen sources, preserved 148/1664 test summaries (5 full skips), 384 forks, 1920 iterations, 1536 chained events, 768 raw hashes, 126 paired comparisons; all 54 4K timing CIs cross zero')
    return files


if __name__ == '__main__':
    verify(sys.argv[1])
