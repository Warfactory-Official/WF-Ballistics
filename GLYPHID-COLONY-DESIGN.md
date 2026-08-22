# Glyphid colony simulation — design

*"Biter" in conversation, `glyphid` in code (upstream ntm-next naming, kept so re-syncs against that
tree stay diffable).*

The goal: colonies, attacks, expansion and evolution keep running in unloaded chunks, so the world has
an ongoing threat rather than one that exists only where a player is standing. Scalable to a whole
world's worth of colonies, not just a swarm in front of you.

---

## 1. Two separate problems

The word "async" covers two things here, and they have opposite difficulty:

| | concern | difficulty |
|---|---|---|
| **off-world** | a glyphid in an unloaded chunk is a data record, not an `Entity` | representation |
| **off-thread** | the work happens on a worker, not the server thread | concurrency |

`SimDrone` solves both at once, which makes them look like one thing.

The load-bearing point: **the off-world tier is the easiest thing in the game to put on a worker,
because there is no world to touch.** All of `WorldThread.assertOff`/`assertOn` exists because loaded
AI reaches into chunk storage. A colony sim reads nothing but its own data. The usual difficulty is
inverted — the off-world tier is *easier* to parallelise than the loaded one.

## 2. Tiers

One record per glyphid does not scale to a world. `SimDrone` gets away with it because there are tens
of drones; a glyphid world has thousands, permanently, in the save file.

| tier | representation | when |
|---|---|---|
| T0 | real `Entity` | loaded chunk, player engaged |
| T1 | sim individual | near the action, not yet worth an entity |
| T2 | **warband** — one record, `count = N`, position, target | crossing the map |
| T3 | **colony** — one record: population, spawn budget, expansion timer | always |
| T4 | region — pressure, evolution | always |

A 500-strong assault crossing 2,000 blocks is *one* T2 record, not 500. A nest holding 50 glyphids is
one T3 record, not 50 idle ones. This is the tier that buys scalability; everything else is detail.

**Materialisation (T2 → T0) is the hard part.** `DroneCarrier`'s "one brain, two bodies" does not
help: that is an identity-preserving swap of *one* drone between representations. Turning one record
with `count = 40` into 40 entities is a different operation, and it is where systems like this break —
counts not conserved, glyphids spawning inside walls, a warband double-spending across two loaded
regions. This one operation gets designed and tested harder than anything else here.

## 3. Aggression input: machine density

Decided: aggression comes from **industry density, weighted by how dirty the industry is**. Combustion,
gas, mufflers, fusion and radiation score higher than clean production.

wfcore's radar already has most of the machinery:

- `RadarRegistryData` — per-level `SavedData`, packed `(x,z) -> value`, fed by block place/break
- `RadarConfig` — whitelist keyed by block registry name, per-block value, KubeJS-scriptable
- `RadarClustering` — DBSCAN over the registry, off-thread, cached 40 ticks, yielding `ClusterData`
  (centre, bounds, combined value, player population)

### 3.1 Blocker: the dependency runs the wrong way

wfcore depends on wfballistics (`compileOnly files('libs/wfballistics-1.1.6.jar')`). **wfballistics
therefore cannot depend on wfcore** — that is a cycle. The radar code has to be ported, not imported.

Resolution:

- **wfballistics owns** the machine registry and the pressure model (it is the mod the colony sim lives
  in, and it must work without wfcore installed).
- **wfcore feeds it.** wfballistics exposes a small registration API; wfcore, which already depends on
  it, reports GT machine values through it. Dependency direction preserved, and the mod that knows what
  a GT machine is worth is the one that says so.
- **Standalone fallback:** a config whitelist of the same shape as `RadarConfig`, plus the existing
  optional `compat/gt` path (already gated on `gtceu` via `WFMixinPlugin`).

### 3.2 Two defects not to copy

Both are tolerable for a radar and not for an aggression input:

1. **Packed `(x,z)` collides vertically.** `addMachine` overwrites the column and `removeMachine`
   deletes it, so breaking the top machine of a stack deregisters the one underneath. A radar wants a
   base footprint and does not care; a density coefficient silently undercounts tall bases, and
   stacking machines becomes a way to cheese aggression down. Needs accumulating columns or 3D keys.
2. **Placement events only.** `BlockEvent.EntityPlaceEvent` misses worldgen and structures, KubeJS,
   AE2/robot placement, `/setblock`, and every machine that existed before the feature was added. A
   large pre-existing base reads as zero threat. Needs a chunk-load backfill scan.

### 3.3 DBSCAN is the right tool for only half of this

`DBSCANClusterer` (commons-math3) is O(n²) without a spatial index, and commons-math3 is jarJar'd into
wfcore but absent from wfballistics. Radar gets away with it: on demand, cached 40 ticks. A colony sim
needs a *continuous* signal.

Split by what each is good at:

- **Pressure field — continuous.** Region grid, cell accumulates the weighted machine values inside it.
  O(1) to update on place/break, O(1) to query, trivial to persist, and it is already the spatial model
  the colony tier uses. Drives evolution, spawn budget, and which way colonies expand.
- **Clustering — on demand.** Port the radar's DBSCAN to pick *attack targets*: `ClusterData`'s centre,
  bounds and value are exactly what a warband needs to aim at. Runs when a warband is dispatched, not
  every tick. (A grid flood-fill over the pressure field is a dependency-free alternative worth
  measuring against it before adding commons-math3.)

## 4. Colony record is the source of truth

The decision that changes the hive port. HBM's model is block-first: `TileEntityGlyphidSpawner` ticks in
the world, `GlyphidHiveFeature` places structures. That cannot simulate an unloaded colony.

Inverted here: **the colony record simulates; blocks are its view in loaded chunks.** A spawner block
delegates to its colony record rather than running its own logic; when chunks load, a colony
materialises its nest; when they unload, the record carries on. This is why the hive port waits on this
document rather than the other way round.

## 5. Tick model

Per level, per tick, all cheap:

1. pressure decays (lazy: only on place/break plus a periodic sweep)
2. evolution integrates from total pressure
3. colonies accumulate spawn budget from local pressure × evolution
4. budget over threshold → emit a warband
5. warbands advance toward their target
6. warband reaches a loaded region → **materialise**

Steps 2–5 read only colony/warband/pressure data, so they are the easy off-thread case (§1). Step 6
touches the world: server thread only, `WorldThread.assertOn`.

## 6. Terrain for off-world travel

Off-world you cannot read blocks. `SimDrone`'s answer is to hold altitude and fly straight, letting
terrain matter only where it can be seen. Glyphids walk, so that does not transfer.

Proposal: **persist a coarse per-chunk summary — surface height, passability — the first time a chunk is
generated**, in `SavedData`. That yields a world-scale navigation graph built incrementally out of
chunks already paid for, and it is the same structure the loaded-tier flow field wants. One graph,
both tiers.

## 7. Open questions

- **Region cell size.** 32×32 chunks (512 blocks) is the starting guess; wants checking against typical
  GT base footprints.
- **Evolution scope** — one scalar per level (Factorio) or per region? Per region rewards spreading out
  and makes a quiet corner stay quiet; global is simpler and easier to communicate.
- **Do colonies expand into loaded chunks?** Placing nest blocks in a chunk a player is standing in is a
  different proposition from doing it out of sight.
- **Observability.** A simulation you cannot see is one you cannot tune. Wants a map/overlay command
  from the start — the precedents are `/wfballistics drone telemetry` and upstream's `GlyphidPathDebug`.

---

## 8. Implemented: colonies, expansion, attacks

### 8.1 Why nothing writes to unloaded chunks

The obvious way to expand out of sight is to write blocks straight into chunk data. Rejected: bypassing
the chunk cache races anything already resident in memory, and under C2ME the chunk pipeline is running
in parallel, so the failure mode is a corrupted region rather than a slow tick.

It is also unnecessary, because §4 already decided the colony record is the truth and blocks are only its
view. **A nest in an unloaded chunk needs no blocks — nothing can see it.** So expansion writes a record,
costs nothing, and the nest is built when the chunk loads on its own. `PendingChunkEdits` covers explicit
block deltas the same way. Zero chunks loaded, no corruption surface, same visible result.

A related consequence: a colony founded off-world cannot know the ground height there, so it does not
guess. `Colony.y` stays `Y_UNRESOLVED` and the surface is sampled at materialisation, when there is real
terrain to sample.

### 8.2 Distance scaling

Strength is a function of distance from world spawn: nothing inside `safeRadius`, ramping linearly to
`maxTier` at `fullStrengthDistance`. Tier multiplies population cap, growth rate, warband size and nest
radius, so the frontier is where the dangerous colonies are and pushing out is a decision with a price.

### 8.3 Build, then strike

Two independent numbers, which is what produces a rhythm rather than a trickle:

- **population** grows on its own to a cap — what an attack is *paid for* out of
- **aggression** accumulates from nearby industry — what decides *whether* to attack

A colony with numbers and no provocation sits still; one with provocation and no numbers keeps building
until it can afford to act. On strike it spends population, resets aggression, and emits one `Warband`
record — one object for however many glyphids, moving in a straight line, which is the tier that makes
this scale.

### 8.4 Expansion needs a stop, not a cooldown

Found by fast-forwarding rather than by reasoning: with only a cooldown, colonies reached **150,000
blocks from spawn**. Expansion is exponential — every colony founded can found more — and each generation
moved outward, so the frontier ran away. Three limits now: `maxColonies` per dimension, `frontierDistance`
beyond which nothing may be founded, and a `crowdingLimit` within `crowdingRadius` that turns expansion
into filling territory in rather than leapfrogging outward.

### 8.5 Provocation radius is deliberately not the cell size

First cut sampled "the neighbouring cells", which silently tied how far industry can be smelled to the
industry cell size — so tuning cells for performance would have quietly changed gameplay, and a colony 850
blocks from a factory was oblivious for no visible reason. It is now an explicit `provocationRadius` with
linear falloff.

Known granularity limit: falloff is measured to **cell centres**, not to machines, so the effective radius
carries up to about half a cell diagonal of error (~360 blocks at the 32-chunk default).

### 8.6 Verified

Growth, aggression accrual, strike, expansion and warband travel all match hand-computed predictions
(aggression 1.0/s at the test pressure; strike at exactly 100 s; warband of 8 for tier 0; population and
aggression correctly spent). Three dispatches and two arrivals observed over a fast-forwarded run, with a
stable 100-round cycle.

`fastforward` is load-bearing for this and had its own bug worth recording: it advanced warbands once per
colony round rather than once per tick, under-reporting travel by the tick interval and making every
strike look 20x slower than it is.

---

## 9. Implemented: materialisation

The T2 → T0 step: a warband record becomes glyphids when somebody is there to be attacked by them.

### 9.1 Conservation is structural, not checked

`Warband.count` is the only ledger and `WarbandMaterialiser.materialise` holds the only line that debits it,
by exactly the number of bodies that reached the world. A glyphid that could not be placed is still owed, so
it is still in the record. Conservation is not a check made afterwards; it is that nothing else can spend a
warband. The same property makes double-spending across two loaded regions impossible rather than guarded
against — there is one record, debited on the server thread, and no copy of it anywhere.

Verified: an 80-strong warband crossing a coastline materialised 0, then 72, then 4 at three different
positions as the terrain allowed, ending with 4 still owed and exactly 76 entities in the world.

### 9.2 Failing to place is normal

Nothing is placed without a loaded chunk, a real surface height, no fluid at the feet, and a clear bounding
box. All four failing is the expected case over water, and it costs nothing: the glyphid waits a tick. A
warband over open ocean places nobody and loses nobody.

This is also why materialisation is not part of `tickWarbands`: that is pure simulation and `fastForward`
runs it thousands of times over. Fast-forwarding the simulation must not spawn entities.

### 9.3 An arrived warband waits

Reaching the target no longer disbands it. The base it came for is simply offline, so it sits there until
somebody turns up, and only age retires it. Without this the attack would quietly evaporate on any base whose
owner was logged out — which is every base the off-world simulation exists to threaten.

### 9.4 Marching needed a goal that upstream does not have

Upstream issues movement under orders from its async decision layer, which this port replaced, so a glyphid
handed a destination stood in it. `GlyphidTaskMoveGoal` walks it there, in hops along the bearing rather than
straight at a destination thousands of blocks away, and bites through what it cannot walk around.

Three vanilla pathfinding behaviours shaped it, each found by measurement rather than by reading:

1. **`createPath(pos, accuracy)` caps the path at the mob's follow range** (16 for a monster). A 24-block hop
   came back as a stub that walked two blocks and stopped, and the swarm crawled at 0.26 blocks/s. Naming the
   range explicitly — the same answer upstream reaches — got it to 2.07 blocks/s, which is the mob's actual
   walk speed, so pathing stopped being the limiter at all.
2. **The pathfinder almost never returns null.** When the target is unreachable it hands back a partial route
   to the best node it found, so the expensive case is also the one that reads as success. Backing off
   repaths on a null result therefore did nothing, measured: 27.6 vs 27.9 µs/entity, inside the noise.
   Re-keying the backoff on *actual displacement* cut path search 39% and the whole tick 22%.
3. **Breaking a block synchronously re-runs A\* for every mob routed through it.** `sendBlockUpdated` walks
   `navigatingMobs` and recomputes inline, which couples every bite a digging swarm takes to every other
   member's pathfinding.

### 9.5 Cost, and what it argues for

Same world, same staging, 80 marching glyphids, 200-tick window:

| | no backoff | progress-keyed backoff |
|---|---|---|
| mean tick | 2.210 ms | 1.728 ms |
| per entity | 27.6 µs | 21.6 µs |
| path search | 1.246 ms (56%) | 0.765 ms (44%) |

**Path search is the dominant term for a marching swarm** — 44% after the fix, against roughly nothing in the
earlier idle-wandering baseline. That difference is the whole case for flow-field navigation: the earlier
measurement said pathfinding was cheap because the swarm was not going anywhere.

At 21.6 µs/entity a 300-strong assault costs about 6.5 ms/tick. Affordable, and still the largest single
system in the tick.

The profiler needed two corrections before any of this was trustworthy, both of them nesting errors that
showed up as negative residuals: path search happens *inside* path following when the navigator recomputes,
and inside digging via the block-update recompute above. Netting both out moved 76% of what looked like
digging cost into path search, where it belongs.

### 9.6 Still open

- **Water stops a swarm.** `FloatGoal` outranks the march goal, so a glyphid that walks into a lake bobs there
  indefinitely. Placement refuses water, so a warband crossing a coastline materialises only on land — but the
  ones already walking can still drown their advance.
- **The hop is terrain-blind.** It aims at a heightmap sample, which over water or a cliff is a spot the
  pathfinder will not accept, and roughly 40% of a marching swarm is failing to make progress at any moment.
  This is what the flow field is for.
- `NestBuilder` is a hook: colonies materialise as data until the spawner block lands with the hive port.
- Evolution (§7) is still not implemented — tier is distance-derived only, with no global progression.
- Extended targeting acquires players at 128 blocks, but `MeleeAttackGoal` paths within follow range, so a
  glyphid can see a target it cannot route to.
