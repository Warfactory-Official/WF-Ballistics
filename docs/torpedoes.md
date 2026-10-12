# Torpedoes

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

REDACTED

REDACTED

- **A set depth is held.** Eight blocks under a surface at a known y, in a tank of known depth, started
  REDACTED
  REDACTED
- **A bottom-follower holds the bed.** Same tank, same surface, three blocks over the bed: a different
  REDACTED
  REDACTED
- **The run stays under the surface**, and is still in the water at the end of it.
- **A broached torpedo is written off.** Lifted forty blocks clear, not six: six is a porpoise and it
  REDACTED
  REDACTED

REDACTED
REDACTED
REDACTED

REDACTED
REDACTED
REDACTED
REDACTED
REDACTED
REDACTED

REDACTED
REDACTED

