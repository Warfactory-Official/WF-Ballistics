# WF Ballistics

A guided-missile and area-effect explosion library for **Minecraft 1.20.1 / Forge**.

WF Ballistics provides a fully server-simulated missile entity - with an oriented hitbox, phased
flight (ascent → cruise → attack), fuel, warheads, and active interception - plus a pluggable
explosion framework and the client-side instanced rendering to draw it all. It ships a playable set
of missiles, launchers, and point-defense turrets, but it is built as a **library**: nearly every
behaviour is registered through a small registry and is meant to be extended by other mods.

| | |
|---|---|
| Minecraft | 1.20.1 |
| Loader | Forge 47.4+ |
| Java | 17 |
| Rendering | [Flywheel](https://github.com/Jozufozu/Flywheel) 1.0.x (client) |
| License | GPL-3.0 |

---

## What's in the box

- **Guided missiles** (`MissileEntity`) - a `Projectile` that integrates its own flight each tick with
  an oriented bounding box (`util/OBB`) for accurate, rotation-aware hit detection, a per-tick turn-rate
  limit, fuel that burns down to a ballistic fall, arming distance, and a health pool that interceptors
  chip away at.
- **Phased, swappable flight** - each missile composes one `FlightStage` per phase (`ASCEND`, `CRUISE`,
  `ATTACK`). Stages include a gravity-turn ascent, terrain-following / high-altitude cruise, a loitering
  orbit (Shahed-style drone), attack runs, and vertical terminal dives.
- **Warheads** - standard HE, mini-nuke, airburst fragmentation, recursive cluster fragmentation, chemical
  gas, a no-op interceptor payload, and inert duds. Each warhead can define a cheaper "neutralised" effect
  used when the missile is shot down mid-air.
- **Active defense** - CIWS turrets (hitscan) and launch-and-forget interceptor batteries (normal /
  supersonic), plus predictive friendly deconfliction so a swarm doesn't ram itself. Stealth missiles are
  only briefly detectable by automatic defenses.
- **Off-world simulation** - missiles far from any player offload from the world into a cheap analytic
  simulation (`sim/`), then rematerialize into real entities near listeners (players, turrets, launchers).
  This keeps long-range strikes cheap without unloading their flight. Missiles force-load a look-ahead
  fan of chunks while in-world (`chunk/`).
- **Area-effect explosion framework** (`aef/`) - an `ExplosionAEF` whose block allocation, block/entity/
  player processing are each pluggable strategies, plus a batched multi-tick ray-marched nuke
  (`aef/nuke`).
- **Supporting systems** - a custom damage-class/resistance model (`damage/`), a custom fire type
  (`fire/`), drifting gas clouds (`entity/mist/`), a data-driven model + rotor catalogue rendered through
  Flywheel instancing (`MissileModels`, `MissileVisual`), and optional [WarForge](https://www.curseforge.com/minecraft/mc-mods/warforge)
  faction friend-or-foe (`compat/WarforgeCompat`).

---

## Project layout

```
com.wf.wfballistics
├── MissileEntity              guided-missile entity: flight, fuel, interception, detonation
├── MissileModels / MissileVisual / ModModels    Flywheel instanced OBJ rendering
├── flight/                    FlightStage + FlightStageRegistry + FlightProfile (per-phase behaviour)
├── warhead/                   WarheadRegistry (Detonation / InterceptDetonation) + payloads
├── item/                      MissilePreset + MissilePresetRegistry (launch-ready configs → items)
├── sim/                       off-world simulation, listeners, collision prediction, tunables
├── chunk/                     MissileChunkLoader (force-loads the flight path)
├── block/                     launcher, CIWS turret, interceptor batteries + their block entities
├── aef/                       ExplosionAEF framework (+ aef/nuke batched ray-march)
├── damage/  fire/  fluid/     custom damage classes, fire type, kerosene fluid
├── entity/                    bomblets, gas mist, nuke-explosion + torex entities
├── compat/                    WarForge faction integration (soft reflection facade)
├── config/                    WFConfig (Forge ModConfig) mirrored into MissileSimConfig statics
└── mixin/ · network/ · client/   engine hooks, packets, client-only rendering/particles
```

Everything is wired up from `WFBallistics` (the `@Mod` entry point) and its registries.

---

## Extending it

All extension points follow the same shape: a static registry keyed by a short stable string id that is
persisted on the missile, so a payload/stage/preset survives save-load and can be selected at runtime.
Register during mod construction (before registries freeze).

### Add a warhead

A warhead is a `Detonation` - given the missile and the impact position it produces the effect. Optionally
pair it with an `InterceptDetonation`, the cheaper effect used when the missile is destroyed mid-air.

```java
WarheadRegistry.register("my_warhead", (missile, pos) -> {
    Level level = missile.level();
    if (level.isClientSide) return;
    // ... spawn your blast / effect at pos ...
});
```

### Add a flight stage

A `FlightStage` returns the desired velocity for a tick (`guide`) and decides when to hand off to the next
phase (`next`). Register it for the phase(s) it flies; the first stage registered for a phase is its default.

```java
FlightStageRegistry.register(MissileEntity.Phase.CRUISE, myCruiseStage);
```

### Add a missile preset (→ item)

A `MissilePreset` is a launch-ready configuration (model, warhead, cruise mode, speed, fuel, flight stages,
etc.). `ModItems` turns each registered preset into a carryable launcher item.

```java
MissilePresetRegistry.register(
    MissilePreset.builder("my_missile", /*model*/ "v2", /*warhead*/ "my_warhead")
        .terrainFollow(24.0).cruiseSpeed(1.0)
        .fuel(MissileEntity.FuelType.LIQUID, 1500)
        .build());
```

### Customize an explosion

`ExplosionAEF` composes independent strategies - swap any of them:

```java
var blast = new ExplosionAEF(level, x, y, z, radius);
blast.setBlockAllocator(new BlockAllocatorStandard(32)); // which blocks are in range
blast.setBlockProcessor(new BlockProcessorStandard());   // what happens to them
blast.setPlayerProcessor(new PlayerProcessorStandard());
blast.explode();
```

Other extension points: `MissileAttitudeRegistry` (how a model orients to its heading, client-side),
`MissileModels` (the OBJ model + rotor catalogue), and `IMissileListener` / `MissileListenerRegistry`
(register a source that rematerializes nearby off-world missiles). Simulation and defense tunables live in
`sim/MissileSimConfig`; user-facing options in `config/WFConfig`.

---

## Building

Standard ForgeGradle project:

```bash
./gradlew build            # produces the mod jar in build/libs
./gradlew runClient        # launch a dev client
./gradlew runServer        # launch a dev server
./gradlew runData          # run data generators
```

The built jar bundles Flywheel via `jarJar`. `spark` is a runtime-only profiling dependency (pulled from
CurseMaven). Mappings are Mojang official for 1.20.1.

### Depending on it

WF Ballistics publishes with `maven-publish` to `mcmodsrepo/`. In development, add the deobfuscated jar as
a dependency and register your warheads/stages/presets from your mod's constructor. Because the registries
are keyed by string id and persisted on the entity, content you add is automatically restored on load and
selectable in the missile emitter GUI.

---

## Credits & license

- Authors: **MrNorwood**, **IGoByLotsOfNames**.
- The AEF (originally VNT (bobcat why you choose such dumb names for stuff)), particle effects: **HBMBobcat**
- Missile models (placeholders): **HBMBobcat**
- Licensed under **GPL-3.0**.
