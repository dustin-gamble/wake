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
import pathlib
import re
import subprocess
import sys

PATTERNS = {
    'Cesium Ion / JWT token': re.compile(rb'eyJ[A-Za-z0-9_-]{6,}\.[A-Za-z0-9_-]{6,}'),
    'private key':            re.compile(rb'-----BEGIN [A-Z ]*PRIVATE KEY'),
    'AWS access key':         re.compile(rb'AKIA[0-9A-Z]{16}'),
    'GitHub token':           re.compile(rb'gh[pousr]_[A-Za-z0-9]{30,}'),
    'Slack token':            re.compile(rb'xox[baprs]-[A-Za-z0-9-]{10,}'),
    'home directory':         re.compile(rb'/Users/[a-z]'),
    # A credential-shaped VALUE, not a constant name: long, and mixing cases and digits the way
    # a generated key does. `PREF_TOKEN = "cesium-ion-token"` is a key name and must not trip it.
    'inline secret':          re.compile(rb'(?i)(api[_-]?key|secret|password|token)\s*[:=]\s*'
                                         rb'["\'](?=[^"\']*[A-Z])(?=[^"\']*[0-9])'
                                         rb'[A-Za-z0-9+/=_-]{24,}["\']'),
}

def real_lan_address():
    """This machine's actual dashboard address, if it is configured.

    Only the real one is blocked. A documentation placeholder like the UI hint
    "for example http://192.168.1.25:8787" is not a leak, and blocking every RFC1918
    address would train everyone to bypass the hook - which is worse than the problem.
    """
    try:
        url = pathlib.Path('.laptop-url').read_text().strip()
    except OSError:
        return None
    found = re.search(r'(\d{1,3}(?:\.\d{1,3}){3})', url)
    return found.group(1).encode() if found else None

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
        lan = real_lan_address()
        if lan and lan in blob:
            hits.append(('this machine\'s LAN address', name, lan.decode()))
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
