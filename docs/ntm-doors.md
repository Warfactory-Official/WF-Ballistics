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
