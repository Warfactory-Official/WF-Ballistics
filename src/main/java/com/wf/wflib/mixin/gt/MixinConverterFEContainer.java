package com.wf.wflib.mixin.gt;

import com.gregtechceu.gtceu.common.machine.trait.ConverterTrait;
import com.wf.wflib.compat.gt.EMPLockable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@code receiveEnergy} reports FE taken before {@code addEnergy} => refuse here or the sender loses it. */
@Mixin(value = ConverterTrait.FEContainer.class, remap = false)
public abstract class MixinConverterFEContainer {

    @Shadow
    @Final
    ConverterTrait this$0;

    @Inject(method = "receiveEnergy", at = @At("HEAD"), cancellable = true)
    private void wflib$blockReceive(int maxReceive, boolean simulate, CallbackInfoReturnable<Integer> cir) {
        if (((EMPLockable) this.this$0).wflib$empLocked()) {
            cir.setReturnValue(0);
        }
    }
}
