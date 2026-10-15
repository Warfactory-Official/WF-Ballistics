package com.wf.wflib.mixin;

import com.wf.wflib.backpack.BackpackContainer;
import com.wf.wflib.backpack.BackpackSlot;
import com.wf.wflib.backpack.Backpacks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Worn backpack slots after the vanilla 46 ({@link Backpacks#FIRST_SLOT}). Shift-click: backpack -> inventory;
 * inventory -> backpack first unless armour / creative (creative slot packets stop at 45).
 */
@Mixin(InventoryMenu.class)
public abstract class MixinInventoryMenu extends AbstractContainerMenu {
    @Shadow
    @Final
    private Player owner;

    protected MixinInventoryMenu(MenuType<?> type, int id) {
        super(type, id);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void wflib$backpackSlots(Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
        BackpackContainer backpack = Backpacks.container(owner);
        for (int i = 0; i < Backpacks.MAX_SLOTS; i++) {
            addSlot(new BackpackSlot(backpack, i, true));
        }
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
    private void wflib$backpackQuickMove(Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
        Slot slot = slots.get(index);
        if (!slot.hasItem() || player.isCreative()) {
            return;
        }
        ItemStack stack = slot.getItem();
        int size = Backpacks.container(owner).slots();
        boolean fromPack = index >= Backpacks.FIRST_SLOT;
        if (!fromPack && (size == 0 || index < 9 || index >= 45
                || player.getEquipmentSlotForItem(stack).getType() == EquipmentSlot.Type.HUMANOID_ARMOR)) {
            return;
        }
        ItemStack before = stack.copy();
        boolean moved = fromPack ? moveItemStackTo(stack, 9, 45, false)
                : moveItemStackTo(stack, Backpacks.FIRST_SLOT, Backpacks.FIRST_SLOT + size, false);
        if (!moved) {
            if (fromPack) {
                cir.setReturnValue(ItemStack.EMPTY);
            }
            return;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY, before);
        } else {
            slot.setChanged();
        }
        slot.onTake(player, stack);
        cir.setReturnValue(before);
    }
}
