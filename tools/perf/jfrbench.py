#!/usr/bin/env python3
"""Record a JFR execution profile over the exact load that produced the 48.8% unattributed residual.

The SwarmProfiler tree says *how much* of a glyphid tick has no call site in it (7.494 ms of 15.345 at 2000
marching bodies) and cannot say *what* is there, because a residual is by construction the part nobody
instrumented. JFR samples the stack instead of the call sites, so it can name frames the profiler has never
heard of -- which is the only way to turn "(unattributed) 3.042 ms" into a method.

Two recordings, because a profile of one load is not attributable on its own:
  A  an empty arena, so every frame the server pays for anyway is visible as a floor
  B  2000 glyphids marching, the arm docs/PERFORMANCE.md measured at 17.0 ms

Preconditions are asserted rather than assumed, per the eight ways this harness has lied before: the
corridor is forceloaded in bands under /forceload's 256-chunk limit and probed at both corners, the
population is printed at both ends of the window, and the SwarmProfiler tree is read *inside* the JFR
window so the two views can be checked against each other before anything is concluded from either.
"""
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from rcon import Rcon  # noqa: E402

# Recordings land beside the harness unless told otherwise; the JDK must be the one the game runs on
# (21), not whatever `java` resolves to -- a JFR file written by 21 does not parse with 17's `jfr`.
TMP = os.environ.get("WF_BENCH_OUT", HERE)
JDK = os.environ.get("WF_JDK21", "/usr/lib/jvm/java-21-openjdk/bin")
MSPT = re.compile(r"Average time per tick:\s*([\d.]+)\s*ms", re.I)
PCTL = re.compile(r"P50:\s*([\d.]+)ms.*?P95:\s*([\d.]+)ms.*?P99:\s*([\d.]+)ms", re.I | re.S)
TIERS = re.compile(r"(\d+) glyphids: (\d+) entities, (\d+) sim")

PAD = (0, -60, 0)
# A corridor rather than a box: the swarm has to still be marching when the recording ends, and a swarm
# that arrives stops pathing and starts milling, which is a different workload from the one being profiled.
CORRIDOR = (-100, -100, 900, 100)   # x0 z0 x1 z1
TARGET = (800, -60, 0)
WARMUP = 45      # seconds of marching before recording, for JIT and for a steady-state spread
WINDOW = 60      # seconds of recording under load
BASE_WINDOW = 20


def connect():
    deadline = time.time() + 420
    while True:
        try:
            return Rcon()
        except (OSError, ConnectionError):
            if time.time() > deadline:
                raise SystemExit("rcon never came up")
            time.sleep(2)


def mspt(r):
    out = r.cmd("tick query") or ""
    mean, pct = MSPT.search(out), PCTL.search(out)
    return (float(pct.group(1)) if pct else (float(mean.group(1)) if mean else float("nan")),
            float(pct.group(2)) if pct else float("nan"),
            float(pct.group(3)) if pct else float("nan"))


def tiers(r):
    m = TIERS.search(r.cmd("wfballistics swarmbench tiers") or "")
    return (int(m.group(2)), int(m.group(3))) if m else (-1, -1)


def forceload(r, x0, z0, x1, z1, label):
    """/forceload refuses more than 256 chunks per command and says so on a line a script will not read."""
    done, step = 0, 80
    x = x0
    while x <= x1:
        hi = min(x + step - 1, x1)
        reply = r.cmd(f"forceload add {x} {z0} {hi} {z1}") or ""
        m = re.search(r"Marked (\d+) chunks", reply)
        if m:
            done += int(m.group(1))
        elif reply.strip():
            print(f"   !! forceload refused: {reply.strip()[:90]}")
        x = hi + 1
    ok = 0
    for cx, cz in ((x0 + 16, z0 + 16), (x1 - 16, z1 - 16)):
        reply = r.cmd(f"forceload query {cx} {cz}") or ""
        # The reply is "Chunk at [-6, -6] in Overworld is marked for force loading" -- not "is force loaded",
        # which is what perf3.py looked for, so every corner probe in that pass reported a held arena as lost.
        # A precondition check that is wrong in the pessimistic direction still ruins a run: it makes a good
        # arm look broken and invites you to go "fix" an arena that was never wrong.
        if "marked for force loading" in reply.lower():
            ok += 1
        else:
            print(f"   !! ({cx}, {cz}) is NOT force loaded: {reply.strip()[:70]}")
    print(f"   {label}: asked for {done} chunks, {ok}/2 corner probes held")
    return ok == 2


def server_pid():
    """The forked game JVM, not the gradle daemon that launched it."""
    try:
        out = subprocess.run([f"{JDK}/jps", "-l"], capture_output=True, text=True, timeout=30).stdout
        for line in out.splitlines():
            if "devlaunch" in line:
                return int(line.split()[0])
    except Exception as exc:                                          # noqa: BLE001
        print(f"   jps failed ({exc}), falling back to pgrep")
    out = subprocess.run(["pgrep", "-f", "net.neoforged.devlaunch.Main"],
                         capture_output=True, text=True).stdout.split()
    return int(out[0]) if out else -1


def jcmd(pid, *args):
    out = subprocess.run([f"{JDK}/jcmd", str(pid), *args],
                         capture_output=True, text=True, timeout=180)
    return (out.stdout + out.stderr).strip()


def record(pid, name, path, seconds, on_window):
    """Start a recording, run `on_window` while it is open, then stop it and confirm the file exists.

    The 1ms sampling period is the whole point: `settings=profile` samples at 10ms, which over a 60s window
    is ~6000 samples of one thread -- enough to rank the top few frames and not enough to break a residual
    into methods. If the JVM refuses the inline event override the run continues at the default rate and
    says so, because a quietly coarser profile would read as a confident answer.
    """
    if os.path.exists(path):
        os.remove(path)
    started = jcmd(pid, "JFR.start", f"name={name}", "settings=profile", f"filename={path}",
                   "jdk.ExecutionSample#period=1ms", "jdk.NativeMethodSample#period=1ms")
    fine = "Started recording" in started
    if not fine:
        print(f"   1ms override refused ({started[:120]}); retrying at the profile default")
        started = jcmd(pid, "JFR.start", f"name={name}", "settings=profile", f"filename={path}")
    print(f"   JFR.start {name}: {started.splitlines()[-1][:100] if started else 'no reply'}"
          f"{'  [1ms]' if fine else '  [10ms DEFAULT]'}")

    on_window(seconds)

    print(f"   JFR.stop  {name}: {jcmd(pid, 'JFR.stop', f'name={name}').splitlines()[-1][:100]}")
    size = os.path.getsize(path) if os.path.exists(path) else 0
    print(f"   {os.path.basename(path)}: {size / 1e6:.1f} MB")
    return size > 0


def main():
    r = connect()
    for _ in range(150):
        if (r.cmd("list") or "").strip():
            break
        time.sleep(2)

    px, py, pz = PAD
    tx, ty, tz = TARGET
    print("=== arena ===")
    for c in ("gamerule randomTickSpeed 0", "gamerule doDaylightCycle false",
              "gamerule doMobSpawning false", "gamerule doFireTick false",
              "time set noon", "weather clear"):
        r.cmd(c)
    held = forceload(r, *CORRIDOR, "march corridor")
    print("   generating...")
    time.sleep(75)
    held = forceload(r, *CORRIDOR, "march corridor (after generation)") and held
    if not held:
        print("   !! corridor is not fully held; the swarm can march out of the loaded world")

    pid = server_pid()
    print(f"   game JVM pid {pid}")
    if pid < 0:
        raise SystemExit("no devlaunch JVM to attach to")
    print("   " + jcmd(pid, "VM.flags").replace("\n", "\n   ")[:600])

    r.cmd("wfballistics swarmbench profile on")

    # --- A: the floor -------------------------------------------------------------------------
    print(f"\n{'-' * 78}\nA  empty arena ({BASE_WINDOW}s)")
    for c in ("wfballistics swarmbench clear", "wfballistics drone clear",
              "wfballistics colony clear"):
        r.cmd(c)
    # `clear` releases the arena forceload and turns profiling off; both have to come back.
    forceload(r, *CORRIDOR, "corridor re-held after clear")
    r.cmd("wfballistics swarmbench profile on")
    time.sleep(10)

    def idle_window(seconds):
        time.sleep(seconds)
        p50, p95, p99 = mspt(r)
        print(f"   MSPT p50 {p50:.2f}, p95 {p95:.2f}, p99 {p99:.2f}   entities/records {tiers(r)}")

    record(pid, "base", f"{TMP}/base.jfr", BASE_WINDOW, idle_window)

    # --- B: 2000 marching ---------------------------------------------------------------------
    print(f"\n{'-' * 78}\nB  2000 glyphids marching ({WINDOW}s, after {WARMUP}s of warmup)")
    # `watcher x z` is refused -- it takes three coordinates, and every entity-tier arm of the last pass
    # asked for two and got "Incomplete (expected 3 coordinates)" on a line nothing read. Those arms were
    # still valid because `sim off` keeps every glyphid an entity without a watcher, which is also why the
    # order here is sim-off first: the watcher is belt and braces, not the mechanism.
    setup = ["wfballistics swarmbench sim off",
             f"wfballistics swarmbench watcher {px} {py} {pz}",
             f"execute positioned {px} {py} {pz} run wfballistics swarmbench spawn 2000 48",
             f"wfballistics swarmbench march {tx} {ty} {tz}",
             "wfballistics swarmbench reset"]
    replies = []
    for c in setup:
        reply = (r.cmd(c) or "").strip()
        replies.append(reply)
        if reply:
            print(f"   > {c[:52]:<52} {reply.splitlines()[0][:70]}")
    joined = "\n".join(replies)
    for needle, why in (("2000 glyphids marching", "the swarm must be under march orders"),
                        ("Spawned", "the swarm must exist")):
        if needle not in joined:
            print(f"   !! ARM DID NOT FIRE: {why} (no {needle!r} in the replies)")

    # `spawn` calls releaseArena() and then forces its own 48-block box, so the corridor has to be proved
    # again after it rather than before: a swarm marching 800 blocks out of a released corridor stops
    # existing, and that reads exactly like the code under test deleting it.
    if not forceload(r, *CORRIDOR, "corridor after spawn"):
        print("   !! corridor lost; abandoning rather than profiling a swarm walking into unloaded chunks")

    print(f"   warming up {WARMUP}s...")
    time.sleep(WARMUP)
    before = tiers(r)
    p50, p95, p99 = mspt(r)
    print(f"   at the start of the window: {before} entities/records, MSPT p50 {p50:.2f}")

    def load_window(seconds):
        """Read the SwarmProfiler tree from inside the JFR window, so the two views describe one workload."""
        deadline = time.time() + seconds
        shots = 0
        while time.time() < deadline:
            time.sleep(min(20.0, max(0.0, deadline - time.time())))
            shots += 1
            print(f"\n   --- SwarmProfiler, {shots * 20}s into the window ---")
            for line in (r.cmd("wfballistics swarmbench report") or "").splitlines()[:22]:
                print("      ", line)

    record(pid, "swarm", f"{TMP}/swarm.jfr", WINDOW, load_window)

    after = tiers(r)
    p50, p95, p99 = mspt(r)
    print(f"\n   at the end of the window:   {after} entities/records, "
          f"MSPT p50 {p50:.2f}, p95 {p95:.2f}, p99 {p99:.2f}")
    if before != after:
        print(f"   !! population moved during the window ({before} -> {after}); the arm leaked")
    print("   density: " + ((r.cmd("wfballistics swarmbench density") or "").splitlines() or [""])[0][:100])

    for line in (r.cmd("wfballistics swarmbench report") or "").splitlines():
        print("      ", line)

    for c in ("wfballistics swarmbench clear", "wfballistics colony clear", "forceload remove all"):
        r.cmd(c)
    print("\n=== recordings ===")
    for f in ("base.jfr", "swarm.jfr"):
        p = f"{TMP}/{f}"
        print(f"  {p}  {os.path.getsize(p) / 1e6:.1f} MB" if os.path.exists(p) else f"  {p}  MISSING")


if __name__ == "__main__":
    main()
