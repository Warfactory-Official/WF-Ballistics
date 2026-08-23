# What the stack costs, measured

Every figure here is server MSPT from `/tick query`, taken on a headless dev server (NeoForge 21.1.248,
superflat, no player logged in, arena forceloaded) with the load applied and removed against a measured
baseline of the same arena. A tick has 50 ms.

Reproduce with the harness under `$CLAUDE_JOB_DIR/tmp`: `perf.py` (scaling and the level passes),
`perf2.py` (separation and flow-field A/B), `perf3.py` (nest, warband materialisation, drones at scale).

## Headline

| subsystem | at | over baseline | per unit |
|---|---|---|---|
| glyphid entities | 2000 marching | **+16.8 ms** | 8.4 µs/bug |
| glyphid records (sim tier) | 2000, 89% simulated | **+4.4 ms** | 2.9 µs/bug |
| separation grid | 2048 bodies | **≤0.7 ms** for the pass | see below |
| shared flow field | 2048 bodies | **no measurable difference** | — |
| colony records | 200 colonies, none loaded | **0.0 ms** | free |
| nest chambers hatching | one tier-1 nest | **+0.5 ms** | — |
| nest holding a garrison | 6 defenders standing | **+0.1 ms** | — |
| warband materialisation | 2000 placed in one tick | **+24.7 ms sustained** | the bodies, not the placing |
| drones | 256 in 16 squads | **+3.8 ms** | 15 µs/drone |

**The swarm's entity tier is the only thing in the stack that is expensive.** Everything else is
rounding error beside it, including all of the drone work and the whole colony record tier.

## Glyphids, entity tier

Linear, with no superlinear term left in it:

| bodies | 100 | 300 | 1000 | 2000 |
|---|---|---|---|---|
| over baseline | +1.0 ms | +2.7 ms | +8.8 ms | +16.8 ms |

At 2000, `SwarmProfiler` splits the 15.3 ms it accounts for as:

```
entity tick                14.687 ms   95.7%
  movement                  5.729 ms   37.3%
    collision sweep         1.884 ms   12.3%
      entity collisions     0.000 ms    0.0%   <- vanilla push, suppressed
      (unattributed)        1.884 ms   12.3%
    fire scan               0.674 ms    4.4%
    blocks inside           0.195 ms    1.3%
    (unattributed)          2.977 ms   19.4%
  ai step                   5.508 ms   35.9%
    path search             1.877 ms   12.2%
    path following          0.589 ms    3.8%
    digging                 0.000 ms    0.0%
    (unattributed)          3.042 ms   19.8%
  base tick                 1.975 ms   12.9%
```

Two things to read off it.

**`entity collisions` is 0.000 ms.** That was the term that grew faster than the swarm did — 1.8% of the
tick at a hundred glyphids and 15.5% at three hundred packed — and suppressing vanilla push is why the
table above is a straight line instead of a curve.

**The largest single cost is un-instrumented.** The two `(unattributed)` residuals are 19.4% and 19.8%,
so 39% of a glyphid's tick is in code with no profiler call site in it. Every *named* line is under 13%.
Anyone optimising the swarm further should place call sites in those two residuals first, because
everything currently visible is smaller than what is not.

Subtracting each line from its parent gives the exclusive cost of every piece, which sums to the 15.345 ms
measured:

| # | what | ms | share | per glyphid |
|---|---|---|---|---|
| 1 | ai step, **unnamed** | 3.042 | 19.8% | 1.53 µs |
| 2 | movement, **unnamed** | 2.977 | 19.4% | 1.49 µs |
| 3 | `baseTick` | 1.975 | 12.9% | 0.99 µs |
| 4 | block collision sweep | 1.884 | 12.3% | 0.95 µs |
| 5 | path search (the march) | 1.877 | 12.2% | 0.94 µs |
| 6 | entity tick, **unnamed** | 1.475 | 9.6% | 0.74 µs |
| 7 | fire scan | 0.674 | 4.4% | 0.34 µs |
| 8 | all four level passes | 0.658 | 4.3% | 0.33 µs |
| 9 | path following | 0.589 | 3.8% | 0.30 µs |
| 10 | blocks inside | 0.195 | 1.3% | 0.10 µs |
| — | entity-vs-entity push | 0.000 | 0.0% | — |
| — | digging | 0.000 | 0.0% | — |

**The top two lines, and six of the top fifteen percent, are code with no call site in it. 7.494 ms —
48.8% of the swarm — is unnamed.** No optimisation of anything on this list is worth starting before
those three residuals are broken up, because each of them is larger than every named line.

**Ceiling:** ~6000 bodies fills a 50 ms tick, ~3000 for half of one.

## The sim tier

2000 glyphids with nobody standing near them: **4.6 ms against 17.0 ms**, at 211 entities and 1789 records
(89% simulated). 2.9 µs per glyphid against 7.7. The tier is worth **3.7×**, and it raises the ceiling to
roughly 11 000 at half a tick.

## The separation grid

At 2048 bodies, priced by what it costs *and* what it buys:

| | MSPT | mean nearest neighbour | overlapping |
|---|---|---|---|
| on | 18.30 ms | 1.07 blocks | 80 of 2048 |
| off | 15.90 ms | **0.12 blocks** | **2009 of 2048** |

**The 2.4 ms difference is not the grid's cost, and it matters not to read it as one.** The profiler's own
total is `TICK + SEPARATE + FLOW + SIM + SQUAD`, and at 2000 bodies that total is 15.345 ms against an
entity tick of 14.687 — so **all four level passes together come to 0.658 ms**, which is the ceiling on
what the separation grid itself can be spending. The other ~1.7 ms of the A/B is the swarm behaving
differently: with separation off it collapses into a pile, and a pile occupies fewer chunks and sweeps
shorter distances, so it is genuinely cheaper to tick. Turning separation off does not buy 2.4 ms of
headroom; it buys under 0.7 ms and a swarm that is a single block.

Which is the right trade anyway: it is the difference between a crowd and two thousand bodies standing in
one place, for at most 4% of the swarm's cost.

## The shared flow field

18.00 ms with it on, 18.40 ms off; path search 1.877 ms against 1.915 ms. **The A/B does not discriminate
on this terrain, by construction** — the bench arena is superflat with nothing to route around, so a
shared field has nothing to save over a straight-line march. It reports itself working (`field at (400, 0):
9409 columns, complete, read 0 ticks ago`); it simply is not being asked anything hard. Re-run this arm
over terrain with obstacles before drawing any conclusion from it.

## The colony record tier

200 colonies simulating with none of them loaded: **0.0 ms**, below the baseline's own noise. 20 000 rounds
of `colony fastforward` (333 simulated minutes) is a one-off with no per-tick cost after it. A built nest
costs +0.5 ms while its chambers are hatching and +0.1 ms once its garrison is standing.

This is the §4 design paying off exactly as intended: a landscape full of colonies that grow, muster and
expand costs nothing until somebody walks up to one.

## Warband materialisation

Placing 2000 bodies out of a warband in a single tick: `Placed 2000 of 2000; 0 still owed to the record`,
with p50 25.9 ms and p99 28.9 ms afterwards. **The placement itself is not a spike** — p99 is within 3 ms
of p50 — the 25.9 ms is simply what 2000 bodies cost once they exist, and they are more expensive here than
the marching arm's 2000 because they have no path yet and are 5679 blocks from their objective.

So the thing to watch is not the moment of materialisation but the population it leaves behind, which is
the entity tier's cost above.

## Drones

Over pre-generated terrain, with the planner pool at 6 workers:

| drones | 64 | 128 | 256 |
|---|---|---|---|
| over baseline | +1.2 ms | +2.0 ms | +3.8 ms |

**15 µs per drone on the server thread**, linear. 928 terrain searches ran during the 256-drone arm, all of
them off the world thread (`A* is running OFF the world thread, as required`), worst 4.1 ms — which never
reaches the server thread, because the search is its own job and a squad whose search is still running keeps
flying on last tick's steering. Nothing in the drone stack is close to being a limiting factor.

## Not measured

- **Missiles.** `wfballistics swarm` requires a player and cannot run headless. Untested here.
- **Worldgen.** Not a subsystem of this mod, but it dominated three arms of an earlier pass before those
  arms were fixed: drones flying 800 blocks over ungenerated chunks read 49.4 ms of a 50 ms tick. If a
  measurement here is inexplicably large, check whether something is generating terrain.
- **The flow field on real terrain**, per above.
