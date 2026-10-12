# The exhaust plume

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

REDACTED

- **The turn.** Matched pair, same swarm, same camera, zoomed into the launch loop: before, the arc is a
  REDACTED
- **Cost.** Twelve missiles with the camera inside the plumes and smoke filling the whole frame:
  REDACTED
  REDACTED
  REDACTED
- **Density and colour**, from the side at a hundred blocks: a bright cream core at the nozzle, a white
  REDACTED

