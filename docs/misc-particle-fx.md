# Everything else that was a particle

## 51. What moved, and what did not

Five hand-ticked particle families moved onto GemRender's instancer. Each is a straight conversion of the
class it replaces, and the two conversions that apply throughout are worth stating once: a vanilla
particle's velocities are per *tick* and a closed form's are per second, so they are multiplied by twenty;
and a vanilla `quadSize` is a **half**-extent while GemRender's quad is one `size` across, so sizes are
doubled.

| was | is now | where |
|---|---|---|
| `RocketFlameParticle` (the blast fireball) | `WFBursts.cloud` | `explosion_large` |
| `AshParticle` | `WFBursts.ash` | `ashes` |
| `BlockShrapnelParticle` | `WFBursts.shrapnel` | `explosion_small` |
| `SmokePlumeParticle` | `WFBursts.plume` | `mining_blast` |
| `BlockDebrisParticle` | `WFBursts.blockChunks` | `mining_blast` |

All five are the same shape (a burst of particles thrown from a point, written once and never touched),
so they share one `InstancedBurst` rather than five copies of the same eleven lines. What each spawns
lives in `WFBursts`, next to the numbers it was converted from. Each keeps its hand-ticked original as
the fallback for when the Flywheel backend is off.

**`ShockwaveParticle` did not move**, and should not. It is one particle per blast, at two call sites.
There is nothing to instance. Neither did the vanilla types (`ELECTRIC_SPARK`, `FLAME`, `SMOKE`, `POOF`,
`CRIT`, `BLOCK`): small counts of vanilla's own look, several of them sent straight from the server with
no client effect to hang an emitter off.

## 52. The mining blast is one packet now

`MiningExplosion` used to send its smoke a particle at a time. The directed form of
`ClientboundLevelParticlesPacket` is the zero-count form (one packet carries one particle), so a
radius-five charge sent **ninety-odd packets to every player within 256 blocks**, plus a burst per broken
block type for the flying chunks.

What the client actually needs is the seed of the thing: how big the blast was, and which blocks came
apart. It draws the same smoke from the same distribution either way, seeded from the blast's own
position so that everyone watching one sees the same smoke, which was true before only because the
server was drawing the numbers. `mining_blast` carries a radius and up to three block-state ids, and the
client decides whether to draw it instanced or hand-ticked.

Three block types is a cap, not the number that happened to fit: one type is one model and one draw call,
and a charge that clips the corner of a dozen different blocks does not look a dozen times better for it.

**The chunks shatter without being ticked.** `BlockDebrisParticle` vanished on contact and left sixteen
vanilla crack particles where it hit. An instanced chunk has no tick in which to notice it has landed,
but it does not need one, because the landing was worked out when it was thrown. `diesOnContact` is a
shorter life, `emitter.spawn` hands back the contact that produced it, and `ShatterScheduler` holds the
crack burst until the moment the chunk gets there. Capped at 300 outstanding, because the whole burst is
now known at once rather than discovered a chunk at a time.

REDACTED

REDACTED
REDACTED

- **block chunks** fly out of the crater as tumbling stone cubes and vanish where they land: 120 spawned
  REDACTED
  REDACTED
- **shrapnel** chips fly, land and stay put on the stage, textured from the block atlas;
- **ash** falls for a second or two and settles flat on the ground, and is still lying there six seconds
  later;
- **the fireball** is an orange additive cluster at the blast;
- **plumes** hang around the crater instead of leaving it.

REDACTED

- **Plumes flew away and never came back.** `SmokePlumeParticle` gets vanilla's `friction`, which is
  REDACTED
  REDACTED
- **One `InstanceType` per burst instead of one per kind.** Flywheel keys its instancers on the
  REDACTED
  REDACTED

REDACTED
REDACTED
REDACTED
REDACTED

