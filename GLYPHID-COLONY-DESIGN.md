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
- ~~**Evolution scope** — one scalar per level (Factorio) or per region?~~ Answered per-level in §14, for
  the reason given here. The cost stands: a quiet corner does not stay quiet.
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

### 11.10 Correcting §11.7: it is two costs, not one

§11.7 divided node-expansion time by block reads, got 69.7 ns per read, and called it memory latency. That
number was an artefact. Varying the working set — same 300 glyphids, same goals, only the area they spread
over — shows it moving the *wrong way*:

| | spawn r=8, targets over 8 blocks | spawn r=100, targets over 180 blocks |
|---|---|---|
| block reads per node | 2.1 | 189.3 |
| ns per node expanded | 1 114 | 7 874 |
| "ns per read" | 522.6 | 41.6 |
| µs per search | 124 | 390 |
| tick | 3.00 ms | 4.76 ms |

Reads per node vary **90×** with locality, because vanilla's `PathTypeCache` absorbs them when the swarm keeps
searching the same ground. Dividing a large fixed cost by a tiny read count is what produced the inflated
figure. Solving the two regimes as `ns/node = fixed + reads × cost`:

- **~36 ns marginal cost per block read** — consistent across both, and genuinely memory-bound (~180 cycles).
- **~1 038 ns fixed cost per node expanded**, independent of reads: `PathTypeCache` hashing, node lookup and
  allocation, direction iteration, malus arithmetic.

Which one dominates depends entirely on how spread out the swarm is:

| | reads | fixed |
|---|---|---|
| tight (a swarm converging on one base) | 7% | **93%** |
| wide (a column crossing fresh terrain) | **87%** | 13% |

So the earlier conclusion — "the lever is fewer block reads" — was right for the march and wrong for the
assault, which is the case that matters most. In a converging swarm almost all of node expansion is the A*'s
own bookkeeping, and the terrain reads are already being cached away.

That does not change the destination, because a flow field removes both terms at once: no per-entity A* means
no per-node fixed cost, and terrain sampled once per region means no per-entity reads. It does change what to
expect from a partial fix — making block reads cheaper would buy ~7% of node expansion in the case we care
about, not 90%.

**On `VarHandle` and `Unsafe`.** Neither addresses either term. A block read costs ~180 cycles because it is
several dependent cache misses (chunk → section → packed `long[]` → palette → `BlockState`); `Unsafe` removes
a bounds check worth about one cycle and cannot make a miss faster. The fixed per-node cost is hash lookups
and allocation, which is a data-structure problem, not an access-primitive one. Both terms are fixed by
layout — a dense, cache-resident passability field — and layout is exactly what `TerrainField` is for.

Where `VarHandle` genuinely earns its place here is publication, not speed: when the flow field is computed on
a worker and read by the server thread, `setRelease`/`getAcquire` is the correct and cheap way to hand it
over. Worth noting too that `sun.misc.Unsafe`'s memory access is deprecated for removal (JEP 471), so betting
a long-lived mod on it is a bad trade even where it would help.

### 11.11 Charge, shared paths, and the PathTypeCache dead end

Two changes, both measured against Lithium 0.15.4 and Accelerated Recoiling (FFM backend) installed, because
that is the environment this ships into.

**Charge instead of pathfinding.** Inside 12 blocks with line of sight, a glyphid drives its move control
straight at the target and never touches the navigator. The move control still steps up and jumps on its own.

**Shared paths.** `GlyphidPathCache` quantises both search endpoints to a 4-block grid, so glyphids going the
same way collide on one key and copy each other's answer instead of each running the same A*. Paths are
copied rather than shared outright: a `Path` carries a mutable cursor, so two mobs on one instance would
advance each other along it.

| 300 glyphids | before | after |
|---|---|---|
| melee, vs 60 drifting targets | 3.40 ms | **3.00 ms** |
| — hits landed in the window | 228 | **373** |
| — path search | 24.1% | 11.4% |
| march, 300 under orders | 5.81 ms | **3.06 ms** |
| — path search | 3.03 ms (52%) | 0.48 ms (16%) |

The melee number is the interesting one: charging landed **63% more hits**, because a glyphid that stops
re-planning and just closes spends its time biting. Shared-path hit rate is 65% on the march and 24% in
melee, which is the expected shape — a warband shares a destination, a brawl does not.

**Current shipping cost at 300: 2.81 ms in melee and 2.81 ms marching, both 9.4 µs/entity.**

#### The PathTypeCache was a dead end, twice over

`PathTypeCache` is a direct-mapped table of 4096 entries, shared level-wide, so it looked like the obvious
capacity problem behind 2.1 reads per node when packed against 189 when spread. It is not. Sweeping the table
from 4096 entries to 262 144 (0.05 MB → 3.00 MB), spread out:

| entries | reads/node | ns/node | tick |
|---|---|---|---|
| 4 096 | 171.8 | 7 190 | 3.85 ms |
| 65 536 | 161.4 | 7 593 | 3.89 ms |
| 131 072 | 161.1 | 7 433 | 3.43 ms |
| 262 144 | 163.0 | 7 840 | 3.50 ms |

A 64× bigger table buys about 8% fewer reads, inside run-to-run noise on tick time. Counting the cache
directly says why:

- Spread out, at vanilla size: **210 path-type queries per node, 93% hit — and they explain 9% of block reads.**
- At 3 MB: 99% hit, explaining 1%.

So the cache was never the problem: it was already 93% effective, and **over 90% of the reads never go
through it at all.** They are the uncached direct `getBlockState` and collision-shape calls elsewhere in
`WalkNodeEvaluator` — floor levels, diagonal validity, fluid checks.

Two consequences. First, the resize is not worth an `@Overwrite` on a vanilla method for 8% of a term that is
itself ~13% of the tick; it stays behind `swarmbench pathcache` as a measuring tool, not a shipped fix.
Second, and more useful: **a `TerrainField` that cached only `PathType` would miss nine tenths of the reads.**
If it is ever built it has to carry floor height and collision, which is a much larger structure than §11.10
assumed — another reason the flow field is not the next thing to build.

Lithium 0.15.4 does not patch `PathTypeCache`, so there is no conflict there; it is now a dev-runtime
dependency precisely so that question gets answered before a patch ships rather than after.

### 11.12 Still open

- **The hop is terrain-blind.** It aims at a heightmap sample, which over a cliff is a spot the pathfinder will
  not accept, and roughly 40% of a marching swarm is failing to make progress at any moment. This is what the
  flow field is for. Flight sidesteps it entirely, which is another argument for wings.
- ~~**Glyphids have no renderer.**~~ Done, §17. Worth keeping the lesson: server-only testing hid this
  completely, because a missing renderer is a client crash and nothing headless ever loads one.
- `NestBuilder` is a hook: colonies materialise as data until the spawner block lands with the hive port. A
  scout now founds the colony *record* on its own (§13.3), so only block placement is still waiting.
- **Ambient wildlife derails a march.** Melee outranks the march goal, so a column crossing a plains biome
  stops to eat it. Fine as behaviour, ruinous as a benchmark — march runs need `doMobSpawning false` and a
  swept arena, or they measure a brawl. Whether it wants a leash in gameplay is a design question, not a bug.
  §16.2: `march2.sh` now sweeps for this, and six animals left over from worldgen were costing 45% of a march
  run's path search.
- **March speed measured 1.45 blocks/s** over a 170-block approach, against 2.07 measured earlier over a
  shorter one. The pathing logic is unchanged by the goal refactor (verified by inspection, not by A/B), so
  this is most likely terrain and column spread rather than a regression — but it is not proven.

---

## 12. Implemented: swimming

Water was listed above as stopping a swarm, blamed on `FloatGoal` outranking the march goal. **That diagnosis
was wrong.** `FloatGoal` carries only `Goal.Flag.JUMP`, so it never blocked a movement goal at all — and it is
in fact load-bearing for the fix, because its constructor is what calls `navigation.setCanFloat(true)`, and
without that flag `WalkNodeEvaluator` refuses every water cell outright whatever its cost.

The real cause was two independent numbers, both vanilla defaults:

**`PathType.WATER` carries a malus of 8**, against 0 for open ground. Costs are per cell, so an eight-block
pond costs more to swim than a sixty-block shoreline detour costs to walk. The search spends itself along the
bank and returns a partial path, which — per §11 — reads as success. `Drowned` sets this to 0 for exactly this
reason; glyphids now do too, along with `WATER_BORDER`, which is the air cell on the bank that a glyphid has
to step through to get in.

**`LivingEntity.travel` thrusts through water at a flat `0.02`**, against a walking speed of `0.25`, unless
`Attributes.WATER_MOVEMENT_EFFICIENCY` is set — which defaults to 0 on everything. It is now 1.0.

### 12.1 Measured

One glyphid under march orders, 40 blocks of open water between it and its destination, same lake both arms:

| water efficiency | crossing time | speed |
|---|---|---|
| 1.0 (shipping) | 20 s | 2.0 blocks/s — its marching pace |
| 0 (vanilla) | 85 s | 0.47 blocks/s |

Four-fold, not the twelve-fold the two constants imply: terminal speed is thrust over drag and the attribute
raises both. Worth stating because the naive reading of the vanilla code overstates the fix by 3×.

Ten glyphids crossed the same lake without a single loss, and **air never dropped below full** — they swim on
the surface, so `MAX_AIR` (600 ticks, double vanilla) only ever matters to one that cannot surface. That is
deliberate: flooding a pit on top of a glyphid should still drown it.

No pathfinding regression: 300 in melee measured 2.506 ms/tick against a 2.813 ms baseline. The arms are not
strictly comparable — this run issued far fewer searches — so this is "no regression", not an improvement.

---

## 13. Implemented: the castes

Eight subclasses on top of the grunt, adapted from ntm-next. `GlyphidCaste` is the table all of it hangs off:
one enum carrying the entity type, the stat bundle, a spawn weight and an evolution gate, so the materialiser,
the bench and the renderer registration all name a caste instead of holding their own list of types. That last
one matters more than it looks — an unregistered renderer is a client crash that server-side testing cannot
see, so the client binds renderers by iterating the table rather than by nine hand-written lines.

### 13.1 What the port had to substitute

Upstream leans on NTM entities that were never ported. Substitutions, all of them behaviour-preserving:

| upstream | here |
|---|---|
| `EntityAcidBomb` | the existing `EntityGlyphidBomb` (acid pool or blast, already built for the flying castes) |
| `EntityChemical` spray | `MistEntity` puffs walked out along the line to the target |
| `EntityRubble` | vanilla `FallingBlockEntity`, which also lands as a block — the digger leaves the ground rearranged |
| `ExplosionVNT` | `ExplosionAEF` |
| pheromone mist (Brenda) | a rally: a `TASK_FOLLOW` waypoint on the corpse plus `communicate`. There is no pheromone system, but there is an orders system, and the pheromone's *function* was "everything comes here" |

### 13.2 Three deliberate divergences

- **The blaster fires four bombs, not ten.** Ten is survivable when each is an acid puddle and is not when each
  is a live `ExplosionAEF` charge, and a warband fields several blasters at once.
- **Lead is divided through.** Every upstream caste samples target displacement over a 20-tick window and then
  multiplies it by a 20- or 60-tick lead, over-leading by a factor of twenty, and relies on a sanity check to
  throw the result away again. `GlyphidBallistics.lead` divides by the sample interval, so the lead is the
  velocity it claims to be.
- **The nuclear caste's parting buff is applied to its neighbours.** Upstream loops over nearby glyphids and
  then calls `addEffect` on the corpse, which does nothing.

### 13.3 The scout is the expansion mechanic

Everything else is an attacker; the scout is how a colony becomes two. It walks to a site far enough from home
to be worth having and calls `ColonyManager.settle`, which is the only path in the mod that creates a colony
from *inside* the world rather than from the simulation. It is spent doing so — which is what keeps expansion
costed, since a colony that wants to spread has to raise and lose a bug per nest.

---

## 14. Implemented: evolution

§7 asked whether evolution should be one scalar per level or per region. This is the per-level answer, for the
reason the question gave: it is the one a player can be told about. The cost is real and worth naming — a quiet
corner of the world does not stay quiet. Region scoping would fix that and is a strictly larger change, because
every colony would then need to know which region it is in.

Two inputs. **Time** is a floor, so an untouched world still hardens; it is deliberately slow (roughly half
evolved after fifty hours). **Industry** is `IndustryApi.totalPressure`, the same signal that provokes
individual colonies, summed across the world — this is the term that makes evolution a consequence of what the
player built.

Asymptotic rather than linear: each step closes a fixed share of the *remaining* gap, so the last stretch costs
far more industry than the first, and the top of the caste table is something a world grows into.

What it buys is narrow on purpose — which castes may be fielded, and a modest multiplier on growth and warband
size. It does **not** touch tier. Distance decides where the hard nests are; evolution decides what a hard nest
has learned to build. Measured shape, via `/wfballistics colony evolution`:

```
0.00   grunt 98%, scout 2%
0.30   grunt 75%, scout 4%, bombardier 12%, brawler 9%
0.60   grunt 55%, scout 4%, bombardier 15%, brawler 13%, digger 9%, blaster 4%
1.00   grunt 37%, scout 4%, bombardier 15%, brawler 15%, digger 11%, blaster 7%,
       behemoth 7%, nuclear 2%, brenda 2%
```

A caste enters at a quarter of its full weight rather than at zero. Ramping from zero looks tidier and is a
bug: the scout is gated at 0.0, so it would never appear in a fresh world, and a colony that cannot field
scouts cannot expand — the simulation stalls before it starts.

The two factors are an untuned starting point. They are config, and the command sets the scalar outright,
because evolution takes tens of hours to move on its own and that is not a test.

---

## 15. Implemented: glyphid armour as DT/DR

`EntityGlyphid.getCurrentDTDR` had existed since the first port and nothing called it. It is now wired through
`DamageResistanceHandler` — but not as a registered profile, because a profile is keyed by entity type and
damage category and the glyphid breaks both assumptions: its threshold comes from how many chitin plates it
still has, so two glyphids of one type resist differently and the same glyphid resists differently a second
later; and it treats a laser and an electrical arc differently despite both being `energy`.

So `DynamicResistance` asks the entity instead, and what it returns is summed with any static profile the
entity also carries. A landed hit may knock a plate off, on the raw amount rather than the mitigated one — a
hit big enough to crack chitin cracks it whether or not the chitin then absorbed the rest.

Verified against the formula rather than by eye. A grunt has `thresholdMultForArmor` 1.0 and `resistanceMult`
0.1, so a raw 6 should land as `(6 − plates/5) × 0.9`:

| plates | predicted | measured |
|---|---|---|
| 5 | 4.50 | 4.50 |
| 4 | 4.68 | 4.68 |

That degradation is the whole point of the model: a fresh glyphid shrugs off small-arms fire and the same
glyphid, worn down, does not.

---

## 16. Implemented: the brain, and why it did not go off-thread

All glyphid behaviour lived inside vanilla `Goal`s bolted to an `Entity`. That is fine for T0 and fatal for
everything above it: a warband record has no goal selector, so §2's tier model could not be built at all until
the decisions came out of the goals. That extraction is what this phase is.

The split is one rule: **a decision goes in `GlyphidBrain`, a world read or write goes in `GlyphidBody`.**

| | reads the world | where it lives |
|---|---|---|
| which errand — bite or march | no | `GlyphidBrain.errand` |
| when to repath, and the backoff | no | `GlyphidBrain.repathDue`, `settle` |
| where the next hop goes | no | `GlyphidBrain.advance` |
| charge instead of path | no | `GlyphidBrain.melee` |
| when a bite is off cooldown | no | `GlyphidBrain.melee` |
| the A*, the heightmap sample, the raycast, the bite | **yes** | `GlyphidBody` |

`GlyphidBrain` imports no `Level`, no `Entity` and no `Path`, which is what makes that table checkable rather
than aspirational. Per-bug memory that used to be fields on the goals — repath timer, backoff, stuck counter,
bite cooldown — is now a `GlyphidMind` the *carrier* owns, so a record can own one too.

Arbitration moved with it. Biting used to outrank marching because the melee goal sat at priority 3 and the
march goal at 4; it is now `GlyphidBrain.errand`, which is the same rule written somewhere that does not need
a goal selector to read it. `GlyphidBrainGoal` is what is left: it holds `MOVE` so idle wandering cannot run
during a march, and releases it when there is nowhere to be.

### 16.1 Measured

Both arms on the same world, three runs each, 300 glyphids.

| 300 glyphids | goals | brain |
|---|---|---|
| melee, vs 60 drifting dummies | 4.45 ms (4.34–4.62) | **3.81 ms (3.72–3.98)** |
| — path searches per tick | 5.0 | **3.7** |
| — hits landed in the window | 964 | **1007** |
| march, 300 under orders | 4.10 ms (3.54–4.66) | 3.89 ms (3.53–4.30) |
| — path searches per tick | 2.9 | **2.2** |

Melee is **14% faster** and the arms do not overlap. The march is inside run-to-run noise and is reported as
no change, not as the 5% the means suggest — but its search rate falls by the same quarter melee's does, and
that is the stable signal in both.

The likely mechanism, stated as a hypothesis rather than a measurement: the old melee goal reset its backoff
every time `canUse` flickered, so a glyphid that kept failing to reach a target never accumulated one and kept
paying full price for searches. One errand held across a whole engagement lets the backoff actually
accumulate. More hits landing on fewer searches is the same shape the charge change produced in §11.11 — a
glyphid that stops re-planning spends its time biting.

Behaviour was checked separately from cost, because a cheaper swarm that has stopped doing anything is also
cheap: 4 glyphids sealed in a one-block stone shell ate 4 blocks out of the wall between them and their orders
in 30 seconds; a column ordered onto the spot it is standing on drops to `TASK_IDLE` and resumes wandering.

### 16.2 The march benchmark was measuring nothing

`march.sh` sends a warband 2,200 blocks and reads back `0.000 ms/tick`. That is not a fast swarm, it is the
unticked-chunk artefact: the column walks out of the forceloaded box within seconds and stops being simulated.
Worse, its glyphids survive the next run's `kill`, so successive runs report 285, then 578, then 870 entities
against an unchanged 0.000 ms.

`march2.sh` replaces it: 300 glyphids ordered across a 136-block diagonal that stays inside the loaded box,
and it sweeps ambient wildlife first. Without the sweep, six animals left over from worldgen pulled 45% of the
"march" benchmark's path search into melee.

### 16.3 Upstream's async layer is a net loss at this scale, and was not ported

ntm-next ships `com.hbm.entity.mob.ai.async` — `GlyphidBrain`, `GlyphidSnapshot`, `GlyphidDecision`,
`GlyphidForkJoinPool`. It looks like exactly this phase already done. Reading it, three things rule it out:

- **`GlyphidBlockView.captureCorridor` copies up to 4,096 block states into a hash map, on the world thread,
  per glyphid, per submission.** A 16-block hop is ~17 steps of a 3×3×3 box, so ~459 reads plus an insert
  each. At §11.10's ~36 ns marginal read that is ≳20 µs per glyphid against a whole-entity budget of 9.4 µs
  per tick. It would roughly triple the cost of the swarm.
- **One `CompletableFuture` per glyphid per tick** (`DEFAULT_RECOMPUTE_INTERVAL_TICKS = 1`), so 300 submissions
  a tick where the drone side dispatches one job per squad.
- **The A* does not move off-thread at all.** The decision is `PathTo(x, y, z, maxDist)` and the world thread
  still runs `createPath`. What crosses the boundary is a raycast and some arithmetic — cheaper than the
  capture that feeds it.

So the shape was taken and the mechanism was not.

### 16.4 Why the brain still runs on the world thread

§11.3 put 20–58% of a swarm off-threadable, but that was measured before charge and shared paths cut path
search from 52% of the march to 16%. On the numbers above the off-threadable share is the search, and moving
it needs a terrain snapshot the workers can read — which §11.11 established has to carry floor height and
collision, not just `PathType`, because over 90% of the reads never touch the type cache. That is Phase 3's
data structure, and it is shelved.

There is also no batch driver yet — no `GlyphidAiScheduler` answering to `DroneAiScheduler`. With one
implementation of `GlyphidCarrier` and nothing to run in parallel it would be indirection with a single
caller. What builds it is the second carrier: the sim tier of §2, which has no goal selector to be driven by
and wants one level-wide pass. Two things would then be worth putting in it that a per-entity goal cannot do —
sharing one target-acquisition query across a cluster instead of 15 box queries a tick, and dispatching
searches against a shared terrain field.

The boundary is what matters now, and it is drawn. Crossing it is a scheduling change rather than a rewrite.

---

## 17. Implemented: rendering

Nine castes over **one mesh and nine skins**. `glyphid.obj` is 217 vertices and 287 triangles in 19 named
parts; the rig poses those parts into 27 instance slots.

Flywheel instancing, on the `DroneVisual` pattern — `AbstractEntityVisual` driving `TransformedInstance`s.
Not a per-entity `EntityRenderer` walking geometry into a `VertexConsumer`, which is what upstream's
`HFRWavefrontObject` is built for and is the thing that does not survive three hundred bugs. Only the obj
*reader* was ported, geometry only, emitting one flywheel `Mesh` per named part.

The existing `neoforge:obj` + `PartialModel` pipeline the missiles and drones use was deliberately not
reused, for two reasons: it bakes a file down to one flat `BakedModel` with no way to address a part, and
the walk cycle needs all 19 posed against each other; and it resolves textures onto the block atlas, so
nine caste skins would have meant 9 × 19 model jsons and entity skins stitched into the block atlas.

Every joint is a function of one scalar — walk phase, or bite phase — so the poses are tabled ahead of time
and drawing a glyphid is 27 matrix multiplies and no trigonometry. The six legs are two meshes drawn three
times each and share a `Model`, so they share an instancer and one draw call; all nine castes share one
upload of the geometry, because flywheel pools by `Mesh` identity.

Per-caste skin and scale, armour plates that disappear as they are shot off, jaws and head tilt off
`getAttackAnim`, the airborne lean off the same `getRoll`/`getPitch` the drone flight model publishes, and
an infestation decal for infected bugs built lazily so uninfected ones pay nothing.

**Measured** (RX 7900 XT, small window, vsync off, three alternating rounds per arm; baseline is the same
300 entities with the flywheel backend off, so entity ticking and tracking are identical in both arms):

| scene | baseline | with visuals | delta |
|---|---|---|---|
| 300 on screen at ~40 m | 1.302 ms | 1.696 ms | +0.394 ms (1.31 µs each) |
| 300, camera inside the swarm | 1.243 ms | 1.625 ms | +0.382 ms (805 → 615 fps) |
| 900 | 1.332 ms | 1.922 ms | +0.590 ms — sub-linear |

Sub-linear at 900 is the shape instancing is supposed to have. The window was small, so fill rate is
under-weighted and these numbers are the CPU-side per-instance cost — which is the part that scales with
population, and the part that mattered.

Two consequences worth knowing. `skipVanillaRender` is true, so with the flywheel backend off glyphids draw
nothing at all — the same trade the drones already make, and it also means no shadow (correct: the original
set `shadowOpaque = 0`) and no F3+B hitbox. And `EntityGlyphidNuclear.fuse` is private and unsynced, so the
death swell starts from the first tick the visual sees the bug dying rather than from the real fuse; syncing
it would cost a data watcher for a cosmetic detail, so it has not been.

Which skin a caste wears now lives on `GlyphidCaste` alongside its gate and its weight, rather than being
rebuilt from the caste's name inside the renderer. A `ResourceLocation` is not a client class, so the table
stays server-safe — the same trade `MissileModels` makes — and a caste whose texture is not named after it
can no longer silently draw the wrong one.

---

## 18. Implemented: digging priced on hardness

### 18.1 The bug: nothing ever consulted the material

Glyphids ate bedrock. Not because there was no gate — there was one, on explosion resistance — but because the
number it compared against was never the block's.

`BlockAllocatorStandard.blockResistance` asked the exploder entity:

```java
explosion.exploder.getBlockExplosionResistance(compat, level, pos, state, fluid, power)
```

`Entity.getBlockExplosionResistance` returns its last argument unchanged. It is an *adjustment* hook — vanilla
computes `max(block, fluid)` resistance and passes **that** in, so an entity can raise or lower it. Passing the
remaining blast power instead means it hands the power straight back, and the comparison becomes
`ceiling < power`, which for a glyphid's blast size of 1–5 against a ceiling of 100 is always false. Every
block in reach went, whatever it was made of.

Confined to glyphids by luck rather than design: every warhead uses the `ExplosionAEF` constructor that leaves
`exploder` null, which takes the correct branch. Only the glyphid dig, the nuclear caste's death blast and
glyphid bombs pass an exploder.

### 18.2 Hardness, not explosion resistance

The gate now reads `getDestroySpeed`. The two numbers disagree exactly where it matters: obsidian is 50
hardness against 1,200 resistance, and a reinforced door is a nuisance to mine and barely resists a blast at
all. A glyphid is biting, not detonating — and hardness is also the number a player already has an intuition
for, because it is the one their pickaxe answers to.

Two distinct ways to be un-chewable, and the difference is the whole point of the mechanic:

- **Above the caste's ceiling** — this caste cannot open it, a bigger one could. This is what makes the
  material a wall is built from a real decision.
- **Negative hardness** — bedrock, barriers, portal frames. Unbreakable to everything, at any evolution.

Digging is a *rate*, not a bite: `ticks = hardness / digStrength × 20`, banked against one block at a time.

| caste | hardness/s | ceiling | stone (1.5) | iron (5) | obsidian (50) |
|---|---|---|---|---|---|
| scout | 0 | — | never | never | never |
| grunt, bombardier | 1.25 | 10 | 1.2 s | 4 s | never |
| brawler, blaster, nuclear | 2 | 25 | 0.8 s | 2.5 s | never |
| behemoth | 4 | 50 | 0.4 s | 1.3 s | 12.5 s |
| brenda | 5 | 60 | 0.3 s | 1 s | 10 s |
| digger | 6.25 | 60 | 0.2 s | 0.8 s | 8 s |

**Measured**, one glyphid sealed in a one-block shell, timing until it got out:

| | grunt | digger |
|---|---|---|
| stone | **20 s** | — |
| iron block | **36 s** | — |
| obsidian | **sealed** (45 s) | **59 s** |
| bedrock | **sealed** (30 s) | — |

Stone against iron is 20 s against 36 s where the hardness ratio is 3.3× — the fixed cost in both is the same
few seconds of failing to path before a glyphid gives up and starts eating.

### 18.3 A breach is a doorway, not a hole

The first working version chewed one block and then stood there. Two things had to be true that were not:

- **One block is not a doorway.** A glyphid is 1.5 blocks wide and a chewed block leaves a hole 1 wide, so it
  ate through and still could not fit. The old blast-shaped bite removed a whole sphere, which is why nobody
  had noticed. A breach now opens the block walked into, the one above it, and their neighbours to either
  side along the wall — widening across the face, because the axis a wall is thin on is the one being
  travelled down.
- **Do not re-earn the stuck timer per block.** Sixty ticks of failing to make progress is the right price for
  deciding to start eating and the wrong price for continuing, and paying it between every block turned a
  two-block wall into a minute of standing still. Chewing now runs straight on to the next block of the
  breach. It is self-limiting: the reach only looks a few blocks ahead, so an open way finds nothing.

Widening is anchored on the block first walked into rather than on whichever was chewed last. Stepping outward
from the last one lets a glyphid tunnel sideways along a wall forever, always one block from finishing.

### 18.4 The cracks

The block a glyphid is working shows vanilla's breaking overlay, advancing 0–9 with the chew, and breaks with
the ordinary particles and sound (`Level.destroyBlock` with drops off). Purely cosmetic, but it is what makes
a swarm at a wall legible: you can see which block is going and roughly when.

The packet is sent only when the stage actually changes — at three hundred glyphids, one per tick per bug to
every player in range is not a thing to do for decoration. It is cleared on any tick that is not a chew and on
the glyphid dying, because a client holds an abandoned overlay for 400 ticks and half-eaten blocks that
nothing is eating look like a bug. `glyphid.diggingOverlay` turns it off.

## 19. Implemented: squads

A swarm that sends every bug at the nearest player is one problem, solved once. Split three ways — one squad
on each defender, one eating the reactor — it is three problems at once, and the defenders have to divide too.

**Equal by power, not by count.** Forty bugs split into two twenties puts every behemoth in one half and hands
the other half a rout. Each caste carries a `power` on its stat bundle (a grunt 1, a behemoth 10, a brenda 20),
explicit rather than derived from health and damage because the castes that matter most to a split are the ones
whose worth is not their statline. The partition is greedy longest-processing-time: strongest first, each onto
whichever squad is currently weakest. One sort, and within a few percent of even at these sizes.

**Objectives**, best first, capped at four squads and at least four bugs each:

| kind | from |
|---|---|
| `PLAYER` | each survival player in range, nearest first — two squads never take the same one |
| `MACHINES` | `IndustryApi.nearestCluster`, the same pressure field the colony tier aims warbands with |
| `BREACH` | the wall between the swarm and its primary objective |
| `RALLY` | where the warband was going. Always last, so the list is never empty |

A squad sent after a player prefers that player in `findTargetCandidate`. Without that, the split decides where
four squads walk and then all four converge on whoever is nearest the moment they arrive — the exact behaviour
it exists to prevent.

**Stateless.** There is no squad object living between reassignments; the whole split is recomputed every 100
ticks from what is standing there. Objectives die, players move, bugs are killed — a roster maintained through
all of that is a lifecycle to get wrong, and recomputing costs one pass. Membership stays stable anyway because
the input is sorted deterministically.

**Measured**, `/wfballistics colony squads`, 30 grunts and 4 behemoths against a detected base:

```
squad 0: 9 bugs,  power 27 -> machines (256, 81, 256)
squad 1: 13 bugs, power 26 -> breach   (230, 73, 230)
squad 2: 15 bugs, power 26 -> rally    (0, 89, 0)
```

Nine bugs against fifteen, and the power is 27 against 26. That is the split working: the heavies are spread,
not the bodies.

### 19.1 Three things that had to be got wrong first

- **Group by home, quantised.** Bugs are grouped by where they came from rather than by where they are, so one
  colony's attack force divides itself and two colonies that happened to meet do not. But each bug records its
  own landing spot, so keying on the exact block gave forty hosts of one. Quantised to a 64-block cell.
- **Median, not mean.** Objectives are looked for around the swarm's position — and once a swarm has been split
  it is walking two ways at once, so the mean is a point in the empty ground between them. Every objective was
  then discovered relative to somewhere no glyphid was. The median sits inside whichever group is larger.
- **Do not filter a breach by distance.** The first version ignored obstructions nearer than four blocks, on
  the theory that anything closer was the swarm's own ground. It threw the objective away in precisely the case
  it exists for: a swarm already pressed against the wall it needs to open, which is where all of them end up.
  The test is now whether the ray struck a *side* face with a solid block above it — vertical and at least two
  tall, which is a wall and not a step.

Verified: 24 grunts sealed in obsidian with a base outside split into machines / breach / rally at power 8/8/8,
with the breach landing on the wall block. `PLAYER` is the one objective not confirmed at runtime — an
RCON-driven headless server has no players to divide over.

---

## 20. Measured: the walled base

The scenario the digging and the squads were built for, and the one the tick was expected to fail on: three
hundred glyphids in front of a fortified compound whose interior is not reachable at all until a wall comes
down. A destination that cannot be pathed to is the pathological case for path search — every failed search
costs a full node budget and returns nothing — so the question was whether a stalled assault is affordable.

The arena is flat by construction: a 77x65 plate, a 25x25 compound of one material four blocks tall with no
roof, 81 furnaces inside as the thing worth attacking, and the swarm spawned 28 blocks off the west face.
Flattened deliberately — natural terrain puts a climbable slope against one face and the arm then measures
the hill rather than the wall. Driven by `wall.sh` over RCON.

| arm | first breach | inside at the end | ms/tick | p95 | path search |
|---|---|---|---|---|---|
| stone, 300 grunts | t+16s | 293 of 300 | 2.419 | 3.339 | 16.0%, 1.5/tick |
| obsidian, 300 grunts | never, 60s | 0 | 2.338 | 3.536 | 17.4%, 0.7/tick |
| obsidian, 280 grunts + 20 diggers | t+26s | 296 of 300 | 2.317 | 3.240 | 13.0%, 1.4/tick |

Against the open-field baselines of §16.1 — 3.89 ms marching and 3.81 ms in melee, both at 300 — **an assault
on a walled base is the cheapest thing a swarm of this size does.** A bug that is chewing is not searching,
and a bug packed against a wall behind two hundred others has nowhere to search to. The stalled arm is the
one to read: sixty seconds of three hundred glyphids wanting something they cannot reach costs 0.7 searches a
tick, because the brain's backoff doubles the retry interval out to `REPATH_MAX` and leaves it there. The
failure this scenario was written to find is not there.

The mixed arm is the caste ceiling doing what it was for. Twenty diggers — 7% of the swarm — opened a wall
that the other 280 could not scratch, and 296 of 300 were inside within twenty seconds of the first hole. A
base wall is not a yes-or-no defence against a swarm; it is a filter on which castes matter, and the answer
to obsidian is to make the diggers not arrive.

### 20.1 Three defects, none of them in the tick

Running the thing surfaced three bugs that the unit-sized tests could not, and all three were silent.

- **A cluster centre is not a place.** The industry field is 512 blocks to a cell, which is the right
  resolution for deciding which region a colony resents and far too coarse to walk to. A cluster centre is the
  value-weighted average of its cells, so the compound — straddling the cell boundary at the origin — reported
  a base centre 38 blocks outside its own wall, on a hillside. The squad went there. The earlier §19 check had
  looked correct only because that base sat at (256, 256), which is exactly a cell centre. Fixed by asking the
  world instead once the world has an answer: `IndustryApi.pressingMachine` scans block entities over loaded
  chunks, and the cluster centre is what is left when nothing is loaded — good enough to point a walk in the
  right direction, and replaced by a real block by the time they arrive. See §20.2 for what it ranks by.
- **The rally objective ate itself.** `RALLY` is meant to be where the warband was going in the first place,
  and it was read back off the bug's task — which a squad assignment had already overwritten. One
  reassignment later the fallback was a copy of the last objective, and the "original" destination walked
  across the map at one objective per five seconds. The pre-squad destination is now remembered on the entity
  the first time it is overwritten, and saved, because it is the warband's orders rather than squad state.
- **Bases vanish on restart.** The pressure field is saved data and comes back with the world; the cluster
  list is derived and does not. Nothing recomputed it: the scan is event-driven off placements and first-time
  chunk sweeps, and an established base triggers neither on a restart, since every chunk of it has been swept
  before. So a restarted server reported no bases at all until somebody placed a machine, and everything
  targeting one quietly did nothing. One `LevelEvent.Load` handler.

The last of those is the one worth remembering. Every arm of §19 was run inside a single session, and a
restart is not something a benchmark does.

### 20.2 A target is picked by worth, not by distance

Aiming at a *machine* rather than a cell centre is only half the answer. Standing inside a base, the nearest
machine is whichever one a glyphid happened to walk past, and a swarm that eats the first furnace it trips
over is not attacking a factory — it is grazing.

The whitelist is already a ranking of how much a block provokes, and it spans nearly three orders of
magnitude: a fusion reactor is 400, a large combustion engine 120, a furnace 1. That ordering is the answer to
"what is worth killing", so a candidate scores `value * (1 - distance/radius)` — the same linear falloff
§8.5 weighs a region's pressure by, at block resolution instead of cell resolution. A reactor anywhere in range outranks every furnace in the building; two
similar machines are decided by which is nearer.

Up to two objectives, at least 24 blocks apart. Two squads eating adjacent blocks of the same machine hall is
not two objectives, it is one with the bodies split; 24 blocks is a room's width, so a mid-sized factory still
offers two and a shed does not.

**Measured.** The compound was rebuilt two-valued: 45 furnaces on the near side, on the line the swarm walks
in along, and 25 blast furnaces (value 2) on the far side, 32 blocks past them.

```
squad 0: 101 bugs, power 101 -> machines (14, 81, 2)     <- the blast furnaces, on the far side
squad 1: 100 bugs, power 100 -> machines (-14, 81, -2)   <- the furnace floor, second objective
squad 2:  99 bugs, power  99 -> rally (0, 81, 0)
```

The lead squad walked past forty-five nearer machines to reach twenty-five better ones, and held that
objective with the swarm standing inside the compound — which is the case that matters, because a cluster
centre is irrelevant the moment the blocks themselves are loaded. 2.642 ms/tick at 300, breached at t+15s.

### 20.3 What the run does not cover

- **Players.** Still no runtime confirmation of the `PLAYER` objective; a headless RCON server has no players.
- **A defended base.** Nothing shoots back. The perf figures are for a swarm against architecture, not
  against architecture and turrets, and the melee arm of §16.1 is the closer proxy for that.
- **Depth.** One-block walls. A three-thick obsidian shell multiplies the digger's 8 s per block by the
  doorway's four blocks and again by depth, which is a wait, not a new behaviour — but it has not been run.

---

## 21. Implemented: separation without a broadphase

§11.4 turned entity push off because it was the only cost that grew faster than the swarm did — 1.8% of the
tick at a hundred glyphids, 15.5% at three hundred packed. What that bought in milliseconds it charged back in
looks: with nothing keeping them apart, three hundred bodies converge to a point and stand inside each other.
Measured, 300 marching on one spot: **0.47 blocks to the nearest neighbour on average, 231 of 300 inside half
a body width of another, and the closest pair at exactly 0.00.**

**The cost was never the push.** It was the broadphase — every glyphid asking the level for an inflated-box
entity query, every tick, which walks entity sections and runs a generic predicate over everything in them.
The arithmetic that follows a query is a handful of subtractions. So the push stays and the query goes: one
uniform grid built per level tick out of `GlyphidTracker`, which already holds every live glyphid, and each
bug compares itself only against the bugs in the nine cells around it. Each pair is visited once and pushed
both ways, which halves the work and means two glyphids cannot disagree about which way they are separating.

Nothing in it is collision. There is no sweep, no bounding-box intersection and no cramming: an impulse is
added to velocity that the `move()` already running will spend, and a pair that ends the tick still
overlapping simply pushes again. The swept-collision cost §11.2 went after is not reachable from here.

### 21.1 Two numbers that had to be got right

**The push has to be worth a walk.** The first cut capped each pair at 0.05 blocks a tick — a fifth of a
walk, deliberately gentle so separation would lose to the direction the glyphid actually wanted to go. It
lost: a swarm converging on a point is three hundred bodies driving inward at walking pace, and with entity
collision off there is nothing else in the way, so a push weaker than the drive does not hold a spacing, it
only slows the compression down. It closed to 0.59 blocks a bug. The cap is now 0.15 — comparable to a walk —
and applied **per glyphid over all its neighbours** rather than per pair, which is what makes a number that
size safe. Six pairs pushing the same way is a shove across the map; six pairs pushing outward from a ring is
nothing at all, and only the sum knows the difference.

**Displacement stopped being an honest signal.** §16's backoff doubles the repath interval when a glyphid is
not moving, on the reasoning that the pathfinder returns a partial route rather than nothing, so movement is
the only truth. Separation breaks that: a bug wedged in a crowd is shoved a block a second, every shove reads
as a path that is working, and the whole swarm holds itself at the minimum repath interval forever. Progress
is now the movement **projected onto the bearing to the destination** — a sideways shove scores nothing, a
backwards one scores less. That change alone took the converging arm from 3.72 to 3.14 ms/tick before
separation was tuned at all.

### 21.2 Measured

Two arms, one server run each, 300 glyphids.

| | nearest neighbour | overlapping | closest pair | ms/tick |
|---|---|---|---|---|
| converge on a point, off | 0.47 b | 231 | 0.00 | 2.956 |
| converge on a point, on | 1.02 b | 78 | 0.19 | 3.540 |
| assault a walled base, off | 0.55 b | 210 | 0.00 | 2.313 |
| assault a walled base, on | 1.04 b | 16 | 0.48 | 2.475 |

The grid pass itself is **0.093 ms at 300**, 2.6% of the tick. The rest of the difference is not separation
work, it is glyphids moving instead of standing in a heap — more path following, more movement, more of the
`move()` that a stationary bug gets for free.

The assault is the case worth reading, and it is nearly free: **+7% for 210 overlapping bugs down to 16.** It
also breaches the wall *sooner* — t+10s against t+15s — because a swarm spread along a wall chews it in
several places at once, where a stacked one queues behind whichever bug got there first.

The converging arm is the worst case at +20%, and it is the one a flow field changes: what those glyphids are
spending their time on is repathing into a pile they cannot enter.

---

## 22. Implemented: Phase 3, the flow field

### 22.1 First, what the tick is actually made of

§11.7 concluded "the lever is fewer block reads", and §11.11 walked that back for the assault. Both were
written before charge, shared paths and the stagger. A JFR recording of 300 marching, 1 ms sampling, sliced by
what the stack is inside — as a share of the samples that are in glyphid code at all, which is 81% of the
server thread:

| | share |
|---|---|
| `Entity.move` and collision | 33.8% |
| goal selector | 25.5% |
| — of which path search | 13.8% |
| `baseTick`, blocks-inside, fluid | 18.9% |
| synched-data writes | 5.7% |
| attribute lookups | 5.2% |
| separation | 3.6% |

**Path search is no longer the dominant term.** It is a third of what movement costs. That is worth saying
plainly, because Phase 3 was scheduled when path search was 52% of a march, and a plan that outlives its
measurement is how projects optimise the wrong thing for a month. The remaining case for the flow field is
that it is 13.8% of the mean and **31% of a p95 tick**, that it scales with swarm size where movement scales
with entity count, and that it is the only one of the three a warband record can use — a record has no
pathfinder, so §2's sim tier cannot navigate at all without it.

The profile also handed over two things that were free. `setAggressive` and its shared-flag write were 4.9%
of everything the swarm did, and the climbable flag another 3.6%, all of it setting values to what they
already were. Both are now written through a server-side mirror, so the unchanged case is a boolean compare.

### 22.2 The field

One flood per destination, not one search per glyphid. Breadth-first from the destination over walkable
columns; a glyphid's whole navigation decision becomes one array index and eight comparisons, against a 212 µs
search that read 4843 blocks.

Four things are load-bearing:

- **Flooded outward from the goal, and read before it is finished.** The columns nearest the destination are
  solved first, which are the ones a converging swarm is standing in, and a glyphid in an unfilled column
  falls back to pathfinding for another second. Nothing ever waits on it, which is what makes an incremental
  build honest rather than a way of hiding a stall.
- **Floors sampled by the flood, not up front.** Only columns the flood reaches are ever read, so a field
  around a sealed base costs the inside of the wall and stops.
- **Budgeted at 768 columns a tick.** A complete field is ~9400 columns; flooding it in one tick would be a
  2 ms spike on the tick it landed, which is exactly the stutter §11.8 says matters more than the mean.
- **Double-buffered.** A rebuild floods alongside the field in use and swaps when finished. The first cut
  replaced the field in place, which put three hundred glyphids back on the pathfinder for the dozen ticks a
  reflood takes — and the comment above it claimed otherwise, which is how it survived a reading.

### 22.3 Glyphids climb, and a field that forgets it is worse than no field

The result that made this work. An obsidian compound — unchewable by a grunt, so digging is off the table —
with a single three-block gap on the far side, destination in the middle, 300 glyphids outside:

| | inside after 60 s | ms/tick |
|---|---|---|
| pathfinding | 231 of 300 | 2.610 |
| flow field, walking model | 91 of 300 | 2.809 |
| flow field, climbing model | **300 of 300** | **2.178** |

Pathfinding "solved" the maze by not solving it: a glyphid walks into the wall, `horizontalCollision` sets the
climb flag, and it goes over the top. The first field modelled a walker — one block of step-up — so it did the
clever thing and sent everyone the long way round to the gap, where three hundred bugs queued at a
three-wide door. **The naive behaviour was better than the smart one, because the naive one was the only one
that knew what a glyphid can do.**

Connecting columns up to eight blocks apart vertically fixes it, and the field goes from worse than
pathfinding to strictly better: everyone gets in, sooner, for less. The climb is charged as one step like any
other move, which is a small lie — climbing is slower than walking — but the honest weight is still far below
the cost of walking around a building, and a weighted flood is a bucket queue for a correction that changes no
decisions.

### 22.4 Measured

300 glyphids, one server run each arm.

| arm | | ms/tick | p95 | max | path search | searches/tick |
|---|---|---|---|---|---|---|
| march 48 blocks, open ground | off | 2.369 | 3.042 | 3.718 | 9.4% | 1.1 |
| | **on** | **2.090** | **2.426** | **2.702** | **0.6%** | **0.1** |
| obsidian compound, one gap | off | 2.610 | 3.618 | 5.462 | 15.9% | — |
| | **on** | **2.178** | **2.699** | **3.694** | **4.6%** | — |
| assault a stone base | on | 2.260 | 2.733 | 4.431 | 4.7% | — |

**−12% on the mean and −27% on the worst tick in the window**, with path search all but gone: 1.1 searches a
tick becomes 0.1, and what is left is the glyphids outside the field's square still hopping their way in. The
field build itself is 0.006 ms/tick amortised — it is finished and idle for most of a run, which is the whole
point of paying for terrain once.

The tail is the number worth keeping. A swarm's mean was never the problem; the 5.4 ms tick when forty of them
repath on the same tick is, and there is no such tick when they are all reading the same array.

### 22.5 What is left

- **The build is still on the world thread.** It is now the structure §11.10 asked for — dense arrays, no
  block reads at lookup — so moving the flood to a worker is a scheduling change. It has not been done because
  at 0.006 ms/tick there is nothing to reclaim yet.
- **Melee still pathfinds**, deliberately: a field is built to a fixed destination and a target that walks
  would invalidate it every few seconds, where the charge of §11.11 answers the same question with a raycast.
- **Terrain the swarm did not change is noticed on a timer.** Chewing invalidates precisely, and a 400-tick
  age is the backstop for a player bricking up a doorway. A stuck-glyphid signal was tried and removed: three
  hundred bugs jammed in a gap are stuck for reasons that have nothing to do with the field, and they reflood
  it into thrashing.
- **Phase 4 is now the bigger lever.** Movement is 33.8% and the only way to cut it is to have fewer things
  moving.

---

## 23. Implemented: Phase 4, the sim tier

§2's T1: a glyphid that is a record rather than an `Entity`, in a loaded chunk, walking. It is the phase §22.5
pointed at — movement was 33.8% of a marching swarm and the only way to cut that is to have fewer things
moving — and it is the largest single win in the subsystem so far.

### 23.1 What an entity costs that a glyphid does not need

From §22.1's profile of 300 marching, as a share of the samples in glyphid code:

| | share | does a walking glyphid need it? |
|---|---|---|
| `Entity.move` + collision sweep | 33.8% | only where something can see it walk into a wall |
| `baseTick`, blocks-inside, fluid | 18.9% | fire, air, portals, freezing — no |
| synched-data writes | 5.7% | there is no client tracking it |
| attribute lookups | 5.2% | its stats are a record on the caste |
| goal selector | 25.5% | the arbitration is `GlyphidBrain.errand` already |

Nearly two thirds of it is the cost of *being an entity*, not the cost of being a glyphid. So the tier is not
an optimisation of any of those; it deletes them, and keeps the one thing that was actually behaviour — the
brain, which §16 took off the entity for exactly this reason. A `SimGlyphid` implements `GlyphidCarrier`,
fills in the same `GlyphidSnapshot`, is planned by the same `GlyphidBrain` against the same `GlyphidMind`,
and carries the plan out by moving a double.

### 23.2 Measured

300 glyphids, 160 blocks down a flat cleared corridor, one server run per arm. The 160 is not arbitrary: the
window has to be all march, because *arriving* is what turns a record back into a body.

| arm | entities | records | ms/tick | p95 | max | per glyphid |
|---|---|---|---|---|---|---|
| sim off | 300 | 0 | 2.718 | 3.546 | 5.144 | 9.1 µs |
| sim on, nobody watching | 5 | 295 | **0.117** | **0.215** | **0.340** | **0.4 µs** |
| sim on, watcher at the base | 58 | 242 | **0.803** | **1.126** | **1.473** | **2.7 µs** |

**23× cheaper with nobody near it, 3.4× cheaper with somebody standing at the target.** The middle row is the
tier at full effect and the bottom row is the case that actually happens: a defender at the base, the front
rank real and fighting, the column behind it records. The sim pass itself is 0.050 ms for 295 records —
**0.17 µs each against 8.8 µs for a body**, a factor of fifty.

The p95 and the max fall further than the mean does, which is the shape every phase of this work has had: a
swarm's mean was never what a player feels.

### 23.3 The two tiers have to walk at the same speed

The one number that had to be got right, and it was wrong first.

Vanilla ground movement accelerates toward a target velocity against block friction; for a grunt the terminal
speed is about 0.375 blocks a tick. A walking glyphid never reaches it, because path following slows at every
node and every corner: measured over a 160-block march it covers **0.130**. A record walks in a straight line
at whatever constant it is given, so deriving that constant from the physics made the sim tier **56% faster
than the entity tier** — a swarm arriving in two waves, which from the outside reads as a design decision
rather than a bug.

Calibrated against the measurement instead, the two tiers walk at **0.131 and 0.128 blocks a tick**.
`swarmbench tiers` reports both off the same run, which is what set the constant and what would catch it
drifting.

### 23.4 The rules, which are the honest statement of what the tier is

A glyphid is an entity when anything could interact with it, and a record the rest of the time. Every clause
is a capability the record does not have:

- **fighting** — a record has no target and cannot acquire one
- **swimming, flying, falling** — physics
- **chewing** — a world write
- **wounded** — it is in a fight; see §23.5
- **scout and nuclear castes** — one founds nests, the other detonates when it dies, and a record's death is
  a list removal. Simming a nuclear would be a way of quietly disarming the most dangerous thing a colony can
  send
- **within 64 blocks of a player** — with 24 blocks of hysteresis, or a glyphid on the boundary changes form
  twice a second

Both directions are budgeted at 24 a tick. A player walking toward a swarm crosses the boundary for hundreds
of glyphids within a few ticks, and `addFreshEntity` in one go is exactly the single-tick spike §11.8 says
matters more than the mean.

**Promotion is conservative in the direction that matters.** A record is only dropped once it is either dead
or standing in the world: a failed spawn leaves it a record to be retried, the same rule
`WarbandMaterialiser` follows for a warband that could not be placed. The mind crosses with it — a glyphid
that spent forty ticks failing to get anywhere would otherwise arrive with a fresh sixty ticks of patience,
and one that keeps changing tier at a wall would never accumulate enough to chew through it.

**Two navigation models, and the lie is bounded.** Inside a flow field a record walks the field, which only
ever crosses columns something could stand in — so it does not pass through walls, and does not need
collision to avoid them. Verified: 200 records marched at a sealed obsidian box and **none ended up inside
it**; 162 of them gave up, promoted, and started chewing. Outside a field it walks straight over the surface
height, which is what `SimDrone` does by holding altitude and what `Warband` does crossing the map. It will
climb a sheer wall the surface goes over — so will a real glyphid — and it can clip a block or two into one
while doing it, which is why the tier is gated on nobody being within sixty-four blocks.

**One thing a record needs that a body gets for free.** `Move.NONE` means "carry on with the walk you are
on", which for an entity is a navigator following a path it was handed twenty ticks ago. A record has no
navigator, so it remembers the hop. Without that it takes one step per repath interval and stands still in
between — a swarm walking at a twentieth of its speed, which reads as a movement-speed bug rather than as a
missing field.

### 23.5 Area damage, or the tier is an exploit

A swarm that no weapon can reach is not a performance feature. `SimGlyphidBlast` runs inside
`EntityProcessorCross`, so a blast that spares entities spares records too and the falloff, the range mutator
and the blast shape are the ones that blast would have used — the shape gate was refactored to answer about a
position rather than an entity, or a shaped charge would have been conical for one tier and spherical for the
other.

It is also where the tier pays off hardest. A blast over three hundred bodies is three hundred `hurt` calls
through damage sources, resistance handlers, hurt animations, sounds and death loot. Against records it is a
distance test, one raycast and a float subtraction.

Measured: a size-4 blast at the edge of a 300-strong swarm killed 24 and left the rest, and the wounded
survivors promoted to bodies over the next two seconds. Cover is checked with one ray per record rather than
the seven-node sample the entity path uses — a record has no bounding box for the nodes to be offset around —
and it works: behind an obsidian wall **6 records lived where 164 in the open were cut to 7**.

What a record's death does not do is drop loot or play an animation. That is deliberate rather than missing:
it dies where nobody is.

### 23.6 Both tiers, one separation grid

§21's grid now holds records alongside bodies, and has to. The two halves of a swarm walk the same field to
the same column, so a tier that separated only its entities would deliver a spread-out front rank followed by
two hundred records standing in one block. It costs nothing — the grid is O(n) in whatever is put into it —
and it is why `GlyphidCarrier` carries a position and a push at all. Measured across the arms: 1.22 b to the
nearest neighbour all-entity, 1.14 b all-record, 1.13 b mixed.

### 23.7 Drawn, because invisible is not the same as absent

A swarm whose back half vanished at sixty-four blocks would be a rendering bug that happened to be a
performance feature. Records are drawn out to the distance vanilla would have tracked the entities they
replaced, by `SimGlyphidVisual` — same mesh, same skins, same pose tables, same twenty-seven instance slots.
The half of `GlyphidVisual` both tiers share was pulled out into `GlyphidRig` rather than copied: two copies
of a transform chain containing the constant `1.5078125` are two copies that will eventually disagree.

Three things are deliberately different from the entity path:

- **One flywheel effect for the whole tier, not one per glyphid.** Per-record visuals would reintroduce
  exactly the per-object bookkeeping the tier deletes.
- **A pool of rigs per caste.** Records appear and disappear as a swarm crosses the promotion boundary;
  creating and deleting instances at that rate would churn the instancer's buffers every few seconds. A frame
  fills as many as it needs and collapses the rest.
- **One packet every four ticks holding the whole visible set**, at ~10 bytes a glyphid, against vanilla's
  one move packet per mob per tick. Whole set and not a delta: a delta needs a removal list, an
  acknowledgement or a heartbeat to notice a record that stopped being sent, which is three ways to leave a
  glyphid drawn where there is none. Sending everything visible makes disappearance the default.

What a record does not have, it does not draw: no bite, no corpse roll, no flight lean, no infestation
overlay. The walk cycle and the armour plates survive, because both are visible at the distance a record
lives at.

### 23.8 The benchmark lied first, again

The first two runs of this measured a jam and said nothing about it. The corridor was cleared from y=81 to
y=95 and the world's rock started at 96, so the march's heightmap sample returned ~121, every waypoint landed
forty blocks inside stone, the A* came back with five-node stubs and the swarm crawled at 0.05 b/tick. Every
`fill` succeeded. All 300 glyphids existed. The tick figures looked plausible — and the arm-1 baseline moved
from 2.876 to 2.304 ms between two runs of an identical script, which is the only reason it got caught.

Same lesson as §26's silent 32768-block `fill` and the same as the unticked-chunk artefact of §16.2: **a
headless benchmark's failure mode is a number, not an error.** The arena builder now clears to y=152 and
probes for a roof afterwards.

### 23.9 What is left

- **Squads only see the entity tier.** `GlyphidSquads` reassigns bodies every 100 ticks; a record keeps the
  orders it had when it was demoted. Defensible rather than merely unfinished — squads exist to divide a
  swarm *at the base*, and a glyphid at the base is within sixty-four blocks of the defender it is dividing
  over, so it has a body. It would stop being defensible the moment squads had to redirect an approach march.
- **A record cannot be shot.** Only area damage reaches it, which is correct while the promotion range is
  larger than any weapon's — and would silently stop being correct if something outranged it.
- **The sim pass is still on the world thread**, and is now the best candidate for moving off it: it reads
  no chunk except a heightmap, which is §1's whole argument for why the off-world tier is the easy one to
  parallelise. At 0.05 ms/tick there is nothing to reclaim yet.
- **T2 is still a separate mechanism.** A warband record and a sim record are now two things that walk
  without an entity, and only one of them can navigate terrain. Merging them is what would let a warband
  cross a map by the same rules it fights by.
