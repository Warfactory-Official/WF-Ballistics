# Smoke over water

## 54. The defect

An explosion over a lake cut a blast-shaped hole in the lake. Not a dark patch or a misordering: the
water was **gone** where the smoke was, in hard rectangles, and through the gap you saw the lake bed.
The missile exhaust did the same in miniature, one pale square per puff, scattered across the sky.

It is not a particle bug. Flywheel draws its instances after entities and **before** vanilla's
translucent terrain, so every one of these particles is drawn before the water it is standing in front
of. Blended geometry that writes depth in that position deletes whatever translucent surface is behind
it, because the water pass is then rejected against a depth the particle had no business writing. And
the hole has the shape of the *quads*, not of the sprite, because `CutoutShaders.EPSILON` only discards
a texel that is entirely transparent: every faintly-visible texel of a soft round puff writes a fully
opaque depth value.

The smoke families were `additive`, which is where the two halves of the problem meet. Additive is
`ONE, ONE`: a fragment can only add light, so it has no business occluding anything, and the depth write
was simply a mistake. But taking it away does not make additive *correct*: it makes the water paint
over the smoke instead, and a thick surface paints it out of the frame entirely. **Additive has no right
answer against water.** Either it erases the water or the water erases it.

## 55. What changed

`ParticleModels` now derives the write mask from the transparency: depth for `OPAQUE`, colour only for
everything blended. That ends the erasure for every additive effect in the mod, the lingering flame and
the blast fireball included.

Ordering needs a blend `WaterSplit` cuts at the water surface (GemRender `INTEGRATION.md` "Choosing a
transparency"). Current:

| effect | model | style `.glow` |
|---|---|---|
| missile exhaust (`trail`, `EXHAUST`) | `glowing` on `mist_soft` | 1, x `(1 - burnt)` in `exhaust.vert` |
| explosion cloud (`cloud`) | `glowing` on `smoke` | 1, over the `cool` span |
| lingering flame (`flame`) | `glowing` on `flame` | 1 |
| torpedo wake, foam, plume, ash, puff, mist | `absorbance` | - |

- `smoke.png` / `flame.png`: 2x2 atlases from `tools/textures/{smoke,flame}.py`; cell, stretch, spin sign, mirror
  = hashes of `spinPhase` in `smoke.vert` / `flame.vert` => spawn MUST pass random phase. Uniform `mist_soft`
  blobs read as identical discs.
- Ash: `ASH_MODEL` = absorbance with `blur(false)`; filtered 8x8 => smear.
- Bones: opaque cutout, sink instead of fade. `ORDER_INDEPENDENT` blends alpha-1 texels => parts see-through.

- `additive` FORBIDDEN for these: not ordered against water. `translucent` (wavelet OIT) replaced: blast
  over water ~1330 -> ~550 us GPU.
- Emission unlit: fire glows at night.
- Camera inside gas (`GasMedium`): GemRender `CameraMedium`. Volumetric: cluster whose bounds hold the eye
  hidden, drawn as fog. Billboards: cell holding the eye, its style culled within 6 blocks, fog for that
  span (extinction from the mist style + `mist_soft` via `ParticleOptics`). ~8-10 -> ~1.1-3.5 ms GPU volumetric.

**The sprite changed with the blend, and had to.** The old one is an 8x8 binary dither mask: 45% of its
texels fully opaque, the rest fully transparent, with nothing in between. Added, that reads as sparks.
Composited, it reads as gravel, which is the same finding `mist` recorded when it moved to the soft
radial sprite, and the reason that sprite exists.

## 56. The numbers had to move too, and it is not a matter of taste

This is the part that is easy to get wrong, because nothing about it is visible in the style.

**Additive blending never reads the alpha.** `ONE, ONE` adds `src.rgb` and that is all; `alphaScale` and
`alphaFalloff` were dead fields in every additive style in this file. What made an additive puff go out
was `cool`, running the *colour* down toward black, because under addition, black is nothing.

Composited, black is not nothing. It is black smoke, and it is fully present for the rest of the
particle's life. Converting a style therefore means moving the fade out of `cool` and into the alpha,
and leaving `cool` doing only what its name says:

| style | was (additive) | is (composited) |
|---|---|---|
| `trail` | `alpha(0.75, 0.4)` unused, `cool(0.1, 0.6)` | `alpha(0.45, 1.4)`, `cool(0.2, 0.45)` |
| `cloud` | `alpha(0.75, 0.5)` unused, `cool(0.1, 0.25)` | `alpha(0.30, 1.6)`, `cool(0.14, 0.3)` |

The alphas are far below the numbers they replace, and that is the second half of the same point. A
blast is hundreds of quads that grow to five times its radius and lie on top of one another; under
addition that density bought brightness, and under compositing it buys opacity. At the old 0.75 the near
layer alone would black out the screen.

REDACTED

REDACTED
REDACTED
REDACTED

- **before**: the lake is *gone*. The whole surface, out to its far edge, replaced by the stone bed at
  REDACTED
  REDACTED
- **after**: the lake is intact in every frame of the burst. The smoke darkens the water it lies over
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
REDACTED
REDACTED

