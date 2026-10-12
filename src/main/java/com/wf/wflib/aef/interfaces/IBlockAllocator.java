package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

public interface IBlockAllocator {

    /** {@code size}: vanilla explosion-power units. Never null; caller owns + may mutate the returned set. */
    Set<BlockPos> allocate(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
