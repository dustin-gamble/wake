#!/usr/bin/env python3
"""Put tablet screenshots on the GitHub page and README.

The tablet sends screenshots to server/data/screenshots/ (gitignored). This copies the ones you
choose into docs/screenshots/, scales them down for the web, and rebuilds the gallery between the
`screenshots:start` / `screenshots:end` markers in docs/index.html and README.md.

    tools/publish-screenshots.py server/data/screenshots/ZoneRow-....png::"Zone Row"
    tools/publish-screenshots.py --list            # what is published
    tools/publish-screenshots.py --remove zone-row # take one down

Publishing is deliberate, one image at a time: LOOK AT EACH IMAGE FIRST. The gauges screen and the
diagnostics drawer can show a network address, and anything on screen becomes public on push.
Coast Flight comes out blank from the in-app capture (WebGL); use a hardware screenshot for it.
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHOTS = ROOT / "docs" / "screenshots"
MANIFEST = SHOTS / "manifest.json"
PAGE = ROOT / "docs" / "index.html"
README = ROOT / "README.md"
START, END = "<!-- screenshots:start -->", "<!-- screenshots:end -->"
MAX_EDGE = 1600
PNG = b"\x89PNG\r\n\x1a\n"


def load():
    return json.loads(MANIFEST.read_text()) if MANIFEST.exists() else []


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-") or "screen"


def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def replace_block(path, block):
    text = path.read_text()
    if text.count(START) != 1 or text.count(END) != 1:
        sys.exit("%s needs exactly one %s ... %s pair" % (path.name, START, END))
    head, rest = text.split(START)
    _, tail = rest.split(END)
    path.write_text(head + START + block + END + tail)


def render(shots):
    if not shots:
        page = ""
        readme = ""
    else:
        figures = "\n".join(
            '          <figure><a href="screenshots/%s"><img src="screenshots/%s" alt="%s" loading="lazy"></a>'
            "<figcaption>%s</figcaption></figure>" % (s["file"], s["file"], esc(s["caption"]), esc(s["caption"]))
            for s in shots)
        page = ('\n      <div class="panel">\n        <h2>Screenshots</h2>\n'
                '        <div class="shots">\n%s\n        </div>\n      </div>\n      ' % figures)
        readme = "\n\n" + "\n".join(
            '<img src="docs/screenshots/%s" alt="%s" width="49%%">' % (s["file"], esc(s["caption"]))
            for s in shots) + "\n\n"
    replace_block(PAGE, page)
    replace_block(README, readme)


def main(argv):
    shots = load()
    if argv and argv[0] == "--list":
        for s in shots:
            print("%-28s %s" % (s["file"], s["caption"]))
        return
    if len(argv) == 2 and argv[0] == "--remove":
        name = argv[1] if argv[1].endswith(".png") else argv[1] + ".png"
        (SHOTS / name).unlink(missing_ok=True)
        shots = [s for s in shots if s["file"] != name]
    else:
        if not argv:
            sys.exit(__doc__)
        SHOTS.mkdir(parents=True, exist_ok=True)
        for arg in argv:
            src, _, caption = arg.partition("::")
            src = Path(src)
            data = src.read_bytes()
            if not data.startswith(PNG):
                sys.exit("%s is not a PNG" % src)
            caption = caption or src.stem.split("-")[0]
            name = slug(caption) + ".png"
            dest = SHOTS / name
            shutil.copyfile(src, dest)
            # macOS sips scales in place and keeps the aspect; elsewhere the full size is kept.
            if shutil.which("sips"):
                subprocess.run(["sips", "-Z", str(MAX_EDGE), str(dest)], check=True,
                               stdout=subprocess.DEVNULL)
            shots = [s for s in shots if s["file"] != name] + [{"file": name, "caption": caption}]
            print("published %s  (%d KB)" % (name, dest.stat().st_size // 1024))
    SHOTS.mkdir(parents=True, exist_ok=True)
    MANIFEST.write_text(json.dumps(shots, indent=2) + "\n")
    render(shots)


if __name__ == "__main__":
    main(sys.argv[1:])
