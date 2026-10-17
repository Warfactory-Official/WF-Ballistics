# Night vision

Image-intensifier goggles (`com.wf.wflib.nvg`). Pack runs True Darkness Biomes => moonless night / caves lightmap 0.

## Wearing

| | |
|---|---|
| items | `nvg_gen1`, `nvg_pvs7`, `nvg_pvs14`, `nvg_pvs31`, `nvg_gpnvg18` (`NvgRegistry.GOGGLES`) |
| standalone | head slot (`NvgItem` = zero-protection `ArmorItem`: else `CustomHeadLayer` draws an item model), drawn on `models/nvg/harness.glb` |
| mount | any click with goggle on cursor onto an `ArmorPreset.nvgMount()` helmet => `wflib:nvg_mount` (full stack), flipped up |
| dismount | lone helmet in a crafting grid => goggle out, helmet stays (`wflib:nvg_dismount`) |
| state | `wflib:nvg_state {down, ir, gain}` on the goggle stack (mounted: inside the helmet's component) |
| keys | flip `L`, illuminator `` ` ``, gain `=` / `-` |
| flip | server `NvgServer`: `down` toggles at tick `SWAP_TICK` 6 of `FLIP_TICKS` 12; client predicts the hand (`NvgClient.reach`) |

- Write state only via `NvgWorn.set` (copies the mounted stack). Worn-stack write => `LivingEquipmentChangeEvent`, same item.
- Model: helmet/harness `socket_nvg` node; goggle glTF `pivot` node, rest = down, flip = `NvgItemClient.UP_ANGLE` about X. Armour glTF face = -Z. Placeholders: MineAssault `tools/ma.py nvg-placeholder`.
- Hand: `NvgHand` (wflib arms, left hand to `NvgHand.BROW`); a viewmodel animating it itself registers `NvgHand.claim` (MineAssault: gun held).
- MineAssault: night vision down => PiP sight refuses ADS unless `sight.nvgCompatible`.

## View

`NvgView` at `RenderGuiEvent.Pre` (after the hand, before the GUI), first person only:
`scene + IrTarget -> flux (tube res, mips) -> ABC (1x1, eased) -> tube image (history) -> composite`.
Outside the tubes: naked-eye scene (no IR light) + housing shadow.

IR light: `Lights.irView(NvgClient::irView)`: viewing => `SPLIT` (GemRender `IrTarget`), IR feed / `ScreenSensor` IR => `MERGED`, else `OFF`.
Night sky: `Lights.nightSky(NvgClient::nightSky)` = darkness x rain x (stars 0.004 + moon 0.03 x phase), MERGED x25 (8-bit colour); sky-lit surfaces only.
Illuminators (`NvgIllumination`): every player with goggle down + `ir` (brow spot); IR feed pass / IR `ScreenSensor` camera (wide spot). Remote vehicle sights: CombinedAssault syncs, calls `NvgIllumination.sight`.

### Optics JSON

`assets/<ns>/nvg/<item path>.json`, client reload.

| key | meaning |
|---|---|
| `tubes` | 1-4 `{x, y, r}`, screen-height units from screen centre |
| `resolution` | photocathode image height / screen height |
| `gain_max` | gain at `gain` 1; applied gain = `gain_max * gain^2 * warm-up` |
| `snr` | shot noise `~ sqrt(y + ebi) * 2.5 / snr` |
| `ebi` | dark noise floor + scintillation rate |
| `halo` | glow round bright sources; `autogate` false => extra bloom |
| `abc_rate` | brightness control easing, 1/s; target mean 0.45 |
| `distortion`, `vignette` | edge warp `1 + d*r^2`, falloff `1 - v*r^4` |
| `honeycomb` | MCP hex pattern strength |
| `phosphor` | rgb (P43 green, P45 white) |
| `persistence` | `max(now, prev * p)` |
| `spectral` | linear r, g, b, IR response |

## Sensor modes (`CameraMode`)

| mode | used by | picture |
|---|---|---|
| `INTENSIFIER` | MineAssault sight `nvg`, CombinedAssault `with_night_vision` | linear gain 250 x `Exposure`, green |
| `IR` | RECON / SECURITY cameras, drones, CombinedAssault `with_ir` | mono NIR (foliage bright, sky dark), IR lights merged, own illuminator, sees through smoke |
| `THERMAL` | | + `wf_thermal_lag`: ghost 0.55, column/row FPN, NETD, NUC freeze 6 ticks / 900 |

Thermal + IR passes hide particles, gas volumes, in-gas fog (`FeedPass.seesThroughSmoke`).
IR + intensifier passes: `IrView.MERGED` (IR lights + night sky into colour); chain uniform `Exposure` = 1 night -> day
factor (intensifier 0.02, IR 0.12) by client sky brightness (`CameraTarget.exposure`).
