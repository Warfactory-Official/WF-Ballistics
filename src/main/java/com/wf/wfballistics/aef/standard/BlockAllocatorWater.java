package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@link BlockAllocatorStandard} variant for underwater detonations: liquids neither absorb the blast nor
 * get added to the destruction set. Without this, a depth charge would either fizzle against the water
 * column or try to "destroy" flowing water and leave odd gaps.
 */
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
