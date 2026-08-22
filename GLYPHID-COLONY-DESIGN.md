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

---

## 10. Implemented: flight

Wings are a **modifier, not a caste**. A winged glyphid is a normal glyphid that can also fly: it still walks,
digs, climbs, takes orders and loses armour plates the same way, so a subclass would fork every one of those
behaviours to gain nothing. It is a capability bit alongside the existing subtype, and the glyphid lands to
fight.

### 10.1 Reusing the drone flight model is not a shortcut

Flight runs on `Multirotor`, the drone's flight model, because the fact that model is built around applies to
both: **the airframe can only push along its own up axis, so it has to tip over to go anywhere.** A flying
insect banks into a turn for the same reason a quadcopter does. The difference between the two is entirely in
the `Airframe` record — the bug leans harder (50° vs 40°), snaps into the lean nearly twice as fast, drags
more so it darts rather than glides, and descends faster than it climbs, which is the opposite of the
quadcopter. A multirotor diving into its own downwash stops flying; a bug folding its wings does not.

`Multirotor.step` is pure arithmetic over values with no world access, which is what made it reusable at all.

### 10.2 Wings are a performance feature

A flying glyphid does **no pathfinding whatsoever**. Measured on the same world, same staging, same count:

| 80 glyphids | walking | flying |
|---|---|---|
| mean tick | 1.728 ms | 0.686 ms |
| per entity | 21.6 µs | 8.6 µs |
| p95 / max | 5.140 / 16.406 ms | 0.813 / 1.084 ms |
| path search | 44% | 0% |

Two and a half times cheaper, and the spikes disappear: with no A* in the loop the cost is flat and
predictable, where a walking swarm's worst tick is twenty times its mean. A flying assault is the *cheap* one.

It also sidesteps the placement problem in §9.2 — a flight needs clearance rather than footing, so it crosses
a coastline intact where a walking warband leaves most of itself owed to the record.

### 10.3 Flight is what the T2 record already assumed

A warband has always moved in a straight line ignoring terrain, because there is no terrain loaded to move
around. Walkers only get away with that because nobody can see them do it; flyers actually do it. So the
abstract tier and the visible one finally agree.

Flying warbands travel three times as fast off-world, and the chance a colony fields them scales with tier —
at the default a tier-0 nest never does and a tier-4 one usually does, so wings are something the frontier has.

### 10.4 Three things that had to be suppressed in the air

Each of these is vanilla behaviour that is right on the ground and wrong off it:

- **Gravity.** `Multirotor` already subtracts it; leaving vanilla's on applies it twice and the glyphid never
  gets off the ground.
- **Climbing.** Vanilla clamps a climbing entity's motion to a crawl in every axis, so a glyphid that brushed
  a wall mid-flight would drop out of the sky.
- **A stale airborne flag.** Wings are for getting somewhere; anything else — orders cancelled, target
  acquired, dropped in water — lands, so nothing can be left hovering with its gravity switched off.

The destination height also had to become shared between the walking and flying goals. Only the walking goal
resolved it, so a flight's arrival test could never pass and the swarm hovered over its own target forever.

### 10.5 Bombing

Wings that only carried a glyphid to a wall would be a faster commute, not a new threat. A flight drops
ordnance on what it was sent to attack: acid now, blasts for the castes that get there. Bombing takes no
movement flag, so a glyphid bombs on the way past rather than stopping to aim.

Acid reuses the existing mist system — it is registered as a fluid because that is the key `MistEffects` looks
an effect up by, and there is deliberately no bucket, since the only thing that produces it is a bug. Unlike
the war gases it is aimed at a base rather than a person: it eats blocks. Glyphids are immune to it, a flight
that dissolved itself on its own bombing run being a strange kind of threat.

The payload is finite — four bombs each by default — so a raid is something a base survives and rebuilds from
rather than a bug parked overhead dissolving it forever.

Two things came out of measuring rather than reasoning:

- **Corrosion was a demolition charge.** Two blocks per twenty ticks cleared *every* block within the radius
  over a pool's life — 49 of 49 in the test. One per thirty pits the area instead: 7 blocks per bomb, so a
  raid eats its way in and lets the swarm through instead of erasing the building.
- **Splashing at the bomb's position put the pool in the air.** A bomb released from altitude covers several
  blocks per tick, so where it *was* when the hit resolved was three blocks above what it hit, and a direct
  strike on a target did nothing at all. Splashing at the contact point instead put it exactly on the target,
  which took it from 20 HP to dead in twelve seconds.

---

## 11. Measured: 300 attacking a base

Everything before this measured a swarm *travelling*. This measures one arriving: 300 glyphids sent at a
walled compound, pathing failing, climbing, digging and packing against it. Ryzen 9 7900X, dev server, one
mod, no players — so these are optimistic against real server hardware by roughly 1.5–2×.

| 300 glyphids | walking, closing | walking, in contact | flying, in contact |
|---|---|---|---|
| mean tick | 5.4–8.1 ms | 4.4 ms | 4.0 ms |
| per entity | 18–27 µs | 14.6 µs | 13.4 µs |
| p95 | 6.7–8.1 ms | 6.6 ms | 5.4 ms |
| max | *(see below)* | 14 ms | 7.3 ms |
| path search | 35–58% | 20% | 4.5% |
| movement | 26% | 50% | 59% |
| collision push | 3% | 9.3% | 15.5% |

**The dominant term changes with what the swarm is doing.** While it is closing, path search dominates. Once
it is in contact and packed, physics does — movement plus collision push is 59% walking and 74% flying. Those
are not the same problem and do not have the same fix.

### 11.1 Per-entity cost is flat; collision push is not

25.4 µs/entity at 100 and 26.9 µs at 300 — flat, so the swarm scales linearly overall. The exception is
`pushEntities`: 1.8% at 100, 9.3% at 300 walking, 15.5% at 300 flying (flyers converge tighter, so they pack
denser). That is the one superlinear term measured, and it is pure waste — a swarm has no reason to shove
itself apart.

### 11.2 The spikes were the benchmark, not the swarm

Early windows showed maxima of 99 ms, 108 ms and 343 ms against means of 2–8 ms. That is the debug
`materialise` command placing 300 entities inside a single tick, which the real path never does —
`materialisePerTick` streams them in four at a time for exactly this reason. Steady state maxima are 7–20 ms.
Worth recording because a mean of 8 ms with a max of 343 ms is the shape of a measurement artefact, and reading
it as swarm cost would have sent the next day's work in the wrong direction.

### 11.3 What this says about threading

The part that can move off-thread is the part that reads no world: path *planning* against a `TerrainField`
snapshot, steering, flight attitude (`Multirotor.step` is already pure), and the whole colony tier. That is
20–58% of a swarm on the move.

The part that cannot is `move()` and `pushEntities` — they read chunk block states and the entity list and
mutate position, which is what `WorldThread.assertOn` exists to enforce. That is 59–74% of a swarm in contact.

So off-threading is the answer for the approach and not for the fight. For the fight the lever is not *where*
the physics runs but *how many entities run it at all*, which is the T1/T2 sim tier: bodies only where
something is touching them, records everywhere else.

### 11.4 Melee, and the end of the superlinear term

Glyphids now attack anything alive worth biting, not only players: livestock, villagers and golems all count,
since a colony that walked past a farm to reach the player would not read as an infestation. Other monsters
are skipped so a swarm does not stop to brawl with the local zombies. That also makes melee measurable without
a client — verified by putting 20 cows in a 300-strong swarm and having 16 of them eaten in twelve seconds.

Searching for prey is a box query rather than a walk of the player list, so acquisition had to drop from every
tick to once a second, staggered by entity id. At three hundred glyphids, searching every tick would have cost
more than the rest of the swarm put together.

Entity push is now skipped between glyphids entirely, and the search for anything else to push runs every
fourth tick. A swarm has no reason to shove itself apart, and the shoving is what made a dense pack jitter.

| 300 glyphids, in contact | before | after |
|---|---|---|
| collision push, walking | 0.410 ms (9.3%) | **0.124 ms (2.8%)** |
| collision push, flying | 0.621 ms (15.5%) | **0.311 ms (6.9%)** |

That removes the only cost measured that grew faster than the swarm did. Side effect worth stating: it also
skips vanilla entity cramming, so a packed swarm no longer suffocates itself — which for a mod about packed
swarms is the behaviour we wanted anyway.

**Full combat cost, 300 glyphids fighting a herd:** 4.9 ms/tick walking (16.2 µs/entity), 4.5 ms flying
(15.1 µs). Melee is barely dearer than marching, and the split holds: movement 41–47%, path search 17–32%,
push under 7%.

### 11.5 Inside `move()`

With push fixed, movement became the largest line in the report and the one that cannot leave the server
thread — so "movement is 45%" is where the question starts. Vanilla's `move` is three unrelated things sharing
a name, and they were split apart (`MixinEntity`, plus a `checkInsideBlocks` override) to find out which one
the swarm actually pays for. 300 walking, in contact:

| inside movement (2.16 ms total) | ms | of tick |
|---|---|---|
| collision sweep | 1.36 | 25.9% |
| — block shapes | 0.74 | 14.1% |
| — entity overlap query | 0.62 | 11.9% |
| friction, fall, attributes, animation | 0.60 | 11.4% |
| fire scan (`getBlockStatesIfLoaded` + `noneMatch`) | 0.15 | 2.8% |
| blocks inside (hitbox volume walk) | 0.06 | 1.1% |

**The collision sweep is the answer**: 63% of movement, roughly half block shapes and half the entity query.
Everything else in `move` put together is smaller than the sweep alone. The two small lines that *sound*
expensive — walking every block the hitbox touches, and streaming those blocks again to ask about fire — cost
under 4% between them.

The entity half is pure waste for a glyphid. `getEntityCollisions` walks the entity sections over the swept
box, allocating a list per entity per tick, and filters on `canCollideWith` — which in 1.21.1 only `Boat` and
`Shulker` ever answer yes to. The list comes back empty every time. A/B'd on the same world in one server run
via `swarmbench entitycollisions off`:

| 300 walking, in contact | query on | query skipped |
|---|---|---|
| collision sweep | 1.36 / 1.50 ms | **0.73 / 0.74 ms** |
| movement | 2.16 / 2.29 ms | **1.55 / 1.54 ms** |
| tick | 5.24 / 5.10 ms | **3.78 / 3.94 ms** |
| worst tick in window | 10.7 / 28.4 ms | **6.2 / 15.0 ms** |

Behaviour is identical unless a glyphid walks into a boat or a shulker, because in both arms the returned list
is empty. The spike reduction is the giveaway that it was also garbage: 300 throwaway lists a tick.

The block half is not free money. It runs `collectColliders` a second time and `collideWithShapes` once per
candidate step height whenever a mob on the ground hits something horizontally — which for a swarm pressed
against a wall is every tick. Cutting it means giving up step-up, and glyphids need to climb terrain.

### 11.6 The melee goal was most of the tick

Benchmarking moved off cows and onto `EntityDebugDummy` — no AI, unkillable, and stationary unless told
otherwise. That last dial turned out to decide the answer.

Against **still** targets, path search is 6% of the tick and vanilla's `MeleeAttackGoal` looks fine. Against
targets that **move**, the same goal is 59%. The difference is structural, not tuning:

- `canUse()` runs a **full A\*** — every twenty ticks, per mob, purely to decide whether attacking is possible.
  Three hundred glyphids with a target pay fifteen searches a tick before any of them has moved.
- `tick()` repaths every four to ten ticks, re-triggered whenever the target shifts **one block**.
- Both call `createPath(entity, 0)`, which takes its range from follow range — 16 for a monster. Anything
  further fails, and a failed search is the expensive one: it expands the whole reachable set before giving up.

So a benchmark against stationary targets would have reported the swarm as fine and been wrong. Movement is a
parameter of the target, not a property of the test.

`GlyphidMeleeGoal` does no pathfinding in `canUse` at all, and shares `GlyphidPathingGoal` with the march —
hops with an explicit range, backoff keyed on displacement, chewing through what is in the way. It re-aims
only when the target has left the hop it was walking to, rather than on a block of drift.

**300 glyphids vs 60 targets drifting on an 8-block circle:**

| | vanilla melee + entity query | glyphid melee, query skipped |
|---|---|---|
| tick | 8.70 ms (29.0 µs/entity) | **4.50 ms (15.0 µs/entity)** |
| p95 | 11.98 ms | 8.64 ms |
| path search | 5.12 ms (58.8%) | 1.50 ms (33.3%) |
| hits landed | 525 | **574** |

Roughly half the tick, and it fights *better* — fewer glyphids stranded without a route, because a hop always
yields one and a glyphid that still cannot route digs instead. Against stationary targets the same pair is
4.16 ms → 2.88 ms (13.9 → 9.6 µs/entity).

Both arms are switchable at runtime (`swarmbench meleegoal vanilla|glyphid`, `swarmbench entitycollisions
on|off`) and read at spawn, so the comparison is one server run on one world rather than four.

**Where 300 now sits:** 9.6 µs/entity against still targets, 15.0 µs moving, 10.3 µs flying. 600 walking
against moving targets costs 8.25 ms — 13.8 µs/entity, so scaling is near enough linear.

The residual split landed too: `base tick` (fire, air, effects, freezing) is 0.36–0.84 ms and was most of what
the report used to leave unattributed, which fell from 0.69 ms to 0.18 ms.

### 11.7 What a path search actually spends itself on

"Path search is 28% of the tick" is still not an answer, so the search was split by what it does rather than
by who asked, and the things it does were counted as well as timed. 300 glyphids, 60 drifting targets:

| inside a path search | share |
|---|---|
| setup + chunk snapshot (`PathNavigationRegion`) | 1.4% |
| the A\* itself | 98.6% |
| — **node expansion** (`WalkNodeEvaluator.getNeighbors`) | **88.6%** |
| — heap + node bookkeeping | 9.9% |

209 µs per search · 92 nodes per search · **2 656 block reads per search** · 28.9 reads per node ·
**69.7 ns per block read**

**It is data retrieval, and specifically memory latency.** Node expansion is the part that reads the world:
for each candidate step it pulls block states out of the chunk snapshot and classifies them. 70 ns per read is
main-memory territory — an L1 hit is single-digit nanoseconds — so this is cache misses, walking a
`PalettedContainer` bit-unpack over a working set far larger than cache, in a scattered 3D pattern.

It is **not** allocation: the per-search setup that does the allocating is 1.4%, and on spike ticks allocation
rises only 1.5× while tick time rises 2.3×, so garbage is a passenger rather than the driver. It is **not**
the algorithm either: heap operations are under 10%.

That rules out two of the three plausible fixes. Making the A\* smarter or allocating less would buy almost
nothing. The lever is **fewer block reads** — which is what a flow field is: pay the terrain reads once for a
region and let every glyphid sample the result, instead of 13 500 reads a tick re-deriving the same terrain
300 times over.

### 11.8 What is in a spike

`p95` is the 95th-percentile tick over the window: one tick in twenty is at least this bad, so at 20 tps it is
about once a second. It matters more than the mean, because a server that averages 4 ms and stutters to 30 is
one players notice; the tick budget is 50 ms and only the tail ever approaches it.

Comparing the worst 5% of ticks against the rest says *which kind* of spike it is:

| worst 9 ticks vs the other 171 | worst | rest | ratio |
|---|---|---|---|
| tick ms | 8.1 | 3.5 | 2.3× |
| path searches | 27.8 | 3.9 | **7.1×** |
| nodes expanded | 2 349 | 371 | 6.3× |
| block reads | 51 877 | 11 542 | 4.5× |
| bytes allocated | 2.72 MB | 1.82 MB | 1.5× |

Searches per tick swing 7×, while nodes *per search* barely move (84 vs 95). So the spikes are **pile-ups —
more searches landing on one tick — not harder searches**. That is a scheduling problem, and scheduling
problems have cheap fixes.

Staggering the start of the goal was not enough: anything that makes a cohort decide to repath at once — a
warband arriving together, or every glyphid re-aiming at one target that moved — re-synchronises them. So a
glyphid may now only search in its own slot, one per entity per `REPATH_MIN` ticks, which bounds searches per
tick at population/20. A/B'd three times each on one world:

| | stagger off | stagger on |
|---|---|---|
| mean tick | 3.66 ms | 3.76 ms |
| p95 | 5.48 ms | 5.48 ms |
| **max** | **21.4 ms** | **10.3 ms** |
| searches on the worst tick | 38.3 | 15.1 |

Mean and p95 unchanged; the max halved, and the worst tick now sits exactly on the 300/20 = 15 bound. This is
a tail fix and nothing else — worth being precise about, because it would be easy to present it as a speedup
and it is not one.

The worst tick still exceeds the bound whenever glyphids are digging: breaking a block calls
`sendBlockUpdated`, which walks `navigatingMobs` and recomputes every nearby path **synchronously**, outside
any slot. That is the remaining unslotted burst.

### 11.9 Recording a JFR profile

`./gradlew runServer -Pjfr` adds `-XX:+DebugNonSafepoints`, which is the flag that makes the profile worth
reading: without it the JIT only emits stack-trace metadata at safepoints, so samples are attributed to the
nearest safepoint and hot leaf methods vanish into their callers. It does not start a recording — attach one
around the benchmark window instead, because a recording spanning startup is mostly classloading:

```
jcmd <pid> JFR.start name=run settings=profile jdk.ExecutionSample#period=1ms
# ... run the benchmark ...
jcmd <pid> JFR.dump name=run filename=run/jfr/run.jfr
jcmd <pid> JFR.stop name=run
```

`jcmd` must come from the JVM being attached to — the Gradle daemon runs Java 17 here while the game runs 21,
and a mismatched `jcmd` fails to attach. `readlink /proc/<pid>/exe` finds the right one.

**Sample the window, not the run.** The server thread is idle ~92% of wall time at these swarm sizes, so a
12-second window yields a few hundred usable samples and the sampler spends the rest on whatever background
thread happens to be runnable. A 45–60 second soak gives 3 500+ server-thread samples; anything shorter is not
worth interpreting.

The execution profile agrees with the counters. Leaf methods on the server thread, 300 in melee:

| | share of server-thread samples |
|---|---|
| `PalettedContainer.get` | 5.4% |
| `LevelChunk.getBlockState` | 3.9% |
| `SimpleBitStorage.get` | 2.5% |
| `PalettedContainer$Strategy.getIndex` | 1.5% |
| `BlockBehaviour$BlockStateBase.getBlock` | 1.6% |

~15% at the leaf in block-state reading alone, which is what §11.7 predicted from timings.

Allocation is diffuse: no steady-state site above 6%, spread across `Direction$Plane.iterator`,
`WalkNodeEvaluator.findAcceptedNode`, `Shapes.create`, `Vec3` arithmetic and fluid handling. It is not the
driver, which is the same conclusion the per-tick byte counter reached.

**A trap worth recording.** The first allocation profile read as 80% `EnchantmentHelper.isImmuneToDamage`.
That was the benchmark's own `kill @e` at the start of the window — *two* samples, with enough extrapolated
weight to swamp everything else. `jdk.ObjectAllocationSample` weights are extrapolations, so a single burst
can dominate a profile; exclude setup and re-read before believing an allocation hotspot.

### 11.10 Still open

- **Water stops a swarm.** `FloatGoal` outranks the march goal, so a glyphid that walks into a lake bobs there
  indefinitely. Placement refuses water, so a warband crossing a coastline materialises only on land — but the
  ones already walking can still drown their advance.
- **The hop is terrain-blind.** It aims at a heightmap sample, which over water or a cliff is a spot the
  pathfinder will not accept, and roughly 40% of a marching swarm is failing to make progress at any moment.
  This is what the flow field is for. Flight sidesteps it entirely, which is another argument for wings.
- **Glyphids have no renderer.** They are bound to a no-op one so the client will start at all; models are the
  flywheel port. Note that server-only testing hid this completely — a missing renderer is a client crash.
- `NestBuilder` is a hook: colonies materialise as data until the spawner block lands with the hive port.
- Evolution (§7) is still not implemented — tier is distance-derived only, with no global progression.
- **Ambient wildlife derails a march.** Melee outranks the march goal, so a column crossing a plains biome
  stops to eat it. Fine as behaviour, ruinous as a benchmark — march runs need `doMobSpawning false` and a
  swept arena, or they measure a brawl. Whether it wants a leash in gameplay is a design question, not a bug.
- **March speed measured 1.45 blocks/s** over a 170-block approach, against 2.07 measured earlier over a
  shorter one. The pathing logic is unchanged by the goal refactor (verified by inspection, not by A/B), so
  this is most likely terrain and column spread rather than a regression — but it is not proven.
