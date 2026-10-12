# Kinetic Rounds

## 27. What one is

Unguided round (bullet, shell, bomb) with no entity: `round/Rounds`, one struct-of-arrays batch per level, a transient
`SimKind` resolved in Post (`EARLY`). Rockets (§29a) and missiles stay entities (targetable).

- Contract: `pos += v; v = v * decay - g`, `decay = (double) (1f - drag)`; water state from the pre-move position.
  CombinedAssault's closed-form solver inverts exactly this.
- Collision per tick (rounds and `RocketEntity`): one block clip over the segment, never a sync load; every entity
  before the clip end struck nearest first, then the block (pass-through never tunnels). The shooter's whole ride
  stack is never hit. A strike or impact listener ending the round ends its step there. Unloaded voxels: air, except
  non-chunk-loading rounds (§29).
  | target | hit test | parts |
  |---|---|---|
  | `PreciseHitbox` | `clip` | null |
  | living | segment clear of AABB and registration box, both + 0.3 => miss; else AHF `classifyRay` (`classifyRayPierced` iff AHF `penetrationEnabled`) | AHF, entry order |
  | living, AHF no part | player + usable rig => miss (rig authority); else AABB + 0.3 | null |
  | other | AABB + 0.3 | null |
- Lag comp: `launch(..., damageScale, rewindTicks, shooterSeq)`. `rewindTicks > 0` => every step sweeps living
  targets as broadcast in `broadcastTick - rewindTicks` at that step (AHF `EntityHistory.candidates` padded 0.3 +
  snapshot). No snapshot then, passengers, `PreciseHitbox` => live, source tick `LIVE`. Common config
  `rounds.rewindWholeFlight` (>= 1.5.0; default true; `Rounds.rewindWholeFlight`) false => spent by the first step that
  swept entities (1.6.0: a step retried on still-decoding terrain, or dark, keeps it), later live.
- Sweep cost (`RoundSweepBench`, 20 players + 200 husks, 2026-09-27): live 1.4-1.65x the 1.2.0 AABB sweep (per-hit
  AHF classification), rewound 3 ticks +10-30% over live, same hit count; AHF capture 26 us/tick.
- Strike: `ProjectileStrikeEvent` with `projectile() == null`, `key()` = round key, `parts()`, `shooter()`; damage through
  `RoundDamageSource`, a `StrikeContext` (threat, travel, key, `parts()`) and AHF `AhfHitSource` (swept segment,
  tick; `LIVE` = unrewound; `hitPart` = first part => AHF pierced list = one part, read `parts()`). Armour/hit code
  reads `StrikeContext.of(source)`.
- Headshot factor: `ArmorSystem.resolutionClaimed(victim)` (claimed players, `claimResolution` predicates) => 1;
  parts => `parts[0] == HEAD`; no parts => eye height +- 0.25 at the swept tick.
- Pierce: preset `pierceDT/DR` on `RoundDamageSource`; `ArmorSystem.pierceDT/DR(source)` = the round's, else the
  thread context (`DamageResistanceHandler.setup`), which rounds never touch => damage nested in the hit keeps the
  outer context.
- WFLib-owned armour (`ArmorSystem.onIncomingDamage`): parts => `resolveSlot(slotFor(parts[0]))`; else coverage
  weighted.
- Wire (`rounds-6`; 1.4.0 `LOST` -> `DEFERRED`; 1.5.0 + pierces; 1.8.16 + ricochets): one `RoundPacket` per player
  per tick: spawns grouped by preset (+ shooter id, seq, both varint `+1`; ricochet count byte), ends (key, position as
  doubles, `RoundEnd`), pierces (§29b), ricochets (§29e); audience 1024 blocks, whole level for chunk-loading rounds;
  FakePlayers / connections without the channel skipped. Client (`round/client`) flies the same recurrence without
  collision; end before the round's first client tick => one replayed segment to the end point; landing re-sync
  updates the same `Round`. Tracer streaks (`TracerRenderer`), rigged models (`RoundsVisual`,
  one Flywheel effect per level).
- Client API: `ClientRounds.addObserver(RoundObserver)` (every round: `tick`, `ended(at, reason)`, `pierced`,
  `ricocheted`),
  `byShot(shooterId, seq)`, `predict(preset, shooterId, seq, pos, v, adoptTicks)` (1.3.3): shooter-side round, same
  recurrence + loaded-block clip (ends there, or pierces as §29b); first server spawn with that shooter + seq adopts it (prediction's
  flight kept, server key + end taken, later re-syncs ignored); none within `adoptTicks` => removed (`CLEARED`).
  Prediction ended on a block before adoption => its server spawn/end swallowed. Server launch line off the
  prediction's (> 1e-3 rad, server spread; >= 1.6.0): velocity = server round's flown to the prediction's age, position
  eased onto it over 2 ticks; ended prediction => server round drawn on its own.
- Tracer streak <= distance flown since the round's first known position (1.3.3; before: a round spawned at the camera
  streaked behind it across the screen). Alpha-blended, unlit (additive washed out to white on a daylit sky). Half width
  per end = clamp(0.05, 0.0012 x d, 0.0035 x d) (1.7.3; one head-distance width => tail under the eye drawn as a band
  across the screen). Head dot 0.0025 rad (1.7.3): streak along the view ray (own shot, round flying away) projects to
  < 1 px; measured 1.7.2 0-2 px -> 1.7.3 8 px (eye launch, sky). `tracerColor` 0 => nothing drawn, model-less round
  invisible.
- Foreign look: `RoundRenderers.register(id, RoundRenderer)` (client) precedes the glTF model: rounds and rockets by
  preset id, missiles by `look`. Per-round client `tick`/`ended` hooks (sounds).

## 28. The knobs

`KineticPreset`:

| Group | Fields | Notes |
| --- | --- | --- |
| Identity | `id`, `model`, `warhead` | model null = tracer only, no item art |
| Flight | `speed`, `drag`, `gravity`, `life` | blocks/tick, fraction/tick, blocks/tick^2, ticks |
| | `water(drag, gravityFactor)` | |
| | `dispersion` | degrees added to the gun's spread |
| Terminal | `mass`, `impactDamage`, `energyDamage(perKJ)` | direct hit = `0.5 m (20v)^2 / 1000 * perKJ` if set, else flat, else 0.05 m v^2; x launch `damageScale` |
| | `headshot(multiplier)` | see headshot factor (§27) |
| | `pierce(dt, dr)` | armour DT/DR ignored by the direct hit |
| | `penetration(blocks, resistance)` | drill: destroys blocks with pen table (§29b) mm/m `<= resistance` (default 60; < 1.6.4 blast resistance, default 30; shipped `ap`/`heat` 250 = METAL: pre-1.6.4 vanilla outcome kept); re-swept after each drilled block within the tick (demolition) |
| | `effect(id, on, chance, params)` | impact effects (§29c); unknown id / bad params throw at build |
| | `blockPen(mm)` | non-destructive pierce (§29b); after drilling |
| | `airburst(height)` | ray down of height + one tick's travel |
| | `proximity(radius)` | |
| | `fragments`, `blastHalfAngle`, `blast(size, breaksBlocks)` | to the warhead; blast credited to the shooter |
| | `passesThroughEntities()` | hurt and keep going |
| Behaviour | `noChunkLoading()` | flies unloaded ground on its saved terrain (bullets, §29) |
| | `tracer(rgb)` | streak colour; 0 = none |
| | `stackSize` | |
| | `quadraticDrag(k)` | `decay = 1 - drag - k\|v\|` (clamped 0); client flies the same |
| Ground fuze | `fuseDelay(ticks)`, `burrow(hardness)` | block contact => rest (v = 0, resynced), dig down while budget >= destroy speed (min 0.1), then fuse |
| | `noEntityContact()` | entities never swept (bombs) |
| Rocket only | `motor(accel, ticks)`, `durability` | blocks/tick^2 along the nose; health vs air defence (8) |

## 29. Ground

Chunk-loading round, any column of its segment unloaded (segment not wholly above the build limit) => tickets on every
column of the segment (tickets outside it released; loaded ones kept, else a released one unloads and they never all
load), waits in place (life refunded) until all load.

Non-chunk-loading round over unloaded ground (1.4.0; before: `LOST` at the first unloaded chunk) = virtual flight:

- Same step, same recurrence: segment voxels in unloaded chunks read on-disk terrain (`round/terrain`); fluid for the
  water law from it too. Entities swept as ever => re-entering loaded chunks resumes strikes and rewind unchanged.
  Segment over unloaded columns only, none loaded within 3 blocks of its XZ box (query inflate 1 + entity section
  lookup 2) (`DARK`) skips the entity sweep. Pierced remainder (new heading/length): columns reclassified.
- Terrain = the chunk as last saved: region file read `READ`-only (`RegionReader`; vanilla `RegionFile` ctor opens
  `CREATE, WRITE`, `close()` pads + forces), NBT -> per-section masks (`SolidityDecoder`): full-cube collision =
  solid, other non-empty collision = its state's shape (via `EmptyBlockGetter`), fluid = wet. 1.18+ layout, no
  DataFixer (renamed block => air).
  | on disk | flown as |
  |---|---|
  | no region file / chunk never saved (ungenerated) | air |
  | unreadable (torn read; slot holding another chunk: `xPos`/`zPos` checked), decode error, pre-1.18 | retried 2x, 5 then 10 ticks later (round waits); then air, logged |
  | saved (`ChunkDataEvent.Save`), IO worker write pending | read through the IO worker (pending write) until an IO worker sync issued after the save completes |
  | dropped from the chunk map, unload save not yet run (tick spare time; never on a sprinting server) | previous save (stale window; ends at that save) |
- Cache (`AsyncTerrainSource`): per level, server-thread owned, LRU 2048 columns / 64 MB, 256 decodes in flight on its own 2
  daemon threads; invalidated on chunk load/unload/save (queued from the chunk events, drained at resolve). Column
  decoding => decoded prefix flown (hits short of it), round stops at its face (resync, no decay/gravity), waits
  there, life spent (>= 1.7.2; <= 1.7.1 whole step waited: loaded target short of it missed while decoding). Decodes queued 8 ticks of travel ahead. Air columns (ungenerated, no
  region file, unreadable) in a separate FIFO set (32768), never the LRU (>= 1.6.2; 1.6.1: misses flying on over
  ungenerated ground evicted decoded terrain => later rounds waited on re-decodes, arrived late).
- Below the build range by 64 (vanilla `checkBelowWorld`) and not rising => `EXPIRED` (>= 1.6.2; before: flew out its
  life, 2400 ticks for MineAssault rounds).
- Unwatched fast-forward: segment over unloaded columns, no player within 1024 of the round and no viewer (player sent
  its spawn or a resync) => up to 16 steps/tick, each while the next segment stays over unloaded columns. Watched => 1
  step/tick (client copy flies 1/tick).
- Resync/pierce/end packets: audience now + viewers still in the level (a round flown past 1024 still ends on the
  client that drew it). Queue full => lookup waits, no stat; region-file existence cached per region.
- Block hit in an unloaded chunk: round ends `BLOCK` there; `DeferredImpact` stored (`DeferredImpacts`, saved with the
  level, 4096 max, overflow dropped + warned), applied at the first rounds resolve after the chunk loads (never in
  `ChunkEvent.Load`): `RoundImpactEvent`, then land (fuse/burrow) or warhead, shooter by UUID. Resting round whose
  chunk unloads => `DEFERRED`, rests again on load with the fuse left. In-flight rounds: transient (life-bounded; client
  copies and keys not restorable).
- Divergences from loaded ground: no drilling (first solid voxel stops; `blockPen` pierces, §29b); face from the collision shape only (no
  interaction-shape override); airburst ray over a decoding column does not trip; a deferred warhead's blast
  reaching still-unloaded neighbours loads them (vanilla explosion).
- Cost (`VirtualFlightBench`, 500 rounds x 25 steps, flat lane, 2026-09-28, 3 runs): disk step 0.92-1.56x the
  loaded step (500-715 ns vs 458-599 ns; no entity sweep, per-voxel mask lookup); terrain span walk on a loaded
  non-chunk-loading step ~50 ns (458 vs 408 ns chunk-loading twin). Decode per column: flat 54-183 us; real terrain
  (`run/world` r.0.0, 1016 chunks) 167-421 us read+inflate + 210-239 us palette, 5.8 mixed sections, 5.1 KB held
  => LRU full ~10 MB.
- Cost, dispersed (`VirtualFlightBench.dispersedFire`, 500 unwatched rounds at random headings, 4 blocks/tick, 160^2
  planted chunks with a farmland layer, ticks paced to 50 ms, 2026-09-28): round steps 2.19 ms/tick mean, 3.57 max;
  stalled only the first tick (500 waiting on first decodes); 7 region stats in 20 ticks; 1855 decodes x 57 us; LRU
  2048 columns, 3.4 MB. Unpaced (sprinting gametest ticks): 638-5600 stalled round-ticks of 9500.

## 29a. Rockets

`round/RocketEntity`: a preset with a motor, flown as an entity so air defence can shoot it. `RocketEntity.fire(level,
preset, from, direction, inaccuracy, carrier, shooter, faction, controlId)`.

- Step = rounds' (same sweep, strike, fuzes, warhead) + nose thrust while `age <= burnTicks` + quadratic drag. Nose fixed
  while burning, then on velocity.
- Ground: needs an entity-ticking chunk (not merely loaded); chunk-loading preset tickets its own and the next chunk.
  `noSave`.
- `InterceptTarget`: class `MISSILE` (recon `EntityTargetSource`, EMCON silent); `interceptDamage` past `durability` or
  `interceptKill` => warhead functions where it is. `discard()` = no warhead (APS). CIWS/interceptor batteries resolve
  any `InterceptTarget` entity, not only `MissileEntity`/`DroneEntity`.
- Client: exhaust + launch hooks on the preset's `RoundRenderer`; none registered => not drawn.

## 29b. Block penetration

`KineticPreset.blockPen` (pack `blockPen`), `round/pen`. Block damage: §29d. Ricochet: §29e.

- Capacity `C = blockPen * |v|^2 / muzzleSpeed^2` (mm steel-eq; KE-scaled => drag and earlier blocks reduce it).
- Cost `c = resistance * t`; `t` = ray length inside the collision shape's boxes (slab, pane, bars: thin; gaps free).
- `c < C` => exits at the last box exit: `|v'| = |v| sqrt(1 - c/C)`, heading turned `u * 8 deg * c/C` about a
  seeded azimuth; rest of the tick flown from there with `v'`, entities behind struck at `v'`; drag/gravity then as
  usual; client copies re-synced. `c >= C` => stops at the depth `C` buys: impact there (`RoundImpactEvent` with the
  entry hit, warhead/landing at the stop point). 64 pierces/step, then the next block stops it.
- Seed `(round key | shooter,seq if shooterSeq, block pos)`: a predicted shot deflects as its server round.
- Resistance (mm per metre of block, `PenTable`; drill reads it too): data map `wflib:penetration` (>= 1.6.4;
  `data/<ns>/data_maps/block/penetration.json`, `{"values": {"<block>|#<tag>": {"resistance": mm, "toughness"?: kg m/s, "ricochet"?: deg}}}`, synced to
  clients, not mandatory) > `PenMaterial` class: first match of `wflib:penetration/<name>` tag (most resistant) >
  unbreakable => `IMPENETRABLE` > blast resistance >= 600 => `ARMOR` > `c:glass_blocks`/`c:glass_panes`,
  `#leaves`/`#wool`/`#wool_carpets`, `#planks`/`#logs` > sound type > blast resistance `<=1` SOFT, `<=4` WOOD, `<=20`
  STONE, else METAL. Table per state id, rebuilt on tag reload (`shouldUpdateStaticData`: integrated client skipped;
  remote client from synced tags) and on block data map update (server reload, client sync).
  | material | mm/m | rifle 10 mm: 1 block |
  |---|---:|---|
  | GLASS | 0.3 | 97% energy out (speed 0.985) |
  | SOFT | 1.5 | 85% |
  | WOOD | 6 | 40%; second plank stops it 2/3 in |
  | EARTH | 25 | stops 0.4 in |
  | STONE | 60 | stops 0.17 in |
  | METAL | 250 | |
  | ARMOR | 1000 | |
  | IMPENETRABLE | inf | stops on the face |
- Shipped data map: wfcore blocks, each `neoforge:mod_loaded wfcore` (no wfcore => entries inert, no unknown-id
  errors). WFLib owns the table; wfcore not edited (build red, not ours). Calibration, not measured ballistics (7.62
  stopped in ~0.4 m sand, ~0.12 m concrete):
  | block | mm/m | class it replaces |
  |---|---:|---|
  | `sandbags`, `hesco_bastion` | 40 | EARTH 25 |
  | `standard_concrete` | 130 | STONE 60 |
  | `reinforced_concrete` | 250 | STONE 60 |
  | `ballistic_glass` | 300 | GLASS 0.3 |
  | `hardened_steel` | 1000 | METAL 250 |
  | `tungsten_plating` | 1500 | METAL 250 |
- Suggested `blockPen` (calibration, not measured ballistics): pistol 3, 5.56 10, 7.62x51 16, .50 BMG 40.
- Cost (`BlockPenGameTest.pierceCost`, launch+step+destroy, best of 7, 3 runs, other tests running): clear 1.1-1.7
  us, plank pierce 3.2-3.8 us, stone stop 2.9-5.9 us (impact + warhead + end; one 19.8 us outlier). Table: 27018
  states 3-17 ms per rebuild (1.6.4-1.7.0), 2 per reload (data map, tags). Decode, real terrain (`r.0.0`): 8.2 KB/chunk held (partials sparse/dense; hash map
  per voxel was 9.1, 14.5 dense-only) => LRU full ~17 MB, 64 MB cap; decode 1.7.0 252-308 us (4 runs), 1.6.4 258-267 (2), 1.6.3 261-276 (1.4.0: 210-239); other tests running, decoder unchanged since 1.6.4.
- Unloaded ground: solid voxels carry their resistance from decode (`SectionSolidity`: one per section, else palette
  of the section's distinct values + ceil(log2 n) rank bit planes, 512 B each); partial voxels by their state (rank into the section's partial states: 1 B/voxel
  dense, 3 B/partial voxel sparse, none uniform). Stop => `DeferredImpact` at the stop point.
  Server table rebuild => every decoded column and in-flight decode dropped (`AsyncTerrainSource.invalidateAll`). Neighbour-dependent shapes
  decode as if alone (§29).
- Events: `RoundPierceEvent` (server, game bus: key, entry hit, `BlockPen.Pass` exited/point/velocity/spent, incoming
  velocity), before the stop's `RoundImpactEvent`. Client: `RoundNetwork.Pierce` (key, block, entry + face, exit or
  stop point, exited) to the end audience; `RoundObserver.pierced` once per pierce: a live prediction reports its
  own, server's skipped; prediction stopped where the server exited => server spawn revives it as a plain round.
- Not covered: `RocketEntity` (no pierce).

## 29d. Block damage

`round/pen/BlockDamage`; presets with `blockPen > 0 && penetration == 0`. Shells/bombs (default 20 kg): drill,
blast, burrow only; impulse would let a 20 kg bomb landing at 20 m/s (400) break stone (360).

- Hit, loaded => `D += m |v - v'|` kg m/s (stopped: `v' = 0`; pierce: deflected exit; ricochet: outgoing). `D >= toughness * blockToughnessScale` =>
  `destroyBlock`, no drops. Fast light => passes, small `D`; heavy slow => stops, `m |v|`.
- Toughness (`PenTable.toughness`): data map `toughness` > data map `6 * resistance` > `PenMaterial`: GLASS 0 (any
  hit), else `6 * resistance` (WOOD 36, STONE 360, METAL 1500, ARMOR 6000, IMPENETRABLE never).
  | per hit, kg m/s | plank | stone |
  |---|---:|---:|
  | 5.56 ball 4 g 800 m/s, blockPen 10 | 1.18-1.19 (passes, <= 4.8 deg turn; 31 to break) | 3.2 (~113) |
  | 12 ga slug 28 g 480 m/s, blockPen 3 | 13.4 (stops; 3) | 13.4 (27) |
- Idle 600 ticks => fades over 200. Cracks: `destroyBlockProgress` stage `ceil(10 D / T) - 1`, breaker id < 0 per
  block; sent on stage change, refreshed every 300 ticks (client drops overlays idle 400). Sweep 20 ticks: state
  changed / faded / unloaded => dropped. Placement over an entry resets it. Not saved.
- WarForge (`explosionsRespectClaims`): block damage, drill, burrow skip blocks `ExplosionProtection` protects for the
  shooter (vehicle => controlling player; none => outsider).
- Config `rounds.blockDamage`, `rounds.blockToughnessScale`.
- Cost (`pierceCost`, tracked, never breaking): plank 3.0-4.1 us, stone 2.9-4.8 us; before 3.2-3.8 / 2.9-5.9 => noise.
- Gap: stops in unloaded chunks (`DeferredImpact`) deal none.

## 29e. Ricochet

`round/pen/Ricochet`. Blocks + water: presets with `blockPen > 0 && penetration == 0`; armour: any round, its owner
decides (`ProjectileStrikeEvent`).

- Block: face entered from air (not right after a pierce exit), loaded, layer would hold: `C sin a < resistance x
  extent` (`extent` = collision shape along the normal). Stop-only (`Pass` not exited) FORBIDDEN: a grazing round
  "exits" the first floor block's corner sliver into the next and stops there head-on. Water: surface entered from air
  (start voxel dry: a start inside the water voxel above the surface counts wet; segment end or just before it wet =>
  fluid clip; loaded spans). Else pierces / stops as before (= break-up).
- `a = asin(-d.n)` grazing, `c` critical: `P = clamp((1.25 - a/c) / 0.5, 0, 1) x (1 - 0.5 a/c)` (a/c 0.25 => 0.875,
  0.75 => 0.625, 1 => 0.25, >= 1.25 => 0).
- `c`, deg (estimates, not measured): data map `ricochet` > `PenMaterial`:
  | GLASS | SOFT | WOOD | EARTH | STONE | METAL | ARMOR, IMPENETRABLE | water |
  |---:|---:|---:|---:|---:|---:|---:|---:|
  | 0 | 0 | 5 | 10 | 20 | 30 | 35 | 7 |
- Out `0.8 v_t - 0.25 v_n` (water 0.7 / 0.5; departure ~0.31 a), scatter <= 3 deg, >= 0.5 deg off the surface; rest
  of the tick from 1e-3 off it. `|v| < 5` b/t (100 m/s) or 2 ricochets => none.
- Tumbling (ricochets > 0): quadratic drag x 3, `blockPen` + armour pen x 0.5. Spawn carries the count: client
  copies fly the same drag.
- Seed: `BlockPen.seed(shot, pos)` salted: prediction skips blocks / water as its server round (own flight, server's
  event skipped); prediction stopped where the server skipped => revived.
- Armour: `ProjectileStrikeEvent.canRicochet()` (WFLib rounds under the cap; rockets, missiles, TACZ never) =>
  `ricochet(v)` (`Ricochet.off(v, n, seed)`): no damage, flies on from the strike point, struck entity not re-hit
  this step. Client: armour ricochet re-syncs a prediction too. CombinedAssault hull: `ArmorResolver` RICOCHET (incidence >
  profile angle, not overmatch), KINETIC / SMALL_ARMS.
- Wire `RoundNetwork.Ricochet` (key, point, normal, `Surface` BLOCK / WATER / ENTITY, block) + re-sync;
  `RoundObserver.ricocheted`; trigger `ricochet` (`velocity` = outgoing).
- Gaps: unloaded / decoding spans (dark flight, `DeferredImpact`) never skip; fluids other than water none.

## 29c. Impact effects

`round/effect` (>= 1.6.4): preset list of `(id, on, chance, params)`, `KineticPreset.Builder.effect`, bound by
`ImpactEffects.bind` at build (unknown id, chance outside (0, 1], unread or bad param => `IllegalArgumentException`).

- Register: `ImpactEffects.register(id, ctx -> ...)` (no params) / `registerFactory(id, (on, params) -> effect)`
  (`Params.num/reqNum/bool/reqRl`); mod construction, any thread, before presets naming it are built; twice => throws.
- `ImpactEffect.check(RegistryAccess)`: `ServerStartingEvent` over every registered preset (`ImpactEffects.check`);
  failures joined in one `IllegalStateException` => server does not start.
- Triggers (`ImpactTrigger`; key = pack spelling):
  | key | when | ctx |
  |---|---|---|
  | `hit` | entity struck, strike outcome PROCEED, after the direct hit; `ImpactEffect.threat` folded in before `ProjectileStrikeEvent` | `entity`, `parts`, `at` entry |
  | `block` | stopped on a block: loaded after `RoundImpactEvent`; unloaded when its deferred impact applies | `block`, `at` stop point, `pass` if pierced |
  | `pierceIn` | `blockPen` met a loaded block, after `RoundPierceEvent` (unloaded: none) | `block`, `pass`, `at` entry |
  | `pierceOut` | `blockPen` exited it | `pass`, `at` exit, `velocity` after |
  | `ricochet` | skipped off a loaded block, water or armour (§29e) | `block` (water: surface), `entity` + `parts` (armour), `at`, `velocity` out |
  | `end` | round removed at a loaded point, impact not owed (stop in unloaded chunk => none; its deferred impact fires `block` + `end` BLOCK once loaded; landed fuse/burrow re-added, `end` when that ends); rocket detonated | `end`, `at` |
- Ctx: level, trigger, preset, key (round key, or the rocket), projectile (rocket), shooter, faction, `at`, `velocity`,
  `energy()` J, `openSide()` (voxel before `at` along the flight; after it for `pierceOut`).
- Chance rolled per trigger per effect (`level.random`); `>= 1` never rolled. Rockets: `hit`, `block`, `end`.
- No effect for a trigger => one bitmask test at its site, no context built.
- Built-ins (`ImpactEffects.IGNITE` ...):
  | id | params | triggers |
  |---|---|---|
  | `wflib:ignite` | `seconds` (5) | `hit`: target burns; others: fire on `openSide()` if placeable |
  | `wflib:damage` | `type` (damage type id; unregistered => server start refused), `amount` | `hit` only; i-frames ignored |
  | `wflib:explode` | `warhead` (unregistered => server start refused), `size` (warhead's), `breaksBlocks` (true) | all |
  | `wflib:thermal` | `pen` mm (0), `wear` x (1) | `hit` only: threat `penetrationMm + pen`, `wearFactor x wear` |
- Target systems stay separate: vehicle armour reads `Threat.penetrationMm`; WFLib armour DT/DR plus
  `Threat.wearFactor` (plate/insert wear points x factor, ceil; `ArmorSystem` via `StrikeContext`).
- Armour pen at impact (`Rounds.threat(preset, v)`, strike path): KINETIC/SMALL_ARMS `armorPenetrationMm x |v|^2 /
  muzzleSpeed^2` (as `blockPen`); HEAT/TANDEM_HEAT/HE and motors unscaled. `Rounds.threat(preset)` = muzzle figure
  (rocket `threat()`). Measured (`armourPenFallsWithImpactEnergy`, 100 mm, 800 m/s, drag 0.02): 0 blocks 100 mm,
  523 blocks 54.55, 1014 blocks 24.31.

## 30. Loading and firing one

Guns live in CombinedAssault (`com.wf.combinedassault.compat.wflib`; its `docs/vehicle-pack-format.md` §11).

```java
long key = Rounds.fire(level, preset, muzzle, direction, inaccuracyDegrees, carrierVelocity, shooter, factionId);
long key = Rounds.launch(level, preset, muzzle, velocity, shooter, factionId, damageScale, rewindTicks, shotSeq);
Rounds.position(level, key); Rounds.within(level, box); Rounds.destroy(level, key); // APS
```

Debug (op): `/wflib round <preset>` fires from the command source position along its rotation, shooter = source
entity (`execute anchored eyes positioned ^ ^ ^ run ...` for the eye).

Client overlay: `/wfdebug rounds [on|off|status]` (client command, off by default; bare = toggle; `status` = live
rounds with preset, tracer, local, speed). Every `ClientRounds` round, tracer or not (`round/client/RoundDebug`).
| mark | colour |
|---|---|
| flown path (origin cross, per-tick points, head) | cyan = local (own prediction, adopted or not); white = server copy |
| head cross | preset tracer rgb; magenta = `tracerColor` 0 (TracerRenderer draws nothing) |
| predicted arc (`RoundArc`, dry, no collision, <= 200 ticks or life/void end) | yellow, dashed, fading |
| resync (server snap; adopted prediction re-aimed) | orange: before cross -> after |
| pierce | blue entry cross -> light-blue exit cross; stopped inside: purple cross |
| end (kept 4 s fading, last 32) | BLOCK red, ENTITY green, FUSE amber, DESTROYED/DUD brown, EXPIRED/DEFERRED/CLEARED grey |
- Path ring 512 points/round (older dropped, origin kept); frame loop allocation-free.
- `RoundArc.predict` == `Rounds` step in open air bit for bit (`anArcPredictionIsTheFlight`, `RoundArcTest`). Wet
  segments diverge (water drag not predicted).

Registering: `KineticPresetRegistry.register(KineticPreset.builder(...).build())` (copy-on-write, any thread) during
mod construction => a carryable item. Vehicle weapons register model-less presets later (no item).

## 31a. Shared ammo (`ammo/`, >= 1.8.0)

Calibre + variant registry; startup only (code, mod construction), no datapack.

- `Ammo.register(Calibre | AmmoVariant, source)`: any thread until WFLib's item `RegisterEvent` (`Ammo.freeze`). Calibre +
  variant ids share one space; id twice => `IllegalStateException` naming both sources; after freeze => refused.
  Catalogue registered in `Ammo` class init; reads lock-free (volatile maps). Consumers resolving references to other
  mods' ammo do it after `Ammo.freeze` (idempotent), not at construction (mod order).
- Freeze: variant calibre unregistered, calibre without variant, look or preset build failure (`flame` without motor, effect unbound, bad params) =>
  one `IllegalStateException` listing each with its source. Then per variant a `KineticPreset` under the variant id
  (model null, chunk-loading iff kind `tank`; SI -> blocks/tick as MineAssault); `explosive == null && armorPen > 0 && bullet >=
  20` => threat KINETIC. Warheads checked at `FMLLoadCompleteEvent` (`Ammo.checkWarheads`).
- Items: one `CartridgeItem` per calibre `<ns>:cartridge_<path, . / - -> _>` (pack calibres: the pack's namespace),
  component `wflib:ammo_variant` (RL; unknown id => decode error); default = calibre's first variant; variant of
  another calibre on a stack => item's default. Name `calibre.name + variant.name`. Stack by kind: pistol/rifle 64,
  shotshell 32, grenade/autocannon 16, tank/rocket 4.
- Catalogue (`AmmoCatalogue`, source `wflib`, id `wflib:<calibre>/<role>`): 49 calibres. Small arms (30, 9x19 ..
  14.5x114 metric ids, e.g. `.50 BMG` = `12.7x99`): `ball` (default) + `tracer` / `ap` / `subsonic` where fielded;
  `12ga` buck (9 pellets) / slug; `40x46`, `40x53` he; `76mm_discharger` frag (vehicle discharger, gravity 28);
  `20x102` he/ap/tracer, `23x115`, `23x152b`, `25x137`, `27x145b`, `30x165`, `30x150b`, `30x173` he/ap; `105mm`,
  `120mm`, `125mm` apfsds/heat/he; `81mm` he, `127mm_naval` he, `406mm_naval` he/ap (kind `tank`, linear drag only:
  CombinedAssault solver exact); `rpg7` heat/frag (`RocketEntity`).
- Tank `damagePerKJ`: apfsds 0.3, heat 0.06, he 0.03 (CombinedAssault hull balance: 125 APFSDS ~2100). Flight = MineAssault converter calibre table
  (`modular-archive-gltf/convert_mineassault.py`; its `wflib_ammo.py` mirrors this file, `tools/test_wflib_ammo.py`
  checks); `damagePerKJ` = median over converted packs; `blockPen` §29b anchors else `31.58 (E/mm)^0.8517`, AP x1.5,
  others energy-scaled from ball.
- `armorPenMm` unset, no explosive (catalogue + pack variants) => `blockPen x RHA_PER_BLOCK_PEN` (estimate).

## 31a2. Vehicle ordnance (`ordnance/VehicleOrdnance`, >= 1.8.27)

Ported from CombinedAssault weapon JSON; registered from the kinetic / missile preset bootstraps (items at static init).

| item | preset | kind |
|---|---|---|
| `rocket_hydra_70`, `rocket_crv7` (70 mm), `rocket_s8` (80 mm) | kinetic, motor | `RocketEntity` when vehicle-fired |
| `bomb_mk_82`, `bomb_fab_250`, `bomb_cbu_87`, `bomb_b61` | kinetic, speed 0, rests, fuse 60, no entity contact | |
| `bomb_gbu_12`, `bomb_gbu_39` | missile, glides (2 g at 2 b/t) to aim point / lock | |
| `missile_agm_114`, `_agm_65`, `_akd_10`, `_kh_31`, `_shturm`, `_vikhr` | missile `BEAM_RIDING`, tandem HEAT | |
| `missile_tow` `OPTICAL`; `_fim_92`, `_aim_9`, `_aim_132`, `_magic`, `_r_73`, `_pl_8` `INFRARED`; `_fb_10` SAR; `_aim_120`, `_mica`, `_r_77`, `_pl_12` `ACTIVE_RADAR`; `_df_41` high altitude 512 | missile | |

- Mapping: accel = T/m/400, k = CdA/m, cruise = sqrt(accel/k), decel = max(accel, k cruise^2), G limit = maxG*g
  from `refSpeed`; seekers other than beam riding glide after burn-out.
- Item path: `KineticPreset.Builder.item` / `MissilePreset.Builder.item`; default `shell_<id>` / `missile_<id>`.
- Fit tags (`data/wflib/tags/item`): `missile/<small|medium|large|huge|pod>`, `bomb/<small|medium|large>`,
  `rocket/<70mm|80mm>`, `artillery_shells`, `countermeasure/<flare|smoke|aps>`. Consumers fit rounds by tag;
  third-party rounds join by tagging.

## 31a3. Countermeasures (`countermeasure/`, >= 1.8.27)

`CountermeasureItem.deploy(level, from, heading, inaccuracy, carrierVelocity, shooter, carrier)`; ex-CombinedAssault.

| item | entity | speed b/t | |
|---|---|---|---|
| `flare_cartridge` | `flare` | 0.5 | `SeekerDecoy` thermal 2 / radar 1; 200 ticks; drag 1 %/t, g 0.1; any block => gone |
| `smoke_grenade` | `smoke_grenade` | 1 | bouncing; first fast block hit pops: box 10^3, `SightBlocker`; 600 ticks |
| `aps_grenade` | `aps_grenade` | 15 | each tick: fastest `Projectile` / `RocketEntity` / round within 8 => both removed; any hit => gone |

- Grenades: g 0.07, bounce 0.75 (x/z /1.5, y /2.5), <= 3 hits/tick, never the shooter or carrier.
- Spread: triangle `0.0172275 x inaccuracy` (flares none).

## 31a4. Audio (`audio/`, >= 1.8.28)

Shared by WFLib, CombinedAssault, MineAssault. Reference: mcww3 `ww3/client/sounds` (curve, absorption, cabin filter shapes).

| piece | side | |
|---|---|---|
| `SoundSpec` | both | one layer; JSON id string or object; `gain(d)`; `Curve`, `EngineSound` (engine def) |
| `Blasts` | both | built-in layer sets: `small`, `large(range)` (far x3, from 0.4), `pop`, `sonicBoom`, `miningCharge` |
| `WfAudio.at` | server | `SoundAtPacket` to players within the widest layer; `trackers(entity)` = skip set for sounds the entity's own copy plays |
| `Emitter` / `Cue` | client | world sound (MASTER, attenuation NONE, gain set per tick) / head-locked |
| `Sounds.at`, `SoundScheduler` | client | one-shots + travel time (`C` = 343 m/s), frame-precise; queue cap 512 |
| `EnginePlayer`, `AlertChannel` | client | engine loops from an `EngineSound`; highest-priority alert loop |
| `AudioMixer.addCabin` | client | enclosure muffle provider (max over providers) |

- Gain: 1 to `ref = max(0.15 range, from)`, `(e^-3n - e^-3)/(1 - e^-3)` to `range`. Vanilla reach = `max(volume, 1) x attenuation` => never used for wflib sounds.
- Air absorption (Emitter): `t = clamp((d/range - 0.2)/0.8)`, LF `1 - 0.25t`, HF `1 - 0.99t`.
- Cabin: `m` slewed 0.25/tick x config `cabinMuffle`; LF `1 - 0.65 m s`, HF `1 - 0.85 m s`; share `s`: category (`EXPLOSIONS` 0.4, `ALERTS` 0, others 1), vanilla world sounds 1, relative 0.
- Doppler: OpenAL, `alSpeedOfSound(17.15)` (blocks/tick), source velocity = Emitter displacement/tick - camera displacement/tick, listener 0. Step > 20 blocks => 0 that tick.
- Filter: one EFX low-pass object; params copied at `AL_DIRECT_FILTER` attach => reuse safe. Applied at channel start (`PlaySoundSourceEvent`, sound thread) and after `SoundEngine.tickNonPaused`; unity => `AL_FILTER_NULL`.
- Volume: per-category config + Sound options sliders (`MixinSoundOptionsScreen`); vanilla category sliders do not reach wflib sounds.
- Gap: no occlusion, no reverb, no `_in` interior assets (cabin = filtered exterior).

## 31b. Visible projectiles

`ProjectileLook` per variant (resolved at freeze, `Ammo.look`): default visible = calibre kind grenade / autocannon /
tank / rocket, bullet >= 20 mm, or a motor; small arms: tracer streak only.

| shape | default for | mesh |
|---|---|---|
| `grenade` | grenade | band 18..28 %, round nose |
| `shell` | autocannon, tank HE | band, ogive, fuze |
| `dart` | tank, no warhead (APFSDS): 6.5 x 0.22 calibre | rod, windscreen, 6 fins |
| `finned` | tank shaped charge (HEAT) | boom + 6 fins, body, probe |
| `rocket` | rocket / motor | motor tube, warhead, 4 fins |
| `bullet` | small arms made visible | boat tail, ogive |

- Mesh (`AmmoMeshes`, pure): lathe about +Z (per-segment normals), fins as boxes; colours = cells of one palette
  `DynamicTexture` (`wflib:dynamic/ammo_palette`, built at client setup from every look + case material + flame); one
  `RigBuilder` model per variant (cartridge, projectile, flame), `GemRenderModels.built`. Pack glTF `look.model`: nose +Z,
  tail at the origin.
- Flight (`RoundsVisual`): nose along velocity (`rotationTo(+Z)`), roll `spinRps * 2 pi * age / 20`; rockets
  (`LookRockets`, first client tick; `RoundRenderers` entry => renderer only) through the same visual, nose = view vector.
- Flame (`flame`, motor only, default on): full-bright lathe cone behind the nozzle, `25 x` nozzle radius, length x
  `0.75..1.25` flicker, while `burning()`.
- LOD (`LookLod`): model while diameter subtends >= 1.5 px (1 px = 0.00113 rad); else body-colour quad along the
  heading (tracer pass, rounds + look rockets), `max(length, 2 px)` x `max(diameter, 1.5 px)`, to 512 blocks; beyond
  none. Switch: sprite == model silhouette. Length-based switch FORBIDDEN: thin models (rocket at 150 blocks, 0.3 px)
  and 1 px sprites cover no pixel centre.
- Trail (GemRender particles, `InstancedTrailEffect.Source`): `trail` (default: motor) on rockets while burning, on
  entity-less rounds while flying.
- Cartridge item: GemRender direct path (`AmmoClient` `ItemAppearance`): nose up-right, longest side 0.9 cell; item
  model `wflib:item/cartridge` (`builtin/entity`, vanilla generated display); catalogue JSON per item shipped
  (`AmmoCatalogueTest` checks each).
- Debug: `/wflib round <preset> [speed blocks/tick] [count]` (count > 1: +-15 deg spread; motor => rocket).
- Rounds hold while the level is frozen (`tick freeze`), server resolve + client copies (1.8.4; before: server flew on, client drew them frozen or raced ahead).
