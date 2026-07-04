package com.wf.wfballistics.mixin.gt;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableEnergyContainer;
import com.wf.wfballistics.compat.gt.EMPLockable;
import com.wf.wfballistics.fx.EMPStunManager;
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
    private static final String WFBALLISTICS_EMP_LOCK_KEY = "WFBallisticsEmpLock";

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

    @Inject(method = "onMachineLoad", at = @At("TAIL"), remap = false)
    private void wfballistics$resumeStun(CallbackInfo ci) {
        this.wfballistics$emitStunFx();
    }

    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        if (this.wfballistics$empLockTicks > 0) {
            tag.putInt(WFBALLISTICS_EMP_LOCK_KEY, this.wfballistics$empLockTicks);
        }
    }

    public void loadCustomPersistedData(CompoundTag tag) {
        if (tag.contains(WFBALLISTICS_EMP_LOCK_KEY)) {
            this.wfballistics$empLockTicks = tag.getInt(WFBALLISTICS_EMP_LOCK_KEY);
            this.wfballistics$emitStunFx();
        }
    }

    @Unique
    private void wfballistics$emitStunFx() {
        if (this.wfballistics$empLockTicks <= 0) {
            return;
        }
        MetaMachine machine = ((NotifiableEnergyContainer) (Object) this).getMachine();
        if (machine != null && machine.getLevel() instanceof ServerLevel serverLevel) {
            EMPStunManager.stun(serverLevel, machine.getPos(), this.wfballistics$empLockTicks);
        }
    }
}
