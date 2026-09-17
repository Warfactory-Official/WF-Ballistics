package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Stage 1 of the explosion pipeline: decides <em>which</em> block positions the blast reaches.
 *
 * @see com.wf.wfballistics.aef.standard.BlockAllocatorStandard the vanilla-equivalent spherical ray-march
 */
public interface IBlockAllocator {

    /**
     * @param explosion the explosion being resolved; use {@link ExplosionAEF#exploder} and
     *      {@link ExplosionAEF#compat} for resistance / destroy checks
     * @param size the explosion radius (same meaning as a vanilla explosion's power)
     * @return the positions the blast can reach. May be empty, never {@code null}. The returned set is
     *      owned by the caller and may be mutated by later pipeline stages.
     */
    Set<BlockPos> allocate(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
