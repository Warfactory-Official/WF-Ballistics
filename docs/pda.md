# PDA

Item `wflib:pda` (`pda/`, client `pda/client/`). Replaces `wflib:terminal`, `wflib:camera_tablet` (no migration: old stacks => air).

## 97. Item and focus

| state | input |
|---|---|
| not held | `Pda.carries` (inventory) still gates telemetry, TV keys |
| held (main hand), rest | mouse-look; use => focus; sneak + use => item action (monitor => copy channels) |
| focused | mouse -> cursor (`MixinMouseHandler`: PDA before TV), LMB = pointer, wheel -> screen, WASD walks; use / Esc / slot change / any Screen => rest |
| fullscreen (`PdaClient.fullscreen(feedId)`) | feed letterboxed on the HUD, PDA hidden; mouse pans `CameraLink`, wheel zooms; use / Esc => focused; link lost => focused |

- No Screen while focused: `setScreen` releases movement keys + mouse grab.
- `startUseItem` repeats while use held => latch until key up.
- Press + release between two frames => `pressLatch` delivers one down frame (synthetic clicks).
- Crosshair layer hidden while focused.
- Off-hand item hidden while held (both `RenderHandEvent`s cancelled).

## 98. Rendering

- `PdaRenderer` (HAND pass): glTF `wflib:models/pda/pda.glb` (placeholder: MineAssault `tools/ma.py pda-placeholder`), nodes `grip_left` / `grip_right` = wrist goals for `client.view.PlayerArms` + `ArmViewPose` (shared with MineAssault).
- Material `extras.textureSlot: "screen"` <- `UiSurfaces` texture of `PdaShell` (384x240 units, v0 = top; px/unit = integer nearest raised screen's window px, `SCREEN_WIDTH` must match model); `TextureSlots.emissive` => unlit.
- Placeholder 0.232 x 0.148 m, screen 0.224 x 0.14. `VIEW_SCALE` 1.9 over the metric model: raised screen ~half the view wide; arms solve to the scaled grips.
- Elsewhere (`GemRenderItemRenderer`): no slots => screen not drawn.
- Surface redraw 20 fps at rest, every frame focused. Cursor / hit table 1 frame stale.

## 99. Apps

- `PdaApps.register(PdaApp)` at client setup; sealed at first draw (`build()` once, one `UiInstance`); later => throws.
- App area `PdaShell.APP_WIDTH` x `APP_HEIGHT`, palette `PdaShell.*` (expressions). Subject / signal names global => prefix `pda_`.
- `visible(player)` per tick; open app turning invisible => home.

| app | data |
|---|---|
| Telemetry | event log: `TelemetryLog` (reporter `recordEvent` while `datalink()` non-null; DAMAGED / OFFLOAD / ONLOAD silent; ring 256, not saved) -> carriers per `TelemetryLink.receives`, pushed per server tick; history `TelemetryLogRequestPacket(after seq)` on open. Rows = ALL + reporters (live `TelemetryClient` fix or last event); classified drones => no datalink |
| Cameras | held PDA `CHANNELS` -> `CameraPanelPacket` (`PdaCamerasRequestPacket` on open, `CameraControlPacket` refresh); owns `CameraLink` while open unless a TV round holds it; FULL => fullscreen |
| Drones | held PDA `CHANNELS` drone targets -> `DroneStatusRequestPacket` per 20 ticks while open -> `DroneStatusPacket` (state, battery, program step, distance; unloaded => `DroneRecall`; nearest dispatcher); feed via `CameraLink`, FULL. DISPATCH tab: kind + target (own position / side marker, this dimension) -> `DroneDispatchPacket` -> `DroneDispatchers.nearest` -> `DroneDispatcher.dispatch`; launched drones -> held PDA `CHANNELS` |
| Map | host's fullscreen map (`WFMaps.openFullscreen()`: JourneyMap `UIManager` internal, Xaero `GuiMap`) inside the app area via `EmbeddedScreen`; LMB / drag / wheel / RMB menu forwarded; map closes itself => home; hidden without a host |
| Markers (`MarkerClient` map tool) | map menu add (name prompt) / remove; `MarkerStore` SavedData per side (faction, else player), cap 64, pushed to online side members; layer `wflib:markers`, current dimension |
| Radar | `PdaRadarSubscribePacket(range)` while open -> `PdaRadarPacket` per 20 ticks: own net `TrackPicture` as a `ScopeFrame` about the player (not `ScopeCache`: frames keyed by net, hub frames would overwrite). Sonar = tracks with the SONAR band bit (rings) |
| Satellites | `SatStatusPacket` per 40 ticks to every carrier: own net's birds, empty without uplink; next pass = `OrbitalNet.nextPass` sampled 40 ticks to 20 min. Console: typed `[callsign\|#n\|n] verb args` -> `SatCommandPacket` (own net + held + uplink) -> `OrbitalNet.commandBy`, issuer = player; replies `SatReplyPacket` + changed `reply()` lines. Local: help, sats, select, clear. IMAGERY tab = `SurveyCache` tiles around the player |
| Alerts | `PdaAlerts` history (32), badge = unread |
| Terminal | line -> `TerminalCommandPacket` -> `PdaTerminal.run` (held PDA): `drones` (held `CHANNELS`, loaded), `nodes` / `sats` (own net + uplink), `rename drone\|node\|sat <#n\|name> <new\|->` (name 1-16 `[A-Za-z0-9_-]`, `-` = default). Drone -> `DroneEntity` callsign (datalink label); node -> `NamedNode` block entity (hub, probe, orbital node), label applied at its next registration; sat -> `callsign` command-bus verb |
| UAVs (CombinedAssault `UavPdaApp`) | other mods' apps: `PdaApps.register` at client setup; panels `PdaShell.panel`; feed rights for non-`CHANNELS` links: `CameraNet.entitle` |

## 100. Uplink (`pda/uplink/Uplink`)

- Own net (`ReconOwners.netOf`) only; no operator override. GROUND = online `GridNode` of that net within its `linkRange` (3D, node eye); RELAY = `OrbitalNet.overhead` relay (`SatPayloads.RELAY`) `inContact`. Cached 10 ticks.
- Gates: PDA radar frames, `SurveyRequestPacket` (else `mayChooseAnyNet` for the debug survey screen). In-world hub / scope / surveillance frames stay chunk-tracker broadcast (display physically present).
- Gap: relay path unverified (no orbital gametest).

## 101. Embedded screen (`client/screen/EmbeddedScreen`)

- Hosted `Screen` drawn to an FBO of the host's px size; while it runs: `Window` size / GUI scale / scaled size (`MixinWindowEmbedded`), main target, `MouseHandler.xpos/ypos`, `minecraft.screen` = its own.
- GUI scale = largest <= window's leaving >= 320x180 GUI px (vanilla 320x240 => scale 1 on a 16:9 app area).
- `setScreen` inside => captured as next screen; `null` => ends; `Standalone` (`TextPromptScreen`) => real `setScreen` after the run, window-sized; PDA stays raised over it.
- Direct `minecraft.screen` writes (JourneyMap `LayeredScreen` popup display / `popLayer`) => swap.
- `MouseHandler.setIgnoreFirstMove` cancelled while running (JourneyMap drag start => lost PDA cursor delta).
- Gap: Xaero embedding unverified.

## 102. Alerts (`pda/alerts/PdaAlert`, client `PdaAlerts`)

- Carried PDA only: HUD toast top-left (3 newest, 5 s; top-right = minimaps) + `wflib:pda.buzz` (`tools/audio/pda_buzz_synth.py`) + history.
- Server: `PdaAlert.send(player, text, tier)`; recon alerts (track -> ALARM / WEAPONS_RELEASE) of own net within 4096 while uplinked, forwarded once per published picture; first sight of a net skipped (stale).
- Client posts: sat overhead / hunter inbound (status transitions), TV link lost. CombinedAssault: UAV low fuel / charge (< 15 %, re-arm > 25 %), fire request received.

## 103. Dispatch (`drone/dispatch/DroneDispatcher`)

- Register while loaded: `DroneDispatchers.add` / `remove`. Permitted: `side()` null, same side, or WarForge allies; no range limit, no uplink gate.
- `DronePadBlockEntity`: offers LOITER; DELIVER with pad cargo (rides leader); STRIKE / MINELAY when the configured mission slings a warhead / mines. Run = configured mission copy, DIRECT, program = one step, home = pad, operator = dispatching player (hears the datalink without a side).
