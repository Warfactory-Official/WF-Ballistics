# Explosion debris

## 48. What it is

The chunks of ground an explosion throws out: real block geometry, cut **only from blocks the blast removed**.

- `Removals` (`ClientLevel.setBlocksDirty` mixin) records every non-air -> air the client sees, 40 ticks kept.
- `explosion_large` packet precedes the block updates (sent at tick end) => `BlastDebris` resolves 3 ticks later:
  claims removals within 24 blocks, groups them into 2x2x2 cells, cell = one chunk thrown from where it stood (away
  from the blast in plan, 45-70 deg up, both in the vent frame). Nothing removed => no chunks.
- Vent frame (`BlastFrame`, client): +y -> open side, from 26 rays x 8 samples (0.5) free fraction; |sum| < 1.5
  (open air, lone block) => +y. Rotates `explosion_small` / `explosion_large` / `instanced_smoke`: cloud launch,
  shockwave ring plane, flash lift, debris cone. Wall hit => vents out of the wall, ring in the wall plane; flat
  ground unchanged (symmetric rays). Buoyancy stays +y. Not rotated: `mining_blast`, foam.
- Chunk cap `debrisCount x 3`; up to 6 cells baked as models (one draw each), chunks share them round robin.
- Fallback (Flywheel off): same cells through hand-ticked `Debris` / `DebrisManager`.

Contact **predicted at spawn** (GemRender `docs/INTEGRATION.md` §7): chunk = axis-aligned box of half extent
`JarModels.Rubble.radius` (farthest filled face); extent sweep stops its side at walls. `debris.vert` eases yaw +
roll to the nearest quarter turn as it settles: tilted => corners sink into floor and walls.

## 49. How a blast is put together

| | |
|---|---|
| `Removals` / `BlastDebris` | removed blocks -> cells -> chunks |
| `JarModels.bake` | cell as a Flywheel model, centred, plus its half extent |
| `WFParticleStyles.debris()` | gravity 0.15/tick, no drag, `stopsOnContact()` |
| `InstancedDebrisEffect` | one model, one emitter, one pool: **one draw call**; per-chunk origins |
| `debris.vert` | moves, tumbles, settles square; leaves the bake's colour and light alone |

**`debris.vert` exists to not do things.** GemRender's stock mesh particle points the model along its
velocity and overwrites `flw_vertexColor` and `flw_vertexLight` with the style's. That is right for a
spark, whose colour *is* the particle, and wrong for a piece of terrain: the block bake has already put
the face shading, the biome tint and the jar's baked sky light in those channels. Tumbles on two axes using
`spinPhase` and `tintScale` as the phases (a chunk's colour is its blocks; the tint float is spare).

### Blast-killed mobs

`EntityProcessorCross` (every AEF blast): a mob alive before the blow, dead after => `BlastCorpses.launch` along
its knockback (`0.5 + 1.6k` blocks/tick + `0.45 + 0.6k` up). `LivingEntity.tickDeath` held while airborne (<= 100
ticks); landing => `splatter` effect (`WFBursts.bloodSpray` + `bloodPool` decals, none in water) + corpse removed
(loot/XP already dropped at death). Not thrown: players, NoAI mobs (never move), burning corpses (cremated).

REDACTED

REDACTED
REDACTED
REDACTED

- debris **lands and stays**: two frames a second apart differ by 0.36% of their pixels, all of it the
  smoke plume;
- it lands **on the surface**, scattered around the blast at the expected radius, and close up the chunks
  REDACTED
- **49 simultaneous blasts, about 1 500 colliding mesh particles: 163 fps against a 169 fps empty-scene
  REDACTED
  REDACTED

REDACTED
REDACTED
REDACTED

REDACTED
REDACTED
REDACTED

