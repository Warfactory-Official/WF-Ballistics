# Sonar

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
number: `CombinedAssault` has been computing an `ENGINE_ACOUSTIC` term for months behind a comment saying it was
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

REDACTED

REDACTED
REDACTED
REDACTED
REDACTED

REDACTED
REDACTED
REDACTED
REDACTED

