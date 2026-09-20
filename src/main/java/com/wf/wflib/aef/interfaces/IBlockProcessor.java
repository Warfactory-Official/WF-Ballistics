package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Stage 3 of the explosion pipeline: applies an effect to every position chosen by the {@link IBlockAllocator}
 * (drop items, remove blocks, convert to fire/rubble, ...).
 */
public interface IBlockProcessor {

    /**
     * @param affectedBlocks the positions produced by the allocator; this method owns them and may mutate
     *      the set in place
     */
    void process(ExplosionAEF explosion, Level level, double x, double y, double z, Set<BlockPos> affectedBlocks);
}
