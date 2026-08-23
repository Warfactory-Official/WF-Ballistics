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

They are broken up below, under "What the residuals actually are".

**Ceiling:** ~6000 bodies fills a 50 ms tick, ~3000 for half of one.

## What the residuals actually are

A residual is by construction the part nobody instrumented, so no further call site can name it — the
profiler can only ever say how much it missed. Java Flight Recorder samples the stack instead of the call
sites, so it can name frames the profiler has never heard of.

Method: `./gradlew runServer -Pjfr`, `jcmd JFR.start settings=profile jdk.ExecutionSample#period=1ms`,
`DebugNonSafepoints` on and `stackdepth=192` (the default 64 truncates the *outermost* frames, which are
the ones a sample has to be attributed by). 60 s window, 20 204 samples, 20 178 of them on the server
thread. Each sample is charged to the innermost `SwarmProfiler` phase marker on its stack — the same rule
a nested begin/end pair follows — so the two views measure one tree in two ways. Reproduce with
`tools/perf/jfr.sh` and read it with `tools/perf/jfranalyse.py`.

**The mapping is only worth as much as its agreement with the profiler, so that is checked first.** Every
phase, JFR against `SwarmProfiler` over the same window:

| phase | JFR | profiler | delta |
|---|---|---|---|
| ai step, unnamed | 3.007 ms | 3.041 ms | −0.034 |
| movement, unnamed (incl. fire scan) | 3.635 ms | 3.668 ms | −0.033 |
| path search, all three levels | 3.457 ms | 3.440 ms | +0.017 |
| base tick (incl. fluid push) | 2.282 ms | 2.293 ms | −0.011 |
| collision sweep | 1.919 ms | 1.858 ms | +0.061 |
| entity tick, unnamed | 1.064 ms | 1.114 ms | −0.050 |
| path following | 0.730 ms | 0.702 ms | +0.028 |
| separation | 0.658 ms | 0.614 ms | +0.044 |
| collision push | 0.236 ms | 0.260 ms | −0.024 |
| blocks inside | 0.172 ms | 0.192 ms | −0.020 |

Ten phases, none off by more than 0.06 ms. One sample is 1.007 µs of tick, which makes the sample counts
below readable as microseconds directly.

*(This run marched the swarm 800 blocks down a forceloaded corridor rather than 300, so its path search is
3.44 ms against the 1.88 ms above — more searching, not slower searching. Every other line, and all three
residuals, reproduce the table above to within 2%.)*

### The ai-step residual is the goal selector

| what | ms | of the residual |
|---|---|---|
| `GoalSelector.tick` — re-evaluating `canUse`/`canContinueToUse` | 1.694 | 56.3% |
| `GoalSelector.tickRunningGoals` | 0.675 | 22.5% |
| `MoveControl.tick` | 0.290 | 9.6% |
| `Level.getProfiler` | 0.210 | 7.0% |
| `Sensing.tick` | 0.094 | 3.1% |

**78.8% of the largest residual in the swarm is the goal selector**, and its leaf frames say what that
means: iterating the goal set (`ObjectLinkedOpenHashSet$SetIterator.next`, 12.7%), asking a path whether it
is finished (`Path.isDone` + `PathNavigation.isDone`, 16.2%), range checks (`AABB.intersects`, 7.3%) and
`getHealth`/`AttributeMap.getValue` (9.6%). A glyphid registers eight goals, so at 2000 bodies this is
320 000 `canUse` evaluations a second, nearly all of which return the same answer they returned last tick.

Block-state reads are **0.3%** of this residual.

`Level.getProfiler` at 7% is vanilla's own instrumentation, which `GoalSelector` pushes and pops around
every goal. It is cheaper on a server that is not a dev run; measure it there before counting it as a win.

### The movement residual is one block search

| what | ms | of the residual |
|---|---|---|
| `Entity.setOnGroundWithMovement` | 1.972 | 54.3% |
| the fire scan's terminal `noneMatch` (charged to `SCAN` by the profiler) | 0.454 | 12.5% |
| `Entity.getBlockPosBelowThatAffectsMyMovement` | 0.325 | 8.9% |
| `LivingEntity.checkFallDamage` | 0.233 | 6.4% |
| `LivingEntity.getBlockSpeedFactor` | 0.118 | 3.2% |

`setOnGroundWithMovement` is `Entity.checkSupportingBlock` → `CollisionGetter.findSupportingBlock`, which
walks a `BlockCollisions` iterator over the entity's box to find the block it is standing on. It is 1.972 ms
— **11.5% of the entire swarm, the largest single named cost in the tick**, larger than the collision sweep
and larger than node expansion.

Here the block-state hypothesis is right: **35.3% of this residual's leaf frames are block-state reads**
(`SimpleBitStorage.get`, `PalettedContainer.get`, `LevelChunkSection.getBlockState`,
`SimpleBitStorage.cellIndex`) and another 12.2% is `BlockBehaviour$BlockStateBase`. Just under half of
movement's residual is reading block states out of palettes.

### The entity-tick residual is synchronised-data bookkeeping

| what | ms | of the residual |
|---|---|---|
| `Entity.setTicksFrozen` (a `SynchedEntityData` write) | 0.281 | 26.4% |
| `NonNullList.get` (the data-item array behind it) | 0.193 | 18.2% |
| `LivingEntity.refreshDirtyAttributes` | 0.142 | 13.3% |
| `LivingEntity.getArrowCount` | 0.044 | 4.2% |

**62% of it is entity-data and attribute bookkeeping** — a frozen-tick counter written every tick through
the synched-data layer for 2000 bugs that are never in powder snow, and a dirty-attribute sweep for bugs
whose attributes do not change.

### Where the block reads actually are

Across the whole swarm, by leaf frame:

| kind of work | ms | share |
|---|---|---|
| unclassified | 6.061 | 35.2% |
| **block state read** | **2.973** | **17.3%** |
| collections | 2.205 | 12.8% |
| pathfinder | 1.400 | 8.1% |
| **block behaviour** | **1.318** | **7.7%** |
| goals / sensing | 0.905 | 5.3% |
| voxel / AABB | 0.729 | 4.2% |
| math / vec | 0.612 | 3.6% |
| wfballistics' own code | 0.515 | 3.0% |
| attributes | 0.406 | 2.4% |

**Block-state reads plus block behaviour are 4.290 ms, 24.9% of the swarm** — a quarter of the tick, which
makes them the largest single kind of work in it. But they are not spread evenly, and that is the useful
part: they are 47.5% of the movement residual and 48.7% of the collision sweep, 22.4% of node expansion,
8.5% of base tick — and **0.3% of the ai-step residual**. Chasing palette lookups would do nothing at all
to the largest residual.

Two more findings worth recording:

- **20.3% of node expansion is comparing tag names.** `String.equals` (9.4%), `ImmutableCollections$SetN.probe`
  (5.0%), `TagKey.equals` (3.7%) and `Holder$Reference.is` (2.2%) inside `WalkNodeEvaluator.findAcceptedNode`
  come to 0.658 ms/tick. That is `BlockState.is(TagKey)` resolving a tag by string on every neighbour of
  every node.
- **97.1% of the collision sweep is already Lithium's.** The dev runtime has Lithium in it, so the sweep
  measured here is `collideMovementWithPostponedFluidCheck`, not vanilla's. Any further work on the sweep is
  competing with Lithium rather than with Mojang.

## What Lithium already fixes, and what it does not

Lithium is on the dev runtime (`localRuntime`, never published), so every number above is measured *with* it.
Two of its optimisation groups are **off by default** and both target things the profile says the swarm is
paying for. Lithium logs its own decisions, which makes the precondition checkable rather than assumed:
at defaults it prints `0 override(s) found` and four lines of the form

```
Option 'mixin.experimental.entity.block_caching.block_support' requires 'mixin.util.block_tracking=true'
but found 'false'. Setting 'mixin.experimental.entity.block_caching.block_support=false'.
```

One server boot per arm, because Lithium reads its options once at startup. Same world, same 2000 bugs, same
800-block corridor; only `lithium.properties` differs. Reproduce with `tools/perf/lith.sh`.

| arm | `lithium.properties` | p50 | vs defaults |
|---|---|---|---|
| L0 | *(defaults)* | 20.4 ms | — |
| L1 | `mixin.ai.pathing=true` | 17.8 ms | **−2.6 ms (−13%)** |
| L2 | `mixin.util.block_tracking=true`<br>`mixin.experimental=true` | 17.4 ms | **−3.0 ms (−15%)** |
| L3 | both | **15.4 ms** | **−5.0 ms (−25%)** |

Where the 5 ms comes from:

| phase | L0 | L3 | delta |
|---|---|---|---|
| path search — node expansion | 2.588 | 0.683 | **−1.905** |
| collision sweep | 1.817 | 0.958 | **−0.859** |
| fire scan | 0.639 | 0.036 | **−0.603** |
| base tick | 2.405 | 1.924 | −0.481 |
| **movement residual** | **2.975** | **2.906** | **−0.069** |
| **ai-step residual** | **3.580** | **3.153** | **−0.427** |

**Lithium fixes what was already named and leaves the residuals almost exactly where they were.** Node
expansion falls by 74% — `ai.pathing` caches the `PathType` on the `BlockState` object itself, so
`getPathTypeStatic` becomes a field read instead of the tag-name string comparison that was 20% of node
expansion. The collision sweep and fire scan halve and vanish respectively. But the two residuals move by
2% and 12%, and after L3 they are **6.06 ms of an 11.9 ms entity tick — 51%, a larger share than before.**

Two specific negatives worth recording, because they are the reason the fix list below exists:

- **`block_support` does not help a marching swarm.** It was applied (26 mixin references, no auto-disable
  lines) and `checkSupportingBlock` did not get cheaper. Lithium's block-listening system invalidates on
  *block* change; `findSupportingBlock` depends on the *entity's* position, and a marching bug moves every
  tick, so the cache never hits. The 1.97 ms stays.
- **Lithium has no goal-selector optimisation.** `mixin.collections.goals` only swaps the backing set for a
  fastutil one — it is already active (the profile's `ObjectLinkedOpenHashSet$SetIterator.next` is
  Lithium's set) and iterating it is still 12.7% of the ai-step residual. Nothing in Lithium touches
  `canUse`/`canContinueToUse` evaluation.

**Caveat that decides whether any of this is real: Lithium is `localRuntime` and is not shipped with this
mod.** If the pack does not include it, the baseline is worse than everything measured here, not better —
97% of the collision sweep above is Lithium's `collideMovementWithPostponedFluidCheck`, not vanilla's.

## The fix list

Costs are the JFR run's (17.203 ms swarm, Lithium at defaults). Items 2–8 are untouched by L3.

| # | fix | measured | where |
|---|---|---|---|
| 1 | Turn on `mixin.ai.pathing`, `mixin.util.block_tracking`, `mixin.experimental` | **−5.0 ms** | pack config, no code |
| 2 | Cache `checkSupportingBlock` on the block underfoot | **1.97 ms** | `EntityGlyphid` |
| 3 | Replace the goal selector for glyphids | **2.37 ms** | `MixinMob` / `EntityGlyphid` |
| 4 | Stagger target acquisition | **0.67 ms** | `GlyphidTargetGoal` |
| 5 | Cache `navigation.isDone()` for the snapshot | **0.54 ms** | `GlyphidBody` |
| 6 | Skip the no-op `setTicksFrozen` write | **0.28 ms** | `EntityGlyphid` |
| 7 | Skip equipment-change detection | **0.26 ms** | mixin |
| 8 | Drop vanilla's profiler churn | **0.23 ms** | falls out of #3 |

**2. `checkSupportingBlock` — 1.97 ms, 11.5% of the swarm, the largest single named cost.**
`Entity.setOnGroundWithMovement` → `checkSupportingBlock` → `findSupportingBlock` walks a `BlockCollisions`
iterator over a 1e-6-tall box under the entity, every tick, and runs it **twice** when the first search
comes back empty and `onGroundNoBlocks` is false. `mainSupportingBlockPos` is only read for friction, soul
sand, honey, slime bounce and step sounds. Override it in `EntityGlyphid` and re-search only when the
floored block position under the bug changes — a marching glyphid crosses a block boundary every ~4 ticks
at 0.25 b/tick, so the hit rate is ~75% before any cleverness.

**3. The goal selector — 2.37 ms, 78.8% of the ai-step residual.**
Vanilla already staggers it: `Mob.serverAiStep` runs the full `tick()` on `(tickCount + id) % 2 == 0` and
the cheap `tickRunningGoals(false)` otherwise. So 2.37 ms is the *already halved* cost of three passes over
eight goals per bug. The leaves are set iteration (12.7%), `Path.isDone` (11.6%), `AABB.intersects` (7.3%)
and `getHealth` (6.3%) — bookkeeping, not decisions. The brain already decides what a glyphid does, and the
record tier proves a glyphid does not need a goal selector to do it: `SimGlyphid` has none and runs at
2.9 µs against 7.7. Cancel `serverAiStep` for glyphids in the mixin we already have and run a purpose-built
step (navigation, move control, the brain, staggered targeting). This also deletes #8 for free.

**4. Target acquisition — 0.67 ms.** `GlyphidTargetGoal.canUse()` returns `true` unconditionally and
`requiresUpdateEveryTick()` is `true`, so `findTargetCandidate()` runs on **every bug on every tick**. It
ends in `nearestPrey`, which inflates the bounding box by `PREY_RANGE = 24` and asks the level for every
`LivingEntity` in a 48-block box — then filters the other glyphids out in Java, one `isPrey` →
`isAlive` → `getHealth` call each. Measured: 0.32 ms in `isPrey` alone, 0.31 ms in the `getHealth` beneath
it, 0.72 ms in `AABB.intersects` under `EntitySection.getEntities`. Stagger it by entity id — a glyphid does
not need to look for a target twenty times a second — and let the section filter reject glyphids before they
are fetched.

**5. `navigation.isDone()` in the snapshot — 0.54 ms.** `Path.isDone()` is two field reads and a
`size()`; 0.35 ms of it is cache misses chasing 2000 cold `Path` objects. Cache the flag on the entity and
refresh it when the path changes. The same argument applies to every per-entity pointer chase in the
snapshot, and it is the general reason the record tier is 3.7× cheaper: an array of records has no cold
object graph to chase.

**6. `setTicksFrozen` — 0.28 ms.** `LivingEntity.aiStep` calls `setTicksFrozen(max(0, i - 2))` every tick,
writing through `SynchedEntityData` for 2000 bugs that are never in powder snow and whose value is already
zero. Override it to return early when the value is unchanged, mirroring it in a field so the read is not
itself a synched-data lookup.

**7. Equipment-change detection — 0.26 ms.** `collectEquipmentChanges` loops all six `EquipmentSlot`
values every tick. Lithium's `equipment_tracking` is on and it still costs this. `detectEquipmentUpdates` is
private, so it needs a mixin.

Together, 2–8 are **5.4 ms of a 17.2 ms swarm, ~31%**, and they compose with the 25% from #1 because they
touch disjoint code.

### What this changes

The 48.8% is named, and it is three different problems:

1. **2.37 ms of goal re-evaluation** that a leader/follower or cohort scheme deletes outright, because a
   follower replaying a proven trajectory has no goals to select. This is the largest single item and the
   one most amenable to a structural fix rather than a micro-optimisation.
2. **1.97 ms of supporting-block search**, which is a cache: a bug that has not moved between blocks since
   last tick is standing on the same block it was standing on.
3. **0.62 ms of synched-data and attribute bookkeeping** for state that never changes on a glyphid.

None of these is a palette problem, though palettes are what two of them spend their time in. The reads are
a symptom of asking the world the same question every tick, for every bug, and the fixes are all upstream of
the read.

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
