package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A pluggable per-block side effect run by a {@link IBlockProcessor}, used to convert blocks the blast touched into
 * something else (fire, scorched rubble, ...).
 */
public interface IBlockMutator {

    /**
     * Called with the block's pre-blast state, before the block is removed.
     */
    void mutatePre(ExplosionAEF explosion, BlockState state, BlockPos pos);

    /**
     * Called after blocks have been cleared; {@code pos} is typically air at this point.
     */
    void mutatePost(ExplosionAEF explosion, BlockPos pos);
}
