package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IBlockProcessor;
import com.wf.wfballistics.compat.GregtechCompat;
import com.wf.wfballistics.fx.EMPStunManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;

import java.util.Set;

public class BlockProcessorEMP implements IBlockProcessor {

    private static final Direction[] SIDES = Direction.values();

    private final int chargeLockTicks;
    private final boolean drainEnergy;
    private final boolean pauseWork;
    private final boolean breakMaintenance;

    public BlockProcessorEMP(int chargeLockSeconds, boolean drainEnergy, boolean pauseWork, boolean breakMaintenance) {
        this.chargeLockTicks = Math.max(0, chargeLockSeconds) * 20;
        this.drainEnergy = drainEnergy;
        this.pauseWork = pauseWork;
        this.breakMaintenance = breakMaintenance;
    }

    private static void drainForgeEnergy(BlockEntity be) {
        if (extract(be, null)) {
            return;
        }
        for (Direction side : SIDES) {
            extract(be, side);
        }
    }

    private static boolean extract(BlockEntity be, Direction side) {
        IEnergyStorage energy = be.getCapability(ForgeCapabilities.ENERGY, side).resolve().orElse(null);
        if (energy != null && energy.canExtract()) {
            int stored = energy.getEnergyStored();
            if (stored > 0) {
                energy.extractEnergy(stored, false);
                return true;
            }
        }
        return false;
    }

    @Override
    public void process(ExplosionAEF explosion, Level level, double x, double y, double z, Set<BlockPos> affectedBlocks) {
        boolean gregtech = GregtechCompat.isActive();

        for (BlockPos pos : affectedBlocks) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                continue;
            }

            if (drainEnergy) {
                drainForgeEnergy(be);
            }

            if (gregtech) {
                boolean stunned = GregtechCompat.applyEMP(level, pos, chargeLockTicks, drainEnergy, pauseWork,
                        breakMaintenance);
                if (stunned && chargeLockTicks > 0 && level instanceof ServerLevel serverLevel) {
                    EMPStunManager.stun(serverLevel, pos, chargeLockTicks);
                }
            }
        }

        affectedBlocks.clear();
    }
}
