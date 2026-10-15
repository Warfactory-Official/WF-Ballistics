package com.wf.wflib.mixin.gt;

import com.wf.wflib.compat.gt.EMPLockable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Overrides {@code acceptEnergyFromNetwork} => base-class lock inject never runs. */
@Mixin(targets = "com.gregtechceu.gtceu.common.machine.electric.BatteryBufferMachine$EnergyBatteryTrait", remap = false)
public abstract class MixinEnergyBatteryTrait {

    @Inject(method = "acceptEnergyFromNetwork", at = @At("HEAD"), cancellable = true)
    private void wflib$blockCharge(CallbackInfoReturnable<Long> cir) {
        if (((EMPLockable) this).wflib$empLocked()) {
            cir.setReturnValue(0L);
        }
    }
}
