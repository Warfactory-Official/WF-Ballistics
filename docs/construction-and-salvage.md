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

REDACTED

REDACTED
REDACTED
heard of a world.

REDACTED

- `blueprint/litematica-negative-size`: a region whose x and y extents both run backwards, with one block in
  REDACTED
  REDACTED
- `work/abandon-is-free-lapse-is-not`: the attempt-counting split, above.
- `work/claims-repel`: two workers at the same point must not be sent to the same corner.
- `work/only-the-holder-completes`: a stale worker cannot mark somebody else's order done.
- `work/orphaned-claims-recovered`: a reload must not permanently strand a claimed order.
- `plan/door-halves-count-once`: one order and one item for a door, not two of each.
- `plan/wall-variants-resolve-to-their-item`: exists because the opposite was assumed first.
- `station/legacy-keeps-every-role`: a pad from before roles keeps doing everything.

REDACTED
REDACTED

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

REDACTED

REDACTED

- `work/closed-site-drops-the-target`: a suspended site must stop being somewhere to be, not merely be
  REDACTED
- `work/arrival-state-follows-the-assignment`: order → `WORK`, station → `SUPPLY`, neither → `EXFIL`,
  closed → `EXFIL`.
- `work/job-drones-do-not-hold-formation` and `work/job-flights-do-not-muster`.
- `work/site-state-is-not-persisted`: `siteOpen` is a sample of the world; a stale one would leave a drone
  REDACTED
- `work/suspension-is-reversible`: progress survives, and the job comes back.
- `territory/survey-covers-every-chunk`: a 61-block box straddling chunk boundaries on both bounds hits all
  REDACTED
  REDACTED
- `territory/samples-land-inside-the-job` and `territory/refusal-reports-where`.

REDACTED
REDACTED
REDACTED
REDACTED
REDACTED

REDACTED

---

