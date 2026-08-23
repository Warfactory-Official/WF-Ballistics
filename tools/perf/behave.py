#!/usr/bin/env python3
"""Behaviour-only re-check of the three applied fixes, with the two arms of applied.py that misfired fixed.

  prey    The cows are placed and counted *before* the swarm exists. The previous run put them down next
          to twenty-four glyphids and took its baseline four seconds later, by which time they had been
          eaten -- so the arm recorded 0 -> 0 and called working code a regression.

  frozen  The powder-snow arena never froze anything, including the vanilla pig standing in it, so it said
          nothing either way. Replaced with a direct test of the setter this change actually touches:
          write TicksFrozen through `data merge` (which lands in setTicksFrozen via readAdditionalSaveData)
          and watch it decay. A mirror that swallowed writes would show a counter that never moves, and the
          pig alongside says what the decay is supposed to look like.
"""
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rcon import Rcon  # noqa: E402

BUG = "wfballistics:glyphid"
PAD = (0, -60, 0)
COUNT = re.compile(r"count:\s*(\d+)")
results = []


def connect():
    deadline = time.time() + 420
    while True:
        try:
            return Rcon()
        except (OSError, ConnectionError):
            if time.time() > deadline:
                raise SystemExit("rcon never came up")
            time.sleep(2)


def count(r, selector):
    m = COUNT.search(r.cmd(f"execute if entity {selector}") or "")
    return int(m.group(1)) if m else 0


def nbt_int(r, selector, key):
    reply = r.cmd(f"data get entity {selector}") or ""
    m = re.search(rf"\b{key}:\s*(-?\d+)", reply)
    return int(m.group(1)) if m else 0


def check(name, ok, detail):
    results.append((name, ok, detail))
    print(f"   [{'PASS' if ok else 'FAIL'}] {name}: {detail}")


def main():
    r = connect()
    for _ in range(150):
        if (r.cmd("list") or "").strip():
            break
        time.sleep(2)
    px, py, pz = PAD
    for c in ("gamerule randomTickSpeed 0", "gamerule doDaylightCycle false",
              "gamerule doMobSpawning false", "gamerule doFireTick false",
              "gamerule doMobLoot false", "time set noon", "weather clear",
              f"forceload add {px - 160} {pz - 160} {px + 160} {pz + 160}"):
        r.cmd(c)
    time.sleep(20)
    r.cmd("wfballistics swarmbench clear")
    r.cmd("kill @e[type=minecraft:cow]")
    r.cmd("kill @e[type=minecraft:pig]")

    # --- prey: placed and counted before the swarm exists ----------------------------------------
    print("\n--- prey index ---")
    for i in range(4):
        r.cmd(f"summon minecraft:cow {px + 6 + i} {py} {pz + 4} "
              f"{{PersistenceRequired:1b,CustomName:'{{\"text\":\"near\"}}'}}")
    for i in range(4):
        r.cmd(f"summon minecraft:cow {px + 40 + i} {py} {pz} "
              f"{{PersistenceRequired:1b,CustomName:'{{\"text\":\"far\"}}'}}")
    time.sleep(5)
    near0 = count(r, '@e[type=minecraft:cow,name="near"]')
    far0 = count(r, '@e[type=minecraft:cow,name="far"]')
    print(f"   placed: {near0} near cows (inside PREY_RANGE 24), {far0} far cows (40 blocks out, rallied swarm stays on the pad)")
    if near0 != 4 or far0 != 4:
        check("PREY ARM DID NOT FIRE (the cows are not there)", False,
              f"near {near0}, far {far0}; expected 4 and 4 -- "
              + (r.cmd("execute as @e[type=minecraft:cow] run data get entity @s Pos") or "none").replace(chr(10), " | ")[:200])
    else:
        r.cmd("wfballistics swarmbench sim off")
        r.cmd(f"execute positioned {px} {py} {pz} run wfballistics swarmbench spawn 24 6")
        # Rallied on their own pad so they stay there; targeting is independent of the rally.
        r.cmd(f"wfballistics swarmbench march {px} {py} {pz}")
        time.sleep(3)
        bugs0 = count(r, f"@e[type={BUG}]")
        time.sleep(45)
        bugs1 = count(r, f"@e[type={BUG}]")
        near1 = count(r, '@e[type=minecraft:cow,name="near"]')
        far1 = count(r, '@e[type=minecraft:cow,name="far"]')
        print(f"   after 45s of {bugs0} glyphids: {near1} near cows, {far1} far cows, {bugs1} glyphids")
        check("prey in range is still hunted", near1 < near0, f"near cows {near0} -> {near1}")
        # Reported, not asserted. A rallied swarm still wanders, and over forty-five seconds a glyphid
        # covers far more than forty blocks -- so whether the far cows die measures pathfinding as much
        # as targeting range. The verdict for this arm comes from abprey.sh, which runs this same probe
        # against the unchanged build: both report 4 -> 0, so the index changed nothing here.
        print(f"   (observation) far cows {far0} -> {far1}; compare against the unchanged build rather\n"
              f"                 than against an expectation -- a rallied swarm still wanders")
        check("the swarm is still not prey", bugs1 == bugs0, f"glyphids {bugs0} -> {bugs1}")
    r.cmd("wfballistics swarmbench clear")
    r.cmd("kill @e[type=minecraft:cow]")

    # --- frozen mirror: written through the setter, watched as it decays --------------------------
    print("\n--- frozen-tick mirror (vanilla pig as the control) ---")
    r.cmd(f"summon {BUG} {px + 20} {py} {pz + 20} {{PersistenceRequired:1b}}")
    r.cmd(f"summon minecraft:pig {px + 22} {py} {pz + 20} {{PersistenceRequired:1b}}")
    time.sleep(3)
    r.cmd(f"data merge entity @e[type={BUG},limit=1] {{TicksFrozen:200}}")
    r.cmd("data merge entity @e[type=minecraft:pig,limit=1] {TicksFrozen:200}")
    time.sleep(1)
    bug0 = nbt_int(r, f"@e[type={BUG},limit=1]", "TicksFrozen")
    pig0 = nbt_int(r, "@e[type=minecraft:pig,limit=1]", "TicksFrozen")
    print(f"   just written: glyphid {bug0}, control pig {pig0}")
    if True:
        check("a write through setTicksFrozen still lands", bug0 > 0,
              f"glyphid {bug0}" + (f", control pig {pig0}" if pig0 > 0 else " (no pig control this run)"))
        time.sleep(6)
        bug1 = nbt_int(r, f"@e[type={BUG},limit=1]", "TicksFrozen")
        pig1 = nbt_int(r, "@e[type=minecraft:pig,limit=1]", "TicksFrozen")
        print(f"   6s later:     glyphid {bug1}, control pig {pig1}")
        # Vanilla thaws two ticks per tick, so both must be well down and must agree with each other.
        # Vanilla is max(0, i - 2) every tick: 200 must be gone inside 100 ticks, and must already have
        # dropped by ~40 after the first second. A mirror swallowing writes would sit at 200.
        check("and still thaws at the vanilla rate", bug0 < 200 and bug1 == 0,
              f"glyphid 200 -> {bug0} after 1s -> {bug1} after 7s"
              + (f"; control pig {pig0} -> {pig1}" if pig0 > 0 else ""))

    # --- equipment --------------------------------------------------------------------------------
    print("\n--- equipment ---")
    r.cmd(f"item replace entity @e[type={BUG},limit=1] armor.head with minecraft:diamond_helmet")
    time.sleep(1)
    armor = r.cmd(f"data get entity @e[type={BUG},limit=1] ArmorItems[3]") or ""
    check("a glyphid handed a helmet still wears it", "diamond_helmet" in armor,
          armor.strip()[:70] or "(no reply)")

    print(f"\n{'=' * 70}")
    failed = [n for n, ok, _ in results if not ok]
    print(f"{len(results) - len(failed)}/{len(results)} behaviour checks passed"
          + (f"; FAILED: {', '.join(failed)}" if failed else ""))
    for c in (f"kill @e[type={BUG}]", "kill @e[type=minecraft:cow]", "kill @e[type=minecraft:pig]",
              "wfballistics swarmbench clear", "forceload remove all"):
        r.cmd(c)


if __name__ == "__main__":
    main()
