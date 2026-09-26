# Designing and Balancing Missiles

A practical, detailed guide to building your own missiles in WFLib: what every knob does, how the
knobs interact, and how to reach a specific role (fast striker, terrain-hugging cruise, loitering drone,
stealthy infiltrator, interceptor) without breaking the balance.

If you only read one thing: a missile is a **budget**. Fuel is the currency, and speed, altitude, evasion,
and range all spend from it. Design is deciding where that budget goes.

---

## 1. The missile state machine

Every missile flies through three **phases**, and each phase is driven by a swappable **stage**:

```
ASCEND  ->  CRUISE  ->  ATTACK
(climb)     (transit)    (terminal dive)
```

- **ASCEND**: boosts up off the pad and pitches over toward the target (a gravity turn). Ends when it
  reaches its safe/cruise altitude.
- **CRUISE**: flies to the target holding an altitude, either hugging terrain or at a fixed height. Ends when
  it closes within the braking range (30 blocks horizontal) of the target.
- **ATTACK**: the terminal dive. Ends on impact.

Each phase runs one stage that you pick per missile. The stage decides the desired velocity; the entity then
applies turn-rate limiting, thrust (accel/decel), and continuous collision. Because stages are swappable, you
turn a cruise missile into a loitering drone by swapping the cruise stage, without touching anything else.

### The per-tick pipeline (why your numbers behave the way they do)

```
stage.guide()            desired velocity for this tick (direction + target speed)
  -> constrainTurn()     rotate heading at most maxTurnRate radians this tick
  -> evade boost         if dodging: scale speed x2 and optionally jink sideways
  -> applyThrust()       ramp ACTUAL speed toward the target speed at accel (up) / decel (down)
  -> move + collision    swept, non-tunneling hit check over the traversed segment
```

Code: `MissileEntity` = body (model, OBB, synced look, allegiance, telemetry); state + behaviour in
`com.wf.wflib.missile`, one NBT sub-tag each; tick order in `MissileTicker`.

| component | owns |
|---|---|
| `MissileFlight` | target, phase, stages, cruise mode/medium, clearance, dive, turn limit |
| `MissileMotor` | fuel, accel/decel, evasion sprint |
| `MissileFuze` | warhead, arming, airburst, impact, `detonate` |
| `MissileDamage` | health, shoot-down actions, spin-out, dud + defuse |
| `MissileSwarm` | formation, saturation break, friendly avoidance |
| `MissileInterceptor` | interceptor targeting, lead, kill roll |
| `MissileSignature` | rcs, evasion |
| `MissileSeeker` | designated target, TV operator + let-go lock |

Two consequences worth internalizing:

1. A stage returns a *target* speed. The missile does not instantly reach it. `acceleration` governs how fast
   actual speed climbs toward it, `deceleration` how fast it sheds. A high top speed with a low acceleration
   spends most of a short flight still speeding up.
2. Turn rate is a hard cap on heading change. A long airframe turns slowly (see `turnRate` below), so a fast,
   long missile cannot make sharp corrections and will overshoot a moving target.

---

## 2. Two ways to build a missile

**MissilePreset (recommended for shippable missiles).** A frozen, launch-ready config that becomes a
carryable item and a launcher option. Registered during mod construction. This is how all the built-ins are
defined (see `MissilePresetRegistry.bootstrap()`), and it is the surface you should use for anything a player
picks up and fires.

```java
MissilePresetRegistry.register(
    MissilePreset.builder("my_missile", "v2", "standard")  // id, model, warhead
        .terrainFollow(24.0)
        .cruiseSpeed(1.0)
        .fuel(MissileEntity.FuelType.LIQUID, 1500)
        .build());
```

**`MissileBuilder` via `MissileEntity.builder` (programmatic / one-off).** The full API a preset wraps. Use it when spawning a
missile from code (commands, dispensers, warheads that spawn child missiles). It exposes a few variables the
preset does not, notably `ascentSpeed(...)`.

```java
MissileEntity m = MissileEntity.builder(ModEntities.STEALTH_MISSILE.get(), level)
        .target(pos)
        .highAltitude(260.0)
        .cruiseSpeed(6.0)
        .acceleration(0.4).deceleration(0.5)
        .fuel(MissileEntity.FuelType.SOLID, 1600)
        .evasion(0.2f)
        .build();
level.addFreshEntity(m);
```

The preset builder covers the common design space. Reach for the entity builder when you need ascent tuning
or per-launch state (a designated target, a swarm id, an interceptor lock).

---

## 3. The knobs, one by one

### Warhead (`detonation` / preset warhead id)

What the missile does on impact. Pick by registered id. Built-ins:

| id              | effect                                                            |
|-----------------|-------------------------------------------------------------------|
| `standard`      | conventional blast (the default)                                  |
| `mininuke`      | large nuclear blast                                               |
| `fragmentation` | scatters `fragmentCount` bomblets (pairs well with an airburst)   |
| `recursive_frag`| splits into child missiles `splitDepth` times before the leaves detonate |
| `gas`           | chemical cloud                                                    |
| `fire`          | incendiary                                                        |
| `emp`           | drains/disables energy systems, no physical blast                 |
| `interceptor`   | the neutralize-on-hit effect used by anti-missile interceptors    |
| `inert`         | no detonation (test rounds)                                       |

The warhead is chosen by id so it survives save/load and offload-to-simulation. `fragmentation` and
`recursive_frag` read `fragmentCount` / `splitDepth`; the others ignore them.

### Model (`model`)

The visual airframe (`v2`, `strong`, `huge`, `atlas`, `shahed`, `stealth`, `abm`, `thermo`, `neon`, ...).
Pylon-sized: `atgm` (1.63 x 0.18), `guided_rocket` (1.87 x 0.07); every other airframe is 4+ blocks long.
The model is not only cosmetic: **turn rate defaults to `1.0 / model_length`**, so a longer model is less
nimble unless you override `turnRate`. Match the model to the role (a stubby drone turns tighter than a long
ballistic body).

### Cruise mode and altitude (`terrainFollow(clearance)` vs `highAltitude(altitude)`)

- **`terrainFollow(clearance)`**: hugs the ground, holding `clearance` blocks above the terrain ahead
  (scanned with lookahead, leaves ignored). Low, hard to see coming, and it climbs over rising ground rather
  than into it. Good for cruise missiles and drones. Typical clearance 16 to 30.
- **`highAltitude(altitude)`**: holds a fixed Y. Now floored to terrain so it still clears mountains, but
  otherwise it flies straight and high. Good for ballistic and long-range strikes. Typical 200 to 300.

Terrain-follow trades exposure for a longer, more fuel-hungry path over hills. High-altitude is a straighter,
cheaper path but visible and interceptable for longer.

### Cruise speed (`cruiseSpeed`, blocks/tick)

The single most important stat. It sets transit speed, and it is the reference for almost everything else:

- **Range is roughly `cruiseSpeed x fuelTicks` blocks.** A 1.0/1500 cruise missile reaches ~1500 blocks; a
  6.0/1600 supersonic reaches ~9600.
- **`cruiseSpeed >= 2.5` is classed supersonic** (`SUPERSONIC_SPEED`). Supersonic missiles trigger sonic
  booms, force slow interceptors into low-odds crossing shots, and are engaged by the supersonic interceptor
  battery rather than the standard one.
- Default is `1.0`. Presets range from `0.8` (loiter drone) to `12.0` (hypersonic).

Faster is not free: it drains fuel per block of range the same, but it demands more `acceleration` to actually
reach that speed, and a higher `turnRate` or it cannot corner.

### Ascent speed (`ascentSpeed`, entity builder only)

The climb speed off the pad. Defaults to `max(1.5, cruiseSpeed x 1.5)`, so a fast missile climbs fast without
you setting anything. Override only when you want a climb rate decoupled from cruise (for example a slow-cruise
drone that still needs a brisk launch). The ascent is a constant-speed gravity turn: it holds this speed while
rotating from vertical toward the target, then hands to cruise.

### Acceleration and deceleration (`accel(acceleration, deceleration)`, blocks/tick^2)

How fast actual speed changes toward what the stage asks for. Defaults 0.15 / 0.25.

- **Scale acceleration with cruise speed.** A 12.0 hypersonic with 0.15 accel would spend the whole flight
  spooling up. The presets scale it: 0.4 at speed 5 to 6, 0.8 at 12, 1.3 for the hypersonic interceptor.
  Rule of thumb: `acceleration ~= cruiseSpeed / 15` gets you to speed in about 15 ticks.
- The terminal dive uses a higher internal acceleration automatically, so you do not need to raise it just for
  a snappy plunge; set it for the cruise spool-up.

### Turn rate (`turnRate`, radians/tick)

Max heading change per tick. Default `1.0 / model_length`. Higher is more agile.

- A fast missile needs a higher turn rate to hit a moving target or correct a terrain-follow path; otherwise it
  arcs wide and overshoots.
- Interceptors live and die on this: they are set 0.45 to 0.6, near pure pursuit.
- Do not over-crank it on a long ballistic body; unrealistic snap-turns look wrong and let it cheat terrain.

### Fuel (`fuel(type, ticks)`)

**The master constraint.** Fuel is measured in ticks of powered flight, one burned per tick. When it runs
dry the missile stops thrusting and falls ballistically (keeping horizontal momentum). It still detonates on
impact, so a dry missile is a dumb bomb, not a dud.

- **Range = speed x fuel.** Size the tank to the intended reach plus climb and margin.
- Type (`SOLID` / `LIQUID`) is currently flavor (identical burn); use it for theming.
- **Evasion spends fuel.** Each evade dodge costs 150 fuel (150 ticks of flight). A missile meant to dodge
  repeatedly needs a bigger tank, which is the intended cost of a high-evasion tier.
- Defaults to 1200. Presets run 600 (short-range interceptor) to 2500 (nuclear).

### Health (`health`)

The interception damage pool. CIWS fire and interceptor chip-damage whittle it; at zero the missile is downed
(and fizzles rather than dropping a full warhead). Higher = harder to shoot down.

- Default 50. Fragile drone 15, tough ballistic 60 to 90, interceptor 20.
- Toughness is an alternative to evasion: soak hits instead of dodging them. It does not cost fuel, but it does
  not help against a clean intercept roll, only against attrition (many grazes / CIWS).

### Shot down (`downedAction`, `dudChance`)

| action | behaviour |
|---|---|
| `FIZZLE` | intercept effect in mid-air |
| `DETONATE` | full warhead in mid-air |
| `CRASH` (default) | motor cut, ballistic fall, intercept effect on impact |
| `POWER_LOSS` | as `CRASH`, heavy horizontal drag |
| `SPIN_OUT` | guidance lost, motor burns 20 to 80 more ticks; turn rate random-walks (corkscrew, loop, dive), smoke + roll; then ballistic. Full warhead on impact, never mid-air |

- `dudChance` (default 0.15, 0 disables): per downed ground impact of `CRASH` / `POWER_LOSS` / `SPIN_OUT`. Dud = missile at rest, nose 30% buried, live.
- Dud: any explosion sets it off; other hits 25% each. Defuse: crouch + `#wflib:defuser` item, hold 100 ticks, 10% goes off. Defused = removed, no drop.
- Picker: `downedAction(DownedActionPicker.weighted(...))` rolls the action per launch.

### Flight stages (`ascentStage`, `cruiseStage`, `attackStage`)

Swap the behavior of a phase. Registered options:

| phase  | id          | behavior                                                                 |
|--------|-------------|--------------------------------------------------------------------------|
| ASCEND | `ascent`    | default gravity-turn climb                                               |
| ASCEND | `intercept` | vertical launch-clear then homing (interceptors)                         |
| ASCEND | `air_launch`| rail/pylon drop: 8 ticks motor cold on launch velocity, then ATTACK; launch < 0.3 b/t => ATTACK at once. Launcher sets velocity + owner; no owner hit during ASCEND |
| CRUISE | `cruise`    | default: fly to target holding altitude, hand off at 30 blocks           |
| CRUISE | `loiter`    | fly to the area, orbit it (radius 24) for ~200 ticks, then dive          |
| CRUISE | `intercept` | 3D homing on a moving target                                             |
| ATTACK | `attack`    | default terminal dive (terminal speed ~14, bleeds horizontal over 30 blk)|
| ATTACK | `dive`      | steep top-attack plunge (terminal ~18, bleeds over 12 blocks)            |
| ATTACK | `intercept` | homing (interceptors)                                                    |
| ATTACK | `direct`    | pure pursuit on the aim point, no terrain profile (TV / line-of-sight rounds) |

The `loiter` cruise + `dive` attack combination is how you build a drone / loitering munition. Pair it with a
`designatedTarget` (entity builder) and it orbits until the target is present, then pounces straight down.

### Airburst (`explosionOffset`, blocks)

Detonate this many blocks above the target during the dive instead of on contact. 0 is a contact/ground
detonation. Use it to spread `fragmentation` bomblets over an area, or to air-detonate `gas`. Fragmentation and
cluster presets use 30 and 16 respectively.

### Fragmentation and splitting (`fragmentCount`, `splitDepth`)

- `fragmentCount`: bomblets a `fragmentation` warhead scatters (default 24). More = wider, denser saturation.
- `splitDepth`: for `recursive_frag`, how many generations it splits into child missiles before the leaves do
  a real blast. Each generation multiplies the count, so keep it low (1 to 2). Pair with an airburst so it
  splits in the air.

### Survivability: stealth and evasion

Three independent levers that make a missile harder to stop:

- **`stealth(true)`**: invisible to automatic detection (interceptor acquisition, batteries, CIWS) except
  within 32 blocks, and even then only 25% per scan. It is a tiny engagement window, not true invisibility;
  a manual UUID lock bypasses it. Best on slow infiltrators that rely on not being seen.
- **`evasion(0..1)`**: chance to shrug off an interception attempt by burning fuel for a speed burst that turns
  the hit into a miss. Amplified 1.5x during the terminal dive. The escape also scales with how much the
  missile actually outruns the interceptor, so a fast or diving missile escapes more reliably. Costs 150 fuel
  per dodge. This is the main "tier" lever: 0.15 to 0.3 for good missiles, 0.6 for a MaRV.
- **`evasiveManeuver(true)`**: makes an evade also jink sideways (a hard lateral break away from the
  interceptor) instead of only sprinting straight, so the dodge genuinely displaces it off the interceptor's
  lead. Needs `evasion` to fire. Reserve for top-tier missiles; it makes them much harder to catch.

### Interceptor-specific (`interceptor`, `interceptMode`, `lockTarget`, `interceptChance`)

An interceptor homes on another missile and resolves a kill by a probability roll at closest approach
(`interceptChance`, default 0.90) rather than carrying a warhead. It launches already armed and cruising.

- Speed must exceed the target's to get a proper timed shot; otherwise it is reduced to a low-odds crossing
  shot (0.35x). That is why the interceptor tiers exist: 4.0 (subsonic), 9.0 (supersonic), 18.0 (hypersonic).
- Turn rate is high (near pure pursuit), health and fuel are low (it is a short-lived one-shot).
- `interceptMode` NEAREST re-acquires the closest hostile each tick; LOCK homes one specific UUID.

---

### Seeker and airframe physics (`seekerMode`, `proximity`, `impactDamage`, `threat`, `blast`, `drag`, `gLimit`)

| `SeekerMode` | lock kept by | defeated by |
|---|---|---|
| `DESIGNATED` | designated entity / aim point | nothing |
| `INFRARED` | LOS in `fov` cone | opaque blocks, `SightBlocker`; `SeekerDecoy` THERMAL |
| `OPTICAL` | LOS in cone | opaque blocks, `SightBlocker` |
| `SEMI_ACTIVE_RADAR` | launcher `TargetIlluminator` each tick | launcher losing track; decoy RADAR |
| `ACTIVE_RADAR` | illuminator until `radarRange`, then own scan (rcs > 0, cone) | 60 ticks lockless => fizzle; decoy RADAR |
| `BEAM_RIDING` | launcher `BeamSource`: aim 4 ticks ahead on the line | no beam => flies on |

- Launcher = entity with UUID `controlId`, else owner. Lock => aim at the lead point. Emitting modes post `SeekerLockEvent` every 2 ticks.
- Decoy seduction: one roll per decoy per missile, p = strength / (strength + lock signature in the band).
- Seeker lock held => no sim offload. A missile never hits its launcher (`controlId` root vehicle).
- `drag(k, glides)`: dv = -k v^2 per tick when burnt out; `glides` => guided until 1.0 b/t, then ballistic. Glide also
  dv = -g * dir.y (g = 9.8/400): dive gains, climb loses (`GlideGameTest`).
- `look(id)`: client draws `RoundRenderers.get(id)` instead of the airframe while its `hasLook()`; airframe `model` still
  sizes the hitbox and turn agility.
- `gLimit(a, ref)`: turn = a * min(1, v^2/ref^2) / v rad/tick, replaces `turnRate`.
- `proximity(r)`: goes off within r of any hittable entity. `impactDamage` hits the struck entity before the warhead.

## 4. How it all fits together (the balance triangle)

Think of three competing goals and one budget:

```
        SPEED  (reach fast, force crossing shots, but drinks fuel and needs accel + turn)
         /  \
        /    \
   RANGE ---- SURVIVABILITY  (evasion consumes fuel; stealth trades speed; health is free but only vs attrition)
        \    /
         \  /
         FUEL  (the shared budget: range = speed x fuel, and every dodge costs 150)
```

Design heuristics that keep a missile coherent:

1. **Set the role first**, then the speed, then everything else follows.
2. **`acceleration ~= cruiseSpeed / 15`** so the missile actually reaches its top speed.
3. **`fuelTicks >= intendedRange / cruiseSpeed + climb margin`**, and add `150 x expectedDodges` if it has
   evasion.
4. **Faster missiles want more turn rate** or they cannot correct their path; slower ones can be sluggish.
5. **Pick one survivability identity**: fast-and-evasive (hypersonic/MaRV), or slow-and-unseen (stealth), or
   tanky-and-cheap (high health, low everything). Stacking all three makes an un-counterable missile and
   flattens the interceptor game.
6. **Counterplay must exist.** Every missile should be beatable by *some* interceptor tier. If you build a
   speed-18 evasion-0.9 missile, you have effectively removed interceptors from the fight.

---

## 5. Worked examples (annotated built-ins)

**Subsonic cruise (the baseline).** Cheap, low, unhurried.
```java
MissilePreset.builder("cruise", "v2", "standard")
    .terrainFollow(24.0).cruiseSpeed(1.0).fuel(LIQUID, 1500)
// ~1500 block range, hugs terrain, default accel/turn. The reference every other design is measured against.
```

**Hypersonic (speed as defense).** Outruns most interceptors; pays in fuel and a big tank.
```java
MissilePreset.builder("hypersonic", "strong", "standard")
    .highAltitude(300.0).cruiseSpeed(12.0).health(60.0f)
    .accel(0.8, 0.9).fuel(SOLID, 2000).evasion(0.3f).evasiveManeuver()
// accel scaled to the speed, high altitude for a straight line, evasion + jink so even a fast interceptor struggles.
```

**MaRV (evasion as defense).** Not the fastest, but dodges hard and often.
```java
MissilePreset.builder("marv", "atlas_thermo", "standard")
    .highAltitude(280.0).cruiseSpeed(7.0).health(70.0f)
    .accel(0.5, 0.6).fuel(SOLID, 1800).evasion(0.6f).evasiveManeuver()
// evasion 0.6 means frequent dodges; the 1800 tank pays for the 150-fuel bursts. Tough too, as a fallback.
```

**Loitering drone (patience as a weapon).** Slow, fragile, cheap, waits over the target.
```java
MissilePreset.builder("loiter", "shahed", "fragmentation")
    .terrainFollow(30.0).cruiseSpeed(0.8)
    .cruiseStage("loiter").attackStage("dive")
    .accel(0.08, 0.15).fuel(LIQUID, 2400).fragmentCount(16).health(15.0f)
// loiter cruise + steep dive, huge tank for orbit time, low health because it relies on not being worth intercepting.
```

**Stealth infiltrator (not being seen).** Slow and low, but nearly invisible to automatic defenses.
```java
MissilePreset.builder("stealth", "stealth", "standard")
    .terrainFollow(24.0).cruiseSpeed(1.2).stealth().evasion(0.3f).fuel(LIQUID, 1600)
// stealth is the primary defense; modest evasion covers the brief detection window.
```

**Interceptor (the counter).** Fast, nimble, disposable.
```java
MissilePreset.builder("interceptor_supersonic", "abm", "interceptor")
    .highAltitude(220.0).cruiseSpeed(9.0).turnRate(0.5).health(20.0f)
    .interceptor(0.90f).accel(0.9, 0.9).fuel(SOLID, 700)
// speed to run down supersonic threats, high turn for pursuit, low fuel/health because it is a one-shot.
```

---

## 6. Recommended ranges (quick reference)

| Knob            | Typical range        | Notes                                              |
|-----------------|----------------------|----------------------------------------------------|
| `cruiseSpeed`   | 0.8 - 12.0           | >= 2.5 is supersonic                               |
| `acceleration`  | ~ cruiseSpeed / 15   | 0.15 default; scale up with speed                  |
| `deceleration`  | acceleration + 0.05  | slightly above accel                               |
| `turnRate`      | 0.3 - 0.8            | interceptors 0.45 - 0.6; leave default otherwise   |
| `fuelTicks`     | 600 - 2500           | range ~= speed x fuel; +150 per expected dodge     |
| `health`        | 15 - 90              | 50 default; 20 for interceptors                    |
| `evasion`       | 0.0 - 0.6            | costs 150 fuel per dodge; 0.6 is top tier          |
| `terrainFollow` | 16 - 30 clearance    | lower is stealthier but risks clipping             |
| `highAltitude`  | 200 - 300            | now floored to terrain                             |
| `fragmentCount` | 8 - 32               | pair with an airburst                              |
| `splitDepth`    | 1 - 2                | counts multiply per generation                     |

---

## 7. Coordinated swarms and the off-world simulation

Missiles fired far from any target or player **offload to a lightweight off-world simulation** after ~100
ticks of cruise, once they are more than 1000 blocks from the target and clear of any player/listener. The
simulation advances them cheaply and rematerializes the real entity when it nears the target or a player. This
is invisible to design: your missile's speed, fuel, and warhead all carry through and travel time matches.
An offloaded missile carries its full entity NBT (`SimMissile.body`) and comes back from it; synthetic records
(orbital drops, sim interceptors) have no body and are rebuilt from their fields.

### 7a. Sim registry (`com.wf.wflib.sim`)

Every off-world tier is a `SimKind` registered in `WFLib.commonSetup` (`SimKinds`); records per level in one
SavedData `wflib_sims` (one sub-tag per persistent kind).

| step | thread | when |
|---|---|---|
| `prepare` | tick | `LevelTickEvent.Pre`; world reads the pass needs |
| `advance` | worker if `async()`, else inline | Pre, overlaps the vanilla level tick; no world/entity access |
| `resolve` | tick | Post, slot `EARLY` (before drone AI) or `LATE` (after colony/squads) |

- During a pass the list belongs to the worker: `SimTier.view()`/`remove` await it; `add` queues in an inbox
  merged at the join.
- Kinds: `wflib:missile` (async), `wflib:round` (transient; collision on the tick thread, §27), `wflib:drone` (AI stays in
  `DroneAiScheduler`), `wflib:glyphid` (async unless `swarmbench simasync off`, LATE).

**Coordinated swarms offload as one object.** A swarm launched with a commander (one missile flying the
mission, the rest holding a wedge formation on it) will, once the whole formation is in cruise and clear,
offload as a single simulated object: the commander drives the track and each member rides along at its
formation offset. When the swarm nears its target or a player, the entire formation rematerializes together,
in formation, and resumes its coordinated attack (the commander dives, the rest break to a saturation spread).
Members burn fuel in the sim just as they would in the air, and a member that runs dry is dropped from the
formation.

Design implication: a coordinated swarm is cheap to fly over long distances, so long-range saturation strikes
are viable. Give swarm members a fuel tank sized for the full distance; a member that runs dry mid-sim is lost.

---

## 8. Registering your own preset

Add it during mod construction, before items are frozen (the built-ins are added in
`MissilePresetRegistry.bootstrap()`):

```java
MissilePresetRegistry.register(
    MissilePreset.builder("my_striker", "thermo", "standard")
        .highAltitude(260.0)
        .cruiseSpeed(6.0)
        .accel(0.4, 0.5)
        .fuel(MissileEntity.FuelType.SOLID, 1600)
        .evasion(0.2f)
        .health(60.0f)
        .build());
```

The preset becomes a carryable item and a launcher/dispenser option automatically. Only `id`, `model`, and
`warhead` are required; everything else takes a sensible default.

For a fully custom flight path (a non-default ascent, a designated-target drone, an interceptor lock), build
the entity directly with `MissileEntity.builder(...)` and add it to the level yourself.

---

## 9. Testing your design

- Fire it and watch the arc. If it never reaches top speed, raise `acceleration`. If it overshoots a moving
  target or clips terrain on a corner, raise `turnRate`.
- If it falls out of the sky short of the target, the tank is too small for `speed x distance` (remember climb
  and any loiter time).
- Against interceptors: a missile that is never caught is over-tuned (too fast + too evasive); a missile always
  caught needs speed (to force crossing shots) or evasion (to dodge) or health (to soak CIWS).
- Balance goal: every missile beatable by some interceptor tier, and every interceptor tier useful against some
  class of missile.

---

# Delivery Drones

Drones are not missiles. A missile is given a target and a warhead and flies one trajectory; a drone is given
a *mission* and works out what to do about it, with a battery deciding how much of the mission it can afford.
They share the infrastructure (chunk loading along the route, telemetry, off-world simulation) and share
nothing else.

## 10. How a drone thinks (the async AI)

All drone decisions are computed off the server thread and only *applied* on it. One tick of latency, per
dimension, driven from the same `LevelTickEvent.Post` hook the missile sim uses:

```
tick N, world thread:  1. collect finished jobs from tick N-1
                       2. adopt any route searches that finished
                       3. apply their plans      <- the only step that touches the world
                       4. snapshot every drone, group into squads
                       5. dispatch one steering job per squad, plus a route search for any drone due one
tick N, workers:       DroneBrain.planSquad(...)  -> plans      (microseconds, must land every tick)
                       DroneBrain.planRoute(...)  -> a route    (milliseconds, lands whenever it lands)
```

**The split is asserted, not assumed.** It is the property everything else rests on, and the kind that
quietly stops being true: someone moves a call, and it works fine until the server is under load. So both
crossings refuse to be on the wrong side: the A* and the squad planning throw if called from the server
thread, and the chunk reads that condense terrain throw if called from anywhere else. `drone selftest`
checks those assertions are armed by tripping them on purpose, and `drone threads` reports the thread the
searches have actually been running on:

```
> wflib drone threads
Planner pool: 6 worker(s)   assertions armed
This command is on the world thread: true (Server thread)
2 terrain search(es), last on 'ForkJoinPool-3-worker-1' in 1.3ms (worst 1.3ms)
A* is running OFF the world thread, as required.
```

That 1.3 ms is the point: on the server thread it would be a 1.3 ms hitch every time any drone replanned.

**Two kinds of job, and they must stay separate.** Steering has to land every tick or the drone coasts;
searching for a route across a few thousand terrain cells takes far longer than a tick. Fold the search into
the steering job and the scheduler, which will not re-dispatch a squad whose job is still running, stalls
every drone in that squad for the length of the search. The visible result is a flight that stutters each
time it replans. Split, the search takes as long as it needs while the steering keeps arriving on time, and a
route planned two hundred blocks ahead does not care whether it turns up a tick or ten later.

The rules that keep this safe, in `drone/ai/`:

- A worker only ever sees a `DroneSnapshot` and a `SquadView`. Neither holds a `Level`, an `Entity` or a
  `SavedData`: that is a compile-time guarantee, not a convention. **If a handler needs something from the
  world, sample it into the snapshot on the world thread** (see `TerrainSampler`); never reach for it later.
- `DronePlan` is inert data. Applying it is the only thing that mutates anything.
- Every field of `DroneAiScheduler` is read and written on the world thread only. The single thing that
  crosses threads is a `Future`, and it is *polled*, never completed into shared state, so there is nothing
  to race on.
- A plan older than `DroneAiScheduler.MAX_PLAN_AGE` is dropped and the drone coasts. A starved planner
  degrades into a hover, not into drones teleporting on stale data.
- Randomness comes from a per-drone seed in the snapshot, so decisions don't depend on thread timing.

**One job per squad, not per drone.** A single job owns every member's snapshot, so formation flight needs no
locking and no cross-thread reads. Solo drones are a squad of one, so the planner has exactly one shape to
handle.

Code: `DroneEntity` = body (model, OBB, battery, camera, allegiance, telemetry, chunk tickets); state in
`com.wf.wflib.drone`, one NBT sub-tag each:

| component | owns |
|---|---|
| `DroneFlight` | state machine, attitude (synced), cruise envelope, move/contacts/ground clamp |
| `DroneRoute` | destination, exfil, staging legs, egress dogleg, planned path |
| `DroneOrders` | program, exchange, work assignment, surveillance contacts |
| `DroneHold` | crate + hold, payload, mine rack (synced load) |
| `DroneDamage` | health, rotor loss, shoot-down, break-up, crash |
| `DroneSquadRole` | squad id, leader, formation, coordination, size |

**One brain, two carriers.** `DroneCarrier` is implemented by both `DroneEntity` and `SimDrone`, and a
snapshot is built the same way from either. An off-world drone therefore runs the *same* AI as a live one:
there is no second, simplified route model to keep in sync (contrast `SimMissileManager.advance`, which
re-implements a straight-line missile track).

## 10a. The flight model (`drone/flight/`)

Everything above decides *where a drone wants to go*. `Multirotor` decides what the airframe can actually do
about that, and its answer is the one that gets flown.

The physics is the standard near-hover quadrotor model, and it is built around one fact: **a multirotor has
exactly one actuator direction.** Its rotors only push along the airframe's own up axis, so the only way it
can accelerate sideways is to tip that axis over and spend part of its lift going where it wants to go:

```
a = T·n̂ − g·ŷ − drag(v)          n̂ = the thrust axis, tipped by the current lean
```

Solve that for level flight and you get the two equations the model runs on: `tan(tilt) = a_h / (g + a_v)`
for the lean an acceleration needs, and `T = (g + a_v) / cos(tilt)` for the thrust it costs. Everything else
follows from them rather than being declared separately:

| You change | Because |
| --- | --- |
| `maxTilt` | ...the top speed changes with it. On a multirotor those are the same fact. |
| cruise speed | ...the lean changes, so the throttle changes, so the battery drain changes. |
| cargo | ...mass rises, so climb rate falls *and* drain rises: one number, `PowerProfile.massFactor`. |

**The lean is real state, not a render flourish.** It is stored on the entity, synced to the client, used for
the hitbox, and carried in the snapshot so next tick's physics starts from it. Critically it is **rate
limited**: a real airframe cannot snap its thrust axis around, so it tips first and accelerates second, and
to stop it must tip back through level and past it. That is why a drone now leads into a turn and settles out
of one instead of changing direction instantly.

**Throttle** is thrust as a multiple of an unladen hover, so `1.0` is holding station, more is climbing or
leaning, `0` is a dead rotor. It drives three things at once:

- how fast the drone climbs: climbing *is* throttle;
- how fast the battery drains: `PowerProfile` prices it as `hover · thrust^1.5` (momentum theory) plus a
  drag term in `speed²`, so a laden climb costs several times a hover with nothing declaring that it should;
- how fast the rotors turn: thrust goes as ω², so the discs spool up and down with the drone's effort.

### Getting somewhere vs. stopping there

These are different problems and `Steering` has a separate controller for each. `cruise` commands a *speed
toward a waypoint* and keeps a floor under it, so a drone in transit never crawls: right for travelling,
and exactly wrong for arriving, because the floor means the drone is still being told to move when it is
already there. `hold` is a *position* controller whose commanded speed goes to zero with the offset, so the
drone settles and stays. `DELIVER` and `LANDING` both use it; that is what lets a drone stop dead over a drop
point and come straight down onto it.

A proportional gain alone is not enough, though. A drone one block out is still under its speed cap, so it is
told to approach at full speed right up to the point and physics does the rest. So the approach is also
capped by what the airframe could still stop from: `v = √(2·a·d)`, the same arithmetic as a car's stopping
distance, with `Airframe.brakingAccel` deliberately well under the `g·tan(maxTilt)` the drone could
theoretically pull (reaching that means having already tipped the thrust axis all the way the other way, and
`tiltRate` says that takes time the drone spends still moving).

The same fact decides **when** the approach starts: `TransitHandler` hands over to `DELIVER` at whichever is
larger of `DELIVER_ENTRY_RADIUS` and `Airframe.stoppingDistance(cruiseSpeed)`: about 14 blocks at the stock
0.75 b/t. Handing over at the old three-block arrival radius meant the drone physically could not stop, and it
spent the whole delivery sliding back and forth across the drop point instead of settling onto it.

One consequence worth knowing: because power rises faster than speed, **range peaks at a middle speed**.
Crawling wastes charge holding the drone up; sprinting wastes it pushing air. The pad's readout warns when a
commanded speed is past the airframe's limit, and `Airframe.topSpeed` solves that limit from the same model
rather than declaring it.

## 10b. Terrain navigation (`drone/nav/`)

Drones fly with `noPhysics` on: the airframe is three blocks wide, and vanilla collision on a box that size
means a drone parked on uneven ground can never climb out of it. Nothing else will keep them out of the
terrain, so three layers do, each catching what the one before it misses:

1. **The route** (`PathPlanner`). An A* over a `TerrainField`, run as its own off-thread job. It searches in
   2½D rather than 3D: a drone can be at any height, so each cell is priced by the *altitude it forces*
   rather than by whether you can stand in it, which collapses the search to a couple of thousand cells.
   Climbing costs several times more per block than travelling (`CLIMB_WEIGHT`), so the search prefers a
   valley to a ridge and will fly a long way around a mountain rather than over it, which is what a real
   delivery drone does, because altitude is battery. Terrain that would push it past `CEILING_MARGIN` is
   impassable, so a wall too tall to climb is gone around. Routes are planned one `PLAN_HORIZON` at a time;
   a longer mission comes back marked `partial` and is extended on arrival.
2. **The terrain guard** (`TerrainGuard`), every tick, against a fresh look at the ground ahead. For each
   patch in front it works out how long the drone will take to reach it and how much climb it can do in that
   time, and demands only the altitude it cannot make up later:
   `required = top + clearance − climbRate · (distance / speed)`. Taking the worst case over everything ahead
   means the drone starts climbing at the last moment it still can, so it crosses a ridge at ridge height
   instead of dragging itself over a whole valley at the height of the next mountain. Below that floor it
   climbs at up to `CLIMB_BOOST` times its normal rate and gives up ground speed to do it.
3. **The hard floor** (`DroneFlight.clampAboveGround`). The first two are prediction; this is measurement.
   Whatever they got wrong, the drone ends the tick on top of the terrain rather than inside it.

Two things that are easy to get wrong here, both of which were:

- **The look-ahead must use the drone's *ordered* speed, not its measured one.** Feed it the measured
  velocity and it oscillates: the guard trades speed for climb, the lower speed buys more time to climb, the
  requirement drops, the guard lets go, the speed returns and so does the requirement: a drone porpoising
  down the whole route at full throttle.
- **Steer at a point well ahead, but take the altitude from just ahead** (`DronePath.ALTITUDE_LEAD`). A long
  lead smooths the corners, but taking the *height* from that same point makes a drone on a slope chase an
  altitude a whole lookahead's worth of climb above it, saturate, fly high, and then reverse the lot when the
  slope does.

The terrain itself is condensed once per chunk into `TerrainCache` and copied out into an immutable
`TerrainField` for the planner: the same bargain `DroneSnapshot` makes for the drone. Chunks are never
forced to load; unknown ground is treated as flat and passable, because refusing to plan through it would
strand every drone at the edge of the loaded area.

## 10c. Keeping a flight from killing itself

Drones fly with block physics off, so nothing inherited stops two occupying the same space. Three rules
handle it, outermost first:

- **Separation** (`Cruising.separation`). The usual flocking rule: push away from any squadmate inside
  `SEPARATION_RADIUS`, harder the closer it is, capped so a crowded drone is nudged clear rather than flung.
  The radius is measured against the *hull*, not the formation, because the formation's spacing is now a
  per-mission setting and a radius pegged to it would make a loose flight avoid at long range for nothing.
  It exists for the moments formation cannot help: climbing out from one launch point, and attack runs.
- **Hull contact** (`Contacts`, applied in `DroneFlight.resolveContacts`). Two hulls that end a tick inside
  each other are pushed apart along the axis of least penetration, half the overlap from each side, capped
  per tick. **Position only**: it never touches velocity, and never blocks the movement on its way to being
  taken. Both of those were tried and both weld a squad to the sky: a quadcopter needs ~35 blocks to shed
  cruise speed, so it cannot avoid a contact a metre away, and cancelling its velocity writes the deletion
  straight into the field the brain reads back as flight state. Drones converging on one point stopped dead
  at exactly zero speed and stayed there. Pushing them apart instead leaves the flight untouched.
- **Spread aim points** (`PayloadRunHandler.aimPoint`). A flight breaks formation for the run, because every
  drone needs its own release solution: each is offset from the others, so each reaches its own lead
  distance at its own moment. Aim them all at one point and they converge on it: arriving together, at the
  same height, over the same spot, each dropping a live warhead through the others. The aim points are fanned
  out in the shape of the formation (`PAYLOAD_AIM_SPREAD` times its spacing), in the *leader's* run-in frame
  so every member measures against the same axis. The run stays separated all the way through the release and
  the pattern lands where the pattern was aimed.

## 10d. Safe exchange (`exchange/`)

Three ways to deliver, trading battery for deniability. They answer three different questions an observer
standing at the drop might be trying to answer.

| Mode | What it hides | What it costs |
| --- | --- | --- |
| **Direct** | Nothing. | Nothing. |
| **Indirect** | Which way the sender lives. | The dogleg, out *and* back. |
| **Handshake** | That the two stations have anything to do with each other. | Two round trips, on two batteries. |

### Station codes

Every pad gets a twelve-character code the first time it exists. **A code is the only thing that travels
between players**, and it is a lookup key into a server-side registry: it carries nothing about where the
station is, so you can post yours publicly and all anyone can do with it is send you things.

The alphabet is Crockford base32: digits and letters minus `I`, `L`, `O` and `U`, so no two glyphs can be
confused when read off a screen and typed back in. `StationCode.normalise` folds the confusables anyway, so
typing a letter O where a zero was meant still finds the right station. Twelve characters of 32 symbols is 60
bits, drawn from `SecureRandom`: not guessable, and not worth enumerating.

**The recipient must allow you first.** Knowing a code is not enough to send to it. Without that, a leaked
code would let anyone make your station spend drones and battery collecting whatever they were sent, or bait
your drone out to a rendezvous they intend to be standing in. Note that `station allow` does *not* check
whether the code exists: confirming that would turn the command into an oracle for testing guesses, and an
allow-list entry for a station that never existed is inert.

### Indirect

The drone flies out to a staging point and turns in from a bearing chosen to be a poor guide back home. The
bearing is **weighted** away from home rather than forbidden from it: see `Obfuscation.approachBearing`.
That distinction matters: an observer who learns that drones *never* arrive from the north has learned the
sender is north, and having ruled out a direction is worth as much as having found one. At the stock
weighting the home bearing comes up about a twelfth as often as the opposite one.

**The egress is doglegged too, on an independently drawn bearing.** Obfuscating only the approach buys
nothing: an observer who cannot tell where the drone came from can simply watch which way it goes.

The detour is priced honestly: `DroneMission.outboundDistance` follows every waypoint, so a mission that
cannot afford the dogleg is refused rather than launched to strand a third of the way round it.

### Handshake

Neither drone ever visits the other's station. Both fly to a neutral point and each collects what the other
left, so watching the drop tells you about the rendezvous and nothing about either endpoint.

The rendezvous is placed in an annulus **offset from the midpoint** of the two stations, pushed out until it
stands at least `RENDEZVOUS_STANDOFF` clear of both. Every part of that is load-bearing: a point on the line
between two stations gives away that line, a point at the midpoint gives away the distance too, and a point
near a station identifies it.

A one-way delivery is modelled as an exchange where only one side has anything to send, so bidirectional
trade falls out for free: each side drops what it brought and collects what the other left, independently
and without waiting.

### What a client is never told

- **A handshake packet contains no coordinates at all.** Not blanked, not zeroed: the fields are absent from
  the wire, so there is nothing for a modified client to read and nothing for the server to be tricked into
  trusting. `DroneSelfTest` serialises a mission and searches the actual bytes, with a direct-mode control so
  the test cannot pass by accident.
- **Classified missions keep no telemetry.** A timeline is a written account of where a drone went and when,
  readable from a command: the easiest of all the leaks to exploit.
- **Refusals carry no distance.** Quoting a shortfall would let a sender binary-search their own battery
  setting until it was accepted and read the range off the boundary. The rendezvous is also re-rolled on
  every attempt, so repeated probing returns noise rather than converging.
- **`drone list` redacts classified destinations** as `[CLSFD]`. Operators can read the world directly if
  they must, but a shared admin screen should not be the easy way around a mechanic that is supposed to be
  beaten by following the drone.
- The pad screen shows *its own* code, which is the one thing you have to be able to read to trade at all.

The intended counterplay is physical: follow the drone.

### Being seen

Any player within `ExchangeManager.WATCH_RADIUS` of a rendezvous cancels the exchange outright. A drone still
inbound takes its cargo home rather than hand it over in front of a witness; a collecting drone that arrives
to find someone there leaves empty, because descending onto a watched crate hands the watcher both.

**Cargo already on the ground stays exactly where it is, and is anyone's.** Making it vanish because somebody
walked past would be both unexplainable to look at and a free undo. Being caught costs you the goods, which
is what makes following a drone worth doing.

### Commands

```
/wflib station code               # this pad's code, and how many stations it accepts from
/wflib station allow <code>       # accept handshakes from that station
/wflib station revoke <code>
/wflib station send <code>        # dispatch this pad's cargo by handshake
/wflib station list               # operator: every registered station
/wflib exchange list              # operator: live and recent exchanges
/wflib exchange cancel <id>
/wflib exchange purge             # cancel everything live
```

## 11. States

`DroneState`, each bound to a handler in `DroneStateRegistry`. Handlers are stateless strategies exactly like
`FlightStage`: they read a snapshot, return a desired velocity, and decide when to hand over.

| State | What it does |
| --- | --- |
| `IDLE` | Parked. Costs nothing. Launches when it has a mission *and* the charge to reach it. |
| `TAKEOFF` | Climbs straight to cruise altitude before going anywhere. |
| `TRANSIT` | Cruises to the destination. |
| `DELIVER` | Descends over the destination and releases the crate. |
| `COLLECT` | Descends over a rendezvous and picks a crate up. |
| `PAYLOAD_RUN` | Attack run: holds release speed down the line and pickles the warhead early. |
| `SURVEIL` | On station over somewhere worth watching: orbits it, or holds still above it, and reports who turns up. |
| `EXFIL` | Returns to the exfil point (by default where it launched). |
| `LANDING` | Controlled descent; ends the mission on touchdown. |
| `DEPLETED` | Battery flat in mid-air: sinks with rotor drag and settles as an `IDLE` drone. Recoverable: nothing detonates. |
| `DOWNED` | Shot down: spins in, comes to rest as a lootable wreck. Never flies again. |

Add a behaviour by implementing `DroneStateHandler` and registering it; nothing else changes.

Drones fly with `noPhysics` on. They navigate by waypoint and never route around obstacles, and the airframe
is 3 blocks wide: with collision on, parking one on uneven ground buries its box in terrain and it can never
take off again. Ground contact comes from the heightmap instead. The OBB still stands, so drones remain
shootable.

## 12. Battery

The fuel-tank analogue, with two differences: drain depends on what the drone is *doing*, and running out is
survivable. `PowerProfile` gives charge/tick per state plus a multiplier for a slung crate (parked ~0, hover
cheap, cruise most).

`PowerPolicy` runs *before* any state handler and can overrule it. Every check holds `LANDING_RESERVE` back so
a drone always has enough left to set itself down:

| Charge available | Decision |
| --- | --- |
| covers delivery + return | Full mission. |
| covers delivery only | One-way: deliver, land at the destination (`POWER_LOW`). |
| covers the trip home only | Abort: keep the cargo, return, land (`POWER_LOW`). |
| less than that | Land now, wherever it is (`POWER_LOW`). |
| none | `DEPLETED`: sink and settle (`POWER_OUT`). |

A mission that can't even reach its destination is **refused at dispatch** with the shortfall reported, rather
than launched to strand halfway. A drone parked on a `DronePadBlock` recharges, which closes the loop.

Range is `charge / (transitDrain / speed)`. Raising `cruiseSpeed` costs nothing per block: the drain is
per tick, so a faster drone covers more ground on the same charge. Cargo is what costs range.

## 12a. Payload missions (strike drones)

A mission with a `payloadId` is a strike instead of a delivery: that one field is the only difference. Each
drone in the flight carries its own warhead (four drones, four bombs), pulled from the **same**
`WarheadRegistry` the missiles use, so `standard`, `shaped_charge`, `mininuke` and anything you register
later all work unchanged. The released payload is a `BombletEntity`, which is already a `WarheadCarrier`, so
the warhead behaves exactly as it would on a missile.

`PAYLOAD_RUN` differs from `DELIVER` in three ways:

- It **breaks off cruise early** (`Tuning.PAYLOAD_RUN_IN`, 140 blocks) so it has room to build up.
- It flies with `Steering.dash`, not `Steering.cruise`: no arrival slowdown. A bombing run has to be *at*
  release speed when it pickles, so easing off on the approach is exactly wrong.
- It **releases before the target**, not over it. `Steering.ballisticLead` solves how far the bomb will
  travel while falling from the current height at the current speed, and the drone pickles once the aim point
  comes inside that distance, and only once it has actually reached `RELEASE_SPEED_TOLERANCE` of the ordered
  speed, so it never drops short while still accelerating.

The payload inherits the drone's velocity on release, which is what makes the solved lead real. If you retune
`releaseSpeed` or the drop altitude, nothing else needs touching: the lead follows. `Tuning.PAYLOAD_GRAVITY`
must stay in step with `BombletEntity`'s gravity, though; that is the one hard-coded coupling.

After release the drone breaks off to `EXFIL`, or lands where it is if the battery won't cover the trip home.

## 12b. Shooting drones down, and recovery

Drones have health and take damage like anything else. At zero they enter `DOWNED`: power is cut, so there is
no smoothing toward a desired velocity: the airframe keeps its momentum, accelerates downward, and spins on
the way in. It comes to rest on the ground **as a wreck and stays one**. It is not deleted, and it never
returns to `IDLE`, so a shot-down drone can't take off again.

Right-click a drone to take what it is carrying:

- carrying a **crate**: opens the crate's inventory where it hangs, so you can empty it without waiting for
  it to be dropped;
- carrying a **warhead**: makes it safe and removes it;
- carrying a **mine rack**: takes the rest of it off, reporting how much was left;
- carrying nothing: reports its state and battery.

That works on a flying drone as well as a wreck, so an armed drone can be disarmed by hand. Hitting a wreck
again breaks it up and spills whatever is still aboard.

## 12c. Mine-laying missions

A mission with a `mines` rack sows a minefield. Same shape as a strike (one field on the mission is the whole
difference), but the run is not a strike run with a different weapon on it, and the state is its own
(`MINELAY`, flown by `MineLayHandler`).

**A strike has one release solution; a laying run has as many as the rack holds.** The drone enters a *lane*
through the ordered point on the heading it arrived on, holds release speed down it, and dispenses one mine
every `spacing` blocks of ground track until the rack is empty. That is what makes the result a minefield and
not a heap, and it is how every real scattering system works.

Three consequences worth knowing before you aim one:

- **The lane is centred on the point you name.** `MineLoad.alongTrack` puts mine 0 half a lane *short* of it
  and the last one half a lane long, so "mine that valley mouth" lays a strip across it rather than starting
  at it. The pad's readout prints how far short the first release is for exactly this reason.
- **The break-off distance grows with the lane.** `TransitHandler.minelayRunIn` is
  `max(PAYLOAD_RUN_IN, laneLength/2 + 48)`: a drone that left cruise inside its own lane would find the first
  several mines already overdue and stitch them into one spot while it caught up.
- **A flight lays parallel strips.** Each drone gets its own full rack (four drones told to lay eight lay
  four sticks of eight, not two each) and its own lay point, fanned out by the formation, so a flight gives
  the field width as well as length. Aiming them all at one lane would converge them onto it, at the same
  height, each dropping live mines through the others.

**Where the next mine is due is geometry, not a timer.** The handler is given a snapshot and nothing else. It
does not need more: the drone's own release solution says where a mine let go now would land, the rack says
where mine *k* is supposed to land, and how many are already gone is capacity minus what is left. So the run
corrects itself: a drone shoved off the lane, slowed by a climb, or sent round again picks the stick up where
the ground says it left off. It will only ever dispense **one per tick** even when it is several behind, because
a rack emptied in a tick is the heap the lane exists to avoid. And it gives up once it is `LANE_OVERRUN` past
its own lane, so a run that was never flyable ends instead of dispensing into the drone's wake for ever.

The mine is not steered at anything. It is let go with the drone's velocity on it and tumbles the rest of the
way down (`MineEntity#scatter`), which is the whole reason the release geometry is worth solving.

```
/wflib drone minelay <x y z> [drones] [mine] [mines per drone] [spacing]
/wflib drone program minelay <x y z>    # ... then `program launch <n> <formation> <mine>`
```

or set the drone pad's mission kind to **Minelay (rack)** and fill in *Mines* and *Lay gap*.

Every drone in the flight lays its own rack, not just the leader. A follower on escort does not run its own
handler at all (it inherits the leader's state and the formation flies it), so an ordnance run has to be
excluded from escorting or the wingmen fly immaculate slots and drop nothing. `DroneState#flownIndividually`
is that exclusion, and both the attack run and the laying run are in it. Each drone's lane is offset by its
formation slot, so a flight of four lays four parallel lanes and the field is as wide as the formation,
which is the only reason to send more than one.

### Mines built to be sown

Two mines ship that **cannot be laid by hand at all**: `scatter_ap` and `scatter_at`. Right-clicking the
ground with one does nothing and says so. That rule is the whole of what makes them their own presets rather
than delivery notes attached to mines that already exist, and it is enforced in `MineItem`, not just
described. There used to be a third, `naval_air`, which differed from `naval` by one line; it is gone, and
that line (`armsOnlyInWater`) now lives on the naval mine itself, where it belongs. A naval mine on a beach
is a dud however it got there: it holds its arming wherever it is dry and gives back arming it had already
done if it is taken out of the water, so one washed ashore goes inert and one that floats off again comes
back.

The two that remain are **their own models**, not repacks: a ten-centimetre cylinder on six splayed vanes,
and the same shape one size up on eight. The vanes are the tell: they are what keeps a mine upright however
it lands, which is the problem a mine thrown out of a canister has to solve and a mine laid by hand never
does. No two presets share a model any more, and `MineSelfTest` enforces that: two mines that draw
identically are one mine.

Any other preset can still be loaded onto a rack and the command accepts it: a drone will sow claymores if
you ask it to. The picker offers the sown pair first and loading anything else prints a warning, because
what you lose is the three properties that follow from nobody being there when it lands:

- **It arrives however it arrives.** Both tumble, neither is buriable, and the arming delay covers the fall
  and the settling.
- **Nobody is going to clear it**, so both **self-destruct**. That is not a courtesy (every real
  scatterable mine is required to), and it is also the only thing bounding what one drone can leave lying in
  a world.
- **You do not defuse it.** `DefuseMethod.NONE` plus a life: blow it in place or wait it out.

### What a loaded drone looks like

A drone draws whatever it is slung with, at the mount its airframe declares. A crate is the crate; a strike
drone carries the **bomblet** that is about to fall off it, drawn from the same model `BombletRenderer`
uses; a minelayer carries up to four of the **actual mine** on its rack, in a two-by-two block, each at its
own model's size, so an anti-armour rack is visibly bigger ordnance than an anti-personnel one. The count
is synced, so the rack empties a mine at a time as the strip goes down, which is the only outward sign that
a drone eighty blocks up is doing anything at all.

### The mines carry their own warheads

Mines used to borrow the missile set, and borrowing it gave away the three things that make a mine a mine.
`MineWarheads` registers `mine_blast`, `mine_frag`, `mine_directional`, `mine_heat`, `mine_naval` and the
two dispenser detonations into the
same `WarheadRegistry` (a mine warhead is a warhead, and this is not a second registry), but every one of
them is built for the job:

- **Nothing is set alight.** `bomblet` and `fragmentation` both end in `ExplosionNukeGeneric.dealDamage`,
  whose last line is `igniteForSeconds(5)`: a nuke's thermal flash, correct where it came from and nonsense
  out of a pressure-plate charge buried in wet soil. A bounding mine set its victims on fire.
- **Fragments are not entities.** `fragmentation` threw twenty `BombletEntity` and each went off as a
  four-block, forty-damage explosion: twenty grenades, at the cost of twenty entities and twenty explosions.
  A sleeve is now resolved geometrically: the share of it that meets you is the share of its solid angle your
  silhouette covers, which is where the inverse-square falloff comes from without anyone writing a curve. So
  the fragment count can be what a real sleeve holds (600 in a bounding mine, 700 balls in a claymore) and
  each victim is hurt **once**, for the whole spray, which is also the only hit the invulnerability window
  would have let through anyway.
- **Cover works.** Every mine warhead is line-of-sight gated. That is the difference between a minefield and
  an area-denial weapon.
- **Only the two that are meant to breach something touch blocks.** The naval mine used `standard` (blast
  size 15 over a 50-block allocation), and took the seabed with it.

`mine_naval` is the one with a rule of its own: water carries the shock rather than shielding against it, so
anything in the water with it takes the full figure with no cover check, and anything standing clear of the
water takes `NAVAL_IN_AIR` of it. A moored mine is a threat to what swims or sails over it and very little
else, which is the point of mooring one.

`WarheadRegistry.declarePeakDamage` exists for these: a warhead that resolves its own damage has no blast size
for the tooltip's estimate to work from, and would otherwise report zero, which reads as "harmless".

### Dispensers: a mine that sows mines

`dispenser_ap` and `dispenser_at` are the racks that lay the scatter mines from the ground. **It is an
entity that gets triggered, not a block and not a block entity**: the name is the vanilla word for
something else entirely. You do not place it into the world grid; you put the object down, arm it, and it
watches, exactly as a mine does, because it *is* a mine: **a dispenser is built out of a mine and is not
one**, and that is the whole design: everything a deployable wants, a mine
already does; it is put down, it settles where it lands, it takes the finish of the ground under it, it can
be armed and made safe again, and it watches for something to come near. The only thing that differs is what
happens when it goes off, and that is a `Detonation` like any other. So this needed no new entity, no new
registry and no second state machine, just `mine_dispense_ap` / `mine_dispense_at`, which throw
`MineWarheads.DISPENSER_CANISTERS` mines out across the front it faces instead of a charge.

Six canisters, because the model has six (`03_CANISTER_01` … `_06`): a number a player can count off the
thing in front of them is worth more than a number that is merely bigger. The rack is expended in the
firing: what is left behind is the field.

Both are `requiresActivation`: you put the rack down, then arm it deliberately, because a deployable that
went live the moment it touched the grass would sow its field around the person carrying it. Their trigger
ranges are far longer than a mine's (10 and 14 blocks), which is the point of the antenna on the model: a
rack is a sensor that answers by making the ground in front of it impassable, so it has to see far enough
out that the field lands *between* it and whatever tripped it.

**A rack is directional.** It faces the way you were looking when you put it down, it watches the
`MineWarheads.DISPENSER_ARC` (120°) sector in front of it and nothing else, and it throws its canisters
into the same sector. One number, read from one place, for both halves: a rack that watched wider than it
could cover would fire at things it cannot block.

That is the property that makes a rack worth *siting* rather than merely placing, and it fixes a real
defect rather than adding a flavour. A rack that sowed a full circle put two of its six mines **behind**
the person who had just walked up and armed it, and spread the remaining four so thin that at the landing
radius they were about eight blocks apart: a ring with a person-wide gap between every pair, which is not
a minefield. The same six across 120° land under three blocks apart, close enough that an anti-personnel
mine's own trigger circles overlap. `MineSelfTest` asserts that arithmetic (`mine/dispense/the-belt-has-no-gap`)
rather than leaving it as a claim in a comment.

The bearings are an **even share of that arc with jitter on each**, not six random draws. Six random
bearings clump, and the odds of a gap wide enough to walk through are better than even. The jitter is
bounded so no draw can throw a canister outside the arc the rack watches: `MineWarheads.canisterOffset`
and `canisterJitter` are public so the self-test can assert exactly that against the shipped arithmetic
instead of a copy of it. A modest range jitter on the throw gives the belt some depth, so it is a belt and
not a fence along one radius.

A carrier with no facing (anything firing these detonations that is not a rack) falls back to the full
circle, which is the honest answer for something that has no front.

### The rack empties its tubes; it does not explode

A dispenser used to blow up along with its load. That was the wrong picture twice over: **a dispenser is a
frame that throws things, not a bomb**, and the model already had each canister as its own node
(`03_CANISTER_01` … `_06`) precisely so the frame could be drawn without them.

So it does. Firing empties the rack and leaves it standing, `SAFE` and harmless.

**The tubes are hollow, and what leaves one is the mine inside it.** Each tube is three separate nodes (
the tube itself, the cap over its mouth (`09_TUBE_CAP_01` … `_06`) and the mine stowed in it
(`10_STOWED_MINE_01` … `_06`)), so firing takes the cap off and the mine out and leaves an open empty
tube on the rack. A spent dispenser is a rack of open tubes, which is what one looks like and also the
only picture that says at a glance how many are left. The first version of this collapsed the whole
canister, because the asset had only the one node to collapse, and a fully fired rack came out as a bare
frame.

`MineRigs.EMPTY` is one clip that does that to each tube in turn, each at its own threshold, so **clip
time is the fraction of the rack that has been fired**: a rack with two gone poses at 2/6. That is still
one number rather than per-instance state, so `PoseCache` buckets racks by load exactly as it buckets
plain mines by nothing: a line of dispensers costs at most one palette evaluation per *distinct* number of
tubes left, which is seven. The cap and the mine go on the same threshold rather than a moment apart:
it would be a nicer order and nobody would ever see it, because the clip is scrubbed by the fraction fired
and for a six-tube rack that takes seven values and lands on none of the times in between.

An asset whose tubes are solid (no payload node) falls back to collapsing the canister whole, so an
older rack still empties rather than firing and keeping everything. A model with no canister nodes at all
gets no clip and is posed with a null one, as every other mine still is.

The count is on the entity (`Canisters` / `CanisterCapacity`, synced, because it is the one thing about a
rack you can see) and the declaration is on the preset: `.canisters(6, rl("scatter_ap"))`. The warhead
throws what the rack is *holding* rather than the six its id was registered for, and shares the bearings
over the rack's full width however many are left, so a part-loaded rack covers the same front with gaps in
it rather than bunching what it has into one corner of the arc. `MineSelfTest` checks the declared count
against the number of canister bones the asset actually has, read out of the glTF with no renderer: a
preset claiming seven on a six-canister model would empty one into nothing and never look any emptier. It
also checks that every tube has a stowed mine and a cap: a re-export that joined those back into the tube
would pass every count above and still empty, by deleting the tube; the bare-frame picture the hollow
asset replaced.

**A frame you keep has to be worth keeping, or it is litter**, and litter would be a worse outcome than
the explosion was. So a rack **reloads**: right-click it with a canister of the mine it sows, or use the
panel's row, one per click. (Until recently only the second half of that was true; see
*What the rack's canister id being server-side broke*, below.) This is also the only thing a sown-only mine can be *used* for by hand, and it
does not breach the rule that they cannot be laid: loading a canister is not planting a mine, and what
comes off the rack is still sown.

Two rules fall out of that and are both enforced:

- **An empty rack will not arm.** It would watch its arc and fire nothing, so `armRefusal` says
  "no canisters on it" rather than letting it sit there looking live.
- **A rack comes up carrying what was on it.** Crouch-click it, or take the panel's *Pack up* row, and the
  frame goes into your hands with its remaining load written on the item
  (`ModDataComponents.MINE_CANISTERS`); laying it again puts that number back on the rack, and the model
  draws it: three tubes still capped is three canisters. Only a part-loaded rack carries a count at all,
  so a recovered full one still stacks with a fresh one out of the creative tab.

  The rule that replaced: **a rack used to have to be full before it could be lifted.** The reasoning was
  an economy one (laying a rack item put down a *loaded* rack, so recovering a spent frame and re-laying
  it would have been six free mines, for ever), and it is answered better by the item remembering. What
  the old rule actually did was make *moving* a dispenser cost a full load of canisters that you then
  could not get back out of it, and it did so through a greyed row that said "reload it before lifting
  it", on a panel that did not offer the row at all while the rack was `SAFE`, which is every rack that
  has just fired and every rack you have just put down.

### What the rack's canister id being server-side broke

Worth writing down because it is the **third** time this exact shape has cost something here, and the
first two are already in this file: `buriable` read `false` on the client and the dig-in row could never
appear; the preset id read `null` and the panel could not name the mine. Both were fixed by syncing the
field. `canisterMineId` (what this rack's tubes hold, and therefore what reloads it) was left a plain
server-side field, and on the client it read `null` on every rack in the world.

The panel decides its own rows. `canisterItem` matched the held stack against that null and therefore
matched nothing, so `reloadRefusal` answered **"no matching canister in hand"** whatever you were
holding, and the row was permanently greyed. That is a rack that can never be reloaded, and since an
empty rack refuses to arm, one that can never be armed again either. Both of those were reported as
separate faults; they were one field.

The lesson is not "sync more fields". It is that **a refusal computed on the client from server-side
state is a lie that looks like a rule**, and it looks like the most convincing kind of rule, because it
names a specific thing you are supposedly doing wrong. A row greyed with a *reason* is trusted far more
than a row that is missing. Anything `MineProbe` reads to decide a row has to be either synced entity
data or a `MinePreset` figure (those are the two things both sides have), and that is now the standing
rule for the whole panel.

### Mines do not set each other off

**Every mine in a field is an entity standing inside another mine's blast radius.** The obvious behaviour (
take the damage, trip, go off next tick) means the first thing to step anywhere clears the entire field in
a ripple, and ten mines buy you exactly one detonation. Real mines are spaced and built specifically so
that does not happen, for exactly the same reason.

So they no longer do. `MineEntity.sympathetic()` refuses damage dealt while another mine's warhead is
running, beyond `SYMPATHY_RANGE`: 1.5 blocks, contact distance. Mines close enough to be touching still go
together, which matters: without it a heap of mines in one block would be an armoured pile you have to
clear a mine at a time, a worse answer than the chain was.

The gate is a flag around the warhead call rather than an inspection of the `DamageSource`, and that is
deliberate. The damage arrives by several routes that do not agree on what they put in one: a sleeve hurts
entities directly with the mine as the direct entity, while the shaped charge goes through `ExplosionAEF`'s
own entity processor and its own explosion source. A flag catches all of them, and cannot be fooled by a
route nobody has written yet. It is saved and restored rather than set and cleared, so a nested detonation
puts the outer blast back instead of leaving the field unguarded for the rest of it.

None of this touches clearing a field deliberately. A charge, a missile, a TNT or a pickaxe reaches `hurt`
with no mine warhead on the stack, so blowing a field in place (the documented way to clear the
scatterable mines, which cannot be defused) works exactly as it did. The probe asserts all three: the rest
of a line survives *and stays armed*, two mines a block apart still go together, and a TNT still clears one.

### Arming, defusing, and the clacker

Four rules, and they are all the same rule looked at from different sides: **what a mine does should never
be a side effect of a gesture you did not know was one.**

**Arming is on the probe overlay, not on right-click.** It used to be a plain click: walk up to a safe
mine, click it, and the minefield is live. That is the most consequential thing you can do to a mine, on
the least deliberate gesture there is, with no confirmation and no way to find out beforehand, and it was
indistinguishable from the click that does nothing. It is `MineProbeActions.ARM` now: a row you read before
you choose it. The row is offered on any mine that *could* be safe (one built `requiresActivation`, or one
laid crouching) and stays visible while it is live reading "already live", so the place to do it is
discoverable from an armed rack and not only from a fresh one. It is not offered on the ordinary mines that
arm themselves and can never be safe again, because there it would be a permanently dead row on every mine
in a field.

**Laying a mine while crouching leaves it safe.** Placing a mine and arming it are two decisions, and the
crouch is what separates them: it is how you build a field in front of your own position without the half
you have already laid going live behind you while you lay the rest, and how you site something you mean to
fire yourself rather than leave to a fuse. The same modifier already means "careful" everywhere else a mine
is concerned (crouching is how you walk past one without tripping it and how you start taking one apart),
so this is a rule the mine already had, applied to the one moment it was missing from. `MineEntity.layInert`
is not the same thing as defusing: defusing is slow, failable and ends with the mine picked up and gone;
this is the mine simply never having started. Both end at `SAFE`, and arming is the way out of either.

**Some mines can be disarmed, and what disarms them is an item tag.** `wflib:defuser`; shipping
`#minecraft:shovels` and `minecraft:shears`, because cutting the wire is the other half of how this is
actually done. A pack that ships a proper mine-clearing tool adds it to the tag and every `DefuseMethod.TOOL`
mine accepts it, with no preset touched and no code changed. The tag is the *filter* and `defuseMethod` is
the *permission*: a mine built `DefuseMethod.NONE` stays undefusable however good your tools are, which is
what keeps the scatterable mines a thing you clear by blowing them in place. All three methods are in use
and the self-test asserts it: a flag that is never false is not a flag.

One trap worth knowing about, because it fails silently: an item tag JSON in the wrong folder loads as an
**empty tag** rather than as an error (1.21 renamed `tags/items` to `tags/item`). An empty defuser tag means
no held item ever matches, so every tool-defusable mine in the game quietly becomes undefusable and the only
way anyone finds out is by crouching at one until it kills them. `mine/defuse/the-defuser-tag-is-populated`
is the check for exactly that.

The panel says all of this on the mine itself rather than only in the item tooltip: by the time you are
standing over one, the item it came from is somebody else's and its tooltip is not something you can read.
"Cannot be defused" on a scattered mine is the single most useful thing the overlay can tell you, and it is
only sayable there. The panel also carries a **Defuse** row, which starts the same job crouch-clicking
starts: you still have to stay crouched and in reach for the whole `defuseTicks`, and a mine with a fail
chance can still go off at the last moment. It greys with "crouch to work on it" when you are standing,
because a defusal begun upright cancels on the very next tick and would otherwise read as a row that does
nothing.

And it says **which way a mine is pointing** (`Watches: 120°, facing NE`), for anything with an arc. That
is the most invisible property a mine has and the one that most changes what you should do about it: a
claymore or a rack you are standing behind is not the same object as one you are standing in front of, and
nothing on either model reads as a bearing from two paces away. Eight compass points rather than degrees,
because that is a thing you can act on from where you are standing.

> **Sides.** `defuseMethod` and `defuseTool` are server-side fields. A mine on the client has whatever the
> constructor left there ("by hand, no tool"), so a panel built from the entity would tell everyone that
> every mine in the world comes apart bare-handed. The probe asks `MineEntity.defuseRefusal` with the
> **preset's** figures, which both sides have; `tryDefuse` asks the same function again with the mine's own.
> One rule, two fact sources, and the server's is the one that decides. Offering a row is never permission
> to perform it.

**A mine can be fired on command, from the same detonator as a mining charge.** Sneak + right-click a mine
with the detonator to wire it (sneak-click again to unwire), right-click anything to fire the circuit:
**or use the panel's wire row**, which is the better way round and is offered whenever you are holding a
detonator. The panel can see something a click cannot: whether the mine is *already on the circuit*. A
wired mine and an unwired one are the same object from any distance, and getting it wrong means either a
dead clacker or a mine you thought was on it. The row reads the held stack's own wiring, which is
network-synchronised and so arrives with it. What it cannot see is whether the mine is *yours* (ownership
is server-side), so that refusal comes back as a message after the fact. A
mine that only goes off when something walks into it is a trap; a mine you can also fire on command is a
weapon, and it is how a claymore is actually used. Command detonation ignores the mine's fuse **entirely** (
arc, ranges, arming delay, and whether it is armed at all), because a firing device is not a sensor. A mine
with a bounce still bounds, because that is the mine's shape rather than its fuse.

Only the owner can wire a mine (or anyone at permission level 2). Mines nobody claimed (everything a rack
or a drone sowed unattributed) stay wireable by anyone, which is the honest reading of ordnance nobody has
put their name to. Whoever fires a mine owns what it does, so a kill credits the person who pressed the
button rather than the person who laid it.

Charges and mines share **one budget** of `DetonatorItem.MAX_CHARGES`: five things, however they are made
up. A detonator that could hold five charges *and* five mines would be two tools sharing an item. They are
two data components (`detonator_charges` by position, `detonator_mines` by entity id) because a block keeps
still and an entity does not, so a single list could only ever be a tagged union, but the cap is arithmetic
and is spent across both.

`IDetonatableEntity` is the entity counterpart of `IDetonatable`, and `DetonatorInteractions` is what gives
the detonator first claim on the click. That matters: NeoForge fires `PlayerInteractEvent.EntityInteract`
*before* `Entity#interact`, and without cancelling there, crouch-clicking a mine with a clacker in hand
would start pulling the mine apart instead of wiring it up. The detonator never mentions mines and
`MineEntity` never mentions the detonator; the next detonatable entity gets all of this by implementing the
interface and nothing else.

**Commands.** `/wflib debug mine lay <preset> <pos> [yaw] [armed|safe]` puts one mine down built
from its preset (beside `debug scatter`, and note it is under `debug`: the info form is
`/wflib debug mine`), and `/wflib demolition fire <targets>` sets off detonatable entities
without a detonator.
Both exist for tests and map-making, and the first exists for a specific reason: `summon wflib:mine`
makes a bare entity carrying the tags typed at it and **class defaults for everything else** (including a
360° arc), so a summoned rack sows a full circle however the preset is written. A probe built on one would
be asserting against its own NBT. `mine lay` builds what `MinePresetRegistry` builds, which is the only way
deleting the arc from a preset can make a test go red.

**A dispenser is not an air-delivered weapon**, and the two paths stay separate on purpose. A drone rack
will accept one, because the rack takes any preset, but a dropped dispenser lands on its side and lands
*safe*: `requiresActivation` means someone still has to walk over and arm it. The command warns you. If
you want a rack delivered by air, put the item in a crate and fly a `deliver` step: that already works, and
it puts the thing down the right way up. The sown mines are the air-delivered half of the set; the racks
are the emplaced half. Both end in the same minefield.

## 12d. Demolition charges (mining explosives)

Moved here from wfcore whole: the charges, the detonator, the blast, its particles and its sounds.
Nothing about the warheads came with it: a mining charge is a **tool**, and that is the whole design.

`mining_charge` (tier 1) and `deep_mining_charge` (tier 2) are placed, then wired to a `detonator`:
sneak + right-click each charge to link it (up to five), right-click anywhere to fire the lot. Linking
flips the charge to its armed texture, and the wiring lives on the detonator stack, so a clicker carries
its own field. Nothing else sets one off, not fire, not flint and steel, not redstone.

**It removes rock and nothing else.** What counts as rock is the block tag
`wflib:natural_blast_breakable` (plus `deep_blast_breakable` and `deep_ores` behind the tier gate),
so a pack decides it. A player's wall inside the radius survives; the stone beside it does not. A charge
also **casts shadows**: anything it cannot break shields everything behind it, resolved with an integer
voxel walk cached per position for the whole blast. And it keeps the drops, fortune-mined for ores and
consolidated into whole stacks: the alternative to one item entity per broken block is a server that
stops. A radius-3 charge through stone leaves six item entities, not a hundred and twenty.

Charges chain: one inside another's radius goes off too.

```
/wflib demolition detonate <x y z>    # fire a placed charge with no detonator (tests, map-making)
```

### The blast has to be visible from further than 32 blocks

This is the one thing that changed in the move rather than coming across unaltered, and it was a real
defect. `ServerLevel#sendParticles` builds its packet with `overrideLimiter = false` and sends it only to
players **within 32 blocks**; `LevelRenderer` then discards any unforced particle whose
`distanceToSqr` from the camera exceeds `1024.0`, which is 32 squared. So a charge tearing a hole in a
hillside produced *no visible smoke at all* for anyone standing back from it, which is everyone who has
just used a detonator. The blast was audible (the audio has always had its own distance model) and
completely invisible.

`BlastParticles` sends the same bursts with the limiter overridden, directly to every player within
**256 blocks**. Counts and velocities are untouched: same effect, drawn for the people who can see it.

**The flag has a second effect, and it is the price.** Forcing a particle also bypasses the client's
"Particles: Minimal" video setting; vanilla's own javadoc says *"regardless of its distance from the
camera and the calculated particle level"*, and the two are the same boolean. There is no way to buy the
range without it. It is taken for this one rare, consequential event and is not extended to anything that
fires repeatedly.

### Sound is two recordings, not one turned down

Inside 24 blocks you get the sharp report; past it, a separately recorded distant rumble. Every listener's
copy is **delayed by the time sound would take to reach them** (~17 blocks/tick), so someone across the
valley sees it before they hear it. The variant is seeded per event, so four people standing together hear
the same one of the four blast recordings.

### Claims

The blast asks WarForge once, for the whole candidate set, through the guarded `WarforgeCompat` layer,
so it respects siege/war/safe zones from the firing faction's own perspective rather than blindly stopping
at any claim, and no-ops entirely when WarForge is absent.

## 13. Squads and formations

Give several drones the same non-zero `squadId`; the first is the leader. `Formations` holds the shapes
(`vee`, `column`, `line`, `grid`) as pure geometry: the wedge math is the same one the missile swarm uses.

**Shape, size and architecture are three separate choices.** A wedge is a wedge whether it is flown tight or
loose, so the spacing between neighbouring slots is a per-mission variable (`DroneMission.formationSpacing`,
default `Formation.DEFAULT_SPACING` = 12 blocks, clamped to 4..96) rather than a constant baked into the
shape. One number scales the whole formation, so a squad keeps its proportions however loosely it is ordered
to fly. The third choice, what the slots are measured *against*, is `DroneMission.coordinationId`, and it
is the one that decides how well the shape is actually held. See §13a.

**A follower is fed its slot's velocity, not the leader's**, and on a wide formation that is the difference
between flying and straggling. A slot is pinned to a frame that rotates, so in a turn the slots sweep as well
as travel: the outside of the turn has a longer arc to cover in the same tick and the inside a shorter one.
Handing a follower the frame's velocity tells the outside drone to fly too slowly and the inside drone too
fast, and the error grows with the spacing, which is why loosening a formation used to make it fly *worse*.
`Slots.of` differentiates the slot's own position across the frame's extrapolation, so the answer is exact
for any shape without the shape knowing it is being differentiated. Measured on a 60-block-radius turn at
20-block spacing, mean slot error fell from 17.8 blocks to 1.6.

**It is fed the slot's acceleration too**, and that is a second, separate fix. `Multirotor` turns a velocity
*error* into a lean, so a drone handed only a velocity has to fall behind before it will tip at all, and
with the attitude rate limit measured in whole ticks, it never catches up while the reference keeps turning.
A steady turn is exactly that case. `SquadCommand` carries the reference acceleration alongside the velocity
and `Multirotor.step` folds it straight into the commanded acceleration, so the lean happens on the tick the
turn does. On a multirotor acceleration *is* attitude (`tan θ = a_h/(g + a_v)`), so this is the same fact the
flight model is built around, used in the other direction.

`grid` is the odd one out and worth knowing how it fills. A `Formation` is only ever asked where a single
slot goes, never how many slots there are, so the square cannot be laid out as a block: it grows outward
from the leader a Chebyshev ring at a time. Every ring that closes completes a filled `(2r+1)x(2r+1)`
square, so nine drones make the 3x3 and twenty-five the 5x5, with the counts in between part-built.

The leader flies the route; everyone else holds a slot. Two things followers *inherit* rather than
rediscover, both learned the hard way (and under `virtual_structure` the leader holds a slot as well: it
still runs its handler, so all of this is unchanged):

- **State.** A follower pinned to its slot never reaches its own waypoints, so its handler would wait forever
  for an arrival that cannot happen. It takes whatever state the leader just chose.
- **The end of the mission.** Otherwise a follower lands still holding a destination and immediately takes off
  again to fly the delivery on its own.

A follower overruled by its own battery drops out of formation for good and flies its own recovery.

Leader succession is handled in `DroneAiScheduler.buildSquads`: a squad with no live leader promotes one of
its survivors **at random** (`promoteIfLeaderless`), mirroring `SwarmManager.promoteSuccessor` on the missile
side. There is no squad registry to keep in sync: a dead leader is simply not in the list any more.

Random, not "the next one in line", and that is the whole point. Ordering by id would hand every decapitated
squad to its lowest-id member, which makes the survivor predictable from outside: shoot the leader and you
already know which drone becomes the one worth shooting next. The promotion is written back to the carrier
rather than recomputed, or the formation would re-form around a different drone every tick and never settle.

## 13a. Coordination models

`Formations` says where the slots are. `CoordinationModels` says what they are measured **against**, and that
turns out to matter far more for how tightly a flight actually holds together. Registered the same way as
shapes and warheads: implement `CoordinationModel`, call `register`, and it appears in the command's tab
completion and on the pad screen.

The four stock models are the four classical answers from formation control, and they differ in exactly one
thing, where the `SquadAnchor` comes from.

| id | Reference is | Costs |
|---|---|---|
| `virtual_structure` *(default)* | a computed point flying the mission | a reference that can drift from reality if not leashed |
| `leader_follower` | the leader's measured state | every error the leader has, amplified by the spacing |
| `consensus` | every pairwise offset at once | n² work, and slower to form up |
| `flocking` | the neighbours, as forces | no defined shape at all |

**Why this is an interface and not a flag.** These are not four tunings of one controller. They disagree
about which drone may be a reference at all, whether the leader flies a station or its own route
(`stationsLeader`), and whether the frame persists between ticks: differences that cannot be expressed as
constants, and which were previously hard-coded assumptions spread across the guidance layer.

**The threading contract still holds.** A model that computes its reference has to remember it between ticks,
and a worker may not remember anything. So the anchor is handed *in* with the `SquadView` and handed *back*
with the `SquadPlan`; `DroneAiScheduler.anchors` keeps it on the world thread, keyed by squad, pruned to live
squads each tick. Every model stays a pure function of its inputs.

### virtual_structure

The formation is treated as a rigid body with its own state, and that body is flown: it seeds on the squad,
integrates forward along the mission each tick, and every drone, the leader included, holds a station on
it. Nobody chases anybody.

This is how a formation gets tight, and the reason is one sentence: **the reference is computed rather than
measured.** Under `leader_follower` a follower's error is its own tracking error *plus* every disturbance the
leader suffered, delivered through the lever arm of the spacing. Here the anchor has no tracking error to
pass on, because it is not tracking anything. It is the architecture behind drone light shows and the
research swarms that hold formation to a hand's breadth.

The leader still runs its handler, so arrivals, state changes and the program queue step on exactly as
before: only the *flying* is delegated. What the handler produces is read as the mission's **intent** for
the frame, never as the leader's achieved motion.

Two guards keep a computed reference honest, and both are load-bearing:

- **The leash** (`LEASH_GAIN` 0.05 horizontal, `VERTICAL_LEASH_GAIN` 0.25). The frame is pulled toward where
  the squad implies it should be. Strictly a virtual structure should ignore where the drones are, but
  nothing else bounds the accumulated difference between a frame integrated from intent and a squad that has
  been shoved about, and an unbounded difference eventually puts every slot somewhere nobody can reach. At
  0.05, measurement noise reaches the followers at a twentieth of the strength it would under
  `leader_follower`. It is measured against each member's *implied* anchor, not the squad centroid: the
  centroid of a wedge sits behind its leader, so pulling toward it would drag the formation backwards a
  little every tick, forever. The vertical gain is five times harder because an altitude error moves the
  formation as one body and distorts nothing, where a horizontal one *rotates the frame*.
- **The throttle** (`LAG_TOLERANCE` 8 blocks, floor `MIN_LAG_THROTTLE` 0.2). The frame eases off when the
  worst-placed member falls behind. This is the formation-feedback loop, and it is what makes a virtual
  structure a control system rather than an animation: without it a squad held up by a climb watches its
  stations recede forever.

### consensus

A drone asks each squadmate "where should you be relative to me, and where are you actually", and flies the
average. The sensing graph is complete rather than a star, so the shape is **rigid**: an error anywhere is
shared out instead of landing entirely on whichever drone was measuring against the one that moved. No
drone's position is privileged.

The relative offsets fix the shape but not where the shape *is*, every constraint is a difference, so
`MISSION_WEIGHT` (0.15) blends in the leader's station as the one informed member's vote. Without it a
consensus flight would hold a perfect wedge into the sea.

It goes through `Steering.station` like everything else. An early version turned the averaged error straight
into a velocity, on the reasoning that an all-pairs average is quiet enough not to need the braking-envelope
cap. That confused noise with physics: the cap is not there to filter anything, it is there because a drone
told to close a twenty-block gap at full speed needs thirty-five blocks to stop again. Measured, that mistake
cost a **ten-block mean error and a twenty-two-block swing on a dead straight leg**.

### flocking

No slots at all. Reynolds' boids (cohesion, alignment, separation) plus a migration term toward a point
behind the leader, which is what stops a flock that is beautifully coordinated about going nowhere.

It will not hold a formation and is not meant to. What it has instead is that nothing can break it: no anchor
to drift, no slot to be unreachable, no assumption about how many drones there are or where they started. A
squad scattered by a near miss re-gathers under this where a slot-based model would still be flying each
survivor at a station on the far side of the group.

`SEPARATION_FRACTION` (0.9 of the squad's spacing) is what gives the flock a *size*, and leaving it out is
the classic mistake: cohesion has no preferred distance in it, so a flock with only cohesion collapses to a
point. The shared hull-clearance backstop does not save it either: the flock just settles at the width of
the backstop. Measured, that was **every drone touching another on essentially every tick** of a
2000-tick leg.

### Measured

Four drones, vee, 12-block spacing, 2400 ticks, flown through the real `DroneBrain`. Error is each
follower's offset from the leader in the leader's own heading frame against the offset the formation calls
for (deliberately model-neutral, so no model's reference is privileged by the measurement. "Form-up" is the
first tick from which every follower stays inside a block of its station; `)` means it never did.

| leg | model | mean err | max err | speed % | form-up |
|---|---|---|---|---|---|
| straight | virtual_structure | 0.00 | 0.00 | 100.0 | 77 |
| | leader_follower | 0.00 | 0.00 | 100.0 | 44 |
| | consensus | 0.00 | 0.00 | 100.0 | 64 |
| | flocking | 20.09 | 37.07 | 106.4 |: |
| turn r=150 | virtual_structure | 0.08 | 0.12 | 95.1 | 572 |
| | leader_follower | 0.03 | 0.15 | 94.9 | 613 |
| | consensus | 0.04 | 0.14 | 95.1 | 632 |
| **turn r=60** | **virtual_structure** | **0.19** | **0.29** | 91.3 | **670** |
| | leader_follower | 0.80 | 4.05 | 91.3 |: |
| | consensus | 1.75 | 5.25 | 91.5 |: |
| turn + climb | virtual_structure | 0.29 | 1.50 | 94.3 |: |
| | leader_follower | 0.03 | 0.15 | 94.9 | 612 |
| | consensus | 0.04 | 0.14 | 94.9 | 630 |

**It is a trade, not a clean win.** On easy legs the three slot-based models are indistinguishable. The
difference is at the limits: on the tightest turn `virtual_structure` is the only one that holds formation at
all, with a fourteenth of `leader_follower`'s peak error, while on a simultaneous turn and continuous climb
it is the loosest of the three, because it smooths its reference and therefore lags a turn rate that keeps
changing. It is the default because its *worst* case is much the best, and peak excursion is what puts drones
into each other; 0.29 blocks of steady offset on a 12-block formation is not something anybody will see.

Old worlds and old pad configs load as `leader_follower` (`CoordinationModels.LEGACY`), so nothing already in
flight changes architecture underneath itself.

## 13b. Staggered launch and form-up

A flight goes up **one drone at a time**, and nobody leaves until everybody is up and in place.

`DroneMission.launchInterval` (default 20 ticks, 0..1200) is the gap between releases. `DroneMission.dispatch`
puts the leader up immediately, everything a caller does with the result is about the drone carrying the
mission, and hands the rest to `DroneLaunchQueue`, a per-dimension `SavedData` ticked from the level tick
before the AI runs. Setting the gap to 0 restores the old all-at-once behaviour, which is what the scenarios
use.

**The queue is persistent, and that is not incidental.** A launch is a promise to put a specific number of
drones in the air, and the flight already up is *waiting* on it: if a reload lost the queue, the drones that
made it would hold over the pad until the muster timeout and then fly the mission short-handed. So the whole
mission rides along, including the dispatch-time fields (the drawn-once dogleg, the exchange it belongs to)
because a drone that comes up after a restart has to fly the same route as the one that came up before it.
`/wflib drone list` reports how many are still to launch, so "still launching" can be told from "lost
three of them".

### MUSTER

`DroneState.MUSTER` sits between `TAKEOFF` and `TRANSIT`. Each drone climbs out, takes its station, and the
flight departs when every member is present, at cruise altitude, and within half a spacing of its slot.

`squadSize` is what the mission **ordered**, carried on every drone, and it has to be: a squad of six with two
still on the pad has a `SquadView` of four and would otherwise look complete to itself.

Three things this got wrong first time, all of which measurement caught:

- **The settle test was a speed test.** Asking that everybody be nearly stationary reads like the safer
  condition and is one a tight formation can never satisfy: station-keeping pulls in, `SEPARATION_RADIUS`
  pushes out, and at close spacing the two reach a limit cycle rather than a fixed point. Eight drones at six
  blocks' spacing held a permanent 0.24-0.42 b/t of jitter that never decayed, so the flight sat over the pad
  until the timeout every time while being in perfectly good shape. It is a **position** test now
  (`MUSTER_IN_PLACE`, half the spacing): every drone nearer its own place than anybody else's, which is the
  actual question.
- **The flight formed up facing nowhere.** Heading comes from velocity, and a climbing drone's horizontal
  velocity is almost entirely squadmates shoving past, so a leader that would depart on a bearing of 1.57
  spent its whole climb and form-up facing 0.87, and since the formation frame is built on the leader's
  heading, the entire formation swung through that difference the moment it set off. That threw a slot 24
  blocks out by **58 blocks**. `Steering.faceToward` now points a climbing or mustering drone at where it is
  going, which costs nothing because that is where it is going.
- **The virtual frame kept its seeded heading.** Same bug from the other end: with the flight stationary the
  anchor had no travel to take a direction from, so it held whatever it was seeded with while the leader
  turned. `VirtualStructure` now borrows the leader's heading below the travel floor.

A follower still in `TAKEOFF` no longer counts as escorting. A slot is at the flight's altitude, so a drone
that took its station straight off the pad would fly the diagonal to it, dragging three blocks of airframe
through whatever it launched next to. It never showed before because the whole squad left the ground
together.

`MUSTER_TIMEOUT` (1200 ticks) is the backstop for a drone destroyed on the pad or a queue lost to a reload:
otherwise one lost drone holds the whole flight in a hover until every battery is flat.

### Measured

Four and eight drones through the real `DroneBrain`, spawning over time, climbing out, forming up, departing.
"Spread" is the widest pair at departure: for a 4-drone vee at 12 spacing the formation itself is ~34 blocks
across, so a smaller number means it left before it was formed. "Peak" is the worst slot error including the
departure transient; "held" is what it actually flies at, past it.

| launch | contacts | departed | spread | peak err | held err |
|---|---|---|---|---|---|
| 4 drones, all at once | 18 | t=158 | 29.1 | 8.92 | 1.56 |
| 4 drones, gap 20 | 7 | t=215 | 30.4 | **4.73** | 1.65 |
| 8 drones, all at once | 17 | t=228 | 79.8 | 6.52 | 1.58 |
| 8 drones, gap 20 | 51 | t=351 | 79.9 | 8.15 | 1.90 |

Held error is 1.5-1.9 blocks everywhere: whichever way it launches, the flight converges to the same tight
formation. What the stagger buys is a cleaner departure: at four drones, roughly half the hull contacts and
half the peak excursion. At eight it costs contacts rather than saving them, because the later drones climb
out through a flight already assembled overhead; those are position-only hull brushes that `Contacts`
resolves without touching flight state, and the formation is unaffected.

Most of the improvement in the all-at-once rows is `MUSTER` too: it applies to any flight of more than one,
so even a simultaneous launch now forms up before it leaves. Before any of this, an 8-drone flight departed
at t=114 with a spread of 34.8 (i.e. not formed) and a peak error of **97.6 blocks**.

One thing tried and reverted: spawning the launch points *across* the outbound track rather than along a
fixed axis. The reasoning is sound, a launch line down the flight path puts each climb-out under somebody
else's station, but measured over four and eight drones, tight and loose, it came out at three times the
hull contacts, because which drones have to cross depends on the shape and the count rather than on the axis.
The comment in `DroneMission.spawn` records it so the idea is not re-tried blind.

## 13c. Programs

A mission is a **queue**, not a destination. `DroneProgram` is an immutable ordered list of `DroneTask`s plus
a cursor; `DroneEntity` holds one, and so does `SimDrone`, so a program survives the off-world boundary.

| Step | What it does |
| --- | --- |
| `moveto` | A waypoint. Passed at the dogleg-corner radius and never stops the drone. |
| `deliver` | Put the slung crate down here. |
| `collect` | Pick a crate up from here. |
| `strike` | Run in and pickle the payload (breaks off cruise `PAYLOAD_RUN_IN` short). |
| `loiter` | Watch a place: orbit at a radius, or hold station when the radius is 0. |
| `exfil` | Go home. Ends the program. |

The trick that keeps this from being a second brain competing with the state machine: **`destination` stays
the authority.** Everything below the queue (terrain sampling, the route search, the battery arithmetic)
already reads that one field, and it still does. A program only *drives* it: `AdvanceTask` writes the next
step's point into the destination, and `TransitHandler` asks the current step what to hand over to on arrival
instead of deciding for itself. A drone with no program (or one loaded from a world saved before programs
existed) reads back exactly the old behaviour from `DroneSnapshot#arrivalState` and flies as it always did.

Two things end a program early, and both have to clear the queue as well as the destination, or the drone
reaches its exfil point, finds the next step waiting, and sets straight back out on charge it just decided it
did not have: a battery override (`DroneBrain`) and an aborted drop into an occupied zone.

Followers step their queue when the leader steps its (`DroneBrain`, escort branch). A drone pinned to a
formation slot never arrives anywhere itself, so its handlers never run: without this it would sit on step
one for the whole flight and price its battery against a waypoint the squad passed minutes ago.

**Surveillance.** `loiter` puts the drone in `SURVEIL`, which is the one cruising state that does not route:
it is not going anywhere, so it holds cruise altitude above whatever it happens to be over rather than
following a planned path. A worker cannot see the player list, so contacts are sampled on the world thread
(`DroneOrders#sweepForContacts`, `WATCH_RADIUS`) and arrive already named in the snapshot; the handler only
decides they are worth writing down, as `SPOTTED` telemetry. The already-seen set lives on the entity, which
is where `DroneStateHandler` says per-drone state belongs, and is cleared on leaving station.

An open-ended loiter (`0` seconds) runs until `PowerPolicy.canHoldStation` says otherwise: the run home plus
`LANDING_RESERVE` plus `STATION_MARGIN`. That margin is why an open-ended watch ends with the drone landing
*at* the exfil point rather than short of it.

## 14. Off-world simulation

`SimDroneManager` decides *when* a drone stops being an entity, not how it flies:

- **Offload** while in `TRANSIT`/`EXFIL`, more than ~128 blocks from the current leg's waypoint, with no
  player within `OFFLOAD_PLAYER_RANGE`.
- **Onload** within `WAYPOINT_ONLOAD_RANGE` of that waypoint, when a player comes near, or whenever the state
  is no longer a travel leg. That last condition matters: the sim has no real terrain to measure against, so a
  drone left simulated while landing would descend forever.

The crate entity is discarded on offload with its contents carried in the `SimDrone` record, and rebuilt on
onload. Note the waypoint is leg-aware: on the way home it is the exfil point, not the destination the drone
still remembers.

## 15. Flying one

```
/wflib drone dispatch <x> <y> <z> [count] [formation] [spacing]   # cargo run, with a crate
/wflib drone strike  <x> <y> <z> [count] [warhead]     # attack run, one warhead each
/wflib drone list                                       # live + [SIM] drones, state, battery, load
/wflib drone pad <padPos> <x> <y> <z> [count] [formation] [spacing]

/wflib drone program moveto|deliver|collect|strike <x> <y> <z>
/wflib drone program loiter <x> <y> <z> [radius] [seconds]   # 0 radius = hold, 0 seconds = until low
/wflib drone program hold <x> <y> <z> [seconds]
/wflib drone program exfil
/wflib drone program list | remove <n> | clear
/wflib drone program launch [count] [formation] [warhead] [spacing]
```

Program steps accumulate in a per-player draft (`DroneDrafts`, in memory, not saved) because a single command
cannot take an arbitrary number of waypoints. `launch` reads the mission kind off the queue: a program that
delivers gets a crate, one that strikes gets a warhead, one that only watches gets neither. The drone pad's
screen has a `Program` button opening the same queue as an editable list (`DroneProgramScreen`), and the
program rides to the server inside the existing `DroneMission` packet.

A `Drone Pad` block holds 27 cargo slots (right-click), stores a `DroneMission`, dispatches on a redstone
rising edge, and recharges drones parked on it.

Both dispatch paths build the same `DroneMission`, the `LaunchConfig` analogue.

## 15a. The drone pad's debug UI

Right-click a `Drone Pad` for the config screen; sneak-right-click for its cargo crate.

The screen mirrors the missile dispenser: cycle buttons for mission kind (delivery/strike), formation and
warhead; typed boxes for destination, drone count, cruise speed, altitude, battery, release speed and
formation spacing; and
**Dispatch / Save / Recall / Clear all drones**.

The readout along the bottom is the useful part, and it is honest because it runs the real code:
`PowerPolicy` and `Steering` are pure functions of a mission with no world access, so the screen calls exactly
what the drone's brain will call a moment later:

- range, rough ETA, and what percentage of the battery the outbound leg costs;
- **WILL BE REFUSED** with the exact charge shortfall, before you waste a launch;
- **ONE-WAY** when it can deliver but not return, so a stranded drone is a decision rather than a surprise;
- on a strike, the ballistic release lead: how far short of the target it will pickle.

Change a number and the verdict updates as you type.

## 15b. Testing commands

```
/wflib drone selftest            # assert the decision layer; prints pass/fail
/wflib drone scenario <name>     # delivery | strike | squad | lowbattery | downed | longrange | terrain
/wflib drone threads             # which thread the A* is running on, and what it costs
/wflib station code              # station codes, allow-lists and handshakes: see 10d
/wflib drone sim <on|off>        # off keeps drones real for a whole mission
/wflib drone recall              # turn every drone around, wherever it is
/wflib drone clear               # delete drones, crates and off-world records
/wflib drone telemetry           # the nearest drone's event timeline
```

`selftest` is the one to run after changing tuning values or handlers. Everything the AI decides is a
function of a `DroneSnapshot`, so the whole decision layer can be driven from synthetic snapshots with no
world at all: battery decisions across the full range/charge matrix and the shape of the power curve; the
flight model (it leans to accelerate, cannot snap its attitude, tops out where `topSpeed` says it will,
climbs worse laden, and turns its rotors at a speed that is not a multiple of the prop's own symmetry); the
navigation (the guard climbs and brakes, the planner routes around a wall too tall to climb and finds the gap
in it, a route is followed by pure pursuit and goes stale when the destination moves); squad safety (aim
points are spread, coincident drones scatter, drones in formation are left alone); the ballistics; the
formations; the state machine; and NBT round-trips. It runs instantly and needs nothing in the world.

The scenarios each set up one situation worth watching: `lowbattery` gives a drone just enough to arrive and
strand, `longrange` is far enough to offload to the sim and back, `downed` launches one and shoots it out of
the sky a few seconds later so you can watch it spin in, and `terrain` flies a long leg at 10 blocks AGL
instead of the usual 40: at 40 a drone simply flies over most terrain and never has to think about it.

`sim off` is what makes the terrain following observable at all. The off-world simulation deliberately has no
terrain (an offloaded drone holds its altitude and flies straight, which is the trade that makes it cheap)
and with no player out on the route, everything offloads. Turning it off keeps a drone in the world for the
whole mission.

## 16. Testing a drone design

- `wflib drone list` is the workhorse: state, altitude, distance to destination and exfil, battery,
  whether it is carrying, and whether it is currently `[SIM]`. The flight columns on the end are what to watch
  when something looks wrong:

  ```
  • TRANSIT  y=72  dst 391m  exf 176m  95% bat  carrying spd0.75 thr1.06 tilt20° agl9  30wp+  [lead]
                                                         ^^^^^^^ ^^^^^^^ ^^^^^^^ ^^^^  ^^^^^
                                                         speed   effort  lean  clearance  route
  ```

  A drone cruising level should sit near `thr1.06 tilt20°`: those are what `Multirotor.cruiseThrottle` and
  `cruiseTilt` predict for the stock airframe at 0.75 b/t, so a steady flight reading anything else means the
  guidance is fighting something. `agl` should hold at the ordered cruise altitude and **must never go
  negative**. `30wp+` is a 30-waypoint route with a `+` for partial (planned to the horizon, not the goal);
  `no route` means it is flying straight at the destination with only the terrain guard protecting it.
- If drones hover instead of climbing, check that the column below them is loaded: an unsampled ground height
  falls back to "one cruise altitude below me" so they hold station rather than climbing away.
- If a flight porpoises or the throttle swings wildly, suspect the altitude reference rather than the flight
  model: something is handing it a target height that jumps. See the two traps in §10b.
- A delivering drone should read `dst 0m spd0.00 tilt 0°` while it descends. If it is oscillating across the
  drop point instead, it began its approach too close to stop in: see §10a.
- If a delivery is refused, the message reports the exact charge shortfall; either raise `batteryCapacity` or
  move the destination.
- Watch a squad's V through a full cycle: it should hold formation through the climb, the cruise, the delivery
  descent *and* the landing, then all park.

# Construction and Salvage

*Foundations only.* Everything below exists, is tested and is drivable from commands. **Nothing flies it
yet**: no drone state consumes a work queue. `/wflib build` produces a real, persistent job that
sits there until something is written to work it. That split is deliberate: the queue's rules are the part
worth getting wrong cheaply.

## 17. Blueprints (`build/`)

### The format is a loader, not a decision

Everything downstream of a reader sees a `Blueprint` (extents, a palette, one index per cell) and nothing
else. `BlueprintFormats` is a registry in the same shape as `Formations` and `CoordinationModels`, keyed by
file extension, so adding a format is adding a file rather than changing anything.

**Palette index 0 is always air and always means "nothing here"**, enforced in `Blueprint`'s constructor
rather than left to each reader, because the two shipped formats disagree about it. Litematica writes air
into its palette as a real entry and uses it for empty; vanilla structures use `structure_void` for empty and
can also contain deliberate air. Both are normalised on the way in.

Whether "nothing" should mean *clear what is already there* is a property of the **job**, not the file. There
is no flag for it: a build that needs cleared ground is a salvage job over the volume followed by a
construction job. Two jobs that already exist beats a mode inside one.

Blueprints live in `<world>/wflib/blueprints/`. **Operator-placed only, and that is a boundary rather
than a convenience**: nothing here is reachable from a packet handler, and no path lets a client add a file
or name one outside the folder. A blueprint is an arbitrary file the server parses and then allocates memory
proportional to. The caps are for an operator's own mistyped export: 8 MiB on disk before anything is opened,
a 64 MiB `NbtAccounter` quota on the inflated tag (gzip means the file size is not the number that matters),
and 256³ cells on the result.

### Litematica (`.litematic`)

The format players actually have. Three things about it are easy to get wrong, and all three were checked
against the 35 real files on the author's machine rather than remembered.

**The bit array straddles.** A palette index may begin in one long and finish in the next, the way vanilla
chunks packed before 1.16. Litematica never followed vanilla's change to padded longs, and a v5 file settles
it: 45 palette entries over 5980 cells is 6 bits each, and the file stores **561** longs where padding would
need 598. One unpacker covers every version.

**`Size` is signed, and `Position` is whichever corner the selection started from.** A region of
`{-13, -11, 15}` runs backwards along x and y. Miss it and the build comes out mirrored on two axes, which
looks almost right.

**The cells are indexed from the region's minimum corner regardless.** The signs describe the selection, not
the array. Established empirically rather than assumed: a warehouse schematic with `Size.y = -11`, position
at the *top*, has 161 of 195 cells solid in array layer 0 and 2 in layer 10. Layer 0 is the floor. Had it
been indexed from `Position`, every build would come out upside down.

Schema 4 to 7 are accepted; 5 and 6 are the ones there were files to test.

### Data versions are the real problem

Not the packing. Schematics outlive versions, and the corpus here spans data versions **1631 (1.13)** to
**3465 (1.20.1)** against 1.21.1's 3955. Across that distance blocks get renamed and blockstate properties
appear underneath them. Without `PaletteReader`'s data fix an old schematic loads as a field of air, and the
failure looks exactly like a broken parser because every cell is "unknown block" and unknown blocks become
air.

Unknown blocks still become air (one missing mod should cost you that mod's blocks, not the whole file) but
the count is carried on the `Blueprint` and reported, because a build with holes in it has to be able to say
why.

### Vanilla structure NBT (`.nbt`)

Here because it costs almost nothing and pays for itself twice: anyone can produce one with a structure block
and no third-party mod, and one can be built *in code*, which is how `BuildSelfTest` exercises the reading
path with no binary fixture in the repo.

### What the reader was measured against

- **The unpacker, against an independent implementation.** Every region of every `.litematic` on disk (35
  regions, **2,840,225 entries**, 174,511 of them straddling a long boundary, bit widths 3 to 6) reproduced
  exactly. Plus a round-trip over widths 2..16 for the cases the corpus does not contain, and out-of-range
  reads returning 0 rather than throwing.
- **The geometry, against Litematica's own metadata.** Every file loaded through the shipped reader:
  **35 of 35 enclosing sizes match** the `EnclosingSize` the file wrote itself.
- **The block counts, against `TotalBlocks`.** Exact on all 11 files whose blocks this server actually has,
  including files at data versions 1631, 2580, 2584 and 2865, so the data fix path is right, not merely
  present. The other 24 are GregTech/WFCore schematics whose palettes correctly resolve to air, reported as
  unknown states rather than silently dropped.

## 18. The work queue (`work/`)

Domain-blind on purpose. It knows about claims, deadlines, retries and ordering; it has never heard of a
block, and nothing in the package imports anything from `build` or `drone`. A `WorkOrder` is a position, an
ordering key and one integer: enough to describe placing a block, breaking one, or the next thing.

Three things make it more than a list with a lock.

### The sequence gate

Handing out any pending order sends workers to jobs that cannot be done yet: a torch before its wall, sand
with nothing under it. Rather than model dependencies, every order carries a `sequence`, and the queue
refuses to hand out anything more than `LOOKAHEAD` (2) beyond the lowest one still outstanding.

Coarse, and meant to be: one integer per order, no graph, and it expresses "layer by layer" exactly, which is
what both jobs need. A lookahead of zero would be a strict barrier that throttles the whole job to its slowest
worker at every layer boundary; two lets workers start the next layer while the last few of this one are
still in the air.

Orders are **sorted by sequence at construction**, so the ready window is a contiguous run of indices from a
cursor that only moves forward. Claiming scans a layer rather than the whole job.

### Deadlines scale with distance

A fixed timeout is the obvious design and it is wrong: how long an order should take depends on how far away
it is. Set it flat and a site five hundred blocks from the depot has every claim expire **while the worker is
still flying to it**: the queue reissues work to workers already on their way, they arrive to find the job
gone, and it never converges.

`deadlineFor` is straight-line travel at the worker's own cruise speed, times `DEADLINE_SLACK` (3), plus
`WORK_ALLOWANCE` (100 ticks) for doing the job, floored at `MIN_DEADLINE` (200). Measured: 2 blocks away
gives 200 ticks, 2000 blocks gives 8103 against a bare flight time of 2666. The slack is generous because the
failure modes are wildly asymmetric: too long merely delays recovering an order that was genuinely lost,
too short breaks the queue outright.

### Claims repel each other

Four workers asking at the same moment would otherwise be given the four nearest orders, which are next to
each other, which puts four of them in the same place: the exact problem the separation and formation work
solved for cruise. Claiming scores candidates by distance *plus* `CROWDING` (4 blocks of equivalent travel)
per block of encroachment inside `CLAIM_SPACING` (8). A soft penalty rather than a hard exclusion, so a queue
whose remaining orders are all in one corner still hands them out instead of deadlocking.

### Retries, and who gets blamed

- `complete`: done. Rejected if the caller does not hold the order, so a stale worker cannot mark work that
  was reassigned underneath it.
- `release(penalise = true)`: got there, could not do it. Counts against the order.
- `abandon`: the *worker* failed (destroyed, recalled, out of battery). **Costs the order nothing.**
- `lapse`: the deadline passed. Counts, because a worker that took a claim and silently stopped existing is
  indistinguishable from an order that cannot be reached, and the only thing that separates them over time is
  that the unreachable one keeps happening.

At `MAX_ATTEMPTS` (3) an order becomes `BLOCKED` and stops being handed out. Terminal on purpose: a block
that cannot be placed must stop consuming flights. It is counted separately from `DONE`, so a job that
"finished" with half of it blocked does not read as a success.

Getting the abandon/lapse split backwards means three unlucky shoot-downs permanently block a perfectly
placeable block, which is why there is a test named after it.

### Persistence

Orders are written as three primitive arrays plus two byte arrays, not a list of compounds: a build of any
size is tens of thousands of orders, and a compound each would be megabytes of tag objects on every autosave.
An order left `CLAIMED` by a save with no surviving claim goes back in the pile **unpenalised**; the world
reloaded, which is nobody's fault.

`WorkRegistry` is server-wide, on the overworld, matching `StationRegistry`. Finished jobs are kept for
`RETENTION` (20 minutes) rather than deleted, because "finished, 40 blocked" is the answer to how the job
went and a job that vanished would take that with it.

## 19. Plans

`ConstructionPlan` and `SalvagePlan` are the same mechanism in opposite directions, and the ordering is their
whole job. A queue handed unordered orders will cheerfully send a drone to place a torch in mid-air two
hundred blocks from anything, and the drone will cheerfully fly there.

`sequence = y * 2 + tier`. Layers ascend; within a layer, structure goes up before anything hangs off it.
Salvage mirrors both axes: layers descend, and attachments come off before their support. Take the wall out
first and the torch falls on the floor, which for a job whose point is recovering the material is a job that
half works.

**The tier test is a proxy.** `Placement.tier` asks whether the block has any collision box of its own,
evaluated against an empty world. Torches, rails, plants, redstone, buttons, levers, wall signs, banners and
vines all come out with nothing; stone, stairs and slabs have a box. It misses doors, trapdoors and beds,
which have boxes and still need support, but those attach within their own layer or below, which the layer
ordering already handles. Anything it gets wrong is a retry, not a lost block.

`Placement.derived` leaves out cells that appear when something else is placed: the upper half of a door, the
head of a bed, the top of a tall flower, piston heads. An order for one would fly a drone out to build a
block that is already there and, worse, bill the job for a second door.

### The bill

Exact for a construction, because the item that places a block is the item it needs. An **estimate** for a
demolition, because what a block actually drops is a loot table and stone mined without a pickaxe drops
nothing.

Vanilla covers more of this than expected, and it was worth checking rather than assuming: **wall-mounted
variants all resolve**, because `StandingAndWallBlockItem` registers both halves of the pair against one item:
 `wall_torch` gives `torch`, `oak_wall_sign` gives `oak_sign`. Crops resolve to their seed, which is exactly
what places them. (The first version of this documented the opposite, and had a test asserting it. Both were
wrong.)

What is genuinely left is **72 of 1060 vanilla blocks**, in three groups: fluids and fire, which need a
bucket or a flint and steel; technical blocks nobody places by hand (`piston_head`, `nether_portal`,
`attached_melon_stem`); and potted plants, which are two actions rather than one. Only the last is a real
loss for a real build, and it is why the count is reported instead of the cells being dropped in silence. A
sugarcane farm in the corpus comes out with 721 of 6350 cells itemless: all water.

## 20. Station roles

A station holds a **set** of `StationRole`, not one of them.

| Role | What it means |
| --- | --- |
| `launch` | Dispatches drones of its own. |
| `depot` | Accepts cargo somebody else sent: the receiving half of a handshake. |
| `materials` | Supplies building materials to a construction job. |
| `recharge` | Recharges drones that land on it. |

A set rather than a type because the capabilities genuinely compose. A pad that supplies materials and
accepts returns but never launches anything is a sensible thing to want, and under a one-of-many enum it
would need its own constant, as would every other combination anyone thought of.

`StationKind` is then a **name** for a set, not a type in its own right: `provider` is `{materials}`,
`station` is all four. Nothing in the mod branches on a kind (every decision is "does this station do X",
asked of the set) so a station whose roles match no preset is not broken, it is `custom[materials
recharge]`. That is what lets presets be added later without invalidating anything saved.

Roles are persisted by `name()`, so constants can be reordered and inserted. **An absent `Roles` tag means
all four**, because every pad registered before roles existed did all four things and a world reload is not
the moment to quietly stop half of them working. Absent is distinguished from empty: an operator who
deliberately turns every role off gets an inert station back, not a reset one.

`StationRegistry.allows` now asks two questions (is this station willing to receive cargo at all, and is it
willing to receive it from you) so a supplier that only hands materials out cannot be flown crates.

## 21. Driving it

```
/wflib blueprint                     list what is in the folder
/wflib blueprint <name>              size, block count, bill, and any caveats
/wflib blueprint reload              drop the cache after editing a file
/wflib build <name> [at <pos>]       queue a construction job (default: where you stand)
/wflib salvage <from> <to>           queue a demolition over a volume
/wflib job                           list jobs, with progress and how many are blocked
/wflib job <id-prefix>               detail, including the queue's frontier
/wflib job cancel <id-prefix>        call one off
/wflib station role                  what the pad you are standing at will do
/wflib station role set <kind>       provider | station
/wflib station role on|off <role>    one flag at a time
```

Blueprints load with or without their extension, and job ids match on a prefix because the list prints
prefixes and nobody is retyping thirty-six characters.

## 22. Testing the foundations

`/wflib drone selftest` runs 26 checks for this on top of the flight suite: **254 total**. They are
pure functions of their inputs: the format readers take a tag rather than a file, and the queue has never
heard of a world.

The ones worth knowing about, because each is named after a mistake:

- `blueprint/litematica-negative-size`: a region whose x and y extents both run backwards, with one block in
  array cell (0,0,0). Indexing from `Position` instead of the minimum corner puts it at the opposite corner.
  The cheapest possible test for the error that would be hardest to see.
- `work/abandon-is-free-lapse-is-not`: the attempt-counting split, above.
- `work/claims-repel`: two workers at the same point must not be sent to the same corner.
- `work/only-the-holder-completes`: a stale worker cannot mark somebody else's order done.
- `work/orphaned-claims-recovered`: a reload must not permanently strand a claimed order.
- `plan/door-halves-count-once`: one order and one item for a door, not two of each.
- `plan/wall-variants-resolve-to-their-item`: exists because the opposite was assumed first.
- `station/legacy-keeps-every-role`: a pad from before roles keeps doing everything.

Not in the suite, because it cannot be: the unpacker's agreement with 2.8 million real palette entries, and
the geometry's agreement with 35 files' own metadata. Those are development-time checks, described in §17.

## 23. Drones that actually build (`WORK`, `SUPPLY`)

Two states and one class of world-thread logic turn a queue into a building site.

### The split, and why it is where it is

A `DroneStateHandler` runs **on a worker thread against a snapshot**. It therefore cannot claim an order,
complete one, or even ask whether one exists: two workers racing on the same `WorkQueue` would hand the same
block to two drones. So the division is absolute:

- **The handler** flies to `WorkAssignment.target()` and, once it has stopped moving, emits `FinishWork` or
  `ExchangeSupplies`. That is all it does.
- **`BuildPilot`**, on the world thread, decides what the arrival meant, touches the queue, and changes
  blocks.

The cost is one tick of latency between finishing a block and being pointed at the next, against several
seconds of flying between them.

`WorkAssignment` is the one field this adds to `DroneSnapshot`: a record rather than five fields, because
that record already has seven copy constructors. Exactly one of `order` and `station` is normally set, and
which one is the drone's whole intention.

### The loop

```
TAKEOFF -> TRANSIT -> WORK -> TRANSIT -> WORK -> ... -> TRANSIT -> SUPPLY -> TRANSIT -> ...
```

`BuildPilot.assign` claims an order; for a construction it then checks the drone is carrying the item, and if
not gives the order back **unpenalised** and diverts to a station. Claiming first and discovering afterwards
wastes one claim per resupply, and is much better than the alternative: peeking at the queue without taking
anything, which two drones can both do and then both fly to the same block.

When nothing is claimable but the job is not finished (everything inside the lookahead is taken), the drone
goes to top up rather than hovering. A trip to the station is never wasted.

`WORK` hovers `WORK_HEIGHT` (4) above the block, **measured from the block and not from the ground under the
drone**: the two are the same on flat ground and very different halfway up a tower. Hovering directly over
the target is only safe because of the order the plan imposes: a construction builds bottom-up so the space
above is still empty, and a salvage strips top-down so it has just been emptied. Worked in any other order
this would fly drones into their own structure.

### What the integration cost

Four things had to change elsewhere, and each of them was a bug waiting to happen:

- **Job flights hold no formation.** `DroneBrain.escorts` now excludes a drone with an assignment. Every
  drone on a build claims its own block, so they are going to different places from the moment they leave the
  pad; a slot measured off the leader would drag each of them away from the block it is meant to be over.
- **Job flights do not muster** either, for the same reason. Left in, they would have waited the full
  `MUSTER_TIMEOUT` every launch, trying to reach slots nothing was steering them to.
- **A battery abort has to release the claim.** `PowerPolicy` turns a drone for home from inside the brain;
  without `BuildPilot`'s stand-down check the drone would fly home still holding an order, which would then
  lapse and be **charged an attempt**: exactly the abandon-versus-lapse mistake the queue's split exists to
  prevent.
- **So does dying.** `DroneEntity.remove` abandons every claim. Waiting for the deadline would leave the
  order unavailable for minutes and then penalise it for a drone that no longer exists.

An offloaded drone carries its assignment into the sim and back. It never has to *do* anything there: `WORK`
and `SUPPLY` are not offloadable states, so a drone only ever offloads on the leg between one block and the
next.

### Materials

A station's store is the pad's own 27 slots: the same ones the cargo screen shows, not a hidden inventory.
A resupply loads only what the **next 512 pending orders** need rather than the whole bill, because on a
large build "one of everything" means running out of the thing actually coming up.

Demolition uses the block's real loot table, so what comes back is what a player would have got, which is
why a salvage bill is only ever an estimate. Anything that will not fit is dropped rather than deleted, and a
full depot means the drone comes back still loaded.

`placeFromCargo` treats a block that is *already the right state* as done rather than paying for it twice,
and puts the item back if the world refuses the placement after it has been taken.

## 24. Territory: no building in a war zone

With WarForge present, drones will not change blocks where the mod says they should not. With it absent every
answer is yes: a server with no factions has no territory to trespass on.

The verdict, in the order the answers matter:

| Situation | Verdict |
| --- | --- |
| Inner zone of an active siege | **refused**: a battle in progress |
| Outer battle zone, or a conquered chunk | **refused**: war zone |
| Safe zone | **refused** |
| Unclaimed | allowed |
| Our own claim, an ally's, or a truce | allowed |
| Anyone else's claim | **refused** |

The siege is asked **first**, and that ordering is the point: `getClaim` would otherwise report the
defender's ordinary claim and read as friendly *to the defender*, so a faction being besieged could quietly
rebuild through it. The ground being fought over is precisely the ground nobody should be building on.

A job records the faction of whoever created it, once, at creation. Not read off whichever drone turns up:
the question is "may **this job** happen here", and the answer must not change with who flew the last sortie.

### Asked three times

Each catches something the others cannot:

1. **Creating a job** surveys the whole volume and refuses, saying *where*: `Cannot build there: it is a war
   zone (at 148, -302).`
2. **Every 200 ticks** a running job is re-surveyed. Territory is not static: a siege can start on top of a
   half-built structure. This **suspends** rather than cancels: the job keeps its progress, stops being
   handed to drones, and resumes when the siege lifts. A suspension is not persisted, because a stale reason
   on disk is worse than none; it is re-derived within ten seconds of a reload.
3. **At the moment of placing or breaking**, as the backstop. The re-check is coarse and the drone has been
   in the air longer than ten seconds; a siege that began while it was inbound has to be caught here or not
   at all. This is the one that cannot be raced.

`WarforgeCompat.setTerritoryProtectionEnabled(false)` turns the whole gate off, for a server that wants
factions for combat but not for building. It is separate from the explosion claim protection because they
answer different questions.

Two smaller things fell out of this. `StationRegistry.allows` now also requires the recipient to hold the
`depot` role, so a supplier that only hands materials out cannot be flown crates. And `WarforgeCompat`'s
`LOADED` flag is now computed in a guarded method: `ModList.get()` is null until mod loading has got far
enough, and an unguarded call in a static initialiser turns that into an `ExceptionInInitializerError` that
poisons the class for the rest of the process.

## 25. Running a build

```
/wflib build <name> [at <pos>]     queue the job (refused outright on bad ground)
/wflib job work <id-prefix> [n]    put n drones from the nearest pad onto it
/wflib job <id-prefix>             progress, blocked count, suspension reason
```

The drones fly out as an ordinary squad, which is what gets them staggered launch, terrain following and
battery aborts for nothing. What makes them builders is the assignment handed over the moment they are
airborne; from the flight model's point of view the destination simply changes every time one finishes a
block. The job id rides on the `DroneMission` rather than being stamped onto the drones dispatch hands back,
because on a staggered launch most of them do not exist yet.

Chunks around live claims are held loaded: nothing can be placed into an unloaded chunk, and a drone that
arrived to find one would fail the order three times and block it. The hold is renewed per tick and lapses on
its own, so a finished job stops holding without having to remember to let go.

## 26. What pass 2 was measured on

**270 self-tests, 0 failures**: 16 more than pass 1. The ones named after mistakes:

- `work/closed-site-drops-the-target`: a suspended site must stop being somewhere to be, not merely be
  advisory, or a drone flies out and hovers over ground it is forbidden to touch.
- `work/arrival-state-follows-the-assignment`: order → `WORK`, station → `SUPPLY`, neither → `EXFIL`,
  closed → `EXFIL`.
- `work/job-drones-do-not-hold-formation` and `work/job-flights-do-not-muster`.
- `work/site-state-is-not-persisted`: `siteOpen` is a sample of the world; a stale one would leave a drone
  permanently stood down over ground that is fine.
- `work/suspension-is-reversible`: progress survives, and the job comes back.
- `territory/survey-covers-every-chunk`: a 61-block box straddling chunk boundaries on both bounds hits all
  25 chunks it touches. **This is the one that matters**: a protection that misses a corner fails silently,
  because a missed corner looks exactly like permission.
- `territory/samples-land-inside-the-job` and `territory/refusal-reports-where`.

**The honest gap**: the verdict *mapping* itself (siege refuses, ally allows, and so on) is not covered,
because it needs WarForge on the classpath and the self-test suite runs without it. What is tested is the
part this codebase actually wrote: the chunk walk, the reporting, the suspend/resume cycle, and the
degradation to "allowed" when there is nothing to ask. The mapping is a twenty-line translation of
`getSiegeZone` and `getClaim`, and it is unverified.

Everything remains **headless**. Nothing in either pass has been run in a live game.

---

# Kinetic Rounds

## 27. What one is

Unguided round (bullet, shell, bomb) with no entity: `round/Rounds`, one struct-of-arrays batch per level, a transient
`SimKind` resolved in Post (`EARLY`). Rockets (§29a) and missiles stay entities (targetable).

- Contract: `pos += v; v = v * decay - g`, `decay = (double) (1f - drag)`; water state from the pre-move position.
  ywzj's closed-form solver inverts exactly this.
- Collision per tick: one block clip over the segment through **loaded chunks only** (unloaded = air, never a
  sync load); entities by `PreciseHitbox`, else AABB + 0.3. The shooter's whole ride stack is never hit.
- Strike: `ProjectileStrikeEvent` with `projectile() == null`, `key()` = round key; damage through
  `RoundDamageSource`, a `StrikeContext` (threat, travel, key). Armour/hit code reads `StrikeContext.of(source)`.
- Wire: one `RoundPacket` per player per tick (spawns grouped by preset, end keys); audience 1024 blocks, whole
  level for chunk-loading rounds. Client (`round/client`) flies the same recurrence without collision; tracer
  streaks (`TracerRenderer`), rigged models (`RoundsVisual`, one Flywheel effect per level).
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
| Terminal | `mass`, `impactDamage` | direct hit = 0.05 m v^2 unless flat |
| | `headshot(multiplier)` | living target hit within eye height +- 0.25 |
| | `penetration(blocks, resistance)` | re-swept after each drilled block within the tick |
| | `airburst(height)` | ray down of height + one tick's travel |
| | `proximity(radius)` | |
| | `fragments`, `blastHalfAngle`, `blast(size, breaksBlocks)` | to the warhead; blast credited to the shooter |
| | `passesThroughEntities()` | hurt and keep going |
| Behaviour | `noChunkLoading()` | lost at unloaded ground (bullets) |
| | `tracer(rgb)` | streak colour; 0 = none |
| | `stackSize` | |
| | `quadraticDrag(k)` | `decay = 1 - drag - k\|v\|` (clamped 0); client flies the same |
| Ground fuze | `fuseDelay(ticks)`, `burrow(hardness)` | block contact => rest (v = 0, resynced), dig down while budget >= destroy speed (min 0.1), then fuse |
| | `noEntityContact()` | entities never swept (bombs) |
| Rocket only | `motor(accel, ticks)`, `durability` | blocks/tick^2 along the nose; health vs air defence (8) |

## 29. Ground

Segment end in an unloaded chunk: chunk-loading round below the build limit => ticket for that chunk, waits in place
(life refunded) until it loads; else lost. Above the build limit nothing is checked. Transient: not saved.

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

## 30. Loading and firing one

Guns live in ywzj_vehicle (`org.ywzj.vehicle.compat.wflib`; its `docs/vehicle-pack-format.md` §11).

```java
long key = Rounds.fire(level, preset, muzzle, direction, inaccuracyDegrees, carrierVelocity, shooter, factionId);
Rounds.position(level, key); Rounds.within(level, box); Rounds.destroy(level, key); // APS
```

Registering: `KineticPresetRegistry.register(KineticPreset.builder(...).build())` during mod construction => a
carryable item. Vehicle weapons register model-less presets later (no item).

## 31. What is verified

`round/gametest/RoundGameTest`: arc vs an independent recurrence (through the build limit), chunk ticket on
descent, bullet lost over unloaded ground, direct hit carries its `StrikeContext`, contact fuze, wall crossing,
drilling, airburst, bomb burrow + fuse, resting, headshot. `RocketGameTest`: burn then coast, contact fuze, radar contact + kill past durability.
Rendering: not yet looked at in a client.

---

# NTM Doors

## 32. What they are

Fifteen of them, ported from NTM's `DoorDecl` family: the vault door, the transition seal, both silo
hatches, and every sliding, folding and swinging thing in between. Each is a **multiblock** (one core
block that thinks, and a box of placeholders around it that point at it), and each is drawn as one
GemRender model rather than as a stack of matrix pushes.

**They do not open by hand.** There is no bare-handed path at all, by design: redstone opens them, and a
key cut to a door's own lock opens that one. A blast door a passer-by can push is not a blast door.

| | |
| --- | --- |
| Package | `com.wf.wflib.door` (`door/client` for everything that draws) |
| Table | `DoorType`: fifteen entries, one per door |
| Blocks | one `DoorBlock` class, told which door it is by a constructor argument |
| Rendering | `DoorVisual`, a Flywheel `BlockEntityVisualizer` over GemRender |
| Command | `/wfdoor build <type>`, `/wfdoor row [spacing]`, `/wfdoor toggle` |

## 33. Reading the table

Everything that differs between two doors is a number in `DoorType`.

| Field | What it means |
| --- | --- |
| `dimensions` | `{up, down, north, south, west, east}` from the core, in the frame the door has facing south. `DoorFrame.rotate` turns it into world extents, so a door never restates its own geometry four times. |
| `openRanges` | `{x, y, z, run, width, axis}`: a line of blocks the door clears as it opens. `run` is length and direction (its sign), `width` how many blocks wide, `axis` which way the width lies. A door may have several; the airlock has one each way. |
| `blockOffset` | how far past the clicked block the core sits. Only the silo hatches use it: they are laid flat and their core is the middle of a pad. |
| `timeToOpen` | ticks from shut to open. Ten for the QE slider, four hundred and eighty for the transition seal. |
| `skins` | how many textures the door can wear. Chosen when it is placed. |
| `blockBound` | the box one block of the door collides and is picked as, given where it is relative to the core and whether the door is open. |
| `rangeOpenTime` | when in the travel a range clears. Defaults to "in step with the door"; the water door clears late, at tick 35 of 60, and the silo hatches clear all at once at tick 20. |

The numbers are NTM's numbers, so a bunker drawn to an NTM plan still fits.

## 34. How a door is one block and also thirty

`DoorBlock` covers the core and the placeholders; `ROLE` tells them apart and `FACING` is the door's own
facing on a core and **the step toward the core** on a placeholder. Finding the core is therefore a walk,
not a search: no scan, no stored coordinates, and a chain that cannot outlive the blocks it runs through.

`DoorRole.OPEN` is a placeholder the door has swung past: same block, no collision, nothing drawn. That
is how a door wider than its own frame stops blocking the doorway without the multiblock coming apart, and
it is a blockstate rather than a field so the client knows it without being told.

Three rules fall out of that and are worth knowing before extending it:

- **Placement is all-or-nothing.** `DoorItem.canPlace` runs the space check before the item is spent, so a
  door that will not fit is never a half-door and never eats the item. Anything else placing the block (
  a dispenser, another mod) is caught in `setPlacedBy` and given the door back.
- **Breaking any block takes the whole door.** A player's pick clears it and drops one item at the core.
  Anything else (an explosion, a piston) pulls the block next to it toward the core, which cascades
  until the core goes and orphans the rest; without that, a hole blown in one corner leaves the far side
  standing.
- **Structural writes are suppressed.** `DoorFrame.structural` marks the writes a door makes to itself so
  the orphan sweep does not read a door rebuilding as a door being destroyed.

## 35. Opening one

Redstone. A door counts *how many of its blocks* are powered rather than asking one of them, so a door
wired at one corner is not un-powered by an unrelated block update at another. Power opens it; losing all
power shuts it; and while it is held powered it will not answer a key, exactly as a powered iron door
ignores a hand.

The other path is the lock:

1. A **padlock** starts blank. Use it in the air and it cuts itself a random pin pattern and hands you the
   one **key** that matches.
2. Use the cut padlock on an unlocked door and the door takes that pattern.
3. From then on the key opens that door, and sneaking with it cycles the door's skin.

`pickChance` is carried on every lock for a lockpicking mechanic that does not exist yet; a lock placed
today is still meaningful when one does.

## 36. How they are drawn

One model, one instance, one draw per door, and a pose that is a pure function of **one number**, how far
open it is. Nothing about the animation is stored: both sides tick `openTicks` identically from the same
synced state, so a door looks the same on every client, after a chunk reload and after a restart, with
nothing to resynchronise.

That number is what makes them cheap. Doors of a type at the same fraction land in one pose-cache bucket
and share a single evaluated bone palette between them, so a corridor of shut blast doors costs one, and
only the ones actually moving cost anything per frame.

**Two clips, not one played backwards.** Opening and closing are not each other's reverse on several of
these (the vault door pulls its plug clear before it rolls and reseats it after), so each direction is
its own clip, scrubbed by the open fraction through an `AnimationDrive`.

**The meshes are NTM's, given a skeleton.** `DoorRigs` reads each `.obj` as one `RigGeometry` per named
group and hangs the groups on bones; the motion NTM did by hand on the matrix stack becomes `DoorTrack`
keyframes and three `PoseDriver`s (`Slide`, `Turn`, `Gate`). The transition seal is the exception: it ships
as a `.glb` converted from NTM's Collada, and its clip is imported rather than restated.

Two things to know before adding a door or editing one:

- **`RigBuilder` keeps a mesh where the artist put it.** A bone's pivot only matters for rotation. So every
  *constant* offset NTM pushes onto the matrix stack before a part (the portcullis housing three blocks
  up, its mast segments one further apart than the last, the quarter turn its locks start at) has to be a
  driver here, not a bone pivot. Getting that wrong makes a part vanish or sit at the door's feet, which is
  what happened to the portcullis mast on the first pass.
- **Flywheel culls a block entity against a one-block sphere.** A door twenty-six blocks across would
  vanish the moment its core left the frustum, so `DoorVisual` overrides `isVisible` with
  `DoorType.renderRadius`.

### What is not carried over

NTM clips several sliding leaves against a plane, so a leaf that has slid past its frame is cut off.
Flywheel instance shaders have no equivalent, so a leaf driven into open air can show where NTM would have
hidden it. In a wall (which is where a door goes) the wall hides it, exactly as the clip plane did.

The silo hatches read their fold off a 20-to-100-tick ramp on a door that only opens for 60, so they stop
half way. That is NTM's own arithmetic, kept, because a door built to an NTM plan should look like the one
in the plan.

## 37. What is verified

Run in the dev client, not headless. Built with `/wfdoor build`, toggled, and looked at:

- the **sliding blast door** shut and open (frame, both leaves parting into the wall, locks turning;
- the **vault door** through its whole travel) the plug pulling clear of the frame, then rolling aside
  along its rail, the roll coupled to the slide;
- the **transition seal** shut and open: the imported `.glb`, checked against four gold markers at the
  corners its dimensions claim (26 wide, 24 tall), then opened for its full twenty-four seconds;
- the **water door** and the **silo hatch** shut, and the hatch opening;
- state sync, the open-range block clearing, and the probe panel reading a door.

- **redstone**: a redstone block against a vault door opens it and holds it open, and the probe reads
  which of its blocks is powered;
- **the lock and key** end to end: a blank padlock cuts itself a pin pattern and hands over the matching
  key, the cut padlock locks a door, a key with the wrong pins does nothing, the right one opens it, and
  sneaking with it cycles the door's skin;
- **no bare-handed path**: three uses on a shut door with an empty hand leave it shut.

The dedicated server starts clean with all fifteen registered.

Two bugs this found, both of which a compile could not have: the portcullis mast drew at the door's feet
because its constant offsets were bone pivots rather than drivers, and the sliding blast door's locks
were missing the quarter turn they start at for the same reason. A third: the key never cycling a skin
-- was vanilla's rule that a sneaking player holding an item never reaches a block's use handler, which
moved the key's whole behaviour onto the item.

Not looked at: the remaining nine doors' animations, and the sounds.

---

# The Probe

## 38. What it is

A panel next to the crosshair saying what you are looking at. NTM's `ILookOverlay` is the ancestor (a
title and some coloured lines, contributed by whichever block or held item had something to say), and
this is that with the ceiling taken off: an element can be **text, a sprite, an item, a block, any baked
model, a bar, a row, a column, or a lambda that draws whatever it likes**.

| | |
| --- | --- |
| Package | `com.wf.wflib.probe` (`probe/client` for everything that draws) |
| Elements | `ProbeElement` (a sealed set, plus `Custom` as the escape hatch |
| Panel | `ProbeInfo`) a title and a column of elements, appended to by every provider |
| Registration | `ProbeRegistry` |
| Settings | `wflib-probe.toml`: position, scale, whether models draw, refresh rate |

## 39. The elements

Nine of them, and three are three-dimensional. Those three are **not** interchangeable, which is the point:

| Element | Draws | Reach for it when |
| --- | --- | --- |
| `Text` | a line, wrapped to the panel | always |
| `Sprite` | a rectangle of a texture file, scaled | you have a png and want part of it |
| `AtlasSprite` | a stitched sprite off any atlas | you want a block or item texture by name |
| `Icon` | an `ItemStack` through the item pipeline | the thing has an item a player recognises |
| `Block` | a `BlockState`'s own model | the machine's model says more than its item does |
| `Model` | any baked model by id, fitted and turning | **the thing has no item at all** |
| `Bar` | a filled bar with an optional label | a fraction: health, charge, travel |
| `Row` / `Column` | children side by side or stacked | laying two of the above out together |
| `Custom` | a lambda, into a box you size | none of the above |

`Sprite` states its source rectangle *and* the source image's size, so it scales into the box. Blitting
without saying how big the source is crops instead, which is the trap that signature exists to close.

`Model` needs its model declared at client setup (`ProbeModels.declare(id)`), because a standalone
model has to be asked for before the game bakes it. Bounds are measured off the baked quads the first
time it draws, which is what lets an arbitrary mesh be fitted into a box without the caller knowing how
big it is.

## 40. Writing a provider

Providers **append**; nothing replaces. A door's provider says what the door is doing, another could add
what its lock is worth, and neither has to know the other exists. Priority orders the panel.

```java
ProbeRegistry.addBlock(MyBlocks.REACTOR.get(), (info, ctx, state, pos, be) -> {
    if (!(be instanceof ReactorBlockEntity reactor)) return;
    info.title(state.getBlock().getName());
    info.bar(reactor.heat() / reactor.maxHeat(), 90, 0xFFCC3333,
             Component.literal(reactor.heat() + " K"));
    info.add(ProbeElement.Model.spinning(MyModels.CORE, 40));
});
```

Three registration shapes, and a predicate rather than a block is the general one:

- `addBlock(block, provider)` / `addEntity(type, provider)`: one thing.
- `addBlocks(predicate, provider, priority)` / `addEntities(...)`: "every door", "anything with a
  buffer".
- `addAnyBlock(provider, priority)`: everything. What the built-in name-and-icon provider uses, at
  `ProbeRegistry.DEFAULT_PRIORITY`; that priority is how the `showEverything` setting can mean "only
  things something has more to say about" without any other provider changing.

### Server data

Most of a probe needs none. A block state, a block entity's synced fields and an entity's tracked data
are all on the client already, and reading them there costs no packets. Register a
`ProbeDataProvider` only for something the server keeps to itself:

```java
ProbeRegistry.addBlockData(state -> state.getBlock() instanceof DoorBlock,
        (data, player, level, pos, state, be) -> data.putInt("Powered", poweredBlocks(level, pos)), 0);
```

It is fetched **only while a player is looking at the thing**, at `refreshTicks`, and the server answers
a given player at most once every 100 ms and only within 64 blocks, so a modified client cannot turn
the probe into a query firehose. The answer arrives in `ctx.data()`, empty until it does.

One trap worth naming, because it fails silently and was in the first version of this: a "never asked"
sentinel cannot be a tick number. `Long.MIN_VALUE` overflows `gameTime - requestedAt` into a negative,
which reads as "asked a moment ago", and the probe never asks at all.

## 41. The action list

Under the panel (**outside the box, over the world**) is a list of what you can **do** to the target,
read the way an interaction list reads in Arma Reforger: the chosen action sits at the top of the stack
full size and full strength, and the ones behind it fall away, each a little smaller and a little fainter
than the last, the furthest one half transparent.

That ramp is the whole interface. It says which row a key press will run without a cursor or a
highlight, and it says how many others are waiting without a scrollbar.

The box wraps what the probe has to *say*; the actions are a menu, and a second box around them reads as
a second panel. That is what the outlined text is for; see below.

| | |
| --- | --- |
| Scroll | hold **left alt** (rebindable) and use the wheel. The hotbar is left alone while it is held. |
| Run | **G** |
| Rows | five by default, `actionRows` in the config; five is also the ramp's length |

**The list wraps.** Scrolling rotates it rather than sliding a window over it, so there is never a row
above the selected one that scrolling cannot reach, which is why the panel needs no scrollbar and no
"more above" marker. Nine actions and five rows still puts every one of them four scrolls away at most.

**The selection is an identity, not an index.** The panel is rebuilt every frame, so remembering "row 3"
would mean something different a moment later. `ProbeSelection` remembers *which action* was chosen and
re-finds it in each new list; one that stops being offered falls back to the first row rather than
silently becoming a different action. Choosing "Skin 4" and pressing G demonstrates both halves: the
door changes skin, that row stops being offered because it is now the current one, and the selection
lands back on the first.

### Offering one

```java
info.action(ProbeAction.of(MY_ACTION, Component.translatable("action.mymod.vent"))
        .withIcon(new ProbeElement.Icon(new ItemStack(Items.LEVER), 10, false)));

// elsewhere, on both sides, once at setup:
ProbeActions.registerBlock(MY_ACTION, (player, level, pos, state, arg) -> vent(level, pos, player));
```

`arg` is a number handed back to the handler, so one id covers a family (which skin, which side, which
channel), rather than needing an id each. A row that cannot be performed right now is offered
`unavailable(reason)` and drawn greyed with the reason after it: a row that is simply missing teaches
nothing, and "Fit padlock - no cut padlock" is the line that tells a player what to go and get.

**Offering an action is not permission to perform it.** The client sends an id, a target and nothing
else, so every handler re-decides whether it is allowed from the same conditions its provider used. The
door handlers all still demand the key, which is what keeps the list a front end for what a key already
does rather than a way around the rule that a door has no bare-handed path. The packet layer adds only
what it can decide generically: an eight-block reach and one action per player per 100 ms.

### Carried, not held

Item gates read the inventory: `ctx.carrying(item | tag)`, `ctx.find(predicate)`; server side `ProbeInventory.find/count/take`. Order: main hand, off hand, rest. Doors: key and cut padlock count from anywhere. `ctx.holding` stays for looks (an outline while in hand), not for permission.

### Timed actions

```java
ProbeActions.registerTimedEntity(ID, new ProbeActions.TimedEntity() {
    public int ticks(ServerPlayer p, Entity e, int arg) { return canStart ? 200 : 0; } // 0 = refuse
    public boolean holds(ServerPlayer p, Entity e, int arg) { return stillValid; }   // every tick
    public boolean perform(ServerPlayer p, Entity e, int arg) { /* at the end */ }
});
info.action(ProbeAction.of(ID, arg, label).timed(200)); // row shows "(10s)"; server's ticks() decides
```

- One job per player (`ProbeJobs`); progress bar under the crosshair, reason on stop.
- Stops: player hurt, target out of reach, target gone, `holds` false, G again, `ProbeJobs.cancelOn(target, reason)` (owner's call, e.g. when it is shot); horizontal drift > 0.75: block job = player's walk, entity job = player-target offset (both on one moving deck => no stop), blamed on whichever travelled further.
- `perform` runs after the job is removed: it may start the next one.
- `ProbeActions.performEntity(player, entity, id, arg)`: same path without a packet (commands, tests); `ProbeJobs.tickAll()` steps jobs by hand.
- `ProbeAction.noted(remark)`: enabled row with a remark (a cost).

### A note on the modifier

**Read GLFW, not the key mapping.** `KeyMapping.isDown()` does not work for a bind whose key *is* a
modifier: pressing alt makes alt the active modifier, and NeoForge's lookup then stops matching a
mapping registered with no modifier, so a binding to left alt never reports itself as down. Alt+scroll
did nothing until `ProbeInput.scrolling()` started asking
`InputConstants.isKeyDown(window, key.getValue())` instead.

A modifier is also two switches under one name, so the bind is read on **both** sides: bound to left
alt, right alt works too. Nobody rebinds left alt meaning "and not right alt".

`-PprobeTrace` logs every scroll the probe sees, what it made of the modifier and how many actions were
in the list. A modifier that is not arriving is otherwise indistinguishable from a handler that is not
running, and neither leaves a trace.

### Outlined text, and the colour trap in it

Half of what the probe draws is **outside the panel**, over whatever the player happens to be looking at
-- snow, a white wall, the sky. A drop shadow only works against a background you control: it is one
side of one glyph, and over a bright surface it disappears. `ProbeText` gives every glyph a border
instead, by drawing the string eight times at ±1 offsets in black and once on top in its own colour. The
border takes the text's *own alpha*, so a row fading out of the action list takes its border with it
rather than leaving a black ghost behind.

**The trap, and it is a silent one: the colour you pass to `drawString` is only a default.**
`Font.StringRenderOutput#accept` reads `style.getColor()` first and falls back to the argument only when
the style has no colour of its own; the argument's *alpha* is used either way. So a `Component` carrying
`ChatFormatting.RED` drawn with black comes out **red**, and every one of those eight border passes drew
in the text's own colour. A probe row is a grey label and a coloured value, so nearly every row was
affected, and each came out as a **solid blob of its own colour** with the glyphs lost inside it: a 5×7
glyph dilated by one pixel in eight directions is a 7×9 filled rectangle.

The fix is to strip the colour out of every style for the border passes only, keeping bold and italic so
the border is still the shape of the glyph behind it:

```java
sink -> text.accept((i, style, cp) -> sink.accept(i, style.withColor((TextColor) null), cp))
```

Worth recognising by sight, because it does not look like a bug in your own five lines; it looks like a
broken font or a bad atlas. The tells are that the shapes are *dilated* rather than wrong (a slash is
still a slash, just fat) and that only **coloured** text is affected.

## 42. What is verified

Run in the dev client:

- the panel on a **door** (title, mod name, live state, skin index), and on a **missile dispenser**,
  where the loaded airframe draws as its actual obj mesh, turning, next to its warhead and target;
- both icon paths, the model path, text, rows and the panel chrome;
- the always-on fallback naming any block and its mod;
- the **server data channel** end to end: a door's powered-block count, which is not synced, arriving
  over the wire and drawing next to an `AtlasSprite` of redstone.

That last one is what caught the sentinel overflow above: the panel was simply missing a line, and
nothing anywhere said why.

The action list, on a vault door with a key in hand (eight actions, five rows:

- the ramp itself: the selected row large and opaque, the rest smaller and fainter down to the fifth;
- **left alt + scroll** rotates the list and leaves the hotbar alone, which is the whole reason the
  modifier exists) confirmed both by the rotation on screen and by `-PprobeTrace` reporting
  `scrolling=true lalt=true` for the alt case and `scrolling=false` for a bare scroll;
- the activate key runs the selected row, including its `arg`, picking "Skin 4" puts the door on skin
  4, server-side, and the world model changes with it;
- a disabled row refuses, silently and correctly;
- the selection falls back to the first row when the action it was on stops being offered.

The entity side and entity actions were covered later, on a mine dispenser (2026-09-14): the panel's
state, finish, canister count and arc lines; the *Arm*, *Load a canister* and *Pack up* rows offered,
greyed with their reasons and then becoming available as the rack's load changed under them; and **G**
running the selected one. That run is also what found the border-colour trap above: every row on it was
a solid blob of its own colour, which had been shipping for as long as the outline had.

Not looked at: `Sprite`, `Bar` and `Custom` in a live panel.

# The Model Gallery

## 43. What it is

`/wfmodels` opens a viewer onto **every model this mod has**, out of every registry that holds one. A
tab per registry, a page of squares at a time, arrow keys or a click to fill the screen with one and turn
it over.

| tab | where they come from | drawn as |
| --- | --- | --- |
| missiles | `MissileModels` | baked vanilla model, placed by `MissileItemRenderer` |
| mines | `MineModels` / `MineRigs` | imported glTF |
| drones | `DroneModels` / `PartRigs` | imported glTF |
| pieces | `DroneModels.pieces` | one node of an airframe, isolated |
| doors | `DoorType` x skins / `DoorRigs` | glTF assembled at load time |
| glyphids | `GlyphidModel` | glTF, castes as sheet variants |
| props | `ModModels` | baked: the billboards, the bones, the crate, the bomblet, the missile props |
| probe | `ProbeModels` | baked, standalone models a probe panel draws |

A tab with nothing in it is not shown, so `probe` appears only once something declares a model there.

It used to show the missiles and nothing else, which meant every model added since (nine mines, two
airframes, twenty-seven pieces of them, thirty-three doors) could only be looked at by spawning one.

## 44. How it is put together

`ModelGallery` is the catalogue and the only place that knows where models live. It hands the screen a
list of `GalleryEntry`: a name, some figures, whatever clips and finishes the thing has, and a way to be
drawn into a square. Adding a registry is a method there; the screen gains nothing to change.

Two implementations cover every registry:

- **`BakedEntry`**: the vanilla path. The gallery has the quads here, which is why **normals mode**
  exists at all: a face that came back from a modelling tool wound the wrong way is invisible in a
  textured render and unmissable once every surface is coloured by its own normal.
- **`GltfEntry`**: GemRender's **direct** path, the one that puts a mine in an inventory slot. Flywheel
  draws the level and a screen is not the level, so instancing is not available; the direct path takes a
  model, a clip, a clip time and a matrix, and draws a whole page in a handful of draws.

Both are flushed by the screen itself, **with the depth test on**. They are not left for the GUI's own
flush at the end of the frame, because `GuiGraphics.flush` turns depth off, and a solid model drawn
without depth is its own back faces.

**Fitting.** A cell wants every model the same size on screen, and these run from a hand-sized
anti-personnel mine to a four-block vault door. Where a registry has already measured its models the
gallery uses its figures, because those are the numbers the game places the thing by and a viewer that
disagreed with them would hide exactly the bug worth seeing. Where it has not (doors, glyphids) the
box is computed once from the model's own skinned bounds and kept, so scrubbing a clip cannot zoom the
camera as it runs.

One case cannot be measured that way. A **piece** is the whole airframe with every other branch collapsed
to a point, and a collapsed branch still leaves a point where its node sat, so the box comes back the size
of the aircraft and the piece draws as a speck. Those read their own subtree out of `GltfBounds` instead.

## 45. Driving it

| | |
| --- | --- |
| tab, or click a tab | change registry |
| arrows | walk the grid; **scrub the clip** when one model fills the screen |
| enter, or click | inspect |
| shift + arrows | turn the inspected model, which is what a mouse drag does |
| R | stop or restart the turntable |
| drag / scroll | rotate / zoom |
| N / C | normals mode / backface culling: both only mean anything on the baked path |
| A / V | next clip / next finish |
| P | hold the clip where it is, so the arrows can scrub it by hand |

Clips are listed as chips along the bottom, and the first is always the model **held at its first frame**:
a rack laid rather than firing, a door shut, a piece intact. So the grid is still, and a clip runs only
when one is asked for.

## 46. The blurred overlay, and why it was there

Since 1.21, `Screen.render` calls `renderBackground` **itself**, and `renderBackground` runs a blur pass
over the whole render target before painting the menu texture across it.
`AbstractContainerScreen.render` does the same.

So the shape that looks right and was right on 1.20:

```java
public void render(...) {
    this.renderBackground(...);   // blur pass 1
    ...draw my panel and my models...
    super.render(...);            // blur pass 2, then the menu texture, over everything just drawn
}
```

-- blurs and then dims **the screen's own content**. It does not read as a bug; it reads as a soft
texture or a bad font, which is why four screens carried it. The correct shape is `super.render` first
and the content on top of it, with `renderBackground` never called by hand; a screen that wants a panel
*under* its widgets overrides `renderBackground` and draws it there, which is what `DroneProgramScreen`
now does.

## 47. What is verified

Run in the dev client, and the reason the arrow keys exist: this compositor does not route synthetic
clicks into the game, so a gallery only reachable by mouse is a gallery that cannot be checked. The
turntable and the clip are separately stoppable for the same reason it matters to a person, looking at
one frame of a clip is something you do from more than one side.

- every tab draws: 36 missiles, 9 mines, 2 drones, 27 pieces, 33 doors, 2 glyphids, 8 props: both
  render paths on screen at once, sharp, over a blurred world;
- the inspector: figures, clip chips, the turntable, and a scrub that holds;
- a **dispenser rack scrubbed from `laid` to `firing`**: six capped tubes, then three open and three
  capped at half, then six open empty tubes with the rack whole, on both the AP and the AT rack. That is
  the visual half of the emptying work, which had only ever been asserted server-side;
- a **vault door scrubbed open** pulls its plug and rolls it aside;
- the **Amazog** with its bin, its gear and all eight rotors.

Two defects the gallery found the first time it was opened, both fixed:

- the **bomblet drew as a missing texture**. `ModModels.BOMBLET` is a baked model instanced into a
  drone's own draw, so its sprite has to be on the block atlas, and `entity/` is not an atlas
  directory, so it never was. A dropped bomblet looked right the whole time, because `BombletRenderer`
  binds the png itself. Fixed by naming the sprite in `assets/minecraft/atlases/blocks.json`.
- **a clip asked for at exactly its own duration wrapped back to its first frame.** GemRender's direct
  path loops the time it is handed, which is right for a rotor and wrong for a rack scrubbed to a stop:
  "everything fired" drew a full rack. Not visible in the world (the level path quantises without
  wrapping), so the viewer is what has to ask for a frame that exists, and `GalleryEntry.Clip` stops a
  whisker short of the end of a ranged clip.

Not looked at: the glyphid caste finishes (nothing has drawn a second one), and mine camouflage, which
has no artwork shipped for it yet; the gallery says so, reporting one variant per mine.

---

# Explosion debris

## 48. What it is

The chunks of ground an explosion throws out: real block geometry, sampled from the terrain the blast
went off in. `WorldInAJar` cuts a sparse cube of block states out of the level around the crater;
`JarModels` turns that into a Flywheel model; `InstancedDebrisEffect` throws a burst of them through
GemRender's particle instancer.

The thing that used to be impossible here is landing. A GemRender particle has no state to read, so it
could not discover the ground on its way down, which is why the hand-ticked `Debris` existed, raycasting
every chunk every tick and drawing each one through a `VertexBuffer` of its own. That path is still in
the tree as the fallback (`DebrisManager`, used when the Flywheel backend is off) and it is what the
old costs were: a 256-chunk cap, a four-bakes-a-frame budget, a GL buffer per chunk.

Contact is now **predicted at spawn**. The arc is a closed form, so it is swept against the world once
when the burst is created, and each chunk carries the age at which it lands. See GemRender's
`docs/INTEGRATION.md`, section 7, for the library half.

## 49. How a blast is put together

| | |
|---|---|
| `WorldInAJar` | a cube of block states cut from the level; already a `BlockAndTintGetter` |
| `JarModels.bake` | that cube as a Flywheel model, centred, plus the radius it rests on |
| `WFParticleStyles.debris()` | gravity 0.15/tick, no drag, `stopsOnContact()` |
| `InstancedDebrisEffect` | one jar, one emitter, one pool: **one draw call** |
| `debris.vert` | moves and tumbles the chunk, and leaves its colour and light alone |

**One effect draws one jar.** A pool draws a single model however many particles are in it, so a blast
cuts its debris from `DEBRIS_JARS` (four) samples rather than one per chunk: four draws for the whole
explosion instead of one per chunk, and nobody can tell that two chunks taken from the same square metre
of ground are the same chunk. `INSTANCED_DEBRIS_DENSITY` then throws three times what the fallback would,
because a chunk no longer costs a raycast a tick.

**`debris.vert` exists to not do things.** GemRender's stock mesh particle points the model along its
velocity and overwrites `flw_vertexColor` and `flw_vertexLight` with the style's. That is right for a
spark, whose colour *is* the particle, and wrong for a piece of terrain: the block bake has already put
the face shading, the biome tint and the jar's baked sky light in those channels, and overwriting them
gives you flat-coloured rubble lit the same at midnight as at noon. The custom shader also tumbles on two
axes instead of rolling about the flight direction, using `spinPhase` and `tintScale` as the two phases:
a chunk of terrain takes its colour from its blocks, so the tint float is spare.

**How far it rests from the surface** is the RMS spread of the jar's blocks about its own middle, not
half its bounding box. A jar grows outward from a 2x2x2 core and thins as it goes, so for the 16-block
sample a large blast takes, the box is most of the sample volume while nearly all the blocks are near the
centre. Half the box would stop a chunk eight blocks up, in mid-air.

## 50. What is verified

In the dev client, against real terrain (the platform in the first attempt was too small, at 60 blocks
a second the debris lands sixty blocks out, off the edge and out of frame, which reads exactly like
"collision does not work"):

- debris **lands and stays**: two frames a second apart differ by 0.36% of their pixels, all of it the
  smoke plume;
- it lands **on the surface**, scattered around the blast at the expected radius, and close up the chunks
  are grey stone with correctly shaded faces; the block bake's colour and light survived;
- **49 simultaneous blasts, about 1 500 colliding mesh particles: 163 fps against a 169 fps empty-scene
  baseline**, GPU 8%, on `flywheel:indirect`. That frame is the expensive one (it baked 196 models and
  swept 1 500 arcs), and every frame after it is free.

The sweep itself is covered headlessly by GemRender's `ParticleSweepScaleTest` (1 000 spawns in 1.22 ms)
and the agreement between the Java prediction and the GLSL that draws it by `ParticleContactGlTest`,
which runs the shipped `particle.glsl` on a real GPU and compares it against `ParticleCollision`.

# Part XVI: Everything else that was a particle

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

## 53. What is verified

In the dev client on a 21x21 stone stage, `flywheel:indirect`, 3440x1440, **170-175 fps** through all of
it and GPU load 8-14%:

- **block chunks** fly out of the crater as tumbling stone cubes and vanish where they land: 120 spawned
  per block type, 115 of them predicted to hit something, which is what a chunk thrown nearly
  horizontally out of a crater should do;
- **shrapnel** chips fly, land and stay put on the stage, textured from the block atlas;
- **ash** falls for a second or two and settles flat on the ground, and is still lying there six seconds
  later;
- **the fireball** is an orange additive cluster at the blast;
- **plumes** hang around the crater instead of leaving it.

Two defects the screenshots caught that nothing else would have:

- **Plumes flew away and never came back.** `SmokePlumeParticle` gets vanilla's `friction`, which is
  applied on all three axes; the converted style had horizontal drag only, so its faint upward buoyancy
  had nothing to balance it and the smoke accelerated into the sky for its whole fifteen seconds.
- **One `InstanceType` per burst instead of one per kind.** Flywheel keys its instancers on the
  `InstanceType` *object*, so building a fresh one inside the factory gave every explosion its own
  instancer, which is exactly the sharing that instancing exists for. They are `static final` now.

And one in GemRender itself, which is the reason ash was landing in mid-air: see the sweep note in
`INTEGRATION.md`. A sixty-second flight was being cut into chords tens of blocks long, and a chord that
long is not crossed at anything like a constant speed, so the hit fraction along it mapped to a small
fraction of the age it should have. Segments are sized from the local curvature now.

# Part XVII: Smoke over water

## 54. The defect

An explosion over a lake cut a blast-shaped hole in the lake. Not a dark patch or a misordering: the
water was **gone** where the smoke was, in hard rectangles, and through the gap you saw the lake bed.
The missile exhaust did the same in miniature, one pale square per puff, scattered across the sky.

It is not a particle bug. Flywheel draws its instances after entities and **before** vanilla's
translucent terrain, so every one of these particles is drawn before the water it is standing in front
of. Blended geometry that writes depth in that position deletes whatever translucent surface is behind
it, because the water pass is then rejected against a depth the particle had no business writing. And
the hole has the shape of the *quads*, not of the sprite, because `CutoutShaders.EPSILON` only discards
a texel that is entirely transparent: every faintly-visible texel of a soft round puff writes a fully
opaque depth value.

The smoke families were `additive`, which is where the two halves of the problem meet. Additive is
`ONE, ONE`: a fragment can only add light, so it has no business occluding anything, and the depth write
was simply a mistake. But taking it away does not make additive *correct*: it makes the water paint
over the smoke instead, and a thick surface paints it out of the frame entirely. **Additive has no right
answer against water.** Either it erases the water or the water erases it.

## 55. What changed

`ParticleModels` now derives the write mask from the transparency: depth for `OPAQUE`, colour only for
everything blended. That ends the erasure for every additive effect in the mod, the lingering flame and
the blast fireball included.

Ordering, though, needs a blend that can be ordered, and GemRender has exactly one: `ORDER_INDEPENDENT`,
which `WaterSplit` cuts at the water surface and composites in two halves, each on its own side of it.
So the two families that are *smoke* moved onto it:

| effect | was | is |
|---|---|---|
| missile exhaust (`trail`) | `additive` on the 8x8 dither mask | `translucent` on `mist_soft` |
| explosion cloud (`cloud`) | `additive` on the 8x8 dither mask | `translucent` on `mist_soft` |

The flame and the small-blast puff stay additive. They are fire rather than smoke, they are brief, and
they are usually at the player's own feet: a misordering against a surface that is rarely there costs
nothing, and now that they write no depth it is a misordering rather than a hole.

**The sprite changed with the blend, and had to.** The old one is an 8x8 binary dither mask: 45% of its
texels fully opaque, the rest fully transparent, with nothing in between. Added, that reads as sparks.
Composited, it reads as gravel, which is the same finding `mist` recorded when it moved to the soft
radial sprite, and the reason that sprite exists.

## 56. The numbers had to move too, and it is not a matter of taste

This is the part that is easy to get wrong, because nothing about it is visible in the style.

**Additive blending never reads the alpha.** `ONE, ONE` adds `src.rgb` and that is all; `alphaScale` and
`alphaFalloff` were dead fields in every additive style in this file. What made an additive puff go out
was `cool`, running the *colour* down toward black, because under addition, black is nothing.

Composited, black is not nothing. It is black smoke, and it is fully present for the rest of the
particle's life. Converting a style therefore means moving the fade out of `cool` and into the alpha,
and leaving `cool` doing only what its name says:

| style | was (additive) | is (composited) |
|---|---|---|
| `trail` | `alpha(0.75, 0.4)` unused, `cool(0.1, 0.6)` | `alpha(0.45, 1.4)`, `cool(0.2, 0.45)` |
| `cloud` | `alpha(0.75, 0.5)` unused, `cool(0.1, 0.25)` | `alpha(0.30, 1.6)`, `cool(0.14, 0.3)` |

The alphas are far below the numbers they replace, and that is the second half of the same point. A
blast is hundreds of quads that grow to five times its radius and lie on top of one another; under
addition that density bought brightness, and under compositing it buys opacity. At the old 0.75 the near
layer alone would black out the screen.

## 57. What is verified

A lake at y=180, 79x79 with a stone rim at its own level so it is still, a spectator camera 34 blocks
back and 8 up, and `/wflib swarm 4` fired across it. The same camera, the same command, the same
1920x1080 framebuffer, before and after:

- **before**: the lake is *gone*. The whole surface, out to its far edge, replaced by the stone bed at
  y=179 tinted yellow by the puffs, with one triangle of water surviving at the left edge. The exhaust
  trails are pale blue rectangles scattered across the sky, each one a quad-shaped hole;
- **after**: the lake is intact in every frame of the burst. The smoke darkens the water it lies over
  and thins out at its edges, its own far edge silhouette unbroken, and a trail crossing the water's
  edge is drawn continuously on both sides of it.

Run under **both** transparency implementations, because they are two implementations and not two
qualities: Fancy, where `waterSplit=active` does the interleaving, and Fabulous, where `waterSplit=idle`
and vanilla's own transparency chain sorts the targets. Water intact in both.

The library half was A/B'd on its own in GemRender's spike, which stages a particle fountain against a
wall of water and can swap only the blend: `-Pparticles=3000 -PwaterColumn=3 -PparticlePlacement=front`
with `-PparticleBlend=additive` against `-PparticleBlend=translucent`. Additive takes a plume-shaped bite
out of the wall; translucent leaves it whole.

**One loose end, found on the way and not touched.** The `instanced_smoke` effect key has no sender
anywhere in the mod. `explosion_small` dispatches to the vanilla-particle path unconditionally, where
`explosion_large` branches on whether Flywheel is available, so `InstancedParticleEffect`,
`InstancedParticleVisual` and the `puff` style are reachable only as a fallback. Either the small blast
should branch the way the large one does, or those three are dead weight; both are behaviour changes
rather than fixes, so they are recorded here rather than made.

# Part XVIII: The exhaust plume

## 58. Why the stock shader cannot draw one

A solid rocket motor's trail is **white**. The propellant is aluminised and it burns to aluminium oxide,
which is a white powder: that is what the trail physically is, and it is why a launch leaves a white
column standing in the sky for a minute. The plume runs incandescent for a few tenths of a second at the
nozzle, goes dense white almost at once, and greys as it thins.

GemRender's stock particle shader can express none of that, and the reason is one field.
`cool(floor, span)` scales the tint toward **black**, because the shader was written for additive
blending where black is the same as nothing. That ramp draws a fire. It cannot draw smoke, because smoke
that is fading is not smoke that is getting darker.

So the exhaust gets its own pair, `wflib:instance/exhaust.vert` and a matching cull shader, and
two style fields are reinterpreted the way {@code ash} and {@code flame} already reinterpret theirs:

| field | stock meaning | here |
|---|---|---|
| `cool(floor, span)` | brightness floor, and the fraction of life to reach it | **seconds** of flame, and **seconds** to become smoke |
| `size(atBirth, growth)` | read against `unitAge` | read against `sqrt(unitAge)` |

The durations are absolute rather than fractions of life because the glow is a property of the *gas* (
a fixed few tenths of a second behind the nozzle), and has nothing to do with how long the smoke that
follows will hang around. And the square root is turbulent diffusion: a plume does nearly all of its
flaring in the first moments and is close to a cylinder after that. Linear growth gives a cone still
opening a hundred blocks back, which no rocket trail does.

`light` rides the same ramp, interpolated in the shader alongside the colour: burning gas lights itself
and smoke does not. Sky light rather than a block sample, because a missile trail is by definition in the
open air, which also makes it go dark at night while the flame at its head does not.

## 59. Density is a spacing, not an alpha

The old trail laid puffs 0.25 blocks apart and made them a third of a block across. That is beads on a
string, and it looked like it. A puff is 1.6 blocks across now, which at the same spacing puts three or
four layers along any line of sight through the tube, and *that* is what makes it read as a column of
smoke. Lifetime went from under three seconds to 7.4, which at a missile's twenty blocks a second is a
column a hundred and fifty blocks long.

Lifetime and size are also drawn **per puff** now rather than once for the whole tick's run, and the
spread is much smaller: a puff's radius is a function of how far through its life it is, so neighbours
with lifetimes differing by two to one are visibly different sizes at the same moment, and the trail came
out as a chain of sections rather than one object.

## 60. What was wrong in a turn

The trail followed a Catmull-Rom spline through the last three emit points, with a fourth control point
extrapolated straight ahead of the missile. Work out what that extrapolation does to the tangent and the
bug falls out: `P3 = P2 + (P2 - P1)` makes the end tangent `(P3 - P1) / 2 = P2 - P1`, the chord itself,
while the *next* tick's segment starts on `(P2' - P0') / 2`, a tangent averaged over two chords. **Those
are different vectors, so the two segments met at a corner.**

On a straight run they agree and nothing shows. In a turn they do not, and the trail came out as a chain
of arcs hinged at every tick: scalloped, with a visible lump per tick, and with the last puff not lying
on the line the missile was actually flying along.

The fix is to stop re-deriving the tangent and start **carrying it**. Each segment is a cubic Hermite
that leaves the previous point along the heading the previous segment arrived on, and reaches the current
point along this one's. Continuity is then a property of the construction rather than a coincidence of
the arithmetic. The arrival heading is the per-tick position delta, which is *exactly* what
`MissileVisual` builds the model's orientation from, so the last puff is on the missile's axis by
definition, and the plume comes out of the nozzle rather than out of the side of it.

Each puff's jet is taken from the local direction along that curve, not from the segment chord. In a hard
turn those differ by the whole turn angle.

## 61. What was wrong at speed

Three things, and they compound.

**The jet scaled with airspeed and nothing capped it.** Exhaust leaves a nozzle at a velocity the motor
decides, and the air stops it in a distance the air decides; neither gets larger because the airframe in
front is going faster. Taking 40% of the missile's speed meant a hypersonic missile at eighteen blocks a
tick threw every puff backwards at 144 blocks a second, and against this drag that is **forty-eight
blocks of run-back** before it stops. The whole trail slid down its own length and tore away from the
nozzle. Capped at 0.6 blocks a tick, which is about four blocks of flare at any speed, and which never
binds below 1.5 blocks a tick.

**The velocity came from `getDeltaMovement`.** A missile does nothing at all client-side but sit where
the packets put it, so its delta movement is whatever the last motion packet happened to say. The two
points the trail is being drawn between are the only speed it can be sure of, so that is what it uses now.

**The puff count was capped and the spacing stretched to fit.** This is the same trap the contact sweep
fell into: ask for a hundred and forty puffs across a thirty-six block jump, get ninety-six at 0.375
blocks apart, and a tube that was three layers deep is two. The cap stays (it is a bound on the per-tick
spawn cost, which is worth having), but the puffs now grow in exact proportion to how far the spacing had
to stretch, so the plume gets *coarser* as the missile gets faster rather than *thinner*.

One more, smaller: the trail is emitted from `xOld` rather than the current position. An entity is drawn
interpolated from `xo` to `x` across the tick, so emitting to `x` put the head of the plume where the
missile would be at the *end* of the tick, sticking out in front of the nozzle by a whole tick of
travel, snapping back twenty times a second. At a block a tick that is invisible. At eighteen it is a
strobe.

## 62. What is verified

- **The turn.** Matched pair, same swarm, same camera, zoomed into the launch loop: before, the arc is a
  chain of discrete lumps with a corner between each; after, one continuous band of even width.
- **Cost.** Twelve missiles with the camera inside the plumes and smoke filling the whole frame:
  **175-177 fps against a 177 fps empty-scene baseline, GPU 6% against 6%**, `flywheel:indirect`, Fancy,
  1920x1080. The frame rate was pinned at the 180 cap in both, so the number that carries the weight is
  the GPU load, which moved by at most one point. Eight trails at a normal viewing distance: identical.
- **Density and colour**, from the side at a hundred blocks: a bright cream core at the nozzle, a white
  column widening behind it, greying and spreading with age, following the missile through its turns.

# Part XIX: Torpedoes

## 63. How different a torpedo is from a missile, in practice

Less than it looks, and the reason is that `MissileEntity` had no concept of water at all. Four mentions
of it in three thousand lines, three of them about fuel; the fourth is `SweptCollision`'s
`ClipContext.Fluid.NONE`, which is what makes a missile fly clean through a lake without noticing. Nothing
had to be undone, only added.

Everything expensive is medium-agnostic already. `FlightStage` is a stateless strategy returning a desired
velocity; pure pursuit, turn-rate limiting, the Dubins pitch-over and the lead computation do not know
which way is up. `ASCEND → CRUISE → ATTACK` maps onto transit → run → sprint without strain. Fuel, health,
`DownedAction`, telemetry, chunk loading, swarm and formation, and the whole preset → item → launcher
pipeline are reused untouched.

Four things genuinely differ, and they are all in one place.

**The terrain reference inverts, and gains a lid.** `scanTerrainTop` used
`Heightmap.Types.MOTION_BLOCKING_NO_LEAVES`, whose predicate counts fluid, over ocean it returns the wave
tops, which is right for a sea-skimmer and exactly backwards for a torpedo. `OCEAN_FLOOR` counts only
blocks that stop motion and so returns the seabed under them. And a torpedo needs a bound the missile has
no slot for: `FlightContext.safeAltitude` was one scalar, and a submerged run needs a corridor, whose
height in most Minecraft ocean is about twenty-five blocks.

**Every block-denominated constant is out by around four.** The slowest missile preset is
`cruiseSpeed(1.0)`: twenty blocks a second. A real torpedo runs at roughly a tenth of a subsonic cruise
missile, which in blocks a tick is slower than a swimming player, so these sit between the honest ratio
and something worth watching. `BRAKING_RANGE` 30, `lookAhead` 32, `terrainScanRadius` 24,
`DAMPENING_RANGE` 50, `ALTITUDE_DEADBAND` 3 are sky figures. In a twenty-five block corridor a three-block
deadband is a tenth of the whole corridor and a fifty-block proportional range is a control that never
leaves its linear region.

**Broaching is a failure mode nothing modelled.** A missile leaving its medium is meaningless.

**The effects and the warhead are different weapons.** A rocket plume is not a wake, and a charge in water
is not a charge in air.

## 64. Medium, not a boolean

`MissileEntity.Medium` carries the heightmap, the look-ahead and the fan radius, because those three are
the same decision. `AIR` scans 32 ahead over a 24 fan on `MOTION_BLOCKING_NO_LEAVES`; `WATER` scans 12
ahead over an 8 fan on `OCEAN_FLOOR`. The distances differ by more than taste: they are measured in blocks
while the control lags answering them are measured in ticks, so the warning they buy is distance over
speed. Thirty-two blocks is three ticks to a hypersonic missile and two hundred to a torpedo, and at the
air figures a torpedo would spend its whole run steering around seabed it will not reach for ten seconds.

The two cruise modes keep their meaning, reflected into the new medium. `terrainFollow(n)` is a
bottom-follower holding `n` over the seabed; `highAltitude(n)` is a fixed running depth `n` below the
surface. Both are then clamped into the corridor between the seabed clearance and
`surface − SURFACE_MARGIN`. Where the corridor has closed (shallow water, where the clearance is already
above the margin) `FlightContext.clampToCorridor` takes the midpoint: both bounds are violated, so it
sits equally far from each rather than committing to one.

`terrainClearance` had to be separable from the altitude parameter to make any of this work.
`MissilePreset` folded the two into one `altitudeParam`, so a fixed-depth torpedo would have carried the
air default of 24 blocks of seabed clearance: deeper than most of the ocean it runs in, which commands it
above the surface for the whole of a shallow run. `Builder.clearance(double)` sets it on its own, and is
applied *after* the mode, because `terrainFollow()` writes the clearance itself and would otherwise
silently discard it.

## 65. A torpedo cannot climb a bank

This one was found by a test rather than by reasoning, which is the only reason it is in here.

The seabed fan takes the **max** over its samples, because for an aircraft a ridge beside the track is
something you must climb over. Underwater that same max is taken over columns that may have no water in
them at all, and a bank is not something a torpedo climbs over, it is something it hits. Left in, the
bank sets the floor, the floor rises above the lid, the corridor collapses, and the torpedo is commanded
to the surface. Anything narrower than open sea (a canal, a trench, a river, a walled test tank) makes a
torpedo climb its own banks and broach.

So the submerged scan is cut off at the waterline: samples whose top reaches the surface are dropped from
the fan rather than raising its answer, leaving the max over the seabed that is actually under water. The
air scan passes `POSITIVE_INFINITY` for the cutoff and is unchanged, bit for bit.

The gametest found it because the tank has walls. It has walls because a tank without them drains across
the whole flat gametest world: the first version of the file failed three of the kinetic-round tests by
flooding their arenas, eight blocks away. Blocks outside your own arena are shared state.

## 66. Broaching, and why one tick out of the water is not one

`tickBroach` runs after the move, because what matters is where the tick ended. One tick in air is a
porpoise, not a broach: the climb is cut and the nose pushed back down, and (this is the part that does
the work) losing the surface also loses the fixed-depth reference, so `computeSafeAltitude` falls back to
the seabed and commands it down on its own. A torpedo lifted six blocks out of the water swims back in
within a handful of ticks. Twenty consecutive dry ticks is a write-off, because by then it is not running,
it is skipping across a lake.

`Phase.ASCEND` is exempt for its whole duration. For a torpedo that phase is the transit from the launcher
into the water, and an air-dropped one is supposed to be dry for all of it.

The entry stage is deliberately not `AscentStage`. That one climbs to `FlightContext.safeAltitude()`,
which underwater is a height above the seabed the torpedo is already above: it would fly the thing
straight out of the sea before the run started.

## 67. A wake is not smoke, and an underwater charge is not a charge

Smoke is grey particles suspended in air and it goes grey because the particles are grey. A wake is not a
substance at all: it is air entrained into water, white because bubbles scatter every wavelength equally.
So it starts at pure white (the densest it will ever be), and does not darken as it ages. It thins, the
air rises out of it, and what is left takes the colour of the water. `cool` running a tint toward black
draws neither end of that, which is the same reason the exhaust needed its own shader (ch. 58), arrived at
from the opposite direction.

`wake.vert` grows linearly rather than as a square root, so unlike the plume it borrows GemRender's stock
cull shader: bubbles hold their own boundary and merely coalesce, they do not diffuse into the water the
way smoke diffuses into air. The style's drag is split (horizontal 0.72 a tick against vertical 0.94),
because the vertical is not drag in the same sense. A bubble's rise is buoyancy fighting drag and settling
at a terminal velocity, and a vertical drag as heavy as the horizontal would hang the wake at the
torpedo's depth instead of letting it climb out, which is the one thing about a wake anybody can see from
a boat.

`InstancedTrailEffect.Kind` is where the two meet. Everything below it (the Hermite, the tangent carried
across the tick boundary, the spacing-stretch compensation, the jet cap) is about laying marks along a
path and is the same problem in both media. What differs is four numbers and a shader. The kind is chosen
from the *synced medium* rather than from where the missile is standing, because the emitter is built once
on the tick the entity spawns, and a torpedo still falling toward the sea has to already know it will want
a wake.

One thing only the dev client could have found: the wake was being laid in **air**. An air-dropped torpedo
spends its whole entry falling through the sky, and the emitter does not care what it is falling through:
the first drop test put a neat string of bubbles down through four hundred blocks of daylight. `Kind.lays`
gates it on `isSubmerged()`, and the path state is still carried across the dry ticks so the wake begins
where the torpedo went in rather than bridging a section back to whatever dropped it. The exhaust kind
answers `true` unconditionally: a motor carries its own oxidiser, and a rocket underwater still burns.

The warhead pulls in two directions at once. Water is incompressible, so the shock carries instead of
dissipating and the charge reaches much further against anything in it, hence the 2.2 reach multiplier on
the entity pass. And water does not break, so the crater is small: the block pass runs at 45% of the blast
size. `BlockAllocatorWater` is what makes any of it work, and it turned out to already exist, written and
never wired, its javadoc saying in as many words that without it *"a depth charge would either fizzle
against the water column or try to destroy flowing water and leave odd gaps."* A torpedo that beached, or
one detonated above the waterline, gets the ordinary allocator instead: the water allocator's premise is
that the medium is not a target, and on land the medium is the target.

## 68. What is verified

Eighteen gametests, all passing, of which five are new (`TorpedoGameTest`). The four that carry weight:

- **A set depth is held.** Eight blocks under a surface at a known y, in a tank of known depth, started
  two blocks under the surface so the only way to arrive is to have been driven there. Would have failed
  on the air heightmap, which counts fluid and would have put the floor at the wave tops.
- **A bottom-follower holds the bed.** Same tank, same surface, three blocks over the bed: a different
  answer from the same corridor, so the two cruise modes really are measuring from different ends rather
  than both falling back on one.
- **The run stays under the surface**, and is still in the water at the end of it.
- **A broached torpedo is written off.** Lifted forty blocks clear, not six: six is a porpoise and it
  swims back, which is the feature working. An earlier version of the test lifted it six and recorded the
  recovery as a failure.

The settle budgets are per preset, because the descent is bounded by the preset's own speed: a torpedo
can no more dive faster than it runs than a missile can. Six blocks at 0.35 a tick is nothing; nineteen at
0.25 is most of two minutes of ticks.

And a dev-client run in a hand-built tank 60 long and 22 deep, which is where the numbers were settled and
where the dry-wake bug turned up: the torpedo enters, drives down to its commanded depth, holds it, and
leaves a churn of white close behind the body thinning into a rising column of discrete bubbles. Two
tuning passes: the first version was big sparse spheres on a neat line, the second was so small and thin
it vanished at ten blocks; the shipped numbers are between them, with the density of the second and most
of the size of the first.

Not verified, and worth saying: no torpedo has been fired at a ship, because there is no ship. The target
set today is floating vehicles, swimmers, naval mines and terrain.

# Part XX: Water foam, and a blast that lasts

## 69. A charge in water has no fireball

An explosion over a lake used to draw the same orange cloud it draws over a field, because nothing in the
effect path knew where the water was. What an underwater charge actually produces is a gas bubble that
never reaches the air: what is seen is water, thrown. So `WFEffects` now looks for a surface before it
draws anything (`WaterSurface.find`, which walks a column up to its top, or a few blocks down to find one
just under a charge that fused above it), and when the charge itself was submerged the fireball is not
drawn at all. It is replaced by two bursts of foam, and the debris pass already cancels itself over water
because a jar sampled from it comes back all air.

Foam is one shader and two styles, which is the split the physics makes:

- **`foamPlume`** is spray. Droplets in air, so they arc and fall back, and the column is sized by where
  it is meant to top out rather than by a velocity: pick a peak of 2.2 blast radii and the launch speed
  falls out of `v = sqrt(2 g h)`. Sized the other way round (a velocity times some factor of the radius) a
  large blast throws its plume clean off the screen, which is what the first version did. Each droplet's
  **life is its own flight time**, `2 v / g`, so it goes out at the water rather than in mid-air and the
  slow rim does not hang around after it has already come down.
- **`foamSurge`** is the base surge and the raft it leaves: driven out across the surface, no gravity at
  all, and a life of nine to sixteen seconds. It is what is still there when everything else has gone.

## 70. Settling is what lays foam flat

`wflib:instance/foam.vert` reads `cool(floor, span)` as two durations in seconds, as `wake.vert`
and `exhaust.vert` do, and uses them for one ramp that drives three things at once: the colour runs from
white froth to the sea's own tint (bubbles scatter every wavelength, so foam does not darken, it thins),
the alpha comes down, and **the quad lies down**. A settled billboard has its world-vertical extent cut to
0.22 and its horizontal spread opened to 1.45, so spray in the air faces the camera and foam on the water
is a flat raft. That is also why foam has a cull shader of its own: the stock bounding sphere is the
particle's size, and a spread quad is larger than that, so borrowing it would clip the raft away at the
edge of the screen.

The difference between the two styles is entirely where the `cool` floor sits relative to the life. The
plume's floor is eight seconds and it never lives that long, so it stays white and upright for all of it.
The surge's is 1.6 seconds, inside its life, so it settles.

The sprite is generated, not drawn: `tools/textures/foam.py` lays ninety soft discs inside a radial
envelope, so the middle fills in and the rim breaks into individual bubbles. A plain radial falloff (what
the mist uses) reads as pale smoke at any size.

## 71. The blast cloud lasts about three times as long

Asked for directly: denser, and longer lasting. Both bursts had their life roughly tripled — the fireball
from 3.5-4.5 seconds to 11-15, the small blast's puffs from 2.25-3.75 to 7.5-12 — and their counts raised
(`INSTANCED_SMOKE_DENSITY` 5 to 9, and a new `INSTANCED_CLOUD_DENSITY` of 5 on the fireball).

Two numbers had to move with them, and neither is taste:

- **`cool`'s span is a fraction of life, so tripling the life triples the fire.** The fireball burnt out
  over 0.3 of four seconds; at thirteen seconds the same 0.3 is a four-second fire. The span is scaled
  down to 0.09 to keep the burn at its old 1.2 seconds. Same for the puff, 0.35 to 0.11.
- **The puff had vertical buoyancy and no vertical drag**, which is fine over three seconds (seven blocks
  of rise) and absurd over ten (eighty). It gets `DRAG_095` on the vertical now, which puts a terminal
  velocity on the climb: about fourteen blocks over the longer life, asymptotically, which is a smoke
  column rather than a rocket.

Alpha went up on both (0.30 to 0.46 on the fireball, 0.38 to 0.46 on the puff) and the alpha exponents
came down, so the cloud holds its opacity for more of its life instead of fading from the first frame.

## 72. What is verified

A dev-client run, Flywheel indirect backend, Fancy graphics, in a hand-built tank 47 blocks across and 10
deep at y=190-199, driven by a new `/wflib boom [small|standard|large]` that fires
`ExplosionCreator` at the command's position so a blast can be looked at without flying something into it.

- **Submerged (y=195, five blocks down).** A white column with a froth collar at its foot, no fireball,
  and a flat raft still on the water four seconds later. The foam composites correctly against the water
  from both sides, which is what `ParticleModels.translucent` buys (see Part XVII).
- **At the waterline (y=200).** Smoke column and foam together, which is the ship-hit case.
- **On land (`large`, over terrain).** The smoke column is still standing and still rising nine seconds
  after the blast, where the old one was gone in four.
- The foam vertex and cull shaders compile and link under the indirect backend
  (`wflib_instance_foam`, `culling/wflib_instance_cull_foam`).

The plume was tuned twice. The first pass was too small; the second put the fastest droplets ninety blocks
up and out of the frame, which is the bug the peak-height derivation in §69 exists to prevent.

# Part XXI: Sonar

## 73. Why the net could not see a torpedo

A torpedo is a `MissileEntity`. It is collected by `EntityTargetSource` on every sweep like any other,
handed to every sensor on the net, and until now **no band could make a plot of it**. Radar's sight line
stops at the surface. Thermal's does too, and water kills an infrared contrast outright. Seismic is the one
band that works through solid ground, and water is the worst material on its whole table: 1.14 per sixteen
blocks, against 1.005 for rock. So the mod's naval half - torpedoes, hulls, naval mines, anything swimming -
was invisible to the mod's own sensor network, and the sensible-looking readout that said "no tracks" was
right for six different reasons at once.

Sonar is the band that covers it, and it covers nothing else: it is the only one with a medium that stops at
a shoreline.

## 74. One noise, and the band decides what carries it

`Signature` already had an `acoustic` float. It had never been used, because the `ACOUSTIC` band - noise in
air - was never built. The sonar band reads **the same float**, and the field's meaning is now "how loud this
thing is" with the band deciding the medium.

That is not a shortcut. It is what makes a vehicle's engine noise mean something without anybody editing a
number: `ywzj_vehicle` has been computing an `ENGINE_ACOUSTIC` term for months behind a comment saying it was
carried faithfully so it would be correct the day the band lit up. It was. It also keeps `Signature` a
five-component record, which matters because the vehicle mod calls its canonical constructor across a mod
boundary.

The two bands are still separable where separating them is the point. A `Countermeasure` declares one band
and is consulted only for that one, so an anechoic tile is a sonar countermeasure and would do nothing to a
microphone.

## 75. Listening is a bearing, pinging is a range

A hydrophone hears a direction well and has no idea how far away anything is. That is the exact mirror of a
geophone, which knows the range to a dig at six hundred blocks about as well as one at sixty and has no
bearing at all. Both reach `CrossFix` as an error ellipse, so two hydrophones on a wide baseline cross into a
fix for precisely the reason three geophones trilaterate - and neither needed a line of new maths.

A ping changes three things at once, and the middle one is the reason to fire one:

- the law goes from `R^2` to `R^4`, so the reach shortens;
- the target term stops being *noise* and starts being *noise or hull, whichever is larger*, so a ship
  drifting with its engines stopped goes from inaudible to a solid contact;
- the ranging becomes a constant one and a half blocks instead of most of the range.

And the cost is not the power bill. Every radiating sonar set is offered to every network in the dimension as
a target of its own, with a loud acoustic signature and its own net's transponder code. Detection costs the
fourth power of range and being detected costs the square, so a set that pings is heard about twice as far as
it can see. This is the first place in the recon design where EMCON is a mechanic rather than an intention.

The anechoic coating closes the triangle. It damps what a hull radiates, so it halves the range that hull is
heard at - and it does nothing at all against a ping, because the echo is read off the **raw** signature.
Hull size is physical; a surface treatment quietens a hull and does not shrink it.

## 76. Water is terrain, and the seabed is the other half of it

The terrain field condenses the world to one surface height per eight-block column. That height is the
*maximum* over the cell, which is why every sight line carries a margin to absorb the bias. Sonar needed the
other surface, so there is now a second array: the seabed, from `OCEAN_FLOOR`, as the **minimum** over the
cell - biased the opposite way, because a coarse grid should not invent a wall across a strait narrower than
one cell.

One walk, two tests:

```
blocked if   seabed rises into the path        land, an island, a shoal
blocked if   the local surface falls below it  the path has left the water
```

Between them those cover a shoreline, an island, a seabed ridge and a target in the air, and none of them is
a special case. The same walk returns the mean depth, which buys two more terms for nothing: shallow water is
a bad place to listen, and eighteen blocks down there is a layer that a contact on the far side of is heard
at about a third the range. **Running depth is to a hydrophone what mast height is to a radar** - the one
placement decision that changes what it hears.

An unsampled cell is skipped rather than counted as either. The fallback height in a terrain field that has
not been built yet would read as a continent, and a freshly placed probe would hear nothing until the build
budget reached it, silently.

## 77. A radio does not work under water

The hydrophone probe is the fifth on the grid: waterlogged, 384 blocks, ten FE a tick while it listens and
twelve times that the moment it pings. **Redstone fires the ping**, so it is a pulse you wire up rather than
a mode you leave on, and it needs no interface at all. Out of the water it reports `DRY` and registers
nothing, which is the entire identity of a water probe.

None of that worked at first, for a reason worth keeping. Link clearance was measured against
`MOTION_BLOCKING_NO_LEAVES`, and **that heightmap counts water as surface**. A node under the sea was
therefore unreachable from any hub at any range: the probe was placeable, powered, calibrated and
permanently orphaned. It now measures against the lower of that heightmap and `OCEAN_FLOOR`, which is the top
of the solid ground with leaves and fluid both discounted - a hydrophone is wired along the bottom, not
talking through the sea.

The same heightmap had the same bug one layer over, with a louder symptom: a torpedo detonating twenty blocks
down was being recorded as a **camouflet**, a charge buried in rock at double coupling, the loudest thing on
the seismic band. One line, both places.

## 78. What is verified

`tools/perf/reconsonar.py`, eight arms, every one with a control beside it that has to keep failing. The
bench world is superflat plains and has no sea in it, so the probe builds one: a channel with walls stopping
exactly at the waterline, because a cell straddling a taller wall reports the wall top as its surface and
every path over it then reads as leaving the water.

The pure half is in `/wflib recon selftest` and runs with no world at all: both laws, the passive
error radius landing near its own range, the constant ranging on a ping, the echo floor finding a silent
hull, the coating halving one range and not the other, a set refusing its own ping while hearing somebody
else's, and the seabed walk stopping at a shoal and not at a seabed.

# Part XXII: TV rounds

## 79. Model

- `seeker(CameraSpec)` on preset/builder => TV round; `wflib:tv` = `atgm` airframe, `shaped_charge`, 2.5 b/t, 0.12 rad/t, 600 t motor, `air_launch` -> `direct`, `CameraSpec.TV_SEEKER` (40 deg FOV, x4, 1200 blocks datalink, optical).
- Operator = subscriber of the round's camera feed (`CameraNet`) passing `TvGuidance.entitled`: `controlId`/owner is the player or anything in their ride chain (vehicle seat, remote UAV); datalink `linkAt(distance) > CameraFeed.USABLE_LINK`.
- Gimbal order (`CameraControlPacket` yaw/pitch) = line of sight flown: `velocity = sight * cruiseSpeed`, then the normal turn/thrust limits. Not during `ASCEND` (rail drop runs out first). Operated => designation cleared, never offloaded to sim.
- Let go (unsubscribe, TTL, out of range, operator dead) => `MissileSeeker.release`: ray along the last sight, 512 blocks, cut at the first unloaded chunk. Entity => designated (homes); block => aim point; nothing => 512 on along the line. Flown by the preset's stages from there.
- Connect ad hoc, any time in flight: `TvConnectPacket` -> newest entitled round in range -> `TvLinkPacket(id)`; round removed => `TvLinkPacket(id, lost)` to its subscribers.

## 80. Client

- Keys: `key.wflib.tv_link` (Y) connect / let go, `key.wflib.tv_view` (M) full view <-> picture-in-picture (top right, 34% width). Scroll = zoom.
- `MixinMouseHandler` wraps `turnPlayer`'s `LocalPlayer.turn` at priority 1500: outermost over a vehicle mod's turret wrap. Linked => mouse pans `CameraLink`, look untouched.
- Order clamped to 60 deg off the heading the client sees (`TvClient.GIMBAL_LIMIT`): no wind-up past the gimbal.
- Feed pass: lens = tracked entity's frame pose + nose + 0.3 along the sight (`CameraTarget`), not the 5 Hz feed walk. Streamed terrain keeps the round tracked at any range. OSD `TV-xxxx`, motor bar `MTR`; burnt out != blind (`CameraFeed.GUIDED`).
- Stale feed (> `CameraFeed.STALE_TICKS`) or lost => "SIGNAL LOST" card 20 ticks, link dropped.
- Feed entity tracked to its subscribers at any range when the client holds its chunk (`CameraNet.watches` in `MixinChunkMapTrackedEntity`); vanilla stops at the view distance. Stream window centred on entity + velocity * 20 t (capped at half the radius), any entity feed (was drones only).
- Any feed pass: `getMainRenderTarget()` = feed FBO (`MixinMinecraftFeedTarget`). Iris without a pack binds the main target inside `renderLevel`; before this every feed under Iris was blank sky.

## 81. What is verified

- `TvGuidanceGameTest` (6): gimbal = line of sight flown, let-go block lock, let-go entity lock, stranger refused + launcher offered, past datalink range flies the last sight line, `wflib:tv` preset wiring.

# Part XXIII: Chunk streaming and remote bodies

## 82. Two streamers, one store

| streamer | who sees | view | tickets |
|---|---|---|---|
| `CameraChunkStream` | feed subscribers (monitor, receiver, TV link) | own `FeedViewArea` pass; player view untouched | FULL per chunk (distance 0), window led by velocity |
| `DetachedBodyStream` | operator riding a `DetachedBodyHost` from afar | whole client moved: `SetChunkCacheCenter` on host, grid origin on host | region ticket radius r at host (ticks like a player); small ticket at the parked body |

- Both = `StreamWindow` (owner identity in `ChunkStreams`, one `Encoder` per window per pass, release on leave).
- Grid origin (`MixinLevelRendererFeed`, vanilla renderer only): feed pass camera > detached host > player. Sodium: no grid, no redirect.
- Parked body (`DetachedBodies`, `MixinChunkMap`, `MixinChunkMapTrackedEntity`): view distance `bodyViewDistance`, player ticket off (`skipPlayer`) for `bodyTicketRadius`, body-side entities untracked except what it rides/carries; host root vehicle always tracked.
- Host contract (`api.DetachedBodyHost`): anchor per operator, rider positioned at anchor, anchors synced to clients (render + `DetachedView`).
- `HostWakeup`: last position per host (`wflib_host_wakeup` saved data, recorded on join/leave + every 40 t); `request(player, id, callback)` tickets the chunk until loaded and `tickCount > 1`.
- Config `detachedBody.*`, `streamDebug.*` (common). Commands: `/wflib stream audit|status|sent|sleeping|debug|reset`; client `/wfstream audit|map`.
- Verified: `mc-harness/scenarios/chunk_stream_audit.mjs` (server belief vs client held, feed + detached UAV).
