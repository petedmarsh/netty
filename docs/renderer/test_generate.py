"""Offline renderer checks. Fabricated values stay in test memory/temp files only."""
import csv
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import statistics
import tempfile
import unittest
from unittest.mock import patch
import generate as g


class DocumentAudit(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.anchors = []
        self.cells = 0
        self.svg = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if 'id' in attrs:
            assert attrs['id'] not in self.ids, 'duplicate ID'
            self.ids.add(attrs['id'])
        assert tag not in ['script', 'iframe', 'link', 'img', 'object'], 'external/active dependency'
        assert not any(k.startswith('on') for k in attrs), 'event handler'
        if tag == 'a' and attrs.get('href', '').startswith('#'):
            self.anchors.append(attrs['href'][1:])
        if tag == 'details' and attrs.get('class') == 'cell':
            self.cells += 1
        self.svg += tag == 'svg'


EVIDENCE = Path(os.environ['HPACK_EVIDENCE']).resolve()
REPO = g.HERE.parent.parent


def render(args):
    return g.main(['--repo', str(REPO), '--evidence', str(EVIDENCE)] + args)


class RendererTests(unittest.TestCase):
    def setUp(self):
        self.c = g.load_campaign(EVIDENCE)
        self.m = self.c.config()
        self.jobs = self.c.schedule(self.m)
        self.values = {j['id']: {'ns': 1000*j['round'] + 10*g.VERSIONS.index(j['version']) +
                                (40 if j['size'] == 65536 else 0), 'bytes': 4.0}
                       for j in self.jobs}

    def test_paired_not_marginal_uncertainty(self):
        rows = g.comparison_rows(self.jobs, self.values)
        self.assertEqual(len(rows), 208)
        for r in rows:
            # Huge shared round variation cancels; paired difference is constant.
            self.assertEqual(r['paired_delta'], r['ci95_low'])
            self.assertEqual(r['paired_delta'], r['ci95_high'])
            self.assertIn(r['pairs'], (3, 6))
            if r['group'] == 'capacity':
                self.assertEqual(r['comparison'], 'stage3/65536 - stage3/4096')
        samples = [1000*r for r in range(1, 7)]
        self.assertGreater(statistics.stdev(samples), 1000)

    def test_csv_refuses_missing_duplicate_nan_tamper(self):
        row = dict(cell='a', value=1.25)
        g.same_rows([{'cell':'a','value':'1.25'}], [row], ['value'])
        for rows in [[], [row, row], [dict(cell='a',value='nan')],
                     [dict(cell='b',value=1.25)], [dict(cell='a',value=1.3)]]:
            with self.assertRaises(ValueError):
                g.same_rows(rows, [row], ['value'])

    def test_escaping_and_chart_semantics(self):
        source = 'context \n\t \n<source>&\n'
        self.assertEqual(g.html.unescape(g.E(source)), source)
        self.assertNotRegex(g.E(source), r'[ \t]+\n')
        self.assertNotIn('<script>', g.table(['<script>'], [['<img src=x>']]))
        mean = g.chart('mean', ['one'], [10])
        delta = g.chart('difference', ['one'], [-1], [(-4, 2)])
        self.assertNotIn('<circle', mean)
        self.assertIn('<circle', delta)
        self.assertIn('-4', delta)

    def test_missing_evidence_refuses_and_preserves_output(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root/'input.json').write_text(json.dumps(self.m))
            output = root/'final.html'
            output.write_text('existing')
            with self.assertRaisesRegex(ValueError, 'freeze.json'):
                render(['--evidence', str(root), '--output', str(output)])
            self.assertEqual(output.read_text(), 'existing')

    def test_preview_cannot_be_final(self):
        with self.assertRaisesRegex(ValueError, 'final filename'):
            render(['--preview', '--output', '/tmp/hpack-64k-series.html'])
        with self.assertRaisesRegex(ValueError, 'outside the checkout'):
            render(['--preview', '--output', str(REPO/'docs/preview.html')])

    def test_complete_csv_gate_with_stubbed_proof_boundary(self):
        # Real report schema + parser/recompute logic; proof internals are mocked here.
        # No fake evidence is written to the real evidence directory.
        c = self.c
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root/'raw').mkdir()
            events = []
            for j in self.jobs:
                outputs = {}
                for suffix in ['.json', '.log']:
                    p = root/'raw'/(j['id']+suffix)
                    p.write_text('test-only')
                    outputs[str(p)] = g.digest(p)
                events.extend([dict(id=j['id'], event='process-finished', returncode=0, outputs=outputs),
                               dict(id=j['id'], event='parsed', values=self.values[j['id']])])
            wire_rows = [dict(version=v, cell=cell, size=size, wire_bytes=10)
                         for v, cell, size in sorted({(j['version'], j['cell'], j['size'])
                         for j in self.jobs if j['cell'] != 'init'})]
            for name, data in [('freeze.json', {'files':{}}), ('schedule.json', self.jobs),
                               ('readiness.json', {'wire_rows':wire_rows})]:
                (root/name).write_text(json.dumps(data))
            (root/'ledger.jsonl').write_text('test-only')
            raw = [dict(**{k:j[k] for k in ['group','cell','round','version','size']},
                        **{'ns/op':self.values[j['id']]['ns'], 'B/op':4.0}) for j in self.jobs]
            for name, rows in [('raw-forks.csv', raw), ('comparisons.csv', g.comparison_rows(self.jobs,self.values)),
                               ('wire.csv', wire_rows)]:
                with (root/name).open('w', newline='') as f:
                    w = csv.DictWriter(f, fieldnames=list(rows[0]))
                    w.writeheader()
                    w.writerows(rows)
            with patch.object(g, 'load_campaign', return_value=c), \
                    patch.object(c, 'verify_freeze', return_value=self.jobs), \
                    patch.object(c, 'inventory', return_value={}), \
                    patch.object(c, 'ledger', return_value=(events, 'hash')), \
                    patch.object(c, 'parse', side_effect=lambda j:self.values[j['id']]):
                actual, comparisons, wire = g.validate(root)
                self.assertEqual(len(actual), len(self.jobs))
                rendered = g.results(self.m, actual, comparisons, wire, False)
                self.assertEqual(rendered.count('class="cell"'), 32)
                self.assertEqual(rendered.count('<svg'), 128)
                # Altering the raw result invalidates its hash before CSV reading.
                (root/'raw'/(self.jobs[0]['id']+'.json')).write_text('changed')
                with self.assertRaisesRegex(ValueError, 'raw proof'):
                    g.validate(root)

    def test_real_integrity_and_tamper(self):
        extracted = (EVIDENCE/'EXTRACTION.json').exists()
        raw, comparisons, wire = g.validate(EVIDENCE, extracted=extracted)
        self.assertEqual((len(raw), len(comparisons), len(wire)), (516, 208, 85))
        original_digest = g.digest
        with patch.object(g, 'digest', side_effect=lambda p: 'changed' if Path(p).parent.name == 'raw' else original_digest(p)):
            with self.assertRaises(ValueError):
                g.validate(EVIDENCE, extracted=extracted)

    def test_real_preview_structure(self):
        with tempfile.TemporaryDirectory() as td:
            output = Path(td)/'preview.html'
            render(['--preview', '--output', str(output)])
            doc = output.read_text()
            audit = DocumentAudit()
            audit.feed(doc)
            self.assertTrue(set(audit.anchors) <= audit.ids)
            self.assertEqual(audit.cells, 32)
            self.assertGreaterEqual(audit.svg, 4)
            self.assertIn('UNMEASURED LAYOUT PREVIEW', doc)
            self.assertIn('&lt;=', doc)  # escaped Java source, not HTML elements
            for sha in g.SHAS:
                self.assertIn(sha, doc)


if __name__ == '__main__':
    unittest.main()
