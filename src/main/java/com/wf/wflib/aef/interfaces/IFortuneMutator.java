package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Supplies a Fortune level applied to a destroyed block's drops, allowing "enriching" charges that yield bonus ore.
 */
public interface IFortuneMutator {

    int mutateFortune(ExplosionAEF explosion, BlockState state, int x, int y, int z);
}
