package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Overrides the per-block item drop probability used by {@link
 * com.wf.wfballistics.aef.standard.BlockProcessorStandard}.
 *
 * @see com.wf.wfballistics.aef.standard.DropChanceMutatorStandard a constant-chance implementation
 */
public interface IDropChanceMutator {

    /**
     * @param chance the chance the processor would otherwise use
     * @return the chance to use, in {@code [0, 1]}
     */
    float mutateDropChance(ExplosionAEF explosion, BlockState state, int x, int y, int z, float chance);
}
