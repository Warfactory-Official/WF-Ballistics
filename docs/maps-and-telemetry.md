# Maps and telemetry

## 88. Map API (`client/map/`)

- Map-mod neutral. Each map mod's types only in its package: `client/map/journeymap/` (plugin annotation), `client/map/xaero/` + `mixin/xaero/` (attached from `WFLibClient` when `xaeroworldmap` / `xaerominimap` loaded; mixins gated in `WFMixinPlugin`). Mod without an overlay API => `MapPainter` draws shapes through an affine.
- Layers: `WFMaps.publish(layer, dimension, shapes)` replaces the layer; `clear(layer)`; late host => replay. Any thread.
- Tools: `WFMaps.addTool(MapTool)`. JM reads `available()` when it builds its toolbar (map opened) => availability change shows on reopen; Xaero: per frame.

| shape | JourneyMap | Xaero (`MapPainter`) |
| --- | --- | --- |
| `Area` | `PolygonOverlay`; clockwise in x/z, else hole | ear-clipped fill, either winding; no holes |
| `Line` | ribbon polygon per x4 zoom band (2..16384); width px at the band's geometric mean | screen-px polyline |
| `Icon` | `MarkerOverlay`, rotation clockwise from north | canvas sprite, + map rotation (minimap) |

Xaero (World Map 1.47.0, Minimap 26.6.0; no API, internals => recheck on bump):
- World map: one `ElementRenderer` element at the camera, render box past the screen; draws under Xaero's UI. Pose: framebuffer px, origin `getWidth()/2` (integer); undone to GUI px for `MapTool.render`.
- Input: `ScreenEvent` press / drag `Pre` on `GuiMap`; skipped when its dropdown is open or a widget is under the cursor. Consumed press => Xaero never starts a pan. Height: `GuiMap.mouseBlockPosY` (32767 = unknown => player y in menus).
- Menu: `MixinGuiMap` appends to `getRightClickOptions`. Xaero menus flat => `MapMenu.sub` opens a second menu at the cursor (`handleRightClick` invoker). Labels literal (Xaero translates names).
- Buttons: right column above Xaero's zoom-out. Top right = Xaero compass, top left = tool text.
- Title tooltip z 1000: `GuiMap` UI at z 970..1000 with depth; tooltip at vanilla z 400 invisible.
- Minimap: `IN_MINIMAP` element handler (private, built with the minimap framebuffers, rebuilt on reload => re-added on identity change). Affine from its `ps`/`pc`/`zoom`: `X = (ps*dx - pc*dz)*zoom`, `Y = (pc*dx + ps*dz)*zoom`. Non-fullscreen shapes, no labels; widths in framebuffer px. Safe mode => nothing.

- Shipped layers: `wflib:recon` (+ `/reach` hover ring), `wflib:rail`, `wflib:telemetry`; tool `RailMapEditor`. CombinedAssault: `FireMissionMapTool`.
- `MapPointer.surfaceY`: host's map height at the cursor (covers unloaded chunks); move events carry none.

## 89. Telemetry (`telemetry/`)

- `TelemetryReporter.datalink()` on entity classes, tracked join -> leave: `MissileEntity` (null under water), `DroneEntity` (null while classified), CombinedAssault UAV vehicles. Events: `TelemetryLog` (docs/pda.md).
- Every `PERIOD_TICKS` = 10 per level: players carrying a PDA (`Pda.carries`, inventory) get reporters whose operator they are, or whose team = their side (`ReconOwners.owningEntity`) or a WarForge ally of it (`areFactionsAllied`: truce excluded). No range limit.
- `MissileEntity.setGroundTarget()`: target y unknown (map point) => target chunk preloaded, y = surface once loaded. CombinedAssault sets it for rack rounds from `indirect_fire` stations.
- `MarkerOverlay` takes no dimension in its constructor: set it or JourneyMap never draws the icon.
- Operator: `MissileEntity.setOperatorId` at launch (missile item, commands, CombinedAssault launchers); UAV = last pilot.
- Client: trail vertex every 4 blocks, 512 max; unheard > 2 periods + 1 s => grey "link lost", dropped after 30 s. `TelemetryClient.tracks()` = PDA Telemetry app.

