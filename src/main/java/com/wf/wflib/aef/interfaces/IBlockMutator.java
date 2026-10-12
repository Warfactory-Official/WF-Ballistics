package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface IBlockMutator {

    /** Pre-blast state; block not yet removed. */
    void mutatePre(ExplosionAEF explosion, BlockState state, BlockPos pos);

    /** After clearing; {@code pos} typically air. */
    void mutatePost(ExplosionAEF explosion, BlockPos pos);
}
