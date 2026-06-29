package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A pluggable per-block side effect run by a {@link IBlockProcessor}, used to convert blocks the blast
 * touched into something else (fire, scorched rubble, ...).
 * <p>
 * It exposes two phases because most conversions need to read the world in its <em>original</em> state but
 * write only after the blast has finished clearing blocks:
 * <ul>
 *   <li>{@link #mutatePre} runs while the block still exists (the {@code state} is the pre-blast state).</li>
 *   <li>{@link #mutatePost} runs after the processor has removed blocks, so {@code pos} is now usually air
 *       and the mutator can decide whether to place something there (e.g. only place fire on air with a
 *       solid block beneath).</li>
 * </ul>
 */
public interface IBlockMutator {

    /** Called with the block's pre-blast state, before the block is removed. */
    void mutatePre(ExplosionAEF explosion, BlockState state, BlockPos pos);

    /** Called after blocks have been cleared; {@code pos} is typically air at this point. */
    void mutatePost(ExplosionAEF explosion, BlockPos pos);
}
