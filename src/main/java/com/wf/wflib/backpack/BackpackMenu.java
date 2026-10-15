package com.wf.wflib.backpack;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Another player's worn backpack: slots 0..{@link Backpacks#MAX_SLOTS} = backpack (screen places the visible window),
 * then viewer inventory 27 + hotbar 9. Client copy: plain container (contents from slot sync only).
 */
public class BackpackMenu extends AbstractContainerMenu {
    public static final int INV = Backpacks.MAX_SLOTS;

    @Nullable
    private final Player owner;
    private final ItemStack opened;
    private final int size;
    private final int ownerId;

    public BackpackMenu(int id, Inventory viewer, Player owner, ItemStack opened, int slots) {
        this(id, viewer, Backpacks.container(owner), owner, opened, slots, owner.getId());
    }

    public static BackpackMenu client(int id, Inventory viewer, RegistryFriendlyByteBuf buf) {
        int ownerId = buf.readVarInt();
        int slots = buf.readVarInt();
        return new BackpackMenu(id, viewer, new SimpleContainer(Backpacks.MAX_SLOTS), null, ItemStack.EMPTY, slots,
                ownerId);
    }

    private BackpackMenu(int id, Inventory viewer, Container backpack, @Nullable Player owner, ItemStack opened,
                         int slots, int ownerId) {
        super(Backpacks.MENU.get(), id);
        this.owner = owner;
        this.opened = opened;
        this.size = slots;
        this.ownerId = ownerId;
        for (int i = 0; i < Backpacks.MAX_SLOTS; i++) {
            addSlot(backpack instanceof BackpackContainer bc ? new BackpackSlot(bc, i, false)
                    : new Slot(backpack, i, -10000, -10000) {
                @Override
                public boolean isActive() {
                    return getContainerSlot() < size;
                }
            });
        }
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(viewer, 9 + r * 9 + c, 0, 0));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(viewer, c, 0, 0));
        }
    }

    public int size() {
        return size;
    }

    public int ownerId() {
        return ownerId;
    }

    @Override
    public boolean stillValid(Player viewer) {
        return owner == null || owner.isAlive() && owner.level() == viewer.level()
                && owner.distanceTo(viewer) <= Backpacks.VIEW_RANGE && Backpacks.worn(owner) == opened
                && !Backpacks.locked(opened);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack before = stack.copy();
        boolean moved = index < INV ? moveItemStackTo(stack, INV, INV + 36, true)
                : moveItemStackTo(stack, 0, size, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return before;
    }
}
