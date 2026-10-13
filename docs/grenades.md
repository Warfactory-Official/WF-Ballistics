# Grenades

Thrown body, warheads, client effects (`grenade/`). Item side (defs, hand state machine) = consumer (MineAssault).

## API
| type | use |
|---|---|
| `api.ChargeSpec` / `api.ChargeItem` | read a carried charge without its owning mod (vehicle barrel insertion) |
| `api.ThermalAttackEvent` | burning thermite near a `PreciseHitbox`; vehicles route heat to armour regions + systems |
| `ThrownCharge.launch` / `cookOff` / `react` / `create` / `debris` / `holdAt` | throw; detonate in hand; reaction without flight (`quiet` = cosmetic); client-only look body; owner-pinned (gun barrel) |

| warhead | |
|---|---|
| `wflib:frag_grenade` | fragment rays (`FragRays`) + small no-block blast |
| `wflib:flashbang` | per-player flash / deafness by distance, view angle, LOS; mobs in LOS dazed |
| `wflib:smoke/<dye>` x16 | growing harmless `wflib:smoke` cloud tinted by dye; blocks seekers + mob sight |
| `wflib:thermite` | reaction: thermite fire, body burns, `ThermalAttackEvent`; no blast, no block damage |
- Smoke / thermite on a carrier other than `ThrownCharge` => reacting charge spawned at the point.

## Rulings
- Shrapnel = rays, never entities. Per victim only rays inside its bounding cone pay a block clip.
- Non-sticky charge settling on an entity attaches anyway => rides vehicles (thermite on a hull roof).
- Flash drawn at `RenderGuiEvent.Pre`: hand whited too, GUI not. Afterimage = frame captured on the flash.
- Hearing loss exempts UI and `Cue.ignoreHearing()` (the ringing) only; alert cues are deafened too.
- Mob sight: `hasLineOfSight` false through >= `SmokeScreens.OPAQUE` blocks of smoke chord.

## Traps
- Gravity before the move: after-move gravity => resting charge alternates grounded / falling, never still.
- Game tests: each on its own floor 26 blocks apart in height; frag / flash reach crosses arenas sharing a height.
