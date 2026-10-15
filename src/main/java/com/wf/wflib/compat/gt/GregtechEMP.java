package com.wf.wflib.compat.gt;

import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.IControllable;
import com.gregtechceu.gtceu.api.capability.IElectricItem;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.common.machine.electric.BatteryBufferMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.MaintenanceHatchPartMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

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
                // Buffer getEnergyStored sums its batteries; changeEnergy zeroes only the internal field.
                if (MetaMachine.getMachine(level, pos) instanceof BatteryBufferMachine buffer) {
                    drainBatteries(buffer.getBatteryInventory());
                }
            }
            if (lockTicks > 0 && energy instanceof EMPLockable lockable) {
                lockable.wflib$empLock(lockTicks);
            }
        }

        if (pauseWork) {
            IControllable controllable = GTCapabilityHelper.getControllable(level, pos, null);
            if (controllable != null) {
                controllable.setWorkingEnabled(false);
            }
        }

        if (breakMaintenance) {
            if (MetaMachine.getMachine(level, pos) instanceof MaintenanceHatchPartMachine maintenance) {
                maintenance.setMaintenanceProblems((byte) 0);
                maintenance.setTaped(false);
            }
        }

        return hasEnergy;
    }

    private static void drainBatteries(IItemHandlerModifiable inventory) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }
            IElectricItem electric = GTCapabilityHelper.getElectricItem(stack);
            IEnergyStorage fe = electric == null ? GTCapabilityHelper.getForgeEnergyItem(stack) : null;
            if (electric != null) {
                electric.discharge(Long.MAX_VALUE, Integer.MAX_VALUE, true, false, false);
            } else if (fe != null) {
                fe.extractEnergy(Integer.MAX_VALUE, false);
            } else {
                continue;
            }
            inventory.setStackInSlot(i, stack);
        }
    }
}
