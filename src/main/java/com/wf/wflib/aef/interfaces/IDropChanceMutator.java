package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Overrides the per-block item drop probability used by {@link
 * com.wf.wflib.aef.standard.BlockProcessorStandard}.
 *
 * @see com.wf.wflib.aef.standard.DropChanceMutatorStandard a constant-chance implementation
 */
public interface IDropChanceMutator {

    /**
     * @param chance the chance the processor would otherwise use
     * @return the chance to use, in {@code [0, 1]}
     */
    float mutateDropChance(ExplosionAEF explosion, BlockState state, int x, int y, int z, float chance);
}
