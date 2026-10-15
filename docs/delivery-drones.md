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

## 10e. Bodies (`ControllableDrone`)

Autonomy flies any `ControllableDrone`: `DroneEntity` (wflib fallback) or another mod's body.
- Body owns motion + slung load; mission state = shared components `DroneFlight`/`DroneRoute`/`DroneOrders`/`DroneSquadRole` (public ctors, save/load).
- Register: `DroneTracker.track(body)` on level join, `untrack` on leave. `DroneEntity` via entity events.
- `DroneTracker.bodies(level)` / `byId` = every body; `drones(level)` = `DroneEntity` only (sim offload, pad recharge, camera monitor).
- `DronePlan.desired`/`feedForward` = guidance setpoint before `Multirotor.step`; self-flying bodies fly these, `velocity` is wflib's integration.
- `supports(DroneTask.Kind)` gates dispatch; `recall(home)` = EXFIL.
- `DroneTasking.assign(body, operator, kind, at)`: one-step program for a body in the world; grounded => home = where it stands.
- Drone pad: idle non-`DroneEntity` body within 8 blocks flying the kind takes the dispatch first (home = pad), else spawn.
- `Multirotor.integrate(v, axis, thrust, frame)`: force law without controller (attitude flown directly).
- Link: `Datalink.quality(level, spec, operatorAt(player), body)` = one figure for feeds (best audience anchor), PDA rows, TV rounds, remote pilots. Riding a `DetachedBodyHost` => operator = parked body anchor. `!usable` (<= `USABLE_LINK`) => host mod drops pilot input; CA: autopilot flies program, else holds (rotors hover, fixed-wing orbits).
- `DroneEntity` manual: CONNECT = remote ride (`DetachedBodyHost`, `api/DetachedAnchors`; refused: no camera / downed / in use). Piloted => brain plans dropped; vanilla passenger input: W/S along look (pitch incl.) x cruise, A/D strafe, jump climbs; heading = look yaw; `Multirotor.step`. Battery empty => input ignored, descends at climb rate. Grounded + no climb => parked; climb => SURVEIL; descend near ground => IDLE. Sneak = release (vanilla). Client `DronePilotView`: camera entity = drone, aimed by player look. Eye (pilot `MixinCameraDronePilot` + feed `CameraSource`) = `DroneModels.lens`: lens node, else gimbal pitch, else hull centre; heading only, lean ignored.
- Client: `DroneCamHud` = one overlay piloted (`pilot(link, release key, freeLook)` per tick; host leave key != sneak: CA sneak = DOWN; FREE LOOK tag) + PDA fullscreen drone/mounted feed (burned `CameraOsd` skipped): link bars, LOW SIGNAL, SIGNAL LOST card; watched adds name/mode/AGL/SPD/BAT/gimbal. `ScreenSensor.set(mode, link)` => same `Degrade` as feeds.

## 10f. Lasers (`laser/`)

- `LaserDamage.perTick = rated / (1 + res (m / 2 kg)^1.5 (1 + armour / 5 mm))`, `WFDamageTypes.LASER`, i-frames cleared. Mass/armour/res from `LaserTarget`; else living 80 kg, box x 100 kg/m³.
- 2 kg quad: rated/2; 80 kg player ~rated/250; 2 t airframe ~0 => anti-FPV only.
- `LaserBeamPacket` -> client `LaserBeams` (2-tick additive strip). Hosts + targeting = other mods (CA `combinedassault:laser`).

## 10g. Counterplay (`recon/env/Interference`, `Datalink`)

Passive engagers see only the recon picture => everything below defeats them.

| tool | effect |
| --- | --- |
| `Interference.Area` | main-thread area, applied in `ReconTargets.collect` as an extra countermeasure. TARGET scope: targets inside x `factor` on `band`; SENSOR scope: sensors inside hear every target x `factor`. `heat > 0` => thermal-only UNKNOWN contact at centre (`SourceIds.ofHeat`, never its own glare) |
| `PortableJammerItem` (`wflib:portable_jammer`) | use toggles `ACTIVE`; carried + on => RADAR TARGET area r 32 x 1e-4 (radar reach x0.1) on the carrier; links with either end inside cut (`Datalink`). Durability = 180 s charge, anvil + redstone block |
| thermite (`GrenadeWarheads`) | while burning: THERMAL TARGET r 8 x 0.02 + heat contact 20 (motor 2.25, body 0.15) |
| blast (`SeismicEvents.report`, grounded) | SEISMIC SENSOR r `48 sqrt(power / 4)` x 0.05, 200 ticks |
| EMP (`EMPWarhead`) | non-player entities in r 48: `Datalink.jam` 300 ticks => link 0, connect refused ("jammed") |
| `DroneNetBlock` (`wflib:drone_net`) | iron-bars shape, no collision (hull solvers stopped drones short of the strands); `catchable` body (`DroneEntity`; other mods `DroneNetBlock.catches`) whose box overlaps a strand => host pins it (`DroneEntity.moveWith`; CA `finishTick`) until the net breaks |

- Gap: no recipes (pack/GT). Heat contacts are clutter + glare; no seeker seduction (`SeekerDecoy` separate).



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

`Blasts.miningCharge`: report to 48 blocks, separately recorded rumble from 24 (full) to 200; travel delay at 343 m/s; one seed per blast => same variant for everyone.

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

`SimDroneManager` decides when a drone stops being an entity:
- Offload: `TRANSIT`/`EXFIL`, > ~128 blocks from current leg's waypoint, no player within `OFFLOAD_PLAYER_RANGE`.
- Onload: within `WAYPOINT_ONLOAD_RANGE` of waypoint, player near, or state no longer a travel leg (sim has no terrain => landing would descend forever).
- Waypoint leg-aware: homeward = exfil, not remembered destination.
- Crate discarded on offload, contents in `SimDrone`, rebuilt on onload.
- Exchange/classified never offload (sim drops legs, exchange link). Team, operator, callsign carried.

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
