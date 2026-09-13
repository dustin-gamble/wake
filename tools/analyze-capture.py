#!/usr/bin/env python3
"""
Analyse an events.jsonl capture from the Ergatta diagnostic.

    python3 tools/analyze-capture.py [path] [--version 0.7]

Reports link health, per-address decode state, the stroke-rate x2 question, and the
water-speed calibration fit. Written to be re-run as new captures land.
"""
import json
import re
import sys
from collections import Counter, defaultdict
from datetime import datetime

DEFAULT = "server/data/events.jsonl"
FIELD_RE = re.compile(r"^(IR[SDT])(\w{3}) (\S+) = (.*)$")
VALUE_RE = re.compile(r"^(-?\d+) \(0x[0-9A-F]+, (\d+)s ago\)$")


def load(path, version):
    out = []
    with open(path) as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if version and not row.get("body", {}).get("appVersion", "").startswith(version):
                continue
            out.append(row)
    return out


def when(row):
    return datetime.fromisoformat(row["receivedAt"].replace("Z", "+00:00"))


def fit(xs, ys):
    """Least squares slope, intercept, r-squared."""
    n = len(xs)
    if n < 4:
        return None
    sx, sy = sum(xs), sum(ys)
    sxx = sum(x * x for x in xs)
    sxy = sum(x * y for x, y in zip(xs, ys))
    denom = n * sxx - sx * sx
    if not denom:
        return None
    slope = (n * sxy - sx * sy) / denom
    intercept = (sy - slope * sx) / n
    mean = sy / n
    tot = sum((y - mean) ** 2 for y in ys)
    res = sum((y - (slope * x + intercept)) ** 2 for x, y in zip(xs, ys))
    return slope, intercept, (1 - res / tot) if tot else 0.0


def main():
    argv = sys.argv[1:]
    path, version = DEFAULT, ""
    i = 0
    while i < len(argv):
        if argv[i] == "--version" and i + 1 < len(argv):
            version = argv[i + 1]
            i += 2          # skip the value so it is not mistaken for the path
        else:
            path = argv[i]
            i += 1

    rows = load(path, version)
    if not rows:
        print(f"No events in {path}" + (f" for version {version}" if version else ""))
        return 1

    print(f"{len(rows)} events from {path}")
    print("versions:", dict(Counter(r["body"]["appVersion"] for r in rows)))
    print("types:", dict(Counter(r["type"] for r in rows).most_common(10)))

    # --- link health -------------------------------------------------------
    opens = [r for r in rows if r["type"] == "serial-opened"]
    fails = [r for r in rows if r["type"] in ("s4-write-failed", "s4-start-failed")]
    print(f"\nLINK  opens={len(opens)}  write-failures={len(fails)} "
          f" reopens={sum(1 for r in rows if r['type'] == 's4-port-reopen')}")
    for op in opens:
        later = [f for f in fails if when(f) > when(op)]
        if later:
            print(f"  open {op['receivedAt'][11:19]} -> first failure after "
                  f"{(when(later[0]) - when(op)).total_seconds():.1f}s")

    # --- per address decode ------------------------------------------------
    status = [r for r in rows if r["type"] == "rowing-status"]
    if not status:
        print("\nNo rowing-status events.")
        return 0

    seen = defaultdict(list)
    for row in status:
        for line in row["body"]["payload"].get("fields", []):
            m = FIELD_RE.match(line)
            if not m:
                continue
            size, addr, label, rest = m.groups()
            v = VALUE_RE.match(rest)
            seen[f"{size}{addr} {label}"].append(
                int(v.group(1)) if v else (None if "retired" in rest else None))

    print("\nADDRESS DECODE")
    for key, vals in seen.items():
        good = [v for v in vals if v is not None]
        if good:
            print(f"  {key:<28} n={len(good):<5} min={min(good):<6} max={max(good):<6}"
                  f" distinct={len(set(good))}")
        else:
            print(f"  {key:<28} never answered")

    # --- stroke rate x2 ----------------------------------------------------
    pairs = [(p.get("strokeRateRaw"), p.get("strokeRate"))
             for p in (r["body"]["payload"] for r in status)
             if p.get("strokeRateRaw")]
    if pairs:
        print(f"\nSTROKE RATE  raw range {min(p[0] for p in pairs)}-{max(p[0] for p in pairs)}"
              f"  reported {min(p[1] for p in pairs)}-{max(p[1] for p in pairs)}")
        print("  Compare against the monitor's own display to settle the x2.")

    # --- water speed calibration ------------------------------------------
    cal = []
    for row in status:
        p = row["body"]["payload"]
        hz, pv = p.get("pulseHz"), p.get("lastPulseValue")
        speed = p.get("waterSpeedMps")
        if speed and speed > 0.2 and hz:
            cal.append((hz, pv or 0, speed))
    print(f"\nWATER SPEED CALIBRATION  {len(cal)} usable samples")
    if len(cal) >= 8:
        speeds = [c[2] for c in cal]
        print(f"  speed range {min(speeds):.2f}-{max(speeds):.2f} m/s")
        for label, xs in (("pulseHz", [c[0] for c in cal]),
                          ("Pxx", [float(c[1]) for c in cal]),
                          ("1/Pxx", [1 / c[1] if c[1] else 0 for c in cal])):
            got = fit(xs, speeds)
            if got:
                slope, intercept, r2 = got
                verdict = "usable" if r2 > 0.6 else "too weak to use"
                print(f"  speed ~ {label:<8} slope={slope:9.4f} R^2={r2:.3f}  {verdict}")
        if max(speeds) - min(speeds) < 1.5:
            print("  NOTE: narrow speed range. Row hard pieces with easy paddling between"
                  " to spread the data before trusting any fit.")
    else:
        print("  Not enough yet.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
