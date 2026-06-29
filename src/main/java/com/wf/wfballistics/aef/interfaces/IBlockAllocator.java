package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * Stage 1 of the explosion pipeline: decides <em>which</em> block positions the blast reaches.
 * <p>
 * Allocators only collect positions; they never modify the world. The collected set is handed to the
 * {@link IBlockProcessor} which decides what actually happens to each block. Splitting "what is hit"
 * from "what happens to it" is what lets the framework reuse the same ray-marching for a destructive
 * blast, a fire-starting blast, a non-destructive shockwave, etc.
 *
 * @see com.wf.wfballistics.aef.standard.BlockAllocatorStandard the vanilla-equivalent spherical ray-march
 */
public interface IBlockAllocator {

    /**
     * @param explosion the explosion being resolved; use {@link ExplosionAEF#exploder} and
     *                  {@link ExplosionAEF#compat} for resistance / destroy checks
     * @param size      the explosion radius (same meaning as a vanilla explosion's power)
     * @return the positions the blast can reach. May be empty, never {@code null}. The returned set is
     *         owned by the caller and may be mutated by later pipeline stages.
     */
    Set<BlockPos> allocate(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
