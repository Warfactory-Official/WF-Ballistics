package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** {@link BlockAllocatorStandard} for underwater blasts: fluid neither resists nor is destroyed. */
public class BlockAllocatorWater extends BlockAllocatorStandard {

    public BlockAllocatorWater(int resolution) {
        super(resolution);
    }

    @Override
    protected float blockResistance(ExplosionAEF explosion, Level level, BlockPos pos, BlockState state, float power) {
        if (!state.getFluidState().isEmpty()) return 0F;
        return super.blockResistance(explosion, level, pos, state, power);
    }

    @Override
    protected boolean canDestroy(ExplosionAEF explosion, Level level, BlockPos pos, BlockState state, float power) {
        if (!state.getFluidState().isEmpty()) return false;
        return super.canDestroy(explosion, level, pos, state, power);
    }
}
