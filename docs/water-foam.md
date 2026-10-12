# Water foam, and a blast that lasts

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
