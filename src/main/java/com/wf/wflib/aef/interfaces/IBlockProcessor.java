package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

public interface IBlockProcessor {

    /** Owns {@code affectedBlocks}; may mutate the set in place. */
    void process(ExplosionAEF explosion, Level level, double x, double y, double z, Set<BlockPos> affectedBlocks);
}
