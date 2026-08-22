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
