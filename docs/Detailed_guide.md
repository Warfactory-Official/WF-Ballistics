# Designing and Balancing Missiles

A practical, detailed guide to building your own missiles in WF-Ballistics: what every knob does, how the
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

**MissileEntity.Builder (programmatic / one-off).** The full API a preset wraps. Use it when spawning a
missile from code (commands, dispensers, warheads that spawn child missiles). It exposes a few variables the
preset does not, notably `ascentStage(...)` and `ascentSpeed(...)`.

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

### Flight stages (`cruiseStage`, `attackStage`, and `ascentStage` on the entity builder)

Swap the behavior of a phase. Registered options:

| phase  | id          | behavior                                                                 |
|--------|-------------|--------------------------------------------------------------------------|
| ASCEND | `ascent`    | default gravity-turn climb                                               |
| ASCEND | `intercept` | vertical launch-clear then homing (interceptors)                         |
| CRUISE | `cruise`    | default: fly to target holding altitude, hand off at 30 blocks           |
| CRUISE | `loiter`    | fly to the area, orbit it (radius 24) for ~200 ticks, then dive          |
| CRUISE | `intercept` | 3D homing on a moving target                                             |
| ATTACK | `attack`    | default terminal dive (terminal speed ~14, bleeds horizontal over 30 blk)|
| ATTACK | `dive`      | steep top-attack plunge (terminal ~18, bleeds over 12 blocks)            |
| ATTACK | `intercept` | homing (interceptors)                                                    |

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
