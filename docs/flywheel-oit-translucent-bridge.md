# Making the rocket exhaust composite correctly with vanilla translucents

## Chosen approach (current): solid translucent terrain

The exhaust vs. water problem is solved the cheap way: on **Fancy/Fast** graphics, translucent terrain
(water, ice, stained glass, ... and the tripwire layer) is rendered **opaque**. `MixinLevelRenderer`
hooks `renderChunkLayer` just after the render type's `setupRenderState()` and, for those layers, turns
blending off and forces depth writes on. The geometry then writes a solid surface into the depth buffer,
so the Flywheel-instanced translucent exhaust occludes against it correctly (via normal depth testing)
instead of showing through. It is also cheaper than blended translucency. Toggle:
`WFClientConfig.solidTranslucentTerrain` (default on). **Fabulous** is left untouched
(`Minecraft.useShaderTransparency()`), keeping its heavier real transparency.

That is the whole solution. Everything below is the abandoned first approach, kept for reference.

---

## Superseded approach: bridging into Flywheel's OIT

Status: **abandoned** (all code removed). It aimed to make vanilla translucents composite with real
transparency by feeding them into Flywheel's own order-independent transparency (OIT) pass. It worked
only on the indirect backend, required mixing into Flywheel's non-API backend internals, and needed
custom shaders replicating Flywheel's moment pipeline - far more complexity than the solid approach for a
result the solid approach delivers acceptably. The Flywheel OIT analysis below remains accurate and useful
if real transparency is ever wanted.

## The problem

The exhaust emitter (`client/flywheel/InstancedTrailEffect` → `InstancedTrailVisual`, sharing
`FlywheelModels.particleQuad()`) draws billboarded translucent quads through Flywheel.

Flywheel 1.0.4 renders **all** of its geometry - opaque and translucent - from a single dispatch,
`VisualizationManager.RenderDispatcher.afterEntities()`, injected into `LevelRenderer.renderLevel` at
the `"blockentities"` profiler push (`impl/mixin/LevelRendererMixin#flywheel$beforeBlockEntities`).
That is **after entities but before vanilla translucent terrain (water)**. There is no per-material
render stage - you cannot ask Flywheel to draw the exhaust after water.

Consequences, both observed:

1. **Whole-quad artifact.** The billboard material had no alpha cutout, so fully-transparent texels
   still wrote depth and blended - the entire quad affected the scene, punching rectangular holes in
   water. (Fixed in stage 0.)
2. **Water vs. exhaust ordering.** Order-dependent translucency drawn before water is a lose-lose:
   no depth write ⇒ water (drawn later) overdraws exhaust that is actually in front; depth write ⇒
   the transparent corners occlude the water behind. Even done "right," water behind the exhaust is
   depth-culled rather than blended, so you never see water *through* the exhaust.

Only OIT that includes **both** the exhaust and the water resolves #2 fully. Flywheel has such an OIT
pass; vanilla water just doesn't participate in it. This doc bridges that gap.

## How Flywheel's OIT works (1.0.4, indirect backend only)

Wavelet/moment-based transmittance OIT. Implemented under `backend/engine/indirect/`; **only the
indirect (GL4.6) backend has it.** On the instancing backend, `Transparency.ORDER_INDEPENDENT`
silently degrades to `TRANSLUCENT` (see `api/material/Transparency`).

`OitFramebuffer` owns three color targets plus the shared scene depth:

| target | format | role |
|---|---|---|
| `depthBounds` | RG32F | near/far eye-depth bounds per pixel (MAX-blended) |
| `coefficients` | RGBA16F ×4 layers | 16 wavelet absorbance coefficients (`internal/wavelet.glsl`) |
| `accumulate` | RGBA16F | transmittance-weighted color |
| depth attach | main scene depth **texture** | so translucents depth-test against opaque geometry |

`OitFramebuffer.prepare()` binds the FBO and attaches `getMainRenderTarget()` (or the item-entity
target under fabulous) depth texture. The render sequence, driven by `IndirectDrawManager.render()`,
draws the translucent geometry **three times**:

```
submitSolid()                                   // opaque, into main framebuffer
oitFramebuffer.prepare()
oitFramebuffer.depthRange()                      // MAX blend -> depthBounds
  submitTransparent(OitMode.DEPTH_RANGE)         // pass 1: geometry
oitFramebuffer.renderTransmittance()             // additive blend -> coefficients (4 MRT)
  submitTransparent(OitMode.GENERATE_COEFFICIENTS)// pass 2: geometry (add_transmittance)
oitFramebuffer.renderDepthFromTransmittance()    // fullscreen: write gl_FragDepth where T->0
oitFramebuffer.accumulate()                      // additive blend -> accumulate
  submitTransparent(OitMode.EVALUATE)            // pass 3: geometry (weighted color)
oitFramebuffer.composite()                       // fullscreen: resolve accumulate -> main target
```

Each `OitMode` is a separately compiled program variant of the same pipeline fragment shader
(`internal/indirect/main.frag` → `_flw_main()`), selecting which MRT outputs to write. The key
building blocks a participating fragment needs (all in `internal/wavelet.glsl`):
`add_transmittance(coeffs, T, depth)` for pass 2, and `transmittance(coeffs, depth)` /
`total_transmittance(coeffs)` for the weighted color in pass 3. Depth is normalized into the
`depthBounds` range.

## The bridge

Draw the visible **vanilla translucent geometry** into the same OIT targets, in the same three
passes, then let Flywheel's `composite()` resolve exhaust + water together.

Two facts make this tractable:

- Translucent **terrain** (water/ice/stained glass) lives in per-section `VertexBuffer`s compiled at
  chunk-build time - they persist on the GPU and can be redrawn at any point in the frame, including
  early during Flywheel's OIT at `afterEntities`. OIT is order-independent, so vanilla's per-section
  translucent re-sort is not needed for correctness.
- The visible section list is on `LevelRenderer` (`renderChunksInFrustum` /
  `SectionRenderDispatcher.RenderSection` → `getCompiled().getVertexBuffer(RenderType.translucent())`).

### Moving parts

1. **A water OIT shader program**, one variant per `OitMode`. It consumes vanilla's block vertex
   format (POS, COLOR, UV0, UV2 light, NORMAL) + a per-section offset uniform, samples the block
   atlas, applies fog, discards on alpha (epsilon), and emits the same OIT outputs as Flywheel's
   pipeline frag by `#include`-ing `flywheel:internal/wavelet.glsl` and matching its `depthBounds`
   normalization + frame uniforms. **This is the hard part and needs in-engine iteration.**
2. **Inject the water draws** into `IndirectDrawManager.render()` right after each
   `submitTransparent(mode)` loop (three inject points, mode-matched), so water co-accumulates with
   the exhaust before `composite()`.
3. **Suppress the later vanilla translucent-terrain pass** - mixin `LevelRenderer.renderChunkLayer`
   (or `renderSectionLayer`) for `RenderType.translucent()` and cancel it, since that geometry is now
   resolved inside Flywheel's OIT.
4. **Backend gate + fallback.** All of the above only runs on the indirect backend with OIT active.
   Otherwise leave vanilla water alone and let the exhaust render as plain `TRANSLUCENT` (stage 0
   already degrades correctly).

## Staged plan

- **Stage 0 - material (done).** `FlywheelModels.PARTICLE`: `TRANSLUCENT → ORDER_INDEPENDENT` +
  `cutout(EPSILON)`. Fixes the whole-quad artifact and puts the exhaust into Flywheel's OIT. On its
  own it already improves the water-in-front case; it does **not** yet show water through exhaust.
- **Stage 1 - capture/suppress plumbing (done, compiles).** `WFClientConfig` (CLIENT) gates it;
  `TranslucentOitBridge.active()` is the backend gate (`flywheel:indirect`); `AccessorRenderChunkInfo`
  + `AccessorLevelRenderer` expose the visible sections; `MixinLevelRenderer` can suppress vanilla's
  translucent-terrain draw (debug flag `debugSuppressVanillaTranslucent`).
- **Stage 2a - inject into the OIT passes + timed capture (done, compiles).** `MixinIndirectDrawManager`
  hooks `render` just before `OitFramebuffer.renderTransmittance` / `renderDepthFromTransmittance` /
  `composite` (once per pass, correct draw-state bound; `remap = false`, `require = 0` so a Flywheel
  change no-ops instead of crashing). `TranslucentOitReplay.replay(pass)` snapshots the sections on
  pass 0 (at `afterEntities`, before vanilla water) and dispatches per pass. Verifiable by the
  `[WF OIT bridge] replaying N sections` log on the indirect backend. Needed `flywheel-forge` (not just
  the API) on the compile classpath so the mixin AP can resolve the backend target.
- **Stage 2b - the OIT geometry draw (implemented, compiles; needs in-engine validation).**
  `TranslucentOitPrograms` compiles three programs (one per pass) that mirror Flywheel's `common.frag`
  OIT branch exactly and prepend Flywheel's own `wavelet.glsl` (loaded at runtime). `TranslucentOitReplay`
  binds the block atlas (unit 0) + lightmap (unit 2), reconstructs the camera-relative view + level
  projection, and draws each captured section's `VertexBuffer` with a per-section chunk-offset uniform,
  once per pass, depth-testing against the shared scene depth. Suppression now flips on automatically for
  a frame only when the replay actually ran (`AccessorLightTexture` supplies the lightmap id). Shader
  compile/link failure disables the draw and falls back to vanilla (no crash). Known simplifications to
  verify/tune on GPU: view-matrix reconstruction (roll/handedness), `RenderSystem.getProjectionMatrix()`
  at the inject point, blue-noise dithering omitted, and fog not yet applied.
- **Stage 3 - widen scope.**
  - *Static chunk layers (done, compiles):* capture now covers both `RenderType.translucent()` (water,
    ice, stained glass, slime, honey, nether portal, ...) and `RenderType.tripwire()`; both are
    per-section BLOCK-format `VertexBuffer`s drawn by the same shader, and both are suppressed in vanilla
    when replayed.
  - *Dynamic sources (remaining):* translucent entities/block-entities, particles, clouds and weather are
    not per-section static buffers - they are transient `MultiBufferSource` / particle / cloud geometry
    generated per frame. Each needs its own capture (intercept the buffer/flush, keep the vertex data,
    redraw it with the OIT program), which is a larger mechanism than the static path. Recommended only
    after stage 2b is visually validated, since they all composite through the same (as-yet unverified)
    OIT replay.

## Risks / open questions

- **Indirect-only.** No OIT on the instancing backend; the whole bridge is gated off there.
- **Internal API.** `IndirectDrawManager`, `OitFramebuffer`, `PipelineCompiler.OitMode` are not
  public API - mixins/refs into them can break across Flywheel updates. Pin the Flywheel version.
- **Shader parity.** Matching vanilla water's look (fog, biome tint, light) inside the OIT frag is
  the main effort and the main visual-risk.
- **Fabulous graphics.** Under `useShaderTransparency()` Flywheel targets the item-entity buffer and
  copies depth (`OitFramebuffer.prepare`, `FixFabulousDepthMixin`); the suppression + replay must
  respect that path.
- **Performance.** Redrawing all visible translucent sections three extra times per frame. Cull to
  sections actually near/behind exhaust if it matters.

## Reference (decompiled seams, Flywheel 1.0.4)

- `impl/mixin/LevelRendererMixin` - render dispatch hooks (`afterEntities` at `"blockentities"`).
- `backend/engine/indirect/IndirectDrawManager#render` - the three-pass OIT orchestration.
- `backend/engine/indirect/OitFramebuffer` - FBO/targets/passes/blend state.
- `assets/flywheel/flywheel/internal/wavelet.glsl` - moment read/write helpers.
- `assets/flywheel/flywheel/internal/oit_depth.frag`, `oit_composite.frag` - fullscreen resolves.
- `api/material/{Transparency,WriteMask,CutoutShader}`, `lib/material/{Materials,CutoutShaders}`.
