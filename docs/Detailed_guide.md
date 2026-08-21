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
> wfballistics drone threads
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
3. **The hard floor** (`DroneEntity.clampAboveGround`). The first two are prediction; this is measurement.
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
- **Hull contact** (`Contacts`, applied in `DroneEntity.resolveContacts`). Two hulls that end a tick inside
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
/wfballistics station code               # this pad's code, and how many stations it accepts from
/wfballistics station allow <code>       # accept handshakes from that station
/wfballistics station revoke <code>
/wfballistics station send <code>        # dispatch this pad's cargo by handshake
/wfballistics station list               # operator: every registered station
/wfballistics exchange list              # operator: live and recent exchanges
/wfballistics exchange cancel <id>
/wfballistics exchange purge             # cancel everything live
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
- carrying nothing: reports its state and battery.

That works on a flying drone as well as a wreck, so an armed drone can be disarmed by hand. Hitting a wreck
again breaks it up and spills whatever is still aboard.

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
`/wfballistics drone list` reports how many are still to launch, so "still launching" can be told from "lost
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
  blocks' spacing held a permanent 0.24–0.42 b/t of jitter that never decayed, so the flight sat over the pad
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

Held error is 1.5–1.9 blocks everywhere: whichever way it launches, the flight converges to the same tight
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
(`DroneEntity#sweepForContacts`, `WATCH_RADIUS`) and arrive already named in the snapshot; the handler only
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
/wfballistics drone dispatch <x> <y> <z> [count] [formation] [spacing]   # cargo run, with a crate
/wfballistics drone strike  <x> <y> <z> [count] [warhead]     # attack run, one warhead each
/wfballistics drone list                                       # live + [SIM] drones, state, battery, load
/wfballistics drone pad <padPos> <x> <y> <z> [count] [formation] [spacing]

/wfballistics drone program moveto|deliver|collect|strike <x> <y> <z>
/wfballistics drone program loiter <x> <y> <z> [radius] [seconds]   # 0 radius = hold, 0 seconds = until low
/wfballistics drone program hold <x> <y> <z> [seconds]
/wfballistics drone program exfil
/wfballistics drone program list | remove <n> | clear
/wfballistics drone program launch [count] [formation] [warhead] [spacing]
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
/wfballistics drone selftest            # assert the decision layer; prints pass/fail
/wfballistics drone scenario <name>     # delivery | strike | squad | lowbattery | downed | longrange | terrain
/wfballistics drone threads             # which thread the A* is running on, and what it costs
/wfballistics station code              # station codes, allow-lists and handshakes: see 10d
/wfballistics drone sim <on|off>        # off keeps drones real for a whole mission
/wfballistics drone recall              # turn every drone around, wherever it is
/wfballistics drone clear               # delete drones, crates and off-world records
/wfballistics drone telemetry           # the nearest drone's event timeline
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

- `wfballistics drone list` is the workhorse: state, altitude, distance to destination and exfil, battery,
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
yet**: no drone state consumes a work queue. `/wfballistics build` produces a real, persistent job that
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

Blueprints live in `<world>/wfballistics/blueprints/`. **Operator-placed only, and that is a boundary rather
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
/wfballistics blueprint                     list what is in the folder
/wfballistics blueprint <name>              size, block count, bill, and any caveats
/wfballistics blueprint reload              drop the cache after editing a file
/wfballistics build <name> [at <pos>]       queue a construction job (default: where you stand)
/wfballistics salvage <from> <to>           queue a demolition over a volume
/wfballistics job                           list jobs, with progress and how many are blocked
/wfballistics job <id-prefix>               detail, including the queue's frontier
/wfballistics job cancel <id-prefix>        call one off
/wfballistics station role                  what the pad you are standing at will do
/wfballistics station role set <kind>       provider | station
/wfballistics station role on|off <role>    one flag at a time
```

Blueprints load with or without their extension, and job ids match on a prefix because the list prints
prefixes and nobody is retyping thirty-six characters.

## 22. Testing the foundations

`/wfballistics drone selftest` runs 26 checks for this on top of the flight suite: **254 total**. They are
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
/wfballistics build <name> [at <pos>]     queue the job (refused outright on bad ground)
/wfballistics job work <id-prefix> [n]    put n drones from the nearest pad onto it
/wfballistics job <id-prefix>             progress, blocked count, suspension reason
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
