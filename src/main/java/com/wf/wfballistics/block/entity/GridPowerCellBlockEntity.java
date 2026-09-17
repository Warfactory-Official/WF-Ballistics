package com.wf.wfballistics.block.entity;

import com.wf.wfballistics.block.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** A creative power source, so the grid can be built and tested without a generator. */
public class GridPowerCellBlockEntity extends BlockEntity {

    /** FE per face per tick. Comfortably more than the heaviest probe draws, so a cell can feed a cluster. */
    private static final int OUTPUT = 512;

    public GridPowerCellBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GRID_POWER_CELL.get(), pos, state);
    }

    public void serverTick() {
        if (!(this.level instanceof ServerLevel sl)) {
            return;
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = this.worldPosition.relative(dir);
            IEnergyStorage sink = sl.getCapability(Capabilities.EnergyStorage.BLOCK, neighbour, dir.getOpposite());
            if (sink != null && sink.canReceive()) {
                sink.receiveEnergy(OUTPUT, false);
            }
        }
    }
}
