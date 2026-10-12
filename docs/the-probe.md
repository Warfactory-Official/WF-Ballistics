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
| `Custom` | a lambda drawing on the GemRender `Canvas`, into a box you size | none of the above |

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

### Nodes

```java
ProbeRegistry.addEntityNodes(e -> e instanceof Tank, (out, ctx, e) ->
        out.add(new ProbeNode("hatch", hatchWorldPos, info -> {
            info.title(name);
            info.action(ProbeAction.of(TOGGLE, 0, label));
        }).colour(0xFFFFD44A).claimed(crosshairOnHatch)), ProbeRegistry.CAPABILITY_PRIORITY);
```

- Target with nodes => no crosshair panel; its nodes carry everything. `addBlockNodes(Predicate<BlockEntity>, ...)`: block entities of loaded chunks in reach.
- Shown: eye -> node <= min(max(entity, block) interaction range + 1.5 m, `reach`), within 25 deg of the crosshair, eye on the `normal` side if set (else visible through walls). Focus = min `angle / max(atan(radius / d), 3 deg)` < 1, else nearest `claimed`.
- Panel built for the focused node only => capture `ctx` in the closure. Data requested for the focused node's target.
- Focused panel beside its dot (right; left at the screen edge). Selection, alt+scroll, G, jobs = above; identity includes `ProbeNode#key`.
- Use key runs the selected row: not sneaking, row available (else vanilla use). Held use key = one activation.
- Projection: `RenderLevelStageEvent` AFTER_LEVEL matrices.

REDACTED

REDACTED

- the panel on a **door** (title, mod name, live state, skin index), and on a **missile dispenser**,
  REDACTED
- both icon paths, the model path, text, rows and the panel chrome;
- the always-on fallback naming any block and its mod;
- the **server data channel** end to end: a door's powered-block count, which is not synced, arriving
  REDACTED

REDACTED
REDACTED

REDACTED

- the ramp itself: the selected row large and opaque, the rest smaller and fainter down to the fifth;
- **left alt + scroll** rotates the list and leaves the hotbar alone, which is the whole reason the
  REDACTED
  REDACTED
- the activate key runs the selected row, including its `arg`, picking "Skin 4" puts the door on skin
  REDACTED
- a disabled row refuses, silently and correctly;
- the selection falls back to the first row when the action it was on stops being offered.

REDACTED
REDACTED
REDACTED
REDACTED
REDACTED

REDACTED

