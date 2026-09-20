package com.wf.wflib.mixin.gt;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.wf.wflib.compat.gt.EMPLockable;
import com.wf.wflib.fx.EMPStunManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NotifiableEnergyContainer.class, remap = false)
public abstract class MixinNotifiableEnergyContainer implements EMPLockable {

    @Unique
    private static final String WFLIB_EMP_LOCK_KEY = "WFLibEmpLock";

    @Unique
    private int wflib$empLockTicks;

    @Override
    public void wflib$empLock(int ticks) {
        if (ticks > this.wflib$empLockTicks) {
            this.wflib$empLockTicks = ticks;
        }
    }

    @Inject(method = "acceptEnergyFromNetwork", at = @At("HEAD"), cancellable = true, remap = false)
    private void wflib$blockCharge(CallbackInfoReturnable<Long> cir) {
        if (this.wflib$empLockTicks > 0) {
            cir.setReturnValue(0L);
        }
    }

    @Inject(method = "updateTick", at = @At("TAIL"), remap = false)
    private void wflib$tickLock(CallbackInfo ci) {
        if (this.wflib$empLockTicks > 0) {
            this.wflib$empLockTicks--;
        }
    }

    @Inject(method = "onMachineLoad", at = @At("TAIL"), remap = false)
    private void wflib$resumeStun(CallbackInfo ci) {
        this.wflib$emitStunFx();
    }

    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        if (this.wflib$empLockTicks > 0) {
            tag.putInt(WFLIB_EMP_LOCK_KEY, this.wflib$empLockTicks);
        }
    }

    public void loadCustomPersistedData(CompoundTag tag) {
        if (tag.contains(WFLIB_EMP_LOCK_KEY)) {
            this.wflib$empLockTicks = tag.getInt(WFLIB_EMP_LOCK_KEY);
            this.wflib$emitStunFx();
        }
    }

    @Unique
    private void wflib$emitStunFx() {
        if (this.wflib$empLockTicks <= 0) {
            return;
        }
        MetaMachine machine = ((NotifiableEnergyContainer) (Object) this).getMachine();
        if (machine != null && machine.getLevel() instanceof ServerLevel serverLevel) {
            EMPStunManager.stun(serverLevel, machine.getPos(), this.wflib$empLockTicks);
        }
    }
}
