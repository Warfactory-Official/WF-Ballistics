package com.wf.wflib.mixin.gt;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableEnergyContainer;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.wf.wflib.compat.gt.EMPLockable;
import com.wf.wflib.fx.EMPStunManager;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NotifiableEnergyContainer.class, remap = false)
public abstract class MixinNotifiableEnergyContainer implements EMPLockable {

    @Unique
    @SaveField(nbtKey = "WFLibEmpLock")
    private int wflib$empLockTicks;

    @Override
    public void wflib$empLock(int ticks) {
        if (ticks > this.wflib$empLockTicks) {
            this.wflib$empLockTicks = ticks;
        }
    }

    @Override
    public boolean wflib$empLocked() {
        return this.wflib$empLockTicks > 0;
    }

    @Inject(method = "acceptEnergyFromNetwork", at = @At("HEAD"), cancellable = true)
    private void wflib$blockCharge(CallbackInfoReturnable<Long> cir) {
        if (this.wflib$empLockTicks > 0) {
            cir.setReturnValue(0L);
        }
    }

    /** Locked => no transfer; {@code simulate=true} keeps {@code BatterySlotTrait} subscribed for resume. */
    @Inject(method = "dischargeOrRechargeEnergyContainers", at = @At("HEAD"), cancellable = true)
    private void wflib$blockBatterySlot(IItemHandlerModifiable itemHandler, int slotIndex, boolean simulate,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (this.wflib$empLockTicks > 0) {
            cir.setReturnValue(simulate);
        }
    }

    /**
     * Catch-all for remaining gains (generator recipe output, multiblock hatch fills).
     * FIXME: PowerSubstationMachine ignores the return => debanked EU into a locked dynamo hatch is lost.
     */
    @Inject(method = "changeEnergy", at = @At("HEAD"), cancellable = true)
    private void wflib$blockGain(long energyToAdd, CallbackInfoReturnable<Long> cir) {
        if (energyToAdd > 0L && this.wflib$empLockTicks > 0) {
            cir.setReturnValue(0L);
        }
    }

    @Inject(method = "updateTick", at = @At("TAIL"))
    private void wflib$tickLock(CallbackInfo ci) {
        if (this.wflib$empLockTicks > 0) {
            this.wflib$empLockTicks--;
        }
    }

    @Inject(method = "onMachineLoad", at = @At("TAIL"))
    private void wflib$resumeStun(CallbackInfo ci) {
        if (this.wflib$empLockTicks <= 0) {
            return;
        }
        MetaMachine machine = ((NotifiableEnergyContainer) (Object) this).getMachine();
        if (machine.getLevel() instanceof ServerLevel serverLevel) {
            EMPStunManager.stun(serverLevel, machine.getBlockPos(), this.wflib$empLockTicks);
        }
    }
}
