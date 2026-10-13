package com.wf.wflib.mixin;

import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.fx.BlastCorpses;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Skips vanilla's per-tick equipment diff for bare glyphids; holds a blast-thrown corpse's death removal; smoke hides. */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity {

    @Inject(method = "detectEquipmentUpdates", at = @At("HEAD"), cancellable = true)
    private void wflib$skipBareEquipmentScan(CallbackInfo ci) {
        if ((Object) this instanceof EntityGlyphid glyphid && glyphid.equipmentEmpty()) {
            ci.cancel();
        }
    }

    @Inject(method = "tickDeath", at = @At("HEAD"), cancellable = true)
    private void wflib$holdThrownCorpse(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.level().isClientSide && BlastCorpses.holdDeath(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "hasLineOfSight", at = @At("RETURN"), cancellable = true)
    private void wflib$smokeHides(net.minecraft.world.entity.Entity target, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (cir.getReturnValueZ() && !self.level().isClientSide
                && com.wf.wflib.grenade.SmokeScreens.blocked(self.level(), self.getEyePosition(), target.getEyePosition())) {
            cir.setReturnValue(false);
        }
    }
}
