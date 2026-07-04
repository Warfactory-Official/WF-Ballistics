package com.wf.wfballistics.mixin.gt;

import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.wf.wfballistics.compat.gt.EMPLockable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NotifiableEnergyContainer.class, remap = false)
public abstract class MixinNotifiableEnergyContainer implements EMPLockable {

    @Unique
    private int wfballistics$empLockTicks;

    @Override
    public void wfballistics$empLock(int ticks) {
        if (ticks > this.wfballistics$empLockTicks) {
            this.wfballistics$empLockTicks = ticks;
        }
    }

    @Inject(method = "acceptEnergyFromNetwork", at = @At("HEAD"), cancellable = true, remap = false)
    private void wfballistics$blockCharge(CallbackInfoReturnable<Long> cir) {
        if (this.wfballistics$empLockTicks > 0) {
            cir.setReturnValue(0L);
        }
    }

    @Inject(method = "updateTick", at = @At("TAIL"), remap = false)
    private void wfballistics$tickLock(CallbackInfo ci) {
        if (this.wfballistics$empLockTicks > 0) {
            this.wfballistics$empLockTicks--;
        }
    }
}
