# TV rounds

## 79. Model

- `seeker(CameraSpec)` on preset/builder => TV round; `wflib:tv` = `atgm` airframe, `shaped_charge`, 2.5 b/t, 0.12 rad/t, 600 t motor, `air_launch` -> `direct`, `CameraSpec.TV_SEEKER` (40 deg FOV, x4, 1200 blocks datalink, optical).
- Operator = subscriber of the round's camera feed (`CameraNet`) passing `TvGuidance.entitled`: `controlId`/owner is the player or anything in their ride chain (vehicle seat, remote UAV); datalink `linkAt(distance) > CameraFeed.USABLE_LINK`.
- Gimbal order (`CameraControlPacket` yaw/pitch) = line of sight flown: `velocity = sight * cruiseSpeed`, then the normal turn/thrust limits. Not during `ASCEND` (rail drop runs out first). Operated => designation cleared, never offloaded to sim.
- Let go (unsubscribe, TTL, out of range, operator dead) => `MissileSeeker.release`: ray along the last sight, 512 blocks, cut at the first unloaded chunk. Entity => designated (homes); block => aim point; nothing => 512 on along the line. Flown by the preset's stages from there.
- Connect ad hoc, any time in flight: `TvConnectPacket` -> newest entitled round in range -> `TvLinkPacket(id)`; round removed => `TvLinkPacket(id, lost)` to its subscribers.

## 80. Client

- View = HUD layout `wflib:tv` (`TvHud`, Part XXVI), OSD = `wflib:camera_osd` drawn into each feed FBO (`CameraOsd`).
- Keys: `key.wflib.tv_link` (Y) connect / let go, `key.wflib.tv_view` (M) full view <-> picture-in-picture (top right, 34% width). Scroll = zoom. PDA not carried => keys drained, link dropped; server `TvGuidance.connect` refuses too.
- `MixinMouseHandler` wraps `turnPlayer`'s `LocalPlayer.turn` at priority 1500: outermost over a vehicle mod's turret wrap. Linked => mouse pans `CameraLink`, look untouched.
- Order clamped to 60 deg off the heading the client sees (`TvClient.GIMBAL_LIMIT`): no wind-up past the gimbal.
- Feed pass: lens = tracked entity's frame pose + nose + 0.3 along the sight (`CameraTarget`), not the 5 Hz feed walk. Streamed terrain keeps the round tracked at any range. OSD `TV-xxxx`, motor bar `MTR`; burnt out != blind (`CameraFeed.GUIDED`).
- Stale feed (> `CameraFeed.STALE_TICKS`) or lost => "SIGNAL LOST" card 20 ticks, link dropped.
- Feed entity tracked to its subscribers at any range when the client holds its chunk (`CameraNet.watches` in `MixinChunkMapTrackedEntity`); vanilla stops at the view distance. Stream window centred on entity + velocity * 20 t (capped at half the radius), any entity feed (was drones only).
- Any feed pass: `getMainRenderTarget()` = feed FBO (`MixinMinecraftFeedTarget`). Iris without a pack binds the main target inside `renderLevel`; before this every feed under Iris was blank sky.
