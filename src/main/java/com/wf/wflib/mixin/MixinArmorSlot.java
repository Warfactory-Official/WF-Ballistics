package com.wf.wflib.mixin;

import com.wf.wflib.armor.ArmorStacks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suit-occupied armour slots refuse placement ({@link ArmorStacks#blocked}); {@code canEquip} sees only the item. */
@Mixin(targets = "net.minecraft.world.inventory.ArmorSlot")
public abstract class MixinArmorSlot {
    @Shadow
    @Final
    private LivingEntity owner;
    @Shadow
    @Final
    private EquipmentSlot slot;

    @Inject(method = "mayPlace", at = @At("RETURN"), cancellable = true)
    private void wflib$suit(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && ArmorStacks.blocked(owner, slot, stack)) {
            cir.setReturnValue(false);
        }
    }
}
