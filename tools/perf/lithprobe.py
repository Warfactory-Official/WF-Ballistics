#!/usr/bin/env python3
"""One arm of the Lithium A/B: 2000 glyphids marching, measured the same way every time.

Run once per server boot, because Lithium's options are read at startup and cannot be changed at runtime.
The arm label and the output file come from argv so the driver can accumulate arms across boots.
"""
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rcon import Rcon  # noqa: E402

MSPT = re.compile(r"Average time per tick:\s*([\d.]+)\s*ms", re.I)
PCTL = re.compile(r"P50:\s*([\d.]+)ms.*?P95:\s*([\d.]+)ms.*?P99:\s*([\d.]+)ms", re.I | re.S)
TIERS = re.compile(r"(\d+) glyphids: (\d+) entities, (\d+) sim")
# The profiler's tree, as "label  <ms> ms  <share>%" with the label's indent carrying the nesting.
LINE = re.compile(r"^\s{2,}(\S.*?)\s{2,}([\d.]+) ms")

PAD = (0, -60, 0)
CORRIDOR = (-100, -100, 900, 100)
TARGET = (800, -60, 0)
WARMUP = 45
MEASURE = 15

LABEL = sys.argv[1]
OUT = sys.argv[2]
FIRST = len(sys.argv) > 3 and sys.argv[3] == "first"


def connect():
    deadline = time.time() + 420
    while True:
        try:
            return Rcon()
        except (OSError, ConnectionError):
            if time.time() > deadline:
                raise SystemExit("rcon never came up")
            time.sleep(2)


def forceload(r, x0, z0, x1, z1):
    done, step = 0, 80
    x = x0
    while x <= x1:
        hi = min(x + step - 1, x1)
        m = re.search(r"Marked (\d+) chunks", r.cmd(f"forceload add {x} {z0} {hi} {z1}") or "")
        done += int(m.group(1)) if m else 0
        x = hi + 1
    ok = sum("marked for force loading" in (r.cmd(f"forceload query {cx} {cz}") or "").lower()
             for cx, cz in ((x0 + 16, z0 + 16), (x1 - 16, z1 - 16)))
    return done, ok


def main():
    r = connect()
    for _ in range(150):
        if (r.cmd("list") or "").strip():
            break
        time.sleep(2)

    px, py, pz = PAD
    tx, ty, tz = TARGET
    for c in ("gamerule randomTickSpeed 0", "gamerule doDaylightCycle false",
              "gamerule doMobSpawning false", "gamerule doFireTick false",
              "time set noon", "weather clear"):
        r.cmd(c)
    marked, corners = forceload(r, *CORRIDOR)
    print(f"[{LABEL}] corridor: {marked} newly marked, {corners}/2 corners held")
    # Only the first boot pays for generation; the world is kept between arms on purpose, so every arm
    # marches over identical, already-generated terrain rather than over its own fresh worldgen.
    time.sleep(75 if FIRST else 15)
    r.cmd("wfballistics swarmbench profile on")

    setup = ["wfballistics swarmbench sim off",
             f"wfballistics swarmbench watcher {px} {py} {pz}",
             f"execute positioned {px} {py} {pz} run wfballistics swarmbench spawn 2000 48",
             f"wfballistics swarmbench march {tx} {ty} {tz}",
             "wfballistics swarmbench reset"]
    replies = "\n".join((r.cmd(c) or "").strip() for c in setup)
    fired = "2000 glyphids marching" in replies and "Spawned 2000" in replies
    if not fired:
        print(f"[{LABEL}] !! ARM DID NOT FIRE\n{replies}")
    forceload(r, *CORRIDOR)          # spawn calls releaseArena(); take the corridor back

    time.sleep(WARMUP)
    r.cmd("wfballistics swarmbench reset")
    time.sleep(MEASURE)

    out = r.cmd("tick query") or ""
    pct = PCTL.search(out)
    mean = MSPT.search(out)
    report = r.cmd("wfballistics swarmbench report") or ""
    tiers = TIERS.search(r.cmd("wfballistics swarmbench tiers") or "")

    # Keep only the first occurrence of each label: the tree prints "(unattributed)" once per parent, and
    # the parent's own line always precedes it, so first-wins keeps them distinguishable by order.
    phases, order = {}, []
    for line in report.splitlines():
        m = LINE.match(line)
        if m:
            label = m.group(1).strip()
            key = label if label != "(unattributed)" else f"(unattributed) under {order[-1] if order else '?'}"
            if key not in phases:
                phases[key] = float(m.group(2))
                order.append(label if label != "(unattributed)" else key)

    arm = {
        "label": LABEL, "fired": fired,
        "p50": float(pct.group(1)) if pct else (float(mean.group(1)) if mean else None),
        "p95": float(pct.group(2)) if pct else None,
        "p99": float(pct.group(3)) if pct else None,
        "entities": int(tiers.group(2)) if tiers else -1,
        "records": int(tiers.group(3)) if tiers else -1,
        "phases": phases,
    }
    print(f"[{LABEL}] MSPT p50 {arm['p50']}, p95 {arm['p95']}, p99 {arm['p99']}  "
          f"({arm['entities']} entities)")
    for line in report.splitlines()[:24]:
        print("   ", line)

    existing = []
    if os.path.exists(OUT):
        with open(OUT) as fh:
            existing = json.load(fh)
    existing.append(arm)
    with open(OUT, "w") as fh:
        json.dump(existing, fh, indent=1)

    for c in ("wfballistics swarmbench clear", "wfballistics colony clear"):
        r.cmd(c)


if __name__ == "__main__":
    main()
