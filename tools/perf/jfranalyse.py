#!/usr/bin/env python3
"""Turn a JFR execution profile into the SwarmProfiler's own phase tree, and then break the residuals open.

The point of this script is the mapping in PHASES below. SwarmProfiler measures phases with begin/end pairs
around specific methods, so a JFR sample can be charged to the same phase by asking which of those methods is
innermost on its stack -- the deepest marker wins, exactly as a nested begin/end would. That makes the two
views directly comparable: if JFR says AI is 36% of the server thread and the profiler says 35.9%, the
mapping is right and the residual breakdown underneath it can be trusted.

A residual is then a sample whose innermost marker *is* the phase: inside serverAiStep with no PATH, NAV or
DIG marker below it. Those are the 7.494 ms that docs/PERFORMANCE.md could not name, and they are broken open
two ways -- by the callee directly under the marker (which sub-call of serverAiStep is it) and by the leaf
frame (what is it actually executing) -- because those answer different questions.

Usage: jfranalyse.py <recording.jfr> [more.jfr ...]
"""
import collections
import os
import subprocess
import sys

# JDK 21's jfr, not whatever `jfr` is on PATH: a recording written by the game's JVM does not parse with an
# older tool, and the failure is an empty parse rather than an error.
JFR = os.environ.get("WF_JDK21", "/usr/lib/jvm/java-21-openjdk/bin") + "/jfr"
THREAD = "Server thread"
TOP = 22

# Innermost match wins, so order in this list is irrelevant -- but the nesting it encodes is not. Each entry
# is (substring of "class.method", phase, parent phase). Mirrors SwarmProfiler.PARENT and the mixin targets:
# AI is Mob#serverAiStep, MOVE is EntityGlyphid#travel (a *sibling* of AI inside LivingEntity#aiStep, not a
# child of it), COLLIDE/INSIDE/SCAN are the three parts of Entity#move that MixinEntity brackets.
PHASES = [
    ("world.level.pathfinder.WalkNodeEvaluator.getNeighbors", "PATH_NEIGHBORS", "PATH_ASTAR"),
    ("world.level.pathfinder.PathFinder.findPath",            "PATH_ASTAR",     "PATH"),
    ("ai.navigation.PathNavigation.createPath",               "PATH",           "AI"),
    ("ai.navigation.PathNavigation.tick",                     "NAV",            "AI"),
    ("wfballistics.entity.glyphid.GlyphidDigging.",           "DIG",            "AI"),
    ("wfballistics.entity.glyphid.ai.GlyphidPathCache.",      "PATHCACHE",      "AI"),
    ("world.level.Level.getEntityCollisions",                 "ENTCOL",         "COLLIDE"),
    ("world.entity.Entity.collide",                           "COLLIDE",        "MOVE"),
    ("world.entity.Entity.checkInsideBlocks",                 "INSIDE",         "MOVE"),
    ("EntityGlyphid.checkInsideBlocks",                       "INSIDE",         "MOVE"),
    # SCAN is an inline region of Entity#move rather than a method, so it is caught by the call it brackets.
    ("world.level.Level.getBlockStatesIfLoaded",              "SCAN",           "MOVE"),
    ("world.entity.Mob.serverAiStep",                         "AI",             "TICK"),
    ("EntityGlyphid.serverAiStep",                            "AI",             "TICK"),
    ("world.entity.LivingEntity.travel",                      "MOVE",           "TICK"),
    ("EntityGlyphid.travel",                                  "MOVE",           "TICK"),
    ("world.entity.Entity.move",                              "MOVE",           "TICK"),
    ("world.entity.LivingEntity.baseTick",                    "BASE",           "TICK"),
    ("EntityGlyphid.baseTick",                                "BASE",           "TICK"),
    ("world.entity.Entity.baseTick",                          "BASE",           "TICK"),
    ("EntityGlyphid.pushEntities",                            "PUSH",           "TICK"),
    ("EntityGlyphid.tick",                                    "TICK",           None),
    # The level passes, which are not inside anybody's tick.
    ("wfballistics.entity.glyphid.GlyphidSeparation.",        "SEPARATE",       None),
    ("wfballistics.entity.glyphid.nav.GlyphidFlowField",      "FLOW",           None),
    ("wfballistics.entity.glyphid.sim.SimGlyphid",            "SIM",            None),
    ("wfballistics.colony.GlyphidSquads.",                    "SQUAD",          None),
]

# What a frame is *doing*, for the rollup that tests the "it is all block-state reads and palette lookups"
# hypothesis. Checked against the leaf frame only, so the shares add to 100%.
KINDS = [
    ("block state read", ("PalettedContainer", "BitStorage", "LevelChunkSection.getBlockState",
                          "LevelChunk.getBlockState", "Level.getBlockState", "ChunkAccess.getBlockState",
                          "PathNavigationRegion.getBlockState", "EmptyLevelChunk", "ImposterProtoChunk",
                          "BlockGetter.getBlockState", "Level.getChunk", "ChunkSource.getChunk",
                          "LevelChunk.getSection", "Level.isOutsideBuildHeight")),
    ("voxel / AABB",     ("VoxelShape", "Shapes.", "AABB.", "DiscreteVoxelShape", "BlockCollisions",
                          "CollisionContext", "CollisionGetter", "IndexMerger", "CubePointRange",
                          "ArrayVoxelShape", "SliceShape", "BitSetDiscreteVoxelShape")),
    ("block behaviour",  ("BlockBehaviour", "BlockState", "Block.", "FluidState", "Blocks.")),
    ("pathfinder",       ("pathfinder.", "Node", "BinaryHeap", "PathType")),
    ("entity lookup",    ("EntitySection", "EntityLookup", "LevelEntityGetter", "EntityTickList",
                          "PersistentEntitySectionManager", "getEntities")),
    ("math / vec",       ("Mth.", "Vec3", "Math.", "StrictMath", "BlockPos", "Vec3i")),
    ("collections",      ("java.util.", "it.unimi.dsi", "Long2Object", "Int2Object", "ObjectArrayList",
                          "HashMap", "ArrayList", "Iterator", "Stream", "Spliterator")),
    ("attributes",       ("AttributeMap", "AttributeInstance", "AttributeModifier", "getAttributeValue")),
    ("goals / sensing",  ("goal.", "Sensing", "GoalSelector", "control.", "targeting")),
    ("wfballistics",     ("com.wf.wfballistics",)),
]


def parse(path):
    """Stream `jfr print` and yield (thread, [frames leaf-first]). Streamed rather than read into a string:
    a minute of 1ms sampling is gigabytes of text and there is no reason for any of it to be resident."""
    cmd = [JFR, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", path]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                            text=True, bufsize=1 << 20)
    thread, frames, in_stack = None, None, False
    for line in proc.stdout:
        if in_stack:
            if line.startswith("  ]"):
                in_stack = False
                yield thread, frames
                thread, frames = None, None
            else:
                stripped = line.strip()
                if stripped and stripped != "...":
                    frames.append(stripped.split("(", 1)[0])
        elif line.startswith("  sampledThread = "):
            thread = line.split('"', 2)[1] if '"' in line else "?"
        elif line.startswith("  stackTrace = ["):
            frames, in_stack = [], True
    proc.stdout.close()
    proc.wait()


def phase_of(frames):
    """The innermost phase marker on the stack, and where it sits: (phase, index of the marker frame)."""
    for i, frame in enumerate(frames):
        for needle, phase, _ in PHASES:
            if needle in frame:
                return phase, i
    return None, -1


def kind_of(frame):
    for name, needles in KINDS:
        for needle in needles:
            if needle in frame:
                return name
    return "other"


def short(frame):
    """`net.minecraft.world.entity.Mob.serverAiStep` -> `Mob.serverAiStep`, keeping nested classes."""
    parts = frame.split(".")
    return ".".join(parts[-2:]) if len(parts) > 2 else frame


def bar(share, width=28):
    return "#" * int(round(share * width))


def report(path):
    total = 0
    threads = collections.Counter()
    phases = collections.Counter()               # innermost phase -> samples (this is the exclusive cost)
    callees = collections.defaultdict(collections.Counter)   # phase -> frame just inside the marker
    leaves = collections.defaultdict(collections.Counter)    # phase -> leaf frame
    kinds = collections.defaultdict(collections.Counter)     # phase -> kind of leaf
    server = 0
    unphased = collections.Counter()

    for thread, frames in parse(path):
        total += 1
        threads[thread] += 1
        if thread != THREAD or not frames:
            continue
        server += 1
        phase, at = phase_of(frames)
        if phase is None:
            phases["(not the swarm)"] += 1
            unphased[short(frames[0])] += 1
            continue
        phases[phase] += 1
        # The frame the marker called: which sub-call of serverAiStep / travel / tick this sample is in.
        callees[phase][short(frames[at - 1]) if at > 0 else "(in the method itself)"] += 1
        leaves[phase][short(frames[0])] += 1
        kinds[phase][kind_of(frames[0])] += 1

    print(f"\n{'=' * 92}\n{path}\n{'=' * 92}")
    print(f"{total} execution samples, {server} of them on '{THREAD}' ({100.0 * server / max(1, total):.1f}%)")
    print("\n  busiest threads")
    for name, n in threads.most_common(8):
        print(f"    {name[:44]:<44} {n:>7}  {100.0 * n / max(1, total):>5.1f}%")
    if server == 0:
        print("  !! nothing sampled on the server thread; nothing below is meaningful")
        return

    swarm = server - phases["(not the swarm)"]
    print(f"\n  phase attribution -- exclusive, innermost marker wins ({swarm} swarm samples "
          f"= {100.0 * swarm / server:.1f}% of the server thread)")
    print(f"    {'phase':<18} {'samples':>8} {'of thread':>10} {'of swarm':>9}")
    for phase, n in phases.most_common():
        print(f"    {phase:<18} {n:>8} {100.0 * n / server:>9.1f}% "
              f"{(100.0 * n / swarm if swarm and phase != '(not the swarm)' else 0):>8.1f}%  "
              f"{bar(n / server)}")

    for phase in ("AI", "MOVE", "TICK", "BASE", "COLLIDE", "PATH_NEIGHBORS", "PATH_ASTAR", "NAV", "INSIDE"):
        n = phases.get(phase, 0)
        if n < 20:
            continue
        print(f"\n  {'-' * 88}\n  {phase} residual: {n} samples, {100.0 * n / server:.1f}% of the server "
              f"thread, {100.0 * n / swarm:.1f}% of the swarm")
        print(f"    called from the phase into  {'':<44}{'samples':>9} {'share':>7}")
        for frame, c in callees[phase].most_common(TOP):
            print(f"      {frame[:56]:<56} {c:>9} {100.0 * c / n:>6.1f}%")
        print(f"    executing (leaf frame)")
        for frame, c in leaves[phase].most_common(TOP):
            print(f"      {frame[:56]:<56} {c:>9} {100.0 * c / n:>6.1f}%  {bar(c / n, 16)}")
        print(f"    what kind of work")
        for name, c in kinds[phase].most_common():
            print(f"      {name:<56} {c:>9} {100.0 * c / n:>6.1f}%")

    if unphased:
        print(f"\n  {'-' * 88}\n  server thread outside the swarm ({phases['(not the swarm)']} samples)")
        for frame, c in unphased.most_common(14):
            print(f"      {frame[:56]:<56} {c:>9}")

    # The hypothesis, tested against the whole swarm rather than one phase: how much of a glyphid's tick is
    # reading a block state, wherever on the stack it happens.
    print(f"\n  {'-' * 88}\n  the swarm's leaf frames by kind of work")
    rolled = collections.Counter()
    for phase, counter in kinds.items():
        if phase == "(not the swarm)":
            continue
        rolled.update(counter)
    for name, c in rolled.most_common():
        print(f"    {name:<24} {c:>9} {100.0 * c / max(1, swarm):>6.1f}%  {bar(c / max(1, swarm))}")


def collapse(path, out):
    """Folded stacks for a flamegraph, server thread only: `a;b;c count` per line."""
    folded = collections.Counter()
    for thread, frames in parse(path):
        if thread == THREAD and frames:
            folded[";".join(short(f) for f in reversed(frames))] += 1
    with open(out, "w") as fh:
        for stack, n in folded.most_common():
            fh.write(f"{stack} {n}\n")
    print(f"\n  folded stacks -> {out} ({len(folded)} distinct)")


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        report(arg)
        collapse(arg, arg.replace(".jfr", "") + ".folded")
