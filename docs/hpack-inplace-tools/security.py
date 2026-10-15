#!/usr/bin/env python3
"""Heuristic decompressed-archive review; prints candidates, never edits evidence.
Not a proof that arbitrary secrets are absent. Review matches in fixture context.
"""
import collections
import re
import sys
import tarfile

pattern = re.compile(r'authorization|bearer |password|secret|credential|token[=:]|PRIVATE KEY|AKIA|gh[pousr]_', re.I)
hits = collections.defaultdict(set)
with tarfile.open(sys.argv[1], 'r:gz') as tar:
    for member in tar:
        if not member.isfile():
            continue
        data = tar.extractfile(member).read()
        text = data.decode('utf8')
        for line in text.splitlines():
            if pattern.search(line):
                # Collapse redacted header indices, not potential credential values.
                line = re.sub(r'^header \d+/\d+ ', 'header N/N ', line)
                hits[line].add(member.name)
for line, names in sorted(hits.items()):
    print(line)
    print('  files: ' + ', '.join(sorted(names)))
print('Review complete:', len(hits), 'distinct candidate lines; no automatic absence guarantee')
