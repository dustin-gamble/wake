#!/usr/bin/env python3
"""Refuse to let a credential into this repository.

Scans what git is about to commit - the staged blobs, not the working tree, because those are
what actually get pushed. Run by tools/git-hooks/pre-commit, and worth running by hand before
any push:

    python3 tools/scan-secrets.py

A note on why this exists rather than a `grep -r`: recursive grep in some environments quietly
skips files and reports a clean tree that is not clean. Reading the blobs out of the index is
the only thing that answers the question actually being asked.
"""
import re
import subprocess
import sys

PATTERNS = {
    'Cesium Ion / JWT token': re.compile(rb'eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}'),
    'private key':            re.compile(rb'-----BEGIN [A-Z ]*PRIVATE KEY'),
    'AWS access key':         re.compile(rb'AKIA[0-9A-Z]{16}'),
    'GitHub token':           re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'),
    'Slack token':            re.compile(rb'xox[baprs]-[A-Za-z0-9-]{10,}'),
    'private LAN address':    re.compile(rb'192\.168\.\d{1,3}\.\d{1,3}'),
    'home directory':         re.compile(rb'/Users/[a-z]'),
    'inline secret':          re.compile(rb'(?i)(api[_-]?key|secret|password|token)\s*[:=]\s*'
                                         rb'["\'][^"\']{16,}["\']'),
}

# Files that legitimately contain a pattern. Keep this list short and justified.
ALLOWED = set()   # nothing needs an exemption; keep it that way


def staged_files():
    out = subprocess.run(['git', 'diff', '--cached', '--name-only', '--diff-filter=ACMR'],
                         capture_output=True, text=True).stdout
    return [f for f in out.split('\n') if f]


def main():
    hits = []
    files = staged_files()
    for name in files:
        if name in ALLOWED:
            continue
        blob = subprocess.run(['git', 'show', ':' + name], capture_output=True).stdout
        for label, pattern in PATTERNS.items():
            found = pattern.search(blob)
            if found:
                sample = found.group()[:32].decode('utf-8', 'replace')
                hits.append((label, name, sample))

    if hits:
        print('\nBLOCKED: this commit contains things that must not be published.\n')
        for label, name, sample in hits:
            print('  %-24s %s' % (label, name))
            print('  %-24s   matched: %s...' % ('', sample))
        print('\nFix the file, or add it to ALLOWED in tools/scan-secrets.py if it is a false')
        print('positive. Do not bypass with --no-verify.\n')
        return 1

    print('secret scan: %d staged file(s), clean' % len(files))
    return 0


if __name__ == '__main__':
    sys.exit(main())
