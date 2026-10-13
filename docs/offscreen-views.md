# Offscreen views

## 83. API (`client.cam.OffscreenView`)

| factory | grid | cost | limit |
|---|---|---|---|
| `sharedGrid()` | main grid (player-centred), own graph | no extra section buffers/compiles | camera outside player render distance => nothing there |
| `ownGrid(viewDistance)` | second grid centred on camera | duplicate buffers + compiles | chunks outside client ring need streaming (§82) |

- `render(pos, yaw, pitch, roll, fovDeg, target, delta)`: anchor = camera entity; `render(anchor, ...)` explicit. Anchor hidden (vanilla-rendered entities only). `close()` releases graph, grid, cloud mesh.
- Call from `RenderFrameEvent.Pre` only. Nested pass => `IllegalStateException`; target without depth => `IllegalArgumentException`; `false` => nothing drawn (no level, or main grid rebuilt this frame: `lastViewDistance` != render distance).
- Aspect = target w/h; near 0.05; far `GameRenderer.getDepthFar()` (Voxy raises it).
- Drone/TV feeds = `ownGrid(camera.feedViewDistance)` + post chain + OSD (`CameraTarget`).
- Feed sources beyond drones/TV rounds: `CameraSources.register(entity -> CameraSource | null)` (server; first non-null wins). Kind `CameraFeed.MOUNTED` => lens = published eye; other entity kinds => eye + `max(GIMBAL_BOOM, bbWidth)` along the sight.
- Weapon sights: `SightSensor(view).render(..., CameraMode)` = pass + `CameraMode.sightChain()` (`wf_sight_intensifier` / `wf_sight_ir` / `wf_sight_thermal`: no macroblock, `Degrade` 0) on the caller target; THERMAL, IR => particle + gas-volume + in-gas-fog free pass (`FeedPass::seesThroughSmoke` on `ParticleBuffer` / `VolumeBuffer` / `CameraMedium` `hideWhen`); THERMAL + heat mask; IR => `Lights.IrView.MERGED` + camera IR illuminator. OPTICAL / null / `CAMERA_EFFECTS` off => plain pass. Chain keyed by (mode, target object); target resize => `resize`.
- Heat mask draws under the pass projection (set + restored itself): the pass restores the caller's => <= 1.8.12 bodies drawn at the caller FOV (sight ~3 deg => specks; drone feed off the main FOV => offset mask).
- Heat mask borrows the feed depth: stencil feed (PiP) => attached as DEPTH_STENCIL; depth alone over the chain target's own stencil (NeoForge adds one when the main has it) = incomplete FBO, mask empty (<= 1.8.12, measured `0x8CD5` after).

## 84. Per-view state

- Swapped per pass, every renderer (`MixinLevelRendererTargets`, Sodium included): Fabulous aux targets + outline `entityTarget` + transparency chain nulled, panoramic mode on. Else Fabulous sends entities/translucents to the screen; outline `entityTarget.clear` rebinds the window-sized viewport mid-pass (entities + everything after drawn at window scale into a smaller target).
- Vanilla renderer only (`MixinLevelRendererFeed`): graph, `visibleSections`, `prevCam*`, cloud mesh + key (`CloudCache`); own grid also `viewArea`, `lastCameraSection*`, translucent sort origin.
- Shared grid: translucent sort origin := camera each pass => no resort from the view; main buffers keep the main camera's sort.
- Clouds: key = camera 12-block cell. `needsUpdate`/`allChanged` (reload, cloud toggle, level change) => every view's
  mesh regenerates, not just the installed one. One shared mesh => two cameras in different cells rebuild + re-upload it twice a frame. Measured (flat, view 40 blocks off the player, mean of rep 2): pass CPU 0.246 -> 0.168 ms, rest of frame 0.433 -> 0.372 ms; view in the player's cell unchanged.
- Sodium: grid mixins off; pass runs Sodium's renderer, which re-walks visibility each pass. Sodium's own cloud cache not swapped.

## 85. Boxy far entities

- Boxy hooks sit inside `renderLevel` (`MixinLevelRendererDistantEntities`: compiled-chunk bypass, cull override, depth re-band, Voxy depth join; `MixinEntityRenderDistance`) => run in the pass unchanged. Entity counted rendered (`E: 1/…`).
- Boxy PRECISE depth needs a stencil on the pass target (`getMainRenderTarget` = target in the pass); none => BASIC.
- Far entity missing when target != window (<= 1.7.0): Sodium => targets not swapped (§84) => outline target clear set the window viewport before entities. Isolated by GL trace (viewport 512 -> 1920x1080 at `entityTarget.clear`, `LevelRenderer.renderLevel`). Not Voxy/Boxy.
- Voxy >= 0.2.16 (wf2-21 fork): viewport per draw framebuffer (`ViewportSelector`, idle 10 s => freed; shader pack => one), `NormalRenderPipeline` targets per size (<= 3). Shared viewport = HiZ + depth-bounding realloc and the other camera's temporal visibility every pass. 0.2.17: main viewport = `Minecraft.mainRenderTarget` id via field accessor (0.2.16 first-seen framebuffer => roles swap when a feed renders first or a resize renews the id; `getMainRenderTarget()` = feed target in a pass).
- Rig `mc-harness/scenarios/offscreen_boxy.mjs` (Sodium 0.8.12, Boxy 2.0.0-alpha.2, bot `Far` on glass at x 1000, window 1920x1080, `/wfview shared <size>`, `eye 3`), steady state (n ~ 610):

| WFLib / Voxy | target | Far drawn | pass CPU ms | GPU span ms | rest of frame CPU ms |
|---|---|---|---|---|---|
| 1.7.0 / 0.2.15 | 512x512 | no | | | |
| 1.7.1 / 0.2.15 | 512x512 | yes | 0.75-0.93 | 0.63-0.70 | 0.72-0.84 |
| 1.7.1 / 0.2.15 | 1920x1080 | yes | 0.54-0.61 | 0.66-0.73 | 0.55-0.60 |
| 1.7.1 / 0.2.16 | 512x512 | yes | 0.50-0.60 | 0.35-0.38 | 0.57-0.60 |
| 1.7.1 / 0.2.16 | 1920x1080 | yes | 0.50-0.72 | 0.50-0.56 | 0.53-0.70 |
