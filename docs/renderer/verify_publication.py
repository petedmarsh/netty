#!/usr/bin/env python3
"""Static publication audit; no browser/Java/network execution."""
import argparse
import hashlib
from html.parser import HTMLParser
import re
import tarfile
import xml.etree.ElementTree as ET
import generate as g


class Audit(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids, self.anchors = set(), set()
        self.cells = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        assert tag not in {'script', 'iframe', 'link', 'img', 'object', 'embed'}
        assert not any(k.startswith('on') for k in attrs)
        if 'id' in attrs:
            assert attrs['id'] not in self.ids
            self.ids.add(attrs['id'])
        if tag == 'a' and attrs.get('href', '').startswith('#'):
            self.anchors.add(attrs['href'][1:])
        self.cells += tag == 'details' and attrs.get('class') == 'cell'


def scan(texts):
    # Host-looking Java packages/methods and known synthetic test fixtures are
    # reviewed exceptions, not a blanket suffix exclusion.
    allowed = {'Dmaven.repo.local', 'client.local', 'clientConnection.local',
               'connection.local', 'i.n.util.internal', 'internal-admin.local',
               'io.netty.channel.internal', 'io.netty.channel.local',
               'io.netty.internal', 'io.netty.util.internal', 'jdk.internal',
               'org.mockito.internal', 'server.local', 'serverConnection.local',
               'users.noreply.ghe.spotify.net'}
    host = re.compile(r'\b(?:[a-zA-Z0-9_-]+\.)+(?:spotify\.net|spotify\.com|internal|corp|local)\b')
    credential_pattern = re.compile(r'-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----|AKIA[0-9A-Z]{16}|gh[pousr]_[a-zA-Z0-9]{30,}|xox[baprs]-[a-zA-Z0-9-]{10,}|(?:password|passwd|secret|access_token)\s*[:=]\s*[\"\x27]?[^\s\"\x27<>,;]{6,}', re.I)
    bearer = re.compile(r'\bBearer\s+([a-zA-Z0-9._~+/=-]+)', re.I)
    count = 0
    for name, text in texts:
        assert not credential_pattern.search(text), 'Credential-pattern review required: ' + name
        assert set(host.findall(text)) <= allowed, 'Hostname review required: ' + name
        for token in bearer.findall(text):
            assert token.startswith('SYNTHETIC_NOT_A_CREDENTIAL_') or token == '=', 'Token review required: ' + name
        count += 1
    print('Publication pattern scan passed:', count, 'decompressed/document files; known synthetic fixtures retained')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--repo', required=True, type=g.Path)
    p.add_argument('--evidence', required=True, type=g.Path)
    a = p.parse_args()
    repo, evidence = a.repo.resolve(), a.evidence.resolve()
    doc = (repo/'docs/hpack-64k-series.html').read_text()
    audit = Audit()
    audit.feed(doc)
    assert audit.anchors <= audit.ids and audit.cells == 32
    svgs = re.findall(r'<svg\b.*?</svg>', doc, re.S)
    for svg in svgs:
        ET.fromstring(svg)
    raw, comparisons, wire = g.validate(evidence)
    expected = g.results(g.load(evidence/'input.json'), raw, comparisons, wire, False)
    assert expected in doc, 'Charts/tables differ from validated evidence'
    assert len(re.findall(r'<svg\b', expected)) == 128
    for sha in g.SHAS[1:]:
        for filename in ['HpackEncoder.java', 'DefaultHttp2HeadersEncoder.java']:
            assert g.E(g.git(repo, 'show', sha+':'+g.PROD+filename)) in doc
        assert g.E(g.git(repo, 'diff', '--no-ext-diff', '--no-color', '--unified=4', sha+'^', sha, '--', g.PROD)) in doc
    assert not g.git(repo, 'diff', '95c9eb3d06fa02f34c17cc356964f7b2cda53476', g.SHAS[-1], '--', '*/src/main/**')
    archive = repo/'docs/evidence/hpack-series-text.tar.gz'
    with tarfile.open(archive) as tar:
        members = tar.getmembers()
        assert len(members) == 3374
        assert all(m.isfile() and not g.Path(m.name).is_absolute() and '..' not in g.Path(m.name).parts for m in members)
        assert not any(g.Path(m.name).suffix in {'.jar', '.class', '.pyc'} or 'm2' in g.Path(m.name).parts for m in members)
        scan((m.name, tar.extractfile(m).read().decode()) for m in members)
    scan((str(f), f.read_text()) for f in (repo/'docs').rglob('*') if f.is_file() and f.suffix != '.gz')
    print('Static audit passed: 32 cells, 128 data SVGs,', len(svgs), 'total SVGs; exact source/diffs/CSV charts and target production')
    print('Archive SHA-256:', hashlib.sha256(archive.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()
