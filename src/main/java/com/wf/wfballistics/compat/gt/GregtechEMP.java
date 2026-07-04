package com.wf.wfballistics.compat.gt;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IControllable;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMaintenanceMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class GregtechEMP {

    private GregtechEMP() {
    }

    public static boolean apply(Level level, BlockPos pos, int lockTicks, boolean drain, boolean pauseWork,
                                boolean breakMaintenance) {
        boolean hasEnergy = false;

        IEnergyContainer energy = GTCapabilityHelper.getEnergyContainer(level, pos, null);
        if (energy != null) {
            hasEnergy = true;
            if (drain) {
                long stored = energy.getEnergyStored();
                if (stored > 0L) {
                    energy.changeEnergy(-stored);
                }
            }
            if (lockTicks > 0 && energy instanceof EMPLockable lockable) {
                lockable.wfballistics$empLock(lockTicks);
            }
        }

        if (pauseWork) {
            IControllable controllable = GTCapabilityHelper.getControllable(level, pos, null);
            if (controllable != null) {
                controllable.setWorkingEnabled(false);
            }
        }

        if (breakMaintenance) {
            IMaintenanceMachine maintenance = GTCapabilityHelper.getMaintenanceMachine(level, pos, null);
            if (maintenance != null) {
                maintenance.setMaintenanceProblems((byte) 0);
                maintenance.setTaped(false);
            }
        }

        return hasEnergy;
    }
}
