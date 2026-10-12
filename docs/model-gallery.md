# The Model Gallery

## 43. What it is

`/wfmodels` opens a viewer onto **every model this mod has**, out of every registry that holds one. A
tab per registry, a page of squares at a time, arrow keys or a click to fill the screen with one and turn
it over.

| tab | where they come from | drawn as |
| --- | --- | --- |
| missiles | `MissileModels` | baked vanilla model, placed by `MissileItemRenderer` |
| mines | `MineModels` / `MineRigs` | imported glTF |
| drones | `DroneModels` / `PartRigs` | imported glTF |
| pieces | `DroneModels.pieces` | one node of an airframe, isolated |
| doors | `DoorType` x skins / `DoorRigs` | glTF assembled at load time |
| glyphids | `GlyphidModel` | glTF, castes as sheet variants |
| props | `ModModels` | baked: the billboards, the bones, the crate, the bomblet, the missile props |
| probe | `ProbeModels` | baked, standalone models a probe panel draws |

A tab with nothing in it is not shown, so `probe` appears only once something declares a model there.

It used to show the missiles and nothing else, which meant every model added since (nine mines, two
airframes, twenty-seven pieces of them, thirty-three doors) could only be looked at by spawning one.

## 44. How it is put together

`ModelGallery` is the catalogue and the only place that knows where models live. It hands the screen a
list of `GalleryEntry`: a name, some figures, whatever clips and finishes the thing has, and a way to be
drawn into a square. Adding a registry is a method there; the screen gains nothing to change.

Two implementations cover every registry:

- **`BakedEntry`**: the vanilla path. The gallery has the quads here, which is why **normals mode**
  exists at all: a face that came back from a modelling tool wound the wrong way is invisible in a
  textured render and unmissable once every surface is coloured by its own normal.
- **`GltfEntry`**: GemRender's **direct** path, the one that puts a mine in an inventory slot. Flywheel
  draws the level and a screen is not the level, so instancing is not available; the direct path takes a
  model, a clip, a clip time and a matrix, and draws a whole page in a handful of draws.

Both are flushed by the screen itself, **with the depth test on**. They are not left for the GUI's own
flush at the end of the frame, because `GuiGraphics.flush` turns depth off, and a solid model drawn
without depth is its own back faces.

**Fitting.** A cell wants every model the same size on screen, and these run from a hand-sized
anti-personnel mine to a four-block vault door. Where a registry has already measured its models the
gallery uses its figures, because those are the numbers the game places the thing by and a viewer that
disagreed with them would hide exactly the bug worth seeing. Where it has not (doors, glyphids) the
box is computed once from the model's own skinned bounds and kept, so scrubbing a clip cannot zoom the
camera as it runs.

One case cannot be measured that way. A **piece** is the whole airframe with every other branch collapsed
to a point, and a collapsed branch still leaves a point where its node sat, so the box comes back the size
of the aircraft and the piece draws as a speck. Those read their own subtree out of `GltfBounds` instead.

## 45. Driving it

| | |
| --- | --- |
| tab, or click a tab | change registry |
| arrows | walk the grid; **scrub the clip** when one model fills the screen |
| enter, or click | inspect |
| shift + arrows | turn the inspected model, which is what a mouse drag does |
| R | stop or restart the turntable |
| drag / scroll | rotate / zoom |
| N / C | normals mode / backface culling: both only mean anything on the baked path |
| A / V | next clip / next finish |
| P | hold the clip where it is, so the arrows can scrub it by hand |

Clips are listed as chips along the bottom, and the first is always the model **held at its first frame**:
a rack laid rather than firing, a door shut, a piece intact. So the grid is still, and a clip runs only
when one is asked for.

## 46. The blurred overlay, and why it was there

Since 1.21, `Screen.render` calls `renderBackground` **itself**, and `renderBackground` runs a blur pass
over the whole render target before painting the menu texture across it.
`AbstractContainerScreen.render` does the same.

So the shape that looks right and was right on 1.20:

```java
public void render(...) {
    this.renderBackground(...);   // blur pass 1
    ...draw my panel and my models...
    super.render(...);            // blur pass 2, then the menu texture, over everything just drawn
}
```

-- blurs and then dims **the screen's own content**. It does not read as a bug; it reads as a soft
texture or a bad font, which is why four screens carried it. The correct shape is `super.render` first
and the content on top of it, with `renderBackground` never called by hand; a screen that wants a panel
*under* its widgets overrides `renderBackground` and draws it there, which is what `DroneProgramScreen`
now does.
