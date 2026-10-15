# Backpacks

Subclass `backpack.BackpackItem`: `spec()` (`BackpackSpec`), `model()` (armour-frame glTF, bone `body`; null => not drawn).

## State
- Worn = player attachment `wflib:worn_backpack` (ItemStack, copyOnDeath); no equipment / curio slot.
- Contents on the stack: component `wflib:backpack_contents` (`ItemContainerContents`); `wflib:backpack_locked`.
- Capability `Backpacks.HANDLER` (`wflib:backpack`, `IItemHandler`, players): null unworn; same rules as slots.
- Death without keepInventory => worn pack dropped with contents.
- Sync: owner full stack; trackers contents stripped (`Worn` S2C).

## Rules (`BackpackContainer.canPlace` / `room`)
- No nested backpacks.
- `Limit(matches, max, name)`: <= `max` matching stacks.
- `maxLoadKg > 0`: per-slot stack size capped so contents (wfweight kg, before `contentWeight`) <= cap.

## UI
- Key `key.wflib.backpack` (B): held -> worn; worn -> inventory (full => refused).
- Survival inventory: panel left of `InventoryScreen` (hidden under recipe book, never in creative). Slots = `InventoryMenu` slots `46..109` (mixin), moved by scroll (Slot x/y AT), inactive while hidden.
- Shift-click: pack -> inventory; inventory -> pack first unless armour / creative (creative slot packets stop at 45).
- Lock button => `Action(TOGGLE_LOCK)`.
- Others: probe node on the drawn pack (last render layer pose, <= 2 ticks old) -> `Backpacks.OPEN` -> `BackpackMenu`; locked or > `VIEW_RANGE` (4) => refused / closed.
- Op: `/wflib backpack wear|lock <players>`.

## wfweight (`compat/wfweight/BackpackWeight`, priority 300 > nested 100 / MineAssault 200)
- Unit = `emptyKg + contentWeight * sum(contents)`, nested depth via `WeightViews1211.stackWeight`.
- Worn pack = inventory source.
