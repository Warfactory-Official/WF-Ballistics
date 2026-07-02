package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IBlockMutator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Leaves scattered fires in the blast's wake. For each cleared position it places vanilla fire (1-in-3
 * chance) when the spot is empty and sits on a face that can actually hold fire, so the result looks like
 * patchy burning rather than a solid sheet of flame.
 *
 * <p>Pair with a {@link BlockProcessorStandard} via {@code withBlockEffect(new BlockMutatorFire())}.
 */
public class BlockMutatorFire implements IBlockMutator {

    @Override
    public void mutatePre(ExplosionAEF explosion, BlockState state, BlockPos pos) {
    }

    @Override
    public void mutatePost(ExplosionAEF explosion, BlockPos pos) {
        Level level = explosion.level;
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);

        if (level.getBlockState(pos).isAir()
                && belowState.isFaceSturdy(level, below, Direction.UP)
                && level.random.nextInt(3) == 0) {
            level.setBlockAndUpdate(pos, Blocks.FIRE.defaultBlockState());
        }
    }
}
