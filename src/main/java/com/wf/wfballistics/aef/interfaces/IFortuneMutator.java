package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Supplies a Fortune level applied to a destroyed block's drops, allowing "enriching" charges that yield
 * bonus ore. Called once per destroyed block by {@link com.wf.wfballistics.aef.standard.BlockProcessorStandard}.
 */
public interface IFortuneMutator {

    int mutateFortune(ExplosionAEF explosion, BlockState state, int x, int y, int z);
}
